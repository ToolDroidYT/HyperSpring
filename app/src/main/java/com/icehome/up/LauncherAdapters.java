package com.icehome.up;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** Launcher adapter registry and active HOME package resolver. */
final class LauncherAdapters {
    private static final LauncherAdapter HYPEROS = new HyperOSLauncherAdapter();
    private static final LauncherAdapter POCO = new PocoLauncherAdapter();

    private LauncherAdapters() {}

    static LauncherAdapter forPackage(String packageName) {
        if (HYPEROS.packageName().equals(packageName)) return HYPEROS;
        if (POCO.packageName().equals(packageName)) return POCO;
        return null;
    }

    static LauncherAdapter resolve(Context context) {
        if (context == null) return HYPEROS;
        PackageManager pm = context.getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo activity = resolved != null ? resolved.activityInfo : null;
            LauncherAdapter selected = activity != null ? forPackage(activity.packageName) : null;
            if (selected != null) return selected;
        } catch (Throwable ignored) {}
        if (isInstalled(pm, POCO.packageName())) return POCO;
        if (isInstalled(pm, HYPEROS.packageName())) return HYPEROS;
        return HYPEROS;
    }

    static String resolvePackage(Context context) {
        return resolve(context).packageName();
    }

    private static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
