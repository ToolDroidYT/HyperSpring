package com.icehome.up;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

/**
 * Abrir una subpágina como en el asistente de configuración de HyperOS:
 * <ol>
 * <li>Lo que había se disuelve en su sitio: se desenfoca, se encoge un poco y se apaga.</li>
 * <li>Donde tocaste nace un círculo de luz que se abre y se desvanece.</li>
 * <li>Los elementos de la página nueva salen de ese círculo, juntos, pequeños y borrosos, y se
 *     separan deslizándose hasta su sitio mientras se enfocan, uno detrás de otro.</li>
 * </ol>
 */
final class PushMorph {

    /** Sale con decisión y se posa muy despacio, sin rebote: tranquilo, como en HyperOS. */
    private static final PathInterpolator SETTLE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static ValueAnimator running;

    /** Si vuelves atrás antes de que termine, se completa al instante para no pelearse con la vuelta. */
    static void finish() {
        if (running != null) running.end();
    }

    /**
     * @param ox, oy  de dónde sale todo (el centro de lo que tocaste), en coordenadas de la página
     * @param tint    color del círculo de luz
     * @param onFrame se llama en cada fotograma (la barra de cristal ve lo de debajo y se repinta)
     */
    static void play(final View under, final ViewGroup page, final float ox, final float oy, int tint,
                     final Runnable onFrame) {
        final float dp = page.getResources().getDisplayMetrics().density;
        final ViewGroup list = (ViewGroup) page.getChildAt(0);
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setAlpha(0f);
        page.setAlpha(0f);

        // 1) La página vieja se disuelve. El fondo de la nueva entra a la vez (sin parpadeo).
        final ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
        out.setDuration(380);
        out.setInterpolator(SETTLE);
        out.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                float k = 1f - 0.04f * t;
                under.setScaleX(k);
                under.setScaleY(k);
                under.setAlpha(1f - t);
                page.setAlpha(Math.min(1f, t * 1.4f));
                blur(under, 22f * dp * t);
                onFrame.run();
            }
        });
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (running == out) running = null;
                blur(under, 0f);                                // invisible: no hace falta desenfocar
                under.setAlpha(0f);
                page.setAlpha(1f);
            }
        });
        running = out;
        out.start();

        // 2) El círculo de luz, encima de la página nueva.
        final Bloom bloom = new Bloom(ox, oy, tint, dp);
        bloom.setBounds(0, 0, 1, 1);
        page.getOverlay().add(bloom);
        ValueAnimator b = ValueAnimator.ofFloat(0f, 1f);
        b.setDuration(700);
        b.setInterpolator(SETTLE);
        b.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                bloom.t = (Float) a.getAnimatedValue();
                bloom.invalidateSelf();
            }
        });
        b.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                page.getOverlay().remove(bloom);
            }
        });
        b.start();

        // 3) Los elementos salen del círculo. Hace falta saber dónde está cada uno, así que se
        //    espera a que la página nueva se haya medido (justo antes de su primer dibujo).
        page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                page.getViewTreeObserver().removeOnPreDrawListener(this);
                bloom.setBounds(0, 0, page.getWidth(), page.getHeight());
                long d = 80;                                    // pronto: que no quede la pantalla vacía
                for (int i = 0; i < list.getChildCount(); i++) {
                    View c = list.getChildAt(i);
                    if (c.getVisibility() != View.VISIBLE) continue;
                    float cx = list.getLeft() + c.getLeft() + c.getWidth() / 2f;
                    float cy = list.getTop() + c.getTop() + c.getHeight() / 2f - page.getScrollY();
                    boolean card = Motion.CASCADE.equals(c.getTag()) && c instanceof ViewGroup;
                    emerge(c, d, (ox - cx) * 0.25f, clamp((oy - cy) * 0.45f, 180f * dp), dp, !card);
                    if (card) {
                        // Las filas de una tarjeta aparecen una a una dentro de ella mientras viaja.
                        ViewGroup g = (ViewGroup) c;
                        for (int j = 0; j < g.getChildCount(); j++) fadeIn(g.getChildAt(j), d + 90 + j * 55L, dp);
                        d += 50 + Math.min(g.getChildCount(), 6) * 35L;
                    } else {
                        d += 60;
                    }
                    if (d > 750) d = 750;                       // en páginas largas, que no se eternice
                }
                return true;
            }
        });
    }

    private static float clamp(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    /** Un elemento sale del círculo: de pequeño, borroso y desplazado hacia él, a nítido en su sitio. */
    private static void emerge(final View v, long delay, final float dx, final float dy, final float dp,
                               final boolean sharpen) {
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.setTranslationY(dy);
        v.setScaleX(0.84f);
        v.setScaleY(0.84f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setStartDelay(delay);
        a.setDuration(680);
        a.setInterpolator(SETTLE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                float t = (Float) an.getAnimatedValue();
                v.setAlpha(Math.min(1f, t * 1.8f));
                v.setTranslationX(dx * (1f - t));
                v.setTranslationY(dy * (1f - t));
                float s = 0.84f + 0.16f * t;
                v.setScaleX(s);
                v.setScaleY(s);
                if (sharpen) blur(v, 16f * dp * (1f - t));
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                v.setAlpha(1f);
                v.setTranslationX(0f);
                v.setTranslationY(0f);
                v.setScaleX(1f);
                v.setScaleY(1f);
                blur(v, 0f);
            }
        });
        a.start();
    }

    /** Una fila dentro de una tarjeta: aparece enfocándose, sin moverse (ya se mueve la tarjeta). */
    private static void fadeIn(final View v, long delay, final float dp) {
        v.setAlpha(0f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setStartDelay(delay);
        a.setDuration(520);
        a.setInterpolator(SETTLE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                float t = (Float) an.getAnimatedValue();
                v.setAlpha(t);
                blur(v, 10f * dp * (1f - t));
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                v.setAlpha(1f);
                blur(v, 0f);
            }
        });
        a.start();
    }

    /** Desenfoque de una vista (Android 12+). Por debajo de medio píxel, se quita. */
    private static void blur(View v, float r) {
        if (Build.VERSION.SDK_INT < 31) return;
        v.setRenderEffect(r < 0.5f ? null : RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL));
    }

    /** El círculo de luz: un disco suave que se abre desde el punto tocado y se desvanece. */
    private static final class Bloom extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float x, y, dp;
        private final int tint;
        float t;

        Bloom(float x, float y, int tint, float dp) {
            this.x = x;
            this.y = y;
            this.tint = tint;
            this.dp = dp;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f * dp);
        }

        @Override
        public void draw(Canvas c) {
            if (t >= 1f) return;
            float r = (24f + 190f * t) * dp;
            float fade = 1f - t;
            int core = (tint & 0x00FFFFFF) | (Math.round(70 * fade) << 24);
            fill.setShader(new RadialGradient(x, y, r, new int[]{core, core & 0x00FFFFFF}, new float[]{0.2f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, fill);
            edge.setColor((Math.round(90 * fade * fade) << 24) | 0xFFFFFF);
            c.drawCircle(x, y, r * 0.82f, edge);
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private PushMorph() {
    }
}
