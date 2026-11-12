package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * El logo de la presentación, que se construye solo:
 * <ol>
 *   <li>Cae una gota coral desde arriba; se estira con la velocidad y rebota al llegar.</li>
 *   <li>La gota se convierte en el cuadrado redondeado del icono.</li>
 *   <li>Crecen las dos patas de la H y se dibuja el muelle entre ellas.</li>
 *   <li>Sale un aro de luz y un brillo cruza el icono.</li>
 * </ol>
 * Después {@link #flyTo} lo lleva con un muelle a su sitio en la pantalla de bienvenida.
 * <p>
 * Ocupa toda la pantalla (la gota cae desde fuera del hueco del logo). Se dibuja en la rejilla de
 * 512 del icono ({@link LogoDrawable}) escalando el lienzo, así las formas se crean una sola vez.
 */
final class IntroLogo extends View {

    /** Duración de la construcción, en ms. */
    static final int BUILD_MS = 1900;

    private final float dp;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG),
            coil = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path coilPath = new Path(), coilPart = new Path(), clip = new Path();
    private final PathMeasure coilMeasure;
    private final float coilLen;

    /** Milisegundos de la construcción (BUILD_MS = terminado). */
    private float t;
    /** Centro y tamaño del logo cuando está en reposo (antes y después de volar). */
    private float cx = -1, cy, size;
    private ValueAnimator clock, fly;

    IntroLogo(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        // Degradado del cuerpo en coordenadas de la rejilla: melocotón arriba a la izquierda, coral abajo.
        body.setShader(new LinearGradient(0, 0, 512, 512, 0xFFFF8A66, 0xFFE8495A, Shader.TileMode.CLAMP));
        // Sombra de color debajo, como si el icono flotara sobre el brillo del fondo.
        body.setShadowLayer(26 * dp, 0, 12 * dp, 0x66F0604D);
        white.setColor(0xFFFFFFFF);
        coil.setColor(0xFFFFFFFF);
        coil.setStyle(Paint.Style.STROKE);
        coil.setStrokeCap(Paint.Cap.ROUND);
        coil.setStrokeJoin(Paint.Join.ROUND);
        coil.setStrokeWidth(22);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        float[] pts = {196, 256, 208, 224, 228, 288, 248, 224, 268, 288, 288, 224, 304, 256, 316, 256};
        coilPath.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) coilPath.lineTo(pts[i], pts[i + 1]);
        coilMeasure = new PathMeasure(coilPath, false);
        coilLen = coilMeasure.getLength();
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /** Empieza la construcción en el centro de la vista, con el tamaño dado (en px). */
    void play(float sizePx) {
        size = sizePx;
        cx = -1;                                             // se coloca en el centro al dibujar
        t = 0;
        if (clock != null) clock.cancel();
        clock = ValueAnimator.ofFloat(0f, BUILD_MS);
        clock.setDuration(BUILD_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                t = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        clock.start();
    }

    /** Pone el logo ya construido en un sitio, sin animar. */
    void place(float x, float y, float sizePx) {
        if (clock != null) clock.cancel();
        if (fly != null) fly.cancel();
        t = BUILD_MS;
        cx = x;
        cy = y;
        size = sizePx;
        invalidate();
    }

    /** Lleva el logo con un muelle a su sitio (centro x, y y tamaño, en px de esta vista). */
    void flyTo(final float x, final float y, final float sizePx) {
        if (cx < 0) {
            cx = getWidth() / 2f;
            cy = getHeight() / 2f;
        }
        final float x0 = cx, y0 = cy, s0 = size;
        if (fly != null) fly.cancel();
        fly = ValueAnimator.ofFloat(0f, 1f);
        Spring.apply(fly, 0f, 1f, 0f, 0.62f, 0.74f);
        fly.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                cx = Motion.lerp(x0, x, f);
                cy = Motion.lerp(y0, y, f);
                size = Motion.lerp(s0, sizePx, f);
                invalidate();
            }
        });
        fly.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        if (fly != null) fly.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (size <= 0) return;
        if (cx < 0) {
            cx = getWidth() / 2f;
            cy = getHeight() / 2f;
        }
        float sec = t / 1000f;

        // 1. Caída: de encima de la pantalla al centro con un muelle que rebota.
        float fall = Motion.spring(sec, 0.56f, 0.6f);
        float startY = -size;
        float y = Motion.lerp(startY, cy, fall);
        // Velocidad (px por ms) mirando un poco atrás: con ella se estira la gota.
        float prev = Motion.lerp(startY, cy, Motion.spring(sec - 0.012f, 0.56f, 0.6f));
        float v = Math.abs(y - prev) / 12f;
        float stretch = t >= BUILD_MS ? 1f : 1f + Math.min(0.42f, v * 0.11f);

        // 2. De gota a icono: crece y las esquinas pasan de círculo al redondeo del icono.
        float morph = Motion.spring(sec - 0.42f, 0.5f, 0.68f);
        float ball = 0.58f;                                 // la gota mide el 58 % del icono
        float k = Motion.lerp(ball, 1f, morph);             // tamaño relativo (con algo de rebote)
        float corner = Motion.lerp(256f, 512f * 0.27f, Math.max(0f, Math.min(1f, morph)));

        // 3. Las patas de la H y el muelle.
        float legs = Motion.spring(sec - 0.78f, 0.42f, 0.55f);
        float coilP = Motion.EXPO_OUT.getInterpolation(Motion.seg(t, 980, 1560));

        float s = size * k / 512f;
        c.save();
        c.translate(cx, y);
        c.scale(s / stretch, s * stretch);
        c.translate(-256, -256);
        r.set(0, 0, 512, 512);
        c.drawRoundRect(r, corner, corner, body);

        if (legs > 0.001f) {
            white.setAlpha(Math.round(255 * Math.min(1f, legs * 2.2f)));
            float up = (256 - 132) * legs, down = (380 - 256) * legs;
            r.set(136, 256 - up, 196, 256 + down);
            c.drawRoundRect(r, 30, 30, white);
            r.set(316, 256 - up, 376, 256 + down);
            c.drawRoundRect(r, 30, 30, white);
        }
        if (coilP > 0.001f) {
            coilPart.reset();
            coilMeasure.getSegment(0, coilLen * coilP, coilPart, true);
            c.drawPath(coilPart, coil);
        }

        // 4b. Brillo que cruza el icono en diagonal, recortado a su forma.
        float sh = Motion.seg(t, 1250, 1900);
        if (sh > 0f && sh < 1f) {
            float pos = Motion.lerp(-400f, 900f, Motion.EASE.getInterpolation(sh));
            shine.setShader(new LinearGradient(pos - 140, pos - 140, pos + 140, pos + 140,
                    new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            clip.reset();
            r.set(0, 0, 512, 512);
            clip.addRoundRect(r, corner, corner, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(r, shine);
            c.restore();
        }
        c.restore();

        // 4a. Dos aros de luz que salen del icono al terminar de formarse.
        for (int i = 0; i < 2; i++) {
            float q = Motion.seg(t, 1080 + i * 140, 1840 + i * 140);
            if (q <= 0f || q >= 1f) continue;
            float e = Motion.EXPO_OUT.getInterpolation(q);
            ring.setStrokeWidth((2.2f - i * 0.8f) * dp);
            ring.setAlpha(Math.round(150 * (1 - q) * (1 - q)));
            c.drawCircle(cx, cy, size * (0.55f + e * 1.25f), ring);
        }
        if (t < BUILD_MS && (clock == null || !clock.isRunning())) t = BUILD_MS;
    }
}
