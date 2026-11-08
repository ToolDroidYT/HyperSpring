package com.icehome.up;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * Ventana invisible a pantalla completa mientras una app vuelve a su icono, para poder usar el
 * escritorio sin esperar a que acabe (como en Samsung): tocar otro icono, un widget, deslizar…
 *
 * Mientras dura la transición de cierre el sistema sigue mandando los toques a la app que se va
 * (invisible), no al launcher. Esta ventana es del mismo tipo que la barra de gestos del launcher
 * (panel de la barra de navegación), por encima de las apps, así que los toques llegan siempre:
 * <ul>
 * <li>en el icono de la app que se cierra, un toque la reabre desde donde está ({@link Listener});</li>
 * <li>en cualquier otro sitio, el gesto entero se pasa tal cual a la ventana del launcher.</li>
 * </ul>
 * Deja libres la barra de estado y la zona del gesto de abajo, para no tapar los gestos del sistema.
 * Si el cierre acaba con el dedo abajo, la ventana espera a que se levante (el gesto no se corta).
 *
 * {@link #want} se llama desde el hilo de animación; la ventana se pone y se quita en el principal.
 */
final class TapCatcher {

    /** Tipo de ventana: TYPE_NAVIGATION_BAR_PANEL, el que usa la barra de gestos de MIUI. */
    private static final int TYPE = 2024;
    /** Margen alrededor del icono, respecto a su ancho: el dedo no es preciso. */
    private static final float MARGIN = 0.25f;
    private static final long TAP_MS = 500;
    /**
     * Seguridad: si la animación deja de pedir la ventana (no debería), se quita sola. Una ventana
     * a pantalla completa olvidada dejaría la pantalla sin responder.
     */
    private static final long STALE_MS = 2500, CHECK_MS = 1000;
    private static volatile long lastWantMs;

    interface Listener {
        /** Toque en el icono de la app que se cierra. */
        void onTap();

        /** Un evento fuera del icono, en coordenadas de pantalla, para el launcher. */
        void forward(MotionEvent screenEvent);
    }

    static volatile Listener listener;
    /** Contexto para el gestor de ventanas (el de la aplicación del launcher). */
    static volatile Context context;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Rect wanted = new Rect(), shown = new Rect();
    /** Zona del icono (pantalla) en la ventana actual; solo hilo principal. */
    private static final Rect iconZone = new Rect();
    private static boolean pending, removeWhenUp;
    private static Catcher view;
    private static WindowManager wm;

    /** Hay un dedo en el escritorio a través de esta ventana. */
    static boolean fingerDown() {
        Catcher c = view;
        return c != null && c.tracking;
    }

    /** Rectángulo del icono en pantalla, o null para quitar la ventana. */
    static void want(RectF icon) {
        if (icon != null) lastWantMs = SystemClock.uptimeMillis();
        synchronized (wanted) {
            if (icon == null || icon.isEmpty()) {
                if (wanted.isEmpty()) return;
                wanted.setEmpty();
            } else {
                int m = Math.round(icon.width() * MARGIN);
                int l = Math.round(icon.left) - m, t = Math.round(icon.top) - m;
                int r = Math.round(icon.right) + m, b = Math.round(icon.bottom) + m;
                if (wanted.left == l && wanted.top == t && wanted.right == r && wanted.bottom == b) return;
                wanted.set(l, t, r, b);
            }
            if (pending) return;
            pending = true;
        }
        MAIN.post(APPLY);
    }

    private static final Runnable APPLY = new Runnable() {
        @Override
        public void run() {
            Rect r = new Rect();
            synchronized (wanted) {
                pending = false;
                r.set(wanted);
            }
            try {
                apply(r);
            } catch (Throwable t) {
                Log.e("toque: ventana sobre el icono", t);
                remove();
            }
        }
    };

    private static void apply(Rect r) {
        if (r.isEmpty()) {
            if (view != null && view.tracking) {
                removeWhenUp = true;                       // el dedo sigue abajo: al levantarlo
                return;
            }
            remove();
            return;
        }
        removeWhenUp = false;
        iconZone.set(r);
        if (view != null) return;                          // la ventana ya cubre la pantalla
        Context c = context;
        if (c == null) return;
        if (wm == null) wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Rect area = screenArea(c);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(area.width(), area.height(), TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = area.left;
        lp.y = area.top;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle("IceHome tap");
        view = new Catcher(c, area.left, area.top);
        wm.addView(view, lp);
        shown.set(area);
        MAIN.removeCallbacks(STALE);
        MAIN.postDelayed(STALE, CHECK_MS);
    }

    /** Pantalla sin la barra de estado ni la zona del gesto de abajo. */
    private static Rect screenArea(Context c) {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        android.content.res.Resources res = c.getResources();
        int status = dimen(res, "status_bar_height", 0);
        int nav = dimen(res, "navigation_bar_height", Math.round(48 * res.getDisplayMetrics().density));
        return new Rect(b.left, b.top + status, b.right, b.bottom - Math.round(nav * 1.5f));
    }

    private static int dimen(android.content.res.Resources res, String name, int fallback) {
        int id = android.content.res.Resources.getSystem().getIdentifier(name, "dimen", "android");
        return id == 0 ? fallback : android.content.res.Resources.getSystem().getDimensionPixelSize(id);
    }

    private static final Runnable STALE = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            long now = SystemClock.uptimeMillis();
            boolean stuckFinger = view.tracking && now - view.down > 10_000;   // un "levantar" perdido
            if (now - lastWantMs > STALE_MS && (!view.tracking || stuckFinger)) {
                Log.w("toque: la ventana del escritorio se quedó sin animación; se quita");
                synchronized (wanted) {
                    wanted.setEmpty();
                }
                remove();
                return;
            }
            MAIN.postDelayed(this, CHECK_MS);
        }
    };

    private static final Runnable REMOVE_IF_DONE = new Runnable() {
        @Override
        public void run() {
            if (removeWhenUp && view != null && !view.tracking) remove();
        }
    };

    private static void remove() {
        View v = view;
        view = null;
        removeWhenUp = false;
        shown.setEmpty();
        if (v != null && wm != null) {
            try {
                wm.removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * En el icono de la app que se cierra: un toque corto y quieto la reabre. Fuera de él: el
     * gesto se pasa al launcher, en coordenadas de pantalla.
     */
    private static final class Catcher extends View {
        private final float slop;
        private final int ox, oy;
        private float x, y;
        private long down;
        volatile boolean tracking;
        private boolean onIcon;

        Catcher(Context c, int ox, int oy) {
            super(c);
            this.ox = ox;
            this.oy = oy;
            slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2f;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                x = e.getX() + ox;
                y = e.getY() + oy;
                down = SystemClock.uptimeMillis();
                tracking = true;
                onIcon = iconZone.contains(Math.round(x), Math.round(y));
            }
            Listener l = listener;
            if (onIcon) {
                if (action == MotionEvent.ACTION_UP && l != null
                        && Math.hypot(e.getX() + ox - x, e.getY() + oy - y) <= slop
                        && SystemClock.uptimeMillis() - down <= TAP_MS) {
                    l.onTap();
                }
            } else if (l != null) {
                MotionEvent s = MotionEvent.obtain(e);
                s.offsetLocation(ox, oy);                  // a coordenadas de pantalla
                try {
                    l.forward(s);
                } finally {
                    s.recycle();
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                tracking = false;
                if (removeWhenUp) MAIN.post(REMOVE_IF_DONE);   // no quitar la ventana en mitad de su toque
            }
            return true;
        }
    }

    private TapCatcher() {
    }
}
