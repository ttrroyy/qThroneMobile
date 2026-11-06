package moe.matsuri.nb4a.net

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import app.rawline.mobile.SagerNet
import app.rawline.mobile.ktx.Logs
import app.rawline.core.mobile.ExchangeContext
import app.rawline.core.mobile.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch

/**
 * The core reads the answer once these calls return (each runs on a goroutine of its own, bounded by the query
 * context), so every call blocks until the resolver answers or the core cancels the query.
 */
object LocalResolverImpl : LocalDNSTransport {

    private const val RCODE_NXDOMAIN = 3

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val signal = CancellationSignal()
        val done = CountDownLatch(1)
        // A cancelled DnsResolver query never calls back, so the cancellation releases the wait itself.
        ctx.onCancel {
            signal.cancel()
            done.countDown()
        }
        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                ctx.rawSuccess(answer)
                done.countDown()
            }

            override fun onError(error: DnsResolver.DnsException) {
                val cause = error.cause
                if (cause is ErrnoException) {
                    ctx.errnoCode(cause.errno)
                } else {
                    Logs.w(error)
                    ctx.errnoCode(114514)
                }
                done.countDown()
            }
        }
        DnsResolver.getInstance().rawQuery(
            SagerNet.underlyingNetwork,
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback
        )
        done.await()
    }

    /** Only below API 29, where [raw] is false. */
    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        try {
            val answer = try {
                SagerNet.underlyingNetwork?.getAllByName(domain)
            } catch (e: UnknownHostException) {
                null
            } ?: InetAddress.getAllByName(domain)
            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
        } catch (e: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
        } catch (e: Exception) {
            Logs.w(e)
            ctx.errnoCode(114514)
        }
    }

}
