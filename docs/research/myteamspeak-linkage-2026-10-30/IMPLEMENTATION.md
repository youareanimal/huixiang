# Implementation status

The first implementation stage retains LoginSession fields 9, 10 and 11 as opaque, bounded protobuf messages. It preserves nested bytes, including unknown fields, instead of guessing private-key formats or reconstructing signed data.

The material survives bookmark-key success, missing-key and unlock-failure paths. It is sealed with the owning account session and retained when the same session saves chat credentials. Replacing or forgetting the account removes its persisted material. Importing chat credentials restores the sealed identity rather than constructing a reduced in-memory session. Existing sessions without these optional fields remain readable.

These bytes are unverified login material, not a voice authentication token. No changes are made to clientinit, clientupdate or updatemytsid, and no URL, email or unsigned badge data is substituted for account proof. This stage alone does not make an official TS6 client display a Huixiang user's account profile.

Still required: establish verification_sign's algorithm and exact signed bytes; key decryption/encoding and identity binding; proof, avatar and badge wire encodings; update timing and revocation handling. Static schemas and synthetic fixtures do not establish these behaviors. Do not infer expiration from a timestamp.

Validation runs only through GitHub Actions. Regression cases cover original message preservation, defensive copies, bookmark-key branches, optional malformed material, encrypted storage, session replacement, logout and legacy data. End-to-end account association remains unverified.
