package com.example.radioplayer;

import android.app.Application;

public class RadioPlayerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeHelper.applySavedTheme(this);
    }
}
