package com.botchat.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

public class MessageStoreTest {

    private static TdApi.Message message(long id) {
        TdApi.Message m = new TdApi.Message();
        m.id = id;
        return m;
    }

    private static TdApi.Message pending(long id) {
        TdApi.Message m = message(id);
        m.sendingState = new TdApi.MessageSendingStatePending();
        return m;
    }

    private static long[] ids(MessageStore store) {
        long[] result = new long[store.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = store.list().get(i).id;
        }
        return result;
    }

    private static void assertIds(MessageStore store, long... expected) {
        assertEquals(expected.length, store.size());
        long[] actual = ids(store);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i]);
        }
    }

    @Test
    public void upsert_mantieneElOrdenPorId() {
        MessageStore store = new MessageStore();
        assertTrue(store.upsert(message(30)));
        assertTrue(store.upsert(message(10)));
        assertTrue(store.upsert(message(20)));
        assertTrue(store.upsert(message(40)));
        assertIds(store, 10, 20, 30, 40);
    }

    @Test
    public void upsert_reemplazaElQueYaEstaba() {
        MessageStore store = new MessageStore();
        store.upsert(message(10));
        TdApi.Message newer = message(10);
        assertFalse(store.upsert(newer));
        assertEquals(1, store.size());
        assertSame(newer, store.find(10));
    }

    @Test
    public void find_yRemove() {
        MessageStore store = new MessageStore();
        store.upsert(message(1));
        store.upsert(message(2));
        assertSame(store.list().get(1), store.find(2));
        assertNull(store.find(99));
        store.remove(1);
        assertIds(store, 2);
        store.remove(99);   // no pasa nada
        assertIds(store, 2);
    }

    @Test
    public void oldestSentId_ignoraLosQueSiguenEnviandose() {
        MessageStore store = new MessageStore();
        assertEquals(0L, store.oldestSentId());
        store.upsert(pending(5));
        assertEquals(0L, store.oldestSentId());
        store.upsert(message(20));
        store.upsert(message(10));
        assertEquals(10L, store.oldestSentId());
    }

    @Test
    public void list_esUnaVistaQueSeActualizaSola() {
        MessageStore store = new MessageStore();
        java.util.List<TdApi.Message> view = store.list();
        assertEquals(0, view.size());
        store.upsert(message(7));
        assertEquals(1, view.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void list_noSePuedeModificarDesdeAfuera() {
        new MessageStore().list().add(message(1));
    }
}
