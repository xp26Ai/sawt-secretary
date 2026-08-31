package com.osaid.sawtsecretary.sip;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * مسار الصوت الفعلي للمكالمة — RTP فوق UDP.
 *
 * هذا هو قلب الجسر: الصوت يدخل ويخرج **رقمياً بالكامل**.
 * ما فيه ميكروفون ولا سمّاعة ولا أي التقاط من الغرفة.
 */
public class RtpSession {

    private static final String TAG = "RtpSession";

    /** 20 ملّي ثانية عند 8 كيلوهرتز = 160 عيّنة = 160 بايت في G.711 */
    public static final int FRAME = 160;
    private static final int FRAME_MS = 20;

    public interface OnAudio {
        /** صوت المتصل، PCM16 عند 8 كيلوهرتز. */
        void received(short[] pcm8k);
    }

    private final DatagramSocket socket;
    private final InetAddress remoteAddr;
    private final int remotePort;
    private final int payloadType;      // 0 = PCMU (μ-law)، 8 = PCMA (A-law)
    private volatile OnAudio sink;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rxThread, txThread;

    private final byte[] txQueue = new byte[8000 * 60];   // حتى 60 ثانية
    private int txHead = 0, txTail = 0;
    private final Object txLock = new Object();

    private int seq;
    private long timestamp;
    private final int ssrc;

    public RtpSession(DatagramSocket socket, InetAddress remoteAddr, int remotePort,
                      int payloadType, OnAudio sink) {
        this.socket = socket;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
        this.payloadType = payloadType;
        this.sink = sink;

        Random r = new Random();
        this.seq = r.nextInt(0xffff);
        this.timestamp = r.nextInt(Integer.MAX_VALUE);
        this.ssrc = r.nextInt();
    }

    /** تُضبط من الخدمة بعد إنشاء الجلسة، قبل start(). */
    public void setSink(OnAudio s) { this.sink = s; }

    public void start() {
        running.set(true);
        rxThread = new Thread(this::receiveLoop, "rtp-rx");
        txThread = new Thread(this::sendLoop, "rtp-tx");
        rxThread.start();
        txThread.start();
    }

    /* ─────────────────────── استقبال ─────────────────────── */

    private void receiveLoop() {
        byte[] buf = new byte[1500];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running.get()) {
            try {
                socket.receive(pkt);
                int len = pkt.getLength();
                if (len < 13) continue;

                // ترويسة RTP: 12 بايت + (عدد CSRC × 4)
                int cc = buf[0] & 0x0f;
                int headerLen = 12 + cc * 4;
                if ((buf[0] & 0x10) != 0) {                       // امتداد
                    if (len < headerLen + 4) continue;
                    int extWords = ((buf[headerLen + 2] & 0xff) << 8) | (buf[headerLen + 3] & 0xff);
                    headerLen += 4 + extWords * 4;
                }
                if (len <= headerLen) continue;

                int pt = buf[1] & 0x7f;
                int payloadLen = len - headerLen;
                short[] pcm = (pt == 8)
                        ? MuLaw.ALaw.decode(buf, headerLen, payloadLen)
                        : MuLaw.decode(buf, headerLen, payloadLen);
                OnAudio s = sink;
                if (s != null) s.received(pcm);

            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "استقبال: " + e.getMessage());
            }
        }
    }

    /* ─────────────────────── إرسال ─────────────────────── */

    /**
     * يضيف صوت السكرتير للطابور. PCM16 عند 8 كيلوهرتز.
     * الإرسال نفسه يتم بإيقاع ثابت 20 ملّي ثانية في خيط منفصل —
     * وهذا شرط أساسي، فالشبكة تتوقع تدفقاً منتظماً لا دفعات.
     */
    public void queue(short[] pcm8k) {
        byte[] enc = (payloadType == 8)
                ? MuLaw.ALaw.encode(pcm8k, 0, pcm8k.length)
                : MuLaw.encode(pcm8k, 0, pcm8k.length);
        synchronized (txLock) {
            for (byte b : enc) {
                int next = (txTail + 1) % txQueue.length;
                if (next == txHead) break;              // الطابور امتلأ
                txQueue[txTail] = b;
                txTail = next;
            }
        }
    }

    /** المتصل قاطع — نرمي كل ما لم يُرسل بعد. */
    public void flush() {
        synchronized (txLock) { txHead = txTail = 0; }
    }

    public boolean hasPending() {
        synchronized (txLock) { return txHead != txTail; }
    }

    private void sendLoop() {
        byte[] frame = new byte[FRAME];
        byte[] pkt = new byte[12 + FRAME];
        long next = System.nanoTime();

        while (running.get()) {
            int got;
            synchronized (txLock) {
                got = 0;
                while (got < FRAME && txHead != txTail) {
                    frame[got++] = txQueue[txHead];
                    txHead = (txHead + 1) % txQueue.length;
                }
            }
            // صمت مريح لو ما فيه صوت — يبقي التدفق منتظماً
            byte silent = (byte) (payloadType == 8 ? 0xd5 : 0xff);
            for (int i = got; i < FRAME; i++) frame[i] = silent;

            try {
                buildHeader(pkt);
                System.arraycopy(frame, 0, pkt, 12, FRAME);
                socket.send(new DatagramPacket(pkt, pkt.length, remoteAddr, remotePort));
                seq = (seq + 1) & 0xffff;
                timestamp += FRAME;
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "إرسال: " + e.getMessage());
            }

            next += FRAME_MS * 1_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); }
                catch (InterruptedException e) { return; }
            } else {
                next = System.nanoTime();   // تأخرنا — نعيد الضبط
            }
        }
    }

    private void buildHeader(byte[] p) {
        p[0] = (byte) 0x80;                       // الإصدار 2
        p[1] = (byte) (payloadType & 0x7f);
        p[2] = (byte) (seq >> 8);
        p[3] = (byte) seq;
        p[4] = (byte) (timestamp >> 24);
        p[5] = (byte) (timestamp >> 16);
        p[6] = (byte) (timestamp >> 8);
        p[7] = (byte) timestamp;
        p[8] = (byte) (ssrc >> 24);
        p[9] = (byte) (ssrc >> 16);
        p[10] = (byte) (ssrc >> 8);
        p[11] = (byte) ssrc;
    }

    public void stop() {
        running.set(false);
        if (rxThread != null) rxThread.interrupt();
        if (txThread != null) txThread.interrupt();
        try { if (rxThread != null) rxThread.join(300); } catch (InterruptedException ignored) { }
        try { if (txThread != null) txThread.join(300); } catch (InterruptedException ignored) { }
    }
}
