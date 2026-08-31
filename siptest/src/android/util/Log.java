package android.util;

/** بديل مبسّط لسجل أندرويد — عشان نشغّل نفس الكود على الحاسب ونختبره. */
public class Log {
    public static int i(String t, String m) { System.out.println("[i] " + t + ": " + m); return 0; }
    public static int w(String t, String m) { System.out.println("[w] " + t + ": " + m); return 0; }
    public static int e(String t, String m) { System.out.println("[e] " + t + ": " + m); return 0; }
    public static int d(String t, String m) { return 0; }
}
