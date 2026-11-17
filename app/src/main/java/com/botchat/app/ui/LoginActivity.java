package com.botchat.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.botchat.app.R;
import com.botchat.app.data.Config;
import com.botchat.app.data.Telegram;

import org.drinkless.tdlib.TdApi;

/**
 * Pantalla de inicio de sesión: número, código y (si la tienes) contraseña de dos pasos.
 * Solo se usa la primera vez; después la app entra directo al chat.
 */
public class LoginActivity extends Activity implements Telegram.Listener {

    private static final int MODE_NONE = 0;
    private static final int MODE_PHONE = 1;
    private static final int MODE_CODE = 2;
    private static final int MODE_PASSWORD = 3;
    private static final int MODE_EMAIL = 4;
    private static final int MODE_EMAIL_CODE = 5;

    private Telegram telegram;
    private TextView title;
    private TextView message;
    private TextView error;
    private TextView link;
    private EditText input;
    private Button button;
    private ProgressBar progress;
    private int mode = MODE_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Aquí se escriben el número y el código de Telegram: sin capturas ni miniatura en "recientes".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_login);

        title = findViewById(R.id.title);
        message = findViewById(R.id.message);
        error = findViewById(R.id.error);
        link = findViewById(R.id.link);
        input = findViewById(R.id.input);
        button = findViewById(R.id.button);
        progress = findViewById(R.id.progress);

        button.setOnClickListener(v -> submit());
        link.setOnClickListener(v -> askPhone());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });

        findViewById(R.id.settings_link).setOnClickListener(
                v -> FilterGate.open(this, () -> startActivity(new Intent(this, SettingsActivity.class))));

        if (!Config.isComplete()) {
            // Primera vez: antes de iniciar sesión hay que configurar el bot.
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }

        telegram = Telegram.get(this);
        telegram.addListener(this);
        telegram.start();
        render(telegram.getAuthState());
    }

    @Override
    protected void onDestroy() {
        if (telegram != null) {
            telegram.removeListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onAuthState(TdApi.AuthorizationState state) {
        render(state);
    }

    @Override
    public void onUpdate(TdApi.Object update) {
        // Esta pantalla solo necesita el estado de la sesión.
    }

    /** Muestra lo que Telegram está pidiendo en este momento. */
    private void render(TdApi.AuthorizationState state) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (state == null) {
            showLoading(getString(R.string.login_connecting));
            return;
        }
        switch (state.getConstructor()) {
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
                askPhone();
                break;
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
                showInput(MODE_CODE, getString(R.string.login_code_title),
                        codeMessage(((TdApi.AuthorizationStateWaitCode) state).codeInfo),
                        getString(R.string.login_code_hint), InputType.TYPE_CLASS_NUMBER, true);
                break;
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                String hint = ((TdApi.AuthorizationStateWaitPassword) state).passwordHint;
                String text = getString(R.string.login_password_message);
                if (hint != null && !hint.isEmpty()) {
                    text += "\n" + getString(R.string.login_password_hint_is, hint);
                }
                showInput(MODE_PASSWORD, getString(R.string.login_password_title), text,
                        getString(R.string.login_password_hint),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
                break;
            }
            case TdApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR:
                showInput(MODE_EMAIL, getString(R.string.login_email_title),
                        getString(R.string.login_email_message), getString(R.string.login_email_hint),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, true);
                break;
            case TdApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR: {
                TdApi.EmailAddressAuthenticationCodeInfo info =
                        ((TdApi.AuthorizationStateWaitEmailCode) state).codeInfo;
                String address = info == null ? "" : info.emailAddressPattern;
                showInput(MODE_EMAIL_CODE, getString(R.string.login_code_title),
                        getString(R.string.login_email_code_message, address),
                        getString(R.string.login_code_hint), InputType.TYPE_CLASS_NUMBER, true);
                break;
            }
            case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR:
                showInfo(getString(R.string.login_no_account_title),
                        getString(R.string.login_no_account_message), true);
                break;
            case TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitPremiumPurchase.CONSTRUCTOR:
                showInfo(getString(R.string.login_unsupported_title),
                        getString(R.string.login_unsupported_message), true);
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                startActivity(new Intent(this, ChatActivity.class));
                finish();
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                showLoading(getString(R.string.login_closing));
                break;
            default:
                showLoading(getString(R.string.login_connecting));
                break;
        }
    }

    private String codeMessage(TdApi.AuthenticationCodeInfo info) {
        if (info == null || info.type == null) {
            return getString(R.string.login_code_generic);
        }
        switch (info.type.getConstructor()) {
            case TdApi.AuthenticationCodeTypeTelegramMessage.CONSTRUCTOR:
                return getString(R.string.login_code_in_app);
            case TdApi.AuthenticationCodeTypeSms.CONSTRUCTOR:
            case TdApi.AuthenticationCodeTypeSmsWord.CONSTRUCTOR:
            case TdApi.AuthenticationCodeTypeSmsPhrase.CONSTRUCTOR:
                return getString(R.string.login_code_sms, info.phoneNumber);
            case TdApi.AuthenticationCodeTypeCall.CONSTRUCTOR:
                return getString(R.string.login_code_call, info.phoneNumber);
            default:
                return getString(R.string.login_code_generic);
        }
    }

    private void askPhone() {
        showInput(MODE_PHONE, getString(R.string.login_phone_title), getString(R.string.login_phone_message),
                getString(R.string.login_phone_hint), InputType.TYPE_CLASS_PHONE, false);
    }

    private void showLoading(String text) {
        mode = MODE_NONE;
        title.setText(R.string.app_name);
        message.setText(text);
        error.setText("");
        input.setVisibility(View.GONE);
        button.setVisibility(View.GONE);
        link.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
    }

    private void showInfo(String titleText, String text, boolean canChangeNumber) {
        mode = MODE_NONE;
        title.setText(titleText);
        message.setText(text);
        error.setText("");
        input.setVisibility(View.GONE);
        button.setVisibility(View.GONE);
        link.setVisibility(canChangeNumber ? View.VISIBLE : View.GONE);
        progress.setVisibility(View.GONE);
    }

    private void showInput(int newMode, String titleText, String text, String hint, int inputType,
                           boolean canChangeNumber) {
        boolean changed = mode != newMode;
        mode = newMode;
        title.setText(titleText);
        message.setText(text);
        if (changed) {
            error.setText("");
            input.setText("");
            input.setInputType(inputType);
        }
        input.setHint(hint);
        input.setVisibility(View.VISIBLE);
        button.setVisibility(View.VISIBLE);
        link.setVisibility(canChangeNumber ? View.VISIBLE : View.GONE);
        setBusy(false);
        input.requestFocus();
    }

    private void setBusy(boolean busy) {
        button.setEnabled(!busy);
        input.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    /** Envía a Telegram lo que el usuario escribió, según lo que se esté pidiendo. */
    private void submit() {
        if (telegram == null || mode == MODE_NONE || !button.isEnabled()) {
            return;
        }
        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            return;
        }

        final TdApi.Function<?> query;
        switch (mode) {
            case MODE_PHONE: {
                String phone = value.replaceAll("[\\s\\-()]", "");
                if (!phone.startsWith("+")) {
                    error.setText(R.string.error_phone_no_country);
                    return;
                }
                query = new TdApi.SetAuthenticationPhoneNumber(phone, null);
                break;
            }
            case MODE_CODE:
                query = new TdApi.CheckAuthenticationCode(value);
                break;
            case MODE_PASSWORD:
                // La contraseña no se recorta: puede tener espacios.
                query = new TdApi.CheckAuthenticationPassword(input.getText().toString());
                break;
            case MODE_EMAIL:
                query = new TdApi.SetAuthenticationEmailAddress(value);
                break;
            case MODE_EMAIL_CODE:
                query = new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(value));
                break;
            default:
                return;
        }

        error.setText("");
        setBusy(true);
        telegram.send(query, result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (result instanceof TdApi.Error) {
                setBusy(false);
                error.setText(Telegram.describe(this, (TdApi.Error) result));
            } else {
                // Si salió bien, Telegram avisa el paso siguiente por onAuthState().
                render(telegram.getAuthState());
            }
        });
    }
}
