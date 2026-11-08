package com.icehome.up;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Pila de widgets estilo HyperOS 4 para el launcher de Kashi V7.5 (com.kx.home.WidgetStack$Layer).
 *
 * BARAJA: detrás de la tarjeta activa se ven las siguientes en diagonal (arriba-derecha),
 * cada una más pequeña, transparente y desenfocada. Cada tarjeta tiene su propio contorno
 * redondeado y ningún contenedor recorta, así la baraja se ve también en reposo.
 *
 * ARRASTRE LIBRE en cualquier dirección: la tarjeta sigue al dedo 1:1 (goma logarítmica
 * pasado el radio), con escala, inclinación y estiramiento según distancia y velocidad.
 *
 * AL SOLTAR, física por fotograma que conserva la velocidad del dedo:
 *  - si supera el 50 % del radio o hay fling hacia fuera, la tarjeta sigue su inercia, hace un
 *    arco, pasa por detrás cuando ya vuelve y acaba en el último nivel de la baraja;
 *  - si no, vuelve a su sitio con sobreimpulso.
 *
 * Flujo de Kashi: touch() -> layoutPages(dy) -> render(...); al soltar -> release(dy, vy).
 * Sustituimos layoutPages y release y, tras render(), aplicamos nuestras transformaciones.
 */
final class StackSwipe {

    // ---------------------------------------------------------------- ajustes
    // Arrastre
    static final float RADIUS_DP = 120f;          // radio antes de la goma
    static final float RUBBER_K = 0.35f;          // fuerza de la goma logarítmica (× radio)
    static final float DRAG_SCALE = 0.95f;        // escala al arrastrar
    static final float TILT_MAX = 6f;             // inclinación máxima (grados)
    static final float TILT_PER_1000DP = 3.5f;    // grados por 1000 dp/s de velocidad horizontal
    static final float STRETCH_MAX = 0.12f;       // estiramiento por distancia (más allá del radio)
    static final float STRETCH_SOFT = 0.9f;
    static final float STRETCH_SQUASH = 0.5f;     // la otra dirección se estrecha la mitad
    static final float VEL_STRETCH = 0.06f;       // estiramiento por velocidad…
    static final float VEL_STRETCH_DP = 4000f;    // …máximo a esta velocidad (dp/s)
    static final float REVEAL_WHILE_DRAG = 0.35f; // cuánto avanza la baraja al arrastrar
    static final float COMMIT_REVEAL_BOOST = 0.15f;
    static final float PARALLAX = 0.08f;          // las de detrás siguen al dedo (÷ nivel)
    // Decisión al soltar
    static final float COMMIT_FRACTION = 0.5f;    // 50 % del radio
    static final float FLING_DP = 900f;           // dp/s
    // Física al soltar
    static final float THROW_RESPONSE = 0.46f, THROW_DAMPING = 0.70f;   // arco + micro-rebote ≈ 5 dp
    static final float RETURN_RESPONSE = 0.36f, RETURN_DAMPING = 0.66f; // vuelta con sobreimpulso
    static final float SCALE_RESPONSE = 0.40f, SCALE_DAMPING = 0.80f;
    static final float REVEAL_RESPONSE = 0.38f, REVEAL_DAMPING = 0.72f;
    static final float THROW_MIN_DP = 1400f;      // impulso mínimo hacia fuera si se suelta sin velocidad
    static final float SWAP_MIN_S = 0.12f, SWAP_MAX_S = 0.35f;
    static final float FADE_S = 0.28f;
    static final float SEND_BLUR_DP = 12f;
    // Baraja
    static final int DECK_VISIBLE = 2;
    static final float DECK_DX_DP = 8f, DECK_DY_DP = 10f;   // por nivel: derecha y arriba
    static final float DECK_SCALE = 0.03f, DECK_ALPHA = 0.18f, DECK_BLUR_DP = 7f;
    static final float DECK_SPREAD = 0.5f;        // cuánto se abre al tocar
    static final float SPREAD_RESPONSE = 0.30f, SPREAD_DAMPING = 0.80f;
    static final long SPREAD_DELAY_MS = 60;

    // ------------------------------------------------------------ reflexión
    private static Field fPages, fCur, fKey, fSettle, fFront, fShowAll, fBase, fTracking, fDragging, fDownY, fSlop;
    private static Method mViewsOf, mHeight, mRender, mHideDotsLater, mSave, mCancelChildren, mShowDots;

    // ---------------------------------------------------------------- estado
    static final class St {
        VelocityTracker vt;
        float downRawX, downRawY, anchorX, rawDx;
        float curVx, curVy;
        boolean active;           // nuestro arrastre/física controla las transformaciones
        boolean beyond;           // pasado el umbral de envío
        boolean renderedOnce;     // ya se pidió a Kashi un render en este gesto
        float dx, dy, scale = 1f, stx = 1f, sty = 1f, tilt, alpha = 1f, blur, reveal;
        int frontPage = -1;
        float frontZ;
        float spread;
        ValueAnimator spreadAnim;
        Runnable spreadPending;
    }

    private static final Map<View, St> STATE = new WeakHashMap<>();
    private static final Map<View, Float> BLUR = new WeakHashMap<>();
    private static final Map<View, Integer> HW = new WeakHashMap<>();
    private static final Map<View, Float> RADIUS = new WeakHashMap<>();
    /** Estado original (clipChildren, clipToPadding, clipToOutline, Z) de los contenedores tocados. */
    private static final Map<View, float[]> SAVED = new WeakHashMap<>();
    private static final Map<View, List<ViewGroup>> RAISED = new WeakHashMap<>();

    static St st(View layer) {
        St s = STATE.get(layer);
        if (s == null) {
            s = new St();
            STATE.put(layer, s);
        }
        return s;
    }

    // --------------------------------------------------------------- instalar

    static void install(ClassLoader cl) {
        Class<?> layer = XposedHelpers.findClassIfExists("com.kx.home.WidgetStack$Layer", cl);
        Class<?> stack = XposedHelpers.findClassIfExists("com.kx.home.WidgetStack", cl);
        if (layer == null || stack == null) {
            XposedBridge.log("IceHome: no se encuentra WidgetStack (¿no es el launcher de Kashi V7.5?)");
            return;
        }
        try {
            fPages = field(layer, "pages");
            fCur = field(layer, "cur");
            fKey = field(layer, "key");
            fSettle = field(layer, "settle");
            fFront = field(layer, "front");
            fShowAll = field(layer, "showAll");
            fBase = field(layer, "base");
            fTracking = field(layer, "tracking");
            fDragging = field(layer, "dragging");
            fDownY = field(layer, "downY");
            fSlop = field(layer, "slop");
            mViewsOf = method(layer, "viewsOf", int.class);
            mHeight = method(layer, "height");
            mRender = method(layer, "render", int.class, int.class, float.class, boolean.class, float.class);
            mHideDotsLater = method(layer, "hideDotsLater");
            mCancelChildren = method(layer, "cancelChildren", MotionEvent.class);
            mShowDots = method(layer, "showDots");
            mSave = method(stack, "save", long.class, List.class, int.class);
            method(layer, "layoutPages", float.class);
            method(layer, "touch", MotionEvent.class);
        } catch (Throwable t) {
            XposedBridge.log("IceHome: la estructura de WidgetStack ha cambiado, no se aplica la animación");
            XposedBridge.log(t);
            return;
        }

        XposedHelpers.findAndHookMethod(layer, "render",
                int.class, int.class, float.class, boolean.class, float.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            afterRender((View) p.thisObject, (Integer) p.args[0]);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(layer, "layoutPages", float.class, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam p) throws Throwable {
                try {
                    layoutPages((View) p.thisObject, (Float) p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                    XposedBridge.invokeOriginalMethod(p.method, p.thisObject, p.args);
                }
                return null;
            }
        });
        XposedHelpers.findAndHookMethod(layer, "release", float.class, float.class, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam p) throws Throwable {
                try {
                    release((View) p.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                    XposedBridge.invokeOriginalMethod(p.method, p.thisObject, p.args);
                }
                return null;
            }
        });
        XposedHelpers.findAndHookMethod(layer, "touch", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    beforeTouch((View) p.thisObject, (MotionEvent) p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    afterTouch((View) p.thisObject, (MotionEvent) p.args[0]);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
        XposedBridge.log("IceHome: pila de widgets HyperOS 4 (v11) activa");
    }

    // ------------------------------------------------------------- arrastre

    static int next(int cur, int n) {
        return n >= 2 ? (cur + 1) % n : -1;
    }

    static void layoutPages(View layer, float dy) throws Throwable {
        St st = st(layer);
        boolean dragging = fDragging.getBoolean(layer);
        int cur = fCur.getInt(layer);
        if (!dragging) {
            if (!st.active) render(layer, cur, -1, 0f, false, 0f);   // reposo (o la física manda)
            return;
        }
        List<Integer> pages = pages(layer);
        int n = pages.size();
        float R = dp(layer, RADIUS_DP);
        float rawDx = st.rawDx;

        float[] v = rubberVec(rawDx, dy, R);
        st.dx = v[0];
        st.dy = v[1];
        float mm = (float) Math.hypot(v[0], v[1]);
        float k = clamp01(mm / R);
        st.scale = lerp(1f, DRAG_SCALE, k);
        float mRaw = (float) Math.hypot(rawDx, dy);
        float aDisp = mRaw > R ? STRETCH_MAX * (1f - (float) Math.exp(-(mRaw - R) / (R * STRETCH_SOFT))) : 0f;
        stretch(st, layer, aDisp, rawDx, dy, st.curVx, st.curVy);
        st.alpha = 1f;
        st.blur = 0f;
        boolean beyond = mm >= COMMIT_FRACTION * R;
        if (beyond && !st.beyond) layer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        st.beyond = beyond;
        float targetReveal = REVEAL_WHILE_DRAG * k + (beyond ? COMMIT_REVEAL_BOOST : 0f);
        st.reveal += (targetReveal - st.reveal) * 0.35f;
        st.frontPage = cur;
        st.active = true;
        if (!st.renderedOnce) {
            st.renderedOnce = true;
            render(layer, cur, next(cur, n), k, true, 0f);   // Kashi prepara visibilidad y puntos
        } else {
            fastFrame(layer);
        }
    }

    /** Goma: lineal hasta R, logarítmica a partir de ahí. */
    static float[] rubberVec(float x, float y, float R) {
        float m = (float) Math.hypot(x, y);
        if (m <= R || m == 0f) return new float[]{x, y};
        float c = RUBBER_K * R;
        float f = (R + c * (float) Math.log(1.0 + (m - R) / c)) / m;
        return new float[]{x * f, y * f};
    }

    /** Estirar y aplastar: a = max(por distancia, por velocidad), en la dirección del movimiento. */
    static void stretch(St st, View v, float aDisp, float dirX, float dirY, float vx, float vy) {
        float dens = v.getResources().getDisplayMetrics().density;
        float sp = (float) Math.hypot(vx, vy) / dens;
        float aVel = VEL_STRETCH * Math.min(1f, sp / VEL_STRETCH_DP);
        float a, ux, uy;
        if (aVel > aDisp && sp > 1f) {
            float m = (float) Math.hypot(vx, vy);
            a = aVel;
            ux = Math.abs(vx) / m;
            uy = Math.abs(vy) / m;
        } else {
            float m = (float) Math.hypot(dirX, dirY);
            if (m < 1e-3f || aDisp <= 0f) {
                st.stx = st.sty = 1f;
                return;
            }
            a = aDisp;
            ux = Math.abs(dirX) / m;
            uy = Math.abs(dirY) / m;
        }
        st.stx = 1f + a * (ux - STRETCH_SQUASH * uy);
        st.sty = 1f + a * (uy - STRETCH_SQUASH * ux);
    }

    // ---------------------------------------------------------------- dibujo

    /** Tras cada render de Kashi: si el gesto manda, nuestras transformaciones; si no, reposo. */
    static void afterRender(View layer, int i) throws Throwable {
        if (fShowAll.getBoolean(layer)) return;
        St st = st(layer);
        if (st.active) {
            fastFrame(layer);
            return;
        }
        List<Integer> pages = pages(layer);
        if (i < 0 || i >= pages.size()) return;
        for (View v : viewsOf(layer, i)) {
            set(v, 0f, 0f, 1f, 1f, 1f, 0f, 0f);
            v.setTranslationZ(0f);
        }
        deck(layer, pages, i, 0f, st);
        setFront(layer, pages, i);
        if (!busy(layer)) raise(layer, false);
        unclip(layer);
    }

    /** Un fotograma sin pasar por el render de Kashi: tarjeta de delante + baraja. */
    static void fastFrame(View layer) {
        try {
            St st = st(layer);
            List<Integer> pages = pages(layer);
            int cur = fCur.getInt(layer);
            if (cur < 0 || cur >= pages.size()) return;
            boolean base = isBase(pages, cur);
            for (View v : viewsOf(layer, cur)) {
                if (!base && v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                set(v, st.dx, st.dy, st.scale * st.stx, st.scale * st.sty, st.alpha, st.blur, st.tilt);
                v.setTranslationZ(st.frontZ);
                hwLayer(v, true);
            }
            deck(layer, pages, cur, st.reveal, st);
            setFront(layer, pages, st.frontPage >= 0 ? st.frontPage : cur);
            raise(layer, true);
            unclip(layer);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Baraja: las siguientes detrás, en diagonal. reveal (0..1) la adelanta un nivel. */
    private static void deck(View layer, List<Integer> pages, int cur, float reveal, St st) throws Throwable {
        int n = pages.size();
        if (n < 2) return;
        float spreadMul = 1f + DECK_SPREAD * st.spread;
        boolean dragging = fDragging.getBoolean(layer);
        float radius = cornerRadius(layer);
        int last = Math.min(n - 1, DECK_VISIBLE + 1);
        for (int d = 1; d <= last; d++) {
            int page = (cur + d) % n;
            float depth = d - reveal;
            float[] p = pose(layer, depth, spreadMul);
            if (st.active && dragging && depth > 0.05f) {
                p[0] += st.dx * PARALLAX / depth;
                p[1] += st.dy * PARALLAX / depth;
            }
            boolean base = isBase(pages, page);
            for (View v : viewsOf(layer, page)) {
                if (!base && v.getVisibility() != View.VISIBLE && p[3] > 0.003f) v.setVisibility(View.VISIBLE);
                set(v, p[0], p[1], p[2], p[2], p[3], p[4], 0f);
                v.setTranslationZ(-0.01f * Math.max(0f, depth));
                roundCard(v, radius);
                if (st.active) hwLayer(v, true);
            }
        }
        for (View v : viewsOf(layer, cur)) roundCard(v, radius);
    }

    /** Pose de un nivel: {tx, ty, escala, opacidad, blur}. Profundidad < 0 = se pasa hacia delante. */
    static float[] pose(View v, float depth, float spreadMul) {
        float dens = v.getResources().getDisplayMetrics().density;
        float dd = Math.max(-0.4f, depth);
        float pos = Math.max(0f, dd);
        float alpha = 1f - DECK_ALPHA * pos;
        if (pos > DECK_VISIBLE) alpha *= clamp01(DECK_VISIBLE + 1f - pos);
        return new float[]{DECK_DX_DP * dens * dd * spreadMul, -DECK_DY_DP * dens * dd * spreadMul,
                1f - DECK_SCALE * dd, clamp01(alpha), DECK_BLUR_DP * dens * pos};
    }

    static void set(View v, float tx, float ty, float sx, float sy, float a, float blurPx, float rot) {
        v.setClipBounds(null);
        v.setTranslationX(tx);
        v.setTranslationY(ty);
        v.setScaleX(sx);
        v.setScaleY(sy);
        v.setRotation(rot);
        v.setAlpha(clamp01(a));
        blur(v, blurPx);
    }

    static void blur(View v, float r) {
        if (Build.VERSION.SDK_INT < 31) return;
        r = Math.round(r);   // pasos de 1 px: no se recrea en cada fotograma
        if (r < 1f) r = 0f;
        Float last = BLUR.get(v);
        if ((last == null && r == 0f) || (last != null && last == r)) return;
        try {
            v.setRenderEffect(r == 0f ? null : RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL));
            if (r == 0f) BLUR.remove(v);
            else BLUR.put(v, r);
        } catch (Throwable ignored) {
        }
    }

    private static void setFront(View layer, List<Integer> pages, int page) throws Throwable {
        if (fFront.getInt(layer) != page) fFront.setInt(layer, page);
        // Página base (el widget original) = Integer.MIN_VALUE: Kashi la pone encima con Z −1 en el Layer.
        layer.setTranslationZ(isBase(pages, page) ? -1f : 0f);
        layer.invalidate();
    }

    /** Capa por hardware durante el gesto: mover/escalar/fundir no redibuja el widget. */
    static void hwLayer(View v, boolean on) {
        if (on) {
            if (HW.containsKey(v)) return;
            HW.put(v, v.getLayerType());
            if (v.getLayerType() != View.LAYER_TYPE_HARDWARE) v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            Integer t = HW.remove(v);
            if (t != null && v.getLayerType() != t) v.setLayerType(t, null);
        }
    }

    // ------------------------------------------------------ recortes y orden

    /**
     * Ningún contenedor recorta las tarjetas: desde el Layer hasta la página (CellLayout)
     * se quitan clipChildren, clipToPadding y clipToOutline. Las esquinas las pone cada
     * tarjeta con su propio contorno (roundCard). Se mantiene también en reposo.
     */
    private static void unclip(View layer) {
        for (View v = layer; v != null; ) {
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                if (!SAVED.containsKey(g)) {
                    SAVED.put(g, new float[]{g.getClipChildren() ? 1 : 0, g.getClipToPadding() ? 1 : 0,
                            g.getClipToOutline() ? 1 : 0, g.getTranslationZ()});
                }
                if (g.getClipChildren()) g.setClipChildren(false);
                if (g.getClipToPadding()) g.setClipToPadding(false);
                if (g.getClipToOutline()) g.setClipToOutline(false);
                if (isPage(g)) break;
            }
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
    }

    /**
     * Durante el gesto, además, toda la jerarquía (hasta la raíz) deja de recortar y sube su
     * Z para que la tarjeta pase por encima de iconos, dock y buscador. Se restaura al acabar.
     */
    private static void raise(View layer, boolean on) {
        if (on) {
            if (RAISED.containsKey(layer)) return;
            List<ViewGroup> chain = new ArrayList<>();
            View root = layer.getRootView();
            for (View v = layer.getParent() instanceof View ? (View) layer.getParent() : null; v != null; ) {
                if (v instanceof ViewGroup && v != root && v.getId() != android.R.id.content) {
                    ViewGroup g = (ViewGroup) v;
                    if (!SAVED.containsKey(g)) {
                        SAVED.put(g, new float[]{g.getClipChildren() ? 1 : 0, g.getClipToPadding() ? 1 : 0,
                                g.getClipToOutline() ? 1 : 0, g.getTranslationZ()});
                    }
                    g.setClipChildren(false);
                    g.setClipToPadding(false);
                    g.setClipToOutline(false);
                    g.setTranslationZ(SAVED.get(g)[3] + 1f);
                    chain.add(g);
                }
                if (v == root) break;
                ViewParent p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
            RAISED.put(layer, chain);
        } else {
            for (View v : new ArrayList<>(HW.keySet())) hwLayer(v, false);
            List<ViewGroup> chain = RAISED.remove(layer);
            if (chain == null) return;
            boolean belowPage = true;
            for (ViewGroup g : chain) {
                float[] s = SAVED.get(g);
                if (s == null) continue;
                g.setTranslationZ(s[3]);
                if (!belowPage) {           // por encima de la página: recorte original
                    g.setClipChildren(s[0] != 0);
                    g.setClipToPadding(s[1] != 0);
                    g.setClipToOutline(s[2] != 0);
                }
                if (isPage(g)) belowPage = false;
            }
        }
    }

    private static boolean isPage(View v) {
        for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (c.getName().endsWith(".CellLayout")) return true;
        }
        return false;
    }

    /** Radio de las esquinas del widget (del contorno original del contenedor). */
    private static float cornerRadius(View layer) {
        Float r = RADIUS.get(layer);
        if (r != null) return r;
        float rad = 0f;
        try {   // el mismo radio que usa MIUI para los widgets (20 dp estándar, 16 dp compacto)
            Class<?> rc = Class.forName("com.miui.home.common.widget.RoundedCornerEnforcement", false,
                    layer.getClass().getClassLoader());
            rad = (Float) rc.getMethod("computeEnforcedRadius", android.content.Context.class)
                    .invoke(null, layer.getContext());
        } catch (Throwable ignored) {
        }
        if (rad <= 0f) try {
            View base = (View) fBase.get(layer);
            if (base != null && base.getOutlineProvider() != null && !(base.getOutlineProvider() instanceof CardOutline)) {
                Outline o = new Outline();
                base.getOutlineProvider().getOutline(base, o);
                rad = o.getRadius();
            }
        } catch (Throwable ignored) {
        }
        if (rad <= 0f) rad = dp(layer, 20f);
        RADIUS.put(layer, rad);
        return rad;
    }

    private static void roundCard(View v, float radius) {
        ViewOutlineProvider p = v.getOutlineProvider();
        if (!(p instanceof CardOutline) || ((CardOutline) p).radius != radius) v.setOutlineProvider(new CardOutline(radius));
        if (!v.getClipToOutline()) v.setClipToOutline(true);
    }

    static final class CardOutline extends ViewOutlineProvider {
        final float radius;

        CardOutline(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    private static boolean busy(View layer) throws Throwable {
        ValueAnimator s = (ValueAnimator) fSettle.get(layer);
        St st = STATE.get(layer);
        return (s != null && s.isRunning()) || fTracking.getBoolean(layer) || fDragging.getBoolean(layer)
                || (st != null && (st.active || (st.spreadAnim != null && st.spreadAnim.isRunning())));
    }

    // ---------------------------------------------------------------- toque

    static void beforeTouch(View layer, MotionEvent e) throws Throwable {
        St st = st(layer);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (st.vt == null) st.vt = VelocityTracker.obtain();
            else st.vt.clear();
            st.rawDx = 0f;
            st.tilt = 0f;
            st.curVx = st.curVy = 0f;
            st.beyond = false;
            st.renderedOnce = false;
            st.downRawX = e.getRawX();
            st.downRawY = e.getRawY();
        }
        // Arrastre en cualquier dirección: si empieza en horizontal, lo tomamos nosotros
        // antes de que Kashi se lo pase al escritorio.
        if (a == MotionEvent.ACTION_MOVE && fTracking.getBoolean(layer) && !fDragging.getBoolean(layer)) {
            float ddx = e.getRawX() - st.downRawX, ddy = e.getRawY() - st.downRawY;
            List<Integer> pages = pages(layer);
            if (pages != null && pages.size() >= 2 && Math.abs(ddx) > fSlop.getInt(layer) && Math.abs(ddx) >= Math.abs(ddy)) {
                st.anchorX = st.downRawX;
                fDragging.setBoolean(layer, true);
                fDownY.setFloat(layer, e.getY() - ddy);
                try {
                    mCancelChildren.invoke(layer, e);
                    mShowDots.invoke(layer);
                } catch (Throwable ignored) {
                }
                ViewParent vp = layer.getParent();
                if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
            }
        }
        if (st.vt != null) {
            MotionEvent raw = MotionEvent.obtain(e);
            raw.setLocation(e.getRawX(), e.getRawY());
            st.vt.addMovement(raw);
            raw.recycle();
        }
        if (!fDragging.getBoolean(layer)) {
            st.anchorX = e.getRawX();
        } else {
            st.rawDx = e.getRawX() - st.anchorX;
            if (a == MotionEvent.ACTION_MOVE && st.vt != null) {
                st.vt.computeCurrentVelocity(1000);
                float vx = st.vt.getXVelocity(), vy = st.vt.getYVelocity();
                st.curVx += (vx - st.curVx) * 0.5f;
                st.curVy += (vy - st.curVy) * 0.5f;
                float target = Math.max(-TILT_MAX, Math.min(TILT_MAX, vx / dp(layer, 1000f) * TILT_PER_1000DP));
                st.tilt += (target - st.tilt) * 0.35f;
            }
        }
    }

    /** Al tocar la baraja se abre un poco; al soltar (o al empezar a arrastrar) se cierra. */
    static void afterTouch(final View layer, MotionEvent e) throws Throwable {
        final St st = st(layer);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            List<Integer> pages = pages(layer);
            if (pages == null || pages.size() < 2 || fShowAll.getBoolean(layer)) return;
            if (st.spreadPending != null) layer.removeCallbacks(st.spreadPending);
            st.spreadPending = new Runnable() {
                @Override
                public void run() {
                    st.spreadPending = null;
                    try {
                        if (fTracking.getBoolean(layer) && !fDragging.getBoolean(layer)) spread(layer, 1f);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            layer.postDelayed(st.spreadPending, SPREAD_DELAY_MS);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || !fTracking.getBoolean(layer)) {
            if (st.spreadPending != null) {
                layer.removeCallbacks(st.spreadPending);
                st.spreadPending = null;
            }
            if (st.spread > 0f || (st.spreadAnim != null && st.spreadAnim.isRunning())) spread(layer, 0f);
        }
    }

    private static void spread(final View layer, float to) {
        final St st = st(layer);
        if (st.spreadAnim != null) {
            st.spreadAnim.removeAllListeners();
            st.spreadAnim.cancel();
        }
        final ValueAnimator va = ValueAnimator.ofFloat(st.spread, to);
        Spring.apply(va, st.spread, to, 0f, SPREAD_RESPONSE, SPREAD_DAMPING);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                st.spread = (Float) a.getAnimatedValue();
                try {
                    if (!st.active && !fDragging.getBoolean(layer)) render(layer, fCur.getInt(layer), -1, 0f, false, 0f);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                // Último render ya sin animación en marcha: restaura el orden/recorte de la jerarquía.
                try {
                    if (st.spreadAnim == va && !st.active && !fDragging.getBoolean(layer)) {
                        render(layer, fCur.getInt(layer), -1, 0f, false, 0f);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
        st.spreadAnim = va;
        va.start();
    }

    // --------------------------------------------------------------- soltar

    static void release(View layer) throws Throwable {
        St st = st(layer);
        if (!st.active) {
            render(layer, fCur.getInt(layer), -1, 0f, false, 0f);
            mHideDotsLater.invoke(layer);
            return;
        }
        List<Integer> pages = pages(layer);
        int n = pages.size();
        int cur = fCur.getInt(layer);
        int nb = next(cur, n);
        float vx = 0f, vy = 0f;
        if (st.vt != null) {
            st.vt.computeCurrentVelocity(1000);
            vx = st.vt.getXVelocity();
            vy = st.vt.getYVelocity();
        }
        float R = dp(layer, RADIUS_DP);
        float m0 = (float) Math.hypot(st.dx, st.dy);
        boolean outward = m0 < 1f || (vx * st.dx + vy * st.dy) > 0f;
        boolean fling = Math.hypot(vx, vy) > dp(layer, FLING_DP) && outward;
        boolean commit = nb >= 0 && (m0 >= COMMIT_FRACTION * R || fling);

        ValueAnimator old = (ValueAnimator) fSettle.get(layer);
        if (old != null) old.cancel();
        physics(layer, pages, cur, nb, commit, vx, vy);
        mHideDotsLater.invoke(layer);
    }

    /**
     * Física por fotograma (muelles 2D, de escala y de la baraja) que conserva la velocidad
     * del dedo. Inclinación y estiramiento salen de la velocidad real de la tarjeta.
     */
    private static void physics(final View layer, final List<Integer> pages, final int cur, final int nb,
                                final boolean commit, float vx, float vy) {
        final St st = st(layer);
        final float dens = layer.getResources().getDisplayMetrics().density;
        final int dEnd = Math.min(pages.size() - 1, DECK_VISIBLE + 1);
        final float[] endPose = pose(layer, dEnd, 1f);
        final float tx = commit ? endPose[0] : 0f, ty = commit ? endPose[1] : 0f;
        final float ts = commit ? endPose[2] : 1f, tr = commit ? 1f : 0f;
        final float endAlpha = endPose[3], endBlur = Math.max(endPose[4], SEND_BLUR_DP * dens);
        final float deepZ = -0.01f * (pages.size() + 1);
        final float posResp = commit ? THROW_RESPONSE : RETURN_RESPONSE;
        final float posDamp = commit ? THROW_DAMPING : RETURN_DAMPING;

        final float[] x = {st.dx, vx}, y = {st.dy, vy}, sc = {st.scale, 0f}, rv = {st.reveal, 0f};
        if (commit) {
            // Soltada casi sin velocidad pero con distancia: impulso hacia fuera.
            float sp = (float) Math.hypot(vx, vy), m = (float) Math.hypot(st.dx, st.dy), min = THROW_MIN_DP * dens;
            if (sp < min) {
                float ux = m > 1f ? st.dx / m : 0f, uy = m > 1f ? st.dy / m : -1f;
                x[1] += ux * (min - sp);
                y[1] += uy * (min - sp);
            }
            layer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }

        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);   // solo como reloj cancelable por Kashi
        va.setDuration(4000);
        va.setInterpolator(null);
        final long[] last = {0L};
        final float[] clock = {0f, -1f};   // tiempo, momento del paso por detrás
        final boolean[] finished = {false};
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (finished[0]) return;       // end() vuelve a llamar aquí: evita la recursión
                long now = System.nanoTime();
                float dt = last[0] == 0L ? 1f / 120f : Math.min(1f / 30f, (now - last[0]) / 1e9f);
                last[0] = now;
                clock[0] += dt;
                springStep(x, tx, posResp, posDamp, dt);
                springStep(y, ty, posResp, posDamp, dt);
                springStep(sc, ts, SCALE_RESPONSE, SCALE_DAMPING, dt);
                springStep(rv, tr, REVEAL_RESPONSE, REVEAL_DAMPING, dt);
                st.dx = x[0];
                st.dy = y[0];
                st.scale = sc[0];
                st.reveal = rv[0];
                st.tilt = Math.max(-TILT_MAX, Math.min(TILT_MAX, x[1] / dens / 1000f * TILT_PER_1000DP));
                stretch(st, layer, 0f, 0f, 0f, x[1], y[1]);
                float q = 0f;
                if (commit) {
                    if (clock[1] < 0f) {
                        boolean returning = (x[1] * (tx - x[0]) + y[1] * (ty - y[0])) > 0f;
                        if ((clock[0] > SWAP_MIN_S && returning) || clock[0] > SWAP_MAX_S) clock[1] = clock[0];
                    }
                    if (clock[1] >= 0f) q = smooth(clamp01((clock[0] - clock[1]) / FADE_S));
                    st.frontPage = clock[1] >= 0f ? nb : cur;
                    st.frontZ = clock[1] >= 0f ? deepZ : 0f;
                }
                st.blur = endBlur * q;
                st.alpha = lerp(1f, endAlpha, q);
                fastFrame(layer);

                boolean settled = Math.abs(x[0] - tx) < 0.5f && Math.abs(y[0] - ty) < 0.5f
                        && Math.hypot(x[1], y[1]) < 20f && Math.abs(sc[0] - ts) < 0.002f
                        && Math.abs(rv[0] - tr) < 0.004f && Math.abs(rv[1]) < 0.05f && (!commit || q >= 1f);
                if (settled || clock[0] > 2.5f) {
                    finished[0] = true;
                    final ValueAnimator self = a;
                    layer.post(new Runnable() {
                        @Override
                        public void run() {
                            if (self.isRunning()) self.end();
                        }
                    });
                }
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            boolean done;

            @Override
            public void onAnimationCancel(Animator a) {
                end();
            }

            @Override
            public void onAnimationEnd(Animator a) {
                end();
            }

            private void end() {
                if (done) return;
                done = true;
                finished[0] = true;
                if (commit) settle(layer, pages, nb);
                reset(layer, cur, nb);
                render(layer, commit ? nb : cur, -1, 0f, false, 0f);
            }
        });
        try {
            fSettle.set(layer, va);
        } catch (Throwable ignored) {
        }
        va.start();
    }

    /** Muelle semi-implícito con subpasos de 1/240 s. s = {posición, velocidad}. */
    static void springStep(float[] s, float target, float response, float damping, float dt) {
        float k = (float) Math.pow(2 * Math.PI / response, 2);
        float c = 2f * damping * (float) Math.sqrt(k);
        int n = Math.max(1, (int) Math.ceil(dt * 240f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            s[1] += (-k * (s[0] - target) - c * s[1]) * h;
            s[0] += s[1] * h;
        }
    }

    private static void reset(View layer, int a, int b) {
        St st = st(layer);
        st.active = false;
        st.beyond = false;
        st.renderedOnce = false;
        st.dx = st.dy = st.tilt = st.blur = st.reveal = st.frontZ = 0f;
        st.scale = st.alpha = st.stx = st.sty = 1f;
        st.frontPage = -1;
        try {
            if (a >= 0) for (View v : viewsOf(layer, a)) set(v, 0f, 0f, 1f, 1f, 1f, 0f, 0f);
            if (b >= 0) for (View v : viewsOf(layer, b)) set(v, 0f, 0f, 1f, 1f, 1f, 0f, 0f);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void settle(View layer, List<Integer> pages, int target) {
        try {
            fCur.setInt(layer, target);
            mSave.invoke(null, fKey.getLong(layer), pages, target);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------- utilidades

    @SuppressWarnings("unchecked")
    static List<Integer> pages(View layer) throws Throwable {
        return (List<Integer>) fPages.get(layer);
    }

    @SuppressWarnings("unchecked")
    static List<View> viewsOf(View layer, int page) throws Throwable {
        return (List<View>) mViewsOf.invoke(layer, page);
    }

    private static boolean isBase(List<Integer> pages, int page) {
        Integer id = pages.get(page);
        return id != null && id == Integer.MIN_VALUE;
    }

    private static void render(View layer, int i, int i2, float f, boolean up, float f2) {
        try {
            mRender.invoke(layer, i, i2, f, up, f2);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    static float dp(View v, float dp) {
        return dp * v.getResources().getDisplayMetrics().density;
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static Method method(Class<?> c, String name, Class<?>... types) throws NoSuchMethodException {
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private StackSwipe() {
    }
}
