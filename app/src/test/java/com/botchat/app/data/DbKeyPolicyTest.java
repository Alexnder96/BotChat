package com.botchat.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.botchat.app.data.DbKeyPolicy.Action;
import com.botchat.app.data.DbKeyPolicy.Key;
import com.botchat.app.data.DbKeyPolicy.Plan;
import com.botchat.app.data.DbKeyPolicy.State;

import org.junit.Test;

import java.util.Arrays;

public class DbKeyPolicyTest {

    @Test
    public void instalacionNueva_nacecifrada() {
        Plan plan = DbKeyPolicy.plan(State.NONE, false, true);
        assertFalse(plan.wipe);
        assertEquals(Arrays.asList(Key.SECURE), plan.attempts);
        assertEquals(Action.MARK_ENCRYPTED, DbKeyPolicy.afterOpen(State.NONE, Key.SECURE, true));
    }

    @Test
    public void baseDeVersionAnterior_seAbreSinClaveYLuegoSeCifra() {
        Plan plan = DbKeyPolicy.plan(State.NONE, true, true);
        assertFalse(plan.wipe);
        assertEquals(Arrays.asList(Key.EMPTY), plan.attempts);
        assertEquals(Action.MIGRATE, DbKeyPolicy.afterOpen(State.NONE, Key.EMPTY, true));
    }

    @Test
    public void baseYaCifrada_seAbreConLaClaveSegura() {
        Plan plan = DbKeyPolicy.plan(State.ENCRYPTED, true, true);
        assertFalse(plan.wipe);
        assertEquals(Arrays.asList(Key.SECURE), plan.attempts);
        assertEquals(Action.NONE, DbKeyPolicy.afterOpen(State.ENCRYPTED, Key.SECURE, true));
    }

    @Test
    public void migracionInterrumpida_seProbanLasDosClaves() {
        Plan plan = DbKeyPolicy.plan(State.PENDING, true, true);
        assertFalse(plan.wipe);
        assertEquals(Arrays.asList(Key.SECURE, Key.EMPTY), plan.attempts);
        // Si abrió con la clave segura, la migración sí había terminado.
        assertEquals(Action.MARK_ENCRYPTED, DbKeyPolicy.afterOpen(State.PENDING, Key.SECURE, true));
        // Si abrió sin clave, hay que repetir la migración.
        assertEquals(Action.MIGRATE, DbKeyPolicy.afterOpen(State.PENDING, Key.EMPTY, true));
    }

    @Test
    public void claveSeguraPerdidaConBaseCifrada_seBorraYEmpiezaDeCero() {
        Plan plan = DbKeyPolicy.plan(State.ENCRYPTED, true, false);
        assertTrue(plan.wipe);
    }

    @Test
    public void keystoreNoDisponibleEnBaseSinCifrar_laAppSigueFuncionandoComoAntes() {
        for (State state : new State[]{State.NONE, State.PENDING}) {
            Plan plan = DbKeyPolicy.plan(state, true, false);
            assertFalse(plan.wipe);
            assertEquals(Arrays.asList(Key.EMPTY), plan.attempts);
            assertEquals(Action.NONE, DbKeyPolicy.afterOpen(state, Key.EMPTY, false));
        }
    }

    @Test
    public void nuncaSeBorraUnaBaseQueSePuedeAbrir() {
        for (State state : State.values()) {
            for (boolean dirHasData : new boolean[]{false, true}) {
                assertFalse("con la clave disponible nunca se borra",
                        DbKeyPolicy.plan(state, dirHasData, true).wipe);
            }
        }
    }
}
