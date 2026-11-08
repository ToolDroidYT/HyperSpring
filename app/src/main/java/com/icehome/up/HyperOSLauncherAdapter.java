package com.icehome.up;

/** Adapter for Xiaomi's system launcher. */
final class HyperOSLauncherAdapter implements LauncherAdapter {
    @Override public String packageName() { return "com.miui.home"; }
    @Override public String displayName() { return "HyperOS Launcher"; }
    @Override public boolean supportsWidgetStack() { return true; }
    @Override public boolean supportsFolderMorph() { return true; }
}
