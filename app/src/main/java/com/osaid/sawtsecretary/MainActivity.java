package com.osaid.sawtsecretary;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Settings st;

    private Switch enabled;
    private TextView statusLine;
    private Button permBtn;
    private EditText owner, secName, status, freeAfter, geminiKey, tgToken, tgChat;
    private EditText sipServer, sipUser, sipPass;
    private TextView sipStatus;
    private Button sipBtn;
    private final android.os.Handler tick = new android.os.Handler(android.os.Looper.getMainLooper());
    private RadioGroup mode;

    private static final String[] NEEDED = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
    };

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        st = new Settings(this);

        enabled    = findViewById(R.id.enabled);
        statusLine = findViewById(R.id.statusLine);
        permBtn    = findViewById(R.id.permBtn);
        owner      = findViewById(R.id.owner);
        secName    = findViewById(R.id.secName);
        status     = findViewById(R.id.status);
        freeAfter  = findViewById(R.id.freeAfter);
        geminiKey  = findViewById(R.id.geminiKey);
        tgToken    = findViewById(R.id.tgToken);
        tgChat     = findViewById(R.id.tgChat);
        mode       = findViewById(R.id.mode);
        sipServer  = findViewById(R.id.sipServer);
        sipUser    = findViewById(R.id.sipUser);
        sipPass    = findViewById(R.id.sipPass);
        sipStatus  = findViewById(R.id.sipStatus);
        sipBtn     = findViewById(R.id.sipBtn);

        load();

        enabled.setOnCheckedChangeListener((b, on) -> {
            if (on && !st.ready()) {
                enabled.setChecked(false);
                toast("عبّي مفتاح Gemini أولاً");
                return;
            }
            if (on && missing().length > 0) {
                enabled.setChecked(false);
                askPermissions();
                return;
            }
            st.enabled(on);
            refresh();
        });

        mode.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.auto15) st.autoAnswerSec(15);
            else if (id == R.id.auto25) st.autoAnswerSec(25);
            else st.autoAnswerSec(0);
        });

        permBtn.setOnClickListener(v -> askPermissions());

        findViewById(R.id.saveBtn).setOnClickListener(v -> { save(); toast("انحفظ"); refresh(); });

        findViewById(R.id.tgTestBtn).setOnClickListener(v -> {
            save();
            if (!st.telegramReady()) { toast("عبّي التوكن ورقم المحادثة"); return; }
            new Thread(() -> {
                boolean ok = Telegram.send(st.tgToken(), st.tgChat(),
                        "<b>✅ تجربة</b>\nتطبيق «صوت — سكرتيري» مربوط بنجاح.");
                runOnUiThread(() -> toast(ok ? "وصلت — شوف تيليجرام" : "ما وصلت، راجع التوكن والرقم"));
            }).start();
        });

        findViewById(R.id.testBtn).setOnClickListener(v -> {
            save();
            if (!st.ready()) { toast("عبّي مفتاح Gemini أولاً"); return; }
            if (missing().length > 0) { askPermissions(); return; }
            Intent i = new Intent(this, SecretaryService.class).setAction(SecretaryService.ACTION_TEST);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            toast("بدأ الاختبار — اسمع من الجوال الثاني");
        });

        sipBtn.setOnClickListener(v -> {
            save();
            if (SipService.running) {
                SipService.stop(this);
                toast("أوقفت الخط");
                return;
            }
            if (!st.sipReady()) { toast("عبّي بيانات SIP"); return; }
            if (!st.ready())    { toast("عبّي مفتاح Gemini"); return; }
            Intent i = new Intent(this, SipService.class).setAction(SipService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            toast("جاري التسجيل…");
        });

        findViewById(R.id.probeBtn).setOnClickListener(v ->
                startActivity(new Intent(this, AudioProbeActivity.class)));

        findViewById(R.id.logBtn).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            sipStatus.setText(SipService.status);
            sipBtn.setText(SipService.running ? "أوقف الخط" : "شغّل الخط");
            tick.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        refresh();
        tick.post(poll);
    }
    @Override protected void onPause() {
        super.onPause();
        save();
        tick.removeCallbacks(poll);
    }

    /* ─────────────────── الحالة ─────────────────── */

    private void refresh() {
        String[] miss = missing();
        boolean ready = st.ready();

        enabled.setChecked(st.enabled() && ready && miss.length == 0);
        permBtn.setVisibility(miss.length > 0 ? View.VISIBLE : View.GONE);

        StringBuilder s = new StringBuilder();
        if (!ready) s.append("ينقص مفتاح Gemini.");
        else if (miss.length > 0) s.append("تنقص ").append(miss.length).append(" أذونات.");
        else if (st.enabled()) {
            int a = st.autoAnswerSec();
            s.append(a > 0 ? "شغّال — يرد تلقائياً بعد " + a + " ثانية"
                           : "شغّال — ينتظر ضغطتك عند كل مكالمة");
        } else s.append("موقوف.");

        if (!st.telegramReady()) s.append("\nتيليجرام غير مربوط — الملخّص يظهر في السجل فقط.");

        statusLine.setText(s.toString());
    }

    private void load() {
        owner.setText(st.ownerName());
        secName.setText(st.secretaryName());
        status.setText(st.status());
        freeAfter.setText(st.freeAfter());
        geminiKey.setText(st.geminiKey());
        tgToken.setText(st.tgToken());
        tgChat.setText(st.tgChat());
        sipServer.setText(st.sipServer());
        sipUser.setText(st.sipUser());
        sipPass.setText(st.sipPass());

        int a = st.autoAnswerSec();
        ((RadioButton) findViewById(a == 15 ? R.id.auto15 : a == 25 ? R.id.auto25 : R.id.manual))
                .setChecked(true);
        refresh();
    }

    private void save() {
        st.ownerName(txt(owner));
        st.secretaryName(txt(secName));
        st.status(txt(status));
        st.freeAfter(txt(freeAfter));
        st.geminiKey(txt(geminiKey));
        st.tgToken(txt(tgToken));
        st.tgChat(txt(tgChat));
        st.sipServer(txt(sipServer));
        st.sipUser(txt(sipUser));
        st.sipPass(txt(sipPass));
    }

    private String txt(EditText e) { return e.getText().toString().trim(); }

    /* ─────────────────── الأذونات ─────────────────── */

    private String[] missing() {
        List<String> out = new ArrayList<>();
        for (String p : NEEDED) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                out.add(p);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            out.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return out.toArray(new String[0]);
    }

    private void askPermissions() {
        String[] miss = missing();
        if (miss.length > 0) ActivityCompat.requestPermissions(this, miss, 7);
    }

    @Override public void onRequestPermissionsResult(
            int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
        if (missing().length > 0) {
            toast("بعض الأذونات مرفوضة — التطبيق ما راح يرد بدونها");
        }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
