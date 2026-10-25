package com.michaelunkai.zcoderemote;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/**
 * Zero-navigation QR pairing: adb (`am start -n com.michaelunkai.zcoderemote/.QrScanActivity`)
 * or the launcher shortcut opens the camera while the desktop "Mobile remote
 * control" QR panel is on screen. Desktop remote links expire within about a
 * minute of being minted, so scanning the live QR — and loading it
 * immediately — is the reliable pairing path; a saved-but-stale link strands
 * the SPA on the sessions menu instead of the chat.
 */
public class QrScanActivity extends AppCompatActivity {

    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                String url = result.getContents() == null ? "" : result.getContents().trim();
                if (url.isEmpty()) {
                    finish();
                    return;
                }
                if (new UrlStore(this).save(url)) {
                    Intent open = new Intent(this, MainActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(open);
                } else {
                    Toast.makeText(this, R.string.toast_invalid_url, Toast.LENGTH_LONG).show();
                }
                finish();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scanner.launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true));
    }
}
