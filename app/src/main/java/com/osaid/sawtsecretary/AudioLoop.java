package com.osaid.sawtsecretary;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * حلقة الصوت أثناء المكالمة.
 *
 * التقاط: الميكروفون يسمع صوت المتصل الخارج من سمّاعة الجوال (وضع مكبّر الصوت).
 * تشغيل: صوت السكرتير يخرج من المكبّر، والميكروفون الداخلي للجوال ينقله للمتصل.
 *
 * نعمل بنصف ازدواج (half-duplex): نوقف إرسال الميكروفون بينما السكرتير
 * يتكلم، وإلا سمع نفسه ودخل في حلقة لا تنتهي.
 */
public class AudioLoop {

    private static final String TAG = "AudioLoop";

    public static final int MIC_RATE = 16000;   // ما يقبله Gemini
    public static final int OUT_RATE = 24000;   // ما يخرجه Gemini

    /** كم نستمر في تجاهل الميكروفون بعد ما يسكت السكرتير (صدى متأخر). */
    private static final long TAIL_MS = 350;

    public interface OnMic { void chunk(byte[] pcm, int len); }

    private AudioRecord rec;
    private AudioTrack track;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread micThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long speakingUntil = 0;

    private final OnMic onMic;

    public AudioLoop(OnMic onMic) { this.onMic = onMic; }

    /** true بينما السكرتير يتكلم (أو بعده مباشرة). */
    public boolean isSpeaking() { return System.currentTimeMillis() < speakingUntil; }

    @SuppressLint("MissingPermission")
    public boolean start() {
        int minIn = AudioRecord.getMinBufferSize(
                MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minIn <= 0) minIn = MIC_RATE;
        int bufIn = Math.max(minIn * 2, MIC_RATE / 5 * 2);   // ≈ 200 ملّي ثانية

        try {
            rec = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufIn);

            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                // بعض الأجهزة ترفض VOICE_COMMUNICATION أثناء المكالمة
                rec.release();
                rec = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufIn);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "تعذّر فتح الميكروفون");
                return false;
            }

            int session = rec.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }

            int minOut = AudioTrack.getMinBufferSize(
                    OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minOut <= 0) minOut = OUT_RATE;

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            // مسار صوت المكالمة — أعلى صوتاً وأقرب لأن يسمعه الطرف الثاني
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(minOut * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            track.play();
            rec.startRecording();
            running.set(true);

            final int chunk = MIC_RATE / 10 * 2;   // 100 ملّي ثانية
            micThread = new Thread(() -> {
                byte[] buf = new byte[chunk];
                while (running.get()) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0 && !isSpeaking()) onMic.chunk(buf, n);
                }
            }, "sawt-mic");
            micThread.start();

            return true;

        } catch (Exception e) {
            Log.e(TAG, "فشل تشغيل الصوت: " + e.getMessage());
            stop();
            return false;
        }
    }

    /** يشغّل مقطعاً من صوت السكرتير. */
    public void play(byte[] pcm24k) {
        if (track == null || !running.get()) return;
        try {
            // مدة المقطع = عدد العيّنات ÷ التردد
            long ms = (long) (pcm24k.length / 2.0 / OUT_RATE * 1000);
            speakingUntil = Math.max(speakingUntil, System.currentTimeMillis()) + ms;
            track.write(pcm24k, 0, pcm24k.length);
        } catch (Exception e) {
            Log.w(TAG, "فشل التشغيل: " + e.getMessage());
        }
    }

    /** المتصل قاطع السكرتير — نفرّغ ما تبقّى. */
    public void flush() {
        if (track == null) return;
        try {
            track.pause();
            track.flush();
            track.play();
        } catch (Exception ignored) { }
        speakingUntil = System.currentTimeMillis();
    }

    /** يُستدعى بعد آخر مقطع صوتي في الدور. */
    public void endOfSpeech() {
        speakingUntil = Math.max(speakingUntil, System.currentTimeMillis()) + TAIL_MS;
    }

    public void stop() {
        running.set(false);
        if (micThread != null) {
            try { micThread.join(500); } catch (InterruptedException ignored) { }
            micThread = null;
        }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
        if (aec != null) { aec.release(); aec = null; }
        if (ns != null)  { ns.release();  ns = null;  }
    }

    /** يضبط الجوال على مكبّر الصوت — شرط أساسي لكي يسمعك المتصل. */
    public static void speakerOn(AudioManager am, boolean on) {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(on);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0);
        } catch (Exception e) {
            Log.w(TAG, "تعذّر ضبط مكبّر الصوت: " + e.getMessage());
        }
    }
}
