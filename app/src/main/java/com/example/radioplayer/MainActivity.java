package com.example.radioplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private List<RadioStationParser.RadioStation> allStations;
    private List<RadioStationParser.RadioStation> displayedStations;
    private RadioStationParser.RadioStation currentStation;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private Player.Listener playerListener;

    private ImageButton playPauseButton, previousButton, nextButton, equalizerButton;
    private ImageButton searchOnlineButton, addStationButton, mainMenuAnchor;
    private MarqueeTextView currentStationView;
    private MarqueeTextView songTitleView;
    private TextView codecInfoView;
    private TextView sleepTimerStatus;
    private TextView stationsTab, favoritesTab;
    private View stationsTabIndicator, favoritesTabIndicator;
    private View streamStatusIndicator;
    private EditText stationFilterEdit;
    private ImageView nowPlayingThumb;
    private SeekBar mainVolumeSeekBar;
    private AudioManager audioManager;
    private ListView stationListView;
    private StationAdapter stationAdapter;
    private int editingPosition = -1;
    private boolean showingFavorites = true;
    private String currentFilter = "";
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler sleepPollHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(3);
    private static final long CONNECTION_TIMEOUT_MS = 120000;

    private final LruCache<String, Bitmap> iconCache = new LruCache<>(64);
    private final Set<String> iconLoading = Collections.synchronizedSet(new HashSet<>());

    private final ActivityResultLauncher<Intent> editStationLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    RadioStationParser.RadioStation updatedStation =
                            (RadioStationParser.RadioStation) data.getSerializableExtra("station");
                    boolean isNew = data.getBooleanExtra("isNewStation", false);
                    boolean isDelete = data.getBooleanExtra("deleteStation", false);
                    boolean playNow = data.getBooleanExtra("playStation", false);

                    if (playNow && updatedStation != null) {
                        playStation(updatedStation);
                        return;
                    }

                    if (isDelete && editingPosition != -1 && editingPosition < displayedStations.size()) {
                        RadioStationParser.RadioStation toRemove = displayedStations.get(editingPosition);
                        allStations.remove(toRemove);
                        updateDisplayedStations();
                        saveAndSyncStations();
                        Toast.makeText(this, "Station deleted", Toast.LENGTH_SHORT).show();
                    } else if (updatedStation != null) {
                        if (isNew) {
                            allStations.add(updatedStation);
                            showingFavorites = false;
                            updateTabUI();
                        } else if (editingPosition != -1 && editingPosition < displayedStations.size()) {
                            RadioStationParser.RadioStation oldStation = displayedStations.get(editingPosition);
                            int index = allStations.indexOf(oldStation);
                            if (index != -1) {
                                allStations.set(index, updatedStation);
                            }
                        }
                        StationStore.sort(allStations);
                        updateDisplayedStations();
                        saveAndSyncStations();
                        Toast.makeText(this, "Station list updated", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @SuppressWarnings("unchecked")
    private final ActivityResultLauncher<Intent> searchStationsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    ArrayList<RadioStationParser.RadioStation> added =
                            (ArrayList<RadioStationParser.RadioStation>) result.getData().getSerializableExtra("added_stations");
                    if (added != null && !added.isEmpty()) {
                        int count = StationStore.mergeStations(allStations, added);
                        showingFavorites = false;
                        updateTabUI();
                        updateDisplayedStations();
                        saveAndSyncStations();
                        Toast.makeText(this, "Imported " + count + " station(s)", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getBooleanExtra("stations_changed", false)) {
                    iconCache.evictAll();
                    allStations = StationStore.load(this);
                    updateDisplayedStations();
                    if (mediaController != null) {
                        mediaController.sendCustomCommand(
                                new SessionCommand("RELOAD_STATIONS", Bundle.EMPTY), Bundle.EMPTY);
                    }
                    if (currentStation != null) {
                        for (RadioStationParser.RadioStation s : allStations) {
                            if (s != null && Objects.equals(s.source, currentStation.source)) {
                                currentStation = s;
                                loadFaviconAsync(s.favicon, nowPlayingThumb);
                                break;
                            }
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playPauseButton = findViewById(R.id.playPauseButton);
        previousButton = findViewById(R.id.previousButton);
        nextButton = findViewById(R.id.nextButton);
        equalizerButton = findViewById(R.id.equalizerButton);
        searchOnlineButton = findViewById(R.id.searchOnlineButton);
        addStationButton = findViewById(R.id.addStationButton);
        mainMenuAnchor = findViewById(R.id.mainMenuAnchor);

        currentStationView = findViewById(R.id.currentStationView);
        songTitleView = findViewById(R.id.songTitleView);
        codecInfoView = findViewById(R.id.codecInfoView);
        sleepTimerStatus = findViewById(R.id.sleepTimerStatus);
        nowPlayingThumb = findViewById(R.id.nowPlayingThumb);
        mainVolumeSeekBar = findViewById(R.id.mainVolumeSeekBar);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setupVolumeControl();
        stationFilterEdit = findViewById(R.id.stationFilterEdit);
        stationListView = findViewById(R.id.stationListView);
        stationsTab = findViewById(R.id.stationsTab);
        favoritesTab = findViewById(R.id.favoritesTab);
        stationsTabIndicator = findViewById(R.id.stationsTabIndicator);
        favoritesTabIndicator = findViewById(R.id.favoritesTabIndicator);
        streamStatusIndicator = findViewById(R.id.streamStatusIndicator);

        applyDynamicThumbSize();

        allStations = StationStore.load(this);
        displayedStations = new ArrayList<>();
        updateDisplayedStations();

        stationAdapter = new StationAdapter(this, displayedStations);
        stationListView.setAdapter(stationAdapter);

        stationListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayedStations.size()) {
                playStation(displayedStations.get(position));
            }
        });

        stationListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= displayedStations.size()) return false;
            showStationContextMenu(view, position);
            return true;
        });

        stationFilterEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentFilter = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                updateDisplayedStations();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        playPauseButton.setOnClickListener(v -> togglePlayback());
        previousButton.setOnClickListener(v -> playPrevious());
        nextButton.setOnClickListener(v -> playNext());
        equalizerButton.setOnClickListener(v -> startActivity(new Intent(this, EqualizerActivity.class)));
        searchOnlineButton.setOnClickListener(v ->
                searchStationsLauncher.launch(new Intent(this, SearchStationsActivity.class)));
        addStationButton.setOnClickListener(v -> {
            editingPosition = -1;
            Intent intent = new Intent(this, StationDetailsActivity.class);
            intent.putExtra("isNewStation", true);
            editStationLauncher.launch(intent);
        });
        codecInfoView.setOnClickListener(v -> showStreamQualityDialog());
        mainMenuAnchor.setOnClickListener(v -> showMainMenu());

        stationsTab.setOnClickListener(v -> {
            showingFavorites = false;
            updateTabUI();
            updateDisplayedStations();
        });

        favoritesTab.setOnClickListener(v -> {
            showingFavorites = true;
            updateTabUI();
            updateDisplayedStations();
        });

        updateTabUI();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        String urlStr = data.toString();
        intent.setAction(null);
        importFromUrl(urlStr, true);
    }

    private void setupVolumeControl() {
        if (mainVolumeSeekBar == null || audioManager == null) return;
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        mainVolumeSeekBar.setMax(maxVolume);
        mainVolumeSeekBar.setProgress(currentVolume);
        mainVolumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioManager != null) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void syncVolumeSlider() {
        if (mainVolumeSeekBar != null && audioManager != null) {
            mainVolumeSeekBar.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncVolumeSlider();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            boolean handled = super.dispatchKeyEvent(event);
            syncVolumeSlider();
            return handled;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applyDynamicThumbSize() {
        if (nowPlayingThumb == null) return;
        float density = getResources().getDisplayMetrics().density;
        float screenWidthDp = getResources().getDisplayMetrics().widthPixels / density;
        boolean isTablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        int sizeDp = isTablet
                ? Math.round(Math.max(72f, Math.min(112f, screenWidthDp * 0.10f)))
                : Math.round(Math.max(48f, Math.min(72f, screenWidthDp * 0.14f)));
        int sizePx = Math.round(sizeDp * density);
        ViewGroup.LayoutParams lp = nowPlayingThumb.getLayoutParams();
        lp.width = sizePx;
        lp.height = sizePx;
        nowPlayingThumb.setLayoutParams(lp);
    }

    private void showStationContextMenu(View anchor, int position) {
        RadioStationParser.RadioStation station = displayedStations.get(position);
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Edit");
        popup.getMenu().add("Delete");
        popup.setOnMenuItemClickListener(item -> {
            CharSequence title = item.getTitle();
            if ("Edit".equals(title)) {
                editingPosition = position;
                Intent intent = new Intent(this, StationDetailsActivity.class);
                intent.putExtra("station", station);
                editStationLauncher.launch(intent);
            } else if ("Delete".equals(title)) {
                new AlertDialog.Builder(this)
                        .setTitle("Delete Station")
                        .setMessage("Delete \"" + StationStore.safeTitle(station) + "\"?")
                        .setPositiveButton("Delete", (d, w) -> {
                            allStations.remove(station);
                            updateDisplayedStations();
                            saveAndSyncStations();
                            Toast.makeText(this, "Station deleted", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            return true;
        });
        popup.show();
    }

    private void updateTabUI() {
        int activeColor = ContextCompat.getColor(this, R.color.app_text_primary);
        int inactiveColor = ContextCompat.getColor(this, R.color.app_text_secondary);
        if (showingFavorites) {
            favoritesTab.setTextColor(activeColor);
            stationsTab.setTextColor(inactiveColor);
            if (favoritesTabIndicator != null) favoritesTabIndicator.setVisibility(View.VISIBLE);
            if (stationsTabIndicator != null) stationsTabIndicator.setVisibility(View.INVISIBLE);
        } else {
            stationsTab.setTextColor(activeColor);
            favoritesTab.setTextColor(inactiveColor);
            if (stationsTabIndicator != null) stationsTabIndicator.setVisibility(View.VISIBLE);
            if (favoritesTabIndicator != null) favoritesTabIndicator.setVisibility(View.INVISIBLE);
        }
    }

    private void updateDisplayedStations() {
        if (allStations == null) allStations = new ArrayList<>();
        if (displayedStations == null) displayedStations = new ArrayList<>();
        displayedStations.clear();
        for (RadioStationParser.RadioStation s : allStations) {
            if (s == null) continue;
            if (showingFavorites && !s.isFavorite) continue;
            if (!currentFilter.isEmpty()) {
                String titleMatch = StationStore.safeTitle(s).toLowerCase(Locale.US);
                String tagsMatch = s.tags != null ? s.tags.toLowerCase(Locale.US) : "";
                if (!titleMatch.contains(currentFilter) && !tagsMatch.contains(currentFilter)) {
                    continue;
                }
            }
            displayedStations.add(s);
        }
        if (stationAdapter != null) stationAdapter.notifyDataSetChanged();
    }

    private void showMainMenu() {
        PopupMenu popup = new PopupMenu(this, mainMenuAnchor);
        popup.getMenu().add("Stream Quality");
        popup.getMenu().add("Sleep Timer");
        popup.getMenu().add("Equalizer");
        popup.getMenu().add("Settings");

        popup.setOnMenuItemClickListener(item -> {
            CharSequence title = item.getTitle();
            if ("Stream Quality".equals(title)) {
                showStreamQualityDialog();
            } else if ("Sleep Timer".equals(title)) {
                showSleepTimerDialog();
            } else if ("Equalizer".equals(title)) {
                startActivity(new Intent(this, EqualizerActivity.class));
            } else if ("Settings".equals(title)) {
                settingsLauncher.launch(new Intent(this, SettingsActivity.class));
            }
            return true;
        });
        popup.show();
    }

    private void showStreamQualityDialog() {
        final String[] options = {
                "Auto (Best available)",
                "320 kbps",
                "256 kbps",
                "128 kbps",
                "64 kbps",
                "32 kbps",
                "16 kbps",
                "Find alternate streams for current station..."
        };
        final int[] kbpsValues = {0, 320, 256, 128, 64, 32, 16, -1};

        new AlertDialog.Builder(this)
                .setTitle("Stream Quality")
                .setItems(options, (dialog, which) -> {
                    int val = kbpsValues[which];
                    if (val == -1) {
                        findAlternateBitrateStreams();
                    } else if (mediaController != null) {
                        Bundle args = new Bundle();
                        args.putInt("kbps", val);
                        mediaController.sendCustomCommand(
                                new SessionCommand("SET_MAX_BITRATE", Bundle.EMPTY), args);
                        Toast.makeText(this,
                                val == 0 ? "Quality: Auto (adaptive streams)" : "Max adaptive bitrate: " + val + " kbps",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void findAlternateBitrateStreams() {
        if (currentStation == null) {
            Toast.makeText(this, "Select a station first", Toast.LENGTH_SHORT).show();
            return;
        }
        String query = StationStore.safeTitle(currentStation);
        Toast.makeText(this, "Checking available streams for " + query + "...", Toast.LENGTH_SHORT).show();
        networkExecutor.execute(() -> {
            try {
                List<RadioStationParser.RadioStation> variants = RadioBrowserClient.findStationVariants(query);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (variants.isEmpty()) {
                        Toast.makeText(this, "No alternate streams found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] labels = new String[variants.size()];
                    for (int i = 0; i < variants.size(); i++) {
                        RadioStationParser.RadioStation v = variants.get(i);
                        String meta = (v.tags != null && !v.tags.isEmpty()) ? v.tags : v.source;
                        labels[i] = StationStore.safeTitle(v) + "\n" + meta;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Available Streams")
                            .setItems(labels, (d, idx) -> playStation(variants.get(idx)))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Lookup failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void importFromUrl(String url, boolean playFirst) {
        Toast.makeText(this, "Fetching " + url + "...", Toast.LENGTH_SHORT).show();
        networkExecutor.execute(() -> {
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

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "No stations found at URL", Toast.LENGTH_LONG).show();
                        return;
                    }
                    int added = StationStore.mergeStations(allStations, parsed);
                    showingFavorites = false;
                    updateTabUI();
                    updateDisplayedStations();
                    saveAndSyncStations();
                    Toast.makeText(this, "Imported " + added + " station(s)", Toast.LENGTH_SHORT).show();
                    if (playFirst) {
                        playStation(parsed.get(0));
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

    private void showSleepTimerDialog() {
        final String[] labels = {"Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes"};
        final int[] minutes = {0, 15, 30, 45, 60};
        new AlertDialog.Builder(this)
                .setTitle("Sleep Timer")
                .setItems(labels, (dialog, which) -> {
                    if (mediaController != null) {
                        Bundle args = new Bundle();
                        args.putInt("minutes", minutes[which]);
                        mediaController.sendCustomCommand(new SessionCommand("SET_SLEEP_TIMER", Bundle.EMPTY), args);
                        updateSleepTimerStatus(minutes[which] * 60_000L);
                        Toast.makeText(this,
                                minutes[which] == 0 ? "Sleep timer off" : "Sleep timer: " + labels[which],
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void pollSleepTimer() {
        sleepPollHandler.removeCallbacksAndMessages(null);
        if (mediaController == null) return;
        ListenableFuture<SessionResult> fut = mediaController.sendCustomCommand(
                new SessionCommand("GET_SLEEP_TIMER", Bundle.EMPTY), Bundle.EMPTY);
        fut.addListener(() -> {
            try {
                SessionResult res = fut.get();
                long remaining = res.extras.getLong("remaining_ms", 0L);
                updateSleepTimerStatus(remaining);
                if (remaining > 0) {
                    sleepPollHandler.postDelayed(this::pollSleepTimer, 15000);
                }
            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private void updateSleepTimerStatus(long remainingMs) {
        if (remainingMs > 0) {
            long mins = (remainingMs + 59_999L) / 60_000L;
            sleepTimerStatus.setText("Sleep: " + mins + "m");
            sleepTimerStatus.setVisibility(View.VISIBLE);
        } else {
            sleepTimerStatus.setVisibility(View.GONE);
        }
    }

    private void saveAndSyncStations() {
        StationStore.save(this, allStations);
        if (mediaController != null) {
            mediaController.sendCustomCommand(new SessionCommand("RELOAD_STATIONS", Bundle.EMPTY), Bundle.EMPTY);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializeController();
    }

    @Override
    protected void onStop() {
        super.onStop();
        cancelTimeout();
        sleepPollHandler.removeCallbacksAndMessages(null);
        if (mediaController != null && playerListener != null) {
            mediaController.removeListener(playerListener);
        }
        playerListener = null;
        mediaController = null;
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        networkExecutor.shutdownNow();
    }

    private void initializeController() {
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                mediaController = controllerFuture.get();
                playerListener = new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                        syncCurrentStation(mediaItem);
                    }

                    @Override
                    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
                        updateMetadataUI(mediaMetadata);
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (mediaController == null) return;
                        updatePlayPauseIcon(isPlaying);
                        if (isPlaying) {
                            cancelTimeout();
                        }
                        updateMetadataUI(mediaController.getMediaMetadata());
                    }

                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (mediaController == null) return;
                        if (playbackState == Player.STATE_BUFFERING) {
                            updateBufferingUI();
                            codecInfoView.setText("");
                            codecInfoView.setVisibility(View.GONE);
                        } else if (playbackState == Player.STATE_READY) {
                            updateMetadataUI(mediaController.getMediaMetadata());
                            updateCodecInfo();
                            cancelTimeout();
                        } else if (playbackState == Player.STATE_IDLE) {
                            cancelTimeout();
                        }
                    }

                    @Override
                    public void onTracksChanged(Tracks tracks) {
                        updateCodecInfo();
                    }
                };
                mediaController.addListener(playerListener);

                syncCurrentStation(mediaController.getCurrentMediaItem());
                updateMetadataUI(mediaController.getMediaMetadata());
                updateCodecInfo();
                updatePlayPauseIcon(mediaController.isPlaying());
                stationAdapter.notifyDataSetChanged();
                pollSleepTimer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void updatePlayPauseIcon(boolean isPlaying) {
        playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        if (streamStatusIndicator != null) {
            streamStatusIndicator.setBackgroundResource(
                    isPlaying ? R.drawable.status_circle_green : R.drawable.status_circle_red);
        }
    }

    private void syncCurrentStation(@Nullable MediaItem mediaItem) {
        if (mediaItem != null && allStations != null) {
            for (RadioStationParser.RadioStation station : allStations) {
                if (station != null && Objects.equals(station.source, mediaItem.mediaId)) {
                    currentStation = station;
                    currentStationView.setText(StationStore.safeTitle(station));
                    loadFaviconAsync(station.favicon, nowPlayingThumb);
                    stationAdapter.notifyDataSetChanged();
                    break;
                }
            }
        }
    }

    private void updateCodecInfo() {
        if (mediaController == null) return;

        Format format = null;
        Tracks tracks = mediaController.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO && group.isSelected()) {
                for (int i = 0; i < group.length; i++) {
                    if (group.isTrackSelected(i)) {
                        format = group.getTrackFormat(i);
                        break;
                    }
                }
            }
        }

        if (format != null) {
            String codec = format.sampleMimeType != null
                    ? format.sampleMimeType.replace("audio/", "").toUpperCase(Locale.US)
                    : "AUDIO";
            int kbps = Math.max(0, format.bitrate / 1000);
            String info = kbps > 0
                    ? String.format(Locale.US, "%s · %d Hz · %d kbps", codec, format.sampleRate, kbps)
                    : String.format(Locale.US, "%s · %d Hz", codec, format.sampleRate);
            codecInfoView.setText(info);
            codecInfoView.setVisibility(View.VISIBLE);
        } else {
            codecInfoView.setText("");
            codecInfoView.setVisibility(View.GONE);
        }
    }

    private void updateMetadataUI(MediaMetadata metadata) {
        String sName = (currentStation != null) ? StationStore.safeTitle(currentStation) : "";
        String sTags = (currentStation != null && currentStation.tags != null) ? currentStation.tags : "";
        String appName = getString(R.string.app_name);

        String titleStr = metadata.title != null ? metadata.title.toString().trim() : "";
        String artistStr = metadata.artist != null ? metadata.artist.toString().trim() : "";

        String displayInfo;
        if (!titleStr.isEmpty() && titleStr.equalsIgnoreCase(sName)) {
            if (!artistStr.isEmpty() && !artistStr.equals(sTags) && !artistStr.equalsIgnoreCase(sName)) {
                displayInfo = artistStr;
            } else {
                displayInfo = getString(R.string.waiting_for_stream);
            }
        } else if (!titleStr.isEmpty()) {
            displayInfo = !artistStr.isEmpty() ? artistStr + " - " + titleStr : titleStr;
        } else {
            displayInfo = getString(R.string.waiting_for_stream);
        }

        if (displayInfo.equalsIgnoreCase(sName) || displayInfo.equalsIgnoreCase(appName) || displayInfo.equalsIgnoreCase("Radio Player")) {
            displayInfo = getString(R.string.waiting_for_stream);
        }

        final String finalInfo = displayInfo;
        runOnUiThread(() -> {
            songTitleView.setText(finalInfo);
            if (currentStation != null) {
                currentStationView.setText(StationStore.safeTitle(currentStation));
            }
        });
    }

    private void updateBufferingUI() {
        runOnUiThread(() -> songTitleView.setText(R.string.buffering));
        startTimeout();
    }

    private void startTimeout() {
        cancelTimeout();
        timeoutHandler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        timeoutHandler.removeCallbacks(timeoutRunnable);
    }

    private final Runnable timeoutRunnable = () -> runOnUiThread(() -> {
        songTitleView.setText(R.string.connection_timeout);
        Toast.makeText(MainActivity.this, R.string.connection_timeout, Toast.LENGTH_LONG).show();
    });

    private void togglePlayback() {
        if (mediaController != null) {
            if (mediaController.isPlaying()) {
                mediaController.pause();
            } else {
                if (mediaController.getPlaybackState() == Player.STATE_IDLE) {
                    if (currentStation != null) {
                        playStation(currentStation);
                    } else if (!displayedStations.isEmpty()) {
                        playStation(displayedStations.get(0));
                    }
                } else {
                    mediaController.play();
                }
            }
        }
    }

    private void playNext() {
        if (displayedStations.isEmpty()) return;
        int nextIndex = 0;
        if (currentStation != null) {
            int currentIndex = -1;
            for (int i = 0; i < displayedStations.size(); i++) {
                if (Objects.equals(displayedStations.get(i).source, currentStation.source)) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex != -1) {
                nextIndex = (currentIndex + 1) % displayedStations.size();
            }
        }
        playStation(displayedStations.get(nextIndex));
    }

    private void playPrevious() {
        if (displayedStations.isEmpty()) return;
        int prevIndex = displayedStations.size() - 1;
        if (currentStation != null) {
            int currentIndex = -1;
            for (int i = 0; i < displayedStations.size(); i++) {
                if (Objects.equals(displayedStations.get(i).source, currentStation.source)) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex != -1) {
                prevIndex = (currentIndex - 1 + displayedStations.size()) % displayedStations.size();
            }
        }
        playStation(displayedStations.get(prevIndex));
    }

    private void playStation(RadioStationParser.RadioStation station) {
        if (station == null || station.source == null || station.source.trim().isEmpty()) return;
        currentStation = station;
        if (mediaController != null) {
            MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                    .setTitle(station.title)
                    .setArtist(station.tags);
            if (station.favicon != null && !station.favicon.isEmpty()) {
                metaBuilder.setArtworkUri(Uri.parse(station.favicon));
            }
            MediaItem mediaItem = new MediaItem.Builder()
                    .setMediaId(station.source)
                    .setUri(station.source)
                    .setMediaMetadata(metaBuilder.build())
                    .build();
            mediaController.setMediaItem(mediaItem);
            mediaController.prepare();
            mediaController.play();
            updateBufferingUI();
            currentStationView.setText(StationStore.safeTitle(station));
            loadFaviconAsync(station.favicon, nowPlayingThumb);
            stationAdapter.notifyDataSetChanged();
        }
    }

    private void loadFaviconAsync(String urlString, ImageView target) {
        if (target == null) return;
        if (urlString == null || urlString.isEmpty()) {
            target.setImageResource(R.mipmap.ic_launcher);
            return;
        }
        Bitmap cached = iconCache.get(urlString);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(R.mipmap.ic_launcher);
        if (!iconLoading.add(urlString)) return;

        networkExecutor.execute(() -> {
            try {
                Bitmap bmp = null;
                if (urlString.startsWith("content://") || urlString.startsWith("file://")) {
                    try (InputStream is = getContentResolver().openInputStream(Uri.parse(urlString))) {
                        if (is != null) bmp = BitmapFactory.decodeStream(is);
                    }
                } else {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(true);
                    try (InputStream is = conn.getInputStream()) {
                        bmp = BitmapFactory.decodeStream(is);
                    } finally {
                        conn.disconnect();
                    }
                }
                if (bmp != null) {
                    iconCache.put(urlString, bmp);
                    final Bitmap finalBmp = bmp;
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            if (target == nowPlayingThumb && currentStation != null && Objects.equals(currentStation.favicon, urlString)) {
                                nowPlayingThumb.setImageBitmap(finalBmp);
                            }
                            stationAdapter.notifyDataSetChanged();
                        }
                    });
                }
            } catch (Exception ignored) {
            } finally {
                iconLoading.remove(urlString);
            }
        });
    }

    private static class StationViewHolder {
        View nowPlayingIndicator;
        ImageView icon;
        TextView title;
        TextView subtitle;
        TextView favIcon;
    }

    private class StationAdapter extends ArrayAdapter<RadioStationParser.RadioStation> {
        StationAdapter(Context context, List<RadioStationParser.RadioStation> stations) {
            super(context, R.layout.station_list_item, stations);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            StationViewHolder holder;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.station_list_item, parent, false);
                holder = new StationViewHolder();
                holder.nowPlayingIndicator = convertView.findViewById(R.id.nowPlayingIndicator);
                holder.icon = convertView.findViewById(R.id.stationIcon);
                holder.title = convertView.findViewById(R.id.stationTitle);
                holder.subtitle = convertView.findViewById(R.id.stationSubtitle);
                holder.favIcon = convertView.findViewById(R.id.favoriteStar);
                convertView.setTag(holder);
            } else {
                holder = (StationViewHolder) convertView.getTag();
            }

            RadioStationParser.RadioStation station = getItem(position);
            if (station == null) {
                holder.nowPlayingIndicator.setVisibility(View.INVISIBLE);
                holder.icon.setImageResource(R.mipmap.ic_launcher);
                holder.title.setText("");
                holder.subtitle.setVisibility(View.GONE);
                holder.favIcon.setText("☆");
                holder.favIcon.setOnClickListener(null);
                convertView.setBackgroundColor(Color.TRANSPARENT);
                return convertView;
            }

            holder.title.setText(StationStore.safeTitle(station));
            if (station.tags != null && !station.tags.trim().isEmpty()) {
                holder.subtitle.setText(station.tags.trim());
                holder.subtitle.setVisibility(View.VISIBLE);
            } else {
                holder.subtitle.setVisibility(View.GONE);
            }
            holder.favIcon.setText(station.isFavorite ? "★" : "☆");
            loadFaviconAsync(station.favicon, holder.icon);

            holder.favIcon.setOnClickListener(v -> {
                station.isFavorite = !station.isFavorite;
                saveAndSyncStations();
                if (showingFavorites) {
                    updateDisplayedStations();
                } else {
                    notifyDataSetChanged();
                }
            });

            boolean isCurrent = currentStation != null && Objects.equals(currentStation.source, station.source);
            holder.nowPlayingIndicator.setVisibility(isCurrent ? View.VISIBLE : View.INVISIBLE);
            if (isCurrent) {
                convertView.setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.app_selected_row));
            } else {
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }
    }
}
