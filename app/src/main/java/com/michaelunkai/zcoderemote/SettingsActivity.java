package com.michaelunkai.zcoderemote;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private UrlStore urlStore;
    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        urlStore = new UrlStore(this);
        urlInput = findViewById(R.id.url_input);
        urlInput.setText(urlStore.get());

        Button btnSave = findViewById(R.id.btn_save);
        Button btnReset = findViewById(R.id.btn_reset);
        Button btnCancel = findViewById(R.id.btn_cancel);

        btnSave.setOnClickListener(v -> {
            if (urlStore.save(urlInput.getText().toString())) {
                finish();
            } else {
                Toast.makeText(SettingsActivity.this, R.string.toast_invalid_url,
                        Toast.LENGTH_SHORT).show();
            }
        });

        btnReset.setOnClickListener(v -> {
            urlStore.reset();
            urlInput.setText(urlStore.get());
            Toast.makeText(SettingsActivity.this, R.string.toast_reset,
                    Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> finish());
    }
}
