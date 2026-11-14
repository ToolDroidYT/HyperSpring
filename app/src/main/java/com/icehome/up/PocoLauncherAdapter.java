package com.icehome.up;

/**
 * Adapter for POCO Launcher ({@code com.mi.android.globallauncher}).
 *
 * <p>The analyzed build (RELEASE-7.50.06.2597) is a Xiaomi launcher that keeps the shared
 * {@code com.miui.home.*} hooks, but it ships neither Kashi's {@code com.kx.home.WidgetStack}
 * nor {@code com.kx.home.ResizeHandle} extensions, and it has no equivalent widget stack or
 * folder drag handle (folders are resized from the folder sheet instead). Both integrations
 * therefore stay disabled. Transition roots are blank container layers, so the default
 * child-only task capture applies.
 */
final class PocoLauncherAdapter implements LauncherAdapter {
    @Override public String packageName() { return "com.mi.android.globallauncher"; }
    @Override public String displayName() { return "POCO Launcher"; }
    @Override public boolean supportsWidgetStack() { return false; }
    @Override public boolean supportsFolderMorph() { return false; }
}
