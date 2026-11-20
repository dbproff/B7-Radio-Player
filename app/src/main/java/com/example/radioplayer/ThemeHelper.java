package com.example.radioplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.RadioButton;
import android.widget.SeekBar;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.CompoundButtonCompat;

public final class ThemeHelper {
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_ACCENT_COLOR = "accent_color";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_LIGHT = 2;

    /**
     * Preset accent colors. Entry 0 means "Default Violet" (uses R.color.app_accent).
     */
    public static final int[] PRESET_COLORS = {
            0,          // Default Violet
            0xFF2196F3, // Blue
            0xFF009688, // Teal
            0xFF4CAF50, // Green
            0xFFFF9800, // Orange
            0xFFF44336, // Red
            0xFFE91E63, // Pink
            0xFF3F51B5  // Indigo
    };

    public static final String[] PRESET_NAMES = {
            "Navy (Default)",
            "Blue",
            "Teal",
            "Green",
            "Orange",
            "Red",
            "Pink",
            "Indigo"
    };

    private ThemeHelper() {}

    public static void applySavedTheme(Context context) {
        applyTheme(getSavedTheme(context));
    }

    public static int getSavedTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM);
    }

    public static void saveAndApplyTheme(Context context, int mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_THEME_MODE, mode)
                .apply();
        applyTheme(mode);
    }

    public static void applyTheme(int mode) {
        switch (mode) {
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static int getSavedAccentColor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_ACCENT_COLOR, 0);
    }

    public static void saveAccentColor(Context context, int color) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ACCENT_COLOR, color)
                .apply();
    }

    public static int getAccentColor(Context context) {
        int saved = getSavedAccentColor(context);
        if (saved != 0) {
            return saved;
        }
        return ContextCompat.getColor(context, R.color.app_accent);
    }

    public static int getSelectedRowColor(Context context) {
        int saved = getSavedAccentColor(context);
        if (saved != 0) {
            return (saved & 0x00FFFFFF) | 0x22000000;
        }
        return ContextCompat.getColor(context, R.color.app_selected_row);
    }

    public static String formatColorName(int savedColor) {
        if (savedColor == 0) {
            return PRESET_NAMES[0];
        }
        for (int i = 1; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == savedColor) {
                return PRESET_NAMES[i];
            }
        }
        return String.format("#%06X", (0xFFFFFF & savedColor));
    }

    public static void tintSeekBar(SeekBar seekBar, int color) {
        if (seekBar == null) return;
        Drawable progressDrawable = seekBar.getProgressDrawable();
        if (progressDrawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) progressDrawable.mutate();
            Drawable progressLayer = layers.findDrawableByLayerId(android.R.id.progress);
            if (progressLayer != null) {
                DrawableCompat.setTint(progressLayer, color);
            } else {
                seekBar.setProgressTintList(ColorStateList.valueOf(color));
            }
        } else {
            seekBar.setProgressTintList(ColorStateList.valueOf(color));
        }
        Drawable thumb = seekBar.getThumb();
        if (thumb != null) {
            DrawableCompat.setTint(thumb.mutate(), color);
        } else {
            seekBar.setThumbTintList(ColorStateList.valueOf(color));
        }
    }

    public static void tintRadioButton(RadioButton radioButton, int color) {
        if (radioButton == null) return;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] colors = new int[]{color, Color.GRAY};
        CompoundButtonCompat.setButtonTintList(radioButton, new ColorStateList(states, colors));
    }
}
