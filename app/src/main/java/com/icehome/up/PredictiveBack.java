package com.icehome.up;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * El «atrás predictivo» de la app (Android 14 o más): mientras deslizas desde el borde, la página
 * de arriba sigue al dedo, se encoge y se le redondean las esquinas, y la de debajo se va acercando.
 * Al soltar, la página se va con un muelle; si te arrepientes, vuelve a su sitio con otro.
 * <p>
 * Antes el gesto no enseñaba nada hasta soltar y la página salía con una animación fija: se notaba
 * «seco». Aquí todo depende del dedo, y al soltar se parte de donde estaba (no salta).
 * <p>
 * Esta clase solo mueve vistas; quién va arriba y qué pasa al terminar lo decide la actividad.
 */
final class PredictiveBack {

    /** Lo que pasa al terminar la salida (quitar la página, cambiar de pestaña...). */
    interface Done {
        void run();
    }

    private final View top;      // la página que se va
    private final View under;    // la que aparece debajo (puede ser null)
    private final float dp;
    private final boolean fromLeft;
    private float progress;      // 0..1, ya suavizado
    private float lift;          // cuánto ha subido o bajado el dedo, en px
    private float startY = Float.NaN;
    private float radius;
    private ValueAnimator anim;
    // Cómo estaba la de debajo al empezar: la recorremos desde ahí hasta su sitio.
    private final float underX0, underA0, underS0;

    PredictiveBack(View top, View under, boolean fromLeft, float dp) {
        this.top = top;
        this.under = under;
        this.fromLeft = fromLeft;
        this.dp = dp;
        if (under != null) {
            under.animate().cancel();
            under.setVisibility(View.VISIBLE);
            underX0 = under.getTranslationX();
            underA0 = under.getAlpha();
            underS0 = under.getScaleX();
        } else {
            underX0 = 0f;
            underA0 = 1f;
            underS0 = 1f;
        }
        top.animate().cancel();
        // Esquinas redondeadas que crecen con el gesto, y algo de sombra para separarla del fondo.
        top.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        top.setClipToOutline(true);
    }

    /** Llamar en cada movimiento del dedo. {@code raw} viene de 0 a 1 tal cual lo da el sistema. */
    void update(float raw, float touchY) {
        if (Float.isNaN(startY)) startY = touchY;
        // Desaceleración: al principio responde mucho y luego cuesta más, como si tirase de una goma.
        float p = 1f - (1f - raw) * (1f - raw);
        progress = p;
        // El dedo arriba o abajo inclina un poco la página hacia ese lado (con tope, para que no baile).
        float dy = touchY - startY;
        float max = 40 * dp;
        lift = max * (float) Math.tanh(dy / (max * 3f));
        apply(p, 0f);
    }

    /**
     * Pinta el estado: {@code p} es el arrastre (0..1) y {@code exit} la salida al soltar (0..1).
     * Con exit = 0 es el gesto; con exit = 1, la página ya está fuera.
     */
    private void apply(float p, float exit) {
        float w = top.getWidth();
        float dir = fromLeft ? 1f : -1f;
        // Durante el gesto: se encoge hasta el 88 %, se desplaza un poco hacia el lado contrario al
        // borde y se le redondean las esquinas (hasta 32 dp, casi como una tarjeta de recientes).
        float s = 1f - 0.12f * p;
        float tx = dir * (w * 0.06f * p);
        radius = 32 * dp * Math.min(1f, p * 1.6f);
        // Al soltar: continúa hacia ese lado hasta salir y se desvanece al final.
        tx = Motion.lerp(tx, dir * w * 1.05f, exit);
        s = Motion.lerp(s, s * 0.94f, exit);
        top.setTranslationX(tx);
        top.setTranslationY(lift * (1f - exit));
        top.setScaleX(s);
        top.setScaleY(s);
        top.setAlpha(1f - Math.max(0f, exit - 0.55f) / 0.45f);
        top.setElevation(18 * dp * Math.min(1f, p * 2f) * (1f - exit));
        top.invalidateOutline();
        if (under != null) {
            // La de debajo viene desde donde estaba (a un lado, pequeña y transparente) hacia su sitio.
            // El gesto la acerca hasta la mitad; la salida termina el recorrido.
            float u = Motion.lerp(0.45f * p, 1f, exit);
            under.setTranslationX(Motion.lerp(underX0, 0f, u));
            under.setAlpha(Motion.lerp(underA0, 1f, Math.min(1f, u * 1.4f)));
            float us = Motion.lerp(underS0, 1f, u);
            under.setScaleX(us);
            under.setScaleY(us);
        }
    }

    /** Al soltar: la página se va. {@code done} se ejecuta cuando ya no se ve. */
    void commit(final Done done) {
        stop();
        final float p = progress;
        anim = ValueAnimator.ofFloat(0f, 1f);
        // Muelle sin rebote (la página no debe volver), algo rápido para que se sienta decidido.
        Spring.apply(anim, 0f, 1f, 2.5f, 0.42f, 0.95f);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                apply(p, (Float) va.getAnimatedValue());
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                reset(top);
                if (done != null) done.run();
            }
        });
        anim.start();
    }

    /** Te arrepientes: todo vuelve a su sitio con un muelle que rebota un pelín. */
    void cancel() {
        stop();
        final float p0 = progress, l0 = lift;
        anim = ValueAnimator.ofFloat(1f, 0f);
        Spring.apply(anim, 1f, 0f, 0f, 0.38f, 0.72f);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float k = (Float) va.getAnimatedValue();
                lift = l0 * k;
                // El rebote puede llevar k un poco por debajo de 0: la página «se pasa» y crece
                // un instante por encima del 100 %. Es lo que la hace sentir elástica.
                apply(p0 * k, 0f);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                reset(top);
                if (under != null) {
                    under.setTranslationX(underX0);
                    under.setAlpha(underA0);
                    under.setScaleX(underS0);
                    under.setScaleY(underS0);
                }
            }
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) anim.cancel();
    }

    /** Deja la página como estaba antes del gesto. */
    private static void reset(View v) {
        v.setClipToOutline(false);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        v.setElevation(0f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
    }
}
