package com.icehome.up;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Perfiles: grupos de valores que se aplican con un toque.
 *
 * <p>Un perfil solo toca los ajustes de "cómo se sienten" las animaciones (forma, velocidad,
 * rebote, calidad). No enciende ni apaga funciones, no cambia el aspecto del cristal y no toca
 * los fotogramas por segundo (dependen de la pantalla de cada móvil). Los ajustes que un perfil
 * no nombra vuelven a su valor por defecto, para que el resultado sea siempre el mismo se venga
 * de donde se venga.
 */
final class Presets {

    /** Ajustes que gestionan los perfiles. Los demás no se tocan nunca. */
    static final String[] KEYS = {
            "icehome_genie_style", "icehome_genie_strength", "icehome_genie_quality", "icehome_genie_physics",
            "icehome_genie_live", "icehome_wallpaper_zoom", "icehome_anim_speed", "icehome_anim_bounce",
            "icehome_curtain_strength",
            "telon_strength", "telon_curve", "telon_blur", "telon_bg_blur", "telon_quality",
    };

    /** Nombre (es, en), explicación (es, en) y pares clave-valor. */
    static final Object[][] ALL = {
            {"Suave", "Smooth",
                    "Animaciones tranquilas, con formas redondeadas y un rebote leve.",
                    "Calm animations with rounded shapes and a slight bounce.",
                    new String[]{"icehome_genie_style", "2", "icehome_genie_strength", "1.6", "icehome_anim_speed", "0.85",
                            "icehome_anim_bounce", "0.05", "icehome_curtain_strength", "0.8",
                            "telon_strength", "0.8", "telon_curve", "0.6"}},
            {"Estilo iOS", "iOS-like",
                    "La ventana crece desde el icono casi sin deformarse, con rebote al llegar y más zoom del fondo.",
                    "The window grows out of the icon with little distortion, a bounce on landing and more wallpaper zoom.",
                    new String[]{"icehome_genie_style", "2", "icehome_genie_strength", "0.6", "icehome_anim_speed", "1.1",
                            "icehome_anim_bounce", "0.12", "icehome_wallpaper_zoom", "1.2", "icehome_genie_physics", "0.5",
                            "telon_strength", "0.5", "telon_blur", "0.3"}},
            {"Rápido", "Fast",
                    "Todo más deprisa y sin rebote, para quien no quiere esperar a la animación.",
                    "Everything quicker and without bounce, for those who don't want to wait for the animation.",
                    new String[]{"icehome_genie_strength", "1.5", "icehome_anim_speed", "1.4", "icehome_anim_bounce", "0",
                            "icehome_genie_physics", "0.5", "telon_strength", "0.7"}},
            {"Rendimiento", "Performance",
                    "Para móviles modestos o para gastar menos: menos resolución, sin contenido en vivo y sin desenfoques del telón.",
                    "For modest phones or to save power: lower resolution, no live content and no curtain blurs.",
                    new String[]{"icehome_genie_quality", "50", "icehome_genie_live", "0", "icehome_anim_speed", "1.2",
                            "icehome_wallpaper_zoom", "0", "telon_quality", "30", "telon_blur", "0", "telon_bg_blur", "0"}},
    };

    static String name(Object[] preset) {
        return I18n.t((String) preset[0], (String) preset[1]);
    }

    static String about(Object[] preset) {
        return I18n.t((String) preset[2], (String) preset[3]);
    }

    /** Aplica un perfil: sus valores, y por defecto el resto de {@link #KEYS}. */
    static void apply(Context c, Object[] preset) {
        SharedPreferences.Editor e = c.getSharedPreferences(PrefsProvider.PREFS, 0).edit();
        for (String k : KEYS) e.remove(k);                  // sin valor guardado = valor por defecto
        String[] kv = (String[]) preset[4];
        for (int i = 0; i + 1 < kv.length; i += 2) e.putString(kv[i], kv[i + 1]);
        e.commit();
    }

    private Presets() {
    }
}
