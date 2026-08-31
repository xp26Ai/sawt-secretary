package com.osaid.sawtsecretary;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * اتصال حيّ ثنائي الاتجاه مع Gemini Live API عبر WebSocket.
 *
 * ندخل صوتاً خاماً PCM 16 كيلوهرتز، ونستقبل صوتاً PCM 24 كيلوهرتز
 * بالإضافة إلى نص ما قيل من الطرفين.
 */
public class GeminiLive {

    private static final String TAG = "GeminiLive";
    private static final String URL =
            "wss://generativelanguage.googleapis.com/ws/"
          + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";

    public static final String MODEL = "gemini-3.1-flash-live-preview";

    public interface Listener {
        void onReady();
        void onAudio(byte[] pcm24k);
        void onHeard(String text);      // ما قاله المتصل
        void onSaid(String text);       // ما قاله السكرتير
        void onTool(String name, JSONObject args);
        void onTurnEnd();
        void onInterrupted();
        void onClosed(String reason);
        void onError(String message);
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private final String apiKey, systemPrompt, voice;
    private final Listener out;
    private WebSocket ws;
    private volatile boolean open = false;

    public GeminiLive(String apiKey, String systemPrompt, String voice, Listener out) {
        this.apiKey = apiKey;
        this.systemPrompt = systemPrompt;
        this.voice = voice;
        this.out = out;
    }

    public boolean isOpen() { return open; }

    public void connect() {
        Request req = new Request.Builder().url(URL + apiKey).build();
        ws = http.newWebSocket(req, new WebSocketListener() {

            @Override public void onOpen(WebSocket s, Response r) {
                try {
                    s.send(buildSetup().toString());
                } catch (Exception e) {
                    out.onError("فشل الإعداد: " + e.getMessage());
                }
            }

            @Override public void onMessage(WebSocket s, String text) { handle(text); }

            @Override public void onMessage(WebSocket s, ByteString bytes) { handle(bytes.utf8()); }

            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                open = false;
                out.onError("انقطع الاتصال: " + t.getMessage());
            }

            @Override public void onClosed(WebSocket s, int code, String reason) {
                open = false;
                out.onClosed(reason);
            }
        });
    }

    private JSONObject buildSetup() throws Exception {
        JSONObject voiceCfg = new JSONObject()
                .put("voice_config", new JSONObject()
                        .put("prebuilt_voice_config", new JSONObject().put("voice_name", voice)));

        JSONObject gen = new JSONObject()
                .put("response_modalities", new JSONArray().put("AUDIO"))
                .put("speech_config", voiceCfg);

        JSONObject vad = new JSONObject()
                .put("disabled", false)
                .put("start_of_speech_sensitivity", "START_SENSITIVITY_LOW")
                .put("end_of_speech_sensitivity", "END_SENSITIVITY_LOW")
                .put("prefix_padding_ms", 200)
                .put("silence_duration_ms", 800);

        JSONObject setup = new JSONObject()
                .put("model", "models/" + MODEL)
                .put("generation_config", gen)
                .put("system_instruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))))
                .put("tools", new JSONArray().put(new JSONObject()
                        .put("function_declarations", Tools.declarations())))
                .put("input_audio_transcription", new JSONObject())
                .put("output_audio_transcription", new JSONObject())
                .put("realtime_input_config", new JSONObject()
                        .put("automatic_activity_detection", vad));

        return new JSONObject().put("setup", setup);
    }

    private void handle(String raw) {
        try {
            JSONObject m = new JSONObject(raw);

            if (m.has("setupComplete")) {
                open = true;
                out.onReady();
                return;
            }

            if (m.has("toolCall")) {
                JSONArray calls = m.getJSONObject("toolCall").optJSONArray("functionCalls");
                JSONArray responses = new JSONArray();
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        String name = c.optString("name");
                        JSONObject args = c.optJSONObject("args");
                        out.onTool(name, args == null ? new JSONObject() : args);
                        responses.put(new JSONObject()
                                .put("id", c.optString("id"))
                                .put("name", name)
                                .put("response", new JSONObject().put("ok", true)));
                    }
                }
                send(new JSONObject().put("tool_response",
                        new JSONObject().put("function_responses", responses)));
                return;
            }

            JSONObject sc = m.optJSONObject("serverContent");
            if (sc == null) return;

            if (sc.optBoolean("interrupted")) out.onInterrupted();

            JSONObject turn = sc.optJSONObject("modelTurn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (inline != null) {
                        String d = inline.optString("data", "");
                        if (!d.isEmpty()) out.onAudio(Base64.decode(d, Base64.DEFAULT));
                    }
                }
            }

            JSONObject in = sc.optJSONObject("inputTranscription");
            if (in != null && !in.optString("text").isEmpty()) out.onHeard(in.optString("text"));

            JSONObject o = sc.optJSONObject("outputTranscription");
            if (o != null && !o.optString("text").isEmpty()) out.onSaid(o.optString("text"));

            if (sc.optBoolean("turnComplete")) out.onTurnEnd();

        } catch (Exception e) {
            Log.w(TAG, "رسالة غير مفهومة: " + e.getMessage());
        }
    }

    private void send(JSONObject o) {
        if (ws != null) ws.send(o.toString());
    }

    /**
     * يرسل مقطع صوت من الميكروفون.
     *
     * تنبيه: `realtime_input.media_chunks` (الموجود في توثيق قوقل) صار مهجوراً،
     * والخادم يقطع الجلسة برمز 1007 عند استخدامه. الصيغة الصحيحة `realtime_input.audio`.
     */
    public void sendAudio(byte[] pcm16k, int len) {
        if (!open || ws == null) return;
        try {
            String b64 = Base64.encodeToString(pcm16k, 0, len, Base64.NO_WRAP);
            send(new JSONObject().put("realtime_input", new JSONObject()
                    .put("audio", new JSONObject()
                            .put("data", b64)
                            .put("mime_type", "audio/pcm;rate=16000"))));
        } catch (Exception ignored) { }
    }

    /** يرسل نصاً — نستخدمه لبدء الترحيب. */
    public void sendText(String text) {
        if (!open || ws == null) return;
        try {
            send(new JSONObject().put("client_content", new JSONObject()
                    .put("turns", new JSONArray().put(new JSONObject()
                            .put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", text)))))
                    .put("turn_complete", true)));
        } catch (Exception ignored) { }
    }

    public void greet() {
        sendText("[بدأت المكالمة — رحّب بالمتصل وعرّف بنفسك واسأله من معي ووش يبي]");
    }

    public void close() {
        open = false;
        if (ws != null) {
            try { ws.close(1000, "انتهت المكالمة"); } catch (Exception ignored) { }
            ws = null;
        }
    }
}
