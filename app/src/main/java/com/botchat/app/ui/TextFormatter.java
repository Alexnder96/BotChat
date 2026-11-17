package com.botchat.app.ui;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.View;

import com.botchat.app.data.Config;
import com.botchat.app.filters.Filters;

import org.drinkless.tdlib.TdApi;

/** Convierte el texto con formato de Telegram (negritas, enlaces, código…) en texto para un TextView. */
final class TextFormatter {

    interface CommandListener {
        void onCommand(String command);
    }

    private TextFormatter() {
    }

    /** Quita de un mensaje las frases de Config.HIDDEN_PHRASES, sin romper negritas ni enlaces. */
    static CharSequence hide(CharSequence message) {
        return hide(message, false);
    }

    /**
     * Igual que hide(message) y, si removeKeywords es true, quita también las palabras clave de Filtros
     * (solo la palabra: el resto del mensaje se queda).
     */
    static CharSequence hide(CharSequence message, boolean removeKeywords) {
        android.text.SpannableStringBuilder result = new android.text.SpannableStringBuilder(message);
        int at;
        for (String phrase : Config.HIDDEN_PHRASES) {
            if (phrase == null || phrase.isEmpty()) {
                continue;
            }
            while ((at = result.toString().indexOf(phrase)) >= 0) {
                result.delete(at, at + phrase.length());
            }
        }
        if (removeKeywords) {
            java.util.List<int[]> ranges = Filters.ranges(result.toString());
            for (int i = ranges.size() - 1; i >= 0; i--) {
                result.delete(ranges.get(i)[0], ranges.get(i)[1]);
            }
        }
        // Limpia los espacios y saltos de línea que quedan sueltos donde estaba la frase.
        while ((at = result.toString().indexOf("\n\n\n")) >= 0) {
            result.delete(at, at + 1);
        }
        while (result.length() > 0 && Character.isWhitespace(result.charAt(0))) {
            result.delete(0, 1);
        }
        while (result.length() > 0 && Character.isWhitespace(result.charAt(result.length() - 1))) {
            result.delete(result.length() - 1, result.length());
        }
        return result;
    }

    static CharSequence format(TdApi.FormattedText formatted, final CommandListener listener) {
        if (formatted == null || formatted.text == null) {
            return "";
        }
        final String text = formatted.text;
        SpannableString result = new SpannableString(text);
        if (formatted.entities == null) {
            return result;
        }
        for (TdApi.TextEntity entity : formatted.entities) {
            int start = entity.offset;
            int end = entity.offset + entity.length;
            if (entity.type == null || start < 0 || end > text.length() || start >= end) {
                continue;
            }
            final String part = text.substring(start, end);
            Object span = null;
            switch (entity.type.getConstructor()) {
                case TdApi.TextEntityTypeBold.CONSTRUCTOR:
                    span = new StyleSpan(Typeface.BOLD);
                    break;
                case TdApi.TextEntityTypeItalic.CONSTRUCTOR:
                    span = new StyleSpan(Typeface.ITALIC);
                    break;
                case TdApi.TextEntityTypeUnderline.CONSTRUCTOR:
                    span = new UnderlineSpan();
                    break;
                case TdApi.TextEntityTypeStrikethrough.CONSTRUCTOR:
                    span = new StrikethroughSpan();
                    break;
                case TdApi.TextEntityTypeCode.CONSTRUCTOR:
                case TdApi.TextEntityTypePre.CONSTRUCTOR:
                case TdApi.TextEntityTypePreCode.CONSTRUCTOR:
                    span = new TypefaceSpan("monospace");
                    break;
                case TdApi.TextEntityTypeTextUrl.CONSTRUCTOR:
                    span = new URLSpan(((TdApi.TextEntityTypeTextUrl) entity.type).url);
                    break;
                case TdApi.TextEntityTypeUrl.CONSTRUCTOR:
                    span = new URLSpan(part.contains("://") ? part : "https://" + part);
                    break;
                case TdApi.TextEntityTypeEmailAddress.CONSTRUCTOR:
                    span = new URLSpan("mailto:" + part);
                    break;
                case TdApi.TextEntityTypePhoneNumber.CONSTRUCTOR:
                    span = new URLSpan("tel:" + part);
                    break;
                case TdApi.TextEntityTypeMention.CONSTRUCTOR:
                    span = new URLSpan("https://t.me/" + (part.startsWith("@") ? part.substring(1) : part));
                    break;
                case TdApi.TextEntityTypeBotCommand.CONSTRUCTOR:
                    if (listener != null) {
                        // Tocar un /comando dentro de un mensaje lo envía, igual que en Telegram.
                        span = new ClickableSpan() {
                            @Override
                            public void onClick(View widget) {
                                listener.onCommand(part);
                            }
                        };
                    }
                    break;
                default:
                    break;
            }
            if (span != null) {
                result.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return result;
    }
}
