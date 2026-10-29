# Upstream provenance

This is ShengQiao TS (声桥 TS), an independent modification of PocketTS.

- Source: https://github.com/KorppuJauho/pocketts
- Baseline commit: `5840b6948694ae6ec36b88af72b3694990198c8f`
- Retrieved: 2026-10-26
- License: GNU GPL v3.0; see LICENSE. Credits and translation notices remain in CREDITS.md and TRANSLATIONS.md.

Local changes: independent app ID/name/vector icon, teal accents, connection guide, default push-to-talk,
review for pasted/shared invitations, disabled upstream update checks, Windows build helper, scoped server smoke test.

The Kotlin namespace remains the upstream namespace to keep the initial fork easy to maintain.
The installed application ID is `app.shengqiao.ts.debug` for debug builds and `app.shengqiao.ts` for release builds.
This app is not affiliated with TeamSpeak Systems GmbH. TeamSpeak is its owners' trademark.

The base voice, chat, channel, file and WebRTC implementations are upstream code, not newly implemented by this fork.
Distribute the corresponding source and license with a distributed build, following LICENSE.
