package com.fongmi.android.tv.ui.activity;

import android.app.Activity;

public class KeepActivity {

    public static void start(Activity activity) {
        if (activity instanceof HomeActivity home) {
            home.change(3);
        } else {
            HistoryActivity.start(activity);
        }
    }
}
