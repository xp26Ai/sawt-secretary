package com.osaid.sawtsecretary;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/** سجل المكالمات — مبني برمجياً، بدون ملف تخطيط. */
public class LogActivity extends AppCompatActivity {

    private LinearLayout list;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0E1117"));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(list);
        setContentView(sv);

        setTitle("سجل المكالمات");
        render();
    }

    private void render() {
        list.removeAllViews();
        JSONArray a = new CallStore(this).all();

        if (a.length() == 0) {
            list.addView(text("ما فيه مكالمات بعد.", 15, "#6B7889", false));
            return;
        }

        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null) continue;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor(
                    c.optBoolean("urgent") ? "#2A1A1E" : "#1A2029"));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            card.setLayoutParams(lp);

            String head = (c.optBoolean("urgent") ? "🔴 " : "") + c.optString("name", "غير معروف");
            card.addView(text(head, 17, "#E6EDF5", true));
            card.addView(text(c.optString("reason", ""), 14, "#9AA8BB", false));

            StringBuilder meta = new StringBuilder();
            meta.append(c.optString("category", ""));
            if (!c.optString("callback", "").isEmpty())
                meta.append("  ·  ").append(c.optString("callback"));
            if (!c.optString("bestTime", "").isEmpty())
                meta.append("  ·  ").append(c.optString("bestTime"));
            meta.append("  ·  ").append(c.optInt("seconds")).append(" ث");
            meta.append("  ·  ").append(c.optString("at", ""));
            card.addView(text(meta.toString(), 12, "#6B7889", false));

            String tr = c.optString("transcript", "");
            if (!tr.isEmpty()) {
                TextView t = text(tr.trim(), 12, "#7E8BA0", false);
                t.setMaxLines(3);
                t.setPadding(0, dp(8), 0, 0);
                card.addView(t);
                card.setOnClickListener(v ->
                        t.setMaxLines(t.getMaxLines() == 3 ? 200 : 3));
            }

            list.addView(card);
        }

        Button clear = new Button(this);
        clear.setText("امسح السجل");
        clear.setOnClickListener(v -> { new CallStore(this).clear(); render(); });
        list.addView(clear);
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.START);
        t.setLineSpacing(0, 1.35f);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
