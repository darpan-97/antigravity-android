package com.antigravity.mobile;

import android.app.Application;
import android.content.Context;
import com.antigravity.mobile.data.TranscriptRepository;

public class AntigravityApp extends Application {
    private static AntigravityApp sInstance;
    private TranscriptRepository mRepository;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mRepository = new TranscriptRepository(this);
    }

    public static AntigravityApp getInstance() {
        return sInstance;
    }

    public TranscriptRepository getRepository() {
        return mRepository;
    }
}
