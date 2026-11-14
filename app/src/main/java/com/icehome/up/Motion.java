package com.icehome.up;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * Piezas de movimiento que comparten la app y la presentación.
 * <p>
 * Todo pasa por animadores normales de Android, así que respeta la «escala de animación» de
 * opciones de desarrollador: con las animaciones quitadas, cada efecto salta a su estado final.
 */
final class Motion {

    /** Salida exponencial: arranca rápido y se posa muy suave. Es la curva típica de las webs modernas. */
    static final PathInterpolator EXPO_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    /** La curva estándar de la app. */
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * Color de la marca: el coral del icono en tres luces (claro, normal y profundo). Es un solo
     * color que cambia de luz, no tres colores distintos: el típico degradado coral-rosa-morado
     * queda genérico. Lo usan el botón principal, las baldosas encendidas y las olas.
     */
    static final int[] BRAND = {0xFFFF8A70, 0xFFF0604D, 0xFFDC4E3E};

    /** Cápsula con el degradado de la marca (fondo del botón principal). */
    static android.graphics.drawable.GradientDrawable brandPill(float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, BRAND);
        g.setCornerRadius(radius);
        return g;
    }

    /** Marca para {@link #cascade}: las filas de esta tarjeta entran una a una. */
    static final String CASCADE = "cascade";

    /**
     * Muelle normalizado: cuánto ha recorrido (de 0 a 1) a los {@code t} segundos.
     *
     * @param response periodo en segundos (menor = más rápido)
     * @param damping  1 = llega sin pasarse; menos de 1 = rebota
     */
    static float spring(float t, float response, float damping) {
        if (t <= 0f) return 0f;
        double z = Math.max(0.05, Math.min(0.999, damping));
        double w0 = 2 * Math.PI / Math.max(0.05, response);
        double wd = w0 * Math.sqrt(1 - z * z), zw = z * w0;
        return (float) (1 - Math.exp(-zw * t) * (Math.cos(wd * t) + zw / wd * Math.sin(wd * t)));
    }

    /** Cuánto se ha avanzado de {@code a} a {@code b} (0 antes, 1 después). */
    static float seg(float t, float a, float b) {
        return Math.max(0f, Math.min(1f, (t - a) / (b - a)));
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Hace aparecer una vista subiendo un poco. Usa un ObjectAnimator propio (no {@code v.animate()})
     * para no dejarle un retraso pegado al animador de la vista, que luego retrasaría el hundimiento
     * al pulsar.
     */
    static void reveal(View v, long delay, float dy) {
        v.setAlpha(0f);
        v.setTranslationY(dy);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy, 0f));
        a.setStartDelay(delay);
        a.setDuration(640);
        a.setInterpolator(EXPO_OUT);
        a.start();
    }

    /**
     * Entrada escalonada de una columna: cada hijo aparece un poco después que el anterior. Las
     * tarjetas marcadas con {@link #CASCADE} entran y, justo detrás, sus filas una a una.
     *
     * @return el retraso al que ha llegado (para encadenar más cosas detrás)
     */
    static long cascade(ViewGroup col, long delay, float dy) {
        long d = delay;
        for (int i = 0; i < col.getChildCount(); i++) {
            View c = col.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            reveal(c, d, dy);
            if (CASCADE.equals(c.getTag()) && c instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) c;
                for (int j = 0; j < g.getChildCount(); j++) reveal(g.getChildAt(j), d + 70 + j * 38L, dy * 0.5f);
                d += Math.min(g.getChildCount(), 8) * 30L;
            }
            d += 55;
            if (d - delay > 700) d = delay + 700;          // en páginas largas, que no se haga eterno
        }
        return d;
    }

    private Motion() {
    }
}
