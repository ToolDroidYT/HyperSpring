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

    // Colores (Monet en Android 12+; si no, unos fijos parecidos). Sin «private»: la presentación
    // ({@link Onboarding}) también los usa.
    int bg, card, pressed, text, text2, text3, accent, onAccent, accentSoft, track, warn;
    /** Acento intenso (interruptores, deslizadores, botón de la presentación) con texto blanco encima. */
    int accentStrong;
    /** Los cuatro tonos suaves de las manchas del fondo (coral, rosa, malva, salmón). */
    int[] vivid;

    private final List<Runnable> refreshers = new ArrayList<>();
    private float dp;
    private FrameLayout root, stack;
    /** La presentación de la primera vez, mientras está en pantalla. */
    private Onboarding ob;
    private GlassBar bar;
    private int insetTop, insetBottom, barSpace;
    /** La hoja de nueva versión o de novedades, mientras está abierta. */
    private UpdateSheet sheet;
    /** Columna de Inicio (para meter el aviso de versión nueva cuando llega la respuesta). */
    private LinearLayout homeCol;
    private View updateBanner;
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
        importOldPrefs();
        I18n.init(this);
        dp = getResources().getDisplayMetrics().density;
        palette();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        stack = new FrameLayout(this);
        root.addView(stack, new FrameLayout.LayoutParams(-1, -1));
        // Desenfoque progresivo bajo la barra de estado: lo que sube al desplazarse se desenfoca
        // poco a poco en vez de chocar con la hora.
        final TopBlur topVeil = new TopBlur(this, bg);
        topVeil.setSource(stack);
        final FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, px(40), Gravity.TOP);
        root.addView(topVeil, vlp);

        bar = new GlassBar(this, tabs(), TAB_ICONS, (card & 0x00FFFFFF) | 0x55000000, text2, accent);
        LiveBackground.setOnFrame(new Runnable() {
            @Override
            public void run() {
                // La barra y el desenfoque de arriba muestran el fondo que se mueve: cada fotograma.
                bar.invalidate();
                topVeil.invalidate();
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
                insetBottom = bottom;
                if (sheet != null) sheet.setBottomInset(bottom);
                blp.bottomMargin = bottom + px(2);
                bar.setLayoutParams(blp);
                vlp.height = top + px(44);
                topVeil.setLayoutParams(vlp);
                if (top != insetTop || barSpace == 0) {
                    insetTop = top;
                    barSpace = bottom + px(2) + bar.heightFor(66);
                    for (ScrollView p : pages) pad(p);
                }
                return in;
            }
        });
        setContentView(root);
        // La primera vez (o tras «Ver la presentación»): presentación y configuración guiada.
        // Si no, la pestaña de Inicio entra con sus tarjetas una a una.
        boolean first = b == null && !getSharedPreferences("app", 0).getBoolean("onboarded", false);
        showTab(0, !first && b == null);
        bar.select(0, false);
        if (first) showOnboarding();
        if (b == null) startupUpdates(first);
        setupBack();
    }

    // ---------------------------------------------------------------- gesto de atrás

    /**
     * El gesto de atrás «predictivo» (Android 14 o más). En vez de esperar a que sueltes, el sistema
     * nos avisa de cada movimiento del dedo y la página lo sigue (ver {@link PredictiveBack}).
     * <p>
     * Solo nos apuntamos cuando hay algo que deshacer dentro de la app (una subpágina, otra pestaña,
     * la hoja de actualización o la presentación). En la raíz nos quitamos, y así el sistema hace su
     * propia animación de salir al escritorio. Por eso {@link #syncBack} se llama tras cada cambio.
     * <p>
     * Para que el sistema use esto hace falta {@code android:enableOnBackInvokedCallback="true"} en
     * el manifiesto. Con eso, el gesto ya no llama a onBackPressed (el botón de la flecha sí).
     */
    private Object backCb;                  // un OnBackAnimationCallback; Object para no romper en Android 11-13
    private boolean backOn;
    private PredictiveBack backAnim;
    private boolean sheetDrag;

    private void setupBack() {
        if (Build.VERSION.SDK_INT < 34) return;
        backCb = new android.window.OnBackAnimationCallback() {
            @Override
            public void onBackStarted(android.window.BackEvent e) {
                startBack(e.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT);
            }

            @Override
            public void onBackProgressed(android.window.BackEvent e) {
                if (backAnim != null) backAnim.update(e.getProgress(), e.getTouchY());
                else if (sheetDrag && sheet != null) sheet.dragBack(e.getProgress());
            }

            @Override
            public void onBackInvoked() {
                finishBack();
            }

            @Override
            public void onBackCancelled() {
                if (backAnim != null) backAnim.cancel();
                else if (sheetDrag && sheet != null) sheet.cancelDrag();
                backAnim = null;
                sheetDrag = false;
            }
        };
        syncBack();
    }

    /** Se apunta o se quita del gesto según haya algo que deshacer dentro de la app. */
    private void syncBack() {
        if (Build.VERSION.SDK_INT < 34 || backCb == null) return;
        boolean need = sheet != null || ob != null || pages.size() > 1 || tab != 0;
        if (need == backOn) return;
        backOn = need;
        android.window.OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        android.window.OnBackInvokedCallback cb = (android.window.OnBackInvokedCallback) backCb;
        if (need) d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        else d.unregisterOnBackInvokedCallback(cb);
    }

    /** Empieza el gesto: decide qué se va a mover. */
    private void startBack(boolean fromLeft) {
        backAnim = null;
        sheetDrag = false;
        if (sheet != null) {
            sheetDrag = true;                       // la hoja baja un poco con el dedo
        } else if (ob != null) {
            // La presentación tiene sus propias transiciones: se cambia de paso al soltar.
        } else if (pages.size() > 1) {
            backAnim = new PredictiveBack(pages.get(pages.size() - 1), pages.get(pages.size() - 2), fromLeft, dp);
        } else if (tab != 0) {
            // En otra pestaña, «atrás» lleva a Inicio: la página se aparta y entra Inicio.
            backAnim = new PredictiveBack(pages.get(0), null, fromLeft, dp);
        }
    }

    /** Has soltado: se completa lo que el gesto estaba enseñando. */
    private void finishBack() {
        final PredictiveBack anim = backAnim;
        boolean drag = sheetDrag;
        backAnim = null;
        sheetDrag = false;
        if (anim == null) {
            // Sin animación preparada (botones de navegación, la hoja o la presentación).
            if (drag && sheet != null) sheet.dismiss();
            else if (ob != null) {
                if (!ob.back()) finish();
            } else onBackPressed();
            return;
        }
        if (pages.size() > 1) {
            final ScrollView top = pages.remove(pages.size() - 1);
            if (top == logPage) logText = null;
            anim.commit(new PredictiveBack.Done() {
                @Override
                public void run() {
                    stack.removeView(top);
                }
            });
        } else {
            bar.select(0, true);
            anim.commit(new PredictiveBack.Done() {
                @Override
                public void run() {
                    showTab(0, true);
                }
            });
            tab = 0;                                // para que syncBack ya sepa que vamos a Inicio
        }
        syncBack();
    }

    // ---------------------------------------------------------------- actualizaciones

    /**
     * Al abrir la app: si se acaba de actualizar, las novedades de esta versión; si no, y toca
     * (cada 6 horas como mucho), mira en GitHub si hay una versión nueva. Durante la presentación
     * de la primera vez, nada: ya hay bastante en pantalla.
     */
    private void startupUpdates(boolean first) {
        // Para probar las pantallas sin publicar nada: am start ... --ez debug_update true
        // (la última release cuenta como nueva) o --ez debug_whatsnew true.
        boolean fakeUpdate = getIntent().getBooleanExtra("debug_update", false);
        boolean fakeNews = getIntent().getBooleanExtra("debug_whatsnew", false);
        android.content.SharedPreferences p = Updates.prefs(this);
        int seen = p.getInt("seen_code", 0);
        p.edit().putInt("seen_code", Version.CODE).apply();
        if (first) return;
        if (fakeNews || (seen > 0 && seen < Version.CODE)) {
            Updates.forVersion(this, Version.NAME, new Updates.Callback() {
                @Override
                public void done(Updates.Release r, String error) {
                    if (r != null && !isFinishing()) showUpdate(r, UpdateSheet.WHATS_NEW);
                }
            });
            return;
        }
        if (fakeUpdate) {
            Updates.pretendNewer = true;
            p.edit().remove("upd_shown").apply();
        }
        if (fakeUpdate || Updates.due(this)) checkUpdates(null);
    }

    /**
     * Pregunta a GitHub por la última versión. Si es nueva, pone el aviso en Inicio y abre la hoja
     * (sola, solo la primera vez que se ve esa versión; a mano, siempre).
     *
     * @param status texto de la fila «Buscar actualizaciones» si se pidió a mano; null al abrir la app
     */
    private void checkUpdates(final TextView status) {
        if (status != null) status.setText(I18n.t("Buscando…", "Checking…"));
        Updates.latest(this, new Updates.Callback() {
            @Override
            public void done(Updates.Release r, String error) {
                if (isFinishing() || isDestroyed()) return;
                if (r == null) {
                    if (status != null) {
                        status.setText(I18n.t("No se pudo conectar con GitHub", "Couldn't reach GitHub"));
                        toast(I18n.t("Sin conexión: ", "No connection: ") + error);
                    }
                    return;
                }
                if (r.newer()) {
                    if (status != null) status.setText(I18n.t("Versión " + r.version + " disponible", "Version " + r.version + " available"));
                    addUpdateBanner(r);
                    android.content.SharedPreferences p = Updates.prefs(SettingsActivity.this);
                    if (status != null || !r.version.equals(p.getString("upd_shown", null))) {
                        p.edit().putString("upd_shown", r.version).apply();
                        showUpdate(r, UpdateSheet.UPDATE);
                    }
                } else if (status != null) {
                    status.setText(I18n.t("Estás al día · ", "Up to date · ") + Version.NAME);
                }
            }
        });
    }

    /** Abre la hoja de nueva versión o de novedades encima de todo. */
    void showUpdate(Updates.Release r, int mode) {
        if (sheet != null || ob != null) return;
        sheet = new UpdateSheet(this, r, mode);
        sheet.setBottomInset(insetBottom);
        sheet.setOnClose(new Runnable() {
            @Override
            public void run() {
                sheet = null;
                syncBack();
            }
        });
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        sheet.show();
        syncBack();
    }

    /** Aviso arriba de Inicio: «Versión X disponible», con un brillo que lo cruza de vez en cuando. */
    private View updateBannerView(final Updates.Release r) {
        LinearLayout c = card();
        c.setTag(null);
        GradientDrawable g = round(Controls.blend(card, accentStrong, 0.28f), px(24));
        g.setStroke(Math.max(1, px(0.8f)), (accent & 0x00FFFFFF) | 0x55000000);
        c.setBackground(g);
        c.setForeground(new UpdateSheet.Shimmer(px(24)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(16);
        c.setLayoutParams(lp);
        c.addView(navRow(Icons.DOWNLOAD, I18n.t("Versión " + r.version + " disponible", "Version " + r.version + " available"),
                I18n.t("Toca para ver qué hay de nuevo", "Tap to see what's new"), true, new Runnable() {
                    @Override
                    public void run() {
                        showUpdate(r, UpdateSheet.UPDATE);
                    }
                }));
        return c;
    }

    private void addUpdateBanner(Updates.Release r) {
        if (tab != 0 || homeCol == null || updateBanner != null) return;
        updateBanner = updateBannerView(r);
        // Debajo del título y la explicación de Inicio.
        homeCol.addView(updateBanner, 0);
        Motion.reveal(updateBanner, 0, px(16));
    }

    // ---------------------------------------------------------------- presentación

    /** Pone la presentación encima de todo y esconde la app (no se dibuja por debajo). */
    void showOnboarding() {
        if (ob != null) return;
        ob = new Onboarding(this);
        root.addView(ob, new FrameLayout.LayoutParams(-1, -1));
        stack.setVisibility(View.INVISIBLE);
        bar.setVisibility(View.INVISIBLE);
        ob.start();
        syncBack();
        root.requestApplyInsets();
    }

    /**
     * Cierra la presentación: se aleja y se desvanece mientras la app aparece detrás acercándose,
     * y la barra sube con un muelle. Con {@code restart}, reinicia el launcher y la interfaz.
     */
    void closeOnboarding(final boolean restart) {
        if (ob == null) return;
        getSharedPreferences("app", 0).edit().putBoolean("onboarded", true).apply();
        final Onboarding o = ob;
        ob = null;
        o.setEnabled(false);
        showTab(0, false);
        bar.select(0, false);
        stack.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        stack.setAlpha(0f);
        stack.setScaleX(0.92f);
        stack.setScaleY(0.92f);
        stack.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(800)
                .setInterpolator(Motion.EXPO_OUT).start();
        if (pages.size() > 0) Motion.cascade((ViewGroup) pages.get(0).getChildAt(0), 160, px(24));
        bar.setTranslationY(px(140));
        ValueAnimator up = ValueAnimator.ofFloat(px(140), 0f);
        Spring.apply(up, px(140), 0f, 0f, 0.6f, 0.72f);
        up.setStartDelay(260);
        up.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                bar.setTranslationY((Float) a.getAnimatedValue());
            }
        });
        up.start();
        o.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(460).setInterpolator(Motion.EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        root.removeView(o);
                    }
                }).start();
        if (restart) {
            // Un poco después, para que se vea la animación antes de que parpadee la pantalla.
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    restart(false);
                    restart(true);
                }
            }, 900);
        }
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
        Migrate.listener = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sheet != null) {
            sheet.dismiss();
            return;
        }
        if (ob != null) {
            if (!ob.back()) super.onBackPressed();
            return;
        }
        if (pages.size() > 1) pop();
        else if (tab != 0) {
            bar.select(0, true);
            showTab(0, true);
        } else super.onBackPressed();
    }

    // ---------------------------------------------------------------- colores
    /**
     * Tema fijo de HyperSpring: oscuro, con el coral del icono como color principal y tonos suaves
     * de su familia (rosa, malva, salmón) para los fondos. No depende del fondo de pantalla (Monet):
     * la app se ve igual en todos los móviles, como una marca.
     */
    private void palette() {
        bg = 0xFF0E0F13;                        // casi negro, con un punto frío
        card = 0xFF1C1D23;
        pressed = 0x66363842;
        text = 0xFFF2F2F5;
        text2 = 0xFFA9ABB6;
        text3 = 0xFF767884;
        accent = 0xFFFF9C8C;                    // coral claro: textos, iconos y cabeceras
        onAccent = 0xFF4A1A12;
        accentSoft = 0x24FF9C8C;
        track = 0xFF3A3B44;
        warn = 0xFF33242A;
        accentStrong = 0xFFEF6A57;              // coral del icono, algo más suave: interruptores, botones
        // Manchas de los fondos: coral, rosa, malva y salmón claro. Suaves, de la misma familia.
        vivid = new int[]{0xFFFF8070, 0xFFE8668E, 0xFFB474E0, 0xFFFF9E86};
        // Tarjetas un poco translúcidas: se adivina el fondo que pasa por detrás.
        card = (card & 0x00FFFFFF) | 0xD9000000;
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
        // Entrada escalonada: título, explicación y cada tarjeta (y sus filas) una detrás de otra.
        if (animate) Motion.cascade((ViewGroup) p.getChildAt(0), 0, px(22));
        for (ScrollView o : old) stack.removeView(o);
        syncBack();
    }

    private void push(ScrollView p) {
        final ScrollView under = pages.get(pages.size() - 1);
        pages.add(p);
        stack.addView(p);
        p.setTranslationX(stack.getWidth());
        p.animate().translationX(0f).setDuration(380).setInterpolator(Motion.EXPO_OUT).setUpdateListener(barTick()).start();
        Motion.cascade((ViewGroup) p.getChildAt(0), 110, px(18));
        // La de debajo se aleja un poco (profundidad), además de irse a un lado.
        under.animate().translationX(-stack.getWidth() * 0.25f).alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .setDuration(380).setInterpolator(EASE).start();
        syncBack();
    }

    private void pop() {
        final ScrollView top = pages.remove(pages.size() - 1);
        ScrollView under = pages.get(pages.size() - 1);
        under.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(EASE).setUpdateListener(barTick()).start();
        top.animate().translationX(stack.getWidth()).setDuration(320).setInterpolator(EASE).withEndAction(new Runnable() {
            @Override
            public void run() {
                stack.removeView(top);
            }
        }).start();
        if (top == logPage) logText = null;
        syncBack();
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

        TextView bigTitle = null;
        if (title != null) {
            bigTitle = text(title, 34, text);
            bigTitle.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            bigTitle.setLetterSpacing(-0.01f);
            bigTitle.setPadding(px(8), px(10), px(8), 0);
            list.addView(bigTitle);
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
        // «Acerca de»: manchas de color intensas por toda la pantalla. El resto: curvas de nivel
        // finas que fluyen sin fin, con movimiento pero sin quitar protagonismo a las tarjetas.
        (glowNext ? new LiveBackground(bg, vivid, 0.48f, true)
                : LiveBackground.lines(bg, vivid[0], vivid[1], 0.26f, dp)).attachTo(sv);
        glowNext = false;
        sv.addView(list);
        final TextView parallax = bigTitle;
        final float fade = px(110);
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                // El título grande va más despacio que el contenido y se apaga al subir (paralaje).
                if (parallax != null) {
                    parallax.setTranslationY(y * 0.42f);
                    parallax.setAlpha(Math.max(0f, 1f - y / fade));
                }
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
        homeCol = c;
        updateBanner = null;
        // Si la última búsqueda encontró una versión nueva, el aviso sigue ahí aunque no haya red.
        Updates.Release cached = Updates.cached(this);
        if (cached != null && cached.newer()) {
            updateBanner = updateBannerView(cached);
            c.addView(updateBanner);
        }
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
            c.addView(notice(I18n.t("Modo seguro del escritorio: el launcher se cerró varias veces seguidas y HyperSpring "
                            + "ha dejado de cargar sus animaciones ahí (el launcher funciona normal). Si no fue por ellas, "
                            + "reactívalas y reinicia el launcher.",
                    "Launcher safe mode: the launcher crashed several times in a row, so HyperSpring stopped loading its "
                            + "animations there (the launcher works normally). If they weren't the cause, turn them back on "
                            + "and restart the launcher."),
                    I18n.t("Reactivar", "Turn back on"), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            send(Remote.RESET_SAFE);
                            toast(I18n.t("Reactivado: reinicia el launcher", "Turned on: restart the launcher"));
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
        c.addView(featuresCard());

        c.addView(label(I18n.t("Perfiles", "Presets")));
        LinearLayout pr = card();
        pr.setTag(Motion.CASCADE);
        for (final Object[] preset : Presets.ALL) {
            pr.addView(navRow(Icons.SLIDERS, Presets.name(preset), Presets.about(preset), false, new Runnable() {
                @Override
                public void run() {
                    confirmPreset(preset);
                }
            }));
        }
        c.addView(pr);

        c.addView(label(I18n.t("Acciones", "Actions")));
        LinearLayout a = card();
        a.setTag(Motion.CASCADE);
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

    /** Tarjeta con los interruptores de cada función (Inicio y la presentación). */
    LinearLayout featuresCard() {
        LinearLayout f = card();
        f.setTag(Motion.CASCADE);
        f.addView(toggleRow(Icons.SPARKLE, item(Tune.ITEMS, "icehome_genie_on"), I18n.t("Abrir y cerrar apps", "Opening and closing apps")));
        f.addView(toggleRow(Icons.LAYERS, item(Tune.ITEMS, "icehome_curtain_on"), I18n.t("Recientes que se doblan al deslizarlas", "Recents that bend as you swipe")));
        f.addView(toggleRow(Icons.WIDGETS, item(Tune.ITEMS, "icehome_stack_on"), I18n.t("Pila de widgets con baraja", "Shuffling widget stack")));
        f.addView(toggleRow(Icons.FOLDER, item(Tune.ITEMS, "icehome_folder_morph_on"), I18n.t("Carpetas con muelle", "Springy folders")));
        f.addView(toggleRow(Icons.DROP, item(Tune.ITEMS, "icehome_folder_glass_on"), I18n.t("Cristal en las carpetas", "Glass folders")));
        f.addView(toggleRow(Icons.CURTAIN, item(com.icehome.up.telon.Tune.ITEMS, "telon_on"), I18n.t("Telón al cambiar de pantalla", "Curtain when switching screens")));
        f.addView(toggleRow(Icons.SLIDERS, item(com.icehome.up.cc.Tune.ITEMS, "kashiplus_cc_on"), I18n.t("Cristal en el centro de control", "Glass control center")));
        return f;
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
        k.addView(statusRow(Icons.HOME, I18n.t("Escritorio", "Launcher"), appVersion("com.miui.home"), "launcher_seen"));
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
        r.addView(navRow(Icons.SPARKLE, I18n.t("Ver la presentación", "Replay the tour"),
                I18n.t("La bienvenida animada y la configuración guiada", "The animated welcome and the guided setup"), false, new Runnable() {
            @Override
            public void run() {
                showOnboarding();
            }
        }));
        final View check = navRow(Icons.DOWNLOAD, I18n.t("Buscar actualizaciones", "Check for updates"),
                I18n.t("Tienes la " + Version.NAME, "You have " + Version.NAME), false, null);
        // El subtítulo de la fila hace de estado: «Buscando…», «Estás al día»…
        final TextView checkStatus = (TextView) ((LinearLayout) ((LinearLayout) check).getChildAt(1)).getChildAt(1);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                checkUpdates(checkStatus);
            }
        });
        r.addView(check);
        r.addView(prefToggleRow(Icons.RESTART, I18n.t("Buscar al abrir la app", "Check when opening the app"),
                I18n.t("Como mucho cada 6 horas. Solo pregunta a GitHub por la última versión.",
                        "At most every 6 hours. It only asks GitHub for the latest version."), "upd_auto", true));
        r.addView(navRow(Icons.LIST, I18n.t("Exportar ajustes", "Export settings"),
                I18n.t("Guarda todos los ajustes en un archivo. Adjúntalo si reportas un error.",
                        "Saves every setting to a file. Attach it when you report a bug."), false, new Runnable() {
            @Override
            public void run() {
                pickFile(true);
            }
        }));
        r.addView(navRow(Icons.LIST, I18n.t("Importar ajustes", "Import settings"),
                I18n.t("Sustituye los ajustes por los de un archivo exportado", "Replaces the settings with those from an exported file"), false, new Runnable() {
            @Override
            public void run() {
                pickFile(false);
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
        // Logo opaco: el fondo de colores pasa por detrás pero no lo tiñe.
        final ImageView logo = new ImageView(this);
        logo.setImageDrawable(new LogoDrawable(LiveBackground.CORAL, 0xFF));
        col.addView(logo, new LinearLayout.LayoutParams(px(96), px(96)));
        floatAndSpin(logo);

        final TextView name = text("HyperSpring", 36, Color.WHITE);
        name.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        name.setLetterSpacing(-0.01f);
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

    /**
     * El logo de «Acerca de» flota despacio arriba y abajo; al tocarlo da una vuelta con un muelle.
     * El vaivén solo corre mientras la página está en pantalla.
     */
    private void floatAndSpin(final View logo) {
        final android.animation.ObjectAnimator fl = android.animation.ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, -px(5), px(5));
        fl.setDuration(2600);
        fl.setRepeatMode(ValueAnimator.REVERSE);
        fl.setRepeatCount(ValueAnimator.INFINITE);
        fl.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        logo.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                fl.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                fl.cancel();
            }
        });
        sink(logo);
        logo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                float from = v.getRotation() % 360f;
                ValueAnimator spin = ValueAnimator.ofFloat(from, 360f);
                Spring.apply(spin, from, 360f, 0f, 0.7f, 0.6f);
                spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        logo.setRotation((Float) a.getAnimatedValue());
                    }
                });
                spin.start();
            }
        });
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
        String st = state(stateKey);
        long when = 0;
        String ver = null, engine = null;
        if (st != null) {
            String[] parts = st.split("\\|");
            try {
                when = Long.parseLong(parts[0]);
            } catch (Throwable ignored) {
            }
            if (parts.length > 1) ver = parts[1];
            // Tercer dato (desde la 2.0): con qué API de Xposed se cargó el módulo ahí.
            if (parts.length > 2 && !"cc_seen".equals(stateKey) && when >= bootTime()) engine = parts[2];
        }
        LinearLayout tc = titles(title, version != null ? version : I18n.t("No encontrado", "Not found"));
        if (tc.getChildCount() > 1) {
            TextView v = (TextView) tc.getChildAt(1);
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
        if (engine != null) {
            TextView e = text(engine, 12, text2);
            e.setMaxLines(1);
            e.setEllipsize(TextUtils.TruncateAt.END);
            tc.addView(e);
        }
        r.addView(tc);
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
                + " · launcher " + appVersion("com.miui.home") + "\n";
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

    /**
     * Icono de una fila: en coral sobre una baldosa coral muy suave. Todas iguales, como en los
     * ajustes de HyperOS con un tema: la lista se ve ordenada y del mismo color que la app.
     * El color que se pasa se ignora.
     */
    private ImageView icon(int kind, int color) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icons(kind, accent));
        iv.setPadding(px(7), px(7), px(7), px(7));
        iv.setBackground(round((accentStrong & 0x00FFFFFF) | 0x2E000000, px(11)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(36), px(36));
        lp.rightMargin = px(14);
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
        if (action != null) {
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    action.run();
                }
            });
        }
        sink(r);
        return r;
    }

    /** Interruptor de una preferencia de la propia app (no de las animaciones). */
    private View prefToggleRow(int iconKind, String title, String sub, final String key, boolean def) {
        LinearLayout r = rowBase();
        r.addView(icon(iconKind, text));
        r.addView(titles(title, sub));
        final Controls.Toggle t = new Controls.Toggle(this, accentStrong, track);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(12);
        r.addView(t, lp);
        t.setChecked(getSharedPreferences("app", 0).getBoolean(key, def), false);
        t.setOnChange(new Controls.Toggle.OnChange() {
            @Override
            public void onChange(boolean on) {
                getSharedPreferences("app", 0).edit().putBoolean(key, on).apply();
            }
        });
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                t.toggle(true);
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
        final Controls.Toggle t = new Controls.Toggle(this, accentStrong, track);
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
        final Controls.Slider sl = new Controls.Slider(this, track, accentStrong);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(6);
        slp.leftMargin = -px(22);
        slp.rightMargin = -px(20);
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
    /** Encabezado de grupo: pequeño y gris, como en los ajustes del sistema. */
    private TextView label(String s) {
        // Cabecera de grupo en el color del sistema: da color a la lista sin cargarla.
        TextView h = text(s, 13.5f, accent);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setLetterSpacing(0.02f);
        h.setPadding(px(12), px(26), px(12), px(9));
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
        c.setTag(Motion.CASCADE);                    // al entrar la página, sus filas aparecen una a una
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
    /**
     * Primera vez que se abre la app tras pasar a libxposed (1.1.0): trae los ajustes de las
     * versiones anteriores (ver {@link Migrate}). Va en segundo plano porque pide root; si
     * importa algo, la pantalla se vuelve a crear con los valores importados.
     */
    /** Pregunta antes de aplicar un perfil: pisa los ajustes de animación que hubiera. */
    private void confirmPreset(final Object[] preset) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.t("Aplicar el perfil «", "Apply the “") + Presets.name(preset) + I18n.t("»", "” preset"))
                .setMessage(Presets.about(preset) + "\n\n" + I18n.t(
                        "Cambia los ajustes de forma, velocidad y calidad de las animaciones. No enciende ni apaga "
                                + "funciones y no toca el cristal ni los fotogramas por segundo. Si quieres poder volver "
                                + "atrás, exporta antes tus ajustes (Acerca de → Exportar ajustes).",
                        "It changes the shape, speed and quality settings of the animations. It doesn't turn features "
                                + "on or off, and it leaves the glass and the frame rate alone. To be able to go back, "
                                + "export your settings first (About → Export settings)."))
                .setNegativeButton(I18n.t("Cancelar", "Cancel"), null)
                .setPositiveButton(I18n.t("Aplicar", "Apply"), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Presets.apply(SettingsActivity.this, preset);
                        settingsReplaced();
                        toast(I18n.t("Perfil aplicado: ", "Preset applied: ") + Presets.name(preset));
                    }
                })
                .show();
    }

    /** Tras cambiar muchos ajustes de golpe: avisa a los procesos enganchados y repinta la app. */
    void settingsReplaced() {
        if (Migrate.needed(this)) Migrate.skip(this);       // ya no hay que importar los antiguos
        send(Remote.TUNE);
        for (Runnable rf : refreshers) rf.run();
    }

    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2;

    /**
     * Abre el selector de archivos del sistema para guardar (exportar) o elegir (importar) el
     * archivo. Así la app no necesita ningún permiso de almacenamiento.
     */
    private void pickFile(boolean export) {
        try {
            android.content.Intent i = new android.content.Intent(export
                    ? android.content.Intent.ACTION_CREATE_DOCUMENT : android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            // Al importar se acepta cualquier tipo: algunos gestores no reconocen .json.
            i.setType(export ? "application/json" : "*/*");
            if (export) i.putExtra(android.content.Intent.EXTRA_TITLE, Backup.FILE_NAME);
            startActivityForResult(i, export ? REQ_EXPORT : REQ_IMPORT);
        } catch (Throwable t) {
            toast(I18n.t("Este móvil no tiene selector de archivos", "This phone has no file picker"));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            if (request == REQ_EXPORT) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    out.write(Backup.export(this).getBytes("UTF-8"));
                }
                toast(I18n.t("Ajustes exportados", "Settings exported"));
            } else if (request == REQ_IMPORT) {
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    byte[] buf = new byte[8192];
                    // Un archivo de ajustes ocupa unos pocos KB: no se lee más de 1 MB.
                    for (int n; sb.length() < (1 << 20) && (n = in.read(buf)) > 0; ) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                int n = Backup.restore(this, sb.toString());
                if (n < 0) {
                    toast(I18n.t("Ese archivo no es de ajustes de HyperSpring", "That file is not a HyperSpring settings file"));
                    return;
                }
                settingsReplaced();
                toast(I18n.t("Ajustes importados: ", "Settings imported: ") + n + ". "
                        + I18n.t("Reinicia el launcher y la interfaz para las funciones.", "Restart the launcher and System UI for the features."));
            }
        } catch (Throwable t) {
            toast(I18n.t("No se pudo: ", "Failed: ") + t.getMessage());
        }
    }

    private void importOldPrefs() {
        Migrate.listener = new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(I18n.t("Ajustes de la versión anterior importados", "Settings from the previous version imported"));
                        if (!isFinishing() && !isDestroyed()) recreate();
                    }
                });
            }
        };
        Migrate.start(this);
    }

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
        // Si aún no se han importado los ajustes antiguos (sin root), ya no se hará: pisarían este.
        if (Migrate.needed(this)) Migrate.skip(this);
        send(Remote.TUNE);
    }

    private void send(String action) {
        Remote.send(this, action);
    }

    /**
     * Reinicia el launcher o la interfaz. Con root (lo normal con LSPosed) se cierra el proceso
     * desde fuera, que funciona siempre. Sin root, se le pide al proceso que se cierre él mismo; pero
     * HyperOS congela el launcher mientras no se ve, así que la orden puede no llegar hasta volver
     * al escritorio.
     */
    void restart(final boolean systemUi) {
        final String name = systemUi ? I18n.t("la interfaz del sistema", "System UI") : I18n.t("el launcher", "the launcher");
        toast(I18n.t("Reiniciando ", "Restarting ") + name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Se cierra el proceso viejo (por su pid) y el sistema lo vuelve a abrir solo.
                String pkg = systemUi ? "com.android.systemui" : "com.miui.home";
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
        if (ob != null) return;                 // en la presentación, el reinicio se ofrece al final
        if (restartToast != null) restartToast.cancel();
        restartToast = android.widget.Toast.makeText(this, systemUi
                ? I18n.t("Se aplica al reiniciar la interfaz (Inicio → Reiniciar interfaz del sistema)", "Applies after restarting System UI (Home → Restart System UI)")
                : I18n.t("Se aplica al reiniciar el launcher (Inicio → Reiniciar launcher)", "Applies after restarting the launcher (Home → Restart launcher)"),
                android.widget.Toast.LENGTH_SHORT);
        restartToast.show();
    }

    /** Hundimiento al pulsar: la vista se encoge un poco y vuelve con un pequeño rebote. */
    void sink(View v) {
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
    String state(String key) {
        try {
            return getSharedPreferences("state", 0).getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    static long bootTime() {
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

    boolean installed(String pkg) {
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
