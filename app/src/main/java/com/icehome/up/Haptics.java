package com.icehome.up;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

/**
 * Vibración muy suave cuando el genio llega al icono: un "tic" corto, de los más ligeros que
 * tenga el motor de vibración. Respeta el ajuste del sistema de vibrar al tocar.
 */
final class Haptics {

    /** 0 = sin vibración; 1 = el tic más fuerte que se permite aquí (que sigue siendo suave). */
    static volatile float strength = 0.3f;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Vibrator vibrator;
    private static long lastMs;

    /** El genio llegó al icono (cualquier hilo). */
    static void land() {
        if (strength <= 0f) return;
        MAIN.post(LAND);
    }

    private static final Runnable LAND = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastMs < 150) return;                 // dos llegadas seguidas: una sola vez
            lastMs = now;
            Context c = TapCatcher.context;
            if (c == null) return;
            try {
                if (Settings.System.getInt(c.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
                if (vibrator == null) vibrator = c.getSystemService(Vibrator.class);
                Vibrator v = vibrator;
                if (v == null || !v.hasVibrator()) return;
                v.vibrate(effect(v), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } catch (Throwable t) {
                Log.e("vibración", t);
            }
        }
    };

    /** El tic más ligero disponible, a la intensidad del ajuste (con un tope bajo). */
    private static VibrationEffect effect(Vibrator v) {
        float s = Math.min(1f, Math.max(0f, strength));
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
            return VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.15f + 0.6f * s).compose();
        }
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            return VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.1f + 0.4f * s).compose();
        }
        if (v.hasAmplitudeControl()) {
            return VibrationEffect.createOneShot(8, Math.max(1, Math.round(15 + 70 * s)));
        }
        return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
    }

    private Haptics() {
    }
}
