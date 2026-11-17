package com.botchat.app.data;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.botchat.app.R;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conexión con Telegram (TDLib). Hay una sola para toda la app.
 *
 * TDLib inicia sesión con TU cuenta, así que recibe avisos de todos tus chats.
 * Aquí se descartan todos menos los del chat con el bot: el resto de la app
 * nunca ve nada de tus otras conversaciones.
 *
 * La base de datos de TDLib (donde queda tu sesión) se guarda cifrada: la clave la maneja SecureStore
 * y la política de qué clave usar está en DbKeyPolicy.
 */
public final class Telegram {

    /** Lo implementan las pantallas para enterarse de lo que pasa. Siempre se llama en el hilo principal. */
    public interface Listener {
        void onAuthState(TdApi.AuthorizationState state);

        void onUpdate(TdApi.Object update);
    }

    /** Respuesta a una petición. Siempre se llama en el hilo principal. */
    public interface Callback {
        void onResult(TdApi.Object result);
    }

    private static final String TAG = "MiBot";
    private static final int MAX_RESTARTS = 5;

    private static Telegram instance;

    public static synchronized Telegram get(Context context) {
        Config.load(context);
        if (instance == null) {
            instance = new Telegram(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Client client;
    private volatile TdApi.AuthorizationState authState;
    private volatile TdApi.ConnectionState connectionState;
    private volatile long botChatId;
    /** Lo tocan el hilo de TDLib y el principal, por eso es atómico. */
    private final AtomicInteger restarts = new AtomicInteger();
    /** Qué clave de la lista de DbKeyPolicy.Plan se está probando (solo lo usa el hilo de TDLib). */
    private volatile int keyAttempt;
    /** true si la base no se pudo abrir con la clave segura y hay que borrarla en el próximo arranque. */
    private volatile boolean forceWipe;

    private Telegram(Context app) {
        this.app = app;
    }

    /** Carpeta privada de la app donde TDLib guarda la sesión y los archivos descargados. */
    public static File dataDir(Context context) {
        return new File(context.getFilesDir(), "tdlib");
    }

    /** Arranca la conexión si todavía no existe. Se puede llamar las veces que sea. */
    public synchronized void start() {
        if (client != null || !Config.isComplete()) {
            return;
        }
        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(1));
        } catch (Client.ExecutionException ignored) {
            // Solo afecta a cuánto escribe TDLib en el Logcat.
        }
        // Si algo falla al procesar un aviso de Telegram, queda escrito en el Logcat (etiqueta "MiBot").
        Client.ExceptionHandler logger = e -> Log.e(TAG, "Error procesando un aviso de Telegram", e);
        client = Client.create(this::handleUpdate, logger, logger);
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public TdApi.AuthorizationState getAuthState() {
        return authState;
    }

    public TdApi.ConnectionState getConnectionState() {
        return connectionState;
    }

    /** La pantalla de chat avisa cuál es el chat del bot; desde ahí solo pasan sus mensajes. */
    public void setBotChatId(long chatId) {
        botChatId = chatId;
    }

    /**
     * Se usa cuando cambian el API ID / API Hash: la conexión actual se cierra y se crea otra con los datos nuevos.
     * Si había una sesión iniciada se cierra (hay que volver a entrar); si no, solo se reinicia la conexión.
     */
    public synchronized void resetConnection() {
        Client current = client;
        restarts.set(0);
        if (current == null) {
            start();
            return;
        }
        boolean loggedIn = authState instanceof TdApi.AuthorizationStateReady;
        authState = null;
        botChatId = 0;
        // Al terminar, TDLib avisa "Closed" y handleAuthState() crea la conexión nueva sola.
        current.send(loggedIn ? new TdApi.LogOut() : new TdApi.Close(), null);
    }

    /** Envía una petición a Telegram. La respuesta llega en el hilo principal (callback puede ser null). */
    public void send(TdApi.Function<?> query, final Callback callback) {
        Client current = client;
        if (current == null) {
            if (callback != null) {
                main.post(() -> callback.onResult(new TdApi.Error(500, "Sin conexión con Telegram")));
            }
            return;
        }
        if (callback == null) {
            current.send(query, null);
        } else {
            current.send(query, result -> main.post(() -> callback.onResult(result)));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Todo lo de abajo corre en el hilo de TDLib, no en el principal.
    // ---------------------------------------------------------------------------------------

    private void handleUpdate(TdApi.Object update) {
        switch (update.getConstructor()) {
            case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                handleAuthState(((TdApi.UpdateAuthorizationState) update).authorizationState);
                return;
            case TdApi.UpdateConnectionState.CONSTRUCTOR:
                connectionState = ((TdApi.UpdateConnectionState) update).state;
                dispatch(update);
                return;
            case TdApi.UpdateFile.CONSTRUCTOR:
                // Solo llegan de archivos que la propia app pidió descargar.
                dispatch(update);
                return;
            default:
                if (botChatId != 0 && chatIdOf(update) == botChatId) {
                    dispatch(update);
                }
        }
    }

    /** Devuelve el chat al que pertenece el aviso, o 0 si es un aviso que la app no usa. */
    private static long chatIdOf(TdApi.Object update) {
        switch (update.getConstructor()) {
            case TdApi.UpdateNewMessage.CONSTRUCTOR:
                return ((TdApi.UpdateNewMessage) update).message.chatId;
            case TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR:
                return ((TdApi.UpdateMessageSendSucceeded) update).message.chatId;
            case TdApi.UpdateMessageSendFailed.CONSTRUCTOR:
                return ((TdApi.UpdateMessageSendFailed) update).message.chatId;
            case TdApi.UpdateMessageContent.CONSTRUCTOR:
                return ((TdApi.UpdateMessageContent) update).chatId;
            case TdApi.UpdateMessageEdited.CONSTRUCTOR:
                return ((TdApi.UpdateMessageEdited) update).chatId;
            case TdApi.UpdateDeleteMessages.CONSTRUCTOR:
                return ((TdApi.UpdateDeleteMessages) update).chatId;
            case TdApi.UpdateChatAction.CONSTRUCTOR:
                return ((TdApi.UpdateChatAction) update).chatId;
            case TdApi.UpdateChatReplyMarkup.CONSTRUCTOR:
                return ((TdApi.UpdateChatReplyMarkup) update).chatId;
            default:
                return 0;
        }
    }

    private void handleAuthState(TdApi.AuthorizationState state) {
        authState = state;
        switch (state.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                sendParameters();
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                restarts.set(0);
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                // Pasa al cerrar sesión: se crea una conexión nueva para poder volver a entrar.
                client = null;
                botChatId = 0;
                connectionState = null;
                if (restarts.getAndIncrement() < MAX_RESTARTS) {
                    main.post(this::start);
                }
                break;
            default:
                break;
        }
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onAuthState(state);
            }
        });
    }

    /**
     * Abre la base de datos de TDLib. La clave la decide DbKeyPolicy:
     *  - instalación nueva: la base nace cifrada con la clave segura;
     *  - base de una versión anterior (sin cifrar): se abre sin clave y enseguida se cifra (SetDatabaseEncryptionKey);
     *  - base ya cifrada: se abre con la clave segura.
     * Si una clave no abre la base se prueba la siguiente; si la clave se perdió de verdad, se borra la base y
     * se empieza de cero (hay que volver a iniciar sesión, pero la app nunca se queda trabada).
     */
    private void sendParameters() {
        final Client current;
        synchronized (this) {
            // start() también es synchronized: así se espera a que termine de crear la conexión.
            current = client;
        }
        if (current == null) {
            return;
        }

        File dir = dataDir(app);
        DbKeyPolicy.State state = SecureStore.state(app);
        if (forceWipe) {
            forceWipe = false;
            wipe(dir);
            SecureStore.clear(app);
            state = DbKeyPolicy.State.NONE;
        }

        byte[] secure = SecureStore.key(app, state != DbKeyPolicy.State.ENCRYPTED);
        boolean keyOk = secure != null;
        DbKeyPolicy.Plan plan = DbKeyPolicy.plan(state, hasData(dir), keyOk);
        if (plan.wipe) {
            Log.w(TAG, "La clave de la base se perdió: se borra la sesión y se empieza de cero");
            wipe(dir);
            SecureStore.clear(app);
            state = DbKeyPolicy.State.NONE;
            secure = SecureStore.key(app, true);
            keyOk = secure != null;
            plan = DbKeyPolicy.plan(state, false, keyOk);
        }

        final int attempts = plan.attempts.size();
        final DbKeyPolicy.Key used = plan.attempts.get(Math.min(keyAttempt, attempts - 1));
        final DbKeyPolicy.State finalState = state;
        final boolean finalKeyOk = keyOk;
        final byte[] finalSecure = secure;

        String path = dir.getAbsolutePath();
        TdApi.SetTdlibParameters parameters = new TdApi.SetTdlibParameters();
        parameters.useTestDc = false;
        parameters.databaseDirectory = path;
        parameters.filesDirectory = path;
        parameters.databaseEncryptionKey = used == DbKeyPolicy.Key.SECURE ? secure : new byte[0];
        parameters.useFileDatabase = true;
        parameters.useChatInfoDatabase = true;
        parameters.useMessageDatabase = true;
        parameters.useSecretChats = false;
        parameters.apiId = Config.apiId();
        parameters.apiHash = Config.apiHash();
        parameters.systemLanguageCode = "es";
        parameters.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        parameters.systemVersion = "Android " + Build.VERSION.RELEASE;
        parameters.applicationVersion = "1.0";
        current.send(parameters,
                result -> onParametersResult(current, result, used, finalState, finalKeyOk, finalSecure, attempts));
    }

    private void onParametersResult(Client current, TdApi.Object result, DbKeyPolicy.Key used,
                                    DbKeyPolicy.State state, boolean keyOk, byte[] secure, int attempts) {
        if (result instanceof TdApi.Error) {
            TdApi.Error error = (TdApi.Error) result;
            Log.e(TAG, "TDLib no abrió la base de datos: " + error.code + " " + error.message);
            if (keyAttempt + 1 < attempts) {
                // Probar la otra clave: se cierra la conexión y, al volver a arrancar, se usa la siguiente.
                keyAttempt++;
                current.send(new TdApi.Close(), null);
            } else {
                keyAttempt = 0;
                if (used == DbKeyPolicy.Key.SECURE && isKeyError(error)) {
                    // La base cifrada no se abre ni con la clave segura: no tiene arreglo. Se borra al reiniciar.
                    forceWipe = true;
                    current.send(new TdApi.Close(), null);
                }
            }
            return;
        }

        keyAttempt = 0;
        switch (DbKeyPolicy.afterOpen(state, used, keyOk)) {
            case MARK_ENCRYPTED:
                SecureStore.setState(app, DbKeyPolicy.State.ENCRYPTED);
                break;
            case MIGRATE:
                // Se anota PENDING primero: si la app se cierra en medio, el próximo arranque prueba las dos claves.
                SecureStore.setState(app, DbKeyPolicy.State.PENDING);
                current.send(new TdApi.SetDatabaseEncryptionKey(secure), migrated -> {
                    if (migrated instanceof TdApi.Ok) {
                        SecureStore.setState(app, DbKeyPolicy.State.ENCRYPTED);
                    } else if (migrated instanceof TdApi.Error) {
                        Log.e(TAG, "No se pudo cifrar la base: " + ((TdApi.Error) migrated).message);
                    }
                });
                break;
            default:
                break;
        }
    }

    private static boolean isKeyError(TdApi.Error error) {
        return error.code == 401
                || (error.message != null && error.message.toLowerCase(Locale.ROOT).contains("encryption key"));
    }

    private static boolean hasData(File dir) {
        File[] children = dir.listFiles();
        return children != null && children.length > 0;
    }

    /** Borra todo lo que hay dentro de la carpeta de la base (la carpeta se queda). */
    private static void wipe(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "No se pudo borrar " + file.getName());
        }
    }

    private void dispatch(final TdApi.Object update) {
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onUpdate(update);
            }
        });
    }

    // ---------------------------------------------------------------------------------------

    /** Convierte los errores más comunes de Telegram en un texto entendible. */
    public static String describe(Context context, TdApi.Error error) {
        String message = error.message == null ? "" : error.message;
        if (message.contains("PHONE_NUMBER_INVALID")) {
            return context.getString(R.string.error_phone_invalid);
        }
        if (message.contains("PHONE_NUMBER_BANNED")) {
            return context.getString(R.string.error_phone_banned);
        }
        if (message.contains("PHONE_CODE_INVALID") || message.contains("EMAIL_CODE_INVALID")) {
            return context.getString(R.string.error_code_invalid);
        }
        if (message.contains("PHONE_CODE_EXPIRED") || message.contains("EMAIL_CODE_EXPIRED")) {
            return context.getString(R.string.error_code_expired);
        }
        if (message.contains("PASSWORD_HASH_INVALID")) {
            return context.getString(R.string.error_password_invalid);
        }
        if (message.contains("API_ID_INVALID") || message.contains("API_ID_PUBLISHED_FLOOD")) {
            return context.getString(R.string.error_api_id);
        }
        if (error.code == 429 || message.contains("FLOOD") || message.contains("Too Many Requests")) {
            return context.getString(R.string.error_flood, message);
        }
        if (message.contains("USERNAME_NOT_OCCUPIED") || message.contains("USERNAME_INVALID")) {
            return context.getString(R.string.error_bot_not_found, Config.botUsername());
        }
        return context.getString(R.string.error_generic, error.code, message);
    }
}
