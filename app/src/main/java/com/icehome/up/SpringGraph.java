package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Editor visual del muelle: dibuja la curva de abrir una app con la «Velocidad» y el «Rebote»
 * elegidos, y al lado un móvil en miniatura donde una ventana se abre y se cierra con ese muelle.
 * <p>
 * Un punto recorre la curva al mismo tiempo que la ventana crece, así se ve qué parte de la gráfica
 * es qué parte del movimiento: la subida es la ventana creciendo, el pico por encima de la línea
 * de puntos es el rebote (se pasa de tamaño) y la parte plana es cuando ya se ha asentado.
 * <p>
 * Al mover un deslizador, la curva no salta: se transforma poco a poco hacia la nueva.
 */
final class SpringGraph extends View {

    /** Periodo base del muelle de MIUI al abrir, en segundos (aproximado; la Velocidad lo divide). */
    private static final float BASE_RESPONSE = 0.42f;
    /** Tiempo que enseña la gráfica: 1,2 s, fijo, para que se note cuando va más rápido o más lento. */
    private static final float SPAN = 1.2f;
    /** Un ciclo de la demo: abrir, quedarse, cerrar, quedarse. */
    private static final float OPEN = 1.3f, CYCLE = 2.8f;

    private final float dp;
    private final int accent, text2, text3;
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            guide = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), win = new Paint(Paint.ANTI_ALIAS_FLAG),
            frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path(), area = new Path();
    private final RectF r = new RectF();
    private final long t0 = SystemClock.uptimeMillis();

    // Lo que se pide (los deslizadores) y lo que se dibuja (va persiguiendo a lo pedido).
    private float wantSpeed = 1f, wantBounce = 0f;
    private float speed = 1f, bounce = 0f;
    private long last;

    SpringGraph(Context c, int accent, int text2, int text3) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.accent = accent;
        this.text2 = text2;
        this.text3 = text3;
        curve.setStyle(Paint.Style.STROKE);
        curve.setStrokeWidth(2.6f * dp);
        curve.setStrokeCap(Paint.Cap.ROUND);
        curve.setStrokeJoin(Paint.Join.ROUND);
        curve.setColor(accent);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(1f * dp);
        label.setTextSize(12 * dp);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(1.4f * dp);
        frame.setColor(0x40FFFFFF);
    }

    /** Valores nuevos (de los deslizadores). {@code now} = sin transición (al crear la vista). */
    void set(float speed, float bounce, boolean now) {
        wantSpeed = speed;
        wantBounce = bounce;
        if (now || !ValueAnimator.areAnimatorsEnabled()) {
            this.speed = speed;
            this.bounce = bounce;
        }
        invalidate();
    }

    /** El muelle como lo deja Springs.java en el launcher: la velocidad divide el periodo. */
    private float response() {
        return BASE_RESPONSE / Math.max(0.3f, speed);
    }

    /** Rebote 0 = MIUI casi sin pasarse (0,98); con rebote, la amortiguación baja a 1 − rebote. */
    private float damping() {
        return bounce > 0.001f ? 1f - bounce : 0.98f;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(176 * dp));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // Relleno bajo la curva: coral arriba que se desvanece hacia abajo.
        fill.setShader(new LinearGradient(0, 0, 0, h, (accent & 0x00FFFFFF) | 0x55000000, accent & 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        win.setShader(new LinearGradient(0, 0, 0, h, Motion.BRAND, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean anim = ValueAnimator.areAnimatorsEnabled();
        // La curva persigue a los valores pedidos: un 14 % del camino por fotograma (a 60 Hz).
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        float k = 1f - (float) Math.pow(0.86f, dt * 60f);
        speed += (wantSpeed - speed) * k;
        bounce += (wantBounce - bounce) * k;
        boolean morphing = Math.abs(wantSpeed - speed) > 0.002f || Math.abs(wantBounce - bounce) > 0.0005f;

        float w = getWidth(), h = getHeight();
        float pad = 6 * dp;
        // La gráfica ocupa la izquierda; el móvil en miniatura, la derecha.
        float phoneW = Math.min(76 * dp, w * 0.24f);
        float gx0 = pad, gx1 = w - phoneW - 22 * dp, gy0 = 30 * dp, gy1 = h - 14 * dp;
        float resp = response(), damp = damping();
        // Eje vertical: de 0 a 1,5 (deja sitio para el rebote más fuerte).
        float yMax = 1.5f;

        // Línea de llegada (100 %) punteada y línea de salida.
        guide.setColor(0x40FFFFFF);
        guide.setPathEffect(new DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
        float yTarget = mapY(1f, gy0, gy1, yMax);
        c.drawLine(gx0, yTarget, gx1, yTarget, guide);
        guide.setPathEffect(null);
        guide.setColor(0x24FFFFFF);
        c.drawLine(gx0, gy1, gx1, gy1, guide);

        // La curva, muestreada cada 2 dp de ancho.
        line.reset();
        area.reset();
        int n = Math.max(24, Math.round((gx1 - gx0) / (2 * dp)));
        float peak = 0f, settle = SPAN;
        boolean settled = false;
        for (int i = 0; i <= n; i++) {
            float t = SPAN * i / n;
            float v = Motion.spring(t, resp, damp);
            peak = Math.max(peak, v);
            float x = gx0 + (gx1 - gx0) * i / n, y = mapY(v, gy0, gy1, yMax);
            if (i == 0) {
                line.moveTo(x, y);
                area.moveTo(x, gy1);
                area.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        // Cuándo se asienta: el último momento en que estaba a más de un 2 % de su sitio.
        for (int i = n; i >= 0; i--) {
            float t = SPAN * i / n;
            if (Math.abs(1f - Motion.spring(t, resp, damp)) > 0.02f) {
                settle = Math.min(SPAN, t + SPAN / n);
                settled = i < n;
                break;
            }
        }
        area.lineTo(gx1, gy1);
        area.close();
        c.drawPath(area, fill);
        c.drawPath(line, curve);

        // El punto que recorre la curva, al compás de la ventana. Durante el cierre se queda arriba.
        float el = anim ? ((now - t0) / 1000f) % CYCLE : OPEN;
        float tp = Math.min(el, SPAN);
        boolean opening = el < OPEN;
        float pv = Motion.spring(opening ? tp : SPAN, resp, damp);
        float dx = gx0 + (gx1 - gx0) * (opening ? tp : SPAN) / SPAN, dy = mapY(pv, gy0, gy1, yMax);
        dot.setColor((accent & 0x00FFFFFF) | 0x38000000);
        c.drawCircle(dx, dy, 9 * dp, dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(dx, dy, 4 * dp, dot);

        // Las cifras de arriba: cuánto se pasa y cuándo se queda quieta.
        float over = Math.max(0f, peak - 1f) * 100f;
        label.setColor(text2);
        label.setTextAlign(Paint.Align.LEFT);
        String a = over >= 0.5f
                ? I18n.t("Se pasa un ", "Overshoots ") + Math.round(over) + " %"
                : I18n.t("Sin rebote", "No overshoot");
        c.drawText(a, gx0, 14 * dp, label);
        label.setColor(text3);
        label.setTextAlign(Paint.Align.RIGHT);
        String b = settled ? I18n.t("Quieta en ", "Settles in ") + Math.round(settle * 1000) + " ms"
                : I18n.t("Más de 1,2 s", "Over 1.2 s");
        c.drawText(b, gx1, 14 * dp, label);

        drawPhone(c, w - phoneW - pad, gy0 - 12 * dp, w - pad, gy1, el, resp, damp, anim);

        if (anim || morphing) postInvalidateOnAnimation();
    }

    private static float mapY(float v, float gy0, float gy1, float yMax) {
        return gy1 - (gy1 - gy0) * (v / yMax);
    }

    /** Móvil en miniatura: la ventana sale del icono y vuelve a él con el mismo muelle. */
    private void drawPhone(Canvas c, float x0, float y0, float x1, float y1, float el,
                           float resp, float damp, boolean anim) {
        r.set(x0, y0, x1, y1);
        float pr = 12 * dp;
        c.drawRoundRect(r, pr, pr, frame);
        float p;
        if (!anim) p = 1f;
        else if (el < OPEN) p = Motion.spring(el, resp, damp);
        else p = 1f - Motion.spring(el - OPEN, resp, damp);
        // El icono, abajo a la izquierda, como en un escritorio.
        float is = 12 * dp, ix = x0 + 8 * dp + is / 2f, iy = y1 - 10 * dp - is / 2f;
        float inset = 3 * dp;
        float fw = (x1 - x0) - inset * 2, fh = (y1 - y0) - inset * 2;
        float fx = (x0 + x1) / 2f, fy = (y0 + y1) / 2f;
        float cw = Math.max(3 * dp, Motion.lerp(is, fw, p)), ch = Math.max(3 * dp, Motion.lerp(is, fh, p));
        float cx = Motion.lerp(ix, fx, p), cy = Motion.lerp(iy, fy, p);
        float rad = Motion.lerp(3.5f * dp, pr - inset, Math.max(0f, Math.min(1f, p)));
        // Con rebote la ventana puede pasarse del móvil un pelín: se deja, así se ve el rebote.
        r.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f);
        c.drawRoundRect(r, rad, rad, win);
    }
}
