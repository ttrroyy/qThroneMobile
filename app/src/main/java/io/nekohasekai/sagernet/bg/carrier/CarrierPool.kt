package app.qthrone.mobile.bg.carrier

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** VPN and tests lease the same native session instead of connecting the same device twice. */
object CarrierPool {
    private class Entry(val plan:CarrierPlan,val interactive:Boolean,val latencyProbe:Boolean) {
        val ready=CompletableDeferred<CarrierProcess>()
        var references=0
        var process:CarrierProcess?=null
        var closing=false
        val closed=CompletableDeferred<Unit>()
    }
    private class Acquisition(val entry:Entry?=null,val owner:Boolean=false,val wait:CompletableDeferred<Unit>?=null)
    private val entries=mutableMapOf<String,Entry>()
    private val probeStartup=NativeProbeStartupGate()
    fun activePlan(key:String):CarrierPlan?=synchronized(entries) { entries[key]?.takeUnless { it.closing }?.plan }
    fun isActive(plan:CarrierPlan)=synchronized(entries) { entries[plan.key]?.let { !it.closing && it.plan===plan }==true }
    fun recoveryFailure(plan:CarrierPlan):Pair<String,Boolean>?=synchronized(entries) {
        entries[plan.key]?.takeUnless { it.closing }?.process?.recoveryFailure()
    }
    class Lease internal constructor(private val release:()->Unit):AutoCloseable {
        private val closed=AtomicBoolean()
        override fun close() { if(closed.compareAndSet(false,true)) release() }
    }
    suspend fun acquire(plan:CarrierPlan, interactive: Boolean=true,latencyProbe:Boolean=!interactive,
        onStatus:(NativeConnectionStage)->Unit={},onWorkers:(Int,Int)->Unit={ _,_ -> }):Lease {
        if(!interactive && plan.kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT,CarrierKind.WDTTPLUS) && activePlan(plan.key)==null) {
            // Do not publish a queued test entry: a user connection must not wait behind it.
            var lease:Lease?=null
            try {
                return probeStartup.prepare { acquireNow(plan,interactive,latencyProbe,onStatus,onWorkers).also { lease=it } }
            } catch(error:Throwable) {
                // Timeout/cancellation can win after acquisition but before withTimeout returns.
                lease?.close()
                throw error
            }
        }
        return acquireNow(plan,interactive,latencyProbe,onStatus,onWorkers)
    }
    private suspend fun takeEntry(plan:CarrierPlan,interactive:Boolean,latencyProbe:Boolean):Acquisition {
        while(true) {
            val choice=synchronized(entries) {
                val existing=entries[plan.key]
                val waiting=existing?.takeIf { it.closing || (interactive && !it.interactive) || (!latencyProbe && it.latencyProbe) }
                    ?: entries.values.firstOrNull { it.closing &&
                        NativeSessionPolicy.sharesRemoteSession(it.plan.kind,it.plan.privateJson,plan.kind,plan.privateJson) }
                if(waiting!=null) Acquisition(wait=waiting.closed)
                else {
                    check(existing==null || existing.plan===plan) { "Native session changed while configuration was being built; retry the test" }
                    check(existing!=null || entries.values.none {
                        NativeSessionPolicy.sharesRemoteSession(it.plan.kind,it.plan.privateJson,plan.kind,plan.privateJson)
                    }) { "Эта сессия уже используется с другими настройками; проверка пропущена, чтобы не прервать туннель" }
                    val owner=existing==null
                    val entry=existing ?: Entry(plan,interactive,latencyProbe).also { entries[plan.key]=it }
                    entry.references++
                    Acquisition(entry,owner)
                }
            }
            val wait=choice.wait
            if(wait!=null) { wait.await();continue }
            return choice
        }
    }
    private suspend fun acquireNow(plan:CarrierPlan,interactive:Boolean,latencyProbe:Boolean,onStatus:(NativeConnectionStage)->Unit,onWorkers:(Int,Int)->Unit):Lease {
        val acquisition=takeEntry(plan,interactive,latencyProbe)
        val entry=acquisition.entry!!
        val owner=acquisition.owner
        fun release() {
            val last=synchronized(entries) {
                entry.references--
                (entry.references==0 && entries[plan.key]===entry).also { if(it) entry.closing=true }
            }
            if(last) {
                // Keep the entry until STOP and child cleanup finish. A new launch of
                // this device must never overlap its previous Go/Rust process, but the
                // caller (a stop on the service's main thread) need not wait for it.
                Thread({
                    try { entry.process?.close() }
                    finally {
                        synchronized(entries) { if(entries[plan.key]===entry) entries.remove(plan.key) }
                        entry.closed.complete(Unit)
                    }
                },"carrier-close").start()
            }
        }
        try {
            if(owner) {
                val context=app.qthrone.mobile.ktx.app
                try {
                    val privateJson=NativeSessionPolicy.probeConfig(plan.kind,plan.privateJson,interactive,latencyProbe)
                    val started=CarrierProcess.start(plan.kind,java.io.File(context.applicationInfo.nativeLibraryDir),
                        java.io.File(context.cacheDir,"carriers"),android.os.Build.VERSION.SDK_INT,
                        privateJson,plan.port,plan.credentials,allowInteractions=interactive,onStatus=onStatus,onWorkers=onWorkers,
                        onCreated={ process -> synchronized(entries) { entry.process=process } })
                    synchronized(entries) { entry.process=started }
                    entry.ready.complete(started)
                } catch(error:Throwable) { entry.ready.completeExceptionally(error);throw error }
            }
            entry.ready.await()
            return Lease(::release)
        } catch(error:Throwable) { release();throw error }
    }
}
