package com.icehome.up.telon;

import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Transformation;
import android.view.animation.TranslateAnimation;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Telón al cambiar de pantalla: en las transiciones del sistema (WMShell, en SystemUI) en las que
 * la ventana de encima se desliza en horizontal (entrar en una pantalla de una app, volver
 * atrás…), esa ventana se inclina (paralelogramo de bordes rectos; o, con el ajuste de forma,
 * se dobla como un telón): arriba fija y la parte de abajo quedándose atrás, más cuanto más
 * rápido va, con un muelle duro que la endereza. La de debajo se desenfoca a la
 * vez que HyperOS la oscurece (al entrar) o se enfoca mientras se aclara (al volver): se captura
 * también, se desenfoca una vez en la GPU y se dibuja en la misma capa, debajo de la que se mueve,
 * con su matriz y su oscurecido; la real se oculta a la vez que la de encima (todo con el mismo
 * retraso, sin desfases entre las dos).
 *
 * Cómo:
 * <ul>
 * <li>{@code DefaultTransitionHandler.startAnimation}: se apunta la ventana de encima (la primera
 *     de la transición), su raíz y su tamaño.</li>
 * <li>{@code DefaultAnimationAdapter.applyTransformation}, cada fotograma: con la matriz y la
 *     transparencia que HyperOS le pone se calcula la velocidad y el doblez, y se dibuja en una
 *     capa propia (OpenGL, {@link TelonRenderer}) con una captura de la ventana tomada al empezar.
 *     En cuanto se ve la nuestra, la ventana real se oculta en la misma transacción de HyperOS.</li>
 * <li>Último fotograma: la ventana real vuelve y la capa se va en esa misma transacción (sin
 *     parpadeo); antes, el doblez se lleva a cero para que no haya salto.</li>
 * </ul>
 * Cualquier fallo deja la animación de HyperOS tal cual.
 */
public final class Telon {

    static volatile float strength = 1f, maxFps = 60f, captureScale = 0.5f, motionBlur = 0.6f, belowBlur = 1f;
    /** Oscurecido máximo de HyperOS en la de debajo (para pasar de oscurecido a desenfoque 0..1). */
    private static final float DIM_FULL = 0.5f;
    /** Entrada suave del desenfoque al aparecer nuestra capa (ns; al volver empieza desenfocada). */
    private static final long BELOW_IN_NS = 80_000_000L;

    /** Doblez por velocidad (px de la fila de abajo por px/s) y máximo (fracción del ancho). */
    private static final float GAIN = 0.036f, MAX = 0.26f;
    /**
     * Motion blur: largo de la estela = lo que avanza la ventana en este tiempo (s) por el ajuste,
     * con un tope (fracción del ancho). Quieta, nada.
     */
    private static final float BLUR_TIME = 0.022f, BLUR_MAX = 0.06f;
    /** Muelle del doblez: duro, con un poco de balanceo. */
    private static final float K = 380f, ZETA = 0.55f;
    /** Tramo final (fracción de la animación) en el que el doblez se lleva a cero. */
    private static final float SETTLE_FROM = 0.72f;
    /** Tras dibujar, un refresco para que el compositor ya tenga nuestra imagen. */
    private static final long READY_NS = 10_000_000L;
    private static final long WATCHDOG_MS = 300;

    private static Field fLeash, fTransaction, fAnim, fTransformation, fRadius, fDimAlpha, fDimLayer;
    /** TransitionUtil.rootIndexFor(change, info): la raíz de la pantalla de una ventana. */
    private static java.lang.reflect.Method rootIndexFor;
    /** Motivos ya anotados en el registro (cada uno una vez, para no llenarlo). */
    private static final java.util.Set<String> SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void skip(String why) {
        if (SEEN.add(why)) Log.i("sin telón: " + why);
    }
    private static Handler renderThread, captureThread;
    private static volatile Session session;

    public static void install(ClassLoader cl) {
        Class<?> adapter = XposedHelpers.findClass("com.android.wm.shell.transition.DefaultSurfaceAnimator$DefaultAnimationAdapter", cl);
        fLeash = field(adapter, "mLeash");
        fTransaction = field(adapter, "mTransaction");
        fAnim = field(adapter, "mAnim");
        fTransformation = field(adapter, "mTransformation");
        fRadius = field(adapter, "mCornerRadius");
        try {
            Class<?> tu = XposedHelpers.findClass("com.android.wm.shell.shared.TransitionUtil", cl);
            for (java.lang.reflect.Method m : tu.getDeclaredMethods()) {
                if (m.getName().equals("rootIndexFor") && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    rootIndexFor = m;
                }
            }
        } catch (Throwable t) {
            Log.w("sin TransitionUtil.rootIndexFor: se usa la primera raíz");
        }
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.wm.shell.transition.DefaultTransitionHandler", cl),
                "startAnimation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length >= 4) onStart(p.args[1], (SurfaceControl.Transaction) p.args[3]);
                        } catch (Throwable t) {
                            Log.e("inicio de transición", t);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(adapter, "applyTransformation", long.class, ValueAnimator.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    onFrame(p.thisObject, (ValueAnimator) p.args[1]);
                } catch (Throwable t) {
                    Log.e("fotograma", t);
                    Session s = session;
                    if (s != null) end(s, "error");
                }
            }
        });
        Log.i("telón activo");
    }

    // ------------------------------------------------------------------ transición

    /** La ventana de encima de una transición: si es de pantalla completa, se le pone telón. */
    private static void onStart(Object info, SurfaceControl.Transaction finishT) {
        if (strength <= 0f || !Hidden.ok()) return;
        List<?> changes = (List<?>) XposedHelpers.callMethod(info, "getChanges");
        if (changes == null || changes.isEmpty()) return;
        Object top = changes.get(0);
        int mode = (Integer) XposedHelpers.callMethod(top, "getMode");
        if (mode < 1 || mode > 4) {                             // abrir, cerrar, al frente, al fondo
            skip("tipo de cambio " + mode);
            return;
        }
        boolean closing = mode == 2 || mode == 4;
        Rect b = (Rect) XposedHelpers.callMethod(top, closing ? "getStartAbsBounds" : "getEndAbsBounds");
        SurfaceControl leash = (SurfaceControl) XposedHelpers.callMethod(top, "getLeash");
        if (b == null || b.isEmpty() || leash == null || !leash.isValid()) {
            skip("ventana sin tamaño o sin superficie");
            return;
        }
        android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
        int dw = Math.min(dm.widthPixels, dm.heightPixels), dh = Math.max(dm.widthPixels, dm.heightPixels);
        if (b.width() < dw * 0.8f || b.height() < dh * 0.8f || b.width() > b.height()) {   // solo en vertical
            skip("ventana que no ocupa la pantalla en vertical (" + b.width() + "×" + b.height() + ")");
            return;
        }
        int ri = 0;
        if (rootIndexFor != null) {
            try {
                ri = (Integer) rootIndexFor.invoke(null, top, info);
            } catch (Throwable ignored) {
            }
        }
        Object root = XposedHelpers.callMethod(info, "getRoot", Math.max(0, ri));
        SurfaceControl rootLeash = root == null ? null : (SurfaceControl) XposedHelpers.callMethod(root, "getLeash");
        if (rootLeash == null || !rootLeash.isValid()) {
            skip("transición sin raíz");
            return;
        }
        Session old = session;
        if (old != null) end(old, "otra transición");
        // Por si algo falla: al terminar, la ventana siempre visible.
        finishT.setAlpha(leash, 1f);
        final Session s = new Session(leash, rootLeash, b.width(), b.height(), closing);
        if (belowBlur > 0f && changes.size() >= 2) below(s, changes.get(1), info, ri, dw, dh, finishT);
        session = s;
        renderHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (session == s && !s.checked) {
                    skip("la ventana de encima no pasó por el animador por defecto de HyperOS");
                    session = null;
                }
            }
        }, 250);
    }

    /** La de debajo (segunda de la transición), si también ocupa la pantalla en la misma raíz. */
    private static void below(Session s, Object change, Object info, int topRoot, int dw, int dh, SurfaceControl.Transaction finishT) {
        try {
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (mode < 1 || mode > 4) return;
            boolean closing = mode == 2 || mode == 4;
            Rect b = (Rect) XposedHelpers.callMethod(change, closing ? "getStartAbsBounds" : "getEndAbsBounds");
            SurfaceControl leash = (SurfaceControl) XposedHelpers.callMethod(change, "getLeash");
            if (b == null || leash == null || !leash.isValid() || b.width() < dw * 0.8f || b.height() < dh * 0.8f) {
                skip("sin desenfoque: la de debajo no ocupa la pantalla");
                return;
            }
            if (rootIndexFor != null) {
                int ri = (Integer) rootIndexFor.invoke(null, change, info);
                if (ri != topRoot) {
                    skip("sin desenfoque: la de debajo está en otra pantalla");
                    return;
                }
            }
            finishT.setAlpha(leash, 1f);                        // por si algo falla
            s.belowLeash = leash;
            s.bw = b.width();
            s.bh = b.height();
        } catch (Throwable t) {
            skip("sin desenfoque: " + t);
        }
    }

    /** Un fotograma de HyperOS para una ventana (se llama para cada ventana de la transición). */
    private static void onFrame(Object adapter, ValueAnimator va) throws Throwable {
        Session s = session;
        if (s == null || s.done) return;
        SurfaceControl leash = (SurfaceControl) fLeash.get(adapter);
        if (leash != null && leash == s.belowLeash) {
            s.postBelow(adapter, va.getAnimatedFraction());
            // HyperOS le vuelve a poner su transparencia en cada fotograma: mientras se ve la
            // copia, oculta (en su misma transacción).
            if (s.belowHidden && !s.done) ((SurfaceControl.Transaction) fTransaction.get(adapter)).setAlpha(leash, 0f);
            return;
        }
        if (leash != s.leash) return;
        Animation anim = (Animation) fAnim.get(adapter);
        if (!s.checked) {
            s.checked = true;
            if (!slides(anim)) {                                // sin deslizamiento: nada que doblar
                skip("animación sin deslizamiento (" + anim.getClass().getSimpleName() + ")");
                session = null;
                return;
            }
            captureHandler().post(s.captureTask);
            renderHandler().post(s.beginTask);
        }
        s.lastFrameMs = SystemClock.uptimeMillis();
        Transformation tr = (Transformation) fTransformation.get(adapter);
        Matrix tm = tr.getMatrix();
        float alpha = tr.getAlpha();
        float radius = roundedCorners(anim) ? Math.max(0f, fRadius.getFloat(adapter)) : 0f;
        float fraction = va.getAnimatedFraction();
        s.step(tm, fraction);
        s.post(tm, alpha, radius);
        SurfaceControl.Transaction t = (SurfaceControl.Transaction) fTransaction.get(adapter);
        SurfaceControl layer = TelonRenderer.layer;
        if (fraction >= 1f) {
            // Último: la ventana real vuelve y la capa se va, en la misma transacción.
            t.setAlpha(leash, alpha);
            if (s.belowHidden && s.belowLeash.isValid()) t.setAlpha(s.belowLeash, 1f);
            if (layer != null && layer.isValid()) t.setVisibility(layer, false);
            s.done = true;
            if (session == s) session = null;
            s.log("fin");
            return;
        }
        if (s.ready() && layer != null && layer.isValid()) {
            t.setAlpha(leash, 0f);
            t.setAlpha(layer, 1f);
            t.setVisibility(layer, true);
            Hidden.setRelativeLayer(t, layer, leash, 1);
            if (s.belowDrawn && !s.belowHidden && System.nanoTime() - s.belowDrawnNs > READY_NS
                    && s.belowLeash != null && s.belowLeash.isValid()) {
                t.setAlpha(s.belowLeash, 0f);
                s.belowHidden = true;
            }
            if (s.shownFrame == 0) {
                s.shownFrame = s.frames;
                s.shownNs = System.nanoTime();
            }
        }
    }

    /** Animation.hasRoundedCorners() es oculta: por reflexión (si no está, sin esquinas). */
    private static java.lang.reflect.Method rounded;
    private static boolean roundedResolved;

    private static boolean roundedCorners(Animation a) {
        try {
            if (!roundedResolved) {
                roundedResolved = true;
                rounded = Animation.class.getMethod("hasRoundedCorners");
            }
            return rounded != null && (Boolean) rounded.invoke(a);
        } catch (Throwable t) {
            return false;
        }
    }

    /** El DefaultTransitionDimmer de HyperOS dentro de la animación (o null). */
    private static Object findDimmer(Animation a) {
        if (a == null) return null;
        if (a.getClass().getName().endsWith("DefaultTransitionDimmer")) {
            if (fDimAlpha == null) {
                try {
                    fDimAlpha = field(a.getClass(), "mCurrentAlpha");
                    fDimLayer = field(a.getClass(), "mDimLayer");
                } catch (Throwable t) {
                    return null;
                }
            }
            return a;
        }
        if (a instanceof AnimationSet) {
            for (Animation c : ((AnimationSet) a).getAnimations()) {
                Object d = findDimmer(c);
                if (d != null) return d;
            }
        }
        return null;
    }

    /** La animación desliza la ventana (en algún nivel hay una traslación). */
    private static boolean slides(Animation a) {
        if (a instanceof TranslateAnimation || a.getClass().getName().contains("Translate")) return true;
        if (a instanceof AnimationSet) {
            for (Animation c : ((AnimationSet) a).getAnimations()) {
                if (slides(c)) return true;
            }
        }
        return false;
    }

    /** Termina una sesión fuera de los fotogramas de HyperOS (cancelada, sin fotogramas…). */
    private static void end(Session s, String why) {
        if (s.done) return;
        s.done = true;
        if (session == s) session = null;
        try {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            if (s.leash.isValid()) t.setAlpha(s.leash, 1f);
            SurfaceControl bl = s.belowLeash;
            if (s.belowHidden && bl != null && bl.isValid()) t.setAlpha(bl, 1f);
            SurfaceControl l = TelonRenderer.layer;
            if (l != null && l.isValid()) t.setVisibility(l, false);
            t.apply();
        } catch (Throwable ignored) {
        }
        s.log(why);
    }

    // ------------------------------------------------------------------ sesión

    private static final class Session {
        final SurfaceControl leash, root;
        final float w, h;
        /** La de debajo (o null) y su tamaño. */
        volatile SurfaceControl belowLeash;
        float bw, bh;
        /** El oscurecedor de HyperOS de la de debajo (o null) y su capa de color. */
        private volatile Object dimmer;
        private boolean dimmerLooked;
        /** Su captura ya está desenfocada / ya salió en pantalla / la real está oculta / no se pudo. */
        volatile boolean hasBelow, belowDrawn, belowHidden, belowFailed;
        private volatile long shownNs, belowDrawnNs;
        final boolean closing;
        volatile boolean checked, done, begun, hasContent;
        volatile long lastFrameMs;
        final long startMs = SystemClock.uptimeMillis();
        int frames, shownFrame;
        float maxBend;
        long captureMs, belowCaptureMs;

        // Física (hilo de HyperOS).
        private float bend, bv, vx, lastTx;
        private long lastNs;
        private boolean init;

        // Estado para dibujar (bajo lock).
        private final Object lock = new Object();
        private final Matrix pending = new Matrix(), drawM = new Matrix();
        private float pAlpha = 1f, pRadius, pBend, pBlur;
        private final Matrix belowPending = new Matrix(), belowM = new Matrix();
        private boolean belowSeen;
        private float pDim, pAmount;
        private final AtomicBoolean renderQueued = new AtomicBoolean();
        private long lastRenderNs;
        private volatile long firstSwapNs;
        private volatile int drawn;

        Session(SurfaceControl leash, SurfaceControl root, float w, float h, boolean closing) {
            this.leash = leash;
            this.root = root;
            this.w = w;
            this.h = h;
            this.closing = closing;
        }

        /** Velocidad horizontal y doblez (muelle duro hacia lo que pide la velocidad). */
        void step(Matrix m, float fraction) {
            float[] v = new float[9];
            m.getValues(v);
            float tx = v[Matrix.MTRANS_X];
            long now = System.nanoTime();
            frames++;
            if (!init) {
                init = true;
                lastTx = tx;
                lastNs = now;
                return;
            }
            float dt = Math.min(0.05f, Math.max(0f, (now - lastNs) / 1e9f));
            lastNs = now;
            if (dt <= 0f) return;
            vx += ((tx - lastTx) / dt - vx) * 0.6f;
            lastTx = tx;
            float max = w * MAX;
            // Hacia la izquierda (entra desde la derecha): la parte de abajo se queda a la derecha.
            float target = max * (float) Math.tanh(-vx * GAIN * strength / max);
            float c = 2f * ZETA * (float) Math.sqrt(K);
            for (float left = dt; left > 0f; left -= 0.004f) {
                float hh = Math.min(0.004f, left);
                bv += (K * (target - bend) - c * bv) * hh;
                bend += bv * hh;
            }
            float settle = 1f - smooth((fraction - SETTLE_FROM) / (1f - SETTLE_FROM));
            float b = bend * settle;
            maxBend = Math.max(maxBend, Math.abs(b));
            float blur = Math.min(w * BLUR_MAX, Math.abs(vx) * BLUR_TIME * motionBlur) * settle;
            synchronized (lock) {
                pBend = b;
                pBlur = blur;
            }
        }

        void post(Matrix m, float alpha, float radius) {
            synchronized (lock) {
                pending.set(m);
                pAlpha = alpha;
                pRadius = radius;
            }
            if (renderQueued.compareAndSet(false, true)) renderHandler().post(renderTask);
            Handler h = renderHandler();
            h.removeCallbacks(watchdog);
            h.postDelayed(watchdog, WATCHDOG_MS);
        }

        /** Un fotograma de HyperOS de la de debajo: su matriz, el oscurecido y cuánto desenfocar. */
        void postBelow(Object adapter, float fraction) throws Throwable {
            Animation anim = (Animation) fAnim.get(adapter);
            if (!dimmerLooked) {
                dimmerLooked = true;
                dimmer = findDimmer(anim);
            }
            float dim = -1f;
            Object d = dimmer;
            if (d != null && fDimAlpha != null) {
                try {
                    dim = fDimAlpha.getFloat(d);
                } catch (Throwable ignored) {
                }
            }
            float amount;
            if (dim >= 0f) {
                amount = Math.min(1f, dim / DIM_FULL);
                amount = 1f - (1f - amount) * (1f - amount);       // el desenfoque llega antes que el oscurecido
            } else {                                            // sin oscurecedor: según el avance
                dim = 0f;
                amount = smooth(closing ? 1f - fraction : fraction);
            }
            Transformation tr = (Transformation) fTransformation.get(adapter);
            synchronized (lock) {
                belowPending.set(tr.getMatrix());
                belowSeen = true;
                pDim = dim;
                pAmount = amount * Math.min(1f, belowBlur);
            }
        }

        /** La capa de color con la que HyperOS oscurece la de debajo (para no capturarla). */
        SurfaceControl dimLayer() {
            Object d = dimmer;
            if (d == null || fDimLayer == null) return null;
            try {
                SurfaceControl l = (SurfaceControl) fDimLayer.get(d);
                return l != null && l.isValid() ? l : null;
            } catch (Throwable t) {
                return null;
            }
        }

        boolean ready() {
            return drawn > 0 && System.nanoTime() - firstSwapNs > READY_NS;
        }

        final Runnable beginTask = new Runnable() {
            @Override
            public void run() {
                if (done) return;
                if (!TelonRenderer.ensure(root, Math.round(w), Math.round(h))) {
                    end(Session.this, "sin OpenGL");
                    return;
                }
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                TelonRenderer.attach(t, root);
                t.setVisibility(TelonRenderer.layer, false);
                t.apply();
                begun = true;
            }
        };

        final Runnable captureTask = new Runnable() {
            @Override
            public void run() {
                if (done || !leash.isValid()) return;
                long t0 = SystemClock.uptimeMillis();
                Hidden.Shot shot = Hidden.capture(leash, new Rect(0, 0, Math.round(w), Math.round(h)),
                        Math.max(0.3f, Math.min(1f, captureScale)), null);
                captureMs = SystemClock.uptimeMillis() - t0;
                if (shot == null) {
                    end(Session.this, "sin captura");
                    return;
                }
                // La de debajo, a la misma resolución (al principio o al final se ve nítida), sin el
                // oscurecido de HyperOS ni la de encima por si cuelga de ella. Las dos se dibujan
                // juntas desde el primer fotograma.
                SurfaceControl bl = belowLeash;
                if (bl != null && !done && bl.isValid()) {
                    long t1 = SystemClock.uptimeMillis();
                    SurfaceControl dl = dimLayer();
                    Hidden.Shot b = Hidden.capture(bl, new Rect(0, 0, Math.round(bw), Math.round(bh)),
                            Math.max(0.3f, Math.min(1f, captureScale)),
                            dl == null ? new SurfaceControl[]{leash} : new SurfaceControl[]{dl, leash});
                    belowCaptureMs = SystemClock.uptimeMillis() - t1;
                    if (b != null && TelonRenderer.queue(TelonRenderer.BELOW, b.buffer, b.colorSpace, Session.this)) {
                        b.buffer.close();
                    } else {
                        if (b != null) b.buffer.close();
                        skip("sin desenfoque: no se pudo capturar la de debajo");
                        belowFailed = true;
                    }
                }
                TelonRenderer.queue(TelonRenderer.TOP, shot.buffer, shot.colorSpace, Session.this);
                shot.buffer.close();
                if (renderQueued.compareAndSet(false, true)) renderHandler().post(renderTask);
            }
        };

        final Runnable renderTask = new Runnable() {
            @Override
            public void run() {
                renderQueued.set(false);
                if (done || !begun) return;
                Object owner = TelonRenderer.consume(TelonRenderer.TOP);
                if (owner == Session.this) hasContent = true;
                if (TelonRenderer.consume(TelonRenderer.BELOW) == Session.this) {
                    if (TelonRenderer.prepareBelow(0.5f + belowBlur)) hasBelow = true;
                    else belowFailed = true;
                }
                if (!hasContent) return;
                if (belowLeash != null && !belowFailed && !hasBelow) return;   // esperando a la de debajo
                long now = System.nanoTime();
                if (drawn > 0 && now - lastRenderNs < (long) (1e9 / Math.max(15f, maxFps)) - 2_500_000L) return;
                lastRenderNs = now;
                float a, r, b, bl, dim, amount;
                boolean below;
                synchronized (lock) {
                    drawM.set(pending);
                    a = pAlpha;
                    r = pRadius;
                    b = pBend;
                    bl = pBlur;
                    below = hasBelow && belowSeen;
                    belowM.set(belowPending);
                    dim = pDim;
                    amount = pAmount;
                }
                long sn = shownNs;
                // Al aparecer la capa se sustituye la real (nítida y oscurecida): el desenfoque entra suave.
                amount *= sn == 0 ? 0f : smooth((now - sn) / (float) BELOW_IN_NS);
                boolean ok = TelonRenderer.begin();
                if (ok && below) ok = TelonRenderer.drawBelow(belowM, 1f, bw, bh, r, dim, amount);
                if (ok) ok = TelonRenderer.drawTop(drawM, a, w, h, r, b, bl);
                if (ok) ok = TelonRenderer.end();
                if (!ok) {
                    end(Session.this, "error al dibujar");
                    return;
                }
                if (below && !belowDrawn) {
                    belowDrawnNs = System.nanoTime();
                    belowDrawn = true;
                }
                if (drawn == 0) firstSwapNs = System.nanoTime();
                drawn++;
            }
        };

        final Runnable watchdog = new Runnable() {
            @Override
            public void run() {
                if (!done && SystemClock.uptimeMillis() - lastFrameMs >= WATCHDOG_MS) end(Session.this, "sin fotogramas");
            }
        };

        void log(String why) {
            Log.i(String.format(java.util.Locale.ROOT,
                    "telón %s: %d fotogramas en %d ms, visible desde el fotograma %d, doblez máx. %.0f px, captura %d ms, debajo %s (%d ms, oscurecedor %s); fin: %s",
                    closing ? "al volver" : "al entrar", frames, SystemClock.uptimeMillis() - startMs,
                    shownFrame, maxBend, captureMs, hasBelow ? "desenfocada" : (belowLeash == null ? "sin tocar" : "sin captura"),
                    belowCaptureMs, dimmer != null ? "sí" : "no", why));
        }
    }

    private static float smooth(float v) {
        v = v < 0f ? 0f : (v > 1f ? 1f : v);
        return v * v * (3f - 2f * v);
    }

    private static synchronized Handler renderHandler() {
        if (renderThread == null) renderThread = start("Telon-render", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        return renderThread;
    }

    private static synchronized Handler captureHandler() {
        if (captureThread == null) captureThread = start("Telon-capture", Process.THREAD_PRIORITY_DISPLAY);
        return captureThread;
    }

    /** Hilos que pueden usar todas las CPU (ver com.icehome.up.Threads). */
    private static Handler start(String name, int prio) {
        Handler h = com.icehome.up.Threads.start(name, prio);
        Log.i("hilo " + com.icehome.up.Threads.describe(h));
        return h;
    }

    private static Field field(Class<?> c, String name) {
        Field f = XposedHelpers.findField(c, name);
        f.setAccessible(true);
        return f;
    }

    private Telon() {
    }
}
