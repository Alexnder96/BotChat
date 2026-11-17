package com.botchat.app.data;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Los mensajes del chat que están en pantalla, ordenados por id. Sin nada de Android.
 * Incluye también los que los filtros ocultan: quién los muestra es el adaptador del chat.
 */
public final class MessageStore {

    private final ArrayList<TdApi.Message> messages = new ArrayList<>();
    private final List<TdApi.Message> view = Collections.unmodifiableList(messages);

    /** Vista de solo lectura de la lista (se actualiza sola cuando cambia el contenido). */
    public List<TdApi.Message> list() {
        return view;
    }

    public int size() {
        return messages.size();
    }

    /** Inserta el mensaje en orden o reemplaza el que ya estaba. Devuelve true si era nuevo. */
    public boolean upsert(TdApi.Message message) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            long id = messages.get(i).id;
            if (id == message.id) {
                messages.set(i, message);
                return false;
            }
            if (id < message.id) {
                messages.add(i + 1, message);
                return true;
            }
        }
        messages.add(0, message);
        return true;
    }

    public TdApi.Message find(long messageId) {
        for (TdApi.Message message : messages) {
            if (message.id == messageId) {
                return message;
            }
        }
        return null;
    }

    public void remove(long messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id == messageId) {
                messages.remove(i);
                return;
            }
        }
    }

    /** Id del mensaje más antiguo ya enviado (los que siguen "enviando" tienen un id temporal), o 0. */
    public long oldestSentId() {
        for (TdApi.Message message : messages) {
            if (message.sendingState == null) {
                return message.id;
            }
        }
        return 0;
    }
}
