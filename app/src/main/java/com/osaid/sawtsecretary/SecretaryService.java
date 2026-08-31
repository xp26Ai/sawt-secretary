package com.osaid.sawtsecretary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

/**
 * الخدمة التي تدير المكالمة: ترد، تفتح مكبّر الصوت، توصل الصوت
 * بـGemini، تجمع المعلومات، ثم ترسل الملخّص لتيليجرام.
 */
public class SecretaryService extends Service {

    private static final String TAG = "SecretaryService";
    private static final String CH_LIVE = "sawt_live";
    private static final String CH_INFO = "sawt_info";
    private static final int ID_LIVE = 41;

    public static final String ACTION_ANSWER = "com.osaid.sawtsecretary.ANSWER";
    public static final String ACTION_STOP   = "com.osaid.sawtsecretary.STOP";
    public static final String ACTION_TEST   = "com.osaid.sawtsecretary.TEST";
    public static final String EXTRA_NUMBER  = "number";

    /** حالة عامة عشان الواجهة تعرف وش يصير. */
    public static volatile boolean active = false;
    public static volatile String  lastLine = "";

    private Settings st;
    private AudioLoop audio;
    private GeminiLive live;
    private PowerManager.WakeLock wake;

    private final StringBuilder transcript = new StringBuilder();
    private JSONObject caller;
    private String urgentWhy, wrapSummary, number;
    private long startedAt;
    private boolean testMode = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        st = new Settings(this);
        channels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            hangUp();
            finish("أنهيتها بنفسك");
            return START_NOT_STICKY;
        }

        testMode = ACTION_TEST.equals(action);
        number = intent == null ? null : intent.getStringExtra(EXTRA_NUMBER);

        startForeground(ID_LIVE, liveNote(testMode ? "اختبار الصوت" : "السكرتير يرد…"));
        begin();
        return START_NOT_STICKY;
    }

    /* ─────────────────────── بدء المكالمة ─────────────────────── */

    private void begin() {
        if (active) return;
        active = true;
        startedAt = System.currentTimeMillis();
        transcript.setLength(0);
        caller = null; urgentWhy = null; wrapSummary = null;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sawt:call");
        wake.acquire(16 * 60 * 1000L);

        if (!testMode) answerCall();

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioLoop.speakerOn(am, true);

        audio = new AudioLoop((buf, len) -> {
            if (live != null) live.sendAudio(buf, len);
        });

        if (!audio.start()) {
            note("تعذّر فتح الميكروفون", "تأكد من إذن التسجيل");
            finish("فشل الصوت");
            return;
        }

        String prompt = testMode ? Prompt.selfTest(st) : Prompt.secretary(st);
        live = new GeminiLive(st.geminiKey(), prompt, st.voice(), listener);
        live.connect();

        // Gemini يقطع الجلسة الصوتية عند 15 دقيقة — نقفل قبلها بهدوء
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (active) { hangUp(); finish("طالت المكالمة"); }
        }, 14 * 60 * 1000L);
    }

    /** يرد على المكالمة الرنّانة. */
    private void answerCall() {
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) tm.acceptRingingCall();
        } catch (SecurityException e) {
            Log.w(TAG, "ما فيه إذن الرد: " + e.getMessage());
            note("ما قدرت أرد", "امنح التطبيق إذن «الرد على المكالمات»");
        } catch (Exception e) {
            Log.w(TAG, "فشل الرد: " + e.getMessage());
        }
    }

    /** يقفل المكالمة فعلياً بعد ما يخلّص السكرتير. */
    @SuppressWarnings("deprecation")
    private void hangUp() {
        if (testMode) return;
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.endCall();
        } catch (SecurityException e) {
            Log.w(TAG, "ما فيه إذن الإغلاق: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "فشل الإغلاق: " + e.getMessage());
        }
    }

    /* ─────────────────────── أحداث Gemini ─────────────────────── */

    private final GeminiLive.Listener listener = new GeminiLive.Listener() {

        @Override public void onReady() {
            live.greet();
            update("السكرتير يتكلم…");
        }

        @Override public void onAudio(byte[] pcm) {
            if (audio != null) audio.play(pcm);
        }

        @Override public void onHeard(String t) {
            transcript.append("المتصل: ").append(t).append('\n');
            lastLine = t;
        }

        @Override public void onSaid(String t) {
            transcript.append("السكرتير: ").append(t).append('\n');
            lastLine = t;
        }

        @Override public void onTool(String name, JSONObject args) {
            switch (name) {
                case Tools.RECORD_CALLER:
                    caller = args;
                    update("عرف: " + args.optString("name", "?"));
                    break;
                case Tools.MARK_URGENT:
                    urgentWhy = args.optString("why");
                    alertUrgent();
                    break;
                case Tools.END_CALL:
                    wrapSummary = args.optString("summary");
                    // نمهل السكرتير يقول جملة الوداع قبل ما نقفل الخط
                    new android.os.Handler(getMainLooper()).postDelayed(() -> {
                        hangUp();
                        finish("خلّص السكرتير");
                    }, 7000);
                    break;
            }
        }

        @Override public void onTurnEnd() {
            if (audio != null) audio.endOfSpeech();
        }

        @Override public void onInterrupted() {
            if (audio != null) audio.flush();
        }

        @Override public void onClosed(String reason) { finish("أُغلقت الجلسة"); }

        @Override public void onError(String message) {
            Log.w(TAG, message);
            note("خطأ في المكالمة", message);
            finish("خطأ");
        }
    };

    /* ─────────────────────── النهاية والملخّص ─────────────────────── */

    /** تُستدعى من CallReceiver لما تنتهي المكالمة من الشبكة. */
    public static void stopNow(Context c) {
        if (!active) return;
        Intent i = new Intent(c, SecretaryService.class).setAction(ACTION_STOP);
        c.startService(i);
    }

    private void finish(String why) {
        if (!active) { stopSelf(); return; }
        active = false;

        if (live != null)  { live.close();  live = null; }
        if (audio != null) { audio.stop();  audio = null; }

        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setSpeakerphoneOn(false);
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) { }

        if (wake != null && wake.isHeld()) { wake.release(); wake = null; }

        if (!testMode) saveAndNotify();
        else note("انتهى الاختبار", "لو سمعت صوت السكرتير من الجوال الثاني، فالتطبيق يشتغل");

        stopForeground(true);
        stopSelf();
    }

    private void saveAndNotify() {
        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        String name   = caller == null ? "غير معروف" : caller.optString("name", "غير معروف");
        String reason = caller == null ? (wrapSummary == null ? "المتصل أغلق قبل ما يوضّح" : wrapSummary)
                                       : caller.optString("reason", "");
        String cat    = caller == null ? "أخرى" : caller.optString("category", "أخرى");
        String back   = caller == null ? "" : caller.optString("callbackNumber", "");
        String when   = caller == null ? "" : caller.optString("bestTime", "");
        String rel    = caller == null ? "" : caller.optString("relation", "");
        boolean urgent = urgentWhy != null;

        JSONObject rec = new JSONObject();
        try {
            rec.put("name", name).put("reason", reason).put("category", cat)
               .put("relation", rel).put("callback", back).put("bestTime", when)
               .put("urgent", urgent).put("urgentWhy", urgentWhy == null ? "" : urgentWhy)
               .put("number", number == null ? "" : number)
               .put("seconds", sec).put("transcript", transcript.toString());
        } catch (Exception ignored) { }

        new CallStore(this).add(rec);

        StringBuilder m = new StringBuilder();
        m.append(urgent ? "<b>🔴 مكالمة مستعجلة</b>\n\n"
                        : "تسويق".equals(cat) ? "<b>⚪ مكالمة تسويق</b>\n\n"
                        : "<b>🔵 مكالمة فاتتك</b>\n\n");
        m.append("<b>المتصل:</b> ").append(Telegram.esc(name));
        if (!rel.isEmpty()) m.append(" — ").append(Telegram.esc(rel));
        m.append('\n');
        if (number != null && !number.isEmpty())
            m.append("<b>من الرقم:</b> <code>").append(Telegram.esc(number)).append("</code>\n");
        m.append("<b>الموضوع:</b> ").append(Telegram.esc(reason)).append('\n');
        m.append("<b>التصنيف:</b> ").append(Telegram.esc(cat)).append('\n');
        if (urgent) m.append("<b>سبب الاستعجال:</b> ").append(Telegram.esc(urgentWhy)).append('\n');
        if (!back.isEmpty())
            m.append("<b>رقم الرد:</b> <code>").append(Telegram.esc(back)).append("</code>\n");
        if (!when.isEmpty()) m.append("<b>أفضل وقت:</b> ").append(Telegram.esc(when)).append('\n');
        m.append("<b>المدة:</b> ").append(sec).append(" ثانية\n");
        if (transcript.length() > 0) {
            String t = transcript.length() > 1500 ? transcript.substring(0, 1500) + "\n…"
                                                  : transcript.toString();
            m.append("\n<b>نص المكالمة:</b>\n<pre>").append(Telegram.esc(t)).append("</pre>");
        }

        final String html = m.toString();
        new Thread(() -> Telegram.send(st.tgToken(), st.tgChat(), html)).start();

        note((urgent ? "🔴 " : "") + name, reason);
    }

    private void alertUrgent() {
        final String html = "<b>🔴 تنبيه فوري — مكالمة جارية الآن</b>\n\n"
                + Telegram.esc(urgentWhy) + "\n\n<b>المتصل:</b> "
                + Telegram.esc(caller == null ? "ما عرّف بنفسه بعد" : caller.optString("name", "?"));
        new Thread(() -> Telegram.send(st.tgToken(), st.tgChat(), html)).start();
        note("🔴 مكالمة مستعجلة الآن", urgentWhy);
    }

    /* ─────────────────────── إشعارات ─────────────────────── */

    private void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel live = new NotificationChannel(
                    CH_LIVE, "مكالمة جارية", NotificationManager.IMPORTANCE_LOW);
            NotificationChannel info = new NotificationChannel(
                    CH_INFO, "ملخّصات المكالمات", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(live);
            nm.createNotificationChannel(info);
        }
    }

    private Notification liveNote(String text) {
        Intent stop = new Intent(this, SecretaryService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CH_LIVE)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("السكرتير على الخط")
                .setContentText(text)
                .setOngoing(true)
                .addAction(0, "أنهِ", pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void update(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(ID_LIVE, liveNote(text));
    }

    private void note(String title, String body) {
        Intent open = new Intent(this, LogActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 4, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CH_INFO)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify((int) System.currentTimeMillis(), n);
    }
}
