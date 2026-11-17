package com.botchat.app.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decide con qué clave abrir la base de datos de TDLib (donde está tu sesión de Telegram) y qué hacer después.
 * Sin nada de Android, para poder probar todos los casos con tests unitarios.
 *
 * Estados guardados (ver SecureStore):
 *  - NONE: todavía no se cifró. Puede ser una instalación nueva o una app anterior con la base SIN cifrar.
 *  - PENDING: se pidió cifrar la base y no se confirmó (la app se cerró en medio). Puede estar con cualquiera
 *    de las dos claves, por eso se prueban las dos.
 *  - ENCRYPTED: la base está cifrada con la clave guardada en el Android Keystore.
 */
public final class DbKeyPolicy {

    public enum State { NONE, PENDING, ENCRYPTED }

    /** EMPTY = sin clave (base sin cifrar). SECURE = la clave aleatoria protegida por el Keystore. */
    public enum Key { EMPTY, SECURE }

    public enum Action {
        /** No hay nada más que hacer. */
        NONE,
        /** La base ya estaba cifrada con la clave segura: solo anotarlo. */
        MARK_ENCRYPTED,
        /** La base estaba sin cifrar: pedirle a TDLib que se cifre con la clave segura. */
        MIGRATE
    }

    public static final class Plan {
        /** true si hay que borrar la base: la clave se perdió, así que no se puede volver a abrir. */
        public final boolean wipe;
        /** Claves a probar, en orden. */
        public final List<Key> attempts;

        Plan(boolean wipe, Key... attempts) {
            this.wipe = wipe;
            this.attempts = Collections.unmodifiableList(Arrays.asList(attempts));
        }
    }

    private DbKeyPolicy() {
    }

    /**
     * @param state       estado guardado
     * @param dirHasData  true si la carpeta de la base ya tiene archivos
     * @param keyOk       true si la clave segura se pudo leer (o crear) del Keystore
     */
    public static Plan plan(State state, boolean dirHasData, boolean keyOk) {
        if (!keyOk) {
            if (state == State.ENCRYPTED) {
                // La base está cifrada y la clave se perdió (Keystore reiniciado, copia a otro teléfono…):
                // es imposible abrirla. Se borra y se empieza de cero (hay que volver a iniciar sesión).
                return new Plan(true, Key.SECURE);
            }
            return new Plan(false, Key.EMPTY);   // sin Keystore la app sigue funcionando como antes
        }
        switch (state) {
            case ENCRYPTED:
                return new Plan(false, Key.SECURE);
            case PENDING:
                return new Plan(false, Key.SECURE, Key.EMPTY);
            default:
                return dirHasData
                        ? new Plan(false, Key.EMPTY)    // base de una versión anterior: se abre y luego se cifra
                        : new Plan(false, Key.SECURE);  // instalación nueva: nace cifrada
        }
    }

    /** Qué hacer una vez que TDLib abrió la base con la clave {@code used}. */
    public static Action afterOpen(State state, Key used, boolean keyOk) {
        if (used == Key.SECURE) {
            return state == State.ENCRYPTED ? Action.NONE : Action.MARK_ENCRYPTED;
        }
        return keyOk ? Action.MIGRATE : Action.NONE;
    }
}
