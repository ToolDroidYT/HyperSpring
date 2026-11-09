package com.icehome.up;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Recientes como cortinas rígidas: al deslizar las tarjetas, cada una se dobla hacia el lado
 * contrario a su movimiento, más cuanto más rápido va, y se endereza con un muelle duro (un
 * pequeño balanceo al parar). Las tarjetas ({@code TaskView}) son vistas del launcher: se
 * deforman con un shader AGSL ({@link RenderEffect}, Android 13+), sin tocar ventanas.
 *
 * Cada fotograma (Choreographer, hilo principal) se mide lo que se ha movido cada tarjeta en
 * pantalla, así vale para cualquier estilo de recientes (en pila como iOS, horizontal…): en la
 * pila cada tarjeta va a su velocidad y se dobla según la suya. Solo funciona mientras algo se
 * mueve; quietas, las tarjetas no llevan efecto (cero coste).
 */
final class RecentsCurtain {

    /**
     * Forma, según {@code mode}:
     * <ul>
     * <li>0, cortina: el centro de la tarjeta adelantado {@code bend} px respecto a arriba y
     *     abajo (una parábola).</li>
     * <li>1, telón: arriba fija y abajo cada vez más atrás (como un telón que se cierra rápido).</li>
     * </ul>
     * La tarjeta se estrecha |bend| para que nada se salga de sus límites (si no, el sistema
     * recortaría el doblez). Borde suave de un píxel.
     */
    static final String AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float bend;\n"
            + "uniform float mode;\n"
            + "half4 main(float2 p) {\n"
            + "  float w = size.x;\n"
            + "  float s = (w - abs(bend)) / w;\n"
            + "  float t = p.y / size.y * 2.0 - 1.0;\n"
            + "  float y = p.y / size.y;\n"
            + "  float prof = mix(1.0 - t * t, -pow(y, 2.4), mode);\n"
            + "  float off = bend * prof + mix(max(-bend, 0.0), max(bend, 0.0), mode);\n"
            + "  float x = (p.x - off) / s;\n"
            + "  float edge = clamp(min(x, w - x) * s + 0.5, 0.0, 1.0);\n"
            + "  if (edge <= 0.0) { return half4(0.0); }\n"
            + "  return content.eval(float2(clamp(x, 0.0, w), p.y)) * half(edge);\n"
            + "}\n";

    /** Doblez por velocidad (px de doblez por px/s) y máximo (fracción del ancho de la tarjeta). */
    private static final float GAIN = 0.02f, MAX = 0.13f;
    /** Muelle del doblez: duro y con un poco de balanceo, como una cortina rígida. */
    private static final float K = 380f, ZETA = 0.55f;
    /** Por debajo de esto (px) la tarjeta va sin efecto. */
    private static final float EPS = 0.3f;
    /** Tras el último desplazamiento, se sigue midiendo este tiempo (inercia, muelle). */
    private static final long LINGER_NS = 250_000_000L;

    static volatile float strength = 1f;
    /** 0 = cortina (se dobla el centro), 1 = telón (arriba fija, se dobla abajo). */
    static volatile float mode;

    private static Class<?> taskViewClass;
    private static final WeakHashMap<View, Card> cards = new WeakHashMap<>();
    private static ViewGroup stack;
    private static boolean running;
    private static long lastKickNs, lastFrameNs;

    /** Estado de una tarjeta (solo hilo principal). */
    private static final class Card {
        float x, v, b, bv;
        boolean init, effect;
        RuntimeShader shader;
    }

    static void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) {
            Log.i("cortina de recientes: necesita Android 13");
            return;
        }
        taskViewClass = XposedHelpers.findClass("com.miui.home.recents.views.TaskView", cl);
        Class<?> stackClass = XposedHelpers.findClass("com.miui.home.recents.views.TaskStackView", cl);
        XposedBridge.hookAllMethods(stackClass, "onStackScrollChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                kick((ViewGroup) p.thisObject);
            }
        });

        // TaskViews are recycled by the launcher. Never leave our RenderEffect on a detached
        // card, or it can reappear as a stale/ghost preview when that view is reused.
        XposedBridge.hookAllMethods(View.class, "onDetachedFromWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                if (taskViewClass.isInstance(v)) clearCard(v);
                if (v == stack) stopTracking();
            }
        });

        // The recents container can remain attached while the launcher animates it off-screen.
        // Clear effects as soon as it becomes hidden or transparent.
        XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                int visibility = (Integer) p.args[0];
                if (taskViewClass.isInstance(v) && visibility != View.VISIBLE) clearCard(v);
                if (v == stack && visibility != View.VISIBLE) stopTracking();
            }
        });
        XposedBridge.hookAllMethods(View.class, "setAlpha", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                float alpha = (Float) p.args[0];
                if (taskViewClass.isInstance(v) && alpha <= 0.01f) clearCard(v);
                if (v == stack && alpha <= 0.01f) stopTracking();
            }
        });
        XposedBridge.hookAllMethods(View.class, "onWindowVisibilityChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.thisObject == stack && (Integer) p.args[0] != View.VISIBLE) stopTracking();
            }
        });
        Log.i("cortina de recientes activa (limpieza al ocultar, reciclar o separar tarjetas)");
    }

    /** Las tarjetas se mueven: a medir cada fotograma hasta que paren y se enderecen. */
    private static void kick(ViewGroup s) {
        if (strength <= 0f) return;
        if (stack != s) {
            clearAll();
            stack = s;
        }
        lastKickNs = System.nanoTime();
        if (!running) {
            running = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(FRAME);
        }
    }

    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            ViewGroup s = stack;
            if (s == null || !s.isAttachedToWindow() || !s.isShown()) {
                stopTracking();
                return;
            }
            float dt = lastFrameNs == 0 ? 0f : Math.min(0.05f, (frameTimeNanos - lastFrameNs) / 1e9f);
            lastFrameNs = frameTimeNanos;
            boolean moving = false;
            try {
                for (int i = 0, n = s.getChildCount(); i < n; i++) {
                    View v = s.getChildAt(i);
                    if (!taskViewClass.isInstance(v) || v.getWidth() <= 0) continue;
                    if (step(v, dt)) moving = true;
                }
            } catch (Throwable t) {
                Log.e("cortina de recientes", t);
                clearAll();
                running = false;
                return;
            }
            if (moving || System.nanoTime() - lastKickNs < LINGER_NS) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                running = false;
            }
        }
    };

    /** Un fotograma de una tarjeta; true si aún se mueve o se está enderezando. */
    private static boolean step(View view, float dt) {
        Card c = cards.get(view);
        if (c == null) {
            c = new Card();
            cards.put(view, c);
        }
        float x = view.getX() + view.getWidth() * 0.5f;
        if (!c.init || dt <= 0f) {
            c.x = x;
            c.init = true;
            return false;
        }
        float vNow = (x - c.x) / dt;
        c.x = x;
        c.v += (vNow - c.v) * 0.5f;                            // los fotogramas no van parejos
        float max = view.getWidth() * MAX;
        float target = max * (float) Math.tanh(c.v * GAIN * strength / max);
        float damp = 2f * ZETA * (float) Math.sqrt(K);
        for (float left = dt; left > 0f; left -= 0.004f) {
            float h = Math.min(0.004f, left);
            c.bv += (K * (target - c.b) - damp * c.bv) * h;
            c.b += c.bv * h;
        }
        boolean active = Math.abs(c.b) > EPS || Math.abs(c.bv) > 5f;
        if (active) {
            if (c.shader == null) c.shader = new RuntimeShader(AGSL);
            c.shader.setFloatUniform("size", view.getWidth(), view.getHeight());
            c.shader.setFloatUniform("bend", c.b);
            c.shader.setFloatUniform("mode", mode);
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(c.shader, "content"));
            c.effect = true;
        } else if (c.effect) {
            view.setRenderEffect(null);
            c.effect = false;
            c.b = c.bv = 0f;
        }
        return active || Math.abs(c.v) > 20f;
    }

    private static void clearAll() {
        for (java.util.Map.Entry<View, Card> e : cards.entrySet()) {
            if (e.getValue().effect && e.getKey() != null) e.getKey().setRenderEffect(null);
        }
        cards.clear();
        stack = null;
    }

    /** Remove our effect from a recycled or hidden task thumbnail immediately. */
    private static void clearCard(View view) {
        Card card = cards.remove(view);
        if (card != null && card.effect) {
            try {
                view.setRenderEffect(null);
            } catch (Throwable t) {
                Log.e("cortina de recientes: limpiar tarjeta", t);
            }
        }
    }

    /** Stop callbacks and restore every card when the recents container leaves the screen. */
    private static void stopTracking() {
        clearAll();
        running = false;
        lastFrameNs = 0;
        lastKickNs = 0;
    }


    private RecentsCurtain() {
    }
}
