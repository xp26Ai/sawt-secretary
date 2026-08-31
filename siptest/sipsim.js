/**
 * خادم SIP محاكى — لاختبار مكدّس التطبيق بلا مزوّد حقيقي ولا رقم.
 *
 * يسوي بالضبط ما يسويه المزوّد:
 *   1. يطلب مصادقة Digest ويتحقق منها فعلاً (لا يقبل أي شيء).
 *   2. يرسل مكالمة واردة (INVITE) مع وصف صوت.
 *   3. يبثّ صوت "المتصل" RTP، ويستقبل ما يرجّعه التطبيق.
 *   4. يقفل بـBYE ويطبع التقرير.
 *
 * تشغيل: node sipsim.js
 */

import dgram from 'node:dgram';
import crypto from 'node:crypto';

const SIP_PORT = 5070;
const RTP_PORT = 40100;
const USER = 'testuser';
const PASS = 's3cret';
const REALM = 'sim.local';
const NONCE = crypto.randomBytes(12).toString('hex');

const sip = dgram.createSocket('udp4');
const rtp = dgram.createSocket('udp4');

const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');
const hdr = (m, n) => (new RegExp(`^${n}\\s*:\\s*(.+)$`, 'im').exec(m) || [])[1]?.trim();

let client = null;          // {address, port}
let contactUri = null;
let callActive = false;
let theirRtp = null;
let rxPackets = 0, rxBytes = 0;
let txPackets = 0;
let firstRxAt = null;
const t0 = Date.now();

/* ─────────────────────── SIP ─────────────────────── */

sip.on('message', (buf, rinfo) => {
  const msg = buf.toString();
  const first = msg.split('\r\n')[0];
  client = rinfo;

  if (first.startsWith('REGISTER')) return onRegister(msg, rinfo);
  if (first.startsWith('SIP/2.0')) return onResponse(msg, rinfo);
  if (first.startsWith('OPTIONS')) return reply(msg, rinfo, 200, 'OK');
});

function reply(req, rinfo, code, reason, extra = '', body = '') {
  const lines = [
    `SIP/2.0 ${code} ${reason}`,
    `Via: ${hdr(req, 'Via')}`,
    `From: ${hdr(req, 'From')}`,
    `To: ${hdr(req, 'To')}${code >= 200 && !/tag=/.test(hdr(req, 'To') || '') ? ';tag=simtag' : ''}`,
    `Call-ID: ${hdr(req, 'Call-ID')}`,
    `CSeq: ${hdr(req, 'CSeq')}`,
  ];
  if (extra) lines.push(extra);
  if (body) {
    lines.push('Content-Type: application/sdp');
    lines.push(`Content-Length: ${Buffer.byteLength(body)}`);
  } else {
    lines.push('Content-Length: 0');
  }
  const out = lines.join('\r\n') + '\r\n\r\n' + body;
  sip.send(out, rinfo.port, rinfo.address);
}

function onRegister(msg, rinfo) {
  const auth = hdr(msg, 'Authorization');

  if (!auth) {
    console.log('  ← REGISTER بلا مصادقة — نطلب التحدي');
    return reply(msg, rinfo, 401, 'Unauthorized',
      `WWW-Authenticate: Digest realm="${REALM}", nonce="${NONCE}", qop="auth", algorithm=MD5`);
  }

  // نتحقق فعلاً من الاستجابة — هذا يثبت صحة تنفيذ Digest في التطبيق
  const g = (k) => (new RegExp(`${k}="?([^",]+)"?`).exec(auth) || [])[1];
  const uri = g('uri'), nc = g('nc'), cnonce = g('cnonce'), qop = g('qop');
  const ha1 = md5(`${USER}:${REALM}:${PASS}`);
  const ha2 = md5(`REGISTER:${uri}`);
  const expected = qop
    ? md5(`${ha1}:${NONCE}:${nc}:${cnonce}:auth:${ha2}`)
    : md5(`${ha1}:${NONCE}:${ha2}`);

  if (g('response') !== expected) {
    console.log('  ✗ مصادقة خاطئة');
    return reply(msg, rinfo, 403, 'Forbidden');
  }

  contactUri = hdr(msg, 'Contact');
  console.log('  ✓ المصادقة صحيحة — سجّلناه');
  reply(msg, rinfo, 200, 'OK', 'Expires: 300');

  if (!callActive) setTimeout(() => sendInvite(rinfo), 1200);
}

let inviteCallId, inviteBranch, inviteFromTag;

function sendInvite(rinfo) {
  callActive = true;
  inviteCallId = crypto.randomBytes(8).toString('hex') + '@sim';
  inviteBranch = 'z9hG4bK' + crypto.randomBytes(6).toString('hex');
  inviteFromTag = crypto.randomBytes(4).toString('hex');

  const target = (/<([^>]+)>/.exec(contactUri || '') || [])[1] || `sip:${USER}@${rinfo.address}`;
  const sdp = [
    'v=0',
    `o=- ${Date.now()} 1 IN IP4 127.0.0.1`,
    's=sim', 'c=IN IP4 127.0.0.1', 't=0 0',
    `m=audio ${RTP_PORT} RTP/AVP 0 101`,
    'a=rtpmap:0 PCMU/8000',
    'a=rtpmap:101 telephone-event/8000',
    'a=ptime:20', 'a=sendrecv', '',
  ].join('\r\n');

  const m = [
    `INVITE ${target} SIP/2.0`,
    `Via: SIP/2.0/UDP 127.0.0.1:${SIP_PORT};branch=${inviteBranch}`,
    'Max-Forwards: 70',
    `From: "1234567890" <sip:1234567890@${REALM}>;tag=${inviteFromTag}`,
    `To: <sip:${USER}@${REALM}>`,
    `Call-ID: ${inviteCallId}`,
    'CSeq: 1 INVITE',
    `Contact: <sip:sim@127.0.0.1:${SIP_PORT}>`,
    'Content-Type: application/sdp',
    `Content-Length: ${Buffer.byteLength(sdp)}`,
    '', sdp,
  ].join('\r\n');

  console.log('  → نرسل مكالمة واردة (INVITE)');
  sip.send(m, rinfo.port, rinfo.address);
}

function onResponse(msg, rinfo) {
  const code = Number(msg.split(' ')[1]);
  const cseq = hdr(msg, 'CSeq') || '';

  if (cseq.includes('INVITE')) {
    if (code === 180) return console.log('  ← 180 يرن');
    if (code === 200) {
      console.log('  ← 200 قَبِل المكالمة');
      const body = msg.split('\r\n\r\n')[1] || '';
      const ip = (/c=IN IP4 ([0-9.]+)/.exec(body) || [])[1];
      const port = Number((/m=audio (\d+)/.exec(body) || [])[1]);
      if (!ip || !port) { console.log('  ✗ وصف صوت ناقص'); return; }
      theirRtp = { ip, port };
      console.log(`  ✓ مسار الصوت: ${ip}:${port}`);

      // ACK
      const ack = [
        `ACK sip:${USER}@${rinfo.address} SIP/2.0`,
        `Via: SIP/2.0/UDP 127.0.0.1:${SIP_PORT};branch=${inviteBranch}`,
        'Max-Forwards: 70',
        `From: "1234567890" <sip:1234567890@${REALM}>;tag=${inviteFromTag}`,
        `To: ${hdr(msg, 'To')}`,
        `Call-ID: ${inviteCallId}`,
        'CSeq: 1 ACK',
        'Content-Length: 0', '', '',
      ].join('\r\n');
      sip.send(ack, rinfo.port, rinfo.address);

      startRtp();
      setTimeout(() => sendBye(rinfo, msg), 9000);
    }
  }
}

function sendBye(rinfo, okMsg) {
  console.log('  → نقفل (BYE)');
  const bye = [
    `BYE sip:${USER}@${rinfo.address} SIP/2.0`,
    `Via: SIP/2.0/UDP 127.0.0.1:${SIP_PORT};branch=z9hG4bK${crypto.randomBytes(6).toString('hex')}`,
    'Max-Forwards: 70',
    `From: "1234567890" <sip:1234567890@${REALM}>;tag=${inviteFromTag}`,
    `To: ${hdr(okMsg, 'To')}`,
    `Call-ID: ${inviteCallId}`,
    'CSeq: 2 BYE',
    'Content-Length: 0', '', '',
  ].join('\r\n');
  sip.send(bye, rinfo.port, rinfo.address);
  setTimeout(report, 900);
}

/* ─────────────────────── RTP ─────────────────────── */

rtp.on('message', (buf) => {
  if (buf.length <= 12) return;
  if (firstRxAt === null) firstRxAt = Date.now() - t0;
  rxPackets++;
  rxBytes += buf.length - 12;
});

let txTimer = null;

function startRtp() {
  let seq = 1, ts = 0;
  const ssrc = crypto.randomBytes(4);
  // نغمة 400 هرتز كصوت "المتصل"
  const tone = Buffer.alloc(160);
  txTimer = setInterval(() => {
    if (!theirRtp) return;
    for (let i = 0; i < 160; i++) {
      const v = Math.round(8000 * Math.sin(2 * Math.PI * 400 * ((ts + i) / 8000)));
      tone[i] = muEncode(v);
    }
    const h = Buffer.alloc(12);
    h[0] = 0x80; h[1] = 0;
    h.writeUInt16BE(seq & 0xffff, 2);
    h.writeUInt32BE(ts >>> 0, 4);
    ssrc.copy(h, 8);
    rtp.send(Buffer.concat([h, tone]), theirRtp.port, theirRtp.ip);
    seq++; ts += 160; txPackets++;
  }, 20);
}

function muEncode(s) {
  const BIAS = 0x84, CLIP = 32635;
  let sign = (s >> 8) & 0x80;
  if (sign) s = -s;
  if (s > CLIP) s = CLIP;
  s += BIAS;
  let exp = 7;
  for (let mask = 0x4000; (s & mask) === 0 && exp > 0; exp--, mask >>= 1);
  const man = (s >> (exp + 3)) & 0x0f;
  return ~(sign | (exp << 4) | man) & 0xff;
}

/* ─────────────────────── التقرير ─────────────────────── */

function report() {
  if (txTimer) clearInterval(txTimer);
  console.log('\n── تقرير المحاكي ──');
  console.log(`  حزم أرسلناها للتطبيق : ${txPackets}`);
  console.log(`  حزم رجعت من التطبيق  : ${rxPackets}  (${(rxBytes / 8000).toFixed(1)} ثانية)`);
  console.log(`  أول حزمة راجعة بعد   : ${firstRxAt === null ? '—' : firstRxAt + ' ملّي ثانية'}`);

  const pass = rxPackets > 100;
  console.log(pass ? '\n  ✓ الصوت يمشي في الاتجاهين\n' : '\n  ✗ ما رجع صوت كافٍ\n');
  sip.close(); rtp.close();
  process.exit(pass ? 0 : 1);
}

sip.bind(SIP_PORT, () => console.log(`  محاكي SIP على المنفذ ${SIP_PORT}`));
rtp.bind(RTP_PORT, () => console.log(`  محاكي الصوت على المنفذ ${RTP_PORT}\n`));
setTimeout(() => { console.log('\n  انتهت المهلة'); report(); }, 40000);
