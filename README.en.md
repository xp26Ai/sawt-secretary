# sawt-secretary

[العربية](README.md)

**A real phone call comes in → a written summary goes out on Telegram.**

An Android app that takes your forwarded calls over a SIP line and wires the call audio straight into Gemini Live.
The network stack is hand-written: 741 lines of Java covering SIP, RTP and G.711 encoding, with no telephony library.
The whole app is 2,972 lines, with three dependencies: `appcompat`, `material` and `okhttp`.
The model asks the caller for a name, a topic and a callback number, then hangs up and sends the summary.

---

## The problem

The easy path looks obvious: the app answers the call, then opens the speaker and the microphone.

I measured that path on a real device. The in-app microphone meter read **zero** during the call.
Android keeps call audio away from ordinary apps, and there is no API to get around it.
Even if it could hear, the speaker sends everything in the room back to the caller.

`AudioProbeActivity.java` runs that measurement and prints what the system reported, word for word.

That leaves one path: the app has to own the audio from the start. It has to become the phone endpoint itself.

---

## How it works

One chain: caller → carrier forwarding → SIP provider → app → Gemini Live.

| Layer | Job | File |
|---|---|---|
| Registration and auth | MD5 Digest per RFC 2617, refreshed every 270 seconds | `sip/SipClient.java` |
| Answering the call | Reads the SDP, picks μ-law or A-law, replies `200 OK` | `sip/SipClient.java` |
| Audio path | One RTP packet every 20 ms, 160 bytes per packet | `sip/RtpSession.java` |
| Encoding | G.711 tables built once at load, so conversion is an array lookup | `sip/MuLaw.java` |
| The bridge | Resampling: phone 8 kHz, model input 16 kHz, model output 24 kHz | `SipService.java` |
| The model | Live WebSocket, three tools: log the caller, urgent alert, hang up | `GeminiLive.java` |

Three points worth calling out:

- **Silence is not nothing.** We send comfort-noise bytes every 20 ms. The network expects a steady stream, not bursts.
- **Barge-in.** When the caller talks over the secretary, we flush the send queue and it goes quiet at once.
- **A dead field.** `realtime_input.media_chunks` is in the Gemini docs, but it kills the session with code 1007.

---

## The key design decision

**The problem:** the easy path died under measurement, and the only alternative is owning the whole audio path.

**The decision:** write the SIP, RTP and G.711 stack myself instead of pulling in a ready-made stack.
SIP only has to do three things here — register, answer a call, hang up.
And tight control over send timing is the whole point, which is the first thing a library hides.

**The cost:** 741 lines to write and maintain, and three protocols I am now responsible for getting right.

**The payoff:** the audio is digital in both directions, so nothing is ever picked up from the room. And the timing is
mine: the send loop corrects its own drift with `System.nanoTime()` after every frame, and the measurement below shows it.

---

## Running it

Build the app:

```
build.bat            # builds an unsigned release APK
```

No signing key ships in this repo; sign the output with your own keystore.
The Gemini key, the Telegram token and the SIP account are entered in the
settings screen and stored in `SharedPreferences` on the device, not in code.

Test the stack with no number and no provider account:

```
siptest\run-test.bat
```

A SIP provider simulator in 248 lines of Node.js. It demands Digest auth and **actually verifies it** with MD5,
then sends an `INVITE`, streams a 400 Hz tone as the caller's voice, hangs up with `BYE` and prints the report.
It runs against the same SIP files that ship in the app — no separate test copy.

Last measured run:

```text
  ← REGISTER بلا مصادقة — نطلب التحدي
  ✓ المصادقة صحيحة — سجّلناه
  → نرسل مكالمة واردة (INVITE)
  ← 180 يرن
  ← 200 قَبِل المكالمة
  ✓ مسار الصوت: 127.0.0.1:56393
  → نقفل (BYE)

── تقرير المحاكي ──
  حزم أرسلناها للتطبيق : 353
  حزم رجعت من التطبيق  : 450  (9.0 ثانية)
  أول حزمة راجعة بعد   : 2644 ملّي ثانية

  ✓ الصوت يمشي في الاتجاهين
```

The simulator prints in Arabic. The report lines read: packets we sent to the app **353**,
packets that came back from the app **450** over **9.0 s**, first packet back after **2644 ms**,
audio flowing both ways.

The gap between 353 and 450 in the same window is not a fault: Node's `setInterval` drifts under load,
while the send loop in the app corrects its drift after every frame. That gap is what the code was built for.

---

## Why I built it

I work as an IT supervisor, and I miss calls while I am out on site.

I tried the easy path, the microphone meter read zero, so I built the digital line instead.
