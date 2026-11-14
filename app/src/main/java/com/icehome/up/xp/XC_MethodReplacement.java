package com.icehome.up.xp;

/** Un hook que sustituye el método entero: el original no se ejecuta (salvo que lo llames tú). */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    @Override
    protected final void beforeHookedMethod(MethodHookParam p) {
        try {
            p.setResult(replaceHookedMethod(p));
        } catch (Throwable t) {
            p.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam p) {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam p) throws Throwable;

    /** Un hook que hace que el método devuelva siempre {@code result}. */
    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam p) {
                return result;
            }
        };
    }
}
