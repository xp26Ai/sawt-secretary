package com.osaid.sawtsecretary;

import org.json.JSONArray;
import org.json.JSONObject;

/** تعريفات الأدوات التي يستدعيها السكرتير بنفسه أثناء المكالمة. */
public class Tools {

    public static final String RECORD_CALLER = "recordCaller";
    public static final String MARK_URGENT   = "markUrgent";
    public static final String END_CALL      = "endCall";

    public static JSONArray declarations() throws Exception {
        JSONArray a = new JSONArray();

        Schema caller = new Schema()
                .str("name",           "اسم المتصل كما قاله",                    true)
                .str("relation",       "صفته: زميل، عميل، مورّد، قريب، جهة حكومية، تسويق، غير معروف", false)
                .str("reason",         "سبب الاتصال بكلماتك، جملة أو جملتين",     true)
                .str("category",       "التصنيف: عمل، شخصي، مالي، موعد، تسويق، احتيال_محتمل، أخرى", true)
                .str("callbackNumber", "الرقم الذي يفضّل الرد عليه، أرقام فقط",   false)
                .str("bestTime",       "أفضل وقت للرد عليه",                      false);

        a.put(declare(RECORD_CALLER,
                "يسجّل بيانات المتصل وسبب اتصاله. استدعها أول ما تعرف الاسم والسبب، "
              + "وأعد استدعاءها كلما اتضحت تفاصيل جديدة.", caller));

        a.put(declare(MARK_URGENT,
                "يعلّم المكالمة كمستعجلة ويرسل تنبيهاً فورياً لصاحب الجوال الآن.",
                new Schema().str("why", "ليش الموضوع مستعجل", true)));

        a.put(declare(END_CALL,
                "ينهي المكالمة بعد جمع المعلومات. استدعها قبل جملة الوداع مباشرة.",
                new Schema().str("summary", "ملخّص المكالمة في سطر واحد", true)));

        return a;
    }

    private static JSONObject declare(String name, String desc, Schema s) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", s.build());
    }

    /** بنّاء بسيط لمخطط المعاملات — أوضح وأأمن من الوسائط المتغيّرة. */
    private static class Schema {
        private final JSONObject props = new JSONObject();
        private final JSONArray required = new JSONArray();

        Schema str(String name, String desc, boolean req) {
            try {
                props.put(name, new JSONObject().put("type", "STRING").put("description", desc));
                if (req) required.put(name);
            } catch (Exception ignored) { }
            return this;
        }

        JSONObject build() throws Exception {
            return new JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", props)
                    .put("required", required);
        }
    }
}
