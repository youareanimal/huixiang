# Cloudflare Pages preview for the monkey stage

This deploys only the web client from feature/monkey-voice-stage. The existing
Huixiang Web Gateway remains the backend; do not deploy a second gateway.

## Cloudflare Pages project

Create a Pages project connected to the GitHub repository
youareanimal/huixiang, with:

- Production branch: main
- Root directory: web/client
- Framework preset: Vite
- Build command: npm run build
- Build output directory: dist
- Preview deployments: enable feature/monkey-voice-stage (or all non-production branches)

The public/_worker.js file is copied by Vite into dist/_worker.js. It uses
Pages Advanced Mode to serve static assets and proxy the gateway routes.

## Preview environment variable

In Settings → Variables and Secrets → Preview, add:

- GATEWAY_ORIGIN = the existing web gateway's public HTTPS origin, for example
  https://web.example.com (origin only; no path and no trailing route).

Do not put the gateway access token in a Pages variable or in the frontend
bundle. Users enter the existing gateway access token in the web UI.

## Gateway allow-list

After the first preview deployment, copy its exact https://…pages.dev URL
from the Pages deployment screen. Add that exact origin to the existing
gateway's ALLOWED_ORIGINS environment variable, preserving any existing
origins, then restart the gateway service.

For example:

ALLOWED_ORIGINS=https://existing-site.example.com,https://<preview-host>.pages.dev

Keep ACCESS_TOKEN enabled. The Worker preserves the browser Origin header
so the gateway can enforce its normal allow-list. No wildcard origin is needed.

## Verify

1. Open the Pages preview URL.
2. Confirm /auth is proxied (the page should show the access-token prompt if
   the gateway requires one).
3. Enter the existing access token and connect to a TeamSpeak server.
4. Join a voice channel and verify member arrival/departure, speaking, mic mute,
   and speaker mute gestures.
5. Confirm the production main site and its domain are unchanged.

The Pages Worker proxies /ws, /auth, /healthz, /files*,
/ server-banner/*, /avatar/*, /cloud-avatar/*, and /account/*.
All other requests are served from Pages static assets.
