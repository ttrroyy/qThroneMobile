package app.qthrone.mobile.bg.carrier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class CarrierProcessTest {
    @Test fun cancelledStartupReturnsBeforePoolOwnedVkCleanup(): Unit = runBlocking {
        val root=Files.createTempDirectory("csqtt-cancel-start").toFile()
        val created=kotlinx.coroutines.CompletableDeferred<CarrierProcess>()
        val stoppedAt=java.util.concurrent.atomic.AtomicLong()
        var running:CarrierProcess?=null
        try {
            for(name in listOf("libqwdtt.so","libcsqtt_transport.so")) File(root,name).apply { writeText("fixture");setExecutable(true) }
            val job=launch {
                CarrierProcess.start(CarrierKind.CSQTT,root,File(root,"configs"),35,"{}",12345,CarrierBridgeCredentials.fresh(),
                    onCreated={ created.complete(it) }) {
                    object:Process() {
                        override fun getOutputStream()=object:java.io.OutputStream() {
                            override fun write(value:Int) { if(value==10) stoppedAt.compareAndSet(0,System.nanoTime()) }
                        }
                        override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
                        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                        override fun exitValue():Int {
                            if(stoppedAt.get()!=0L && System.nanoTime()-stoppedAt.get()>=1_200_000_000L) return 0
                            throw IllegalThreadStateException()
                        }
                        override fun waitFor()=0
                        override fun destroy() { fail("VK cleanup must keep its grace period") }
                    }
                }
            }
            running=created.await()
            val before=System.nanoTime()
            job.cancel(); job.join()
            assertTrue("A stopped startup must not await VK cleanup",System.nanoTime()-before<1_000_000_000L)
            assertEquals("The pool still owns the live process",0L,stoppedAt.get())
            running.close()
            assertTrue(stoppedAt.get()>0)
            assertTrue(File(root,"configs").listFiles().orEmpty().isEmpty())
        } finally { running?.close();root.deleteRecursively() }
    }
    @Test fun csqttConnectionAndLatencyProbeUseOnlyLegacyWithoutChangingCredentials(): Unit = runBlocking {
        for (interactive in listOf(false,true)) {
            val root=Files.createTempDirectory("csqtt-protocol-retry").toFile()
            try {
                for(name in listOf("libqwdtt.so","libcsqtt_transport.so"))
                    File(root,name).apply { writeText("fixture");setExecutable(true) }
                val source="""{"peer":"192.0.2.1:46000","password":"fixture-secret","device_id":"old-phone","hashes":["manual_hash_12345678"],"workers":18}"""
                val configuration=NativeSessionPolicy.probeConfig(CarrierKind.CSQTT,source,interactive)
                val launches=mutableListOf<app.qthrone.mobile.outbound.json.JsonObject>()
                val failure=runCatching {
                    CarrierProcess.start(CarrierKind.CSQTT,root,File(root,"configs"),35,configuration,12345,
                        CarrierBridgeCredentials.fresh(),allowInteractions=interactive) { builder ->
                        val config=app.qthrone.mobile.outbound.json.JsonInput.parseObject(File(builder.command()[2]).readText())
                        launches.add(config)
                        val output="FATAL_PROTOCOL: fixture mismatch\n"
                        object:Process() {
                            override fun getOutputStream()=ByteArrayOutputStream()
                            override fun getInputStream()=ByteArrayInputStream(output.toByteArray())
                            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                            override fun waitFor()=1
                            override fun exitValue()=1
                            override fun destroy() {}
                        }
                    }
                }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(1,launches.size)
                assertFalse(launches.single().contains("protocol_variant"))
                for (config in launches) {
                    assertEquals("fixture-secret",config.string("password"))
                    assertEquals("old-phone",config.string("device_id"))
                    assertEquals(listOf("manual_hash_12345678"),config.array("hashes").strings())
                    assertEquals(!interactive,config.bool("probe_only"))
                }
                assertTrue(launches.single().string("salt").isNotEmpty())
                assertTrue(File(root,"configs").listFiles().orEmpty().isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun csqttCanFinishVkCleanupAfterStopWithoutBeingKilled(): Unit = runBlocking {
        val root=Files.createTempDirectory("csqtt-cleanup").toFile()
        try {
            for(name in listOf("libqwdtt.so","libcsqtt_transport.so")) File(root,name).apply { writeText("fixture");setExecutable(true) }
            val stoppedAt=java.util.concurrent.atomic.AtomicLong()
            val destroyed=java.util.concurrent.atomic.AtomicBoolean()
            val failure=runCatching {
                CarrierProcess.start(CarrierKind.CSQTT,root,File(root,"configs"),35,"{}",12345,CarrierBridgeCredentials.fresh()) {
                    object:Process() {
                        override fun getOutputStream()=object:java.io.OutputStream() {
                            override fun write(value:Int) { if(value==10) stoppedAt.compareAndSet(0,System.nanoTime()) }
                        }
                        override fun getInputStream()=ByteArrayInputStream("FATAL_AUTH: rejected\n".toByteArray())
                        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                        override fun exitValue():Int {
                            if(destroyed.get() || stoppedAt.get()!=0L && System.nanoTime()-stoppedAt.get()>=1_200_000_000L) return 0
                            throw IllegalThreadStateException()
                        }
                        override fun waitFor()=0
                        override fun destroy() { destroyed.set(true) }
                    }
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue("STOP must reach the Rust wrapper",stoppedAt.get()>0)
            assertFalse("The original VK cleanup must not be cut off after one second",destroyed.get())
            assertTrue(File(root,"configs").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationAtReadyDispatcherHandoffClosesTheNativeProcess(): Unit = runBlocking {
        val root=Files.createTempDirectory("carrier-cancel-ready").toFile()
        val executor=Executors.newSingleThreadExecutor()
        val queue=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher=object:kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) { queue.add(block) }
        }
        try {
            File(root,"libqwdtt.so").apply { writeText("fixture");setExecutable(true) }
            val configs=File(root,"configs")
            val stopped=java.util.concurrent.atomic.AtomicBoolean()
            ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout=5000
                val server=executor.submit {
                    listener.accept().use { socket ->
                        socket.soTimeout=5000
                        DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                        socket.getOutputStream().write(byteArrayOf(5,-1))
                    }
                    listener.accept().use { socket ->
                        socket.soTimeout=5000
                        val input=DataInputStream(socket.getInputStream())
                        input.readFully(ByteArray(3));socket.getOutputStream().write(byteArrayOf(5,2))
                        assertEquals(1,input.readUnsignedByte())
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        socket.getOutputStream().write(byteArrayOf(1,0))
                    }
                }
                val job=kotlinx.coroutines.CoroutineScope(dispatcher).launch {
                    CarrierProcess.start(CarrierKind.QWDTT,root,configs,35,"{}",listener.localPort,CarrierBridgeCredentials.fresh()) {
                        object:Process() {
                            override fun getOutputStream()=object:java.io.OutputStream() {
                                override fun write(value:Int) { if(value==10) stopped.set(true) }
                            }
                            override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
                            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                            override fun waitFor()=0
                            override fun exitValue():Int { if(!stopped.get()) throw IllegalThreadStateException();return 0 }
                            override fun destroy() { stopped.set(true) }
                        }
                    }.close()
                }
                checkNotNull(queue.poll(5,TimeUnit.SECONDS)).run()
                // Native readiness succeeded; hold its return on the original dispatcher.
                val ready=checkNotNull(queue.poll(5,TimeUnit.SECONDS))
                job.cancel();ready.run();job.join()
                server.get(5,TimeUnit.SECONDS)
                assertTrue(stopped.get())
                assertTrue(configs.listFiles().orEmpty().isEmpty())
            }
        } finally { executor.shutdownNow();root.deleteRecursively() }
    }

    @Test fun fiftySequentialProbeProcessesFinishCleanupBeforeTheNextStart(): Unit = runBlocking {
        val root=Files.createTempDirectory("carrier-repeat").toFile()
        val executor=Executors.newSingleThreadExecutor()
        try {
            File(root,"libqwdtt.so").apply { writeText("fixture");setExecutable(true) }
            val configs=File(root,"configs")
            ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout=3000
                val server=executor.submit {
                    repeat(50) {
                        listener.accept().use { socket ->
                            socket.soTimeout=3000
                            DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                            socket.getOutputStream().write(byteArrayOf(5,-1))
                        }
                        listener.accept().use { socket ->
                            socket.soTimeout=3000
                            val input=DataInputStream(socket.getInputStream())
                            input.readFully(ByteArray(3));socket.getOutputStream().write(byteArrayOf(5,2))
                            assertEquals(1,input.readUnsignedByte())
                            input.readFully(ByteArray(input.readUnsignedByte()))
                            input.readFully(ByteArray(input.readUnsignedByte()))
                            socket.getOutputStream().write(byteArrayOf(1,0))
                        }
                    }
                }
                repeat(50) {
                    assertTrue(configs.listFiles().orEmpty().isEmpty())
                    val stopped=java.util.concurrent.atomic.AtomicBoolean()
                    val control=object:java.io.OutputStream() {
                        override fun write(value:Int) { if(value==10) stopped.set(true) }
                    }
                    val owned=CarrierProcess.start(CarrierKind.QWDTT,root,configs,35,"{}",listener.localPort,CarrierBridgeCredentials.fresh()) {
                        object:Process() {
                            override fun getOutputStream()=control
                            override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
                            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                            override fun waitFor()=0
                            override fun exitValue():Int { if(!stopped.get()) throw IllegalThreadStateException();return 0 }
                            override fun destroy() { stopped.set(true) }
                        }
                    }
                    owned.close()
                    assertTrue(stopped.get())
                    assertTrue(configs.listFiles().orEmpty().isEmpty())
                }
                server.get(5,TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow();root.deleteRecursively() }
    }

    @Test fun unavailableCallRetriesOtherHashesButServerAuthRefusalStopsImmediately() = runBlocking {
        val root=Files.createTempDirectory("carrier-hash-fallback").toFile()
        try {
            File(root,"libqwdtt.so").apply { writeText("fixture");setExecutable(true) }
            val privateJson="""{"hashes":["closed","second","third"],"password":"fixture"}"""
            val attempted=mutableListOf<String>()
            val configs=File(root,"configs")
            val error=runCatching {
                CarrierProcess.start(CarrierKind.QWDTT,root,configs,35,privateJson,12345,CarrierBridgeCredentials.fresh()) { builder ->
                    val config=app.qthrone.mobile.outbound.json.JsonInput.parseObject(File(builder.command()[2]).readText())
                    attempted.add(config.array("hashes").strings().first())
                    val line=if(attempted.size==1) "Call not found (error_code=951)" else "FATAL_AUTH: private-secret"
                    object:Process() {
                        override fun getOutputStream()=ByteArrayOutputStream()
                        override fun getInputStream()=ByteArrayInputStream((line+"\n").toByteArray())
                        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                        override fun waitFor()=0
                        override fun exitValue()=0
                        override fun destroy() {}
                    }
                }
            }.exceptionOrNull()
            assertEquals(listOf("closed","second"),attempted)
            assertEquals(NativeSessionPolicy.startupFailure("FATAL_AUTH:"),error?.message)
            assertTrue(configs.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun qwdttAndCsqttExposeServerAuthRefusalInsteadOfStartupTimeout() = runBlocking {
        val root=Files.createTempDirectory("carrier-auth-refusal").toFile()
        try {
            val native=File(root,"native").apply { mkdir() }
            File(native,"libqwdtt.so").apply { writeText("fixture");setExecutable(true) }
            File(native,"libcsqtt_transport.so").apply { writeText("fixture");setExecutable(true) }
            for(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT)) {
                val directory=File(root,kind.name).apply { mkdir() }
                val error=runCatching {
                    CarrierProcess.start(kind,native,directory,35,"{}",12345,CarrierBridgeCredentials.fresh(),1000) {
                        object:Process() {
                            override fun getOutputStream()=ByteArrayOutputStream()
                            override fun getInputStream()=ByteArrayInputStream("FATAL_AUTH: fixture-secret\n".toByteArray())
                            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                            override fun waitFor()=0
                            override fun exitValue()=0
                            override fun destroy() {}
                        }
                    }
                }.exceptionOrNull()
                assertEquals(NativeSessionPolicy.startupFailure("FATAL_AUTH:"),error?.message)
                assertFalse(error?.message.orEmpty().contains("fixture-secret"))
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun failedStartUsesPrivateFileAndRemovesIt() = runBlocking {
        val root = Files.createTempDirectory("carrier-test").toFile()
        try {
            val executable = File(root, CarrierKind.QWDTT.executable)
            executable.writeText("test fixture")
            assertTrue(executable.setExecutable(true))
            val configs = File(root, "private")
            val privateJson = "{\"password\":\"test-fixture-secret\"}"
            var launched = false
            var destroyed = false
            val control=ByteArrayOutputStream()
            val error = runCatching {
                CarrierProcess.start(CarrierKind.QWDTT, root, configs, 24, privateJson, 12345, CarrierBridgeCredentials.fresh()) { builder ->
                    launched = true
                    assertEquals(3, builder.command().size)
                    assertEquals("-config", builder.command()[1])
                    assertFalse(builder.command().any { it.contains("test-fixture-secret") })
                    assertTrue(File(builder.command()[2]).readText() == privateJson)
                    object : Process() {
                        override fun getOutputStream() = control
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                        override fun waitFor() = 1
                        override fun exitValue() = 1
                        override fun destroy() { destroyed = true }
                    }
                }
            }.exceptionOrNull()
            assertTrue(launched)
            // An already exited child needs cleanup, not another termination signal.
            assertFalse(destroyed)
            assertEquals("STOP\n",control.toString("UTF-8"))
            assertTrue(error is IllegalStateException)
            assertFalse(error?.message.orEmpty().contains("test-fixture-secret"))
            assertTrue(configs.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun csqttRejectsUnsupportedAndroidBeforeLaunching() = runBlocking {
        var launched = false
        val error = runCatching {
            CarrierProcess.start(CarrierKind.CSQTT, File("missing"), File("missing"), 24, "{}", 12345, CarrierBridgeCredentials.fresh()) {
                launched = true
                error("must not launch")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(launched)
    }

    @Test fun readinessRejectsAnUnauthenticatedLocalProxy() {
        probeServer { listener ->
            listener.accept().use { socket ->
                socket.soTimeout = 2000
                DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                socket.getOutputStream().write(byteArrayOf(5, 0))
            }
        }.let { assertFalse(it) }
    }

    @Test fun readinessRequiresSuccessfulPasswordAuthentication() {
        for (accepted in listOf(true, false)) {
            val ready = probeServer { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = 2000
                    DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                    socket.getOutputStream().write(byteArrayOf(5, -1))
                }
                listener.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    val greeting = ByteArray(3).also { input.readFully(it) }
                    assertArrayEquals(byteArrayOf(5, 1, 2), greeting)
                    socket.getOutputStream().write(byteArrayOf(5, 2))
                    assertEquals(1, input.readUnsignedByte())
                    val username = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    val password = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    assertEquals("fixture-user", String(username, Charsets.UTF_8))
                    assertEquals("fixture-password", String(password, Charsets.UTF_8))
                    socket.getOutputStream().write(byteArrayOf(1, if (accepted) 0 else 1))
                }
            }
            assertEquals(accepted, ready)
        }
    }

    private fun probeServer(serve: (ServerSocket) -> Unit): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 2000
                val server = executor.submit { serve(listener) }
                val ready = CarrierProcess.socksReady(listener.localPort,
                    CarrierBridgeCredentials("fixture-user", "fixture-password"))
                server.get(3, TimeUnit.SECONDS)
                return ready
            }
        } finally { executor.shutdownNow() }
    }
}
