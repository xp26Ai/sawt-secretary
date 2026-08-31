import com.osaid.sawtsecretary.sip.MuLaw;
import com.osaid.sawtsecretary.sip.RtpSession;
import com.osaid.sawtsecretary.sip.SipClient;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * مشغّل اختبار لمكدّس SIP — يشغّل نفس كود التطبيق على الحاسب
 * مقابل خادم SIP محاكى، عشان نتأكد أنه يشتغل قبل ما يروح للجوال.
 */
public class Main {

    static final AtomicInteger rxFrames = new AtomicInteger();
    static final AtomicLong rxSamples = new AtomicLong();
    static volatile boolean registered = false;
    static volatile boolean gotCall = false;
    static volatile String callFrom = null;
    static volatile boolean ended = false;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5070;

        System.out.println("── اختبار مكدّس SIP ──");

        SipClient sip = new SipClient(host, port, "testuser", "s3cret", "مساعد",
                new SipClient.Listener() {
            @Override public void onRegistered(boolean ok, String detail) {
                System.out.println("  تسجيل: " + (ok ? "نجح" : "فشل") + " — " + detail);
                registered = ok;
            }

            @Override public void onIncoming(String from, RtpSession rtp) {
                System.out.println("  مكالمة واردة من: " + from);
                gotCall = true;
                callFrom = from;

                // نردّ نفس الصوت للطرف الآخر — يثبت أن المسارين يعملان
                rtp.setSink(pcm8k -> {
                    rxFrames.incrementAndGet();
                    rxSamples.addAndGet(pcm8k.length);
                    // نضاعف السعة قليلاً حتى يميّز المحاكي أنه صوتنا لا صداه
                    short[] out = new short[pcm8k.length];
                    for (int i = 0; i < pcm8k.length; i++) {
                        int v = pcm8k[i] / 2;
                        out[i] = (short) v;
                    }
                    rtp.queue(out);
                });
                rtp.start();
            }

            @Override public void onCallEnded(String reason) {
                System.out.println("  انتهت المكالمة: " + reason);
                ended = true;
            }

            @Override public void onLog(String line) {
                System.out.println("  · " + line);
            }
        });

        sip.start();

        // ننتظر النتائج
        long deadline = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < deadline && !ended) Thread.sleep(200);
        Thread.sleep(500);
        sip.stop();

        System.out.println("\n── النتيجة ──");
        System.out.println("  تسجيل ناجح     : " + (registered ? "✓" : "✗"));
        System.out.println("  مكالمة واردة   : " + (gotCall ? "✓ من " + callFrom : "✗"));
        System.out.println("  إطارات مستلمة  : " + rxFrames.get()
                + "  (" + String.format("%.1f", rxSamples.get() / 8000.0) + " ثانية)");

        boolean pass = registered && gotCall && rxFrames.get() > 50;
        System.out.println(pass ? "\n  ✓ المكدّس يعمل\n" : "\n  ✗ فشل\n");
        System.exit(pass ? 0 : 1);
    }

    /** فحص سريع لصحة ترميز μ-law — بلا شبكة. */
    static void codecCheck() {
        short[] tone = new short[800];
        for (int i = 0; i < tone.length; i++) tone[i] = (short) (10000 * Math.sin(i * 0.2));
        short[] back = MuLaw.decode(MuLaw.encode(tone, 0, tone.length), 0, tone.length);
        long err = 0;
        for (int i = 0; i < tone.length; i++) err += Math.abs(back[i] - tone[i]);
        System.out.println("  متوسط خطأ μ-law: " + (err / tone.length));
    }
}
