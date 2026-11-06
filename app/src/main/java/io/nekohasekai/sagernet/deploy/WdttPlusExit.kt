package app.rawline.mobile.deploy

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.Locale

/*
 * «Выходной IP и прокси» из WDTT Plus (ui/DeployTab.kt, ExternalProxyDiagnostics.kt, OutboundProbe.kt
 * at the pinned WDTT-Plus commit). The shell scripts and their checks are copied unchanged; only the SSH
 * plumbing below is ours: JSch, the main server's host key pinned at deploy, the sudo password on stdin.
 */

private const val CMD_TIMEOUT = 900000L
internal const val WGCF_VERSION = "2.2.32"
private const val WGCF_LINUX_AMD64_SHA256 = "2ff97f2201972ce582a424455d50a3719a380eef0cd1f3144f7779348e122a2c"
private const val WGCF_LINUX_AMD64_URL =
    "https://github.com/ViRb3/wgcf/releases/download/v$WGCF_VERSION/wgcf_${WGCF_VERSION}_linux_amd64"
private const val WGCF_LATEST_RELEASE_API = "https://api.github.com/repos/ViRb3/wgcf/releases/latest"
internal const val THREEPROXY_VERSION = "0.9.7"
private const val THREEPROXY_SOURCE_SHA256 =
    "efe862ef8b7c0ddf7b1c45d6b5d72f0b7cd0a3c54447419c7f1bd2239a06fc30"
private const val THREEPROXY_SOURCE_URL =
    "https://github.com/3proxy/3proxy/archive/refs/tags/$THREEPROXY_VERSION.tar.gz"

/** Progress of the running operation goes to the screen that started it. */
internal object DeployManager {
    val progress = ThreadLocal<((Float, String) -> Unit)?>()
    fun updateProgress(value: Float, step: String) { progress.get()?.invoke(value, step) }
    fun writeError(message: String) { android.util.Log.w("WdttPlusExit", message) }
}

internal enum class ProxyKind(val label: String, val protocol: String) {
    Socks5("SOCKS5", "socks5"),
    Http("HTTP", "http")
}

internal data class SshCredentials(
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val allowPasswordAuthentication: Boolean = true
) {
    val hasAuthentication: Boolean
        get() = privateKey.isNotBlank() || (allowPasswordAuthentication && password.isNotBlank())
}

/** The WDTT Plus server being managed; its session reuses the host key pinned at deploy. */
internal class OutboundSshTarget(val admin: WdttPlusAdmin, val server: DeployServer) {
    val host: String get() = server["host"]
    val user: String get() = server["user"]
    val pass: String get() = if (server["user"] == "root") "" else server["sudoPassword"].ifBlank { server["sshPassword"] }
    val port: Int get() = server.port("sshPort")
    val mainPassword: String get() = server["password"]
}

private fun createCriticalSshSession(target: OutboundSshTarget): Session = target.admin.session()
private fun createSafeReadOnlySshSession(target: OutboundSshTarget): Session = target.admin.session()

/**
 * The second VPS is typed in for this one operation; WDTT Plus does not pin its key either.
 * shortcut: its host key is accepted on first contact, pin it once the screen remembers second servers.
 */
private fun createCriticalSshSession(host: String, user: String, credentials: SshCredentials, port: Int): Session {
    val jsch = JSch()
    if (credentials.privateKey.isNotBlank()) jsch.addIdentity("Rawline-exit", credentials.privateKey.toByteArray(), null,
        credentials.privateKeyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray())
    return jsch.getSession(user, host, port).apply {
        setConfig("StrictHostKeyChecking", "no")
        setConfig("PreferredAuthentications", if (credentials.privateKey.isNotBlank()) "publickey" else "password,keyboard-interactive")
        if (credentials.password.isNotBlank()) setPassword(credentials.password)
        connect(20_000)
    }
}

@Suppress("UNUSED_PARAMETER")
private suspend fun runSafeInspection(
    target: OutboundSshTarget,
    relayOperation: List<String>,
    timeout: Long,
    direct: (SSHClient) -> String,
): String = withContext(Dispatchers.IO) {
    // WDTT Plus can also read this through its own running tunnel; here the server is always reached directly.
    val session = createSafeReadOnlySshSession(target)
    try { runInterruptible { direct(SSHClient(session, target.pass)).trim() } } finally { runCatching { session.disconnect() } }
}

/** WDTT Plus's SSHClient semantics: merged output, WDTT_PROGRESS lines as progress, a nonzero exit as "error:". */
private class SSHClient(private val session: Session, private val pass: String) {
    fun exec(command: String, timeout: Long = CMD_TIMEOUT): String {
        if (!session.isConnected) return "error: session is down"
        val channel = session.openChannel("exec") as ChannelExec
        val result = StringBuilder()
        try {
            val plan = deployStdinPlan(command, pass)
            channel.setCommand("${plan?.command ?: command} 2>&1")
            channel.setInputStream(plan?.input.orEmpty().byteInputStream())
            val reader = channel.inputStream.bufferedReader(Charsets.UTF_8)
            channel.connect(15_000)
            val deadline = System.currentTimeMillis() + timeout
            val progress = Regex("""^WDTT_PROGRESS\|(\d+\.?\d*)\|(.+)$""")
            val ansi = Regex("""\u001B\[[;\d]*m""")
            fun take(line: String) {
                val match = progress.find(line.trim())
                if (match != null) DeployManager.updateProgress(match.groupValues[1].toFloatOrNull() ?: 0f, match.groupValues[2])
                else if (!line.contains("WDTT_PROGRESS") && !line.contains("password for")) result.appendLine(line.replace(ansi, ""))
            }
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    DeployManager.writeError("SSH: сервер не завершил команду за ${timeout / 1000} с.")
                    return "error: timeout"
                }
                if (reader.ready()) {
                    take(reader.readLine() ?: break)
                } else if (channel.isClosed) {
                    reader.readLines().forEach(::take)
                    break
                } else Thread.sleep(100)
            }
            when {
                channel.exitStatus < 0 -> result.appendLine("error: SSH channel closed before exit status")
                channel.exitStatus != 0 -> result.appendLine("error: remote command exited with code ${channel.exitStatus}")
            }
            return result.toString()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.io.InterruptedIOException) {
            throw e
        } catch (e: Exception) {
            return if (result.isNotEmpty()) result.appendLine("error: SSH channel interrupted").toString() else "error: ${e.message}"
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    fun upload(localFile: File, remotePath: String, permissions: Int? = null) {
        check(session.isConnected) { "Session is down" }
        val sftp = session.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(15_000)
            sftp.put(localFile.absolutePath, remotePath)
            permissions?.let { sftp.chmod(it, remotePath) }
        } finally {
            runCatching { sftp.disconnect() }
        }
    }
}

internal fun friendlyDeployError(error: Throwable, operation: String): String =
    error.message?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotBlank() }?.take(300)
        ?: "операция «$operation» завершилась с неизвестной ошибкой"

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

internal fun rootCommand(command: String): String {
    val quoted = shellQuote(command)
    return "if [ \"\$(id -u)\" = \"0\" ]; then bash -c $quoted; " +
        "elif command -v sudo >/dev/null 2>&1; then sudo -S -p '' bash -c $quoted; " +
        "else echo 'error: root privileges required and sudo not found'; exit 1; fi"
}

private fun shellScript(vararg blocks: String): String =
    blocks.joinToString("\n") { it.trimIndent().trim('\n') }.trim() + "\n"

private fun randomToken(length: Int): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

@Suppress("UNUSED_PARAMETER")
private suspend fun runRootScript(
    context: Context,
    target: OutboundSshTarget,
    script: String,
    timeout: Long = CMD_TIMEOUT,
    safeReadOnly: Boolean = false,
    relayOperation: List<String>? = null,
): String = withContext(Dispatchers.IO) {
    var session: Session? = null
    val scriptFile = File(context.cacheDir, "wdtt-outbound-${System.currentTimeMillis()}.sh")
    val remotePath = "/tmp/${scriptFile.name}"
    try {
        scriptFile.writeText(script.trimIndent() + "\n")
        session = createCriticalSshSession(target)
        val ssh = SSHClient(session, target.pass)
        ssh.upload(scriptFile, remotePath)
        val output = ssh.exec(
            rootCommand("chmod 700 $remotePath; bash $remotePath; code=\$?; rm -f $remotePath; exit \$code"),
            timeout = timeout
        )
        markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException(rootScriptErrorExcerpt(output)) }
        if (output.startsWith("error:", ignoreCase = true) || output.contains("\nerror:", ignoreCase = true)) {
            throw IllegalStateException(output.trim().take(500))
        }
        output.trim()
    } finally {
        scriptFile.delete()
        try { session?.disconnect() } catch (_: Exception) {}
    }
}

@Suppress("UNUSED_PARAMETER")
private suspend fun runCheckedRootScript(
    target: OutboundSshTarget,
    script: String,
    timeout: Long = CMD_TIMEOUT,
    safeReadOnly: Boolean = false,
    relayOperation: List<String>? = null,
): String = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        session = createCriticalSshSession(target)
        val output = SSHClient(session, target.pass)
            .exec(rootCommand(script), timeout = timeout)
            .trim()
        markerValue(output, "WDTT_ERROR")?.let {
            throw IllegalStateException(rootScriptErrorExcerpt(output))
        }
        if (output.startsWith("error:", ignoreCase = true) ||
            output.contains("\nerror:", ignoreCase = true)
        ) {
            throw IllegalStateException(rootScriptErrorExcerpt(output))
        }
        output
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
    }
}

internal fun markerValue(output: String, name: String): String? =
    Regex("^$name=(.*)$", setOf(RegexOption.MULTILINE)).find(output)?.groupValues?.getOrNull(1)?.trim()

private fun decodeBase64Text(value: String): String =
    String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)

private fun encodeBase64Text(value: String): String =
    android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

internal fun localProxyCredentialsIssue(login: String, password: String): String? = when {
    login.isBlank() -> "Укажите логин прокси."
    !login.matches(Regex("^[A-Za-z0-9._@+-]{1,40}$")) ->
        "В логине допустимы латинские буквы, цифры и знаки . _ @ + -"
    password.length !in 8..80 -> "Пароль должен содержать от 8 до 80 символов."
    !password.matches(Regex("^[A-Za-z0-9._~!@%+?,=-]{8,80}$")) ->
        "В пароле допустимы латинские буквы, цифры и знаки . _ ~ ! @ % + ? , = -"
    else -> null
}

internal fun externalProxyCredentialsIssue(login: String, password: String): String? = when {
    login.isBlank() && password.isNotBlank() -> "Для пароля внешнего прокси укажите логин."
    login.isNotBlank() && password.isBlank() -> "Для указанного логина внешнего прокси введите пароль."
    login.length > 80 -> "Логин внешнего прокси не должен быть длиннее 80 символов."
    password.length > 120 -> "Пароль внешнего прокси не должен быть длиннее 120 символов."
    ':' in login -> "Логин внешнего прокси не должен содержать двоеточие."
    login.any(Char::isISOControl) || password.any(Char::isISOControl) ->
        "Логин и пароль внешнего прокси содержат недопустимые символы."
    else -> null
}

internal fun tunInterfaceNameIssue(value: String): String? {
    val name = value.trim()
    return when {
        name.isBlank() -> "Укажите имя TUN-интерфейса."
        name.length > 15 -> "Имя сетевого интерфейса не должно быть длиннее 15 символов."
        !name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]*$")) ->
            "Имя интерфейса должно начинаться с латинской буквы или цифры; далее допустимы знаки . _ : -"
        name in setOf("lo", "wdtt0", "wg-wdtt-exit") ->
            "Интерфейс $name зарезервирован и не может быть выбран как внешний TUN."
        else -> null
    }
}

internal data class TunInterfaceCandidate(
    val name: String,
    val isUp: Boolean
)

internal fun tunInterfaceSelectionIssue(
    value: String,
    candidates: List<TunInterfaceCandidate>
): String? {
    val nameIssue = tunInterfaceNameIssue(value)
    if (nameIssue != null) return nameIssue
    return candidates.firstOrNull { it.name == value.trim() && !it.isUp }
        ?.let { "Интерфейс ${it.name} найден, но остановлен. Сначала запустите создающую его службу." }
}

internal fun escapeRedsocksQuotedValue(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

internal data class OutboundProfileForms(
    val localProxyPort: String,
    val localProxyLogin: String,
    val localProxyPassword: String,
    val externalProxyKindName: String,
    val externalProxyHost: String,
    val externalProxyPort: String,
    val externalProxyLogin: String,
    val externalProxyPassword: String,
    val wireGuardExitHost: String,
    val wireGuardExitSshPort: String,
    val wireGuardExitUser: String,
    val wireGuardExitPassword: String,
    val wireGuardExitPort: String,
    val wireGuardExitDns: String,
    val importedWireGuardConfig: String,
    val tunInterface: String = ""
)

internal data class OutboundServerSnapshot(
    val mode: String,
    val detail: String,
    val updatedAt: String,
    val hasProfile: Boolean,
    val localProxyPresent: Boolean,
    val localProxyActive: Boolean,
    val localProxyPort: String,
    val localProxyLogin: String,
    val localProxyPassword: String,
    val externalProxyPresent: Boolean,
    val externalProxyActive: Boolean,
    val externalProxyKindName: String,
    val externalProxyHost: String,
    val externalProxyPort: String,
    val externalProxyLogin: String,
    val externalProxyPassword: String,
    val externalProxyProfileSaved: Boolean = false,
    val wireGuardPresent: Boolean,
    val wireGuardActive: Boolean,
    val wireGuardExitHost: String,
    val wireGuardExitSshPort: String,
    val wireGuardExitUser: String,
    val wireGuardExitPassword: String,
    val wireGuardExitPort: String,
    val wireGuardExitDns: String,
    val warpPresent: Boolean,
    val warpMtu: String,
    val importedWireGuardConfig: String,
    val checkedAtMillis: Long = System.currentTimeMillis(),
    val externalProxyServiceActive: Boolean = externalProxyActive,
    val externalProxyRouteActive: Boolean = externalProxyActive,
    val wireGuardInterfaceActive: Boolean = wireGuardActive,
    val wireGuardServiceActive: Boolean = wireGuardActive,
    val wireGuardPolicyRuleActive: Boolean = wireGuardActive,
    val wireGuardDefaultRouteActive: Boolean = wireGuardActive,
    val wireGuardNatActive: Boolean = wireGuardActive,
    val wireGuardOwnerMode: String = "",
    val wireGuardConfigOwnerMode: String = "",
    val wireGuardMatchesWarp: Boolean = false,
    val localProxyServiceEnabled: Boolean = localProxyActive,
    val externalProxyServiceEnabled: Boolean = externalProxyServiceActive,
    val wireGuardServiceEnabled: Boolean = wireGuardServiceActive,
    val tunInterface: String = "",
    val tunProfileSaved: Boolean = false,
    val tunPresent: Boolean = false,
    val tunInterfaceActive: Boolean = false,
    val tunServiceActive: Boolean = false,
    val tunServiceEnabled: Boolean = false,
    val tunPolicyRuleActive: Boolean = false,
    val tunDefaultRouteActive: Boolean = false,
    val tunForwardRulesActive: Boolean = false,
    val tunIpForwardActive: Boolean = false,
    val tunFailClosedActive: Boolean = false
) {
    val modeLabel: String
        get() = when (mode) {
            "direct" -> "прямой выход"
            "external_proxy" -> "внешний TCP-прокси"
            "tun_interface" -> "существующий TUN-интерфейс"
            "warp_free" -> "бесплатный WARP"
            "imported_wg" -> "VPN/WireGuard-файл"
            "wireguard_vps" -> "выход через другой сервер"
            else -> mode.ifBlank { "не указан" }
        }


    val activeRouteLabels: List<String>
        get() = buildList {
            if (externalProxyRouteActive) add("внешний TCP-прокси")
            if (wireGuardRoutingPresent) add("WireGuard-выход")
            if (tunRoutingPresent) add("TUN-интерфейс")
        }

    val externalProxyRoutingPresent: Boolean
        get() = externalProxyServiceActive || externalProxyRouteActive

    val wireGuardRoutingPresent: Boolean
        get() = wireGuardInterfaceActive ||
            wireGuardPolicyRuleActive ||
            wireGuardDefaultRouteActive ||
            wireGuardNatActive

    val wireGuardHealthy: Boolean
        get() = wireGuardActive && wireGuardServiceActive && wireGuardServiceEnabled

    val tunRoutingPresent: Boolean
        get() = tunServiceActive || tunPolicyRuleActive || tunDefaultRouteActive || tunForwardRulesActive

    val tunHealthy: Boolean
        get() = tunPresent && tunInterfaceActive && tunServiceActive && tunServiceEnabled &&
            tunPolicyRuleActive && tunDefaultRouteActive && tunForwardRulesActive && tunIpForwardActive

    val hasRouteConflict: Boolean
        get() = listOf(
            externalProxyRouteActive,
            wireGuardRoutingPresent,
            tunRoutingPresent
        ).count { it } > 1

    val routeConflictMessage: String?
        get() = if (hasRouteConflict) {
            "На сервере одновременно остались несколько маршрутов внешнего выхода WDTT: ${activeRouteLabels.joinToString(" и ")}. Это может нарушить интернет у клиентов; нажмите «Вернуть прямой выход», затем включите только один режим."
        } else {
            null
        }
}

internal fun String.isValidPublicHost(): Boolean {
    val value = trim()
    if (value.isBlank() || value.length > 253 || value.any { it == '/' || it == '\\' || it == ':' || it == '@' }) return false
    val ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    if (ipv4.matches(value)) return true
    if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) return false
    val labels = value.split(".")
    if (labels.size < 2) return false
    return labels.all { label ->
        label.isNotBlank() &&
            label.length <= 63 &&
            !label.startsWith("-") &&
            !label.endsWith("-") &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}

/** Read-only checks shared by installation, diagnostics and the WARP watchdog. */
internal fun outboundProbeFunctions(): String = """
    wdtt_probe_ipv4() {
      printf '%s\n' "${'$'}1" | awk -F. '
        NR != 1 || NF != 4 { exit 1 }
        { for (i=1; i<=4; i++) if (${'$'}i !~ /^[0-9]+$/ || ${'$'}i > 255) exit 1 }
      '
    }
    wdtt_routed_https() (
      source_ip="${'$'}1"
      expected_iface="${'$'}2"
      url="${'$'}3"
      [ -n "${'$'}source_ip" ] && [ -n "${'$'}expected_iface" ] || return 1
      host="${'$'}{url#https://}"
      host="${'$'}{host%%/*}"
      destination="${'$'}(getent ahostsv4 "${'$'}host" 2>/dev/null | awk '{print ${'$'}1; exit}')"
      wdtt_probe_ipv4 "${'$'}destination" || return 1
      route="${'$'}(ip -4 route get "${'$'}destination" from "${'$'}source_ip" 2>/dev/null)" || return 1
      printf '%s\n' "${'$'}route" | awk -v expected="${'$'}expected_iface" '
        { for (i=1; i<NF; i++) if (${'$'}i == "dev" && ${'$'}(i+1) == expected) found=1 }
        END { exit found ? 0 : 1 }
      ' || return 1
      # Pin the same destination whose route was checked. Do not use curlrc/env proxies.
      response="${'$'}(curl -q --proxy '' --noproxy '*' --interface "${'$'}source_ip" \
        --resolve "${'$'}host:443:${'$'}destination" -4fsS --connect-timeout 4 --max-time 10 \
        "${'$'}url" 2>/dev/null)" || return 1
      [ -n "${'$'}response" ] || return 1
      printf '%s\n' "${'$'}response"
    )
    wdtt_routed_public_ip() (
      for url in https://api.ipify.org https://checkip.amazonaws.com; do
        result="${'$'}(wdtt_routed_https "${'$'}1" "${'$'}2" "${'$'}url")" || continue
        if wdtt_probe_ipv4 "${'$'}result"; then printf '%s\n' "${'$'}result"; return 0; fi
      done
      return 1
    )
""".trimIndent()

/** Keep the error marker and its details even after a long install/progress log. */
internal fun rootScriptErrorExcerpt(output: String): String {
    val marker = Regex("(?m)^WDTT_ERROR=").findAll(output).lastOrNull()
    return if (marker != null) output.substring(marker.range.first).take(1600)
    else output.takeLast(1600)
}

internal fun externalProxyFailureMessage(output: String): String {
    val code = Regex("(?m)^WDTT_PROXY_CURL_EXIT=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
    val reason = when (code) {
        5, 6 -> "Сервер не смог определить адрес проверочного сайта через DNS."
        7 -> "Не удалось установить TCP-соединение через перенаправление."
        22 -> "Проверочные сайты вернули ошибку HTTP."
        28 -> "Истекло время ожидания ответа через прокси."
        35, 60 -> "Не прошла проверка защищённого HTTPS-соединения."
        45 -> "Не удалось отправить проверку с адреса интерфейса WDTT."
        0 -> "Проверочные сайты не вернули корректный IPv4-адрес."
        else -> "Не удалось получить ответ проверочного сайта через прокси."
    }
    return "$reason Включение внешнего TCP-прокси отменено, восстановлен прямой выход WDTT. " +
        "Кнопка «Проверить» проверяет сам прокси отдельно от перенаправления через redsocks."
}

internal fun externalProxyProbeFunctions(): String = """
    wdtt_valid_proxy_test_ip() {
      printf '%s\n' "${'$'}1" | awk -F. '
        NR != 1 || NF != 4 { exit 1 }
        { for (i = 1; i <= 4; i++) if (${'$'}i !~ /^[0-9]+$/ || ${'$'}i > 255) exit 1 }
      '
    }
    wdtt_test_redsocks_path() {
      proxy_ip="${'$'}1"
      systemctl is-active --quiet wdtt-redsocks || { echo WDTT_ERROR=external_proxy_service_inactive; return 1; }
      command -v curl >/dev/null 2>&1 || { echo WDTT_ERROR=curl_not_installed; return 1; }
      test_source="${'$'}(wdtt_test_source)"
      [ -n "${'$'}test_source" ] || { echo WDTT_ERROR=wdtt_iface_not_found; return 1; }
      wdtt_cleanup_proxy_test
      WDTT_PROXY_TEST_SOURCE="${'$'}test_source"
      if ! iptables -I INPUT -i lo -s "${'$'}test_source" -d 127.0.0.1 -p tcp --dport 12345 -m comment --comment WDTT_PROXY_TEST -j ACCEPT ||
         ! iptables -t nat -N WDTT_PROXY_TEST ||
         ! wdtt_proxy_reserved_returns WDTT_PROXY_TEST "${'$'}proxy_ip" ||
         ! iptables -t nat -A WDTT_PROXY_TEST -p tcp -j REDIRECT --to-ports 12345 ||
         ! iptables -t nat -I OUTPUT -s "${'$'}test_source" -p tcp -j WDTT_PROXY_TEST; then
        wdtt_cleanup_proxy_test
        echo WDTT_ERROR=external_proxy_test_rule_failed
        return 1
      fi
      test_error_file="${'$'}(mktemp /tmp/wdtt-proxy-check.XXXXXX)" || {
        wdtt_cleanup_proxy_test
        echo WDTT_ERROR=external_proxy_test_temp_failed
        return 1
      }
      test_ok=0
      test_code=0
      for test_url in https://api.ipify.org https://checkip.amazonaws.com; do
        test_code=0
        # Ignore curlrc and environment proxies: only our transparent route is under test.
        test_ip="${'$'}(curl -q --proxy '' --noproxy '*' --interface "${'$'}test_source" \
          -4fsS --connect-timeout 5 --max-time 12 "${'$'}test_url" 2>"${'$'}test_error_file")" || test_code=${'$'}?
        if [ "${'$'}test_code" = 0 ] && wdtt_valid_proxy_test_ip "${'$'}test_ip"; then
          test_ok=1
          break
        fi
      done
      # A successful direct request must never masquerade as a working transparent proxy.
      test_packets="${'$'}(iptables -t nat -L WDTT_PROXY_TEST -n -v -x 2>/dev/null |
        awk '${'$'}3 == "REDIRECT" { total += ${'$'}1 } END { print total + 0 }')"
      wdtt_cleanup_proxy_test
      rm -f "${'$'}test_error_file"
      if [ "${'$'}test_ok" != 1 ]; then
        echo WDTT_ERROR=external_proxy_apply_failed
        echo "WDTT_PROXY_CURL_EXIT=${'$'}test_code"
        return 1
      fi
      if [ "${'$'}test_packets" -eq 0 ]; then
        echo WDTT_ERROR=external_proxy_test_not_redirected
        return 1
      fi
      echo "Проверка перенаправления через redsocks успешна. IP через прокси: ${'$'}test_ip"
    }
""".trimIndent()

internal fun outboundShellPrelude(): String = """
    set -e
    umask 077
    WDTT_SUBNET="${'$'}(ip -4 route show dev wdtt0 scope link 2>/dev/null | awk '{print ${'$'}1; exit}')"
    [ -n "${'$'}WDTT_SUBNET" ] || WDTT_SUBNET="10.66.66.0/24"
    WDTT_IFACE="wdtt0"
    WDTT_TABLE="100"
    WDTT_WG_IFACE="wg-wdtt-exit"
    WDTT_WG_OWNER_FILE="/etc/wdtt-plus/wg-exit/owner"
    WDTT_WG_CONFIG_OWNER_FILE="/etc/wdtt-plus/wg-exit/config-owner"
    WDTT_TUN_TABLE="110"
    WDTT_TUN_PRIORITY="90"
    WDTT_TUN_CONFIG_FILE="/etc/wdtt-plus/tun-exit/interface"
    WDTT_TUN_OWNER_FILE="/etc/wdtt-plus/tun-exit/owner"
    install -d -m 0700 /etc/wdtt /etc/wdtt/outbound /etc/wdtt-plus /etc/wdtt-plus/wg-exit /etc/wdtt-plus/tun-exit
    wdtt_ext_iface() {
      ip -o route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if (${'$'}i=="dev") {print ${'$'}(i+1); exit}}'
    }
    wdtt_test_source() {
      ip -4 -o addr show dev "${'$'}WDTT_IFACE" scope global 2>/dev/null | awk '{split(${'$'}4, value, "/"); print value[1]; exit}'
    }
    wdtt_install_pkg() {
      if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y >/dev/null 2>&1 || true
        DEBIAN_FRONTEND=noninteractive apt-get install -y "${'$'}@" >/dev/null
      elif command -v dnf >/dev/null 2>&1; then
        dnf install -y "${'$'}@" >/dev/null
      elif command -v yum >/dev/null 2>&1; then
        yum install -y "${'$'}@" >/dev/null
      elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive install -y "${'$'}@" >/dev/null
      elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache "${'$'}@" >/dev/null
      elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm --needed "${'$'}@" >/dev/null
      else
        return 1
      fi
    }
    wdtt_install_redsocks_tools() {
      if command -v apt-get >/dev/null 2>&1; then
        wdtt_install_pkg redsocks curl iptables psmisc iproute2 coreutils
      elif command -v dnf >/dev/null 2>&1; then
        wdtt_install_pkg redsocks curl iptables psmisc iproute coreutils
      elif command -v yum >/dev/null 2>&1; then
        wdtt_install_pkg redsocks curl iptables psmisc iproute coreutils
      elif command -v zypper >/dev/null 2>&1; then
        wdtt_install_pkg redsocks curl iptables psmisc iproute2 coreutils
      elif command -v apk >/dev/null 2>&1; then
        wdtt_install_pkg redsocks curl iptables psmisc iproute2 coreutils
      elif command -v pacman >/dev/null 2>&1; then
        wdtt_install_pkg redsocks curl iptables psmisc iproute2 coreutils
      else
        return 1
      fi
    }
    wdtt_install_wireguard_tools() {
      if command -v apt-get >/dev/null 2>&1; then
        wdtt_install_pkg wireguard-tools curl ca-certificates iptables iproute2
      elif command -v dnf >/dev/null 2>&1; then
        wdtt_install_pkg wireguard-tools curl ca-certificates iptables iproute
      elif command -v yum >/dev/null 2>&1; then
        wdtt_install_pkg wireguard-tools curl ca-certificates iptables iproute
      elif command -v zypper >/dev/null 2>&1; then
        wdtt_install_pkg wireguard-tools curl ca-certificates iptables iproute2
      elif command -v apk >/dev/null 2>&1; then
        wdtt_install_pkg wireguard-tools curl ca-certificates iptables iproute2
      elif command -v pacman >/dev/null 2>&1; then
        wdtt_install_pkg wireguard-tools curl ca-certificates iptables iproute2
      else
        return 1
      fi
    }
    wdtt_clear_proxy_out() {
      if command -v iptables >/dev/null 2>&1; then
        while iptables -t nat -D PREROUTING -i "${'$'}WDTT_IFACE" -p tcp -j WDTT_PROXY_OUT 2>/dev/null; do :; done
        while iptables -D INPUT -i "${'$'}WDTT_IFACE" -s "${'$'}WDTT_SUBNET" -p tcp --dport 12345 -m comment --comment WDTT_PROXY_OUT -j ACCEPT 2>/dev/null; do :; done
        iptables -t nat -F WDTT_PROXY_OUT 2>/dev/null || true
        iptables -t nat -X WDTT_PROXY_OUT 2>/dev/null || true
      fi
      systemctl disable --now wdtt-redsocks 2>/dev/null || systemctl stop wdtt-redsocks 2>/dev/null || true
      wdtt_kill_redsocks_listener
      systemctl reset-failed wdtt-redsocks.service 2>/dev/null || true
    }
    wdtt_clear_wireguard_out() {
      systemctl disable --now wdtt-warp-watchdog.timer 2>/dev/null || true
      systemctl disable --now wdtt-wg-exit.service 2>/dev/null || true
      if command -v iptables >/dev/null 2>&1; then
        while iptables -D FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}WDTT_WG_IFACE" -s "${'$'}WDTT_SUBNET" -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
        while iptables -D FORWARD -i "${'$'}WDTT_WG_IFACE" -o "${'$'}WDTT_IFACE" -d "${'$'}WDTT_SUBNET" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
        while iptables -t nat -D POSTROUTING -s "${'$'}WDTT_SUBNET" -o "${'$'}WDTT_WG_IFACE" -m comment --comment WDTT_EXIT -j MASQUERADE 2>/dev/null; do :; done
      fi
      while ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TABLE" priority 100 2>/dev/null; do :; done
      ip route flush table "${'$'}WDTT_TABLE" 2>/dev/null || true
      if ! wg-quick down "${'$'}WDTT_WG_IFACE" 2>/dev/null; then
        ip link delete "${'$'}WDTT_WG_IFACE" 2>/dev/null || true
      fi
      rm -f "${'$'}WDTT_WG_OWNER_FILE"
      systemctl reset-failed wdtt-wg-exit.service 2>/dev/null || true
    }
    wdtt_tun_is_owned() {
      [ "${'$'}(cat "${'$'}WDTT_TUN_OWNER_FILE" 2>/dev/null | head -n 1)" = WDTT_TUN_EXIT_V1 ]
    }
    wdtt_tun_reserved_state_present() {
      [ -e "${'$'}WDTT_TUN_OWNER_FILE" ] && return 0
      [ -e "${'$'}WDTT_TUN_CONFIG_FILE" ] && return 0
      [ -e /etc/systemd/system/wdtt-tun-exit.service ] && return 0
      [ -e /usr/local/lib/wdtt/tun-exit-route ] && return 0
      [ -e /usr/local/lib/wdtt/tun-exit-watch ] && return 0
      systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null && return 0
      ip rule show 2>/dev/null | grep -Eq "lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})" && return 0
      [ -n "${'$'}(ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null)" ] && return 0
      command -v iptables >/dev/null 2>&1 &&
        iptables -S FORWARD 2>/dev/null | grep -q -- '--comment WDTT_TUN_EXIT' && return 0
      return 1
    }
    wdtt_require_tun_cleanup_ownership() {
      ! wdtt_tun_reserved_state_present || wdtt_tun_is_owned
    }
    wdtt_clear_tun_out() {
      wdtt_require_tun_cleanup_ownership || return 1
      wdtt_tun_is_owned || return 0
      systemctl disable --now wdtt-tun-exit.service 2>/dev/null || systemctl stop wdtt-tun-exit.service 2>/dev/null || true
      if [ -x /usr/local/lib/wdtt/tun-exit-route ]; then
        /usr/local/lib/wdtt/tun-exit-route down >/dev/null 2>&1 || true
      else
        tun_iface="${'$'}(cat "${'$'}WDTT_TUN_CONFIG_FILE" 2>/dev/null | head -n 1)"
        while ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TUN_TABLE" priority "${'$'}WDTT_TUN_PRIORITY" 2>/dev/null; do :; done
        ip route flush table "${'$'}WDTT_TUN_TABLE" 2>/dev/null || true
        if command -v iptables >/dev/null 2>&1 && [ -n "${'$'}tun_iface" ]; then
          while iptables -D FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}tun_iface" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null; do :; done
          while iptables -D FORWARD -i "${'$'}tun_iface" -o "${'$'}WDTT_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null; do :; done
        fi
      fi
      if command -v iptables >/dev/null 2>&1; then
        while :; do
          tun_rule="${'$'}(iptables -S FORWARD 2>/dev/null | grep -- '--comment WDTT_TUN_EXIT' | head -n 1 || true)"
          [ -n "${'$'}tun_rule" ] || break
          set -- ${'$'}tun_rule
          [ "${'$'}{1:-}" = "-A" ] || break
          shift
          iptables -D "${'$'}@" 2>/dev/null || break
        done
      fi
      rm -f /run/wdtt-tun-exit.state
      systemctl reset-failed wdtt-tun-exit.service 2>/dev/null || true
    }
    wdtt_clear_external_out() {
      wdtt_require_tun_cleanup_ownership || { echo WDTT_ERROR=tun_exit_not_owned; exit 3; }
      wdtt_clear_proxy_out
      wdtt_clear_wireguard_out
      wdtt_clear_tun_out
    }
    wdtt_kill_redsocks_listener() {
      rm -f /run/wdtt-redsocks.pid 2>/dev/null || true
      if command -v fuser >/dev/null 2>&1; then
        fuser -k 12345/tcp >/dev/null 2>&1 || true
      elif command -v ss >/dev/null 2>&1; then
        ss -ltnp 2>/dev/null | awk '/127\\.0\\.0\\.1:12345|\\*:12345/ {print}' | sed -n 's/.*pid=\\([0-9][0-9]*\\).*/\\1/p' | while read -r pid; do
          [ -n "${'$'}pid" ] && kill "${'$'}pid" 2>/dev/null || true
        done
      fi
      if command -v ss >/dev/null 2>&1 && ss -ltnp 2>/dev/null | grep -q ':12345'; then
        pkill -x redsocks 2>/dev/null || true
      fi
      systemctl reset-failed wdtt-redsocks 2>/dev/null || true
    }
    wdtt_proxy_reserved_returns() {
      chain="${'$'}1"
      proxy_ip="${'$'}2"
      for net in 0.0.0.0/8 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
        iptables -t nat -A "${'$'}chain" -d "${'$'}net" -j RETURN || return 1
      done
      if [ -n "${'$'}proxy_ip" ]; then
        iptables -t nat -A "${'$'}chain" -d "${'$'}proxy_ip" -j RETURN || return 1
      fi
    }
    wdtt_cleanup_proxy_test() {
      cleanup_test_source="${'$'}{WDTT_PROXY_TEST_SOURCE:-}"
      [ -n "${'$'}cleanup_test_source" ] && iptables -t nat -D OUTPUT -s "${'$'}cleanup_test_source" -p tcp -j WDTT_PROXY_TEST 2>/dev/null || true
      # Удаляем также правило старых версий, если предыдущая проверка была прервана.
      iptables -t nat -D OUTPUT -p tcp -m owner --uid-owner 0 -j WDTT_PROXY_TEST 2>/dev/null || true
      iptables -t nat -F WDTT_PROXY_TEST 2>/dev/null || true
      iptables -t nat -X WDTT_PROXY_TEST 2>/dev/null || true
      if [ -n "${'$'}cleanup_test_source" ]; then
        iptables -D INPUT -i lo -s "${'$'}cleanup_test_source" -d 127.0.0.1 -p tcp --dport 12345 -m comment --comment WDTT_PROXY_TEST -j ACCEPT 2>/dev/null || true
      fi
      WDTT_PROXY_TEST_SOURCE=""
    }
    ${outboundProbeFunctions().replace("\n", "\n    ")}
    ${externalProxyProbeFunctions().replace("\n", "\n    ")}
    wdtt_write_mode() {
      mode="${'$'}1"
      detail="${'$'}2"
      cat >/etc/wdtt/outbound.json <<EOF
    {
      "outboundMode": "${'$'}mode",
      "detail": "${'$'}detail",
      "wdttSubnet": "${'$'}WDTT_SUBNET",
      "interface": "${'$'}WDTT_IFACE",
      "routingTable": ${'$'}WDTT_TABLE,
      "updatedAt": "$(date -Is)"
    }
    EOF
      chmod 0600 /etc/wdtt/outbound.json
    }
""".trimIndent()

private fun outboundReadPrelude(): String = """
    set -e
    WDTT_IFACE="wdtt0"
    WDTT_TABLE="100"
    WDTT_WG_IFACE="wg-wdtt-exit"
    WDTT_WG_OWNER_FILE="/etc/wdtt-plus/wg-exit/owner"
    WDTT_WG_CONFIG_OWNER_FILE="/etc/wdtt-plus/wg-exit/config-owner"
    WDTT_TUN_TABLE="110"
    WDTT_TUN_PRIORITY="90"
    WDTT_TUN_CONFIG_FILE="/etc/wdtt-plus/tun-exit/interface"
    WDTT_TUN_OWNER_FILE="/etc/wdtt-plus/tun-exit/owner"
""".trimIndent()

internal fun outboundStatusScript(): String = shellScript(
    outboundShellPrelude(),
    """
    MODE="direct"
    if [ -f /etc/wdtt/outbound.json ]; then
      MODE="${'$'}(grep -o '"outboundMode"[[:space:]]*:[[:space:]]*"[^"]*"' /etc/wdtt/outbound.json | sed 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"//;s/".*//' | head -1)"
      [ -n "${'$'}MODE" ] || MODE="direct"
    fi
    case "${'$'}MODE" in
      direct) MODE_LABEL="прямой выход";;
      external_proxy) MODE_LABEL="внешний TCP-прокси";;
      tun_interface) MODE_LABEL="существующий TUN-интерфейс";;
      warp_free) MODE_LABEL="бесплатный WARP";;
      imported_wg) MODE_LABEL="VPN/WireGuard-файл";;
      wireguard_vps) MODE_LABEL="выход через другой сервер";;
      *) MODE_LABEL="${'$'}MODE";;
    esac
    SERVER_IP="${'$'}(curl -4fsS --max-time 8 https://api.ipify.org 2>/dev/null || echo 'не удалось определить')"
    echo "Текущий выход WDTT: ${'$'}MODE_LABEL"
    echo "Подсеть клиентов WDTT: ${'$'}WDTT_SUBNET"
    echo "Интерфейс клиентов: ${'$'}WDTT_IFACE"
    echo "Внешний IP самого сервера: ${'$'}SERVER_IP"
    case "${'$'}MODE" in
      direct)
        echo "Проверочный выход WDTT: ${'$'}SERVER_IP (прямой выход)"
        ;;
      warp_free|imported_wg|wireguard_vps|tun_interface)
        TEST_SOURCE="${'$'}(wdtt_test_source)"
        STATUS_EXIT_IFACE=wg-wdtt-exit
        [ "${'$'}MODE" != tun_interface ] || STATUS_EXIT_IFACE="${'$'}(cat "${'$'}WDTT_TUN_CONFIG_FILE" 2>/dev/null | head -n 1)"
        WDTT_EXIT_IP=""
        [ -n "${'$'}TEST_SOURCE" ] && WDTT_EXIT_IP="${'$'}(wdtt_routed_public_ip "${'$'}TEST_SOURCE" "${'$'}STATUS_EXIT_IFACE" || true)"
        if [ -n "${'$'}WDTT_EXIT_IP" ]; then
          echo "Проверочный выход WDTT: ${'$'}WDTT_EXIT_IP"
        else
          if [ "${'$'}MODE" = "tun_interface" ]; then
            TUN_STATUS_IFACE="${'$'}(cat "${'$'}WDTT_TUN_CONFIG_FILE" 2>/dev/null | head -n 1)"
            echo "Проверочный выход WDTT: не удалось проверить через TUN-интерфейс ${'$'}{TUN_STATUS_IFACE:-не указан}"
          else
            echo "Проверочный выход WDTT: не удалось проверить через ${'$'}WDTT_WG_IFACE"
          fi
        fi
        if [ "${'$'}MODE" = "warp_free" ]; then
          WARP_TRACE=""
          [ -n "${'$'}TEST_SOURCE" ] && WARP_TRACE="${'$'}(wdtt_routed_https "${'$'}TEST_SOURCE" "wg-wdtt-exit" https://www.cloudflare.com/cdn-cgi/trace || true)"
          WARP_STATE="${'$'}(printf '%s\n' "${'$'}WARP_TRACE" | sed -n 's/^warp=//p' | head -n 1)"
          echo "Cloudflare WARP: ${'$'}{WARP_STATE:-проверка не пройдена}"
          echo "Автопроверка WARP: ${'$'}(systemctl is-active wdtt-warp-watchdog.timer 2>/dev/null || echo не запущена)"
          echo "wgcf: ${'$'}(cat /etc/wdtt-plus/warp/wgcf-version 2>/dev/null || echo версия неизвестна)"
          WARP_SELECTED_MTU="${'$'}(sed -n 's/^WARP_MTU=//p' /etc/wdtt-plus/warp/selected.env 2>/dev/null | head -n 1)"
          WARP_SELECTED_ENDPOINT="${'$'}(sed -n 's/^WARP_ENDPOINT=//p' /etc/wdtt-plus/warp/selected.env 2>/dev/null | head -n 1)"
          if [ -n "${'$'}WARP_SELECTED_MTU" ] || [ -n "${'$'}WARP_SELECTED_ENDPOINT" ]; then
            echo "Подбор WARP: MTU ${'$'}{WARP_SELECTED_MTU:-неизвестно}, endpoint ${'$'}{WARP_SELECTED_ENDPOINT:-неизвестен}"
          fi
        fi
        ;;
      external_proxy)
        echo "Проверочный выход WDTT: через внешний TCP-прокси; точный IP смотрите при проверке/диагностике прокси"
        ;;
      *)
        echo "Проверочный выход WDTT: режим не распознан"
        ;;
    esac
    if systemctl is-active wdtt-3proxy >/dev/null 2>&1; then echo "Прокси на этом VPS: служба запущена"; else echo "Прокси на этом VPS: служба остановлена"; fi
    if systemctl is-enabled wdtt-3proxy >/dev/null 2>&1; then echo "Автозапуск прокси на этом VPS: включён"; else echo "Автозапуск прокси на этом VPS: выключен"; fi
    if systemctl is-active wdtt-redsocks >/dev/null 2>&1; then echo "Служба внешнего TCP-прокси для WDTT: запущена"; else echo "Служба внешнего TCP-прокси для WDTT: остановлена"; fi
    if systemctl is-enabled wdtt-redsocks >/dev/null 2>&1; then echo "Автозапуск внешнего TCP-прокси: включён"; else echo "Автозапуск внешнего TCP-прокси: выключен"; fi
    if command -v iptables >/dev/null 2>&1 &&
       iptables -t nat -C PREROUTING -i "${'$'}WDTT_IFACE" -p tcp -j WDTT_PROXY_OUT 2>/dev/null; then
      echo "Маршрут обычного TCP-трафика WDTT через внешний прокси: применён"
    else
      echo "Маршрут обычного TCP-трафика WDTT через внешний прокси: отсутствует"
    fi
    if systemctl is-active wdtt-wg-exit.service >/dev/null 2>&1; then echo "Служба WireGuard-выхода: запущена"; else echo "Служба WireGuard-выхода: остановлена"; fi
    if systemctl is-enabled wdtt-wg-exit.service >/dev/null 2>&1; then echo "Автозапуск WireGuard-выхода: включён"; else echo "Автозапуск WireGuard-выхода: выключен"; fi
    if ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TABLE([[:space:]]|${'$'})|from [^ ]+ lookup wdtt-exit([[:space:]]|${'$'})"; then
      echo "Правило маршрутизации подсети WDTT: применено"
    else
      echo "Правило маршрутизации подсети WDTT: отсутствует"
    fi
    if ip route show table "${'$'}WDTT_TABLE" 2>/dev/null | grep -Eq "^default([[:space:]].*)? dev ${'$'}WDTT_WG_IFACE([[:space:]]|${'$'})"; then
      echo "Маршрут по умолчанию через WireGuard: применён"
    else
      echo "Маршрут по умолчанию через WireGuard: отсутствует"
    fi
    if command -v iptables >/dev/null 2>&1 &&
       iptables -t nat -S POSTROUTING 2>/dev/null |
         grep -q -- "-o ${'$'}WDTT_WG_IFACE .*--comment WDTT_EXIT .*MASQUERADE"; then
      echo "NAT WireGuard-выхода для подсети WDTT: применён"
    else
      echo "NAT WireGuard-выхода для подсети WDTT: отсутствует"
    fi
    if command -v wg >/dev/null 2>&1 && wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1; then
      echo "WireGuard ${'$'}WDTT_WG_IFACE:"
      wg show "${'$'}WDTT_WG_IFACE" | sed -E 's/(private key: ).*/\1(скрыт)/'
    else
      echo "WireGuard ${'$'}WDTT_WG_IFACE: не запущен"
    fi
    TUN_STATUS_IFACE="${'$'}(cat "${'$'}WDTT_TUN_CONFIG_FILE" 2>/dev/null | head -n 1)"
    echo "TUN-интерфейс выхода WDTT: ${'$'}{TUN_STATUS_IFACE:-не настроен}"
    if [ -n "${'$'}TUN_STATUS_IFACE" ] && [ -d "/sys/class/net/${'$'}TUN_STATUS_IFACE" ]; then
      echo "Состояние TUN-интерфейса: ${'$'}(cat "/sys/class/net/${'$'}TUN_STATUS_IFACE/operstate" 2>/dev/null || echo неизвестно)"
    else
      echo "Состояние TUN-интерфейса: не найден"
    fi
    if systemctl is-active wdtt-tun-exit.service >/dev/null 2>&1; then echo "Служба TUN-выхода: запущена"; else echo "Служба TUN-выхода: остановлена"; fi
    if systemctl is-enabled wdtt-tun-exit.service >/dev/null 2>&1; then echo "Автозапуск TUN-выхода: включён"; else echo "Автозапуск TUN-выхода: выключен"; fi
    if [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" = "1" ]; then echo "Пересылка IPv4-пакетов: включена"; else echo "Пересылка IPv4-пакетов: выключена"; fi
    if [ -n "${'$'}TUN_STATUS_IFACE" ] &&
       ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})"; then
      echo "Правило маршрутизации WDTT через TUN: применено"
    else
      echo "Правило маршрутизации WDTT через TUN: отсутствует"
    fi
    if [ -n "${'$'}TUN_STATUS_IFACE" ] &&
       ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null |
         grep -Eq "^default([[:space:]].*)? dev ${'$'}TUN_STATUS_IFACE([[:space:]]|${'$'})"; then
      echo "Маршрут по умолчанию через TUN: применён"
    else
      echo "Маршрут по умолчанию через TUN: отсутствует"
    fi
    if ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null | grep -Eq '^unreachable default([[:space:]]|${'$'})'; then
      echo "Аварийная блокировка прямого выхода: активна"
    else
      echo "Аварийная блокировка прямого выхода: не требуется"
    fi
    """
)

private suspend fun readOutboundStatus(target: OutboundSshTarget): String = withContext(Dispatchers.IO) {
    DeployManager.updateProgress(0.25f, "Подключаюсь к серверу и читаю текущий режим выхода...")
    val output = runSafeInspection(target, listOf("outbound-status"), 30_000L) { ssh ->
        DeployManager.updateProgress(0.70f, "Проверяю службы прокси и WireGuard...")
        ssh.exec(rootCommand(outboundStatusScript()), timeout = 30000L)
    }
    DeployManager.updateProgress(1f, "Статус выхода получен.")
    output
}

internal fun tunInterfaceCandidatesScript(): String = """
    set -e
    for iface_path in /sys/class/net/*; do
      [ -f "${'$'}iface_path/tun_flags" ] || continue
      iface="${'$'}{iface_path##*/}"
      case "${'$'}iface" in
        ''|[!A-Za-z0-9]*|*[!A-Za-z0-9_.:-]*|lo|wdtt0|wg-wdtt-exit) continue;;
      esac
      [ "${'$'}{#iface}" -le 15 ] || continue
      if ip -o link show dev "${'$'}iface" 2>/dev/null | grep -q '<[^>]*UP[,>]'; then
        state=1
      else
        state=0
      fi
      printf 'WDTT_TUN_CANDIDATE=%s|%s\n' "${'$'}iface" "${'$'}state"
    done | sort -u
""".trimIndent()

internal fun parseTunInterfaceCandidates(output: String): List<TunInterfaceCandidate> =
    output.lineSequence()
        .mapNotNull { line ->
            val value = line.removePrefix("WDTT_TUN_CANDIDATE=")
            if (value == line) return@mapNotNull null
            val name = value.substringBefore('|').trim()
            val state = value.substringAfter('|', "0").trim()
            if (tunInterfaceNameIssue(name) != null) return@mapNotNull null
            TunInterfaceCandidate(name = name, isUp = state == "1")
        }
        .distinctBy(TunInterfaceCandidate::name)
        .sortedWith(compareByDescending<TunInterfaceCandidate> { it.isUp }.thenBy { it.name })
        .take(32)
        .toList()

private suspend fun discoverTunInterfaces(target: OutboundSshTarget): List<TunInterfaceCandidate> =
    withContext(Dispatchers.IO) {
        val output = runSafeInspection(target, listOf("tun-candidates"), 30_000L) { ssh ->
            ssh.exec(rootCommand(tunInterfaceCandidatesScript()), timeout = 30000L)
        }
        parseTunInterfaceCandidates(output)
    }

private fun outboundProfileSaveScript(forms: OutboundProfileForms): String {
    val kindName = ProxyKind.entries.firstOrNull { it.name == forms.externalProxyKindName }?.name.orEmpty()
    fun port(value: String): String = value.filter(Char::isDigit).take(5)
    fun line(name: String, value: String): String = "$name=$value"
    fun b64Line(name: String, value: String): String = "$name=${encodeBase64Text(value)}"
    val profileLines = listOf(
        line("VERSION", "1"),
        line("LOCAL_PROXY_PORT", port(forms.localProxyPort)),
        b64Line("LOCAL_PROXY_LOGIN_B64", forms.localProxyLogin),
        b64Line("LOCAL_PROXY_PASSWORD_B64", forms.localProxyPassword),
        line("EXTERNAL_PROXY_KIND", kindName),
        b64Line("EXTERNAL_PROXY_HOST_B64", forms.externalProxyHost),
        line("EXTERNAL_PROXY_PORT", port(forms.externalProxyPort)),
        b64Line("EXTERNAL_PROXY_LOGIN_B64", forms.externalProxyLogin),
        b64Line("EXTERNAL_PROXY_PASSWORD_B64", forms.externalProxyPassword),
        b64Line("WG_VPS_HOST_B64", forms.wireGuardExitHost),
        line("WG_VPS_SSH_PORT", port(forms.wireGuardExitSshPort)),
        b64Line("WG_VPS_USER_B64", forms.wireGuardExitUser),
        // SSH-пароль второго VPS нужен только во время настройки. Не оставляем
        // root-доступ к нему на основном сервере.
        b64Line("WG_VPS_PASSWORD_B64", ""),
        line("WG_VPS_PORT", port(forms.wireGuardExitPort)),
        // DNS для клиентов задаётся в основных настройках WDTT, а не в
        // policy-routed WireGuard-выходе.
        b64Line("WG_VPS_DNS_B64", ""),
        b64Line("IMPORTED_WG_CONFIG_B64", forms.importedWireGuardConfig),
        b64Line("TUN_INTERFACE_B64", forms.tunInterface)
    ).joinToString("\n")
    val profileScript = """
        PROFILE_FILE=/etc/wdtt/outbound-profile.env
        TMP_FILE="${'$'}PROFILE_FILE.tmp"
        cat >"${'$'}TMP_FILE" <<'WDTT_OUTBOUND_PROFILE'
    """.trimIndent() + "\n" + profileLines + "\n" + """
        WDTT_OUTBOUND_PROFILE
        printf 'UPDATED_AT=%s\n' "$(date -Is)" >>"${'$'}TMP_FILE"
        chmod 600 "${'$'}TMP_FILE"
        mv "${'$'}TMP_FILE" "${'$'}PROFILE_FILE"
        echo "Профиль полей выходного IP сохранён на сервере."
    """.trimIndent()
    return shellScript(outboundShellPrelude(), profileScript)
}

private suspend fun writeOutboundProfileToServer(
    context: Context,
    target: OutboundSshTarget,
    forms: OutboundProfileForms
): String = runRootScript(
    context = context,
    target = target,
    script = outboundProfileSaveScript(forms),
    timeout = 30000L
)

private suspend fun saveOutboundProfileMessage(
    context: Context,
    target: OutboundSshTarget,
    forms: OutboundProfileForms,
    successMessage: String
): String = runCatching {
    writeOutboundProfileToServer(context, target, forms)
}.fold(
    onSuccess = { successMessage },
    onFailure = {
        DeployManager.writeError("Outbound profile save failed: ${it.message}")
        "Режим включён, но профиль полей не сохранился на сервере: ${friendlyDeployError(it, "сохранение")}."
    }
)

internal fun outboundSnapshotScript(): String = shellScript(
    outboundReadPrelude(),
    """
    PROFILE_FILE=/etc/wdtt/outbound-profile.env
    wdtt_profile_value() {
      key="${'$'}1"
      [ -f "${'$'}PROFILE_FILE" ] || return 0
      grep -E "^${'$'}key=" "${'$'}PROFILE_FILE" 2>/dev/null | tail -n 1 | sed 's/^[^=]*=//'
    }
    wdtt_profile_has_key() {
      key="${'$'}1"
      [ -f "${'$'}PROFILE_FILE" ] && grep -q -E "^${'$'}key=" "${'$'}PROFILE_FILE" 2>/dev/null
    }
    wdtt_b64() {
      command -v base64 >/dev/null 2>&1 || return 0
      printf '%s' "${'$'}1" | base64 | tr -d '\n'
    }
    wdtt_file_b64() {
      file="${'$'}1"
      [ -f "${'$'}file" ] || return 0
      command -v base64 >/dev/null 2>&1 || return 0
      base64 "${'$'}file" 2>/dev/null | tr -d '\n'
    }
    wdtt_json_string() {
      file="${'$'}1"
      key="${'$'}2"
      [ -f "${'$'}file" ] || return 0
      grep -o "\"${'$'}key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "${'$'}file" 2>/dev/null | sed "s/.*\"${'$'}key\"[[:space:]]*:[[:space:]]*\"//;s/\".*//" | head -n 1
    }
    wdtt_json_number() {
      file="${'$'}1"
      key="${'$'}2"
      [ -f "${'$'}file" ] || return 0
      grep -o "\"${'$'}key\"[[:space:]]*:[[:space:]]*[0-9][0-9]*" "${'$'}file" 2>/dev/null | sed "s/.*\"${'$'}key\"[[:space:]]*:[[:space:]]*//" | head -n 1
    }
    wdtt_redsocks_value() {
      key="${'$'}1"
      [ -f /etc/wdtt/redsocks.conf ] || return 0
      sed -n "s/^[[:space:]]*${'$'}key[[:space:]]*=[[:space:]]*//Ip" /etc/wdtt/redsocks.conf 2>/dev/null | head -n 1 | sed 's/[;[:space:]]*$//;s/^"//;s/"$//'
    }
    wdtt_3proxy_login() {
      [ -f /etc/wdtt/3proxy.cfg ] || return 0
      grep -E '^[[:space:]]*users[[:space:]]+' /etc/wdtt/3proxy.cfg 2>/dev/null | head -n 1 | sed -E 's/^[[:space:]]*users[[:space:]]+([^:]+):CL:.*/\1/'
    }
    wdtt_3proxy_password() {
      [ -f /etc/wdtt/3proxy.cfg ] || return 0
      grep -E '^[[:space:]]*users[[:space:]]+' /etc/wdtt/3proxy.cfg 2>/dev/null | head -n 1 | sed -E 's/^[[:space:]]*users[[:space:]]+[^:]+:CL:(.*)$/\1/'
    }
    wdtt_3proxy_port() {
      [ -f /etc/wdtt/3proxy.cfg ] || return 0
      grep -E '^[[:space:]]*socks[[:space:]].*-p[0-9]+' /etc/wdtt/3proxy.cfg 2>/dev/null | head -n 1 | sed -E 's/.*-p([0-9]+).*/\1/'
    }

    MODE="${'$'}(wdtt_json_string /etc/wdtt/outbound.json outboundMode)"
    [ -n "${'$'}MODE" ] || MODE="direct"
    DETAIL="${'$'}(wdtt_json_string /etc/wdtt/outbound.json detail)"
    UPDATED_AT="${'$'}(wdtt_json_string /etc/wdtt/outbound.json updatedAt)"
    HAS_PROFILE=0
    [ -f "${'$'}PROFILE_FILE" ] && HAS_PROFILE=1
    PROFILE_UPDATED_AT="${'$'}(wdtt_profile_value UPDATED_AT)"
    [ -n "${'$'}UPDATED_AT" ] || UPDATED_AT="${'$'}PROFILE_UPDATED_AT"

    LOCAL_PRESENT=0
    if [ -f /etc/wdtt/local-proxy.json ] || [ -f /etc/wdtt/3proxy.cfg ]; then
      LOCAL_PRESENT=1
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet wdtt-3proxy 2>/dev/null; then
      LOCAL_SERVICE_ACTIVE=1
      LOCAL_PRESENT=1
    else
      LOCAL_SERVICE_ACTIVE=0
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-enabled --quiet wdtt-3proxy 2>/dev/null; then
      LOCAL_SERVICE_ENABLED=1
    else
      LOCAL_SERVICE_ENABLED=0
    fi
    if wdtt_profile_has_key LOCAL_PROXY_PORT; then
      LOCAL_PORT="${'$'}(wdtt_profile_value LOCAL_PROXY_PORT)"
    else
      LOCAL_PORT="${'$'}(wdtt_json_number /etc/wdtt/local-proxy.json socks5Port)"
      [ -n "${'$'}LOCAL_PORT" ] || LOCAL_PORT="${'$'}(wdtt_3proxy_port)"
    fi
    LOCAL_ACTIVE="${'$'}LOCAL_SERVICE_ACTIVE"
    case "${'$'}LOCAL_PORT" in
      ''|*[!0-9]*) LOCAL_ACTIVE=0;;
      *)
        if [ "${'$'}LOCAL_SERVICE_ACTIVE" = 1 ] &&
           command -v ss >/dev/null 2>&1 &&
           ! ss -ltn 2>/dev/null | awk '{print ${'$'}4}' | grep -Eq ":${'$'}LOCAL_PORT${'$'}"; then
          LOCAL_ACTIVE=0
        fi
        ;;
    esac
    if wdtt_profile_has_key LOCAL_PROXY_LOGIN_B64; then
      LOCAL_LOGIN_B64="${'$'}(wdtt_profile_value LOCAL_PROXY_LOGIN_B64)"
    else
      LOCAL_LOGIN="${'$'}(wdtt_json_string /etc/wdtt/local-proxy.json login)"
      [ -n "${'$'}LOCAL_LOGIN" ] || LOCAL_LOGIN="${'$'}(wdtt_3proxy_login)"
      LOCAL_LOGIN_B64="${'$'}(wdtt_b64 "${'$'}LOCAL_LOGIN")"
    fi
    if wdtt_profile_has_key LOCAL_PROXY_PASSWORD_B64; then
      LOCAL_PASSWORD_B64="${'$'}(wdtt_profile_value LOCAL_PROXY_PASSWORD_B64)"
    else
      LOCAL_PASSWORD="${'$'}(wdtt_json_string /etc/wdtt/local-proxy.json password)"
      [ -n "${'$'}LOCAL_PASSWORD" ] || LOCAL_PASSWORD="${'$'}(wdtt_3proxy_password)"
      LOCAL_PASSWORD_B64="${'$'}(wdtt_b64 "${'$'}LOCAL_PASSWORD")"
    fi

    DETAIL_KIND=""
    DETAIL_HOST=""
    DETAIL_PORT=""
    case "${'$'}DETAIL" in
      socks5://*|http://*)
        DETAIL_KIND="${'$'}{DETAIL%%://*}"
        DETAIL_REST="${'$'}{DETAIL#*://}"
        DETAIL_HOST="${'$'}{DETAIL_REST%:*}"
        DETAIL_PORT="${'$'}{DETAIL_REST##*:}"
        ;;
    esac
    REDSOCKS_TYPE="${'$'}(wdtt_redsocks_value type)"
    REDSOCKS_KIND=""
    case "${'$'}REDSOCKS_TYPE" in
      socks5) REDSOCKS_KIND="Socks5";;
      http|http-connect) REDSOCKS_KIND="Http";;
    esac
    if wdtt_profile_has_key EXTERNAL_PROXY_KIND; then
      EXTERNAL_KIND="${'$'}(wdtt_profile_value EXTERNAL_PROXY_KIND)"
    else
      case "${'$'}DETAIL_KIND" in
        socks5) EXTERNAL_KIND="Socks5";;
        http) EXTERNAL_KIND="Http";;
        *) EXTERNAL_KIND="${'$'}REDSOCKS_KIND";;
      esac
    fi
    EXTERNAL_PROXY_PROFILE_SAVED=0
    if wdtt_profile_has_key EXTERNAL_PROXY_HOST_B64; then
      EXTERNAL_HOST_B64="${'$'}(wdtt_profile_value EXTERNAL_PROXY_HOST_B64)"
      [ -n "${'$'}EXTERNAL_HOST_B64" ] && EXTERNAL_PROXY_PROFILE_SAVED=1
    else
      EXTERNAL_HOST="${'$'}DETAIL_HOST"
      [ -n "${'$'}EXTERNAL_HOST" ] || EXTERNAL_HOST="${'$'}(wdtt_redsocks_value ip)"
      EXTERNAL_HOST_B64="${'$'}(wdtt_b64 "${'$'}EXTERNAL_HOST")"
    fi
    if wdtt_profile_has_key EXTERNAL_PROXY_PORT; then
      EXTERNAL_PORT="${'$'}(wdtt_profile_value EXTERNAL_PROXY_PORT)"
    else
      EXTERNAL_PORT="${'$'}DETAIL_PORT"
      [ -n "${'$'}EXTERNAL_PORT" ] || EXTERNAL_PORT="${'$'}(wdtt_redsocks_value port)"
    fi
    if wdtt_profile_has_key EXTERNAL_PROXY_LOGIN_B64; then
      EXTERNAL_LOGIN_B64="${'$'}(wdtt_profile_value EXTERNAL_PROXY_LOGIN_B64)"
    else
      EXTERNAL_LOGIN_B64="${'$'}(wdtt_b64 "$(wdtt_redsocks_value login)")"
    fi
    if wdtt_profile_has_key EXTERNAL_PROXY_PASSWORD_B64; then
      EXTERNAL_PASSWORD_B64="${'$'}(wdtt_profile_value EXTERNAL_PROXY_PASSWORD_B64)"
    else
      EXTERNAL_PASSWORD_B64="${'$'}(wdtt_b64 "$(wdtt_redsocks_value password)")"
    fi
    EXTERNAL_PRESENT=0
    if [ -f /etc/wdtt/redsocks.conf ]; then
      EXTERNAL_PRESENT=1
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet wdtt-redsocks 2>/dev/null; then
      EXTERNAL_SERVICE_ACTIVE=1
      EXTERNAL_PRESENT=1
      if command -v ss >/dev/null 2>&1 &&
         ! ss -ltn 2>/dev/null | awk '{print ${'$'}4}' | grep -Eq ':12345${'$'}'; then
        EXTERNAL_SERVICE_ACTIVE=0
      fi
    else
      EXTERNAL_SERVICE_ACTIVE=0
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-enabled --quiet wdtt-redsocks 2>/dev/null; then
      EXTERNAL_SERVICE_ENABLED=1
    else
      EXTERNAL_SERVICE_ENABLED=0
    fi
    EXTERNAL_ROUTE_ACTIVE=0
    if command -v iptables >/dev/null 2>&1 &&
       iptables -t nat -C PREROUTING -i "${'$'}WDTT_IFACE" -p tcp -j WDTT_PROXY_OUT 2>/dev/null &&
       iptables -t nat -S WDTT_PROXY_OUT 2>/dev/null | grep -q -- '--to-ports 12345'; then
      EXTERNAL_ROUTE_ACTIVE=1
    fi
    EXTERNAL_ACTIVE=0
    if [ "${'$'}EXTERNAL_SERVICE_ACTIVE" = 1 ] && [ "${'$'}EXTERNAL_ROUTE_ACTIVE" = 1 ]; then
      EXTERNAL_ACTIVE=1
    fi

    WG_CONF=""
    [ -f /etc/wireguard/wg-wdtt-exit.conf ] && WG_CONF=/etc/wireguard/wg-wdtt-exit.conf
    [ -z "${'$'}WG_CONF" ] && [ -f /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf ] && WG_CONF=/etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
    WG_PRESENT=0
    [ -n "${'$'}WG_CONF" ] && WG_PRESENT=1
    if command -v wg >/dev/null 2>&1 && wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1; then
      WG_INTERFACE_ACTIVE=1
      WG_PRESENT=1
    else
      WG_INTERFACE_ACTIVE=0
    fi
    if command -v systemctl >/dev/null 2>&1 &&
       systemctl is-active --quiet wdtt-wg-exit.service 2>/dev/null; then
      WG_SERVICE_ACTIVE=1
    else
      WG_SERVICE_ACTIVE=0
    fi
    if command -v systemctl >/dev/null 2>&1 &&
       systemctl is-enabled --quiet wdtt-wg-exit.service 2>/dev/null; then
      WG_SERVICE_ENABLED=1
    else
      WG_SERVICE_ENABLED=0
    fi
    WG_POLICY_RULE_ACTIVE=0
    ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TABLE([[:space:]]|${'$'})|from [^ ]+ lookup wdtt-exit([[:space:]]|${'$'})" &&
      WG_POLICY_RULE_ACTIVE=1
    WG_DEFAULT_ROUTE_ACTIVE=0
    ip route show table "${'$'}WDTT_TABLE" 2>/dev/null | grep -Eq "^default([[:space:]].*)? dev ${'$'}WDTT_WG_IFACE([[:space:]]|${'$'})" &&
      WG_DEFAULT_ROUTE_ACTIVE=1
    WG_NAT_ACTIVE=0
    if command -v iptables >/dev/null 2>&1 &&
       iptables -t nat -S POSTROUTING 2>/dev/null |
         grep -q -- "-o ${'$'}WDTT_WG_IFACE .*--comment WDTT_EXIT .*MASQUERADE"; then
      WG_NAT_ACTIVE=1
    fi
    WG_ACTIVE=0
    if [ "${'$'}WG_INTERFACE_ACTIVE" = 1 ] &&
       [ "${'$'}WG_POLICY_RULE_ACTIVE" = 1 ] &&
       [ "${'$'}WG_DEFAULT_ROUTE_ACTIVE" = 1 ] &&
       [ "${'$'}WG_NAT_ACTIVE" = 1 ]; then
      WG_ACTIVE=1
    fi
    WG_OWNER_MODE="${'$'}(cat "${'$'}WDTT_WG_OWNER_FILE" 2>/dev/null | head -n 1)"
    case "${'$'}WG_OWNER_MODE" in
      wireguard_vps|warp_free|imported_wg) ;;
      *) WG_OWNER_MODE="";;
    esac
    WG_CONFIG_OWNER_MODE="${'$'}(cat "${'$'}WDTT_WG_CONFIG_OWNER_FILE" 2>/dev/null | head -n 1)"
    case "${'$'}WG_CONFIG_OWNER_MODE" in
      wireguard_vps|warp_free|imported_wg) ;;
      *) WG_CONFIG_OWNER_MODE="";;
    esac
    WG_ENDPOINT=""
    WG_DNS=""
    WG_MTU=""
    if [ -n "${'$'}WG_CONF" ]; then
      WG_ENDPOINT="${'$'}(sed -n 's/^[[:space:]]*Endpoint[[:space:]]*=[[:space:]]*//Ip' "${'$'}WG_CONF" 2>/dev/null | head -n 1 | tr -d ' ')"
      WG_DNS="${'$'}(sed -n 's/^[[:space:]]*DNS[[:space:]]*=[[:space:]]*//Ip' "${'$'}WG_CONF" 2>/dev/null | head -n 1 | tr -d ' ')"
      WG_MTU="${'$'}(sed -n 's/^[[:space:]]*MTU[[:space:]]*=[[:space:]]*//Ip' "${'$'}WG_CONF" 2>/dev/null | head -n 1 | tr -d ' ')"
    fi
    WG_ENDPOINT_HOST=""
    WG_ENDPOINT_PORT=""
    if [ -n "${'$'}WG_ENDPOINT" ]; then
      WG_ENDPOINT_HOST="${'$'}{WG_ENDPOINT%:*}"
      WG_ENDPOINT_PORT="${'$'}{WG_ENDPOINT##*:}"
      WG_ENDPOINT_HOST="${'$'}{WG_ENDPOINT_HOST#[}"
      WG_ENDPOINT_HOST="${'$'}{WG_ENDPOINT_HOST%]}"
    fi
    if wdtt_profile_has_key WG_VPS_HOST_B64; then
      WG_VPS_HOST_B64="${'$'}(wdtt_profile_value WG_VPS_HOST_B64)"
    elif [ "${'$'}MODE" = "wireguard_vps" ]; then
      WG_VPS_HOST_B64="${'$'}(wdtt_b64 "${'$'}WG_ENDPOINT_HOST")"
    else
      WG_VPS_HOST_B64=""
    fi
    WG_VPS_SSH_PORT="${'$'}(wdtt_profile_value WG_VPS_SSH_PORT)"
    WG_VPS_USER_B64="${'$'}(wdtt_profile_value WG_VPS_USER_B64)"
    WG_VPS_PASSWORD_B64="${'$'}(wdtt_profile_value WG_VPS_PASSWORD_B64)"
    if wdtt_profile_has_key WG_VPS_PORT; then
      WG_VPS_PORT="${'$'}(wdtt_profile_value WG_VPS_PORT)"
    elif [ "${'$'}MODE" = "wireguard_vps" ]; then
      WG_VPS_PORT="${'$'}WG_ENDPOINT_PORT"
    else
      WG_VPS_PORT=""
    fi
    if wdtt_profile_has_key WG_VPS_DNS_B64; then
      WG_VPS_DNS_B64="${'$'}(wdtt_profile_value WG_VPS_DNS_B64)"
    elif [ "${'$'}MODE" = "wireguard_vps" ]; then
      WG_VPS_DNS_B64="${'$'}(wdtt_b64 "${'$'}WG_DNS")"
    else
      WG_VPS_DNS_B64=""
    fi
    if [ -n "${'$'}WG_VPS_HOST_B64" ]; then
      WG_PRESENT=1
    fi
    IMPORTED_WG_CONFIG_B64="${'$'}(wdtt_profile_value IMPORTED_WG_CONFIG_B64)"
    if [ "${'$'}MODE" = "imported_wg" ] && ! wdtt_profile_has_key IMPORTED_WG_CONFIG_B64 && [ -n "${'$'}WG_CONF" ]; then
      IMPORTED_WG_CONFIG_B64="${'$'}(wdtt_file_b64 "${'$'}WG_CONF")"
    fi
    [ -n "${'$'}IMPORTED_WG_CONFIG_B64" ] && WG_PRESENT=1
    WARP_PRESENT=0
    if [ -d /etc/wdtt-plus/warp ] ||
       [ -f /usr/local/bin/wgcf ] ||
       [ -f /usr/local/lib/wdtt/warp-watchdog ] ||
       [ -f /etc/systemd/system/wdtt-warp-watchdog.service ] ||
       [ -f /etc/systemd/system/wdtt-warp-watchdog.timer ]; then
      WARP_PRESENT=1
    fi
    WG_MATCHES_WARP=0
    if [ -n "${'$'}WG_CONF" ] &&
       [ -f /etc/wdtt-plus/warp/wgcf-profile.conf ] &&
       cmp -s "${'$'}WG_CONF" /etc/wdtt-plus/warp/wgcf-profile.conf; then
      WG_MATCHES_WARP=1
    fi

    TUN_CONFIG_INTERFACE="${'$'}(cat "${'$'}WDTT_TUN_CONFIG_FILE" 2>/dev/null | head -n 1)"
    TUN_INTERFACE="${'$'}TUN_CONFIG_INTERFACE"
    TUN_PROFILE_SAVED=0
    if wdtt_profile_has_key TUN_INTERFACE_B64; then
      TUN_INTERFACE_B64="${'$'}(wdtt_profile_value TUN_INTERFACE_B64)"
      [ -n "${'$'}TUN_INTERFACE_B64" ] && TUN_PROFILE_SAVED=1
      if [ -z "${'$'}TUN_INTERFACE" ] && [ -n "${'$'}TUN_INTERFACE_B64" ] && command -v base64 >/dev/null 2>&1; then
        TUN_INTERFACE="${'$'}(printf '%s' "${'$'}TUN_INTERFACE_B64" | base64 -d 2>/dev/null || true)"
      fi
    else
      TUN_INTERFACE_B64="${'$'}(wdtt_b64 "${'$'}TUN_INTERFACE")"
    fi
    TUN_OWNED=0
    [ "${'$'}(cat "${'$'}WDTT_TUN_OWNER_FILE" 2>/dev/null | head -n 1)" = WDTT_TUN_EXIT_V1 ] && TUN_OWNED=1
    TUN_PRESENT=0
    # Сохранённое поле имени нужно лишь для повторного включения. Оно не
    # означает, что WDTT уже настроил маршрутизацию через внешний TUN.
    if [ "${'$'}TUN_OWNED" = 1 ]; then
      TUN_PRESENT=1
    fi
    TUN_INTERFACE_ACTIVE=0
    if [ -n "${'$'}TUN_INTERFACE" ] && [ -d "/sys/class/net/${'$'}TUN_INTERFACE" ] &&
       ip -o link show dev "${'$'}TUN_INTERFACE" 2>/dev/null | grep -q '<[^>]*UP[,>]'; then
      TUN_INTERFACE_ACTIVE=1
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null; then
      TUN_SERVICE_ACTIVE=1
    else
      TUN_SERVICE_ACTIVE=0
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-enabled --quiet wdtt-tun-exit.service 2>/dev/null; then
      TUN_SERVICE_ENABLED=1
    else
      TUN_SERVICE_ENABLED=0
    fi
    TUN_POLICY_RULE_ACTIVE=0
    [ "${'$'}TUN_PRESENT" = 1 ] &&
      ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})" &&
      TUN_POLICY_RULE_ACTIVE=1
    TUN_DEFAULT_ROUTE_ACTIVE=0
    TUN_FAIL_CLOSED_ACTIVE=0
    if [ -n "${'$'}TUN_INTERFACE" ] &&
       ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null |
         grep -Eq "^default([[:space:]].*)? dev ${'$'}TUN_INTERFACE([[:space:]]|${'$'})"; then
      TUN_DEFAULT_ROUTE_ACTIVE=1
    fi
    ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null | grep -Eq '^unreachable default([[:space:]]|${'$'})' &&
      TUN_FAIL_CLOSED_ACTIVE=1
    TUN_FORWARD_RULES_ACTIVE=0
    if command -v iptables >/dev/null 2>&1 && [ -n "${'$'}TUN_INTERFACE" ] &&
       iptables -C FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}TUN_INTERFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null &&
       iptables -C FORWARD -i "${'$'}TUN_INTERFACE" -o "${'$'}WDTT_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null; then
      TUN_FORWARD_RULES_ACTIVE=1
    fi
    TUN_IP_FORWARD_ACTIVE=0
    [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" = "1" ] && TUN_IP_FORWARD_ACTIVE=1

    printf 'WDTT_OUTBOUND_MODE=%s\n' "${'$'}MODE"
    printf 'WDTT_OUTBOUND_DETAIL_B64=%s\n' "$(wdtt_b64 "${'$'}DETAIL")"
    printf 'WDTT_OUTBOUND_UPDATED_AT=%s\n' "${'$'}UPDATED_AT"
    printf 'WDTT_HAS_PROFILE=%s\n' "${'$'}HAS_PROFILE"
    printf 'WDTT_LOCAL_PROXY_PRESENT=%s\n' "${'$'}LOCAL_PRESENT"
    printf 'WDTT_LOCAL_PROXY_ACTIVE=%s\n' "${'$'}LOCAL_ACTIVE"
    printf 'WDTT_LOCAL_PROXY_SERVICE_ENABLED=%s\n' "${'$'}LOCAL_SERVICE_ENABLED"
    printf 'WDTT_LOCAL_PROXY_PORT=%s\n' "${'$'}LOCAL_PORT"
    printf 'WDTT_LOCAL_PROXY_LOGIN_B64=%s\n' "${'$'}LOCAL_LOGIN_B64"
    printf 'WDTT_LOCAL_PROXY_PASSWORD_B64=%s\n' "${'$'}LOCAL_PASSWORD_B64"
    printf 'WDTT_EXTERNAL_PROXY_PRESENT=%s\n' "${'$'}EXTERNAL_PRESENT"
    printf 'WDTT_EXTERNAL_PROXY_ACTIVE=%s\n' "${'$'}EXTERNAL_ACTIVE"
    printf 'WDTT_EXTERNAL_PROXY_SERVICE_ACTIVE=%s\n' "${'$'}EXTERNAL_SERVICE_ACTIVE"
    printf 'WDTT_EXTERNAL_PROXY_SERVICE_ENABLED=%s\n' "${'$'}EXTERNAL_SERVICE_ENABLED"
    printf 'WDTT_EXTERNAL_PROXY_ROUTE_ACTIVE=%s\n' "${'$'}EXTERNAL_ROUTE_ACTIVE"
    printf 'WDTT_EXTERNAL_PROXY_KIND_NAME=%s\n' "${'$'}EXTERNAL_KIND"
    printf 'WDTT_EXTERNAL_PROXY_HOST_B64=%s\n' "${'$'}EXTERNAL_HOST_B64"
    printf 'WDTT_EXTERNAL_PROXY_PORT=%s\n' "${'$'}EXTERNAL_PORT"
    printf 'WDTT_EXTERNAL_PROXY_LOGIN_B64=%s\n' "${'$'}EXTERNAL_LOGIN_B64"
    printf 'WDTT_EXTERNAL_PROXY_PASSWORD_B64=%s\n' "${'$'}EXTERNAL_PASSWORD_B64"
    printf 'WDTT_EXTERNAL_PROXY_PROFILE_SAVED=%s\n' "${'$'}EXTERNAL_PROXY_PROFILE_SAVED"
    printf 'WDTT_WG_PRESENT=%s\n' "${'$'}WG_PRESENT"
    printf 'WDTT_WG_ACTIVE=%s\n' "${'$'}WG_ACTIVE"
    printf 'WDTT_WG_INTERFACE_ACTIVE=%s\n' "${'$'}WG_INTERFACE_ACTIVE"
    printf 'WDTT_WG_SERVICE_ACTIVE=%s\n' "${'$'}WG_SERVICE_ACTIVE"
    printf 'WDTT_WG_SERVICE_ENABLED=%s\n' "${'$'}WG_SERVICE_ENABLED"
    printf 'WDTT_WG_POLICY_RULE_ACTIVE=%s\n' "${'$'}WG_POLICY_RULE_ACTIVE"
    printf 'WDTT_WG_DEFAULT_ROUTE_ACTIVE=%s\n' "${'$'}WG_DEFAULT_ROUTE_ACTIVE"
    printf 'WDTT_WG_NAT_ACTIVE=%s\n' "${'$'}WG_NAT_ACTIVE"
    printf 'WDTT_WG_OWNER_MODE=%s\n' "${'$'}WG_OWNER_MODE"
    printf 'WDTT_WG_CONFIG_OWNER_MODE=%s\n' "${'$'}WG_CONFIG_OWNER_MODE"
    printf 'WDTT_WG_MATCHES_WARP=%s\n' "${'$'}WG_MATCHES_WARP"
    printf 'WDTT_WG_VPS_HOST_B64=%s\n' "${'$'}WG_VPS_HOST_B64"
    printf 'WDTT_WG_VPS_SSH_PORT=%s\n' "${'$'}WG_VPS_SSH_PORT"
    printf 'WDTT_WG_VPS_USER_B64=%s\n' "${'$'}WG_VPS_USER_B64"
    printf 'WDTT_WG_VPS_PASSWORD_B64=%s\n' "${'$'}WG_VPS_PASSWORD_B64"
    printf 'WDTT_WG_VPS_PORT=%s\n' "${'$'}WG_VPS_PORT"
    printf 'WDTT_WG_VPS_DNS_B64=%s\n' "${'$'}WG_VPS_DNS_B64"
    printf 'WDTT_WARP_PRESENT=%s\n' "${'$'}WARP_PRESENT"
    printf 'WDTT_WARP_MTU=%s\n' "${'$'}WG_MTU"
    printf 'WDTT_IMPORTED_WG_CONFIG_B64=%s\n' "${'$'}IMPORTED_WG_CONFIG_B64"
    printf 'WDTT_TUN_INTERFACE_B64=%s\n' "${'$'}TUN_INTERFACE_B64"
    printf 'WDTT_TUN_PROFILE_SAVED=%s\n' "${'$'}TUN_PROFILE_SAVED"
    printf 'WDTT_TUN_PRESENT=%s\n' "${'$'}TUN_PRESENT"
    printf 'WDTT_TUN_INTERFACE_ACTIVE=%s\n' "${'$'}TUN_INTERFACE_ACTIVE"
    printf 'WDTT_TUN_SERVICE_ACTIVE=%s\n' "${'$'}TUN_SERVICE_ACTIVE"
    printf 'WDTT_TUN_SERVICE_ENABLED=%s\n' "${'$'}TUN_SERVICE_ENABLED"
    printf 'WDTT_TUN_POLICY_RULE_ACTIVE=%s\n' "${'$'}TUN_POLICY_RULE_ACTIVE"
    printf 'WDTT_TUN_DEFAULT_ROUTE_ACTIVE=%s\n' "${'$'}TUN_DEFAULT_ROUTE_ACTIVE"
    printf 'WDTT_TUN_FAIL_CLOSED_ACTIVE=%s\n' "${'$'}TUN_FAIL_CLOSED_ACTIVE"
    printf 'WDTT_TUN_FORWARD_RULES_ACTIVE=%s\n' "${'$'}TUN_FORWARD_RULES_ACTIVE"
    printf 'WDTT_TUN_IP_FORWARD_ACTIVE=%s\n' "${'$'}TUN_IP_FORWARD_ACTIVE"
    """
)

private suspend fun readOutboundServerSnapshot(
    context: Context,
    target: OutboundSshTarget
): OutboundServerSnapshot {
    DeployManager.updateProgress(0.25f, "Подключаюсь к серверу и ищу профиль выходного IP...")
    val output = runRootScript(
        context = context,
        target = target,
        script = outboundSnapshotScript(),
        timeout = 30000L,
        safeReadOnly = true,
        relayOperation = listOf("outbound-snapshot"),
    )
    DeployManager.updateProgress(1f, "Настройки выходного IP прочитаны.")
    return parseOutboundServerSnapshot(output)
}

internal fun parseOutboundServerSnapshot(output: String): OutboundServerSnapshot {
    fun value(name: String): String = markerValue(output, name).orEmpty()
    fun decoded(name: String): String {
        val raw = value(name)
        if (raw.isBlank()) return ""
        return runCatching { decodeBase64Text(raw) }.getOrDefault("")
    }
    fun flag(name: String): Boolean = value(name) == "1"
    return OutboundServerSnapshot(
        mode = value("WDTT_OUTBOUND_MODE").ifBlank { "direct" },
        detail = decoded("WDTT_OUTBOUND_DETAIL_B64"),
        updatedAt = value("WDTT_OUTBOUND_UPDATED_AT"),
        hasProfile = flag("WDTT_HAS_PROFILE"),
        localProxyPresent = flag("WDTT_LOCAL_PROXY_PRESENT"),
        localProxyActive = flag("WDTT_LOCAL_PROXY_ACTIVE"),
        localProxyPort = value("WDTT_LOCAL_PROXY_PORT"),
        localProxyLogin = decoded("WDTT_LOCAL_PROXY_LOGIN_B64"),
        localProxyPassword = decoded("WDTT_LOCAL_PROXY_PASSWORD_B64"),
        externalProxyPresent = flag("WDTT_EXTERNAL_PROXY_PRESENT"),
        externalProxyActive = flag("WDTT_EXTERNAL_PROXY_ACTIVE"),
        externalProxyKindName = value("WDTT_EXTERNAL_PROXY_KIND_NAME").takeIf { name -> ProxyKind.entries.any { it.name == name } }.orEmpty(),
        externalProxyHost = decoded("WDTT_EXTERNAL_PROXY_HOST_B64"),
        externalProxyPort = value("WDTT_EXTERNAL_PROXY_PORT"),
        externalProxyLogin = decoded("WDTT_EXTERNAL_PROXY_LOGIN_B64"),
        externalProxyPassword = decoded("WDTT_EXTERNAL_PROXY_PASSWORD_B64"),
        externalProxyProfileSaved = flag("WDTT_EXTERNAL_PROXY_PROFILE_SAVED"),
        wireGuardPresent = flag("WDTT_WG_PRESENT"),
        wireGuardActive = flag("WDTT_WG_ACTIVE"),
        wireGuardExitHost = decoded("WDTT_WG_VPS_HOST_B64"),
        wireGuardExitSshPort = value("WDTT_WG_VPS_SSH_PORT"),
        wireGuardExitUser = decoded("WDTT_WG_VPS_USER_B64"),
        wireGuardExitPassword = decoded("WDTT_WG_VPS_PASSWORD_B64"),
        wireGuardExitPort = value("WDTT_WG_VPS_PORT"),
        wireGuardExitDns = decoded("WDTT_WG_VPS_DNS_B64"),
        warpPresent = flag("WDTT_WARP_PRESENT"),
        warpMtu = value("WDTT_WARP_MTU"),
        importedWireGuardConfig = decoded("WDTT_IMPORTED_WG_CONFIG_B64"),
        externalProxyServiceActive = flag("WDTT_EXTERNAL_PROXY_SERVICE_ACTIVE"),
        externalProxyRouteActive = flag("WDTT_EXTERNAL_PROXY_ROUTE_ACTIVE"),
        wireGuardInterfaceActive = flag("WDTT_WG_INTERFACE_ACTIVE"),
        wireGuardServiceActive = flag("WDTT_WG_SERVICE_ACTIVE"),
        wireGuardPolicyRuleActive = flag("WDTT_WG_POLICY_RULE_ACTIVE"),
        wireGuardDefaultRouteActive = flag("WDTT_WG_DEFAULT_ROUTE_ACTIVE"),
        wireGuardNatActive = flag("WDTT_WG_NAT_ACTIVE"),
        wireGuardOwnerMode = value("WDTT_WG_OWNER_MODE"),
        wireGuardConfigOwnerMode = value("WDTT_WG_CONFIG_OWNER_MODE"),
        wireGuardMatchesWarp = flag("WDTT_WG_MATCHES_WARP"),
        localProxyServiceEnabled = flag("WDTT_LOCAL_PROXY_SERVICE_ENABLED"),
        externalProxyServiceEnabled = flag("WDTT_EXTERNAL_PROXY_SERVICE_ENABLED"),
        wireGuardServiceEnabled = flag("WDTT_WG_SERVICE_ENABLED"),
        tunInterface = decoded("WDTT_TUN_INTERFACE_B64"),
        tunProfileSaved = flag("WDTT_TUN_PROFILE_SAVED"),
        tunPresent = flag("WDTT_TUN_PRESENT"),
        tunInterfaceActive = flag("WDTT_TUN_INTERFACE_ACTIVE"),
        tunServiceActive = flag("WDTT_TUN_SERVICE_ACTIVE"),
        tunServiceEnabled = flag("WDTT_TUN_SERVICE_ENABLED"),
        tunPolicyRuleActive = flag("WDTT_TUN_POLICY_RULE_ACTIVE"),
        tunDefaultRouteActive = flag("WDTT_TUN_DEFAULT_ROUTE_ACTIVE"),
        tunForwardRulesActive = flag("WDTT_TUN_FORWARD_RULES_ACTIVE"),
        tunIpForwardActive = flag("WDTT_TUN_IP_FORWARD_ACTIVE"),
        tunFailClosedActive = flag("WDTT_TUN_FAIL_CLOSED_ACTIVE")
    )
}

private fun outboundRestoreSummary(snapshot: OutboundServerSnapshot): String {
    val parts = mutableListOf<String>()
    parts += "Поля выходного IP прочитаны с сервера."
    parts += "Активный режим: ${snapshot.modeLabel}."
    if (snapshot.hasProfile) parts += "Сохранённый профиль найден."
    if (snapshot.localProxyPresent) {
        parts += if (snapshot.localProxyActive) "Прокси на этом VPS найден и запущен." else "Прокси на этом VPS найден, служба не запущена."
    }
    if (snapshot.externalProxyPresent) {
        parts += if (snapshot.externalProxyActive) "Внешний TCP-прокси включён." else "Поля внешнего TCP-прокси заполнены."
    } else if (snapshot.externalProxyProfileSaved) {
        parts += "Сохранены только поля внешнего TCP-прокси; служба и маршрутизация WDTT не настроены."
    }
    if (snapshot.wireGuardPresent) {
        parts += if (snapshot.wireGuardActive) "WireGuard-выход найден и запущен." else "Поля WireGuard-выхода заполнены."
    }
    if (snapshot.tunPresent) {
        parts += if (snapshot.tunHealthy) {
            "Выход через TUN-интерфейс ${snapshot.tunInterface} найден и запущен."
        } else {
            "Настройка TUN-интерфейса ${snapshot.tunInterface.ifBlank { "без имени" }} найдена, но маршрут сейчас не активен."
        }
    } else if (snapshot.tunProfileSaved) {
        parts += "Сохранено только имя TUN-интерфейса для повторного включения; маршрутизация WDTT через него не настроена."
    }
    if (snapshot.mode == "warp_free") {
        parts += if (snapshot.wireGuardActive) {
            "Бесплатный WARP найден; откройте его окно для проверки Cloudflare и автопроверки."
        } else {
            "Профиль бесплатного WARP найден, но WireGuard сейчас не запущен. Нажмите «Установить / восстановить»."
        }
    }
    if (snapshot.localProxyPresent && snapshot.localProxyPassword.isBlank()) {
        parts += "Пароль прокси не найден в старом серверном конфиге; введите его вручную."
    }
    if (snapshot.mode == "wireguard_vps" && snapshot.wireGuardExitPassword.isBlank()) {
        parts += "SSH-пароль второго сервера в сохранённом профиле не найден; введите пароль или локально добавьте приватный SSH-ключ."
    }
    return parts.joinToString(" ")
}

private suspend fun checkWireGuardExit(
    target: OutboundSshTarget,
    expectedMode: String
): String = withContext(Dispatchers.IO) {
    DeployManager.updateProgress(0.25f, "Подключаюсь к серверу и проверяю WireGuard-выход...")
    val expectedLabel = when (expectedMode) {
        "wireguard_vps" -> "выход через другой сервер"
        "warp_free" -> "бесплатный WARP"
        "imported_wg" -> "VPN/WireGuard-файл"
        else -> expectedMode
    }
    val script = shellScript(
            outboundShellPrelude(),
            """
            MODE="${'$'}(grep -o '"outboundMode"[[:space:]]*:[[:space:]]*"[^"]*"' /etc/wdtt/outbound.json 2>/dev/null | sed 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"//;s/".*//' | head -n 1)"
            [ -n "${'$'}MODE" ] || MODE="direct"
            case "${'$'}MODE" in
              warp_free) MODE_LABEL="бесплатный WARP";;
              imported_wg) MODE_LABEL="VPN/WireGuard-файл";;
              wireguard_vps) MODE_LABEL="выход через другой сервер";;
              external_proxy) MODE_LABEL="внешний TCP-прокси";;
              direct) MODE_LABEL="прямой выход";;
              *) MODE_LABEL="${'$'}MODE";;
            esac
            echo "Активный режим WDTT: ${'$'}MODE_LABEL"
            if [ "${'$'}MODE" != ${shellQuote(expectedMode)} ]; then
              echo "Предупреждение: сейчас включён не режим «$expectedLabel»."
            fi
            command -v wg >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_tools_required; exit 2; }
            wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_not_active; exit 3; }
            echo "WireGuard ${'$'}WDTT_WG_IFACE запущен."
            TEST_SOURCE="${'$'}(wdtt_test_source)"
            [ -n "${'$'}TEST_SOURCE" ] || { echo WDTT_ERROR=wdtt_test_source_missing; exit 3; }
            EXIT_IP="${'$'}(wdtt_routed_public_ip "${'$'}TEST_SOURCE" "wg-wdtt-exit" || true)"
            if [ -n "${'$'}EXIT_IP" ]; then
              echo "Проверка маршрута сервера через WireGuard успешна. Проверочный IP: ${'$'}EXIT_IP"
            else
              echo WDTT_ERROR=wireguard_exit_check_failed
              exit 3
            fi
            wg show "${'$'}WDTT_WG_IFACE" | sed -E 's/(private key: ).*/\1(скрыт)/' || true
            """
        )
    DeployManager.updateProgress(0.70f, "Проверяю внешний IP через WireGuard-интерфейс...")
    val output = runSafeInspection(target, listOf("check-wireguard", expectedMode), 30_000L) { ssh ->
        ssh.exec(rootCommand(script), timeout = 30000L)
    }.trim()
    markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException(it) }
    DeployManager.updateProgress(1f, "WireGuard-выход проверен.")
    output
}

private suspend fun readOutboundDiagnostics(target: OutboundSshTarget): String = withContext(Dispatchers.IO) {
    DeployManager.updateProgress(0.20f, "Подключаюсь к серверу для диагностики...")
    DeployManager.updateProgress(0.45f, "Читаю режим выхода, службы и внешний IP...")
    val script = shellScript(
            outboundStatusScript(),
            """
            echo
            echo "Правила, которые выбирают выход для WDTT-пользователей:"
            ROUTE_RULES="${'$'}(ip rule show | grep -E 'lookup (100|110|wdtt)|10\.66\.66' || true)"
            if [ -n "${'$'}ROUTE_RULES" ]; then
              printf '%s\n' "${'$'}ROUTE_RULES"
            else
              echo "Отдельных правил выбора маршрута для WDTT сейчас нет."
            fi
            echo
            echo "Маршрутные таблицы WDTT-пользователей:"
            WDTT_ROUTES="${'$'}({ ip route show table 100 2>/dev/null; ip route show table 110 2>/dev/null; } || true)"
            if [ -n "${'$'}WDTT_ROUTES" ]; then
              printf '%s\n' "${'$'}WDTT_ROUTES"
            else
              echo "Маршрутная таблица WDTT сейчас пуста."
            fi
            echo
            echo "Правила перенаправления через прокси, WireGuard или TUN:"
            REDIRECT_RULES="${'$'}({ iptables -t nat -S 2>/dev/null; iptables -S FORWARD 2>/dev/null; } | grep -E 'WDTT_PROXY_OUT|WDTT_EXIT|WDTT_LOCAL_PROXY|WDTT_TUN_EXIT' || true)"
            if [ -n "${'$'}REDIRECT_RULES" ]; then
              printf '%s\n' "${'$'}REDIRECT_RULES"
            else
              echo "Управляемых правил WDTT через прокси, WireGuard или TUN сейчас нет."
            fi
            echo
            echo "Служба внешнего TCP-прокси WDTT:"
            systemctl status wdtt-redsocks --no-pager -l 2>/dev/null | sed -n '1,12p' || echo "Служба wdtt-redsocks не найдена или systemctl недоступен."
            echo
            echo "Служба существующего TUN-интерфейса WDTT:"
            systemctl status wdtt-tun-exit --no-pager -l 2>/dev/null | sed -n '1,12p' || echo "Служба wdtt-tun-exit не найдена или systemctl недоступен."
            echo
            echo "Локальный порт redsocks:"
            if command -v ss >/dev/null 2>&1; then
              REDSOCKS_LISTEN="${'$'}(ss -ltnp 2>/dev/null | grep ':12345' || true)"
              if [ -n "${'$'}REDSOCKS_LISTEN" ]; then
                printf '%s\n' "${'$'}REDSOCKS_LISTEN"
                if printf '%s\n' "${'$'}REDSOCKS_LISTEN" | grep -q '127\\.0\\.0\\.1:12345'; then
                  echo "Внимание: redsocks слушает только 127.0.0.1. Для трафика WDTT из PREROUTING нужен 0.0.0.0:12345, иначе пинги могут работать, а сайты у пользователей не открываться."
                fi
              else
                echo "redsocks сейчас не слушает порт 12345."
              fi
            else
              echo "Команда ss недоступна."
            fi
            echo
            echo "Последние сообщения redsocks:"
            tail -n 20 /var/log/wdtt-redsocks.log 2>/dev/null || echo "Лог redsocks пуст или недоступен."
            """
        )
    DeployManager.updateProgress(0.75f, "Собираю маршруты и правила перенаправления...")
    val output = runSafeInspection(target, listOf("outbound-diagnostics"), 30_000L) { ssh ->
        ssh.exec(rootCommand(script), timeout = 30000L)
    }.trim()
    DeployManager.updateProgress(1f, "Диагностика собрана.")
    output
}

internal fun disableOutboundExitScript(): String = shellScript(
    outboundShellPrelude(),
    """
    MODE_BEFORE_CLEANUP="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
    TUN_OWNED=0
    [ "${'$'}(cat "${'$'}WDTT_TUN_OWNER_FILE" 2>/dev/null | head -n 1)" = WDTT_TUN_EXIT_V1 ] && TUN_OWNED=1
    echo "WDTT_PROGRESS|0.25|Останавливаю внешний TCP-прокси, WireGuard- и TUN-выход, если они включены..."
    wdtt_clear_external_out
    if command -v wg >/dev/null 2>&1 && wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1; then
      ip link delete "${'$'}WDTT_WG_IFACE" 2>/dev/null || true
    fi
    while ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TABLE" priority 100 2>/dev/null; do :; done
    ip route flush table "${'$'}WDTT_TABLE" 2>/dev/null || true
    CLEANUP_LEFT=0
    (command -v wg >/dev/null 2>&1 && wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1) && CLEANUP_LEFT=1
    systemctl is-active --quiet wdtt-wg-exit.service 2>/dev/null && CLEANUP_LEFT=1
    systemctl is-active --quiet wdtt-redsocks.service 2>/dev/null && CLEANUP_LEFT=1
    ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TABLE([[:space:]]|${'$'})|from [^ ]+ lookup wdtt-exit([[:space:]]|${'$'})" &&
      CLEANUP_LEFT=1
    ip route show table "${'$'}WDTT_TABLE" 2>/dev/null | grep -Eq "^default([[:space:]].*)? dev ${'$'}WDTT_WG_IFACE([[:space:]]|${'$'})" &&
      CLEANUP_LEFT=1
    if [ "${'$'}TUN_OWNED" = 1 ]; then
      systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null && CLEANUP_LEFT=1
      ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})" && CLEANUP_LEFT=1
      [ -n "${'$'}(ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null)" ] && CLEANUP_LEFT=1
    elif [ "${'$'}MODE_BEFORE_CLEANUP" = "tun_interface" ]; then
      CLEANUP_LEFT=1
    fi
    if command -v iptables >/dev/null 2>&1; then
      iptables -t nat -S PREROUTING 2>/dev/null | grep -q -- "-i ${'$'}WDTT_IFACE .* -j WDTT_PROXY_OUT" &&
        CLEANUP_LEFT=1
      iptables -t nat -S POSTROUTING 2>/dev/null | grep -q -- "-o ${'$'}WDTT_WG_IFACE .*--comment WDTT_EXIT .*MASQUERADE" &&
        CLEANUP_LEFT=1
      if [ "${'$'}TUN_OWNED" = 1 ]; then
        iptables -S FORWARD 2>/dev/null | grep -q -- '--comment WDTT_TUN_EXIT' && CLEANUP_LEFT=1
      fi
    fi
    if [ "${'$'}CLEANUP_LEFT" != 0 ]; then
      echo WDTT_ERROR=direct_cleanup_failed
      exit 3
    fi
    echo "WDTT_PROGRESS|0.75|Сохраняю режим прямого выхода через текущий сервер..."
    wdtt_write_mode "direct" "прямой выход"
    echo "WDTT_PROGRESS|1.0|Прямой выход включён."
    echo "Внешний TCP-прокси, WireGuard- и TUN-выход отключены. WDTT-пользователи снова идут напрямую через текущий сервер."
    """
)

private suspend fun disableOutboundExit(target: OutboundSshTarget): String = withContext(Dispatchers.IO) {
    runCheckedRootScript(target, disableOutboundExitScript(), timeout = 30000L)
}

internal fun buildTunInterfaceExitScript(interfaceName: String): String {
    require(tunInterfaceNameIssue(interfaceName) == null) {
        tunInterfaceNameIssue(interfaceName) ?: "некорректное имя TUN-интерфейса"
    }
    val quotedInterface = shellQuote(interfaceName.trim())
    return shellScript(
        outboundShellPrelude(),
        """
        TUN_IFACE=$quotedInterface
        command -v ip >/dev/null 2>&1 || { echo WDTT_ERROR=iproute_required; exit 2; }
        command -v iptables >/dev/null 2>&1 || { echo WDTT_ERROR=iptables_required; exit 2; }
        command -v systemctl >/dev/null 2>&1 || { echo WDTT_ERROR=systemd_required; exit 2; }
        command -v curl >/dev/null 2>&1 || { echo WDTT_ERROR=curl_not_installed; exit 2; }
        [ -d "/sys/class/net/${'$'}WDTT_IFACE" ] || { echo WDTT_ERROR=wdtt_iface_not_found; exit 2; }
        [ -d "/sys/class/net/${'$'}TUN_IFACE" ] || { echo WDTT_ERROR=tun_interface_not_found; exit 2; }
        ip -o link show dev "${'$'}TUN_IFACE" 2>/dev/null | grep -q '<[^>]*UP[,>]' ||
          { echo WDTT_ERROR=tun_interface_not_up; exit 2; }
        EXT_IFACE="${'$'}(wdtt_ext_iface)"
        [ -n "${'$'}EXT_IFACE" ] || { echo WDTT_ERROR=external_interface_not_found; exit 2; }
        [ "${'$'}TUN_IFACE" != "${'$'}EXT_IFACE" ] || { echo WDTT_ERROR=tun_interface_is_primary; exit 2; }
        TUN_OWNED=0
        [ "${'$'}(cat "${'$'}WDTT_TUN_OWNER_FILE" 2>/dev/null | head -n 1)" = WDTT_TUN_EXIT_V1 ] && TUN_OWNED=1
        if [ "${'$'}TUN_OWNED" = 0 ]; then
          TUN_COLLISION=0
          [ -e /etc/systemd/system/wdtt-tun-exit.service ] && TUN_COLLISION=1
          [ -e /usr/local/lib/wdtt/tun-exit-route ] && TUN_COLLISION=1
          [ -e /usr/local/lib/wdtt/tun-exit-watch ] && TUN_COLLISION=1
          [ -e "${'$'}WDTT_TUN_CONFIG_FILE" ] && TUN_COLLISION=1
          ip rule show 2>/dev/null | grep -Eq "lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})" && TUN_COLLISION=1
          [ -n "${'$'}(ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null)" ] && TUN_COLLISION=1
          iptables -S FORWARD 2>/dev/null | grep -q -- '--comment WDTT_TUN_EXIT' && TUN_COLLISION=1
          [ "${'$'}TUN_COLLISION" = 0 ] || { echo WDTT_ERROR=tun_exit_ownership_conflict; exit 2; }
        fi

        echo "WDTT_PROGRESS|0.20|Отключаю прежний внешний выход WDTT..."
        wdtt_clear_external_out
        TUN_COMMITTED=0
        tun_rollback() {
          [ "${'$'}TUN_COMMITTED" = 1 ] && return 0
          wdtt_clear_tun_out >/dev/null 2>&1 || true
          wdtt_write_mode "direct" "rollback after TUN setup error" >/dev/null 2>&1 || true
        }
        trap tun_rollback EXIT
        if [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" != "1" ]; then
          printf '1\n' >/proc/sys/net/ipv4/ip_forward 2>/dev/null || true
        fi
        if [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" != "1" ]; then
          wdtt_write_mode "direct" "rollback after IPv4 forwarding error"
          echo WDTT_ERROR=tun_ip_forward_failed
          exit 3
        fi
        echo "WDTT_PROGRESS|0.42|Создаю управляемые маршруты через ${'$'}TUN_IFACE..."
        mkdir -p /usr/local/lib/wdtt /etc/wdtt-plus/tun-exit
        printf '%s\n' WDTT_TUN_EXIT_V1 >"${'$'}WDTT_TUN_OWNER_FILE"
        printf '%s\n' "${'$'}TUN_IFACE" >"${'$'}WDTT_TUN_CONFIG_FILE"
        chmod 600 "${'$'}WDTT_TUN_OWNER_FILE" "${'$'}WDTT_TUN_CONFIG_FILE"

        cat >/usr/local/lib/wdtt/tun-exit-route <<'WDTT_TUN_ROUTE'
        #!/bin/sh
        set -u
        WDTT_IFACE=wdtt0
        TUN_IFACE=$quotedInterface
        WDTT_TUN_TABLE=110
        WDTT_TUN_PRIORITY=90
        WDTT_SUBNET="${'$'}(ip -4 route show dev "${'$'}WDTT_IFACE" scope link 2>/dev/null | awk '{print ${'$'}1; exit}')"
        [ -n "${'$'}WDTT_SUBNET" ] || WDTT_SUBNET=10.66.66.0/24
        clear_forward_rules() {
          if command -v iptables >/dev/null 2>&1; then
            while iptables -D FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}TUN_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null; do :; done
            while iptables -D FORWARD -i "${'$'}TUN_IFACE" -o "${'$'}WDTT_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null; do :; done
          fi
        }
        policy_rule_present() {
          ip rule show 2>/dev/null |
            grep -Eq "from ${'$'}WDTT_SUBNET lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})|from ${'$'}WDTT_SUBNET table ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})"
        }
        blocked_status() {
          policy_rule_present || return 1
          ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null |
            grep -Eq '^unreachable default([[:space:]]|${'$'})'
        }
        cleanup() {
          while ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TUN_TABLE" priority "${'$'}WDTT_TUN_PRIORITY" 2>/dev/null; do :; done
          ip route flush table "${'$'}WDTT_TUN_TABLE" 2>/dev/null || true
          clear_forward_rules
        }
        status() {
          [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" = "1" ] || return 1
          [ -d "/sys/class/net/${'$'}WDTT_IFACE" ] || return 1
          [ -d "/sys/class/net/${'$'}TUN_IFACE" ] || return 1
          ip -o link show dev "${'$'}TUN_IFACE" 2>/dev/null | grep -q '<[^>]*UP[,>]' || return 1
          ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})" || return 1
          ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null |
            grep -Eq "^default([[:space:]].*)? dev ${'$'}TUN_IFACE([[:space:]]|${'$'})" || return 1
          iptables -C FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}TUN_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null || return 1
          iptables -C FORWARD -i "${'$'}TUN_IFACE" -o "${'$'}WDTT_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT 2>/dev/null || return 1
        }
        block() {
          blocked_status && return 0
          ip route replace unreachable default table "${'$'}WDTT_TUN_TABLE" || exit 1
          policy_rule_present ||
            ip rule add from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TUN_TABLE" priority "${'$'}WDTT_TUN_PRIORITY" || exit 1
          blocked_status
        }
        case "${'$'}{1:-}" in
          block)
            block
            ;;
          down)
            cleanup
            ;;
          status)
            status || exit 1
            ;;
          up)
            if [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" != "1" ]; then
              printf '1\n' >/proc/sys/net/ipv4/ip_forward 2>/dev/null || exit 1
            fi
            [ "${'$'}(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)" = "1" ] || exit 1
            [ -d "/sys/class/net/${'$'}WDTT_IFACE" ] || exit 1
            [ -d "/sys/class/net/${'$'}TUN_IFACE" ] || exit 1
            ip -o link show dev "${'$'}TUN_IFACE" 2>/dev/null | grep -q '<[^>]*UP[,>]' || exit 1
            EXT_IFACE="${'$'}(ip -o route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if (${'$'}i=="dev") {print ${'$'}(i+1); exit}}')"
            [ -n "${'$'}EXT_IFACE" ] && [ "${'$'}TUN_IFACE" != "${'$'}EXT_IFACE" ] || exit 1
            # Сначала удерживаем подсеть на unreachable-маршруте. Рабочий
            # default через TUN ставим только после полной подготовки правил.
            ip route replace unreachable default table "${'$'}WDTT_TUN_TABLE" || exit 1
            policy_rule_present ||
              ip rule add from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TUN_TABLE" priority "${'$'}WDTT_TUN_PRIORITY" || exit 1
            trap 'code=${'$'}?; trap - 0; [ "${'$'}code" -eq 0 ] || block; exit "${'$'}code"' 0
            clear_forward_rules
            iptables -I FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}TUN_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT || exit 1
            iptables -I FORWARD -i "${'$'}TUN_IFACE" -o "${'$'}WDTT_IFACE" -m comment --comment WDTT_TUN_EXIT -j ACCEPT || exit 1
            ip route replace default dev "${'$'}TUN_IFACE" table "${'$'}WDTT_TUN_TABLE" || exit 1
            status || exit 1
            trap - 0
            ;;
          *) exit 2;;
        esac
        WDTT_TUN_ROUTE

        cat >/usr/local/lib/wdtt/tun-exit-watch <<'WDTT_TUN_WATCH'
        #!/bin/sh
        set -u
        ROUTE_HELPER=/usr/local/lib/wdtt/tun-exit-route
        STATE_FILE=/run/wdtt-tun-exit.state
        report_state() {
          new_state="${'$'}1"
          old_state="${'$'}(cat "${'$'}STATE_FILE" 2>/dev/null || true)"
          [ "${'$'}new_state" = "${'$'}old_state" ] && return 0
          printf '%s\n' "${'$'}new_state" >"${'$'}STATE_FILE" 2>/dev/null || true
          if command -v logger >/dev/null 2>&1; then
            case "${'$'}new_state" in
              active) logger -t wdtt-tun-exit "TUN route is active";;
              blocked) logger -t wdtt-tun-exit "TUN unavailable; WDTT direct exit is blocked";;
              error) logger -t wdtt-tun-exit "TUN unavailable; fail-closed route could not be confirmed";;
            esac
          fi
        }
        stop_watch() {
          trap - INT TERM EXIT
          "${'$'}ROUTE_HELPER" down >/dev/null 2>&1 || true
          rm -f "${'$'}STATE_FILE"
          exit 0
        }
        trap stop_watch INT TERM EXIT
        while :; do
          if "${'$'}ROUTE_HELPER" status >/dev/null 2>&1; then
            report_state active
          elif "${'$'}ROUTE_HELPER" up >/dev/null 2>&1; then
            report_state active
          elif "${'$'}ROUTE_HELPER" block >/dev/null 2>&1; then
            report_state blocked
          else
            report_state error
          fi
          sleep 10 &
          wait "${'$'}!"
        done
        WDTT_TUN_WATCH
        chmod 700 /usr/local/lib/wdtt/tun-exit-route /usr/local/lib/wdtt/tun-exit-watch

        cat >/etc/systemd/system/wdtt-tun-exit.service <<'WDTT_TUN_SERVICE'
        [Unit]
        Description=WDTT Plus policy-routed existing TUN exit
        After=network-online.target wdtt.service
        Wants=network-online.target

        [Service]
        Type=simple
        ExecStart=/usr/local/lib/wdtt/tun-exit-watch
        ExecStop=/usr/local/lib/wdtt/tun-exit-route down
        Restart=always
        RestartSec=5s

        [Install]
        WantedBy=multi-user.target
        WDTT_TUN_SERVICE
        systemctl daemon-reload
        if ! systemctl enable --now wdtt-tun-exit.service >/tmp/wdtt-tun-exit-start.log 2>&1; then
          tail -n 12 /tmp/wdtt-tun-exit-start.log 2>/dev/null || true
          wdtt_clear_tun_out
          wdtt_write_mode "direct" "rollback after TUN service start error"
          rm -f /tmp/wdtt-tun-exit-start.log
          echo WDTT_ERROR=tun_exit_service_inactive
          exit 3
        fi
        TUN_READY=0
        for attempt in 1 2 3 4 5 6 7 8 9 10; do
          if systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null &&
             /usr/local/lib/wdtt/tun-exit-route status >/dev/null 2>&1; then
            TUN_READY=1
            break
          fi
          sleep 1
        done
        if [ "${'$'}TUN_READY" != 1 ]; then
          tail -n 12 /tmp/wdtt-tun-exit-start.log 2>/dev/null || true
          wdtt_clear_tun_out
          wdtt_write_mode "direct" "rollback after TUN route error"
          rm -f /tmp/wdtt-tun-exit-start.log
          echo WDTT_ERROR=tun_exit_route_inactive
          exit 3
        fi
        rm -f /tmp/wdtt-tun-exit-start.log
        echo "WDTT_PROGRESS|0.78|Проверяю реальный выход трафика WDTT через ${'$'}TUN_IFACE..."
        TEST_SOURCE="${'$'}(wdtt_test_source)"
        [ -n "${'$'}TEST_SOURCE" ] || {
          wdtt_clear_tun_out
          wdtt_write_mode "direct" "rollback after missing WDTT test source"
          echo WDTT_ERROR=wdtt_test_source_missing
          exit 3
        }
        EXIT_IP="${'$'}(wdtt_routed_public_ip "${'$'}TEST_SOURCE" "${'$'}TUN_IFACE" || true)"
        if [ -z "${'$'}EXIT_IP" ]; then
          wdtt_clear_tun_out
          wdtt_write_mode "direct" "rollback after TUN traffic test error"
          echo WDTT_ERROR=tun_exit_check_failed
          exit 3
        fi
        echo "WDTT_PROGRESS|0.94|Сохраняю режим выхода через TUN-интерфейс..."
        wdtt_write_mode "tun_interface" "${'$'}TUN_IFACE"
        TUN_COMMITTED=1
        trap - EXIT
        echo "WDTT_PROGRESS|1.0|Выход через TUN-интерфейс включён."
        echo "TUN-выход включён только для WDTT-пользователей. Интерфейс: ${'$'}TUN_IFACE. Проверочный IP: ${'$'}EXIT_IP"
        """
    )
}

private suspend fun enableTunInterface(
    target: OutboundSshTarget,
    interfaceName: String
): String = withContext(Dispatchers.IO) {
    runCheckedRootScript(target, buildTunInterfaceExitScript(interfaceName), timeout = 60000L)
}

internal fun checkTunInterfaceExitScript(interfaceName: String): String {
    require(tunInterfaceNameIssue(interfaceName) == null) {
        tunInterfaceNameIssue(interfaceName) ?: "некорректное имя TUN-интерфейса"
    }
    return shellScript(
        outboundShellPrelude(),
        """
        TUN_IFACE=${shellQuote(interfaceName.trim())}
        [ "${'$'}(cat "${'$'}WDTT_TUN_OWNER_FILE" 2>/dev/null | head -n 1)" = WDTT_TUN_EXIT_V1 ] ||
          { echo WDTT_ERROR=tun_exit_not_owned; exit 3; }
        [ "${'$'}(cat "${'$'}WDTT_TUN_CONFIG_FILE" 2>/dev/null | head -n 1)" = "${'$'}TUN_IFACE" ] ||
          { echo WDTT_ERROR=tun_interface_mismatch; exit 3; }
        systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null || { echo WDTT_ERROR=tun_exit_service_inactive; exit 3; }
        /usr/local/lib/wdtt/tun-exit-route status >/dev/null 2>&1 || { echo WDTT_ERROR=tun_exit_route_inactive; exit 3; }
        TEST_SOURCE="${'$'}(wdtt_test_source)"
        [ -n "${'$'}TEST_SOURCE" ] || { echo WDTT_ERROR=wdtt_test_source_missing; exit 3; }
        EXIT_IP="${'$'}(wdtt_routed_public_ip "${'$'}TEST_SOURCE" "${'$'}TUN_IFACE" || true)"
        [ -n "${'$'}EXIT_IP" ] || { echo WDTT_ERROR=tun_exit_check_failed; exit 3; }
        echo "Проверка маршрута сервера через TUN-интерфейс ${'$'}TUN_IFACE. Проверочный IP: ${'$'}EXIT_IP"
        """
    )
}

private suspend fun checkTunInterfaceExit(
    target: OutboundSshTarget,
    interfaceName: String
): String = withContext(Dispatchers.IO) {
    runCheckedRootScript(
        target,
        checkTunInterfaceExitScript(interfaceName),
        timeout = 30000L,
        safeReadOnly = true,
        relayOperation = listOf("check-tun", interfaceName.trim()),
    )
}

internal fun deleteTunInterfaceExitScript(): String = shellScript(
    outboundShellPrelude(),
    """
    MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
    wdtt_require_tun_cleanup_ownership || { echo WDTT_ERROR=tun_exit_not_owned; exit 3; }
    echo "WDTT_PROGRESS|0.25|Останавливаю управляемый TUN-выход WDTT..."
    wdtt_clear_tun_out
    if [ "${'$'}MODE" = "tun_interface" ]; then
      wdtt_write_mode "direct" "прямой выход"
    fi
    echo "WDTT_PROGRESS|0.55|Удаляю только файлы маршрутизации WDTT..."
    rm -f /etc/systemd/system/wdtt-tun-exit.service \
      /usr/local/lib/wdtt/tun-exit-route \
      /usr/local/lib/wdtt/tun-exit-watch \
      /run/wdtt-tun-exit.state \
      "${'$'}WDTT_TUN_CONFIG_FILE" \
      "${'$'}WDTT_TUN_OWNER_FILE"
    rmdir /etc/wdtt-plus/tun-exit 2>/dev/null || true
    if [ -f /etc/wdtt/outbound-profile.env ]; then
      tmp_profile=/etc/wdtt/outbound-profile.env.tmp
      grep -v '^TUN_INTERFACE_B64=' /etc/wdtt/outbound-profile.env >"${'$'}tmp_profile" 2>/dev/null || true
      chmod 600 "${'$'}tmp_profile"
      mv "${'$'}tmp_profile" /etc/wdtt/outbound-profile.env
    fi
    systemctl daemon-reload 2>/dev/null || true
    systemctl reset-failed wdtt-tun-exit.service 2>/dev/null || true
    TUN_REMOVE_LEFT=0
    systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null && TUN_REMOVE_LEFT=1
    systemctl is-enabled --quiet wdtt-tun-exit.service 2>/dev/null && TUN_REMOVE_LEFT=1
    ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TUN_TABLE([[:space:]]|${'$'})" && TUN_REMOVE_LEFT=1
    [ -n "${'$'}(ip route show table "${'$'}WDTT_TUN_TABLE" 2>/dev/null)" ] && TUN_REMOVE_LEFT=1
    command -v iptables >/dev/null 2>&1 && iptables -S FORWARD 2>/dev/null | grep -q 'WDTT_TUN_EXIT' && TUN_REMOVE_LEFT=1
    [ -e /etc/systemd/system/wdtt-tun-exit.service ] && TUN_REMOVE_LEFT=1
    [ -e /usr/local/lib/wdtt/tun-exit-route ] && TUN_REMOVE_LEFT=1
    [ -e /usr/local/lib/wdtt/tun-exit-watch ] && TUN_REMOVE_LEFT=1
    [ -e "${'$'}WDTT_TUN_CONFIG_FILE" ] && TUN_REMOVE_LEFT=1
    [ -e "${'$'}WDTT_TUN_OWNER_FILE" ] && TUN_REMOVE_LEFT=1
    grep -q '^TUN_INTERFACE_B64=' /etc/wdtt/outbound-profile.env 2>/dev/null && TUN_REMOVE_LEFT=1
    if [ "${'$'}TUN_REMOVE_LEFT" != 0 ]; then
      echo WDTT_ERROR=tun_exit_remove_failed
      exit 3
    fi
    echo "WDTT_PROGRESS|1.0|Настройка TUN-выхода удалена."
    echo "Служба и маршруты WDTT удалены. Сам TUN-интерфейс и создавшее его приложение не изменялись."
    """
)

private suspend fun deleteTunInterfaceExit(target: OutboundSshTarget): String = withContext(Dispatchers.IO) {
    runCheckedRootScript(target, deleteTunInterfaceExitScript(), timeout = 30000L)
}

internal fun localProxyFirewallScript(port: Int): String {
    require(port in 1..65533) { "порт SOCKS5 должен быть от 1 до 65533" }
    val httpPort = port + 1
    val adminPort = port + 2
    return """
        #!/bin/sh
        set -eu
        set -f
        action="${'$'}{1:-}"
        cleanup() {
          command -v iptables >/dev/null 2>&1 || return 0
          while :; do
            rule="${'$'}(iptables -S INPUT 2>/dev/null | grep 'WDTT_LOCAL_PROXY' | head -n 1 || true)"
            [ -n "${'$'}rule" ] || break
            set -- ${'$'}rule
            [ "${'$'}{1:-}" = "-A" ] || break
            shift
            iptables -D "${'$'}@" 2>/dev/null || break
          done
        }
        if [ "${'$'}action" = down ]; then
          cleanup
          exit 0
        fi
        [ "${'$'}action" = up ] || exit 2
        command -v iptables >/dev/null 2>&1 || exit 0
        cleanup
        iptables -I INPUT -p tcp --dport $port -m comment --comment WDTT_LOCAL_PROXY -j ACCEPT
        iptables -I INPUT -p tcp --dport $httpPort -m comment --comment WDTT_LOCAL_PROXY -j ACCEPT
        iptables -I INPUT -p tcp --dport $adminPort -m comment --comment WDTT_LOCAL_PROXY -j ACCEPT
    """.trimIndent() + "\n"
}

internal fun buildLocalProxyInstallScript(
    port: Int,
    login: String,
    proxyPassword: String
): String {
    require(port in 1..65533) { "порт SOCKS5 должен быть от 1 до 65533" }
    require(localProxyCredentialsIssue(login, proxyPassword) == null) {
        localProxyCredentialsIssue(login, proxyPassword).orEmpty()
    }
    val httpPort = (port + 1).coerceAtMost(65535)
    val firewallScriptBase64 = encodeBase64Text(localProxyFirewallScript(port))
    val script = shellScript(
        outboundShellPrelude(),
        """
        PROXY_PORT=$port
        HTTP_PORT=$httpPort
        ADMIN_PORT=${httpPort + 1}
        PROXY_LOGIN=${shellQuote(login)}
        PROXY_PASSWORD=${shellQuote(proxyPassword)}
        THREEPROXY_VERSION=${shellQuote(THREEPROXY_VERSION)}
        THREEPROXY_SOURCE_URL=${shellQuote(THREEPROXY_SOURCE_URL)}
        THREEPROXY_SOURCE_SHA256=${shellQuote(THREEPROXY_SOURCE_SHA256)}
        wdtt_progress() { echo "WDTT_PROGRESS|${'$'}1|${'$'}2"; }
        install_pkg() {
          if command -v apt-get >/dev/null 2>&1; then
            apt-get update -y >/dev/null 2>&1 || true
            DEBIAN_FRONTEND=noninteractive apt-get install -y "${'$'}@" >/dev/null 2>&1
          elif command -v dnf >/dev/null 2>&1; then
            dnf install -y "${'$'}@" >/dev/null 2>&1
          elif command -v yum >/dev/null 2>&1; then
            yum install -y "${'$'}@" >/dev/null 2>&1
          elif command -v zypper >/dev/null 2>&1; then
            zypper --non-interactive install -y "${'$'}@" >/dev/null 2>&1
          elif command -v apk >/dev/null 2>&1; then
            apk add --no-cache "${'$'}@" >/dev/null 2>&1
          elif command -v pacman >/dev/null 2>&1; then
            pacman -Sy --noconfirm --needed "${'$'}@" >/dev/null 2>&1
          else
            return 1
          fi
        }
        install_3proxy_build_deps() {
          if command -v apt-get >/dev/null 2>&1; then
            install_pkg curl ca-certificates tar gzip make gcc coreutils libc6-dev libssl-dev libpcre2-dev
          elif command -v dnf >/dev/null 2>&1; then
            install_pkg curl ca-certificates tar gzip make gcc coreutils glibc-devel openssl-devel pcre2-devel
          elif command -v yum >/dev/null 2>&1; then
            install_pkg curl ca-certificates tar gzip make gcc coreutils glibc-devel openssl-devel pcre2-devel
          elif command -v zypper >/dev/null 2>&1; then
            install_pkg curl ca-certificates tar gzip make gcc coreutils glibc-devel libopenssl-devel pcre2-devel
          elif command -v apk >/dev/null 2>&1; then
            install_pkg curl ca-certificates tar gzip make gcc coreutils musl-dev linux-headers openssl-dev pcre2-dev
          elif command -v pacman >/dev/null 2>&1; then
            install_pkg curl ca-certificates tar gzip make gcc coreutils glibc openssl pcre2
          else
            return 1
          fi
        }
        install_3proxy_from_source() {
          TMP_DIR="${'$'}(mktemp -d)"
          cleanup() { rm -rf "${'$'}TMP_DIR"; }
          trap cleanup EXIT
          wdtt_progress 0.50 "Готовлю проверенную сборку 3proxy из исходников..."
          install_3proxy_build_deps || true
          command -v curl >/dev/null 2>&1 || { echo WDTT_ERROR=3proxy_source_no_curl; exit 2; }
          command -v tar >/dev/null 2>&1 || { echo WDTT_ERROR=3proxy_source_no_tar; exit 2; }
          command -v gzip >/dev/null 2>&1 || { echo WDTT_ERROR=3proxy_source_no_gzip; exit 2; }
          command -v make >/dev/null 2>&1 || { echo WDTT_ERROR=3proxy_source_no_make; exit 2; }
          command -v sha256sum >/dev/null 2>&1 || { echo WDTT_ERROR=3proxy_source_no_sha256sum; exit 2; }
          (command -v gcc >/dev/null 2>&1 || command -v cc >/dev/null 2>&1) || { echo WDTT_ERROR=3proxy_source_no_compiler; exit 2; }
          [ -f /usr/include/openssl/evp.h ] || [ -f /usr/local/include/openssl/evp.h ] || { echo WDTT_ERROR=3proxy_source_no_openssl_headers; exit 2; }
          [ -f /usr/include/pcre2.h ] || [ -f /usr/local/include/pcre2.h ] || { echo WDTT_ERROR=3proxy_source_no_pcre2_headers; exit 2; }
          cd "${'$'}TMP_DIR"
          wdtt_progress 0.58 "Скачиваю закреплённый выпуск 3proxy ${'$'}THREEPROXY_VERSION..."
          curl -fsSL --retry 2 --connect-timeout 12 --max-time 180 \
            -o 3proxy.tar.gz "${'$'}THREEPROXY_SOURCE_URL" ||
            { echo WDTT_ERROR=3proxy_source_download_failed; exit 2; }
          ACTUAL_SOURCE_SHA256="${'$'}(sha256sum 3proxy.tar.gz | awk '{print ${'$'}1}')"
          [ "${'$'}ACTUAL_SOURCE_SHA256" = "${'$'}THREEPROXY_SOURCE_SHA256" ] ||
            { echo WDTT_ERROR=3proxy_source_checksum_failed; exit 2; }
          tar -xzf 3proxy.tar.gz || { echo WDTT_ERROR=3proxy_source_unpack_failed; exit 2; }
          cd "3proxy-${'$'}THREEPROXY_VERSION"
          wdtt_progress 0.68 "Собираю 3proxy на сервере..."
          ln -sf Makefile.Linux Makefile
          make >/tmp/wdtt-3proxy-build.log 2>&1 || { echo WDTT_ERROR=3proxy_source_build_failed; tail -n 20 /tmp/wdtt-3proxy-build.log; exit 2; }
          BUILT_BIN="${'$'}(find . -type f -name 3proxy -perm -111 | head -n1)"
          [ -n "${'$'}BUILT_BIN" ] || { echo WDTT_ERROR=3proxy_source_binary_missing; exit 2; }
          install -m 755 "${'$'}BUILT_BIN" /usr/local/bin/3proxy || { echo WDTT_ERROR=3proxy_source_install_failed; exit 2; }
          printf '%s\n' "${'$'}THREEPROXY_VERSION" >/etc/wdtt/3proxy-version
          chmod 600 /etc/wdtt/3proxy-version
        }
        wdtt_progress 0.08 "Определяю систему и права..."
        command -v systemctl >/dev/null 2>&1 || { echo WDTT_ERROR=systemd_required; exit 2; }
        wdtt_progress 0.18 "Готовлю пакетный менеджер..."
        install_pkg curl ca-certificates coreutils || true
        THREEPROXY_BIN="${'$'}(command -v 3proxy || true)"
        INSTALLED_THREEPROXY_VERSION="${'$'}(cat /etc/wdtt/3proxy-version 2>/dev/null || true)"
        if [ -z "${'$'}THREEPROXY_BIN" ] || [ "${'$'}INSTALLED_THREEPROXY_VERSION" != "${'$'}THREEPROXY_VERSION" ]; then
          wdtt_progress 0.32 "Устанавливаю закреплённый выпуск 3proxy ${'$'}THREEPROXY_VERSION..."
          install_3proxy_from_source
          THREEPROXY_BIN="${'$'}(command -v 3proxy || true)"
        else
          wdtt_progress 0.50 "Закреплённый выпуск 3proxy ${'$'}THREEPROXY_VERSION уже установлен."
        fi
        [ -n "${'$'}THREEPROXY_BIN" ] || { echo WDTT_ERROR=3proxy_install_failed; exit 2; }
        wdtt_progress 0.76 "Пишу настройки SOCKS5 и HTTP..."
        cat >/etc/wdtt/3proxy.cfg <<EOF
        daemon
        nserver 1.1.1.1
        nserver 8.8.8.8
        nscache 65536
        timeouts 1 5 30 60 180 1800 15 60
        auth strong
        users ${'$'}PROXY_LOGIN:CL:${'$'}PROXY_PASSWORD
        allow ${'$'}PROXY_LOGIN
        socks -p${'$'}PROXY_PORT -i0.0.0.0 -e0.0.0.0
        proxy -p${'$'}HTTP_PORT -i0.0.0.0 -e0.0.0.0
        admin -p${'$'}ADMIN_PORT -i0.0.0.0
        EOF
        chmod 600 /etc/wdtt/3proxy.cfg
        mkdir -p /usr/local/lib/wdtt
        command -v base64 >/dev/null 2>&1 || { echo WDTT_ERROR=local_proxy_helper_write_failed; exit 2; }
        printf '%s' ${shellQuote(firewallScriptBase64)} | base64 -d >/usr/local/lib/wdtt/local-proxy-firewall ||
          { echo WDTT_ERROR=local_proxy_helper_write_failed; exit 2; }
        chmod 700 /usr/local/lib/wdtt/local-proxy-firewall
        wdtt_progress 0.82 "Настраиваю службу wdtt-3proxy..."
        cat >/etc/systemd/system/wdtt-3proxy.service <<EOF
        [Unit]
        Description=WDTT Plus authenticated proxy
        After=network-online.target
        Wants=network-online.target

        [Service]
        Type=forking
        ExecStart=${'$'}THREEPROXY_BIN /etc/wdtt/3proxy.cfg
        ExecStartPost=/usr/local/lib/wdtt/local-proxy-firewall up
        ExecStopPost=/usr/local/lib/wdtt/local-proxy-firewall down
        ExecReload=/bin/kill -HUP ${'$'}MAINPID
        Restart=on-failure

        [Install]
        WantedBy=multi-user.target
        EOF
        systemctl daemon-reload
        systemctl reset-failed wdtt-3proxy 2>/dev/null || true
        wdtt_progress 0.88 "Запускаю прокси-службу..."
        if ! systemctl enable wdtt-3proxy >/dev/null ||
           ! systemctl restart wdtt-3proxy >/dev/null ||
           ! systemctl is-active --quiet wdtt-3proxy; then
          systemctl disable --now wdtt-3proxy 2>/dev/null || true
          echo WDTT_ERROR=local_proxy_service_inactive
          exit 3
        fi
        wdtt_progress 0.92 "Открываю порты в firewall, если он есть..."
        /usr/local/lib/wdtt/local-proxy-firewall up ||
          { echo WDTT_ERROR=local_proxy_firewall_failed; systemctl disable --now wdtt-3proxy 2>/dev/null || true; exit 3; }
        wdtt_progress 0.96 "Проверяю подключение через установленный SOCKS5..."
        ${outboundProbeFunctions().replace("\n", "\n        ")}
        TEST_IP="${'$'}(curl -q --noproxy '' --proxy-user "${'$'}PROXY_LOGIN:${'$'}PROXY_PASSWORD" --socks5-hostname "127.0.0.1:${'$'}PROXY_PORT" -4fsS --max-time 12 https://api.ipify.org 2>/dev/null)" || TEST_IP=""
        wdtt_probe_ipv4 "${'$'}TEST_IP" ||
          { systemctl disable --now wdtt-3proxy 2>/dev/null || true; echo WDTT_ERROR=local_proxy_check_failed; exit 3; }
        SERVER_IP="${'$'}(curl -4fsS --max-time 8 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print ${'$'}1}')"
        cat >/etc/wdtt/local-proxy.json <<EOF
        {
          "enabled": true,
          "type": "socks5,http",
          "host": "${'$'}SERVER_IP",
          "socks5Port": ${'$'}PROXY_PORT,
          "httpPort": ${'$'}HTTP_PORT,
          "webPort": ${'$'}ADMIN_PORT,
          "login": "${'$'}PROXY_LOGIN",
          "password": "${'$'}PROXY_PASSWORD"
        }
        EOF
        chmod 600 /etc/wdtt/local-proxy.json
        wdtt_progress 1.0 "Прокси установлен и проверен."
        echo "Прокси на этом VPS включён."
        echo "SOCKS5-прокси: socks5://${'$'}PROXY_LOGIN:********@${'$'}SERVER_IP:${'$'}PROXY_PORT"
        echo "HTTP-прокси: http://${'$'}PROXY_LOGIN:********@${'$'}SERVER_IP:${'$'}HTTP_PORT"
        echo "Веб-страница 3proxy: http://${'$'}SERVER_IP:${'$'}ADMIN_PORT/"
        """
    )
    return script
}

private suspend fun installLocalProxy(
    context: Context,
    target: OutboundSshTarget,
    port: Int,
    login: String,
    proxyPassword: String
): String = runRootScript(
    context = context,
    target = target,
    script = buildLocalProxyInstallScript(port, login, proxyPassword),
    timeout = CMD_TIMEOUT,
)

private suspend fun checkLocalProxy(
    context: Context,
    target: OutboundSshTarget,
    port: Int,
    login: String,
    proxyPassword: String
): String {
    require(port in 1..65533) { "порт SOCKS5 должен быть от 1 до 65533" }
    require(localProxyCredentialsIssue(login, proxyPassword) == null) {
        localProxyCredentialsIssue(login, proxyPassword).orEmpty()
    }
    val script = """
        PROXY_PORT=$port
        PROXY_LOGIN=${shellQuote(login)}
        PROXY_PASSWORD=${shellQuote(proxyPassword)}
        echo "WDTT_PROGRESS|0.15|Проверяю, запущена ли служба прокси..."
        if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files wdtt-3proxy.service >/dev/null 2>&1; then
          systemctl is-active --quiet wdtt-3proxy || { echo WDTT_ERROR=local_proxy_service_inactive; exit 3; }
        fi
        echo "WDTT_PROGRESS|0.45|Проверяю curl на сервере..."
        command -v curl >/dev/null 2>&1 || { echo WDTT_ERROR=curl_not_installed; exit 2; }
        echo "WDTT_PROGRESS|0.70|Подключаюсь через SOCKS5 127.0.0.1:${'$'}PROXY_PORT..."
        ${outboundProbeFunctions().replace("\n", "\n        ")}
        IP="${'$'}(curl -q --noproxy '' --proxy-user "${'$'}PROXY_LOGIN:${'$'}PROXY_PASSWORD" --socks5-hostname "127.0.0.1:${'$'}PROXY_PORT" -4fsS --max-time 12 https://api.ipify.org 2>/dev/null)" || IP=""
        wdtt_probe_ipv4 "${'$'}IP" || { echo WDTT_ERROR=local_proxy_check_failed; exit 3; }
        echo "WDTT_PROGRESS|1.0|Прокси отвечает."
        echo "Проверка успешна: SOCKS5 на 127.0.0.1:${'$'}PROXY_PORT отвечает с указанными логином и паролем. Выходной IP: ${'$'}IP"
    """.trimIndent()
    return runRootScript(
        context,
        target,
        script,
        timeout = 30000L,
        safeReadOnly = true,
        relayOperation = listOf("check-local-proxy", port.toString(), login, proxyPassword),
    )
}

internal fun stopLocalProxyScript(): String = """
    set -e
    echo "WDTT_PROGRESS|0.35|Останавливаю службу прокси на этом сервере..."
    systemctl disable --now wdtt-3proxy 2>/dev/null || systemctl stop wdtt-3proxy 2>/dev/null || true
    echo "WDTT_PROGRESS|0.85|Проверяю, что прокси больше не запущен..."
    if systemctl is-active --quiet wdtt-3proxy 2>/dev/null ||
       systemctl is-enabled --quiet wdtt-3proxy 2>/dev/null; then
      echo WDTT_ERROR=local_proxy_service_still_active
      exit 3
    fi
    echo "WDTT_PROGRESS|1.0|Прокси остановлен."
    echo "Прокси на этом VPS остановлен и убран из автозапуска. Настройки сохранены, его можно снова включить кнопкой «Установить»."
""".trimIndent()

private suspend fun stopLocalProxy(target: OutboundSshTarget): String =
    runCheckedRootScript(target, stopLocalProxyScript(), timeout = 20000L)

internal fun removeLocalProxyScript(): String = shellScript(
    outboundShellPrelude(),
    """
    echo "WDTT_PROGRESS|0.25|Останавливаю службу прокси..."
    systemctl disable --now wdtt-3proxy 2>/dev/null || true
    echo "WDTT_PROGRESS|0.55|Удаляю файлы настроек прокси..."
    rm -f /etc/systemd/system/wdtt-3proxy.service /etc/wdtt/3proxy.cfg /etc/wdtt/local-proxy.json \
      /usr/local/lib/wdtt/local-proxy-firewall
    systemctl daemon-reload 2>/dev/null || true
    echo "WDTT_PROGRESS|0.80|Удаляю правила доступа к портам прокси..."
    if command -v iptables >/dev/null 2>&1; then
      iptables -S INPUT 2>/dev/null | grep WDTT_LOCAL_PROXY | sed 's/^-A /iptables -D /' | while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
    fi
    if [ -f /etc/wdtt/outbound-profile.env ]; then
      tmp_profile=/etc/wdtt/outbound-profile.env.tmp
      grep -Ev '^(LOCAL_PROXY_PORT|LOCAL_PROXY_LOGIN_B64|LOCAL_PROXY_PASSWORD_B64)=' \
        /etc/wdtt/outbound-profile.env >"${'$'}tmp_profile" 2>/dev/null || true
      chmod 600 "${'$'}tmp_profile"
      mv "${'$'}tmp_profile" /etc/wdtt/outbound-profile.env
    fi
    LOCAL_REMOVE_LEFT=0
    systemctl is-active --quiet wdtt-3proxy 2>/dev/null && LOCAL_REMOVE_LEFT=1
    systemctl is-enabled --quiet wdtt-3proxy 2>/dev/null && LOCAL_REMOVE_LEFT=1
    [ -e /etc/systemd/system/wdtt-3proxy.service ] && LOCAL_REMOVE_LEFT=1
    [ -e /etc/wdtt/3proxy.cfg ] && LOCAL_REMOVE_LEFT=1
    [ -e /etc/wdtt/local-proxy.json ] && LOCAL_REMOVE_LEFT=1
    [ -e /usr/local/lib/wdtt/local-proxy-firewall ] && LOCAL_REMOVE_LEFT=1
    if command -v iptables >/dev/null 2>&1 &&
       iptables -S INPUT 2>/dev/null | grep -q WDTT_LOCAL_PROXY; then
      LOCAL_REMOVE_LEFT=1
    fi
    if [ "${'$'}LOCAL_REMOVE_LEFT" != 0 ]; then
      echo WDTT_ERROR=local_proxy_remove_failed
      exit 3
    fi
    echo "WDTT_PROGRESS|1.0|Прокси удалён."
    echo "Прокси на этом VPS удалён: служба, настройки, сохранённые реквизиты и правила доступа к портам очищены."
    """
)

private suspend fun removeLocalProxy(target: OutboundSshTarget): String =
    runCheckedRootScript(target, removeLocalProxyScript(), timeout = 30000L)

private suspend fun checkExternalProxy(
    context: Context,
    target: OutboundSshTarget,
    kind: ProxyKind,
    host: String,
    port: Int,
    login: String,
    proxyPassword: String
): String {
    require(host.isValidPublicHost()) { "укажите домен или IPv4 внешнего прокси без схемы и порта" }
    require(port in 1..65535) { "порт внешнего прокси должен быть от 1 до 65535" }
    require(externalProxyCredentialsIssue(login, proxyPassword) == null) {
        externalProxyCredentialsIssue(login, proxyPassword).orEmpty()
    }
    val scheme = if (kind == ProxyKind.Socks5) "socks5h" else "http"
    val proxyUri = "$scheme://$host:$port"
    val script = shellScript(externalProxyProbeFunctions(), """
        echo "WDTT_PROGRESS|0.25|Проверяю curl на сервере..."
        command -v curl >/dev/null 2>&1 || { echo WDTT_ERROR=curl_not_installed; exit 2; }
        echo "WDTT_PROGRESS|0.55|Пробую выйти в интернет через указанный ${kind.label}..."
        PROXY_URI=${shellQuote(proxyUri)}
        PROXY_LOGIN=${shellQuote(login)}
        PROXY_PASSWORD=${shellQuote(proxyPassword)}
        CHECK_EXIT=0
        if [ -n "${'$'}PROXY_LOGIN" ]; then
          ${outboundProbeFunctions().replace("\n", "\n        ")}
        IP="${'$'}(curl -q --noproxy '' --proxy "${'$'}PROXY_URI" --proxy-user "${'$'}PROXY_LOGIN:${'$'}PROXY_PASSWORD" -4fsS --max-time 15 https://api.ipify.org 2>/dev/null)" || CHECK_EXIT=${'$'}?
        else
          ${outboundProbeFunctions().replace("\n", "\n        ")}
        IP="${'$'}(curl -q --noproxy '' --proxy "${'$'}PROXY_URI" -4fsS --max-time 15 https://api.ipify.org 2>/dev/null)" || CHECK_EXIT=${'$'}?
        fi
        [ "${'$'}CHECK_EXIT" = 0 ] && wdtt_valid_proxy_test_ip "${'$'}IP" || { echo WDTT_ERROR=external_proxy_check_failed; exit 3; }
        echo "WDTT_PROGRESS|1.0|Внешний TCP-прокси отвечает."
        echo "Проверка успешна: ${kind.label} отвечает, сервер смог открыть проверочный сайт через него. IP через прокси: ${'$'}IP"
    """.trimIndent())
    return runRootScript(
        context,
        target,
        script,
        timeout = 30000L,
        safeReadOnly = true,
        relayOperation = listOf(
            "check-external-proxy",
            kind.name,
            host,
            port.toString(),
            login,
            proxyPassword,
        ),
    )
}

internal fun externalProxyRoutesScript(): String = """
    #!/bin/sh
    set -eu
    action="${'$'}{1:-}"
    PROXY_IP="${'$'}{2:-}"
    WDTT_IFACE=wdtt0
    WDTT_SUBNET="${'$'}(ip -4 route show dev "${'$'}WDTT_IFACE" scope link | awk '{print ${'$'}1; exit}')"
    [ -n "${'$'}WDTT_SUBNET" ] || WDTT_SUBNET=10.66.66.0/24
    cleanup() {
      while iptables -D INPUT -i "${'$'}WDTT_IFACE" -s "${'$'}WDTT_SUBNET" -p tcp --dport 12345 -m comment --comment WDTT_PROXY_OUT -j ACCEPT 2>/dev/null; do :; done
      while iptables -t nat -D PREROUTING -i "${'$'}WDTT_IFACE" -p tcp -j WDTT_PROXY_OUT 2>/dev/null; do :; done
      iptables -t nat -F WDTT_PROXY_OUT 2>/dev/null || true
      iptables -t nat -X WDTT_PROXY_OUT 2>/dev/null || true
    }
    if [ "${'$'}action" = down ]; then
      cleanup
      exit 0
    fi
    [ "${'$'}action" = up ] || exit 2
    [ -n "${'$'}PROXY_IP" ] || exit 2
    cleanup
    iptables -t nat -N WDTT_PROXY_OUT
    for net in 0.0.0.0/8 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
      iptables -t nat -A WDTT_PROXY_OUT -d "${'$'}net" -j RETURN
    done
    iptables -t nat -A WDTT_PROXY_OUT -d "${'$'}PROXY_IP" -j RETURN
    iptables -t nat -A WDTT_PROXY_OUT -p tcp -j REDIRECT --to-ports 12345
    iptables -I INPUT -i "${'$'}WDTT_IFACE" -s "${'$'}WDTT_SUBNET" -p tcp --dport 12345 -m comment --comment WDTT_PROXY_OUT -j ACCEPT
    iptables -t nat -A PREROUTING -i "${'$'}WDTT_IFACE" -p tcp -j WDTT_PROXY_OUT
""".trimIndent() + "\n"

private suspend fun enableExternalProxy(
    context: Context,
    target: OutboundSshTarget,
    kind: ProxyKind,
    host: String,
    port: Int,
    login: String,
    proxyPassword: String
): String {
    require(host.isValidPublicHost()) { "укажите домен или IPv4 внешнего прокси без схемы и порта" }
    require(port in 1..65535) { "порт внешнего прокси должен быть от 1 до 65535" }
    require(externalProxyCredentialsIssue(login, proxyPassword) == null) {
        externalProxyCredentialsIssue(login, proxyPassword).orEmpty()
    }
    val redsocksType = if (kind == ProxyKind.Socks5) "socks5" else "http-connect"
    val routesScriptBase64 = encodeBase64Text(externalProxyRoutesScript())
    val script = shellScript(
        outboundShellPrelude(),
        """
        PROXY_KIND=${shellQuote(kind.protocol)}
        REDSOCKS_TYPE=${shellQuote(redsocksType)}
        PROXY_HOST=${shellQuote(host)}
        PROXY_PORT=$port
        PROXY_LOGIN=${shellQuote(login)}
        PROXY_PASSWORD=${shellQuote(proxyPassword)}
        PROXY_LOGIN_CONFIG=${shellQuote(escapeRedsocksQuotedValue(login))}
        PROXY_PASSWORD_CONFIG=${shellQuote(escapeRedsocksQuotedValue(proxyPassword))}
        wdtt_progress() { echo "WDTT_PROGRESS|${'$'}1|${'$'}2"; }
        wdtt_progress 0.12 "Готовлю компонент перенаправления через внешний TCP-прокси..."
        wdtt_install_redsocks_tools || true
        REDSOCKS_BIN="${'$'}(command -v redsocks || true)"
        [ -n "${'$'}REDSOCKS_BIN" ] || { echo WDTT_ERROR=redsocks_not_installed; exit 2; }
        wdtt_progress 0.30 "Проверяю сетевые инструменты и интерфейс WDTT..."
        command -v iptables >/dev/null 2>&1 || { echo WDTT_ERROR=iptables_required; exit 2; }
        [ -d /sys/class/net/"${'$'}WDTT_IFACE" ] || { echo WDTT_ERROR=wdtt_iface_not_found; exit 2; }
        wdtt_progress 0.45 "Определяю адрес внешнего прокси..."
        PROXY_IP="${'$'}(getent ahostsv4 "${'$'}PROXY_HOST" | awk '{print ${'$'}1; exit}')"
        [ -n "${'$'}PROXY_IP" ] || PROXY_IP="${'$'}PROXY_HOST"
        wdtt_progress 0.55 "Отключаю прежний внешний выход WDTT..."
        wdtt_clear_external_out
        wdtt_progress 0.65 "Записываю настройки внешнего прокси..."
        cat >/etc/wdtt/redsocks.conf <<EOF
        base {
          log_debug = off;
          log_info = on;
          log = "file:/var/log/wdtt-redsocks.log";
          daemon = on;
          redirector = iptables;
        }
        redsocks {
          local_ip = 0.0.0.0;
          local_port = 12345;
          ip = ${'$'}PROXY_IP;
          port = ${'$'}PROXY_PORT;
          type = ${'$'}REDSOCKS_TYPE;
        EOF
        if [ -n "${'$'}PROXY_LOGIN" ]; then
          printf '  login = "%s";\n' "${'$'}PROXY_LOGIN_CONFIG" >>/etc/wdtt/redsocks.conf
          printf '  password = "%s";\n' "${'$'}PROXY_PASSWORD_CONFIG" >>/etc/wdtt/redsocks.conf
        fi
        cat >>/etc/wdtt/redsocks.conf <<EOF
        }
        EOF
        chmod 600 /etc/wdtt/redsocks.conf
        mkdir -p /usr/local/lib/wdtt
        command -v base64 >/dev/null 2>&1 || { echo WDTT_ERROR=external_proxy_helper_write_failed; exit 2; }
        printf '%s' ${shellQuote(routesScriptBase64)} | base64 -d >/usr/local/lib/wdtt/redsocks-routes ||
          { echo WDTT_ERROR=external_proxy_helper_write_failed; exit 2; }
        chmod 700 /usr/local/lib/wdtt/redsocks-routes
        wdtt_progress 0.76 "Настраиваю службу перенаправления WDTT..."
        cat >/etc/systemd/system/wdtt-redsocks.service <<EOF
        [Unit]
        Description=WDTT Plus external proxy redirector
        After=network-online.target wdtt.service
        Wants=network-online.target

        [Service]
        Type=forking
        ExecStart=${'$'}REDSOCKS_BIN -c /etc/wdtt/redsocks.conf -p /run/wdtt-redsocks.pid
        ExecStartPost=/usr/local/lib/wdtt/redsocks-routes up ${'$'}PROXY_IP
        ExecStopPost=/usr/local/lib/wdtt/redsocks-routes down ${'$'}PROXY_IP
        PIDFile=/run/wdtt-redsocks.pid
        Restart=on-failure

        [Install]
        WantedBy=multi-user.target
        EOF
        systemctl daemon-reload
        wdtt_progress 0.84 "Запускаю службу внешнего TCP-прокси..."
        if ! systemctl enable wdtt-redsocks >/dev/null ||
           ! systemctl restart wdtt-redsocks >/dev/null ||
           ! systemctl is-active --quiet wdtt-redsocks; then
          journalctl -u wdtt-redsocks -n 30 --no-pager 2>/dev/null || true
          wdtt_clear_external_out
          wdtt_write_mode "direct" "rollback after external proxy service error"
          echo WDTT_ERROR=external_proxy_service_inactive
          exit 3
        fi
        wdtt_progress 0.92 "Направляю обычные TCP-подключения WDTT через внешний TCP-прокси..."
        /usr/local/lib/wdtt/redsocks-routes up "${'$'}PROXY_IP" ||
          { echo WDTT_ERROR=external_proxy_route_install_failed; wdtt_clear_external_out; wdtt_write_mode "direct" "rollback after external proxy route error"; exit 3; }
        wdtt_progress 0.96 "Проверяю путь WDTT через внешний TCP-прокси..."
        if ! wdtt_test_redsocks_path "${'$'}PROXY_IP"; then
          wdtt_clear_external_out
          wdtt_write_mode "direct" "rollback after external proxy error"
          exit 3
        fi
        wdtt_write_mode "external_proxy" "${'$'}PROXY_KIND://${'$'}PROXY_HOST:${'$'}PROXY_PORT"
        wdtt_progress 1.0 "Внешний TCP-прокси включён."
        echo "Внешний TCP-прокси включён для обычных TCP-подключений WDTT-пользователей. UDP, QUIC и голосовой трафик через него не перенаправляются."
        echo "Подсеть клиентов WDTT: ${'$'}WDTT_SUBNET"
        echo "Прокси: ${'$'}PROXY_KIND://${'$'}PROXY_HOST:${'$'}PROXY_PORT"
        """
    )
    return runRootScript(context, target, script, timeout = CMD_TIMEOUT)
}

internal fun deleteExternalProxyScript(): String = shellScript(
    outboundShellPrelude(),
    """
    MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
    echo "WDTT_PROGRESS|0.25|Останавливаю внешний TCP-прокси..."
    wdtt_clear_proxy_out
    echo "WDTT_PROGRESS|0.55|Удаляю службу и настройки внешнего прокси..."
    rm -f /etc/systemd/system/wdtt-redsocks.service \
      /etc/wdtt/redsocks.conf \
      /usr/local/lib/wdtt/redsocks-routes \
      /var/log/wdtt-redsocks.log \
      /run/wdtt-redsocks.pid
    systemctl daemon-reload 2>/dev/null || true
    if [ -f /etc/wdtt/outbound-profile.env ]; then
      tmp_profile=/etc/wdtt/outbound-profile.env.tmp
      grep -Ev '^(EXTERNAL_PROXY_KIND|EXTERNAL_PROXY_HOST_B64|EXTERNAL_PROXY_PORT|EXTERNAL_PROXY_LOGIN_B64|EXTERNAL_PROXY_PASSWORD_B64)=' \
        /etc/wdtt/outbound-profile.env >"${'$'}tmp_profile" 2>/dev/null || true
      chmod 600 "${'$'}tmp_profile"
      mv "${'$'}tmp_profile" /etc/wdtt/outbound-profile.env
    fi
    if [ "${'$'}MODE" = "external_proxy" ]; then
      wdtt_write_mode "direct" "прямой выход"
    fi
    EXTERNAL_REMOVE_LEFT=0
    systemctl is-active --quiet wdtt-redsocks 2>/dev/null && EXTERNAL_REMOVE_LEFT=1
    systemctl is-enabled --quiet wdtt-redsocks 2>/dev/null && EXTERNAL_REMOVE_LEFT=1
    [ -e /etc/systemd/system/wdtt-redsocks.service ] && EXTERNAL_REMOVE_LEFT=1
    [ -e /etc/wdtt/redsocks.conf ] && EXTERNAL_REMOVE_LEFT=1
    [ -e /usr/local/lib/wdtt/redsocks-routes ] && EXTERNAL_REMOVE_LEFT=1
    if command -v iptables >/dev/null 2>&1 &&
       { iptables -t nat -S WDTT_PROXY_OUT >/dev/null 2>&1 ||
         iptables -t nat -S PREROUTING 2>/dev/null | grep -q -- "-i ${'$'}WDTT_IFACE .* -j WDTT_PROXY_OUT"; }; then
      EXTERNAL_REMOVE_LEFT=1
    fi
    if [ "${'$'}EXTERNAL_REMOVE_LEFT" != 0 ]; then
      echo WDTT_ERROR=external_proxy_remove_failed
      exit 3
    fi
    echo "WDTT_PROGRESS|1.0|Настройки внешнего TCP-прокси удалены."
    echo "Служба, конфигурация, сохранённые реквизиты и правила внешнего TCP-прокси удалены."
    """
)

private suspend fun deleteExternalProxy(target: OutboundSshTarget): String =
    runCheckedRootScript(target, deleteExternalProxyScript(), timeout = 30000L)

internal fun validateWireGuardConfigText(config: String): Result<Unit> = runCatching {
    val raw = config.trim()
    require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "файл больше 64 КБ" }
    require('\u0000' !in raw) { "файл содержит недопустимые нулевые байты" }
    require(raw.contains(Regex("(?im)^\\s*\\[Interface]\\s*$"))) { "не найден раздел с настройками вашего WireGuard-клиента" }
    require(raw.contains(Regex("(?im)^\\s*PrivateKey\\s*=\\s*\\S+"))) { "в файле нет приватного ключа клиента" }
    require(raw.contains(Regex("(?im)^\\s*Address\\s*=\\s*\\S+"))) { "в файле нет адреса клиента" }
    require(raw.contains(Regex("(?im)^\\s*\\[Peer]\\s*$"))) { "не найден раздел с настройками удалённого сервера" }
    require(raw.contains(Regex("(?im)^\\s*PublicKey\\s*=\\s*\\S+"))) { "в файле нет публичного ключа удалённого сервера" }
    require(raw.contains(Regex("(?im)^\\s*Endpoint\\s*=\\s*\\S+"))) { "в файле нет адреса удалённого сервера" }
    require(raw.contains(Regex("(?im)^\\s*AllowedIPs\\s*=\\s*\\S+"))) { "в файле нет маршрутов для WireGuard" }
    require(!raw.contains(Regex("(?im)^\\s*(PreUp|PostUp|PreDown|PostDown)\\s*="))) {
        "команды запуска и остановки запрещены для безопасного импорта"
    }
    val interfaceKeys = setOf("privatekey", "address", "dns", "mtu", "table", "listenport")
    val peerKeys = setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")
    var section = ""
    var interfaceCount = 0
    var peerCount = 0
    raw.lineSequence().forEachIndexed { index, sourceLine ->
        val line = sourceLine.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim().lowercase(Locale.ROOT)
            when (section) {
                "interface" -> interfaceCount++
                "peer" -> peerCount++
                else -> throw IllegalArgumentException("неподдерживаемый раздел в строке ${index + 1}")
            }
            return@forEachIndexed
        }
        val separator = line.indexOf('=')
        require(separator in 1 until line.lastIndex) { "неверная строка ${index + 1}" }
        val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
        val value = line.substring(separator + 1).trim()
        require(value.isNotBlank()) { "пустое значение в строке ${index + 1}" }
        val allowed = when (section) {
            "interface" -> interfaceKeys
            "peer" -> peerKeys
            else -> emptySet()
        }
        require(key in allowed) { "параметр ${line.substring(0, separator).trim()} не разрешён для безопасного импорта" }
        if (key == "mtu") {
            val mtuValue = value.toIntOrNull()
            require(mtuValue != null && mtuValue in 576..9000) { "MTU должен быть от 576 до 9000" }
        }
        if (key == "endpoint") {
            val port = value.substringAfterLast(':', "").toIntOrNull()
            require(port != null && port in 1..65535 && !value.any(Char::isWhitespace)) { "Endpoint должен содержать адрес и порт от 1 до 65535" }
        }
    }
    require(interfaceCount == 1) { "должен быть ровно один раздел [Interface]" }
    require(peerCount == 1) { "должен быть ровно один раздел [Peer]" }
    val allowedIps = Regex("(?im)^\\s*AllowedIPs\\s*=\\s*(.+)$")
        .find(raw)?.groupValues?.getOrNull(1).orEmpty()
        .split(',').map(String::trim)
    require("0.0.0.0/0" in allowedIps) { "для выходного VPN в AllowedIPs должен быть маршрут 0.0.0.0/0" }
}

internal fun sanitizeWireGuardConfigForWdttExit(config: String): String {
    validateWireGuardConfigText(config).getOrThrow()
    val lines = config.trim().lines()
    val out = mutableListOf<String>()
    var inInterface = false
    var tableInserted = false
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.equals("[Interface]", ignoreCase = true)) {
            inInterface = true
            tableInserted = false
            out += "[Interface]"
            return@forEach
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            if (inInterface && !tableInserted) {
                out += "Table = off"
                tableInserted = true
            }
            inInterface = false
            out += trimmed
            return@forEach
        }
        if (trimmed.startsWith("Table", ignoreCase = true) ||
            trimmed.startsWith("DNS", ignoreCase = true)
        ) return@forEach
        if (trimmed.startsWith("PreUp", ignoreCase = true) ||
            trimmed.startsWith("PostUp", ignoreCase = true) ||
            trimmed.startsWith("PreDown", ignoreCase = true) ||
            trimmed.startsWith("PostDown", ignoreCase = true)
        ) return@forEach
        out += line
    }
    if (inInterface && !tableInserted) out += "Table = off"
    return out.joinToString("\n").trim() + "\n"
}

internal fun wireGuardPolicyScript(mode: String, detail: String): String = shellScript(
    outboundShellPrelude(),
    """
    [ -d /sys/class/net/"${'$'}WDTT_IFACE" ] || { echo WDTT_ERROR=wdtt_iface_not_found; exit 2; }
    command -v wg-quick >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_tools_required; exit 2; }
    command -v iptables >/dev/null 2>&1 || { echo WDTT_ERROR=iptables_required; exit 2; }
    sed -i -E '/^[[:space:]]*DNS[[:space:]]*=/Id' /etc/wireguard/wg-wdtt-exit.conf
    echo "WDTT_PROGRESS|0.72|Отключаю прежний внешний выход WDTT..."
    wdtt_clear_external_out
    echo "WDTT_PROGRESS|0.78|Создаю постоянную службу WireGuard-выхода..."
    mkdir -p /usr/local/lib/wdtt /etc/wdtt-plus/wg-exit
    printf '%s\n' ${shellQuote(mode)} >"${'$'}WDTT_WG_OWNER_FILE"
    printf '%s\n' ${shellQuote(mode)} >"${'$'}WDTT_WG_CONFIG_OWNER_FILE"
    chmod 600 "${'$'}WDTT_WG_OWNER_FILE"
    chmod 600 "${'$'}WDTT_WG_CONFIG_OWNER_FILE"
    cat >/usr/local/lib/wdtt/wg-exit-up <<'WDTT_WG_UP'
    #!/bin/sh
    set -eu
    WDTT_IFACE=wdtt0
    WDTT_WG_IFACE=wg-wdtt-exit
    WDTT_TABLE=100
    WDTT_SUBNET="${'$'}(ip -4 route show dev "${'$'}WDTT_IFACE" scope link 2>/dev/null | awk '{print ${'$'}1; exit}')"
    [ -n "${'$'}WDTT_SUBNET" ] || WDTT_SUBNET=10.66.66.0/24
    wdtt_wg_up_cleanup() {
      status="${'$'}?"
      trap - 0
      if [ "${'$'}status" -ne 0 ]; then
        set +e
        while iptables -D FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}WDTT_WG_IFACE" -s "${'$'}WDTT_SUBNET" -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
        while iptables -D FORWARD -i "${'$'}WDTT_WG_IFACE" -o "${'$'}WDTT_IFACE" -d "${'$'}WDTT_SUBNET" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
        iptables -t nat -D POSTROUTING -s "${'$'}WDTT_SUBNET" -o "${'$'}WDTT_WG_IFACE" -m comment --comment WDTT_EXIT -j MASQUERADE 2>/dev/null
        while ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TABLE" priority 100 2>/dev/null; do :; done
        ip route flush table "${'$'}WDTT_TABLE" 2>/dev/null
        wg-quick down "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1 || ip link delete "${'$'}WDTT_WG_IFACE" 2>/dev/null
      fi
      exit "${'$'}status"
    }
    trap wdtt_wg_up_cleanup 0
    wg-quick down "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1 || true
    wg-quick up "${'$'}WDTT_WG_IFACE"
    ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TABLE" priority 100 2>/dev/null || true
    ip rule add from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TABLE" priority 100
    ip route replace default dev "${'$'}WDTT_WG_IFACE" table "${'$'}WDTT_TABLE"
    iptables -t nat -C POSTROUTING -s "${'$'}WDTT_SUBNET" -o "${'$'}WDTT_WG_IFACE" -m comment --comment WDTT_EXIT -j MASQUERADE 2>/dev/null || \
      iptables -t nat -A POSTROUTING -s "${'$'}WDTT_SUBNET" -o "${'$'}WDTT_WG_IFACE" -m comment --comment WDTT_EXIT -j MASQUERADE
    while iptables -D FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}WDTT_WG_IFACE" -s "${'$'}WDTT_SUBNET" -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
    while iptables -D FORWARD -i "${'$'}WDTT_WG_IFACE" -o "${'$'}WDTT_IFACE" -d "${'$'}WDTT_SUBNET" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
    iptables -I FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}WDTT_WG_IFACE" -s "${'$'}WDTT_SUBNET" -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT
    iptables -I FORWARD -i "${'$'}WDTT_WG_IFACE" -o "${'$'}WDTT_IFACE" -d "${'$'}WDTT_SUBNET" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT
    trap - 0
    WDTT_WG_UP
    cat >/usr/local/lib/wdtt/wg-exit-down <<'WDTT_WG_DOWN'
    #!/bin/sh
    WDTT_IFACE=wdtt0
    WDTT_WG_IFACE=wg-wdtt-exit
    WDTT_TABLE=100
    WDTT_SUBNET="${'$'}(ip -4 route show dev "${'$'}WDTT_IFACE" scope link 2>/dev/null | awk '{print ${'$'}1; exit}')"
    [ -n "${'$'}WDTT_SUBNET" ] || WDTT_SUBNET=10.66.66.0/24
    while iptables -D FORWARD -i "${'$'}WDTT_IFACE" -o "${'$'}WDTT_WG_IFACE" -s "${'$'}WDTT_SUBNET" -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
    while iptables -D FORWARD -i "${'$'}WDTT_WG_IFACE" -o "${'$'}WDTT_IFACE" -d "${'$'}WDTT_SUBNET" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FORWARD -j ACCEPT 2>/dev/null; do :; done
    iptables -t nat -D POSTROUTING -s "${'$'}WDTT_SUBNET" -o "${'$'}WDTT_WG_IFACE" -m comment --comment WDTT_EXIT -j MASQUERADE 2>/dev/null || true
    ip rule del from "${'$'}WDTT_SUBNET" table "${'$'}WDTT_TABLE" priority 100 2>/dev/null || true
    ip route flush table "${'$'}WDTT_TABLE" 2>/dev/null || true
    wg-quick down "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1 || true
    WDTT_WG_DOWN
    chmod 700 /usr/local/lib/wdtt/wg-exit-up /usr/local/lib/wdtt/wg-exit-down
    cat >/etc/systemd/system/wdtt-wg-exit.service <<'WDTT_WG_SERVICE'
    [Unit]
    Description=WDTT Plus policy-routed WireGuard exit
    After=network-online.target wdtt.service
    Wants=network-online.target

    [Service]
    Type=oneshot
    RemainAfterExit=yes
    ExecStart=/usr/local/lib/wdtt/wg-exit-up
    ExecStop=/usr/local/lib/wdtt/wg-exit-down

    [Install]
    WantedBy=multi-user.target
    WDTT_WG_SERVICE
    systemctl daemon-reload
    echo "WDTT_PROGRESS|0.84|Поднимаю WireGuard-интерфейс и маршруты WDTT..."
    if ! systemctl enable --now wdtt-wg-exit.service >/tmp/wdtt-wg-exit-start.log 2>&1 ||
       ! systemctl is-active --quiet wdtt-wg-exit.service; then
      echo "WireGuard-служба не запустилась; очищаю частично созданный интерфейс и маршруты."
      tail -n 12 /tmp/wdtt-wg-exit-start.log 2>/dev/null || true
      systemctl disable --now wdtt-wg-exit.service 2>/dev/null || true
      /usr/local/lib/wdtt/wg-exit-down >/dev/null 2>&1 || true
      ip link delete "${'$'}WDTT_WG_IFACE" 2>/dev/null || true
      systemctl reset-failed wdtt-wg-exit.service 2>/dev/null || true
      wdtt_write_mode "direct" "rollback after WireGuard service start error"
      rm -f "${'$'}WDTT_WG_OWNER_FILE"
      rm -f /tmp/wdtt-wg-exit-start.log
      echo WDTT_ERROR=wireguard_exit_service_inactive
      exit 3
    fi
    rm -f /tmp/wdtt-wg-exit-start.log
    if [ ${shellQuote(mode)} != warp_free ]; then
      TEST_SOURCE="${'$'}(wdtt_test_source)"
      if ! wdtt_routed_public_ip "${'$'}TEST_SOURCE" "${'$'}WDTT_WG_IFACE" >/dev/null; then
        wdtt_clear_wireguard_out
        wdtt_write_mode direct "rollback after WireGuard route check error"
        echo WDTT_ERROR=wireguard_exit_check_failed
        exit 3
      fi
    fi
    echo "WDTT_PROGRESS|0.95|Сохраняю новый режим выхода WDTT..."
    wdtt_write_mode ${shellQuote(mode)} ${shellQuote(detail)}
    sleep 1
    echo "WDTT_PROGRESS|1.0|WireGuard-выход включён."
    echo "Выход через WireGuard включён только для WDTT-пользователей."
    echo "Подсеть клиентов WDTT: ${'$'}WDTT_SUBNET"
    wg show "${'$'}WDTT_WG_IFACE" | sed -E 's/(private key: ).*/\1(скрыт)/' || true
    """
)

private fun wgcfToolManagementScript(): String = """
    WGCF_BASELINE_VERSION=${shellQuote(WGCF_VERSION)}
    WGCF_BASELINE_URL=${shellQuote(WGCF_LINUX_AMD64_URL)}
    WGCF_BASELINE_SHA256=${shellQuote(WGCF_LINUX_AMD64_SHA256)}
    WGCF_LATEST_API=${shellQuote(WGCF_LATEST_RELEASE_API)}
    WGCF_BIN=/usr/local/bin/wgcf
    WGCF_STATE_DIR=/etc/wdtt-plus/warp

    wdtt_wgcf_compatible() {
      candidate="${'$'}1"
      [ -x "${'$'}candidate" ] || return 1
      "${'$'}candidate" register --help 2>&1 | grep -q -- '--accept-tos' || return 1
      "${'$'}candidate" generate --help 2>&1 | grep -q -- '--profile' || return 1
      "${'$'}candidate" status --help >/dev/null 2>&1 || return 1
    }

    wdtt_wgcf_registration_error() {
      log="${'$'}1"
      if grep -Eqi '(^|[^0-9])429([^0-9]|${'$'})|too many requests|rate.?limit' "${'$'}log"; then
        echo warp_registration_rate_limited
      elif grep -Eqi 'x509|certificate (has expired|is not yet valid|signed by unknown authority)|certificate verify' "${'$'}log"; then
        echo warp_registration_certificate_failed
      elif grep -Eqi 'tls handshake timeout|ssl connection timeout|handshake failure|remote error: tls' "${'$'}log"; then
        echo warp_registration_tls_failed
      elif grep -Eqi 'no such host|temporary failure in name resolution|server misbehaving|name or service not known' "${'$'}log"; then
        echo warp_registration_dns_failed
      elif grep -Eqi 'i/o timeout|context deadline exceeded|connection timed out|connection reset|connection refused|network is unreachable|no route to host' "${'$'}log"; then
        echo warp_registration_network_failed
      elif grep -Eqi '(^|[^0-9])(401|403)([^0-9]|${'$'})|forbidden|access denied|error 1020' "${'$'}log"; then
        echo warp_registration_rejected
      elif grep -Eqi '(^|[^0-9])5[0-9][0-9]([^0-9]|${'$'})|internal server error|bad gateway|service unavailable|gateway timeout' "${'$'}log"; then
        echo warp_registration_service_failed
      else
        echo warp_registration_failed
      fi
    }

    wdtt_fetch_wgcf_release() {
      version="${'$'}1"
      tag="v${'$'}version"
      file="wgcf_${'$'}{version}_linux_amd64"
      base="https://github.com/ViRb3/wgcf/releases/download/${'$'}tag"
      curl -fsSL --retry 2 --connect-timeout 12 --max-time 180 "${'$'}base/checksums.txt" -o "${'$'}WGCF_TMP/checksums.txt" || return 1
      curl -fsSL --retry 2 --connect-timeout 12 --max-time 300 "${'$'}base/${'$'}file" -o "${'$'}WGCF_TMP/wgcf.candidate" || return 1
      expected="${'$'}(awk -v name="${'$'}file" '${'$'}2 == name {print ${'$'}1; exit}' "${'$'}WGCF_TMP/checksums.txt")"
      [ -n "${'$'}expected" ] || return 1
      actual="${'$'}(sha256sum "${'$'}WGCF_TMP/wgcf.candidate" | awk '{print ${'$'}1}')"
      [ "${'$'}actual" = "${'$'}expected" ] || return 1
      chmod 700 "${'$'}WGCF_TMP/wgcf.candidate"
      wdtt_wgcf_compatible "${'$'}WGCF_TMP/wgcf.candidate" || return 1
      WGCF_SELECTED_VERSION="${'$'}version"
      WGCF_SELECTED_SHA256="${'$'}actual"
      return 0
    }

    wdtt_fetch_wgcf_baseline() {
      curl -fsSL --retry 2 --connect-timeout 12 --max-time 300 "${'$'}WGCF_BASELINE_URL" -o "${'$'}WGCF_TMP/wgcf.candidate" || return 1
      actual="${'$'}(sha256sum "${'$'}WGCF_TMP/wgcf.candidate" | awk '{print ${'$'}1}')"
      [ "${'$'}actual" = "${'$'}WGCF_BASELINE_SHA256" ] || return 1
      chmod 700 "${'$'}WGCF_TMP/wgcf.candidate"
      wdtt_wgcf_compatible "${'$'}WGCF_TMP/wgcf.candidate" || return 1
      WGCF_SELECTED_VERSION="${'$'}WGCF_BASELINE_VERSION"
      WGCF_SELECTED_SHA256="${'$'}actual"
      return 0
    }

    wdtt_install_or_update_wgcf() {
      arch="${'$'}(uname -m)"
      case "${'$'}arch" in
        x86_64|amd64) ;;
        *) echo WDTT_ERROR=warp_unsupported_arch; return 2;;
      esac
      command -v curl >/dev/null 2>&1 || { echo WDTT_ERROR=warp_download_tools_missing; return 2; }
      command -v sha256sum >/dev/null 2>&1 || { echo WDTT_ERROR=warp_checksum_tool_missing; return 2; }
      mkdir -p "${'$'}WGCF_STATE_DIR"
      chmod 700 "${'$'}WGCF_STATE_DIR"
      WGCF_TMP="${'$'}(mktemp -d)"
      WGCF_SELECTED_VERSION=""
      WGCF_SELECTED_SHA256=""
      latest_json="${'$'}WGCF_TMP/latest.json"
      latest_version=""
      if curl -fL --retry 1 --connect-timeout 10 --max-time 30 -H 'Accept: application/vnd.github+json' "${'$'}WGCF_LATEST_API" -o "${'$'}latest_json" 2>/dev/null; then
        latest_tag="${'$'}(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\(v[0-9][0-9.]*\)".*/\1/p' "${'$'}latest_json" | head -n 1)"
        latest_version="${'$'}{latest_tag#v}"
        printf '%s' "${'$'}latest_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+${'$'}' || latest_version=""
      fi
      if [ -n "${'$'}latest_version" ]; then
        wdtt_fetch_wgcf_release "${'$'}latest_version" || true
      fi
      if [ -z "${'$'}WGCF_SELECTED_VERSION" ]; then
        if wdtt_wgcf_compatible "${'$'}WGCF_BIN"; then
          WGCF_SELECTED_VERSION="${'$'}(cat "${'$'}WGCF_STATE_DIR/wgcf-version" 2>/dev/null || echo установленная)"
          WGCF_SELECTED_SHA256="${'$'}(sha256sum "${'$'}WGCF_BIN" | awk '{print ${'$'}1}')"
          printf '%s\n' "${'$'}WGCF_SELECTED_VERSION" >"${'$'}WGCF_STATE_DIR/wgcf-version"
          printf '%s\n' "${'$'}WGCF_SELECTED_SHA256" >"${'$'}WGCF_STATE_DIR/wgcf-sha256"
          chmod 600 "${'$'}WGCF_STATE_DIR/wgcf-version" "${'$'}WGCF_STATE_DIR/wgcf-sha256"
          rm -rf "${'$'}WGCF_TMP"
          echo "Свежий выпуск wgcf сейчас не проверен; сохранена установленная совместимая версия ${'$'}WGCF_SELECTED_VERSION."
          return 0
        fi
        wdtt_fetch_wgcf_baseline || { rm -rf "${'$'}WGCF_TMP"; echo WDTT_ERROR=warp_wgcf_download_failed; return 2; }
      fi
      current_sha=""
      [ -x "${'$'}WGCF_BIN" ] && current_sha="${'$'}(sha256sum "${'$'}WGCF_BIN" | awk '{print ${'$'}1}')"
      if [ "${'$'}current_sha" != "${'$'}WGCF_SELECTED_SHA256" ]; then
        [ -x "${'$'}WGCF_BIN" ] && cp -f "${'$'}WGCF_BIN" "${'$'}WGCF_BIN.previous" || true
        install -m 700 "${'$'}WGCF_TMP/wgcf.candidate" "${'$'}WGCF_BIN"
        if [ -f "${'$'}WGCF_STATE_DIR/wgcf-account.toml" ] &&
           ! "${'$'}WGCF_BIN" --config "${'$'}WGCF_STATE_DIR/wgcf-account.toml" status >/dev/null 2>&1; then
          if [ -x "${'$'}WGCF_BIN.previous" ]; then
            install -m 700 "${'$'}WGCF_BIN.previous" "${'$'}WGCF_BIN"
            rm -rf "${'$'}WGCF_TMP"
            echo WDTT_ERROR=warp_wgcf_update_rolled_back
            return 2
          fi
        fi
      fi
      printf '%s\n' "${'$'}WGCF_SELECTED_VERSION" >"${'$'}WGCF_STATE_DIR/wgcf-version"
      printf '%s\n' "${'$'}WGCF_SELECTED_SHA256" >"${'$'}WGCF_STATE_DIR/wgcf-sha256"
      chmod 600 "${'$'}WGCF_STATE_DIR/wgcf-version" "${'$'}WGCF_STATE_DIR/wgcf-sha256"
      rm -rf "${'$'}WGCF_TMP"
      echo "Версия wgcf: ${'$'}WGCF_SELECTED_VERSION; SHA-256 проверен."
    }
""".trimIndent()

private fun freeWarpWatchdogInstallScript(): String = """
    mkdir -p /usr/local/lib/wdtt /etc/wdtt-plus/warp
    cat >/usr/local/lib/wdtt/warp-watchdog <<'WDTT_WARP_WATCHDOG'
    #!/bin/sh
    set -u
    ${outboundProbeFunctions().replace("\n", "\n    ")}
    MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
    [ "${'$'}MODE" = warp_free ] || exit 0
    STATE_DIR=/etc/wdtt-plus/warp
    IFACE=wg-wdtt-exit
    TEST_SOURCE="${'$'}(ip -4 -o addr show dev wdtt0 scope global 2>/dev/null | awk '{split(${'$'}4, value, "/"); print value[1]; exit}')"
    check_warp() {
      [ -n "${'$'}TEST_SOURCE" ] || return 1
      trace="${'$'}(wdtt_routed_https "${'$'}TEST_SOURCE" "wg-wdtt-exit" https://www.cloudflare.com/cdn-cgi/trace || true)"
      printf '%s\n' "${'$'}trace" | grep -Eq '^warp=(on|plus)${'$'}'
    }
    write_state() {
      status="${'$'}1"
      printf 'STATUS=%s\nCHECKED_AT=%s\n' "${'$'}status" "${'$'}(date -Is)" >"${'$'}STATE_DIR/health.env"
      chmod 600 "${'$'}STATE_DIR/health.env"
    }
    if check_warp; then
      write_state healthy
      exit 0
    fi
    systemctl restart wdtt-wg-exit.service >/dev/null 2>&1 || true
    sleep 8
    if check_warp; then
      write_state recovered
      exit 0
    fi
    write_state failed
    exit 1
    WDTT_WARP_WATCHDOG
    chmod 700 /usr/local/lib/wdtt/warp-watchdog
    cat >/etc/systemd/system/wdtt-warp-watchdog.service <<'WDTT_WARP_WATCHDOG_SERVICE'
    [Unit]
    Description=WDTT Plus free WARP health check
    After=network-online.target wdtt-wg-exit.service

    [Service]
    Type=oneshot
    ExecStart=/usr/local/lib/wdtt/warp-watchdog
    WDTT_WARP_WATCHDOG_SERVICE
    cat >/etc/systemd/system/wdtt-warp-watchdog.timer <<'WDTT_WARP_WATCHDOG_TIMER'
    [Unit]
    Description=Check WDTT Plus free WARP every five minutes

    [Timer]
    OnBootSec=2min
    OnUnitActiveSec=5min
    RandomizedDelaySec=30
    Persistent=true

    [Install]
    WantedBy=timers.target
    WDTT_WARP_WATCHDOG_TIMER
    systemctl daemon-reload
    systemctl enable --now wdtt-warp-watchdog.timer >/dev/null
""".trimIndent()

private fun freeWarpAutoTuneScript(): String = """
    WARP_LAST_REASON=""
    WARP_LAST_STATE=""
    WARP_LAST_HANDSHAKE=""
    WARP_LAST_EXIT_IP=""
    WARP_SELECTED_MTU=""
    WARP_SELECTED_ENDPOINT=""
    WARP_ENDPOINT_CANDIDATES=""
    WARP_ENDPOINT_COUNT=0
    WARP_MTU_CANDIDATES=""

    wdtt_warp_endpoint_valid() {
      local value="${'$'}1"
      local port
      [ -n "${'$'}value" ] || return 1
      printf '%s' "${'$'}value" | grep -Eq '^[A-Za-z0-9._-]+:[0-9]{1,5}${'$'}' || return 1
      port="${'$'}{value##*:}"
      [ "${'$'}port" -ge 1 ] 2>/dev/null && [ "${'$'}port" -le 65535 ] || return 1
      return 0
    }

    wdtt_warp_add_endpoint() {
      local candidate="${'$'}1"
      wdtt_warp_endpoint_valid "${'$'}candidate" || return 0
      case " ${'$'}WARP_ENDPOINT_CANDIDATES " in
        *" ${'$'}candidate "*) return 0;;
      esac
      [ "${'$'}WARP_ENDPOINT_COUNT" -lt 10 ] || return 0
      WARP_ENDPOINT_CANDIDATES="${'$'}WARP_ENDPOINT_CANDIDATES ${'$'}candidate"
      WARP_ENDPOINT_COUNT=${'$'}((WARP_ENDPOINT_COUNT + 1))
    }

    wdtt_warp_profile_endpoint() {
      awk -F= 'tolower(${'$'}1) ~ /^[[:space:]]*endpoint[[:space:]]*${'$'}/ { value=${'$'}2; sub(/^[[:space:]]*/, "", value); sub(/[[:space:]]*${'$'}/, "", value); print value; exit }' "${'$'}RAW_PROFILE"
    }

    wdtt_warp_build_endpoint_candidates() {
      local original host port p ip
      WARP_ENDPOINT_CANDIDATES=""
      WARP_ENDPOINT_COUNT=0
      original="${'$'}(wdtt_warp_profile_endpoint || true)"
      wdtt_warp_add_endpoint "${'$'}original"
      wdtt_warp_add_endpoint "engage.cloudflareclient.com:2408"
      wdtt_warp_add_endpoint "engage.cloudflareclient.com:500"
      if wdtt_warp_endpoint_valid "${'$'}original"; then
        host="${'$'}{original%:*}"
        port="${'$'}{original##*:}"
        for p in "${'$'}port" 2408 500 1701 4500; do
          wdtt_warp_add_endpoint "${'$'}host:${'$'}p"
        done
        if command -v getent >/dev/null 2>&1; then
          for ip in ${'$'}(getent ahostsv4 "${'$'}host" 2>/dev/null | awk '{print ${'$'}1}' | grep -E '^[0-9.]+${'$'}' | awk '!seen[${'$'}0]++' | head -n 4); do
            for p in 2408 500; do
              wdtt_warp_add_endpoint "${'$'}ip:${'$'}p"
            done
          done
        fi
      fi
      wdtt_warp_add_endpoint "162.159.192.1:2408"
      wdtt_warp_add_endpoint "162.159.193.1:2408"
      wdtt_warp_add_endpoint "162.159.192.1:500"
      wdtt_warp_add_endpoint "162.159.193.1:500"
    }

    wdtt_warp_add_mtu() {
      local value="${'$'}1"
      printf '%s' "${'$'}value" | grep -Eq '^(12[89][0-9]|1[3-4][0-9][0-9]|1500)${'$'}' || return 0
      case " ${'$'}WARP_MTU_CANDIDATES " in
        *" ${'$'}value "*) return 0;;
      esac
      WARP_MTU_CANDIDATES="${'$'}WARP_MTU_CANDIDATES ${'$'}value"
    }

    wdtt_warp_build_mtu_candidates() {
      local value
      WARP_MTU_CANDIDATES=""
      for value in "${'$'}WARP_MTU" 1420 1392 1360 1280; do
        wdtt_warp_add_mtu "${'$'}value"
      done
    }

    wdtt_warp_write_profile() {
      local mtu="${'$'}1"
      local endpoint="${'$'}2"
      local tmp="${'$'}SAFE_PROFILE.tmp"
      printf '%s' "${'$'}mtu" | grep -Eq '^(12[89][0-9]|1[3-4][0-9][0-9]|1500)${'$'}' || { WARP_LAST_REASON="invalid_mtu"; return 2; }
      wdtt_warp_endpoint_valid "${'$'}endpoint" || { WARP_LAST_REASON="invalid_endpoint"; return 2; }
      awk -v mtu="${'$'}mtu" -v endpoint="${'$'}endpoint" '
        BEGIN { in_interface=0; in_peer=0; endpoint_written=0 }
        /^[[:space:]]*\[Interface\][[:space:]]*${'$'}/ {
          print "[Interface]"
          print "Table = off"
          print "MTU = " mtu
          in_interface=1
          in_peer=0
          next
        }
        /^[[:space:]]*\[Peer\][[:space:]]*${'$'}/ {
          print "[Peer]"
          in_interface=0
          in_peer=1
          next
        }
        /^[[:space:]]*\[/ {
          in_interface=0
          in_peer=0
          print
          next
        }
        in_interface && /^[[:space:]]*(Table|MTU|DNS|PreUp|PostUp|PreDown|PostDown)[[:space:]]*=/ { next }
        in_peer && /^[[:space:]]*Endpoint[[:space:]]*=/ {
          if (!endpoint_written) {
            print "Endpoint = " endpoint
            endpoint_written=1
          }
          next
        }
        /^[[:space:]]*(PreUp|PostUp|PreDown|PostDown)[[:space:]]*=/ { next }
        { print }
      ' "${'$'}RAW_PROFILE" >"${'$'}tmp"
      grep -Eq '^[[:space:]]*\[Interface\][[:space:]]*${'$'}' "${'$'}tmp" || { rm -f "${'$'}tmp"; WARP_LAST_REASON="profile_without_interface"; return 2; }
      grep -Eq '^[[:space:]]*\[Peer\][[:space:]]*${'$'}' "${'$'}tmp" || { rm -f "${'$'}tmp"; WARP_LAST_REASON="profile_without_peer"; return 2; }
      grep -Eq '^[[:space:]]*PrivateKey[[:space:]]*=' "${'$'}tmp" || { rm -f "${'$'}tmp"; WARP_LAST_REASON="profile_without_private_key"; return 2; }
      grep -Eq '^[[:space:]]*Endpoint[[:space:]]*=[[:space:]]*[^[:space:]]+:[0-9]+' "${'$'}tmp" || { rm -f "${'$'}tmp"; WARP_LAST_REASON="profile_without_endpoint"; return 2; }
      grep -Eq '^[[:space:]]*AllowedIPs[[:space:]]*=.*0\.0\.0\.0/0' "${'$'}tmp" || { rm -f "${'$'}tmp"; WARP_LAST_REASON="profile_without_default_route"; return 2; }
      ! grep -Eqi '^[[:space:]]*(DNS|PreUp|PostUp|PreDown|PostDown)[[:space:]]*=' "${'$'}tmp" || { rm -f "${'$'}tmp"; WARP_LAST_REASON="profile_has_unsafe_lines"; return 2; }
      chmod 600 "${'$'}tmp"
      install -m 600 "${'$'}tmp" /etc/wireguard/wg-wdtt-exit.conf
      install -m 600 "${'$'}tmp" /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
      mv -f "${'$'}tmp" "${'$'}SAFE_PROFILE"
    }

    wdtt_warp_trace_state() {
      printf '%s\n' "${'$'}1" | sed -n 's/^warp=//p' | head -n 1
    }

    wdtt_warp_latest_handshake() {
      wg show "${'$'}WDTT_WG_IFACE" latest-handshakes 2>/dev/null | awk '{print ${'$'}2}' | sort -nr | head -n 1
    }

    wdtt_warp_check_current() {
      local source="${'$'}1"
      local trace
      [ -n "${'$'}source" ] || { WARP_LAST_REASON="wdtt_test_source_missing"; return 1; }
      trace="${'$'}(wdtt_routed_https "${'$'}source" "wg-wdtt-exit" https://www.cloudflare.com/cdn-cgi/trace || true)"
      WARP_LAST_STATE="${'$'}(wdtt_warp_trace_state "${'$'}trace")"
      WARP_LAST_HANDSHAKE="${'$'}(wdtt_warp_latest_handshake || true)"
      case "${'$'}WARP_LAST_STATE" in
        on|plus) return 0;;
      esac
      if [ -z "${'$'}trace" ]; then
        WARP_LAST_REASON="trace_empty"
      elif [ -n "${'$'}WARP_LAST_STATE" ]; then
        WARP_LAST_REASON="trace_warp_${'$'}WARP_LAST_STATE"
      else
        WARP_LAST_REASON="trace_without_warp_state"
      fi
      return 1
    }

    wdtt_warp_save_selected() {
      local endpoint_safe
      endpoint_safe="${'$'}(printf '%s' "${'$'}WARP_SELECTED_ENDPOINT" | tr -cd 'A-Za-z0-9._:-')"
      printf 'WARP_MTU=%s\nWARP_ENDPOINT=%s\nWARP_STATE=%s\nCHECKED_AT=%s\n' \
        "${'$'}WARP_SELECTED_MTU" "${'$'}endpoint_safe" "${'$'}WARP_LAST_STATE" "${'$'}(date -Is)" >"${'$'}WARP_DIR/selected.env"
      printf 'STATUS=healthy\nCHECKED_AT=%s\n' "${'$'}(date -Is)" >"${'$'}WARP_DIR/health.env"
      chmod 600 "${'$'}WARP_DIR/selected.env" "${'$'}WARP_DIR/health.env"
    }

    wdtt_warp_try_candidate() {
      local mtu="${'$'}1"
      local endpoint="${'$'}2"
      local source="${'$'}3"
      local attempt
      echo "WDTT_PROGRESS|0.97|Пробую WARP: MTU ${'$'}mtu, endpoint ${'$'}endpoint..."
      wdtt_warp_write_profile "${'$'}mtu" "${'$'}endpoint" || return 1
      if ! systemctl restart wdtt-wg-exit.service >/dev/null 2>&1; then
        WARP_LAST_REASON="wireguard_exit_service_inactive"
        return 1
      fi
      sleep 3
      if ! wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1; then
        WARP_LAST_REASON="wireguard_not_active"
        return 1
      fi
      for attempt in 1 2; do
        if wdtt_warp_check_current "${'$'}source"; then
          WARP_SELECTED_MTU="${'$'}mtu"
          WARP_SELECTED_ENDPOINT="${'$'}endpoint"
          WARP_LAST_EXIT_IP="${'$'}(wdtt_routed_public_ip "${'$'}source" "wg-wdtt-exit" || true)"
          wdtt_warp_save_selected
          return 0
        fi
        [ "${'$'}attempt" = 2 ] || sleep 4
      done
      return 1
    }

    wdtt_warp_autotune() {
      local source primary mtu endpoint attempted
      source="${'$'}(wdtt_test_source)"
      [ -n "${'$'}source" ] || { WARP_LAST_REASON="wdtt_test_source_missing"; return 1; }
      primary="${'$'}(wdtt_warp_profile_endpoint || true)"
      wdtt_warp_build_mtu_candidates
      wdtt_warp_build_endpoint_candidates
      [ -n "${'$'}WARP_ENDPOINT_CANDIDATES" ] || { WARP_LAST_REASON="no_endpoint_candidates"; return 1; }
      attempted=0
      if wdtt_warp_endpoint_valid "${'$'}primary"; then
        for mtu in ${'$'}WARP_MTU_CANDIDATES; do
          attempted=${'$'}((attempted + 1))
          wdtt_warp_try_candidate "${'$'}mtu" "${'$'}primary" "${'$'}source" && return 0
        done
      fi
      for endpoint in ${'$'}WARP_ENDPOINT_CANDIDATES; do
        [ "${'$'}endpoint" = "${'$'}primary" ] && continue
        attempted=${'$'}((attempted + 1))
        wdtt_warp_try_candidate "1280" "${'$'}endpoint" "${'$'}source" && return 0
      done
      WARP_LAST_REASON="${'$'}{WARP_LAST_REASON:-all_candidates_failed}; attempts=${'$'}attempted"
      return 1
    }
""".trimIndent()

private suspend fun updateWgcfTool(context: Context, target: OutboundSshTarget): String =
    runRootScript(
        context = context,
        target = target,
        script = shellScript(
            outboundShellPrelude(),
            wgcfToolManagementScript(),
            """
            echo "WDTT_PROGRESS|0.15|Проверяю архитектуру и инструменты загрузки..."
            wdtt_install_wireguard_tools || true
            echo "WDTT_PROGRESS|0.45|Проверяю свежий выпуск wgcf и контрольную сумму..."
            wdtt_install_or_update_wgcf
            echo "WDTT_PROGRESS|1.0|Проверка обновления wgcf завершена."
            """
        ),
        timeout = 8 * 60 * 1000L
    )

internal fun buildFreeWarpInstallScript(mtu: Int = 1280): String {
    require(mtu in 1280..1500) { "MTU WARP должен быть от 1280 до 1500" }
    return shellScript(
        "WARP_MTU=$mtu",
        outboundShellPrelude(),
        wgcfToolManagementScript(),
        """
        echo "WDTT_PROGRESS|0.08|Проверяю сервер и устанавливаю WireGuard-инструменты..."
        [ -d /sys/class/net/"${'$'}WDTT_IFACE" ] || { echo WDTT_ERROR=wdtt_iface_not_found; exit 2; }
        wdtt_install_wireguard_tools || true
        command -v wg-quick >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_tools_required; exit 2; }
        echo "WDTT_PROGRESS|0.24|Проверяю выпуск и SHA-256 инструмента wgcf..."
        wdtt_install_or_update_wgcf
        WARP_DIR=/etc/wdtt-plus/warp
        ACCOUNT="${'$'}WARP_DIR/wgcf-account.toml"
        RAW_PROFILE="${'$'}WARP_DIR/wgcf-profile.raw.conf"
        SAFE_PROFILE="${'$'}WARP_DIR/wgcf-profile.conf"
        mkdir -p "${'$'}WARP_DIR" /etc/wireguard /etc/wdtt-plus/wg-exit
        chmod 700 "${'$'}WARP_DIR"
        umask 077
        echo "WDTT_PROGRESS|0.38|Проверяю сохранённую регистрацию WARP..."
        NEED_REGISTER=0
        if [ -f "${'$'}ACCOUNT" ]; then
          if ! /usr/local/bin/wgcf --config "${'$'}ACCOUNT" status >/dev/null 2>&1; then
            mv "${'$'}ACCOUNT" "${'$'}ACCOUNT.invalid-$(date +%s)"
            NEED_REGISTER=1
          fi
        else
          NEED_REGISTER=1
        fi
        if [ "${'$'}NEED_REGISTER" = 1 ]; then
          echo "WDTT_PROGRESS|0.46|Регистрирую бесплатный профиль Cloudflare WARP..."
          if ! /usr/local/bin/wgcf --config "${'$'}ACCOUNT" register --accept-tos --name "WDTT Plus" --model "WDTT Plus Server" >/tmp/wdtt-wgcf-register.log 2>&1; then
            if [ ! -f "${'$'}ACCOUNT" ] || ! /usr/local/bin/wgcf --config "${'$'}ACCOUNT" status >/dev/null 2>&1; then
              REGISTER_ERROR="${'$'}(wdtt_wgcf_registration_error /tmp/wdtt-wgcf-register.log)"
              rm -f /tmp/wdtt-wgcf-register.log
              echo "WDTT_ERROR=${'$'}REGISTER_ERROR"
              exit 3
            fi
            echo "Cloudflare вернул ошибку регистрации, но созданный профиль прошёл повторную проверку; продолжаю."
          fi
        fi
        echo "WDTT_PROGRESS|0.56|Создаю WireGuard-профиль WARP..."
        /usr/local/bin/wgcf --config "${'$'}ACCOUNT" generate --profile "${'$'}RAW_PROFILE" >/tmp/wdtt-wgcf-generate.log 2>&1 || {
          rm -f /tmp/wdtt-wgcf-generate.log
          echo WDTT_ERROR=warp_profile_generation_failed
          exit 3
        }
        rm -f /tmp/wdtt-wgcf-register.log /tmp/wdtt-wgcf-generate.log
        grep -Eq '^[[:space:]]*\[Interface\][[:space:]]*${'$'}' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        grep -Eq '^[[:space:]]*PrivateKey[[:space:]]*=' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        grep -Eq '^[[:space:]]*Address[[:space:]]*=' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        grep -Eq '^[[:space:]]*\[Peer\][[:space:]]*${'$'}' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        grep -Eq '^[[:space:]]*Endpoint[[:space:]]*=' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        grep -Eq '^[[:space:]]*AllowedIPs[[:space:]]*=.*0\.0\.0\.0/0' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        [ "${'$'}(wc -c <"${'$'}RAW_PROFILE")" -le 65536 ] || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        [ "${'$'}(grep -Eic '^[[:space:]]*\[Interface\][[:space:]]*${'$'}' "${'$'}RAW_PROFILE")" = 1 ] || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        [ "${'$'}(grep -Eic '^[[:space:]]*\[Peer\][[:space:]]*${'$'}' "${'$'}RAW_PROFILE")" = 1 ] || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        ! grep -Eqi '^[[:space:]]*(PreUp|PostUp|PreDown|PostDown)[[:space:]]*=' "${'$'}RAW_PROFILE" || { echo WDTT_ERROR=warp_profile_invalid; exit 3; }
        printf '%s' "${'$'}WARP_MTU" | grep -Eq '^(12[89][0-9]|1[3-4][0-9][0-9]|1500)${'$'}' || { echo WDTT_ERROR=warp_mtu_invalid; exit 3; }
        awk -v mtu="${'$'}WARP_MTU" '
          BEGIN { in_interface=0 }
          /^[[:space:]]*\[Interface\][[:space:]]*${'$'}/ { print "[Interface]"; print "Table = off"; print "MTU = " mtu; in_interface=1; next }
          /^[[:space:]]*\[/ { in_interface=0; print; next }
          /^[[:space:]]*(Table|MTU|DNS|PreUp|PostUp|PreDown|PostDown)[[:space:]]*=/ { next }
          { print }
        ' "${'$'}RAW_PROFILE" >"${'$'}SAFE_PROFILE"
        chmod 600 "${'$'}ACCOUNT" "${'$'}RAW_PROFILE" "${'$'}SAFE_PROFILE"
        install -m 600 "${'$'}SAFE_PROFILE" /etc/wireguard/wg-wdtt-exit.conf
        install -m 600 "${'$'}SAFE_PROFILE" /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
        """,
        freeWarpAutoTuneScript(),
        wireGuardPolicyScript("warp_free", "бесплатный Cloudflare WARP"),
        freeWarpWatchdogInstallScript(),
        """
        echo "WDTT_PROGRESS|0.96|Подбираю MTU/endpoint и проверяю выход через WARP..."
        if ! wdtt_warp_autotune; then
          systemctl disable --now wdtt-warp-watchdog.timer 2>/dev/null || true
          wdtt_clear_external_out
          wdtt_write_mode "direct" "rollback after WARP check error"
          echo "Диагностика WARP: ${'$'}{WARP_LAST_REASON:-проверка Cloudflare не прошла}."
          echo WDTT_ERROR=warp_trace_check_failed
          exit 3
        fi
        echo "WDTT_PROGRESS|1.0|Бесплатный WARP установлен и проверен."
        echo "Бесплатный WARP включён только для WDTT-пользователей."
        echo "Подобрано: MTU ${'$'}WARP_SELECTED_MTU, endpoint ${'$'}WARP_SELECTED_ENDPOINT."
        [ -n "${'$'}WARP_LAST_EXIT_IP" ] && echo "Проверочный выходной IP Cloudflare: ${'$'}WARP_LAST_EXIT_IP"
        [ -n "${'$'}WARP_LAST_HANDSHAKE" ] && [ "${'$'}WARP_LAST_HANDSHAKE" != 0 ] && echo "WireGuard-рукопожатие WARP получено."
        echo "Автоматическая проверка запускается каждые 5 минут и один раз перезапускает WireGuard при сбое."
        """
    )
}

private suspend fun installOrRepairFreeWarp(
    context: Context,
    target: OutboundSshTarget,
    mtu: Int
): String = runRootScript(context, target, buildFreeWarpInstallScript(mtu), timeout = CMD_TIMEOUT)

internal suspend fun checkFreeWarp(
    target: OutboundSshTarget,
    restartOnFailure: Boolean
): String = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        session = if (restartOnFailure) createCriticalSshSession(target) else createSafeReadOnlySshSession(target)
        val ssh = SSHClient(session, target.pass)
        val restart = if (restartOnFailure) "1" else "0"
        val script = shellScript(
            outboundShellPrelude(),
            """
            WARP_DIR=/etc/wdtt-plus/warp
            ACCOUNT="${'$'}WARP_DIR/wgcf-account.toml"
            RAW_PROFILE="${'$'}WARP_DIR/wgcf-profile.raw.conf"
            SAFE_PROFILE="${'$'}WARP_DIR/wgcf-profile.conf"
            CURRENT_MTU="${'$'}(sed -n 's/^[[:space:]]*MTU[[:space:]]*=[[:space:]]*//Ip' /etc/wireguard/wg-wdtt-exit.conf 2>/dev/null | head -n 1)"
            printf '%s' "${'$'}CURRENT_MTU" | grep -Eq '^(12[89][0-9]|1[3-4][0-9][0-9]|1500)${'$'}' || CURRENT_MTU=1280
            WARP_MTU="${'$'}CURRENT_MTU"
            """,
            freeWarpAutoTuneScript(),
            """
            MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
            [ "${'$'}MODE" = "warp_free" ] || { echo WDTT_ERROR=warp_mode_not_active; exit 3; }
            [ -f "${'$'}ACCOUNT" ] || { echo WDTT_ERROR=warp_account_missing; exit 3; }
            [ -f /etc/wireguard/wg-wdtt-exit.conf ] || { echo WDTT_ERROR=warp_profile_missing; exit 3; }
            command -v wg >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_tools_required; exit 2; }
            if [ "$restart" = 1 ]; then
              [ -f "${'$'}RAW_PROFILE" ] || { echo WDTT_ERROR=warp_profile_missing; exit 3; }
              BACKUP_PROFILE="${'$'}(mktemp)"
              cp -f /etc/wireguard/wg-wdtt-exit.conf "${'$'}BACKUP_PROFILE"
              echo "WDTT_PROGRESS|0.30|Перезапускаю WARP и подбираю MTU/endpoint..."
              if ! wdtt_warp_autotune; then
                install -m 600 "${'$'}BACKUP_PROFILE" /etc/wireguard/wg-wdtt-exit.conf 2>/dev/null || true
                install -m 600 "${'$'}BACKUP_PROFILE" /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf 2>/dev/null || true
                rm -f "${'$'}BACKUP_PROFILE"
                systemctl restart wdtt-wg-exit.service >/dev/null 2>&1 || true
                echo "Диагностика WARP: ${'$'}{WARP_LAST_REASON:-проверка Cloudflare не прошла}."
                echo WDTT_ERROR=warp_trace_check_failed
                exit 3
              fi
              rm -f "${'$'}BACKUP_PROFILE"
            else
              wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_not_active; exit 3; }
              TEST_SOURCE="${'$'}(wdtt_test_source)"
              [ -n "${'$'}TEST_SOURCE" ] || { echo WDTT_ERROR=wdtt_test_source_missing; exit 3; }
              echo "WDTT_PROGRESS|0.60|Проверяю текущий WARP без изменения настроек..."
              if ! wdtt_warp_check_current "${'$'}TEST_SOURCE"; then
                echo "Диагностика WARP: ${'$'}{WARP_LAST_REASON:-проверка Cloudflare не прошла}."
                echo WDTT_ERROR=warp_trace_check_failed
                exit 3
              fi
              WARP_LAST_EXIT_IP="${'$'}(wdtt_routed_public_ip "${'$'}TEST_SOURCE" "wg-wdtt-exit" || true)"
            fi
            WARP_KIND="${'$'}WARP_LAST_STATE"
            VERSION="${'$'}(cat "${'$'}WARP_DIR/wgcf-version" 2>/dev/null || echo неизвестна)"
            TIMER="${'$'}(systemctl is-active wdtt-warp-watchdog.timer 2>/dev/null || true)"
            printf 'STATUS=healthy\nCHECKED_AT=%s\n' "${'$'}(date -Is)" >/etc/wdtt-plus/warp/health.env
            chmod 600 /etc/wdtt-plus/warp/health.env
            echo "Проверка успешна: Cloudflare сообщает warp=${'$'}WARP_KIND."
            [ -n "${'$'}WARP_LAST_EXIT_IP" ] && echo "Выходной IP: ${'$'}WARP_LAST_EXIT_IP"
            if [ -n "${'$'}WARP_SELECTED_MTU" ]; then
              echo "Подобрано: MTU ${'$'}WARP_SELECTED_MTU, endpoint ${'$'}WARP_SELECTED_ENDPOINT."
            fi
            echo "wgcf: ${'$'}VERSION"
            echo "Автопроверка: ${'$'}{TIMER:-не запущена}"
            [ -n "${'$'}WARP_LAST_HANDSHAKE" ] && [ "${'$'}WARP_LAST_HANDSHAKE" != 0 ] && echo "Последнее WireGuard-рукопожатие: $(date -d @${'$'}WARP_LAST_HANDSHAKE -Is 2>/dev/null || echo ${'$'}WARP_LAST_HANDSHAKE)"
            """
        )
        val output = ssh.exec(rootCommand(script), timeout = 45000L).trim()
        markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException(rootScriptErrorExcerpt(output)) }
        output
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
    }
}

private suspend fun resetFreeWarpRegistration(target: OutboundSshTarget): String = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        session = createCriticalSshSession(target)
        val ssh = SSHClient(session, target.pass)
        val script = shellScript(
            outboundShellPrelude(),
            """
            WARP_DIR=/etc/wdtt-plus/warp
            MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
            OWNER="${'$'}(cat "${'$'}WDTT_WG_OWNER_FILE" 2>/dev/null | head -n 1)"
            CONFIG_OWNER="${'$'}(cat "${'$'}WDTT_WG_CONFIG_OWNER_FILE" 2>/dev/null | head -n 1)"
            WARP_CONFIG_MATCH=0
            if [ -f /etc/wireguard/wg-wdtt-exit.conf ] &&
               [ -f "${'$'}WARP_DIR/wgcf-profile.conf" ] &&
               cmp -s /etc/wireguard/wg-wdtt-exit.conf "${'$'}WARP_DIR/wgcf-profile.conf"; then
              WARP_CONFIG_MATCH=1
            fi
            WARP_OWNS_CONFIG=0
            if [ "${'$'}CONFIG_OWNER" = "warp_free" ] ||
               { [ -z "${'$'}CONFIG_OWNER" ] && [ "${'$'}WARP_CONFIG_MATCH" = 1 ]; }; then
              WARP_OWNS_CONFIG=1
            fi
            WARP_OWNS_ACTIVE_WG=0
            if [ "${'$'}MODE" = "warp_free" ] ||
               { [ "${'$'}MODE" = "direct" ] && [ "${'$'}OWNER" = "warp_free" ]; } ||
               { [ "${'$'}MODE" = "direct" ] && [ -z "${'$'}OWNER" ] && [ "${'$'}WARP_CONFIG_MATCH" = 1 ]; }; then
              WARP_OWNS_ACTIVE_WG=1
            fi
            echo "WDTT_PROGRESS|0.20|Останавливаю WARP и автопроверку..."
            systemctl disable --now wdtt-warp-watchdog.timer wdtt-warp-watchdog.service 2>/dev/null || true
            if [ "${'$'}MODE" = "warp_free" ] || [ "${'$'}WARP_OWNS_ACTIVE_WG" = 1 ]; then
              echo "WDTT_PROGRESS|0.45|Возвращаю прямой выход WDTT..."
              wdtt_clear_wireguard_out
              wdtt_write_mode "direct" "WARP registration reset"
            fi
            if [ "${'$'}WARP_OWNS_CONFIG" = 1 ]; then
              rm -f /etc/wireguard/wg-wdtt-exit.conf \
                /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf \
                "${'$'}WDTT_WG_CONFIG_OWNER_FILE"
            fi
            echo "WDTT_PROGRESS|0.75|Удаляю текущую регистрацию и профиль WARP..."
            mkdir -p "${'$'}WARP_DIR"
            rm -f "${'$'}WARP_DIR"/wgcf-account.toml "${'$'}WARP_DIR"/wgcf-account.toml.invalid-* \
              "${'$'}WARP_DIR"/wgcf-profile.raw.conf "${'$'}WARP_DIR"/wgcf-profile.conf \
              "${'$'}WARP_DIR"/selected.env "${'$'}WARP_DIR"/health.env
            chmod 700 "${'$'}WARP_DIR" 2>/dev/null || true
            systemctl reset-failed wdtt-warp-watchdog.service wdtt-wg-exit.service 2>/dev/null || true
            if [ -e "${'$'}WARP_DIR/wgcf-account.toml" ] ||
               [ -e "${'$'}WARP_DIR/wgcf-profile.conf" ]; then
              echo WDTT_ERROR=warp_registration_reset_failed
              exit 3
            fi
            echo "WDTT_PROGRESS|1.0|Регистрация WARP сброшена."
            echo "Текущая регистрация, ключи и профиль WARP удалены. Проверенный wgcf оставлен на сервере."
            if [ "${'$'}MODE" = "warp_free" ] || [ "${'$'}WARP_OWNS_ACTIVE_WG" = 1 ]; then
              echo "WDTT-пользователи снова выходят напрямую через текущий VPS. Для новой регистрации нажмите «Установить / восстановить»."
            else
              echo "Активный режим выхода WDTT не был WARP; он не изменён."
            fi
            """
        )
        val output = ssh.exec(rootCommand(script), timeout = 60000L).trim()
        markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException(rootScriptErrorExcerpt(output)) }
        output
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
    }
}

private suspend fun deleteFreeWarp(target: OutboundSshTarget): String = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        session = createCriticalSshSession(target)
        val ssh = SSHClient(session, target.pass)
        val script = shellScript(
            outboundShellPrelude(),
            """
            WARP_DIR=/etc/wdtt-plus/warp
            MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
            OWNER="${'$'}(cat "${'$'}WDTT_WG_OWNER_FILE" 2>/dev/null | head -n 1)"
            CONFIG_OWNER="${'$'}(cat "${'$'}WDTT_WG_CONFIG_OWNER_FILE" 2>/dev/null | head -n 1)"
            WARP_CONFIG_MATCH=0
            if [ -f /etc/wireguard/wg-wdtt-exit.conf ] &&
               [ -f "${'$'}WARP_DIR/wgcf-profile.conf" ] &&
               cmp -s /etc/wireguard/wg-wdtt-exit.conf "${'$'}WARP_DIR/wgcf-profile.conf"; then
              WARP_CONFIG_MATCH=1
            fi
            WARP_OWNS_CONFIG=0
            if [ "${'$'}CONFIG_OWNER" = "warp_free" ] ||
               { [ -z "${'$'}CONFIG_OWNER" ] && [ "${'$'}WARP_CONFIG_MATCH" = 1 ]; }; then
              WARP_OWNS_CONFIG=1
            fi
            WARP_OWNS_ACTIVE_WG=0
            if [ "${'$'}MODE" = "warp_free" ] ||
               { [ "${'$'}MODE" = "direct" ] && [ "${'$'}OWNER" = "warp_free" ]; } ||
               { [ "${'$'}MODE" = "direct" ] && [ -z "${'$'}OWNER" ] && [ "${'$'}WARP_CONFIG_MATCH" = 1 ]; }; then
              WARP_OWNS_ACTIVE_WG=1
            fi
            echo "WDTT_PROGRESS|0.20|Останавливаю автоматическую проверку WARP..."
            systemctl disable --now wdtt-warp-watchdog.timer wdtt-warp-watchdog.service 2>/dev/null || true
            if [ "${'$'}MODE" = "warp_free" ] || [ "${'$'}WARP_OWNS_ACTIVE_WG" = 1 ]; then
              echo "WDTT_PROGRESS|0.45|Возвращаю прямой выход WDTT..."
              wdtt_clear_wireguard_out
              wdtt_write_mode "direct" "прямой выход"
            fi
            if [ "${'$'}WARP_OWNS_CONFIG" = 1 ]; then
              rm -f /etc/wireguard/wg-wdtt-exit.conf \
                /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf \
                "${'$'}WDTT_WG_CONFIG_OWNER_FILE"
            fi
            echo "WDTT_PROGRESS|0.70|Удаляю регистрацию, ключи и профиль WARP..."
            rm -rf "${'$'}WARP_DIR"
            rm -f /usr/local/bin/wgcf /usr/local/bin/wgcf.previous /usr/local/lib/wdtt/warp-watchdog
            rm -f /etc/systemd/system/wdtt-warp-watchdog.service /etc/systemd/system/wdtt-warp-watchdog.timer
            systemctl daemon-reload 2>/dev/null || true
            systemctl reset-failed wdtt-warp-watchdog.service 2>/dev/null || true
            WARP_REMOVE_LEFT=0
            [ -e "${'$'}WARP_DIR" ] && WARP_REMOVE_LEFT=1
            [ -e /usr/local/bin/wgcf ] && WARP_REMOVE_LEFT=1
            [ -e /usr/local/lib/wdtt/warp-watchdog ] && WARP_REMOVE_LEFT=1
            [ -e /etc/systemd/system/wdtt-warp-watchdog.service ] && WARP_REMOVE_LEFT=1
            [ -e /etc/systemd/system/wdtt-warp-watchdog.timer ] && WARP_REMOVE_LEFT=1
            systemctl is-active --quiet wdtt-warp-watchdog.timer 2>/dev/null && WARP_REMOVE_LEFT=1
            systemctl is-enabled --quiet wdtt-warp-watchdog.timer 2>/dev/null && WARP_REMOVE_LEFT=1
            if [ "${'$'}WARP_REMOVE_LEFT" != 0 ]; then
              echo WDTT_ERROR=warp_remove_failed
              exit 3
            fi
            echo "WDTT_PROGRESS|1.0|Бесплатный WARP удалён."
            echo "Регистрация, ключи, профиль и автоматическая проверка WARP удалены с сервера."
            if [ "${'$'}MODE" = "warp_free" ] || [ "${'$'}WARP_OWNS_ACTIVE_WG" = 1 ]; then
              echo "WDTT-пользователи снова выходят напрямую через текущий VPS."
            else
              echo "Другой активный режим выхода не изменён."
            fi
            """
        )
        val output = ssh.exec(rootCommand(script), timeout = 60000L).trim()
        markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException(rootScriptErrorExcerpt(output)) }
        output
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
    }
}

private suspend fun enableImportedWireGuardExit(
    context: Context,
    target: OutboundSshTarget,
    config: String
): String = withContext(Dispatchers.IO) {
    val sanitized = sanitizeWireGuardConfigForWdttExit(config)
    var session: Session? = null
    val configFile = File(context.cacheDir, "wdtt-imported-wg.conf")
    try {
        configFile.writeText(sanitized)
        session = createCriticalSshSession(target)
        val ssh = SSHClient(session, target.pass)
        ssh.upload(configFile, "/tmp/wdtt-imported-wg.conf")
        val script = shellScript(
            outboundShellPrelude(),
            """
            echo "WDTT_PROGRESS|0.18|Готовлю инструменты WireGuard на текущем сервере..."
            wdtt_install_wireguard_tools || true
            echo "WDTT_PROGRESS|0.45|Сохраняю выбранный VPN/WireGuard-файл без опасных команд..."
            mkdir -p /etc/wdtt-plus/wg-exit /etc/wireguard
            install -m 600 /tmp/wdtt-imported-wg.conf /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
            install -m 600 /tmp/wdtt-imported-wg.conf /etc/wireguard/wg-wdtt-exit.conf
            rm -f /tmp/wdtt-imported-wg.conf
            """,
            wireGuardPolicyScript("imported_wg", "VPN/WireGuard-файл")
        )
        val output = ssh.exec(rootCommand(script), timeout = CMD_TIMEOUT)
        markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException(it) }
        output.trim()
    } finally {
        configFile.delete()
        try { session?.disconnect() } catch (_: Exception) {}
    }
}

internal fun deleteImportedWireGuardExitScript(): String = shellScript(
    outboundShellPrelude(),
    """
    MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
    OWNER="${'$'}(cat "${'$'}WDTT_WG_OWNER_FILE" 2>/dev/null | head -n 1)"
    CONFIG_OWNER="${'$'}(cat "${'$'}WDTT_WG_CONFIG_OWNER_FILE" 2>/dev/null | head -n 1)"
    OWNS_ACTIVE=0
    OWNS_CONFIG=0
    if [ "${'$'}MODE" = "imported_wg" ] ||
       { [ "${'$'}MODE" = "direct" ] && [ "${'$'}OWNER" = "imported_wg" ]; }; then
      OWNS_ACTIVE=1
      OWNS_CONFIG=1
    fi
    if [ "${'$'}CONFIG_OWNER" = "imported_wg" ] ||
       { [ -z "${'$'}CONFIG_OWNER" ] &&
         grep -q '^IMPORTED_WG_CONFIG_B64=.' /etc/wdtt/outbound-profile.env 2>/dev/null &&
         [ "${'$'}MODE" != "warp_free" ] &&
         [ "${'$'}MODE" != "wireguard_vps" ]; }; then
      OWNS_CONFIG=1
    fi
    echo "WDTT_PROGRESS|0.25|Отключаю импортированный WireGuard-выход, если он запущен..."
    if [ "${'$'}OWNS_ACTIVE" = 1 ]; then
      wdtt_clear_wireguard_out
      wdtt_write_mode "direct" "прямой выход"
    fi
    if [ "${'$'}OWNS_CONFIG" = 1 ]; then
      rm -f /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf \
        /etc/wireguard/wg-wdtt-exit.conf \
        "${'$'}WDTT_WG_CONFIG_OWNER_FILE"
    fi
    echo "WDTT_PROGRESS|0.60|Удаляю сохранённую копию VPN/WireGuard-файла..."
    if [ -f /etc/wdtt/outbound-profile.env ]; then
      tmp_profile=/etc/wdtt/outbound-profile.env.tmp
      grep -v '^IMPORTED_WG_CONFIG_B64=' /etc/wdtt/outbound-profile.env >"${'$'}tmp_profile" 2>/dev/null || true
      chmod 600 "${'$'}tmp_profile"
      mv "${'$'}tmp_profile" /etc/wdtt/outbound-profile.env
    fi
    IMPORTED_REMOVE_LEFT=0
    grep -q '^IMPORTED_WG_CONFIG_B64=' /etc/wdtt/outbound-profile.env 2>/dev/null &&
      IMPORTED_REMOVE_LEFT=1
    if [ "${'$'}OWNS_CONFIG" = 1 ]; then
      if [ "${'$'}OWNS_ACTIVE" = 1 ] &&
         command -v wg >/dev/null 2>&1 &&
         wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1; then
        IMPORTED_REMOVE_LEFT=1
      fi
      [ -e /etc/wireguard/wg-wdtt-exit.conf ] && IMPORTED_REMOVE_LEFT=1
      [ -e /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf ] && IMPORTED_REMOVE_LEFT=1
    fi
    if [ "${'$'}IMPORTED_REMOVE_LEFT" != 0 ]; then
      echo WDTT_ERROR=imported_wireguard_remove_failed
      exit 3
    fi
    echo "WDTT_PROGRESS|1.0|VPN/WireGuard-файл удалён."
    if [ "${'$'}OWNS_ACTIVE" = 1 ]; then
      echo "VPN/WireGuard-файл удалён, выход WDTT возвращён напрямую через текущий сервер."
    else
      echo "Сохранённая копия VPN/WireGuard-файла удалена. Другой активный режим выхода не изменён."
    fi
    """
)

private suspend fun deleteImportedWireGuardExit(target: OutboundSshTarget): String =
    runCheckedRootScript(target, deleteImportedWireGuardExitScript(), timeout = 30000L)

internal fun deleteWireGuardVpsCurrentScript(): String = shellScript(
    outboundShellPrelude(),
    """
    MODE="${'$'}(sed -n 's/.*"outboundMode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /etc/wdtt/outbound.json 2>/dev/null | head -n 1)"
    OWNER="${'$'}(cat "${'$'}WDTT_WG_OWNER_FILE" 2>/dev/null | head -n 1)"
    CONFIG_OWNER="${'$'}(cat "${'$'}WDTT_WG_CONFIG_OWNER_FILE" 2>/dev/null | head -n 1)"
    OWNS_ACTIVE=0
    OWNS_CONFIG=0
    if [ "${'$'}MODE" = "wireguard_vps" ] ||
       { [ "${'$'}MODE" = "direct" ] && [ "${'$'}OWNER" = "wireguard_vps" ]; }; then
      OWNS_ACTIVE=1
      OWNS_CONFIG=1
    fi
    if [ "${'$'}CONFIG_OWNER" = "wireguard_vps" ] ||
       { [ -z "${'$'}CONFIG_OWNER" ] &&
         [ -f /etc/wireguard/wg-wdtt-exit.conf ] &&
         grep -Eq 'Address[[:space:]]*=[[:space:]]*10\\.77\\.77\\.2/30' /etc/wireguard/wg-wdtt-exit.conf 2>/dev/null &&
         grep -Eq 'AllowedIPs[[:space:]]*=[[:space:]]*0\\.0\\.0\\.0/0' /etc/wireguard/wg-wdtt-exit.conf 2>/dev/null; }; then
      OWNS_CONFIG=1
    fi
    echo "WDTT_PROGRESS|0.25|Возвращаю прямой выход на текущем VPS..."
    if [ "${'$'}OWNS_ACTIVE" = 1 ]; then
      wdtt_clear_wireguard_out
      wdtt_write_mode "direct" "прямой выход"
    fi
    if [ "${'$'}OWNS_CONFIG" = 1 ]; then
      rm -f /etc/wireguard/wg-wdtt-exit.conf \
        /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf \
        "${'$'}WDTT_WG_CONFIG_OWNER_FILE" \
        /etc/wdtt-plus/wg-exit/private.key \
        /etc/wdtt-plus/wg-exit/public.key \
        /etc/systemd/system/wdtt-wg-exit.service \
        /usr/local/lib/wdtt/wg-exit-up \
        /usr/local/lib/wdtt/wg-exit-down
      rm -rf /etc/wdtt-plus/wg-exit/transactions
      rmdir /etc/wdtt-plus/wg-exit 2>/dev/null || true
      systemctl daemon-reload 2>/dev/null || true
    fi
    echo "WDTT_PROGRESS|0.55|Удаляю сохранённые поля дополнительного VPS..."
    if [ -f /etc/wdtt/outbound-profile.env ]; then
      tmp_profile=/etc/wdtt/outbound-profile.env.tmp
      grep -Ev '^(WG_VPS_HOST_B64|WG_VPS_SSH_PORT|WG_VPS_USER_B64|WG_VPS_PASSWORD_B64|WG_VPS_PORT|WG_VPS_DNS_B64)=' \
        /etc/wdtt/outbound-profile.env >"${'$'}tmp_profile" 2>/dev/null || true
      chmod 600 "${'$'}tmp_profile"
      mv "${'$'}tmp_profile" /etc/wdtt/outbound-profile.env
    fi
    CURRENT_REMOVE_LEFT=0
    if [ "${'$'}OWNS_CONFIG" = 1 ]; then
      systemctl is-active --quiet wdtt-wg-exit.service 2>/dev/null && CURRENT_REMOVE_LEFT=1
      systemctl is-enabled --quiet wdtt-wg-exit.service 2>/dev/null && CURRENT_REMOVE_LEFT=1
      if [ "${'$'}OWNS_ACTIVE" = 1 ]; then
        (command -v wg >/dev/null 2>&1 && wg show "${'$'}WDTT_WG_IFACE" >/dev/null 2>&1) &&
          CURRENT_REMOVE_LEFT=1
        ip rule show 2>/dev/null | grep -Eq "from [^ ]+ lookup ${'$'}WDTT_TABLE([[:space:]]|${'$'})|from [^ ]+ lookup wdtt-exit([[:space:]]|${'$'})" &&
          CURRENT_REMOVE_LEFT=1
      fi
      [ -e /etc/wireguard/wg-wdtt-exit.conf ] && CURRENT_REMOVE_LEFT=1
      [ -e /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf ] && CURRENT_REMOVE_LEFT=1
      [ -e /etc/wdtt-plus/wg-exit/private.key ] && CURRENT_REMOVE_LEFT=1
      [ -e /etc/wdtt-plus/wg-exit/public.key ] && CURRENT_REMOVE_LEFT=1
    fi
    if [ "${'$'}CURRENT_REMOVE_LEFT" != 0 ]; then
      echo WDTT_ERROR=wireguard_vps_current_remove_failed
      exit 3
    fi
    echo WDTT_CURRENT_WG_REMOVED=1
    """
)

internal fun deleteWireGuardVpsForeignScript(): String = """
    set -e
    WG_IFACE=wg-wdtt-exit
    WG_CONF=/etc/wireguard/wg-wdtt-exit.conf
    OWNED=0
    if [ -f "${'$'}WG_CONF" ]; then
      if grep -q 'WDTT_EXIT_FOREIGN' "${'$'}WG_CONF" 2>/dev/null &&
         grep -Eq 'Address[[:space:]]*=[[:space:]]*10\\.77\\.77\\.1/30' "${'$'}WG_CONF" 2>/dev/null; then
        OWNED=1
      else
        echo WDTT_ERROR=foreign_wireguard_not_owned
        exit 3
      fi
    fi
    if command -v iptables >/dev/null 2>&1 &&
       { iptables -S INPUT 2>/dev/null | grep -q WDTT_EXIT_FOREIGN ||
         iptables -S FORWARD 2>/dev/null | grep -q WDTT_EXIT_FOREIGN ||
         iptables -t nat -S POSTROUTING 2>/dev/null | grep -q WDTT_EXIT_FOREIGN; }; then
      OWNED=1
    fi
    if [ "${'$'}OWNED" != 1 ]; then
      echo WDTT_FOREIGN_WG_REMOVED=1
      echo "На дополнительном VPS созданная WDTT конфигурация WireGuard уже отсутствует."
      exit 0
    fi
    echo "WDTT_PROGRESS|0.72|Останавливаю WireGuard на дополнительном VPS..."
    systemctl disable --now wg-quick@wg-wdtt-exit 2>/dev/null || true
    if command -v iptables >/dev/null 2>&1; then
      iptables -S INPUT 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
        while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
      iptables -S FORWARD 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
        while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
      iptables -t nat -S POSTROUTING 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -t nat -D /' |
        while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
    fi
    echo "WDTT_PROGRESS|0.86|Удаляю конфигурацию и ключи с дополнительного VPS..."
    rm -f "${'$'}WG_CONF" \
      /etc/sysctl.d/99-wdtt-exit-forward.conf \
      /etc/wdtt-plus/wg-exit/private.key \
      /etc/wdtt-plus/wg-exit/public.key \
      /etc/wdtt-plus/wg-exit/owner \
      /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
    rm -rf /etc/wdtt-plus/wg-exit/transactions
    rmdir /etc/wdtt-plus/wg-exit 2>/dev/null || true
    FOREIGN_REMOVE_LEFT=0
    systemctl is-active --quiet wg-quick@wg-wdtt-exit 2>/dev/null && FOREIGN_REMOVE_LEFT=1
    systemctl is-enabled --quiet wg-quick@wg-wdtt-exit 2>/dev/null && FOREIGN_REMOVE_LEFT=1
    [ -e "${'$'}WG_CONF" ] && FOREIGN_REMOVE_LEFT=1
    [ -e /etc/wdtt-plus/wg-exit/private.key ] && FOREIGN_REMOVE_LEFT=1
    [ -e /etc/wdtt-plus/wg-exit/public.key ] && FOREIGN_REMOVE_LEFT=1
    if command -v iptables >/dev/null 2>&1 &&
       { iptables -S INPUT 2>/dev/null | grep -q WDTT_EXIT_FOREIGN ||
         iptables -S FORWARD 2>/dev/null | grep -q WDTT_EXIT_FOREIGN ||
         iptables -t nat -S POSTROUTING 2>/dev/null | grep -q WDTT_EXIT_FOREIGN; }; then
      FOREIGN_REMOVE_LEFT=1
    fi
    if [ "${'$'}FOREIGN_REMOVE_LEFT" != 0 ]; then
      echo WDTT_ERROR=foreign_wireguard_remove_failed
      exit 3
    fi
    echo WDTT_FOREIGN_WG_REMOVED=1
    echo "WDTT_PROGRESS|1.0|Выход через другой сервер удалён с обоих VPS."
""".trimIndent()

private suspend fun deleteWireGuardExitVps(
    current: OutboundSshTarget,
    foreignHost: String,
    foreignPort: Int,
    foreignUser: String,
    foreignCredentials: SshCredentials
): String = withContext(Dispatchers.IO) {
    require(foreignHost.isValidPublicHost()) { "укажите адрес дополнительного VPS" }
    require(foreignPort in 1..65535) { "укажите корректный SSH-порт дополнительного VPS" }
    require(foreignUser.isNotBlank()) { "укажите SSH-логин дополнительного VPS" }
    require(foreignCredentials.hasAuthentication) { "добавьте пароль или SSH-ключ дополнительного VPS" }
    var currentSession: Session? = null
    var foreignSession: Session? = null
    try {
        DeployManager.updateProgress(0.08f, "Проверяю SSH-доступ к обоим VPS...")
        currentSession = createCriticalSshSession(current)
        foreignSession = createCriticalSshSession(foreignHost, foreignUser, foreignCredentials, foreignPort)
        val currentOutput = SSHClient(currentSession, current.pass)
            .exec(rootCommand(deleteWireGuardVpsCurrentScript()), timeout = 30000L)
        markerValue(currentOutput, "WDTT_ERROR")?.let {
            throw IllegalStateException(rootScriptErrorExcerpt(currentOutput))
        }
        require(markerValue(currentOutput, "WDTT_CURRENT_WG_REMOVED") == "1") {
            "текущий VPS не подтвердил удаление WireGuard-выхода"
        }
        val foreignOutput = SSHClient(foreignSession, foreignCredentials.password)
            .exec(rootCommand(deleteWireGuardVpsForeignScript()), timeout = 30000L)
        markerValue(foreignOutput, "WDTT_ERROR")?.let {
            throw IllegalStateException(rootScriptErrorExcerpt(foreignOutput))
        }
        require(markerValue(foreignOutput, "WDTT_FOREIGN_WG_REMOVED") == "1") {
            "дополнительный VPS не подтвердил удаление WireGuard"
        }
        "Выход через другой сервер удалён: текущий VPS использует прямой выход, созданные WDTT настройки дополнительного VPS очищены."
    } finally {
        try { currentSession?.disconnect() } catch (_: Exception) {}
        try { foreignSession?.disconnect() } catch (_: Exception) {}
    }
}

private suspend fun installWireGuardExitVps(
    context: Context,
    current: OutboundSshTarget,
    foreignHost: String,
    foreignPort: Int,
    foreignUser: String,
    foreignCredentials: SshCredentials,
    wgPort: Int
): String = withContext(Dispatchers.IO) {
    require(foreignHost.isValidPublicHost()) { "укажите домен или IPv4 дополнительного VPS без схемы и порта" }
    require(foreignPort in 1..65535) { "SSH-порт дополнительного VPS должен быть от 1 до 65535" }
    require(foreignUser.isNotBlank()) { "укажите SSH-логин дополнительного VPS" }
    require(foreignCredentials.hasAuthentication) { "добавьте пароль или SSH-ключ дополнительного VPS" }
    require(wgPort in 1..65535) { "порт WireGuard должен быть от 1 до 65535" }
    var currentSession: Session? = null
    var foreignSession: Session? = null
    val transactionId = randomToken(12).lowercase()
    val foreignBackupDir = "/etc/wdtt-plus/wg-exit/transactions/$transactionId/foreign"
    val currentBackupDir = "/etc/wdtt-plus/wg-exit/transactions/$transactionId/current"
    var foreignTouched = false
    var currentTouched = false
    try {
        DeployManager.updateProgress(0.10f, "Подключаюсь к текущему серверу WDTT...")
        currentSession = createCriticalSshSession(current)
        DeployManager.updateProgress(0.18f, "Подключаюсь к другому серверу для WireGuard-выхода...")
        foreignSession = createCriticalSshSession(
            foreignHost,
            foreignUser,
            foreignCredentials,
            foreignPort
        )
        val currentSsh = SSHClient(currentSession, current.pass)
        val foreignSsh = SSHClient(foreignSession, foreignCredentials.password)
        val previousHostOutput = currentSsh.exec(
            rootCommand(
                """
                SAVED_B64="${'$'}(grep '^WG_VPS_HOST_B64=' /etc/wdtt/outbound-profile.env 2>/dev/null | tail -n 1 | sed 's/^[^=]*=//')"
                SAVED_HOST=""
                [ -z "${'$'}SAVED_B64" ] || SAVED_HOST="${'$'}(printf '%s' "${'$'}SAVED_B64" | base64 -d 2>/dev/null || true)"
                if [ -z "${'$'}SAVED_HOST" ] && [ -f /etc/wireguard/wg-wdtt-exit.conf ]; then
                  ENDPOINT="${'$'}(sed -n 's/^[[:space:]]*Endpoint[[:space:]]*=[[:space:]]*//Ip' /etc/wireguard/wg-wdtt-exit.conf | head -n 1 | tr -d ' ')"
                  SAVED_HOST="${'$'}{ENDPOINT%:*}"
                  SAVED_HOST="${'$'}{SAVED_HOST#[}"
                  SAVED_HOST="${'$'}{SAVED_HOST%]}"
                fi
                printf 'WDTT_PREVIOUS_WG_VPS_HOST=%s\n' "${'$'}SAVED_HOST"
                """.trimIndent()
            ),
            timeout = 15000L
        )
        val previousHost = markerValue(previousHostOutput, "WDTT_PREVIOUS_WG_VPS_HOST").orEmpty()
        if (previousHost.isNotBlank() && !previousHost.equals(foreignHost, ignoreCase = true)) {
            throw IllegalStateException("WDTT_ERROR=wireguard_vps_previous_server_requires_removal")
        }

        val prepareKeys = shellScript(outboundShellPrelude(), """
            echo "WDTT_PROGRESS|0.26|Готовлю WireGuard-инструменты и ключи..."
            wdtt_install_wireguard_tools || true
            command -v wg >/dev/null 2>&1 || { echo WDTT_ERROR=wireguard_tools_required; exit 2; }
            mkdir -p /etc/wdtt-plus/wg-exit
            umask 077
            [ -f /etc/wdtt-plus/wg-exit/private.key ] || wg genkey >/etc/wdtt-plus/wg-exit/private.key
            wg pubkey </etc/wdtt-plus/wg-exit/private.key >/etc/wdtt-plus/wg-exit/public.key
            printf 'WDTT_WG_PUB='; cat /etc/wdtt-plus/wg-exit/public.key; printf '\n'
        """)
        DeployManager.updateProgress(0.28f, "Готовлю WireGuard-ключи на текущем сервере...")
        val currentPub = markerValue(currentSsh.exec(rootCommand(prepareKeys), timeout = CMD_TIMEOUT), "WDTT_WG_PUB")
            ?: throw IllegalStateException("текущий сервер не отдал публичный ключ WireGuard")
        DeployManager.updateProgress(0.38f, "Готовлю WireGuard-ключи на другом сервере...")
        val foreignPub = markerValue(foreignSsh.exec(rootCommand(prepareKeys), timeout = CMD_TIMEOUT), "WDTT_WG_PUB")
            ?: throw IllegalStateException("другой сервер не отдал публичный ключ WireGuard")

        val foreignConfigScript = shellScript(
            """
            CURRENT_PUB=${shellQuote(currentPub)}
            WG_PORT=$wgPort
            BACKUP_DIR=${shellQuote(foreignBackupDir)}
            """,
            outboundShellPrelude(),
            """
            command -v systemctl >/dev/null 2>&1 || { echo WDTT_ERROR=systemd_required; exit 2; }
            command -v iptables >/dev/null 2>&1 || { echo WDTT_ERROR=iptables_required; exit 2; }
            echo "WDTT_PROGRESS|0.50|Определяю внешний интерфейс другого сервера..."
            EXT_IFACE="${'$'}(wdtt_ext_iface)"
            [ -n "${'$'}EXT_IFACE" ] || { echo WDTT_ERROR=foreign_ext_iface_not_found; exit 2; }
            echo "WDTT_PROGRESS|0.53|Сохраняю прежнее состояние другого сервера для отката..."
            rm -rf "${'$'}BACKUP_DIR"
            mkdir -p "${'$'}BACKUP_DIR"
            chmod 700 "${'$'}BACKUP_DIR"
            [ ! -f /etc/wireguard/wg-wdtt-exit.conf ] ||
              { cp -a /etc/wireguard/wg-wdtt-exit.conf "${'$'}BACKUP_DIR/wg.conf"; touch "${'$'}BACKUP_DIR/had_wg_conf"; }
            [ ! -f /etc/sysctl.d/99-wdtt-exit-forward.conf ] ||
              { cp -a /etc/sysctl.d/99-wdtt-exit-forward.conf "${'$'}BACKUP_DIR/sysctl.conf"; touch "${'$'}BACKUP_DIR/had_sysctl_conf"; }
            sysctl -n net.ipv4.ip_forward >"${'$'}BACKUP_DIR/ip_forward" 2>/dev/null || printf '0\n' >"${'$'}BACKUP_DIR/ip_forward"
            systemctl is-active --quiet wg-quick@wg-wdtt-exit 2>/dev/null && touch "${'$'}BACKUP_DIR/was_active" || true
            systemctl is-enabled --quiet wg-quick@wg-wdtt-exit 2>/dev/null && touch "${'$'}BACKUP_DIR/was_enabled" || true
            iptables -S INPUT 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' >"${'$'}BACKUP_DIR/input.rules" || true
            iptables -S FORWARD 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' >"${'$'}BACKUP_DIR/forward.rules" || true
            iptables -t nat -S POSTROUTING 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' >"${'$'}BACKUP_DIR/nat.rules" || true
            chmod 600 "${'$'}BACKUP_DIR"/*
            systemctl stop wg-quick@wg-wdtt-exit 2>/dev/null || true
            iptables -S INPUT 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
              while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
            iptables -S FORWARD 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
              while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
            iptables -t nat -S POSTROUTING 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -t nat -D /' |
              while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
            echo "WDTT_PROGRESS|0.56|Включаю пересылку трафика на другом сервере..."
            sysctl -w net.ipv4.ip_forward=1 >/dev/null ||
              { echo WDTT_ERROR=foreign_ip_forward_failed; exit 3; }
            mkdir -p /etc/sysctl.d /etc/wireguard
            printf 'net.ipv4.ip_forward=1\n' >/etc/sysctl.d/99-wdtt-exit-forward.conf
            PRIV="${'$'}(cat /etc/wdtt-plus/wg-exit/private.key)"
            echo "WDTT_PROGRESS|0.62|Записываю WireGuard-настройки другого сервера..."
            cat >/etc/wireguard/wg-wdtt-exit.conf <<EOF
            [Interface]
            Address = 10.77.77.1/30
            ListenPort = ${'$'}WG_PORT
            PrivateKey = ${'$'}PRIV
            PostUp = iptables -C INPUT -p udp --dport ${'$'}WG_PORT -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${'$'}WG_PORT -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT
            PostUp = iptables -t nat -C POSTROUTING -s 10.77.77.0/30 -o ${'$'}EXT_IFACE -m comment --comment WDTT_EXIT_FOREIGN -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.77.77.0/30 -o ${'$'}EXT_IFACE -m comment --comment WDTT_EXIT_FOREIGN -j MASQUERADE
            PostUp = iptables -I FORWARD -i wg-wdtt-exit -o ${'$'}EXT_IFACE -s 10.77.77.0/30 -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT
            PostDown = iptables -D FORWARD -i wg-wdtt-exit -o ${'$'}EXT_IFACE -s 10.77.77.0/30 -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null || true
            PostUp = iptables -I FORWARD -i ${'$'}EXT_IFACE -o wg-wdtt-exit -d 10.77.77.0/30 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT
            PostDown = iptables -D FORWARD -i ${'$'}EXT_IFACE -o wg-wdtt-exit -d 10.77.77.0/30 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null || true
            PostDown = iptables -D INPUT -p udp --dport ${'$'}WG_PORT -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null || true
            PostDown = iptables -t nat -D POSTROUTING -s 10.77.77.0/30 -o ${'$'}EXT_IFACE -m comment --comment WDTT_EXIT_FOREIGN -j MASQUERADE 2>/dev/null || true

            [Peer]
            PublicKey = ${'$'}CURRENT_PUB
            AllowedIPs = 10.77.77.2/32
            EOF
            chmod 600 /etc/wireguard/wg-wdtt-exit.conf
            echo "WDTT_PROGRESS|0.68|Запускаю WireGuard на другом сервере..."
            systemctl enable wg-quick@wg-wdtt-exit >/dev/null ||
              { echo WDTT_ERROR=foreign_wireguard_enable_failed; exit 3; }
            systemctl restart wg-quick@wg-wdtt-exit >/dev/null ||
              { echo WDTT_ERROR=foreign_wireguard_start_failed; exit 3; }
            systemctl is-active --quiet wg-quick@wg-wdtt-exit ||
              { echo WDTT_ERROR=foreign_wireguard_start_failed; exit 3; }
            iptables -C INPUT -p udp --dport "${'$'}WG_PORT" -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null &&
              iptables -C FORWARD -i wg-wdtt-exit -o ${'$'}EXT_IFACE -s 10.77.77.0/30 -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null &&
              iptables -C FORWARD -i ${'$'}EXT_IFACE -o wg-wdtt-exit -d 10.77.77.0/30 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment WDTT_EXIT_FOREIGN -j ACCEPT 2>/dev/null &&
              iptables -t nat -C POSTROUTING -s 10.77.77.0/30 -o "${'$'}EXT_IFACE" -m comment --comment WDTT_EXIT_FOREIGN -j MASQUERADE 2>/dev/null ||
              { echo WDTT_ERROR=foreign_firewall_failed; exit 3; }
            echo WDTT_FOREIGN_READY=1
            echo "WireGuard на другом сервере запущен: ${'$'}EXT_IFACE"
            """
        )
        DeployManager.updateProgress(0.48f, "Настраиваю WireGuard на другом сервере...")
        foreignTouched = true
        val foreignOutput = foreignSsh.exec(rootCommand(foreignConfigScript), timeout = CMD_TIMEOUT)
        markerValue(foreignOutput, "WDTT_ERROR")?.let { throw IllegalStateException(rootScriptErrorExcerpt(foreignOutput)) }
        require(markerValue(foreignOutput, "WDTT_FOREIGN_READY") == "1") {
            "другой сервер не подтвердил запуск WireGuard"
        }

        val currentConfigScript = shellScript(
            """
            FOREIGN_PUB=${shellQuote(foreignPub)}
            FOREIGN_HOST=${shellQuote(foreignHost)}
            WG_PORT=$wgPort
            CURRENT_BACKUP_DIR=${shellQuote(currentBackupDir)}
            """,
            outboundShellPrelude(),
            """
            echo "WDTT_PROGRESS|0.70|Сохраняю прежний режим выхода текущего сервера..."
            rm -rf "${'$'}CURRENT_BACKUP_DIR"
            mkdir -p "${'$'}CURRENT_BACKUP_DIR"
            chmod 700 "${'$'}CURRENT_BACKUP_DIR"
            touch "${'$'}CURRENT_BACKUP_DIR/created"
            [ ! -f /etc/wireguard/wg-wdtt-exit.conf ] ||
              { cp -a /etc/wireguard/wg-wdtt-exit.conf "${'$'}CURRENT_BACKUP_DIR/wg.conf"; touch "${'$'}CURRENT_BACKUP_DIR/had_wg_conf"; }
            [ ! -f /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf ] ||
              { cp -a /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf "${'$'}CURRENT_BACKUP_DIR/saved_wg.conf"; touch "${'$'}CURRENT_BACKUP_DIR/had_saved_wg_conf"; }
            [ ! -f /etc/wdtt/outbound.json ] ||
              { cp -a /etc/wdtt/outbound.json "${'$'}CURRENT_BACKUP_DIR/outbound.json"; touch "${'$'}CURRENT_BACKUP_DIR/had_outbound_json"; }
            [ ! -f "${'$'}WDTT_WG_OWNER_FILE" ] ||
              { cp -a "${'$'}WDTT_WG_OWNER_FILE" "${'$'}CURRENT_BACKUP_DIR/owner"; touch "${'$'}CURRENT_BACKUP_DIR/had_owner"; }
            [ ! -f "${'$'}WDTT_WG_CONFIG_OWNER_FILE" ] ||
              { cp -a "${'$'}WDTT_WG_CONFIG_OWNER_FILE" "${'$'}CURRENT_BACKUP_DIR/config-owner"; touch "${'$'}CURRENT_BACKUP_DIR/had_config_owner"; }
            systemctl is-active --quiet wdtt-wg-exit.service 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/wg_was_active" || true
            systemctl is-enabled --quiet wdtt-wg-exit.service 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/wg_was_enabled" || true
            systemctl is-active --quiet wdtt-redsocks.service 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/proxy_was_active" || true
            systemctl is-enabled --quiet wdtt-redsocks.service 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/proxy_was_enabled" || true
            systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/tun_was_active" || true
            systemctl is-enabled --quiet wdtt-tun-exit.service 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/tun_was_enabled" || true
            systemctl is-active --quiet wdtt-warp-watchdog.timer 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/warp_timer_was_active" || true
            systemctl is-enabled --quiet wdtt-warp-watchdog.timer 2>/dev/null && touch "${'$'}CURRENT_BACKUP_DIR/warp_timer_was_enabled" || true
            chmod 600 "${'$'}CURRENT_BACKUP_DIR"/*
            echo "WDTT_PROGRESS|0.72|Записываю WireGuard-настройки текущего сервера..."
            mkdir -p /etc/wireguard /etc/wdtt-plus/wg-exit
            PRIV="${'$'}(cat /etc/wdtt-plus/wg-exit/private.key)"
            cat >/etc/wireguard/wg-wdtt-exit.conf <<EOF
            [Interface]
            Address = 10.77.77.2/30
            PrivateKey = ${'$'}PRIV
            Table = off

            [Peer]
            PublicKey = ${'$'}FOREIGN_PUB
            Endpoint = ${'$'}FOREIGN_HOST:${'$'}WG_PORT
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
            EOF
            chmod 600 /etc/wireguard/wg-wdtt-exit.conf
            install -m 600 /etc/wireguard/wg-wdtt-exit.conf /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
            """,
            wireGuardPolicyScript("wireguard_vps", "другой сервер ${foreignHost}:${wgPort}")
        )
        DeployManager.updateProgress(0.72f, "Применяю WireGuard-выход на текущем сервере...")
        currentTouched = true
        val output = currentSsh.exec(rootCommand(currentConfigScript), timeout = CMD_TIMEOUT)
        if (output.contains("WDTT_ERROR=") || output.lineSequence().any { it.startsWith("error:") }) throw IllegalStateException(rootScriptErrorExcerpt(output))
        currentSsh.exec(rootCommand("rm -rf ${shellQuote("/etc/wdtt-plus/wg-exit/transactions/$transactionId")}"), timeout = 15000L)
        foreignSsh.exec(rootCommand("rm -rf ${shellQuote("/etc/wdtt-plus/wg-exit/transactions/$transactionId")}"), timeout = 15000L)
        currentTouched = false
        foreignTouched = false
        DeployManager.updateProgress(1f, "WireGuard-выход через другой сервер включён.")
        output.trim().ifBlank { "Выход через WireGuard включён для WDTT-пользователей." }
    } catch (e: Exception) {
        var rollbackIncomplete = false
        if (foreignTouched) {
            val rollbackScript = shellScript(
                "BACKUP_DIR=${shellQuote(foreignBackupDir)}",
                """
                [ -d "${'$'}BACKUP_DIR" ] || { echo WDTT_ROLLBACK_OK=1; exit 0; }
                systemctl disable --now wg-quick@wg-wdtt-exit 2>/dev/null || true
                if command -v iptables >/dev/null 2>&1; then
                  iptables -S INPUT 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
                    while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
                  iptables -S FORWARD 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
                    while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
                  iptables -t nat -S POSTROUTING 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -t nat -D /' |
                    while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
                fi
                if [ -f "${'$'}BACKUP_DIR/had_wg_conf" ]; then
                  install -m 600 "${'$'}BACKUP_DIR/wg.conf" /etc/wireguard/wg-wdtt-exit.conf
                else
                  rm -f /etc/wireguard/wg-wdtt-exit.conf
                fi
                if [ -f "${'$'}BACKUP_DIR/had_sysctl_conf" ]; then
                  install -m 644 "${'$'}BACKUP_DIR/sysctl.conf" /etc/sysctl.d/99-wdtt-exit-forward.conf
                else
                  rm -f /etc/sysctl.d/99-wdtt-exit-forward.conf
                fi
                PREVIOUS_FORWARD="${'$'}(cat "${'$'}BACKUP_DIR/ip_forward" 2>/dev/null || echo 0)"
                sysctl -w "net.ipv4.ip_forward=${'$'}PREVIOUS_FORWARD" >/dev/null 2>&1 || true
                if [ -f "${'$'}BACKUP_DIR/was_enabled" ]; then
                  systemctl enable wg-quick@wg-wdtt-exit >/dev/null 2>&1 || true
                else
                  systemctl disable wg-quick@wg-wdtt-exit >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/was_active" ]; then
                  systemctl start wg-quick@wg-wdtt-exit >/dev/null 2>&1 || true
                else
                  systemctl stop wg-quick@wg-wdtt-exit >/dev/null 2>&1 || true
                fi
                if command -v iptables >/dev/null 2>&1; then
                  iptables -S INPUT 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
                    while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
                  iptables -S FORWARD 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -D /' |
                    while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
                  iptables -t nat -S POSTROUTING 2>/dev/null | grep 'WDTT_EXIT_FOREIGN' | sed 's/^-A /iptables -t nat -D /' |
                    while read -r cmd; do ${'$'}cmd 2>/dev/null || true; done
                  while IFS= read -r rule; do [ -z "${'$'}rule" ] || iptables ${'$'}rule 2>/dev/null || true; done <"${'$'}BACKUP_DIR/input.rules"
                  while IFS= read -r rule; do
                    [ -z "${'$'}rule" ] && continue
                    # Owned ACCEPT rules must precede a pre-existing terminal DROP.
                    rule="${'$'}(printf '%s\n' "${'$'}rule" | sed 's/^-A /-I /')"
                    iptables ${'$'}rule 2>/dev/null || exit 3
                  done <"${'$'}BACKUP_DIR/forward.rules"
                  while IFS= read -r rule; do [ -z "${'$'}rule" ] || iptables -t nat ${'$'}rule 2>/dev/null || true; done <"${'$'}BACKUP_DIR/nat.rules"
                fi
                if [ -f "${'$'}BACKUP_DIR/was_active" ] &&
                   ! systemctl is-active --quiet wg-quick@wg-wdtt-exit 2>/dev/null; then
                  exit 3
                fi
                rm -rf "${'$'}BACKUP_DIR"
                echo WDTT_ROLLBACK_OK=1
                """
            )
            suspend fun rollbackForeign(session: Session): Boolean = runCatching {
                val rollbackOutput = SSHClient(session, foreignCredentials.password)
                    .exec(rootCommand(rollbackScript), timeout = 45000L)
                markerValue(rollbackOutput, "WDTT_ROLLBACK_OK") == "1"
            }.getOrDefault(false)
            val rolledBack = foreignSession?.let { rollbackForeign(it) } ?: false
            if (!rolledBack) {
                try { foreignSession?.disconnect() } catch (_: Exception) {}
                foreignSession = runCatching {
                    createCriticalSshSession(foreignHost, foreignUser, foreignCredentials, foreignPort)
                }.getOrNull()
                if (foreignSession?.let { rollbackForeign(it) } != true) rollbackIncomplete = true
            }
        }
        if (currentTouched) {
            val rollbackCurrentScript = shellScript(
                "BACKUP_DIR=${shellQuote(currentBackupDir)}",
                outboundShellPrelude(),
                """
                [ -d "${'$'}BACKUP_DIR" ] || { echo WDTT_ROLLBACK_OK=1; exit 0; }
                wdtt_clear_external_out
                if [ -f "${'$'}BACKUP_DIR/had_wg_conf" ]; then
                  install -m 600 "${'$'}BACKUP_DIR/wg.conf" /etc/wireguard/wg-wdtt-exit.conf
                else
                  rm -f /etc/wireguard/wg-wdtt-exit.conf
                fi
                if [ -f "${'$'}BACKUP_DIR/had_saved_wg_conf" ]; then
                  install -m 600 "${'$'}BACKUP_DIR/saved_wg.conf" /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
                else
                  rm -f /etc/wdtt-plus/wg-exit/wg-wdtt-exit.conf
                fi
                if [ -f "${'$'}BACKUP_DIR/had_outbound_json" ]; then
                  install -m 600 "${'$'}BACKUP_DIR/outbound.json" /etc/wdtt/outbound.json
                else
                  rm -f /etc/wdtt/outbound.json
                fi
                if [ -f "${'$'}BACKUP_DIR/had_owner" ]; then
                  install -m 600 "${'$'}BACKUP_DIR/owner" "${'$'}WDTT_WG_OWNER_FILE"
                else
                  rm -f "${'$'}WDTT_WG_OWNER_FILE"
                fi
                if [ -f "${'$'}BACKUP_DIR/had_config_owner" ]; then
                  install -m 600 "${'$'}BACKUP_DIR/config-owner" "${'$'}WDTT_WG_CONFIG_OWNER_FILE"
                else
                  rm -f "${'$'}WDTT_WG_CONFIG_OWNER_FILE"
                fi
                if [ -f "${'$'}BACKUP_DIR/wg_was_enabled" ]; then
                  systemctl enable wdtt-wg-exit.service >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/proxy_was_enabled" ]; then
                  systemctl enable wdtt-redsocks.service >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/tun_was_enabled" ]; then
                  systemctl enable wdtt-tun-exit.service >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/wg_was_active" ]; then
                  systemctl restart wdtt-wg-exit.service >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/proxy_was_active" ]; then
                  systemctl restart wdtt-redsocks.service >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/tun_was_active" ]; then
                  systemctl restart wdtt-tun-exit.service >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/warp_timer_was_enabled" ]; then
                  systemctl enable wdtt-warp-watchdog.timer >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/warp_timer_was_active" ]; then
                  systemctl start wdtt-warp-watchdog.timer >/dev/null 2>&1 || true
                fi
                if [ -f "${'$'}BACKUP_DIR/wg_was_active" ] &&
                   ! systemctl is-active --quiet wdtt-wg-exit.service 2>/dev/null; then
                  exit 3
                fi
                if [ -f "${'$'}BACKUP_DIR/proxy_was_active" ] &&
                   ! systemctl is-active --quiet wdtt-redsocks.service 2>/dev/null; then
                  exit 3
                fi
                if [ -f "${'$'}BACKUP_DIR/tun_was_active" ] &&
                   { ! systemctl is-active --quiet wdtt-tun-exit.service 2>/dev/null ||
                     ! /usr/local/lib/wdtt/tun-exit-route status >/dev/null 2>&1; }; then
                  exit 3
                fi
                if [ -f "${'$'}BACKUP_DIR/warp_timer_was_active" ] &&
                   ! systemctl is-active --quiet wdtt-warp-watchdog.timer 2>/dev/null; then
                  exit 3
                fi
                rm -rf ${shellQuote("/etc/wdtt-plus/wg-exit/transactions/$transactionId")}
                echo WDTT_ROLLBACK_OK=1
                """
            )
            suspend fun rollbackCurrent(session: Session): Boolean = runCatching {
                val rollbackOutput = SSHClient(session, current.pass)
                    .exec(rootCommand(rollbackCurrentScript), timeout = 45000L)
                markerValue(rollbackOutput, "WDTT_ROLLBACK_OK") == "1"
            }.getOrDefault(false)
            var currentRollbackOk = currentSession?.let { rollbackCurrent(it) } ?: false
            if (!currentRollbackOk) {
                try { currentSession?.disconnect() } catch (_: Exception) {}
                currentSession = runCatching {
                    createCriticalSshSession(current)
                }.getOrNull()
                currentRollbackOk = currentSession?.let { rollbackCurrent(it) } ?: false
            }
            if (!currentRollbackOk) rollbackIncomplete = true
        }
        if (rollbackIncomplete) {
            throw IllegalStateException(
                "${e.message.orEmpty()}\nWDTT_ERROR=outbound_rollback_incomplete",
                e
            )
        }
        throw e
    } finally {
        try { currentSession?.disconnect() } catch (_: Exception) {}
        try { foreignSession?.disconnect() } catch (_: Exception) {}
    }
}

private val wireGuardOutboundModes = setOf("wireguard_vps", "warp_free", "imported_wg")

private fun OutboundServerSnapshot.outboundModeMismatchWarning(): String? = when {
    routeConflictMessage != null -> routeConflictMessage
    mode == "direct" && (externalProxyRoutingPresent || wireGuardRoutingPresent || tunRoutingPresent) ->
        "записан прямой выход, но на сервере остались внешние службы или правила маршрутизации. Нажмите «Вернуть прямой выход» для полной очистки."
    mode == "external_proxy" && externalProxyServiceActive && !externalProxyRouteActive ->
        "служба внешнего TCP-прокси запущена, но правило перенаправления трафика WDTT отсутствует."
    mode == "external_proxy" && !externalProxyServiceActive && externalProxyRouteActive ->
        "правило внешнего TCP-прокси осталось, но его служба не запущена; TCP-трафик клиентов может не работать."
    mode == "external_proxy" && externalProxyActive && !externalProxyServiceEnabled ->
        "внешний TCP-прокси работает, но его автозапуск выключен; после перезагрузки сервера режим пропадёт."
    mode == "external_proxy" && !externalProxyActive ->
        "режим записан как внешний TCP-прокси, но он запущен не полностью."
    mode in wireGuardOutboundModes && wireGuardActive && !wireGuardServiceActive ->
        "WireGuard-маршрут работает, но постоянная служба не активна; после перезагрузки сервера режим может пропасть."
    mode in wireGuardOutboundModes && wireGuardActive && !wireGuardServiceEnabled ->
        "WireGuard-маршрут работает, но автозапуск службы выключен; после перезагрузки сервера режим пропадёт."
    mode in wireGuardOutboundModes && !wireGuardActive ->
        "режим записан как ${modeLabel}, но не все компоненты WireGuard-маршрута запущены."
    mode == "tun_interface" && tunHealthy.not() ->
        if (tunFailClosedActive) {
            "внешний TUN недоступен; аварийная блокировка защищает трафик WDTT от прямого выхода."
        } else {
            "режим записан как существующий TUN-интерфейс, но интерфейс, служба или управляемые маршруты запущены не полностью."
        }
    externalProxyRoutingPresent && mode != "external_proxy" ->
        "на сервере остались компоненты внешнего TCP-прокси, хотя записан режим «${modeLabel}»."
    wireGuardRoutingPresent && mode !in wireGuardOutboundModes ->
        "на сервере остались компоненты WireGuard-выхода, хотя записан режим «${modeLabel}»."
    tunRoutingPresent && mode != "tun_interface" ->
        "на сервере остались компоненты выхода через TUN-интерфейс, хотя записан режим «${modeLabel}»."
    else -> null
}

/** What WdttPlusExitActivity runs; each action and its follow-up save are paired as on WDTT Plus's screen. */
internal class WdttPlusExit(private val context: Context, server: DeployServer) {
    private val target = OutboundSshTarget(WdttPlusAdmin(server), server)

    suspend fun snapshot(): OutboundServerSnapshot = readOutboundServerSnapshot(context, target)
    fun summary(snapshot: OutboundServerSnapshot): String = outboundRestoreSummary(snapshot)
    fun warning(snapshot: OutboundServerSnapshot): String? = snapshot.outboundModeMismatchWarning()
    fun forms(s: OutboundServerSnapshot) = OutboundProfileForms(
        s.localProxyPort, s.localProxyLogin, s.localProxyPassword, s.externalProxyKindName, s.externalProxyHost, s.externalProxyPort,
        s.externalProxyLogin, s.externalProxyPassword, s.wireGuardExitHost, s.wireGuardExitSshPort, s.wireGuardExitUser, "",
        s.wireGuardExitPort, s.wireGuardExitDns, s.importedWireGuardConfig, s.tunInterface
    )
    private suspend fun saved(result: String, forms: OutboundProfileForms, message: String) =
        "$result\n" + saveOutboundProfileMessage(context, target, forms, message)

    suspend fun status() = readOutboundStatus(target)
    suspend fun diagnostics() = readOutboundDiagnostics(target)
    suspend fun direct() = disableOutboundExit(target)

    suspend fun installLocal(forms: OutboundProfileForms, port: Int, login: String, password: String) =
        saved(installLocalProxy(context, target, port, login, password), forms, "Поля прокси сохранены на сервере для восстановления.")
    suspend fun checkLocal(port: Int, login: String, password: String) = checkLocalProxy(context, target, port, login, password)
    suspend fun stopLocal() = stopLocalProxy(target)
    suspend fun removeLocal() = removeLocalProxy(target)

    suspend fun checkExternal(kind: ProxyKind, host: String, port: Int, login: String, password: String) =
        checkExternalProxy(context, target, kind, host, port, login, password)
    suspend fun enableExternal(forms: OutboundProfileForms, kind: ProxyKind, host: String, port: Int, login: String, password: String) =
        saved(enableExternalProxy(context, target, kind, host, port, login, password), forms, "Поля внешнего прокси сохранены на сервере для восстановления.")
    suspend fun deleteExternal() = deleteExternalProxy(target)

    suspend fun tunCandidates() = discoverTunInterfaces(target)
    suspend fun enableTun(forms: OutboundProfileForms, name: String) =
        saved(enableTunInterface(target, name), forms, "Имя TUN-интерфейса сохранено на сервере для восстановления.")
    suspend fun checkTun(): String = snapshot().let {
        if (it.mode == "tun_interface" && it.tunHealthy) checkTunInterfaceExit(target, it.tunInterface) else summary(it)
    }
    suspend fun deleteTun() = deleteTunInterfaceExit(target)

    suspend fun installVps(forms: OutboundProfileForms, host: String, sshPort: Int, user: String, credentials: SshCredentials, wgPort: Int) =
        saved(installWireGuardExitVps(context, target, host, sshPort, user, credentials, wgPort), forms,
            "Поля выхода через другой сервер сохранены на сервере для восстановления.")
    suspend fun deleteVps(host: String, sshPort: Int, user: String, credentials: SshCredentials) =
        deleteWireGuardExitVps(target, host, sshPort, user, credentials)

    suspend fun installWarp(mtu: Int) = installOrRepairFreeWarp(context, target, mtu)
    suspend fun restartWarp() = checkFreeWarp(target, restartOnFailure = true)
    suspend fun updateWgcf() = updateWgcfTool(context, target)
    suspend fun resetWarp() = resetFreeWarpRegistration(target)
    suspend fun deleteWarp() = deleteFreeWarp(target)

    fun validateWireGuard(config: String) = validateWireGuardConfigText(config)
    suspend fun enableWireGuardFile(forms: OutboundProfileForms, config: String) =
        saved(enableImportedWireGuardExit(context, target, config), forms, "VPN/WireGuard-файл сохранён на сервере для восстановления.")
    suspend fun deleteWireGuardFile() = deleteImportedWireGuardExit(target)

    /** WDTT Plus's check: the WireGuard route when that mode is active, otherwise what the server reports. */
    suspend fun checkWireGuard(mode: String): String = snapshot().let {
        if (it.mode == mode && it.wireGuardActive) {
            if (mode == "warp_free") checkFreeWarp(target, restartOnFailure = false) else checkWireGuardExit(target, mode)
        } else summary(it)
    }
}
