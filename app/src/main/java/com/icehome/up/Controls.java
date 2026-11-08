package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/** Interruptor y deslizador propios (cápsulas redondas, colores del sistema). */
final class Controls {

    /** Interruptor en cápsula: pista de color con la bola oscura a la derecha si está activo. */
    static final class Toggle extends View {
        interface OnChange {
            void onChange(boolean on);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int onTrack, offTrack, onKnob, offKnob;
        private final float dp;
        private boolean checked;
        private float pos;
        private ValueAnimator anim;
        private OnChange listener;

        Toggle(Context c, int onTrack, int offTrack, int onKnob, int offKnob) {
            super(c);
            this.onTrack = onTrack;
            this.offTrack = offTrack;
            this.onKnob = onKnob;
            this.offKnob = offKnob;
            dp = c.getResources().getDisplayMetrics().density;
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
            anim = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            anim.setDuration(260);
            anim.setInterpolator(new OvershootInterpolator(1.6f));
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pos = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        /** Desde la fila o el propio interruptor: cambia y avisa. */
        void toggle(boolean user) {
            setChecked(!checked, true);
            if (user) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onChange(checked);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(52 * dp), Math.round(30 * dp));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            float t = Math.max(0f, Math.min(1f, pos));
            track.setColor(blend(offTrack, onTrack, t));
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, track);
            float kr = r - 4f * dp;
            float x = r + (w - 2 * r) * pos;
            knob.setColor(blend(offKnob, onKnob, t));
            c.drawCircle(x, r, kr, knob);
        }
    }

    /** Deslizador: pista gruesa redondeada, relleno de color y bola clara con un aro. */
    static final class Slider extends View {
        interface OnChange {
            void onChange(float fraction, boolean done);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                knob = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float dp;
        private float value, press;
        private OnChange listener;
        private ValueAnimator pressAnim;
        private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

        Slider(Context c, int trackColor, int fillColor, int knobColor) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            track.setColor(trackColor);
            fill.setColor(fillColor);
            knob.setColor(knobColor);
            ring.setColor(fillColor);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3f * dp);
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
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(36 * dp));
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
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Arrastre horizontal: el deslizador; vertical: la lista se desplaza.
                    if (!dragging && Math.abs(e.getX() - downX) > slop && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        animatePress(1f);
                    }
                    if (dragging) set(e.getX(), false);
                    return true;
                case MotionEvent.ACTION_UP:
                    set(e.getX(), true);
                    if (dragging) animatePress(0f);
                    dragging = false;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        animatePress(0f);
                        if (listener != null) listener.onChange(value, true);
                    }
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private void set(float x, boolean done) {
            float pad = 14f * dp;
            value = Math.max(0f, Math.min(1f, (x - pad) / Math.max(1f, getWidth() - 2 * pad)));
            invalidate();
            if (listener != null) listener.onChange(value, done);
        }

        private void animatePress(float to) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = ValueAnimator.ofFloat(press, to);
            pressAnim.setDuration(180);
            pressAnim.setInterpolator(EASE);
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
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cy = h / 2f, pad = 14f * dp;
            float th = 7f * dp, tr = th / 2f;
            rect.set(pad - tr, cy - tr, w - pad + tr, cy + tr);
            c.drawRoundRect(rect, tr, tr, track);
            float x = pad + (w - 2 * pad) * value;
            rect.set(pad - tr, cy - tr, x, cy + tr);
            c.drawRoundRect(rect, tr, tr, fill);
            float kr = (10f + 2f * press) * dp;
            c.drawCircle(x, cy, kr, knob);
            c.drawCircle(x, cy, kr - 1.5f * dp, ring);
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
