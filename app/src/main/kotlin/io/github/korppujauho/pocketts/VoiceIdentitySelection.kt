package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.CloudFailure
import io.github.korppujauho.pocketts.proto.Identity
import io.github.korppujauho.pocketts.proto.MyTsAccount

/** A signed-in or partially restored account must never fall back to a local guest identity. */
internal fun resolveVoiceIdentity(
    account: MyTsAccount.Session?,
    hasStoredAccount: Boolean,
    accountIdentity: (MyTsAccount.Session) -> Identity,
    guestIdentity: () -> Identity,
): Identity {
    if (account != null) return accountIdentity(account)
    if (hasStoredAccount) throw CloudFailure("IDENTITY_ACCOUNT_MISSING")
    return guestIdentity()
}
