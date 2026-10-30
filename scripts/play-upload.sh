#!/usr/bin/env bash
# Uploads a signed app bundle to a Google Play track with the Play Developer API (used by the release workflow).
#
#   scripts/play-upload.sh BUNDLE.aab "release notes"
#
# PLAY_SERVICE_ACCOUNT_JSON  the service account's key (JSON); without it nothing is uploaded
# PLAY_TRACK                 the track, by default "alpha" (the closed test)
# PLAY_RELEASE_STATUS        "draft" (the default: Play Console's "Send for review" stays a person's step)
#                            or "completed" (rolled out to the track's testers once Google has reviewed it)
#
# Only curl, openssl and jq: the service account signs a token request itself (a JWT), no Google libraries.
set -euo pipefail

PACKAGE=io.github.korppujauho.pocketts
API=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE
UPLOAD=https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PACKAGE
BUNDLE="${1:?Usage: scripts/play-upload.sh BUNDLE.aab \"release notes\"}"
NOTES="${2:-}"
TRACK="${PLAY_TRACK:-alpha}"
STATUS="${PLAY_RELEASE_STATUS:-draft}"

if [ -z "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then
  echo "No Play service account set up: the bundle isn't uploaded (upload it in Play Console)"
  exit 0
fi

KEYS=$(mktemp -d)
trap 'rm -rf "$KEYS"' EXIT
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# A request; when Google says no, its answer goes to the log and the script stops
call() {
  local body
  if ! body=$(curl -sS --fail-with-body "$@"); then
    echo "::error::Google Play: $body" >&2
    exit 1
  fi
  printf '%s' "$body"
}

# An access token for the service account
EMAIL=$(jq -r .client_email <<< "$PLAY_SERVICE_ACCOUNT_JSON")
TOKEN_URI=$(jq -r '.token_uri // "https://oauth2.googleapis.com/token"' <<< "$PLAY_SERVICE_ACCOUNT_JSON")
jq -r .private_key <<< "$PLAY_SERVICE_ACCOUNT_JSON" > "$KEYS/key.pem"
NOW=$(date +%s)
HEADER=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
CLAIMS=$(jq -cn --arg iss "$EMAIL" --arg aud "$TOKEN_URI" --argjson iat "$NOW" \
  '{iss: $iss, scope: "https://www.googleapis.com/auth/androidpublisher", aud: $aud, iat: $iat, exp: ($iat + 600)}' | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$CLAIMS" | openssl dgst -sha256 -sign "$KEYS/key.pem" | b64url)
TOKEN=$(call "$TOKEN_URI" \
  -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer -d "assertion=$HEADER.$CLAIMS.$SIGNATURE" | jq -r .access_token)
AUTH=(-H "Authorization: Bearer $TOKEN")

# An edit: the bundle and the track's new release go in together, and count only when committed
EDIT=$(call -X POST "${AUTH[@]}" "$API/edits" | jq -r .id)
VERSION_CODE=$(call -X POST "${AUTH[@]}" -H "Content-Type: application/octet-stream" \
  --data-binary "@$BUNDLE" "$UPLOAD/edits/$EDIT/bundles?uploadType=media" | jq -r .versionCode)
echo "Uploaded version $VERSION_CODE"

RELEASE=$(jq -n --arg code "$VERSION_CODE" --arg status "$STATUS" --arg notes "$NOTES" --arg track "$TRACK" '{
  track: $track,
  releases: [{versionCodes: [$code], status: $status} + (if $notes == "" then {} else {releaseNotes: [{language: "en-US", text: $notes}]} end)]
}')
call -X PUT "${AUTH[@]}" -H "Content-Type: application/json" -d "$RELEASE" \
  "$API/edits/$EDIT/tracks/$TRACK" > /dev/null

# A draft isn't sent for review; a completed release is, unless the app's changes are sent by hand in Play Console.
# Asking not to send them works only with Play Console's managed publishing on; without it Play refuses the question
# (its changes go for review by themselves), so the edit is then committed without it.
NOT_SENT=$([ "$STATUS" = draft ] && echo true || echo false)
if ! body=$(curl -sS --fail-with-body -X POST "${AUTH[@]}" "$API/edits/$EDIT:commit?changesNotSentForReview=$NOT_SENT"); then
  if [ "$NOT_SENT" = true ] && grep -q "changesNotSentForReview must not be set" <<< "$body"; then
    echo "Managed publishing is off: committing as Play Console does"
    call -X POST "${AUTH[@]}" "$API/edits/$EDIT:commit" > /dev/null
  else
    echo "::error::Google Play: $body" >&2
    exit 1
  fi
fi
echo "Version $VERSION_CODE is on the $TRACK track as $STATUS"
