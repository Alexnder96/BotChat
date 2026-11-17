package com.botchat.app.filters;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Filtros del usuario: qué mensajes del bot NO se muestran, y la contraseña que protege esa pantalla.
 *
 * Esta clase solo GUARDA los datos (en el teléfono) y los entrega a quien los pida. Las reglas en sí están en
 * FilterRules y TextRules, y el hash de la contraseña en PasswordHasher y Lockout (todas sin Android y con tests).
 *
 * Hay dos listas:
 *  - Palabras clave: se QUITAN del texto y de los pies de foto (el resto del mensaje se ve igual).
 *  - Imágenes por nombre: se ocultan las fotos/archivos cuyo nombre de archivo o pie de foto contiene el texto.
 *    Solo se oculta la foto o el archivo: el pie de foto (y los botones y la hora) se quedan.
 *
 * Las comparaciones ignoran mayúsculas y tildes ("Camión" coincide con "camion").
 * La contraseña nunca se guarda: solo un hash con sal (PBKDF2).
 */
public final class Filters {

    /** Resultados de checkPassword. */
    public static final int PASSWORD_OK = 0;
    public static final int PASSWORD_WRONG = 1;
    public static final int PASSWORD_LOCKED = 2;

    public static final int MIN_PASSWORD = 4;

    private static final String PREFS = "botchat_filters";
    private static final String KEY_HASH = "password_hash";
    private static final String KEY_SALT = "password_salt";
    private static final String KEY_ITERATIONS = "password_iterations";
    private static final String KEY_FAILS = "password_fails";
    private static final String KEY_LAST_FAIL = "password_last_fail";
    private static final String KEY_WORDS = "keywords";
    private static final String KEY_IMAGES = "image_names";

    private static SharedPreferences prefs;
    private static volatile List<String> words = new ArrayList<>();
    private static volatile List<String> images = new ArrayList<>();
    private static volatile FilterRules rules = FilterRules.EMPTY;

    private Filters() {
    }

    public static synchronized void load(Context context) {
        if (prefs != null) {
            return;
        }
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        words = readList(KEY_WORDS);
        images = readList(KEY_IMAGES);
        rebuild();
    }

    // ---------------------------------------------------------------------------------------
    // Contraseña
    // ---------------------------------------------------------------------------------------

    public static synchronized boolean hasPassword() {
        return prefs != null && prefs.getString(KEY_HASH, null) != null;
    }

    /** Guarda una contraseña nueva. Es lento (hash): llamar fuera del hilo principal. */
    public static void setPassword(String password) {
        byte[] salt = PasswordHasher.newSalt();
        byte[] hash = PasswordHasher.hash(password, salt, PasswordHasher.CURRENT_ITERATIONS);
        if (hash == null) {
            return;
        }
        synchronized (Filters.class) {
            storeHash(salt, hash, PasswordHasher.CURRENT_ITERATIONS);
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LAST_FAIL, 0).commit();
        }
    }

    /**
     * Comprueba la contraseña: PASSWORD_OK, PASSWORD_WRONG o PASSWORD_LOCKED (demasiados errores seguidos: ver
     * Lockout y lockRemainingMs). Es lento (hash): llamar fuera del hilo principal.
     * Si la contraseña era correcta y su hash usaba menos iteraciones que las actuales, se guarda mejorado.
     */
    public static int checkPassword(String password) {
        byte[] salt;
        byte[] expected;
        int iterations;
        synchronized (Filters.class) {
            String saltText = prefs.getString(KEY_SALT, null);
            String hashText = prefs.getString(KEY_HASH, null);
            if (saltText == null || hashText == null) {
                return PASSWORD_WRONG;
            }
            if (lockRemainingMs() > 0) {
                return PASSWORD_LOCKED;
            }
            salt = Base64.decode(saltText, Base64.NO_WRAP);
            expected = Base64.decode(hashText, Base64.NO_WRAP);
            iterations = prefs.getInt(KEY_ITERATIONS, PasswordHasher.LEGACY_ITERATIONS);
        }

        boolean ok = PasswordHasher.matches(password, salt, iterations, expected);

        byte[] upgradedSalt = null;
        byte[] upgradedHash = null;
        if (ok && iterations != PasswordHasher.CURRENT_ITERATIONS) {
            upgradedSalt = PasswordHasher.newSalt();
            upgradedHash = PasswordHasher.hash(password, upgradedSalt, PasswordHasher.CURRENT_ITERATIONS);
        }

        synchronized (Filters.class) {
            if (ok) {
                prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LAST_FAIL, 0).commit();
                if (upgradedHash != null) {
                    storeHash(upgradedSalt, upgradedHash, PasswordHasher.CURRENT_ITERATIONS);
                }
                return PASSWORD_OK;
            }
            prefs.edit()
                    .putInt(KEY_FAILS, prefs.getInt(KEY_FAILS, 0) + 1)
                    .putLong(KEY_LAST_FAIL, System.currentTimeMillis())
                    .commit();
            return lockRemainingMs() > 0 ? PASSWORD_LOCKED : PASSWORD_WRONG;
        }
    }

    /** Milisegundos que faltan para poder volver a intentar la contraseña (0 si ya se puede). */
    public static synchronized long lockRemainingMs() {
        if (prefs == null) {
            return 0;
        }
        return Lockout.remainingMs(prefs.getInt(KEY_FAILS, 0), prefs.getLong(KEY_LAST_FAIL, 0),
                System.currentTimeMillis());
    }

    private static void storeHash(byte[] salt, byte[] hash, int iterations) {
        prefs.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(KEY_ITERATIONS, iterations)
                .commit();
    }

    /** "Restablecer el bot": borra la contraseña y todos los filtros. */
    public static synchronized void resetAll() {
        prefs.edit().clear().commit();
        words = new ArrayList<>();
        images = new ArrayList<>();
        rebuild();
    }

    // ---------------------------------------------------------------------------------------
    // Listas
    // ---------------------------------------------------------------------------------------

    public static List<String> keywords() {
        return Collections.unmodifiableList(words);
    }

    public static List<String> imageNames() {
        return Collections.unmodifiableList(images);
    }

    /** Devuelve false si estaba vacío o ya existía. */
    public static synchronized boolean addKeyword(String value) {
        List<String> updated = withAdded(words, value);
        if (updated == null) {
            return false;
        }
        words = updated;
        save(KEY_WORDS, words);
        rebuild();
        return true;
    }

    public static synchronized void removeKeyword(String value) {
        words = without(words, value);
        save(KEY_WORDS, words);
        rebuild();
    }

    public static synchronized boolean addImageName(String value) {
        List<String> updated = withAdded(images, value);
        if (updated == null) {
            return false;
        }
        images = updated;
        save(KEY_IMAGES, images);
        rebuild();
        return true;
    }

    public static synchronized void removeImageName(String value) {
        images = without(images, value);
        save(KEY_IMAGES, images);
        rebuild();
    }

    private static List<String> withAdded(List<String> list, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            return null;
        }
        String key = TextRules.normalize(clean);
        for (String existing : list) {
            if (TextRules.normalize(existing).equals(key)) {
                return null;
            }
        }
        List<String> updated = new ArrayList<>(list);
        updated.add(clean);
        return updated;
    }

    private static List<String> without(List<String> list, String value) {
        List<String> updated = new ArrayList<>(list);
        updated.remove(value);
        return updated;
    }

    private static void rebuild() {
        rules = FilterRules.of(words, images);
    }

    private static List<String> readList(String key) {
        List<String> result = new ArrayList<>();
        String json = prefs.getString(key, null);
        if (json == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                result.add(array.getString(i));
            }
        } catch (JSONException ignored) {
            // Si el dato estuviera dañado se empieza con la lista vacía.
        }
        return result;
    }

    private static void save(String key, List<String> list) {
        prefs.edit().putString(key, new JSONArray(list).toString()).apply();
    }

    // ---------------------------------------------------------------------------------------
    // Reglas vigentes (atajos a FilterRules)
    // ---------------------------------------------------------------------------------------

    /**
     * Las reglas vigentes. Es un objeto inmutable: si el usuario cambia una lista, aquí aparece OTRO objeto,
     * así que quien guarde decisiones puede compararlo con == para saber si siguen siendo válidas.
     */
    public static FilterRules rules() {
        return rules;
    }

    public static boolean hasImageNames() {
        return rules.hasImages();
    }

    public static boolean shouldHide(TdApi.Message message) {
        return rules.shouldHide(message);
    }

    public static boolean mediaMatches(TdApi.Message message) {
        return rules.mediaMatches(message);
    }

    public static boolean matchesPhotoFile(String path) {
        return rules.matchesPhotoFile(path);
    }

    public static List<int[]> ranges(String text) {
        return rules.ranges(text);
    }

    public static String strip(String text) {
        return rules.strip(text);
    }
}
