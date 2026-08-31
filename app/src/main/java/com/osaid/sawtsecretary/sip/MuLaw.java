package com.osaid.sawtsecretary.sip;

/**
 * ترميز G.711 μ-law — لغة الصوت في شبكات الهاتف.
 *
 * جداول ثابتة تُبنى مرة واحدة عند التحميل: التحويل يصير بحث في مصفوفة،
 * بلا أي حساب أثناء المكالمة.
 */
public final class MuLaw {

    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;

    private static final short[] DECODE = new short[256];
    private static final byte[] ENCODE = new byte[65536];

    static {
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xff;
            int t = (((u & 0x0f) << 3) + BIAS) << ((u & 0x70) >> 4);
            t -= BIAS;
            DECODE[i] = (short) (((u & 0x80) != 0) ? -t : t);
        }
        for (int s = -32768; s < 32768; s++) {
            int sample = s;
            int sign = (sample >> 8) & 0x80;
            if (sign != 0) sample = -sample;
            if (sample > CLIP) sample = CLIP;
            sample += BIAS;

            int exponent = 7;
            for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; exponent--, mask >>= 1) ;

            int mantissa = (sample >> (exponent + 3)) & 0x0f;
            ENCODE[s & 0xffff] = (byte) (~(sign | (exponent << 4) | mantissa));
        }
    }

    private MuLaw() { }

    /** μ-law → PCM16 */
    public static short[] decode(byte[] mu, int off, int len) {
        short[] out = new short[len];
        for (int i = 0; i < len; i++) out[i] = DECODE[mu[off + i] & 0xff];
        return out;
    }

    /** PCM16 → μ-law */
    public static byte[] encode(short[] pcm, int off, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = ENCODE[pcm[off + i] & 0xffff];
        return out;
    }

    /** ترميز G.711 A-law — بديل شائع في أوروبا والشرق الأوسط. */
    public static final class ALaw {
        private static final short[] DEC = new short[256];
        private static final byte[] ENC = new byte[65536];

        static {
            for (int i = 0; i < 256; i++) {
                int a = i ^ 0x55;
                int t = (a & 0x0f) << 4;
                int seg = (a & 0x70) >> 4;
                if (seg == 0) t += 8;
                else if (seg == 1) t += 0x108;
                else { t += 0x108; t <<= seg - 1; }
                DEC[i] = (short) (((a & 0x80) != 0) ? t : -t);
            }
            for (int s = -32768; s < 32768; s++) {
                int sample = s;
                int sign = (sample & 0x8000) != 0 ? 0x00 : 0x80;
                if (sign == 0x00) sample = -sample;
                if (sample > 32635) sample = 32635;

                int enc;
                if (sample >= 256) {
                    int exp = 7;
                    for (int mask = 0x4000; (sample & mask) == 0 && exp > 0; exp--, mask >>= 1) ;
                    int man = (sample >> (exp + 3)) & 0x0f;
                    enc = (exp << 4) | man;
                } else {
                    enc = sample >> 4;
                }
                ENC[s & 0xffff] = (byte) ((enc ^ 0x55) | sign);
            }
        }

        public static short[] decode(byte[] a, int off, int len) {
            short[] out = new short[len];
            for (int i = 0; i < len; i++) out[i] = DEC[a[off + i] & 0xff];
            return out;
        }

        public static byte[] encode(short[] pcm, int off, int len) {
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) out[i] = ENC[pcm[off + i] & 0xffff];
            return out;
        }
    }

    /** إعادة عيّنة خطّية — كافية للكلام الهاتفي وبلا تأخير يُذكر. */
    public static short[] resample(short[] in, int fromRate, int toRate) {
        if (fromRate == toRate) return in;
        double ratio = (double) fromRate / toRate;
        int outLen = (int) (in.length / ratio);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i * ratio;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double f = pos - i0;
            out[i] = (short) (in[i0] * (1 - f) + in[i1] * f);
        }
        return out;
    }
}
