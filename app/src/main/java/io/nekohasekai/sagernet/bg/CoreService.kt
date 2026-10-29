package app.qthrone.mobile.bg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.qthrone.mobile.aidl.ICoreService
import app.qthrone.mobile.aidl.ISubscriptionCallback
import app.qthrone.mobile.aidl.ITestSessionCallback
import app.qthrone.mobile.bg.test.TestEngine
import app.qthrone.mobile.bg.test.TestSpec
import app.qthrone.mobile.group.SubscriptionQueue

// Always-bindable :bg host for the probe boxes, so the main process never loads the core.
class CoreService : Service() {

    private val binder = object : ICoreService.Stub() {

        override fun startTest(spec: TestSpec, cb: ITestSessionCallback): Int = TestEngine.start(spec, cb)

        override fun stopTests() = TestEngine.stop()

        override fun runningTestSession(): Int = TestEngine.runningSession()

        override fun refreshGroup(gid: Long, showDiff: Boolean) = SubscriptionQueue.refreshGroup(gid, showDiff)

        override fun refreshAll(onlyAllowed: Boolean) = SubscriptionQueue.refreshAll(onlyAllowed)

        override fun subscribeUrl(url: String, name: String?, autoUpdate: Boolean, allowInsecureRequests: Boolean): Long =
            SubscriptionQueue.subscribeUrl(url, name.orEmpty(), autoUpdate, allowInsecureRequests)

        override fun importUrl(url: String, gid: Long) = SubscriptionQueue.importUrl(url, gid)

        override fun importFile(path: String, gid: Long) = SubscriptionQueue.importFile(path, gid)

        override fun groupAction(gid: Long, action: String): String = SubscriptionQueue.groupAction(gid, action)

        override fun resolveProfiles(ids: LongArray?): String = SubscriptionQueue.resolveProfiles(ids?.toList().orEmpty())

        override fun registerSubscriptionCallback(cb: ISubscriptionCallback) = SubscriptionQueue.register(cb)

        override fun unregisterSubscriptionCallback(cb: ISubscriptionCallback) = SubscriptionQueue.unregister(cb)

        override fun warpRegister(tunnelType: String, proxy: String?, apiHosts: Array<String>?, useCurrent: Boolean): String =
            WarpRegistration.register(tunnelType, proxy.orEmpty(), apiHosts ?: emptyArray(), useCurrent)

        override fun genQuicSignature(domain: String?): String = QuicSignature.generate(domain.orEmpty())

        override fun captureDiagnostics(durationMs: Int, outputPath: String?): String =
            CoreDiagnostics.capture(durationMs, outputPath.orEmpty())

        override fun stopDiagnostics() = CoreDiagnostics.stop()
    }

    override fun onCreate() {
        super.onCreate()
        CoreForeground.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        CoreForeground.onStartCommand(this, startId)

    override fun onTimeout(startId: Int, fgsType: Int) = CoreForeground.onTimeout(this)

    override fun onDestroy() {
        CoreForeground.detach(this)
        super.onDestroy()
    }
}
