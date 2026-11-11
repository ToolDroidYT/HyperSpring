package com.icehome.up;

import com.icehome.up.xp.XposedBridge;

/**
 * Arranque de HyperSpring en cada proceso enganchado. Lo llaman las dos entradas del módulo:
 * {@link LegacyEntry} (API clásica de Xposed, 93) y {@link ModernEntry} (libxposed, API 102).
 * Reúne lo que antes eran tres módulos:
 * <ul>
 * <li>com.miui.home: animaciones del escritorio (antes Ice Home).</li>
 * <li>com.android.systemui: telón al cambiar de pantalla (antes Telón) y cristal y apertura del
 * centro de control (antes KashiPlus, con miui.systemui.plugin).</li>
 * </ul>
 * Si sigue instalado alguno de los módulos viejos, su parte no se carga aquí (dos ganchos sobre lo
 * mismo romperían la interfaz).
 */
public class MainHook {

    static final String OLD_TELON = "com.telon.fx", OLD_KASHIPLUS = "com.kashiplus.tweaks";
    /** En SystemUI: el centro de control lo lleva este módulo (sin KashiPlus viejo ni modo seguro). */
    static volatile boolean ccInstalled;
    private static volatile boolean sysuiChecked;
    /** Cargadores del plugin ya enganchados (el plugin puede recargarse con otro cargador). */
    private static final java.util.Map<ClassLoader, Boolean> ccDone = new java.util.WeakHashMap<>();

    private static volatile boolean launcherDone;

    /**
     * Un paquete acaba de cargarse en este proceso.
     *
     * @param pkg         paquete cargado (launcher, SystemUI o su plugin)
     * @param processName nombre del proceso; puede ser null
     * @param cl          cargador de clases del paquete: con él se buscan las clases a enganchar
     * @param dataDir     carpeta de datos del paquete (para el modo seguro del launcher)
     */
    static void onPackage(String pkg, String processName, ClassLoader cl, String dataDir) {
        if ("com.android.systemui".equals(pkg) || "miui.systemui.plugin".equals(pkg) || "com.miui.home".equals(pkg)) {
            // Aún no hay animaciones: el hilo semilla nace con todas las CPU (ver Threads).
            try {
                Threads.init();
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        if ("com.android.systemui".equals(pkg)) {
            systemUi(processName, cl);
            return;
        }
        if ("miui.systemui.plugin".equals(pkg)) {
            // El plugin también se carga en procesos auxiliares (p. ej. :flashlight) sin SystemUI.
            if (processName == null || processName.equals("com.android.systemui")) {
                checkSystemUi();
                installCc(cl);
            }
            return;
        }
        if (!"com.miui.home".equals(pkg)) return;
        if (processName != null && !processName.equals("com.miui.home")) return;
        if (launcherDone) return;                           // una sola vez por proceso
        launcherDone = true;
        XposedBridge.log("IceHome: launcher enganchado, motor " + engineName());
        try {
            Remote.install(false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        // Modo seguro: si el launcher se está cayendo una y otra vez, arranca sin animaciones.
        if (!LauncherSafe.checkAtLoad(dataDir)) return;
        // Los interruptores se leen cuando el launcher ya tiene su contexto: antes, con el del
        // sistema, el proveedor de ajustes rechaza la consulta y todo salía activado.
        final ClassLoader fcl = cl;
        onAttach(new WithContext() {
            @Override
            public void run(android.content.Context c) {
                installLauncher(c, fcl);
            }
        });
    }

    private static void installLauncher(android.content.Context c, ClassLoader cl) {
        Log.i("funciones: genio " + onOff(Tune.on(c, "icehome_genie_on"))
                + ", recientes " + onOff(Tune.on(c, "icehome_curtain_on"))
                + ", pila " + onOff(Tune.on(c, "icehome_stack_on"))
                + ", carpetas " + onOff(Tune.on(c, "icehome_folder_morph_on"))
                + ", cristal carpetas " + onOff(Tune.on(c, "icehome_folder_glass_on")));
        if (Tune.on(c, "icehome_genie_on")) {
            try {
                WindowAnim.install(cl);
                Genie.enabled = true;
                Genie.startThreads();
            } catch (Throwable t) {
                Log.e("no se pudo instalar el genio (¿otra versión del launcher?)", t);
            }
        }
        try {
            WallpaperZoom.install(cl);
        } catch (Throwable t) {
            Log.e("no se pudo instalar el zoom del fondo", t);
        }
        try {
            IconPress.install(cl);
        } catch (Throwable t) {
            Log.e("no se pudo instalar el icono que se hunde", t);
        }
        if (Tune.on(c, "icehome_curtain_on")) {
            try {
                RecentsCurtain.install(cl);
            } catch (Throwable t) {
                Log.e("no se pudo instalar la cortina de recientes", t);
            }
        }
        if (Tune.on(c, "icehome_stack_on")) {
            try {
                StackSwipe.install(cl);
            } catch (Throwable t) {
                XposedBridge.log("IceHome: error instalando StackSwipe");
                XposedBridge.log(t);
            }
        }
        if (Tune.on(c, "icehome_folder_glass_on")) {
            try {
                GlassFolder.install(cl);
            } catch (Throwable t) {
                XposedBridge.log("IceHome: error instalando GlassFolder");
                XposedBridge.log(t);
            }
        }
        if (Tune.on(c, "icehome_folder_morph_on")) {
            try {
                FolderMorph.install(cl);
            } catch (Throwable t) {
                XposedBridge.log("IceHome: error instalando FolderMorph");
                XposedBridge.log(t);
            }
        }
        }

    /** Para el registro: con qué API se ha cargado el módulo aquí. */
    static String engineName() {
        try {
            return XposedBridge.engine().name();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String onOff(boolean on) {
        return on ? "sí" : "no";
    }

    interface WithContext {
        void run(android.content.Context c);
    }

    /** Ejecuta {@code w} una vez, justo después de Application.attach (ya hay contexto propio). */
    static void onAttach(final WithContext w) {
        com.icehome.up.xp.XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
                android.content.Context.class, new com.icehome.up.xp.XC_MethodHook() {
                    private boolean done;

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (done) return;
                        done = true;
                        try {
                            w.run((android.content.Context) p.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ interfaz del sistema

    private static boolean telonOn;

    /** Una vez por proceso de SystemUI: módulos viejos y modo seguro. */
    private static synchronized void checkSystemUi() {
        if (sysuiChecked) return;
        sysuiChecked = true;
        android.content.Context c = com.icehome.up.telon.Tune.systemContext();
        // Modo seguro: si la interfaz se está cayendo una y otra vez, no se carga nada de aquí.
        boolean safe = com.icehome.up.telon.SafeMode.checkAtLoad(c);
        boolean oldTelon = installed(c, OLD_TELON), oldKashi = installed(c, OLD_KASHIPLUS);
        if (oldTelon) com.icehome.up.telon.Log.w("sigue instalado el módulo Telón: desinstálalo; el telón de HyperSpring no se carga");
        if (oldKashi) com.icehome.up.telon.Log.w("sigue instalado KashiPlus: desinstálalo; el centro de control de HyperSpring no se carga");
        telonOn = safe && !oldTelon;
        ccInstalled = safe && !oldKashi;
    }

    private static boolean installed(android.content.Context c, String pkg) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile boolean sysuiDone;

    private static void systemUi(String processName, ClassLoader cl) {
        // Solo el proceso principal y una vez: LSPosed puede avisar de SystemUI también en otros
        // procesos suyos (sin nombre de proceso); ahí no hay WMShell y además contaba como otro
        // arranque para el modo seguro (que saltaba con dos reinicios en vez de tres).
        String proc = processName;
        if (proc == null) {
            try {
                proc = android.app.Application.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        if (proc != null && !proc.equals("com.android.systemui")) return;
        if (sysuiDone) return;
        sysuiDone = true;
        XposedBridge.log("IceHome: SystemUI enganchado, motor " + engineName());
        try {
            Remote.install(true);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        checkSystemUi();
        if (telonOn) {
            final ClassLoader fcl = cl;
            onAttach(new WithContext() {
                @Override
                public void run(android.content.Context c) {
                    boolean on = com.icehome.up.telon.Tune.on(c, "telon_on");
                    com.icehome.up.telon.Log.i("telón " + onOff(on));
                    if (!on) return;
                    try {
                        com.icehome.up.telon.Telon.install(fcl);
                    } catch (Throwable t) {
                        com.icehome.up.telon.Log.e("no se pudo instalar el telón (¿otra versión de HyperOS?)", t);
                    }
                }
            });
        }
        if (ccInstalled) {
            try {
                com.icehome.up.cc.CcGlass.installFramework();
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            watchPluginLoader();
        }
    }

    private static synchronized void installCc(ClassLoader cl) {
        if (!ccInstalled || ccDone.containsKey(cl)) return;
        ccDone.put(cl, Boolean.TRUE);
        try {
            com.icehome.up.cc.CcGlass.install(cl);
        } catch (Throwable t) {
            XposedBridge.log("IceHome: error instalando el centro de control");
            XposedBridge.log(t);
        }
    }

    /**
     * Respaldo: si este LSPosed no avisa con el paquete del plugin, se detecta el cargador de
     * clases que SystemUI crea para MIUISystemUIPlugin.apk.
     */
    private static void watchPluginLoader() {
        try {
            XposedBridge.hookAllConstructors(dalvik.system.BaseDexClassLoader.class, new com.icehome.up.xp.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object path = p.args.length > 0 ? p.args[0] : null;
                    if (!(path instanceof String)) return;
                    String s = (String) path;
                    if (!s.contains("SystemUIPlugin") && !s.contains("miui.systemui.plugin")) return;
                    ClassLoader cl = (ClassLoader) p.thisObject;
                    try {
                        cl.loadClass("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView");
                    } catch (Throwable t) {
                        return;
                    }
                    installCc(cl);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
