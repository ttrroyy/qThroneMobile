package app.rawline.mobile.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.rawline.mobile.aidl.ICoreService
import app.rawline.mobile.ktx.app
import kotlinx.coroutines.CompletableDeferred

// Main-process client of the :bg CoreService; each call binds for its own duration.
object CoreServiceClient {

    private class Connection : ServiceConnection, IBinder.DeathRecipient {
        val service = CompletableDeferred<ICoreService>()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            runCatching { binder.linkToDeath(this, 0) }
            service.complete(ICoreService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun binderDied() = died()

        private fun died() {
            service.completeExceptionally(IllegalStateException("core service died"))
        }
    }

    private suspend fun <T> withService(block: suspend (ICoreService) -> T): T {
        val connection = Connection()
        val bound = app.bindService(
            Intent(app, CoreService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        if (!bound) {
            runCatching { app.unbindService(connection) }
            error("cannot bind core service")
        }
        try {
            return block(connection.service.await())
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }

    /** Binds for the duration of [block]; for one-shot calls such as `groupAction` or `warpRegister`. */
    suspend fun <T> call(block: suspend (ICoreService) -> T): T = withService { service -> block(service) }

    /**
     * Keeps a binding until the returned function is called; [onConnected] runs on every (re)connection,
     * [onDisconnected] when the :bg process dies. For long-lived listeners such as test sessions and subscription callbacks.
     */
    fun bindPersistent(onConnected: (ICoreService) -> Unit, onDisconnected: () -> Unit = {}): () -> Unit {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) =
                onConnected(ICoreService.Stub.asInterface(binder))

            override fun onServiceDisconnected(name: ComponentName?) = onDisconnected()
        }
        val bound = app.bindService(Intent(app, CoreService::class.java), connection, Context.BIND_AUTO_CREATE)
        return { if (bound) runCatching { app.unbindService(connection) } }
    }

}
