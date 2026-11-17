package com.botchat.app.filters;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hash de la contraseña de Filtros (PBKDF2-HMAC-SHA256 con sal). Sin nada de Android.
 * La contraseña nunca se guarda: solo la sal, el número de iteraciones y el hash.
 *
 * Es lento A PROPÓSITO (cientos de miles de iteraciones): llámalo siempre fuera del hilo principal.
 */
public final class PasswordHasher {

    /** Iteraciones para contraseñas nuevas (lo que recomienda OWASP para PBKDF2-HMAC-SHA256). */
    public static final int CURRENT_ITERATIONS = 60_000;

    /** Iteraciones que usaba la primera versión de la app: se siguen aceptando y se mejoran al entrar. */
    public static final int LEGACY_ITERATIONS = 60_000;

    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private PasswordHasher() {
    }

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** El hash de la contraseña, o null si el teléfono no soporta el algoritmo. */
    public static byte[] hash(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return null;
        } finally {
            spec.clearPassword();
        }
    }

    /** true si la contraseña coincide con el hash guardado (comparación en tiempo constante). */
    public static boolean matches(String password, byte[] salt, int iterations, byte[] expected) {
        if (salt == null || expected == null || iterations <= 0) {
            return false;
        }
        byte[] actual = hash(password, salt, iterations);
        return actual != null && MessageDigest.isEqual(actual, expected);
    }
}
