package com.osaid.sawtsecretary;

import android.util.Log;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** إرسال ملخّص المكالمة لصاحب الجوال في تيليجرام. */
public class Telegram {

    private static final OkHttpClient HTTP = new OkHttpClient();

    /** يرجع true لو وصلت. */
    public static boolean send(String token, String chatId, String html) {
        if (token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) return false;
        try {
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendMessage")
                    .post(new FormBody.Builder()
                            .add("chat_id", chatId)
                            .add("text", html)
                            .add("parse_mode", "HTML")
                            .add("disable_web_page_preview", "true")
                            .build())
                    .build();
            try (Response res = HTTP.newCall(req).execute()) {
                if (res.isSuccessful()) return true;
                Log.w("Telegram", "فشل: " + res.code());
                return false;
            }
        } catch (Exception e) {
            Log.w("Telegram", "فشل: " + e.getMessage());
            return false;
        }
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
