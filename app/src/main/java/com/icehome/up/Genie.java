package com.icehome.up;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Efecto genio al abrir y cerrar apps; la animación de MIUI no llega a verse.
 *
 * El sistema solo deja mover, escalar y recortar la ventana de la app, no deformarla. Así que
 * durante todo el efecto se dibuja una capa propia (hija de la ventana del launcher) con el
 * contenido deformado por {@link GlRenderer}, y la ventana real se oculta.
 *
 * <ul>
 * <li><b>Abrir</b>: desde el primer fotograma. Hasta que llega la primera captura de la ventana (o
 *     si aún no existe, en el arranque rápido) el contenido es el color de la app con su icono.</li>
 * <li><b>Con ventana</b>: el contenido se captura en un hilo aparte, sin frenar el dibujo; cada
 *     fotograma usa la última captura. La capa se pone encima de la ventana y el cambio se hace en
 *     la transacción de MIUI que mueve la ventana ({@link #onApply}).</li>
 * <li><b>Arrastrar para cerrar</b>: el extremo cercano es el dedo; al soltar, se desliza al icono.</li>
 * </ul>
 *
 * La capa, su contexto de dibujo y el shader se crean una vez y se calientan al volver al
 * escritorio ({@link #prewarm}): en un móvil modesto crearlos cuesta más que la animación entera.
 *
 * Hilos: animación de MIUI → {@link #frame}; "IceHome-genie" → dibujo; "IceHome-capture" →
 * capturas; aplicador de MIUI → {@link #onApply}.
 */
final class Genie {

    /** Capa relativa a la ventana: por encima de ella (0) y por debajo del icono flotante (3). */
    private static final int Z = 2;
    /** Sin ventana aún: por encima de todo lo que dibuja el launcher. */
    private static final int Z_TOP = 1_000_000;
    /** Forma del genio por unidad de intensidad (ajustada contra el vídeo de referencia). */
    private static final float LEAD = 0.35f, LAG = 0.6f;
    /** Al cerrar, el extremo lejano se adelanta menos en vertical: la copa encoge proporcionada. */
    private static final float CLOSE_RISE = 0.35f;
    /** Al cerrar, tramo de anchura (0 = icono) en el que la copa se funde con el icono de MIUI. */
    private static final float ICON_FADE_START = 0.22f, ICON_FADE_END = 0.06f;
    /** Al arrastrar, el genio entra progresivamente para no saltar desde la ventana normal. */
    private static final float RAMP_IN_S = 0.08f;
    /** Al abrir, fundido de entrada del genio. */
    private static final float OPEN_FADE_S = 0.06f;
    /** Si el genio aparece con la ventana ya más grande que esto, no se cruza con el icono de MIUI. */
    private static final float EARLY_PW = 0.35f;
    /**
     * Tramo de anchura (0 = icono, 1 = pantalla) en el que el genio pasa de icono a app, o al
     * revés. Medido en la animación de serie de MIUI (HyperOS 3, a cámara lenta):
     * <ul>
     * <li>Al abrir, el icono desaparece enseguida: con la ventana a ~1,3 veces el icono ya solo
     *     se ve el color de la app.</li>
     * <li>Al cerrar, el icono toma el relevo pronto, con la ventana aún a ~2 veces el icono, y lo
     *     que queda del viaje es el icono encogiendo. Con el relevo al final (como antes, de 0,2 a
     *     0,03) la app oscura se mezclaba con el icono y se veía un cuadrado oscuro.</li>
     * </ul>
     */
    private static final float OPEN_MIX_START = 0.12f, OPEN_MIX_END = 0.02f;
    private static final float CLOSE_MIX_START = 0.3f, CLOSE_MIX_END = 0.13f;
    /** Lado del icono como textura (potencia de 2, con mipmaps). */
    private static final int ICON_TEXTURE = 128;
    /** Al interrumpir, la forma de abrir y la de cerrar se funden en este tiempo. */
    private static final float DIR_BLEND_S = 0.15f;
    /** Al soltar el dedo, el extremo cercano pasa del dedo al icono en este tiempo. */
    private static final float ICON_BLEND_S = 0.12f;
    /** Ancho del "icono" virtual bajo el dedo, respecto a la pantalla. */
    private static final float FINGER_ANCHOR = 0.2f;
    /** Tras el fin de una animación, margen por si MIUI encadena otra con el mismo animador. */
    private static final long GRACE_MS = 40;
    /** Un animador solo releva al del genio si este lleva este tiempo sin fotogramas. */
    private static final long HANDOVER_MS = 30;
    /** Sin fotogramas durante este tiempo, se da la animación por terminada. */
    private static final long WATCHDOG_MS = 300;
    /** Lo mismo con el dedo en la pantalla (puede estar quieto). */
    private static final long HOLD_MS = 5000;
    /** Tras tantos fallos seguidos al preparar o dibujar, el genio se aparta un rato. */
    private static final int MAX_FAILURES = 3;
    private static final long PAUSE_MS = 5000;
    /** Una ventana sustituida por otra animación no vuelve a llevar genio en este tiempo. */
    private static final long RETIRED_MS = 1000;
    /**
     * Físicas de lámpara: el extremo lejano cuelga del cercano con un muelle (rigidez en 1/s² y
     * amortiguación relativa; < 1 = se balancea un poco antes de quedarse quieto).
     */
    private static final float LAMP_K = 170f, LAMP_ZETA = 0.5f;
    /** Inclinación y estiramiento máximos de la lámpara, respecto al tamaño de la pantalla. */
    private static final float LAMP_MAX_BEND = 0.22f, LAMP_MAX_LIFT = 0.12f;
    /** Al soltar, las físicas se apagan en este tiempo mientras la copa vuela al icono. */
    private static final float LAMP_FADE_S = 0.35f;
    /**
     * Dos iconos cuyos centros están a menos de esta fracción del ancho del icono son el mismo
     * (reabrir la misma app): MIUI da rectángulos algo distintos al cerrar y al abrir.
     */
    private static final float SAME_ICON = 0.5f;
    /** Icono de MIUI y genio a la vez durante este tiempo al empezar (un refresco y algo). */
    private static final long OVERLAP_NS = 20_000_000L;
    /** Al cerrar, con la ventana por encima de esta anchura (progreso) el icono flotante no se ve. */
    private static final float CLOSE_ICON_HIDE_PW = 0.3f;
    /**
     * Altura del icono (fracción de la pantalla) entre la que la copa pasa de estrecharse hacia
     * abajo a estrecharse hacia arriba; en medio, mezcla (sin saltos si el icono cambia).
     */
    private static final float FLIP_LO = 0.40f, FLIP_HI = 0.60f;
    /** Constante de tiempo del cambio de sentido de la copa (al soltar hacia un icono de arriba). */
    private static final float FLIP_S = 0.12f;
    /** Fundido de la pantalla provisional a la primera captura de la app. */
    private static final float CONTENT_FADE_S = 0.14f;
    /** Del color de fondo provisional al de la app, cuando MIUI lo da. */
    private static final float BG_BLEND_S = 0.1f;
    /** Por debajo de esta anchura (progreso) el genio ya ha llegado al icono: vibración. */
    private static final float LAND_PW = 0.03f;
    /** Un fotograma que tarda más que esto respecto al anterior cuenta como tirón en el registro. */
    private static final long JANK_NS = 25_000_000L;
    /** Separación mínima entre capturas: cada una carga al compositor del sistema. */
    private static final long CAPTURE_GAP_MS = 30;
    /**
     * Además, entre captura y captura el compositor queda libre al menos este múltiplo de lo que
     * tardó la última: en un móvil modesto una captura cuesta 25-80 ms y, seguidas, dan tirones.
     */
    private static final float CAPTURE_IDLE = 1.5f;
    /** Primera captura al cerrar, más pequeña para que llegue antes (luego vienen las buenas). */
    private static final float FIRST_CLOSE_SCALE = 0.35f;
    /** Tiempo tras el primer dibujo para que el compositor ya lo tenga (un refresco). */
    private static final long READY_NS = 10_000_000L;
    /**
     * Presupuesto de capturas (cada una ocupa al compositor 25-80 ms en un móvil modesto y es lo
     * que daba tirones): al abrir "en vivo", como mucho una cada {@link #OPEN_LIVE_MS}; al cerrar
     * el contenido ya no cambia y basta con una (ver {@link Session#captureDue}).
     * Sin "en vivo", al abrir solo las primeras (y otra si la ventana cambia de superficie).
     */
    private static final long OPEN_LIVE_MS = 200;
    /** Sin "en vivo", al abrir: la primera captura y otra a esta distancia (la app ya se ha pintado). */
    private static final long OPEN_SECOND_MS = 300;
    /**
     * Resolución máxima de las capturas: el coste para el compositor crece con los píxeles y el
     * contenido va en movimiento; los bordes del genio salen a la resolución del ajuste.
     */
    private static final float CAPTURE_MAX_SCALE = 0.5f;
    /**
     * Genio suelto (la app que se cierra cuando se abre otra): MIUI abandona su animación y el
     * genio sigue solo hasta el icono con un muelle propio, del estilo del de cerrar de MIUI.
     */
    private static final float GHOST_K = 300f, GHOST_ZETA = 0.92f;
    private static final long GHOST_MAX_NS = 1_500_000_000L;
    /** Al acabar, el icono real aparece y la capa se va un poco después (mejor solapar que un hueco). */
    private static final long GHOST_HANDOFF_MS = 40;
    /** Un genio que dibujó hace menos de esto ya pinta el suelto en su fotograma. */
    private static final long SHARED_FRAME_NS = 20_000_000L;

    static volatile boolean enabled;
    static volatile float strength = 2.2f;
    /**
     * Forma de copa (como Home Up de Samsung, medida fotograma a fotograma en su vídeo): el extremo
     * lejano (arriba) va muy por delante, llega arriba y casi a todo lo ancho en ~1/3 del
     * recorrido, mientras el cercano al icono (abajo) se queda atrás, estrecho, y llega el último.
     * Los lados son rectas entre los dos extremos (un trapecio con hombros suaves), sin la cola
     * curva del genio clásico. Al cerrar es lo mismo al revés: abajo se mete en el icono antes.
     */
    static volatile int style = 0;   // STYLE_CLASSIC (por defecto)
    /** Estilos: genio clásico con cola, copa como Samsung, copa «Ice» (la nuestra) y Profundidad (sin genio). */
    static final int STYLE_CLASSIC = 0, STYLE_SAMSUNG = 1, STYLE_ICE = 2, STYLE_DEPTH = 3;
    /**
     * Profundidad: sin genio. La ventana sigue el rectángulo de MIUI (su muelle de siempre), pero
     * se comporta como una lámina con peso, vista en perspectiva:
     * <ul>
     * <li>Sigue la animación normal de HyperOS (el rectángulo de MIUI), sin cambiar su recorrido.</li>
     * <li><b>Hoja que rota:</b> rígida, sin deformarse, siempre con arriba hacia el fondo
     * y abajo hacia ti (hasta {@link #DEPTH_TILT}). Al salir de la app se dobla enseguida y
     * más cuanto más fuerte es el gesto; nunca se sale de la pantalla. La lleva un muelle con peso, así que se
     * inclina con retraso y se endereza al llegar. A pantalla completa es un rectángulo normal.</li>
     * <li><b>Desenfoque de movimiento:</b> como una cámara con el obturador abierto
     * {@link #DEPTH_SHUTTER_S}: cada punto se difumina en la dirección en la que va. Al crecer
     * desde el icono los bordes se estiran hacia fuera, como un zoom.</li>
     * <li>El escritorio se oscurece un poco detrás, para dar fondo.</li>
     * </ul>
     * «Intensidad» escala las dos cosas (2,2 = lo de aquí).
     */
    /**
     * Inclinación máxima de la hoja (arriba al fondo): arriba se ve hasta un 20 % más pequeña y
     * abajo un 33 % más grande (1/(1 ± 0,25)), si cabe en la pantalla. Con Intensidad 2,2.
     * Con 0,3 apenas se notaba al salir: el ojo necesita que arriba mida ~⅔ de abajo.
     */
    private static final float DEPTH_TILT = 0.5f;
    /** Al abrir, la hoja sale ya inclinada esto (muy sutil, un 5 %). */
    private static final float DEPTH_TILT_START = 0.05f;
    /**
     * Muelle de la inclinación: periodo (s) y amortiguamiento. Algo de rebote (0,55): la hoja
     * tiene peso, se inclina con retraso al empezar a moverse y se endereza pasándose un poco.
     */
    private static final float TILT_RESPONSE = 0.14f, TILT_DAMPING = 0.6f; // el cierre dura ~0,25 s: con 0,2 no llegaba a doblarse
    /** Velocidad (en alturas de pantalla por segundo) a la que la curvatura llega a su máximo. */
    private static final float DEPTH_SPEED = 2.2f;
    /** Obturador del desenfoque, en segundos (~3/4 de un fotograma a 60 Hz). */
    private static final float DEPTH_SHUTTER_S = 0.0125f;
    /** Estela máxima, en fracción de la altura de pantalla: con tirones no se emborrona de más. */
    private static final float DEPTH_MAX_BLUR = 0.09f;
    /** Profundidad: oscurecido del escritorio, muy suave (el desenfoque del fondo ya lo pone HyperOS). */
    private static final float DEPTH_DIM = 0.06f;
    /**
     * Ice: copa algo más suave que la de Samsung (arriba se adelanta un poco menos, abajo se
     * estrecha menos y el contenido se lee siempre), el escritorio se oscurece detrás
     * ({@link #ICE_DIM}) y al cerrar el icono recibe la copa con un pequeño aplastamiento.
     */
    private static final float ICE_LEAD = 0.8f, ICE_LAG = 0.75f;
    /** Ice: oscurecido del escritorio con la app abierta del todo (va con la apertura). */
    static final float ICE_DIM = 0.14f;
    /**
     * Sin "en vivo", al abrir: capturas a esta distancia hasta tener {@link #OPEN_EARLY_SHOTS}
     * (la primera suele llegar antes de que la app pinte nada: sin esto se veía blanca ~300 ms).
     */
    private static final long OPEN_EARLY_MS = 100;
    private static final int OPEN_EARLY_SHOTS = 3;
    /**
     * Copa con intensidad 2,2 (lo medido en Samsung): extremo lejano 1-(1-q)^3, cercano q^2,1. En
     * vertical el cercano casi no se retrasa (sigue a MIUI): solo se queda estrecho. Ninguna de
     * las dos curvas es vertical en q = 0 (una potencia q^0,3 sí lo era: al empezar a abrir la copa
     * saltaba de golpe a media pantalla y al acabar de cerrar el icono se quedaba en trapecio), y
     * además la deformación se apaga cerca del icono ({@link #CUP_FADE}).
     */
    private static final float CUP_LEAD = 0.9f, CUP_LAG = 0.5f;
    /** Por debajo de esta apertura la copa se va convirtiendo en el rectángulo de MIUI (el icono). */
    private static final float CUP_FADE = 0.12f;
    static volatile boolean live = true;
    /**
     * Fotogramas por segundo máximos del genio. Se dibuja al ritmo de los refrescos de la
     * pantalla, como mucho a este: con pantalla a 90-120 Hz y una GPU modesta, dibujar a cada
     * fotograma de MIUI es más trabajo sin más fluidez.
     */
    static volatile float maxFps = 60f;
    /** Margen al comparar con el periodo (los refrescos no llegan exactos). */
    private static final long PACE_SLACK_NS = 2_500_000L;
    /**
     * Predicción entre muestras de MIUI: como mucho este número de refrescos por delante de la
     * última (cubre los huecos medidos, de 1-3 refrescos, sin inventar si MIUI se para de verdad).
     */
    private static final float MAX_AHEAD_PERIODS = 2f;
    /** Sin muestras de MIUI durante estos refrescos, el bucle de dibujo se para hasta la siguiente. */
    private static final int KEEP_TICKING_PERIODS = 4;

    private static long periodNs() {
        return (long) (1e9 / Math.max(15f, maxFps)) - PACE_SLACK_NS;
    }

    /** Intensidad de las físicas de lámpara (0 = sin balanceo). */
    static volatile float physics = 1f;
    /** Superficie del inicio que MIUI escala ({@code HomeLayerElement}): antecesora de la capa. */
    static volatile SurfaceControl homeLeash;

    private static Handler renderThread, captureThread;
    private static Object retiredKey;
    private static int failures;
    private static long pausedUntilMs;
    private static int retiredTask = -1;
    private static long retiredMs;
    private static volatile Session session, ending;
    /** Genio suelto (solo hilo de dibujo, salvo lecturas). */
    private static volatile Ghost ghost;
    /** Ventana que se abre sin genio: el genio suelto va justo por debajo de ella. */
    private static volatile SurfaceControl ghostBelow;

    /** Mostrar u ocultar el icono real del escritorio (lo pone WindowAnim; hilo principal). */
    interface Icons {
        void setVisible(View icon, boolean visible);
    }

    static volatile Icons icons;

    // ---------------------------------------------------------------- precarga
    /** Una captura o un icono preparados de antemano caducan a este tiempo. */
    private static final long PRELOAD_MS = 3000;
    /** Captura de la app tomada al empezar el gesto de cerrar (antes del primer fotograma). */
    private static final Object PRE = new Object();
    private static Hidden.Shot preShot;
    private static int preTask = -1;
    private static long preShotMs;
    /** Icono subido a la GPU al tocarlo (antes de soltar el dedo y de que empiece la apertura). */
    private static final Object PREPARED_ICON = new Object();
    private static final RectF preparedRect = new RectF();
    private static volatile long preparedMs;
    /**
     * Caché de la última imagen de cada app (la captura al empezar el gesto de cerrarla). Al
     * volver a abrirla, si su tarea sigue en recientes, Android enseña al abrir esa misma imagen
     * (una "instantánea" de la tarea): el genio sale ya con ella en vez de con el color y el
     * icono. Pocas entradas: cada una ocupa ~1 MB de memoria de la GPU.
     */
    private static final int SNAPSHOTS = 4;
    private static final java.util.LinkedHashMap<String, Snap> snaps = new java.util.LinkedHashMap<>(8, 0.75f, true);
    /** Contenido precargado al tocar un icono (instantánea de su app). */
    private static final Object PREPARED_CONTENT = new Object();
    private static volatile String preparedPkg;

    private static final class Snap {
        final int task;
        final Hidden.Shot shot;

        Snap(int task, Hidden.Shot shot) {
            this.task = task;
            this.shot = shot;
        }
    }

    /** Hilo de capturas: guarda la imagen de una app (y suelta la más antigua si sobran). */
    private static void remember(String pkg, int task, Hidden.Shot shot) {
        synchronized (snaps) {
            Snap old = snaps.put(pkg, new Snap(task, shot));
            if (old != null && old.shot.buffer != shot.buffer) old.shot.buffer.close();
            java.util.Iterator<java.util.Map.Entry<String, Snap>> it = snaps.entrySet().iterator();
            while (snaps.size() > SNAPSHOTS && it.hasNext()) {
                it.next().getValue().shot.buffer.close();
                it.remove();
            }
        }
    }

    /** Una captura ya usada: se suelta, salvo que la guarde la caché. */
    private static void release(Hidden.Shot shot) {
        synchronized (snaps) {
            for (Snap s : snaps.values()) {
                if (s.shot.buffer == shot.buffer) return;
            }
        }
        shot.buffer.close();
    }

    /** La tarea sigue en recientes (al abrirla, Android enseñará su instantánea). */
    private static boolean taskAlive(int taskId) {
        android.content.Context c = TapCatcher.context;
        if (c == null || taskId == -1) return false;
        try {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            @SuppressWarnings("deprecation")
            java.util.List<android.app.ActivityManager.RecentTaskInfo> tasks =
                    am.getRecentTasks(16, android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (android.app.ActivityManager.RecentTaskInfo t : tasks) {
                if (t.taskId == taskId) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Superficie del launcher y tamaño de pantalla de la última preparación (para precargar). */
    private static volatile SurfaceControl lastRoot;
    private static volatile int lastW, lastH;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Un fotograma, rellenado por {@link WindowAnim} en el hilo de animación (se reutiliza). */
    static final class Frame {
        Object key;
        boolean open;
        /** Ventana de la app; null en el arranque rápido, antes de que exista. */
        SurfaceControl leash;
        /** Tarea de la ventana (-1 si aún no hay). */
        int taskId = -1;
        int originX, originY;
        final RectF cur = new RectF(), icon = new RectF(), full = new RectF();
        /** false al arrastrar: el extremo cercano sigue al dedo. */
        boolean hasIcon;
        float radius;
        // Solo al empezar una sesión:
        SurfaceControl root;
        SurfaceControl[] exclude;
        int bg;
        Drawable iconDrawable;
        /** Icono del escritorio al que vuelve la app (al cerrar), si MIUI lo sabe. */
        View targetView;
    }

    // ------------------------------------------------------------------ entrada desde los hooks

    /** Hay un genio en marcha (de cualquier animador). */
    static boolean active() {
        Session s = session;
        return s != null && !s.finishing;
    }

    /** Hay genio en marcha para este animador (en cualquier sentido). */
    static boolean running(Object key) {
        Session s = session;
        return s != null && s.key == key;
    }

    static synchronized void frame(Frame f) {
        long nowMs = SystemClock.uptimeMillis();
        // Una ventana cuya animación ya sustituyó otra más reciente se deja a MIUI un momento.
        if (nowMs < pausedUntilMs) return;                 // fallos seguidos: pausa (ver fail)
        if (nowMs - retiredMs < RETIRED_MS && (f.key == retiredKey || (f.taskId != -1 && f.taskId == retiredTask))) {
            return;
        }
        Session s = session;
        if (s != null && s.finishing) {
            stop("reinicio tras un fallo");                // no seguir alimentando una sesión muerta
            s = null;
        }
        if (s != null && s.taskId != -1 && f.taskId != -1 && s.taskId != f.taskId) {
            // Animaciones en paralelo de dos ventanas: gana la más reciente. Al animador antiguo
            // solo se le aparta si no es el mismo que anima la ventana nueva.
            retiredKey = s.key != f.key ? s.key : null;
            retiredTask = s.taskId;
            retiredMs = nowMs;
            stop("otra ventana");
            s = null;
        }
        if (s != null && s.key != f.key && f.open && !s.open && s.sameApp(f)) {
            // Se toca el icono mientras la app se cierra: la nueva apertura manda ya, sin esperar
            // a que acabe el cierre. El genio da la vuelta desde donde esté.
            retiredKey = s.key;
            retiredTask = -1;
            retiredMs = nowMs;
            s.keyEnded = false;
            s.handedOver = true;
            s.key = f.key;
            Log.i("genio: se reabre la app durante el cierre");
        } else if (s != null && s.key != f.key && f.open && !s.open) {
            // Otra app mientras se cierra la anterior: la nueva empieza su propio genio.
            Log.i("genio: otra app (tarea " + f.taskId + " frente a " + s.taskId + ", icono "
                    + (f.hasIcon ? f.icon.toShortString() : "—") + " frente a " + s.goalString() + ")");
            retiredKey = s.key;
            retiredTask = s.taskId;
            retiredMs = nowMs;
            // MIUI abandona el cierre: si el genio ya tiene contenido, sigue solo hasta el icono
            // mientras la otra se abre (las dos animaciones a la vez).
            boolean alone = s.canGoAlone();
            s.ghosted = alone;
            ghostBelow = null;                             // la nueva lleva genio: la capa es de las dos
            stop(alone ? "sigue sola mientras se abre otra app" : "se abre otra app");
            s = null;
        }
        if (s != null && s.key != f.key) {
            // Otro animador: en el arranque rápido MIUI pasa la animación de uno a otro cuando
            // llega la ventana, y al interrumpir puede seguir otro. El genio sigue igual, sin
            // cortes. Si el anterior sigue mandando fotogramas, manda él.
            if (!s.keyEnded && SystemClock.uptimeMillis() - s.lastFrameMs < HANDOVER_MS) return;
            s.keyEnded = false;
            if (!s.handedOver) Log.i("genio: relevo de animador");
            s.handedOver = true;
            s.key = f.key;
        }
        if (s == null) {
            if (f.root == null || !Hidden.ok()) return;
            s = new Session(f);
            session = s;
        }
        s.post(f);
    }

    /**
     * Se abre otra app con una animación que no lleva genio (desde un widget, p. ej.) mientras
     * una se cierra: la que se cierra sigue sola hasta su icono, por debajo de la ventana nueva.
     */
    static synchronized void otherAppOpening(Object key, SurfaceControl newWindow) {
        Session s = session;
        if (s == null || s.key == key || s.open) return;
        retiredKey = s.key;
        retiredTask = s.taskId;
        retiredMs = SystemClock.uptimeMillis();
        boolean alone = s.canGoAlone();
        s.ghosted = alone;
        ghostBelow = newWindow;
        stop(alone ? "sigue sola mientras se abre otra app" : "se abre otra app");
    }

    /** MIUI terminó o canceló una animación: si no encadena otra enseguida, se termina. */
    static void animEnded(Object key) {
        final Session s = session;
        if (s == null || s.key != key) return;
        s.keyEnded = true;                                 // otro animador puede relevarlo ya
        final long at = SystemClock.uptimeMillis();
        renderHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (session == s && s.lastFrameMs <= at) stop("fin");
            }
        }, GRACE_MS);
    }

    /** La animación de este animador ya no lleva genio (otro tipo, gesto rápido…). */
    static void stopFor(Object key, String why) {
        Session s = session;
        if (s != null && s.key == key) stop(why);
    }

    static synchronized void stop(String why) {
        Session s = session;
        if (s == null) return;
        session = null;
        s.finishing = true;
        s.why = why;
        TapCatcher.want(null);
        ending = s;
        renderHandler().post(s.endTask);
    }

    /** Un fallo al preparar o dibujar; varios seguidos apartan el genio un rato. */
    private static synchronized void fail() {
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            pausedUntilMs = SystemClock.uptimeMillis() + PAUSE_MS;
            Log.w("genio: varios fallos seguidos, en pausa " + PAUSE_MS / 1000 + " s");
        }
    }

    private static synchronized void succeeded() {
        failures = 0;
    }

    /** Sin genio en marcha ni terminando: los hooks de cada transacción salen sin hacer nada. */
    static boolean idle() {
        return session == null && ending == null && ghost == null;
    }

    /** El genio suelto tiene este icono del escritorio: MIUI no debe volver a mostrarlo aún. */
    static boolean ghostHolds(Object view) {
        Ghost g = ghost;
        return g != null && g.iconView != null && g.iconView.get() == view;
    }

    /**
     * Prepara capa, contexto de dibujo y shader con un dibujo invisible, para que la primera
     * animación no pague su creación. Se llama al volver al escritorio; si ya están, no hace nada.
     */
    static void prewarm(final SurfaceControl root, final int w, final int h) {
        if (root == null || w <= 0 || h <= 0) return;
        lastRoot = root;
        lastW = w;
        lastH = h;
        renderHandler().post(new Runnable() {
            @Override
            public void run() {
                if (session != null || ending != null || !Hidden.ok()) return;
                GlRenderer.warm(root, w, h);
            }
        });
    }

    /**
     * Transparencia para el icono flotante de MIUI (que dibuja, en el rectángulo de su animación,
     * el icono, un velo del color de la app y a veces una captura). Con genio no se ve: al abrir,
     * solo hasta que el genio sale del icono (fundido corto); al cerrar, solo al final, cuando la
     * copa ya casi tiene el tamaño del icono.
     */
    static float floatingIconAlpha() {
        Session s = session;
        if (s == null) return 1f;
        // Al cerrar, MIUI dibuja su icono flotante (con un velo del color del icono) a la medida
        // de su rectángulo y por debajo de la ventana: de serie, la ventana lo tapa entero. Con
        // genio la ventana se oculta y la copa es más estrecha abajo, y el velo asomaba alrededor
        // como un marco claro. Aquí sobra (el genio ya dibuja el icono al final): fuera en cuanto
        // se ve el genio, o mientras la ventana aún es grande y lo tapa. Si el genio no llega a
        // verse, MIUI lo enseña como siempre.
        if (!s.open && (s.shown || s.pwNow > CLOSE_ICON_HIDE_PW)) return 0f;
        // El genio ya es el icono. El de MIUI (encima del genio) se quita un refresco después de
        // que se vea el genio: van en transacciones distintas y nunca debe haber un fotograma vacío.
        if (s.iconReady) return s.shown && SystemClock.elapsedRealtimeNanos() - s.shownNs > OVERLAP_NS ? 0f : 1f;
        if (s.open && s.shown && !s.shownEarly) return 0f;
        return 1f - s.layerAlpha();
    }

    /**
     * El velo de MIUI (rectángulo del color de la app que crece con su animación) nunca se ve con
     * genio: el genio ya pone ese color debajo de su contenido, con su forma.
     */
    static boolean hideVeil() {
        return session != null;
    }

    /**
     * Un toque en (x, y) de pantalla cae en el icono al que vuelve la app que se está cerrando
     * (con un margen: el dedo no es preciso).
     */
    static boolean tapOnClosingIcon(float x, float y) {
        Session s = session;
        if (s == null || s.open || s.following) return false;
        synchronized (s.lock) {
            RectF r = s.tapRect;
            if (r.isEmpty()) return false;
            float m = r.width() * 0.25f;
            return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
        }
    }

    /**
     * El gesto de cerrar empieza (el sistema le da la ventana al launcher): se captura ya, antes
     * del primer fotograma, para que el genio no tenga que esperar a una captura al empezar.
     */
    static void precapture(final SurfaceControl leash, final int taskId, final int x, final int y, final String pkg) {
        if (!enabled || leash == null || !leash.isValid() || lastW <= 0 || taskId == -1) return;
        captureHandler().post(new Runnable() {
            @Override
            public void run() {
                if (!leash.isValid()) return;
                Rect crop = new Rect(x, y, x + lastW, y + lastH);
                Hidden.Shot shot = Hidden.capture(leash, crop, Math.min(CAPTURE_MAX_SCALE, GlRenderer.scale), null);
                if (shot == null) return;
                synchronized (PRE) {
                    if (preShot != null) release(preShot);
                    preShot = shot;
                    preTask = taskId;
                    preShotMs = SystemClock.uptimeMillis();
                }
                if (pkg != null) remember(pkg, taskId, shot);
            }
        });
    }

    /** La captura precargada de esta tarea, si es reciente (una sola vez). */
    private static Hidden.Shot takePreShot(int taskId) {
        synchronized (PRE) {
            Hidden.Shot s = preShot;
            if (s == null || taskId == -1 || preTask != taskId
                    || SystemClock.uptimeMillis() - preShotMs > PRELOAD_MS) {
                return null;
            }
            preShot = null;
            return s;
        }
    }

    /**
     * Se toca un icono: se sube ya a la GPU (y se prepara la capa) mientras el dedo está abajo,
     * para que al abrir la app el primer fotograma del genio salga sin esperas. {@code d} es una
     * copia propia; {@code screen}, dónde está el icono en pantalla.
     */
    static void prepareIcon(final Drawable d, final RectF screen, final String pkg) {
        if (!enabled || d == null || screen.isEmpty()) return;
        renderHandler().post(new Runnable() {
            @Override
            public void run() {
                // Con un genio en marcha su icono está en la GPU: no se toca.
                if (session != null || ending != null || ghost != null || !Hidden.ok()) return;
                SurfaceControl root = lastRoot;
                if (root == null || !root.isValid()) return;
                try {
                    GlRenderer.warm(root, lastW, lastH);
                    if (!GlRenderer.warmed) return;
                    Bitmap b = Bitmap.createBitmap(ICON_TEXTURE, ICON_TEXTURE, Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, ICON_TEXTURE, ICON_TEXTURE);
                    d.draw(new Canvas(b));
                    GlRenderer.uploadIcon(b, PREPARED_ICON);
                    b.recycle();
                    preparedPkg = null;
                    // Su última imagen, si la hay y Android la va a enseñar al abrir.
                    Snap snap;
                    synchronized (snaps) {
                        snap = pkg == null ? null : snaps.get(pkg);
                    }
                    if (snap != null && taskAlive(snap.task)
                            && GlRenderer.queue(snap.shot.buffer, snap.shot.colorSpace, PREPARED_CONTENT)
                            && GlRenderer.consume() == PREPARED_CONTENT) {
                        preparedPkg = pkg;
                    }
                    synchronized (preparedRect) {
                        preparedRect.set(screen);
                    }
                    preparedMs = SystemClock.uptimeMillis();
                } catch (Throwable t) {
                    Log.e("genio: icono precargado", t);
                }
            }
        });
    }

    /** El icono precargado es el de esta apertura (mismo sitio, reciente y aún en la GPU). */
    private static boolean preparedFor(RectF icon) {
        if (GlRenderer.iconOwner() != PREPARED_ICON || SystemClock.uptimeMillis() - preparedMs > PRELOAD_MS) return false;
        synchronized (preparedRect) {
            float tol = Math.max(icon.width(), preparedRect.width()) * SAME_ICON;
            return Math.abs(icon.centerX() - preparedRect.centerX()) < tol
                    && Math.abs(icon.centerY() - preparedRect.centerY()) < tol;
        }
    }

    /** Oscurecido del escritorio detrás de la copa (solo Ice): va con lo abierta que está. */
    private static float dimFor(float pw) {
        float k = style == STYLE_ICE ? ICE_DIM : style == STYLE_DEPTH ? DEPTH_DIM : 0f;
        return k * Math.max(0f, Math.min(1f, pw));
    }

    /** La sesión de este animador vuelve a un icono y aún no sabe cuál es su vista. */
    static boolean needsTarget(Object key) {
        Session s = session;
        return s != null && s.key == key && s.targetView == null;
    }

    /** La sesión de este animador aún no tiene el color de la app (MIUI lo da unos fotogramas tarde). */
    static boolean needsShade(Object key) {
        Session s = session;
        return s != null && s.key == key && !s.bgKnown;
    }

    /**
     * Color de fondo mientras MIUI no da el de la app: el de las ventanas de inicio del sistema,
     * negro en modo oscuro y blanco en claro. Antes era siempre blanco, y en modo oscuro se veía
     * un destello blanco que MIUI de serie no tiene.
     */
    private static int provisionalBg() {
        android.content.Context c = TapCatcher.context;
        try {
            if (c != null && (c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                return 0xFF000000;
            }
        } catch (Throwable ignored) {
        }
        return 0xFFFFFFFF;
    }

    /** Mezcla de dos colores opacos (t = 0 → a, 1 → b). */
    private static int mixColor(int a, int b, float t) {
        int r = Math.round(lerp((a >> 16) & 255, (b >> 16) & 255, t));
        int g = Math.round(lerp((a >> 8) & 255, (b >> 8) & 255, t));
        int bl = Math.round(lerp(a & 255, b & 255, t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** La sesión de este animador aún no tiene el icono (al cerrar se conoce al soltar el dedo). */
    static boolean needsIcon(Object key) {
        Session s = session;
        return s != null && s.key == key && s.iconDrawable == null && !s.iconFailed;
    }

    /** Desde {@code SurfaceParams.applyTo}: añade nuestros cambios a la transacción de MIUI. */
    static void onApply(SurfaceControl.Transaction t, SurfaceControl surface, float alpha, boolean hasAlpha) {
        Ghost gh = ghost;
        if (gh != null && surface == gh.leash) {               // la app que vuelve sola: la dibuja el genio
            t.setAlpha(surface, 0f);
            return;
        }
        Session s = session;
        if (s != null && surface == s.oldLeash && !s.finishing && s.shown) {
            t.setAlpha(surface, 0f);
            return;
        }
        if (s == null || s.leash != surface) {
            s = ending;
            if (s == null || s.leash != surface) return;
        }
        if (hasAlpha) s.alpha = alpha;
        SurfaceControl layer = s.layer;
        if (layer == null || !layer.isValid()) return;
        if (s.finishing) {
            if (s.ghosted) {                               // sigue sola: la capa la usa el genio suelto
                t.setAlpha(surface, 0f);
                return;
            }
            t.setVisibility(layer, false);                 // MIUI ya pone su propia transparencia
            if (s.hidWindow && !hasAlpha) t.setAlpha(surface, s.alpha);
            s.handedBack = true;
            return;
        }
        if (!s.ready()) {
            // Al abrir, hasta que se vea el genio solo se ve el icono flotante de MIUI (que
            // crece igual que el genio): ni la ventana desnuda ni el velo, que parpadeaban.
            if (s.open) {
                t.setAlpha(surface, 0f);
                s.hidWindow = true;
            }
            return;
        }
        s.markShown();
        float a = s.layerAlpha();
        // La ventana real se oculta; el genio entra en un fundido de unos fotogramas sobre el icono flotante.
        // (Antes, sin icono preparado, la ventana se fundía con la capa: con la copa, que no tiene
        // la forma del rectángulo de MIUI, se veía un fotograma la ventana real detrás.)
        t.setAlpha(surface, 0f);
        t.setAlpha(layer, a);
        t.setVisibility(layer, true);
        Hidden.setRelativeLayer(t, layer, surface, Z);
    }

    /** Desde {@code TransactionCompat.setMatrix}: si MIUI escala el inicio, la capa no se escala. */
    static void onMatrix(SurfaceControl.Transaction t, SurfaceControl surface, Matrix m) {
        if (surface != homeLeash) return;
        Session s = session;
        if (s != null) s.compensate(t, m);
    }

    /**
     * Prioridad del hilo de dibujo (nice de Linux; menos es más prioridad). Al abrir una app las 8
     * CPU están al 100 % y la app que arranca lleva sus hilos de inicio a −12: con −8
     * (URGENT_DISPLAY) el genio esperaba hasta 30 ms por fotograma a que le dejaran una CPU. Con
     * −16 gana esas esperas; trabaja ~5 ms por fotograma y se duerme hasta el siguiente refresco,
     * así que no le quita tiempo apreciable a nadie. Una app puede pedirla sin permisos especiales
     * (el audio usa −19).
     */
    private static final int RENDER_PRIORITY = -16;

    private static synchronized Handler renderHandler() {
        if (renderThread == null) renderThread = start("IceHome-genie", RENDER_PRIORITY);
        return renderThread;
    }

    private static synchronized Handler captureHandler() {
        if (captureThread == null) captureThread = start("IceHome-capture", Process.THREAD_PRIORITY_DISPLAY);
        return captureThread;
    }

    /** Crea los dos hilos ya, al instalar (sin animación en marcha); ver {@link Threads}. */
    static void startThreads() {
        renderHandler();
        captureHandler();
    }

    private static Handler start(String name, int priority) {
        Handler h = Threads.start(name, priority);
        Log.i("hilo " + Threads.describe(h));
        return h;
    }

    // ------------------------------------------------------------------ sesión

    private static final class Session {

        volatile Object key;
        /** Sentido actual (cambia si se interrumpe); el inicial, para el registro y la entrada. */
        volatile boolean open;
        final boolean startedOpen;
        final SurfaceControl root;
        final RectF full;
        /**
         * Color de la app bajo el contenido (el velo de MIUI). MIUI lo conoce unos fotogramas
         * después de empezar a abrir (ver WindowAnim): hasta entonces vale uno provisional según
         * el modo oscuro, y al llegar el bueno se pasa a él en {@link #BG_BLEND_S}.
         */
        private volatile int bgFrom, bgTo;
        private volatile long bgNs;
        volatile boolean bgKnown;
        volatile Drawable iconDrawable;
        /** El icono ya está en la GPU: el genio sale de él y vuelve a él. */
        volatile boolean iconReady, iconFailed;
        final long startMs = SystemClock.uptimeMillis();

        volatile SurfaceControl leash, layer;
        volatile int taskId = -1;
        volatile boolean finishing, shown, shownEarly, handedBack, handedOver, keyEnded, hidWindow, ghosted;
        /** Icono del escritorio al que vuelve (al cerrar). */
        volatile java.lang.ref.WeakReference<View> targetView;
        /** Superficie anterior de la ventana, si MIUI la cambió: también se mantiene oculta. */
        volatile SurfaceControl oldLeash;
        volatile long shownNs, lastFrameMs, leashMs, shownMs;
        private float firstPw = -1f;
        volatile float alpha = 1f, iconFade, pwNow;
        volatile int posted;
        String why;

        // Estado de MIUI (hilo de animación → hilos propios).
        final Object lock = new Object();
        /** Icono destino, para reconocer un toque en él (bajo lock). */
        final RectF tapRect = new RectF();
        private final RectF pendingCur = new RectF(), pendingIcon = new RectF();
        private float pendingRadius, pendingDir;
        // Sentido: 0 = abrir, 1 = cerrar, con transición al interrumpir (hilo de animación).
        private float dirFrom, dirTo, dirNow;
        private long dirNs;
        private Rect crop;
        private final AtomicBoolean renderQueued = new AtomicBoolean(), captureQueued = new AtomicBoolean();
        // Extremo cercano: icono, dedo, o transición de uno a otro (solo hilo de animación).
        private final RectF iconNow = new RectF(), iconFrom = new RectF(), iconTo = new RectF(), iconGoal = new RectF();
        private boolean iconInit;
        volatile boolean following;
        private boolean landed;
        private long blendNs;
        // Lámpara: posición y velocidad del extremo lejano (centro de arriba), peso de las físicas.
        private float lampX, lampY, lampVx, lampVy, lampW;
        private long lampNs;
        private boolean lampInit;
        private float pendingBend, pendingLift, pendingFlip;
        private float flipNow;
        private long flipNs;
        // Velocidad de los bordes del rectángulo de MIUI (px/s): para que el genio suelto siga
        // igual y para predecir el rectángulo entre dos fotogramas de MIUI (ver predict).
        private final float[] edgeVel = new float[4];
        /**
         * Profundidad: velocidad de cada borde tal como se ve en pantalla (px/s), medida entre dos
         * dibujos nuestros, no entre fotogramas de MIUI. MIUI manda fotogramas a trompicones; con
         * su velocidad la estela se apagaba de golpe en un hueco y se quedaba puesta al final. Con
         * la de los dibujos, si la ventana no se mueve en pantalla, no hay estela: es lo que haría
         * una cámara.
         */
        private final float[] velNow = new float[4];
        private final RectF prevDraw = new RectF();
        private long prevDrawNs;

        private void drawVelocity(RectF cur) {
            long now = System.nanoTime();
            float dt = (now - prevDrawNs) / 1e9f;
            if (prevDrawNs == 0 || dt > 0.1f) {
                java.util.Arrays.fill(velNow, 0f);        // primer dibujo o tras una pausa larga
            } else if (dt > 0.004f) {
                // Suavizado ligero (60 %): sigue los cambios sin temblar con un fotograma irregular.
                velNow[0] = lerp(velNow[0], (cur.left - prevDraw.left) / dt, 0.6f);
                velNow[1] = lerp(velNow[1], (cur.top - prevDraw.top) / dt, 0.6f);
                velNow[2] = lerp(velNow[2], (cur.right - prevDraw.right) / dt, 0.6f);
                velNow[3] = lerp(velNow[3], (cur.bottom - prevDraw.bottom) / dt, 0.6f);
            }
            prevDraw.set(cur);
            prevDrawNs = now;
        }
        // Profundidad: inclinación de la hoja (y su velocidad) que lleva el muelle, en el hilo de dibujo.
        private float tiltNow, tiltSpeed;
        private long tiltNs;

        /**
         * Muelle de la hoja: la inclinación persigue la que pide la velocidad ({@code tiltTarget})
         * con peso, en pasos de 4 ms (estable aunque un fotograma llegue tarde). Al abrir parte
         * ya un poco inclinada; al cerrar, recta. Sigue redibujando hasta quedarse quieta.
         */
        void tiltSpring(GlRenderer.Geo g) {
            long now = System.nanoTime();
            if (tiltNs == 0) {
                tiltNow = startedOpen ? g.tiltStart : 0f;
                tiltSpeed = 0f;
            } else {
                float dt = Math.min(0.05f, (now - tiltNs) / 1e9f);
                float w = (float) (2 * Math.PI / TILT_RESPONSE);
                float k = w * w, c = 2f * TILT_DAMPING * w;
                while (dt > 0f) {
                    float step = Math.min(dt, 0.004f);
                    tiltSpeed += (k * (g.tiltTarget - tiltNow) - c * tiltSpeed) * step;
                    tiltNow += tiltSpeed * step;
                    dt -= step;
                }
            }
            tiltNs = now;
            applyTilt(g, tiltNow);
            if (Math.abs(tiltNow - g.tiltTarget) > 0.002f || Math.abs(tiltSpeed) > 0.02f) dirty = true;
        }

        private final RectF prevCur = new RectF();
        private long prevCurNs;
        /** Cuándo llegó la última muestra de MIUI (System.nanoTime, bajo lock). */
        private long sampleNs;
        /** Hacia dónde va cada borde: el icono al cerrar, la pantalla al abrir (bajo lock). */
        private final RectF pendingGoal = new RectF();
        private boolean pendingHasGoal;

        // Capturas ("IceHome-capture"); la última espera en GlRenderer hasta el siguiente dibujo.
        private volatile long lastCaptureMs, lastCaptureCostMs;
        private final ArrayList<SurfaceControl> excludeList = new ArrayList<>();
        private SurfaceControl[] exclude;
        private int captures, failed;
        /** La última captura al cerrar fue la primera, a baja resolución: toca otra buena. */
        private volatile boolean lowResShot;
        private long captureNs;
        private boolean layerExcluded;
        private volatile boolean preloaded, iconPreloaded, contentPreloaded;
        private long windowSinceNs;

        // Solo en el hilo de dibujo.
        private final RectF cur = new RectF(), icon = new RectF();
        private final GlRenderer.Geo geoNow = new GlRenderer.Geo();
        private long firstNs, firstPostMs, drawNs, lastDrawNs, maxGapNs;
        private int janks;
        private final SurfaceControl.Transaction ownTx = new SurfaceControl.Transaction();
        private volatile long firstSwapNs;
        private boolean started;
        private volatile boolean fromWindow;

        // Compensación de la escala del inicio (aplicador / hilo de dibujo).
        private final Matrix inverse = new Matrix(), layerMatrix = new Matrix();
        private final float[] tmp = new float[9];
        private boolean compensated;

        final Runnable renderTask = new Runnable() {
            @Override
            public void run() {
                renderQueued.set(false);
                render(System.nanoTime());
            }
        };

        /**
         * Hay algo nuevo que dibujar. El primer fotograma, ya; los siguientes, en el próximo
         * refresco de la pantalla que respete {@link #maxFps}. Si el bucle de refrescos ya está
         * en marcha (ver {@link #pacer}), basta con marcarlo: lo recoge el siguiente refresco.
         */
        void requestRender() {
            dirty = true;
            if (!renderQueued.compareAndSet(false, true)) return;
            renderHandler().post(posted == 0 ? renderTask : schedulePacer);
        }

        private volatile boolean dirty;
        private long lastPacedNs;

        private final Runnable schedulePacer = new Runnable() {
            @Override
            public void run() {
                android.view.Choreographer.getInstance().postFrameCallback(pacer);
            }
        };

        /**
         * Bucle de refrescos del hilo de dibujo. MIUI calcula su muelle en el hilo principal del
         * launcher, que al abrir una app va justo (fotogramas de 15-20 ms y huecos de 25-60 ms):
         * si el genio solo dibujase cuando llega un fotograma de MIUI, cada hueco sería un tirón.
         * Así que, mientras MIUI siga animando, el genio dibuja en cada refresco de la pantalla y
         * rellena los huecos prediciendo el rectángulo ({@link #predict}).
         */
        private final android.view.Choreographer.FrameCallback pacer = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                android.view.Choreographer ch = android.view.Choreographer.getInstance();
                if (finishing) {
                    renderQueued.set(false);
                    return;
                }
                if (lastPacedNs != 0 && frameTimeNanos - lastPacedNs < periodNs()) {
                    ch.postFrameCallback(this);                  // aún no toca (límite de fps)
                    return;
                }
                lastPacedNs = frameTimeNanos;
                if (render(frameTimeNanos) && !finishing) {
                    ch.postFrameCallback(this);                  // sigue en el próximo refresco
                    return;
                }
                renderQueued.set(false);
                // Llegó una muestra justo mientras se decidía parar: que no se pierda.
                if (dirty && !finishing && renderQueued.compareAndSet(false, true)) ch.postFrameCallback(this);
            }
        };
        final Runnable captureTask = new Runnable() {
            @Override
            public void run() {
                captureOnce();
            }
        };
        final Runnable endTask = new Runnable() {
            @Override
            public void run() {
                end();
            }
        };
        private final Runnable watchdog = new Runnable() {
            @Override
            public void run() {
                if (session == Session.this) stop("sin fotogramas");
            }
        };

        Session(Frame f) {
            key = f.key;
            open = startedOpen = f.open;
            dirTo = dirFrom = dirNow = f.open ? 0f : 1f;
            root = f.root;
            full = new RectF(f.full);
            if (f.open) {
                bgKnown = (f.bg >>> 24) != 0;
                bgFrom = bgTo = bgKnown ? f.bg | 0xFF000000 : provisionalBg();
            } else {
                // Al cerrar, MIUI solo encoge la ventana: nada debajo. Con el velo (claro en muchas
                // apps) asomaba un borde blanco por las esquinas y la barra de gestos de la captura.
                bgKnown = true;
                bgFrom = bgTo = 0;
            }
            iconDrawable = f.iconDrawable;
            if (f.exclude != null) Collections.addAll(excludeList, f.exclude);
        }

        /** Color de fondo de este fotograma (fundido del provisional al de MIUI). */
        int bgNow() {
            long ns = bgNs;
            if (ns == 0) return bgTo;
            float t = smooth((System.nanoTime() - ns) / 1e9f / BG_BLEND_S);
            if (t >= 1f) return bgTo;
            dirty = true;                                      // seguir dibujando hasta acabar
            return mixColor(bgFrom, bgTo, t);
        }

        /** Puede seguir sola hasta el icono: ya se ve, con su captura, y sabe a qué icono va. */
        boolean canGoAlone() {
            synchronized (lock) {
                return shown && fromWindow && !following && !tapRect.isEmpty() && !finishing;
            }
        }

        String goalString() {
            return iconInit ? iconGoal.toShortString() : "—";
        }

        /** La apertura nueva es de esta misma app: misma tarea, o sin ventana aún y mismo icono. */
        boolean sameApp(Frame f) {
            if (f.taskId != -1 && taskId != -1) return f.taskId == taskId;
            if (!f.hasIcon || !iconInit || iconGoal.isEmpty()) return false;
            float tol = Math.max(f.icon.width(), iconGoal.width()) * SAME_ICON;
            return Math.abs(f.icon.centerX() - iconGoal.centerX()) < tol
                    && Math.abs(f.icon.centerY() - iconGoal.centerY()) < tol;
        }

        /** Transparencia de nuestra capa: al abrir, fundido de entrada; al cerrar, de salida. */
        float layerAlpha() {
            if (iconReady) return shown ? 1f : 0f;
            if (!open) return 1f - iconFade;
            if (!shown) return 0f;
            return smooth((SystemClock.elapsedRealtimeNanos() - shownNs) / 1e9f / OPEN_FADE_S);
        }

        void markShown() {
            if (shown) return;
            succeeded();
            shownNs = SystemClock.elapsedRealtimeNanos();
            shownMs = SystemClock.uptimeMillis();
            shownEarly = pwNow < EARLY_PW;
            shown = true;
        }

        // ---------------------------------------------------------- hilo de animación

        void post(Frame f) {
            lastFrameMs = SystemClock.uptimeMillis();
            if (f.leash != null && f.leash != leash) {
                if (leash != null) {
                    // MIUI cambió de superficie (pantalla de inicio de la app → ventana real).
                    Log.i("genio: la ventana cambia de superficie");
                    oldLeash = leash;
                    synchronized (lock) {
                        crop = null;
                    }
                    lastCaptureMs = 0;                       // la ventana nueva, cuanto antes
                }
                leash = f.leash;
                if (leashMs == 0) leashMs = lastFrameMs;
            }
            if (f.iconDrawable != null && iconDrawable == null) iconDrawable = f.iconDrawable;
            if (!bgKnown && (f.bg >>> 24) != 0) {
                // Llega el color de la ventana de inicio de la app: del provisional a él, sin saltos.
                bgFrom = bgNow();
                bgTo = f.bg | 0xFF000000;
                bgNs = System.nanoTime();
                bgKnown = true;
                dirty = true;
            }
            if (f.targetView != null) targetView = new java.lang.ref.WeakReference<>(f.targetView);
            if (taskId == -1 && f.taskId != -1) taskId = f.taskId;
            long nowNs = SystemClock.elapsedRealtimeNanos();
            // Interrupción: el sentido cambia y la forma de abrir y la de cerrar se funden.
            float dirTarget = f.open ? 0f : 1f;
            if (dirTarget != dirTo) {
                if (f.open != open) {
                    Log.i("genio: interrupción, ahora " + (f.open ? "abrir" : "cerrar"));
                    lastCaptureMs = 0;                       // contenido al día para el nuevo sentido
                }
                dirFrom = dirNow;
                dirTo = dirTarget;
                dirNs = nowNs;
            }
            open = f.open;
            dirNow = lerp(dirFrom, dirTo, smooth((nowNs - dirNs) / 1e9f / DIR_BLEND_S));
            updateIcon(f, nowNs);
            lamp(f.cur, nowNs);
            // Copa al derecho o al revés: con el dedo, siempre al derecho (el dedo es el extremo
            // cercano, abajo); al soltar, cambia poco a poco hacia la que pide el icono.
            float flipTarget = following ? 0f : flipFor(f.hasIcon ? f.icon : iconNow, full);
            if (flipNs == 0) flipNow = flipTarget;
            else flipNow += (flipTarget - flipNow) * (1f - (float) Math.exp(-(nowNs - flipNs) / 1e9f / FLIP_S));
            flipNs = nowNs;
            float pw = progress(f.cur.width(), iconNow.width(), full.width());
            pwNow = pw;
            if (firstPw < 0f) firstPw = pw;
            if (!open) iconFade = 1f - smooth((pw - ICON_FADE_END) / (ICON_FADE_START - ICON_FADE_END));
            // Llega al icono: un tic de vibración (una vez; si se reabre, vuelve a poder sonar).
            if (open) landed = false;
            else if (!landed && !following && f.hasIcon && pw < LAND_PW) {
                landed = true;
                Haptics.land();
                if (style == STYLE_ICE) {
                    java.lang.ref.WeakReference<View> tv = targetView;
                    IconPress.land(tv == null ? null : tv.get());
                }
            }
            synchronized (lock) {
                float dt = (nowNs - prevCurNs) / 1e9f;
                if (prevCurNs != 0 && dt > 0.002f && dt < 0.1f) {
                    float k = 0.5f;                            // suavizado: los fotogramas de MIUI no van parejos
                    edgeVel[0] = lerp(edgeVel[0], (f.cur.left - prevCur.left) / dt, k);
                    edgeVel[1] = lerp(edgeVel[1], (f.cur.top - prevCur.top) / dt, k);
                    edgeVel[2] = lerp(edgeVel[2], (f.cur.right - prevCur.right) / dt, k);
                    edgeVel[3] = lerp(edgeVel[3], (f.cur.bottom - prevCur.bottom) / dt, k);
                }
                prevCur.set(f.cur);
                prevCurNs = nowNs;
                sampleNs = System.nanoTime();               // mismo reloj que los refrescos
                pendingCur.set(f.cur);
                pendingIcon.set(iconNow);
                pendingRadius = f.radius;
                pendingDir = dirNow;
                pendingFlip = flipNow;
                // Destino de la predicción: nunca se adelanta más allá de él.
                pendingHasGoal = open || f.hasIcon;
                if (open) pendingGoal.set(full);
                else if (f.hasIcon) pendingGoal.set(f.icon);
                if (f.hasIcon) tapRect.set(f.icon);
                else tapRect.setEmpty();
                // Volviendo al icono: se puede tocar para reabrir la app.
                TapCatcher.want(!open && !following && f.hasIcon ? f.icon : null);
                float w = lampW * physics;
                // Límites suaves (sin topes bruscos al deslizar en diagonal muy rápido).
                pendingBend = soft((lampX - f.cur.centerX()) * w, full.width() * LAMP_MAX_BEND);
                pendingLift = soft((lampY - f.cur.top) * w, full.height() * LAMP_MAX_LIFT);
                if (crop == null && f.leash != null) {
                    crop = new Rect(f.originX, f.originY, f.originX + Math.round(full.width()),
                            f.originY + Math.round(full.height()));
                }
            }
            requestRender();
            // Cuándo hace falta otra captura lo decide captureOnce (presupuesto de capturas).
            if (leash != null && captureDue() && captureQueued.compareAndSet(false, true)) {
                captureHandler().post(captureTask);
            }
        }

        /**
         * Extremo cercano: el icono; al arrastrar, un "icono" bajo el dedo. Al pasar de uno a otro
         * (soltar, o interrumpir una apertura con el dedo) se desliza suavemente, siguiendo al
         * destino aunque se mueva.
         */
        private void updateIcon(Frame f, long now) {
            boolean toIcon = f.hasIcon;
            if (toIcon) {
                iconTo.set(f.icon);
            } else {
                float w = full.width() * FINGER_ANCHOR;
                iconTo.set(f.cur.centerX() - w / 2, f.cur.bottom - w, f.cur.centerX() + w / 2, f.cur.bottom);
            }
            if (!iconInit) {
                iconNow.set(iconTo);
                iconFrom.set(iconTo);
                blendNs = now - (long) (ICON_BLEND_S * 1e9f);
                following = !toIcon;
                iconInit = true;
            } else if (following == toIcon || (toIcon && !iconGoal.equals(f.icon))) {
                iconFrom.set(iconNow);                       // nuevo destino: desde donde está ahora
                blendNs = now;
                following = !toIcon;
            }
            if (toIcon) iconGoal.set(f.icon);
            float t = smooth((now - blendNs) / 1e9f / ICON_BLEND_S);
            iconNow.set(lerp(iconFrom.left, iconTo.left, t), lerp(iconFrom.top, iconTo.top, t),
                    lerp(iconFrom.right, iconTo.right, t), lerp(iconFrom.bottom, iconTo.bottom, t));
        }

        /**
         * Físicas de lámpara: el extremo lejano (centro de arriba) persigue al de MIUI con un
         * muelle. Al arrastrar con el dedo se queda atrás, se inclina y se balancea; al soltar,
         * las físicas se apagan suavemente mientras la copa vuela al icono.
         */
        private void lamp(RectF cur, long now) {
            float tx = cur.centerX(), ty = cur.top;
            if (!lampInit) {
                lampX = tx;
                lampY = ty;
                lampNs = now;
                lampInit = true;
            }
            float dt = Math.min(0.05f, Math.max(0f, (now - lampNs) / 1e9f));
            lampNs = now;
            // Peso: entra al arrastrar, sale al soltar o al abrir.
            float target = following ? 1f : 0f;
            float rate = dt / (following ? 0.12f : LAMP_FADE_S);
            lampW = target > lampW ? Math.min(target, lampW + rate) : Math.max(target, lampW - rate);
            if (lampW <= 0f) {                                  // sin físicas: pegado al de MIUI
                lampX = tx;
                lampY = ty;
                lampVx = lampVy = 0f;
                return;
            }
            float c = 2f * LAMP_ZETA * (float) Math.sqrt(LAMP_K);
            for (float left = dt; left > 0f; left -= 0.004f) {  // pasos cortos: estable con saltos
                float h = Math.min(0.004f, left);
                lampVx += (LAMP_K * (tx - lampX) - c * lampVx) * h;
                lampVy += (LAMP_K * (ty - lampY) - c * lampVy) * h;
                lampX += lampVx * h;
                lampY += lampVy * h;
            }
        }

        // ---------------------------------------------------------- hilo de capturas

        private void captureOnce() {
            captureQueued.set(false);
            SurfaceControl window = leash, l = layer;
            Rect c;
            synchronized (lock) {
                c = crop;
            }
            if (finishing || window == null || c == null) return;
            // Una captura cada vez, ya usada la anterior, y espaciadas: capturar carga al compositor.
            if (GlRenderer.hasQueued() || !captureDue()) return;
            // La primera puede ir antes de que exista la capa (al cerrar, así se ve antes); la capa
            // se excluye en cuanto se pone encima de la ventana.
            if (l != null && !layerExcluded) {
                excludeList.add(0, l);
                exclude = null;
                layerExcluded = true;
            }
            long t0 = System.nanoTime();
            Hidden.Shot shot = captures == 0 && !startedOpen ? takePreShot(taskId) : null;
            if (shot != null) {
                preloaded = true;                                 // tomada al empezar el gesto
            } else {
                float scale = Math.min(CAPTURE_MAX_SCALE, GlRenderer.scale);
                lowResShot = captures == 0 && !startedOpen && FIRST_CLOSE_SCALE < scale;
                if (lowResShot) scale = FIRST_CLOSE_SCALE;
                shot = Hidden.capture(window, c, scale, exclusions());
                long cost = System.nanoTime() - t0;
                captureNs += cost;
                lastCaptureCostMs = cost / 1_000_000L;
            }
            captures++;
            lastCaptureMs = SystemClock.uptimeMillis();
            if (shot == null) {
                failed++;
                return;
            }
            boolean ok = !finishing && window.isValid() && GlRenderer.queue(shot.buffer, shot.colorSpace, this);
            release(shot);                                       // la Surface guarda su referencia
            if (!ok) return;
            requestRender();
        }

        /** Toca capturar: la primera siempre; luego según el sentido, "en vivo" y lo que cuestan. */
        boolean captureDue() {
            if (lastCaptureMs == 0) return true;
            // Con el dedo en el escritorio (otra app, deslizar…), el compositor para el escritorio:
            // el contenido de la que se cierra ya no cambia.
            if (!open && TapCatcher.fingerDown()) return false;
            // Al cerrar, el contenido ya no cambia y cada captura frena a SurfaceFlinger 25-40 ms
            // (un tirón de toda la pantalla): solo se repite si la primera salió a baja resolución,
            // y entonces cuanto antes, mientras la ventana aún es grande. Antes se refrescaba cada
            // 700 ms, cuando la ventana ya casi era el icono y el refresco solo daba un tirón.
            if (!open && !lowResShot) return false;
            long budget = open ? (live ? (captures < OPEN_EARLY_SHOTS ? OPEN_EARLY_MS : OPEN_LIVE_MS)
                    : (captures < OPEN_EARLY_SHOTS ? OPEN_EARLY_MS : Long.MAX_VALUE))
                    : 0;
            if (budget == Long.MAX_VALUE) return false;
            long gap = Math.max(budget, (long) (lastCaptureCostMs * CAPTURE_IDLE));
            return SystemClock.uptimeMillis() - lastCaptureMs >= gap;
        }

        /** Capas que no deben salir en la captura; sin las que MIUI ya haya liberado. */
        private SurfaceControl[] exclusions() {
            boolean changed = exclude == null;
            for (int i = excludeList.size() - 1; i >= 0; i--) {
                if (!excludeList.get(i).isValid()) {
                    excludeList.remove(i);
                    changed = true;
                }
            }
            if (changed) exclude = excludeList.toArray(new SurfaceControl[0]);
            return exclude;
        }

        // ---------------------------------------------------------- hilo de dibujo

        /**
         * Dibuja el fotograma del refresco {@code frameNs} (System.nanoTime). Devuelve si el bucle
         * de refrescos debe seguir: sí mientras MIUI siga mandando muestras.
         */
        boolean render(long frameNs) {
            if (finishing) return false;
            float radius, dir, bend, lift, flip;
            boolean moved, fresh;
            synchronized (lock) {
                // MIUI lleva un rato sin muestras: ha acabado o el dedo está quieto. Se para el
                // bucle (la próxima muestra lo vuelve a poner en marcha).
                fresh = frameNs - sampleNs < KEEP_TICKING_PERIODS * periodNs();
                moved = predict(frameNs, cur);
                flip = pendingFlip;
                icon.set(pendingIcon);
                radius = pendingRadius;
                dir = pendingDir;
                bend = pendingBend;
                lift = pendingLift;
            }
            if (!dirty && !moved && posted > 0) return fresh;  // nada nuevo que enseñar
            dirty = false;
            SurfaceControl w = leash;
            if (w != null && !w.isValid()) {                   // el sistema canceló la transición
                stop("la ventana ya no existe");
                return false;
            }
            Handler h = renderHandler();
            h.removeCallbacks(watchdog);
            // Con el dedo quieto MIUI no manda fotogramas: no es el final.
            h.postDelayed(watchdog, following ? HOLD_MS : WATCHDOG_MS);
            if (!started && !begin()) {
                finishing = true;
                fail();
                return false;
            }
            boolean hadWindow = fromWindow;
            if (GlRenderer.consume() == this) fromWindow = true;
            // Al abrir, la primera captura entra fundida sobre la pantalla provisional.
            if (fromWindow && !hadWindow && startedOpen && posted > 0) windowSinceNs = System.nanoTime();
            if (!fromWindow && !startedOpen && lastCaptureMs == 0) {
                // Al cerrar, la captura precargada al empezar el gesto: se usa ya, sin esperar.
                Hidden.Shot pre = takePreShot(taskId);
                if (pre != null) {
                    boolean q = GlRenderer.queue(pre.buffer, pre.colorSpace, this);
                    release(pre);
                    if (q && GlRenderer.consume() == this) {
                        fromWindow = true;
                        preloaded = true;
                        captures++;
                        lastCaptureMs = SystemClock.uptimeMillis();
                    }
                }
            }
            if (!fromWindow) {
                if (!startedOpen) {                           // al cerrar, sin captura aún no hay nada
                    if (leash != null && captureQueued.compareAndSet(false, true)) captureHandler().post(captureTask);
                    return fresh;
                }
            }

            long now = SystemClock.elapsedRealtimeNanos();
            if (firstNs == 0) firstNs = now;
            // Entrada progresiva solo si el genio empieza sobre la ventana normal (al arrastrar).
            float ramp = startedOpen ? 1f : smooth((now - firstNs) / 1e9f / RAMP_IN_S);
            if (posted == 0 && startedOpen && !fromWindow && preparedPkg != null && preparedFor(icon)
                    && GlRenderer.windowOwner() == PREPARED_CONTENT) {
                // Al abrir una app que sigue en recientes: su última imagen desde el primer fotograma.
                GlRenderer.claimWindow(this);
                fromWindow = true;
                contentPreloaded = true;
            }
            if (!iconReady && !iconFailed && iconDrawable != null) {
                if (posted == 0 && preparedFor(icon)) {         // ya subido al tocar el icono
                    GlRenderer.claimIcon(this);
                    iconReady = true;
                    iconPreloaded = true;
                } else {
                    uploadIcon();
                }
            }
            if (style == STYLE_DEPTH) drawVelocity(cur);
            shape(cur, icon, full, dir, ramp, lift, radius, bend, flip, iconReady, velNow, geoNow);
            if (style == STYLE_DEPTH) tiltSpring(geoNow);
            geoNow.fade = windowSinceNs == 0 ? 1f : smooth((System.nanoTime() - windowSinceNs) / 1e9f / CONTENT_FADE_S);
            if (geoNow.fade < 1f) dirty = true;                  // seguir dibujando hasta acabar el fundido
            // Con estela, otro fotograma: así se apaga sola aunque MIUI deje de mandar fotogramas.
            if (geoNow.vel[0] != 0f || geoNow.vel[1] != 0f || geoNow.vel[2] != 0f || geoNow.vel[3] != 0f) dirty = true;
            long t0 = System.nanoTime();
            if (lastDrawNs != 0) {
                long gap = t0 - lastDrawNs;
                if (gap > JANK_NS) janks++;
                if (gap > maxGapNs) maxGapNs = gap;
            }
            lastDrawNs = t0;
            Ghost gh = ghost;
            boolean ok = GlRenderer.beginFrame(dimFor(Math.max(geoNow.pw, gh != null ? gh.lastPw : 0f)));
            if (ok && gh != null) ok = gh.drawStep(t0);               // el que vuelve solo, debajo
            ok = ok && redraw() && GlRenderer.endFrame();
            if (!ok) {
                finishing = true;
                fail();
                return false;
            }
            drawNs += System.nanoTime() - t0;
            if (posted == 0) {
                firstPostMs = SystemClock.uptimeMillis();
                firstSwapNs = System.nanoTime();
            }
            posted++;

            // Sin ventana, nadie más gobierna la capa: se muestra desde aquí.
            if (leash == null && ready()) {
                markShown();
                ownTx.setAlpha(layer, layerAlpha());
                ownTx.setVisibility(layer, true);
                ownTx.apply();
            }
            return fresh;
        }

        /**
         * Rectángulo de MIUI previsto para el refresco {@code frameNs}, a partir de la última
         * muestra y la velocidad de cada borde (bajo lock). Solo rellena huecos: se adelanta como
         * mucho {@link #MAX_AHEAD_PERIODS} refrescos (uno con el dedo, que puede pararse en seco) y
         * ningún borde pasa de su destino (el icono al cerrar, la pantalla al abrir). Devuelve si
         * el resultado difiere de la muestra (hay algo nuevo que dibujar).
         */
        private boolean predict(long frameNs, RectF out) {
            out.set(pendingCur);
            float ahead = (frameNs - sampleNs) / 1e9f;
            float max = (following ? 1f : MAX_AHEAD_PERIODS) / Math.max(15f, maxFps);
            if (ahead <= 0f || sampleNs == 0) return false;
            ahead = Math.min(ahead, max);
            out.left = towards(pendingCur.left, out.left + edgeVel[0] * ahead, pendingGoal.left);
            out.top = towards(pendingCur.top, out.top + edgeVel[1] * ahead, pendingGoal.top);
            out.right = towards(pendingCur.right, out.right + edgeVel[2] * ahead, pendingGoal.right);
            out.bottom = towards(pendingCur.bottom, out.bottom + edgeVel[3] * ahead, pendingGoal.bottom);
            return Math.abs(out.left - pendingCur.left) + Math.abs(out.top - pendingCur.top)
                    + Math.abs(out.right - pendingCur.right) + Math.abs(out.bottom - pendingCur.bottom) > 0.5f;
        }

        /** Un borde previsto que no cruza su destino (si lo hay): se queda en él. */
        private float towards(float from, float to, float goal) {
            if (!pendingHasGoal) return to;
            return (from - goal) * (to - goal) < 0f ? goal : to;
        }

        /** Este genio en el fotograma en curso (hilo de dibujo). */
        boolean redraw() {
            return GlRenderer.drawGenie(GlRenderer.main(), fromWindow, geoNow, full.width() * 0.5f, full.height(), bgNow());
        }

        /** Ya hay una imagen nuestra en pantalla: un dibujo hecho y un refresco después. */
        boolean ready() {
            return posted > 0 && System.nanoTime() - firstSwapNs > READY_NS;
        }

        /** Primera vez en el hilo de dibujo: capa lista, oculta, arriba del todo y sin escala del inicio. */
        private boolean begin() {
            if (!GlRenderer.ensure(root, Math.round(full.width()), Math.round(full.height()))) return false;
            // Una captura en espera: si es de esta animación (al cerrar se pide antes de la capa), sirve.
            if (GlRenderer.hasQueued() && GlRenderer.consume() == this) fromWindow = true;
            SurfaceControl l = GlRenderer.layer;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            GlRenderer.attach(t, root);                        // por si el launcher rehízo su superficie
            if (ghost == null) t.setVisibility(l, false);      // si hay un genio suelto, sigue a la vista
            t.setLayer(l, Z_TOP);                              // también deja de ser relativa
            synchronized (inverse) {
                Hidden.setMatrix(t, l, GlRenderer.layerMatrix(compensated ? inverse : null, layerMatrix), tmp);
                layer = l;
            }
            t.apply();
            started = true;
            return true;
        }

        /** El icono de la app, a la GPU (una vez por animación). */
        private void uploadIcon() {
            try {
                Bitmap b = Bitmap.createBitmap(ICON_TEXTURE, ICON_TEXTURE, Bitmap.Config.ARGB_8888);
                Drawable d = iconDrawable;
                d.setBounds(0, 0, ICON_TEXTURE, ICON_TEXTURE);
                d.draw(new Canvas(b));
                GlRenderer.uploadIcon(b, this);
                b.recycle();
                iconReady = true;
            } catch (Throwable e) {
                Log.e("genio: icono", e);
                iconFailed = true;
                iconDrawable = null;
            }
        }

        /**
         * Deshace en nuestra capa la escala que MIUI aplica al inicio, en la misma transacción.
         * Si la capa aún no es nuestra, se recuerda y se aplica al empezar.
         */
        void compensate(SurfaceControl.Transaction t, Matrix homeMatrix) {
            synchronized (inverse) {
                if (!homeMatrix.invert(inverse)) return;
                compensated = true;
                SurfaceControl l = layer;
                if (l != null && l.isValid()) Hidden.setMatrix(t, l, GlRenderer.layerMatrix(inverse, layerMatrix), tmp);
            }
        }

        /** Fin: la capa se oculta (se reutiliza) y la ventana real queda con la transparencia de MIUI. */
        void end() {
            renderHandler().removeCallbacks(watchdog);
            if (ghosted && !goAlone()) ghosted = false;
            SurfaceControl l = layer;
            synchronized (inverse) {
                layer = null;                                  // ningún hook vuelve a tocarla
            }
            if (ending == this) ending = null;
            SurfaceControl window = leash;
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                Ghost gh = ghost;
                if (ghosted) {
                    // Sigue sola: la capa, arriba del todo y a la vista, hasta que llegue al icono.
                    if (window != null && window.isValid()) t.setAlpha(window, 0f);
                    if (l != null && l.isValid() && !otherSessionOnLayer()) {
                        SurfaceControl below = ghostBelow;
                        ghostBelow = null;
                        if (below != null && below.isValid()) Hidden.setRelativeLayer(t, l, below, -1);
                        else t.setLayer(l, Z_TOP);
                        t.setAlpha(l, 1f);
                        t.setVisibility(l, true);
                    }
                } else {
                    if ((shown || hidWindow) && !handedBack && window != null && window.isValid()) t.setAlpha(window, alpha);
                    if (gh != null && startedOpen) {
                        finishGhost();                         // la app abierta ya lo tapa todo
                        gh = null;
                    }
                    if (l != null && l.isValid()) {
                        if (gh == null) t.setVisibility(l, false);
                        else if (!otherSessionOnLayer()) t.setLayer(l, Z_TOP);
                    }
                }
                t.apply();
            } catch (Throwable e) {
                Log.e("genio: error al terminar", e);
            }
            Log.i(String.format(java.util.Locale.ROOT,
                    "genio %s a %.0f%%: empieza con anchura %.0f%%, %d fotogramas en %d ms (%d tirones, máx. %.0f ms); "
                            + "primer dibujo a %s, ventana a %s, visible a %s (%s); captura %.1f ms (%d/%d fallidas), "
                            + "dibujo %.1f ms%s; fin: %s",
                    startedOpen ? "abrir" : "cerrar", GlRenderer.scale * 100, firstPw * 100, posted,
                    SystemClock.uptimeMillis() - startMs, janks, maxGapNs / 1e6f,
                    ms(firstPostMs), ms(leashMs), ms(shownMs),
                    shown ? (shownEarly ? "desde el icono" : "tarde") : "no llegó a verse",
                    captures == 0 ? 0f : captureNs / 1e6f / captures, failed, captures,
                    posted == 0 ? 0f : drawNs / 1e6f / posted,
                    (preloaded ? "; captura precargada" : "") + (iconPreloaded ? "; icono precargado" : "")
                            + (contentPreloaded ? "; con su última imagen" : ""), why));
        }

        /** Otra sesión ya colocó la capa sobre su ventana. */
        private boolean otherSessionOnLayer() {
            Session s = session;
            return s != null && s != this && s.started;
        }

        /** Hilo de dibujo: este genio pasa a seguir solo (se queda con su contenido). */
        private boolean goAlone() {
            GlRenderer.Content c = GlRenderer.detach(this);
            if (c == null) return false;
            Ghost old = ghost;
            if (old != null) finishGhost();
            RectF raw = new RectF(), goal = new RectF();
            float[] vel = new float[4];
            float r, b;
            synchronized (lock) {
                raw.set(pendingCur);
                goal.set(tapRect);
                System.arraycopy(edgeVel, 0, vel, 0, 4);
                r = pendingRadius;
                b = pendingBend;
            }
            java.lang.ref.WeakReference<View> v = targetView;
            ghost = new Ghost(c, full, raw, goal, vel, r, b, bgNow(), iconReady, leash, v);
            Ghost.schedule();
            final View iconView = v == null ? null : v.get();
            final Icons ic = icons;
            if (iconView != null && ic != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ic.setVisible(iconView, false);         // MIUI lo enseña al abandonar el cierre
                    }
                });
            }
            Log.i("genio: la app que se cierra sigue sola hasta su icono");
            return true;
        }

        private String ms(long t) {
            return t == 0 ? "—" : (t - startMs) + " ms";
        }
    }

    /**
     * Estilo Profundidad (ver {@link #STYLE_DEPTH}): el rectángulo de MIUI tal cual (su
     * animación normal), con desenfoque según la velocidad de cada borde ({@code vel}, px/s, ya
     * suavizada) y la inclinación que pide su velocidad ({@link GlRenderer.Geo#tiltTarget}). La
     * inclinación de verdad la lleva un muelle en la sesión ({@link Session#tiltSpring}); aquí
     * se pone ya la pedida, para quien dibuje sin muelle (el genio que vuelve solo).
     */
    private static void depth(RectF cur, RectF icon, RectF full, float dir, float ramp, float radius,
                              boolean iconReady, float[] vel, float pw, GlRenderer.Geo out) {
        out.cur.set(cur);
        out.icon.set(icon);
        out.pw = pw;
        out.lead = out.lag = 1f;
        out.cup = 2f;
        out.radius = radius;
        // Como en HyperOS: al salir, el icono crece y se funde con la app; al volver, al revés.
        out.iconMix = iconMix(pw, dir, iconReady);
        out.splashIcon = 0f;
        out.bend = 0f;
        out.flip = 0f;
        out.tiltTarget = out.tiltStart = 0f;
        out.tiltEnv = 1f;
        if (vel == null) return;
        float amt = strength / 2.2f * ramp;
        // Muy cerca del icono la estela se apaga: aterriza nítido, sin «manchar» el icono. Y al
        // abrir, en el último tramo (el muelle llegando a pantalla completa), también: la app
        // queda nítida justo cuando la ventana de verdad la sustituye.
        float env = smooth((pw - 0.04f) / 0.16f) * lerp(smooth((1f - pw) / 0.07f), 1f, dir);
        float h = Math.max(1f, full.height());
        float maxBlur = DEPTH_MAX_BLUR * h;
        float sum = 0f, most = 0f;
        for (int i = 0; i < 4; i++) {
            float v = vel[i] * DEPTH_SHUTTER_S * amt * env;
            out.vel[i] = Math.max(-maxBlur, Math.min(maxBlur, v));
            most = Math.max(most, Math.abs(out.vel[i]));
            sum += Math.abs(vel[i]);
        }
        // Menos de medio píxel de estela no se ve: a cero, y así se deja de redibujar.
        if (most < 0.5f) java.util.Arrays.fill(out.vel, 0f);
        // La hoja: rígida, solo rota sobre su centro (sin curvarse), y siempre hacia el mismo lado:
        // arriba hacia el fondo y abajo hacia ti, esté donde esté el icono (como en el vídeo de
        // HyperOS 4: al salir de la app, arriba se ve más estrecha que abajo).
        float speed = smooth(sum * 0.25f / h / DEPTH_SPEED);
        // Al salir se dobla enseguida: en cuanto deja la pantalla completa (away sube a tope en
        // el primer 12 % del recorrido), sin esperar a coger velocidad; un gesto fuerte la
        // dobla más (speed). Al abrir, solo con la velocidad: se va enderezando al frenar.
        float away = smooth((1f - pw) / 0.12f) * 0.85f;
        out.tiltTarget = DEPTH_TILT * amt * lerp(speed, Math.max(speed, away), dir);
        // Al abrir sale ya un poco inclinada (muy sutil): el muelle empieza ahí.
        out.tiltStart = DEPTH_TILT_START * amt;
        // Pegada al icono, plana (al cerrar); a pantalla completa, plana (al abrir).
        out.tiltEnv = lerp(1f, smooth((pw - 0.04f) / 0.16f), dir) * lerp(smooth((1f - pw) / 0.05f), 1f, dir);
        // Hueco: el borde de abajo, que se acerca, nunca más grande que la pantalla. Al girar
        // sobre el centro, abajo se ve 1/(1 − k/2) de grande; cabe si k ≤ 2·(1 − tamaño/pantalla).
        // Así, a pantalla completa es plana y se puede doblar más cuanto más pequeña es.
        float fill = Math.max(cur.width() / Math.max(1f, full.width()), cur.height() / h);
        out.tiltRoom = Math.max(0f, 2f * (1f - fill));
        applyTilt(out, out.tiltTarget);
    }

    /**
     * Pone la hoja inclinada {@code a}: cuánto más lejos está arriba que abajo, en fracción de la
     * distancia a la cámara (+ = arriba al fondo), sin pasar del hueco ({@code tiltRoom}).
     * Rotación rígida sobre el centro: z = inclinación·y0, lineal (ver GlRenderer.PERSP); el
     * centro se queda a la distancia del rectángulo de MIUI, arriba se aleja y abajo se acerca.
     * Sin empuje: si la ventana entera se alejara al inclinarse, se vería encoger y volver a
     * crecer (un zoom raro).
     */
    private static void applyTilt(GlRenderer.Geo g, float a) {
        float hh = Math.max(1f, g.cur.height() * 0.5f);
        float k = Math.max(-g.tiltRoom, Math.min(g.tiltRoom, a * g.tiltEnv));
        g.tilt = -k / (2f * hh);                            // y0 < 0 es arriba: allí z = +k/2
        g.curl = 0f;
        g.push = 0f;
    }

    /**
     * Forma del genio a partir del rectángulo de MIUI ({@code cur}, se modifica): extremo lejano
     * adelantado y cercano retrasado, en horizontal (shader) y en vertical (aquí, como
     * desplazamiento sobre el rectángulo: con fuerza 0 es el de MIUI), más la lámpara.
     */
    private static void shape(RectF cur, RectF icon, RectF full, float dir, float ramp, float lift,
                              float radius, float bend, float flip, boolean iconReady, float[] vel, GlRenderer.Geo out) {
        float pw = progress(cur.width(), icon.width(), full.width());
        float ph = progress(cur.height(), icon.height(), full.height());
        float g = strength * ramp;
        // Curvatura y estela solo en Profundidad: en los demás estilos, a cero.
        out.tilt = out.curl = out.push = 0f;
        java.util.Arrays.fill(out.vel, 0f);
        if (style == STYLE_DEPTH) {
            depth(cur, icon, full, dir, ramp, radius, iconReady, vel, pw, out);
            return;
        }
        if (style != STYLE_CLASSIC) {
            // Como Samsung (vídeo a cámara lenta): esté donde esté el icono, al abrir lo primero
            // sube rápido el borde de arriba y, al llegar, baja el resto; al cerrar, abajo entra
            // antes en el icono y arriba lo sigue. La copa nunca se da la vuelta: lo que cambia con
            // la altura del icono es cuánto recorre cada extremo (icono alto: arriba llega en
            // seguida y abajo baja mucho; en el dock, al revés).
            flip = 0f;
            boolean ice = style == STYLE_ICE;
            float cl = 1f + CUP_LEAD * (ice ? ICE_LEAD : 1f) * g, cg = 1f + CUP_LAG * (ice ? ICE_LAG : 1f) * g;
            float vg = 1f + (cg - 1f) * 0.12f;
            float env = smooth(ph / CUP_FADE);
            float far = (1f - (float) Math.pow(1f - ph, cl) - ph) * env, near = ((float) Math.pow(ph, vg) - ph) * env;
            cur.top += (full.top - icon.top) * lerp(far, near, flip) + lift * (1f - flip);
            cur.bottom += (full.bottom - icon.bottom) * lerp(near, far, flip);
            float mh = Math.max(8f, icon.height() * 0.5f);
            if (cur.bottom - cur.top < mh) {
                float c = lerp(cur.bottom - mh, cur.top, flip);
                cur.top = c;
                cur.bottom = c + mh;
            }
            out.cur.set(cur);
            out.icon.set(icon);
            out.pw = pw;
            out.lead = cl;
            out.lag = cg;
            out.cup = 1f;
            out.radius = radius;
            out.iconMix = iconMix(pw, dir, iconReady);
            out.splashIcon = 0f;
            out.bend = bend;
            out.flip = flip;
            return;
        }
        out.cup = 0f;
        float lead = 1f / (1f + LEAD * g), lag = 1f + LAG * g;
        // Cerca del icono la deformación se apaga (como en la copa, CUP_FADE): q^lead tiene
        // pendiente infinita en 0 y, sin esto, al empezar a abrir el extremo lejano saltaba de
        // golpe y al acabar de cerrar el genio llegaba al icono con un tirón. El shader hace lo mismo.
        float env = smooth(ph / CUP_FADE);
        float far = ((float) Math.pow(ph, lead) - ph) * env, near = ((float) Math.pow(ph, lag) - ph) * env;
        far *= lerp(1f, CLOSE_RISE, dir);
        // El extremo lejano (arriba, o abajo si el icono está arriba) se adelanta; el cercano se retrasa.
        cur.top += (full.top - icon.top) * lerp(far, near, flip) + lift * (1f - flip);
        cur.bottom += (full.bottom - icon.bottom) * lerp(near, far, flip);
        float minH = Math.max(8f, icon.height() * 0.5f);
        if (cur.bottom - cur.top < minH) {
            float c = lerp(cur.bottom - minH, cur.top, flip);
            cur.top = c;
            cur.bottom = c + minH;
        }
        out.cur.set(cur);
        out.icon.set(icon);
        out.pw = pw;
        out.lead = lead;
        out.lag = lag;
        out.radius = radius;
        out.iconMix = iconMix(pw, dir, iconReady);
        out.splashIcon = 0f;
        out.bend = bend;
        out.flip = flip;
    }

    /**
     * Cuánto es icono (1) y cuánto app (0) el genio con esta apertura; {@code dir} 0 = abrir,
     * 1 = cerrar (en medio, al interrumpir). Ver {@link #OPEN_MIX_START}.
     */
    private static float iconMix(float pw, float dir, boolean iconReady) {
        if (!iconReady) return 0f;
        float start = lerp(OPEN_MIX_START, CLOSE_MIX_START, dir), end = lerp(OPEN_MIX_END, CLOSE_MIX_END, dir);
        return 1f - smooth((pw - end) / (start - end));
    }

    /** Copa al derecho (0, icono en la parte de abajo) o al revés (1, icono arriba). */
    private static float flipFor(RectF icon, RectF full) {
        float y = (icon.centerY() - full.top) / Math.max(1f, full.height());
        return smooth((FLIP_HI - y) / (FLIP_HI - FLIP_LO));
    }

    /** Hilo de dibujo: el genio suelto termina; su icono real vuelve y su contenido se libera. */
    private static void finishGhost() {
        final Ghost g = ghost;
        if (g == null) return;
        ghost = null;
        final View v = g.iconView == null ? null : g.iconView.get();
        final Icons ic = icons;
        if (v != null && ic != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    ic.setVisible(v, true);
                }
            });
        }
        renderHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ghost == null && session == null && ending == null) {
                    SurfaceControl l = GlRenderer.layer;
                    if (l != null && l.isValid()) {
                        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                        t.setVisibility(l, false);
                        t.apply();
                    }
                }
                GlRenderer.release(g.content);
            }
        }, GHOST_HANDOFF_MS);
        Log.i(String.format(java.util.Locale.ROOT, "genio suelto: llegó al icono en %d ms",
                (System.nanoTime() - g.startNs) / 1_000_000L));
    }

    /**
     * La app que se cierra mientras se abre otra: MIUI deja su animación y el genio sigue solo
     * hasta el icono, con un muelle por cada borde que parte de la velocidad que llevaba. Se
     * dibuja en la misma capa, debajo del genio de la app que se abre. Solo hilo de dibujo.
     */
    private static final class Ghost {
        final GlRenderer.Content content;
        final RectF full, icon, cur = new RectF(), tmp = new RectF();
        final float[] pos = new float[4], vel, goal = new float[4];
        final float startRadius, iconRadius;
        final int bg;
        final boolean iconReady;
        final SurfaceControl leash;
        final java.lang.ref.WeakReference<View> iconView;
        /** Apertura del último fotograma (para el oscurecido del escritorio). */
        volatile float lastPw = 1f;
        final GlRenderer.Geo geo = new GlRenderer.Geo();
        final long startNs = System.nanoTime();
        long lastNs = startNs;
        float bend;

        Ghost(GlRenderer.Content content, RectF full, RectF cur, RectF icon, float[] vel, float radius,
              float bend, int bg, boolean iconReady, SurfaceControl leash, java.lang.ref.WeakReference<View> iconView) {
            this.content = content;
            this.full = new RectF(full);
            this.icon = new RectF(icon);
            this.vel = vel;
            this.startRadius = radius;
            this.iconRadius = icon.width() * 0.22f;
            this.bend = bend;
            this.bg = bg;
            this.iconReady = iconReady;
            this.leash = leash;
            this.iconView = iconView;
            this.lastPw = progress(cur.width(), icon.width(), full.width());
            pos[0] = cur.left;
            pos[1] = cur.top;
            pos[2] = cur.right;
            pos[3] = cur.bottom;
            goal[0] = icon.left;
            goal[1] = icon.top;
            goal[2] = icon.right;
            goal[3] = icon.bottom;
        }

        /** Avanza hasta {@code now} y lo dibuja; false si falla el dibujo. Si llega, termina. */
        boolean drawStep(long now) {
            float dt = Math.min(0.05f, Math.max(0f, (now - lastNs) / 1e9f));
            lastNs = now;
            float c = 2f * GHOST_ZETA * (float) Math.sqrt(GHOST_K);
            boolean still = true;
            for (int i = 0; i < 4; i++) {
                for (float left = dt; left > 0f; left -= 0.004f) {
                    float h = Math.min(0.004f, left);
                    vel[i] += (GHOST_K * (goal[i] - pos[i]) - c * vel[i]) * h;
                    pos[i] += vel[i] * h;
                }
                if (Math.abs(goal[i] - pos[i]) > 0.5f || Math.abs(vel[i]) > 8f) still = false;
            }
            bend *= (float) Math.exp(-dt / 0.12f);
            boolean arrived = still || now - startNs > GHOST_MAX_NS;
            if (arrived) {                                       // última imagen: justo el icono
                System.arraycopy(goal, 0, pos, 0, 4);
                bend = 0f;
            }
            cur.set(pos[0], pos[1], pos[2], pos[3]);
            float pw = progress(cur.width(), icon.width(), full.width());
            lastPw = pw;
            tmp.set(cur);
            shape(tmp, icon, full, 1f, 1f, 0f, lerp(iconRadius, startRadius, pw), bend, flipFor(icon, full), iconReady, vel, geo);
            boolean ok = GlRenderer.drawGenie(content, true, geo, full.width() * 0.5f, full.height(), bg);
            if (arrived) {
                Haptics.land();
                if (style == STYLE_ICE) IconPress.land(iconView == null ? null : iconView.get());
                finishGhost();
            }
            return ok;
        }

        /**
         * Fotogramas propios mientras no los dé otra animación: si otro genio dibujó hace nada,
         * ya pintó este; si no, este fotograma lo pinta él (y al otro con su última forma).
         */
        static void schedule() {
            android.view.Choreographer.getInstance().postFrameCallback(TICK);
        }

        private static long lastTickNs;

        private static final android.view.Choreographer.FrameCallback TICK = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                Ghost g = ghost;
                if (g == null) return;
                if (lastTickNs != 0 && frameTimeNanos - lastTickNs < periodNs()) {
                    schedule();
                    return;
                }
                lastTickNs = frameTimeNanos;
                long now = System.nanoTime();
                Session s = session;
                boolean other = s != null && s.started && !s.finishing && s.posted > 0;
                if (other && now - s.lastDrawNs < Math.max(SHARED_FRAME_NS, periodNs() + PACE_SLACK_NS * 2)) {
                    schedule();
                    return;
                }
                boolean ok = GlRenderer.beginFrame(dimFor(g.lastPw)) && g.drawStep(now);
                if (ok && other) ok = s.redraw();
                ok = ok && GlRenderer.endFrame();
                if (!ok) {
                    finishGhost();
                    return;
                }
                if (ghost == g) schedule();
            }
        };
    }

    private static float progress(float v, float from, float to) {
        return clamp((v - from) / Math.max(1f, to - from));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Como un tope en ±max, pero que se acerca a él suavemente. */
    private static float soft(float v, float max) {
        return max <= 0f ? 0f : max * (float) Math.tanh(v / max);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smooth(float v) {
        v = clamp(v);
        return v * v * (3f - 2f * v);
    }

    private Genie() {
    }
}
