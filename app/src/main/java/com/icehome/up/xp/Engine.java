package com.icehome.up.xp;

import java.lang.reflect.Member;

/**
 * Un "motor" de hooks: lo que de verdad engancha los métodos. Hay dos, porque el módulo puede
 * cargarse de dos maneras y cada una ofrece una API distinta:
 * <ul>
 * <li>{@link LegacyEngine}: la API clásica de Xposed (93, {@code de.robv.android.xposed}).</li>
 * <li>{@link ModernEngine}: libxposed (API 101/102, {@code io.github.libxposed.api}).</li>
 * </ul>
 * El resto del módulo no habla con ninguna de las dos: usa {@link XposedBridge} y
 * {@link XposedHelpers} de este paquete, que llaman al motor que esté puesto.
 */
public interface Engine {

    /** Nombre para el registro, p. ej. «libxposed 102 (Vector v2.2)». */
    String name();

    /** Engancha un método o constructor. */
    void hook(Member m, XC_MethodHook callback);

    /** Llama al método original, saltándose los hooks. */
    Object invokeOriginal(Member m, Object thisObject, Object[] args) throws Throwable;

    /** Pide a ART que no incruste (inline) los métodos que llama {@code m}. */
    boolean deoptimize(Member m);

    void log(String text);

    void log(Throwable t);
}
