package app.qthrone.mobile.bg.carrier

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguredWorkersTest {
    @Test fun eachClientReportsTheWorkersItStartsWith() {
        assertEquals(18, CarrierProcess.configuredWorkers(CarrierKind.QWDTT, """{"workers":18}"""))
        assertEquals(10, CarrierProcess.configuredWorkers(CarrierKind.CSQTT, """{"workers":10}"""))
        assertEquals(27, CarrierProcess.configuredWorkers(CarrierKind.WDTTPLUS, """{"args":["-peer","h:1","-n","27","-listen","x"]}"""))
        assertEquals(0, CarrierProcess.configuredWorkers(CarrierKind.OLCRTC, """{"workers":5}"""))
        assertEquals(0, CarrierProcess.configuredWorkers(CarrierKind.WDTTPLUS, "not json"))
    }

    @Test fun healthKeepsTheLastActiveCount() {
        val health = NativeTransportHealth()
        assertEquals(null, health.activeWorkers())
        health.accept("[СТАТИСТИКА] Активных: 7 | ↓0.00 МБ / ↑0.00 МБ")
        assertEquals(7, health.activeWorkers())
    }
}
