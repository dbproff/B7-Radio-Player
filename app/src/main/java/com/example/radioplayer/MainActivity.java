package com.example.radioplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
import androidx.core.widget.ImageViewCompat;
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
    private static final int TAB_STATIONS = 0;
    private static final int TAB_FAVORITES = 1;
    private static final int TAB_RECORDED = 2;

    private TextView stationsTab, favoritesTab, recordedTab;
    private View stationsTabIndicator, favoritesTabIndicator, recordedTabIndicator;
    private View streamStatusIndicator;
    private View captureHeaderRow, captureControlsRow, captureStatusDot;
    private TextView captureHeaderText, captureFoldIndicator, captureDetailText;
    private ImageButton captureRecordPauseButton, captureStopButton;
    private boolean capturePanelExpanded = false;
    private ImageView nowPlayingThumb;
    private SeekBar mainVolumeSeekBar;
    private View playbackProgressContainer;
    private SeekBar playbackSeekBar;
    private TextView playbackPositionText, playbackDurationText;
    private boolean userSeekingPlayback = false;
    private final Handler playbackProgressHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackProgressRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
            playbackProgressHandler.postDelayed(this, 500);
        }
    };
    private AudioManager audioManager;
    private ListView stationListView;
    private StationAdapter stationAdapter;
    private RecordedAdapter recordedAdapter;
    private final List<StreamRecorder.RecordedItem> recordedItems = new ArrayList<>();
    private final Set<Uri> selectedRecordUris = new HashSet<>();
    private Uri currentRecordedUri = null;
    private int editingPosition = -1;
    private int currentTab = TAB_FAVORITES;

    private final StreamRecorder.Listener recorderListener = new StreamRecorder.Listener() {
        @Override
        public void onRecorderStateChanged(int state, String stationTitle, String fileName,
                                           long elapsedMs, long bytesWritten, String errorMessage) {
            updateCapturePanelUI(state, stationTitle, fileName, elapsedMs, bytesWritten, errorMessage);
        }

        @Override
        public void onRecordingFinished() {
            Toast.makeText(MainActivity.this, "Recording saved", Toast.LENGTH_SHORT).show();
            loadRecordedItemsAsync();
        }
    };
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
                            currentTab = TAB_STATIONS;
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
                        currentTab = TAB_STATIONS;
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
        playbackProgressContainer = findViewById(R.id.playbackProgressContainer);
        playbackSeekBar = findViewById(R.id.playbackSeekBar);
        playbackPositionText = findViewById(R.id.playbackPositionText);
        playbackDurationText = findViewById(R.id.playbackDurationText);
        setupPlaybackSeekBar();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setupVolumeControl();
        stationListView = findViewById(R.id.stationListView);
        stationsTab = findViewById(R.id.stationsTab);
        favoritesTab = findViewById(R.id.favoritesTab);
        recordedTab = findViewById(R.id.recordedTab);
        stationsTabIndicator = findViewById(R.id.stationsTabIndicator);
        favoritesTabIndicator = findViewById(R.id.favoritesTabIndicator);
        recordedTabIndicator = findViewById(R.id.recordedTabIndicator);
        streamStatusIndicator = findViewById(R.id.streamStatusIndicator);

        captureHeaderRow = findViewById(R.id.captureHeaderRow);
        captureControlsRow = findViewById(R.id.captureControlsRow);
        captureStatusDot = findViewById(R.id.captureStatusDot);
        captureHeaderText = findViewById(R.id.captureHeaderText);
        captureFoldIndicator = findViewById(R.id.captureFoldIndicator);
        captureDetailText = findViewById(R.id.captureDetailText);
        captureRecordPauseButton = findViewById(R.id.captureRecordPauseButton);
        captureStopButton = findViewById(R.id.captureStopButton);

        applyDynamicThumbSize();

        allStations = StationStore.load(this);
        displayedStations = new ArrayList<>();
        updateDisplayedStations();

        stationAdapter = new StationAdapter(this, displayedStations);
        recordedAdapter = new RecordedAdapter();
        stationListView.setAdapter(stationAdapter);
        applyAccentColor();
        setupCapturePanel();
        StreamRecorder.getInstance().addListener(recorderListener);

        stationListView.setOnItemClickListener((parent, view, position, id) -> {
            if (currentTab == TAB_RECORDED) {
                if (position >= 0 && position < recordedItems.size()) {
                    StreamRecorder.RecordedItem item = recordedItems.get(position);
                    if (!selectedRecordUris.isEmpty()) {
                        toggleRecordSelection(item);
                    } else {
                        playRecordedItem(item);
                    }
                }
            } else if (position >= 0 && position < displayedStations.size()) {
                playStation(displayedStations.get(position));
            }
        });

        stationListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (currentTab == TAB_RECORDED) {
                if (position >= 0 && position < recordedItems.size()) {
                    showRecordedContextMenu(view, recordedItems.get(position));
                    return true;
                }
                return false;
            }
            if (position < 0 || position >= displayedStations.size()) return false;
            showStationContextMenu(view, position);
            return true;
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
            currentTab = TAB_STATIONS;
            updateTabUI();
            updateDisplayedStations();
        });

        favoritesTab.setOnClickListener(v -> {
            currentTab = TAB_FAVORITES;
            updateTabUI();
            updateDisplayedStations();
        });

        recordedTab.setOnClickListener(v -> {
            currentTab = TAB_RECORDED;
            updateTabUI();
            loadRecordedItemsAsync();
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
        applyAccentColor();
        if (currentTab == TAB_RECORDED) {
            loadRecordedItemsAsync();
        }
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable);
        playbackProgressHandler.post(playbackProgressRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable);
    }

    private void setupPlaybackSeekBar() {
        if (playbackSeekBar == null) return;
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && playbackPositionText != null) {
                    playbackPositionText.setText(StreamRecorder.formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeekingPlayback = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeekingPlayback = false;
                if (mediaController != null) {
                    mediaController.seekTo(seekBar.getProgress());
                }
            }
        });
    }

    private void updatePlaybackProgress() {
        if (playbackProgressContainer == null) return;
        if (mediaController == null) {
            playbackProgressContainer.setVisibility(View.GONE);
            return;
        }
        MediaItem currentItem = mediaController.getCurrentMediaItem();
        boolean isRecorded = currentRecordedUri != null
                || (currentItem != null && currentItem.mediaId.startsWith("recorded:"));
        long duration = mediaController.getDuration();
        boolean seekable = isRecorded && mediaController.isCurrentMediaItemSeekable()
                && duration > 0 && duration != androidx.media3.common.C.TIME_UNSET;
        if (!seekable) {
            playbackProgressContainer.setVisibility(View.GONE);
            return;
        }
        playbackProgressContainer.setVisibility(View.VISIBLE);
        long position = Math.max(0L, Math.min(mediaController.getCurrentPosition(), duration));
        if (playbackDurationText != null) {
            playbackDurationText.setText(StreamRecorder.formatDuration(duration));
        }
        if (!userSeekingPlayback) {
            if (playbackSeekBar != null) {
                playbackSeekBar.setMax((int) duration);
                playbackSeekBar.setProgress((int) position);
            }
            if (playbackPositionText != null) {
                playbackPositionText.setText(StreamRecorder.formatDuration(position));
            }
        }
    }

    private void applyAccentColor() {
        int accent = ThemeHelper.getAccentColor(this);
        if (playPauseButton != null) {
            ImageViewCompat.setImageTintList(playPauseButton, ColorStateList.valueOf(accent));
        }
        if (songTitleView != null) {
            songTitleView.setTextColor(accent);
        }
        if (sleepTimerStatus != null) {
            sleepTimerStatus.setTextColor(accent);
        }
        if (stationsTabIndicator != null) {
            stationsTabIndicator.setBackgroundColor(accent);
        }
        if (favoritesTabIndicator != null) {
            favoritesTabIndicator.setBackgroundColor(accent);
        }
        if (recordedTabIndicator != null) {
            recordedTabIndicator.setBackgroundColor(accent);
        }
        if (captureRecordPauseButton != null) {
            ImageViewCompat.setImageTintList(captureRecordPauseButton, ColorStateList.valueOf(accent));
        }
        ThemeHelper.tintSeekBar(mainVolumeSeekBar, accent);
        ThemeHelper.tintSeekBar(playbackSeekBar, accent);
        if (stationAdapter != null) {
            stationAdapter.notifyDataSetChanged();
        }
        if (recordedAdapter != null) {
            recordedAdapter.notifyDataSetChanged();
        }
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

    private void setupCapturePanel() {
        if (captureHeaderRow != null) {
            captureHeaderRow.setOnClickListener(v -> {
                capturePanelExpanded = !capturePanelExpanded;
                if (captureControlsRow != null) {
                    captureControlsRow.setVisibility(capturePanelExpanded ? View.VISIBLE : View.GONE);
                }
                if (captureFoldIndicator != null) {
                    captureFoldIndicator.setText(capturePanelExpanded ? "▲" : "▼");
                }
            });
        }
        if (captureRecordPauseButton != null) {
            captureRecordPauseButton.setOnClickListener(v -> {
                StreamRecorder recorder = StreamRecorder.getInstance();
                int st = recorder.getState();
                if (st == StreamRecorder.STATE_IDLE) {
                    if (currentStation == null || currentStation.source == null || currentStation.source.trim().isEmpty()) {
                        Toast.makeText(this, "Play a radio station first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String song = songTitleView != null ? songTitleView.getText().toString() : "";
                    recorder.startRecording(this, currentStation.source, StationStore.safeTitle(currentStation), song);
                } else if (st == StreamRecorder.STATE_RECORDING) {
                    recorder.pauseRecording();
                } else if (st == StreamRecorder.STATE_PAUSED) {
                    recorder.resumeRecording();
                }
            });
        }
        if (captureStopButton != null) {
            captureStopButton.setOnClickListener(v -> StreamRecorder.getInstance().stopRecording());
        }
    }

    private void updateCapturePanelUI(int state, String stationTitle, String fileName,
                                      long elapsedMs, long bytesWritten, String errorMessage) {
        if (captureHeaderText == null) return;
        String timeStr = StreamRecorder.formatDuration(elapsedMs);
        String sizeStr = StreamRecorder.formatSize(bytesWritten);

        if (state == StreamRecorder.STATE_RECORDING) {
            captureHeaderText.setText("REC • " + timeStr + " • " + sizeStr);
            if (captureStatusDot != null) captureStatusDot.setAlpha(1.0f);
            if (captureRecordPauseButton != null) {
                captureRecordPauseButton.setImageResource(R.drawable.ic_pause);
                captureRecordPauseButton.setContentDescription("Pause recording");
            }
            if (captureStopButton != null) {
                captureStopButton.setEnabled(true);
                captureStopButton.setAlpha(1.0f);
            }
            if (captureDetailText != null) {
                captureDetailText.setText(fileName != null ? fileName : ("Recording " + stationTitle));
            }
        } else if (state == StreamRecorder.STATE_PAUSED) {
            captureHeaderText.setText("PAUSED • " + timeStr + " • " + sizeStr);
            if (captureStatusDot != null) captureStatusDot.setAlpha(0.6f);
            if (captureRecordPauseButton != null) {
                captureRecordPauseButton.setImageResource(R.drawable.ic_record);
                captureRecordPauseButton.setContentDescription("Resume recording");
            }
            if (captureStopButton != null) {
                captureStopButton.setEnabled(true);
                captureStopButton.setAlpha(1.0f);
            }
            if (captureDetailText != null) {
                captureDetailText.setText("Paused" + (fileName != null ? " — " + fileName : ""));
            }
        } else {
            captureHeaderText.setText("Stream Capture");
            if (captureStatusDot != null) captureStatusDot.setAlpha(0.35f);
            if (captureRecordPauseButton != null) {
                captureRecordPauseButton.setImageResource(R.drawable.ic_record);
                captureRecordPauseButton.setContentDescription("Record");
            }
            if (captureStopButton != null) {
                captureStopButton.setEnabled(false);
                captureStopButton.setAlpha(0.35f);
            }
            if (captureDetailText != null) {
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    captureDetailText.setText("Capture error: " + errorMessage);
                } else {
                    captureDetailText.setText("Tap ⏺ to capture the playing stream");
                }
            }
        }
    }

    private void updateTabUI() {
        int activeColor = ContextCompat.getColor(this, R.color.app_text_primary);
        int inactiveColor = ContextCompat.getColor(this, R.color.app_text_secondary);

        stationsTab.setTextColor(currentTab == TAB_STATIONS ? activeColor : inactiveColor);
        favoritesTab.setTextColor(currentTab == TAB_FAVORITES ? activeColor : inactiveColor);
        if (recordedTab != null) {
            recordedTab.setTextColor(currentTab == TAB_RECORDED ? activeColor : inactiveColor);
        }

        if (stationsTabIndicator != null) {
            stationsTabIndicator.setVisibility(currentTab == TAB_STATIONS ? View.VISIBLE : View.INVISIBLE);
        }
        if (favoritesTabIndicator != null) {
            favoritesTabIndicator.setVisibility(currentTab == TAB_FAVORITES ? View.VISIBLE : View.INVISIBLE);
        }
        if (recordedTabIndicator != null) {
            recordedTabIndicator.setVisibility(currentTab == TAB_RECORDED ? View.VISIBLE : View.INVISIBLE);
        }

        if (currentTab == TAB_RECORDED) {
            if (stationListView.getAdapter() != recordedAdapter) {
                stationListView.setAdapter(recordedAdapter);
            }
        } else {
            if (!selectedRecordUris.isEmpty()) {
                selectedRecordUris.clear();
            }
            if (stationListView.getAdapter() != stationAdapter) {
                stationListView.setAdapter(stationAdapter);
            }
        }
    }

    private void updateDisplayedStations() {
        if (allStations == null) allStations = new ArrayList<>();
        if (displayedStations == null) displayedStations = new ArrayList<>();
        displayedStations.clear();
        for (RadioStationParser.RadioStation s : allStations) {
            if (s == null) continue;
            if (currentTab == TAB_FAVORITES && !s.isFavorite) continue;
            displayedStations.add(s);
        }
        if (stationAdapter != null) stationAdapter.notifyDataSetChanged();
    }

    private void loadRecordedItemsAsync() {
        networkExecutor.execute(() -> {
            List<StreamRecorder.RecordedItem> loaded = StreamRecorder.listRecordings(MainActivity.this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                recordedItems.clear();
                recordedItems.addAll(loaded);
                if (recordedAdapter != null) {
                    recordedAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void playRecordedItem(StreamRecorder.RecordedItem item) {
        if (item == null || item.uri == null || mediaController == null) return;
        currentStation = null;
        currentRecordedUri = item.uri;
        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(item.displayName)
                .setArtist("Recorded Stream")
                .build();
        MediaItem mediaItem = new MediaItem.Builder()
                .setMediaId("recorded:" + item.uri)
                .setUri(item.uri)
                .setMediaMetadata(meta)
                .build();
        mediaController.setMediaItem(mediaItem);
        mediaController.prepare();
        mediaController.play();
        if (currentStationView != null) {
            currentStationView.setText(item.displayName);
        }
        if (songTitleView != null) {
            songTitleView.setText("Recorded Stream • " + StreamRecorder.formatSize(item.sizeBytes));
        }
        if (nowPlayingThumb != null) {
            setNowPlayingThumbVisible(false);
        }
        if (stationAdapter != null) stationAdapter.notifyDataSetChanged();
        if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
    }

    private void toggleRecordSelection(StreamRecorder.RecordedItem item) {
        if (item == null || item.uri == null) return;
        if (selectedRecordUris.contains(item.uri)) {
            selectedRecordUris.remove(item.uri);
        } else {
            selectedRecordUris.add(item.uri);
        }
        if (recordedAdapter != null) {
            recordedAdapter.notifyDataSetChanged();
        }
    }

    private void showRecordedContextMenu(View anchor, StreamRecorder.RecordedItem item) {
        PopupMenu popup = new PopupMenu(this, anchor);
        if (!selectedRecordUris.isEmpty()) {
            int count = selectedRecordUris.size();
            popup.getMenu().add("Delete Selected (" + count + ")");
            if (count < recordedItems.size()) {
                popup.getMenu().add("Select All");
            }
            popup.getMenu().add("Clear Selection");
            popup.setOnMenuItemClickListener(menuItem -> {
                String title = String.valueOf(menuItem.getTitle());
                if (title.startsWith("Delete Selected")) {
                    confirmDeleteSelectedRecords();
                } else if ("Select All".equals(title)) {
                    for (StreamRecorder.RecordedItem r : recordedItems) {
                        if (r != null && r.uri != null) selectedRecordUris.add(r.uri);
                    }
                    if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
                } else if ("Clear Selection".equals(title)) {
                    selectedRecordUris.clear();
                    if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
                }
                return true;
            });
            popup.show();
            return;
        }

        popup.getMenu().add("Play");
        popup.getMenu().add("Delete");
        popup.getMenu().add("Select");
        popup.getMenu().add("Select All");
        popup.setOnMenuItemClickListener(menuItem -> {
            CharSequence title = menuItem.getTitle();
            if ("Play".equals(title)) {
                playRecordedItem(item);
            } else if ("Delete".equals(title)) {
                new AlertDialog.Builder(this)
                        .setTitle("Delete Recording")
                        .setMessage("Delete \"" + item.displayName + "\"?")
                        .setPositiveButton("Delete", (d, w) -> {
                            boolean ok = StreamRecorder.deleteRecording(this, item);
                            if (ok) {
                                recordedItems.remove(item);
                                if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
                                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Could not delete recording", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else if ("Select".equals(title)) {
                if (item != null && item.uri != null) {
                    selectedRecordUris.add(item.uri);
                    if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
                }
            } else if ("Select All".equals(title)) {
                for (StreamRecorder.RecordedItem r : recordedItems) {
                    if (r != null && r.uri != null) selectedRecordUris.add(r.uri);
                }
                if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
            }
            return true;
        });
        popup.show();
    }

    private void confirmDeleteSelectedRecords() {
        int count = selectedRecordUris.size();
        if (count == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete Recordings")
                .setMessage("Delete " + count + " selected recording(s)?")
                .setPositiveButton("Delete", (d, w) -> {
                    List<StreamRecorder.RecordedItem> toDelete = new ArrayList<>();
                    for (StreamRecorder.RecordedItem r : recordedItems) {
                        if (r != null && r.uri != null && selectedRecordUris.contains(r.uri)) {
                            toDelete.add(r);
                        }
                    }
                    int deleted = 0;
                    for (StreamRecorder.RecordedItem r : toDelete) {
                        if (StreamRecorder.deleteRecording(this, r)) {
                            recordedItems.remove(r);
                            deleted++;
                        }
                    }
                    selectedRecordUris.clear();
                    if (recordedAdapter != null) recordedAdapter.notifyDataSetChanged();
                    Toast.makeText(this, "Deleted " + deleted + " recording(s)", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                    currentTab = TAB_STATIONS;
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
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable);
        StreamRecorder.getInstance().removeListener(recorderListener);
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

    private void setNowPlayingThumbVisible(boolean visible) {
        if (nowPlayingThumb != null && nowPlayingThumb.getParent() instanceof View) {
            ((View) nowPlayingThumb.getParent()).setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void syncCurrentStation(@Nullable MediaItem mediaItem) {
        if (mediaItem != null && mediaItem.mediaId.startsWith("recorded:")) {
            currentStation = null;
            setNowPlayingThumbVisible(false);
            return;
        }
        if (mediaItem != null && allStations != null) {
            for (RadioStationParser.RadioStation station : allStations) {
                if (station != null && Objects.equals(station.source, mediaItem.mediaId)) {
                    currentStation = station;
                    currentStationView.setText(StationStore.safeTitle(station));
                    setNowPlayingThumbVisible(true);
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
                StreamRecorder.getInstance().onSongChanged(finalInfo);
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
        currentRecordedUri = null;
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
            setNowPlayingThumbVisible(true);
            loadFaviconAsync(station.favicon, nowPlayingThumb);
            stationAdapter.notifyDataSetChanged();
        }
    }

    private void setDefaultStationIcon(ImageView target) {
        if (target == null) return;
        if (target == nowPlayingThumb) {
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setImageResource(R.mipmap.ic_launcher);
            return;
        }
        int sizePx = Math.round(32f * getResources().getDisplayMetrics().density);
        android.graphics.drawable.GradientDrawable bullet = new android.graphics.drawable.GradientDrawable();
        bullet.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bullet.setSize(sizePx, sizePx);
        bullet.setColor(ThemeHelper.getAccentColor(this));
        target.setScaleType(ImageView.ScaleType.CENTER);
        target.setImageDrawable(bullet);
    }

    private void loadFaviconAsync(String urlString, ImageView target) {
        if (target == null) return;
        if (urlString == null || urlString.isEmpty()) {
            setDefaultStationIcon(target);
            return;
        }
        Bitmap cached = iconCache.get(urlString);
        if (cached != null) {
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setImageBitmap(cached);
            return;
        }
        setDefaultStationIcon(target);
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
                                nowPlayingThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
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
                setDefaultStationIcon(holder.icon);
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
                if (currentTab == TAB_FAVORITES) {
                    updateDisplayedStations();
                } else {
                    notifyDataSetChanged();
                }
            });

            int accent = ThemeHelper.getAccentColor(MainActivity.this);
            holder.favIcon.setTextColor(accent);
            holder.nowPlayingIndicator.setBackgroundColor(accent);

            boolean isCurrent = currentStation != null && Objects.equals(currentStation.source, station.source);
            holder.nowPlayingIndicator.setVisibility(isCurrent ? View.VISIBLE : View.INVISIBLE);
            if (isCurrent) {
                convertView.setBackgroundColor(ThemeHelper.getSelectedRowColor(MainActivity.this));
            } else {
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }
    }

    private class RecordedAdapter extends ArrayAdapter<StreamRecorder.RecordedItem> {
        private final java.text.DateFormat dateFormat = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);

        RecordedAdapter() {
            super(MainActivity.this, R.layout.station_list_item, recordedItems);
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

            StreamRecorder.RecordedItem item = getItem(position);
            if (item != null) {
                holder.title.setText(item.displayName);
                String meta = StreamRecorder.formatSize(item.sizeBytes);
                if (item.dateAddedMs > 0) {
                    meta += " · " + dateFormat.format(new java.util.Date(item.dateAddedMs));
                }
                holder.subtitle.setText(meta);
                holder.subtitle.setVisibility(View.VISIBLE);
                if (holder.icon.getParent() instanceof View) {
                    ((View) holder.icon.getParent()).setVisibility(View.GONE);
                }

                int accent = ThemeHelper.getAccentColor(MainActivity.this);
                boolean isSelected = item.uri != null && selectedRecordUris.contains(item.uri);
                boolean inSelectionMode = !selectedRecordUris.isEmpty();
                if (inSelectionMode) {
                    holder.favIcon.setText(isSelected ? "☑" : "☐");
                    holder.favIcon.setTextColor(accent);
                    holder.favIcon.setOnClickListener(v -> toggleRecordSelection(item));
                } else {
                    holder.favIcon.setText("▶");
                    holder.favIcon.setTextColor(accent);
                    holder.favIcon.setOnClickListener(v -> playRecordedItem(item));
                }

                boolean isCurrent = currentRecordedUri != null && currentRecordedUri.equals(item.uri);
                holder.nowPlayingIndicator.setBackgroundColor(accent);
                holder.nowPlayingIndicator.setVisibility((isCurrent || isSelected) ? View.VISIBLE : View.INVISIBLE);
                convertView.setBackgroundColor((isCurrent || isSelected)
                        ? ThemeHelper.getSelectedRowColor(MainActivity.this)
                        : Color.TRANSPARENT);
            }
            return convertView;
        }
    }
}
