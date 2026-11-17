package com.botchat.app.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Permite abrir con otra app (visor de PDF, galería…) un archivo que mandó el bot.
 * Solo da acceso de lectura, solo al archivo que el usuario tocó y solo dentro de la
 * carpeta de descargas de TDLib.
 */
public class TdFileProvider extends ContentProvider {

    /** Devuelve la dirección content:// de un archivo descargado, o null si está fuera de la carpeta. */
    static Uri uriFor(Context context, String path) {
        try {
            String base = Telegram.dataDir(context).getCanonicalPath();
            String file = new File(path).getCanonicalPath();
            if (!file.startsWith(base + File.separator)) {
                return null;
            }
            Uri.Builder builder = new Uri.Builder()
                    .scheme("content")
                    .authority(context.getPackageName() + ".files");
            for (String segment : file.substring(base.length()).split(File.separator)) {
                if (!segment.isEmpty()) {
                    builder.appendPath(segment);
                }
            }
            return builder.build();
        } catch (IOException e) {
            return null;
        }
    }

    static String mimeOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        String path = uri.getPath();
        if (context == null || path == null) {
            throw new FileNotFoundException();
        }
        try {
            File base = Telegram.dataDir(context).getCanonicalFile();
            File file = new File(base, path).getCanonicalFile();
            if (!file.getPath().startsWith(base.getPath() + File.separator) || !file.isFile()) {
                throw new FileNotFoundException();
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Solo lectura");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return mimeOf(uri.getLastPathSegment());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
