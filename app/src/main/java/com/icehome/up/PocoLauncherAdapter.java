package com.icehome.up;

/**
 * Adapter for POCO Launcher. Kashi's WidgetStack and ResizeHandle extension classes
 * are absent from the analyzed POCO Launcher APK, so those integrations are disabled.
 */
final class PocoLauncherAdapter implements LauncherAdapter {
    @Override public String packageName() { return "com.mi.android.globallauncher"; }
    @Override public String displayName() { return "POCO Launcher"; }
    @Override public boolean supportsWidgetStack() { return false; }
    @Override public boolean supportsFolderMorph() { return false; }
}
