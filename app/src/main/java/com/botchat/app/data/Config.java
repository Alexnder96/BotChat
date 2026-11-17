package com.botchat.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuración del bot. Ya NO se edita en el código: se llena dentro de la propia app
 * (la primera vez que se abre, y después desde el menú ⋮ → Configuración).
 *
 * Los datos quedan guardados en el teléfono (SharedPreferences privadas de la app).
 */
public final class Config {

    /** Título que se muestra arriba del chat si no escribes otro. */
    public static final String DEFAULT_TITLE = "CHAT BOT";

    /** Frases que NO quieres ver en las respuestas del bot (se quitan del texto del mensaje). */
    public static final String[] HIDDEN_PHRASES = {
    };

    private static final String PREFS = "botchat_config";
    private static final String KEY_API_ID = "api_id";
    private static final String KEY_API_HASH = "api_hash";
    private static final String KEY_BOT = "bot_username";
    private static final String KEY_SHOW_TITLE = "show_title";
    private static final String KEY_TITLE = "title";
    private static final String KEY_SECURE_CHAT = "secure_chat";

    private static boolean loaded;
    private static volatile int apiId;
    private static volatile String apiHash = "";
    private static volatile String bot = "";
    private static volatile boolean showTitle = true;
    private static volatile String title = DEFAULT_TITLE;
    private static volatile boolean secureChat;

    private Config() {
    }

    /** Lee lo guardado. Se puede llamar las veces que sea (solo lee la primera). */
    public static synchronized void load(Context context) {
        if (loaded) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        apiId = prefs.getInt(KEY_API_ID, 0);
        apiHash = prefs.getString(KEY_API_HASH, "").trim();
        bot = cleanUsername(prefs.getString(KEY_BOT, ""));
        showTitle = prefs.getBoolean(KEY_SHOW_TITLE, true);
        title = prefs.getString(KEY_TITLE, DEFAULT_TITLE);
        secureChat = prefs.getBoolean(KEY_SECURE_CHAT, false);
        loaded = true;
    }

    /** api_id de my.telegram.org (0 si todavía no se configuró). */
    public static int apiId() {
        return apiId;
    }

    /** api_hash de my.telegram.org. */
    public static String apiHash() {
        return apiHash;
    }

    /** Usuario del bot, sin @. */
    public static String botUsername() {
        return bot;
    }

    /** true si arriba del chat se muestra el título del bot. */
    public static boolean showTitle() {
        return showTitle;
    }

    /** Texto del título del bot. */
    public static String title() {
        return title == null || title.trim().isEmpty() ? DEFAULT_TITLE : title;
    }

    /** true si en el chat se bloquean las capturas de pantalla (y la miniatura en "recientes"). */
    public static boolean secureChat() {
        return secureChat;
    }

    /** true cuando ya están los tres datos obligatorios. */
    public static boolean isComplete() {
        return apiId > 0 && !apiHash.isEmpty() && !bot.isEmpty();
    }

    public static synchronized void saveConnection(Context context, int newApiId, String newApiHash, String newBot) {
        apiId = newApiId;
        apiHash = newApiHash == null ? "" : newApiHash.trim();
        bot = cleanUsername(newBot);
        prefs(context).edit()
                .putInt(KEY_API_ID, apiId)
                .putString(KEY_API_HASH, apiHash)
                .putString(KEY_BOT, bot)
                .apply();
    }

    public static synchronized void saveTitle(Context context, boolean show, String text) {
        showTitle = show;
        title = text == null || text.trim().isEmpty() ? DEFAULT_TITLE : text.trim();
        prefs(context).edit()
                .putBoolean(KEY_SHOW_TITLE, showTitle)
                .putString(KEY_TITLE, title)
                .apply();
    }

    public static synchronized void saveSecureChat(Context context, boolean secure) {
        secureChat = secure;
        prefs(context).edit().putBoolean(KEY_SECURE_CHAT, secure).apply();
    }

    /** Borra toda la configuración (API ID, API Hash, usuario del bot y título). Lo usa "Restablecer el bot". */
    public static synchronized void clear(Context context) {
        apiId = 0;
        apiHash = "";
        bot = "";
        showTitle = true;
        title = DEFAULT_TITLE;
        secureChat = false;
        prefs(context).edit().clear().apply();
    }

    /** Usuario del bot limpio: acepta "@bot", "t.me/bot" o "https://t.me/bot". */
    public static String cleanUsername(String raw) {
        String name = raw == null ? "" : raw.trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        return name.trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
