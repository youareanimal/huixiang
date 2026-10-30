// Runs inside the WebTS page (put there before WebTS's own script). WebTS's names (latestState, socket, watchStream,
// peerConnection, watchingClid, streamVideo) are looked up when these run, so they're WebTS's by then.
(() => {
  // The viewer's nickname, before WebTS reads its settings
  try {
    if (!localStorage.getItem("ts_settings")) localStorage.setItem("ts_settings", JSON.stringify({ nick: "Stream viewer" }));
  } catch (e) {}

  let movedAt = 0;
  let askedAt = 0;

  // The test page's clock (/clock) as the phone shares it: a row of 26 black and white blocks across the screen,
  // white-black, then 20 bits of the time (milliseconds, counted on the test machine, wrapping every 2^20), then a
  // 4-bit check. Found by trying rows until one reads cleanly.
  const BLOCKS = 26;
  const WRAP = 1 << 20;
  function readClock(data, width, height) {
    for (let y = 2; y < height; y += 3) {
      const bits = [];
      for (let i = 0; i < BLOCKS; i++) {
        const x = Math.floor(((i + 0.5) * width) / BLOCKS);
        const p = (y * width + x) * 4;
        const v = (data[p] + data[p + 1] + data[p + 2]) / 3;
        if (v > 185) bits.push(1);
        else if (v < 70) bits.push(0);
        else break; // not a clear block: not the clock's row
      }
      if (bits.length !== BLOCKS || bits[0] !== 1 || bits[1] !== 0) continue;
      let value = 0;
      for (let i = 2; i < 22; i++) value = value * 2 + bits[i];
      let check = 0;
      for (let i = 22; i < 26; i++) check = check * 2 + bits[i];
      let sum = 0;
      for (let n = value; n > 0; n >>>= 4) sum += n & 15;
      if ((sum & 15) === check) return value;
    }
    return null;
  }

  const canvas = document.createElement("canvas");
  const ctx = () => canvas.getContext("2d", { willReadFrequently: true });

  // The stream's picture in a player of our own (WebTS's may not show one), from the connection's video
  let player = null;
  let playerTrack = null;
  function picture() {
    if (typeof peerConnection === "undefined" || !peerConnection) return null;
    const receiver = peerConnection.getReceivers().find((r) => r.track && r.track.kind === "video");
    if (!receiver) return null;
    if (!player) {
      player = document.createElement("video");
      player.muted = true;
      player.autoplay = true;
      player.playsInline = true;
      player.style.cssText = "position:fixed;left:0;top:0;width:64px;opacity:0.01;pointer-events:none";
      document.body.appendChild(player);
    }
    if (playerTrack !== receiver.track) {
      playerTrack = receiver.track;
      player.srcObject = new MediaStream([receiver.track]);
      player.play().catch(() => {});
    }
    return player;
  }

  // H.264 like the TeamSpeak app (VIEWER_CODEC=h264): WebTS relabels the phone's H.264 only when the offer has
  // nothing else it plays, so it's told there's nothing else
  let codecSet = false;
  function setCodec() {
    if (codecSet || window.__viewerCodec !== "h264" || typeof offerHasOtherPlayableVideo !== "function") return;
    window.offerHasOtherPlayableVideo = () => false;
    codecSet = true;
  }

  window.__viewer = {
    // One step: find a stream, go to its channel, watch it. What it's doing, for the log.
    tick() {
      setCodec();
      const s = typeof latestState === "undefined" ? null : latestState;
      if (!s || !s.connected) return { phase: "connecting" };
      const clients = s.clients || [];
      const self = clients.find((c) => c.clid === s.selfClid);
      if (!self) return { phase: "connecting" };
      const streamer = clients.find((c) => c.isStreaming && c.clid !== self.clid);
      if (!streamer) return { phase: "waiting" };
      if (streamer.cid !== self.cid) {
        if (Date.now() - movedAt > 5000) {
          movedAt = Date.now();
          socket.emit("move-channel", { cid: streamer.cid });
        }
        return { phase: "moving", streamer: streamer.nickname };
      }
      const live = peerConnection && !["failed", "closed"].includes(peerConnection.connectionState);
      if (watchingClid === streamer.clid && live) {
        return { phase: peerConnection.connectionState === "connected" ? "watching" : "connecting to the stream", streamer: streamer.nickname };
      }
      if (Date.now() - askedAt > 10000) {
        askedAt = Date.now();
        watchStream(streamer.clid, streamer.nickname);
      }
      return { phase: "asking to watch", streamer: streamer.nickname };
    },

    // WebRTC's counters for the stream being watched
    async stats() {
      if (typeof peerConnection === "undefined" || !peerConnection) return null;
      const report = await peerConnection.getStats();
      let video = null;
      let audio = null;
      let pair = null;
      report.forEach((x) => {
        if (x.type === "inbound-rtp" && x.kind === "video") video = x;
        if (x.type === "inbound-rtp" && x.kind === "audio") audio = x;
        if (x.type === "candidate-pair" && x.nominated && x.state === "succeeded") pair = x;
      });
      const local = pair && report.get(pair.localCandidateId);
      const remote = pair && report.get(pair.remoteCandidateId);
      return {
        time: Date.now(),
        state: peerConnection.connectionState,
        width: video?.frameWidth, height: video?.frameHeight, fps: video?.framesPerSecond,
        bytes: video?.bytesReceived, packets: video?.packetsReceived, lost: video?.packetsLost,
        frames: video?.framesDecoded, dropped: video?.framesDropped,
        bufferDelay: video?.jitterBufferDelay, bufferFrames: video?.jitterBufferEmittedCount,
        freezes: video?.freezeCount, freezeTime: video?.totalFreezesDuration,
        codec: video?.codecId ? report.get(video.codecId)?.mimeType : null, decoder: video?.decoderImplementation,
        audioBytes: audio?.bytesReceived, audioLevel: audio?.audioLevel,
        route: local ? `${local.candidateType}${local.relayProtocol ? "/" + local.relayProtocol : ""} -> ${remote?.candidateType}` : null,
        rtt: pair?.currentRoundTripTime,
        keyFrames: video?.keyFramesDecoded, nacks: video?.nackCount, plis: video?.pliCount,
        webtsPicture: typeof streamVideo !== "undefined" && !!streamVideo.videoWidth,
      };
    },

    // The frame on screen now: the delay read from the test page's clock, if it's in the picture, and a JPEG of it
    // (when asked) with the viewer's time and that delay written on it.
    frame(withPicture) {
      const v = picture();
      if (!v || !v.videoWidth) return null;
      const now = Date.now();
      canvas.width = v.videoWidth;
      canvas.height = v.videoHeight;
      const c = ctx();
      c.drawImage(v, 0, 0);
      const shown = readClock(c.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height);
      const delay = shown === null ? null : (((now % WRAP) - shown) % WRAP + WRAP) % WRAP;
      if (!withPicture) return { now, delay };
      const scale = Math.min(1, 960 / Math.max(canvas.width, canvas.height));
      const out = document.createElement("canvas");
      out.width = Math.round(canvas.width * scale);
      out.height = Math.round(canvas.height * scale);
      const o = out.getContext("2d");
      o.drawImage(canvas, 0, 0, out.width, out.height);
      const text = `viewer ${new Date(now).toISOString().slice(11, 22)} UTC` + (delay === null ? "" : `, delay ${(delay / 1000).toFixed(2)} s`);
      o.font = "bold 22px sans-serif";
      o.fillStyle = "rgba(0,0,0,0.75)";
      o.fillRect(0, out.height - 36, o.measureText(text).width + 20, 36);
      o.fillStyle = "#fff";
      o.fillText(text, 10, out.height - 11);
      return { now, delay, jpeg: out.toDataURL("image/jpeg", 0.75).split(",")[1] };
    },
  };
})();
