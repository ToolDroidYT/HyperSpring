package com.icehome.up.xp;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * Motor para la API clásica de Xposed (93): traduce nuestros hooks a los de
 * {@code de.robv.android.xposed}. Es el único sitio del módulo (junto con LegacyEntry) que
 * nombra esas clases; con libxposed no existen, y por eso esta clase solo se carga cuando el
 * módulo entra por la API antigua.
 */
public final class LegacyEngine implements Engine {

    private Method deopt;
    private boolean deoptResolved;

    @Override
    public String name() {
        return "API 93 (Xposed clásico)";
    }

    @Override
    public void hook(Member m, final XC_MethodHook cb) {
        de.robv.android.xposed.XposedBridge.hookMethod(m, new de.robv.android.xposed.XC_MethodHook() {
            // Xposed clásico comparte un solo "param" entre todos los hooks del mismo método;
            // el nuestro se guarda dentro con una clave propia de este hook.
            private final String key = "hs" + System.identityHashCode(this);

            @Override
            protected void beforeHookedMethod(MethodHookParam lp) throws Throwable {
                XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                p.method = lp.method;
                p.thisObject = lp.thisObject;
                p.args = lp.args;
                lp.setObjectExtra(key, p);
                cb.beforeHookedMethod(p);
                lp.args = p.args;
                back(p, lp);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam lp) throws Throwable {
                XC_MethodHook.MethodHookParam p = (XC_MethodHook.MethodHookParam) lp.getObjectExtra(key);
                if (p == null) {
                    p = new XC_MethodHook.MethodHookParam();
                    p.method = lp.method;
                    p.thisObject = lp.thisObject;
                    p.args = lp.args;
                }
                p.result = lp.getResult();
                p.throwable = lp.getThrowable();
                p.returnEarly = false;
                cb.afterHookedMethod(p);
                back(p, lp);
            }

            /** Si nuestro hook puso un resultado o una excepción, se lo pasa a Xposed. */
            private void back(XC_MethodHook.MethodHookParam p, MethodHookParam lp) {
                if (!p.returnEarly) return;
                if (p.throwable != null) lp.setThrowable(p.throwable);
                else lp.setResult(p.result);
            }
        });
    }

    @Override
    public Object invokeOriginal(Member m, Object thisObject, Object[] args) throws Throwable {
        return de.robv.android.xposed.XposedBridge.invokeOriginalMethod(m, thisObject, args);
    }

    /**
     * deoptimizeMethod no es de la API clásica: lo añadió LSPosed. Se busca por reflexión y, si
     * este Xposed no lo tiene, simplemente no se hace.
     */
    @Override
    public synchronized boolean deoptimize(Member m) {
        if (!deoptResolved) {
            deoptResolved = true;
            try {
                deopt = de.robv.android.xposed.XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
                deopt.setAccessible(true);
            } catch (Throwable t) {
                log("HyperSpring: este LSPosed no tiene deoptimizeMethod");
            }
        }
        if (deopt == null) return false;
        try {
            deopt.invoke(null, m);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void log(String text) {
        de.robv.android.xposed.XposedBridge.log(text);
    }

    @Override
    public void log(Throwable t) {
        de.robv.android.xposed.XposedBridge.log(t);
    }
}
