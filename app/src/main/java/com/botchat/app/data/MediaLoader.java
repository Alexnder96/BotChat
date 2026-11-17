package com.botchat.app.data;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.SparseArray;
import android.widget.Toast;

import com.botchat.app.R;

import org.drinkless.tdlib.TdApi;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Se encarga de los archivos y fotos del chat: pide las descargas a Telegram, recuerda cómo va cada una,
 * convierte las fotos en imágenes (con una memoria que no crece sin límite) y abre los archivos con otra app.
 * Quien dibuja los mensajes (MessageAdapter) solo le pregunta y se entera de los cambios por el Listener.
 */
public final class MediaLoader {

    public interface Listener {
        /** Cambió algo que se ve (avance de una descarga, una foto lista). downloadCompleted: un archivo terminó. */
        void onChanged(boolean downloadCompleted);

        /** Una descarga falló. */
        void onDownloadFailed();
    }

    private final Activity activity;
    private final Telegram telegram;
    private final Listener listener;

    /** Último estado conocido de cada archivo (TDLib avisa del avance de las descargas). */
    private final SparseArray<TdApi.File> files = new SparseArray<>();
    private final SparseArray<String> openWhenReady = new SparseArray<>();
    private final HashSet<Integer> requested = new HashSet<>();
    private final HashSet<Integer> decoding = new HashSet<>();
    private final HashSet<Integer> broken = new HashSet<>();
    private final HashSet<Integer> downloadFailed = new HashSet<>();
    private final LruCache<Integer, Bitmap> bitmaps;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean released;

    public MediaLoader(Activity activity, Telegram telegram, Listener listener) {
        this.activity = activity;
        this.telegram = telegram;
        this.listener = listener;
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        this.bitmaps = new LruCache<Integer, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void release() {
        released = true;
        decoder.shutdown();
    }

    /** El estado más reciente que se conoce de un archivo. */
    public TdApi.File latest(TdApi.File file) {
        TdApi.File known = files.get(file.id);
        return known != null ? known : file;
    }

    public static boolean isDownloaded(TdApi.File file) {
        return file.local != null && file.local.isDownloadingCompleted
                && file.local.path != null && !file.local.path.isEmpty();
    }

    /** true si Telegram no pudo descargar ese archivo. */
    public boolean hasFailed(int fileId) {
        return downloadFailed.contains(fileId);
    }

    /** La imagen ya lista de una foto, o null si todavía no está. */
    public Bitmap bitmap(int fileId) {
        return bitmaps.get(fileId);
    }

    /** Hace lo necesario para tener la imagen de la foto: descargarla o, si ya está descargada, prepararla. */
    public void ensureBitmap(TdApi.File file) {
        if (isDownloaded(file)) {
            decode(file);
        } else {
            download(file, 1);
        }
    }

    /** La pantalla de chat lo llama cada vez que TDLib avisa del avance de una descarga. */
    public void onFile(TdApi.File file) {
        if (file == null || released) {
            return;
        }
        files.put(file.id, file);
        boolean done = isDownloaded(file);
        if (done) {
            String mime = openWhenReady.get(file.id);
            if (mime != null) {
                openWhenReady.remove(file.id);
                openNow(file, mime);
            }
        }
        listener.onChanged(done);
    }

    /** Elige la versión más grande de la foto que no pase de 1280 px (o la más chica si todas pasan). */
    public static TdApi.PhotoSize pickSize(TdApi.Photo photo) {
        if (photo == null || photo.sizes == null || photo.sizes.length == 0) {
            return null;
        }
        TdApi.PhotoSize best = null;
        TdApi.PhotoSize smallest = photo.sizes[0];
        for (TdApi.PhotoSize size : photo.sizes) {
            int side = Math.max(size.width, size.height);
            if (side <= 1280 && (best == null || side > Math.max(best.width, best.height))) {
                best = size;
            }
            if (side < Math.max(smallest.width, smallest.height)) {
                smallest = size;
            }
        }
        return best != null ? best : smallest;
    }

    public void download(TdApi.File file, int priority) {
        final int id = file.id;
        if (!requested.add(id)) {
            return;
        }
        telegram.send(new TdApi.DownloadFile(id, priority, 0, 0, false), result -> {
            if (result instanceof TdApi.File) {
                onFile((TdApi.File) result);
            } else {
                requested.remove(id);
                downloadFailed.add(id);
                listener.onDownloadFailed();
                if (openWhenReady.get(id) != null) {
                    openWhenReady.remove(id);
                    toast(R.string.file_download_failed);
                }
            }
        });
    }

    private void decode(TdApi.File file) {
        final int id = file.id;
        final String path = file.local.path;
        if (released || broken.contains(id) || !decoding.add(id)) {
            return;
        }
        decoder.execute(() -> {
            final Bitmap bitmap = decodeSampled(path);
            main.post(() -> {
                decoding.remove(id);
                if (bitmap != null) {
                    bitmaps.put(id, bitmap);
                } else {
                    broken.add(id);
                }
                if (!released) {
                    listener.onChanged(false);
                }
            });
        });
    }

    private static Bitmap decodeSampled(String path) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int side = Math.max(options.outWidth, options.outHeight);
            int sample = 1;
            while (side / sample > 1280) {
                sample *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Abre el archivo con otra app. Si todavía no está descargado, lo baja y lo abre al terminar. */
    public void open(TdApi.File file, String mime) {
        TdApi.File current = latest(file);
        if (isDownloaded(current)) {
            openNow(current, mime);
            return;
        }
        openWhenReady.put(current.id, mime == null ? "" : mime);
        download(current, 16);
        listener.onChanged(false);
    }

    private void openNow(TdApi.File file, String mime) {
        Uri uri = TdFileProvider.uriFor(activity, file.local.path);
        if (uri == null) {
            toast(R.string.file_open_failed);
            return;
        }
        if (mime == null || mime.isEmpty()) {
            mime = TdFileProvider.mimeOf(file.local.path);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.file_no_app);
        }
    }

    private void toast(int resource) {
        Toast.makeText(activity, resource, Toast.LENGTH_SHORT).show();
    }
}
