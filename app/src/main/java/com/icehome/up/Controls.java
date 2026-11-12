package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Interruptor y deslizador propios, al estilo de HyperOS: pista del color del sistema y bola blanca.
 * <p>
 * Al tocarlos, la bola se convierte en una gota de cristal líquido: crece, se vuelve transparente
 * y refracta la pista que tiene debajo (la aumenta en el centro y la dobla en el borde), con el
 * canto iluminado y un brillo arriba. Al soltar vuelve a ser blanca.
 * La refracción real es de Android 13+; antes, una lupa dibujada con el lienzo.
 */
final class Controls {

    /** Lo que hay debajo de la gota (la pista): se vuelve a dibujar aumentado dentro de ella. */
    interface Base {
        void drawBase(Canvas c);
    }

    /**
     * La gota de cristal. En Android 13+ es una lente de verdad: la pista se graba en un RenderNode
     * y pasa por el mismo sombreador AGSL que la barra de pestañas ({@link GlassBar#LENS}), que
     * aumenta el centro y dobla la imagen cerca del borde (sin dispersión de color: limpio).
     * Antes de Android 13, una lupa dibujada con el lienzo. Encima, en los dos casos, velo, brillo
     * y canto iluminado.
     */
    static final class Glass {
        private final float dp;
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                spec = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r2 = new RectF();
        private RenderNode node;
        private RuntimeShader lens;

        Glass(float dp) {
            this.dp = dp;
            veil.setColor(0xFFFFFFFF);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.3f * dp);
            shade.setColor(0x00000000);
            shade.setShadowLayer(6 * dp, 0, 2 * dp, 0x55000000);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    lens = new RuntimeShader(GlassBar.LENS);
                    node = new RenderNode("glassKnob");
                } catch (Throwable t) {
                    android.util.Log.w("HyperSpring", "cristal de los controles: el sombreador no compila", t);
                    lens = null;
                }
            }
        }

        /** Contorno de la gota (también sirve para quitar la pista de debajo antes de dibujarla). */
        Path shape(RectF r) {
            float rad = Math.min(r.width(), r.height()) / 2f;
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            return clip;
        }

        /**
         * Si la gota va a refractar de verdad en este lienzo. Entonces la vista no debe pintar la
         * pista por dentro de la gota: la lente ya la pinta doblada, y si no se verían las dos.
         */
        boolean real(Canvas c) {
            return lens != null && c.isHardwareAccelerated();
        }

        /**
         * @param a    opacidad de la gota (0 = no se ve)
         * @param zoom cuánto aumenta lo de debajo
         */
        void draw(Canvas c, View v, RectF r, float a, float zoom, Base base) {
            if (a <= 0.01f) return;
            float rad = Math.min(r.width(), r.height()) / 2f;
            int ai = Math.round(255 * Math.min(1f, a));
            shape(r);
            // Sombra suave alrededor (no dentro: la gota es transparente): «flota» sobre la pista.
            shade.setShadowLayer(6 * dp, 0, 2 * dp, Math.round(0x50 * a) << 24);
            shade.setColor(Math.round(0x01 * a) << 24);
            int s0 = c.save();
            c.clipOutPath(clip);
            c.drawRoundRect(r, rad, rad, shade);
            c.restoreToCount(s0);

            boolean lensDone = false;
            if (real(c)) {
                try {
                    int w = v.getWidth(), h = v.getHeight();
                    node.setPosition(0, 0, w, h);
                    RecordingCanvas rc = node.beginRecording(w, h);
                    base.drawBase(rc);
                    node.endRecording();
                    lens.setFloatUniform("center", r.centerX(), r.centerY());
                    lens.setFloatUniform("halfSize", r.width() / 2f, r.height() / 2f);
                    lens.setFloatUniform("radius", rad);
                    // Casi toda la gota es «borde curvo»: dobla mucho, como una gota de agua.
                    lens.setFloatUniform("refractionHeight", rad * 0.9f);
                    lens.setFloatUniform("refractionAmount", -rad * 0.8f);
                    lens.setFloatUniform("magnify", zoom);
                    lens.setFloatUniform("chroma", 0f);              // sin arcoíris: más limpio dentro de la app
                    node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
                    node.setAlpha(Math.min(1f, a));
                    int s = c.save();
                    c.clipPath(clip);
                    c.drawRenderNode(node);
                    c.restoreToCount(s);
                    lensDone = true;
                } catch (Throwable t) {
                    android.util.Log.w("HyperSpring", "cristal de los controles desactivado", t);
                    lens = null;                                  // lupa del lienzo a partir de aquí
                }
            }
            if (!lensDone) {
                int s = c.save();
                c.clipPath(clip);
                c.scale(zoom, zoom, r.centerX(), r.centerY());
                base.drawBase(c);                               // la pista, aumentada como por una lupa
                c.restoreToCount(s);
            }

            veil.setAlpha(Math.round((lensDone ? 0x10 : 0x1E) * a));
            c.drawRoundRect(r, rad, rad, veil);

            // Brillo de arriba: una franja clara que se apaga hacia el centro.
            spec.setShader(new LinearGradient(0, r.top, 0, r.centerY(),
                    (Math.round(0x55 * a) << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            r2.set(r.left + 2 * dp, r.top + 1.5f * dp, r.right - 2 * dp, r.centerY());
            c.drawRoundRect(r2, rad, rad, spec);

            // Canto: claro arriba, apagado en el medio y algo de luz abajo (como un borde curvo).
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{0xE6FFFFFF, 0x26FFFFFF, 0x80FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            rim.setAlpha(ai);
            float in = 0.65f * dp;
            r2.set(r.left + in, r.top + in, r.right - in, r.bottom - in);
            c.drawRoundRect(r2, rad - in, rad - in, rim);
        }
    }

    /** Bola blanca en reposo, con una sombra corta. */
    private static Paint knobPaint(float dp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setShadowLayer(3 * dp, 0, 1 * dp, 0x40000000);
        return p;
    }

    /**
     * Pinta la pista. Si la gota de cristal está a la vista y refracta de verdad, la pista se
     * pinta solo por fuera de ella: por dentro la pinta la lente, ya doblada.
     */
    private static void drawUnder(Canvas c, Glass glass, RectF knob, float press, Base base) {
        if (press * 1.2f > 0.01f && glass.real(c)) {
            int s = c.save();
            c.clipOutPath(glass.shape(knob));
            base.drawBase(c);
            c.restoreToCount(s);
        } else {
            base.drawBase(c);
        }
    }

    /** Anima {@code from → to} con un muelle y llama a {@code set} en cada fotograma. */
    private static ValueAnimator spring(float from, float to, float response, float damping, final View v,
                                        final Setter set) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        Spring.apply(a, from, to, 0f, response, damping);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                set.set((Float) va.getAnimatedValue());
                v.invalidate();
            }
        });
        a.start();
        return a;
    }

    private interface Setter {
        void set(float v);
    }

    // ------------------------------------------------------------------ interruptor

    /**
     * Interruptor en cápsula. La pista es gris apagado y del color del sistema encendido; la bola
     * es blanca. Mientras se mantiene pulsado, la bola se ensancha y se vuelve cristal.
     */
    static final class Toggle extends View implements Base {
        interface OnChange {
            void onChange(boolean on);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), knob;
        private final RectF rect = new RectF(), k = new RectF();
        private final int onTrack, offTrack;
        private final float dp;
        private final Glass glass;
        private boolean checked;
        private float pos, press;
        private ValueAnimator anim, pressAnim;
        private OnChange listener;

        Toggle(Context c, int onTrack, int offTrack) {
            super(c);
            this.onTrack = onTrack;
            this.offTrack = offTrack;
            dp = c.getResources().getDisplayMetrics().density;
            knob = knobPaint(dp);
            glass = new Glass(dp);
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(true);
                }
            });
        }

        void setOnChange(OnChange l) {
            listener = l;
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean on, boolean animate) {
            if (anim != null) anim.cancel();
            checked = on;
            if (!animate) {
                pos = on ? 1f : 0f;
                invalidate();
                return;
            }
            anim = spring(pos, on ? 1f : 0f, 0.42f, 0.72f, this, new Setter() {
                @Override
                public void set(float v) {
                    pos = v;
                }
            });
        }

        /** Desde la fila o el propio interruptor: cambia y avisa. */
        void toggle(boolean user) {
            setChecked(!checked, true);
            // Android 14+ tiene una vibración distinta para encender y para apagar: se nota sin mirar.
            if (user) performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 34
                    ? (checked ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF)
                    : HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onChange(checked);
        }

        private void pressTo(float to) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = spring(press, to, to > 0 ? 0.3f : 0.4f, to > 0 ? 0.62f : 0.8f, this, new Setter() {
                @Override
                public void set(float v) {
                    press = v;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressTo(1f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressTo(0f);
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onMeasure(int w, int h) {
            // Algo más grande que la pista: la gota pulsada sobresale sin cortarse.
            setMeasuredDimension(Math.round(62 * dp), Math.round(40 * dp));
        }

        @Override
        public void drawBase(Canvas c) {
            float t = Math.max(0f, Math.min(1f, pos));
            track.setColor(blend(offTrack, onTrack, t));
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, track);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float tw = 50 * dp, th = 30 * dp;
            rect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);
            float p = Math.max(0f, press);
            float r = th / 2f - 2.5f * dp;
            float kw = 2 * r * (1f + 0.55f * p), kh = 2 * r * (1f + 0.3f * p);
            float x = Motion.lerp(rect.left + th / 2f, rect.right - th / 2f, pos);
            k.set(x - kw / 2f, cy - kh / 2f, x + kw / 2f, cy + kh / 2f);
            drawUnder(c, glass, k, p, this);
            glass.draw(c, this, k, Math.min(1f, p * 1.2f), 1.3f, this);
            float solid = Math.max(0f, 1f - p * 1.4f);
            if (solid > 0f) {
                knob.setAlpha(Math.round(255 * solid));
                c.drawRoundRect(k, kh / 2f, kh / 2f, knob);
            }
        }
    }

    // ------------------------------------------------------------------ deslizador

    /**
     * Deslizador: pista fina, relleno del color del sistema y una bola en cápsula. Al arrastrar, la
     * cápsula se vuelve cristal y aumenta la pista; si el dedo va rápido, se estira en esa
     * dirección y se recoge al frenar.
     */
    static final class Slider extends View implements Base {
        interface OnChange {
            void onChange(float fraction, boolean done);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), knob;
        private final RectF rect = new RectF(), k = new RectF();
        private final float dp;
        private final Glass glass;
        private float value, press, vel, lastX;
        private long lastT;
        private OnChange listener;
        private ValueAnimator pressAnim;
        private float trackL, trackR, cy;

        Slider(Context c, int trackColor, int fillColor) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            track.setColor(trackColor);
            fill.setColor(fillColor);
            knob = knobPaint(dp);
            glass = new Glass(dp);
        }

        void setOnChange(OnChange l) {
            listener = l;
        }

        void setFraction(float f) {
            value = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(44 * dp));
        }

        private float downX, downY;
        private boolean dragging;

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    lastX = e.getX();
                    lastT = e.getEventTime();
                    vel = 0f;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Arrastre horizontal: el deslizador; vertical: la lista se desplaza.
                    if (!dragging && Math.abs(e.getX() - downX) > slop && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        pressTo(1f);
                    }
                    if (dragging) {
                        long dt = Math.max(1, e.getEventTime() - lastT);
                        float v = (e.getX() - lastX) / dt;            // px por ms
                        vel = vel * 0.6f + v * 0.4f;
                        lastX = e.getX();
                        lastT = e.getEventTime();
                        set(e.getX(), false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    set(e.getX(), true);
                    if (dragging) pressTo(0f);
                    dragging = false;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        pressTo(0f);
                        if (listener != null) listener.onChange(value, true);
                    }
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private void set(float x, boolean done) {
            float old = value;
            value = Math.max(0f, Math.min(1f, (x - trackL) / Math.max(1f, trackR - trackL)));
            // Un toque al llegar a un extremo, como los deslizadores del sistema.
            if (old != value && (value == 0f || value == 1f)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            if (listener != null) listener.onChange(value, done);
        }

        private void pressTo(float to) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = spring(press, to, to > 0 ? 0.32f : 0.42f, to > 0 ? 0.6f : 0.8f, this, new Setter() {
                @Override
                public void set(float v) {
                    press = v;
                }
            });
        }

        @Override
        public void drawBase(Canvas c) {
            float th = 6f * dp, tr = th / 2f;
            rect.set(trackL - tr, cy - tr, trackR + tr, cy + tr);
            c.drawRoundRect(rect, tr, tr, track);
            float x = trackL + (trackR - trackL) * value;
            rect.set(trackL - tr, cy - tr, x, cy + tr);
            c.drawRoundRect(rect, tr, tr, fill);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            cy = getHeight() / 2f;
            trackL = 24f * dp;
            trackR = w - 24f * dp;
            float x = trackL + (trackR - trackL) * value;
            float p = Math.max(0f, press);
            // Estiramiento por velocidad: se alarga en horizontal y adelgaza un poco.
            float st = 1f + Math.min(0.35f, Math.abs(vel) * 0.12f);
            float kw = (30f + 14f * p) * dp * st, kh = (20f + 10f * p) * dp / (float) Math.sqrt(st);
            k.set(x - kw / 2f, cy - kh / 2f, x + kw / 2f, cy + kh / 2f);
            drawUnder(c, glass, k, p, this);
            glass.draw(c, this, k, Math.min(1f, p * 1.2f), 1.6f, this);
            float solid = Math.max(0f, 1f - p * 1.4f);
            if (solid > 0f) {
                knob.setAlpha(Math.round(255 * solid));
                c.drawRoundRect(k, kh / 2f, kh / 2f, knob);
            }
            // La velocidad se apaga sola cuando el dedo para: la gota se recoge.
            if (Math.abs(vel) > 0.01f) {
                vel *= 0.86f;
                postInvalidateOnAnimation();
            } else {
                vel = 0f;
            }
        }
    }

    static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private Controls() {
    }
}
