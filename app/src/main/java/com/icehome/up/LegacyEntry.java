package com.icehome.up;

import com.icehome.up.xp.LegacyEngine;
import com.icehome.up.xp.XposedBridge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entrada por la API clásica de Xposed (93), para LSPosed antiguos. Está apuntada en
 * {@code assets/xposed_init}. Solo pone el motor antiguo y pasa el aviso a {@link MainHook},
 * que es común a las dos entradas.
 */
public class LegacyEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (XposedBridge.engine() == null) XposedBridge.setEngine(new LegacyEngine());
        MainHook.onPackage(lpparam.packageName, lpparam.processName, lpparam.classLoader,
                lpparam.appInfo != null ? lpparam.appInfo.dataDir : null);
    }
}
