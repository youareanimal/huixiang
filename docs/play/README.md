# Google Play

How PocketTS gets onto Google Play. The texts are in [listing.md](listing.md), the form answers in
[console-answers.md](console-answers.md), and the graphics in `docs/screenshots/play/`.

## The Play version

- **Same app, same key.** The Play version has the same app ID (`io.github.korppujauho.pocketts`) and is signed with
  the same release key as the GitHub version, so people can move between them without uninstalling.
- **An app bundle.** Play takes an `.aab`, not an APK. The `play` build type (`:app:bundlePlay`) is the release with
  one difference: no GitHub update check, because Play updates it.
- **Built by the release workflow.** Pushing a `v*` tag builds `PocketTS-<version>-play.aab` alongside the APK, signs
  it, records an attestation, and keeps it with the run: Actions › Release › the run › Artifacts (for 90 days).

## Setting up (once)

1. **Create the app** in Play Console: name PocketTS, default language English (United States), App, Free.
2. **App signing with our own key.** On the first upload, Play asks how to sign the app. Choose to use your own key,
   "Export and upload a key from Java keystore". Download the encryption tool (`pepk.jar`) and the encryption key
   (`encryption_public_key.pem`) it offers, then, in WSL:
   ```bash
   scripts/dev.sh play-key ~/Downloads/pepk.jar ~/Downloads/encryption_public_key.pem
   ```
   This asks for the release key's password and writes `pocketts-play-signing-key.zip` next to the `.pem`, encrypted
   so only Google can open it. Upload that zip, then delete it. The release key itself is also the upload key, so
   later bundles are accepted as they are.
3. **Fill in App content** with [console-answers.md](console-answers.md), and the **Main store listing** with
   [listing.md](listing.md) and the graphics.

## Closed test (12 testers, 14 days)

New personal developer accounts must run a closed test before the app can go public: at least 12 testers, opted in
for the last 14 days in a row. Testers who leave and come back start their 14 days again.

1. Testing › Closed testing › create a track, and add testers: an email list, or a Google Group they join.
2. Upload the bundle from the latest release run, with the testers' release notes from [listing.md](listing.md).
3. Send the testers the opt-in link. They accept it, then install PocketTS from Play with the same Google account.
   Testers with the GitHub version can install over it (same app and key).
4. Keep releasing as usual: each release's bundle goes to the closed track. New builds don't restart the 14 days.
5. After 14 days with 12 or more testers, apply for production access (Dashboard). Google's review usually takes up to
   a week.

## Each release on Play

With the automatic upload set up (below), the release workflow puts each release's bundle on the closed test track
as a draft, with this version's changelog headlines as its release notes. Then, in Play Console › Publishing
overview, check it and "Send for review". Don't send a new version while the previous one is still in review.

Without it (the workflow says so and skips the step):

1. Make the GitHub release as usual (see DEVELOPING.md, "Releasing").
2. Download the bundle from the release run's artifacts.
3. Play Console › the track › Create new release › upload the bundle, paste the changelog lines, roll out.

`versionCode` must go up with every bundle, which the usual release steps already do.

## Automatic upload (once)

`scripts/play-upload.sh` uploads with the Play Developer API as a service account (a Google account for programs).

1. **Google Cloud Console** (console.cloud.google.com): create a project, for example `pocketts-play`, and in APIs &
   Services › Library turn on the **Google Play Android Developer API**.
2. In IAM & Admin › **Service accounts**: create one, for example `play-upload`. It needs no roles in the project.
   Open it › Keys › Add key › JSON: the key file downloads. It can publish the app: keep it like the release key.
3. **Play Console** › Users and permissions › Invite new users: the service account's email
   (`…@….iam.gserviceaccount.com`). Under App permissions add PocketTS with "Release to testing tracks" (and "View app
   information"). Invite. It can take a day before the permission works.
4. **GitHub** › Settings › Environments › `release` › Add environment secret: `PLAY_SERVICE_ACCOUNT_JSON`, the key
   file's whole content. Then delete the downloaded file.

The next release uploads its bundle as a draft. Once that has worked a few times, `PLAY_RELEASE_STATUS: completed` in
`release.yml` sends each release for review by itself.
