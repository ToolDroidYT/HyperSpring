package com.icehome.up;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Ajustes de HyperSpring. Se guardan en la app (PrefsProvider) y se avisa al launcher y a la
 * interfaz del sistema con una difusión protegida: se aplican al momento y sin root.
 * <p>
 * Interfaz hecha en código, con colores del sistema (Monet): cuatro pestañas (Inicio, Escritorio,
 * Sistema y Acerca de) en una barra flotante de cristal líquido ({@link GlassBar}); cada pestaña
 * es una lista de grupos con icono y flecha que abren su página de ajustes (deslizadores e
 * interruptores propios, {@link Controls}), con su título grande y botón de volver.
 */
public class SettingsActivity extends Activity {

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    // Colores (Monet en Android 12+; si no, unos fijos parecidos).
    private int bg, card, pressed, text, text2, text3, accent, onAccent, accentSoft, track, warn;

    private final List<Runnable> refreshers = new ArrayList<>();
    private float dp;
    private FrameLayout stack;
    private GlassBar bar;
    private int insetTop, barSpace;
    /** La próxima página que se cree lleva los brillos de color de «Acerca de». */
    private boolean glowNext;
    private int tab;
    /** Páginas abiertas en la pestaña actual (la primera es la raíz). */
    private final List<ScrollView> pages = new ArrayList<>();

    private String[] tabs() {
        return new String[]{I18n.t("Inicio", "Home"), I18n.t("Escritorio", "Launcher"),
                I18n.t("Sistema", "System"), I18n.t("Acerca de", "About")};
    }
    private static final int[] TAB_ICONS = {Icons.HOME, Icons.GRID, Icons.PHONE, Icons.INFO};

    /** Una sección de un Tune.ITEMS: su nombre y sus filas. */
    private static final class Section {
        final String name;
        final List<Object[]> items = new ArrayList<>();

        Section(String name) {
            this.name = name;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        dp = getResources().getDisplayMetrics().density;
        palette();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        stack = new FrameLayout(this);
        root.addView(stack, new FrameLayout.LayoutParams(-1, -1));

        bar = new GlassBar(this, tabs(), TAB_ICONS, (card & 0x00FFFFFF) | 0x55000000, text2, accent);
        LiveBackground.setOnFrame(new Runnable() {
            @Override
            public void run() {
                bar.invalidate();                      // la barra refracta el fondo que se mueve
            }
        });
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, bar.heightFor(66), Gravity.BOTTOM);
        blp.leftMargin = px(16);
        blp.rightMargin = px(16);
        root.addView(bar, blp);
        // La barra ve todo el contenedor de páginas (no solo la de arriba): en las transiciones,
        // con dos páginas a la vez, no se queda media barra vacía.
        bar.setSource(stack);
        bar.setOnSelect(new GlassBar.OnSelect() {
            @Override
            public void onSelect(int index) {
                showTab(index, true);
            }
        });
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int top = in.getSystemWindowInsetTop(), bottom = in.getSystemWindowInsetBottom();
                blp.bottomMargin = bottom + px(2);
                bar.setLayoutParams(blp);
                if (top != insetTop || barSpace == 0) {
                    insetTop = top;
                    barSpace = bottom + px(2) + bar.heightFor(66);
                    for (ScrollView p : pages) pad(p);
                }
                return in;
            }
        });
        setContentView(root);
        showTab(0, false);
        bar.select(0, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
        for (Runnable r : refreshers) r.run();
        LiveBackground.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LiveBackground.pause();
    }

    @Override
    protected void onDestroy() {
        LiveBackground.setOnFrame(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pages.size() > 1) pop();
        else if (tab != 0) {
            bar.select(0, true);
            showTab(0, true);
        } else super.onBackPressed();
    }

    // ---------------------------------------------------------------- colores
    private void palette() {
        if (Build.VERSION.SDK_INT >= 31) {
            int n1900 = getColor(android.R.color.system_neutral1_900);
            int n1800 = getColor(android.R.color.system_neutral1_800);
            bg = mix(n1900, Color.BLACK, 0.35f);                    // negro suave, con el tono del sistema
            card = mix(n1900, n1800, 0.62f);
            pressed = (getColor(android.R.color.system_neutral1_700) & 0x00FFFFFF) | 0x66000000;
            text = getColor(android.R.color.system_neutral1_50);
            text2 = getColor(android.R.color.system_neutral2_300);
            text3 = getColor(android.R.color.system_neutral2_500);
            accent = getColor(android.R.color.system_accent1_200);
            onAccent = getColor(android.R.color.system_accent1_800);
            accentSoft = (getColor(android.R.color.system_accent1_200) & 0x00FFFFFF) | 0x24000000;
            track = getColor(android.R.color.system_neutral2_700);
            warn = mix(getColor(android.R.color.system_accent3_900), n1900, 0.25f);
        } else {
            bg = 0xFF111114;
            card = 0xFF202127;
            pressed = 0x66373841;
            text = 0xFFE9E9EE;
            text2 = 0xFFA6A8B3;
            text3 = 0xFF767884;
            accent = 0xFFCDBDFF;
            onAccent = 0xFF34215F;
            accentSoft = 0x24CDBDFF;
            track = 0xFF45464F;
            warn = 0xFF33242C;
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ---------------------------------------------------------------- navegación
    private void showTab(int index, boolean animate) {
        tab = index;
        refreshers.clear();
        logText = null;
        final List<ScrollView> old = new ArrayList<>(pages);
        pages.clear();
        ScrollView p;
        switch (index) {
            case 1:
                p = desktopTab();
                break;
            case 2:
                p = systemTab();
                break;
            case 3:
                p = aboutTab();
                break;
            default:
                p = homeTab();
                break;
        }
        pages.add(p);
        stack.addView(p);
        if (animate) {
            p.setAlpha(0f);
            p.setTranslationY(px(14));
            p.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(EASE).setUpdateListener(barTick()).start();
        }
        for (ScrollView o : old) stack.removeView(o);
    }

    private void push(ScrollView p) {
        final ScrollView under = pages.get(pages.size() - 1);
        pages.add(p);
        stack.addView(p);
        p.setTranslationX(stack.getWidth());
        p.animate().translationX(0f).setDuration(340).setInterpolator(EASE).setUpdateListener(barTick()).start();
        under.animate().translationX(-stack.getWidth() * 0.25f).alpha(0f).setDuration(340).setInterpolator(EASE).start();
    }

    private void pop() {
        final ScrollView top = pages.remove(pages.size() - 1);
        ScrollView under = pages.get(pages.size() - 1);
        under.animate().translationX(0f).alpha(1f).setDuration(320).setInterpolator(EASE).setUpdateListener(barTick()).start();
        top.animate().translationX(stack.getWidth()).setDuration(320).setInterpolator(EASE).withEndAction(new Runnable() {
            @Override
            public void run() {
                stack.removeView(top);
            }
        }).start();
        if (top == logPage) logText = null;
    }

    private ValueAnimator.AnimatorUpdateListener barTick() {
        return new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                bar.invalidate();
            }
        };
    }

    /** Una página: título grande (y botón de volver si no es la raíz) y su contenido. */
    private ScrollView page(String title, String subtitle, boolean back, LinearLayout content) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(px(52));
        if (back) {
            ImageView b = new ImageView(this);
            b.setImageDrawable(new Icons(Icons.BACK, text));
            b.setPadding(px(10), px(10), px(10), px(10));
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed), round(card, px(22)), null));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
            top.addView(b, new LinearLayout.LayoutParams(px(44), px(44)));
        }
        if (back || title != null) list.addView(top);

        if (title != null) {
            TextView t = text(title, 34, text);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setPadding(px(8), px(10), px(8), 0);
            list.addView(t);
        }
        if (subtitle != null) {
            TextView s = text(subtitle, 15, text2);
            s.setLineSpacing(0, 1.15f);
            s.setPadding(px(8), px(6), px(8), 0);
            list.addView(s);
        }
        list.addView(content);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        new LiveBackground(bg, getResources().getDisplayMetrics().density, glowNext).attachTo(sv);
        glowNext = false;
        sv.addView(list);
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                bar.invalidate();
            }
        });
        pad(sv);
        return sv;
    }

    private void pad(ScrollView sv) {
        View list = sv.getChildAt(0);
        if (list != null) list.setPadding(px(16), insetTop + px(8), px(16), barSpace + px(16));
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    // ---------------------------------------------------------------- pestañas
    private ScrollView homeTab() {
        LinearLayout c = column();
        StringBuilder old = new StringBuilder();
        if (installed(MainHook.OLD_TELON)) old.append("Telón");
        if (installed(MainHook.OLD_KASHIPLUS)) old.append(old.length() > 0 ? I18n.t(" y KashiPlus", " and KashiPlus") : "KashiPlus");
        if (old.length() > 0) {
            c.addView(notice(I18n.t("Sigue instalado " + old + ". HyperSpring ya lo incluye: desinstálalo (y quítalo de "
                            + "LSPosed) y reinicia. Mientras tanto, esa parte no se carga.",
                    old + " is still installed. HyperSpring already includes it: uninstall it (and remove it from "
                            + "LSPosed) and reboot. Until then, that part is not loaded."), null, null));
        }
        if (com.icehome.up.telon.SafeMode.active(this)) {
            c.addView(notice(I18n.t("Modo seguro: la interfaz del sistema se reinició varias veces seguidas y el telón y el "
                            + "centro de control se han desactivado solos. Si no fue por ellos, reactívalos y reinicia la interfaz.",
                    "Safe mode: System UI restarted several times in a row, so the curtain and the control center were "
                            + "turned off automatically. If they weren't the cause, turn them back on and restart System UI."),
                    I18n.t("Reactivar", "Turn back on"), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            send(Remote.RESET_SAFE);
                            toast(I18n.t("Reactivado: reinicia la interfaz", "Turned on: restart System UI"));
                        }
                    }));
        }

        if ("1".equals(state("launcher_safe"))) {
            String launcherSafeMessage = I18n.t(
                    "Modo seguro del escritorio: HyperSpring ha desactivado sus animaciones para proteger POCO Launcher "
                            + "tras detectar varios cierres por fallo o ANR. El resto del módulo sigue activo.",
                    "Launcher safe mode: HyperSpring disabled its launcher animations to protect POCO Launcher after "
                            + "detecting repeated crashes or ANRs. The rest of the module remains active.");
            String launcherSafeDetails = state("launcher_safe_details");
            if (launcherSafeDetails != null && !launcherSafeDetails.trim().isEmpty()) {
                launcherSafeMessage += "\n\n" + launcherSafeDetails;
            }
            c.addView(notice(launcherSafeMessage,
                    I18n.t("Reactivar", "Reactivate"), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            send(Remote.RESET_SAFE);
                            toast(I18n.t("Reactivado: reinicia el launcher", "Reactivated: restart the launcher"));
                        }
                    }));
        }
        String stale = staleParts();
        if (stale != null) {
            c.addView(notice(I18n.t("Has actualizado HyperSpring, pero " + stale + " sigue con la versión anterior. "
                            + "Reinícialo (o reinicia el móvil) para cargar la nueva.",
                    "You updated HyperSpring, but " + stale + " is still running the previous version. "
                            + "Restart it (or reboot the phone) to load the new one."), null, null));
        } else if (state("launcher_seen") == null && state("systemui_seen") == null) {
            c.addView(notice(I18n.t("Todavía no se ha detectado HyperSpring funcionando. Si acabas de instalarlo o actualizarlo, "
                            + "reinicia el launcher y la interfaz (abajo) o el móvil. Si sigue igual, revisa en LSPosed que el "
                            + "módulo y sus ámbitos (Launcher del sistema e Interfaz del sistema) estén activos.",
                    "HyperSpring hasn't been detected running yet. If you just installed or updated it, restart the "
                            + "launcher and System UI (below) or reboot. If nothing changes, check in LSPosed that the module "
                            + "and its scopes (System launcher and System UI) are enabled."),
                    null, null));
        }

        c.addView(label(I18n.t("Funciones", "Features")));
        LinearLayout f = card();
        f.addView(toggleRow(Icons.SPARKLE, item(Tune.ITEMS, "icehome_genie_on"), I18n.t("Abrir y cerrar apps", "Opening and closing apps")));
        f.addView(toggleRow(Icons.LAYERS, item(Tune.ITEMS, "icehome_curtain_on"), I18n.t("Recientes que se doblan al deslizarlas", "Recents that bend as you swipe")));
        LauncherAdapter activeAdapter = LauncherAdapters.resolve(this);
        if (activeAdapter.supportsWidgetStack()) {
            f.addView(toggleRow(Icons.WIDGETS, item(Tune.ITEMS, "icehome_stack_on"), I18n.t("Pila de widgets con baraja", "Shuffling widget stack")));
        }
        if (activeAdapter.supportsFolderMorph()) {
            f.addView(toggleRow(Icons.FOLDER, item(Tune.ITEMS, "icehome_folder_morph_on"), I18n.t("Carpetas con muelle", "Springy folders")));
        }
        f.addView(toggleRow(Icons.DROP, item(Tune.ITEMS, "icehome_folder_glass_on"), I18n.t("Cristal en las carpetas", "Glass folders")));
        f.addView(toggleRow(Icons.CURTAIN, item(com.icehome.up.telon.Tune.ITEMS, "telon_on"), I18n.t("Telón al cambiar de pantalla", "Curtain when switching screens")));
        f.addView(toggleRow(Icons.SLIDERS, item(com.icehome.up.cc.Tune.ITEMS, "kashiplus_cc_on"), I18n.t("Cristal en el centro de control", "Glass control center")));
        c.addView(f);

        c.addView(label(I18n.t("Acciones", "Actions")));
        LinearLayout a = card();
        a.addView(navRow(Icons.RESTART, I18n.t("Reiniciar launcher", "Restart launcher"),
                I18n.t("Aplica los cambios de funciones del escritorio", "Applies launcher feature changes"), false, new Runnable() {
            @Override
            public void run() {
                restart(false);
            }
        }));
        a.addView(navRow(Icons.RESTART, I18n.t("Reiniciar interfaz del sistema", "Restart System UI"),
                I18n.t("Telón y centro de control", "Curtain and control center"), false, new Runnable() {
            @Override
            public void run() {
                restart(true);
            }
        }));
        a.addView(navRow(Icons.LIST, I18n.t("Registro", "Log"),
                I18n.t("Lo que anotan el launcher y la interfaz", "What the launcher and System UI write down"), true, new Runnable() {
            @Override
            public void run() {
                push(logPage());
            }
        }));
        c.addView(a);
        if (!getSharedPreferences("app", 0).getBoolean("root_ok", false)) {
            c.addView(warning(I18n.t("Los botones de reiniciar necesitan que concedas root a HyperSpring (Magisk, KernelSU o "
                            + "APatch). Sin root, se intenta a través de la interfaz del sistema, pero puede no funcionar en "
                            + "todos los móviles; en ese caso, reinicia el móvil.",
                    "The restart buttons need root access granted to HyperSpring (Magisk, KernelSU or APatch). Without "
                            + "root, HyperSpring tries through System UI, but that may not work on every phone; if it doesn't, "
                            + "reboot the phone.")));
        }
        return page("HyperSpring", I18n.t("Animaciones y cristal líquido para HyperOS. Toca una función para ver qué hace.",
                "Animations and liquid glass for HyperOS. Tap a feature to see what it does."), false, c);
    }

    private ScrollView desktopTab() {
        LinearLayout c = column();
        c.addView(label(I18n.t("Animaciones", "Animations")));
        LinearLayout g = card();
        for (Section s : sections(Tune.ITEMS)) {
            if (s.name.startsWith("Funciones")) continue;            // están en Inicio
            g.addView(sectionRow(s));
        }
        c.addView(g);
        return page(I18n.t("Escritorio", "Launcher"), I18n.t("Animaciones del launcher (com.miui.home). Probado con HyperOS Launcher Mod 7.5 de Mods Center; también con el de serie.",
                "Launcher animations (com.miui.home). Tested with HyperOS Launcher Mod 7.5 from Mods Center; also works with the stock one."), false, c);
    }

    private ScrollView systemTab() {
        LinearLayout c = column();
        c.addView(label(I18n.t("Transiciones", "Transitions")));
        LinearLayout t = card();
        for (Section s : sections(com.icehome.up.telon.Tune.ITEMS)) {
            if (s.name.startsWith("Función")) continue;
            t.addView(sectionRow(s));
        }
        c.addView(t);
        c.addView(label(I18n.t("Centro de control", "Control center")));
        LinearLayout cc = card();
        for (Section s : sections(com.icehome.up.cc.Tune.ITEMS)) {
            if (s.name.equals("General")) continue;
            cc.addView(sectionRow(s));
        }
        c.addView(cc);
        return page(I18n.t("Sistema", "System"), I18n.t("Transiciones de la interfaz y cristal del centro de control. Probado con el iOS Control Center de Mods Center; también con el de serie.",
                "System UI transitions and control center glass. Tested with the iOS Control Center from Mods Center; also works with the stock one."), false, c);
    }

    private ScrollView aboutTab() {
        LinearLayout c = column();
        c.addView(hero());

        c.addView(warning(I18n.t("HyperSpring solo se ha probado en un Redmi 14C con HyperOS 3 (Android 16). No puedo "
                        + "asegurar que funcione en otros dispositivos. Si algo falla, abre un issue en GitHub con tu móvil, "
                        + "la versión de HyperOS y del launcher, y el registro (botón de abajo).",
                "HyperSpring has only been tested on a Redmi 14C with HyperOS 3 (Android 16). I can't guarantee it "
                        + "works on other devices. If something breaks, open an issue on GitHub with your phone, HyperOS and "
                        + "launcher versions, and the log (button below).")));
        LinearLayout lb = new LinearLayout(this);
        lb.setPadding(0, px(12), 0, 0);
        lb.addView(button(I18n.t("Copiar registro", "Copy log"), true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        c.addView(lb);

        c.addView(label(I18n.t("Compatibilidad", "Compatibility")));
        LinearLayout k = glassCard();
        k.addView(infoRow(Icons.PHONE, I18n.t("Este móvil", "This phone"), Build.MANUFACTURER + " " + Build.MODEL + " · " + systemVersion()));
        k.addView(statusRow(Icons.HOME, I18n.t("Escritorio", "Launcher"), appVersion(LauncherAdapters.resolvePackage(this)), "launcher_seen"));
        k.addView(statusRow(Icons.CURTAIN, I18n.t("Interfaz del sistema", "System UI"), appVersion("com.android.systemui"), "systemui_seen"));
        String cc = state("cc_seen");
        String ccKind = cc == null ? null : cc.endsWith("|ios") ? I18n.t("mod iOS Control Center", "iOS Control Center mod")
                : I18n.t("centro de control de serie", "stock control center");
        String plugin = appVersion("miui.systemui.plugin");
        k.addView(statusRow(Icons.TOGGLES, I18n.t("Centro de control", "Control center"),
                ccKind != null ? ccKind + (plugin != null ? " · " + plugin : "") : plugin, "cc_seen"));
        c.addView(k);
        TextView hint = text(I18n.t("«Activo»: HyperSpring se ha cargado ahí desde el último reinicio. Si acabas de instalarlo "
                        + "o actualizarlo, reinicia el launcher y la interfaz desde Inicio (o el móvil).",
                "\u201cActive\u201d: HyperSpring has loaded there since the last reboot. If you just installed or updated "
                        + "it, restart the launcher and System UI from Home (or reboot)."), 13, 0xB3FFFFFF);
        hint.setPadding(px(10), px(10), px(10), 0);
        c.addView(hint);

        c.addView(label(I18n.t("Requisitos", "Requirements")));
        LinearLayout q = glassCard();
        q.addView(infoRow(Icons.SHIELD, I18n.t("Root y LSPosed", "Root and LSPosed"),
                I18n.t("Magisk, KernelSU o APatch con LSPosed (o un fork actual como JingMatrix/Vector).",
                        "Magisk, KernelSU or APatch with LSPosed (or a current fork such as JingMatrix/Vector).")));
        q.addView(infoRow(Icons.PHONE, I18n.t("HyperOS 2 o 3", "HyperOS 2 or 3"),
                I18n.t("Android 13 o superior. El cristal y la refracción necesitan Android 13+.",
                        "Android 13 or newer. The glass and refraction need Android 13+.")));
        q.addView(infoRow(Icons.LIST, I18n.t("Ámbitos en LSPosed", "LSPosed scopes"),
                I18n.t("Launcher del sistema (com.miui.home) e Interfaz del sistema (com.android.systemui). "
                                + "El centro de control (miui.systemui.plugin) se engancha desde la interfaz.",
                        "System launcher (com.miui.home) and System UI (com.android.systemui). "
                                + "The control center (miui.systemui.plugin) is hooked from System UI.")));
        q.addView(infoRow(Icons.SPARKLE, I18n.t("Probado con", "Tested with"),
                I18n.t("Redmi 14C (Helio G81 Ultra) con HyperOS 3 y los mods de Kashi de Mods Center: HyperOS Launcher "
                                + "Mod 7.5 e iOS Control Center. También funciona con el launcher y el centro de control de serie.",
                        "Redmi 14C (Helio G81 Ultra) on HyperOS 3 with Kashi's Mods Center mods: HyperOS Launcher Mod 7.5 "
                                + "and iOS Control Center. Also works with the stock launcher and control center.")));
        c.addView(q);

        c.addView(label(I18n.t("Créditos", "Credits")));
        LinearLayout cr = glassCard();
        cr.addView(infoRow(Icons.LIGHT, I18n.t("Refracción del borde", "Edge refraction"),
                I18n.t("Lente de Kyant0/AndroidLiquidGlass (Apache-2.0), portada a los sombreadores del cristal y de esta barra.",
                        "Lens from Kyant0/AndroidLiquidGlass (Apache-2.0), ported to the glass shaders and this bar.")));
        cr.addView(infoRow(Icons.CODE, "Mods Center", I18n.t("Los mods de launcher y centro de control sobre los que se ha probado son de Kashi.",
                "The launcher and control center mods it was tested with are by Kashi.")));
        c.addView(cr);

        c.addView(label(I18n.t("Ajustes", "Settings")));
        LinearLayout r = glassCard();
        final String mode = I18n.mode(this);
        String langNow = "es".equals(mode) ? "Español" : "en".equals(mode) ? "English" : I18n.t("Automático (idioma del sistema)", "Automatic (system language)");
        r.addView(navRow(Icons.PALETTE, I18n.t("Idioma", "Language"), langNow, false, new Runnable() {
            @Override
            public void run() {
                // automático → español → inglés → automático
                I18n.setMode(SettingsActivity.this, "auto".equals(mode) ? "es" : "es".equals(mode) ? "en" : "auto");
                recreate();
            }
        }));
        r.addView(navRow(Icons.RESTART, I18n.t("Restablecer todo", "Reset everything"),
                I18n.t("Vuelve todos los ajustes a sus valores por defecto", "Puts every setting back to its default"), false, new Runnable() {
            @Override
            public void run() {
                getSharedPreferences(PrefsProvider.PREFS, 0).edit().clear().commit();
                send(Remote.TUNE);
                for (Runnable rf : refreshers) rf.run();
                toast(I18n.t("Valores por defecto. Reinicia el launcher y la interfaz para las funciones.",
                        "Defaults restored. Restart the launcher and System UI for the features."));
            }
        }));
        c.addView(r);
        glowNext = true;
        return page(null, null, false, c);
    }

    /** Cabecera de «Acerca de»: logo, nombre y versión, sobre el fondo vivo. */
    private View hero() {
        LinearLayout col = column();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, px(72), 0, px(36));
        // Fondo coral algo transparente: los brillos que pasan por detrás lo tiñen.
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(new LogoDrawable(LiveBackground.CORAL, 0xA8));
        col.addView(logo, new LinearLayout.LayoutParams(px(96), px(96)));

        final TextView name = text("HyperSpring", 36, Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, px(22), 0, 0);
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView ver = text(I18n.t("Versión ", "Version ") + version(), 15, 0xCCFFFFFF);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, px(8), 0, 0);
        col.addView(ver, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text(I18n.t("Animaciones y cristal líquido para HyperOS", "Animations and liquid glass for HyperOS"), 14, 0x99FFFFFF);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(px(24), px(4), px(24), 0);
        col.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        return col;
    }

    /** Tarjeta de cristal para «Acerca de»: deja ver el fondo vivo. */
    private LinearLayout glassCard() {
        LinearLayout c = card();
        GradientDrawable g = round(0x1CFFFFFF, px(24));
        g.setStroke(Math.max(1, px(0.7f)), 0x1FFFFFFF);
        c.setBackground(g);
        return c;
    }

    /** Fila de compatibilidad: versión de la app enganchada y si HyperSpring está activo ahí. */
    private View statusRow(int iconKind, String title, String version, String stateKey) {
        LinearLayout r = rowBase();
        r.setBackground(null);
        r.addView(icon(iconKind, text));
        LinearLayout tc = titles(title, version != null ? version : I18n.t("No encontrado", "Not found"));
        if (tc.getChildCount() > 1) {
            TextView v = (TextView) tc.getChildAt(1);
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
        r.addView(tc);
        String st = state(stateKey);
        long when = 0;
        String ver = null;
        if (st != null) {
            String[] parts = st.split("\\|");
            try {
                when = Long.parseLong(parts[0]);
            } catch (Throwable ignored) {
            }
            if (parts.length > 1) ver = parts[1];
        }
        boolean active = when >= bootTime();
        boolean old = active && !"cc_seen".equals(stateKey) && ver != null && !ver.equals(Version.NAME);
        String label = !active ? (when > 0 ? I18n.t("Sin cargar", "Not loaded") : I18n.t("No detectado", "Not detected"))
                : old ? I18n.t("Reiniciar", "Restart") : I18n.t("Activo", "Active");
        TextView pill = text(label, 13, active && !old ? onAccent : text);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setPadding(px(12), px(5), px(12), px(5));
        pill.setBackground(round(active && !old ? accent : track, px(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(12);
        r.addView(pill, lp);
        return r;
    }

    /** Página de una sección: sus ajustes y «Restablecer esta sección». */
    private ScrollView sectionPage(final Section s) {
        LinearLayout c = column();
        c.addView(space(16));
        LinearLayout k = card();
        for (Object[] it : s.items) k.addView(settingRow(it));
        c.addView(k);
        TextView hint = text(I18n.t("Toca una fila para leer qué hace. Mantén pulsada para volver a su valor por defecto.",
                "Tap a row to read what it does. Long-press it to reset it to its default."), 13, text3);
        hint.setPadding(px(10), px(12), px(10), 0);
        c.addView(hint);
        LinearLayout b = new LinearLayout(this);
        b.setPadding(0, px(18), 0, 0);
        b.addView(button(I18n.t("Restablecer esta sección", "Reset this section"), false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Object[] it : s.items) write((String) it[0], null);
                for (Runnable r : refreshers) r.run();
                toast(I18n.t("Valores por defecto", "Defaults restored"));
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        c.addView(b);
        return page(shortName(I18n.section(s.name)), null, true, c);
    }

    // ---------------------------------------------------------------- registro
    private TextView logText;
    private ScrollView logPage;

    private ScrollView logPage() {
        LinearLayout c = column();
        c.addView(space(16));
        LinearLayout k = card();
        logText = text("", 11.5f, text2);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setLineSpacing(0, 1.2f);
        logText.setPadding(px(18), px(16), px(18), px(16));
        k.addView(logText);
        c.addView(k);
        LinearLayout b = new LinearLayout(this);
        b.setPadding(0, px(16), 0, 0);
        b.addView(button(I18n.t("Borrar", "Clear"), false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getContentResolver().delete(PrefsProvider.LOG, null, null);
                refreshLog();
            }
        }), weight(px(10)));
        b.addView(button(I18n.t("Copiar", "Copy"), true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        }), weight(0));
        c.addView(b);
        refreshLog();
        logPage = page(I18n.t("Registro", "Log"), null, true, c);
        return logPage;
    }

    private void refreshLog() {
        if (logText == null) return;
        String l = readLog();
        logText.setText(l.isEmpty() ? I18n.t("Vacío. Abre y cierra alguna app (o cambia de pantalla dentro de una) y vuelve aquí.",
                "Empty. Open and close an app (or switch screens inside one) and come back here.") : l);
        bar.invalidate();
    }

    /** Copia el registro con los datos del móvil delante (lo que hace falta en un issue). */
    private void copyLog() {
        String head = "HyperSpring " + version() + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · " + systemVersion()
                + " · launcher " + appVersion(LauncherAdapters.resolvePackage(this)) + "\n";
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("HyperSpring", head + readLog()));
        toast(I18n.t("Registro copiado", "Log copied"));
    }

    private String readLog() {
        StringBuilder sb = new StringBuilder();
        try (android.database.Cursor cu = getContentResolver().query(PrefsProvider.LOG, null, null, null, null)) {
            while (cu != null && cu.moveToNext()) sb.append(cu.getString(0)).append('\n');
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- secciones
    private static List<Section> sections(Object[][] items) {
        List<Section> out = new ArrayList<>();
        Section cur = null;
        for (Object[] it : items) {
            if ("#".equals(it[0])) {
                cur = new Section((String) it[1]);
                out.add(cur);
            } else {
                if (cur == null) {
                    cur = new Section("Ajustes");
                    out.add(cur);
                }
                cur.items.add(it);
            }
        }
        return out;
    }

    private static Object[] item(Object[][] items, String key) {
        for (Object[] it : items) if (it[0].equals(key)) return it;
        throw new IllegalArgumentException(key);
    }

    private static String shortName(String s) {
        int p = s.indexOf(" (");
        return p > 0 ? s.substring(0, p) : s;
    }

    private static int iconFor(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("abrir")) return Icons.SPARKLE;
        if (n.contains("recientes")) return Icons.LAYERS;
        if (n.contains("cristal de las carpetas")) return Icons.DROP;
        if (n.contains("carpeta")) return Icons.FOLDER;
        if (n.contains("telón")) return Icons.CURTAIN;
        if (n.contains("interior")) return Icons.DROP;
        if (n.contains("refracción")) return Icons.PALETTE;
        if (n.contains("canto") || n.contains("luz")) return Icons.LIGHT;
        if (n.contains("fondo")) return Icons.GRID;
        return Icons.SLIDERS;
    }

    private View sectionRow(final Section s) {
        String sum = s.items.size() == 1 ? I18n.t("1 ajuste", "1 setting") : s.items.size() + I18n.t(" ajustes", " settings");
        return navRow(iconFor(s.name), shortName(I18n.section(s.name)), sum, true, new Runnable() {
            @Override
            public void run() {
                push(sectionPage(s));
            }
        });
    }

    // ---------------------------------------------------------------- filas
    private LinearLayout rowBase() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(18), px(15), px(16), px(15));
        r.setMinimumHeight(px(64));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed), null, new ColorDrawable(Color.WHITE)));
        return r;
    }

    private ImageView icon(int kind, int color) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icons(kind, color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(26), px(26));
        lp.rightMargin = px(16);
        iv.setLayoutParams(lp);
        return iv;
    }

    private LinearLayout titles(String title, String sub) {
        LinearLayout t = column();
        TextView a = text(title, 16.5f, text);
        a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.addView(a);
        if (sub != null) {
            TextView s = text(sub, 13.5f, text2);
            s.setPadding(0, px(2), 0, 0);
            s.setLineSpacing(0, 1.1f);
            t.addView(s);
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return t;
    }

    private View navRow(int iconKind, String title, String sub, boolean chevron, final Runnable action) {
        LinearLayout r = rowBase();
        r.addView(icon(iconKind, text));
        r.addView(titles(title, sub));
        if (chevron) {
            ImageView ch = new ImageView(this);
            ch.setImageDrawable(new Icons(Icons.CHEVRON, text3));
            r.addView(ch, new LinearLayout.LayoutParams(px(20), px(20)));
        }
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                action.run();
            }
        });
        sink(r);
        return r;
    }

    private View infoRow(int iconKind, String title, String value) {
        LinearLayout r = rowBase();
        r.setBackground(null);
        r.addView(icon(iconKind, text));
        r.addView(titles(title, value));
        return r;
    }

    private View toggleRow(int iconKind, final Object[] it, String title) {
        LinearLayout r = rowBase();
        if (iconKind >= 0) r.addView(icon(iconKind, text));
        LinearLayout tc = titles(title, null);
        final TextView d = desc(it);
        if (d != null) tc.addView(d);
        r.addView(tc);
        final Controls.Toggle t = new Controls.Toggle(this, accent, track, onAccent, text2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(12);
        r.addView(t, lp);
        final String key = (String) it[0];
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                t.setChecked(stored(it) >= 0.5f, false);
            }
        };
        refresh.run();
        refreshers.add(refresh);
        t.setOnChange(new Controls.Toggle.OnChange() {
            @Override
            public void onChange(boolean on) {
                write(key, on ? "1" : "0");
                // Las funciones se enganchan al arrancar el proceso: ofrecer el reinicio.
                if (key.startsWith("icehome_") && key.endsWith("_on") && !key.equals("icehome_press_on")) {
                    pendingRestart(false);
                } else if (key.equals("telon_on")) {
                    pendingRestart(true);
                }
            }
        });
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (d != null) expand(d);                  // la fila explica; el interruptor cambia
                else t.toggle(true);
            }
        });
        sink(r);
        resetOnLongPress(r, it, refresh);
        return r;
    }

    private View settingRow(final Object[] it) {
        final int decimals = (Integer) it[5];
        if (decimals < 0) return toggleRow(-1, it, I18n.title(it));
        final String key = (String) it[0];
        final float min = (Float) it[2], max = (Float) it[3];
        LinearLayout r = rowBase();
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.NO_GRAVITY);
        r.setPadding(px(18), px(14), px(16), px(8));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tc = titles(I18n.title(it), null);
        final TextView d = desc(it);
        if (d != null) tc.addView(d);
        head.addView(tc);
        final TextView value = text("", 15, accent);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setPadding(px(10), px(3), px(10), px(3));
        value.setBackground(round(accentSoft, px(10)));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = px(12);
        head.addView(value, vlp);
        r.addView(head);
        final Controls.Slider sl = new Controls.Slider(this, track, accent, text);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(6);
        slp.leftMargin = -px(12);
        slp.rightMargin = -px(10);
        r.addView(sl, slp);
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                float v = stored(it);
                sl.setFraction((v - min) / (max - min));
                value.setText(format(v, decimals));
            }
        };
        refresh.run();
        refreshers.add(refresh);
        sl.setOnChange(new Controls.Slider.OnChange() {
            @Override
            public void onChange(float f, boolean done) {
                float v = min + (max - min) * f;
                value.setText(format(v, decimals));
                if (done) write(key, format(v, Math.max(decimals, 0)).replace(',', '.'));
            }
        });
        if (d != null) {
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expand(d);
                }
            });
        }
        resetOnLongPress(r, it, refresh);
        return r;
    }

    /** Explicación de un ajuste: una línea; al tocar la fila se despliega entera (y se recoge). */
    private TextView desc(Object[] it) {
        String ds = I18n.desc(it);
        if (ds == null || ds.isEmpty()) return null;
        TextView d = text(ds, 13.5f, text2);
        d.setLineSpacing(0, 1.18f);
        d.setPadding(0, px(3), 0, 0);
        d.setMaxLines(1);
        d.setEllipsize(TextUtils.TruncateAt.END);
        return d;
    }

    private void expand(final TextView d) {
        final boolean open = d.getMaxLines() == 1;
        int w = d.getWidth();
        if (w <= 0) return;
        final int from = d.getHeight();
        d.setMaxLines(open ? Integer.MAX_VALUE : 1);
        d.setEllipsize(open ? null : TextUtils.TruncateAt.END);
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = d.getMeasuredHeight();
        d.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (from == to) return;
        final ViewGroup.LayoutParams lp = d.getLayoutParams();
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(open ? 300 : 240);
        a.setInterpolator(EASE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                lp.height = (Integer) va.getAnimatedValue();
                d.setLayoutParams(lp);
                bar.invalidate();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                d.setLayoutParams(lp);
            }
        });
        if (open) {
            d.setAlpha(0.6f);
            d.animate().alpha(1f).setDuration(300).start();
        }
        a.start();
    }

    private void resetOnLongPress(View r, final Object[] it, final Runnable refresh) {
        final float def = (Float) it[4];
        final int decimals = (Integer) it[5];
        r.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                write((String) it[0], null);
                refresh.run();
                toast(I18n.t("Por defecto: ", "Default: ") + (decimals < 0
                        ? (def >= 0.5f ? I18n.t("activado", "on") : I18n.t("desactivado", "off")) : format(def, decimals)));
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- piezas
    private TextView label(String s) {
        TextView h = text(s, 15, accent);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(px(10), px(26), px(10), px(10));
        return h;
    }

    private View space(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(dpH)));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(round(card, px(24)));
        c.setClipToOutline(true);
        c.setPadding(0, px(4), 0, px(4));
        c.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private View notice(String msg, String action, View.OnClickListener l) {
        LinearLayout n = column();
        n.setBackground(round(warn, px(24)));
        n.setPadding(px(20), px(18), px(20), px(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(16);
        n.setLayoutParams(lp);
        TextView w = text(msg, 14.5f, text);
        w.setLineSpacing(0, 1.2f);
        n.addView(w);
        if (action != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.topMargin = px(14);
            n.addView(button(action, true, l), blp);
        }
        return n;
    }

    /** Advertencia en rojo suave (root, dispositivos probados). */
    private View warning(String msg) {
        LinearLayout n = new LinearLayout(this);
        n.setGravity(Gravity.TOP);
        GradientDrawable g = round(Controls.blend(card, 0xFFE5534B, 0.20f), px(22));
        g.setStroke(Math.max(1, px(0.8f)), 0x40E5534B);
        n.setBackground(g);
        n.setPadding(px(16), px(14), px(18), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(16);
        n.setLayoutParams(lp);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Icons(Icons.SHIELD, 0xFFFFB4AB));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(22), px(22));
        ilp.rightMargin = px(12);
        ilp.topMargin = px(1);
        n.addView(ic, ilp);
        TextView w = text(msg, 14, 0xFFFFDAD5);
        w.setLineSpacing(0, 1.2f);
        n.addView(w, new LinearLayout.LayoutParams(0, -2, 1f));
        return n;
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView b = text(s, 15.5f, primary ? onAccent : text);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(22), px(16), px(22), px(16));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33000000 : pressed),
                round(primary ? accent : card, px(20)), null));
        b.setClickable(true);
        b.setOnClickListener(l);
        sink(b);
        return b;
    }

    // ---------------------------------------------------------------- datos
    private float stored(Object[] it) {
        float def = (Float) it[4];
        try {
            String v = getSharedPreferences(PrefsProvider.PREFS, 0).getString((String) it[0], null);
            if (v != null) return Math.max((Float) it[2], Math.min((Float) it[3], Float.parseFloat(v.trim())));
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** Guarda en la app (PrefsProvider lo sirve) y avisa al launcher y a la interfaz: sin root. */
    private void write(String key, String value) {
        android.content.SharedPreferences.Editor e = getSharedPreferences(PrefsProvider.PREFS, 0).edit();
        if (value == null) e.remove(key);
        else e.putString(key, value);
        e.commit();
        send(Remote.TUNE);
    }

    private void send(String action) {
        android.content.Intent i = new android.content.Intent(action);
        i.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(i, Remote.PERMISSION);
    }

    /**
     * Reinicia el launcher o la interfaz. Con root (lo normal con LSPosed) se cierra el proceso
     * desde fuera, que funciona siempre. Sin root, se le pide al proceso que se cierre él mismo; pero
     * HyperOS congela el launcher mientras no se ve, así que la orden puede no llegar hasta volver
     * al escritorio.
     */
    private void restart(final boolean systemUi) {
        final String name = systemUi ? I18n.t("la interfaz del sistema", "System UI") : I18n.t("el launcher", "the launcher");
        toast(I18n.t("Reiniciando ", "Restarting ") + name + "…");
        if (!systemUi) {
            String marker = System.currentTimeMillis() + "|settings";
            boolean saved = getSharedPreferences("state", 0).edit()
                    .putString("launcher_restart_requested", marker).commit();
            android.util.Log.i("HyperSpring", saved
                    ? "Marked POCO/HyperOS launcher restart as intentional"
                    : "Could not persist intentional launcher restart marker");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Se cierra el proceso viejo (por su pid) y el sistema lo vuelve a abrir solo.
                String pkg = systemUi ? "com.android.systemui" : LauncherAdapters.resolvePackage(SettingsActivity.this);
                String cmd = "p=$(pidof " + pkg + "); "
                        + (systemUi ? "" : "am force-stop " + pkg + " >/dev/null 2>&1; sleep 0.3; ")
                        + "[ -n \"$p\" ] && kill -9 $p 2>/dev/null; echo hecho";
                final String[] out = {""};
                boolean ok = false;
                try {
                    Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    for (String l; (l = r.readLine()) != null; ) sb.append(l).append(' ');
                    out[0] = sb.toString().trim();
                    ok = p.waitFor() == 0 && out[0].contains("hecho");
                } catch (Throwable t) {
                    out[0] = String.valueOf(t.getMessage());
                }
                final boolean rooted = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (rooted) {
                            if (!getSharedPreferences("app", 0).getBoolean("root_ok", false)) {
                                getSharedPreferences("app", 0).edit().putBoolean("root_ok", true).apply();
                            }
                            return;
                        }
                        // Sin root: la interfaz del sistema (que nunca se congela) cierra el
                        // launcher; y el propio proceso se cierra si recibe la orden.
                        send(systemUi ? Remote.RESTART_SYSTEMUI : Remote.RESTART_LAUNCHER);
                        toast(I18n.t("Sin root (", "No root (") + (out[0].isEmpty() ? I18n.t("denegado", "denied") : out[0]) + "): "
                                + I18n.t("se ha pedido a " + name + " que se reinicie.", "asked " + name + " to restart."));
                    }
                });
            }
        }, "reinicio").start();
    }

    private android.widget.Toast restartToast;

    /** Aviso corto tras cambiar una función que necesita reiniciar su proceso. */
    private void pendingRestart(boolean systemUi) {
        if (restartToast != null) restartToast.cancel();
        restartToast = android.widget.Toast.makeText(this, systemUi
                ? I18n.t("Se aplica al reiniciar la interfaz (Inicio → Reiniciar interfaz del sistema)", "Applies after restarting System UI (Home → Restart System UI)")
                : I18n.t("Se aplica al reiniciar el launcher (Inicio → Reiniciar launcher)", "Applies after restarting the launcher (Home → Restart launcher)"),
                android.widget.Toast.LENGTH_SHORT);
        restartToast.show();
    }

    /** Hundimiento al pulsar: la vista se encoge un poco y vuelve con un pequeño rebote. */
    private void sink(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(140).setInterpolator(EASE).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(380)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
                        break;
                    default:
                        break;
                }
                return false;                                   // el clic y la onda siguen igual
            }
        });
    }

    /** Lo que publican el launcher y la interfaz del sistema (ver {@link PrefsProvider#STATE}). */
    private String state(String key) {
        try {
            return getSharedPreferences("state", 0).getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** Partes que cargaron una versión anterior de HyperSpring desde el último reinicio, o null. */
    private String staleParts() {
        String launcher = staleOne("launcher_seen") ? I18n.t("el launcher", "the launcher") : null;
        String ui = staleOne("systemui_seen") ? I18n.t("la interfaz del sistema", "System UI") : null;
        if (launcher != null && ui != null) return I18n.t("el launcher y la interfaz del sistema", "the launcher and System UI");
        return launcher != null ? launcher : ui;
    }

    private boolean staleOne(String key) {
        String st = state(key);
        if (st == null) return false;
        String[] p = st.split("\\|");
        try {
            return Long.parseLong(p[0]) >= bootTime() && p.length > 1 && !p[1].equals(Version.NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    private String appVersion(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** «Android 15 · OS3.0.4.0» (HyperOS) o «MIUI 14» si es MIUI. */
    private static String systemVersion() {
        String os = prop("ro.mi.os.version.incremental");
        if (os == null) os = prop("ro.mi.os.version.name");
        String out = "Android " + Build.VERSION.RELEASE;
        if (os != null) return out + " · HyperOS " + (os.startsWith("OS") ? os.substring(2) : os);
        String miui = prop("ro.miui.ui.version.name");
        return miui != null ? out + " · MIUI " + miui.replace("V", "") : out;
    }

    private static String prop(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- utilidades
    private static String format(float v, int decimals) {
        if (decimals <= 0) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private LinearLayout.LayoutParams weight(int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = rightMargin;
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
