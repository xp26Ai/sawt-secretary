package com.osaid.sawtsecretary.sip;

import android.util.Log;

import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * وكيل SIP بسيط ومكتفٍ ذاتياً — بدون أي مكتبة خارجية.
 *
 * يسجّل في مزوّد الهاتف، ينتظر المكالمات الواردة، يرد عليها،
 * ويسلّم مسار الصوت (RTP) للجسر. كل شيء عبر UDP.
 *
 * نُفِّذ يدوياً بدل استيراد مكدّس ضخم لأننا نحتاج جزءاً صغيراً منه فقط،
 * ولأن التحكم الكامل في مسار الصوت هو بيت القصيد.
 */
public class SipClient {

    private static final String TAG = "SipClient";
    private static final int EXPIRES = 300;

    public interface Listener {
        void onRegistered(boolean ok, String detail);
        /** مكالمة واردة — رقم المتصل، ومقبس/عنوان الصوت جاهزان. */
        void onIncoming(String from, RtpSession rtp);
        void onCallEnded(String reason);
        void onLog(String line);
    }

    /* ── الإعدادات ── */
    private final String server;      // مثال: sip.provider.com
    private final int serverPort;
    private final String user;
    private final String password;
    private final String displayName;
    private final Listener out;

    /* ── الحالة ── */
    private DatagramSocket sip;
    private DatagramSocket rtpSocket;
    private InetAddress serverAddr;
    private String localIp;
    private int localPort, rtpPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rxThread, keepThread;
    private final Random rnd = new Random();

    private String callId, fromTag, toTag, remoteUri, remoteContact;
    private String lastInviteBranch, lastInviteFrom, lastInviteTo, lastInviteVia, lastInviteCseq;
    private RtpSession rtp;
    private volatile boolean inCall = false;

    public SipClient(String server, int serverPort, String user, String password,
                     String displayName, Listener out) {
        this.server = server;
        this.serverPort = serverPort <= 0 ? 5060 : serverPort;
        this.user = user;
        this.password = password;
        this.displayName = displayName == null || displayName.isEmpty() ? user : displayName;
        this.out = out;
    }

    /* ─────────────────────── التشغيل ─────────────────────── */

    public void start() throws Exception {
        serverAddr = InetAddress.getByName(server);

        sip = new DatagramSocket();
        sip.setSoTimeout(1000);
        localPort = sip.getLocalPort();

        // نعرف عنواننا المحلي من مقبس موصول للخادم
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(serverAddr, this.serverPort);
            localIp = probe.getLocalAddress().getHostAddress();
        }

        rtpSocket = new DatagramSocket();
        rtpPort = rtpSocket.getLocalPort();

        running.set(true);
        rxThread = new Thread(this::receiveLoop, "sip-rx");
        rxThread.start();

        log("محلي " + localIp + ":" + localPort + " · صوت " + rtpPort);
        register(null);

        keepThread = new Thread(this::keepAliveLoop, "sip-keep");
        keepThread.start();
    }

    private void keepAliveLoop() {
        while (running.get()) {
            try {
                Thread.sleep(EXPIRES * 1000L - 30000);
                if (!running.get()) return;
                register(null);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("تجديد التسجيل فشل: " + e.getMessage());
            }
        }
    }

    /* ─────────────────────── التسجيل ─────────────────────── */

    private String regCallId, regFromTag;
    private int regCseq = 1;

    private void register(String authHeader) {
        try {
            if (regCallId == null) {
                regCallId = hex(16) + "@" + localIp;
                regFromTag = hex(8);
            }
            String branch = "z9hG4bK" + hex(10);
            StringBuilder m = new StringBuilder();
            m.append("REGISTER sip:").append(server).append(" SIP/2.0\r\n");
            m.append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(localPort)
             .append(";rport;branch=").append(branch).append("\r\n");
            m.append("Max-Forwards: 70\r\n");
            m.append("From: \"").append(displayName).append("\" <sip:").append(user).append('@')
             .append(server).append(">;tag=").append(regFromTag).append("\r\n");
            m.append("To: <sip:").append(user).append('@').append(server).append(">\r\n");
            m.append("Call-ID: ").append(regCallId).append("\r\n");
            m.append("CSeq: ").append(regCseq).append(" REGISTER\r\n");
            m.append("Contact: <sip:").append(user).append('@').append(localIp).append(':')
             .append(localPort).append(">\r\n");
            if (authHeader != null) m.append(authHeader).append("\r\n");
            m.append("Expires: ").append(EXPIRES).append("\r\n");
            m.append("User-Agent: SawtSecretary/1.0\r\n");
            m.append("Content-Length: 0\r\n\r\n");
            send(m.toString());
        } catch (Exception e) {
            out.onRegistered(false, e.getMessage());
        }
    }

    /* ─────────────────────── الاستقبال ─────────────────────── */

    private void receiveLoop() {
        byte[] buf = new byte[8192];
        while (running.get()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sip.receive(p);
                handle(new String(buf, 0, p.getLength(), "UTF-8"));
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "استقبال: " + e.getMessage());
            }
        }
    }

    private void handle(String msg) {
        String first = msg.split("\r\n", 2)[0];

        if (first.startsWith("SIP/2.0")) {
            handleResponse(first, msg);
        } else if (first.startsWith("INVITE ")) {
            handleInvite(msg);
        } else if (first.startsWith("BYE ")) {
            respond(msg, 200, "OK", null);
            endCall("أقفل المتصل");
        } else if (first.startsWith("CANCEL ")) {
            respond(msg, 200, "OK", null);
            endCall("ألغى المتصل");
        } else if (first.startsWith("ACK ")) {
            // بدأت المكالمة فعلياً
        } else if (first.startsWith("OPTIONS ")) {
            respond(msg, 200, "OK", null);
        }
    }

    private void handleResponse(String status, String msg) {
        int code = Integer.parseInt(status.split(" ")[1]);
        String cseq = header(msg, "CSeq");

        if (cseq != null && cseq.contains("REGISTER")) {
            if (code == 401 || code == 407) {
                String chal = header(msg, code == 401 ? "WWW-Authenticate" : "Proxy-Authenticate");
                if (chal == null) { out.onRegistered(false, "تحدٍّ ناقص"); return; }
                regCseq++;
                String hdr = (code == 401 ? "Authorization: " : "Proxy-Authorization: ")
                        + digest(chal, "REGISTER", "sip:" + server);
                register(hdr);
            } else if (code == 200) {
                out.onRegistered(true, "مسجّل — ينتظر المكالمات");
            } else if (code >= 400) {
                out.onRegistered(false, "رفض التسجيل (" + code + ")");
            }
        }
    }

    /* ─────────────────────── المكالمة الواردة ─────────────────────── */

    private void handleInvite(String msg) {
        if (inCall) { respond(msg, 486, "Busy Here", null); return; }

        callId = header(msg, "Call-ID");
        lastInviteFrom = header(msg, "From");
        lastInviteTo = header(msg, "To");
        lastInviteVia = header(msg, "Via");
        lastInviteCseq = header(msg, "CSeq");
        remoteContact = header(msg, "Contact");
        toTag = hex(8);

        String from = extractUser(lastInviteFrom);

        // نقرأ عنوان ومنفذ الصوت من وصف الجلسة (SDP)
        String body = msg.contains("\r\n\r\n") ? msg.split("\r\n\r\n", 2)[1] : "";
        String remoteIp = find(body, "(?m)^c=IN IP4 ([0-9.]+)");
        String mLine = find(body, "(?m)^m=audio (\\d+) RTP/AVP ([0-9 ]+)");
        int remoteRtpPort = 0;
        int pt = 0;
        if (mLine != null) {
            Matcher mm = Pattern.compile("^m=audio (\\d+) RTP/AVP ([0-9 ]+)", Pattern.MULTILINE).matcher(body);
            if (mm.find()) {
                remoteRtpPort = Integer.parseInt(mm.group(1));
                for (String s : mm.group(2).trim().split("\\s+")) {
                    int v = Integer.parseInt(s);
                    if (v == 0 || v == 8) { pt = v; break; }   // نقبل PCMU أو PCMA فقط
                }
            }
        }

        if (remoteIp == null || remoteRtpPort == 0) {
            respond(msg, 488, "Not Acceptable Here", null);
            return;
        }

        respond(msg, 180, "Ringing", null);
        log("مكالمة من " + from + " · صوت " + remoteIp + ":" + remoteRtpPort
                + " · ترميز " + (pt == 8 ? "A-law" : "μ-law"));

        try {
            rtp = new RtpSession(rtpSocket, InetAddress.getByName(remoteIp), remoteRtpPort, pt,
                    pcm -> { /* يُربط في الخدمة */ });
            inCall = true;

            String sdp = buildSdp(pt);
            respond(msg, 200, "OK", sdp);
            out.onIncoming(from, rtp);

        } catch (Exception e) {
            log("فشل بدء الصوت: " + e.getMessage());
            respond(msg, 500, "Server Internal Error", null);
        }
    }

    private String buildSdp(int pt) {
        String codec = pt == 8 ? "PCMA" : "PCMU";
        return "v=0\r\n"
             + "o=- " + System.currentTimeMillis() + " 1 IN IP4 " + localIp + "\r\n"
             + "s=sawt\r\n"
             + "c=IN IP4 " + localIp + "\r\n"
             + "t=0 0\r\n"
             + "m=audio " + rtpPort + " RTP/AVP " + pt + " 101\r\n"
             + "a=rtpmap:" + pt + " " + codec + "/8000\r\n"
             + "a=rtpmap:101 telephone-event/8000\r\n"
             + "a=fmtp:101 0-16\r\n"
             + "a=ptime:20\r\n"
             + "a=sendrecv\r\n";
    }

    /** يقفل المكالمة من طرفنا. */
    public void hangUp() {
        if (!inCall) return;
        try {
            String target = remoteContact != null
                    ? find(remoteContact, "<([^>]+)>")
                    : "sip:" + server;
            if (target == null) target = "sip:" + server;

            String branch = "z9hG4bK" + hex(10);
            StringBuilder m = new StringBuilder();
            m.append("BYE ").append(target).append(" SIP/2.0\r\n");
            m.append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(localPort)
             .append(";rport;branch=").append(branch).append("\r\n");
            m.append("Max-Forwards: 70\r\n");
            m.append("From: ").append(lastInviteTo).append(";tag=").append(toTag).append("\r\n");
            m.append("To: ").append(lastInviteFrom).append("\r\n");
            m.append("Call-ID: ").append(callId).append("\r\n");
            m.append("CSeq: 2 BYE\r\n");
            m.append("Content-Length: 0\r\n\r\n");
            send(m.toString());
        } catch (Exception e) {
            log("فشل الإغلاق: " + e.getMessage());
        }
        endCall("أغلقنا المكالمة");
    }

    private void endCall(String why) {
        if (!inCall) return;
        inCall = false;
        if (rtp != null) { rtp.stop(); rtp = null; }
        out.onCallEnded(why);
    }

    /* ─────────────────────── أدوات ─────────────────────── */

    private void respond(String req, int code, String reason, String sdp) {
        try {
            StringBuilder m = new StringBuilder();
            m.append("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
            m.append("Via: ").append(header(req, "Via")).append("\r\n");
            String to = header(req, "To");
            if (code >= 180 && to != null && !to.contains("tag=")) to = to + ";tag=" + toTag;
            m.append("From: ").append(header(req, "From")).append("\r\n");
            m.append("To: ").append(to).append("\r\n");
            m.append("Call-ID: ").append(header(req, "Call-ID")).append("\r\n");
            m.append("CSeq: ").append(header(req, "CSeq")).append("\r\n");
            m.append("Contact: <sip:").append(user).append('@').append(localIp).append(':')
             .append(localPort).append(">\r\n");
            m.append("User-Agent: SawtSecretary/1.0\r\n");
            if (sdp != null) {
                m.append("Content-Type: application/sdp\r\n");
                m.append("Content-Length: ").append(sdp.getBytes("UTF-8").length).append("\r\n\r\n");
                m.append(sdp);
            } else {
                m.append("Content-Length: 0\r\n\r\n");
            }
            send(m.toString());
        } catch (Exception e) {
            Log.w(TAG, "رد: " + e.getMessage());
        }
    }

    private void send(String msg) throws Exception {
        byte[] b = msg.getBytes("UTF-8");
        sip.send(new DatagramPacket(b, b.length, serverAddr, serverPort));
    }

    /** مصادقة Digest حسب RFC 2617 — MD5. */
    private String digest(String challenge, String method, String uri) {
        Map<String, String> p = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+)=\"?([^\",]+)\"?").matcher(challenge);
        while (m.find()) p.put(m.group(1).toLowerCase(), m.group(2));

        String realm = p.getOrDefault("realm", "");
        String nonce = p.getOrDefault("nonce", "");
        String qop = p.get("qop");
        String opaque = p.get("opaque");

        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String response, cnonce = hex(8), nc = "00000001";

        if (qop != null && qop.contains("auth")) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }

        StringBuilder h = new StringBuilder();
        h.append("Digest username=\"").append(user).append("\"");
        h.append(", realm=\"").append(realm).append("\"");
        h.append(", nonce=\"").append(nonce).append("\"");
        h.append(", uri=\"").append(uri).append("\"");
        h.append(", response=\"").append(response).append("\"");
        if (qop != null && qop.contains("auth")) {
            h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
        }
        if (opaque != null) h.append(", opaque=\"").append(opaque).append("\"");
        h.append(", algorithm=MD5");
        return h.toString();
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] out = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(new BigInteger(1, out).toString(16));
            while (sb.length() < 32) sb.insert(0, '0');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String header(String msg, String name) {
        Matcher m = Pattern.compile("(?im)^" + name + "\\s*:\\s*(.+)$").matcher(msg);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String find(String s, String regex) {
        if (s == null) return null;
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String extractUser(String fromHeader) {
        String u = find(fromHeader, "sip:([^@>;]+)");
        return u == null ? "غير معروف" : u;
    }

    private String hex(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(Integer.toHexString(rnd.nextInt(16)));
        return sb.toString();
    }

    private void log(String s) { Log.i(TAG, s); out.onLog(s); }

    public boolean isInCall() { return inCall; }

    public void stop() {
        running.set(false);
        hangUp();
        if (keepThread != null) keepThread.interrupt();
        if (rxThread != null) rxThread.interrupt();
        if (sip != null) sip.close();
        if (rtpSocket != null) rtpSocket.close();
    }
}
