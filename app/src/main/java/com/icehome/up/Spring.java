package com.icehome.up;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

/**
 * Muelle amortiguado analítico (como Folme/HyperOS: "response" + "damping").
 *
 * Se usa como interpolador de un ValueAnimator normal, de modo que el animador
 * sigue siendo cancelable desde el touch de Kashi (campo Layer.settle) y respeta
 * la escala de animación del sistema.
 *
 * Solución de x'' = -k·x - c·x' con x(0)=1 (desplazamiento normalizado) y la
 * velocidad inicial del dedo convertida a esas mismas unidades.
 */
final class Spring implements TimeInterpolator {

    private final double zw;     // ζ·ω0
    private final double wd;     // ω amortiguada
    private final double b;      // coeficiente del seno
    private final double durSec;

    private Spring(double zw, double wd, double b, double durSec) {
        this.zw = zw;
        this.wd = wd;
        this.b = b;
        this.durSec = durSec;
    }

    @Override
    public float getInterpolation(float input) {
        double t = input * durSec;
        double n = Math.exp(-zw * t) * (Math.cos(wd * t) + b * Math.sin(wd * t));
        return (float) (1.0 - n);
    }

    /**
     * Configura {@code va} (ya creado con ofFloat(start, end)) para que se mueva
     * como un muelle.
     *
     * @param velocity velocidad inicial en unidades del valor por segundo
     * @param response periodo del muelle en segundos (menor = más rápido)
     * @param damping  ratio de amortiguamiento (1 = sin rebote, &lt;1 = rebote)
     */
    static void apply(ValueAnimator va, float start, float end, float velocity,
                      float response, float damping) {
        double delta = end - start;
        if (Math.abs(delta) < 1e-4) {
            va.setDuration(1);
            return;
        }
        double zeta = Math.max(0.05, Math.min(0.99, damping));
        double w0 = 2 * Math.PI / Math.max(0.05, response);
        double wd = w0 * Math.sqrt(1 - zeta * zeta);
        double zw = zeta * w0;
        // n'(0) = -v0 / delta  (n = desplazamiento normalizado restante)
        double n1 = -velocity / delta;
        double b = (n1 + zw) / wd;
        double amp = Math.sqrt(1 + b * b);
        double settle = Math.log(amp / 0.002) / zw;   // hasta 0,2 % del recorrido
        settle = Math.max(0.16, Math.min(0.95, settle));
        va.setInterpolator(new Spring(zw, wd, b, settle));
        va.setDuration((long) (settle * 1000));
    }
}
