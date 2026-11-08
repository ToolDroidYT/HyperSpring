package com.icehome.up;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Redimensionado de carpetas como en HyperOS 4, para el launcher de Kashi V7.5.
 *
 * Mientras arrastras el tirador (ResizeHandle$Overlay):
 *  - El dedo se mide SIEMPRE respecto a la carpeta del inicio del gesto (no a la que haya
 *    en cada momento) y se proyecta sobre los estados válidos: tamaños de Kashi (1x1, 2x1,
 *    1x2, 2x2, 4x2) que caben en la rejilla y en celdas libres.
 *  - Se elige el estado válido más cercano CON HISTÉRESIS: en la frontera entre dos tamaños
 *    no alterna (antes vibraba sin parar).
 *  - En cuanto cambia, se pide a Kashi que lo aplique ya (sin su retardo), de uno en uno.
 *  - El contenedor persigue ese tamaño con un muelle (rigidez 400, ζ 0,8) y un pequeño tirón
 *    elástico hacia el dedo; nunca va más allá en ninguna dirección.
 *  - Los iconos NO se regeneran: durante todo el gesto los dibuja una capa persistente
 *    (IconLayer) con los iconos de la carpeta; en cada cambio de diseño cada icono vuela a su
 *    nuevo sitio, los que entran aparecen con zoom y rebote y los que sobran se encogen. Los
 *    reales (que Kashi recrea y recarga) quedan ocultos debajo hasta el final.
 *    Nunca se recorta ningún icono.
 * Al soltar, el contenedor se asienta en el tamaño final con el mismo muelle.
 */
final class FolderMorph {

    // ---------------------------------------------------------------- ajustes
    /** Muelle universal: rigidez 400, amortiguación 0,8 (periodo = 2π/√400 ≈ 0,314 s). */
    static final float SPRING_RESPONSE = (float) (2 * Math.PI / Math.sqrt(400.0)), SPRING_DAMPING = 0.8f;
    /** Tirón elástico del contenedor hacia el dedo (máximo, en celdas). */
    static final float PULL_L_CELLS = 0.18f;
    /** Histéresis: otro tamaño debe estar esta distancia (celdas) más cerca para cambiar. */
    static final float HYSTERESIS_CELLS = 0.22f;
    /** Tiempo mínimo entre dos cambios de tamaño pedidos a Kashi. */
    static final long APPLY_MIN_INTERVAL_MS = 140;
    /** Margen de seguridad de los iconos dentro de la tarjeta (fracción del icono). */
    static final float SAFE_PADDING = 0.04f;
    /** Iconos de la capa: movimiento y aparición (amortiguación baja = zoom con rebote). */
    static final float ICON_MOVE_RESPONSE = 0.26f, ICON_MOVE_DAMPING = 0.84f;
    static final float ICON_POP_RESPONSE = 0.32f, ICON_POP_DAMPING = 0.55f;
    static final long SWAP_WAIT_MAX_MS = 600;   // espera a que los iconos reales estén cargados
    static final float MIN_SIZE_DP = 40f;
    static final long WATCH_TIMEOUT_MS = 1200;
    static final long RELEASE_WAIT_MS = 900;

    // ------------------------------------------------------------ reflexión
    private static Field fItem, fGhost, fResizing, fInfo, fCells, fScale, fWantX, fWantY, fReadyAt, fLastApply;
    private static Field fOverlayStatic;
    private static Method mTarget, mIconBounds, mSizes, mApply;

    // ---------------------------------------------------------------- estado
    private static final Map<Object, Runnable> WATCH = new WeakHashMap<>();
    /** Iconos reales ocultos (la capa los dibuja por ellos). */
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<>();
    private static final Map<View, float[]> LIVE = new WeakHashMap<>();      // contra-escala de iconos
    private static final Map<View, RectF> LIVE_D = new WeakHashMap<>();      // rectángulo dibujado
    private static final Map<View, boolean[]> LIVE_SAVED = new WeakHashMap<>();
    private static Session session;
    private static boolean wasResizing;
    private static long lastEvtTime = -1;
    private static int lastEvtAction = -1;

    /** Un arrastre del tirador. */
    static final class Session {
        Object overlay;
        View view;                       // carpeta real actual (cambia con cada tamaño)
        float grabX, grabY;              // del dedo a la esquina inferior derecha
        float left, top;                 // esquina fija
        float box0W, box0H;              // caja de la carpeta al empezar…
        int sw0, sh0;                    // …y su tamaño en celdas (referencia estable)
        int[] want;                      // estado válido elegido
        long lastApplyAt;
        final RectF d = new RectF();     // rectángulo dibujado
        final RectF target = new RectF();
        boolean releasing;
        ValueAnimator settle;
        Follow follow;
        IconLayer layer;
    }

    // --------------------------------------------------------------- instalar

    static void install(ClassLoader cl) {
        try {
            installDrag(cl);
        } catch (Throwable t) {
            XposedBridge.log("IceHome: error instalando el redimensionado de carpetas");
            XposedBridge.log(t);
        }

        XC_MethodHook capture = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    arm(p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        int hooked = 0;
        Class<?> conv = XposedHelpers.findClassIfExists(
                "com.miui.home.launcher.convertsize.FolderIconConvertSizeController", cl);
        Class<?> bl = XposedHelpers.findClassIfExists("com.miui.home.launcher.BaseLauncher", cl);
        try {
            if (conv != null) {
                for (Method m : conv.getDeclaredMethods()) {
                    if (m.getName().equals("convertFolderSize") && m.getParameterTypes().length == 2) {
                        XposedBridge.hookMethod(m, capture);
                        hooked++;
                    }
                }
            }
            if (bl != null) {
                for (Method m : bl.getDeclaredMethods()) {
                    if (m.getName().equals("bindFolderResize") && m.getParameterTypes().length == 2) {
                        XposedBridge.hookMethod(m, capture);
                        hooked++;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        if (hooked == 0) XposedBridge.log("IceHome: no se encuentra convertFolderSize, sin animación de carpetas");

        // ART puede incrustar métodos pequeños y saltarse los hooks: desoptimizamos los llamadores.
        try {
            if (conv != null) Deopt.all(conv);
            if (bl != null) {
                for (Method m : bl.getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.equals("bindFolderResize") || n.startsWith("lambda$bindFolderResize$")
                            || (n.startsWith("$r8$lambda$") && m.getParameterTypes().length == 5)) {
                        Deopt.method(m);
                    }
                }
            }
            Deopt.all(XposedHelpers.findClassIfExists("com.kx.home.ResizeHandle", cl));
            Deopt.all(XposedHelpers.findClassIfExists("com.kx.home.ResizeHandle$Overlay", cl));
            Deopt.all(XposedHelpers.findClassIfExists("com.miui.home.folder.KxFolderGrid", cl));
        } catch (Throwable t) {
            XposedBridge.log("IceHome: no se pudo desoptimizar (no es grave)");
        }
    }

    private static void installDrag(ClassLoader cl) throws Throwable {
        Class<?> ov = XposedHelpers.findClassIfExists("com.kx.home.ResizeHandle$Overlay", cl);
        Class<?> rh = XposedHelpers.findClassIfExists("com.kx.home.ResizeHandle", cl);
        Class<?> kx = XposedHelpers.findClassIfExists("com.kx.home.Kx", cl);
        if (ov == null || rh == null || kx == null) {
            XposedBridge.log("IceHome: no se encuentra ResizeHandle de Kashi");
            return;
        }
        fItem = StackSwipe.field(ov, "item");
        fGhost = StackSwipe.field(ov, "ghost");
        fResizing = StackSwipe.field(ov, "resizing");
        fInfo = StackSwipe.field(ov, "info");
        fCells = StackSwipe.field(ov, "cells");
        fScale = StackSwipe.field(ov, "scale");
        fWantX = StackSwipe.field(ov, "wantX");
        fWantY = StackSwipe.field(ov, "wantY");
        fReadyAt = StackSwipe.field(ov, "readyAt");
        fLastApply = StackSwipe.field(ov, "lastApply");
        mTarget = StackSwipe.method(ov, "target");
        mSizes = StackSwipe.method(ov, "sizes");
        mApply = StackSwipe.method(ov, "apply", boolean.class);
        mIconBounds = StackSwipe.method(kx, "iconBounds", View.class);
        fOverlayStatic = StackSwipe.field(rh, "sOverlay");

        // Kashi aplica el tamaño con wantX/wantY: siempre el estado válido que elegimos.
        XposedHelpers.findAndHookMethod(ov, "apply", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Session s = session;
                if (s != null && s.overlay == p.thisObject && s.want != null) setWant(p.thisObject, s.want);
            }
        });
        // Toques: los del overlay de Kashi y, de respaldo, todos los de la ventana del launcher.
        XposedHelpers.findAndHookMethod(ov, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                wasResizing = isResizing(p.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                handle(p.thisObject, (MotionEvent) p.args[0]);
            }
        });
        Class<?> bl = XposedHelpers.findClassIfExists("com.miui.home.launcher.BaseLauncher", cl);
        for (Class<?> c = bl; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        wasResizing = isResizing(currentOverlay());
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object o = currentOverlay();
                        if (o != null) handle(o, (MotionEvent) p.args[0]);
                    }
                });
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }
        // Cada fotograma: sin el "cuadrado" de Kashi y con su tirador en la esquina dibujada.
        XposedHelpers.findAndHookMethod(ov, "onPreDraw", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (isResizing(p.thisObject)) ((RectF) fGhost.get(p.thisObject)).setEmpty();
                    Session s = session;
                    if (s != null && s.overlay == p.thisObject) ((RectF) fItem.get(p.thisObject)).set(s.d);
                } catch (Throwable ignored) {
                }
            }
        });
        XposedHelpers.findAndHookMethod(ov, "close", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Session s = session;
                if (s != null && s.overlay == p.thisObject && !s.releasing) release();
            }
        });
        XposedBridge.log("IceHome: carpetas HyperOS 4 (v12) activas");
    }

    private static Object currentOverlay() {
        try {
            return fOverlayStatic.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isResizing(Object overlay) {
        try {
            return overlay != null && fResizing.getBoolean(overlay);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- arrastre

    private static void handle(Object overlay, MotionEvent e) {
        try {
            int a = e.getActionMasked();
            if (e.getEventTime() == lastEvtTime && (a == lastEvtAction || a == MotionEvent.ACTION_MOVE)) return;
            boolean resizing = isResizing(overlay);
            if (!wasResizing && !resizing) return;
            lastEvtTime = e.getEventTime();
            lastEvtAction = a;
            if (a == MotionEvent.ACTION_MOVE && resizing) {
                drag(overlay, e.getRawX(), e.getRawY());
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                Session s = session;
                if (s != null && s.overlay == overlay && !s.releasing) release();
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void drag(Object overlay, float x, float y) throws Throwable {
        View t = (View) mTarget.invoke(overlay);
        if (t == null) return;
        Session s = session;
        if (s == null || s.overlay != overlay || s.releasing) {
            if (s != null && s.settle != null) s.settle.cancel();
            if (s != null && s.follow != null) s.follow.stop();
            s = new Session();
            s.overlay = overlay;
            RectF b = naturalBox(t);
            s.left = b.left;
            s.top = b.top;
            s.box0W = b.width();
            s.box0H = b.height();
            s.grabX = b.right - x;
            s.grabY = b.bottom - y;
            int[] sp = spans(overlay);
            s.sw0 = sp != null ? sp[0] : 1;
            s.sh0 = sp != null ? sp[1] : 1;
            s.d.set(b);
            s.target.set(b);
            s.view = t;
            session = s;
            applyLive(t, s.d);
            s.layer = IconLayer.fromView(t, contentsOfOverlay(overlay));
            hideIcons(t);
            s.follow = new Follow(s);
        }
        s.view = t;

        float cx = x + s.grabX, cy = y + s.grabY;   // esquina que pide el dedo
        float right = cx, bottom = cy;
        Grid g = grid(overlay, t);
        if (g != null) {
            // Celdas que pide el dedo, medidas contra la carpeta del inicio (referencia estable).
            float fw = s.sw0 + (cx - (s.left + s.box0W)) / g.px;
            float fh = s.sh0 + (cy - (s.top + s.box0H)) / g.py;
            float[] pr = project(fw, fh, g.valid);
            int[] cand = nearest(pr[0], pr[1], g.valid);
            if (s.want == null || !has(g.valid, s.want[0], s.want[1])) {
                s.want = cand;
            } else if (cand[0] != s.want[0] || cand[1] != s.want[1]) {
                float dc = (float) Math.sqrt(dist2(pr[0], pr[1], cand[0], cand[1]));
                float dw = (float) Math.sqrt(dist2(pr[0], pr[1], s.want[0], s.want[1]));
                if (dw - dc > HYSTERESIS_CELLS) s.want = cand;     // histéresis: sin alternar
            }
            setWant(overlay, s.want);

            // Contenedor: caja del estado elegido + tirón elástico hacia el dedo.
            float wr = s.left + s.box0W + (s.want[0] - s.sw0) * g.px;
            float wb = s.top + s.box0H + (s.want[1] - s.sh0) * g.py;
            float ex = cx - wr, ey = cy - wb, L = PULL_L_CELLS * Math.min(g.px, g.py);
            float m = (float) Math.hypot(ex, ey);
            float k = m > 1e-3f ? L * (1f - (float) Math.exp(-m / L)) / m : 0f;
            right = wr + ex * k;
            bottom = wb + ey * k;

            // Un cambio de tamaño cada vez, y solo cuando el anterior ya ha terminado.
            long now = SystemClock.uptimeMillis();
            if ((s.want[0] != g.sw || s.want[1] != g.sh) && WATCH.isEmpty()
                    && now - s.lastApplyAt > APPLY_MIN_INTERVAL_MS) {
                s.lastApplyAt = now;
                applyNow(overlay);
            }
        }
        float min = StackSwipe.dp(t, MIN_SIZE_DP);
        s.target.set(s.left, s.top, Math.max(s.left + min, right), Math.max(s.top + min, bottom));
        ((RectF) fGhost.get(overlay)).setEmpty();
    }

    /** Pide a Kashi que aplique ya el tamaño (sin su retardo de 260–600 ms). */
    private static void applyNow(Object overlay) {
        try {
            fReadyAt.setLong(overlay, 0L);
            fLastApply.setLong(overlay, 0L);
            mApply.invoke(overlay, false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** El contenedor persigue su objetivo con el muelle universal, fotograma a fotograma. */
    static final class Follow implements Choreographer.FrameCallback {
        final Session s;
        final float[] l = new float[2], t = new float[2], r = new float[2], b = new float[2];
        long last;
        boolean stopped;

        Follow(Session s) {
            this.s = s;
            l[0] = s.d.left;
            t[0] = s.d.top;
            r[0] = s.d.right;
            b[0] = s.d.bottom;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            stopped = true;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override
        public void doFrame(long nanos) {
            if (stopped) return;
            float dt = last == 0 ? 1f / 120f : Math.min(1f / 30f, (nanos - last) / 1e9f);
            last = nanos;
            StackSwipe.springStep(l, s.target.left, SPRING_RESPONSE, SPRING_DAMPING, dt);
            StackSwipe.springStep(t, s.target.top, SPRING_RESPONSE, SPRING_DAMPING, dt);
            StackSwipe.springStep(r, s.target.right, SPRING_RESPONSE, SPRING_DAMPING, dt);
            StackSwipe.springStep(b, s.target.bottom, SPRING_RESPONSE, SPRING_DAMPING, dt);
            s.d.set(l[0], t[0], r[0], b[0]);
            View v = s.view;
            if (v != null && v.isAttachedToWindow()) {
                applyLive(v, s.d);
                if (s.layer != null) s.layer.retarget(v);
            }
            try {
                ((RectF) fItem.get(s.overlay)).set(s.d);
                ((View) s.overlay).invalidate();
            } catch (Throwable ignored) {
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private static void release() {
        final Session s = session;
        if (s == null) return;
        s.releasing = true;
        if (s.follow != null) s.follow.stop();
        final View target = s.view;
        if (!WATCH.isEmpty()) {
            // Hay un cambio de tamaño en camino: begin() hará el asentamiento al llegar.
            if (target != null) {
                target.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (session == s && s.settle == null) settle(s.view, s);
                    }
                }, RELEASE_WAIT_MS);
            }
            return;
        }
        if (target != null) settle(target, s);
        else session = null;
    }

    /** Del rectángulo dibujado al tamaño real, con el muelle universal; luego, iconos reales. */
    private static void settle(final View v, final Session s) {
        if (v == null || !v.isAttachedToWindow()) {
            if (v != null) {
                clearLive(v);
                showIcons(v);
            }
            if (s.layer != null) s.layer.remove();
            if (session == s) session = null;
            return;
        }
        final RectF from = new RectF(s.d), to = naturalBox(v);
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        Spring.apply(va, 0f, 1f, 0f, SPRING_RESPONSE, SPRING_DAMPING);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                lerpRect(s.d, from, to, (Float) a.getAnimatedValue());
                applyLive(v, s.d);
                if (s.layer != null) s.layer.retarget(v);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                clearLive(v);
                finishWithLayer(v, s.layer, new Runnable() {
                    @Override
                    public void run() {
                        if (session == s) session = null;
                    }
                });
            }
        });
        s.settle = va;
        va.start();
    }

    /**
     * Cuando los iconos de la capa se han asentado en su sitio final y los reales ya están
     * cargados, se cambian unos por otros en el mismo punto (sin que se note).
     */
    private static void finishWithLayer(final View v, final IconLayer layer, final Runnable then) {
        if (layer == null) {
            showIcons(v);
            if (then != null) then.run();
            return;
        }
        layer.retarget(v);
        final long deadline = SystemClock.uptimeMillis() + SWAP_WAIT_MAX_MS;
        layer.whenSettled(new Runnable() {
            @Override
            public void run() {
                if (!realIconsReady(v) && SystemClock.uptimeMillis() < deadline) {
                    v.postDelayed(this, 32);
                    return;
                }
                showIcons(v);
                layer.remove();
                if (then != null) then.run();
            }
        });
    }

    private static boolean realIconsReady(View v) {
        for (View ic : icons(v)) {
            if (ic instanceof ImageView && ((ImageView) ic).getDrawable() == null) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ zero-clip

    /**
     * Dibuja la carpeta ocupando D (pantalla), anclada arriba a la izquierda, sin recortar
     * nada: el contenido se escala y cada icono recibe una escala efectiva uniforme
     * u = min(1, sx, sy) con margen, así nunca sobresale de la tarjeta. Los textos solo se mueven.
     */
    static void applyLive(View v, RectF d) {
        if (!(v instanceof ViewGroup) || v.getWidth() == 0) return;
        ViewGroup g = (ViewGroup) v;
        Rect ib = iconBounds(v);
        float ps = parentScale(v);
        RectF b = naturalBox(v);
        float bw = Math.max(1, ib.width()), bh = Math.max(1, ib.height());
        float sx = Math.max(0.05f, d.width() / ps / bw), sy = Math.max(0.05f, d.height() / ps / bh);
        float tx = (d.left - b.left) / ps, ty = (d.top - b.top) / ps;

        ViewParent p = v.getParent();
        if (!LIVE_SAVED.containsKey(v)) {
            LIVE_SAVED.put(v, new boolean[]{g.getClipChildren(),
                    p instanceof ViewGroup && ((ViewGroup) p).getClipChildren()});
        }
        g.setClipChildren(false);
        if (p instanceof ViewGroup) ((ViewGroup) p).setClipChildren(false);
        if (v.getClipToOutline()) v.setClipToOutline(false);

        for (int k = 0; k < g.getChildCount(); k++) {
            View c = g.getChildAt(k);
            if (c instanceof TextView) {
                float ccx = c.getLeft() + c.getWidth() / 2f, ccy = c.getTop() + c.getHeight() / 2f;
                c.setScaleX(1f);
                c.setScaleY(1f);
                c.setTranslationX((ccx - ib.left) * (sx - 1f) + tx);
                c.setTranslationY((ccy - ib.top) * (sy - 1f) + ty);
            } else {
                c.setPivotX(ib.left - c.getLeft());
                c.setPivotY(ib.top - c.getTop());
                c.setScaleX(sx);
                c.setScaleY(sy);
                c.setTranslationX(tx);
                c.setTranslationY(ty);
            }
        }
        float u = Math.min(1f, Math.min(sx, sy));
        if (u < 1f) u *= (1f - SAFE_PADDING);
        float[] counter = {u / sx, u / sy};
        for (View ic : icons(v)) {
            ic.setScaleX(counter[0]);
            ic.setScaleY(counter[1]);
        }
        LIVE.put(v, counter);
        RectF cur = LIVE_D.get(v);
        if (cur == null) LIVE_D.put(v, new RectF(d));
        else cur.set(d);
    }

    static void clearLive(View v) {
        LIVE.remove(v);
        LIVE_D.remove(v);
        boolean[] saved = LIVE_SAVED.remove(v);
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int k = 0; k < g.getChildCount(); k++) {
            View c = g.getChildAt(k);
            c.setScaleX(1f);
            c.setScaleY(1f);
            c.setTranslationX(0f);
            c.setTranslationY(0f);
        }
        for (View ic : icons(v)) {
            ic.setScaleX(1f);
            ic.setScaleY(1f);
        }
        if (saved != null) {
            g.setClipChildren(saved[0]);
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).setClipChildren(saved[1]);
        }
    }

    // ----------------------------------------------------- estados válidos

    static final class Grid {
        int sw, sh;            // tamaño actual (celdas)
        float px, py;          // paso de celda en pantalla
        List<int[]> valid;
    }

    private static int[] spans(Object overlay) {
        try {
            Object info = fInfo.get(overlay);
            return new int[]{info.getClass().getField("spanX").getInt(info), info.getClass().getField("spanY").getInt(info)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tamaños de Kashi que caben en la rejilla (celdas y borde de la página) y en celdas libres. */
    @SuppressWarnings("unchecked")
    private static Grid grid(Object overlay, View t) {
        try {
            int[] cells = (int[]) fCells.get(overlay);
            float sc = fScale.getFloat(overlay);
            Object info = fInfo.get(overlay);
            if (cells == null || cells.length < 4 || info == null || sc <= 0f) return null;
            Grid g = new Grid();
            g.px = (cells[0] + cells[2]) * sc;
            g.py = (cells[1] + cells[3]) * sc;
            if (g.px <= 1f || g.py <= 1f) return null;
            Class<?> ic = info.getClass();
            g.sw = ic.getField("spanX").getInt(info);
            g.sh = ic.getField("spanY").getInt(info);
            int cx = ic.getField("cellX").getInt(info), cy = ic.getField("cellY").getInt(info);

            View[][] occ = null;
            int hc = -1, vc = -1;
            View page = null;
            for (ViewParent p = t.getParent(); p instanceof View; p = ((View) p).getParent()) {
                Field fh = findField(p.getClass(), "mHCells"), fo = findField(p.getClass(), "mOccupiedCell");
                if (fh != null || fo != null) {
                    page = (View) p;
                    if (fo != null) occ = (View[][]) fo.get(p);
                    Field fv = findField(p.getClass(), "mVCells");
                    if (fh != null) hc = fh.getInt(p);
                    if (fv != null) vc = fv.getInt(p);
                    break;
                }
            }
            if (hc < 0 && occ != null) hc = occ.length;
            if (vc < 0 && occ != null && occ.length > 0) vc = occ[0].length;
            RectF pageR = page != null ? screenRect(page) : null;
            RectF box = naturalBox(t);

            g.valid = new ArrayList<>();
            for (int[] sz : (List<int[]>) mSizes.invoke(overlay)) {
                if (!fits(sz[0], sz[1], cx, cy, occ, hc, vc, t)) continue;
                if (pageR != null) {
                    float r = box.right + (sz[0] - g.sw) * g.px, bt = box.bottom + (sz[1] - g.sh) * g.py;
                    if (r > pageR.right + 0.25f * g.px || bt > pageR.bottom + 0.25f * g.py) continue;
                }
                g.valid.add(sz);
            }
            if (!has(g.valid, g.sw, g.sh)) g.valid.add(new int[]{g.sw, g.sh});
            return g;
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean fits(int w, int h, int cx, int cy, View[][] occ, int hc, int vc, View self) {
        if (hc > 0 && (cx < 0 || cx + w > hc)) return false;
        if (vc > 0 && (cy < 0 || cy + h > vc)) return false;
        if (occ == null) return true;
        for (int x = cx; x < cx + w; x++) {
            for (int y = cy; y < cy + h; y++) {
                if (x < 0 || y < 0 || x >= occ.length || y >= occ[x].length) return false;
                View o = occ[x][y];
                if (o != null && o != self) return false;
            }
        }
        return true;
    }

    /**
     * Punto más cercano del espacio válido: los tamaños, las transiciones rectas entre los que
     * comparten ancho o alto y las zonas rellenas cuando cuatro forman un rectángulo.
     */
    static float[] project(float fw, float fh, List<int[]> valid) {
        float bx = valid.get(0)[0], by = valid.get(0)[1], best = Float.MAX_VALUE;
        int n = valid.size();
        for (int i = 0; i < n; i++) {
            int[] a = valid.get(i);
            float d = dist2(fw, fh, a[0], a[1]);
            if (d < best) {
                best = d;
                bx = a[0];
                by = a[1];
            }
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                int[] b = valid.get(j);
                float qx, qy;
                if (a[1] == b[1] && a[0] < b[0]) {
                    qx = clamp(fw, a[0], b[0]);
                    qy = a[1];
                } else if (a[0] == b[0] && a[1] < b[1]) {
                    qx = a[0];
                    qy = clamp(fh, a[1], b[1]);
                } else if (a[0] < b[0] && a[1] < b[1] && has(valid, a[0], b[1]) && has(valid, b[0], a[1])) {
                    qx = clamp(fw, a[0], b[0]);
                    qy = clamp(fh, a[1], b[1]);
                } else {
                    continue;
                }
                d = dist2(fw, fh, qx, qy);
                if (d < best) {
                    best = d;
                    bx = qx;
                    by = qy;
                }
            }
        }
        return new float[]{bx, by};
    }

    static int[] nearest(float fw, float fh, List<int[]> valid) {
        int[] best = valid.get(0);
        float bd = Float.MAX_VALUE;
        for (int[] v : valid) {
            float d = dist2(fw, fh, v[0], v[1]);
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    private static boolean has(List<int[]> valid, int w, int h) {
        for (int[] v : valid) if (v[0] == w && v[1] == h) return true;
        return false;
    }

    private static void setWant(Object overlay, int[] want) {
        try {
            fWantX.setInt(overlay, want[0]);
            fWantY.setInt(overlay, want[1]);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------- cambio de diseño

    /** Lo que había antes del cambio de tamaño (solo para cambios sin arrastre). */
    static final class Snap {
        RectF box;
        List<RectF> icons;
        List<Drawable> drawables;
    }

    /** Captura la carpeta y vigila, antes de cada fotograma, hasta que Kashi la sustituya. */
    private static void arm(final Object info) throws Throwable {
        final View old = buddy(info);
        if (old == null || !old.isAttachedToWindow() || WATCH.containsKey(info)) return;
        final Snap s = capture(old);
        final ViewTreeObserver vto = old.getViewTreeObserver();
        final long deadline = SystemClock.uptimeMillis() + WATCH_TIMEOUT_MS;
        final ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
        final Runnable cancel = new Runnable() {
            @Override
            public void run() {
                if (vto.isAlive()) vto.removeOnPreDrawListener(holder[0]);
                if (WATCH.get(info) == this) WATCH.remove(info);
            }
        };
        holder[0] = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    View nv = buddy(info);
                    if (nv != null && nv != old && nv.isAttachedToWindow() && nv.getWidth() > 0) {
                        cancel.run();
                        clearLive(old);
                        begin(nv, s, info);
                    } else if (SystemClock.uptimeMillis() > deadline) {
                        cancel.run();
                    }
                } catch (Throwable t) {
                    cancel.run();
                    XposedBridge.log(t);
                }
                return true;
            }
        };
        vto.addOnPreDrawListener(holder[0]);
        WATCH.put(info, cancel);
    }

    private static View buddy(Object infoEx) throws Throwable {
        if (infoEx == null) return null;
        Object r = infoEx.getClass().getMethod("getBuddyIconView").invoke(infoEx);
        if (r == null || r instanceof View) return (View) r;
        Method as = r.getClass().getMethod("asType", Class.class);
        as.setAccessible(true);
        return (View) as.invoke(r, View.class);
    }

    private static Snap capture(View old) {
        Snap s = new Snap();
        s.icons = new ArrayList<>();
        s.drawables = new ArrayList<>();
        for (View ic : icons(old)) {
            s.icons.add(screenRect(ic));
            s.drawables.add(ic instanceof ImageView ? copy(((ImageView) ic).getDrawable(), ic) : null);
        }
        RectF liveD = LIVE_D.get(old);
        s.box = liveD != null ? new RectF(liveD) : naturalBox(old);
        return s;
    }

    /**
     * Kashi ha sustituido la carpeta. Los iconos reales de la nueva se ocultan y la capa de
     * iconos (la del arrastre, o una nueva sembrada con los de la antigua) los lleva a su
     * nuevo sitio. El contenedor lo lleva el arrastre o, sin arrastre, su propio muelle.
     */
    private static void begin(final View nv, Snap snap, Object info) {
        if (nv.getWidth() == 0 || nv.getHeight() == 0) return;
        final Session ses = session;
        hideIcons(nv);
        if (ses != null) {
            ses.view = nv;
            applyLive(nv, ses.d);
            if (ses.layer == null) ses.layer = IconLayer.fromSnap(nv, snap, contents(info));
            ses.layer.retarget(nv);
            if (ses.releasing && ses.settle == null) settle(nv, ses);
            return;
        }
        // Cambio sin arrastre (p. ej. desde el menú): contenedor e iconos con su propio muelle.
        final IconLayer layer = IconLayer.fromSnap(nv, snap, contents(info));
        final RectF ro = new RectF(snap.box), rn = naturalBox(nv), cur = new RectF();
        applyLive(nv, ro);
        layer.retarget(nv);
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        Spring.apply(va, 0f, 1f, 0f, SPRING_RESPONSE, SPRING_DAMPING);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                lerpRect(cur, ro, rn, (Float) a.getAnimatedValue());
                applyLive(nv, cur);
                layer.retarget(nv);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                clearLive(nv);
                finishWithLayer(nv, layer, null);
            }
        });
        va.start();
    }

    // --------------------------------------------------------- capa de iconos

    /** Copias de los iconos de TODO el contenido de la carpeta (en su orden). */
    private static List<Drawable> contents(Object info) {
        List<Drawable> out = new ArrayList<>();
        try {
            Object list = info.getClass().getMethod("getContents").invoke(info);
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (out.size() >= 40) break;
                    Drawable d = null;
                    try {
                        d = (Drawable) item.getClass().getMethod("getIconDrawable").invoke(item);
                    } catch (Throwable ignored) {
                    }
                    out.add(d != null ? copy(d, null) : null);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<Drawable> contentsOfOverlay(Object overlay) {
        try {
            return contents(fInfo.get(overlay));
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static Drawable copy(Drawable d, View ctx) {
        if (d == null) return null;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            if (cs == null) return null;
            return (ctx != null ? cs.newDrawable(ctx.getResources()) : cs.newDrawable()).mutate();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void hideIcons(View v) {
        for (View ic : icons(v)) {
            if (ic.getVisibility() == View.VISIBLE) {
                ic.setVisibility(View.INVISIBLE);
                HIDDEN.put(ic, Boolean.TRUE);
            }
        }
    }

    private static void showIcons(View v) {
        for (View ic : icons(v)) {
            if (HIDDEN.remove(ic) != null) ic.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Capa persistente de iconos dibujada en el overlay de la ventana. Cada icono es un
     * "sprite" con muelles de posición, tamaño y presencia; su destino es, en cada fotograma,
     * el sitio del icono real (oculto) en el diseño actual de la carpeta.
     */
    static final class IconLayer extends Drawable implements Choreographer.FrameCallback {
        static final class Sprite {
            Drawable d;
            boolean real;                 // el dibujo viene de un icono real (no del contenido)
            boolean placed;
            float[] x = new float[2], y = new float[2], s = new float[2], p = new float[2];
            float tx, ty, ts, tp;
        }

        final View root;
        final int[] rl = new int[2];
        final List<Sprite> sprites = new ArrayList<>();
        final List<Drawable> pool;
        long last;
        boolean removed;
        Runnable onSettled;

        private IconLayer(View anyView, List<Drawable> pool) {
            this.root = anyView.getRootView();
            this.pool = pool;
            root.getLocationOnScreen(rl);
            setBounds(0, 0, Math.max(1, root.getWidth()), Math.max(1, root.getHeight()));
            try {
                root.getOverlay().add(this);
            } catch (Throwable ignored) {
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        /** Sembrada con los iconos que se ven ahora en la carpeta v (en su sitio, sin animar). */
        static IconLayer fromView(View v, List<Drawable> pool) {
            IconLayer l = new IconLayer(v, pool);
            List<View> ics = icons(v);
            for (int k = 0; k < ics.size(); k++) {
                View ic = ics.get(k);
                Drawable d = ic instanceof ImageView ? copy(((ImageView) ic).getDrawable(), ic) : null;
                l.seed(k, screenRect(ic), d);
            }
            return l;
        }

        /** Sembrada con lo capturado antes del cambio de tamaño. */
        static IconLayer fromSnap(View v, Snap snap, List<Drawable> pool) {
            IconLayer l = new IconLayer(v, pool);
            if (snap != null) {
                for (int k = 0; k < snap.icons.size(); k++) l.seed(k, snap.icons.get(k), snap.drawables.get(k));
            }
            return l;
        }

        private Sprite sprite(int k) {
            while (sprites.size() <= k) sprites.add(new Sprite());
            Sprite sp = sprites.get(k);
            if (sp.d == null && k < pool.size()) sp.d = pool.get(k);
            return sp;
        }

        private void seed(int k, RectF r, Drawable real) {
            Sprite sp = sprite(k);
            if (real != null) {
                sp.d = real;
                sp.real = true;
            }
            sp.x[0] = sp.tx = r.centerX();
            sp.y[0] = sp.ty = r.centerY();
            sp.s[0] = sp.ts = r.width();
            sp.p[0] = sp.tp = 1f;
            sp.placed = true;
        }

        /** Destinos = sitios de los iconos reales (ocultos) del diseño actual de v. */
        void retarget(View v) {
            if (removed) return;
            List<View> ics = icons(v);
            for (int k = 0; k < ics.size(); k++) {
                View ic = ics.get(k);
                if (ic.getVisibility() == View.VISIBLE) {     // si el launcher la vuelve a mostrar al cargarla
                    ic.setVisibility(View.INVISIBLE);
                    HIDDEN.put(ic, Boolean.TRUE);
                }
                RectF r = screenRect(ic);
                Sprite sp = sprite(k);
                if (!sp.real && ic instanceof ImageView && ((ImageView) ic).getDrawable() != null) {
                    Drawable d = copy(((ImageView) ic).getDrawable(), ic);
                    if (d != null) {
                        sp.d = d;
                        sp.real = true;
                    }
                }
                sp.tx = r.centerX();
                sp.ty = r.centerY();
                sp.ts = r.width();
                sp.tp = 1f;
                if (!sp.placed || (sp.p[0] < 0.02f && sp.p[1] <= 0f)) {
                    // Aparece en su sitio con zoom.
                    sp.x[0] = sp.tx;
                    sp.y[0] = sp.ty;
                    sp.s[0] = sp.ts;
                    sp.x[1] = sp.y[1] = sp.s[1] = 0f;
                    if (!sp.placed) sp.p[0] = 0f;
                    sp.placed = true;
                }
            }
            for (int k = ics.size(); k < sprites.size(); k++) sprites.get(k).tp = 0f;   // sobran: se encogen
            invalidateSelf();
        }

        void whenSettled(Runnable r) {
            onSettled = r;
        }

        void remove() {
            if (removed) return;
            removed = true;
            Choreographer.getInstance().removeFrameCallback(this);
            try {
                root.getOverlay().remove(this);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void doFrame(long nanos) {
            if (removed) return;
            float dt = last == 0 ? 1f / 120f : Math.min(1f / 30f, (nanos - last) / 1e9f);
            last = nanos;
            boolean moving = false;
            for (Sprite sp : sprites) {
                if (!sp.placed) continue;
                StackSwipe.springStep(sp.x, sp.tx, ICON_MOVE_RESPONSE, ICON_MOVE_DAMPING, dt);
                StackSwipe.springStep(sp.y, sp.ty, ICON_MOVE_RESPONSE, ICON_MOVE_DAMPING, dt);
                StackSwipe.springStep(sp.s, sp.ts, ICON_MOVE_RESPONSE, ICON_MOVE_DAMPING, dt);
                StackSwipe.springStep(sp.p, sp.tp, ICON_POP_RESPONSE, ICON_POP_DAMPING, dt);
                if (Math.abs(sp.x[0] - sp.tx) > 0.5f || Math.abs(sp.y[0] - sp.ty) > 0.5f
                        || Math.abs(sp.s[0] - sp.ts) > 0.5f || Math.abs(sp.p[0] - sp.tp) > 0.01f
                        || Math.abs(sp.p[1]) > 0.05f) {
                    moving = true;
                }
            }
            invalidateSelf();
            if (!moving && onSettled != null) {
                Runnable r = onSettled;
                onSettled = null;
                r.run();
                if (removed) return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            for (Sprite sp : sprites) {
                if (!sp.placed || sp.d == null || sp.p[0] <= 0.002f) continue;
                float size = Math.max(0f, sp.s[0] * sp.p[0]);
                float cx = sp.x[0] - rl[0], cy = sp.y[0] - rl[1];
                sp.d.setBounds(Math.round(cx - size / 2f), Math.round(cy - size / 2f),
                        Math.round(cx + size / 2f), Math.round(cy + size / 2f));
                sp.d.setAlpha(Math.round(255 * StackSwipe.clamp01(sp.p[0] * 1.6f)));
                sp.d.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------ utilidades

    private static Rect iconBounds(View v) {
        try {
            Rect r = (Rect) mIconBounds.invoke(null, v);
            if (r != null && !r.isEmpty()) return r;
        } catch (Throwable ignored) {
        }
        return new Rect(0, 0, v.getWidth(), v.getHeight());
    }

    /** Caja del icono de la carpeta en pantalla, solo por layout (sin transformaciones). */
    private static RectF naturalBox(View v) {
        Rect ib = iconBounds(v);
        float ps = parentScale(v), ox, oy;
        ViewParent p = v.getParent();
        if (p instanceof View) {
            View pv = (View) p;
            int[] pl = new int[2];
            pv.getLocationOnScreen(pl);
            ox = pl[0] + (v.getLeft() - pv.getScrollX()) * ps;
            oy = pl[1] + (v.getTop() - pv.getScrollY()) * ps;
        } else {
            int[] l = new int[2];
            v.getLocationOnScreen(l);
            ox = l[0];
            oy = l[1];
        }
        return new RectF(ox + ib.left * ps, oy + ib.top * ps, ox + ib.right * ps, oy + ib.bottom * ps);
    }

    private static List<View> icons(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View v, List<View> out) {
        if (v.getVisibility() != View.VISIBLE && !HIDDEN.containsKey(v)) return;
        if (v.getClass().getName().contains("PreviewIconView")) {
            if (v.getWidth() > 0) out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) collect(g.getChildAt(k), out);
        }
    }

    private static RectF screenRect(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        float sx = 1f, sy = 1f;
        for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            sx *= c.getScaleX();
            sy *= c.getScaleY();
        }
        return new RectF(l[0], l[1], l[0] + v.getWidth() * sx, l[1] + v.getHeight() * sy);
    }

    private static float parentScale(View v) {
        return parentScaleXY(v)[0];
    }

    private static float[] parentScaleXY(View v) {
        float sx = 1f, sy = 1f;
        ViewParent p = v.getParent();
        for (View c = p instanceof View ? (View) p : null; c != null;
             c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            sx *= c.getScaleX();
            sy *= c.getScaleY();
        }
        return new float[]{Math.max(0.01f, sx), Math.max(0.01f, sy)};
    }

    private static Field findField(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void lerpRect(RectF out, RectF a, RectF b, float t) {
        out.set(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t));
    }

    private static float dist2(float ax, float ay, float bx, float by) {
        return (ax - bx) * (ax - bx) + (ay - by) * (ay - by);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private FolderMorph() {
    }
}
