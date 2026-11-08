package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static float getFloatField(Object obj, String fieldName) { return 0; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static boolean getBooleanField(Object obj, String fieldName) { return false; }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static int getStaticIntField(Class<?> clazz, String fieldName) { return 0; }
    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {}
    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {}
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static java.lang.reflect.Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) { return null; }
    public static java.lang.reflect.Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) { return null; }
    public static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) { return null; }
}
