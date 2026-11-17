package com.botchat.app.filters;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las reglas de filtrado en un instante dado: una "foto" inmutable de las dos listas del usuario
 * (palabras clave e imágenes por nombre). Sin nada de Android, así que se prueba con tests unitarios.
 *
 * Cuando el usuario cambia una lista se crea otro FilterRules; quien guarde decisiones (como el adaptador del
 * chat) puede comparar por identidad (==) para saber si sus decisiones siguen vigentes.
 */
public final class FilterRules {

    public static final FilterRules EMPTY = new FilterRules(Collections.<String>emptyList(),
            Collections.<String>emptyList());

    /** Nombres de archivo de foto de Telegram, como 5071069394380721250_121.jpg (el _121 es el tamaño). */
    private static final Pattern PHOTO_NAME = Pattern.compile("^(\\d{6,})_\\d+(\\.[a-z0-9]+)?$");

    private final List<String> words;    // ya normalizadas
    private final List<String> images;   // ya normalizadas

    private FilterRules(List<String> words, List<String> images) {
        this.words = words;
        this.images = images;
    }

    /** Crea las reglas a partir de las listas tal como las escribió el usuario. */
    public static FilterRules of(List<String> words, List<String> images) {
        return new FilterRules(normalizeAll(words), normalizeAll(images));
    }

    private static List<String> normalizeAll(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String n = TextRules.normalize(value);
                if (!n.isEmpty()) {
                    result.add(n);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean hasWords() {
        return !words.isEmpty();
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    /** Dónde están en el texto las palabras clave a quitar (ver TextRules.ranges). */
    public List<int[]> ranges(String text) {
        return TextRules.ranges(text, words);
    }

    /** El texto sin las palabras clave. */
    public String strip(String text) {
        return TextRules.strip(text, words);
    }

    /**
     * true si el mensaje (del bot) debe ocultarse ENTERO. Tus propios mensajes nunca se ocultan.
     *  - Un mensaje de texto solo se oculta si, al quitar las palabras clave, no queda nada.
     *  - Las fotos y archivos NO se deciden aquí: se oculta solo la foto o el archivo y el pie de foto
     *    se queda (ver mediaMatches).
     */
    public boolean shouldHide(TdApi.Message message) {
        if (words.isEmpty() || message == null || message.isOutgoing || message.content == null) {
            return false;
        }
        if (message.content.getConstructor() != TdApi.MessageText.CONSTRUCTOR) {
            return false;
        }
        String text = textOf(((TdApi.MessageText) message.content).text);
        return !text.trim().isEmpty() && strip(text).trim().isEmpty();
    }

    /**
     * true si la foto o el archivo del mensaje (del bot) debe ocultarse porque su nombre de archivo o su
     * pie de foto contiene un nombre de la lista de imágenes ocultas. Solo se oculta la foto o el archivo:
     * el pie de foto, los botones y la hora del mensaje se quedan.
     */
    public boolean mediaMatches(TdApi.Message message) {
        if (images.isEmpty() || message == null || message.isOutgoing || message.content == null) {
            return false;
        }

        String fileName;
        TdApi.MessageContent content = message.content;
        switch (content.getConstructor()) {
            case TdApi.MessagePhoto.CONSTRUCTOR:
                fileName = "";
                break;
            case TdApi.MessageDocument.CONSTRUCTOR: {
                TdApi.MessageDocument value = (TdApi.MessageDocument) content;
                fileName = value.document == null || value.document.fileName == null ? "" : value.document.fileName;
                break;
            }
            case TdApi.MessageVideo.CONSTRUCTOR: {
                TdApi.MessageVideo value = (TdApi.MessageVideo) content;
                fileName = value.video == null || value.video.fileName == null ? "" : value.video.fileName;
                break;
            }
            case TdApi.MessageAnimation.CONSTRUCTOR: {
                TdApi.MessageAnimation value = (TdApi.MessageAnimation) content;
                fileName = value.animation == null || value.animation.fileName == null ? "" : value.animation.fileName;
                break;
            }
            default:
                return false;   // stickers, audios, notas de voz, etc. no se filtran
        }

        String names = TextRules.normalize(fileName + " " + textOf(captionOf(content)));
        for (String name : images) {
            if (names.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * true si el archivo de una foto descargada (ruta completa) coincide con algún nombre de la lista.
     * Las fotos no traen nombre en Telegram: el que se ve en la galería es el del archivo que descarga la app.
     * Si escribes un nombre tipo 5071069394380721250_121.jpg se compara solo el número de la foto,
     * así vale para todos los tamaños de esa misma foto.
     */
    public boolean matchesPhotoFile(String path) {
        if (path == null || images.isEmpty()) {
            return false;
        }
        String base = TextRules.normalize(path.substring(path.lastIndexOf('/') + 1));
        for (String name : images) {
            Matcher matcher = PHOTO_NAME.matcher(name);
            String key = matcher.matches() ? matcher.group(1) : name;
            if (base.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Pie de foto de una foto, documento, video o animación (null si el mensaje no tiene). */
    public static TdApi.FormattedText captionOf(TdApi.MessageContent content) {
        if (content == null) {
            return null;
        }
        switch (content.getConstructor()) {
            case TdApi.MessagePhoto.CONSTRUCTOR:
                return ((TdApi.MessagePhoto) content).caption;
            case TdApi.MessageDocument.CONSTRUCTOR:
                return ((TdApi.MessageDocument) content).caption;
            case TdApi.MessageVideo.CONSTRUCTOR:
                return ((TdApi.MessageVideo) content).caption;
            case TdApi.MessageAnimation.CONSTRUCTOR:
                return ((TdApi.MessageAnimation) content).caption;
            default:
                return null;
        }
    }

    private static String textOf(TdApi.FormattedText formatted) {
        return formatted == null || formatted.text == null ? "" : formatted.text;
    }
}
