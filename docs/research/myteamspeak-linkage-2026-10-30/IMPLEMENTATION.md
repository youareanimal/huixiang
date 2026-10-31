# myTeamSpeak voice linkage implementation

LoginSession fields 9–11 survive login, encrypted account storage, profile refresh, and chat configuration changes. Older token-only sessions still work; they need a fresh account login to obtain voice material.

Voice auth is separate from `MyTSID_Verification_Token` (chat verification). Every `clientinit` derives the server-direction packet-type-8, generation-0, packet-id-0 key/nonce from that connection’s IV and signs `key || nonce || ASCII("MyTeamSpeakID")`. The command includes `myTeamspeakId`, `acTime`, `userPubKey`, `authSign`, `pubSign`, and `pubSignCert`. It never sends the session token, decrypted scalar, sync key, or raw LoginSession identity envelope.

The account's 32-byte private scalar is decrypted using the existing IV + AES-256 little-endian CTR + SHA-512 envelope reader. Its public key must match before signing. Signing is the scalar-key Ed25519 variant: nonce SHA-512(private scalar || message), reduced modulo L, followed by R and S. It is not JCA's seed-key format. Account creation time is byte-swapped as in the official account reader; the 33-byte account ID is retained. The first 64 public_signature bytes are pubSign and the remainder is pubSignCert.

After connection, `updatemytsdata` carries protocol-escaped raw certificate bytes (unwrapped LoginSession field 10), the original signed AvatarData message, and the signed badge list from `/user` `getSignedBadges`. Metadata fetch happens on the app worker and a service failure does not interrupt voice or discard signed avatar data. Metadata is gated by successful local proof construction and checked against the current client/account before sending. Server-side proof/signature acceptance and visual TS6 interoperability still require a real-account session.

## Evidence and checks

Official Linux beta5 archive SHA-256: `394472a25ee9c2419c44a5e71a401c21bffecf2fdcb27fbe2173d0fffd1875a4`. Native executable SHA-256: `1189672c2c290997a419100b0f29f4da95d0df9d1e744b07a764eba1e8a0944f`.

Actions run [41004024803](https://github.com/youareanimal/huixiang/actions/runs/41004024803) compared three completely synthetic scalar/public-key/message vectors with native signing routine `0x474bab0`; all signatures matched the independent formulation byte for byte. Portable curve dispatch initialization is `0x47534e0`; the harness never starts the client or opens an account session. `scripts/research/myts-native-vectors.py` reproduces the comparison in the manual research workflow.

Static trace: `0x27a9d20` parses the local bridge identity; `0x27aa1b0` creates the six voice proof fields; `0x455ce60` derives connection key/nonce; `0x27ab380` builds `updatemytsdata`; `0x2a80410` checks the encrypted private-key SHA-512 envelope.

Regression tests cover native signing vectors, encrypted scalar integrity, public-key mismatch, both handshake IV formats, fresh signatures on reconnect, actual encrypted/fragmented clientinit output, metadata encoding, optional malformed metadata, and badge-service failure. All builds and tests run through GitHub Actions. No local compilation or test execution.

## 0.5.3: unsigned creation-time correction

The initial implementation incorrectly required the byte-swapped uint64 to be positive as a signed Kotlin Long. Valid values with bit 63 set were therefore rejected before any proof could be sent. Keeping all 64 bits and formatting with Long.toUnsignedString matches the native voice formatter at 0x4761890: decimal formatting uses the "%llu" format string at 0x5fa949. The native account conversion still performs bswap at 0x2a3121f.

The earlier synthetic tests chose values below Long.MAX_VALUE and could not detect this. New vectors cover 128 -> 9223372036854775808 and 1700000128 -> 9291299199498846208, as well as signed boundaries and UINT64_MAX, and assert the actual encrypted clientinit text. The manual native comparison also invokes the official uint64 formatter for these synthetic values.

Proof construction now reports PROOF_SENT, not confirmed identity. After initserver the client queries its own client variables and reports SERVER_CONFIRMED only when client_myteamspeak_id matches the expected account. Missing, mismatched, unrelated-client, or denied query data cannot establish confirmation. The own-profile UI exposes these bounded states without logging account IDs, signatures, keys, or tokens. This is not a claim that the full real-account/TS6 avatar flow has been reproduced in CI.
