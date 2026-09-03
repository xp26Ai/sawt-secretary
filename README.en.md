# sawt-secretary

[العربية](README.md)

**A real phone call comes in → a written summary goes out on Telegram.**

An Android app that takes your forwarded calls over a SIP line. It sends the call audio straight into Gemini Live.
I wrote the network stack myself: 741 lines of Java for SIP, RTP and G.711 encoding. No telephony library.
The whole app is 2,972 lines, with three dependencies: `appcompat`, `material` and `okhttp`.
The model asks the caller for a name, a topic and a callback number. Then it hangs up and sends the summary.

---

## The problem

The easy path looks obvious. The app answers the call, then opens the speaker and the microphone.

I measured that path on a real device. The in-app microphone meter read **zero** during the call.
Android keeps call audio away from normal apps. No API gets around it.
And if it could hear, the speaker sends the room noise back to the caller.

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
- **Barge-in.** The caller can talk over the secretary. We flush the send queue and it goes quiet at once.
- **A dead field.** The Gemini docs list `realtime_input.media_chunks`. But it kills the session with code 1007.

---

## The key design decision

**The problem:** the easy path died under measurement. The only other option is to own the whole audio path.

**The decision:** write the SIP, RTP and G.711 stack myself. No ready-made stack.
SIP only does three things here: register, answer a call, hang up.
And tight control over send timing is the whole point. A library hides that first.

**The cost:** 741 lines to write and keep working. And three protocols I now have to get right.

**The payoff:** the audio is digital in both directions. Nothing is ever picked up from the room.
The timing is mine too. The send loop fixes its own drift with `System.nanoTime()` after every frame.
The measurement below shows it.

---

## Running it

Build the app:

```
build.bat            # builds an unsigned release APK
```

No signing key ships in this repo. Sign the output with your own keystore.
You enter the Gemini key, the Telegram token and the SIP account in settings.
The app stores them in `SharedPreferences` on the device, not in code.

Test the stack with no number and no provider account:

```
siptest\run-test.bat
```

A SIP provider simulator in 248 lines of Node.js. It asks for Digest auth and **actually verifies it** with MD5.
Then it sends an `INVITE`. It streams a 400 Hz tone as the caller's voice.
Then it hangs up with `BYE` and prints the report.
It runs against the same SIP files that ship in the app. There is no separate test copy.

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

The simulator prints in Arabic. The report lines read: we sent **353** packets to the app.
The app sent back **450** packets over **9.0 s**. The first packet came back after **2644 ms**.
Audio flows both ways.

The gap between 353 and 450 in the same window is not a fault.
Node's `setInterval` drifts under load. The send loop in the app fixes its drift after every frame.
That gap is what the code was built for.

---

## Why I built it

I work as an IT supervisor. I miss calls while I am out on site.

I tried the easy path. The microphone meter read zero. So I built the digital line instead.
