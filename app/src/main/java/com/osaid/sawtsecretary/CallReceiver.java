package com.osaid.sawtsecretary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

import androidx.core.app.NotificationCompat;

/**
 * يراقب حالة الجوال:
 *  - يرن     → يعرض إشعاراً فيه زر «خلّ السكرتير يرد» (وقد يرد تلقائياً).
 *  - يرد أنت → يلغي كل شيء، المكالمة لك.
 *  - انتهت   → يوقف الخدمة إن كانت شغّالة.
 */
public class CallReceiver extends BroadcastReceiver {

    private static final String CH_RING = "sawt_ring";
    private static final int ID_RING = 40;

    private static String lastState = TelephonyManager.EXTRA_STATE_IDLE;
    private static Runnable pendingAuto;
    private static final Handler H = new Handler(Looper.getMainLooper());

    @Override public void onReceive(Context ctx, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (state == null) return;

        Settings st = new Settings(ctx);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            if (!st.enabled() || !st.ready()) return;
            showRingNote(ctx, number);

            int auto = st.autoAnswerSec();
            if (auto > 0) {
                cancelAuto();
                pendingAuto = () -> {
                    // نتأكد أنه ما زال يرن ولا أحد رد
                    if (TelephonyManager.EXTRA_STATE_RINGING.equals(lastState)) {
                        answer(ctx, number);
                    }
                };
                H.postDelayed(pendingAuto, auto * 1000L);
            }

        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            // رُدّ على المكالمة — إما أنت أو السكرتير
            cancelAuto();
            clearRingNote(ctx);

        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            cancelAuto();
            clearRingNote(ctx);
            SecretaryService.stopNow(ctx);
        }

        lastState = state;
    }

    static void cancelAuto() {
        if (pendingAuto != null) { H.removeCallbacks(pendingAuto); pendingAuto = null; }
    }

    static void answer(Context ctx, String number) {
        Intent i = new Intent(ctx, SecretaryService.class)
                .setAction(SecretaryService.ACTION_ANSWER)
                .putExtra(SecretaryService.EXTRA_NUMBER, number);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /* ── إشعار الرنين مع زر التسليم ── */

    private void showRingNote(Context ctx, String number) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_RING, "مكالمة واردة", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);   // ما نبي صوت فوق صوت الرنين
            nm.createNotificationChannel(ch);
        }

        Intent hand = new Intent(ctx, ActionReceiver.class)
                .setAction(ActionReceiver.ACTION_HANDOFF)
                .putExtra(SecretaryService.EXTRA_NUMBER, number);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, hand,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(ctx, CH_RING)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("مكالمة واردة"
                        + (number == null || number.isEmpty() ? "" : " من " + number))
                .setContentText("مشغول؟ خلّ السكرتير يرد")
                .addAction(0, "خلّ السكرتير يرد", pi)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, false)
                .setOngoing(true)
                .build();
        nm.notify(ID_RING, n);
    }

    private void clearRingNote(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(ID_RING);
    }
}
