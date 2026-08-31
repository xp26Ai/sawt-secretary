package com.osaid.sawtsecretary;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** سجل المكالمات — ملف JSON بسيط داخل التطبيق. */
public class CallStore {

    private static final String FILE = "calls.json";
    private static final int MAX = 200;

    private final File file;

    public CallStore(Context c) {
        file = new File(c.getFilesDir(), FILE);
    }

    public JSONArray all() {
        try {
            if (!file.exists()) return new JSONArray();
            String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public void add(JSONObject call) {
        try {
            JSONArray a = all();
            JSONArray out = new JSONArray();
            call.put("at", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            out.put(call);
            for (int i = 0; i < a.length() && out.length() < MAX; i++) out.put(a.get(i));
            try (FileOutputStream fo = new FileOutputStream(file)) {
                fo.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    public void clear() {
        try { file.delete(); } catch (Exception ignored) { }
    }
}
