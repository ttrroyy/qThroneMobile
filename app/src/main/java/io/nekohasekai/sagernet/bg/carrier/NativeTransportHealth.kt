package app.qthrone.mobile.bg.carrier

enum class NativeConnectionStage { PREPARING, AUTHORIZING, CAPTCHA, WAITING_VK, CONNECTING, CONNECTED, RECONNECTING }

/** Only public lifecycle markers are kept; URLs, hashes and captcha tokens are never status text. */
internal class NativeTransportHealth(private val minimumRecoveryPeak: Int = 9, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val startedAt = clock()
    private var zeroSince = startedAt
    private var lastStats = startedAt
    private var everActive = false
    private var statsSeen = false
    private var peak = 0
    private var zeroEventAt = 0L
    private var serverRestartAt = 0L
    private var wrapTimeouts = 0
    private var workers: Int? = null
    var transientFailure = false
        private set
    var credentialsFailed = false
        private set
    private var stage = NativeConnectionStage.PREPARING
    private val captchaFailure = Regex("капча не решена|ошибка решения капчи|автоцепочка не прошла|captcha (?:chain )?failed|captcha attempts exhausted|webview captcha (?:failed|timed out)", RegexOption.IGNORE_CASE)
    private val captchaSolved = Regex("Smart Captcha решена|решила капчу|решил капчу|Капча решена|solve succeeded", RegexOption.IGNORE_CASE)
    private val active = Regex("(?:Активных:\\s*|\\bactive=)([0-9]+)", RegexOption.IGNORE_CASE)

    @Synchronized fun accept(line: String): NativeConnectionStage {
        active.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { count ->
            if (count == 0 && workers != 0) zeroSince = clock()
            workers = count
            lastStats = clock()
            statsSeen = true
            if (count > 0) { everActive = true; wrapTimeouts = 0 }
        }
        workers?.let { peak = maxOf(peak, it) }
        // CSQTT's own events, the ones the original app restarts on.
        if (line.contains("[CSQTT diagnostic] ACTIVE_ZERO")) { workers = 0; zeroEventAt = clock() }
        if (line.contains("[CSQTT diagnostic] SERVER_RESTART")) serverRestartAt = clock()
        // A DTLS handshake the server never answers: the password (WRAP key) does not match.
        if (line.contains("WRAP_AUTH_TIMEOUT", true)) { if ((workers ?: 0) > 0) wrapTimeouts = 0 else wrapTimeouts++ }
        if (line.contains("[READY]") || line.contains("[CSQTT diagnostic] READY")) workers = maxOf(1, workers ?: 0)
        val flood = line.contains("Flood control", true) || Regex("error_code[\\s:=]+9\\b").containsMatchIn(line)
        // Only a failed solve counts, like the original app: "капча решена" lines are progress.
        val captchaFailed = captchaFailure.containsMatchIn(line)
        if (flood || captchaFailed ||
            line.contains("timed out", true) || line.contains("timeout", true) ||
            line.contains("connection reset", true) || line.contains("network is unreachable", true)) transientFailure = true
        if (line.contains("Ошибка кредов", true)) credentialsFailed = true
        stage = when {
            (workers ?: 0) > 0 -> NativeConnectionStage.CONNECTED
            flood -> NativeConnectionStage.WAITING_VK
            line.startsWith("CAPTCHA_SOLVE|") || line.contains("Решаю VK Smart Captcha") ||
                line.contains("решение капчи", true) -> NativeConnectionStage.CAPTCHA
            captchaSolved.containsMatchIn(line) -> NativeConnectionStage.AUTHORIZING
            line.contains("Запрос кредов", true) || line.contains("VK Auth", true) -> NativeConnectionStage.AUTHORIZING
            line.contains("TURN TCP") || line.contains("TURN UDP") || line.contains("Креды OK") -> NativeConnectionStage.CONNECTING
            workers == 0 -> NativeConnectionStage.RECONNECTING
            else -> stage
        }
        return stage
    }

    /** Workers the client last reported up, as its own app shows them; null before the first report. */
    @Synchronized fun activeWorkers(): Int? = workers

    /** The original watchdog's verdict: three WRAP timeouts and no worker ever up after 30 s. */
    @Synchronized fun wrongPassword(captchaWaiting: Boolean): Boolean =
        !captchaWaiting && !everActive && wrapTimeouts >= 3 && clock() - startedAt > 30_000

    @Synchronized fun needsRestart(captchaWaiting: Boolean, starting: Boolean): Boolean {
        if (captchaWaiting) { zeroSince = clock(); lastStats = clock(); return false }
        if (!starting) {
            // Original CSQTT: a panel restart of the server, or streams dropped from their peak, after 4 s.
            if (serverRestartAt > 0 && clock() - serverRestartAt >= 4_000) return true
            if (zeroEventAt > 0 && peak >= minimumRecoveryPeak && (workers ?: 0) < peak && clock() - zeroEventAt >= 4_000) return true
            if ((workers ?: 0) >= peak) zeroEventAt = 0
        }
        if ((workers ?: 0) > 0) {
            zeroSince = clock()
            // Workers report every few seconds; silence means a hung client (STALE_STATS_MS of the original).
            // Only clients that report statistics (qWDTT) are judged by them.
            return statsSeen && !starting && clock() - startedAt > 120_000 && clock() - lastStats > 90_000
        }
        // Like the original Android watchdog: never restart just because no user traffic flows.
        return clock() - zeroSince >= 90_000 &&
            (workers == 0 || (starting && credentialsFailed && transientFailure))
    }
}
