package com.icehome.up;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * El fondo de pantalla se acerca al abrir una app y se aleja al volver al escritorio, como en
 * Samsung. MIUI ya lo hace (con su muelle, a 1,14× al abrir y 1,06× en recientes), pero solo en
 * móviles con un nivel de potencia de 4 o más ({@code ComputilityConfig.WallpaperStrategy}): en
 * los modestos está apagado. Aquí se enciende y se ajusta su intensidad.
 *
 * Lo mueve el propio servicio del fondo del sistema (o {@code WallpaperManager} con el zoom), no
 * el launcher: su coste para la GPU es pequeño.
 */
final class WallpaperZoom {

    /** 0 = como MIUI lo tenga; 1 = lo de MIUI en los móviles potentes; más = más zoom. */
    static volatile float strength = 1f;

    static void install(ClassLoader cl) {
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.miui.launcher.utils.ComputilityConfig$WallpaperStrategy", cl),
                "isSceneSupport", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (strength > 0f) p.setResult(true);
                    }
                });
        Class<?> param = XposedHelpers.findClass("com.miui.home.recents.anim.WallpaperParam", cl);
        final Constructor<?> make = XposedHelpers.findConstructorExact(param, float.class, float.class, float.class);
        final Method zoom = WindowAnim.method(param, "getZoomOut");
        final Method damping = WindowAnim.method(param, "getDampingRatio");
        final Method response = WindowAnim.method(param, "getResponse");
        Class<?> companion = XposedHelpers.findClass("com.miui.home.recents.anim.WallpaperParam$Companion", cl);
        final Method base = WindowAnim.method(companion, "getDefaultScale");
        XC_MethodHook scale = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                float k = strength;
                Object r = p.getResult();
                if (r == null || k <= 0f || k == 1f) return;
                float d = (Float) base.invoke(p.thisObject);
                float z = (Float) zoom.invoke(r);
                float extra = z / Math.max(0.01f, d) - 1f;              // 0,14 al abrir, 0,06 en recientes
                p.setResult(make.newInstance(d * (1f + extra * k), damping.invoke(r), response.invoke(r)));
            }
        };
        XposedHelpers.findAndHookMethod(companion, "getAppStateParams", scale);
        XposedHelpers.findAndHookMethod(companion, "getRecentStateParams", scale);
        Log.i("zoom del fondo al abrir y cerrar activo");
    }

    private WallpaperZoom() {
    }
}
