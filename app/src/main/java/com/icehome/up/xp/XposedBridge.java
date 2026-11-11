package com.icehome.up.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Fachada de hooks del módulo. Tiene los mismos nombres que el {@code XposedBridge} clásico, pero
 * reenvía cada llamada al motor que se puso al cargar el módulo ({@link Engine}): la API antigua
 * (93) o libxposed (101/102). Así el resto del código no sabe ni le importa cuál se está usando.
 */
public final class XposedBridge {

    private static volatile Engine engine;

    /** Lo llama el punto de entrada (LegacyEntry o ModernEntry) antes que nada. */
    public static void setEngine(Engine e) {
        engine = e;
    }

    public static Engine engine() {
        return engine;
    }

    public static void log(String text) {
        Engine e = engine;
        if (e != null) e.log(text);
        else android.util.Log.i("HyperSpring", text);
    }

    public static void log(Throwable t) {
        Engine e = engine;
        if (e != null) e.log(t);
        else android.util.Log.e("HyperSpring", "error", t);
    }

    /** Engancha un método o constructor concreto. */
    public static void hookMethod(Member m, XC_MethodHook callback) {
        if (m == null) throw new NullPointerException("método nulo");
        if (callback == null) throw new NullPointerException("callback nulo");
        if (m instanceof Method && Modifier.isAbstract(m.getModifiers())) {
            throw new IllegalArgumentException("no se puede enganchar un método abstracto: " + m);
        }
        engine.hook(m, callback);
    }

    /** Engancha todos los métodos con ese nombre declarados en la clase. Devuelve los enganchados. */
    public static Set<Member> hookAllMethods(Class<?> c, String name, XC_MethodHook callback) {
        Set<Member> done = new HashSet<>();
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name) || Modifier.isAbstract(m.getModifiers())) continue;
            hookMethod(m, callback);
            done.add(m);
        }
        return done;
    }

    /** Engancha todos los constructores de la clase. */
    public static Set<Member> hookAllConstructors(Class<?> c, XC_MethodHook callback) {
        Set<Member> done = new HashSet<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            hookMethod(k, callback);
            done.add(k);
        }
        return done;
    }

    /** Llama al método original sin pasar por los hooks. */
    public static Object invokeOriginalMethod(Member m, Object thisObject, Object[] args) throws Throwable {
        return engine.invokeOriginal(m, thisObject, args == null ? new Object[0] : args);
    }

    /**
     * ART puede incrustar (inline) un método pequeño dentro de quien lo llama, y entonces su hook
     * no se ejecuta nunca. Desoptimizar al que llama lo evita.
     */
    public static boolean deoptimizeMethod(Member m) {
        try {
            return engine.deoptimize(m);
        } catch (Throwable t) {
            return false;
        }
    }

    private XposedBridge() {
    }
}
