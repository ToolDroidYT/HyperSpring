package com.icehome.up.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utilidades de reflexión con los mismos nombres que el {@code XposedHelpers} clásico. Con
 * libxposed (API 102) las clases {@code de.robv.android.xposed} no se pueden usar, así que
 * aquí están reescritas las que necesita el módulo. Igual que las originales, lanzan un
 * {@link Error} si no encuentran lo que se pide (cada función del módulo se instala dentro de un
 * {@code try/catch (Throwable)}).
 */
public final class XposedHelpers {

    /** Como en Xposed clásico: es un Error, no una Exception. */
    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String name, Throwable cause) {
            super(name, cause);
        }
    }

    // Se buscan una vez y se guardan: la reflexión es lenta y algunos hooks la usan a menudo.
    // La clave es la propia clase (no su nombre): dos cargadores pueden tener clases homónimas.
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> METHODS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ clases

    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            // Con cl == null, Class.forName busca en las clases del sistema (como Xposed).
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ClassNotFoundError(name, e);
        }
    }

    public static Class<?> findClassIfExists(String name, ClassLoader cl) {
        try {
            return findClass(name, cl);
        } catch (ClassNotFoundError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ métodos y constructores

    private static Method unchecked;
    private static boolean uncheckedResolved;

    /**
     * Los métodos declarados en una clase, aunque alguno use un tipo que no existe en este móvil.
     *
     * <p>{@code Class.getDeclaredMethods()} comprueba los tipos de TODOS los métodos y, si uno
     * solo no se puede cargar, falla entero con NoClassDefFoundError. Pasa con
     * {@code BaseLauncher} del launcher de Kashi, que tiene un método que nombra una clase
     * ({@code NewHomeView}) que no viene en el APK: por eso no se encontraba ninguno de sus
     * métodos. Android tiene por dentro una versión que no comprueba nada
     * ({@code getDeclaredMethodsUnchecked}); se usa como respaldo. Con los métodos que devuelve,
     * pide {@code getParameterCount()} antes que {@code getParameterTypes()}: el segundo sí puede
     * fallar en el método problemático.
     */
    public static Method[] declaredMethods(Class<?> c) {
        if (c == null) return new Method[0];
        try {
            return c.getDeclaredMethods();
        } catch (LinkageError e) {
            // sigue abajo, con la versión que no comprueba los tipos
        }
        try {
            synchronized (XposedHelpers.class) {
                if (!uncheckedResolved) {
                    uncheckedResolved = true;
                    unchecked = Class.class.getDeclaredMethod("getDeclaredMethodsUnchecked", boolean.class);
                    unchecked.setAccessible(true);
                }
            }
            if (unchecked != null) return (Method[]) unchecked.invoke(c, false);
        } catch (Throwable ignored) {
        }
        return new Method[0];
    }

    /** Método declarado en la clase con exactamente esos tipos (Class o nombre de clase). */
    public static Method findMethodExact(Class<?> c, String name, Object... types) {
        try {
            Method m = c.getDeclaredMethod(name, classes(c.getClassLoader(), types));
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(c.getName() + "#" + name);
        }
    }

    public static Constructor<?> findConstructorExact(Class<?> c, Object... types) {
        try {
            Constructor<?> k = c.getDeclaredConstructor(classes(c.getClassLoader(), types));
            k.setAccessible(true);
            return k;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(c.getName() + "#<init>");
        }
    }

    /** Los últimos argumentos son los tipos de los parámetros y, al final, el hook. */
    public static void findAndHookMethod(Class<?> c, String name, Object... typesAndCallback) {
        int n = typesAndCallback.length;
        if (n == 0 || !(typesAndCallback[n - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("falta el hook");
        }
        Object[] types = new Object[n - 1];
        System.arraycopy(typesAndCallback, 0, types, 0, n - 1);
        XposedBridge.hookMethod(findMethodExact(c, name, types), (XC_MethodHook) typesAndCallback[n - 1]);
    }

    public static void findAndHookMethod(String className, ClassLoader cl, String name, Object... typesAndCallback) {
        findAndHookMethod(findClass(className, cl), name, typesAndCallback);
    }

    private static Class<?>[] classes(ClassLoader cl, Object[] types) {
        // Un Class[] pasado con un cast a Object llega como un único argumento. El XposedHelpers
        // clásico lo acepta igual (el varargs se aplana); aquí se aplana también para que una
        // llamada tipo findMethodExact(c, name, (Object) types) no rompa todos los enganches.
        if (types.length == 1 && types[0] instanceof Class<?>[]) types = (Object[]) types[0];
        Class<?>[] out = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            Object t = types[i];
            if (t instanceof Class) out[i] = (Class<?>) t;
            else if (t instanceof String) out[i] = findClass((String) t, cl);
            else throw new IllegalArgumentException("tipo de parámetro no válido: " + t);
        }
        return out;
    }

    /**
     * Llama a un método por su nombre (también privado o heredado), eligiendo el que encaje con
     * los argumentos. Las excepciones del método salen tal cual.
     */
    public static Object callMethod(Object obj, String name, Object... args) {
        Class<?> c = obj.getClass();
        StringBuilder key = new StringBuilder(name);
        for (Object a : args) key.append(',').append(a == null ? "null" : a.getClass().getName());
        ConcurrentHashMap<String, Method> perClass = METHODS.get(c);
        if (perClass == null) {
            METHODS.putIfAbsent(c, new ConcurrentHashMap<String, Method>());
            perClass = METHODS.get(c);
        }
        String k = key.toString();
        Method m = perClass.get(k);
        if (m == null) {
            m = bestMatch(c, name, args);
            perClass.put(k, m);
        }
        try {
            return m.invoke(obj, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    private static Method bestMatch(Class<?> c, String name, Object[] args) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : declaredMethods(k)) {
                if (!m.getName().equals(name) || Modifier.isAbstract(m.getModifiers())) continue;
                if (m.getParameterCount() != args.length) continue;
                try {
                    if (!fits(m.getParameterTypes(), args)) continue;
                } catch (LinkageError e) {
                    continue;                               // usa un tipo que no existe aquí
                }
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodError(c.getName() + "#" + name);
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if (a == null) {
                if (t.isPrimitive()) return false;
            } else if (!box(t).isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    /** int → Integer, etc.: por reflexión los valores primitivos viajan en su clase envoltorio. */
    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == float.class) return Float.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        if (t == char.class) return Character.class;
        return Void.class;
    }

    // ------------------------------------------------------------------ campos

    /** Campo de la clase o de alguna de sus superclases, ya accesible. */
    public static Field findField(Class<?> c, String name) {
        ConcurrentHashMap<String, Field> perClass = FIELDS.get(c);
        if (perClass == null) {
            FIELDS.putIfAbsent(c, new ConcurrentHashMap<String, Field>());
            perClass = FIELDS.get(c);
        }
        Field f = perClass.get(name);
        if (f != null) return f;
        for (Class<?> s = c; s != null; s = s.getSuperclass()) {
            try {
                f = s.getDeclaredField(name);
                f.setAccessible(true);
                perClass.put(name, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError(c.getName() + "#" + name);
    }

    public static Object getObjectField(Object obj, String name) {
        try {
            return findField(obj.getClass(), name).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getIntField(Object obj, String name) {
        try {
            return findField(obj.getClass(), name).getInt(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static float getFloatField(Object obj, String name) {
        try {
            return findField(obj.getClass(), name).getFloat(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getStaticIntField(Class<?> c, String name) {
        try {
            return findField(c, name).getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticIntField(Class<?> c, String name, int value) {
        try {
            findField(c, name).setInt(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticBooleanField(Class<?> c, String name, boolean value) {
        try {
            findField(c, name).setBoolean(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    private XposedHelpers() {
    }
}
