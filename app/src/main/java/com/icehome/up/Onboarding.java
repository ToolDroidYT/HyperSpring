package com.icehome.up;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Presentación y configuración guiada de la primera vez que se abre la app.
 * <p>
 * Va encima de toda la interfaz (una capa más en la raíz de {@link SettingsActivity}), en
 * oscuro. Cinco pasos:
 * <ol>
 *   <li>Bienvenida, sobre manchas suaves de coral, rosa y malva que recorren la pantalla: la gota
 *   cae y se convierte en el logo ({@link IntroLogo}), que sube a su sitio; el nombre llega con
 *   las letras separadas y desenfocado y «se cierra»; luego, tres funciones una a una.</li>
 *   <li>Comprobación: módulo cargado en el launcher y en la interfaz, y root.</li>
 *   <li>Estilo: los perfiles, cada uno con una ventanita que se abre con su muelle.</li>
 *   <li>Funciones: los mismos interruptores que en Inicio.</li>
 *   <li>Todo listo: ofrece reiniciar el launcher y la interfaz para cargarlo todo.</li>
 * </ol>
 * Transiciones (mezcla de la primera presentación y de la de HyperCeiler):
 * <ul>
 *   <li>De la bienvenida al primer paso, la bienvenida se encoge como una tarjeta con esquinas
 *   redondeadas (con el logo dentro) y se apaga, mientras el paso llega acercándose. Al volver,
 *   al revés.</li>
 *   <li>Entre los demás pasos, el viejo se va a un lado y el nuevo entra con un muelle mientras
 *   sus piezas aparecen una a una. Los puntos de abajo se estiran como una gota.</li>
 * </ul>
 */
final class Onboarding extends FrameLayout {

    private static final int STEPS = 5;
    private static final int OK = 0xFF5BE38A, WARN = 0xFFFFB547;

    private final SettingsActivity act;
    private final float dp;
    private final IntroLogo logo;
    private final FrameLayout pages;
    private final LinearLayout bottom, buttons;
    private final Dots dots;
    private final TextView next, back, skip;
    private final View topScrim;
    private int step = -1;
    private View current;
    private View logoSlot;
    private final List<View> introParts = new ArrayList<>();
    private TextView welcomeTitle;
    private int insetTop, insetBottom;
    /** Perfil elegido en el paso de estilo (null = mantener los ajustes). */
    private Object[] chosen;
    private final List<View> presetCards = new ArrayList<>();
    private boolean intro;

    Onboarding(SettingsActivity a) {
        super(a);
        act = a;
        dp = a.getResources().getDisplayMetrics().density;
        setClickable(true);                                   // no deja pasar toques a la app de debajo
        // Los pasos van sobre el mismo fondo que las páginas de la app (curvas de nivel tranquilas);
        // la bienvenida lleva el suyo, de manchas de color.
        LiveBackground.lines(a.bg, a.vivid[0], a.vivid[1], 0.22f, dp).attachTo(this);

        pages = new FrameLayout(a);
        addView(pages, new LayoutParams(-1, -1));
        logo = new IntroLogo(a);
        addView(logo, new LayoutParams(-1, -1));
        // Velo de arriba: lo que se desplaza no se mezcla con la barra de estado ni con «Saltar».
        topScrim = new View(a);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(a.bg & 0x00FFFFFF) | 0xF0000000, (a.bg & 0x00FFFFFF) | 0xB0000000, a.bg & 0x00FFFFFF}));
        addView(topScrim, new LayoutParams(-1, px(60)));

        // Abajo: puntos de progreso y botones.
        bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        dots = new Dots(a, STEPS, dp);
        bottom.addView(dots, new LinearLayout.LayoutParams(-2, px(10)));
        buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LayoutTransition lt = new LayoutTransition();          // el botón grande se estira solo al aparecer «Atrás»
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setDuration(320);
        buttons.setLayoutTransition(lt);
        back = new TextView(a);
        styleText(back, I18n.t("Atrás", "Back"), 16, a.text);
        back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        back.setGravity(Gravity.CENTER);
        back.setPadding(px(22), 0, px(22), 0);
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), glass(px(28)), null));
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (step == STEPS - 1) finish(false);
                else back();
            }
        });
        a.sink(back);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, px(56));
        blp.rightMargin = px(12);
        buttons.addView(back, blp);
        next = new TextView(a);
        styleText(next, "", 16.5f, Color.WHITE);
        next.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        next.setGravity(Gravity.CENTER);
        next.setMaxLines(1);
        next.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), Motion.brandPill(px(28)), null));
        next.setForeground(new Shine(dp));
        next.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                forward();
            }
        });
        a.sink(next);
        buttons.addView(next, new LinearLayout.LayoutParams(0, px(56), 1f));
        LinearLayout.LayoutParams bb = new LinearLayout.LayoutParams(-1, -2);
        bb.topMargin = px(22);
        bottom.addView(buttons, bb);
        // Velo que oscurece por abajo: el contenido que pasa por detrás de los botones se funde.
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{a.bg & 0x00FFFFFF, (a.bg & 0x00FFFFFF) | 0xE0000000, (a.bg & 0x00FFFFFF) | 0xF5000000,
                        (a.bg & 0x00FFFFFF) | 0xF5000000}));
        addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM));

        skip = new TextView(a);
        styleText(skip, I18n.t("Saltar", "Skip"), 14.5f, a.text2);
        skip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        skip.setPadding(px(16), px(8), px(16), px(8));
        skip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), glass(px(18)), null));
        skip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(false);
            }
        });
        addView(skip, new LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        applyInsets();
    }

    // ------------------------------------------------------------------ arranque y bordes

    /** Empieza: con la construcción del logo la primera vez, o directamente en la bienvenida. */
    void start() {
        intro = true;
        go(0);
        // El contenido de la bienvenida espera a que el logo termine de formarse.
        for (View v : introParts) v.setAlpha(0f);
        bottom.setAlpha(0f);
        skip.setAlpha(0f);
        logo.play(px(96));
        postDelayed(new Runnable() {
            @Override
            public void run() {
                landLogo(true);
                long d = 120;
                if (welcomeTitle != null) trackIn(welcomeTitle, d);
                for (View v : introParts) {
                    if (v == welcomeTitle) continue;
                    d += 90;
                    Motion.reveal(v, d, px(22));
                }
                bottom.setTranslationY(px(30));
                bottom.animate().alpha(1f).translationY(0f).setStartDelay(d + 120).setDuration(700)
                        .setInterpolator(Motion.EXPO_OUT).start();
                skip.animate().alpha(1f).setStartDelay(d + 200).setDuration(500).start();
                intro = false;
            }
        }, ValueAnimator.areAnimatorsEnabled() ? IntroLogo.BUILD_MS - 260 : 0);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        insetTop = in.getSystemWindowInsetTop();
        insetBottom = in.getSystemWindowInsetBottom();
        applyInsets();
        return in;
    }

    private void applyInsets() {
        bottom.setPadding(px(24), px(56), px(24), insetBottom + px(20));
        LayoutParams tl = (LayoutParams) topScrim.getLayoutParams();
        tl.height = insetTop + px(58);
        topScrim.setLayoutParams(tl);
        LayoutParams sl = (LayoutParams) skip.getLayoutParams();
        sl.topMargin = insetTop + px(10);
        sl.rightMargin = px(14);
        skip.setLayoutParams(sl);
        for (int i = 0; i < pages.getChildCount(); i++) padPage(pages.getChildAt(i));
    }

    private void padPage(View page) {
        if (!(page instanceof ScrollView)) return;
        View col = ((ScrollView) page).getChildAt(0);
        if (col != null) col.setPadding(px(24), insetTop + px(60), px(24), insetBottom + px(170));
    }

    /** Lleva el logo al hueco que le deja la bienvenida. */
    private void landLogo(boolean animate) {
        if (logoSlot == null || logoSlot.getWidth() == 0) return;
        int[] a = new int[2], b = new int[2];
        logoSlot.getLocationInWindow(a);
        logo.getLocationInWindow(b);
        float x = a[0] - b[0] + logoSlot.getWidth() / 2f, y = a[1] - b[1] + logoSlot.getHeight() / 2f;
        if (animate) logo.flyTo(x, y, logoSlot.getWidth());
        else logo.place(x, y, logoSlot.getWidth());
    }

    // ------------------------------------------------------------------ navegación

    /** Atrás del sistema: paso anterior. Devuelve false en la bienvenida (sale de la app). */
    boolean back() {
        if (step <= 0) return false;
        go(step - 1);
        return true;
    }

    private void forward() {
        if (intro) return;
        if (step == 2 && chosen != null) {
            Presets.apply(act, chosen);
            act.settingsReplaced();
        }
        if (step < STEPS - 1) go(step + 1);
        else finish(true);
    }

    private void finish(boolean restart) {
        act.closeOnboarding(restart);
    }

    private void go(int to) {
        final boolean fwd = to > step;
        final int from = step;
        step = to;
        final float w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        final View old = current;
        View page = build(to);
        padPage(page);
        pages.addView(page, new LayoutParams(-1, -1));
        current = page;

        if (old != null && (from == 0 || to == 0)) {
            // Entre la bienvenida y el resto (como HyperCeiler): la bienvenida se encoge como una
            // tarjeta con esquinas redondeadas, con el logo, y se apaga; el paso llega acercándose.
            final View welcome = from == 0 ? old : page;
            final View other = from == 0 ? page : old;
            roundCorners(welcome);
            if (from == 0) {
                shrink(welcome, true, old);
                shrink(logo, true, null);
                other.setAlpha(0f);
                other.setScaleX(1.06f);
                other.setScaleY(1.06f);
                other.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(560)
                        .setInterpolator(Motion.EXPO_OUT).start();
                Motion.cascade((ViewGroup) ((ScrollView) other).getChildAt(0), 180, px(22));
            } else {
                welcome.setAlpha(0f);
                welcome.setScaleX(0.88f);
                welcome.setScaleY(0.88f);
                shrink(welcome, false, null);
                logo.setAlpha(0f);
                logo.setScaleX(0.88f);
                logo.setScaleY(0.88f);
                shrink(logo, false, null);
                post(new Runnable() {
                    @Override
                    public void run() {
                        landLogo(false);
                    }
                });
                old.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(300).setInterpolator(Motion.EASE)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                pages.removeView(old);
                            }
                        }).start();
            }
        } else if (old != null) {
            old.animate().translationX(fwd ? -w * 0.28f : w * 0.28f).alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(340).setInterpolator(Motion.EASE).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            pages.removeView(old);
                        }
                    }).start();
            page.setTranslationX(fwd ? w * 0.4f : -w * 0.4f);
            final View p = page;
            ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
            Spring.apply(in, 0f, 1f, 0f, 0.55f, 0.84f);
            final float x0 = page.getTranslationX();
            in.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    p.setTranslationX(x0 * (1f - (Float) a.getAnimatedValue()));
                }
            });
            in.start();
            ViewGroup col = (ViewGroup) ((ScrollView) page).getChildAt(0);
            Motion.cascade(col, 90, px(26));
        }

        dots.go(to, from >= 0);
        back.setVisibility(to == 0 ? GONE : VISIBLE);
        back.setText(to == STEPS - 1 ? I18n.t("Más tarde", "Later") : I18n.t("Atrás", "Back"));
        String label = to == 0 ? I18n.t("Empezar", "Get started")
                : to == STEPS - 1 ? I18n.t("Reiniciar", "Restart") : I18n.t("Continuar", "Continue");
        swapText(next, label, from >= 0);
        if (!intro) skip.animate().alpha(to == STEPS - 1 ? 0f : 1f).setStartDelay(0).setDuration(240).start();
        skip.setClickable(to != STEPS - 1);
    }

    /** Encoge (y apaga) o devuelve a su tamaño una vista; al encogerse, quita {@code removeAfter}. */
    private void shrink(final View v, final boolean out, final View removeAfter) {
        v.animate().alpha(out ? 0f : 1f).scaleX(out ? 0.88f : 1f).scaleY(out ? 0.88f : 1f)
                .setStartDelay(0).setDuration(out ? 420 : 520).setInterpolator(out ? Motion.EASE : Motion.EXPO_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (removeAfter != null) pages.removeView(removeAfter);
                        if (!out) v.setClipToOutline(false);        // ya a pantalla completa: sin esquinas
                    }
                }).start();
    }

    /** Esquinas redondeadas, para que al encogerse la página parezca una tarjeta. */
    private void roundCorners(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 36 * dp);
            }
        });
        v.setClipToOutline(true);
    }

    private View build(int s) {
        switch (s) {
            case 1:
                return checksPage();
            case 2:
                return stylePage();
            case 3:
                return featuresPage();
            case 4:
                return donePage();
            default:
                return welcomePage();
        }
    }

    // ------------------------------------------------------------------ pasos

    private ScrollView welcomePage() {
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        introParts.clear();
        logoSlot = new View(act);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(96), px(96));
        sl.topMargin = px(8);
        c.addView(logoSlot, sl);

        welcomeTitle = new TextView(act);
        styleText(welcomeTitle, "HyperSpring", 40, Color.WHITE);
        welcomeTitle.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.BOLD), 800, false));
        welcomeTitle.setLetterSpacing(-0.02f);
        welcomeTitle.setGravity(Gravity.CENTER);
        // Blanco puro: con el degradado de la marca el título se veía pastel y perdía fuerza.
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = px(22);
        c.addView(welcomeTitle, tl);
        introParts.add(welcomeTitle);

        TextView tag = new TextView(act);
        styleText(tag, I18n.t("Animaciones y cristal líquido para HyperOS", "Animations and liquid glass for HyperOS"), 17, act.text2);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(px(12), px(8), px(12), 0);
        c.addView(tag, new LinearLayout.LayoutParams(-1, -2));
        introParts.add(tag);

        View sp = new View(act);
        c.addView(sp, new LinearLayout.LayoutParams(1, px(20)));
        Object[][] rows = {
                {Icons.SPARKLE, I18n.t("Ventanas con vida", "Windows that move"),
                        I18n.t("Las apps salen del icono y vuelven a él con efecto genio.",
                                "Apps grow out of their icon and fold back into it with a genie effect.")},
                {Icons.DROP, I18n.t("Cristal líquido", "Liquid glass"),
                        I18n.t("Centro de control con refracción en el borde y luz del color de lo que hay detrás.",
                                "A control center with edge refraction and light tinted by what's behind it.")},
                {Icons.CURTAIN, I18n.t("Físicas en todo", "Physics everywhere"),
                        I18n.t("Una cortina al cambiar de pantalla, carpetas con muelle y recientes que se doblan.",
                                "A curtain between screens, springy folders and recents that bend.")},
        };
        for (Object[] r : rows) {
            View f = feature((Integer) r[0], (String) r[1], (String) r[2]);
            c.addView(f);
            introParts.add(f);
        }
        ScrollView sv = scroll(c);
        // Fondo propio de manchas suaves (coral, rosa, malva) que recorren la pantalla: al pasar al
        // primer paso, la bienvenida se encoge como una tarjeta con este fondo dentro.
        new LiveBackground(act.bg, act.vivid, 0.36f, true).attachTo(sv);
        return sv;
    }

    /** Fila de la bienvenida: icono en una gota de cristal, título y una línea. */
    private View feature(int icon, String title, String sub) {
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(4), px(10), px(4), px(10));
        ImageView iv = new ImageView(act);
        iv.setImageDrawable(new Icons(icon, Color.WHITE));
        iv.setPadding(px(11), px(11), px(11), px(11));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x66FF8A76, 0x66B97FE6});
        g.setCornerRadius(px(16));
        g.setStroke(Math.max(1, px(0.8f)), 0x33FFFFFF);
        iv.setBackground(g);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(48), px(48));
        il.rightMargin = px(16);
        r.addView(iv, il);
        r.addView(titles(title, sub), new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private ScrollView checksPage() {
        LinearLayout c = column();
        c.addView(heading(Icons.SHIELD, I18n.t("Comprobando tu móvil", "Checking your phone"),
                I18n.t("HyperSpring necesita LSPosed, sus dos ámbitos y root. Esto es lo que veo:",
                        "HyperSpring needs LSPosed, its two scopes and root. Here's what I see:")));
        final LinearLayout card = glassCard();
        card.setTag(Motion.CASCADE);

        boolean launcher = active("launcher_seen"), ui = active("systemui_seen");
        final Check l = check(card, Icons.HOME, I18n.t("Escritorio", "Launcher"));
        final Check u = check(card, Icons.CURTAIN, I18n.t("Interfaz del sistema", "System UI"));
        final Check r = check(card, Icons.SHIELD, I18n.t("Acceso root", "Root access"));
        c.addView(card);

        resolve(l, 520, launcher, launcher ? I18n.t("HyperSpring está activo en el launcher", "HyperSpring is active in the launcher")
                : I18n.t("Todavía no se ha cargado. Activa el módulo en LSPosed con el ámbito «Launcher del sistema» y reinicia el móvil.",
                "Not loaded yet. Enable the module in LSPosed with the “System launcher” scope and reboot."));
        resolve(u, 760, ui, ui ? I18n.t("HyperSpring está activo en la interfaz", "HyperSpring is active in System UI")
                : I18n.t("Todavía no se ha cargado. Marca también «Interfaz del sistema» en LSPosed y reinicia.",
                "Not loaded yet. Also tick “System UI” in LSPosed and reboot."));
        askRoot(r, 1000);
        return scroll(c);
    }

    /** Pide root (sale el aviso de Magisk o KernelSU) y marca la fila con el resultado. */
    private void askRoot(final Check r, final long minDelay) {
        r.mark.set(StatusMark.PENDING);
        r.sub.setText(I18n.t("Pidiendo acceso…", "Asking for access…"));
        final long t0 = SystemClock.uptimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    for (String l; (l = br.readLine()) != null; ) sb.append(l);
                    ok = p.waitFor() == 0 && sb.toString().contains("uid=0");
                } catch (Throwable ignored) {
                }
                final boolean rooted = ok;
                if (rooted) act.getSharedPreferences("app", 0).edit().putBoolean("root_ok", true).apply();
                long wait = Math.max(0, minDelay - (SystemClock.uptimeMillis() - t0));
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAttachedToWindow()) return;
                        resolve(r, 0, rooted, rooted
                                ? I18n.t("Concedido: los botones de reiniciar funcionan al momento", "Granted: the restart buttons work instantly")
                                : I18n.t("Sin root, reiniciar el launcher y la interfaz puede fallar. Concédelo en Magisk, KernelSU o APatch. Toca para reintentar.",
                                "Without root, restarting the launcher and System UI may fail. Grant it in Magisk, KernelSU or APatch. Tap to retry."));
                        if (!rooted) {
                            r.row.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    r.row.setOnClickListener(null);
                                    askRoot(r, 400);
                                }
                            });
                        }
                    }
                }, wait);
            }
        }, "root").start();
    }

    private ScrollView stylePage() {
        LinearLayout c = column();
        c.addView(heading(Icons.SLIDERS, I18n.t("¿Cómo quieres que se sienta?", "How should it feel?"),
                I18n.t("Cada perfil ajusta la forma, la velocidad y el rebote. Puedes cambiarlo cuando quieras en Inicio → Perfiles.",
                        "Each preset tunes shape, speed and bounce. You can change it any time in Home → Presets.")));
        presetCards.clear();
        LinearLayout grid = column();
        grid.setTag(Motion.CASCADE);
        LinearLayout row = null;
        for (int i = 0; i < Presets.ALL.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(act);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = px(12);
                grid.addView(row, rl);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % 2 == 0) cl.rightMargin = px(6);
            else cl.leftMargin = px(6);
            row.addView(presetCard(Presets.ALL[i]), cl);
        }
        grid.addView(presetCard(null), new LinearLayout.LayoutParams(-1, -2));
        c.addView(grid);
        selectPreset(chosen, false);
        return scroll(c);
    }

    private View presetCard(final Object[] preset) {
        LinearLayout k = column();
        k.setPadding(px(16), px(14), px(16), px(16));
        k.setBackground(glass(px(24)));
        k.setTag(preset);
        if (preset != null) {
            float speed = 1f, bounce = 0f;
            String[] kv = (String[]) preset[4];
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (kv[i].equals("icehome_anim_speed")) speed = Float.parseFloat(kv[i + 1]);
                if (kv[i].equals("icehome_anim_bounce")) bounce = Float.parseFloat(kv[i + 1]);
            }
            k.addView(new SpringDemo(act, dp, speed, bounce), new LinearLayout.LayoutParams(-1, px(70)));
            TextView n = new TextView(act);
            styleText(n, Presets.name(preset), 16.5f, act.text);
            n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            n.setPadding(0, px(10), 0, 0);
            k.addView(n);
            TextView a = new TextView(act);
            styleText(a, Presets.about(preset), 12.5f, act.text2);
            a.setLineSpacing(0, 1.12f);
            a.setMaxLines(4);
            a.setEllipsize(TextUtils.TruncateAt.END);
            a.setPadding(0, px(3), 0, 0);
            k.addView(a);
        } else {
            k.setOrientation(LinearLayout.HORIZONTAL);
            k.setGravity(Gravity.CENTER_VERTICAL);
            ImageView iv = new ImageView(act);
            iv.setImageDrawable(new Icons(Icons.SLIDERS, act.text));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(24), px(24));
            il.rightMargin = px(14);
            k.addView(iv, il);
            k.addView(titles(I18n.t("Mantener mis ajustes", "Keep my settings"),
                    I18n.t("No cambia nada de lo que ya tengas", "Leaves whatever you have untouched")), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        k.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                selectPreset(preset, true);
            }
        });
        act.sink(k);
        presetCards.add(k);
        return k;
    }

    /** Marca un perfil: el elegido se ilumina con un borde claro y los demás se apagan un poco. */
    private void selectPreset(Object[] preset, boolean animate) {
        chosen = preset;
        for (View k : presetCards) {
            boolean on = k.getTag() == preset;
            GradientDrawable g = (GradientDrawable) k.getBackground();
            g.setColor(on ? 0x30FFFFFF : 0x14FFFFFF);
            g.setStroke(px(on ? 1.8f : 0.8f), on ? 0xD8FFFFFF : 0x1FFFFFFF);
            float a = on ? 1f : 0.62f;
            if (animate) k.animate().alpha(a).setStartDelay(0).setDuration(260).setInterpolator(Motion.EASE).start();
            else k.setAlpha(a);
        }
    }

    private ScrollView featuresPage() {
        LinearLayout c = column();
        c.addView(heading(Icons.TOGGLES, I18n.t("Elige qué activar", "Pick what to turn on"),
                I18n.t("Toca una fila para leer qué hace. Se cargan al reiniciar el launcher y la interfaz, en el último paso.",
                        "Tap a row to read what it does. They load when the launcher and System UI restart, in the last step.")));
        LinearLayout f = act.featuresCard();
        f.setBackground(glass(px(24)));
        f.setTag(Motion.CASCADE);
        c.addView(f);
        return scroll(c);
    }

    private ScrollView donePage() {
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        Burst b = new Burst(act, dp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(200), px(200));
        bl.topMargin = px(10);
        c.addView(b, bl);
        TextView t = new TextView(act);
        styleText(t, I18n.t("¡Todo listo!", "All set!"), 36, Color.WHITE);
        t.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.BOLD), 800, false));
        t.setGravity(Gravity.CENTER);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = new TextView(act);
        styleText(s, I18n.t("Para cargar las funciones hay que reiniciar el launcher y la interfaz del sistema. "
                        + "La pantalla parpadeará un momento.",
                "To load the features, the launcher and System UI need a restart. The screen will flicker for a moment."), 16, act.text2);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.18f);
        s.setPadding(px(8), px(12), px(8), 0);
        c.addView(s, new LinearLayout.LayoutParams(-1, -2));
        TextView h = new TextView(act);
        styleText(h, I18n.t("Puedes volver a ver esta presentación en Acerca de.", "You can replay this tour from About."), 13.5f, act.text3);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, px(18), 0, 0);
        c.addView(h, new LinearLayout.LayoutParams(-1, -2));
        b.play(260);
        return scroll(c);
    }

    // ------------------------------------------------------------------ comprobaciones

    /** Una fila de la comprobación: marca, título y explicación. */
    private static final class Check {
        LinearLayout row;
        StatusMark mark;
        TextView sub;
    }

    private Check check(LinearLayout card, int icon, String title) {
        Check ch = new Check();
        ch.row = new LinearLayout(act);
        ch.row.setGravity(Gravity.CENTER_VERTICAL);
        ch.row.setPadding(px(16), px(14), px(16), px(14));
        ch.row.setMinimumHeight(px(68));
        ch.mark = new StatusMark(act, dp);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(px(30), px(30));
        ml.rightMargin = px(14);
        ch.row.addView(ch.mark, ml);
        LinearLayout t = titles(title, I18n.t("Comprobando…", "Checking…"));
        ch.sub = (TextView) t.getChildAt(1);
        ch.row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView iv = new ImageView(act);
        iv.setImageDrawable(new Icons(icon, act.text3));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(20), px(20));
        il.leftMargin = px(10);
        ch.row.addView(iv, il);
        card.addView(ch.row);
        return ch;
    }

    private void resolve(final Check ch, long delay, final boolean ok, final String text) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                ch.mark.set(ok ? StatusMark.OK : StatusMark.WARN);
                ch.sub.animate().alpha(0f).setDuration(110).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        ch.sub.setText(text);
                        ch.sub.animate().alpha(1f).setDuration(260).start();
                    }
                }).start();
                ch.row.performHapticFeedback(ok ? HapticFeedbackConstants.CLOCK_TICK : HapticFeedbackConstants.REJECT);
            }
        }, delay);
    }

    private boolean active(String key) {
        String st = act.state(key);
        if (st == null) return false;
        try {
            return Long.parseLong(st.split("\\|")[0]) >= SettingsActivity.bootTime();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ piezas

    private ScrollView scroll(LinearLayout col) {
        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        sv.setFillViewport(true);
        sv.addView(col, new LayoutParams(-1, -2));
        return sv;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout glassCard() {
        LinearLayout k = column();
        k.setBackground(glass(px(24)));
        k.setPadding(0, px(4), 0, px(4));
        return k;
    }

    private GradientDrawable glass(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1CFFFFFF);
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, px(0.8f)), 0x24FFFFFF);
        return g;
    }

    /**
     * Cabecera de un paso: icono de línea en una baldosa coral (como HyperCeiler), título grande y
     * explicación. Tres piezas que entran una detrás de otra.
     */
    private LinearLayout heading(int icon, String title, String sub) {
        LinearLayout h = column();
        ImageView iv = new ImageView(act);
        iv.setImageDrawable(new Icons(icon, act.accent));
        iv.setPadding(px(12), px(12), px(12), px(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor((act.accentStrong & 0x00FFFFFF) | 0x2E000000);
        g.setCornerRadius(px(16));
        iv.setBackground(g);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(52), px(52));
        il.bottomMargin = px(18);
        h.addView(iv, il);
        TextView t = new TextView(act);
        styleText(t, title, 32, Color.WHITE);
        t.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.BOLD), 750, false));
        t.setLetterSpacing(-0.015f);
        t.setLineSpacing(0, 1.05f);
        h.addView(t);
        TextView s = new TextView(act);
        styleText(s, sub, 15.5f, act.text2);
        s.setLineSpacing(0, 1.18f);
        s.setPadding(0, px(10), 0, px(24));
        h.addView(s);
        h.setTag(Motion.CASCADE);
        return h;
    }

    private LinearLayout titles(String title, String sub) {
        LinearLayout t = column();
        TextView a = new TextView(act);
        styleText(a, title, 16.5f, act.text);
        a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.addView(a);
        TextView s = new TextView(act);
        styleText(s, sub, 13f, act.text2);
        s.setLineSpacing(0, 1.1f);
        s.setPadding(0, px(2), 0, 0);
        t.addView(s);
        return t;
    }

    private void styleText(TextView t, String s, float sp, int color) {
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
    }

    /** Cambia el texto de un botón con un fundido corto (sin que salte). */
    private void swapText(final TextView t, final String s, boolean animate) {
        if (!animate || s.contentEquals(t.getText())) {
            t.setText(s);
            return;
        }
        t.animate().alpha(0f).translationY(-px(6)).setStartDelay(0).setDuration(110).withEndAction(new Runnable() {
            @Override
            public void run() {
                t.setText(s);
                t.setTranslationY(px(6));
                t.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Motion.EXPO_OUT).start();
            }
        }).start();
    }

    /**
     * Título que «se cierra»: llega con las letras separadas y desenfocado y se junta mientras se
     * enfoca. Es el efecto de entrada de muchas webs de producto.
     */
    private void trackIn(final TextView t, long delay) {
        final float sp0 = 0.32f, sp1 = t.getLetterSpacing();
        final float blur = 18 * dp;
        t.setAlpha(0f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setStartDelay(delay);
        a.setDuration(1100);
        a.setInterpolator(Motion.EXPO_OUT);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float f = (Float) va.getAnimatedValue();
                t.setAlpha(Math.min(1f, f * 1.6f));
                t.setLetterSpacing(Motion.lerp(sp0, sp1, f));
                if (Build.VERSION.SDK_INT >= 31) {
                    float r = blur * (1f - f);
                    t.setRenderEffect(r > 0.5f ? RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) : null);
                }
            }
        });
        a.start();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ------------------------------------------------------------------ vistas propias

    /**
     * Puntos de progreso. El activo es una cápsula con el degradado de la marca; al cambiar de
     * paso, su borde delantero va con un muelle rápido y el trasero con uno lento, así que se
     * estira como una gota y luego se recoge.
     */
    static final class Dots extends View {
        private final int n;
        private final float dp;
        private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG), on = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float head, tail;
        private ValueAnimator ha, ta;

        Dots(Context c, int n, float dp) {
            super(c);
            this.n = n;
            this.dp = dp;
            off.setColor(0x40FFFFFF);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round((n * 18 + 16) * dp), Math.round(10 * dp));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            on.setShader(new LinearGradient(0, 0, w, 0, Motion.BRAND, null, Shader.TileMode.CLAMP));
        }

        void go(int i, boolean animate) {
            if (ha != null) ha.cancel();
            if (ta != null) ta.cancel();
            if (!animate) {
                head = tail = i;
                invalidate();
                return;
            }
            ha = spring(head, i, 0.32f, 0.78f, true);
            ta = spring(tail, i, 0.62f, 0.86f, false);
        }

        private ValueAnimator spring(float from, float to, float response, float damping, final boolean isHead) {
            ValueAnimator a = ValueAnimator.ofFloat(from, to);
            Spring.apply(a, from, to, 0f, response, damping);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    if (isHead) head = (Float) va.getAnimatedValue();
                    else tail = (Float) va.getAnimatedValue();
                    invalidate();
                }
            });
            a.start();
            return a;
        }

        @Override
        protected void onDraw(Canvas c) {
            float step = 18 * dp, x0 = 8 * dp + step / 2f, cy = getHeight() / 2f, rad = 3.5f * dp;
            for (int i = 0; i < n; i++) c.drawCircle(x0 + i * step, cy, rad, off);
            float a = Math.min(head, tail), b = Math.max(head, tail);
            float half = 4.5f * dp, w = 7 * dp;          // la cápsula activa es más ancha que un punto
            r.set(x0 + a * step - w, cy - half, x0 + b * step + w, cy + half);
            c.drawRoundRect(r, half, half, on);
        }
    }

    /** Marca de estado: arco que gira mientras comprueba; luego ✓ verde o ! ámbar que salta con un muelle. */
    static final class StatusMark extends View {
        static final int PENDING = 0, OK = 1, WARN = 2;
        private final float dp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path check = new Path(), part = new Path();
        private int state = PENDING;
        private long since = SystemClock.uptimeMillis();

        StatusMark(Context c, float dp) {
            super(c);
            this.dp = dp;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(int s) {
            state = s;
            since = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, rad = Math.min(w, h) / 2f - dp;
            float el = (SystemClock.uptimeMillis() - since) / 1000f;
            boolean anim = ValueAnimator.areAnimatorsEnabled();
            if (state == PENDING) {
                p.setStrokeWidth(2.4f * dp);
                p.setColor(0x33FFFFFF);
                c.drawCircle(cx, cy, rad - dp, p);
                p.setColor(0xFFFFFFFF);
                r.set(cx - rad + dp, cy - rad + dp, cx + rad - dp, cy + rad - dp);
                float start = (el * 400f) % 360f;
                c.drawArc(r, start, 90f + 60f * (float) Math.sin(el * 4f), false, p);
                postInvalidateOnAnimation();
                return;
            }
            float pop = anim ? Motion.spring(el, 0.38f, 0.5f) : 1f;
            fill.setColor(state == OK ? Onboarding.OK : Onboarding.WARN);
            c.save();
            c.scale(pop, pop, cx, cy);
            c.drawCircle(cx, cy, rad, fill);
            p.setColor(0xFF10131A);
            p.setStrokeWidth(2.6f * dp);
            float draw = anim ? Motion.EXPO_OUT.getInterpolation(Motion.seg(el, 0.08f, 0.5f)) : 1f;
            if (state == OK) {
                check.reset();
                check.moveTo(cx - rad * 0.42f, cy + rad * 0.02f);
                check.lineTo(cx - rad * 0.1f, cy + rad * 0.34f);
                check.lineTo(cx + rad * 0.46f, cy - rad * 0.3f);
                PathMeasure pm = new PathMeasure(check, false);
                part.reset();
                pm.getSegment(0, pm.getLength() * draw, part, true);
                c.drawPath(part, p);
            } else {
                c.drawLine(cx, cy - rad * 0.42f, cx, cy - rad * 0.42f + rad * 0.5f * draw, p);
                if (draw > 0.9f) c.drawPoint(cx, cy + rad * 0.4f, p);
            }
            c.restore();
            if (anim && el < 1.2f) postInvalidateOnAnimation();
        }
    }

    /**
     * Ventanita de un perfil: un icono que se abre en ventana y se vuelve a cerrar en bucle, con
     * la velocidad y el rebote de ese perfil (lo mismo que hará el genio de verdad).
     */
    static final class SpringDemo extends View {
        private final float dp, response, damping;
        private final Paint win = new Paint(Paint.ANTI_ALIAS_FLAG), dock = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final long t0 = SystemClock.uptimeMillis();

        SpringDemo(Context c, float dp, float speed, float bounce) {
            super(c);
            this.dp = dp;
            response = 0.46f / Math.max(0.3f, speed);
            damping = Math.max(0.32f, Math.min(0.99f, 1f - bounce * 4f));
            dock.setColor(0x24FFFFFF);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            win.setShader(new LinearGradient(0, 0, w, h, Motion.BRAND, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cycle = 2.8f, el = ((SystemClock.uptimeMillis() - t0) / 1000f) % cycle;
            boolean anim = ValueAnimator.areAnimatorsEnabled();
            float p;
            if (!anim) p = 1f;
            else if (el < 1.3f) p = Motion.spring(el, response, damping);
            else p = 1f - Motion.spring(el - 1.3f, response, damping);
            // Del icono (abajo, en el centro) a la ventana (casi todo el hueco).
            float iw = 16 * dp, ix = w / 2f, iy = h - 10 * dp;
            float fw = Math.min(w * 0.56f, h * 0.62f * 1.1f), fh = h - 10 * dp, fx = w / 2f, fy = h / 2f - 3 * dp;
            r.set(ix - 22 * dp, iy - 1.5f * dp, ix + 22 * dp, iy + 1.5f * dp);
            c.drawRoundRect(r, 2 * dp, 2 * dp, dock);
            float cw = Math.max(4 * dp, Motion.lerp(iw, fw, p)), ch = Math.max(4 * dp, Motion.lerp(iw, fh, p));
            float cx = Motion.lerp(ix, fx, p), cy = Motion.lerp(iy - iw / 2f - 4 * dp, fy, p);
            float rad = Motion.lerp(5 * dp, 9 * dp, Math.max(0f, Math.min(1f, p)));
            r.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f);
            c.drawRoundRect(r, rad, rad, win);
            if (anim) postInvalidateOnAnimation();
        }
    }

    /** Final: círculo con el degradado que salta, ✓ que se dibuja y una explosión de chispas. */
    static final class Burst extends View {
        private final float dp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path(), part = new Path();
        private final float[] ang = new float[18], dist = new float[18], size = new float[18];
        private final int[] col = new int[18];
        private long start = -1;

        Burst(Context c, float dp) {
            super(c);
            this.dp = dp;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(Color.WHITE);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            fill.setShadowLayer(22 * dp, 0, 8 * dp, 0x66E8578F);
            Random rnd = new Random();
            for (int i = 0; i < ang.length; i++) {
                ang[i] = (float) (i * Math.PI * 2 / ang.length + rnd.nextFloat() * 0.3f);
                dist[i] = 0.62f + rnd.nextFloat() * 0.38f;
                size[i] = 2.2f + rnd.nextFloat() * 2.8f;
                col[i] = Motion.BRAND[i % 3];
            }
        }

        /** Arranca a los {@code delay} ms (con la página ya entrando). */
        void play(final long delay) {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    start = SystemClock.uptimeMillis();
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    invalidate();
                }
            }, delay);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            fill.setShader(new LinearGradient(0, 0, w, h, Motion.BRAND, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (start < 0) return;
            boolean anim = ValueAnimator.areAnimatorsEnabled();
            float el = anim ? (SystemClock.uptimeMillis() - start) / 1000f : 5f;
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, R = Math.min(w, h) * 0.26f;

            // Chispas: salen disparadas con frenada exponencial y se apagan.
            float q = Motion.seg(el, 0.08f, 1.1f);
            if (q > 0f && q < 1f) {
                float e = Motion.EXPO_OUT.getInterpolation(q);
                for (int i = 0; i < ang.length; i++) {
                    float d = R * (1.05f + e * 0.95f * dist[i] * 1.6f);
                    dot.setColor(col[i]);
                    dot.setAlpha(Math.round(255 * (1 - q)));
                    c.drawCircle(cx + (float) Math.cos(ang[i]) * d, cy + (float) Math.sin(ang[i]) * d, size[i] * dp * (1 - q * 0.5f), dot);
                }
            }
            float rq = Motion.seg(el, 0.05f, 0.9f);
            if (rq > 0f && rq < 1f) {
                ring.setStrokeWidth(2 * dp);
                ring.setAlpha(Math.round(140 * (1 - rq) * (1 - rq)));
                c.drawCircle(cx, cy, R * (1f + Motion.EXPO_OUT.getInterpolation(rq) * 0.9f), ring);
            }
            float pop = Motion.spring(el, 0.42f, 0.5f);
            c.save();
            c.scale(pop, pop, cx, cy);
            c.drawCircle(cx, cy, R, fill);
            stroke.setStrokeWidth(R * 0.16f);
            float draw = Motion.EXPO_OUT.getInterpolation(Motion.seg(el, 0.18f, 0.7f));
            check.reset();
            check.moveTo(cx - R * 0.42f, cy + R * 0.02f);
            check.lineTo(cx - R * 0.1f, cy + R * 0.34f);
            check.lineTo(cx + R * 0.46f, cy - R * 0.3f);
            PathMeasure pm = new PathMeasure(check, false);
            part.reset();
            pm.getSegment(0, pm.getLength() * draw, part, true);
            c.drawPath(part, stroke);
            c.restore();
            if (anim && el < 1.6f) postInvalidateOnAnimation();
        }
    }

    /** Destello que cruza el botón principal cada pocos segundos, para invitar a tocarlo. */
    static final class Shine extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long t0 = SystemClock.uptimeMillis();
        private final float dp;

        Shine(float dp) {
            this.dp = dp;
        }

        @Override
        public void draw(Canvas c) {
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            android.graphics.Rect b = getBounds();
            float el = ((SystemClock.uptimeMillis() - t0) / 1000f) % 3.6f;
            float q = Motion.seg(el, 1.6f, 2.5f);
            if (q > 0f && q < 1f) {
                float w = b.width(), x = Motion.lerp(-w * 0.3f, w * 1.3f, Motion.EASE.getInterpolation(q));
                p.setShader(new LinearGradient(x - 60 * dp, 0, x + 60 * dp, 0,
                        new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                c.save();
                c.clipRect(b);
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new RectF(b), b.height() / 2f, b.height() / 2f, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawRect(b, p);
                c.restore();
            }
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
