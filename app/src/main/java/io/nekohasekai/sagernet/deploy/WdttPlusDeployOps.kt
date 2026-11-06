package app.rawline.mobile.deploy

import org.json.JSONObject

/*
 * The remote steps of WDTT Plus's performDeploy/performUninstall (ui/DeployTab.kt) over the deploy console's
 * SSH session. Deviations are marked "Rawline:".
 */

internal enum class ServerUpdateRollbackAction { RestorePrevious, KeepCurrentAndDeleteBackup, CancelPreparation, ArchiveLegacyPreparation }

/**
 * One SSH session's WDTT Plus deploy operations. [run] executes a command and returns its merged output
 * with an "error:" line when the remote command fails, like WDTT Plus's SSHClient.exec.
 */
internal class WdttPlusDeployOps(private val run: suspend (String, Long) -> String) {
    suspend fun exec(command: String, timeout: Long): String = run(rootCommand(command), timeout)

    /** Critical mutations must not mistake partial output or a nonzero remote exit for success. */
    suspend fun execChecked(command: String, timeout: Long): String {
        val output = exec(command, timeout)
        if (Regex("^error:", RegexOption.MULTILINE).containsMatchIn(output)) {
            throw IllegalStateException("Серверная операция не завершена:\n" + serverUpdateFailureDetail(output))
        }
        return output
    }

    suspend fun verifyRootAccess() = validateRootAccessOutput(exec("printf 'WDTT_ROOT_ACCESS=ok\\n'", 20_000L))

    suspend fun ownership(): DeploymentOwnership =
        deploymentOwnershipFromProbe(exec(standaloneInstallerOwnershipProbeScript(), 15_000L))

    suspend fun assertAndroidDeployMayManageServer(operation: String) {
        when (ownership()) {
            DeploymentOwnership.AndroidDeploy,
            DeploymentOwnership.PreservedAndroidData -> Unit
            DeploymentOwnership.StandaloneInstaller -> throw IllegalStateException(
                "сервер управляется standalone-инсталлером. Для $operation используйте server-installer/install.sh на VPS; Android-деплой не будет менять эту установку."
            )
            DeploymentOwnership.LegacyAndroidDeploy -> throw IllegalStateException(
                "старая Android-установка без метки не допускает $operation. Сначала выполните обновление с сохранением."
            )
            DeploymentOwnership.NoInstall,
            DeploymentOwnership.IncompleteAndroidDeploy,
            DeploymentOwnership.UnknownExisting -> throw IllegalStateException(
                "не удалось безопасно подтвердить, что установка принадлежит Android-деплою. $operation остановлено."
            )
        }
    }

    /** WDTT Plus's markPreservedAndroidData with its set -e checks made effective ([DeployProtocol.WDTT_PLUS_MARK_PRESERVED]). */
    suspend fun markPreservedAndroidData() {
        require(DeployProtocol.wdttPlusMarked(exec(DeployProtocol.WDTT_PLUS_MARK_PRESERVED, 15_000L))) {
            "не удалось безопасно отметить сохранённые данные Android-деплоя"
        }
    }

    /**
     * qThrone 1.8.1 and older removed WDTT Plus with deploy.sh uninstall, which keeps /etc/wdtt without the
     * preserved-data marker, so WDTT Plus's probe sees an unknown install. Only that exact shape is accepted.
     */
    suspend fun hasUnmarkedRawlineData(): Boolean = markerValue(exec(UNMARKED_QTHRONE_DATA_PROBE, 15_000L), "WDTT_UNMARKED_QTHRONE_DATA") == "1"

    /** The data step of WDTT Plus's preserving uninstall, then its marker: the probe then sees preserved Android data. */
    suspend fun adoptUnmarkedRawlineData() {
        execChecked("{ [ -d /etc/wdtt ] && [ ! -L /etc/wdtt ]; } || { echo 'error: /etc/wdtt изменился'; exit 1; }; " +
            "chmod 700 /etc/wdtt && chmod 600 /etc/wdtt/passwords.json /etc/wdtt/wg-keys.dat", 15_000L)
        markPreservedAndroidData()
    }

    suspend fun readRemotePasswordsJson(): String? {
        val output = exec(READ_PASSWORDS_JSON, 20_000L)
        markerValue(output, "WDTT_ERROR")?.let { throw IllegalStateException("сервер отклонил passwords.json: $it") }
        val dbB64 = markerValue(output, "WDTT_DB_B64") ?: return null
        return String(android.util.Base64.decode(dbB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }

    suspend fun prepareServerUpdateRollback(attemptId: String) {
        val output = execChecked(prepareServerUpdateRollbackScript(attemptId = attemptId), SERVER_UPDATE_PREPARE_TIMEOUT_MS)
        markerValue(output, "WDTT_UPDATE_BACKUP")?.takeIf { it != "ready" }?.let { state ->
            throw IllegalStateException(
                if (state == "stale") {
                    "найдена незавершённая страховочная копия прошлого обновления; " +
                        "новая установка заблокирована до проверки или отката"
                } else {
                    "страховочная копия отклонена: $state"
                }
            )
        }
        require(Regex("^WDTT_UPDATE_BACKUP=ready$", RegexOption.MULTILINE).containsMatchIn(output)) {
            "не удалось подготовить страховочную копию обновления"
        }
    }

    suspend fun inspectServerUpdateRollback(): ServerUpdateRollbackState =
        parseServerUpdateRollbackState(exec(serverUpdateRollbackProbeScript(), 15_000L))

    suspend fun cleanupServerUpdateRollback(attemptId: String) {
        val state = inspectServerUpdateRollback()
        require(state is ServerUpdateRollbackState.PreparedValid || state is ServerUpdateRollbackState.Committed) {
            "страховочная копия изменилась или повреждена; автоматическое удаление запрещено"
        }
        val output = execChecked(cleanupServerUpdateBackupScript(attemptId = attemptId), 30_000L)
        check(markerValue(output, "WDTT_UPDATE_CLEANUP") == "ok") { "Удаление страховочной копии не подтверждено" }
    }

    suspend fun rollbackServerUpdate(attemptId: String? = null) {
        require(inspectServerUpdateRollback() is ServerUpdateRollbackState.PreparedValid) {
            "страховочная копия не прошла повторную проверку; восстановление остановлено"
        }
        val output = execChecked(rollbackServerUpdateScript(attemptId = attemptId), SERVER_UPDATE_PREPARE_TIMEOUT_MS)
        require(Regex("^WDTT_ROLLBACK=ok$", RegexOption.MULTILINE).containsMatchIn(output)) {
            "сервер не подтвердил откат обновления"
        }
    }

    /** One recovery pass, never a retry of install/copy; a stale attempt cannot roll back another device's update. */
    suspend fun recoverServerUpdateAfterFailure(attemptId: String) {
        val own = execChecked(attemptOwnedScript(attemptId), 15_000L)
        if (markerValue(own, "WDTT_UPDATE_ATTEMPT_OWNED") != "1") return
        when (inspectServerUpdateRollback()) {
            is ServerUpdateRollbackState.PreparedValid -> rollbackServerUpdate(attemptId)
            ServerUpdateRollbackState.PreparationIncomplete ->
                execChecked(cancelServerUpdatePreparationScript(attemptId = attemptId), SERVER_UPDATE_PREPARE_TIMEOUT_MS)
            ServerUpdateRollbackState.Committed, ServerUpdateRollbackState.None -> Unit
            else -> error("Страховочная копия не прошла проверку; автоматический откат запрещён")
        }
    }

    /** WDTT Plus's resolveServerUpdateRollback: the user's choice for a copy left by an earlier attempt. */
    suspend fun resolveServerUpdateRollback(action: ServerUpdateRollbackAction, mainPassword: String): String {
        when (action) {
            ServerUpdateRollbackAction.RestorePrevious -> rollbackServerUpdate()
            ServerUpdateRollbackAction.KeepCurrentAndDeleteBackup -> {
                val state = inspectServerUpdateRollback()
                require(state is ServerUpdateRollbackState.PreparedValid || state is ServerUpdateRollbackState.Committed) {
                    "страховочная копия не прошла повторную проверку; удаление запрещено"
                }
                require(markerValue(execChecked(cleanupServerUpdateBackupScript(), 30_000L), "WDTT_UPDATE_CLEANUP") == "ok") {
                    "текущее состояние WDTT не прошло проверку; страховочная копия сохранена"
                }
            }
            ServerUpdateRollbackAction.CancelPreparation -> {
                check(inspectServerUpdateRollback() is ServerUpdateRollbackState.PreparationIncomplete)
                val output = execChecked(cancelServerUpdatePreparationScript(), SERVER_UPDATE_PREPARE_TIMEOUT_MS)
                check(markerValue(output, "WDTT_UPDATE_PREPARATION") == "cancelled")
            }
            ServerUpdateRollbackAction.ArchiveLegacyPreparation -> {
                val state = inspectServerUpdateRollback()
                check(state is ServerUpdateRollbackState.PreparedCorrupted && state.diagnostic == "missing or unsafe state")
                check(ownership() in setOf(DeploymentOwnership.AndroidDeploy, DeploymentOwnership.LegacyAndroidDeploy))
                val current = readRemotePasswordsJson() ?: error("Рабочая база сервера не найдена")
                validatePasswordsDbForPreserving(JSONObject(current), mainPassword)
                val output = execChecked(archiveLegacyUpdatePreparationScript(), 30_000L)
                check(markerValue(output, "WDTT_UPDATE_PREPARATION") == "archived")
            }
        }
        check(inspectServerUpdateRollback() is ServerUpdateRollbackState.None) {
            "операция завершилась, но каталог страховочной копии всё ещё найден"
        }
        return when (action) {
            ServerUpdateRollbackAction.RestorePrevious ->
                "Прежнее состояние WDTT Plus восстановлено и проверено. Страховочная копия удалена."
            ServerUpdateRollbackAction.KeepCurrentAndDeleteBackup ->
                "Текущее состояние WDTT Plus проверено. Удалена только страховочная копия."
            ServerUpdateRollbackAction.CancelPreparation ->
                "Подготовка отменена. Состояние службы проверено; незавершённая копия сохранена отдельно. Можно повторить обновление."
            ServerUpdateRollbackAction.ArchiveLegacyPreparation ->
                "Рабочая установка проверена. Незавершённая копия сохранена отдельно; можно повторить обновление."
        }
    }
}

private const val READ_PASSWORDS_JSON = "path=/etc/wdtt/passwords.json; " +
    "if [ -e \"\$path\" ]; then " +
    "[ -f \"\$path\" ] && [ ! -L \"\$path\" ] || { echo WDTT_ERROR=unsafe_passwords_json; exit 2; }; " +
    "size=\$(stat -c %s \"\$path\" 2>/dev/null || echo 0); " +
    "[ \"\$size\" -gt 0 ] && [ \"\$size\" -le 5000000 ] || { echo WDTT_ERROR=bad_passwords_json_size; exit 2; }; " +
    "printf 'WDTT_DB_B64='; base64 \"\$path\" | tr -d '\\n'; printf '\\n'; fi"

internal fun unmarkedQthroneDataProbe(pathPrefix: String = ""): String {
    val root = DeployProtocol.quote(pathPrefix.trimEnd('/'))
    return "r=$root; c=\"\$r/etc/wdtt\"; " +
        "if [ ! -e \"\$r/etc/systemd/system/wdtt.service\" ] && [ ! -L \"\$r/etc/systemd/system/wdtt.service\" ] && " +
        "[ ! -e \"\$r/usr/local/bin/wdtt-server\" ] && [ ! -L \"\$r/usr/local/bin/wdtt-server\" ] && " +
        "[ ! -e \"\$r/var/lib/wdtt-server-installer/ownership\" ] && [ ! -e \"\$c/.android-deploy-preserved\" ] && " +
        "[ -d \"\$c\" ] && [ ! -L \"\$c\" ] && [ -f \"\$c/passwords.json\" ] && [ ! -L \"\$c/passwords.json\" ] && [ -s \"\$c/passwords.json\" ] && " +
        "[ -f \"\$c/wg-keys.dat\" ] && [ ! -L \"\$c/wg-keys.dat\" ] && [ -s \"\$c/wg-keys.dat\" ]; " +
        "then echo WDTT_UNMARKED_QTHRONE_DATA=1; else echo WDTT_UNMARKED_QTHRONE_DATA=0; fi"
}

private val UNMARKED_QTHRONE_DATA_PROBE = unmarkedQthroneDataProbe()

internal fun attemptOwnedScript(attemptId: String, paths: ServerUpdatePaths = ServerUpdatePaths()): String =
    serverUpdateShellContext(paths) + "\n" + """
    if wdtt_regular "${'$'}BACKUP/attempt" && [ "${'$'}(cat "${'$'}BACKUP/attempt")" = ${DeployProtocol.quote(attemptId)} ]; then
      echo WDTT_UPDATE_ATTEMPT_OWNED=1
    fi
    """.trimIndent()

/** WDTT Plus's isUnsafeLegacyServerAsset: wg0 or /etc/wireguard without the /etc/wdtt and wdtt0 layout. */
internal fun unsafeLegacyWdttPlusServer(bytes: ByteArray): Boolean {
    val text = String(bytes, Charsets.ISO_8859_1)
    val hasCurrentLayout = "/etc/wdtt" in text && "wdtt0" in text
    val hasLegacyMarkers = "/etc/wireguard" in text || "wg0" in text
    return hasLegacyMarkers && !hasCurrentLayout
}

/** WDTT Plus's post-install probe: binary, config, service, admin socket, server hash and version. */
internal const val WDTT_PLUS_VERIFY_INSTALL =
    "sleep 2; printf 'BINARY=%s\\n' \"\$([ -x /usr/local/bin/wdtt-server ] && echo 1 || echo 0)\"; " +
        "printf 'CONFIG=%s\\n' \"\$([ -d /etc/wdtt ] && echo 1 || echo 0)\"; " +
        "printf 'SERVICE=%s\\n' \"\$(systemctl is-active wdtt 2>/dev/null || true)\"; " +
        "printf 'ADMIN_SOCKET=%s\\n' \"\$([ -S /run/wdtt/admin.sock ] && echo 1 || echo 0)\"; " +
        "printf 'SERVER_SHA256=%s\\n' \"\$(sha256sum /usr/local/bin/wdtt-server 2>/dev/null | awk '{print \$1}')\"; " +
        "printf 'SERVER_VERSION=%s\\n' \"\$(/usr/local/bin/wdtt-server --version 2>/dev/null | head -n 1)\""

/** What WDTT Plus's verification found missing, empty when the install is confirmed. */
internal fun wdttPlusInstallMissing(verifyOutput: String, expectedServerSha256: String): String {
    fun has(pattern: String) = Regex(pattern, RegexOption.MULTILINE).containsMatchIn(verifyOutput)
    val installedSha256 = Regex("^SERVER_SHA256=([0-9a-fA-F]{64})$", RegexOption.MULTILINE)
        .find(verifyOutput)?.groupValues?.getOrNull(1)?.lowercase()
    val version = Regex("^SERVER_VERSION=([0-9][0-9A-Za-z._-]*)$", RegexOption.MULTILINE).find(verifyOutput)?.groupValues?.getOrNull(1).orEmpty()
    return buildList {
        if (!has("^BINARY=1$")) add("бинарник")
        if (!has("^CONFIG=1$")) add("конфиг /etc/wdtt")
        if (!has("^SERVICE=active$")) add("служба wdtt")
        if (!has("^ADMIN_SOCKET=1$")) add("admin-сокет новой версии")
        if (installedSha256 != expectedServerSha256) add("актуальная версия бинарника")
        if (version.isBlank()) add("версия серверной части")
    }.joinToString(", ")
}

/** The service, binary, firewall, data and sysctl steps of WDTT Plus's performUninstall. */
internal fun wdttPlusUninstallSteps(mode: ServerUninstallMode, sshPort: Int, dtlsPort: Int, wgPort: Int): List<Pair<String, String>> = listOf(
    "Остановка сервиса..." to (
        "systemctl unmask wdtt 2>/dev/null || true; " +
            "systemctl stop wdtt 2>/dev/null || true; " +
            "systemctl disable wdtt 2>/dev/null || true; " +
            "rm -f /etc/systemd/system/wdtt.service; " +
            "systemctl daemon-reload 2>/dev/null || true"),
    "Удаление бинарника..." to "pkill -x wdtt-server 2>/dev/null || true; rm -f /usr/local/bin/wdtt-server",
    "Очистка firewall..." to (
        "if command -v iptables >/dev/null 2>&1; then " +
            "for i in 1 2 3 4 5; do " +
            "for iface in \$(ls /sys/class/net 2>/dev/null || true); do " +
            "iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o \"\$iface\" -m comment --comment WDTT_MANAGED -j MASQUERADE 2>/dev/null || true; " +
            "done; " +
            "iptables -t mangle -D FORWARD -s 10.66.66.0/24 -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment WDTT_MANAGED -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true; " +
            "iptables -t mangle -D FORWARD -d 10.66.66.0/24 -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment WDTT_MANAGED -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true; " +
            "iptables -D INPUT -p udp --dport $dtlsPort -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D INPUT -p udp --dport $wgPort -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D INPUT -p udp --dport 56000 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D INPUT -p udp --dport 56001 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D INPUT -p tcp --dport $sshPort -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D INPUT -p tcp --dport 22 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D FORWARD -i wdtt0 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "iptables -D FORWARD -o wdtt0 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
            "done; " +
            "while rule_no=\$(iptables -L INPUT --line-numbers -n 2>/dev/null | awk '/WDTT_MANAGED/ {print \$1; exit}'); [ -n \"\$rule_no\" ]; do iptables -D INPUT \"\$rule_no\" 2>/dev/null || break; done; " +
            "while rule_no=\$(iptables -L FORWARD --line-numbers -n 2>/dev/null | awk '/WDTT_MANAGED/ {print \$1; exit}'); [ -n \"\$rule_no\" ]; do iptables -D FORWARD \"\$rule_no\" 2>/dev/null || break; done; " +
            "while rule_no=\$(iptables -t nat -L POSTROUTING --line-numbers -n 2>/dev/null | awk '/WDTT_MANAGED/ {print \$1; exit}'); [ -n \"\$rule_no\" ]; do iptables -t nat -D POSTROUTING \"\$rule_no\" 2>/dev/null || break; done; " +
            "while rule_no=\$(iptables -t mangle -L FORWARD --line-numbers -n 2>/dev/null | awk '/WDTT_MANAGED/ {print \$1; exit}'); [ -n \"\$rule_no\" ]; do iptables -t mangle -D FORWARD \"\$rule_no\" 2>/dev/null || break; done; " +
            "fi; " +
            "if command -v nft >/dev/null 2>&1; then " +
            "nft delete table ip wdtt 2>/dev/null || true; " +
            "nft delete table inet wdtt 2>/dev/null || true; " +
            "nft delete table inet wdtt_mangle 2>/dev/null || true; " +
            "fi"),
    (if (mode == ServerUninstallMode.PreserveData) "Сохраняю данные..." else "Удаляю данные...") to (
        "ip link show wdtt0 >/dev/null 2>&1 && ip link del wdtt0 2>/dev/null || true; " +
            if (mode == ServerUninstallMode.PreserveData) {
                "[ -d /etc/wdtt ] && [ ! -L /etc/wdtt ]; " +
                    "chmod 700 /etc/wdtt; " +
                    "[ -f /etc/wdtt/passwords.json ] && chmod 600 /etc/wdtt/passwords.json; " +
                    "[ -f /etc/wdtt/wg-keys.dat ] && chmod 600 /etc/wdtt/wg-keys.dat"
            } else {
                // Rawline: the original's `[ -d ] && [ ! -L ];` does not stop `rm -rf` on a symlinked /etc/wdtt; refuse it.
                "if [ -L /etc/wdtt ]; then echo 'error: /etc/wdtt является символической ссылкой'; exit 1; fi; " +
                    "rm -rf /etc/wdtt; rm -rf /var/tmp/wdtt-plus-update-backup; " +
                    "rm -f /var/log/wdtt-install.log"
            }),
    "Очистка системных настроек..." to (
        "rm -f /etc/sysctl.d/99-wdtt.conf /tmp/deploy.sh /tmp/wdtt-server /tmp/wdtt-passwords.json.new; " +
            "sysctl --system >/dev/null 2>&1 || true"),
)

internal fun wdttPlusUninstallProbe(mode: ServerUninstallMode): String =
    "printf 'WDTT_UNINSTALL_SERVICE=%s\\n' \"\$([ ! -e /etc/systemd/system/wdtt.service ] && echo 0 || echo 1)\"; " +
        "printf 'WDTT_UNINSTALL_BINARY=%s\\n' \"\$([ ! -e /usr/local/bin/wdtt-server ] && echo 0 || echo 1)\"; " +
        "printf 'WDTT_UNINSTALL_INTERFACE=%s\\n' \"\$(ip link show wdtt0 >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
        "printf 'WDTT_UNINSTALL_PROCESS=%s\\n' \"\$(pgrep -x wdtt-server >/dev/null 2>&1 && echo 1 || echo 0)\"; " +
        "printf 'WDTT_UNINSTALL_SYSCTL=%s\\n' \"\$([ ! -e /etc/sysctl.d/99-wdtt.conf ] && echo 0 || echo 1)\"; " +
        "printf 'WDTT_UNINSTALL_FIREWALL=%s\\n' \"\$({ { ! command -v iptables-save >/dev/null 2>&1 || ! iptables-save 2>/dev/null | grep -q WDTT_MANAGED; } && { ! command -v nft >/dev/null 2>&1 || ! nft list tables 2>/dev/null | grep -Eq '^table (ip|inet) wdtt(_mangle)?\$'; }; } && echo 0 || echo 1)\"; " +
        if (mode == ServerUninstallMode.PreserveData) {
            "printf 'WDTT_UNINSTALL_DATA=%s\\n' \"\$([ -f /etc/wdtt/passwords.json ] && [ -f /etc/wdtt/wg-keys.dat ] && [ -f /etc/wdtt/.android-deploy-preserved ] && echo preserved || echo invalid)\""
        } else {
            "printf 'WDTT_UNINSTALL_DATA=%s\\n' \"\$([ ! -e /etc/wdtt ] && echo removed || echo present)\""
        }

internal fun wdttPlusUninstallVerified(output: String, mode: ServerUninstallMode): Boolean =
    listOf("SERVICE", "BINARY", "INTERFACE", "PROCESS", "SYSCTL", "FIREWALL").all { markerValue(output, "WDTT_UNINSTALL_$it") == "0" } &&
        markerValue(output, "WDTT_UNINSTALL_DATA") == if (mode == ServerUninstallMode.PreserveData) "preserved" else "removed"
