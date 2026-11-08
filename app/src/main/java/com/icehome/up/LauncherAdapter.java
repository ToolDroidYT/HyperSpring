package com.icehome.up;

import android.content.Context;

/** Launcher-specific compatibility boundary. */
interface LauncherAdapter {
    String packageName();
    String displayName();
    boolean supportsWidgetStack();
    boolean supportsFolderMorph();

    default void install(Context context, ClassLoader classLoader) {
        MainHook.installLauncherFeatures(context, classLoader, this);
    }
}
