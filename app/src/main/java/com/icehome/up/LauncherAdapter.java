package com.icehome.up;

import android.content.Context;

/** Launcher-specific compatibility boundary. */
interface LauncherAdapter {
    String packageName();
    String displayName();
    boolean supportsWidgetStack();
    boolean supportsFolderMorph();

    /** Whether task captures should omit the transition leash and capture only its children. */
    default boolean captureChildrenOnly() { return true; }

    default void install(Context context, ClassLoader classLoader) {
        MainHook.installLauncherFeatures(context, classLoader, this);
    }
}
