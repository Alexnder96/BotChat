package com.botchat.app.filters;

/**
 * Bloqueo por intentos fallidos de contraseña, sin nada de Android.
 *
 * Los primeros 4 errores no tienen castigo; desde el 5.º hay que esperar 30 s, y la espera se duplica con
 * cada error más hasta un máximo de 15 minutos. Una contraseña correcta vuelve el contador a cero.
 */
public final class Lockout {

    /** Errores seguidos que se permiten antes de empezar a esperar. */
    public static final int FREE_ATTEMPTS = 4;

    private static final long BASE_MS = 30_000L;
    private static final long MAX_MS = 15 * 60_000L;

    private Lockout() {
    }

    /** Cuánto hay que esperar después del último error, según cuántos errores seguidos hubo. */
    public static long delayMs(int failures) {
        if (failures <= FREE_ATTEMPTS) {
            return 0;
        }
        int doublings = Math.min(failures - FREE_ATTEMPTS - 1, 10);   // el tope evita desbordar el desplazamiento
        return Math.min(BASE_MS << doublings, MAX_MS);
    }

    /**
     * Cuánto falta para poder volver a intentar (0 si ya se puede). Si el reloj del teléfono se movió hacia atrás
     * se cuenta la espera completa, para que cambiar la hora no sirva para saltarse el bloqueo.
     */
    public static long remainingMs(int failures, long lastFailureMs, long nowMs) {
        long delay = delayMs(failures);
        if (delay == 0) {
            return 0;
        }
        long elapsed = nowMs - lastFailureMs;
        if (elapsed < 0) {
            return delay;
        }
        return Math.max(0, delay - elapsed);
    }
}
