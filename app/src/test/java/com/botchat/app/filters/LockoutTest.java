package com.botchat.app.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LockoutTest {

    @Test
    public void delayMs_losPrimerosErroresNoTienenCastigo() {
        for (int failures = 0; failures <= Lockout.FREE_ATTEMPTS; failures++) {
            assertEquals(0L, Lockout.delayMs(failures));
        }
    }

    @Test
    public void delayMs_desdeElQuintoErrorSeDuplica() {
        assertEquals(30_000L, Lockout.delayMs(5));
        assertEquals(60_000L, Lockout.delayMs(6));
        assertEquals(120_000L, Lockout.delayMs(7));
        assertEquals(240_000L, Lockout.delayMs(8));
    }

    @Test
    public void delayMs_tieneUnMaximoDeQuinceMinutos() {
        assertEquals(900_000L, Lockout.delayMs(12));
        assertEquals(900_000L, Lockout.delayMs(100));
        assertEquals(900_000L, Lockout.delayMs(Integer.MAX_VALUE));
    }

    @Test
    public void remainingMs_sinCastigoEsCero() {
        assertEquals(0L, Lockout.remainingMs(3, 1_000, 1_001));
    }

    @Test
    public void remainingMs_cuentaElTiempoQueFalta() {
        assertEquals(20_000L, Lockout.remainingMs(5, 100_000, 110_000));
        assertEquals(0L, Lockout.remainingMs(5, 100_000, 130_000));
        assertEquals(0L, Lockout.remainingMs(5, 100_000, 500_000));
    }

    @Test
    public void remainingMs_siElRelojVaHaciaAtrasSeCuentaLaEsperaCompleta() {
        assertEquals(30_000L, Lockout.remainingMs(5, 100_000, 50_000));
    }

    @Test
    public void remainingMs_nuncaEsNegativo() {
        assertTrue(Lockout.remainingMs(9, 0, Long.MAX_VALUE / 2) >= 0);
    }
}
