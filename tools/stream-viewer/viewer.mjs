// The stream viewer: opens WebTS (WEBTS_URL) in a headless Chromium as "Stream viewer", watches whoever streams on the
// server (going to their channel), and writes what it sees to /out: viewer.log once a second, and a picture of the
// stream every few seconds in /out/pictures. The test page shows both (/viewer). Only for testing.
import { spawn } from "node:child_process";
import { appendFileSync, mkdirSync, readdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import path from "node:path";

const WEBTS_URL = process.env.WEBTS_URL || "http://127.0.0.1:3002/";
const OUT = "/out";
const PICTURES = path.join(OUT, "pictures");
const LOG = path.join(OUT, "viewer.log");
const PICTURE_EVERY = Number(process.env.PICTURE_EVERY || 5); // seconds
const CODEC = process.env.VIEWER_CODEC || "h264"; // h264 as the TeamSpeak app takes it, or "webts" for WebTS's choice
const KEEP_PICTURES = 120;
const DEBUG_PORT = 9223;

mkdirSync(PICTURES, { recursive: true });
const clock = (t = Date.now()) => new Date(t).toISOString().slice(11, 19);
function log(line) {
  const text = `${clock()} ${line}`;
  console.log(text);
  appendFileSync(LOG, text + "\n");
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- Chromium, driven over its DevTools protocol ---

const chrome = spawn("chromium", [
  "--headless=new", "--no-sandbox", "--disable-gpu", "--mute-audio",
  "--autoplay-policy=no-user-gesture-required", "--deny-permission-prompts", // no microphone: it only watches
  `--remote-debugging-port=${DEBUG_PORT}`, "--user-data-dir=/tmp/chromium", "--window-size=1280,900",
  "about:blank",
], { stdio: ["ignore", "ignore", "pipe"] });
chrome.on("exit", (code) => {
  log(`Chromium stopped (${code})`);
  process.exit(1);
});

async function pageSocket() {
  for (let i = 0; i < 60; i++) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json`)).json();
      const page = targets.find((t) => t.type === "page");
      if (page) return page.webSocketDebuggerUrl;
    } catch (e) {}
    await sleep(500);
  }
  throw new Error("Chromium didn't start");
}

const ws = new WebSocket(await pageSocket());
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});
let nextId = 1;
const waiting = new Map();
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.id && waiting.has(msg.id)) {
    const { resolve, reject } = waiting.get(msg.id);
    waiting.delete(msg.id);
    msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
  } else if (msg.method === "Runtime.consoleAPICalled") {
    const text = msg.params.args.map((a) => a.value ?? a.description ?? "").join(" ");
    if (/\[WebRTC\]|error/i.test(text)) log(`  page: ${text.slice(0, 300)}`);
  }
};
ws.onclose = () => {
  log("Lost Chromium");
  process.exit(1);
};
function cdp(method, params = {}) {
  const id = nextId++;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => waiting.set(id, { resolve, reject }));
}
async function run(expression) {
  const r = await cdp("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
}

await cdp("Runtime.enable");
await cdp("Page.enable");
await cdp("Page.addScriptToEvaluateOnNewDocument", { source: `window.__viewerCodec = ${JSON.stringify(CODEC)};` });
await cdp("Page.addScriptToEvaluateOnNewDocument", { source: readFileSync(new URL("./page.js", import.meta.url), "utf8") });
await cdp("Page.navigate", { url: WEBTS_URL });
log(`Opened ${WEBTS_URL} (video: ${CODEC === "h264" ? "H.264 as the TeamSpeak app" : "as WebTS chooses"})`);

// --- Once a second: what it's doing, and the stream's numbers ---

let lastPhase = "";
let last = null; // the previous second's counters
let tick = 0;
let failures = 0;

function line(phase, s, prev, frame) {
  const parts = [phase];
  if (!s) return parts.join(" | ");
  const dt = prev ? (s.time - prev.time) / 1000 : 0;
  const rate = (a, b) => (prev && dt > 0 && a != null && b != null ? ((a - b) * 8) / dt / 1000 : null);
  if (s.width) parts.push(`${s.width}x${s.height} ${Math.round(s.fps || 0)} fps`);
  const kbit = rate(s.bytes, prev?.bytes);
  if (kbit != null) parts.push(`${Math.round(kbit)} kbit/s`);
  if (s.codec) parts.push(`${s.codec.replace("video/", "")}${s.decoder ? " (" + s.decoder + ")" : ""}`);
  if (s.lost != null) parts.push(`lost ${s.lost}/${s.packets}`);
  if (s.dropped) parts.push(`dropped ${s.dropped}`);
  if (s.freezes) parts.push(`freezes ${s.freezes} (${(s.freezeTime || 0).toFixed(1)} s)`);
  // How long frames waited in the viewer before they were shown, over the last second
  if (prev && s.bufferFrames > prev.bufferFrames) {
    parts.push(`wait ${Math.round(((s.bufferDelay - prev.bufferDelay) / (s.bufferFrames - prev.bufferFrames)) * 1000)} ms`);
  }
  if (frame?.delay != null) parts.push(`DELAY ${(frame.delay / 1000).toFixed(2)} s`);
  const sound = rate(s.audioBytes, prev?.audioBytes);
  if (sound != null) parts.push(`sound ${Math.round(sound)} kbit/s` + (s.audioLevel != null ? ` level ${s.audioLevel.toFixed(2)}` : ""));
  const more = (key) => (prev && s[key] != null && prev[key] != null ? s[key] - prev[key] : 0);
  if (more("keyFrames")) parts.push(`key frame`);
  if (more("nacks")) parts.push(`asked to resend ${more("nacks")}`);
  if (more("plis")) parts.push(`asked for a key frame`);
  if (s.width && !s.webtsPicture) parts.push("no picture in WebTS");
  if (s.route) parts.push(`${s.route}${s.rtt != null ? " rtt " + Math.round(s.rtt * 1000) + " ms" : ""}`);
  if (s.state !== "connected") parts.push(s.state);
  return parts.join(" | ");
}

function savePicture(frame) {
  const name = `${new Date(frame.now).toISOString().replace(/[:T]/g, "-").slice(0, 21)}.jpg`;
  writeFileSync(path.join(PICTURES, name), Buffer.from(frame.jpeg, "base64"));
  const all = readdirSync(PICTURES).filter((f) => f.endsWith(".jpg")).sort();
  for (const old of all.slice(0, Math.max(0, all.length - KEEP_PICTURES))) unlinkSync(path.join(PICTURES, old));
}

for (;;) {
  await sleep(1000);
  tick++;
  try {
    const step = await run("window.__viewer ? window.__viewer.tick() : { phase: 'loading' }");
    const phase = step.streamer ? `${step.phase}: ${step.streamer}` : step.phase;
    const s = step.phase === "waiting" ? null : await run("window.__viewer.stats()");
    const frame = s ? await run(`window.__viewer.frame(${tick % PICTURE_EVERY === 0})`) : null;
    if (frame?.jpeg) savePicture(frame);
    if (s || phase !== lastPhase) log(line(phase, s, last, frame));
    lastPhase = phase;
    last = s;
    failures = 0;
  } catch (e) {
    // The page reloading (WebTS reconnecting) or not there yet
    if (++failures % 10 === 1) log(`(page not ready: ${e.message.split("\n")[0]})`);
  }
}
