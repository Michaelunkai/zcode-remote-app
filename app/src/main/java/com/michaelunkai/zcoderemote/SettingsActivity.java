package com.michaelunkai.zcoderemote;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

public class SettingsActivity extends AppCompatActivity {

    private UrlStore urlStore;
    private EditText urlInput;
    private Button btnSave;

    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                String url = result.getContents() == null ? "" : result.getContents().trim();
                if (!url.isEmpty()) {
                    urlInput.setText(url);
                    btnSave.performClick();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        urlStore = new UrlStore(this);
        urlInput = findViewById(R.id.url_input);
        urlInput.setText(urlStore.get());

        btnSave = findViewById(R.id.btn_save);
        Button btnScan = findViewById(R.id.btn_scan);
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

        btnScan.setOnClickListener(v -> scanner.launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)));

        btnReset.setOnClickListener(v -> {
            urlStore.reset();
            urlInput.setText(urlStore.get());
            Toast.makeText(SettingsActivity.this, R.string.toast_reset,
                    Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> finish());
    }
}
