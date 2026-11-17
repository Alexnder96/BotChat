package com.botchat.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.botchat.app.R;
import com.botchat.app.data.Config;
import com.botchat.app.data.Telegram;
import com.botchat.app.filters.Filters;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Las ventanas de contraseña de los filtros: crearla la primera vez, pedirla para entrar,
 * cambiarla y "restablecer el bot" si se perdió.
 *
 * Calcular el hash de la contraseña es lento a propósito, así que se hace en otro hilo para no trabar la pantalla.
 * Tras varios errores seguidos hay que esperar un rato antes de volver a intentar (ver Lockout).
 */
final class FilterGate {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "filter-gate");
        thread.setDaemon(true);
        return thread;
    });

    private FilterGate() {
    }

    /** Pide la contraseña (o la crea si es la primera vez) y, si es correcta, ejecuta onGranted. */
    static void open(Activity activity, Runnable onGranted) {
        if (Filters.hasPassword()) {
            ask(activity, onGranted);
        } else {
            create(activity, false, onGranted);
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void ask(final Activity activity, final Runnable onGranted) {
        LinearLayout box = box(activity);
        final EditText password = field(activity, R.string.password_hint);
        final TextView error = errorView(activity);
        box.addView(password);
        box.addView(error);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.password_ask_title)
                .setMessage(R.string.password_ask_message)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.password_forgot, null)
                .setPositiveButton(R.string.password_ok, null)
                .create();
        showKeyboard(dialog);
        dialog.show();

        final Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        long waiting = Filters.lockRemainingMs();
        if (waiting > 0) {
            error.setText(lockedMessage(activity, waiting));
        }

        // Se reemplazan los clics para que la ventana no se cierre sola si la contraseña es incorrecta.
        ok.setOnClickListener(v -> {
            long wait = Filters.lockRemainingMs();
            if (wait > 0) {
                error.setText(lockedMessage(activity, wait));
                return;
            }
            final String typed = password.getText().toString();
            error.setText(R.string.password_checking);
            ok.setEnabled(false);
            WORKER.execute(() -> {
                final int result = Filters.checkPassword(typed);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) {
                        return;
                    }
                    ok.setEnabled(true);
                    if (result == Filters.PASSWORD_OK) {
                        dialog.dismiss();
                        onGranted.run();
                    } else if (result == Filters.PASSWORD_LOCKED) {
                        error.setText(lockedMessage(activity, Filters.lockRemainingMs()));
                        password.setText("");
                    } else {
                        error.setText(R.string.password_wrong);
                        password.setText("");
                    }
                });
            });
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmReset(activity, dialog));
    }

    /**
     * "Restablecer el bot": tras confirmar borra filtros, contraseña, configuración (API ID, API Hash, usuario)
     * y cierra la sesión de Telegram. Así, quien olvide la contraseña no se queda con acceso a los códigos:
     * la app vuelve a la pantalla de configuración inicial, como recién instalada.
     */
    private static void confirmReset(final Activity activity, final AlertDialog passwordDialog) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_confirm, (d, which) -> {
                    passwordDialog.dismiss();
                    Telegram.get(activity).resetConnection();   // cierra la sesión (antes de borrar la configuración)
                    Filters.resetAll();
                    Config.clear(activity);
                    Toast.makeText(activity, R.string.reset_done, Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(activity, LoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    activity.startActivity(intent);
                })
                .show();
    }

    /** Crear una contraseña nueva (primera vez) o cambiar la que existe. */
    static void create(final Activity activity, final boolean change, final Runnable onDone) {
        LinearLayout box = box(activity);
        final EditText first = field(activity, R.string.password_hint);
        final EditText second = field(activity, R.string.password_confirm_hint);
        final TextView error = errorView(activity);
        box.addView(first);
        box.addView(second);
        box.addView(error);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(change ? R.string.password_change_title : R.string.password_create_title)
                .setMessage(R.string.password_create_message)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.password_ok, null)
                .create();
        showKeyboard(dialog);
        dialog.show();

        final Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setOnClickListener(v -> {
            final String a = first.getText().toString();
            String b = second.getText().toString();
            if (a.length() < Filters.MIN_PASSWORD) {
                error.setText(R.string.password_short);
            } else if (!a.equals(b)) {
                error.setText(R.string.password_mismatch);
            } else {
                error.setText(R.string.password_checking);
                ok.setEnabled(false);
                WORKER.execute(() -> {
                    Filters.setPassword(a);
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) {
                            return;
                        }
                        dialog.dismiss();
                        if (change) {
                            Toast.makeText(activity, R.string.password_changed, Toast.LENGTH_SHORT).show();
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                    });
                });
            }
        });
    }

    // ---------------------------------------------------------------------------------------

    private static String lockedMessage(Activity activity, long remainingMs) {
        long seconds = Math.max(1, (remainingMs + 999) / 1000);
        String wait = seconds < 60 ? seconds + " s" : ((seconds + 59) / 60) + " min";
        return activity.getString(R.string.password_locked, wait);
    }

    private static void showKeyboard(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private static LinearLayout box(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int side = dp(activity, 24);
        box.setPadding(side, dp(activity, 8), side, 0);
        return box;
    }

    private static EditText field(Activity activity, int hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        // setSingleLine va ANTES de setInputType: si no, deshace el enmascarado y la contraseña se vería.
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setBackgroundResource(R.drawable.field_background);
        field.setTextColor(activity.getColor(R.color.text_primary));
        field.setHintTextColor(activity.getColor(R.color.text_secondary));
        field.setTextSize(16);
        field.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(activity, 18);
        field.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 48));
        params.topMargin = dp(activity, 8);
        field.setLayoutParams(params);
        return field;
    }

    private static TextView errorView(Activity activity) {
        TextView view = new TextView(activity);
        view.setTextColor(activity.getColor(R.color.error));
        view.setTextSize(13);
        view.setVisibility(View.VISIBLE);
        view.setMinHeight(dp(activity, 24));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
