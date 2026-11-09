package com.icehome.up;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.Choreographer;
import android.view.MotionEvent;
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
            + "uniform float amount;\n"
            + "half4 main(float2 p) {\n"
            + "  float w = size.x;\n"
            + "  float s = (w - abs(bend)) / w;\n"
            + "  float t = p.y / size.y * 2.0 - 1.0;\n"
            + "  float y = p.y / size.y;\n"
            + "  float prof = mix(1.0 - t * t, -pow(y, 2.4), mode);\n"
            + "  float off = bend * prof + mix(max(-bend, 0.0), max(bend, 0.0), mode);\n"
            + "  float x = (p.x - off) / s;\n"
            + "  float edge = clamp(min(x, w - x) * s + 0.5, 0.0, 1.0);\n"
            + "  half4 normal = content.eval(p);\n"
            + "  half4 bent = edge <= 0.0 ? half4(0.0) : content.eval(float2(clamp(x, 0.0, w), p.y)) * half(edge);\n"
            + "  return mix(normal, bent, half(clamp(amount, 0.0, 1.0)));\n"
            + "}\n";

    /** Doblez por velocidad (px de doblez por px/s) y máximo (fracción del ancho de la tarjeta). */
    private static final float GAIN = 0.014f, MAX = 0.10f;
    /** Muelle del doblez: duro y con un poco de balanceo, como una cortina rígida. */
    private static final float K = 150f, ZETA = 0.88f;
    /** Por debajo de esto (px) la tarjeta va sin efecto. */
    private static final float EPS = 0.04f;
    /** Blend visual del shader; entrada rápida y salida más larga para evitar un corte. */
    private static final float BLEND_IN_S = 0.075f, BLEND_OUT_S = 0.15f;
    /** Movimiento mínimo que cuenta como arrastre mientras el dedo sigue pulsado. */
    private static final float HOLD_MOTION_PX_S = 18f;
    /** Mantener seguimiento el tiempo suficiente para que la tarjeta termine su asentamiento. */
    private static final long LINGER_NS = 350_000_000L;

    static volatile float strength = 1f;
    /** 0 = cortina (se dobla el centro), 1 = telón (arriba fija, se dobla abajo). */
    static volatile float mode;

    private static Class<?> taskViewClass;
    private static final WeakHashMap<View, Card> cards = new WeakHashMap<>();
    private static ViewGroup stack;
    private static ViewGroup observedStack;
    private static View pressedCard;
    private static boolean running;
    private static long lastKickNs, lastFrameNs;

    private static final View.OnAttachStateChangeListener STACK_ATTACH = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}

        @Override public void onViewDetachedFromWindow(View v) {
            if (v == observedStack) stopTracking();
        }
    };

    /** Estado de una tarjeta (solo hilo principal). */
    private static final class Card {
        float x, v, b, bv, blend, holdTarget;
        boolean init, effect, pressed;
        RuntimeShader shader;
        View.OnAttachStateChangeListener attachListener;
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

        // Observe the gesture at the stack boundary without replacing MIUI touch handling.
        // ViewGroup.dispatchTouchEvent is inherited by TaskStackView on the analyzed launcher.
        XposedBridge.hookAllMethods(ViewGroup.class, "dispatchTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!stackClass.isInstance(p.thisObject)) return;
                try {
                    onStackTouch((ViewGroup) p.thisObject, (MotionEvent) p.args[0]);
                } catch (Throwable t) {
                    Log.e("cortina de recientes: gesto", t);
                }
            }
        });

        // Cover launchers that override dispatchTouchEvent directly.
        XposedBridge.hookAllMethods(stackClass, "dispatchTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    onStackTouch((ViewGroup) p.thisObject, (MotionEvent) p.args[0]);
                } catch (Throwable t) {
                    Log.e("cortina de recientes: gesto", t);
                }
            }
        });

        Log.i("cortina de recientes activa (gesto retenido, salida suave y limpieza al ocultar o reciclar)");
    }

    /** Las tarjetas se mueven: a medir cada fotograma hasta que paren y se enderecen. */
    private static void kick(ViewGroup s) {
        if (strength <= 0f) return;
        if (stack != s) {
            stopTracking();
            stack = s;
            observedStack = s;
            s.addOnAttachStateChangeListener(STACK_ATTACH);
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
                stopTracking();
                return;
            }
            if (moving || System.nanoTime() - lastKickNs < LINGER_NS) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                running = false;
            }
        }
    };

    /** Obtain per-card state and register cleanup for recycled TaskViews. */
    private static Card cardFor(View view) {
        Card c = cards.get(view);
        if (c != null) return c;
        c = new Card();
        final View cardView = view;
        c.attachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}

            @Override public void onViewDetachedFromWindow(View v) {
                clearCard(cardView);
            }
        };
        cards.put(view, c);
        view.addOnAttachStateChangeListener(c.attachListener);
        return c;
    }

    /** Hit-test nested TaskViews without intercepting or consuming the launcher gesture. */
    private static View findTaskViewAt(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            float localX = x - child.getX();
            float localY = y - child.getY();
            if (localX < 0f || localY < 0f || localX > child.getWidth() || localY > child.getHeight()) continue;
            if (taskViewClass.isInstance(child)) return child;
            if (child instanceof ViewGroup) {
                View found = findTaskViewAt((ViewGroup) child, localX, localY);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void releasePressedCard() {
        View old = pressedCard;
        pressedCard = null;
        if (old == null) return;
        Card c = cards.get(old);
        if (c != null) {
            c.pressed = false;
            c.holdTarget = 0f;
        }
    }

    /** Track the pressed TaskView so its bend does not collapse while the finger is stationary. */
    private static void onStackTouch(ViewGroup s, MotionEvent event) {
        if (event == null) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                releasePressedCard();
                View hit = findTaskViewAt(s, event.getX(), event.getY());
                if (hit != null) {
                    Card c = cardFor(hit);
                    c.pressed = true;
                    c.holdTarget = c.b;
                    pressedCard = hit;
                    kick(s);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (pressedCard != null && cards.containsKey(pressedCard)) {
                    lastKickNs = System.nanoTime();
                    if (!running) kick(s);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressedCard != null) {
                    releasePressedCard();
                    kick(s); // keep the frame loop alive for the release spring and card settling
                }
                break;
            default:
                break;
        }
    }

    /** Un mapa por tarjeta; true mientras se mueve, se mantiene pulsada o se endereza. */
    private static boolean step(View view, float dt) {
        Card c = cardFor(view);
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
        float velocityTarget = max * (float) Math.tanh(c.v * GAIN * strength / max);
        float target = velocityTarget;
        if (c.pressed) {
            // Update the held bend only while the card is genuinely moving. When the finger
            // pauses, keep the last target instead of springing abruptly back to a rectangle.
            if (Math.abs(vNow) >= HOLD_MOTION_PX_S) c.holdTarget = velocityTarget;
            target = c.holdTarget;
        } else {
            c.holdTarget = 0f;
        }
        float damp = 2f * ZETA * (float) Math.sqrt(K);
        for (float left = dt; left > 0f; left -= 0.004f) {
            float h = Math.min(0.004f, left);
            c.bv += (K * (target - c.b) - damp * c.bv) * h;
            c.b += c.bv * h;
        }
        boolean bending = Math.abs(c.b) > EPS || Math.abs(c.bv) > 0.5f
                || Math.abs(target) > EPS || (c.pressed && Math.abs(c.holdTarget) > EPS);
        float blendTarget = bending ? 1f : 0f;
        float blendTime = blendTarget > c.blend ? BLEND_IN_S : BLEND_OUT_S;
        float blendStep = 1f - (float) Math.exp(-dt / blendTime);
        c.blend += (blendTarget - c.blend) * blendStep;
        c.blend = Math.max(0f, Math.min(1f, c.blend));

        boolean keepEffect = c.blend > 0.002f || Math.abs(c.b) > EPS || Math.abs(c.bv) > 0.5f;
        if (keepEffect) {
            if (c.shader == null) c.shader = new RuntimeShader(AGSL);
            c.shader.setFloatUniform("size", view.getWidth(), view.getHeight());
            c.shader.setFloatUniform("bend", c.b);
            c.shader.setFloatUniform("mode", mode);
            c.shader.setFloatUniform("amount", c.blend);
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(c.shader, "content"));
            c.effect = true;
        } else if (c.effect) {
            view.setRenderEffect(null);
            c.effect = false;
            c.b = c.bv = c.blend = 0f;
            view.invalidate();
            if (view.getParent() instanceof View) ((View) view.getParent()).invalidate();
        }
        return c.pressed || keepEffect || Math.abs(c.v) > 1.5f;
    }

    /** Remove our effect from a recycled or hidden task thumbnail immediately. */
    private static void clearCard(View view) {
        Card card = cards.remove(view);
        if (card == null) return;
        if (pressedCard == view) pressedCard = null;
        card.pressed = false;
        if (card.attachListener != null) {
            view.removeOnAttachStateChangeListener(card.attachListener);
            card.attachListener = null;
        }
        if (card.effect) {
            try {
                view.setRenderEffect(null);
                view.invalidate();
                if (view.getParent() instanceof View) ((View) view.getParent()).invalidate();
            } catch (Throwable t) {
                Log.e("cortina de recientes: limpiar tarjeta", t);
            }
            card.effect = false;
        }
    }

    private static void clearAll() {
        for (java.util.Map.Entry<View, Card> e : new java.util.ArrayList<>(cards.entrySet())) {
            View view = e.getKey();
            Card card = e.getValue();
            if (view == null || card == null) continue;
            if (card.attachListener != null) {
                view.removeOnAttachStateChangeListener(card.attachListener);
                card.attachListener = null;
            }
            card.pressed = false;
            if (card.effect) {
                try {
                    view.setRenderEffect(null);
                    view.invalidate();
                    if (view.getParent() instanceof View) ((View) view.getParent()).invalidate();
                } catch (Throwable t) {
                    Log.e("cortina de recientes: limpiar tarjeta", t);
                }
                card.effect = false;
            }
        }
        pressedCard = null;
        cards.clear();
        stack = null;
    }

    /** Stop callbacks and restore cards when recents is detached or no longer visible. */
    private static void stopTracking() {
        ViewGroup old = observedStack;
        observedStack = null;
        if (old != null) old.removeOnAttachStateChangeListener(STACK_ATTACH);
        clearAll();
        running = false;
        pressedCard = null;
        lastFrameNs = 0;
        lastKickNs = 0;
    }

    private RecentsCurtain() {
    }
}
