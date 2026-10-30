# Testing PocketTS by hand

What the automatic tests can't cover: real voice, pictures, video and how it behaves on a phone. Tick each item
and note anything odd: what you did, what happened, roughly when. Test builds send their log to the development
machine, so the time is enough for the log to be found.

**Where each test happens:**
- **Phone**: PocketTS on the Android phone.
- **PC**: the TeamSpeak app on a computer, on the same server.
- **2nd phone**: a second phone with PocketTS (optional).

Ticked items have been tested on the phone (Samsung Galaxy A56, Android 16) with the test builds of 20 and 21 October 2026.

Use the same server for all of them. A test server is fine, except that some things (pictures, streams) need
permissions guests may not have.

## 1. Install and connect (Phone)
- [x] The test build installs. Remove an older test build first if Android says the package conflicts.
- [x] Connect with a nickname and the server's **name** (no port): it connects.
- [x] Connect with **address:port**.
- [x] A wrong server password shows the server's refusal, and the app stays on the connect screen.
- [x] Your name in the channel tree has the **YOU** badge, and your channel is outlined in blue.

## 2. Channels (Phone + PC)
- [x] Phone: tap another channel to move there. PC: you see the phone move.
- [x] PC: move into the phone's channel. Phone: the chat shows "… joined coming from …".
- [x] A password-protected channel asks for the password: the right one lets you in, a wrong one is refused.
      (Fixed after the 20 October test: the right password was refused too.)
- [x] Spacers (if the server has any) look like they do in the TeamSpeak app.
- [x] Channel banners (if the server has any) sit like in the TeamSpeak app: from the left, centred up and down.
- [x] Icons and group badges match what the TeamSpeak app shows.
- [x] Tap a spacer channel (on a server that uses one as a channel, such as for AFK): nothing happens. Turn on
      Settings › General › "Allow joining spacer channels": a tap now joins it, also after you've been in other
      channels.
- [x] Channel banners fill their whole row (no empty strip under or beside them), aren't stretched, and fade out
      on the right.
- [x] Join a password-protected channel with "Remember the password" ticked. Go elsewhere and tap it again: it joins
      straight away, without asking. Also after reconnecting.
- [x] Tick "Remember the password" with a wrong password: the "invalid password" warning shows and the password is
      asked again (ticked). The right one joins, and from then on it joins without asking.
- [x] Change the channel's password on the PC: tapping the channel shows the warning and asks for the new one.
- [x] Settings › Advanced › Channel passwords › Forget saved passwords: the channel asks for its password again.
- [x] As a server admin (allowed to skip channel passwords): a password-protected channel joins without asking.
      As a normal user, it asks, with no warning first.
- [ ] Settings › UI › Show admin functions, as a server admin: someone's menu shows Move, Kick and Ban. Move them
      into a password-protected channel (no password asked), kick them from the channel (they land in the default
      channel and see the reason), kick them from the server, and ban them for 10 minutes (they're removed and can't
      come back until it ends; the PC's ban list shows it, and removing it there lets them back).
- [ ] The same as a normal user: each says "You don't have permission to …". With the setting off, no admin row.

## 3. Hearing (Phone + PC)
- [x] PC talks: the phone plays it, and the speaker's name glows blue with a green ring.
- [x] Phone sound button off: nothing plays, and the PC shows the phone's sound as off.
- [x] Tap the PC user on the phone and change "Volume for you": it gets quieter or louder.
- [x] Settings › Playback › voice volume, and "Play test sound".
- [x] **Screen off for 5 minutes** while the PC talks now and then: the phone keeps playing.
- [x] Another app open (for example a browser) for 5 minutes: still playing.

## 4. Talking (Phone + PC)
- [x] First connect: the phone asks for the microphone. Allow it.
- [x] **Voice activation**: talk on the phone, and the PC hears it. The phone shows you glowing while you talk.
- [x] Settings › Capture: the level bar moves when you talk. Move the activation level and check it cuts out background noise.
- [x] **Push-to-talk**: hold the round floating button, and the PC hears it only while you hold.
- [x] Push-to-talk with the **"Hold to talk" button** in the control bar.
- [x] Push-to-talk with the **volume buttons** (Settings › General).
- [x] **Talk button over other apps** (Settings › General, push-to-talk mode): Android asks to allow it. Go to another
      app: the round button stays on screen. Hold it, and the PC hears you. Drag it somewhere else: it stays there.
      Back in the app, it goes away.
- [ ] **Headset button** (Settings › General, push-to-talk mode): press the button on wired headphones, then on
      Bluetooth ones: the PC hears you until you press again. Try it with the screen off too. With the option off,
      the button plays and pauses music again.
      (Not tested yet: needs headphones with a button.)
- [x] **Continuous transmission** works.
- [x] Mic button off: the PC hears nothing and shows the phone's microphone as off.
- [x] **Echo cancellation on**, loudspeaker: the PC doesn't hear its own voice back.
- [x] Echo cancellation **off**, with headphones: sound quality is good.
- [x] Wired or Bluetooth headset: voice goes to and from the headset.
- [x] **Screen off** while talking with voice activation: the PC still hears you.

## 5. Chat (Phone + PC)
- [x] Channel message from the phone: the PC sees it, and the other way round.
- [x] Message with a link: the link can be tapped. A link to a picture shows the picture.
- [x] **Bold** text (`[b]` from the PC, or `**bold**`) shows bold.
- [x] Private message: on the phone, tap the PC user › Chat. The PC gets it, and its reply arrives in the phone's @tab.
- [x] Unread red dot on a tab, and on "Chat", when a message arrives in a tab you're not looking at.
- [x] Read it (open the tab): the dots go away and stay away. People joining or leaving don't make a dot.
- [x] **Poke**: the PC pokes the phone and a card appears. Poke the PC from the phone's person menu.
- [x] With the app in the background, a private message and a poke come as **notifications**.
- [x] Tap the server name: server info (welcome message, users, channels, website).
- [x] **Chat history**: write in the channel and privately with the PC, and let the PC join and leave. Disconnect,
      connect to the same server again: the channel's lines (with the join and leave lines) are back. When the PC
      user writes privately again, or you open a private chat with them, the old private lines are there too.
- [x] Swipe the app away while connected, open it and connect: the last lines are still there.
- [x] Another server: its chat starts empty (each server keeps its own).
- [x] The next day: yesterday's lines show the day after the time ("21:36 - Mon").
      (Works; the day is in the phone's language, e.g. Finnish.)
- [x] Close a private chat's tab and open it again from the person's menu: its lines are still there.
- [x] Settings › General › Clear chat history › Clear: the chat on screen empties, and after reconnecting it stays empty.

## 5b. Sounds (Phone + PC)
- [x] Settings › Playback › Sounds for events is on, the volume slider and "Play a sound" work.
- [x] Sounds: you connect and disconnect, you switch channel, the PC joins and leaves your channel, a message comes
      in and goes out, the PC pokes you, the microphone and sound buttons, a stream in your channel starts and stops.
- [x] Sound button off: no event sounds (only the sound button's own click).
- [x] Sounds for events off: none at all.

## 6. Pictures (Phone + PC)
Sending pictures needs a server whose file transfer works for you (and the permission to upload).

- [x] Avatars from the PC users show in the tree, the chat and the person menu.
- [x] Settings › General › Profile picture › Change: the PC shows the new avatar. Remove: it's gone.
- [x] PC sends a picture in channel chat: the phone shows it. Tap it for full screen and pinch to zoom.
- [x] Phone sends a picture (Choose a picture) and the PC sees it.
- [x] Phone sends a photo (Take a photo): it's the right way up on the PC.
- [ ] A GIF from the phone moves on the PC.
- [x] Attach a picture: it shows above the message box with an X to remove it. Type a few words and tap Send: the
      picture and the words under it arrive as one message, on the PC too.
- [x] With the camera not allowed yet (Android settings › Apps › PocketTS › Permissions), choose "Take a photo":
      Android asks for the camera first, and the app doesn't close. Saying no shows a short note instead.
- [x] Attach a picture, then tap the message box: with the keyboard open, the buttons and the switch go away, the
      picture gets smaller and the text you type stays fully visible. Close the keyboard: everything is back.
- [x] The PC pastes a picture into the channel chat (TeamSpeak's own picture sharing): the phone shows one line,
      "TeamSpeak images aren't supported in PocketTS", not a long string.
- [x] A picture sent from the phone is in the channel's files under /PocketTS (the PC's file browser).

## 7. Streams (Phone + PC)
- [x] PC shares its screen in the phone's channel: the phone shows the stream mark and **WATCH**.
- [x] WATCH: the PC's stream shows in a blue box on the server screen (like your own large preview), with its sound.
- [x] The sound button: on/off and the volume slider work.
- [x] Full screen: a wide stream turns the app sideways (even with auto-rotate off), a tall one stays upright. Tap
      for the buttons; leave full screen and the app turns back.
- [x] Full screen shows the whole picture, with black bars where its shape differs from the screen's (nothing cut
      off). The same in the blue box and the small window.
- [x] While watching, go to the home screen: the stream keeps playing in a **small window** over other apps. Move it,
      tap it to open the app again. Close it with its ×: watching stops (the PC sees one viewer fewer).
- [x] The small-window button in the player's top bar does the same.
- [x] The PC stops sharing: the phone's player says it ended and closes.
- [x] Phone shares its **camera** (Share › Camera): the PC can watch it.
- [x] While sharing the camera, **Flip** switches between the front and back camera.
- [x] Phone's auto-rotate **off**, sharing the camera: hold the phone sideways. The PC's picture turns upright after
      a moment.
- [x] Tap the rotate button (it becomes a yellow lock): turning the phone no longer turns the picture. The lock is
      remembered for the next share.
- [x] With the lock on, turning the phone: your preview still looks like a camera's viewfinder (no sudden flip).
- [ ] **Both cameras** (Share › Camera › Both, only shown if the phone can do it; not on the Galaxy A56): the PC
      sees the back camera with the front camera small in the corner. **Swap** switches them. Turning the phone keeps both upright.
- [x] HD and data saver both work.
- [x] 1080p (Full HD) works, and the PC's picture is sharper than at 720p.
- [x] The share bar: tap your small picture: it grows inside the bar (at most half the screen). Tap again: small.
- [x] Small picture: "Camera", the eye and the number of viewers on one line, on the right above the buttons.
- [x] Auto-rotate **off**, sharing the camera, phone held sideways: the round buttons' icons turn to stay upright
      (the buttons stay where they are).
- [x] The same: your preview is the right way up to you, with the lock on and off.
- [x] Phone shares its **screen** (Share › Screen, then allow): the PC can watch it.
- [x] Phone screen off while sharing the camera: the PC keeps seeing it.
- [x] On mobile data instead of Wi-Fi (needs a server reachable from the internet: the test server is only on the
      home network). If it doesn't connect, a relay (TURN) server may be needed (Settings › Advanced).
- [ ] Optional, 2nd phone: phone ↔ phone streams.
- [x] Share the front camera: your preview isn't stretched, and it shows the same as the PC (not mirrored).
- [x] Share the front camera and tap the small preview to make it big: not stretched. Tap again: small, not stretched.
- [x] Share the camera with "Stream sound" ticked: the PC shows the stream has sound and hears the phone's
      surroundings with the picture. The share bar shows a microphone.
- [x] With stream sound on: talk in the channel (voice activation or push-to-talk): the stream goes quiet meanwhile
      (the bar's microphone is crossed out), so the PC doesn't hear you twice.
- [x] With stream sound on: the mute button mutes the stream's sound too; unmuting brings it back.
- [x] Without "Stream sound": the PC shows no sound for the stream and hears nothing from it.
      (Fixed 21 Oct: it carried the microphone before.)
- [x] The channel voice still works normally while a stream with sound is on (both use the microphone), with
      echo cancellation on and off: voice activation reacts, and the stream hears you from as far as the voice does.
- [ ] Share the front camera: the share bar has a sun button. On: the screen goes to full brightness with a white
      frame around it, and the buttons still work through it. Off, switching to the back camera or stopping: back to
      normal brightness, no frame.
- [ ] While sharing or watching a stream, the screen doesn't go dark by itself.

## 8. Favourites and settings (Phone)
- [x] When connecting, tick "Save to favourites": it shows in the Favourites list.
- [x] The edit button (pencil) on a favourite: name, nickname there, password, connect automatically.
- [x] Connect automatically (in the edit window): close the app (swipe it away), open it, and it connects by itself.
- [x] In the edit window, Delete is on the left of Cancel and Save, and removes the favourite.
- [x] Edit window › Advanced › File transfer port: set the test server's port. Avatars and pictures load there.
      (The test server's file transfer needs the PC's firewall to let the phone in on that port.)
- [x] Server screen header: Disconnect, a gap, then the star and the gear on the right.
- [x] On the server: ★ menu › switch to another favourite.
- [x] Settings › Nickname: the PC sees the new name.
- [x] Tap yourself › Set away with a message: the PC shows you away. Then I'm back.
- [x] Popups (channel password, favourite, poke, share, server info, identity import) and the person menu have the
      new rounded look, with pill buttons.
- [x] Settings: the tab names (General, Playback, Capture, Advanced) each fit on one line.
- [x] A warning (for example a wrong channel password) has its own background and a close button, and goes away by
      itself after a few seconds.
- [x] Settings › Advanced › Identity › Export: a warning first, then Android asks where to save `pocketts-identity.ini` (no
      sharing to apps). Import it again (Choose a file): no complaint, and the identity field hides what's pasted.
- [x] Settings › Advanced › Identity › Import › Choose a file…: pick the .ini file the TeamSpeak app on the PC exported. It says
  "Imported"; after reconnecting, the PC sees you with that identity's unique id (the same as the PC's own).
- [ ] Settings › Advanced › Security level shows your level. Raise level: pick a level a few above it, see the
      estimate, Raise: the level counts up and ends in "Done". Raise again to a high level and Stop: it keeps the level
      reached, the badge shows it, and the server still knows you (same unique id).
- [ ] On the test server, set the needed security level above yours (`serveredit
      virtualserver_needed_identity_security_level=…`): connecting offers to raise it; Raise connects when it gets
      there. Cancel instead: "Not connected: the server needs identity security level …". Set it back afterwards.
- [x] Install this build over an older one (don't uninstall first): the server still knows you (same unique id), and
  the remembered server password, the favourites' passwords and the relay password (Settings › Advanced) are all still there.
- [ ] Optional, when moving to a new phone with Android's "copy apps and data": the favourites, nickname and settings
  come along, the passwords don't, and the new phone has a new identity unless you import the exported one.
- [x] Settings fill almost the whole screen; each tab needs little scrolling, and the checkboxes are still easy to tap.
- [x] Settings › General › Profile picture › Change: the PC (TeamSpeak 6) shows the new picture, also after it
      reconnects.
- [x] Settings › Advanced › Identity shows Wipe, Export and Import (no dots). Wipe warns first; Cancel keeps it.
      Wipe, then reconnect: the server sees a new person (new unique id, no groups).
- [ ] Settings › General › Keep the screen on: connected, the screen stays on; off, it goes dark as usual.
- [ ] The reworked sounds (switching channel, being moved, joining and leaving, messages, streams) sound right on
      the phone.
- [x] The release APK (`scripts/dev.sh release`) installs next to the test build and works: connect, talk, chat, a
      picture, a stream (watch and share), favourites, settings. Settings › General has "Check for updates".
