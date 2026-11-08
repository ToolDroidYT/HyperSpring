package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public synchronized static void log(String text) {}
    public synchronized static void log(Throwable t) {}
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, java.lang.reflect.InvocationTargetException { return null; }
}
