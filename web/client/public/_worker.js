/**
 * Cloudflare Pages Advanced Mode worker.
 *
 * Static assets are served by Pages. Dynamic API and WebSocket requests are
 * forwarded to the existing Huixiang Web Gateway so the preview does not need
 * a second gateway instance.
 *
 * Pages Preview environment variable:
 *   GATEWAY_ORIGIN=https://your-existing-web-gateway.example.com
 *
 * The gateway must allow the exact Pages preview origin in ALLOWED_ORIGINS.
 * Keep ACCESS_TOKEN configured on the gateway; it is never embedded here.
 */

const isGatewayPath = (pathname) =>
  pathname === "/ws" ||
  pathname === "/auth" ||
  pathname === "/healthz" ||
  pathname === "/files" ||
  pathname.startsWith("/files/") ||
  pathname.startsWith("/server-banner/") ||
  pathname.startsWith("/avatar/") ||
  pathname.startsWith("/cloud-avatar/") ||
  pathname.startsWith("/account/");

function getGatewayOrigin(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.username || url.password ||
        (url.pathname !== "/" && url.pathname !== "") || url.search || url.hash) {
      return null;
    }
    return url.origin;
  } catch {
    return null;
  }
}

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);

    if (!isGatewayPath(incoming.pathname)) {
      return env.ASSETS.fetch(request);
    }

    const gatewayOrigin = getGatewayOrigin(env.GATEWAY_ORIGIN);
    if (!gatewayOrigin) {
      return new Response(
        "Cloudflare Pages preview is missing a valid GATEWAY_ORIGIN (HTTPS origin required).",
        { status: 503, headers: { "Content-Type": "text/plain; charset=utf-8" } },
      );
    }

    const upstreamUrl = new URL(incoming.pathname + incoming.search, gatewayOrigin);
    const headers = new Headers(request.headers);
    headers.delete("host");

    // Preserve the browser Origin and cookies. The gateway uses Origin as an
    // allow-list check, while the browser still sees a same-origin Pages URL.
    const init = {
      method: request.method,
      headers,
      redirect: "manual",
    };
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = request.body;
    }

    return fetch(new Request(upstreamUrl, init));
  },
};
