package com.michaelunkai.zcoderemote;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native device-control surface: everything the accessibility engine can do,
 * plus volume/media/torch/brightness, Wake-on-LAN for the PC, the "Hey ZCode"
 * wake-word toggle and one-tap setup for every permission this needs.
 */
public class DevicePanelActivity extends AppCompatActivity {

    private static final int REQUEST_MIC = 51;
    private static final int REQUEST_NOTIFICATIONS = 52;

    private LinearLayout root;
    private TextView result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_panel);
        root = findViewById(R.id.panel_root);
        buildUi();
        startServicesIfReady();
    }

    private void startServicesIfReady() {
        SessionKeepService.start(this);
        HotwordService.startIfEnabled(this);
    }

    private void buildUi() {
        LinearLayout header = row();
        TextView title = text(getString(R.string.panel_title), 22, true, 0xFFFFFFFF);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        header.addView(title);
        header.addView(actionButton(getString(R.string.panel_close), this::finish));
        root.addView(header);
        root.addView(text(getString(R.string.panel_subtitle), 13, false, 0xFF9AA3B2));

        root.addView(sectionLabel(getString(R.string.panel_section_navigation)));
        root.addView(buttonRow(
                actionButton("◀ " + getString(R.string.panel_back),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_BACK, feedback())),
                actionButton("⌂ " + getString(R.string.panel_home),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_HOME, feedback())),
                actionButton("▤ " + getString(R.string.panel_recents),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_RECENTS, feedback()))));
        root.addView(buttonRow(
                actionButton("☰ " + getString(R.string.panel_notifications),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_NOTIFICATIONS, feedback())),
                actionButton("⚙ " + getString(R.string.panel_quick_settings),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_QUICK_SETTINGS, feedback())),
                actionButton("⏻ " + getString(R.string.panel_power),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_POWER_DIALOG, feedback()))));
        root.addView(buttonRow(
                actionButton(getString(R.string.panel_lock),
                        () -> DeviceActions.lockScreen(this, feedback())),
                actionButton(getString(R.string.panel_split),
                        () -> DeviceActions.globalAction(this, DeviceActions.GLOBAL_SPLIT_SCREEN, feedback()))));

        root.addView(sectionLabel(getString(R.string.panel_section_sound)));
        root.addView(buttonRow(
                actionButton("Vol +", () -> DeviceActions.volume(this, "up", feedback())),
                actionButton("Vol −", () -> DeviceActions.volume(this, "down", feedback())),
                actionButton("Mute", () -> DeviceActions.volume(this, "mute", feedback()))));
        root.addView(buttonRow(
                actionButton("⏯", () -> DeviceActions.mediaKey(this, "play", feedback())),
                actionButton("⏭", () -> DeviceActions.mediaKey(this, "next", feedback())),
                actionButton("⏮", () -> DeviceActions.mediaKey(this, "prev", feedback()))));

        root.addView(sectionLabel(getString(R.string.panel_section_tools)));
        root.addView(buttonRow(
                actionButton("🔦 On", () -> DeviceActions.torch(this, true, feedback())),
                actionButton("🔦 Off", () -> DeviceActions.torch(this, false, feedback()))));
        root.addView(buttonRow(
                actionButton("📸 " + getString(R.string.panel_screenshot),
                        () -> DeviceActions.screenshot(this, feedback())),
                actionButton("⌨ " + getString(R.string.panel_type_text), this::showTypeTextDialog),
                actionButton("▤ " + getString(R.string.panel_launch_app), this::showAppPicker)));

        root.addView(sectionLabel(getString(R.string.panel_section_brightness)));
        root.addView(buildBrightnessControl());

        root.addView(sectionLabel(getString(R.string.panel_section_wake)));
        root.addView(buildWakePcCard());

        root.addView(sectionLabel(getString(R.string.panel_section_live)));
        root.addView(buildKeepAliveSwitch());
        root.addView(buildHotwordSwitch());

        root.addView(sectionLabel(getString(R.string.panel_section_setup)));
        root.addView(buildSetupGrid());

        result = text(getString(R.string.panel_result_hint), 13, false, 0xFF8B7CFF);
        result.setMinLines(1);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resultParams.topMargin = dp(16);
        result.setLayoutParams(resultParams);
        root.addView(result);
    }

    private LinearLayout buildBrightnessControl() {
        LinearLayout card = card();
        SeekBar seek = new SeekBar(this);
        seek.setMax(255);
        DeviceActions.readBrightness(this, msg -> runOnUiThread(() -> {
            try {
                seek.setProgress(Integer.parseInt(msg.trim()));
            } catch (NumberFormatException ignored) {
            }
        }));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                DeviceActions.setBrightness(DevicePanelActivity.this, bar.getProgress(), feedback());
            }
        });
        TextView label = text(getString(R.string.panel_brightness_hint), 13, false, 0xFF9AA3B2);
        card.addView(label);
        card.addView(seek);
        return card;
    }

    private LinearLayout buildWakePcCard() {
        LinearLayout card = card();
        EditText mac = new EditText(this);
        mac.setHint("AA:BB:CC:DD:EE:FF");
        mac.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        mac.setText(DevicePrefs.getWakeMac(this));
        mac.setTextColor(0xFFFFFFFF);
        mac.setHintTextColor(0xFF6B7280);

        EditText target = new EditText(this);
        target.setHint(getString(R.string.wol_target_hint));
        target.setInputType(InputType.TYPE_CLASS_TEXT);
        target.setText(DevicePrefs.getWakeTarget(this));
        target.setTextColor(0xFFFFFFFF);
        target.setHintTextColor(0xFF6B7280);

        TextView note = text(getString(R.string.wol_note), 12, false, 0xFF9AA3B2);

        Button wake = actionButton("⏻ " + getString(R.string.wol_button), () -> {
            DevicePrefs.setWakeMac(this, mac.getText().toString());
            DevicePrefs.setWakeTarget(this, target.getText().toString());
            feedback().onResult(getString(R.string.wol_sending));
            DeviceActions.wakePc(this, feedback());
        });
        LinearLayout.LayoutParams wakeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wakeParams.topMargin = dp(8);
        wake.setLayoutParams(wakeParams);

        card.addView(labelText(getString(R.string.wol_mac_label)));
        card.addView(mac);
        card.addView(labelText(getString(R.string.wol_target_label)));
        card.addView(target);
        card.addView(wake);
        card.addView(note);
        return card;
    }

    private View buildKeepAliveSwitch() {
        Switch toggle = new Switch(this);
        toggle.setText(getString(R.string.panel_keep_alive));
        toggle.setTextColor(0xFFFFFFFF);
        toggle.setChecked(DevicePrefs.isKeepAliveEnabled(this));
        toggle.setOnCheckedChangeListener((button, on) -> {
            DevicePrefs.setKeepAliveEnabled(this, on);
            if (on) {
                SessionKeepService.start(this);
            } else {
                SessionKeepService.stop(this);
            }
            feedback().onResult(on ? getString(R.string.toast_keep_alive_on)
                    : getString(R.string.toast_keep_alive_off));
        });
        return pad(toggle);
    }

    private View buildHotwordSwitch() {
        Switch toggle = new Switch(this);
        toggle.setText(getString(R.string.panel_hotword));
        toggle.setTextColor(0xFFFFFFFF);
        toggle.setChecked(DevicePrefs.isHotwordEnabled(this));
        toggle.setOnCheckedChangeListener((button, on) -> {
            DevicePrefs.setHotwordEnabled(this, on);
            if (on) {
                if (DeviceActions.hasMicPermission(this)) {
                    HotwordService.startIfEnabled(this);
                    feedback().onResult(getString(R.string.toast_hotword_on));
                } else {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
                }
            } else {
                HotwordService.stop(this);
                feedback().onResult(getString(R.string.toast_hotword_off));
            }
        });
        return pad(toggle);
    }

    private LinearLayout buildSetupGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(setupButton(
                () -> getString(R.string.setup_accessibility, DeviceActions.isAccessibilityEnabled(this)
                        ? "✓" : "—"),
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        grid.addView(setupButton(
                () -> getString(R.string.setup_mic, DeviceActions.hasMicPermission(this) ? "✓" : "—"),
                this::requestMicIfNeeded));
        grid.addView(setupButton(
                () -> getString(R.string.setup_battery, DeviceActions.isIgnoringBatteryOptimizations(this)
                        ? "✓" : "—"),
                this::requestBatteryWhitelist));
        grid.addView(setupButton(
                () -> getString(R.string.setup_write_settings, Settings.System.canWrite(this) ? "✓" : "—"),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts(
                        "package", getPackageName(), null)))));
        grid.addView(setupButton(
                () -> getString(R.string.setup_assistant),
                () -> startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))));
        grid.addView(setupButton(
                () -> getString(R.string.setup_overlay),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts(
                        "package", getPackageName(), null)))));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grid.addView(setupButton(
                    () -> getString(R.string.setup_notifications,
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                    == PackageManager.PERMISSION_GRANTED ? "✓" : "—"),
                    this::requestNotificationsIfNeeded));
        }
        return grid;
    }

    private Button setupButton(java.util.function.Supplier<String> label, Runnable onClick) {
        Button button = actionButton(label.get(), onClick);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private void refreshSetup() {
        root.removeAllViews();
        buildUi();
    }

    private void requestMicIfNeeded() {
        if (!DeviceActions.hasMicPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        }
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void requestBatteryWhitelist() {
        if (DeviceActions.isIgnoringBatteryOptimizations(this)) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void showTypeTextDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(24), dp(12), dp(24), 0);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTextColor(0xFFFFFFFF);
        container.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.panel_type_text)
                .setView(container)
                .setPositiveButton(R.string.panel_send, (dialog, which) ->
                        DeviceActions.typeText(this, input.getText().toString(), feedback()))
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void showAppPicker() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(main, 0);
        List<Object[]> entries = new ArrayList<>();
        for (ResolveInfo info : apps) {
            if (getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            entries.add(new Object[]{String.valueOf(info.loadLabel(getPackageManager())), info});
        }
        if (entries.isEmpty()) {
            feedback().onResult(getString(R.string.action_app_not_found));
            return;
        }
        entries.sort((a, b) ->
                String.CASE_INSENSITIVE_ORDER.compare((String) a[0], (String) b[0]));
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = (String) entries.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.panel_launch_app)
                .setItems(labels, (dialog, which) -> {
                    ResolveInfo info = (ResolveInfo) entries.get(which)[1];
                    DeviceActions.launchApp(this, info.activityInfo.packageName, feedback());
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MIC) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                HotwordService.startIfEnabled(this);
                feedback().onResult(getString(R.string.toast_hotword_on));
            } else {
                DevicePrefs.setHotwordEnabled(this, false);
                feedback().onResult(getString(R.string.toast_hotword_needs_mic));
            }
            refreshSetup();
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            refreshSetup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
    }

    /** Toast feedback used from non-activity callers (JS bridge). */
    public static DeviceActions.Callback toastFeedback(Context context) {
        Context app = context.getApplicationContext();
        return message -> new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
    }

    private DeviceActions.Callback feedback() {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        return message -> runOnUiThread(() -> result.setText(stamp + "  " + message));
    }

    // ---- tiny programmatic-UI helpers ----

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = row();
        for (Button button : buttons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(button, params);
        }
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(14));
        card.setBackgroundColor(0xFF131824);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(6), dp(4), dp(6));
        card.setLayoutParams(params);
        return card;
    }

    private TextView sectionLabel(String title) {
        TextView label = text(title, 14, true, 0xFF8B7CFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(22);
        params.bottomMargin = dp(4);
        label.setLayoutParams(params);
        return label;
    }

    private TextView labelText(String title) {
        TextView label = text(title, 13, false, 0xFF9AA3B2);
        label.setPadding(0, dp(8), 0, dp(2));
        return label;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return view;
    }

    private Button actionButton(String label, Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private View pad(View view) {
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
