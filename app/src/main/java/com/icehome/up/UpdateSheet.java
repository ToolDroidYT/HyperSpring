package com.icehome.up;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Hoja de «nueva versión» (y de «novedades» tras actualizar). Sube desde abajo con un muelle
 * sobre un fondo de manchas de color, y cuenta la novedad por partes:
 * <ol>
 * <li>El logo aparece con un rebote y suelta ondas, como un aviso que «late».</li>
 * <li>El número de versión gira: la tuya sube y se va, la nueva entra desde abajo.</li>
 * <li>Los cambios (las notas de la release de GitHub) entran uno a uno.</li>
 * <li>El botón lleva un brillo que lo recorre; al tocarlo se convierte en la barra de progreso de
 * la descarga y, al terminar, abre el instalador del sistema.</li>
 * </ol>
 */
final class UpdateSheet extends FrameLayout {

    static final int UPDATE = 0, WHATS_NEW = 1;

    private final SettingsActivity act;
    private final float dp;
    private final Updates.Release rel;
    private final int mode;
    private final View scrim;
    private final LinearLayout sheet;
    private final LinearLayout notes;
    private final Badge badge;
    private final TextView eyebrow, oldVer, newVer, sub;
    private final ProgressButton action;
    private final LinearLayout links;
    private Thread download;
    private boolean closing;
    private Runnable onClose;

    UpdateSheet(SettingsActivity a, Updates.Release r, int mode) {
        super(a);
        act = a;
        rel = r;
        this.mode = mode;
        dp = a.getResources().getDisplayMetrics().density;
        setClickable(true);

        scrim = new View(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        addView(scrim, new LayoutParams(-1, -1));

        // La hoja: esquinas de arriba redondeadas, sin pasarse del 86 % de la pantalla.
        final int maxH = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.86f);
        sheet = new LinearLayout(a) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.min(maxH, MeasureSpec.getSize(h)), MeasureSpec.AT_MOST));
            }
        };
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);                            // los toques en la hoja no la cierran
        new LiveBackground(a.bg, a.vivid, 0.45f, true).attachTo(sheet);
        final float rad = 30 * dp;
        sheet.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), Math.round(v.getHeight() + rad), rad);
            }
        });
        sheet.setClipToOutline(true);
        sheet.setPadding(px(24), px(10), px(24), 0);
        addView(sheet, new LayoutParams(-1, -2, Gravity.BOTTOM));

        View handle = new View(a);
        handle.setBackground(round(0x66FFFFFF, px(2)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(px(36), px(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = px(14);
        sheet.addView(handle, hl);

        badge = new Badge(a, a.accent);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(120), px(120));
        bl.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(badge, bl);

        eyebrow = center(text(mode == UPDATE ? I18n.t("NUEVA VERSIÓN", "NEW VERSION") : I18n.t("NOVEDADES", "WHAT'S NEW"), 12.5f, a.accent));
        eyebrow.setLetterSpacing(0.16f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sheet.addView(eyebrow);

        // Dos números superpuestos: el viejo se va hacia arriba y el nuevo llega desde abajo.
        FrameLayout ver = new FrameLayout(a);
        ver.setClipChildren(true);
        oldVer = center(text(Version.NAME, 44, a.text3));
        newVer = center(text(r.version, 44, a.text));
        for (TextView t : new TextView[]{oldVer, newVer}) {
            t.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            t.setLetterSpacing(-0.01f);
            ver.addView(t, new LayoutParams(-1, -2));
        }
        if (mode == UPDATE) newVer.setAlpha(0f);
        else oldVer.setVisibility(GONE);
        sheet.addView(ver);

        String s;
        if (mode == UPDATE) {
            s = I18n.t("Tienes la " + Version.NAME, "You have " + Version.NAME);
            if (r.apkName != null) s += " · " + r.apkName + (r.apkSize > 0 ? " · " + size(r.apkSize) : "");
        } else {
            s = I18n.t("Actualizada. Esto es lo que ha cambiado:", "Updated. Here's what changed:");
        }
        sub = center(text(s, 14, a.text2));
        sub.setPadding(0, px(2), 0, px(14));
        sheet.addView(sub);

        // Las notas, en su propio desplazamiento (la hoja no crece más allá del 86 %).
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        sv.setFadingEdgeLength(px(24));
        sv.setVerticalFadingEdgeEnabled(true);
        notes = new LinearLayout(a);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setBackground(round((a.card & 0x00FFFFFF) | 0xB3000000, px(22)));
        notes.setPadding(px(18), px(8), px(18), px(16));
        render(r.notes);
        sv.addView(notes);
        sheet.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        action = new ProgressButton(a, a.accentStrong, mode == UPDATE);
        action.setLabel(mode == UPDATE ? I18n.t("Descargar e instalar", "Download and install") : I18n.t("Entendido", "Got it"));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, px(56));
        al.topMargin = px(16);
        sheet.addView(action, al);
        a.sink(action);
        action.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (UpdateSheet.this.mode == UPDATE) start();
                else dismiss();
            }
        });

        links = new LinearLayout(a);
        links.setGravity(Gravity.CENTER);
        if (mode == UPDATE) {
            links.addView(link(I18n.t("Más tarde", "Later"), new Runnable() {
                @Override
                public void run() {
                    dismiss();
                }
            }));
        }
        links.addView(link(I18n.t("Ver en GitHub", "View on GitHub"), new Runnable() {
            @Override
            public void run() {
                open(rel.page);
            }
        }));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = px(4);
        sheet.addView(links, ll);

        // Capa de partículas por encima de todo. No recoge toques: solo dibuja.
        sparks = new Particles(a);
        addView(sparks, new LayoutParams(-1, -1));
    }

    private final Particles sparks;

    /** Lanza {@code n} partículas desde el centro del logo, esté donde esté la hoja en ese momento. */
    private void burstFromBadge(int n, float speed) {
        int[] me = new int[2], b = new int[2];
        sparks.getLocationInWindow(me);
        badge.getLocationInWindow(b);
        // getLocationInWindow ya cuenta la traslación de la hoja; la escala del rebote no importa
        // porque el centro del logo no se mueve al escalar.
        float cx = b[0] - me[0] + badge.getWidth() / 2f;
        float cy = b[1] - me[1] + badge.getHeight() / 2f;
        sparks.emit(cx, cy, n, speed, 30 * dp);
    }

    /** Lo que hay que hacer al cerrarse del todo. */
    void setOnClose(Runnable r) {
        onClose = r;
    }

    /** Ajusta el hueco de abajo a la barra de gestos. */
    void setBottomInset(int px) {
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(), sheet.getPaddingRight(), px + Math.round(14 * dp));
    }

    // ------------------------------------------------------------------ entrada y salida

    void show() {
        scrim.animate().alpha(1f).setDuration(320).setInterpolator(Motion.EASE).start();
        sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        sheet.post(new Runnable() {
            @Override
            public void run() {
                float from = sheet.getHeight() + 40 * dp;
                ValueAnimator up = ValueAnimator.ofFloat(from, 0f);
                Spring.apply(up, from, 0f, 0f, 0.55f, 0.82f);
                up.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator va) {
                        sheet.setTranslationY((Float) va.getAnimatedValue());
                    }
                });
                up.start();
            }
        });
        // El logo llega con un rebote y empieza a «latir».
        badge.setScaleX(0.3f);
        badge.setScaleY(0.3f);
        badge.setAlpha(0f);
        badge.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(160).setDuration(620)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
        // Cuando el logo «aterriza», un primer puñado de chispas. En «novedades» es la única
        // explosión (no hay giro de versión), así que es más grande.
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!closing) burstFromBadge(mode == UPDATE ? 28 : 80, mode == UPDATE ? 0.6f : 1f);
            }
        }, 480);
        Motion.reveal(eyebrow, 260, px(12));
        Motion.reveal(mode == UPDATE ? oldVer : newVer, 320, px(16));
        Motion.reveal(sub, 400, px(12));
        if (mode == UPDATE) {
            // El cambio de número: un tic al girar.
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    flipVersion();
                }
            }, 900);
        }
        Motion.reveal((View) notes.getParent(), 460, px(20));
        Motion.cascade(notes, 520, px(14));
        Motion.reveal(action, 640, px(18));
        Motion.reveal(links, 720, px(10));
    }

    private void flipVersion() {
        float d = 34 * dp;
        oldVer.animate().translationY(-d).alpha(0f).setDuration(380).setInterpolator(Motion.EASE).start();
        newVer.setTranslationY(d);
        newVer.setAlpha(0f);
        newVer.animate().translationY(0f).alpha(1f).setDuration(620).setInterpolator(new OvershootInterpolator(1.6f)).start();
        newVer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        badge.burst();
        burstFromBadge(90, 1f);                              // la celebración de verdad
    }

    /**
     * Gesto de atrás en curso: la hoja baja un poco y se encoge con el dedo, como si la fueras a
     * tirar hacia abajo. Al soltar se cierra desde ahí ({@link #dismiss}) o vuelve ({@link #cancelDrag}).
     */
    void dragBack(float raw) {
        if (closing) return;
        float p = 1f - (1f - raw) * (1f - raw);             // responde rápido al principio
        sheet.animate().cancel();
        sheet.setTranslationY(p * 90 * dp);
        float s = 1f - 0.06f * p;
        sheet.setScaleX(s);
        sheet.setScaleY(s);
        scrim.setAlpha(1f - 0.4f * p);
    }

    /** Te arrepientes del gesto: la hoja sube a su sitio con un muelle que rebota un poco. */
    void cancelDrag() {
        if (closing) return;
        final float y0 = sheet.getTranslationY(), s0 = sheet.getScaleX(), a0 = scrim.getAlpha();
        ValueAnimator back = ValueAnimator.ofFloat(1f, 0f);
        Spring.apply(back, 1f, 0f, 0f, 0.4f, 0.7f);
        back.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float k = (Float) va.getAnimatedValue();
                sheet.setTranslationY(y0 * k);
                float s = 1f - (1f - s0) * k;
                sheet.setScaleX(s);
                sheet.setScaleY(s);
                scrim.setAlpha(1f - (1f - a0) * Math.max(0f, k));
            }
        });
        back.start();
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        if (download != null) download.interrupt();
        scrim.animate().alpha(0f).setDuration(260).start();
        sheet.animate().translationY(sheet.getHeight() + 40 * dp).setDuration(300).setInterpolator(Motion.EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (getParent() != null) ((android.view.ViewGroup) getParent()).removeView(UpdateSheet.this);
                        if (onClose != null) onClose.run();
                    }
                }).start();
    }

    // ------------------------------------------------------------------ descarga

    private void start() {
        if (download != null) return;
        if (rel.apkUrl == null) {                       // la release no trae APK: a GitHub
            open(rel.page);
            return;
        }
        action.setProgress(0f);
        download = Updates.download(act, rel, new Updates.Progress() {
            @Override
            public void progress(float f) {
                action.setProgress(f);
            }

            @Override
            public void done(File apk, String error) {
                download = null;
                if (closing) return;
                if (apk == null) {
                    failed(error);
                    return;
                }
                action.setLabel(I18n.t("Instalando…", "Installing…"));
                Updates.install(act, apk, new Updates.Callback() {
                    @Override
                    public void done(Updates.Release r, String err) {
                        failed(err);
                    }
                });
            }
        });
    }

    private void failed(String error) {
        action.setLabel(I18n.t("Reintentar", "Try again"));
        // Sacudida corta, como un «no» con la cabeza.
        ObjectAnimator.ofFloat(action, View.TRANSLATION_X, 0, 14 * dp, -11 * dp, 8 * dp, -5 * dp, 2 * dp, 0)
                .setDuration(460).start();
        action.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Toast.makeText(act, I18n.t("No se pudo actualizar: ", "Couldn't update: ") + error, Toast.LENGTH_LONG).show();
    }

    private void open(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(act, url, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ notas (Markdown sencillo)

    /**
     * Convierte las notas de la release (Markdown de GitHub) en filas: cabeceras, viñetas (con
     * sangría) y párrafos, con negrita y código. Las tablas se saltan (no caben en un móvil).
     */
    private void render(String md) {
        if (md == null || md.trim().isEmpty()) {
            notes.addView(para(I18n.t("Esta versión no trae notas.", "This release has no notes.")));
            return;
        }
        int n = 0;
        for (String raw : md.replace("\r", "").split("\n")) {
            String line = raw.replaceAll("\\s+$", "");
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("|") || t.startsWith("---")) continue;
            if (++n > 60) {
                notes.addView(para(I18n.t("…y más en GitHub.", "…and more on GitHub.")));
                break;
            }
            if (t.startsWith("#")) {
                notes.addView(heading(t.replaceAll("^#+\\s*", "")));
            } else if (t.matches("^\\*\\*[^*]+\\*\\*:?$")) {
                notes.addView(heading(t.replaceAll("\\*\\*|:$", "")));
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                notes.addView(bullet(t.substring(2), Math.min(2, indent / 2)));
            } else {
                notes.addView(para(t));
            }
        }
    }

    private TextView heading(String s) {
        TextView h = text(s, 13.5f, act.accent);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setLetterSpacing(0.03f);
        h.setPadding(0, px(14), 0, px(4));
        return h;
    }

    private TextView para(String s) {
        TextView p = text("", 14.5f, act.text2);
        p.setText(inline(s));
        p.setLineSpacing(0, 1.15f);
        p.setPadding(0, px(8), 0, 0);
        return p;
    }

    private View bullet(String s, int level) {
        LinearLayout r = new LinearLayout(act);
        r.setPadding(px(level * 16), px(6), 0, 0);
        View dot = new View(act);
        dot.setBackground(round(level == 0 ? act.accent : act.text3, px(3)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(level == 0 ? 6 : 5), px(level == 0 ? 6 : 5));
        dl.topMargin = px(8);
        dl.rightMargin = px(12);
        r.addView(dot, dl);
        TextView t = text("", 14.5f, act.text2);
        t.setText(inline(s));
        t.setLineSpacing(0, 1.15f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    /** **negrita** (en blanco), `código` (monoespaciado) y [enlaces](url) (se queda el texto). */
    private CharSequence inline(String s) {
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        SpannableStringBuilder b = new SpannableStringBuilder();
        int boldAt = -1, codeAt = -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.startsWith("**", i)) {
                if (boldAt < 0) boldAt = b.length();
                else {
                    b.setSpan(new StyleSpan(Typeface.BOLD), boldAt, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    b.setSpan(new ForegroundColorSpan(act.text), boldAt, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    boldAt = -1;
                }
                i++;
            } else if (s.charAt(i) == '`') {
                if (codeAt < 0) codeAt = b.length();
                else {
                    b.setSpan(new TypefaceSpan("monospace"), codeAt, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    codeAt = -1;
                }
            } else {
                b.append(s.charAt(i));
            }
        }
        return b;
    }

    // ------------------------------------------------------------------ piezas

    private TextView link(String s, final Runnable r) {
        TextView t = text(s, 14.5f, act.text2);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(18), px(14), px(18), px(10));
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        act.sink(t);
        return t;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private static TextView center(TextView t) {
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576f) : (bytes / 1024) + " KB";
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ------------------------------------------------------------------ logo que late

    /** El logo con ondas que salen de él sin parar; {@link #burst()} lanza una más fuerte. */
    static final class Badge extends View {
        private final LogoDrawable logo = new LogoDrawable(LiveBackground.CORAL, 0xFF);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private long burstAt = -10000;

        Badge(Context c, int color) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(color);
        }

        void burst() {
            burstAt = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r0 = 32 * dp, span = Math.min(cx, cy) - r0;
            long now = SystemClock.uptimeMillis();
            boolean anim = ValueAnimator.areAnimatorsEnabled();
            if (anim) {
                // Tres ondas desfasadas: nacen en el borde del logo, crecen y se apagan.
                for (int i = 0; i < 3; i++) {
                    float t = ((now + i * 800L) % 2400L) / 2400f;
                    ring.setStrokeWidth((2.2f - 1.4f * t) * dp);
                    ring.setAlpha(Math.round(150 * (1f - t) * (1f - t)));
                    c.drawCircle(cx, cy, r0 + span * t, ring);
                }
                float b = (now - burstAt) / 700f;
                if (b < 1f) {
                    ring.setStrokeWidth(3f * dp * (1f - b));
                    ring.setAlpha(Math.round(230 * (1f - b)));
                    c.drawCircle(cx, cy, r0 + span * (float) Math.sqrt(b) * 1.1f, ring);
                }
            }
            int s = Math.round(r0 * 2);
            logo.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            logo.draw(c);
            if (anim && isAttachedToWindow()) postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ partículas

    /**
     * Explosión de chispas y confeti. Cada partícula es un punto con velocidad que frena con el aire
     * (rozamiento), cae con la gravedad y se apaga al final de su vida. Hay dos tipos:
     * <ul>
     * <li><b>Chispa:</b> un trazo corto en la dirección en la que se mueve (parece una estela).</li>
     * <li><b>Confeti:</b> un rectangulito que gira y se «da la vuelta» (se estrecha y se ensancha),
     * como un papel que cae.</li>
     * </ul>
     * Se guarda todo en arrays de float en vez de un objeto por partícula: así no se crea basura en
     * cada fotograma y el recolector de memoria no provoca tirones.
     */
    static final class Particles extends View {
        private static final int MAX = 220;
        // Posición, velocidad, vida, vida total, giro, velocidad de giro, tamaño, tipo.
        private final float[] x = new float[MAX], y = new float[MAX], vx = new float[MAX], vy = new float[MAX];
        private final float[] life = new float[MAX], ttl = new float[MAX], rot = new float[MAX], spin = new float[MAX];
        private final float[] size = new float[MAX];
        private final int[] color = new int[MAX];
        private final boolean[] confetti = new boolean[MAX];
        private int count;
        private long last;
        private final float dp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final java.util.Random rnd = new java.util.Random();
        // Coral, rosa, malva y un poco de blanco para que «brille».
        private static final int[] COLORS = {0xFFFF6A55, 0xFFE94B7C, 0xFF9C5CF0, 0xFFFF9C8C, 0xFFFFFFFF};

        Particles(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        /**
         * @param speed  1 = explosión completa; menos, más tímida
         * @param radius las partículas nacen en el borde del logo, no en su centro
         */
        void emit(float cx, float cy, int n, float speed, float radius) {
            if (!ValueAnimator.areAnimatorsEnabled()) return;   // sin animaciones, sin fiesta
            for (int k = 0; k < n; k++) {
                int i;
                if (count < MAX) i = count++;
                else i = rnd.nextInt(MAX);                       // lleno: se recicla una cualquiera
                double ang = rnd.nextDouble() * Math.PI * 2;
                // Velocidad en dp por segundo. Un poco hacia arriba de más para que «salte».
                float v = (160 + rnd.nextFloat() * 420) * dp * speed;
                x[i] = cx + (float) Math.cos(ang) * radius;
                y[i] = cy + (float) Math.sin(ang) * radius;
                vx[i] = (float) Math.cos(ang) * v;
                vy[i] = (float) Math.sin(ang) * v - 140 * dp * speed;
                life[i] = 0f;
                ttl[i] = 1.5f + rnd.nextFloat() * 1.2f;
                confetti[i] = rnd.nextFloat() < 0.45f;
                size[i] = (confetti[i] ? 4.5f + rnd.nextFloat() * 3.5f : 2.4f + rnd.nextFloat() * 1.8f) * dp;
                rot[i] = rnd.nextFloat() * 360f;
                spin[i] = (rnd.nextFloat() - 0.5f) * 900f;
                color[i] = COLORS[rnd.nextInt(COLORS.length)];
            }
            last = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (count == 0) return;
            long now = SystemClock.uptimeMillis();
            // dt limitado: si la app se congela un momento, que no salten de golpe.
            float dt = Math.min(0.033f, (now - last) / 1000f);
            last = now;
            float g = 260 * dp;                                  // gravedad, dp/s²
            float drag = (float) Math.exp(-2.2f * dt);           // aire: pierde un % por segundo
            int alive = 0;
            for (int i = 0; i < count; i++) {
                life[i] += dt;
                if (life[i] >= ttl[i]) continue;
                vx[i] *= drag;
                vy[i] = vy[i] * drag + g * dt;
                x[i] += vx[i] * dt;
                y[i] += vy[i] * dt;
                rot[i] += spin[i] * dt;
                // Compacta: copia la viva a la posición «alive» para no dejar huecos.
                if (alive != i) {
                    x[alive] = x[i]; y[alive] = y[i]; vx[alive] = vx[i]; vy[alive] = vy[i];
                    life[alive] = life[i]; ttl[alive] = ttl[i]; rot[alive] = rot[i]; spin[alive] = spin[i];
                    size[alive] = size[i]; color[alive] = color[i]; confetti[alive] = confetti[i];
                }
                draw(c, alive);
                alive++;
            }
            count = alive;
            if (count > 0) postInvalidateOnAnimation();
        }

        private void draw(Canvas c, int i) {
            float t = life[i] / ttl[i];
            // Se apaga en el último 40 % de su vida, y al principio entra en un instante.
            float a = Math.min(1f, t * 12f) * (t < 0.6f ? 1f : 1f - (t - 0.6f) / 0.4f);
            p.setColor(color[i]);
            p.setAlpha(Math.round(255 * a));
            if (confetti[i]) {
                c.save();
                c.translate(x[i], y[i]);
                c.rotate(rot[i]);
                // El «volteo»: el ancho va de lleno a casi nada con un coseno del giro.
                float w = size[i] * Math.abs((float) Math.cos(Math.toRadians(rot[i] * 1.7f)));
                rect.set(-w, -size[i] * 0.55f, w, size[i] * 0.55f);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(rect, size[i] * 0.2f, size[i] * 0.2f, p);
                c.restore();
            } else {
                // Estela: un trazo hacia atrás, más largo cuanto más rápido va.
                float k = 0.045f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(size[i]);
                c.drawLine(x[i], y[i], x[i] - vx[i] * k, y[i] - vy[i] * k, p);
            }
        }
    }

    // ------------------------------------------------------------------ botón con progreso

    /**
     * Botón en cápsula que se convierte en barra de progreso. En reposo (si {@code shine}) le
     * cruza un brillo cada poco; descargando, se rellena de izquierda a derecha con el porcentaje.
     */
    static final class ProgressButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG), shineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final boolean shine;
        private final float dp;
        private String text = "";
        private float target = -1f, shown;               // -1: botón normal

        ProgressButton(Context c, int color, boolean shine) {
            super(c);
            this.shine = shine;
            dp = c.getResources().getDisplayMetrics().density;
            bg.setColor(color);
            fill.setColor(color);
            label.setColor(0xFFFFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(16 * dp);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setClickable(true);
        }

        void setLabel(String s) {
            text = s;
            target = -1f;
            setEnabled(true);
            invalidate();
        }

        void setProgress(float f) {
            if (target < 0) shown = 0f;
            target = f;
            setEnabled(false);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            String t = text;
            if (target >= 0f) {
                // Fondo apagado y relleno que persigue el valor real (avanza suave, sin saltos).
                bg.setAlpha(80);
                c.drawRoundRect(r, rad, rad, bg);
                shown += (target - shown) * 0.22f;
                if (Math.abs(target - shown) > 0.002f) postInvalidateOnAnimation();
                r.set(0, 0, Math.max(h, w * shown), h);
                c.drawRoundRect(r, rad, rad, fill);
                t = I18n.t("Descargando… ", "Downloading… ") + Math.round(target * 100) + " %";
            } else {
                bg.setAlpha(255);
                c.drawRoundRect(r, rad, rad, bg);
                if (shine && ValueAnimator.areAnimatorsEnabled()) {
                    // Un brillo cruza el botón y descansa: 1,1 s de pasada cada 3 s.
                    float p = (SystemClock.uptimeMillis() % 3000L) / 1100f;
                    if (p < 1f) {
                        float x = -w * 0.4f + p * w * 1.8f;
                        shineP.setShader(new LinearGradient(x - w * 0.25f, 0, x + w * 0.25f, h,
                                new int[]{0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                        c.drawRoundRect(r, rad, rad, shineP);
                    }
                    postInvalidateOnAnimation();
                }
            }
            Paint.FontMetrics fm = label.getFontMetrics();
            c.drawText(t, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, label);
        }
    }

    // ------------------------------------------------------------------ brillo para el aviso

    /** Primer plano de un aviso: una franja de luz lo cruza de vez en cuando. */
    static final class Shimmer extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        Shimmer(float radius) {
            this.radius = radius;
        }

        @Override
        public void draw(Canvas c) {
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            Rect b = getBounds();
            float w = b.width();
            float t = (SystemClock.uptimeMillis() % 3600L) / 1400f;
            if (t < 1f) {
                float x = b.left - w * 0.3f + t * w * 1.6f;
                p.setShader(new LinearGradient(x - w * 0.2f, b.top, x + w * 0.2f, b.bottom,
                        new int[]{0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(b), radius, radius, p);
            }
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
