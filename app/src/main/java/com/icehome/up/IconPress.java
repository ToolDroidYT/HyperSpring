package com.icehome.up;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * El icono se hunde al pulsarlo (como en iOS y Samsung): se encoge un poco al poner el dedo y
 * vuelve con un pequeño rebote al levantarlo, al cancelar el toque (deslizar, mantener…) o al
 * abrir la app.
 */
final class IconPress {

    static volatile boolean enabled = true;
    /** Tamaño mientras está pulsado. */
    private static final float PRESSED = 0.9f;
    private static final long DOWN_MS = 110, UP_MS = 300;
    private static final DecelerateInterpolator DOWN = new DecelerateInterpolator(1.6f);
    private static final OvershootInterpolator UP = new OvershootInterpolator(2.2f);

    static void install(ClassLoader cl) {
        Class<?> icon = XposedHelpers.findClass("com.miui.home.launcher.ShortcutIcon", cl);
        XposedHelpers.findAndHookMethod(icon, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled) return;
                View v = (View) p.thisObject;
                switch (((MotionEvent) p.args[0]).getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        release(pressed == null ? null : pressed.get());
                        pressed = new java.lang.ref.WeakReference<>(v);
                        v.animate().cancel();
                        v.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(DOWN_MS).setInterpolator(DOWN).start();
                        v.removeCallbacks(SAFETY);
                        v.postDelayed(SAFETY, SAFETY_MS);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        release(v);
                        break;
                    default:
                        break;
                }
            }
        });
        // El icono no siempre recibe el resto del toque (a veces lo lleva la cuadrícula): al
        // levantar el dedo en cualquier sitio del launcher, el icono pulsado vuelve igualmente.
        XposedHelpers.findAndHookMethod("com.miui.home.launcher.BaseLauncher", cl, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                int a = ((MotionEvent) p.args[0]).getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) releasePressed();
            }
        });
        Log.i("iconos que se hunden al pulsarlos");
    }

    private static java.lang.ref.WeakReference<View> pressed;
    /** Por si no llega ningún "levantar" (el launcher pasa a segundo plano…). */
    private static final long SAFETY_MS = 1500;
    private static final Runnable SAFETY = new Runnable() {
        @Override
        public void run() {
            releasePressed();
        }
    };

    private static void releasePressed() {
        java.lang.ref.WeakReference<View> r = pressed;
        pressed = null;
        if (r != null) release(r.get());
    }

    private static void release(View v) {
        if (v == null) return;
        v.removeCallbacks(SAFETY);
        if (v.getScaleX() == 1f && v.getScaleY() == 1f) return;
        v.animate().cancel();
        v.animate().scaleX(1f).scaleY(1f).setDuration(UP_MS).setInterpolator(UP).start();
    }

    /** Ice: al llegar la copa, el icono se aplasta un poco y rebota a su tamaño. */
    private static final float LAND = 0.92f;
    private static final long LAND_DOWN_MS = 90, LAND_UP_MS = 320;
    private static final OvershootInterpolator LAND_UP = new OvershootInterpolator(2.6f);

    static void land(final View v) {
        if (v == null) return;
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    v.animate().cancel();
                    v.animate().scaleX(LAND).scaleY(LAND).setDuration(LAND_DOWN_MS).setInterpolator(DOWN)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(LAND_UP_MS).setInterpolator(LAND_UP).start();
                                }
                            }).start();
                    // Por si algo cancela la vuelta: nunca se queda pequeño.
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (v.getScaleX() != 1f && !v.isPressed()) {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                            }
                        }
                    }, LAND_DOWN_MS + LAND_UP_MS + 400);
                } catch (Throwable e) {
                    Log.e("icono al aterrizar", e);
                }
            }
        });
    }

    private IconPress() {
    }
}
