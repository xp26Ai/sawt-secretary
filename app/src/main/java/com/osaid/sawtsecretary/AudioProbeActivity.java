package com.osaid.sawtsecretary;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * فاحص المسارات الصوتية.
 *
 * ليش موجود؟ لأن السؤال «هل يقدر التطبيق يوهم النظام بوجود سمّاعة
 * فيأخذ مسار الصوت؟» يُقاس ولا يُفترض. هذي الشاشة تجرّب **كل** الطرق
 * المتاحة لتطبيق عادي على جهازك أنت، وتطبع ما قاله النظام حرفياً.
 *
 * وتقيس أيضاً مستوى الميكروفون — عشان تعرف أثناء مكالمة حقيقية
 * هل يرجع صوتاً فعلاً أم صمتاً.
 */
public class AudioProbeActivity extends AppCompatActivity {

    private LinearLayout out;
    private AudioManager am;
    private AudioRecord rec;
    private Thread meterThread;
    private TextView meter;
    private volatile boolean metering = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0E1117"));
        out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        out.setPadding(dp(16), dp(16), dp(16), dp(28));
        sv.addView(out);
        setContentView(sv);
        setTitle("فحص المسارات الصوتية");

        run();
    }

    private void run() {
        out.removeAllViews();

        head("١ · الأجهزة التي يراها النظام");
        note("هذي القائمة تجي من طبقة العتاد (HAL). التطبيق يقرأها فقط، "
           + "ولا يقدر يضيف لها جهازاً وهمياً.");
        listDevices();

        head("٢ · محاولة اختيار جهاز للمكالمة");
        tryCommunicationDevices();

        head("٣ · محاولة تزييف سمّاعة سلكية");
        tryFakeHeadset();

        head("٤ · مقياس الميكروفون");
        note("شغّله أثناء مكالمة حقيقية. لو بقي على صفر، فالنظام يمنع "
           + "التطبيق من سماع المكالمة. لو تحرّك، فهو يسمع.");
        buildMeter();

        Button again = new Button(this);
        again.setText("أعد الفحص");
        again.setOnClickListener(v -> run());
        out.addView(again);
    }

    /* ─────────────────── ١ · الأجهزة ─────────────────── */

    private void listDevices() {
        AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo[] ins  = am.getDevices(AudioManager.GET_DEVICES_INPUTS);

        line("مخارج (" + outs.length + "):", true);
        for (AudioDeviceInfo d : outs) line("   • " + typeName(d.getType()), false);

        line("مداخل (" + ins.length + "):", true);
        for (AudioDeviceInfo d : ins) line("   • " + typeName(d.getType()), false);
    }

    /* ─────────────────── ٢ · اختيار جهاز ─────────────────── */

    private void tryCommunicationDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bad("يحتاج أندرويد 12 فأحدث — جهازك أقدم.");
            return;
        }
        List<AudioDeviceInfo> avail = am.getAvailableCommunicationDevices();
        if (avail.isEmpty()) { bad("النظام ما أعطى أي جهاز متاح."); return; }

        int okCount = 0;
        for (AudioDeviceInfo d : avail) {
            boolean okSet;
            try {
                okSet = am.setCommunicationDevice(d);
            } catch (Exception e) {
                okSet = false;
            }
            if (okSet) okCount++;
            line((okSet ? "   ✓ " : "   ✗ ") + typeName(d.getType()), false);
        }
        try { am.clearCommunicationDevice(); } catch (Exception ignored) { }

        if (okCount > 0) {
            good("النظام يسمح باختيار " + okCount + " من الأجهزة الموجودة فعلاً.");
        }
        note("لاحظ: هذا يختار من الموجود. ما فيه طريقة تضيف جهازاً غير موجود.");
    }

    /* ─────────────────── ٣ · تزييف سمّاعة ─────────────────── */

    @SuppressWarnings("deprecation")
    private void tryFakeHeadset() {
        // المحاولة أ: الدالة القديمة
        boolean before = am.isWiredHeadsetOn();
        String aResult;
        try {
            am.setWiredHeadsetOn(true);
            boolean after = am.isWiredHeadsetOn();
            aResult = (after && !before)
                    ? "نجحت — تغيّرت الحالة!"
                    : "بلا أثر — الحالة بقيت " + (after ? "متصلة" : "غير متصلة");
        } catch (Exception e) {
            aResult = "رُفضت — " + e.getClass().getSimpleName();
        }
        line("أ · setWiredHeadsetOn(true)", true);
        line("   " + aResult, false);

        // المحاولة ب: بثّ إشعار توصيل السمّاعة
        String bResult;
        try {
            Intent i = new Intent(AudioManager.ACTION_HEADSET_PLUG)
                    .putExtra("state", 1)
                    .putExtra("name", "sawt-virtual")
                    .putExtra("microphone", 1);
            sendBroadcast(i);
            bResult = "مرّ بدون استثناء — لكن راجع القائمة أعلاه: هل ظهرت سمّاعة؟";
        } catch (SecurityException e) {
            bResult = "رُفض — النظام يحمي هذا البثّ (SecurityException)";
        } catch (Exception e) {
            bResult = "فشل — " + e.getClass().getSimpleName();
        }
        line("ب · بثّ ACTION_HEADSET_PLUG", true);
        line("   " + bResult, false);

        // المحاولة ج: هل ظهر جهاز جديد بعد المحاولتين؟
        int outsNow = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).length;
        line("ج · عدد المخارج بعد المحاولتين: " + outsNow, true);

        try { am.setWiredHeadsetOn(false); } catch (Exception ignored) { }

        note("الخلاصة: إضافة جهاز صوتي وهمي تحتاج تعديل طبقة العتاد (HAL) "
           + "في نظام مبني خصيصاً، أو صلاحية روت. ما فيها واجهة لتطبيق عادي.");
    }

    /* ─────────────────── ٤ · مقياس الميكروفون ─────────────────── */

    private void buildMeter() {
        meter = text("— اضغط ابدأ —", 20, "#4DA3FF", true);
        meter.setPadding(0, dp(6), 0, dp(6));
        out.addView(meter);

        Button b = new Button(this);
        b.setText("ابدأ القياس");
        b.setOnClickListener(v -> {
            if (metering) { stopMeter(); b.setText("ابدأ القياس"); }
            else if (startMeter()) b.setText("أوقف القياس");
        });
        out.addView(b);
    }

    @SuppressLint("MissingPermission")
    private boolean startMeter() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            meter.setText("ما فيه إذن ميكروفون");
            return false;
        }
        int min = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 16000;

        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                meter.setText("تعذّر فتح الميكروفون");
                return false;
            }
            rec.startRecording();
        } catch (Exception e) {
            meter.setText("فشل: " + e.getMessage());
            return false;
        }

        metering = true;
        final int chunk = 1600;
        meterThread = new Thread(() -> {
            short[] buf = new short[chunk];
            while (metering) {
                int n = rec.read(buf, 0, chunk);
                if (n <= 0) continue;
                double sum = 0;
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    peak = Math.max(peak, Math.abs(buf[i]));
                }
                final int rms = (int) Math.sqrt(sum / n);
                final int pk = peak;
                ui.post(() -> {
                    int bars = Math.min(20, rms / 300);
                    StringBuilder bar = new StringBuilder();
                    for (int i = 0; i < 20; i++) bar.append(i < bars ? '█' : '·');
                    meter.setText(bar + "\nRMS " + rms + "   ذروة " + pk
                            + (pk < 20 ? "\nصمت تام — النظام يمنع السماع" : ""));
                });
            }
        }, "sawt-meter");
        meterThread.start();
        return true;
    }

    private void stopMeter() {
        metering = false;
        if (meterThread != null) {
            try { meterThread.join(400); } catch (InterruptedException ignored) { }
            meterThread = null;
        }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); stopMeter(); }

    /* ─────────────────── أدوات العرض ─────────────────── */

    private static String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "سمّاعة الأذن المدمجة";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:  return "مكبّر الصوت المدمج";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:      return "الميكروفون المدمج";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:    return "سمّاعة سلكية بميكروفون";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "سمّاعة سلكية بلا ميكروفون";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:    return "بلوتوث (مكالمات)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:   return "بلوتوث (موسيقى)";
            case AudioDeviceInfo.TYPE_USB_HEADSET:      return "سمّاعة USB";
            case AudioDeviceInfo.TYPE_USB_DEVICE:       return "جهاز USB صوتي";
            case AudioDeviceInfo.TYPE_TELEPHONY:        return "الهاتف (مسار الشبكة)";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:    return "خلط بعيد (نظام)";
            case AudioDeviceInfo.TYPE_HDMI:             return "HDMI";
            case AudioDeviceInfo.TYPE_FM_TUNER:         return "راديو FM";
            default: return "نوع " + t;
        }
    }

    private void head(String s) {
        TextView t = text(s, 15, "#4DA3FF", true);
        t.setPadding(0, dp(20), 0, dp(8));
        out.addView(t);
    }

    private void line(String s, boolean bold) {
        out.addView(text(s, bold ? 14 : 13, bold ? "#E6EDF5" : "#9AA8BB", bold));
    }

    private void note(String s) {
        TextView t = text(s, 12, "#6B7889", false);
        t.setPadding(0, dp(4), 0, dp(8));
        out.addView(t);
    }

    private void good(String s) { out.addView(text("✓ " + s, 13, "#3FBF7F", false)); }
    private void bad(String s)  { out.addView(text("✗ " + s, 13, "#F26D6D", false)); }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setLineSpacing(0, 1.35f);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
