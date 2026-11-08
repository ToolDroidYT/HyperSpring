package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Barra de pestañas flotante de cristal líquido, con icono y nombre en cada pestaña.
 * <ul>
 * <li>Lo que pasa por debajo (la página) se ve desenfocado y doblado en el borde de la cápsula.</li>
 * <li>La pestaña elegida lleva una burbuja de cristal. Al tocar o arrastrar, la burbuja crece,
 * sobresale de la barra y hace de lupa sobre los iconos que pasa por encima (aumenta y dobla en
 * su borde); al soltar se coloca en la pestaña más cercana con un pequeño rebote.</li>
 * </ul>
 * Refracción: la lente de Kyant0/AndroidLiquidGlass (Apache-2.0,
 * {@code RoundedRectRefractionShaderString}): circleMap(1 − d/altura)·cantidad hacia dentro a lo
 * largo del gradiente del rectángulo redondeado. Android 13+; en 12, solo desenfoque; antes, liso.
 */
final class GlassBar extends View {

    interface OnSelect {
        void onSelect(int index);
    }

    /** Refracción del borde (y aumento, para la burbuja) de una cápsula centrada en {@code center}. */
    private static final String LENS =
            "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float2 halfSize;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float magnify;\n"
            + "uniform float chroma;\n"
            + "float sdRoundedRect(float2 coord, float2 hs, float r) {\n"
            + "  float2 cc = abs(coord) - (hs - float2(r));\n"
            + "  return length(max(cc, 0.0)) + min(max(cc.x, cc.y), 0.0) - r;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 hs, float r) {\n"
            + "  float2 cc = abs(coord) - (hs - float2(r));\n"
            + "  if (cc.x >= 0.0 || cc.y >= 0.0) return sign(coord) * normalize(max(cc, 0.0));\n"
            + "  float gx = step(cc.y, cc.x);\n"
            + "  return sign(coord) * float2(gx, 1.0 - gx);\n"
            + "}\n"
            + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n"
            + "half4 main(float2 coord) {\n"
            + "  float2 c = coord - center;\n"
            + "  float sd = sdRoundedRect(c, halfSize, radius);\n"
            + "  float2 q = center + c / magnify;\n"
            + "  if (-sd >= refractionHeight) return content.eval(q);\n"
            + "  sd = min(sd, 0.0);\n"
            + "  float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "  float gr = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "  float2 grad = normalize(gradSdRoundedRect(c, halfSize, gr));\n"
            // Dispersión (como Kyant0/AndroidLiquidGlass): cada canal se desvía algo distinto, más
            // cuanto más cerca del borde, así el reflejo del canto se abre en un leve arcoíris.
            + "  float2 off = d * grad;\n"
            + "  float k = chroma * (1.0 - clamp(-sd / refractionHeight, 0.0, 1.0));\n"
            + "  if (k <= 0.0) return content.eval(q + off);\n"
            // 7 muestras repartidas en [-k, k]: el rojo pesa más hacia fuera y el azul hacia dentro,
            // y la suma da un arcoíris suave en vez de tres copias separadas.
            + "  half3 acc = half3(0.0); half3 wsum = half3(0.0); half alpha = 0.0;\n"
            + "  for (int i = 0; i < 7; i++) {\n"
            + "    float t = float(i) / 3.0 - 1.0;\n"
            + "    half4 s = content.eval(q + off * (1.0 + k * t));\n"
            + "    half3 w = half3(max(0.0, 1.0 - abs(t - 0.6)), 1.0 - abs(t) * 0.7, max(0.0, 1.0 - abs(t + 0.6)));\n"
            + "    acc += s.rgb * w; wsum += w; alpha += s.a;\n"
            + "  }\n"
            + "  return half4(acc / wsum, alpha / 7.0);\n"
            + "}\n";

    private final String[] labels;
    private final Icons[] icons;
    private final float dp, pad;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), bubbleFill = new Paint(Paint.ANTI_ALIAS_FLAG),
            bubbleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pill = new Path(), bubblePath = new Path();
    private final RectF bar = new RectF(), bubble = new RectF();
    private final int textColor, selectedColor;
    private RenderNode backdrop, content, lensNode;
    private RuntimeShader barLens, bubbleLens;
    private View source;
    private OnSelect listener;
    private int selected;
    private float thumbAt, press;
    private ValueAnimator slide, pressAnim;
    private boolean dragging;
    private float downX, grabOffset;
    private final int[] myLoc = new int[2], srcLoc = new int[2];
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final float BLUR_DP = 4f;            // se intuye lo de debajo y se nota la refracción

    GlassBar(Context c, String[] labels, int[] iconKinds, int tint, int textColor, int selectedColor) {
        super(c);
        this.labels = labels;
        this.textColor = textColor;
        this.selectedColor = selectedColor;
        dp = c.getResources().getDisplayMetrics().density;
        pad = 12f * dp;                                      // sitio para que la burbuja sobresalga
        icons = new Icons[labels.length];
        for (int i = 0; i < labels.length; i++) icons[i] = new Icons(iconKinds[i], textColor);
        fill.setColor(tint);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, dp));
        bubbleStroke.setStyle(Paint.Style.STROKE);
        bubbleStroke.setStrokeWidth(Math.max(1f, dp));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12f * dp);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 29) {
            backdrop = new RenderNode("glassBarBackdrop");
            content = new RenderNode("glassBarContent");
            lensNode = new RenderNode("glassBarBubble");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                barLens = new RuntimeShader(LENS);
                bubbleLens = new RuntimeShader(LENS);
            } catch (Throwable t) {
                barLens = bubbleLens = null;
            }
        }
        setClickable(true);
    }

    /** Alto total de la vista para una barra de {@code barDp}: la barra más el margen de la burbuja. */
    int heightFor(float barDp) {
        return Math.round(barDp * dp + 2 * pad);
    }

    void setSource(View v) {
        source = v;
        invalidate();
    }

    void setOnSelect(OnSelect l) {
        listener = l;
    }

    void select(int index, boolean animate) {
        selected = index;
        slideTo(index, animate);
    }

    private void slideTo(float to, boolean animate) {
        if (slide != null) slide.cancel();
        if (!animate) {
            thumbAt = to;
            invalidate();
            return;
        }
        slide = ValueAnimator.ofFloat(thumbAt, to);
        slide.setDuration(420);
        slide.setInterpolator(new OvershootInterpolator(1.5f));
        slide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                thumbAt = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        slide.start();
    }

    private void animatePress(float to) {
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(press, to);
        pressAnim.setDuration(to > press ? 200 : 360);
        pressAnim.setInterpolator(to > press ? EASE : new OvershootInterpolator(1.2f));
        pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                press = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        pressAnim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float seg = bar.width() / labels.length;
        float x = e.getX() - bar.left;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!bar.contains(e.getX(), e.getY())) return false;
                downX = x;
                dragging = false;
                grabOffset = x / seg - 0.5f - thumbAt;
                animatePress(1f);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(x - downX) > 6f * dp) dragging = true;
                if (dragging) {
                    if (slide != null) slide.cancel();
                    // Se arrastra la burbuja (si se cogió fuera de ella, va hacia el dedo).
                    float want = x / seg - 0.5f - (Math.abs(grabOffset) < 0.6f ? grabOffset : 0f);
                    thumbAt = Math.max(-0.15f, Math.min(labels.length - 0.85f, want));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                int i = dragging ? Math.round(Math.max(0f, Math.min(labels.length - 1f, thumbAt)))
                        : Math.max(0, Math.min(labels.length - 1, (int) (x / seg)));
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) i = selected;
                animatePress(0f);
                if (i != selected) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    selected = i;
                    if (listener != null) listener.onSelect(i);
                }
                slideTo(i, true);
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bar.set(0, pad, w, h - pad);
        float r = bar.height() / 2f;
        pill.reset();
        pill.addRoundRect(bar, r, r, Path.Direction.CW);
        stroke.setShader(new LinearGradient(0, bar.top, w * 0.55f, bar.bottom,
                new int[]{0x70FFFFFF, 0x12FFFFFF, 0x30FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // Brillo suave arriba (como la luz sobre un cristal), que se apaga hacia abajo.
        sheen.setShader(new LinearGradient(0, bar.top, 0, bar.bottom,
                new int[]{0x1CFFFFFF, 0x06FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean hw = canvas.isHardwareAccelerated() && content != null;
        if (hw) {
            // Todo lo de la barra en un RenderNode, para dibujarlo y además verlo por la burbuja.
            content.setPosition(0, 0, w, h);
            RecordingCanvas rc = content.beginRecording(w, h);
            drawBar(rc, w, true);
            content.endRecording();
            canvas.drawRenderNode(content);
        } else {
            drawBar(canvas, w, false);
        }
        drawBubble(canvas, w, h, hw);
    }

    private void drawBar(Canvas c, int w, boolean hw) {
        float r = bar.height() / 2f;
        if (hw) drawBackdrop(c, w, getHeight(), r);
        c.drawPath(pill, fill);
        c.drawPath(pill, sheen);
        // Base de la burbuja, dentro de la barra (lo que se ve en reposo).
        float seg = bar.width() / labels.length;
        float inset = 4f * dp;
        bubble.set(bar.left + thumbAt * seg + inset, bar.top + inset, bar.left + (thumbAt + 1f) * seg - inset, bar.bottom - inset);
        float br = bubble.height() / 2f;
        bubbleFill.setColor(0x26FFFFFF);
        c.drawRoundRect(bubble, br, br, bubbleFill);
        c.drawPath(pill, stroke);
        float iconSize = 24f * dp;
        float iconTop = bar.centerY() - iconSize / 2f - 8f * dp;
        float baseline = bar.centerY() + 19f * dp;
        for (int i = 0; i < labels.length; i++) {
            float near = Math.max(0f, 1f - Math.abs(thumbAt - i));
            int col = Controls.blend(textColor, selectedColor, near);
            float cx = bar.left + seg * (i + 0.5f);
            Icons ic = icons[i];
            ic.setColor(col);
            ic.setBounds(Math.round(cx - iconSize / 2f), Math.round(iconTop), Math.round(cx + iconSize / 2f), Math.round(iconTop + iconSize));
            ic.draw(c);
            text.setColor(col);
            c.drawText(labels[i], cx, baseline, text);
        }
    }

    /** La burbuja: al tocar o arrastrar crece, sobresale y hace de lupa sobre la barra. */
    private void drawBubble(Canvas canvas, int w, int h, boolean hw) {
        if (press <= 0.001f) return;
        float seg = bar.width() / labels.length;
        float grow = press;
        float bw = seg - 8f * dp + 18f * dp * grow, bh = bar.height() - 8f * dp + 2f * pad * 0.85f * grow;
        float cx = bar.left + (thumbAt + 0.5f) * seg, cy = bar.centerY();
        bubble.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
        float br = bh / 2f;
        bubblePath.reset();
        bubblePath.addRoundRect(bubble, br, br, Path.Direction.CW);
        if (hw && bubbleLens != null && lensNode != null) {
            bubbleLens.setFloatUniform("center", cx, cy);
            bubbleLens.setFloatUniform("halfSize", bw / 2f, bh / 2f);
            bubbleLens.setFloatUniform("radius", br);
            bubbleLens.setFloatUniform("refractionHeight", Math.min(br, 14f * dp));
            bubbleLens.setFloatUniform("refractionAmount", -16f * dp * grow);
            bubbleLens.setFloatUniform("magnify", 1f + 0.22f * grow);
            bubbleLens.setFloatUniform("chroma", 0.25f);
            lensNode.setPosition(0, 0, w, h);
            RecordingCanvas rc = lensNode.beginRecording(w, h);
            rc.drawRenderNode(content);
            lensNode.endRecording();
            lensNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(bubbleLens, "content"));
            canvas.save();
            canvas.clipPath(bubblePath);
            canvas.drawRenderNode(lensNode);
            canvas.restore();
        }
        bubbleFill.setColor(((int) (0x14 * grow) << 24) | 0xFFFFFF);
        canvas.drawPath(bubblePath, bubbleFill);
        bubbleStroke.setColor(((int) (0x55 * grow) << 24) | 0xFFFFFF);
        canvas.drawPath(bubblePath, bubbleStroke);
    }

    /** Lo de debajo, desenfocado y refractado, dentro de la cápsula. */
    private void drawBackdrop(Canvas c, int w, int h, float r) {
        if (backdrop == null || source == null || Build.VERSION.SDK_INT < 31) return;
        try {
            getLocationInWindow(myLoc);
            source.getLocationInWindow(srcLoc);
            backdrop.setPosition(0, 0, w, h);
            RecordingCanvas rc = backdrop.beginRecording(w, h);
            rc.translate(srcLoc[0] - myLoc[0] - source.getScrollX(), srcLoc[1] - myLoc[1] - source.getScrollY());
            source.draw(rc);
            backdrop.endRecording();
            // Desenfoque leve y algo más de color, y luego la lente del borde (lo que se ve es la refracción).
            RenderEffect blur = RenderEffect.createBlurEffect(BLUR_DP * dp, BLUR_DP * dp, Shader.TileMode.CLAMP);
            ColorMatrix vib = new ColorMatrix();
            vib.setSaturation(1.35f);
            RenderEffect effect = RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vib)), blur);
            if (barLens != null) {
                barLens.setFloatUniform("center", bar.centerX(), bar.centerY());
                barLens.setFloatUniform("halfSize", bar.width() / 2f, bar.height() / 2f);
                barLens.setFloatUniform("radius", r);
                barLens.setFloatUniform("refractionHeight", Math.min(r, 28f * dp));
                barLens.setFloatUniform("refractionAmount", -20f * dp);
                barLens.setFloatUniform("magnify", 1f);
                barLens.setFloatUniform("chroma", 0.35f);
                effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(barLens, "content"), effect);
            }
            backdrop.setRenderEffect(effect);
            c.save();
            c.clipPath(pill);
            c.drawRenderNode(backdrop);
            c.restore();
        } catch (Throwable t) {
            backdrop = null;                                       // cristal liso a partir de aquí
        }
    }
}
