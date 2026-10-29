# Privacy policy

PocketTS is a voice chat app for TeamSpeak servers. It has no account of its own, no ads, no analytics and no crash
reporting. The developer doesn't run any server for it and receives nothing from the app.

This policy covers PocketTS from GitHub and from Google Play.

## What leaves the phone, and where it goes

PocketTS talks to the **TeamSpeak servers you connect to**, and sends them what TeamSpeak needs:

- your nickname and your TeamSpeak identity's public key, and the server and channel passwords you enter;
- your voice, while you talk;
- your chat messages, pokes and the pictures you send, and the files you upload to a channel's files;
- your camera (with your microphone's sound, if you choose) and your screen (with what the phone plays, if you
  choose), while you share them.

Each server is run by someone else, under their own rules. They see what any TeamSpeak app would show them, such as
your IP address. Choose servers you trust.

PocketTS also connects to:

- **Your network's name service (DNS)**, and a server's TeamSpeak name service (TSDNS) when it has one, to find the
  address you typed.
- **TeamSpeak's server nickname service (named.myteamspeak.com)**, only when what you type has no dot in it (a
  server nickname, such as `myclan`), to find the server it stands for. It gets that name and, like any website,
  your IP address. Normal addresses are never sent there.
- **Websites that servers and people link to.** A server's banner, avatars from myTeamSpeak, and pictures in the
  chat are loaded from where they're kept. Those sites see your IP address.
- **A relay (TURN) server for streams**, only if you set one in the settings.
- **GitHub, in the GitHub version only:** once a day it asks GitHub whether a newer release is out, unless you turn
  that off (Settings › General). Nothing about you is sent. The Google Play version doesn't do this, because Play
  updates it.

## What stays on the phone

- Your TeamSpeak identity and the passwords you save, encrypted with a key in Android's keystore that never leaves
  the phone.
- Your settings, favourite servers and per-person volumes.
- The chat history, encrypted, for 90 days.
- A cache of pictures from the chat, at most 100 MB, which Android may clear.

PocketTS doesn't use Android's cloud backup. When you move to a new phone with Android's own transfer, your settings
and favourites may come along, but your identity, passwords and chat history don't. To move your identity, export it
(Settings › Advanced) and import it on the new phone.

Pictures you save go into your gallery (Pictures/PocketTS). Pictures you share go only to the app you pick.

Removing the app removes all of this. Settings › Advanced can also let go of your identity.

## Permissions

- **Microphone:** your voice, and a camera stream's sound. Recording what other apps play during a screen share
  also needs this permission.
- **Camera:** only while you share your camera.
- **Screen sharing:** only after Android asks you, and only while you share.
- **Notifications:** pokes, private messages, and the notification that keeps the connection going.
- **Display over other apps:** only if you turn on the talk button that stays on screen.
- **Foreground service:** so voice keeps working with the screen off or another app open.

## Children

PocketTS isn't meant for children under 13.

## Changes and contact

Changes to this policy are made in this file, and its history shows every change. Questions can be asked in the
project's [issues](https://github.com/KorppuJauho/pocketts/issues). For something private, use the private reporting
described in [SECURITY.md](SECURITY.md).

Last updated: 24 October 2026.
