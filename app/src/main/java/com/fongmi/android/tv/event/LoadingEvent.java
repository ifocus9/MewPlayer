package com.fongmi.android.tv.event;

import androidx.annotation.StringRes;

import com.fongmi.android.tv.App;

import org.greenrobot.eventbus.EventBus;

public record LoadingEvent(String text) {

    public static void show(String text) {
        EventBus.getDefault().post(new LoadingEvent(text));
    }

    public static void show(@StringRes int resId) {
        show(App.get().getString(resId));
    }

    public static void show(@StringRes int resId, Object... formatArgs) {
        show(App.get().getString(resId, formatArgs));
    }
}
