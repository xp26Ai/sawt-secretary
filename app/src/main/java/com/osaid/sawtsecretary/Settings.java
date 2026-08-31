package com.osaid.sawtsecretary;

import android.content.Context;
import android.content.SharedPreferences;

/** كل الإعدادات في مكان واحد. */
public class Settings {

    private static final String FILE = "sawt";
    private final SharedPreferences p;

    public Settings(Context c) {
        p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private String get(String k, String d) { return p.getString(k, d); }
    private void set(String k, String v) { p.edit().putString(k, v).apply(); }

    // ── مفاتيح الخدمات ──
    public String geminiKey()            { return get("gemini_key", ""); }
    public void   geminiKey(String v)    { set("gemini_key", v); }

    public String tgToken()              { return get("tg_token", ""); }
    public void   tgToken(String v)      { set("tg_token", v); }

    public String tgChat()               { return get("tg_chat", ""); }
    public void   tgChat(String v)       { set("tg_chat", v); }

    // ── شخصية السكرتير ──
    public String ownerName()            { return get("owner", "صاحب الجوال"); }
    public void   ownerName(String v)    { set("owner", v); }

    public String secretaryName()        { return get("sec_name", "مساعد"); }
    public void   secretaryName(String v){ set("sec_name", v); }

    public String status()               { return get("status", "مشغول الآن ولا يقدر يرد"); }
    public void   status(String v)       { set("status", v); }

    public String freeAfter()            { return get("free_after", "راح يرجع لك بأقرب وقت"); }
    public void   freeAfter(String v)    { set("free_after", v); }

    public String voice()                { return get("voice", "Charon"); }
    public void   voice(String v)        { set("voice", v); }

    // ── حساب SIP (خط الهاتف الرقمي) ──
    public String sipServer()            { return get("sip_server", ""); }
    public void   sipServer(String v)    { set("sip_server", v); }

    public int  sipPort()                { return p.getInt("sip_port", 5060); }
    public void sipPort(int v)           { p.edit().putInt("sip_port", v).apply(); }

    public String sipUser()              { return get("sip_user", ""); }
    public void   sipUser(String v)      { set("sip_user", v); }

    public String sipPass()              { return get("sip_pass", ""); }
    public void   sipPass(String v)      { set("sip_pass", v); }

    public boolean sipReady() {
        return !sipServer().isEmpty() && !sipUser().isEmpty() && !sipPass().isEmpty();
    }

    // ── السلوك ──
    /** 0 = يدوي فقط (أنت تضغط). أكبر من صفر = يرد تلقائياً بعد هذي الثواني. */
    public int  autoAnswerSec()          { return p.getInt("auto_sec", 0); }
    public void autoAnswerSec(int v)     { p.edit().putInt("auto_sec", v).apply(); }

    public boolean enabled()             { return p.getBoolean("enabled", false); }
    public void    enabled(boolean v)    { p.edit().putBoolean("enabled", v).apply(); }

    public boolean ready() {
        return !geminiKey().isEmpty();
    }

    public boolean telegramReady() {
        return !tgToken().isEmpty() && !tgChat().isEmpty();
    }
}
