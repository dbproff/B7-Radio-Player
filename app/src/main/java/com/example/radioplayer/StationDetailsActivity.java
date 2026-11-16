package com.example.radioplayer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.KeyListener;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StationDetailsActivity extends AppCompatActivity {

    private EditText nameEdit, websiteEdit, sourceEdit, tagsEdit, faviconEdit;
    private ImageView iconPreview;
    private CheckBox favoriteCheck;
    private TextView titleText;
    private ImageButton backButton, menuAnchor;
    private Button playButton, editButton, saveButton, deleteButton, cancelEditButton, updateImageButton;
    private RadioStationParser.RadioStation station;
    private RadioStationParser.RadioStation stationBackup;
    private KeyListener originalKeyListener;
    private boolean isAddingNew = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    String uriStr = uri.toString();
                    faviconEdit.setText(uriStr);
                    loadPreview(uriStr);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_station_details);

        station = (RadioStationParser.RadioStation) getIntent().getSerializableExtra("station");
        isAddingNew = getIntent().getBooleanExtra("isNewStation", false);

        titleText = findViewById(R.id.detailsTitle);
        nameEdit = findViewById(R.id.detailName);
        websiteEdit = findViewById(R.id.detailWebsite);
        sourceEdit = findViewById(R.id.detailSource);
        tagsEdit = findViewById(R.id.detailTags);
        faviconEdit = findViewById(R.id.detailFavicon);
        iconPreview = findViewById(R.id.detailIconPreview);
        favoriteCheck = findViewById(R.id.detailFavoriteCheck);
        menuAnchor = findViewById(R.id.detailsMenuAnchor);

        originalKeyListener = websiteEdit.getKeyListener();

        playButton = findViewById(R.id.playButton);
        editButton = findViewById(R.id.editButton);
        saveButton = findViewById(R.id.saveButton);
        deleteButton = findViewById(R.id.deleteButton);
        cancelEditButton = findViewById(R.id.cancelEditButton);
        backButton = findViewById(R.id.backButton);
        updateImageButton = findViewById(R.id.updateImageButton);

        populateFields(station);

        if (isAddingNew) {
            station = new RadioStationParser.RadioStation();
            station.title = "";
            station.url = "";
            station.source = "";
            station.name = "";
            enableEditing(true);
            nameEdit.requestFocus();
        } else {
            enableEditing(false);
        }

        menuAnchor.setOnClickListener(this::showPopupMenu);
        editButton.setOnClickListener(v -> enableEditing(true));
        updateImageButton.setOnClickListener(v -> showUpdateImageOptions());

        playButton.setOnClickListener(v -> {
            if (station != null) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("station", station);
                resultIntent.putExtra("playStation", true);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });

        saveButton.setOnClickListener(v -> {
            if (station != null) {
                String name = nameEdit.getText().toString().trim();
                String website = websiteEdit.getText().toString().trim();
                String stream = sourceEdit.getText().toString().trim();
                String tags = tagsEdit.getText().toString().trim();
                String favicon = faviconEdit.getText().toString().trim();

                if (name.isEmpty()) {
                    nameEdit.setError("Name is required");
                    return;
                }
                if (stream.isEmpty()) {
                    sourceEdit.setError("Stream URL is required");
                    return;
                }

                station.title = name;
                station.name = name;
                station.url = website.isEmpty() ? stream : website;
                station.source = stream;
                station.tags = tags;
                station.favicon = favicon;
                station.isFavorite = favoriteCheck.isChecked();

                Intent resultIntent = new Intent();
                resultIntent.putExtra("station", station);
                resultIntent.putExtra("isNewStation", isAddingNew);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });

        deleteButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete Station")
                .setMessage("Are you sure you want to delete this station?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("station", station);
                    resultIntent.putExtra("deleteStation", true);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show());

        View.OnClickListener cancelOrBack = v -> {
            if (saveButton.getVisibility() == View.VISIBLE) {
                if (isAddingNew) {
                    if (stationBackup != null) {
                        isAddingNew = false;
                        station = stationBackup;
                        stationBackup = null;
                        populateFields(station);
                        enableEditing(false);
                    } else {
                        finish();
                    }
                } else {
                    enableEditing(false);
                    populateFields(station);
                }
            } else {
                finish();
            }
        };
        backButton.setOnClickListener(cancelOrBack);
        cancelEditButton.setOnClickListener(cancelOrBack);
    }

    private void showUpdateImageOptions() {
        String[] options = {"Fetch from Internet (radio-browser.info)", "Choose Local Image"};
        new AlertDialog.Builder(this)
                .setTitle("Update Station Image")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        fetchImageFromInternet();
                    } else {
                        pickImageLauncher.launch(new String[]{"image/*"});
                    }
                })
                .show();
    }

    private void fetchImageFromInternet() {
        String streamUrl = sourceEdit.getText().toString().trim();
        String name = nameEdit.getText().toString().trim();
        Toast.makeText(this, "Searching for station image...", Toast.LENGTH_SHORT).show();
        updateImageButton.setEnabled(false);

        executor.execute(() -> {
            String found = RadioBrowserClient.findStationFavicon(streamUrl, name);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateImageButton.setEnabled(true);
                if (found != null && !found.isEmpty()) {
                    faviconEdit.setText(found);
                    loadPreview(found);
                    Toast.makeText(this, "Station image updated", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No image found online", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void loadPreview(String urlOrUri) {
        if (urlOrUri == null || urlOrUri.trim().isEmpty()) {
            iconPreview.setImageResource(R.mipmap.ic_launcher);
            return;
        }
        String trimmed = urlOrUri.trim();
        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                if (trimmed.startsWith("content://") || trimmed.startsWith("file://")) {
                    try (InputStream is = getContentResolver().openInputStream(Uri.parse(trimmed))) {
                        if (is != null) bmp = BitmapFactory.decodeStream(is);
                    }
                } else {
                    HttpURLConnection conn = (HttpURLConnection) new URL(trimmed).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(true);
                    try (InputStream is = conn.getInputStream()) {
                        bmp = BitmapFactory.decodeStream(is);
                    } finally {
                        conn.disconnect();
                    }
                }
            } catch (Exception ignored) {}

            final Bitmap finalBmp = bmp;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalBmp != null) {
                    iconPreview.setImageBitmap(finalBmp);
                } else {
                    iconPreview.setImageResource(R.mipmap.ic_launcher);
                }
            });
        });
    }

    private void populateFields(RadioStationParser.RadioStation s) {
        if (s != null) {
            nameEdit.setText(s.title);
            websiteEdit.setText(s.url);
            sourceEdit.setText(s.source);
            tagsEdit.setText(s.tags);
            faviconEdit.setText(s.favicon);
            favoriteCheck.setChecked(s.isFavorite);
            loadPreview(s.favicon);
        } else {
            nameEdit.setText("");
            websiteEdit.setText("");
            sourceEdit.setText("");
            tagsEdit.setText("");
            faviconEdit.setText("");
            favoriteCheck.setChecked(false);
            iconPreview.setImageResource(R.mipmap.ic_launcher);
        }
    }

    private void showPopupMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.station_details_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_add) {
                startAddingNewStation();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void startAddingNewStation() {
        stationBackup = station;
        isAddingNew = true;
        station = new RadioStationParser.RadioStation();
        populateFields(null);
        enableEditing(true);
    }

    private void enableEditing(boolean enabled) {
        nameEdit.setEnabled(enabled);
        sourceEdit.setEnabled(enabled);
        tagsEdit.setEnabled(enabled);
        faviconEdit.setEnabled(enabled);
        favoriteCheck.setEnabled(enabled);
        websiteEdit.setEnabled(true);

        if (enabled) {
            int editBg = ContextCompat.getColor(this, R.color.app_edit_bg);
            nameEdit.setBackgroundColor(editBg);
            websiteEdit.setBackgroundColor(editBg);
            sourceEdit.setBackgroundColor(editBg);
            tagsEdit.setBackgroundColor(editBg);
            faviconEdit.setBackgroundColor(editBg);

            websiteEdit.setKeyListener(originalKeyListener);
            websiteEdit.setFocusable(true);
            websiteEdit.setFocusableInTouchMode(true);
            websiteEdit.setMovementMethod(null);
            websiteEdit.setAutoLinkMask(0);
            websiteEdit.setText(websiteEdit.getText().toString());

            if (titleText != null) titleText.setText(isAddingNew ? R.string.add_new_station : R.string.edit_station);
            playButton.setVisibility(View.GONE);
            editButton.setVisibility(View.GONE);
            saveButton.setVisibility(View.VISIBLE);
            deleteButton.setVisibility(isAddingNew ? View.GONE : View.VISIBLE);
            cancelEditButton.setVisibility(View.VISIBLE);
            updateImageButton.setVisibility(View.VISIBLE);
            menuAnchor.setVisibility(View.GONE);
        } else {
            nameEdit.setBackground(null);
            websiteEdit.setBackground(null);
            sourceEdit.setBackground(null);
            tagsEdit.setBackground(null);
            faviconEdit.setBackground(null);

            websiteEdit.setKeyListener(null);
            websiteEdit.setFocusable(false);
            websiteEdit.setFocusableInTouchMode(false);
            websiteEdit.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
            websiteEdit.setMovementMethod(LinkMovementMethod.getInstance());
            websiteEdit.setText(websiteEdit.getText().toString());

            if (titleText != null) titleText.setText(R.string.station_details);
            playButton.setVisibility(View.VISIBLE);
            editButton.setVisibility(View.VISIBLE);
            saveButton.setVisibility(View.GONE);
            deleteButton.setVisibility(View.GONE);
            cancelEditButton.setVisibility(View.GONE);
            updateImageButton.setVisibility(View.GONE);
            menuAnchor.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
