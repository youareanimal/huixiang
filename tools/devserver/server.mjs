// The development page on the home network (scripts/dev.sh devserver up): a phone opens it to install the latest test
// build, and test builds send their log here (dist/phone.log). It also shows what the stream viewer sees (/viewer,
// scripts/dev.sh viewer up), and a clock to put on the phone's screen while sharing it, to measure the delay (/clock), and
// TeamSpeak links to the test server to tap on the phone (/links).
// Only for testing; nothing else is served.
import { createServer } from "node:http";
import { appendFileSync, createReadStream, existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

const DIST = "/dist";
const LOG = path.join(DIST, "phone.log");
const MAX_LOG_POST = 1024 * 1024;
const VIEWER = path.join(DIST, "viewer");
const VIEWER_PICTURES = path.join(VIEWER, "pictures");

const escape = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function apks() {
  return readdirSync(DIST)
    .filter((f) => f.endsWith(".apk"))
    .map((f) => ({ name: f, stat: statSync(path.join(DIST, f)) }))
    .sort((a, b) => b.stat.mtimeMs - a.stat.mtimeMs);
}

const CLOCK_PAGE = `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Clock</title>
<style>html,body{margin:0;background:#000;color:#fff;font-family:system-ui,sans-serif;text-align:center}
#code{display:flex;width:100vw;height:16vh}#code div{flex:1}#time{font:bold 14vw monospace;margin-top:12vh}
#note{font-size:4vw;color:#aaa;margin:4vh 4vw}</style>
<div id="code"></div><div id="time"></div><div id="note">Syncing with the test machine...</div>
<script>
const code = document.getElementById("code");
const blocks = [];
for (let i = 0; i < 26; i++) blocks.push(code.appendChild(document.createElement("div")));
let offset = 0;
const now = () => performance.timeOrigin + performance.now() + offset;
async function sync() {
  let best = null;
  for (let i = 0; i < 8; i++) {
    const a = performance.now();
    const server = Number(await (await fetch("/time", { cache: "no-store" })).text());
    const b = performance.now();
    if (!best || b - a < best.rtt) best = { rtt: b - a, offset: server + (b - a) / 2 - (performance.timeOrigin + b) };
  }
  offset = best.offset;
  document.getElementById("note").textContent = "Following the test machine's clock (within " + Math.ceil(best.rtt / 2) + " ms). Share your screen with this page open.";
}
sync().catch(() => {}); setInterval(() => sync().catch(() => {}), 30000);
function draw() {
  const t = Math.floor(now());
  const value = t % (1 << 20);
  let sum = 0;
  for (let n = value; n > 0; n >>>= 4) sum += n & 15;
  const bits = [1, 0];
  for (let i = 19; i >= 0; i--) bits.push((value >> i) & 1);
  for (let i = 3; i >= 0; i--) bits.push(((sum & 15) >> i) & 1);
  bits.forEach((b, i) => { blocks[i].style.background = b ? "#fff" : "#000"; });
  const d = new Date(t);
  document.getElementById("time").textContent = d.toLocaleTimeString([], { hour12: false }) + "." + Math.floor(d.getMilliseconds() / 100);
  requestAnimationFrame(draw);
}
requestAnimationFrame(draw);
</script>`;

createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");

  if (req.method === "POST" && url.pathname === "/log") {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > MAX_LOG_POST) req.destroy();
    });
    req.on("end", () => {
      appendFileSync(LOG, body.endsWith("\n") ? body : body + "\n");
      res.writeHead(204).end();
    });
    return;
  }

  if (req.method === "GET" && url.pathname.startsWith("/apk/")) {
    const name = path.basename(decodeURIComponent(url.pathname.slice(5)));
    const file = apks().find((a) => a.name === name);
    if (!file) return res.writeHead(404).end("Not found");
    res.writeHead(200, {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": file.stat.size,
      "Content-Disposition": `attachment; filename="${name}"`,
    });
    return createReadStream(path.join(DIST, name)).pipe(res);
  }

  // The stream viewer's log and pictures, newest first; the page reloads itself
  if (req.method === "GET" && url.pathname === "/viewer") {
    const lines = existsSync(path.join(VIEWER, "viewer.log"))
      ? readFileSync(path.join(VIEWER, "viewer.log"), "utf8").trimEnd().split("\n").slice(-40).reverse()
      : ["The viewer isn't running (scripts/dev.sh viewer up)"];
    const pictures = existsSync(VIEWER_PICTURES) ? readdirSync(VIEWER_PICTURES).filter((f) => f.endsWith(".jpg")).sort().reverse().slice(0, 4) : [];
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    return res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="3"><title>Stream viewer</title>
<style>body{font:14px system-ui,sans-serif;margin:12px}pre{font-size:11px;white-space:pre-wrap;word-break:break-word}
img{max-width:48%;margin:1%;vertical-align:top}</style>
<h2>Stream viewer</h2><p>Times are UTC. <a href="/clock">Clock</a> (show it while sharing your screen: the log then says
the DELAY) &middot; <a href="/">Test builds</a></p>
<div>${pictures.map((p) => `<a href="/viewer/pictures/${p}"><img src="/viewer/pictures/${p}" alt="${p}"></a>`).join("")}</div>
<pre>${escape(lines.join("\n"))}</pre>`);
  }

  if (req.method === "GET" && url.pathname.startsWith("/viewer/pictures/")) {
    const name = path.basename(decodeURIComponent(url.pathname.slice(17)));
    if (!name.endsWith(".jpg") || !existsSync(path.join(VIEWER_PICTURES, name))) return res.writeHead(404).end("Not found");
    res.writeHead(200, { "Content-Type": "image/jpeg", "Cache-Control": "no-store" });
    return createReadStream(path.join(VIEWER_PICTURES, name)).pipe(res);
  }

  // This machine's clock, for /clock
  if (req.method === "GET" && url.pathname === "/time") {
    res.writeHead(200, { "Content-Type": "text/plain", "Cache-Control": "no-store" });
    return res.end(String(Date.now()));
  }

  // A clock that follows this machine's (the viewer's) time, as text and as a row of blocks the viewer reads from the
  // stream: white, black, 20 bits of the time in milliseconds (wrapping), and a 4-bit check (stream-viewer/page.js).
  if (req.method === "GET" && url.pathname === "/clock") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    return res.end(CLOCK_PAGE);
  }

  // TeamSpeak links to the test server, to tap on the phone (a typed ts3server:// in a browser's address bar isn't opened)
  if (req.method === "GET" && url.pathname === "/links") {
    const host = (req.headers.host || "localhost").replace(/:\d+$/, "");
    const port = process.env.TS_LINK_PORT || "9989";
    const base = `ts3server://${host}?port=${port}`;
    const links = [
      ["Plain", base],
      ["With a channel (TestiKanava)", `${base}&channel=TestiKanava`],
      ["With a nickname (used only when the app has none)", `${base}&nickname=LinkUser`],
      ["With a privilege key that doesn't exist (the server refuses it)", `${base}&token=NotARealKey`],
      ["teamspeak://connect form", `teamspeak://connect?address=${host}:${port}`],
      ["Broken (no address)", "ts3server://?port=9989"],
    ];
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    return res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketTS test links</title>
<style>body{font:16px system-ui,sans-serif;margin:24px;line-height:1.5}a{font-size:1.2em}li{margin:14px 0}code{font-size:.8em;color:#666}</style>
<h1>TeamSpeak links</h1><ul>${links.map(([t, l]) => `<li><a href="${escape(l)}">${escape(t)}</a><br><code>${escape(l)}</code></li>`).join("")}</ul>`);
  }

  if (req.method === "GET" && url.pathname === "/") {
    const list = apks()
      .map((a) => `<li><a href="/apk/${encodeURIComponent(a.name)}">${escape(a.name)}</a>
        <small>${(a.stat.size / 1048576).toFixed(1)} MB, ${a.stat.mtime.toISOString().slice(0, 16).replace("T", " ")} UTC</small></li>`)
      .join("");
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    return res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketTS test builds</title>
<style>body{font:16px system-ui,sans-serif;margin:24px;line-height:1.5}a{font-size:1.2em}li{margin:12px 0}</style>
<h1>PocketTS test builds</h1><p>Tap the newest one to download, then open it to install.</p>
<ul>${list || "<li>No builds yet</li>"}</ul>
<p><a href="/viewer">Stream viewer</a> &middot; <a href="/clock">Clock</a> &middot; <a href="/links">TeamSpeak links</a></p>`);
  }

  res.writeHead(404).end("Not found");
}).listen(8686, () => console.log("Dev page on port 8686"));
