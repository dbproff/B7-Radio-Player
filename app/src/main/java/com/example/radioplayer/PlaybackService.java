package com.example.radioplayer;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.metadata.icy.IcyInfo;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi public class PlaybackService extends MediaLibraryService {
    private static final String TAG = "PlaybackService";
    private MediaLibrarySession mediaLibrarySession;
    private ExoPlayer player;
    private List<RadioStationParser.RadioStation> stations = new ArrayList<>();
    private Equalizer legacyEqualizer;
    private int lastBass = 1500;
    private int lastMid = 1500;
    private int lastTreble = 1500;
    private int currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Handler sleepTimerHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService eqExecutor = Executors.newSingleThreadExecutor();
    private long sleepTimerEndTimeMs = 0L;
    private int retryCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        loadStations();
        loadSavedEqualizerSettings();
        initializePlayer();
        initializeSession();
        restoreLastStation();
        Log.d(TAG, "PlaybackService created");
    }

    private void restoreLastStation() {
        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        String lastStationId = prefs.getString("last_station_id", null);
        if (lastStationId != null && player != null && stations != null) {
            for (RadioStationParser.RadioStation station : stations) {
                if (getStationId(station).equals(lastStationId)) {
                    player.setMediaItem(createMediaItem(station));
                    player.prepare();
                    player.play();
                    Log.d(TAG, "Restored and auto-playing last station: " + station.title);
                    break;
                }
            }
        }
    }

    private void saveLastStation(String mediaId) {
        if (mediaId == null) return;
        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        prefs.edit().putString("last_station_id", mediaId).apply();
    }

    private void loadSavedEqualizerSettings() {
        SharedPreferences prefs = getSharedPreferences("EqualizerPrefs", MODE_PRIVATE);
        lastBass = prefs.getInt("bass", 1500);
        lastMid = prefs.getInt("mid", 1500);
        lastTreble = prefs.getInt("treble", 1500);
        Log.d(TAG, "Loaded EQ settings: Bass=" + lastBass + ", Mid=" + lastMid + ", Treble=" + lastTreble);
    }

    @OptIn(markerClass = UnstableApi.class) 
    private void initializePlayer() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        Map<String, String> defaultRequestProperties = new HashMap<>();
        defaultRequestProperties.put("Icy-MetaData", "1");

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("RadioPlayer/1.0")
                .setDefaultRequestProperties(defaultRequestProperties)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpDataSourceFactory);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null) {
                    saveLastStation(mediaItem.mediaId);
                    if (mediaItem.mediaMetadata.artworkData == null) {
                        loadArtworkAsync(mediaItem);
                    }
                }
            }

            @Override
            public void onMetadata(@NonNull Metadata metadata) {
                for (int i = 0; i < metadata.length(); i++) {
                    Metadata.Entry entry = metadata.get(i);
                    if (entry instanceof IcyInfo icyInfo) {
                        updateMetadataFromIcy(icyInfo);
                    }
                }
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                if (audioSessionId != 0 && currentAudioSessionId != audioSessionId) {
                    currentAudioSessionId = audioSessionId;
                    Log.d(TAG, "Audio session ID changed: " + audioSessionId);
                    setupAudioEffects(audioSessionId);
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    int sessionId = player.getAudioSessionId();
                    if (sessionId != 0 && currentAudioSessionId != sessionId) {
                        currentAudioSessionId = sessionId;
                        Log.d(TAG, "Syncing EQ on READY, session: " + sessionId);
                        setupAudioEffects(sessionId);
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    MediaItem current = player.getCurrentMediaItem();
                    boolean isRecorded = current != null && current.mediaId.startsWith("recorded:");
                    if (isRecorded) {
                        player.pause();
                        player.seekTo(0);
                    } else {
                        Log.d(TAG, "Playback ended, retrying for live stream");
                        player.prepare();
                        player.play();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Player error: " + error.getErrorCodeName(), error);
                if (isRetryableError(error)) {
                    attemptReconnect();
                }
            }
        });
    }

    private boolean isRetryableError(PlaybackException error) {
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED;
    }

    private void attemptReconnect() {
        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(() -> {
            if (player != null) {
                Log.d(TAG, "Attempting to reconnect...");
                player.prepare();
                player.play();
            }
        }, 3000); // 3 second delay
    }

    private void updateMetadataFromIcy(IcyInfo icyInfo) {
        MediaItem currentItem = player.getCurrentMediaItem();
        if (currentItem == null) return;

        String decodedTitle = null;
        try {
            if (icyInfo.rawMetadata != null) {
                String raw = new String(icyInfo.rawMetadata, StandardCharsets.ISO_8859_1);
                int titleStart = raw.indexOf("StreamTitle='");
                if (titleStart != -1) {
                    titleStart += 13;
                    int titleEnd = raw.indexOf("';", titleStart);
                    if (titleEnd != -1 && titleEnd <= icyInfo.rawMetadata.length) {
                        byte[] titleBytes = new byte[titleEnd - titleStart];
                        System.arraycopy(icyInfo.rawMetadata, titleStart, titleBytes, 0, titleBytes.length);

                        String utf8Candidate = new String(titleBytes, StandardCharsets.UTF_8);
                        if (!utf8Candidate.contains("\uFFFD")) {
                            decodedTitle = utf8Candidate;
                        } else {
                            decodedTitle = new String(titleBytes, java.nio.charset.Charset.forName("windows-1251"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding ICY metadata", e);
        }

        if (decodedTitle == null || decodedTitle.isEmpty()) {
            decodedTitle = icyInfo.title;
        }

        if (decodedTitle == null || decodedTitle.isEmpty()) return;

        String songDisplay = decodedTitle.trim();
        if (isInvalidStreamTitle(songDisplay)) return;

        String stationName = "";
        if (stations != null) {
            for (RadioStationParser.RadioStation s : stations) {
                if (getStationId(s).equals(currentItem.mediaId)) {
                    stationName = s.title != null ? s.title : "";
                    break;
                }
            }
        }
        if (stationName.isEmpty() && currentItem.mediaMetadata.title != null) {
            stationName = currentItem.mediaMetadata.title.toString();
        }

        String songDisplayFinal = songDisplay;
        MediaMetadata newMetadata = currentItem.mediaMetadata.buildUpon()
                .setTitle(stationName.isEmpty() ? songDisplayFinal : stationName)
                .setArtist(stationName.isEmpty() ? "" : songDisplayFinal)
                .build();

        int currentIndex = player.getCurrentMediaItemIndex();
        if (currentIndex >= 0 && currentIndex < player.getMediaItemCount()) {
            player.replaceMediaItem(currentIndex, currentItem.buildUpon().setMediaMetadata(newMetadata).build());
        }
    }

    private static boolean isInvalidStreamTitle(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("{") || t.startsWith("[") || t.startsWith("<")) return true;
        String lower = t.toLowerCase(java.util.Locale.US);
        return lower.contains("\"status\":")
                || lower.contains("\"message\":")
                || lower.contains("\"result\":")
                || lower.contains("\"error\":");
    }

    private void setupAudioEffects(int audioSessionId) {
        if (audioSessionId == 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return;

        eqExecutor.execute(() -> {
            Log.d(TAG, "Setting up Audio Effects for session: " + audioSessionId);
            try {
                if (legacyEqualizer != null) {
                    legacyEqualizer.release();
                    legacyEqualizer = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing old equalizer", e);
            }

            try {
                legacyEqualizer = new Equalizer(0, audioSessionId);
                legacyEqualizer.setEnabled(true);
                Log.d(TAG, "Equalizer enabled");

                applyEqualizerSettings();

                Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
                intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
                intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                sendBroadcast(intent);
            } catch (Exception | LinkageError e) {
                Log.e(TAG, "Failed to initialize Equalizer", e);
                legacyEqualizer = null;
            }
        });
    }

    private void applyEqualizerSettings() {
        if (legacyEqualizer == null) return;
        try {
            short numBands = legacyEqualizer.getNumberOfBands();
            if (numBands <= 0) return;

            short[] range = legacyEqualizer.getBandLevelRange();
            short minLevel = range != null && range.length >= 2 ? range[0] : -1500;
            short maxLevel = range != null && range.length >= 2 ? range[1] : 1500;

            for (short band = 0; band < numBands; band++) {
                int rawSlider;
                if (band < numBands / 3) {
                    rawSlider = lastBass;
                } else if (band < (2 * numBands) / 3 || numBands < 3) {
                    rawSlider = lastMid;
                } else {
                    rawSlider = lastTreble;
                }
                // SeekBar range is 0..3000 with 1500 as neutral center.
                float normalized = Math.max(0f, Math.min(1f, rawSlider / 3000f));
                short level = (short) Math.round(minLevel + normalized * (maxLevel - minLevel));
                legacyEqualizer.setBandLevel(band, level);
            }
            Log.d(TAG, "Applied EQ settings to Equalizer (" + numBands + " bands)");
        } catch (Exception e) {
            Log.e(TAG, "Error applying settings to Equalizer", e);
        }
    }

    private void updateEqualizer(int bass, int mid, int treble) {
        lastBass = bass;
        lastMid = mid;
        lastTreble = treble;
        
        SharedPreferences.Editor editor = getSharedPreferences("EqualizerPrefs", MODE_PRIVATE).edit();
        editor.putInt("bass", bass);
        editor.putInt("mid", mid);
        editor.putInt("treble", treble);
        editor.apply();

        applyEqualizerSettings();
    }

    private void loadStations() {
        stations = StationStore.load(this);
        Log.d(TAG, "Loaded " + stations.size() + " stations via StationStore");
    }

    private void setSleepTimerMinutes(int minutes) {
        sleepTimerHandler.removeCallbacksAndMessages(null);
        if (minutes <= 0) {
            sleepTimerEndTimeMs = 0L;
            Log.d(TAG, "Sleep timer cancelled");
            return;
        }
        long delayMs = minutes * 60_000L;
        sleepTimerEndTimeMs = System.currentTimeMillis() + delayMs;
        sleepTimerHandler.postDelayed(() -> {
            sleepTimerEndTimeMs = 0L;
            if (player != null && player.isPlaying()) {
                player.pause();
                Log.d(TAG, "Sleep timer expired, playback paused");
            }
        }, delayMs);
        Log.d(TAG, "Sleep timer set for " + minutes + " minutes");
    }

    private String getStationId(RadioStationParser.RadioStation station) {
        if (station == null) return "";
        if (station.source != null && !station.source.isEmpty()) return station.source;
        return station.url != null ? station.url : "";
    }

    private MediaItem createMediaItem(RadioStationParser.RadioStation station) {
        String id = getStationId(station);
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(station.title)
                .setArtist(station.tags)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setArtworkUri(station.favicon != null && !station.favicon.isEmpty() ? Uri.parse(station.favicon) : null)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build();
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri(id)
                .setMediaMetadata(metadata)
                .build();
    }

    private void initializeSession() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        ForwardingPlayer sessionPlayer = new ForwardingPlayer(player) {
            @Override
            public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build();
            }

            @Override
            public boolean isCommandAvailable(int command) {
                if (command == Player.COMMAND_SEEK_TO_PREVIOUS
                        || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                        || command == Player.COMMAND_SEEK_TO_NEXT
                        || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                    return stations != null && stations.size() > 1;
                }
                return super.isCommandAvailable(command);
            }

            @Override
            public void seekToPrevious() {
                skipStation(-1);
            }

            @Override
            public void seekToPreviousMediaItem() {
                skipStation(-1);
            }

            @Override
            public void seekToNext() {
                skipStation(1);
            }

            @Override
            public void seekToNextMediaItem() {
                skipStation(1);
            }
        };

        CommandButton quitButton = new CommandButton.Builder()
                .setDisplayName("Quit")
                .setIconResId(R.drawable.ic_close)
                .setSessionCommand(new SessionCommand("QUIT_APP", Bundle.EMPTY))
                .build();

        mediaLibrarySession = new MediaLibrarySession.Builder(this, sessionPlayer, new MediaLibrarySession.Callback() {
            @NonNull
            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @Nullable LibraryParams params) {
                MediaMetadata metadata = new MediaMetadata.Builder()
                        .setTitle("Radio Stations")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build();
                MediaItem root = new MediaItem.Builder()
                        .setMediaId("ROOT")
                        .setMediaMetadata(metadata)
                        .build();
                return Futures.immediateFuture(LibraryResult.ofItem(root, params));
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String parentId, int page, int pageSize, @Nullable LibraryParams params) {
                if (parentId.equals("ROOT")) {
                    List<MediaItem> items = new ArrayList<>();
                    for (RadioStationParser.RadioStation station : stations) {
                        items.add(createMediaItem(station));
                    }
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params));
                }
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetItem(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String mediaId) {
                for (RadioStationParser.RadioStation station : stations) {
                    if (getStationId(station).equals(mediaId)) {
                        return Futures.immediateFuture(LibraryResult.ofItem(createMediaItem(station), null));
                    }
                }
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
            }

            @NonNull
            @Override
            public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems) {
                List<MediaItem> updatedItems = new ArrayList<>();
                for (MediaItem item : mediaItems) {
                    if (item.localConfiguration != null) {
                        updatedItems.add(item);
                    } else {
                        // Resolve item from mediaId (essential for legacy browsers like Android Auto)
                        boolean resolved = false;
                        for (RadioStationParser.RadioStation station : stations) {
                            if (getStationId(station).equals(item.mediaId)) {
                                updatedItems.add(createMediaItem(station));
                                resolved = true;
                                break;
                            }
                        }
                        if (!resolved) {
                            updatedItems.add(item);
                        }
                    }
                }
                return Futures.immediateFuture(updatedItems);
            }

            @NonNull
            @Override
            public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull SessionCommand customCommand, @NonNull Bundle args) {
                if (customCommand.customAction.equals("GET_EQ_SETTINGS")) {
                    Bundle resultBundle = new Bundle();
                    resultBundle.putInt("bass", lastBass);
                    resultBundle.putInt("mid", lastMid);
                    resultBundle.putInt("treble", lastTreble);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS, resultBundle));
                } else if (customCommand.customAction.equals("UPDATE_EQ")) {
                    updateEqualizer(args.getInt("bass"), args.getInt("mid"), args.getInt("treble"));
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                } else if (customCommand.customAction.equals("RELOAD_STATIONS")) {
                    loadStations();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                } else if (customCommand.customAction.equals("SET_SLEEP_TIMER")) {
                    setSleepTimerMinutes(args.getInt("minutes", 0));
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                } else if (customCommand.customAction.equals("GET_SLEEP_TIMER")) {
                    long remaining = Math.max(0L, sleepTimerEndTimeMs - System.currentTimeMillis());
                    if (sleepTimerEndTimeMs == 0L) remaining = 0L;
                    Bundle resultBundle = new Bundle();
                    resultBundle.putLong("remaining_ms", remaining);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS, resultBundle));
                } else if (customCommand.customAction.equals("SET_MAX_BITRATE")) {
                    int kbps = args.getInt("kbps", 0);
                    int maxBps = kbps > 0 ? kbps * 1000 : Integer.MAX_VALUE;
                    if (player != null) {
                        player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters()
                                        .buildUpon()
                                        .setMaxAudioBitrate(maxBps)
                                        .build());
                    }
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                } else if (customCommand.customAction.equals("QUIT_APP")) {
                    quitPlaybackService();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
            }

            @NonNull
            @Override
            public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
                MediaSession.ConnectionResult connectionResult = MediaLibrarySession.Callback.super.onConnect(session, controller);
                SessionCommands.Builder commandsBuilder = connectionResult.availableSessionCommands.buildUpon();
                commandsBuilder.add(new SessionCommand("GET_EQ_SETTINGS", Bundle.EMPTY));
                commandsBuilder.add(new SessionCommand("UPDATE_EQ", Bundle.EMPTY));
                commandsBuilder.add(new SessionCommand("RELOAD_STATIONS", Bundle.EMPTY));
                commandsBuilder.add(new SessionCommand("SET_SLEEP_TIMER", Bundle.EMPTY));
                commandsBuilder.add(new SessionCommand("GET_SLEEP_TIMER", Bundle.EMPTY));
                commandsBuilder.add(new SessionCommand("SET_MAX_BITRATE", Bundle.EMPTY));
                commandsBuilder.add(new SessionCommand("QUIT_APP", Bundle.EMPTY));
                return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commandsBuilder.build())
                        .setAvailablePlayerCommands(connectionResult.availablePlayerCommands)
                        .setCustomLayout(ImmutableList.of(quitButton))
                        .build();
            }
        }).setSessionActivity(pendingIntent).build();
        mediaLibrarySession.setCustomLayout(ImmutableList.of(quitButton));
    }

    private void skipStation(int delta) {
        if (player == null) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current != null && current.mediaId.startsWith("recorded:") && player.getMediaItemCount() > 1) {
            int count = player.getMediaItemCount();
            int nextIdx = (player.getCurrentMediaItemIndex() + delta + count) % count;
            player.seekTo(nextIdx, 0L);
            player.play();
            return;
        }
        if (stations == null || stations.isEmpty()) return;
        retryHandler.removeCallbacksAndMessages(null);
        retryCount = 0;
        int currentIndex = 0;
        if (current != null) {
            for (int i = 0; i < stations.size(); i++) {
                if (getStationId(stations.get(i)).equals(current.mediaId)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        int size = stations.size();
        int nextIndex = (currentIndex + delta + size) % size;
        RadioStationParser.RadioStation nextStation = stations.get(nextIndex);
        player.setMediaItem(createMediaItem(nextStation));
        player.prepare();
        player.play();
    }

    private void quitPlaybackService() {
        retryHandler.removeCallbacksAndMessages(null);
        sleepTimerHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void loadArtworkAsync(MediaItem item) {
        String artworkUrl = null;
        if (item.mediaMetadata.artworkUri != null) {
            artworkUrl = item.mediaMetadata.artworkUri.toString();
        } else if (stations != null) {
            for (RadioStationParser.RadioStation s : stations) {
                if (getStationId(s).equals(item.mediaId) && s.favicon != null && !s.favicon.isEmpty()) {
                    artworkUrl = s.favicon;
                    break;
                }
            }
        }
        if (artworkUrl == null || artworkUrl.trim().isEmpty()) return;
        final String targetUrl = artworkUrl.trim();
        final String targetMediaId = item.mediaId;

        artworkExecutor.execute(() -> {
            byte[] bytes = fetchArtworkBytes(targetUrl);
            if (bytes == null || bytes.length == 0) return;
            retryHandler.post(() -> {
                if (player == null) return;
                MediaItem current = player.getCurrentMediaItem();
                if (current == null || !targetMediaId.equals(current.mediaId)) return;
                MediaMetadata updatedMeta = current.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build();
                int idx = player.getCurrentMediaItemIndex();
                if (idx >= 0 && idx < player.getMediaItemCount()) {
                    player.replaceMediaItem(idx, current.buildUpon().setMediaMetadata(updatedMeta).build());
                }
            });
        });
    }

    @Nullable
    private byte[] fetchArtworkBytes(String urlOrUri) {
        try {
            if (urlOrUri.startsWith("content://") || urlOrUri.startsWith("file://")) {
                try (InputStream is = getContentResolver().openInputStream(Uri.parse(urlOrUri))) {
                    return readBytes(is);
                }
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(urlOrUri).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            try (InputStream is = conn.getInputStream()) {
                return readBytes(is);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readBytes(@Nullable InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int total = 0;
        int maxBytes = 512 * 1024;
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            total += nRead;
            if (total > maxBytes) {
                return null;
            }
            buffer.write(data, 0, nRead);
        }
        byte[] raw = buffer.toByteArray();
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (bmp == null) return raw;
        if (bmp.getWidth() > 256 || bmp.getHeight() > 256) {
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 256, 256, true);
            bmp.recycle();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            scaled.recycle();
            return out.toByteArray();
        }
        bmp.recycle();
        return raw;
    }

    @Override
    public void onDestroy() {
        retryHandler.removeCallbacksAndMessages(null);
        sleepTimerHandler.removeCallbacksAndMessages(null);
        artworkExecutor.shutdownNow();
        eqExecutor.shutdownNow();
        if (mediaLibrarySession != null) { mediaLibrarySession.release(); mediaLibrarySession = null; }
        if (player != null) { player.release(); player = null; }
        if (legacyEqualizer != null) { legacyEqualizer.release(); legacyEqualizer = null; }
        super.onDestroy();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaLibrarySession;
    }
}
