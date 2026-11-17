package com.botchat.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.botchat.app.R;
import com.botchat.app.data.MediaLoader;
import com.botchat.app.data.Telegram;
import com.botchat.app.filters.FilterRules;
import com.botchat.app.filters.Filters;

import org.drinkless.tdlib.TdApi;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Dibuja cada mensaje de la lista: texto, foto, archivo adjunto y botones del bot, y decide cuáles se ven según
 * los filtros. Las descargas y las imágenes las maneja MediaLoader.
 */
final class MessageAdapter extends BaseAdapter {

    interface Actions {
        void onInlineButton(TdApi.Message message, TdApi.InlineKeyboardButton button);

        void onCommand(String command);
    }

    private static final int TYPE_INCOMING = 0;
    private static final int TYPE_OUTGOING = 1;

    /** Resultado del filtro de imágenes para un mensaje (ver mediaFilter). */
    private static final int MEDIA_SHOWN = 0;     // se ve todo
    private static final int MEDIA_HIDDEN = 1;    // se oculta la foto/archivo; el pie de foto se queda
    private static final int MEDIA_WAITING = 2;   // la foto aún se descarga: todavía no se sabe su nombre

    /**
     * Lo que los filtros deciden de un mensaje. Se guarda para no repetir el cálculo con cada aviso nuevo:
     * sigue valiendo mientras no cambien las reglas, el contenido ni los botones del mensaje.
     */
    private static final class Decision {
        final FilterRules rules;
        final TdApi.MessageContent content;
        final TdApi.ReplyMarkup markup;
        final boolean hide;     // el mensaje entero se oculta
        final int media;        // MEDIA_*
        final boolean body;     // sin la foto, queda pie de foto o botones que mostrar

        Decision(FilterRules rules, TdApi.Message message, boolean hide, int media, boolean body) {
            this.rules = rules;
            this.content = message.content;
            this.markup = message.replyMarkup;
            this.hide = hide;
            this.media = media;
            this.body = body;
        }
    }

    private final Activity activity;
    private final MediaLoader media;
    /** Todos los mensajes del chat (incluidos los que los filtros ocultan). */
    private final List<TdApi.Message> messages;
    /** Solo los que se muestran: se recalcula en cada notifyDataSetChanged(). */
    private final ArrayList<TdApi.Message> visible = new ArrayList<>();
    /** Ids de los mensajes visibles cuya foto/archivo está oculta por un filtro (se ve el resto del mensaje). */
    private final HashSet<Long> mediaHidden = new HashSet<>();
    /** Decisiones de los filtros, por id de mensaje. Solo contiene mensajes que siguen en la lista. */
    private HashMap<Long, Decision> decisions = new HashMap<>();
    private final Actions actions;
    private final LayoutInflater inflater;
    private final float density;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());

    MessageAdapter(Activity activity, Telegram telegram, List<TdApi.Message> messages, Actions actions) {
        this.activity = activity;
        this.messages = messages;
        this.actions = actions;
        this.inflater = LayoutInflater.from(activity);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.media = new MediaLoader(activity, telegram, new MediaLoader.Listener() {
            @Override
            public void onChanged(boolean downloadCompleted) {
                if (downloadCompleted && Filters.hasImageNames()) {
                    // Ya se conoce el nombre de la foto: se vuelve a decidir si se muestra.
                    notifyDataSetChanged();
                } else {
                    redraw();
                }
            }

            @Override
            public void onDownloadFailed() {
                if (Filters.hasImageNames()) {
                    notifyDataSetChanged();
                }
            }
        });
        refilter();
    }

    /** Vuelve a calcular qué mensajes se ven según los filtros del usuario. */
    private void refilter() {
        FilterRules rules = Filters.rules();
        HashMap<Long, Decision> next = new HashMap<>();
        visible.clear();
        mediaHidden.clear();
        for (TdApi.Message message : messages) {
            Decision d = decisions.get(message.id);
            if (d == null || d.rules != rules || d.content != message.content || d.markup != message.replyMarkup) {
                d = decide(message, rules);
            }
            if (d.media != MEDIA_WAITING) {
                next.put(message.id, d);   // lo que sigue pendiente se vuelve a calcular la próxima vez
            }
            if (d.hide || d.media == MEDIA_WAITING) {
                continue;   // texto que era solo palabras clave, o foto aún sin nombre conocido
            }
            if (d.media == MEDIA_HIDDEN) {
                if (!d.body) {
                    continue;   // sin pie de foto ni botones solo quedaría la hora: se oculta todo
                }
                mediaHidden.add(message.id);   // se ve el mensaje, pero sin la foto/archivo
            }
            visible.add(message);
        }
        decisions = next;
    }

    private Decision decide(TdApi.Message message, FilterRules rules) {
        boolean hide = rules.shouldHide(message);
        int mediaState = hide ? MEDIA_SHOWN : mediaFilter(message, rules);
        boolean body = mediaState == MEDIA_HIDDEN && hasBody(message);
        return new Decision(rules, message, hide, mediaState, body);
    }

    /**
     * Filtro de imágenes por nombre para las fotos y archivos del bot. Para las fotos el nombre solo se
     * conoce cuando ya están descargadas, así que mientras tanto el mensaje no se muestra (y se pide la
     * descarga) para que una foto filtrada no aparezca ni un instante.
     */
    private int mediaFilter(TdApi.Message message, FilterRules rules) {
        if (message.isOutgoing || !rules.hasImages()) {
            return MEDIA_SHOWN;
        }
        if (rules.mediaMatches(message)) {
            return MEDIA_HIDDEN;   // por nombre de archivo o por pie de foto
        }
        if (!(message.content instanceof TdApi.MessagePhoto)) {
            return MEDIA_SHOWN;
        }
        TdApi.PhotoSize size = MediaLoader.pickSize(((TdApi.MessagePhoto) message.content).photo);
        if (size == null || size.photo == null) {
            return MEDIA_SHOWN;
        }
        TdApi.File file = media.latest(size.photo);
        if (MediaLoader.isDownloaded(file)) {
            return rules.matchesPhotoFile(file.local.path) ? MEDIA_HIDDEN : MEDIA_SHOWN;
        }
        if (media.hasFailed(file.id)) {
            return MEDIA_SHOWN;   // no se pudo bajar: se muestra igual (con su marcador) en vez de perderla
        }
        media.download(file, 1);
        return MEDIA_WAITING;
    }

    /** true si, sin la foto/archivo, al mensaje le queda algo que mostrar: pie de foto (ya sin palabras clave) o botones. */
    private boolean hasBody(TdApi.Message message) {
        if (message.replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard) {
            return true;
        }
        CharSequence caption = TextFormatter.hide(
                TextFormatter.format(FilterRules.captionOf(message.content), null), true);
        return caption.length() > 0;
    }

    /** Cambió la lista de mensajes o los filtros: se vuelve a filtrar y a dibujar. */
    @Override
    public void notifyDataSetChanged() {
        refilter();
        super.notifyDataSetChanged();
    }

    /** Solo vuelve a dibujar (avance de descargas, fotos listas); la lista de mensajes es la misma. */
    private void redraw() {
        super.notifyDataSetChanged();
    }

    void release() {
        media.release();
    }

    /** La pantalla de chat lo llama cada vez que TDLib avisa del avance de una descarga. */
    void onFile(TdApi.File file) {
        media.onFile(file);
    }

    @Override
    public int getCount() {
        return visible.size();
    }

    @Override
    public TdApi.Message getItem(int position) {
        return visible.get(position);
    }

    @Override
    public long getItemId(int position) {
        return visible.get(position).id;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return visible.get(position).isOutgoing ? TYPE_OUTGOING : TYPE_INCOMING;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TdApi.Message message = visible.get(position);
        Holder holder;
        if (convertView == null) {
            int layout = message.isOutgoing ? R.layout.item_message_out : R.layout.item_message_in;
            convertView = inflater.inflate(layout, parent, false);
            holder = new Holder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        bind(holder, message);
        return convertView;
    }

    private void bind(Holder holder, final TdApi.Message message) {
        TdApi.FormattedText text = null;
        String note = null;
        TdApi.PhotoSize photo = null;
        TdApi.File attachment = null;
        String attachmentName = null;
        String attachmentMime = null;

        TdApi.MessageContent content = message.content;
        switch (content == null ? 0 : content.getConstructor()) {
            case TdApi.MessageText.CONSTRUCTOR:
                text = ((TdApi.MessageText) content).text;
                break;
            case TdApi.MessagePhoto.CONSTRUCTOR: {
                TdApi.MessagePhoto value = (TdApi.MessagePhoto) content;
                photo = MediaLoader.pickSize(value.photo);
                text = value.caption;
                break;
            }
            case TdApi.MessageDocument.CONSTRUCTOR: {
                TdApi.MessageDocument value = (TdApi.MessageDocument) content;
                attachment = value.document.document;
                attachmentName = firstNonEmpty(value.document.fileName, activity.getString(R.string.file_document));
                attachmentMime = value.document.mimeType;
                text = value.caption;
                break;
            }
            case TdApi.MessageVideo.CONSTRUCTOR: {
                TdApi.MessageVideo value = (TdApi.MessageVideo) content;
                attachment = value.video.video;
                attachmentName = firstNonEmpty(value.video.fileName, activity.getString(R.string.file_video));
                attachmentMime = value.video.mimeType;
                text = value.caption;
                break;
            }
            case TdApi.MessageAnimation.CONSTRUCTOR: {
                TdApi.MessageAnimation value = (TdApi.MessageAnimation) content;
                attachment = value.animation.animation;
                attachmentName = firstNonEmpty(value.animation.fileName, activity.getString(R.string.file_animation));
                attachmentMime = value.animation.mimeType;
                text = value.caption;
                break;
            }
            case TdApi.MessageAudio.CONSTRUCTOR: {
                TdApi.MessageAudio value = (TdApi.MessageAudio) content;
                attachment = value.audio.audio;
                attachmentName = firstNonEmpty(value.audio.fileName, value.audio.title,
                        activity.getString(R.string.file_audio));
                attachmentMime = value.audio.mimeType;
                text = value.caption;
                break;
            }
            case TdApi.MessageVoiceNote.CONSTRUCTOR: {
                TdApi.MessageVoiceNote value = (TdApi.MessageVoiceNote) content;
                attachment = value.voiceNote.voice;
                attachmentName = activity.getString(R.string.file_voice);
                attachmentMime = value.voiceNote.mimeType;
                text = value.caption;
                break;
            }
            case TdApi.MessageSticker.CONSTRUCTOR:
                note = activity.getString(R.string.message_sticker, ((TdApi.MessageSticker) content).sticker.emoji);
                break;
            case TdApi.MessageAnimatedEmoji.CONSTRUCTOR:
                note = ((TdApi.MessageAnimatedEmoji) content).emoji;
                break;
            default:
                note = activity.getString(R.string.message_unsupported);
                break;
        }

        if (mediaHidden.contains(message.id)) {
            // Filtro de imágenes: la foto/archivo no se ve, pero el pie de foto, los botones y la hora sí.
            photo = null;
            attachment = null;
        }

        bindPhoto(holder, photo);
        bindAttachment(holder, attachment, attachmentName, attachmentMime);

        CharSequence body = note != null ? note
                : TextFormatter.hide(TextFormatter.format(text, actions::onCommand), !message.isOutgoing);
        holder.text.setText(body);
        holder.text.setVisibility(body.length() == 0 ? View.GONE : View.VISIBLE);

        bindButtons(holder, message);
        bindMeta(holder, message);
    }

    private void bindPhoto(Holder holder, TdApi.PhotoSize photo) {
        if (photo == null || photo.photo == null) {
            holder.photo.setImageBitmap(null);
            holder.photo.setOnClickListener(null);
            holder.photo.setVisibility(View.GONE);
            return;
        }
        final TdApi.File file = media.latest(photo.photo);

        // El tamaño se fija antes de tener la imagen para que la lista no salte al cargarla.
        int width = dp(240);
        int height = photo.width > 0 ? Math.round(width * (float) photo.height / photo.width) : width;
        height = Math.max(dp(90), Math.min(dp(320), height));
        ViewGroup.LayoutParams params = holder.photo.getLayoutParams();
        params.width = width;
        params.height = height;
        holder.photo.setLayoutParams(params);

        Bitmap bitmap = media.bitmap(file.id);
        holder.photo.setImageBitmap(bitmap);
        if (bitmap == null) {
            media.ensureBitmap(file);
        }
        holder.photo.setOnClickListener(v -> media.open(file, "image/jpeg"));
        holder.photo.setVisibility(View.VISIBLE);
    }

    private void bindAttachment(Holder holder, TdApi.File attachment, String name, final String mime) {
        if (attachment == null) {
            holder.attachment.setOnClickListener(null);
            holder.attachment.setVisibility(View.GONE);
            return;
        }
        final TdApi.File file = media.latest(attachment);
        long size = file.size > 0 ? file.size : file.expectedSize;

        StringBuilder label = new StringBuilder("📎 ").append(name);
        if (size > 0) {
            label.append(" · ").append(Formatter.formatShortFileSize(activity, size));
        }
        label.append('\n');
        if (MediaLoader.isDownloaded(file)) {
            label.append(activity.getString(R.string.file_tap_to_open));
        } else if (file.local != null && file.local.isDownloadingActive) {
            int percent = size > 0 ? (int) (file.local.downloadedSize * 100 / size) : 0;
            label.append(activity.getString(R.string.file_downloading, percent));
        } else {
            label.append(activity.getString(R.string.file_tap_to_download));
        }
        holder.attachment.setText(label);
        holder.attachment.setOnClickListener(v -> media.open(file, mime));
        holder.attachment.setVisibility(View.VISIBLE);
    }

    private void bindButtons(Holder holder, final TdApi.Message message) {
        holder.buttons.removeAllViews();
        if (!(message.replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard)) {
            holder.buttons.setVisibility(View.GONE);
            holder.bubble.setMinimumWidth(0);
            return;
        }
        TdApi.InlineKeyboardButton[][] rows = ((TdApi.ReplyMarkupInlineKeyboard) message.replyMarkup).rows;
        for (TdApi.InlineKeyboardButton[] row : rows) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (final TdApi.InlineKeyboardButton button : row) {
                TextView view = (TextView) inflater.inflate(R.layout.item_inline_button, line, false);
                view.setText(button.text);
                view.setOnClickListener(v -> actions.onInlineButton(message, button));
                line.addView(view);
            }
            holder.buttons.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        holder.bubble.setMinimumWidth(dp(230));
        holder.buttons.setVisibility(View.VISIBLE);
    }

    private void bindMeta(Holder holder, TdApi.Message message) {
        long millis = message.date * 1000L;
        String meta = (DateUtils.isToday(millis) ? timeFormat : dateFormat).format(new Date(millis));
        boolean failed = message.sendingState instanceof TdApi.MessageSendingStateFailed;
        if (failed) {
            meta = activity.getString(R.string.message_failed);
        } else if (message.sendingState instanceof TdApi.MessageSendingStatePending) {
            meta = activity.getString(R.string.message_sending);
        } else if (message.editDate != 0) {
            meta = activity.getString(R.string.message_edited, meta);
        }
        holder.meta.setText(meta);
        holder.meta.setTextColor(activity.getColor(failed ? R.color.error : R.color.text_secondary));
    }

    // ---------------------------------------------------------------------------------------

    private void copy(CharSequence text) {
        if (text == null || text.length() == 0) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("mensaje", text.toString()));
            Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private final class Holder {
        final LinearLayout bubble;
        final ImageView photo;
        final TextView attachment;
        final TextView text;
        final LinearLayout buttons;
        final TextView meta;

        Holder(View root) {
            bubble = root.findViewById(R.id.bubble);
            photo = root.findViewById(R.id.photo);
            attachment = root.findViewById(R.id.attachment);
            text = root.findViewById(R.id.text);
            buttons = root.findViewById(R.id.buttons);
            meta = root.findViewById(R.id.meta);

            // Enlaces y /comandos tocables; mantener pulsado copia el texto.
            text.setMovementMethod(LinkMovementMethod.getInstance());
            text.setOnLongClickListener(v -> {
                copy(text.getText());
                return true;
            });
        }
    }
}
