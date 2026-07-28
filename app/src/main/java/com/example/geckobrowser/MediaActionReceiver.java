package com.example.geckobrowser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Recoit les commandes des boutons de la notification multimedia. */
public final class MediaActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        MediaHub.dispatchExternalAction(intent.getAction());
    }
}
