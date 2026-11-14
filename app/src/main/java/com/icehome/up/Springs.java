package com.icehome.up;

import android.graphics.RectF;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedHelpers;

/**
 * Muelles de abrir y cerrar apps. MIUI da un muelle (amortiguación, periodo) a cada propiedad:
 * centerX, centerY, Width, Height, radio, transparencia… Aquí se ajustan después de MIUI y del
 * mod iOS de Kashi:
 * <ul>
 * <li>Velocidad: divide el periodo.</li>
 * <li>Rebote: limita la amortiguación (1 = sin rebote).</li>
 * </ul>
 */
final class Springs {

    static volatile float speed = 1f, bounce = 0f;
    /** Ice: rebote mínimo al abrir y velocidad relativa al cerrar. */
    private static final float ICE_OPEN_BOUNCE = 0.08f, ICE_CLOSE_SPEED = 0.85f;

    private static Constructor<?> make;
    private static Field key, damping, response, impulse;

    static void install(ClassLoader cl) {
        final Class<?> param = XposedHelpers.findClass("com.miui.home.recents.util.SpringParamsProvider$SpringParam", cl);
        make = XposedHelpers.findConstructorExact(param, String.class, float.class, float.class, float.class);
        key = WindowAnim.field(param, "key");
        damping = WindowAnim.field(param, "damping");
        response = WindowAnim.field(param, "response");
        impulse = WindowAnim.field(param, "impulse");
        Class<?> type = XposedHelpers.findClass("com.miui.home.recents.util.RectFSpringAnim$AnimType", cl);

        XposedHelpers.findAndHookMethod("com.miui.home.recents.util.SpringParamsProvider$Companion", cl,
                "generateSpringBundlesByAnimType", type, RectF.class, RectF.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String t = ((Enum<?>) p.args[0]).name();
                            if (!"OPEN_FROM_HOME".equals(t) && !"CLOSE_TO_HOME".equals(t)) return;
                            Object out = adjust(param, (Object[]) p.getResult(), "OPEN_FROM_HOME".equals(t));
                            if (out != null) p.setResult(out);
                        } catch (Throwable e) {
                            Log.e("muelles", e);
                        }
                    }
                });
    }

    /** Copia ajustada del array de muelles, o null si no hay nada que cambiar. */
    private static Object adjust(Class<?> param, Object[] in, boolean open) throws Exception {
        float s = speed, b = bounce;
        if (Genie.style == Genie.STYLE_ICE) {
            // Ice: al abrir, un pequeño rebote al llegar; al cerrar, algo más lenta (hacia un punto
            // pequeño, si va igual de rápida parece que desaparece) y sin rebote (no tiembla en el icono).
            if (open) b = Math.max(b, ICE_OPEN_BOUNCE);
            else {
                s *= ICE_CLOSE_SPEED;
                b = 0f;
            }
        }
        if (in == null || (s == 1f && b == 0f)) return null;
        Object out = Array.newInstance(param, in.length);
        for (int i = 0; i < in.length; i++) {
            Object sp = in[i];
            String k = (String) key.get(sp);
            float d = damping.getFloat(sp), r = response.getFloat(sp) / s;
            if (b > 0f) d = Math.min(d, 1f - b);
            Array.set(out, i, make.newInstance(k, d, r, impulse.getFloat(sp)));
        }
        return out;
    }

    private Springs() {
    }
}
