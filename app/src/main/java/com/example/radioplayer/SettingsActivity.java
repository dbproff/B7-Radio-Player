package com.example.radioplayer;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SettingsActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean stationsChanged = false;
    private View accentPreviewSwatch;
    private TextView accentCurrentLabel;
    private LinearLayout accentPresetsContainer;
    private TextView settingsThemeHeader, settingsAccentHeader, settingsCaptureHeader,
            settingsLibraryHeader, settingsAboutHeader;
    private RadioButton themeSystemRadio, themeDarkRadio, themeLightRadio;
    private RadioGroup captureLocationGroup;
    private RadioButton captureMusicRadio, captureDownloadsRadio, captureCustomRadio;
    private TextView captureCustomFolderText;

    private final ActivityResultLauncher<Uri> chooseCaptureFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    try {
                        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(uri, flags);
                    } catch (Exception ignored) {}
                    StreamRecorder.setCustomFolderUri(this, uri.toString());
                    StreamRecorder.setCaptureLocationMode(this, StreamRecorder.LOCATION_CUSTOM);
                    if (captureLocationGroup != null) {
                        captureLocationGroup.check(R.id.captureCustomRadio);
                    }
                    updateCaptureFolderLabel();
                    Toast.makeText(this, "Capture folder updated", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ActivityResultLauncher<String[]> importDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importStationsFromDocument(uri);
                }
            }
    );

    private final ActivityResultLauncher<String> exportDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/xml"),
            uri -> {
                if (uri != null) {
                    exportAllStationsToUri(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton backButton = findViewById(R.id.settingsBackButton);
        backButton.setOnClickListener(v -> finishWithResult());

        RadioGroup themeGroup = findViewById(R.id.themeRadioGroup);
        int currentTheme = ThemeHelper.getSavedTheme(this);
        if (currentTheme == ThemeHelper.THEME_DARK) {
            themeGroup.check(R.id.themeDarkRadio);
        } else if (currentTheme == ThemeHelper.THEME_LIGHT) {
            themeGroup.check(R.id.themeLightRadio);
        } else {
            themeGroup.check(R.id.themeSystemRadio);
        }

        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int selectedMode = ThemeHelper.THEME_SYSTEM;
            if (checkedId == R.id.themeDarkRadio) {
                selectedMode = ThemeHelper.THEME_DARK;
            } else if (checkedId == R.id.themeLightRadio) {
                selectedMode = ThemeHelper.THEME_LIGHT;
            }
            if (selectedMode != ThemeHelper.getSavedTheme(this)) {
                ThemeHelper.saveAndApplyTheme(this, selectedMode);
            }
        });

        settingsThemeHeader = findViewById(R.id.settingsThemeHeader);
        settingsAccentHeader = findViewById(R.id.settingsAccentHeader);
        settingsCaptureHeader = findViewById(R.id.settingsCaptureHeader);
        settingsLibraryHeader = findViewById(R.id.settingsLibraryHeader);
        settingsAboutHeader = findViewById(R.id.settingsAboutHeader);
        themeSystemRadio = findViewById(R.id.themeSystemRadio);
        themeDarkRadio = findViewById(R.id.themeDarkRadio);
        themeLightRadio = findViewById(R.id.themeLightRadio);
        captureLocationGroup = findViewById(R.id.captureLocationGroup);
        captureMusicRadio = findViewById(R.id.captureMusicRadio);
        captureDownloadsRadio = findViewById(R.id.captureDownloadsRadio);
        captureCustomRadio = findViewById(R.id.captureCustomRadio);
        captureCustomFolderText = findViewById(R.id.captureCustomFolderText);
        accentPreviewSwatch = findViewById(R.id.accentPreviewSwatch);
        accentCurrentLabel = findViewById(R.id.accentCurrentLabel);
        accentPresetsContainer = findViewById(R.id.accentPresetsContainer);

        setupCaptureLocationControls();
        findViewById(R.id.settingsCustomColorButton).setOnClickListener(v -> showCustomColorPickerDialog());
        setupAccentPresets();
        applySettingsAccent();

        findViewById(R.id.settingsImportUrlButton).setOnClickListener(v -> showImportUrlDialog());
        findViewById(R.id.settingsImportFileButton).setOnClickListener(v ->
                importDocumentLauncher.launch(new String[]{"text/xml", "application/xml", "audio/x-mpegurl", "audio/x-scpls", "*/*"}));
        findViewById(R.id.settingsExportFileButton).setOnClickListener(v ->
                exportDocumentLauncher.launch("radio_stations.xml"));
        findViewById(R.id.settingsUpdateImagesButton).setOnClickListener(v -> showUpdateImagesDialog());
        findViewById(R.id.settingsScanDeadButton).setOnClickListener(v -> scanForDeadStations());

        TextView versionText = findViewById(R.id.aboutVersionText);
        String versionName = "0.0.1";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) {
                versionName = info.versionName;
            }
        } catch (Exception ignored) {}
        versionText.setText("Version " + versionName);
    }

    private void setupCaptureLocationControls() {
        int mode = StreamRecorder.getCaptureLocationMode(this);
        if (mode == StreamRecorder.LOCATION_DOWNLOADS) {
            captureLocationGroup.check(R.id.captureDownloadsRadio);
        } else if (mode == StreamRecorder.LOCATION_CUSTOM && !StreamRecorder.getCustomFolderUri(this).isEmpty()) {
            captureLocationGroup.check(R.id.captureCustomRadio);
        } else {
            captureLocationGroup.check(R.id.captureMusicRadio);
        }
        updateCaptureFolderLabel();

        captureLocationGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.captureDownloadsRadio) {
                StreamRecorder.setCaptureLocationMode(this, StreamRecorder.LOCATION_DOWNLOADS);
            } else if (checkedId == R.id.captureCustomRadio) {
                if (StreamRecorder.getCustomFolderUri(this).isEmpty()) {
                    chooseCaptureFolderLauncher.launch(null);
                } else {
                    StreamRecorder.setCaptureLocationMode(this, StreamRecorder.LOCATION_CUSTOM);
                }
            } else {
                StreamRecorder.setCaptureLocationMode(this, StreamRecorder.LOCATION_MUSIC);
            }
        });

        findViewById(R.id.settingsChooseCaptureFolderButton).setOnClickListener(v ->
                chooseCaptureFolderLauncher.launch(null));
    }

    private void updateCaptureFolderLabel() {
        if (captureCustomFolderText == null) return;
        String uriStr = StreamRecorder.getCustomFolderUri(this);
        if (uriStr == null || uriStr.isEmpty()) {
            captureCustomFolderText.setText("No custom folder selected");
        } else {
            Uri uri = Uri.parse(uriStr);
            String seg = uri.getLastPathSegment();
            captureCustomFolderText.setText("Folder: " + (seg != null ? seg : uriStr));
        }
    }

    private void setupAccentPresets() {
        if (accentPresetsContainer == null) return;
        accentPresetsContainer.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(36 * density);
        int margin = Math.round(10 * density);
        int strokeSelected = Math.round(3 * density);
        int savedColor = ThemeHelper.getSavedAccentColor(this);
        int defaultViolet = ContextCompat.getColor(this, R.color.app_accent);
        int borderColor = ContextCompat.getColor(this, R.color.app_text_primary);

        for (int i = 0; i < ThemeHelper.PRESET_COLORS.length; i++) {
            final int presetValue = ThemeHelper.PRESET_COLORS[i];
            int displayColor = (presetValue == 0) ? defaultViolet : presetValue;
            boolean isSelected = (savedColor == presetValue);

            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(margin);
            swatch.setLayoutParams(lp);

            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(displayColor);
            if (isSelected) {
                circle.setStroke(strokeSelected, borderColor);
            }
            swatch.setBackground(circle);
            swatch.setContentDescription(ThemeHelper.PRESET_NAMES[i]);
            swatch.setOnClickListener(v -> {
                ThemeHelper.saveAccentColor(this, presetValue);
                setupAccentPresets();
                applySettingsAccent();
            });
            accentPresetsContainer.addView(swatch);
        }
    }

    private void applySettingsAccent() {
        int accent = ThemeHelper.getAccentColor(this);
        int saved = ThemeHelper.getSavedAccentColor(this);

        if (settingsThemeHeader != null) settingsThemeHeader.setTextColor(accent);
        if (settingsAccentHeader != null) settingsAccentHeader.setTextColor(accent);
        if (settingsCaptureHeader != null) settingsCaptureHeader.setTextColor(accent);
        if (settingsLibraryHeader != null) settingsLibraryHeader.setTextColor(accent);
        if (settingsAboutHeader != null) settingsAboutHeader.setTextColor(accent);

        ThemeHelper.tintRadioButton(themeSystemRadio, accent);
        ThemeHelper.tintRadioButton(themeDarkRadio, accent);
        ThemeHelper.tintRadioButton(themeLightRadio, accent);
        ThemeHelper.tintRadioButton(captureMusicRadio, accent);
        ThemeHelper.tintRadioButton(captureDownloadsRadio, accent);
        ThemeHelper.tintRadioButton(captureCustomRadio, accent);

        if (accentPreviewSwatch != null) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(accent);
            circle.setStroke(Math.round(1.5f * getResources().getDisplayMetrics().density),
                    ContextCompat.getColor(this, R.color.app_divider));
            accentPreviewSwatch.setBackground(circle);
        }
        if (accentCurrentLabel != null) {
            accentCurrentLabel.setText(ThemeHelper.formatColorName(saved));
        }
    }

    private void showCustomColorPickerDialog() {
        int initialColor = ThemeHelper.getAccentColor(this);
        final float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);

        View preview = new View(this);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(48 * density));
        previewLp.bottomMargin = Math.round(10 * density);
        preview.setLayoutParams(previewLp);

        TextView hexText = new TextView(this);
        hexText.setTextSize(14f);
        hexText.setPadding(0, 0, 0, Math.round(12 * density));

        TextView hueLabel = new TextView(this);
        hueLabel.setText("Hue");
        SeekBar hueBar = new SeekBar(this);
        hueBar.setMax(360);
        hueBar.setProgress(Math.round(hsv[0]));

        TextView satLabel = new TextView(this);
        satLabel.setText("Saturation");
        satLabel.setPadding(0, Math.round(8 * density), 0, 0);
        SeekBar satBar = new SeekBar(this);
        satBar.setMax(100);
        satBar.setProgress(Math.round(hsv[1] * 100f));

        TextView valLabel = new TextView(this);
        valLabel.setText("Brightness");
        valLabel.setPadding(0, Math.round(8 * density), 0, 0);
        SeekBar valBar = new SeekBar(this);
        valBar.setMax(100);
        valBar.setProgress(Math.round(hsv[2] * 100f));

        Runnable updatePreview = () -> {
            int color = Color.HSVToColor(hsv);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(8 * density);
            bg.setColor(color);
            preview.setBackground(bg);
            hexText.setText(String.format("#%06X", (0xFFFFFF & color)));
            ThemeHelper.tintSeekBar(hueBar, color);
            ThemeHelper.tintSeekBar(satBar, color);
            ThemeHelper.tintSeekBar(valBar, color);
        };
        updatePreview.run();

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                hsv[0] = hueBar.getProgress();
                hsv[1] = satBar.getProgress() / 100f;
                hsv[2] = valBar.getProgress() / 100f;
                updatePreview.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        hueBar.setOnSeekBarChangeListener(listener);
        satBar.setOnSeekBarChangeListener(listener);
        valBar.setOnSeekBarChangeListener(listener);

        container.addView(preview);
        container.addView(hexText);
        container.addView(hueLabel);
        container.addView(hueBar);
        container.addView(satLabel);
        container.addView(satBar);
        container.addView(valLabel);
        container.addView(valBar);

        new AlertDialog.Builder(this)
                .setTitle("Custom Accent Color")
                .setView(container)
                .setPositiveButton("Apply", (d, w) -> {
                    int chosen = Color.HSVToColor(hsv);
                    ThemeHelper.saveAccentColor(this, chosen);
                    setupAccentPresets();
                    applySettingsAccent();
                })
                .setNeutralButton("Reset (Violet)", (d, w) -> {
                    ThemeHelper.saveAccentColor(this, 0);
                    setupAccentPresets();
                    applySettingsAccent();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showImportUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/playlist.m3u or stream URL");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Import Stream / Playlist URL")
                .setView(input)
                .setPositiveButton("Import", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        importFromUrl(url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importFromUrl(String url) {
        Toast.makeText(this, "Fetching " + url + "...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                List<RadioStationParser.RadioStation> parsed;
                if (RadioBrowserClient.isPlaylistUrl(url)) {
                    parsed = RadioBrowserClient.fetchPlaylistFromUrl(url);
                } else {
                    RadioStationParser.RadioStation direct = new RadioStationParser.RadioStation();
                    direct.source = url;
                    direct.url = url;
                    Uri uri = Uri.parse(url);
                    direct.title = uri.getHost() != null ? uri.getHost() : url;
                    direct.name = direct.title;
                    parsed = Collections.singletonList(direct);
                }
                List<RadioStationParser.RadioStation> all = StationStore.load(this);
                int added = StationStore.mergeStations(all, parsed);
                StationStore.save(this, all);
                stationsChanged = true;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Imported " + added + " station(s)", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void importStationsFromDocument(Uri uri) {
        executor.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new IllegalStateException("Cannot open file");
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    }
                }
                List<RadioStationParser.RadioStation> parsed =
                        RadioStationParser.parseAuto(sb.toString(), uri.getLastPathSegment());
                List<RadioStationParser.RadioStation> all = StationStore.load(this);
                int added = StationStore.mergeStations(all, parsed);
                StationStore.save(this, all);
                stationsChanged = true;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Imported " + added + " station(s)", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void exportAllStationsToUri(Uri uri) {
        try {
            List<RadioStationParser.RadioStation> all = StationStore.load(this);
            String xml = RadioStationParser.serializeStationsXml(all);
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("Cannot open destination");
                os.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Exported " + all.size() + " stations", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showUpdateImagesDialog() {
        String[] options = {"Missing images only", "Refresh all station images"};
        new AlertDialog.Builder(this)
                .setTitle("Update Station Images")
                .setItems(options, (dialog, which) -> updateAllStationImages(which == 1))
                .show();
    }

    private void updateAllStationImages(boolean refreshAll) {
        Toast.makeText(this, "Updating station images...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            List<RadioStationParser.RadioStation> all = StationStore.load(this);
            int updated = 0;
            for (RadioStationParser.RadioStation s : all) {
                if (s == null) continue;
                boolean hasImage = s.favicon != null && !s.favicon.trim().isEmpty();
                if (!refreshAll && hasImage) continue;
                String found = RadioBrowserClient.findStationFavicon(s.source, s.title);
                if (found != null && !found.isEmpty()) {
                    s.favicon = found;
                    updated++;
                }
            }
            if (updated > 0) {
                StationStore.save(this, all);
                stationsChanged = true;
            }
            final int finalUpdated = updated;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, "Updated " + finalUpdated + " station image(s)", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static class DeadStationResult {
        final RadioStationParser.RadioStation station;
        final String reason;

        DeadStationResult(RadioStationParser.RadioStation station, String reason) {
            this.station = station;
            this.reason = reason;
        }
    }

    private void scanForDeadStations() {
        List<RadioStationParser.RadioStation> all = StationStore.load(this);
        if (all.isEmpty()) {
            Toast.makeText(this, "No stations to scan", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(all.size());
        progressBar.setProgress(0);
        TextView statusText = new TextView(this);
        statusText.setText("Checking 0 / " + all.size() + " stations...");
        statusText.setPadding(0, pad / 2, 0, 0);

        layout.addView(progressBar);
        layout.addView(statusText);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Scanning Stations")
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled.set(true))
                .create();
        progressDialog.show();

        executor.execute(() -> {
            List<DeadStationResult> deadList = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (RadioStationParser.RadioStation station : all) {
                    futures.add(pool.submit(() -> {
                        if (cancelled.get()) return;
                        String error = checkStreamAlive(station != null ? station.source : null);
                        if (error != null) {
                            deadList.add(new DeadStationResult(station, error));
                        }
                        int count = done.incrementAndGet();
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && progressDialog.isShowing()) {
                                progressBar.setProgress(count);
                                statusText.setText("Checked " + count + " / " + all.size()
                                        + " (" + deadList.size() + " dead)");
                            }
                        });
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    if (cancelled.get()) break;
                    try {
                        f.get();
                    } catch (Exception ignored) {}
                }
            } finally {
                pool.shutdownNow();
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                if (cancelled.get()) {
                    Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (deadList.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Scan Complete")
                            .setMessage("All " + all.size() + " stations are active.")
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    showDeadStationsBulkDeleteDialog(deadList);
                }
            });
        });
    }

    private String checkStreamAlive(String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return "Empty URL";
        }
        String currentUrl = urlStr.trim();
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "RadioPlayer/1.0 (Android)");
                conn.setRequestProperty("Icy-MetaData", "1");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) {
                        return "Broken redirect (" + code + ")";
                    }
                    currentUrl = new URL(new URL(currentUrl), loc.trim()).toString();
                    continue;
                }
                if (code >= 200 && code < 300) {
                    return null;
                }
                return "HTTP " + code;
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName();
                return msg;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return "Too many redirects";
    }

    private void showDeadStationsBulkDeleteDialog(List<DeadStationResult> deadList) {
        Collections.sort(deadList, (a, b) ->
                StationStore.safeTitle(a.station).compareToIgnoreCase(StationStore.safeTitle(b.station)));

        String[] labels = new String[deadList.size()];
        boolean[] checked = new boolean[deadList.size()];
        for (int i = 0; i < deadList.size(); i++) {
            DeadStationResult r = deadList.get(i);
            labels[i] = StationStore.safeTitle(r.station) + " (" + r.reason + ")";
            checked[i] = true;
        }

        new AlertDialog.Builder(this)
                .setTitle("Dead Stations Found (" + deadList.size() + ")")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Delete Selected", (dialog, which) -> {
                    Set<String> sourcesToDelete = new HashSet<>();
                    for (int i = 0; i < deadList.size(); i++) {
                        if (checked[i] && deadList.get(i).station != null && deadList.get(i).station.source != null) {
                            sourcesToDelete.add(deadList.get(i).station.source);
                        }
                    }
                    if (sourcesToDelete.isEmpty()) return;
                    List<RadioStationParser.RadioStation> all = StationStore.load(this);
                    int before = all.size();
                    all.removeIf(s -> s != null && sourcesToDelete.contains(s.source));
                    int removed = before - all.size();
                    StationStore.save(this, all);
                    stationsChanged = true;
                    Toast.makeText(this, "Deleted " + removed + " dead station(s)", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void finishWithResult() {
        if (stationsChanged) {
            Intent data = new Intent();
            data.putExtra("stations_changed", true);
            setResult(RESULT_OK, data);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
