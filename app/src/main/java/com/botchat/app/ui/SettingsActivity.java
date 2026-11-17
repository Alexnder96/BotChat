package com.botchat.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.botchat.app.R;
import com.botchat.app.data.Config;
import com.botchat.app.data.Telegram;

/**
 * Configuración del bot: API ID, API Hash, usuario del bot y título.
 *
 * Se abre sola la primera vez (cuando todavía falta algún dato) y después desde el menú ⋮ → Configuración.
 */
public class SettingsActivity extends Activity {

    private boolean firstTime;

    private EditText apiId;
    private EditText apiHash;
    private EditText botUsername;
    private Switch showTitle;
    private Switch secureChat;
    private TextView titleLabel;
    private EditText titleText;
    private TextView error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        firstTime = !Config.isComplete();
        // Ya configurada: solo se llega aquí después de la contraseña. Si Android recrea la pantalla
        // por su cuenta (sin pasar por la contraseña), no se muestra nada.
        if (savedInstanceState != null && !firstTime) {
            finish();
            return;
        }
        // Muestra el API Hash: sin capturas de pantalla ni miniatura en "recientes".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);

        TextView title = findViewById(R.id.title);
        TextView message = findViewById(R.id.message);
        apiId = findViewById(R.id.api_id);
        apiHash = findViewById(R.id.api_hash);
        botUsername = findViewById(R.id.bot_username);
        showTitle = findViewById(R.id.show_title);
        secureChat = findViewById(R.id.secure_chat);
        titleLabel = findViewById(R.id.title_label);
        titleText = findViewById(R.id.title_text);
        error = findViewById(R.id.error);
        Button save = findViewById(R.id.save);
        View cancel = findViewById(R.id.cancel);

        if (firstTime) {
            title.setText(R.string.settings_first_title);
            message.setText(R.string.settings_first_message);
            save.setText(R.string.settings_save_continue);
            cancel.setVisibility(View.GONE);
        } else {
            title.setText(R.string.settings_title);
            message.setText(R.string.settings_message);
            save.setText(R.string.settings_save);
            cancel.setVisibility(View.VISIBLE);
        }

        if (Config.apiId() > 0) {
            apiId.setText(String.valueOf(Config.apiId()));
        }
        apiHash.setText(Config.apiHash());
        botUsername.setText(Config.botUsername());
        showTitle.setChecked(Config.showTitle());
        secureChat.setChecked(Config.secureChat());
        titleText.setText(Config.title());

        showTitle.setOnCheckedChangeListener((button, checked) -> updateTitleFields());
        updateTitleFields();

        save.setOnClickListener(v -> save());
        cancel.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStop() {
        // Con la configuración ya hecha, al salir de la pantalla se vuelve a pedir la contraseña.
        // (La primera vez no: puedes ir a my.telegram.org a copiar los datos y volver.)
        if (!firstTime && !isChangingConfigurations() && !isFinishing()) {
            finish();
        }
        super.onStop();
    }

    private void updateTitleFields() {
        int visibility = showTitle.isChecked() ? View.VISIBLE : View.GONE;
        titleLabel.setVisibility(visibility);
        titleText.setVisibility(visibility);
    }

    private void save() {
        error.setText("");

        int id;
        try {
            id = Integer.parseInt(apiId.getText().toString().trim());
        } catch (NumberFormatException e) {
            id = 0;
        }
        if (id <= 0) {
            error.setText(R.string.settings_error_api_id);
            return;
        }
        final int newId = id;

        final String newHash = apiHash.getText().toString().trim();
        if (newHash.isEmpty()) {
            error.setText(R.string.settings_error_api_hash);
            return;
        }

        final String newBot = Config.cleanUsername(botUsername.getText().toString());
        if (!newBot.matches("[A-Za-z0-9_]{3,}")) {
            error.setText(R.string.settings_error_bot);
            return;
        }

        final boolean show = showTitle.isChecked();
        final String newTitle = titleText.getText().toString().trim();

        boolean apiChanged = !firstTime && (newId != Config.apiId() || !newHash.equals(Config.apiHash()));
        if (apiChanged) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_api_changed_title)
                    .setMessage(R.string.settings_api_changed_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> apply(newId, newHash, newBot, show, newTitle, true))
                    .show();
        } else {
            apply(newId, newHash, newBot, show, newTitle, false);
        }
    }

    private void apply(int newId, String newHash, String newBot, boolean show, String newTitle,
                       boolean apiChanged) {
        Config.saveConnection(this, newId, newHash, newBot);
        Config.saveTitle(this, show, newTitle);
        Config.saveSecureChat(this, secureChat.isChecked());

        if (firstTime) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        } else if (apiChanged) {
            // Con otro API ID / API Hash hay que empezar la sesión de nuevo.
            Telegram.get(this).resetConnection();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
