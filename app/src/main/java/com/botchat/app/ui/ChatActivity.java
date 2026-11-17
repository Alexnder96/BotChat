package com.botchat.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.botchat.app.R;
import com.botchat.app.data.Config;
import com.botchat.app.data.MessageStore;
import com.botchat.app.data.Telegram;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.List;

/**
 * La pantalla principal: el chat con el bot y nada más.
 * Escribes la consulta abajo y la respuesta del bot aparece sola en cuanto llega.
 *
 * Girar el teléfono no recrea esta pantalla (ver configChanges en el manifiesto), así que el chat no se
 * recarga ni pierde su posición.
 */
public class ChatActivity extends Activity implements Telegram.Listener, MessageAdapter.Actions {

    /** Cuántos mensajes anteriores se cargan cada vez. */
    private static final int PAGE = 40;
    /** Espera antes de volver a pedir el historial si Telegram devolvió un error. */
    private static final long HISTORY_RETRY_MS = 3000;

    private Telegram telegram;
    private MessageAdapter adapter;
    private final MessageStore store = new MessageStore();

    private ListView list;
    private TextView title;
    private TextView subtitle;
    private TextView empty;
    private EditText input;
    private ScrollView keyboardScroll;
    private LinearLayout keyboard;

    private long chatId;
    private long keyboardMessageId;
    private boolean loading;
    private boolean hasMore = true;
    private long historyRetryAt;
    private boolean resumed;
    private boolean typing;
    /** true cuando Telegram ya está listo y se empezó a abrir el chat del bot. */
    private boolean started;
    /** Usuario del bot con el que se abrió el chat (para detectar si se cambia en Configuración). */
    private String openedBot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyScreenSecurity();
        setContentView(R.layout.activity_chat);

        list = findViewById(R.id.list);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        empty = findViewById(R.id.empty);
        input = findViewById(R.id.input);
        keyboardScroll = findViewById(R.id.keyboard_scroll);
        keyboard = findViewById(R.id.keyboard);

        telegram = Telegram.get(this);
        adapter = new MessageAdapter(this, telegram, store.list(), this);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                // Al llegar arriba del todo se cargan mensajes más antiguos.
                if (firstVisible == 0 && totalCount > 0) {
                    loadHistory(0);
                }
            }
        });

        findViewById(R.id.send).setOnClickListener(v -> sendFromInput());
        // Enter (tecla de enviar del teclado del teléfono o Enter de un teclado físico) envía el mensaje,
        // igual que el botón. Al devolver true el teclado se queda abierto para seguir escribiendo.
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enterKey) {
                sendFromInput();
                return true;
            }
            return false;
        });
        findViewById(R.id.menu).setOnClickListener(this::showMenu);

        applyTitle();
        empty.setText(R.string.chat_loading);

        telegram.addListener(this);
        telegram.start();
        checkAuth(telegram.getAuthState());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (openedBot != null && !openedBot.equals(Config.botUsername())) {
            // Se cambió el usuario del bot en Configuración: se abre el chat del bot nuevo desde cero.
            telegram.setBotChatId(0);
            recreate();
            return;
        }
        // Por si se cambió el título, los filtros o el bloqueo de capturas mientras la pantalla estaba en segundo plano.
        applyScreenSecurity();
        applyTitle();
        adapter.notifyDataSetChanged();
        markAllRead();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        telegram.removeListener(this);
        if (chatId != 0) {
            telegram.send(new TdApi.CloseChat(chatId), null);
        }
        adapter.release();
        super.onDestroy();
    }

    /** Bloquea las capturas de pantalla (y la miniatura en "recientes") si así se pidió en Configuración. */
    private void applyScreenSecurity() {
        if (Config.secureChat()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sesión: esperar a que Telegram esté listo
    // ---------------------------------------------------------------------------------------

    /**
     * Cuando Android reabre la app tras cerrarla en segundo plano, la conexión con Telegram tarda un instante
     * en estar lista: se espera en vez de saltar a la pantalla de inicio de sesión.
     */
    private void checkAuth(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateReady) {
            if (!started) {
                started = true;
                updateSubtitle();
                openedBot = Config.botUsername();
                openBotChat();
            }
        } else if (state == null || state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            // Todavía arrancando: llegará otro aviso.
        } else {
            goToLogin();
        }
    }

    @Override
    public void onAuthState(TdApi.AuthorizationState state) {
        checkAuth(state);
    }

    // ---------------------------------------------------------------------------------------
    // Abrir el chat y cargar el historial
    // ---------------------------------------------------------------------------------------

    private void openBotChat() {
        telegram.send(new TdApi.SearchPublicChat(Config.botUsername()), result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!(result instanceof TdApi.Chat)) {
                empty.setText(describe(result));
                return;
            }
            TdApi.Chat chat = (TdApi.Chat) result;
            chatId = chat.id;
            telegram.setBotChatId(chatId);
            telegram.send(new TdApi.OpenChat(chatId), null);
            showKeyboardOf(chat.replyMarkupMessageId);
            loadHistory(0);
        });
    }

    /**
     * Pide a Telegram mensajes anteriores al más antiguo que ya se muestra.
     * TDLib puede devolver menos de los pedidos, por eso se repite hasta juntar una página.
     */
    private void loadHistory(final int loadedSoFar) {
        if (loading || !hasMore || chatId == 0 || SystemClock.elapsedRealtime() < historyRetryAt) {
            return;
        }
        loading = true;
        telegram.send(new TdApi.GetChatHistory(chatId, store.oldestSentId(), 0, PAGE, false), result -> {
            loading = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!(result instanceof TdApi.Messages)) {
                // Un error (por ejemplo, sin internet) no debe cortar el historial para siempre: se reintenta.
                historyRetryAt = SystemClock.elapsedRealtime() + HISTORY_RETRY_MS;
                if (adapter.getCount() == 0) {
                    empty.setText(describe(result));
                    list.postDelayed(() -> loadHistory(0), HISTORY_RETRY_MS);
                }
                return;
            }

            // Se recuerda qué se estaba viendo para que la lista no salte al agregar mensajes arriba.
            boolean wasEmpty = adapter.getCount() == 0;
            int visibleBefore = adapter.getCount();
            int firstVisible = list.getFirstVisiblePosition();
            View firstView = list.getChildAt(0);
            int offset = firstView == null ? 0 : firstView.getTop();

            int added = 0;
            TdApi.Message[] received = ((TdApi.Messages) result).messages;
            if (received != null) {
                for (TdApi.Message message : received) {
                    if (message != null && store.upsert(message)) {
                        added++;
                    }
                }
            }
            if (added == 0) {
                hasMore = false;
            }
            adapter.notifyDataSetChanged();
            int visibleAdded = adapter.getCount() - visibleBefore;
            if (wasEmpty) {
                scrollToBottom();
            } else if (visibleAdded > 0) {
                list.setSelectionFromTop(firstVisible + visibleAdded, offset);
            }

            if (hasMore && loadedSoFar + added < PAGE) {
                loadHistory(loadedSoFar + added);
            } else {
                if (adapter.getCount() == 0) {
                    empty.setText(R.string.chat_empty);
                }
                markAllRead();
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Avisos en vivo de Telegram (solo llegan los del chat del bot)
    // ---------------------------------------------------------------------------------------

    @Override
    public void onUpdate(TdApi.Object update) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        switch (update.getConstructor()) {
            case TdApi.UpdateNewMessage.CONSTRUCTOR: {
                // AQUÍ llega cada respuesta del bot (y también tus propios mensajes al enviarlos).
                TdApi.Message message = ((TdApi.UpdateNewMessage) update).message;
                boolean follow = message.isOutgoing || isAtBottom();
                store.upsert(message);
                if (!message.isOutgoing) {
                    typing = false;
                    updateSubtitle();
                    if (resumed) {
                        markRead(new long[]{message.id});
                    }
                }
                adapter.notifyDataSetChanged();
                if (follow) {
                    scrollToBottom();
                }
                break;
            }
            case TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR: {
                TdApi.UpdateMessageSendSucceeded sent = (TdApi.UpdateMessageSendSucceeded) update;
                store.remove(sent.oldMessageId);
                store.upsert(sent.message);
                adapter.notifyDataSetChanged();
                break;
            }
            case TdApi.UpdateMessageSendFailed.CONSTRUCTOR: {
                TdApi.UpdateMessageSendFailed failed = (TdApi.UpdateMessageSendFailed) update;
                store.remove(failed.oldMessageId);
                store.upsert(failed.message);
                adapter.notifyDataSetChanged();
                toast(describe(failed.error));
                break;
            }
            case TdApi.UpdateMessageContent.CONSTRUCTOR: {
                // Muchos bots mandan "Buscando…" y luego editan ese mismo mensaje con el resultado.
                TdApi.UpdateMessageContent changed = (TdApi.UpdateMessageContent) update;
                TdApi.Message message = store.find(changed.messageId);
                if (message != null) {
                    message.content = changed.newContent;
                    adapter.notifyDataSetChanged();
                }
                break;
            }
            case TdApi.UpdateMessageEdited.CONSTRUCTOR: {
                TdApi.UpdateMessageEdited edited = (TdApi.UpdateMessageEdited) update;
                TdApi.Message message = store.find(edited.messageId);
                if (message != null) {
                    message.editDate = edited.editDate;
                    message.replyMarkup = edited.replyMarkup;
                    adapter.notifyDataSetChanged();
                }
                break;
            }
            case TdApi.UpdateDeleteMessages.CONSTRUCTOR: {
                TdApi.UpdateDeleteMessages deleted = (TdApi.UpdateDeleteMessages) update;
                if (deleted.isPermanent && !deleted.fromCache) {
                    for (long id : deleted.messageIds) {
                        store.remove(id);
                    }
                    adapter.notifyDataSetChanged();
                }
                break;
            }
            case TdApi.UpdateChatAction.CONSTRUCTOR: {
                TdApi.UpdateChatAction action = (TdApi.UpdateChatAction) update;
                typing = !(action.action instanceof TdApi.ChatActionCancel);
                updateSubtitle();
                break;
            }
            case TdApi.UpdateChatReplyMarkup.CONSTRUCTOR:
                showKeyboardOf(((TdApi.UpdateChatReplyMarkup) update).replyMarkupMessageId);
                break;
            case TdApi.UpdateFile.CONSTRUCTOR:
                adapter.onFile(((TdApi.UpdateFile) update).file);
                break;
            case TdApi.UpdateConnectionState.CONSTRUCTOR:
                updateSubtitle();
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Enviar
    // ---------------------------------------------------------------------------------------

    /** Envía lo que hay en la caja de texto (botón de enviar o Enter) y la vacía si se envió. */
    private void sendFromInput() {
        if (sendText(input.getText().toString())) {
            input.setText("");
        }
    }

    /**
     * Envía un mensaje de texto al bot. La respuesta llega después por onUpdate().
     * Devuelve false si no se pudo enviar (texto vacío o chat todavía sin abrir).
     */
    private boolean sendText(String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (chatId == 0) {
            toast(getString(R.string.chat_not_ready));
            return false;
        }
        TdApi.FormattedText formatted = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        TdApi.InputMessageText content = new TdApi.InputMessageText(formatted, null, true);
        telegram.send(new TdApi.SendMessage(chatId, null, null, null, null, content), result -> {
            if (result instanceof TdApi.Error && !isFinishing() && !isDestroyed()) {
                toast(describe(result));
            }
        });
        return true;
    }

    @Override
    public void onCommand(String command) {
        sendText(command);
    }

    /** Botones que el bot pone debajo de un mensaje. */
    @Override
    public void onInlineButton(TdApi.Message message, TdApi.InlineKeyboardButton button) {
        TdApi.InlineKeyboardButtonType type = button.type;
        if (type instanceof TdApi.InlineKeyboardButtonTypeUrl) {
            openUrl(((TdApi.InlineKeyboardButtonTypeUrl) type).url);
        } else if (type instanceof TdApi.InlineKeyboardButtonTypeCopyText) {
            copy(((TdApi.InlineKeyboardButtonTypeCopyText) type).text);
        } else if (type instanceof TdApi.InlineKeyboardButtonTypeCallback) {
            byte[] data = ((TdApi.InlineKeyboardButtonTypeCallback) type).data;
            TdApi.CallbackQueryPayload payload = new TdApi.CallbackQueryPayloadData(data);
            telegram.send(new TdApi.GetCallbackQueryAnswer(message.chatId, message.id, payload), result -> {
                if (isFinishing() || isDestroyed() || !(result instanceof TdApi.CallbackQueryAnswer)) {
                    // Si el bot no contesta el botón no se muestra nada: lo normal es que
                    // responda editando el mensaje o mandando uno nuevo.
                    return;
                }
                TdApi.CallbackQueryAnswer answer = (TdApi.CallbackQueryAnswer) result;
                if (answer.text != null && !answer.text.isEmpty()) {
                    if (answer.showAlert) {
                        new AlertDialog.Builder(this)
                                .setMessage(answer.text)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    } else {
                        toast(answer.text);
                    }
                }
                if (answer.url != null && !answer.url.isEmpty()) {
                    openUrl(answer.url);
                }
            });
        } else {
            toast(getString(R.string.button_unsupported));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Teclado de respuestas rápidas del bot (los botones que reemplazan al teclado)
    // ---------------------------------------------------------------------------------------

    private void showKeyboardOf(long messageId) {
        keyboardMessageId = messageId;
        if (messageId == 0) {
            renderKeyboard(null);
            return;
        }
        telegram.send(new TdApi.GetMessage(chatId, messageId), result -> {
            if (isFinishing() || isDestroyed() || keyboardMessageId != messageId) {
                return;
            }
            TdApi.ReplyMarkup markup = result instanceof TdApi.Message ? ((TdApi.Message) result).replyMarkup : null;
            renderKeyboard(markup instanceof TdApi.ReplyMarkupShowKeyboard
                    ? (TdApi.ReplyMarkupShowKeyboard) markup : null);
        });
    }

    private void renderKeyboard(final TdApi.ReplyMarkupShowKeyboard markup) {
        keyboard.removeAllViews();
        if (markup == null || markup.rows == null || markup.rows.length == 0) {
            keyboardScroll.setVisibility(View.GONE);
            return;
        }
        final long messageId = keyboardMessageId;
        for (TdApi.KeyboardButton[] row : markup.rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (final TdApi.KeyboardButton button : row) {
                TextView view = (TextView) getLayoutInflater().inflate(R.layout.item_inline_button, line, false);
                view.setText(button.text);
                view.setOnClickListener(v -> {
                    if (!(button.type instanceof TdApi.KeyboardButtonTypeText)) {
                        toast(getString(R.string.button_unsupported));
                        return;
                    }
                    sendText(button.text);
                    if (markup.oneTime) {
                        telegram.send(new TdApi.DeleteChatReplyMarkup(chatId, messageId), null);
                    }
                });
                line.addView(view);
            }
            keyboard.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // Con muchas filas el panel se queda en una altura fija y se puede deslizar.
        ViewGroup.LayoutParams params = keyboardScroll.getLayoutParams();
        params.height = markup.rows.length > 4 ? dp(4 * 46 + 8) : ViewGroup.LayoutParams.WRAP_CONTENT;
        keyboardScroll.setLayoutParams(params);
        keyboardScroll.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------------------------------
    // Detalles de la pantalla
    // ---------------------------------------------------------------------------------------

    private void updateSubtitle() {
        TdApi.ConnectionState connection = telegram.getConnectionState();
        subtitle.setVisibility(View.VISIBLE);
        if (connection instanceof TdApi.ConnectionStateWaitingForNetwork) {
            subtitle.setText(R.string.state_no_network);
        } else if (connection != null && !(connection instanceof TdApi.ConnectionStateReady)) {
            subtitle.setText(R.string.state_connecting);
        } else if (typing) {
            subtitle.setText(R.string.state_typing);
        } else {
            subtitle.setVisibility(View.GONE);   // sin @usuario: solo queda el título
        }
    }

    private boolean isAtBottom() {
        return adapter.getCount() == 0 || list.getLastVisiblePosition() >= adapter.getCount() - 1;
    }

    private void scrollToBottom() {
        if (adapter.getCount() > 0) {
            list.setSelection(adapter.getCount() - 1);
        }
    }

    /** Muestra u oculta el título del bot según Configuración. */
    private void applyTitle() {
        if (Config.showTitle()) {
            title.setText(Config.title());
            title.setVisibility(View.VISIBLE);
        } else {
            title.setVisibility(View.GONE);
        }
    }

    /** Marca como leídos los últimos mensajes del bot, para que no queden pendientes en Telegram. */
    private void markAllRead() {
        List<TdApi.Message> messages = store.list();
        if (!resumed || chatId == 0 || messages.isEmpty()) {
            return;
        }
        ArrayList<Long> ids = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && ids.size() < 20; i--) {
            if (!messages.get(i).isOutgoing) {
                ids.add(messages.get(i).id);
            }
        }
        long[] array = new long[ids.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = ids.get(i);
        }
        markRead(array);
    }

    private void markRead(long[] ids) {
        if (chatId != 0 && ids.length > 0) {
            telegram.send(new TdApi.ViewMessages(chatId, ids, new TdApi.MessageSourceChatHistory(), true), null);
        }
    }

    private static final int MENU_FILTERS = 1;
    private static final int MENU_SETTINGS = 2;
    private static final int MENU_LOGOUT = 3;

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_FILTERS, 0, R.string.menu_filters);
        menu.getMenu().add(0, MENU_SETTINGS, 1, R.string.menu_settings);
        menu.getMenu().add(0, MENU_LOGOUT, 2, R.string.menu_logout);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_FILTERS:
                    // Los filtros están protegidos con contraseña.
                    FilterGate.open(this, () -> startActivity(new Intent(this, FiltersActivity.class)));
                    break;
                case MENU_SETTINGS:
                    // Configuración muestra el API ID y el API Hash: también pide la contraseña.
                    FilterGate.open(this, () -> startActivity(new Intent(this, SettingsActivity.class)));
                    break;
                default:
                    confirmLogout();
                    break;
            }
            return true;
        });
        menu.show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_logout)
                .setMessage(R.string.logout_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.menu_logout, (dialog, which) -> telegram.send(new TdApi.LogOut(), null))
                .show();
    }

    private void goToLogin() {
        if (isFinishing()) {
            return;
        }
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | NullPointerException e) {
            toast(getString(R.string.url_failed));
        }
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && text != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("texto", text));
            toast(getString(R.string.copied));
        }
    }

    private String describe(TdApi.Object result) {
        if (result instanceof TdApi.Error) {
            return Telegram.describe(this, (TdApi.Error) result);
        }
        return getString(R.string.error_unknown);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
