package io.github.korppujauho.pocketts

import android.net.DnsResolver
import android.os.CancellationSignal
import io.github.korppujauho.pocketts.proto.DnsMessage
import io.github.korppujauho.pocketts.proto.ServerAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** DNS SRV lookups through Android's own resolver (the phone's DNS settings, private DNS included). */
object AndroidDns : ServerAddress.SrvLookup {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dns").apply { isDaemon = true } }

    override fun srv(name: String): List<ServerAddress.Srv> {
        val done = CountDownLatch(1)
        var answer: ByteArray? = null
        var failure: Exception? = null
        val cancel = CancellationSignal()
        DnsResolver.getInstance().rawQuery(
            null, DnsMessage.srvQuery(name), DnsResolver.FLAG_EMPTY, executor, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(a: ByteArray, rcode: Int) {
                    answer = a
                    done.countDown()
                }
                override fun onError(e: DnsResolver.DnsException) {
                    failure = e
                    done.countDown()
                }
            },
        )
        if (!done.await(5, TimeUnit.SECONDS)) {
            cancel.cancel()
            throw IllegalStateException("No answer")
        }
        failure?.let { throw it }
        return answer?.let { DnsMessage.parseSrv(it) } ?: emptyList()
    }
}
