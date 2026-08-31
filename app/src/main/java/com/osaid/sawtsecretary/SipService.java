package com.osaid.sawtsecretary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.osaid.sawtsecretary.sip.MuLaw;
import com.osaid.sawtsecretary.sip.RtpSession;
import com.osaid.sawtsecretary.sip.SipClient;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * الجسر الاحترافي.
 *
 *   المتصل ⇄ شبكة الهاتف ⇄ مزوّد SIP ⇄ [هذا التطبيق] ⇄ Gemini Live
 *
 * الصوت رقمي بالكامل من طرف إلى طرف. لا ميكروفون، لا سمّاعة،
 * لا التقاط من الغرفة — مستحيل يتسرّب صوتك أو صوت من حولك.
 *
 * الترددات: الهاتف 8 كيلوهرتز · دخل Gemini 16 · خرج Gemini 24.
 * التحويل يتم هنا في الاتجاهين.
 */
public class SipService extends Service {

    private static final String TAG = "SipService";
    private static final String CH = "sawt_sip";
    private static final int ID = 51;

    public static final String ACTION_START = "com.osaid.sawtsecretary.SIP_START";
    public static final String ACTION_STOP  = "com.osaid.sawtsecretary.SIP_STOP";

    /** حالة عامة تقرأها الواجهة. */
    public static volatile boolean running = false;
    public static volatile String status = "متوقف";
    public static volatile String lastLine = "";

    private Settings st;
    private SipClient sip;
    private GeminiLive live;
    private RtpSession rtp;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    private final StringBuilder transcript = new StringBuilder();
    private JSONObject caller;
    private String urgentWhy, wrapSummary, fromNumber;
    private long startedAt;

    /** بقايا عيّنات لم تكتمل لإطار — نحتفظ بها للدفعة التالية. */
    private short[] inRemainder = new short[0];

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        st = new Settings(this);
        channel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(a)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (running) return START_STICKY;

        startForeground(ID, note("جاري التسجيل…"));
        connect();
        return START_STICKY;   // نظل شغّالين حتى لو قتل النظام الخدمة
    }

    /* ─────────────────────── التسجيل في المزوّد ─────────────────────── */

    private void connect() {
        if (!st.sipReady()) { status = "بيانات SIP ناقصة"; stopSelf(); return; }
        if (!st.ready())    { status = "مفتاح Gemini ناقص"; stopSelf(); return; }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sawt:sip");
        wake.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sawt:sip");
        wifiLock.acquire();

        running = true;
        sip = new SipClient(st.sipServer(), st.sipPort(), st.sipUser(), st.sipPass(),
                st.secretaryName(), listener);
        try {
            sip.start();
        } catch (Exception e) {
            status = "فشل الاتصال: " + e.getMessage();
            update(status);
            Log.e(TAG, status);
        }
    }

    private final SipClient.Listener listener = new SipClient.Listener() {

        @Override public void onRegistered(boolean ok, String detail) {
            status = ok ? "جاهز — ينتظر المكالمات" : "التسجيل فشل: " + detail;
            update(status);
        }

        @Override public void onIncoming(String from, RtpSession session) {
            fromNumber = from;
            rtp = session;
            startedAt = System.currentTimeMillis();
            transcript.setLength(0);
            caller = null; urgentWhy = null; wrapSummary = null;
            inRemainder = new short[0];

            update("مكالمة من " + from);
            startGemini();

            // صوت المتصل: 8 كيلوهرتز → 16 لـGemini
            rtp.setSink(pcm8k -> {
                if (live == null) return;
                short[] pcm16k = MuLaw.resample(pcm8k, 8000, 16000);
                live.sendAudio(toBytes(pcm16k), pcm16k.length * 2);
            });
            rtp.start();
        }

        @Override public void onCallEnded(String reason) {
            finishCall(reason);
        }

        @Override public void onLog(String line) {
            lastLine = line;
            Log.i(TAG, line);
        }
    };

    /* ─────────────────────── جلسة Gemini ─────────────────────── */

    private void startGemini() {
        live = new GeminiLive(st.geminiKey(), Prompt.secretary(st), st.voice(),
                new GeminiLive.Listener() {

            @Override public void onReady() { live.greet(); }

            /** صوت السكرتير: 24 كيلوهرتز → 8 للهاتف. */
            @Override public void onAudio(byte[] pcm24k) {
                if (rtp == null) return;
                short[] s24 = toShorts(pcm24k);
                short[] s8 = MuLaw.resample(s24, 24000, 8000);
                rtp.queue(s8);
            }

            @Override public void onHeard(String t) { transcript.append("المتصل: ").append(t).append('\n'); }
            @Override public void onSaid(String t)  { transcript.append("السكرتير: ").append(t).append('\n'); lastLine = t; }

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
                        new android.os.Handler(getMainLooper()).postDelayed(() -> {
                            if (sip != null) sip.hangUp();
                        }, 7000);
                        break;
                }
            }

            @Override public void onTurnEnd() { }

            /** المتصل قاطع — نرمي ما لم يُرسل عشان يسكت السكرتير فوراً. */
            @Override public void onInterrupted() { if (rtp != null) rtp.flush(); }

            @Override public void onClosed(String reason) { }

            @Override public void onError(String message) {
                Log.w(TAG, "Gemini: " + message);
                update("خطأ: " + message);
            }
        });
        live.connect();
    }

    /* ─────────────────────── نهاية المكالمة ─────────────────────── */

    private void finishCall(String why) {
        if (live != null) { live.close(); live = null; }
        rtp = null;

        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        if (sec > 2) saveAndNotify(sec);

        status = "جاهز — ينتظر المكالمات";
        update(status);
    }

    private void saveAndNotify(long sec) {
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
            rec.put("name", name).put("reason", reason).put("category", cat).put("relation", rel)
               .put("callback", back).put("bestTime", when).put("urgent", urgent)
               .put("urgentWhy", urgentWhy == null ? "" : urgentWhy)
               .put("number", fromNumber == null ? "" : fromNumber)
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
        if (fromNumber != null && !fromNumber.isEmpty())
            m.append("<b>من الرقم:</b> <code>").append(Telegram.esc(fromNumber)).append("</code>\n");
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
        popup((urgent ? "🔴 " : "") + name, reason);
    }

    private void alertUrgent() {
        final String html = "<b>🔴 تنبيه فوري — مكالمة جارية الآن</b>\n\n"
                + Telegram.esc(urgentWhy) + "\n\n<b>المتصل:</b> "
                + Telegram.esc(caller == null ? "ما عرّف بنفسه بعد" : caller.optString("name", "?"));
        new Thread(() -> Telegram.send(st.tgToken(), st.tgChat(), html)).start();
        popup("🔴 مكالمة مستعجلة الآن", urgentWhy);
    }

    /* ─────────────────────── تحويل العيّنات ─────────────────────── */

    private static byte[] toBytes(short[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }

    private static short[] toShorts(byte[] pcm) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = b.getShort();
        return out;
    }

    /* ─────────────────────── الإيقاف والإشعارات ─────────────────────── */

    public static void stop(Context c) {
        c.startService(new Intent(c, SipService.class).setAction(ACTION_STOP));
    }

    private void shutdown() {
        running = false;
        status = "متوقف";
        if (live != null) { live.close(); live = null; }
        if (sip != null)  { sip.stop();  sip = null;  }
        if (wake != null && wake.isHeld()) wake.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        running = false;
    }

    private void channel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel c = new NotificationChannel(CH, "خط السكرتير",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
    }

    private Notification note(String text) {
        Intent stop = new Intent(this, SipService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 9, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent oi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("خط السكرتير")
                .setContentText(text)
                .setContentIntent(oi)
                .setOngoing(true)
                .addAction(0, "أوقف", pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void update(String text) {
        status = text;
        getSystemService(NotificationManager.class).notify(ID, note(text));
    }

    private void popup(String title, String body) {
        Intent open = new Intent(this, LogActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        getSystemService(NotificationManager.class).notify((int) System.currentTimeMillis(), n);
    }
}
