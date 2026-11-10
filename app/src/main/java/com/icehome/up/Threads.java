package com.icehome.up;

import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hilos del módulo que pueden usar todas las CPU.
 *
 * <p>En Linux, un hilo nuevo hereda las CPU en las que puede correr (su "afinidad") del hilo que
 * lo crea. Durante una animación, HyperOS ata el hilo principal del launcher y de SystemUI a los
 * dos núcleos grandes (cpu6-7 en el Redmi 14C) para que vaya rápido, y lo suelta al acabar. Los
 * hilos del genio, del telón y del cristal se creaban justo entonces, la primera vez que hacían
 * falta, y se quedaban atados a esos dos núcleos para siempre. Allí compiten con SurfaceFlinger,
 * que los tiene casi llenos durante una animación: en una traza, el hilo del genio pasaba casi
 * 2 s de cada 4,5 s esperando un núcleo libre y solo 0,13 s trabajando. Eso eran los tirones.
 *
 * <p>Arreglo: todos se crean desde un hilo "semilla", creado al arrancar el proceso (antes de
 * cualquier animación) y que nunca hace nada más, así que conserva todas las CPU. El sistema sigue
 * pudiendo llevar nuestros hilos a los núcleos grandes cuando hay hueco; solo deja de estar
 * prohibido usar los demás.
 */
public final class Threads {

    private static HandlerThread seed;
    private static int seedCpus;

    /** Al cargar el módulo en un proceso (hilo principal, aún sin animaciones): crea la semilla. */
    public static synchronized void init() {
        if (seed != null) return;
        seed = new HandlerThread("IceHome-seed");
        seed.start();
        seed.getLooper();                                   // espera a que el hilo exista
        seedCpus = cpus(seed.getThreadId());
    }

    /**
     * Un hilo con su Handler, creado desde el hilo con más CPU permitidas: el que llama o la
     * semilla. Nunca bloquea más de unos milisegundos.
     */
    public static Handler start(final String name, final int priority) {
        HandlerThread from;
        synchronized (Threads.class) {
            int mine = cpus(android.os.Process.myTid());
            // Si el que llama tiene más CPU que la semilla (p. ej. la semilla nació atada), se
            // renueva desde aquí: así nunca se queda la peor de las dos.
            if (seed == null || mine > seedCpus) {
                if (seed != null) seed.quitSafely();
                seed = null;
                init();
            }
            from = mine >= seedCpus ? null : seed;
        }
        if (from == null) return create(name, priority);
        final AtomicReference<Handler> made = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = new Handler(from.getLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    made.set(create(name, priority));
                } finally {
                    done.countDown();
                }
            }
        });
        if (posted) {
            try {
                done.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Handler h = made.get();
        // Respaldo: si la semilla no respondió, se crea aquí (atado quizá, pero funciona).
        return h != null ? h : create(name, priority);
    }

    private static Handler create(String name, int priority) {
        HandlerThread t = new HandlerThread(name, priority);
        t.start();
        return new Handler(t.getLooper());
    }

    /** Para el registro: «IceHome-genie en CPU 0-7». */
    public static String describe(Handler h) {
        try {
            Thread t = h.getLooper().getThread();
            int tid = t instanceof HandlerThread ? ((HandlerThread) t).getThreadId() : -1;
            return t.getName() + " en CPU " + allowed(tid);
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Cuántas CPU puede usar el hilo {@code tid} de este proceso (0 si no se sabe). */
    static int cpus(int tid) {
        String list = allowed(tid);
        int n = 0;
        try {
            for (String part : list.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int dash = part.indexOf('-');
                if (dash < 0) n++;
                else n += Integer.parseInt(part.substring(dash + 1)) - Integer.parseInt(part.substring(0, dash)) + 1;
            }
        } catch (Throwable e) {
            return 0;
        }
        return n;
    }

    /** "Cpus_allowed_list" de /proc para un hilo de este proceso, p. ej. "0-7" o "6-7". */
    private static String allowed(int tid) {
        if (tid <= 0) return "?";
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/task/" + tid + "/status"));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("Cpus_allowed_list:")) return l.substring(18).trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "?";
    }

    private Threads() {
    }
}
