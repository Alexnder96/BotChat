package com.botchat.app.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class PasswordHasherTest {

    private static final int FAST = 1000;   // para que la prueba no tarde

    @Test
    public void matches_laContrasenaCorrectaCoincide() {
        byte[] salt = PasswordHasher.newSalt();
        byte[] hash = PasswordHasher.hash("clave segura", salt, FAST);
        assertNotNull(hash);
        assertEquals(32, hash.length);
        assertTrue(PasswordHasher.matches("clave segura", salt, FAST, hash));
    }

    @Test
    public void matches_unaContrasenaIncorrectaNoCoincide() {
        byte[] salt = PasswordHasher.newSalt();
        byte[] hash = PasswordHasher.hash("clave segura", salt, FAST);
        assertFalse(PasswordHasher.matches("clave segura ", salt, FAST, hash));
        assertFalse(PasswordHasher.matches("Clave segura", salt, FAST, hash));
        assertFalse(PasswordHasher.matches("", salt, FAST, hash));
    }

    @Test
    public void matches_conOtroNumeroDeIteracionesNoCoincide() {
        byte[] salt = PasswordHasher.newSalt();
        byte[] hash = PasswordHasher.hash("clave", salt, FAST);
        assertFalse(PasswordHasher.matches("clave", salt, FAST + 1, hash));
    }

    @Test
    public void hash_laSalDistintaDaHashDistinto() {
        byte[] hashA = PasswordHasher.hash("clave", PasswordHasher.newSalt(), FAST);
        byte[] hashB = PasswordHasher.hash("clave", PasswordHasher.newSalt(), FAST);
        assertFalse(Arrays.equals(hashA, hashB));
    }

    @Test
    public void hash_esDeterministaConLaMismaSal() {
        byte[] salt = PasswordHasher.newSalt();
        assertTrue(Arrays.equals(PasswordHasher.hash("clave", salt, FAST), PasswordHasher.hash("clave", salt, FAST)));
    }

    @Test
    public void matches_datosFaltantesNoCoinciden() {
        assertFalse(PasswordHasher.matches("x", null, FAST, new byte[32]));
        assertFalse(PasswordHasher.matches("x", PasswordHasher.newSalt(), FAST, null));
        assertFalse(PasswordHasher.matches("x", PasswordHasher.newSalt(), 0, new byte[32]));
    }

    @Test
    public void iteracionesActualesSonMasAltasQueLasDeLaPrimeraVersion() {
        assertTrue(PasswordHasher.CURRENT_ITERATIONS >= 600_000);
        assertTrue(PasswordHasher.CURRENT_ITERATIONS > PasswordHasher.LEGACY_ITERATIONS);
    }

    @Test
    public void unaContrasenaAntiguaSigueFuncionandoYSePuedeMejorar() {
        // Hash como lo guardaba la primera versión (60 000 iteraciones)...
        byte[] oldSalt = PasswordHasher.newSalt();
        byte[] oldHash = PasswordHasher.hash("mi clave", oldSalt, PasswordHasher.LEGACY_ITERATIONS);
        assertTrue(PasswordHasher.matches("mi clave", oldSalt, PasswordHasher.LEGACY_ITERATIONS, oldHash));
        // ...y el hash nuevo (con las iteraciones actuales) también valida la misma contraseña.
        byte[] newSalt = PasswordHasher.newSalt();
        byte[] newHash = PasswordHasher.hash("mi clave", newSalt, PasswordHasher.CURRENT_ITERATIONS);
        assertTrue(PasswordHasher.matches("mi clave", newSalt, PasswordHasher.CURRENT_ITERATIONS, newHash));
        assertFalse(PasswordHasher.matches("otra", newSalt, PasswordHasher.CURRENT_ITERATIONS, newHash));
    }
}
