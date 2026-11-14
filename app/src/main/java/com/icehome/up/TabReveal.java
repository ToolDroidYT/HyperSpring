package com.icehome.up;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;

import java.util.List;

/**
 * Cambio de pestaña con una ola de cristal líquido. La página nueva nace en círculo del botón
 * tocado y crece; su borde es como el canto de un cristal grueso (curva la imagen y brilla).
 * Delante de la ola, la página vieja se ve empujada hacia fuera, como agua apartada, y a la vez
 * se hunde (se aleja, se apaga y se desenfoca).
 * <p>
 * El círculo es el contorno (outline) de la página con recorte: lo hace la GPU. Las dos
 * deformaciones son sombreadores AGSL (Android 13+); antes, solo el círculo y el anillo de luz.
 */
final class TabReveal {

    /**
     * Canto de cristal de la página nueva: en la franja de {@code width} px junto al borde, la
     * imagen se curva hacia dentro (se toma de más cerca del centro, como al mirar por el borde
     * de una lupa) y un brillo blanco marca la arista.
     */
    static final String RIM_SHADER =
            "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float radius;\n"
            + "uniform float width;\n"
            + "uniform float amount;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 d = p - center;\n"
            + "  float dist = length(d);\n"
            + "  float e = radius - dist;\n"
            + "  if (e < 0.0 || e > width) return content.eval(p);\n"
            + "  float2 n = d / max(dist, 1.0);\n"
            + "  float k = 1.0 - e / width;\n"
            + "  half4 c = content.eval(p - n * amount * k * k);\n"
            + "  float shine = 0.22 * k * k * k;\n"
            + "  c.rgb = min(c.rgb + half3(shine) * c.a, c.aaa);\n"
            + "  return c;\n"
            + "}\n";

    /**
     * Empuje de la página vieja: justo delante de la ola, cada píxel enseña lo que había un poco
     * más cerca del centro, así el contenido parece apartado hacia fuera. El empuje se apaga con
     * la distancia al frente.
     */
    static final String PUSH_SHADER =
            "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float radius;\n"
            + "uniform float width;\n"
            + "uniform float amount;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 d = p - center;\n"
            + "  float dist = length(d);\n"
            + "  float x = (dist - radius) / width;\n"
            + "  if (x < -1.0 || x > 5.0) return content.eval(p);\n"
            + "  float2 n = d / max(dist, 1.0);\n"
            + "  float push = amount * exp(-0.9 * max(x, 0.0));\n"
            + "  return content.eval(p - n * push);\n"
            + "}\n";

    /** Suave al salir y al llegar, para que el frente de la ola se vea cruzar la pantalla. */
    private static final PathInterpolator WAVE = new PathInterpolator(0.4f, 0.1f, 0.15f, 1f);
    private static ValueAnimator running;

    /** Si hay un cambio a medias (tocaste otra pestaña muy rápido), se termina al instante. */
    static void finish() {
        if (running != null) running.end();
    }

    private static RuntimeShader shader(String src, float cx, float cy, float width) {
        if (Build.VERSION.SDK_INT < 33) return null;
        try {
            RuntimeShader s = new RuntimeShader(src);
            s.setFloatUniform("center", cx, cy);
            s.setFloatUniform("width", width);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @param root  contenedor de todo; el anillo se pone justo encima de {@code stack}
     * @param cx    centro del botón tocado, en coordenadas de {@code root}
     * @param done  quita las páginas viejas cuando acaba
     */
    static void play(final ViewGroup root, View stack, final List<? extends View> old, final View page,
                     final float cx, final float cy, final Runnable done) {
        final float dp = root.getResources().getDisplayMetrics().density;
        float w = root.getWidth(), h = root.getHeight();
        final float maxR = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy)) + 40f * dp;
        final float startR = 26f * dp;
        final float[] r = {startR};
        final RuntimeShader rim = shader(RIM_SHADER, cx, cy, 34f * dp);
        final RuntimeShader push = shader(PUSH_SHADER, cx, cy, 40f * dp);

        // La página nueva solo se ve dentro del círculo.
        page.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                float rr = r[0];
                o.setRoundRect(Math.round(cx - rr), Math.round(cy - rr), Math.round(cx + rr), Math.round(cy + rr), rr);
            }
        });
        page.setClipToOutline(true);
        // Las dos páginas giran en torno al botón: la nueva crece desde él, la vieja se va hacia él.
        page.setPivotX(cx);
        page.setPivotY(cy);
        for (View o : old) {
            o.setPivotX(cx);
            o.setPivotY(cy);
        }

        final Ring ring = new Ring(root.getContext(), cx, cy, dp);
        root.addView(ring, root.indexOfChild(stack) + 1, new ViewGroup.LayoutParams(-1, -1));

        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(900);
        a.setInterpolator(WAVE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float t = (Float) va.getAnimatedValue();
                r[0] = startR + (maxR - startR) * t;
                page.invalidateOutline();
                float s = 1.04f - 0.04f * t;                     // la nueva llega un poco de cerca
                page.setScaleX(s);
                page.setScaleY(s);
                ring.set(r[0], t);
                // Fuerza de la ola: plena casi todo el recorrido y se calma al final.
                float force = 1f - t * t * t;
                if (rim != null) {
                    rim.setFloatUniform("radius", r[0]);
                    rim.setFloatUniform("amount", 26f * dp * force);
                    page.setRenderEffect(RenderEffect.createRuntimeShaderEffect(rim, "content"));
                }
                for (View o : old) {
                    float k = 1f - 0.08f * t;
                    o.setScaleX(k);
                    o.setScaleY(k);
                    o.setAlpha(1f - 0.55f * t);
                    if (Build.VERSION.SDK_INT >= 31) {
                        RenderEffect e = null;
                        float b = 10f * dp * t;
                        if (b >= 0.5f) e = RenderEffect.createBlurEffect(b, b, Shader.TileMode.CLAMP);
                        if (push != null) {
                            // El radio de la ola en las coordenadas de la página vieja (que se encoge).
                            push.setFloatUniform("radius", r[0] / k);
                            push.setFloatUniform("amount", 48f * dp * force);
                            RenderEffect pe = RenderEffect.createRuntimeShaderEffect(push, "content");
                            e = e == null ? pe : RenderEffect.createChainEffect(e, pe);   // empuje y luego desenfoque
                        }
                        o.setRenderEffect(e);
                    }
                }
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                if (running == a) running = null;
                root.removeView(ring);
                if (Build.VERSION.SDK_INT >= 31) page.setRenderEffect(null);
                page.setClipToOutline(false);
                page.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                page.setScaleX(1f);
                page.setScaleY(1f);
                for (View o : old) {
                    if (Build.VERSION.SDK_INT >= 31) o.setRenderEffect(null);
                    o.setScaleX(1f);
                    o.setScaleY(1f);
                    o.setAlpha(1f);
                }
                done.run();
            }
        });
        running = a;
        a.start();
    }

    /** El anillo de luz en el frente de la ola: un halo ancho y suave y una línea fina encima. */
    private static final class Ring extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix spin = new Matrix();
        private final SweepGradient sweep;
        private final float cx, cy;
        private float r, t;

        Ring(Context c, float cx, float cy, float dp) {
            super(c);
            this.cx = cx;
            this.cy = cy;
            int[] b = Motion.BRAND;
            sweep = new SweepGradient(cx, cy, new int[]{b[0], b[1], b[2], b[1], b[0]}, null);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(16f * dp);
            glow.setShader(sweep);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f * dp);
            line.setShader(sweep);
        }

        void set(float r, float t) {
            this.r = r;
            this.t = t;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (r <= 0f || t >= 1f) return;
            // Los colores dan media vuelta mientras la onda crece: el borde parece líquido.
            spin.setRotate(180f * t, cx, cy);
            sweep.setLocalMatrix(spin);
            float fade = 1f - t;
            glow.setAlpha(Math.round(55 * fade));
            line.setAlpha(Math.round(200 * fade));
            c.drawCircle(cx, cy, r, glow);
            c.drawCircle(cx, cy, r, line);
        }
    }

    private TabReveal() {
    }
}
