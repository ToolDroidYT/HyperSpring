package com.icehome.up;

import com.icehome.up.xp.ModernEngine;
import com.icehome.up.xp.XposedBridge;

import io.github.libxposed.api.XposedModule;

/**
 * Entrada por libxposed (API 101/102), para Vector y LSPosed actuales. Está apuntada en
 * {@code META-INF/xposed/java_init.list}. El framework crea un objeto de esta clase en cada
 * proceso enganchado y va llamando a estos métodos:
 * <ol>
 * <li>{@link #onModuleLoaded}: el módulo acaba de cargarse en el proceso.</li>
 * <li>{@link #onPackageReady}: un paquete (launcher, SystemUI, plugin…) ya tiene su cargador
 * de clases. Equivale al {@code handleLoadPackage} de la API antigua.</li>
 * </ol>
 * Solo pone el motor moderno y pasa el aviso a {@link MainHook}, común a las dos entradas.
 */
public class ModernEntry extends XposedModule {

    private String process;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        // "this" es también la puerta a la API (hook, log, deoptimize…): se la damos al motor.
        XposedBridge.setEngine(new ModernEngine(this));
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        android.content.pm.ApplicationInfo info = param.getApplicationInfo();
        // A diferencia de la API antigua, libxposed avisa también cuando un paquete se carga solo
        // por sus recursos, sin código. Su cargador de clases está vacío ("DexPathList[[]...") y
        // ahí no hay nada que enganchar: se ignora, como hacía la API antigua.
        if (!param.isFirstPackage() && String.valueOf(param.getClassLoader()).contains("DexPathList[[]")) {
            return;
        }
        MainHook.onPackage(param.getPackageName(), process, param.getClassLoader(),
                info != null ? info.dataDir : null);
    }
}
