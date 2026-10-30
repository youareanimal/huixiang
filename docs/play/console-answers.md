# Play Console answers

Answers for the forms under Play Console › Policy and programs › App content, matching what the app does (see
[PRIVACY.md](../../PRIVACY.md)). Where a form has free text, the text to use is in a box.

## Privacy policy

```
https://github.com/KorppuJauho/pocketts/blob/main/PRIVACY.md
```

## Ads

No, the app doesn't contain ads.

## App access

Everything works without an account or login, but the app needs a TeamSpeak server to connect to. Choose "All or
some functionality is restricted" and give the reviewers a server:

```
PocketTS connects to TeamSpeak servers; no account or login is needed. To try it, enter this server address on the first screen, any nickname, and tap Connect:

Server: <address of a TeamSpeak server the reviewers may use>
Server password: <if it has one>
```

Use a server that can stay up for the review, and that you're happy for Google's reviewers to join.

## Content rating

Category: **Social or Communication**. Answers:

- Violence, fear, sexuality, language, controlled substances, gambling: **No** to all
- Users can interact or exchange content with each other: **Yes** (voice, chat, pictures, streams)
- Shares the user's current physical location with other users: **No**
- Allows users to buy digital goods: **No**
- Unrestricted access to the internet (a web browser or search engine): **No** (links in the chat open in the
  phone's own browser)

## Target audience and content

- **Target age groups:** 18 and over
- **Could the app unintentionally appeal to children:** No

## News app

No.

## Data safety

PocketTS sends data to the TeamSpeak servers the user picks, not to the developer. Google counts any data leaving the
phone as collected, so the types below are declared as collected for app functionality. The servers aren't the
developer's partners, and the user chooses them, so nothing counts as shared.

**Overview**

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **No.** TeamSpeak's voice, chat and stream
  connections are encrypted, but its file transfers (pictures) aren't.
- Which of the following methods of account creation does your app support? **My app does not allow users to create
  an account**
- Do you provide a way for users to request that their data is deleted? **No.** The developer holds no user data, and
  what the app keeps on the phone is removed with the app.

**Data types** (all: collected, not shared, not processed ephemerally, purpose **App functionality**)

| Category | Type | Required or optional | What it is |
|---|---|---|---|
| Personal info | Name | Required | The nickname others see |
| Personal info | User IDs | Required | The TeamSpeak identity (a public key) |
| Messages | Other in-app messages | Optional | Chat messages and pokes |
| Photos and videos | Photos | Optional | Pictures sent in the chat |
| Files and docs | Files and docs | Optional | Files uploaded to a channel's files |
| Photos and videos | Videos | Optional | Camera and screen streams |
| Audio | Voice or sound recordings | Optional | Voice, and a stream's sound |

Not collected: location, financial info, health, contacts, calendar, app activity, web browsing, app info and
performance (no crash reports), device or other IDs (no advertising ID).

## Advertising ID

No, the app doesn't use an advertising ID.

## Government apps, financial features, health apps

No to all.

## Foreground services

The app's foreground service keeps a connection going with the screen off. Play asks for each kind it uses: what the
user is doing, what happens if it stops, and a link to a short video showing it (a phone screen recording, uploaded
to YouTube as unlisted, works).

**Media playback**

```
While connected to a voice server, the app plays the other people's voices. The service keeps them playing with the screen off or another app open, as in a phone call. If it stopped, the user would stop hearing the channel without knowing.
```

Video: connect to a server, lock the screen, and show (on another device) that voices still play.

**Microphone**

```
While connected, the user talks to the channel by voice activation or push-to-talk, also with the screen off or another app open (for example during a game). If it stopped, the user's voice would cut out mid-conversation without them knowing.
```

Video: connect, open another app, and talk; show the channel hearing it.

**Camera**

```
The user shares their camera with the channel as a live stream, started by them from the Share window. The camera is used only while the stream is on, and keeps streaming with the screen off or another app open. If it stopped, the stream would freeze for everyone watching.
```

Video: start a camera share, switch to another app, and show the stream still live.

**Media projection**

```
The user shares their screen with the channel as a live stream, started by them from the Share window after Android asks for permission. It runs only while the stream is on. If it stopped, the stream would end for everyone watching.
```

Video: start a screen share, open another app, and show the viewers seeing it.

A single video showing all four is fine, if each part is clear.
