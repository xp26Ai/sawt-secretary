package com.osaid.sawtsecretary;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** يستقبل ضغطة زر «خلّ السكرتير يرد» من الإشعار. */
public class ActionReceiver extends BroadcastReceiver {

    public static final String ACTION_HANDOFF = "com.osaid.sawtsecretary.HANDOFF";

    @Override public void onReceive(Context ctx, Intent intent) {
        if (!ACTION_HANDOFF.equals(intent.getAction())) return;
        ctx.getSystemService(NotificationManager.class).cancel(40);
        CallReceiver.cancelAuto();
        CallReceiver.answer(ctx, intent.getStringExtra(SecretaryService.EXTRA_NUMBER));
    }
}
