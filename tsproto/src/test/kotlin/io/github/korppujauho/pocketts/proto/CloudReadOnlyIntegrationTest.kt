package io.github.korppujauho.pocketts.proto

import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Opt-in with a private file path, never raw credentials in environment variables or logs. No sends or uploads. */
class CloudReadOnlyIntegrationTest {
    @Test fun officialAccountContactsCloudRoomsAndHistory() {
        val path = System.getenv("SHENGQIAO_ACCOUNT_TRANSFER").orEmpty()
        assumeTrue(path.isNotBlank(), "No private transfer supplied")
        val file = File(path)
        require(file.length() <= 32768)
        val account = CloudAccountTransfer.parse(file.readText())
        val myts = MyTsAccount()
        val identity = MyTsAccount.Session(account.myTsSession, account.username)
        assertTrue(myts.valid(identity), "Imported myTS session is expired; export again after desktop login")
        val contacts = myts.contacts(identity)
        val cloud = MatrixCloud(account.domain)
        // Reuse the one named probe device from research; do not create a new device for every test run.
        val session = cloud.login(account, "SHENGQIAO_READONLY_PROBE_20261026")
        val snapshot = cloud.sync(session)
        assertTrue(snapshot.cursor.isNotBlank())
        println("READ-ONLY: account valid; contacts=${contacts.size}; rooms=${snapshot.rooms.size}; encrypted=${snapshot.rooms.count { it.encrypted }}")
        val room = snapshot.rooms.firstOrNull()
        if (room != null) {
            val history = cloud.history(session, room.id)
            println("READ-ONLY: history endpoint verified; messages=${history.size}; no content logged")
        }
        // This deployment returns M_UNRECOGNIZED for Matrix logout/device deletion. Do not claim revocation.
        println("No messages, read receipts, invitations, uploads or E2EE key updates were sent")
    }
}
