package com.icehome.up;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * ART puede incrustar (inline) métodos pequeños dentro de quien los llama al
 * compilar el launcher; entonces el hook nunca se ejecuta. LSPosed ofrece
 * XposedBridge.deoptimizeMethod(Member) para forzar que el llamador se interprete
 * y vuelva a pasar por el método enganchado. Se invoca por reflexión porque no
 * forma parte de la API clásica de Xposed.
 */
final class Deopt {

    private static Method deopt;
    private static boolean resolved;

    static void method(Member m) {
        if (m == null) return;
        if (!resolved) {
            resolved = true;
            try {
                deopt = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
                deopt.setAccessible(true);
            } catch (Throwable t) {
                XposedBridge.log("IceHome: este LSPosed no tiene deoptimizeMethod");
            }
        }
        if (deopt == null) return;
        try {
            deopt.invoke(null, m);
        } catch (Throwable ignored) {
        }
    }

    /** Desoptimiza todos los métodos declarados de una clase (envoltorios ofuscados de Kashi). */
    static int all(Class<?> c) {
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                    || java.lang.reflect.Modifier.isNative(m.getModifiers())) continue;
            method(m);
            n++;
        }
        return n;
    }

    private Deopt() {
    }
}
