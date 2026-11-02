package io.github.korppujauho.pocketts.proto

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Isolated local server only; synthetic identities and tag, no account or live service. */
class ChatTagTransportResearchTest {
    private class Peer : TsClient.Listener {
        val commands = CopyOnWriteArrayList<Command>()
        @Volatile var connected = false
        @Volatile var stopped = false
        val client = TsClient(this) { println("PEER_LOG " + it) }
        override fun onConnected(clientId: Int) { connected = true }
        override fun onCommand(cmd: Command) { commands.add(cmd) }
        override fun onDisconnected(reason: String?) { stopped = true }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (!predicate()) {
                assertTrue(!stopped, "$label disconnected")
                assertTrue(System.nanoTime() < deadline, "$label timed out")
                Thread.sleep(50)
            }
        }
    }
    @Test fun testSyntheticTagPropagation() {
        assumeTrue(System.getenv("LOCAL_TAG_RESEARCH") == "1")
        val a=Peer()
        val b=Peer()
        try {
            a.client.connect(TsClient.Options("127.0.0.1",9987,Identity.generate(8),"TagResearchA"))
            a.await("sender connect") { a.connected }
            b.client.connect(TsClient.Options("127.0.0.1",9987,Identity.generate(8),"TagResearchB"))
            b.await("observer connect") { b.connected }
            val id=a.client.clientId.toString()
            b.await("observer sees sender") { b.commands.any { it.name == "notifycliententerview" && it.groups.any { g -> g["clid"] == id } } }
            val value=Json.write(mapOf("tag" to "example@myteamspeak.com"))
            a.client.sendCommand(Commands.build("clientupdate", "client_user_tag" to value, "client_meta_data" to value, "return_code" to "quiet-myts-tag"))
            a.await("command receipt") { a.commands.any { it.name == "error" && it["return_code"] == "quiet-myts-tag" } }
            val receipt=a.commands.first { it.name == "error" && it["return_code"] == "quiet-myts-tag" }
            println("RESEARCH update_receipt_code=" + receipt["id"])
            Thread.sleep(1500)
            val events=b.commands.filter { it.name == "notifyclientupdated" }.flatMap { it.groups }.filter { it["clid"] == id }
            println("RESEARCH observer_event_user_tag_match=" + events.any { it["client_user_tag"] == value })
            println("RESEARCH observer_event_metadata_match=" + events.any { it["client_meta_data"] == value })
            a.client.sendCommand(Commands.build("clientgetvariables","clid" to a.client.clientId,"return_code" to "research-self"))
            b.client.sendCommand(Commands.build("clientgetvariables","clid" to a.client.clientId,"return_code" to "research-peer"))
            a.await("sender query") { a.commands.any { it.name=="error" && it["return_code"]=="research-self" } }
            b.await("observer query") { b.commands.any { it.name=="error" && it["return_code"]=="research-peer" } }
            for ((label,peer) in listOf("self" to a,"peer" to b)) {
                val rows=peer.commands.filter { it.name=="clientvariables" }.flatMap { it.groups }
                println("RESEARCH " + label + "_query_code=" + peer.commands.first { it.name=="error" && it["return_code"]=="research-"+label }["id"])
                println("RESEARCH " + label + "_query_user_tag_match=" + rows.any { it["client_user_tag"]==value })
                println("RESEARCH " + label + "_query_metadata_match=" + rows.any { it["client_meta_data"]==value })
            }
        } finally { a.client.close(); b.client.close() }
    }
}
