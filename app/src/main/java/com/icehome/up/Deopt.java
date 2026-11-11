package com.icehome.up;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import com.icehome.up.xp.XposedBridge;

/**
 * ART puede incrustar (inline) métodos pequeños dentro de quien los llama al
 * compilar el launcher; entonces el hook nunca se ejecuta. "Desoptimizar" al
 * llamador lo obliga a interpretarse y a pasar otra vez por el método enganchado.
 * Lo hace el motor de hooks que esté en uso (ver com.icehome.up.xp.Engine).
 */
final class Deopt {

    static void method(Member m) {
        if (m == null) return;
        XposedBridge.deoptimizeMethod(m);
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
