package com.icehome.up;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Apps excluidas del genio: se abren y se cierran con la animación normal de HyperOS.
 * <p>
 * Sirve para juegos, la cámara o apps de vídeo, donde lo que quieres es que la app salga ya, sin
 * adornos. Se guarda en un solo ajuste de texto con los paquetes separados por comas
 * ({@code com.android.camera,com.whatsapp}), y llega al launcher igual que los demás ajustes.
 */
final class Exclude {

    /** Clave del ajuste. No está en Tune.ITEMS porque no es un número. */
    static final String KEY = "icehome_exclude";

    /** Lo que usa el launcher. Se sustituye entero al recargar, así no hace falta sincronizar hilos. */
    static volatile Set<String> genie = Collections.emptySet();

    /** Lee el texto guardado (null = ninguna). */
    static Set<String> parse(String saved) {
        Set<String> out = new HashSet<>();
        if (saved == null) return out;
        for (String p : saved.split(",")) {
            p = p.trim();
            if (valid(p)) out.add(p);
        }
        return out;
    }

    /** El texto que se guarda. */
    static String join(Set<String> pkgs) {
        StringBuilder b = new StringBuilder();
        for (String p : pkgs) {
            if (!valid(p)) continue;
            if (b.length() > 0) b.append(',');
            b.append(p);
        }
        return b.toString();
    }

    /**
     * Un nombre de paquete normal: letras, números, puntos y guiones bajos. Así un archivo de
     * ajustes importado o tocado a mano no puede meter nada raro.
     */
    static boolean valid(String p) {
        if (p == null || p.isEmpty() || p.length() > 200) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_')) return false;
        }
        return true;
    }

    /** ¿Esta app va sin genio? */
    static boolean genie(String pkg) {
        return pkg != null && genie.contains(pkg);
    }

    private Exclude() {
    }
}
