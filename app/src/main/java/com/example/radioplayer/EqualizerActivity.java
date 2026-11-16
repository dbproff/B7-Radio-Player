package com.example.radioplayer;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Locale;

public class EqualizerActivity extends AppCompatActivity {

    private SeekBar bassSeekBar, midSeekBar, trebleSeekBar, volumeSeekBar;
    private TextView bassValueText, midValueText, trebleValueText, volumeValueText;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private AudioManager audioManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);

        bassSeekBar = findViewById(R.id.bassSeekBar);
        midSeekBar = findViewById(R.id.midSeekBar);
        trebleSeekBar = findViewById(R.id.trebleSeekBar);
        volumeSeekBar = findViewById(R.id.volumeSeekBar);

        bassValueText = findViewById(R.id.bassValueText);
        midValueText = findViewById(R.id.midValueText);
        trebleValueText = findViewById(R.id.trebleValueText);
        volumeValueText = findViewById(R.id.volumeValueText);

        ImageButton backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        Button resetButton = findViewById(R.id.eqResetButton);
        resetButton.setOnClickListener(v -> applyPreset(1500, 1500, 1500));

        findViewById(R.id.presetFlat).setOnClickListener(v -> applyPreset(1500, 1500, 1500));
        findViewById(R.id.presetBass).setOnClickListener(v -> applyPreset(2400, 1500, 1300));
        findViewById(R.id.presetVocal).setOnClickListener(v -> applyPreset(1100, 2200, 1800));
        findViewById(R.id.presetRock).setOnClickListener(v -> applyPreset(2100, 1300, 2100));
        findViewById(R.id.presetJazz).setOnClickListener(v -> applyPreset(1800, 1700, 1900));

        SharedPreferences prefs = getSharedPreferences("EqualizerPrefs", MODE_PRIVATE);
        bassSeekBar.setProgress(prefs.getInt("bass", 1500));
        midSeekBar.setProgress(prefs.getInt("mid", 1500));
        trebleSeekBar.setProgress(prefs.getInt("treble", 1500));
        updateEqLabels();

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setupVolumeControl();
        setupEqualizerControls();
    }

    private void applyPreset(int bass, int mid, int treble) {
        bassSeekBar.setProgress(bass);
        midSeekBar.setProgress(mid);
        trebleSeekBar.setProgress(treble);
        updateEqLabels();
        updateEqualizer();
    }

    private void updateEqLabels() {
        bassValueText.setText(formatDb(bassSeekBar.getProgress()));
        midValueText.setText(formatDb(midSeekBar.getProgress()));
        trebleValueText.setText(formatDb(trebleSeekBar.getProgress()));
    }

    private String formatDb(int sliderVal) {
        int db = Math.round((sliderVal - 1500) / 100f);
        if (db > 0) return "+" + db + " dB";
        return db + " dB";
    }

    private void setupVolumeControl() {
        if (volumeSeekBar == null || audioManager == null) return;

        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        volumeSeekBar.setMax(maxVolume);
        volumeSeekBar.setProgress(currentVolume);
        updateVolumeLabel(currentVolume, maxVolume);

        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateVolumeLabel(progress, maxVolume);
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateVolumeLabel(int current, int max) {
        int pct = max > 0 ? Math.round((current * 100f) / max) : 0;
        volumeValueText.setText(String.format(Locale.US, "%d%%", pct));
    }

    private void setupEqualizerControls() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateEqLabels();
                if (fromUser && mediaController != null) {
                    updateEqualizer();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        bassSeekBar.setOnSeekBarChangeListener(listener);
        midSeekBar.setOnSeekBarChangeListener(listener);
        trebleSeekBar.setOnSeekBarChangeListener(listener);
    }

    private void updateEqualizer() {
        int bass = bassSeekBar.getProgress();
        int mid = midSeekBar.getProgress();
        int treble = trebleSeekBar.getProgress();

        getSharedPreferences("EqualizerPrefs", MODE_PRIVATE).edit()
                .putInt("bass", bass)
                .putInt("mid", mid)
                .putInt("treble", treble)
                .apply();

        Bundle args = new Bundle();
        args.putInt("bass", bass);
        args.putInt("mid", mid);
        args.putInt("treble", treble);
        if (mediaController != null) {
            mediaController.sendCustomCommand(new SessionCommand("UPDATE_EQ", Bundle.EMPTY), args);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                mediaController = controllerFuture.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        mediaController = null;
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }
}
