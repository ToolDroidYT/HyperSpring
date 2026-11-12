package com.icehome.up;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Textos de la app en español e inglés. Por defecto sigue el idioma del sistema (español para
 * es/ca/gl/eu, inglés para el resto); en «Acerca de» se puede fijar uno. Los ajustes (Tune.ITEMS)
 * tienen el texto en español en el propio código; aquí está su traducción por clave.
 */
final class I18n {

    static final String PREF = "lang";               // "auto", "es" o "en" (en las preferencias de la app)
    static boolean en;

    static void init(Context c) {
        String mode = c.getSharedPreferences("app", 0).getString(PREF, "auto");
        if ("es".equals(mode)) en = false;
        else if ("en".equals(mode)) en = true;
        else {
            String l = Locale.getDefault().getLanguage();
            en = !(l.equals("es") || l.equals("ca") || l.equals("gl") || l.equals("eu"));
        }
    }

    static String mode(Context c) {
        return c.getSharedPreferences("app", 0).getString(PREF, "auto");
    }

    static void setMode(Context c, String mode) {
        c.getSharedPreferences("app", 0).edit().putString(PREF, mode).commit();
    }

    static String t(String es, String english) {
        return en ? english : es;
    }

    /** Título de un ajuste. */
    static String title(Object[] it) {
        if (!en) return (String) it[1];
        String[] e = ITEMS.get((String) it[0]);
        return e != null ? e[0] : (String) it[1];
    }

    /** Explicación de un ajuste (o null). */
    static String desc(Object[] it) {
        String es = it.length > 6 && it[6] instanceof String ? (String) it[6] : null;
        if (!en || es == null) return es;
        String[] e = ITEMS.get((String) it[0]);
        return e != null && e.length > 1 ? e[1] : es;
    }

    /** Nombre de una sección (las secciones se identifican por su nombre en español). */
    static String section(String es) {
        if (!en) return es;
        String e = SECTIONS.get(es);
        return e != null ? e : es;
    }

    private static final Map<String, String> SECTIONS = new HashMap<>();
    private static final Map<String, String[]> ITEMS = new HashMap<>();

    private static void s(String es, String english) {
        SECTIONS.put(es, english);
    }

    private static void i(String key, String title, String desc) {
        ITEMS.put(key, new String[]{title, desc});
    }

    static {
        s("Funciones", "Features");
        s("Abrir y cerrar apps", "Opening and closing apps");
        s("Recientes", "Recents");
        s("Carpetas", "Folders");
        s("Función", "Feature");
        s("Telón", "Curtain");
        s("General", "General");
        s("Interior del cristal", "Inside the glass");
        s("Refracción del borde", "Edge refraction");
        s("Canto y línea de luz", "Edge and light rim");
        s("Fondo", "Background");
        s("Ajustes", "Settings");

        i("icehome_genie_on", "Genie effect when opening and closing apps",
                "The window comes out of its icon and goes back into it in a cup or genie shape. Applies after restarting the launcher.");
        i("icehome_stack_on", "Widget stack",
                "Stacked widgets shuffle with a gesture and can be dragged freely. Applies after restarting the launcher.");
        i("icehome_folder_morph_on", "Springy folders",
                "Folders resize with a soft bounce when they open or close. Applies after restarting the launcher.");
        i("icehome_folder_glass_on", "Glass folders",
                "Open folders get a blurred glass background instead of a flat colour. Applies after restarting the launcher.");
        i("icehome_curtain_on", "Bending recents",
                "In recents, cards bend the other way depending on how hard you swipe them. Applies after restarting the launcher.");
        i("icehome_genie_style", "Style",
                "0 = classic genie with a tail. 1 = Samsung-like cup: the top rises first, then the rest follows. 2 = Ice: a softer cup with a bounce on open, the icon catching the cup and the home screen dimming behind. 3 = Depth: no genie; the normal HyperOS animation, but the window is a rigid sheet that tilts (top away), more the harder you close it, and leaves a motion blur trail. Strength sets the tilt and the blur.");
        i("icehome_genie_strength", "Strength",
                "How much the window bends. 0 is a plain rectangle; 1.8–2.2 looks like Samsung.");
        i("icehome_genie_quality", "Resolution",
                "Sharpness of the animation. Higher looks sharper but uses more GPU; on modest phones, 50–60 %.");
        i("icehome_genie_physics", "Drag physics",
                "When you close an app with your finger, the window swings like a hanging lamp. 0 keeps it rigid.");
        i("icehome_genie_fps", "Frames per second",
                "Maximum frame rate of the animation. Set it to your screen's refresh rate (60 if it doesn't go above 60 Hz) to avoid wasted work.");
        i("icehome_genie_live", "Live content",
                "While opening, the app keeps updating as it grows. Off saves GPU work and the image stays still until the end.");
        i("icehome_press_on", "Icons sink when pressed",
                "Pressing an icon shrinks it a little and it bounces back when released.");
        i("icehome_wallpaper_zoom", "Wallpaper zoom",
                "The wallpaper zooms in when an app opens and out when you go back. 0 keeps the HyperOS behaviour.");
        i("icehome_haptic", "Haptic on arrival",
                "A very soft vibration when the window lands in its icon. 0 turns it off. Follows the system vibration setting.");
        i("icehome_anim_speed", "Speed",
                "Speed of the open and close animations. Above 1 is faster.");
        i("icehome_anim_bounce", "Bounce",
                "How far the window overshoots before settling. 0 lands without bouncing.");
        i("icehome_curtain_strength", "Bend",
                "How much recents cards bend depending on your finger's speed.");
        i("icehome_curtain_style", "Curtain style",
                "On: the top stays fixed and the bottom bends, like a stage curtain. Off: the middle bends, like a drape.");
        i("icehome_folder_blur", "Blur",
                "How much the content behind an open folder is blurred, in dp.");

        i("telon_on", "Curtain when switching screens",
                "Inside an app, the window coming in or going out tilts with its speed and the one below blurs. Applies after restarting System UI.");
        i("telon_strength", "Tilt",
                "How much the window tilts depending on how fast it moves. 0 keeps it straight.");
        i("telon_curve", "Shape",
                "0 = straight tilt, like a parallelogram. 1 = curved like a curtain, with the bottom bending more.");
        i("telon_blur", "Motion trail",
                "Blur along the direction of movement when the window moves fast. 0 turns it off.");
        i("telon_bg_blur", "Blur the screen below",
                "The screen behind blurs while the new one comes in and sharpens again when you go back. 0 turns it off.");
        i("telon_fps", "Frames per second",
                "Maximum frame rate of the curtain. Set it to your screen's refresh rate.");
        i("telon_quality", "Resolution",
                "Sharpness of the window during the curtain. Higher looks sharper but costs more.");

        i("kashiplus_cc_on", "Glass control center",
                "Control center tiles and buttons become liquid glass. Tested with the ModsCenter iOS control center and the stock HyperOS one.");
        i("kashiplus_cc_blur", "Blur",
                "How much what you see through the tiles is blurred, in dp.");
        i("kashiplus_cc_sat", "Tile saturation",
                "Colour intensity seen through the large tiles. 1 = unchanged.");
        i("kashiplus_cc_sat_button", "Button saturation",
                "The same for the small round buttons.");
        i("kashiplus_cc_tone", "Light",
                "Glass brightness. Above 1 lightens it; below 1 darkens it.");
        i("kashiplus_cc_white", "White veil",
                "A very subtle white layer on the glass so text reads better over dark backgrounds.");
        i("kashiplus_cc_gloss", "Gloss",
                "A soft reflection at the top of each tile.");
        i("kashiplus_cc_magnify", "Magnifier",
                "Magnifies what you see through the centre of the tile, like a thick lens.");
        i("kashiplus_cc_refr", "Refraction",
                "How much the background bends at the edge of each tile, in dp. This is the AndroidLiquidGlass lens.");
        i("kashiplus_cc_refr_h", "Refraction width",
                "How far the refraction reaches inwards from the edge, in dp. Small buttons limit it automatically.");
        i("kashiplus_cc_chroma", "Colour dispersion",
                "Rainbow-like colour fringing at the corners, like a prism. 0 turns it off.");
        i("kashiplus_cc_edge", "Lens edge",
                "Thickness of each tile's lit edge, in dp.");
        i("kashiplus_cc_edge_over", "Edge colour",
                "The edge takes the colour of what is just outside the tile, up to this distance in dp.");
        i("kashiplus_cc_edge_sat", "Edge saturation",
                "Intensity of the edge colour.");
        i("kashiplus_cc_rim", "Light rim width",
                "The bright line running along the edge, in dp.");
        i("kashiplus_cc_rim_gain", "Light rim strength",
                "Brightness of that line. 0 turns it off.");
        i("kashiplus_cc_dim", "Dim the background",
                "How much the screen behind the control center is darkened.");
    }

    private I18n() {
    }
}
