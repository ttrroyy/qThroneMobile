package app.rawline.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class NativeTransportHealthTest {
    @Test fun transientAuthFailureWithoutWorkersRestartsAfterGraceAndCaptchaPausesWatchdog() {
        var now=0L
        val health=NativeTransportHealth { now }
        assertEquals(NativeConnectionStage.WAITING_VK,health.accept("error_code=9 Flood control"))
        health.accept("[ГРУППА #1] Ошибка кредов: captcha attempts exhausted")
        now=89_999;assertFalse(health.needsRestart(false,true))
        now=90_000;assertFalse(health.needsRestart(true,true))
        now=179_999;assertFalse(health.needsRestart(false,true))
        now=180_000;assertTrue(health.needsRestart(false,true))
    }
    @Test fun noTrafficDoesNotMeanNoWorkersAndCsqttZeroWorkersRecovers() {
        var now=0L
        val health=NativeTransportHealth { now }
        health.accept("[CSQTT diagnostic] active=9 up=0 down=0")
        // Statistics keep arriving every few seconds although no bytes move.
        while(now<600_000) { now+=3_000;health.accept("[CSQTT diagnostic] active=9 up=0 down=0") }
        assertFalse(health.needsRestart(false,false))
        assertEquals(NativeConnectionStage.RECONNECTING,health.accept("[CSQTT diagnostic] active=0 up=0 down=0"))
        now+=90_000;assertTrue(health.needsRestart(false,false))
        assertEquals(NativeConnectionStage.CONNECTED,health.accept("[ВОРКЕР #1] [READY]"))
        assertFalse(health.needsRestart(false,false))
    }
    @Test fun silentClientWithWorkersIsRestartedLikeTheOriginalWatchdog() {
        var now=0L
        val health=NativeTransportHealth { now }
        health.accept("[СТАТИСТИКА] Активных: 9 | Трафик: 0.00 МБ | ↓0.00 МБ / ↑0.00 МБ")
        now=100_000;assertFalse("grace after start",health.needsRestart(false,false))
        now=121_000;assertTrue("no statistics for over 90 s",health.needsRestart(false,false))
        assertFalse("never during startup",health.needsRestart(false,true))
    }
    @Test fun csqttEventsRestartAfterFourSeconds() {
        var now=1_000L
        val health=NativeTransportHealth { now }
        health.accept("[CSQTT diagnostic] active=18 up=0 down=0")
        health.accept("[CSQTT diagnostic] ACTIVE_ZERO")
        now=4_999;assertFalse(health.needsRestart(false,false))
        now=5_000;assertTrue(health.needsRestart(false,false))
        val recovered=NativeTransportHealth { now }
        recovered.accept("[CSQTT diagnostic] active=18 up=0 down=0")
        recovered.accept("[CSQTT diagnostic] ACTIVE_ZERO")
        recovered.accept("[CSQTT diagnostic] active=18 up=0 down=0")
        now+=10_000;assertFalse("streams came back",recovered.needsRestart(false,false))
        val panel=NativeTransportHealth { now }
        panel.accept("[CSQTT diagnostic] SERVER_RESTART")
        now+=4_000;assertTrue(panel.needsRestart(false,false))
    }
    @Test fun repeatedWrapTimeoutsWithoutWorkersMeanWrongPassword() {
        var now=0L
        val health=NativeTransportHealth { now }
        repeat(3) { health.accept("[ГРУППА #1] WRAP_AUTH_TIMEOUT: DTLS timeout, пароль/WRAP не подтверждён") }
        now=29_000;assertFalse(health.wrongPassword(false))
        now=31_000;assertTrue(health.wrongPassword(false))
        assertFalse("not while a VK check is open",health.wrongPassword(true))
        val working=NativeTransportHealth { now }
        working.accept("[СТАТИСТИКА] Активных: 9 | Трафик: 0.00 МБ")
        repeat(3) { working.accept("WRAP_AUTH_TIMEOUT") }
        now+=60_000;assertFalse("one failed worker of a working tunnel",working.wrongPassword(false))
    }
    @Test fun csqttPublicFatalMessagesStayFatalEvenAfterTransientCaptchaErrors() {
        assertNotNull(NativeSessionPolicy.startupFailure("[CSQTT] Сервер отказал в авторизации CSQTT. Проверьте пароль"))
        assertNotNull(NativeSessionPolicy.startupFailure("Пароль CSQTT привязан к другому устройству"))
        assertNotNull(NativeSessionPolicy.startupFailure("Клиент и сервер CSQTT несовместимы"))
        assertNull(NativeSessionPolicy.startupFailure("error_code=9 Flood control"))
    }
    @Test fun solvedCaptchaIsProgressAndOnlyAFailedSolveIsTransient() {
        val health=NativeTransportHealth { 0L }
        assertEquals(NativeConnectionStage.CAPTCHA,health.accept("[КАПЧА] Решаю VK Smart Captcha автоматически (v2, попыток=2)..."))
        assertEquals(NativeConnectionStage.AUTHORIZING,health.accept("[STREAM 1] [КАПЧА] AUTO: Go v2 решил капчу"))
        health.accept("[STREAM 1] [КАПЧА] WBV: auto solve succeeded")
        assertFalse(health.transientFailure)
        health.accept("[STREAM 2] [КАПЧА] AUTO: автоцепочка не прошла, открыт ручной WebView")
        assertTrue(health.transientFailure)
    }
}
