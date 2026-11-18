package com.example.radioplayer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Captures live HTTP/HTTPS radio streams to Music, Downloads, or a user-selected SAF folder,
 * and lists/deletes captured recordings.
 */
public final class StreamRecorder {

    public static final int STATE_IDLE = 0;
    public static final int STATE_RECORDING = 1;
    public static final int STATE_PAUSED = 2;

    public static final int LOCATION_MUSIC = 0;
    public static final int LOCATION_DOWNLOADS = 1;
    public static final int LOCATION_CUSTOM = 2;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_CAPTURE_LOCATION = "capture_location";
    private static final String KEY_CAPTURE_CUSTOM_URI = "capture_custom_uri";
    private static final String SUBFOLDER_NAME = "B7 Radio Player";

    public static final class RecordedItem {
        public final String displayName;
        public final Uri uri;
        public final long sizeBytes;
        public final long dateAddedMs;
        public final String mimeType;
        public final boolean isCustomTreeDoc;

        public RecordedItem(String displayName, Uri uri, long sizeBytes, long dateAddedMs,
                            String mimeType, boolean isCustomTreeDoc) {
            this.displayName = displayName;
            this.uri = uri;
            this.sizeBytes = sizeBytes;
            this.dateAddedMs = dateAddedMs;
            this.mimeType = mimeType;
            this.isCustomTreeDoc = isCustomTreeDoc;
        }
    }

    public interface Listener {
        void onRecorderStateChanged(int state, String stationTitle, String fileName,
                                    long elapsedMs, long bytesWritten, String errorMessage);
        void onRecordingFinished();
    }

    private static final StreamRecorder INSTANCE = new StreamRecorder();

    public static StreamRecorder getInstance() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new HashSet<>();

    private volatile int state = STATE_IDLE;
    private volatile boolean stopRequested = false;
    private volatile boolean pauseRequested = false;

    private Context appContext;
    private String currentStreamUrl;
    private String currentStationTitle;
    private String currentSongTitle;
    private String currentFileName;
    private String currentMimeType = "audio/mpeg";
    private Uri currentOutputUri;
    private File currentOutputFile;
    private OutputStream currentOutputStream;
    private int currentLocationMode = LOCATION_MUSIC;

    private long accumulatedElapsedMs = 0L;
    private long segmentStartMs = 0L;
    private long bytesWritten = 0L;
    private Thread workerThread;

    private final Runnable tickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == STATE_RECORDING) {
                notifyState(null);
                mainHandler.postDelayed(this, 500);
            }
        }
    };

    private StreamRecorder() {}

    public void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (listeners) {
            listeners.add(listener);
        }
        listener.onRecorderStateChanged(state, currentStationTitle, currentFileName,
                getElapsedMs(), bytesWritten, null);
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public int getState() {
        return state;
    }

    public String getCurrentStationTitle() {
        return currentStationTitle;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    public long getBytesWritten() {
        return bytesWritten;
    }

    public long getElapsedMs() {
        if (state == STATE_RECORDING && segmentStartMs > 0) {
            return accumulatedElapsedMs + Math.max(0L, System.currentTimeMillis() - segmentStartMs);
        }
        return accumulatedElapsedMs;
    }

    public static int getCaptureLocationMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_CAPTURE_LOCATION, LOCATION_MUSIC);
    }

    public static void setCaptureLocationMode(Context context, int mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CAPTURE_LOCATION, mode)
                .apply();
    }

    public static String getCustomFolderUri(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CAPTURE_CUSTOM_URI, "");
    }

    public static void setCustomFolderUri(Context context, String uriString) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CAPTURE_CUSTOM_URI, uriString != null ? uriString : "")
                .apply();
    }

    public synchronized void startRecording(Context context, String streamUrl,
                                            String stationTitle, String songTitle) {
        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            notifyState("No active stream URL to record");
            return;
        }
        if (state == STATE_RECORDING) {
            return;
        }
        if (state == STATE_PAUSED) {
            resumeRecording();
            return;
        }

        this.appContext = context.getApplicationContext();
        this.currentStreamUrl = streamUrl.trim();
        this.currentStationTitle = (stationTitle != null && !stationTitle.trim().isEmpty())
                ? stationTitle.trim() : "Radio Stream";
        this.currentSongTitle = (songTitle != null) ? songTitle.trim() : "";
        this.accumulatedElapsedMs = 0L;
        this.bytesWritten = 0L;
        this.stopRequested = false;
        this.pauseRequested = false;
        this.currentOutputUri = null;
        this.currentOutputFile = null;
        this.currentOutputStream = null;
        this.currentLocationMode = getCaptureLocationMode(this.appContext);

        state = STATE_RECORDING;
        segmentStartMs = System.currentTimeMillis();
        notifyState(null);
        mainHandler.post(tickerRunnable);

        startWorker();
    }

    public synchronized void pauseRecording() {
        if (state != STATE_RECORDING) return;
        pauseRequested = true;
        accumulatedElapsedMs += Math.max(0L, System.currentTimeMillis() - segmentStartMs);
        segmentStartMs = 0L;
        state = STATE_PAUSED;
        mainHandler.removeCallbacks(tickerRunnable);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        notifyState(null);
    }

    public synchronized void resumeRecording() {
        if (state != STATE_PAUSED || currentStreamUrl == null) return;
        pauseRequested = false;
        stopRequested = false;
        state = STATE_RECORDING;
        segmentStartMs = System.currentTimeMillis();
        notifyState(null);
        mainHandler.post(tickerRunnable);
        startWorker();
    }

    public synchronized void stopRecording() {
        if (state == STATE_IDLE) return;
        stopRequested = true;
        pauseRequested = false;
        if (state == STATE_RECORDING && segmentStartMs > 0) {
            accumulatedElapsedMs += Math.max(0L, System.currentTimeMillis() - segmentStartMs);
        }
        segmentStartMs = 0L;
        mainHandler.removeCallbacks(tickerRunnable);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (state == STATE_PAUSED) {
            finalizeOutputAndReset(null);
        }
    }

    private void startWorker() {
        workerThread = new Thread(this::captureLoop, "StreamRecorderThread");
        workerThread.start();
    }

    private void captureLoop() {
        HttpURLConnection conn = null;
        String errorMsg = null;
        try {
            String urlStr = currentStreamUrl;
            for (int redirect = 0; redirect < 5; redirect++) {
                if (stopRequested || pauseRequested) break;
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "B7RadioPlayer/1.0 (Android)");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null || loc.trim().isEmpty()) {
                        throw new IOException("Broken redirect (" + code + ")");
                    }
                    urlStr = new URL(new URL(urlStr), loc.trim()).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                break;
            }

            if (conn != null && !stopRequested && !pauseRequested) {
                if (currentOutputStream == null) {
                    String contentType = conn.getContentType();
                    openDestinationOutput(contentType);
                }
                byte[] buffer = new byte[8192];
                try (InputStream is = conn.getInputStream()) {
                    int read;
                    while (!stopRequested && !pauseRequested && (read = is.read(buffer)) != -1) {
                        if (read > 0 && currentOutputStream != null) {
                            currentOutputStream.write(buffer, 0, read);
                            bytesWritten += read;
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (!stopRequested && !pauseRequested) {
                errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }

        if (pauseRequested && !stopRequested) {
            try {
                if (currentOutputStream != null) {
                    currentOutputStream.flush();
                }
            } catch (Exception ignored) {}
            notifyState(errorMsg);
        } else {
            finalizeOutputAndReset(errorMsg);
        }
    }

    private void openDestinationOutput(String contentType) throws IOException {
        String ext = ".mp3";
        String mime = "audio/mpeg";
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.US);
            if (lower.contains("aac")) {
                ext = ".aac";
                mime = "audio/aac";
            } else if (lower.contains("ogg") || lower.contains("opus")) {
                ext = ".ogg";
                mime = "audio/ogg";
            } else if (lower.contains("flac")) {
                ext = ".flac";
                mime = "audio/flac";
            }
        }
        currentMimeType = mime;

        String safeStation = sanitizeFilePart(currentStationTitle, 32);
        if (safeStation.isEmpty()) safeStation = "Radio";

        String safeSong = "";
        if (currentSongTitle != null && !currentSongTitle.isEmpty()
                && !"...".equals(currentSongTitle)
                && !currentSongTitle.startsWith("Buffering")
                && !currentSongTitle.startsWith("Recorded Stream")) {
            safeSong = sanitizeFilePart(currentSongTitle, 40);
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        if (!safeSong.isEmpty()) {
            currentFileName = safeStation + "_" + safeSong + "_" + stamp + ext;
        } else {
            currentFileName = safeStation + "_" + stamp + ext;
        }

        if (currentLocationMode == LOCATION_CUSTOM) {
            String treeStr = getCustomFolderUri(appContext);
            if (treeStr != null && !treeStr.isEmpty()) {
                Uri treeUri = Uri.parse(treeStr);
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                Uri created = DocumentsContract.createDocument(
                        appContext.getContentResolver(), docUri, mime, currentFileName);
                if (created == null) {
                    throw new IOException("Cannot create file in custom folder");
                }
                currentOutputUri = created;
                currentOutputStream = appContext.getContentResolver().openOutputStream(created);
                if (currentOutputStream == null) {
                    throw new IOException("Cannot open custom folder output stream");
                }
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = appContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, currentFileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection;
            if (currentLocationMode == LOCATION_DOWNLOADS) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER_NAME);
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_MUSIC + "/" + SUBFOLDER_NAME);
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            }

            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new IOException("Failed to create MediaStore entry");
            }
            currentOutputUri = uri;
            currentOutputStream = resolver.openOutputStream(uri);
            if (currentOutputStream == null) {
                throw new IOException("Failed to open output stream");
            }
        } else {
            String dirType = (currentLocationMode == LOCATION_DOWNLOADS)
                    ? Environment.DIRECTORY_DOWNLOADS : Environment.DIRECTORY_MUSIC;
            File baseDir = appContext.getExternalFilesDir(dirType);
            if (baseDir == null) {
                baseDir = appContext.getFilesDir();
            }
            File targetDir = new File(baseDir, SUBFOLDER_NAME);
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw new IOException("Cannot create output directory");
            }
            File outFile = new File(targetDir, currentFileName);
            currentOutputFile = outFile;
            currentOutputUri = Uri.fromFile(outFile);
            currentOutputStream = new FileOutputStream(outFile);
        }
    }

    private synchronized void finalizeOutputAndReset(String errorMsg) {
        if (currentOutputStream != null) {
            try {
                currentOutputStream.flush();
                currentOutputStream.close();
            } catch (Exception ignored) {}
            currentOutputStream = null;
        }

        if (appContext != null && currentOutputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && currentLocationMode != LOCATION_CUSTOM) {
            try {
                if (bytesWritten == 0) {
                    appContext.getContentResolver().delete(currentOutputUri, null, null);
                } else {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    appContext.getContentResolver().update(currentOutputUri, values, null, null);
                }
            } catch (Exception ignored) {}
        } else if (currentOutputFile != null && bytesWritten == 0 && currentOutputFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            currentOutputFile.delete();
        }

        boolean saved = bytesWritten > 0;
        state = STATE_IDLE;
        segmentStartMs = 0L;
        stopRequested = false;
        pauseRequested = false;
        mainHandler.removeCallbacks(tickerRunnable);

        notifyState(errorMsg);
        if (saved) {
            mainHandler.post(() -> {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<>(listeners);
                }
                for (Listener l : copy) {
                    l.onRecordingFinished();
                }
            });
        }
    }

    private void notifyState(String errorMsg) {
        final int st = state;
        final String title = currentStationTitle;
        final String file = currentFileName;
        final long elapsed = getElapsedMs();
        final long bytes = bytesWritten;
        mainHandler.post(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) {
                l.onRecorderStateChanged(st, title, file, elapsed, bytes, errorMsg);
            }
        });
    }

    public static List<RecordedItem> listRecordings(Context context) {
        List<RecordedItem> items = new ArrayList<>();
        Set<String> seenUris = new HashSet<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, items, seenUris);
            queryMediaStore(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, items, seenUris);
        } else {
            collectLegacyFiles(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), items, seenUris);
            collectLegacyFiles(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), items, seenUris);
        }

        String customTree = getCustomFolderUri(context);
        if (customTree != null && !customTree.isEmpty()) {
            queryCustomTree(context, Uri.parse(customTree), items, seenUris);
        }

        Collections.sort(items, (a, b) -> Long.compare(b.dateAddedMs, a.dateAddedMs));
        return items;
    }

    private static void queryMediaStore(Context context, Uri collection,
                                        List<RecordedItem> items, Set<String> seenUris) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {"%" + SUBFOLDER_NAME + "%"};
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, args,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                if (!seenUris.add(itemUri.toString())) continue;
                String name = cursor.getString(nameCol);
                long size = cursor.getLong(sizeCol);
                long dateMs = cursor.getLong(dateCol) * 1000L;
                String mime = cursor.getString(mimeCol);
                if (name == null || size <= 0) continue;
                items.add(new RecordedItem(name, itemUri, size, dateMs, mime, false));
            }
        } catch (Exception ignored) {}
    }

    private static void collectLegacyFiles(File baseDir, List<RecordedItem> items, Set<String> seenUris) {
        if (baseDir == null) return;
        File dir = new File(baseDir, SUBFOLDER_NAME);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || f.length() <= 0) continue;
            Uri uri = Uri.fromFile(f);
            if (!seenUris.add(uri.toString())) continue;
            items.add(new RecordedItem(f.getName(), uri, f.length(), f.lastModified(), "audio/*", false));
        }
    }

    private static void queryCustomTree(Context context, Uri treeUri,
                                        List<RecordedItem> items, Set<String> seenUris) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = context.getContentResolver().query(
                    childrenUri, projection, null, null, null)) {
                if (cursor == null) return;
                int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
                int modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);

                while (cursor.moveToNext()) {
                    String mime = cursor.getString(mimeCol);
                    String name = cursor.getString(nameCol);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                    if (name == null) continue;
                    String lower = name.toLowerCase(Locale.US);
                    boolean isAudio = (mime != null && mime.startsWith("audio/"))
                            || lower.endsWith(".mp3") || lower.endsWith(".aac")
                            || lower.endsWith(".ogg") || lower.endsWith(".flac");
                    if (!isAudio) continue;

                    String docId = cursor.getString(idCol);
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    if (!seenUris.add(docUri.toString())) continue;
                    long size = cursor.getLong(sizeCol);
                    long modMs = cursor.getLong(modCol);
                    items.add(new RecordedItem(name, docUri, size, modMs, mime, true));
                }
            }
        } catch (Exception ignored) {}
    }

    public static boolean deleteRecording(Context context, RecordedItem item) {
        if (item == null || item.uri == null) return false;
        try {
            if ("file".equals(item.uri.getScheme()) && item.uri.getPath() != null) {
                return new File(item.uri.getPath()).delete();
            }
            if (item.isCustomTreeDoc) {
                return DocumentsContract.deleteDocument(context.getContentResolver(), item.uri);
            }
            return context.getContentResolver().delete(item.uri, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeFilePart(String input, int maxLen) {
        if (input == null) return "";
        String cleaned = input.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1).trim();
        if (cleaned.length() > maxLen) {
            cleaned = cleaned.substring(0, maxLen).trim();
        }
        return cleaned;
    }

    public static String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
