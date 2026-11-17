package com.botchat.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.botchat.app.R;
import com.botchat.app.filters.Filters;

import java.util.List;

/**
 * Pantalla de filtros. Solo se abre después de escribir la contraseña (ver FilterGate).
 * Si sales de la app y vuelves, se cierra: hay que escribir la contraseña otra vez.
 */
public class FiltersActivity extends Activity {

    private interface Remover {
        void remove(String value);
    }

    private EditText wordInput;
    private EditText imageInput;
    private LinearLayout wordList;
    private LinearLayout imageList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Si Android recrea la pantalla desde cero (sin pasar por la contraseña), no se muestra nada.
        if (savedInstanceState != null) {
            finish();
            return;
        }
        // Muestra tus palabras filtradas: sin capturas de pantalla ni miniatura en "recientes".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_filters);

        wordInput = findViewById(R.id.word_input);
        imageInput = findViewById(R.id.image_input);
        wordList = findViewById(R.id.word_list);
        imageList = findViewById(R.id.image_list);

        findViewById(R.id.word_add).setOnClickListener(v -> addWord());
        findViewById(R.id.image_add).setOnClickListener(v -> addImage());
        wordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addWord();
                return true;
            }
            return false;
        });
        imageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addImage();
                return true;
            }
            return false;
        });
        findViewById(R.id.change_password).setOnClickListener(v -> FilterGate.create(this, true, null));
        findViewById(R.id.done).setOnClickListener(v -> finish());

        render();
    }

    @Override
    protected void onStop() {
        // Al salir de la pantalla (otra app, bloqueo) se vuelve a pedir la contraseña.
        if (!isChangingConfigurations()) {
            finish();
        }
        super.onStop();
    }

    private void addWord() {
        if (Filters.addKeyword(wordInput.getText().toString())) {
            wordInput.setText("");
            render();
        } else if (!wordInput.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.filters_duplicate, Toast.LENGTH_SHORT).show();
        }
    }

    private void addImage() {
        if (Filters.addImageName(imageInput.getText().toString())) {
            imageInput.setText("");
            render();
        } else if (!imageInput.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.filters_duplicate, Toast.LENGTH_SHORT).show();
        }
    }

    private void render() {
        fill(wordList, Filters.keywords(), value -> {
            Filters.removeKeyword(value);
            render();
        });
        fill(imageList, Filters.imageNames(), value -> {
            Filters.removeImageName(value);
            render();
        });
    }

    private void fill(LinearLayout container, List<String> values, final Remover remover) {
        container.removeAllViews();
        if (values.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.filters_none);
            none.setTextColor(getColor(R.color.text_secondary));
            none.setTextSize(14);
            none.setPadding(0, dp(8), 0, dp(8));
            container.addView(none);
            return;
        }
        for (final String value : values) {
            View row = getLayoutInflater().inflate(R.layout.item_filter, container, false);
            ((TextView) row.findViewById(R.id.value)).setText(value);
            row.findViewById(R.id.remove).setOnClickListener(v -> remover.remove(value));
            container.addView(row);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
