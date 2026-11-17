package com.botchat.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Guarda la clave con la que se cifra la base de datos de TDLib (tu sesión de Telegram).
 *
 * La clave es un número aleatorio de 32 bytes. Nunca se guarda tal cual: se cifra con una llave AES que vive
 * dentro del Android Keystore (el chip de seguridad del teléfono, de donde esa llave no se puede sacar) y solo
 * se guarda el resultado cifrado. Qué clave usar y cuándo migrar lo decide DbKeyPolicy.
 */
public final class SecureStore {

    private static final String TAG = "MiBot";
    private static final String PREFS = "botchat_secure";
    private static final String KEY_STATE = "db_state";
    private static final String KEY_BLOB = "db_key";
    private static final String ALIAS = "botchat_db_key";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecureStore() {
    }

    public static synchronized DbKeyPolicy.State state(Context context) {
        String saved = prefs(context).getString(KEY_STATE, DbKeyPolicy.State.NONE.name());
        try {
            return DbKeyPolicy.State.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return DbKeyPolicy.State.NONE;
        }
    }

    public static synchronized void setState(Context context, DbKeyPolicy.State state) {
        prefs(context).edit().putString(KEY_STATE, state.name()).commit();
    }

    /**
     * La clave de la base (32 bytes), o null si no se puede leer.
     *
     * @param create si no hay clave guardada, crear una nueva. Debe ser false cuando la base ya está cifrada:
     *               una clave nueva no abriría esa base.
     */
    public static synchronized byte[] key(Context context, boolean create) {
        SharedPreferences prefs = prefs(context);
        String blob = prefs.getString(KEY_BLOB, null);
        try {
            if (blob != null) {
                return decrypt(Base64.decode(blob, Base64.NO_WRAP));
            }
            if (!create) {
                return null;
            }
            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            prefs.edit().putString(KEY_BLOB, Base64.encodeToString(encrypt(key), Base64.NO_WRAP)).commit();
            return key;
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            Log.e(TAG, "No se pudo usar el Android Keystore", e);
            return null;
        }
    }

    /** Olvida la clave y el estado (y borra la llave del Keystore). Solo se usa si la base se va a borrar. */
    public static synchronized void clear(Context context) {
        prefs(context).edit().clear().commit();
        try {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            if (store.containsAlias(ALIAS)) {
                store.deleteEntry(ALIAS);
            }
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            Log.e(TAG, "No se pudo borrar la llave del Keystore", e);
        }
    }

    // ---------------------------------------------------------------------------------------

    private static byte[] encrypt(byte[] plain) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
        return out;
    }

    private static byte[] decrypt(byte[] blob) throws GeneralSecurityException, IOException {
        if (blob.length <= IV_BYTES) {
            throw new GeneralSecurityException("Clave guardada dañada");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
        return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
    }

    /** La llave AES del Keystore con la que se cifra la clave de la base (se crea la primera vez). */
    private static SecretKey wrappingKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
