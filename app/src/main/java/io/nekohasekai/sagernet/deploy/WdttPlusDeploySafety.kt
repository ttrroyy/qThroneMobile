package app.rawline.mobile.deploy

import org.json.JSONArray
import org.json.JSONObject

/*
 * WDTT Plus deploy safety, carried over verbatim from its app (ui/ServerUpdateSafety.kt, ui/DeployTab.kt,
 * VpnDns.kt, RemoteCommandLog.kt, ServerAdminClient.kt): ownership probe, rollback copy, database checks.
 * Deviations are marked "qThrone:".
 */


/** Paths are injectable only for host-free shell tests; production uses the canonical paths. */
internal data class ServerUpdatePaths(
    val backup: String = "/var/tmp/wdtt-plus-update-backup",
    val config: String = "/etc/wdtt",
    val binary: String = "/usr/local/bin/wdtt-server",
    val unit: String = "/etc/systemd/system/wdtt.service",
    val lock: String = "/run/lock/wdtt-plus-update.lock",
)

internal const val SERVER_UPDATE_PREPARE_TIMEOUT_MS = 180_000L

private fun updateQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun serverUpdateShellContext(paths: ServerUpdatePaths = ServerUpdatePaths()): String = """
    set -euo pipefail
    umask 077
    BACKUP=${updateQuote(paths.backup)}
    CONFIG=${updateQuote(paths.config)}
    BINARY=${updateQuote(paths.binary)}
    UNIT=${updateQuote(paths.unit)}
    LOCK=${updateQuote(paths.lock)}
    wdtt_lock() {
      command -v flock >/dev/null || { echo 'error: для безопасного обновления требуется flock'; exit 2; }
      [ ! -L "${'$'}LOCK" ] || { echo 'error: небезопасный файл блокировки обновления'; exit 2; }
      if [ ! -e "${'$'}LOCK" ]; then
        # Atomic exclusive creation; never truncate an existing path in a
        # shared lock directory, including one created between these checks.
        (set -C; : > "${'$'}LOCK") 2>/dev/null || true
      fi
      wdtt_regular "${'$'}LOCK" && [ "${'$'}(stat -c %u "${'$'}LOCK")" = "${'$'}(id -u)" ] &&
        [ "${'$'}(stat -c %h "${'$'}LOCK")" = 1 ] || exit 2
      exec 9<"${'$'}LOCK"
      flock -n 9 || { echo 'error: другая операция обновления ещё выполняется'; exit 2; }
    }
    wdtt_update_phase() {
      printf '%s\n' "${'$'}1" > "${'$'}BACKUP/state.next"
      mv -f -- "${'$'}BACKUP/state.next" "${'$'}BACKUP/state"
    }
    wdtt_regular() { [ -f "${'$'}1" ] && [ ! -L "${'$'}1" ]; }
    wdtt_private_backup() {
      [ -d "${'$'}BACKUP" ] && [ ! -L "${'$'}BACKUP" ] &&
        [ "${'$'}(stat -c %u "${'$'}BACKUP")" = "${'$'}(id -u)" ] &&
        [ "${'$'}(stat -c %a "${'$'}BACKUP")" = 700 ]
    }
    wdtt_safe_tree() {
      [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ] || return 1
      local unsafe
      unsafe=${'$'}(find "${'$'}1" -mindepth 1 \( -type l -o ! \( -type f -o -type d \) \) -print -quit) || return 1
      [ -z "${'$'}unsafe" ]
    }
    wdtt_manifest() {
      (cd "${'$'}BACKUP"; find . -mindepth 1 -type f \
        ! -path ./state ! -path ./state.next ! -path ./checksums \
        ! -path ./failure -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum --)
    }
    wdtt_quarantine() {
      local destination
      destination=${'$'}(mktemp -d "${'$'}{BACKUP}.incomplete.XXXXXX")
      mv -T -- "${'$'}BACKUP" "${'$'}destination/copy"
      printf 'WDTT_UPDATE_ARCHIVE=%s\n' "${'$'}destination"
    }
    wdtt_discard_backup() {
      local destination
      destination=${'$'}(mktemp -d "${'$'}{BACKUP}.completed.XXXXXX")
      mv -T -- "${'$'}BACKUP" "${'$'}destination/copy"
      rm -rf -- "${'$'}destination"
    }
    wdtt_stop_service() {
      if ! systemctl stop wdtt; then
        [ "${'$'}(systemctl show -p LoadState --value wdtt)" = not-found ] &&
          ! systemctl is-active --quiet wdtt && command -v pgrep >/dev/null &&
          ! pgrep -x wdtt-server >/dev/null || return 1
      fi
      ! systemctl is-active --quiet wdtt
    }
""".trimIndent()

/** A stopped daemon cannot rewrite passwords.json/server.log between copy and comparison. */
internal fun prepareServerUpdateRollbackScript(
    paths: ServerUpdatePaths = ServerUpdatePaths(),
    attemptId: String = java.util.UUID.randomUUID().toString(),
): String =
    serverUpdateShellContext(paths) + "\n" + """
    wdtt_lock
    [ ! -e "${'$'}BACKUP" ] && [ ! -L "${'$'}BACKUP" ] || { echo WDTT_UPDATE_BACKUP=stale; exit 3; }
    stage=preflight
    for tool in cp cmp diff sha256sum find sort xargs stat mktemp du df awk; do
      command -v "${'$'}tool" >/dev/null || { printf 'error: для безопасного обновления требуется %s\n' "${'$'}tool"; exit 4; }
    done
    [ ! -e "${'$'}CONFIG" ] || wdtt_safe_tree "${'$'}CONFIG" || { echo 'error: небезопасная конфигурация'; exit 4; }
    [ ! -L "${'$'}CONFIG" ] || exit 4
    for path in "${'$'}BINARY" "${'$'}UNIT"; do
      [ ! -e "${'$'}path" ] && [ ! -L "${'$'}path" ] || wdtt_regular "${'$'}path" || exit 4
    done
    # Refuse an obviously impossible snapshot before interrupting a healthy service.
    # This is only preflight; a later ENOSPC is still handled by the preparation trap.
    required_kib=32768
    for path in "${'$'}CONFIG" "${'$'}BINARY" "${'$'}UNIT"; do
      if [ -e "${'$'}path" ]; then
        size_kib=${'$'}(du -sk -- "${'$'}path" | awk '{print ${'$'}1}')
        [[ "${'$'}size_kib" =~ ^[0-9]+${'$'} ]] || exit 4
        required_kib=${'$'}((required_kib + size_kib))
      fi
    done
    available_kib=${'$'}(df -Pk -- "${'$'}(dirname "${'$'}BACKUP")" | awk 'NR==2 {print ${'$'}4}')
    [[ "${'$'}available_kib" =~ ^[0-9]+${'$'} ]] && [ "${'$'}available_kib" -ge "${'$'}required_kib" ] || {
      echo 'error: недостаточно свободного места для полной страховочной копии'; exit 4;
    }
    install -d -m 700 "${'$'}BACKUP"
    ready=0
    wdtt_prepare_exit() {
      local status=${'$'}?
      trap - EXIT HUP INT TERM
      if [ "${'$'}ready" = 0 ]; then
        set +e
        printf '%s\n' "${'$'}stage" > "${'$'}BACKUP/failure"
        wdtt_update_phase preparation_failed
        restored=1
        if [ -f "${'$'}BACKUP/was_active" ]; then
          systemctl start wdtt && systemctl is-active --quiet wdtt || restored=0
        fi
        if [ "${'$'}restored" = 1 ]; then wdtt_quarantine; fi
        printf 'error: подготовка страховочной копии остановлена на этапе %s (код %s)\n' "${'$'}stage" "${'$'}status"
        [ "${'$'}status" != 0 ] || status=1
      fi
      exit "${'$'}status"
    }
    trap wdtt_prepare_exit EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    stage=journal
    printf '2\n' > "${'$'}BACKUP/format"
    printf '%s\n' ${updateQuote(attemptId)} > "${'$'}BACKUP/attempt"
    wdtt_update_phase preparing
    stage=service_state
    if systemctl is-active --quiet wdtt; then
      wdtt_regular "${'$'}UNIT" || exit 4
      touch "${'$'}BACKUP/was_active"
    fi
    if systemctl is-enabled --quiet wdtt; then touch "${'$'}BACKUP/was_enabled"; fi
    stage=stop_service
    if [ -f "${'$'}BACKUP/was_active" ]; then
      printf 'WDTT_PROGRESS|0.057|Создаю согласованную копию. Служба WDTT приостановлена на время обновления…\n'
    fi
    # Also stop an inactive/starting unit: it must not activate during copying.
    wdtt_stop_service || exit 5
    stage=copy_config
    if [ -d "${'$'}CONFIG" ]; then
      cp -a -- "${'$'}CONFIG" "${'$'}BACKUP/config"
      wdtt_safe_tree "${'$'}BACKUP/config" || exit 5
      stage=verify_config
      # Compare in both directions: missing or extra files/directories are also a failure.
      diff -qr -- "${'$'}CONFIG" "${'$'}BACKUP/config" >/dev/null || exit 5
      touch "${'$'}BACKUP/had_config"
    fi
    stage=copy_binary
    if wdtt_regular "${'$'}BINARY"; then
      cp -a -- "${'$'}BINARY" "${'$'}BACKUP/wdtt-server"
      cmp -s -- "${'$'}BINARY" "${'$'}BACKUP/wdtt-server" || exit 5
      touch "${'$'}BACKUP/had_binary"
    fi
    stage=copy_unit
    if wdtt_regular "${'$'}UNIT"; then
      cp -a -- "${'$'}UNIT" "${'$'}BACKUP/wdtt.service"
      cmp -s -- "${'$'}UNIT" "${'$'}BACKUP/wdtt.service" || exit 5
      touch "${'$'}BACKUP/had_service"
    fi
    stage=manifest
    wdtt_manifest > "${'$'}BACKUP/checksums"
    stage=publish
    wdtt_update_phase prepared
    ready=1
    echo WDTT_UPDATE_BACKUP=ready
    """.trimIndent()

internal fun serverUpdateManifestProbe(): String = """
    if [ -e "${'$'}BACKUP/format" ] || [ -L "${'$'}BACKUP/format" ]; then
      wdtt_regular "${'$'}BACKUP/format" && [ "${'$'}(cat "${'$'}BACKUP/format")" = 2 ] ||
        wdtt_backup_result prepared_corrupted invalid_format
      wdtt_regular "${'$'}BACKUP/checksums" || wdtt_backup_result prepared_corrupted missing_checksums
      expected=${'$'}(cat "${'$'}BACKUP/checksums") || wdtt_backup_result prepared_corrupted unreadable_checksums
      actual=${'$'}(wdtt_manifest) || wdtt_backup_result prepared_corrupted unreadable_backup
      [ "${'$'}expected" = "${'$'}actual" ] || wdtt_backup_result prepared_corrupted checksum_mismatch
    elif [ -e "${'$'}BACKUP/checksums" ] || [ -L "${'$'}BACKUP/checksums" ] ||
         [ -e "${'$'}BACKUP/attempt" ] || [ -L "${'$'}BACKUP/attempt" ]; then
      wdtt_backup_result prepared_corrupted missing_format
    fi
""".trimIndent()

/** Interrupted preparation has not changed the installation; never restore a partial config. */
internal fun cancelServerUpdatePreparationScript(
    paths: ServerUpdatePaths = ServerUpdatePaths(),
    attemptId: String? = null,
): String =
    serverUpdateShellContext(paths) + "\n" + """
    wdtt_lock
    ${attemptId?.let(::serverUpdateAttemptGuard).orEmpty()}
    wdtt_private_backup || exit 2
    wdtt_safe_tree "${'$'}BACKUP" || exit 2
    wdtt_regular "${'$'}BACKUP/format" && [ "${'$'}(cat "${'$'}BACKUP/format")" = 2 ] || exit 2
    wdtt_regular "${'$'}BACKUP/state" || exit 2
    case "${'$'}(cat "${'$'}BACKUP/state")" in preparing|preparation_failed) ;; *) exit 2 ;; esac
    wdtt_regular "${'$'}BINARY" && [ -x "${'$'}BINARY" ] && wdtt_regular "${'$'}UNIT" || exit 3
    wdtt_regular "${'$'}CONFIG/passwords.json" && [ -s "${'$'}CONFIG/passwords.json" ] || exit 3
    if [ -f "${'$'}BACKUP/was_active" ]; then
      systemctl start wdtt
      systemctl is-active --quiet wdtt || exit 3
    fi
    wdtt_quarantine
    echo WDTT_UPDATE_PREPARATION=cancelled
    """.trimIndent()

internal fun serverUpdateApplyGuardScript(
    command: String,
    attemptId: String,
    paths: ServerUpdatePaths = ServerUpdatePaths(),
): String =
    serverUpdateShellContext(paths) + "\n" + """
    wdtt_lock
    wdtt_regular "${'$'}BACKUP/state" || exit 2
    case "${'$'}(cat "${'$'}BACKUP/state")" in prepared|applying) ;; *) exit 2 ;; esac
    wdtt_regular "${'$'}BACKUP/attempt" && [ "${'$'}(cat "${'$'}BACKUP/attempt")" = ${updateQuote(attemptId)} ] || exit 2
    probe=${'$'}( (${serverUpdateRollbackProbeScript(paths.backup)}) )
    printf '%s\n' "${'$'}probe" | grep -qx 'WDTT_UPDATE_BACKUP_STATUS=prepared_valid' || exit 2
    wdtt_update_phase applying
    """.trimIndent() + "\n" + command

internal fun serverUpdateAttemptGuard(attemptId: String): String = """
    wdtt_regular "${'$'}BACKUP/attempt" && [ "${'$'}(cat "${'$'}BACKUP/attempt")" = ${updateQuote(attemptId)} ] || exit 2
""".trimIndent()

internal fun serverUpdateFailureDetail(output: String): String =
    redactRemoteDiagnostics(output).lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("WDTT_PROGRESS|") }
        .takeLastLines(12).joinToString("\n").takeLast(3000)

private fun Sequence<String>.takeLastLines(count: Int): List<String> {
    val lines = ArrayDeque<String>()
    for (line in this) { if (lines.size == count) lines.removeFirst(); lines.addLast(line) }
    return lines.toList()
}

/** Explicit recovery for the old config-only, unmarked preparation failure; never delete it. */
internal fun archiveLegacyUpdatePreparationScript(paths: ServerUpdatePaths = ServerUpdatePaths()): String =
    serverUpdateShellContext(paths) + "\n" + """
    wdtt_lock
    wdtt_private_backup || exit 2
    wdtt_safe_tree "${'$'}BACKUP" && wdtt_safe_tree "${'$'}BACKUP/config" || exit 2
    [ ! -e "${'$'}BACKUP/state" ] && [ ! -L "${'$'}BACKUP/state" ] || exit 2
    unknown=${'$'}(find "${'$'}BACKUP" -mindepth 1 -maxdepth 1 ! -name config -print -quit)
    [ -z "${'$'}unknown" ] || exit 2
    wdtt_regular "${'$'}BINARY" && [ -x "${'$'}BINARY" ] && wdtt_regular "${'$'}UNIT" || exit 3
    wdtt_regular "${'$'}CONFIG/passwords.json" && [ -s "${'$'}CONFIG/passwords.json" ] || exit 3
    systemctl is-active --quiet wdtt || exit 3
    wdtt_quarantine
    echo WDTT_UPDATE_PREPARATION=archived
    """.trimIndent()

internal fun cleanupServerUpdateBackupScript(
    paths: ServerUpdatePaths = ServerUpdatePaths(),
    attemptId: String? = null,
): String = serverUpdateShellContext(paths) + "\n" + """
    wdtt_lock
    ${attemptId?.let(::serverUpdateAttemptGuard).orEmpty()}
    probe=${'$'}( (${serverUpdateRollbackProbeScript(paths.backup)}) )
    printf '%s\n' "${'$'}probe" | grep -Eq '^WDTT_UPDATE_BACKUP_STATUS=(prepared_valid|committed_valid)$' || exit 2
    wdtt_regular "${'$'}BINARY" && [ -x "${'$'}BINARY" ] && wdtt_regular "${'$'}UNIT" || exit 3
    wdtt_regular "${'$'}CONFIG/passwords.json" && [ -s "${'$'}CONFIG/passwords.json" ] || exit 3
    systemctl is-active --quiet wdtt || exit 3
    if [ -f "${'$'}BACKUP/format" ]; then wdtt_update_phase committed; fi
    # Move atomically out of the recovery path first. Interruption during deletion
    # cannot turn a successfully committed update into an incomplete active backup.
    wdtt_discard_backup
    echo WDTT_UPDATE_CLEANUP=ok
""".trimIndent()

internal fun rollbackServerUpdateScript(
    paths: ServerUpdatePaths = ServerUpdatePaths(),
    attemptId: String? = null,
): String = serverUpdateShellContext(paths) + "\n" + """
    wdtt_lock
    ${attemptId?.let(::serverUpdateAttemptGuard).orEmpty()}
    probe=${'$'}( (${serverUpdateRollbackProbeScript(paths.backup)}) )
    printf '%s\n' "${'$'}probe" | grep -qx 'WDTT_UPDATE_BACKUP_STATUS=prepared_valid' || exit 2
    [ ! -L "${'$'}CONFIG" ] && [ ! -L "${'$'}BINARY" ] && [ ! -L "${'$'}UNIT" ] || exit 3
    wdtt_safe_tree "${'$'}BACKUP" || exit 3
    if [ -f "${'$'}BACKUP/format" ]; then wdtt_update_phase rolling_back; fi
    wdtt_stop_service || exit 3
    if [ -f "${'$'}BACKUP/had_binary" ]; then
      install -m 755 "${'$'}BACKUP/wdtt-server" "${'$'}BINARY"
      cmp -s "${'$'}BACKUP/wdtt-server" "${'$'}BINARY" || exit 3
    else rm -f -- "${'$'}BINARY"; fi
    if [ -f "${'$'}BACKUP/had_service" ]; then
      install -m 644 "${'$'}BACKUP/wdtt.service" "${'$'}UNIT"
      cmp -s "${'$'}BACKUP/wdtt.service" "${'$'}UNIT" || exit 3
    else rm -f -- "${'$'}UNIT"; fi
    # The complete validated backup remains untouched until restoration and
    # service verification succeed, including after an interrupted copy.
    rm -rf -- "${'$'}CONFIG"
    if [ -f "${'$'}BACKUP/had_config" ]; then
      cp -a -- "${'$'}BACKUP/config" "${'$'}CONFIG"
      diff -qr -- "${'$'}BACKUP/config" "${'$'}CONFIG" >/dev/null || exit 3
    fi
    systemctl daemon-reload
    if [ -f "${'$'}BACKUP/was_enabled" ]; then
      systemctl enable wdtt >/dev/null 2>&1
    elif [ -f "${'$'}BACKUP/had_service" ]; then
      systemctl disable wdtt >/dev/null 2>&1
    fi
    if [ -f "${'$'}BACKUP/was_active" ]; then
      systemctl restart wdtt
      sleep 2
      systemctl is-active --quiet wdtt || exit 3
    else
      ! systemctl is-active --quiet wdtt || exit 3
    fi
    for pair in 'had_binary' 'had_service' 'had_config'; do
      case "${'$'}pair" in had_binary) path="${'$'}BINARY" ;; had_service) path="${'$'}UNIT" ;; had_config) path="${'$'}CONFIG" ;; esac
      if [ -f "${'$'}BACKUP/${'$'}pair" ]; then
        [ -e "${'$'}path" ] && [ ! -L "${'$'}path" ] || exit 3
      else [ ! -e "${'$'}path" ] && [ ! -L "${'$'}path" ] || exit 3; fi
    done
    wdtt_discard_backup
    echo WDTT_ROLLBACK=ok
""".trimIndent()

internal enum class DeployMode {
    FreshInstall,
    PreserveData,
    ResetAll
}

internal enum class ServerUninstallMode {
    PreserveData,
    RemoveAll
}

internal data class ServerUpdateRollbackMarkers(
    val hadConfig: Boolean,
    val hadBinary: Boolean,
    val hadService: Boolean,
    val wasActive: Boolean,
    val wasEnabled: Boolean,
)

internal sealed class ServerUpdateRollbackState {
    data object None : ServerUpdateRollbackState()
    data object PreparationIncomplete : ServerUpdateRollbackState()
    data object Committed : ServerUpdateRollbackState()
    data class PreparedValid(val markers: ServerUpdateRollbackMarkers) : ServerUpdateRollbackState()
    data class PreparedCorrupted(val diagnostic: String) : ServerUpdateRollbackState()
    data class UnknownState(val diagnostic: String) : ServerUpdateRollbackState()
}

internal fun parseServerUpdateRollbackState(output: String): ServerUpdateRollbackState {
    fun flag(name: String): Boolean = markerValue(output, name) == "1"
    val diagnostic = markerValue(output, "WDTT_UPDATE_BACKUP_DIAGNOSTIC")
        ?.replace('_', ' ')
        ?.take(160)
        .orEmpty()
    return when (markerValue(output, "WDTT_UPDATE_BACKUP_STATUS")) {
        "none" -> ServerUpdateRollbackState.None
        "preparation_incomplete" -> ServerUpdateRollbackState.PreparationIncomplete
        "committed_valid" -> ServerUpdateRollbackState.Committed
        "prepared_valid" -> ServerUpdateRollbackState.PreparedValid(
            ServerUpdateRollbackMarkers(
                hadConfig = flag("WDTT_UPDATE_BACKUP_HAD_CONFIG"),
                hadBinary = flag("WDTT_UPDATE_BACKUP_HAD_BINARY"),
                hadService = flag("WDTT_UPDATE_BACKUP_HAD_SERVICE"),
                wasActive = flag("WDTT_UPDATE_BACKUP_WAS_ACTIVE"),
                wasEnabled = flag("WDTT_UPDATE_BACKUP_WAS_ENABLED"),
            ),
        )
        "prepared_corrupted" -> ServerUpdateRollbackState.PreparedCorrupted(
            diagnostic.ifBlank { "неполная или небезопасная структура копии" },
        )
        else -> ServerUpdateRollbackState.UnknownState(
            diagnostic.ifBlank { "неизвестное состояние страховочной копии" },
        )
    }
}

internal enum class DeploymentOwnership {
    NoInstall,
    StandaloneInstaller,
    AndroidDeploy,
    LegacyAndroidDeploy,
    PreservedAndroidData,
    IncompleteAndroidDeploy,
    UnknownExisting
}

internal fun standaloneInstallerOwnershipProbeScript(pathPrefix: String = ""): String {
    val normalizedPrefix = pathPrefix.trimEnd('/')
    return """
    root_prefix=${updateQuote(normalizedPrefix)}
    ownership="${'$'}root_prefix/var/lib/wdtt-server-installer/ownership"
    unit="${'$'}root_prefix/etc/systemd/system/wdtt.service"
    binary="${'$'}root_prefix/usr/local/bin/wdtt-server"
    config="${'$'}root_prefix/etc/wdtt"
    passwords="${'$'}root_prefix/etc/wdtt/passwords.json"
    wg_keys="${'$'}root_prefix/etc/wdtt/wg-keys.dat"
    preserved_marker="${'$'}root_prefix/etc/wdtt/.android-deploy-preserved"
    marker='Managed by WDTT Plus standalone server installer'
    if [ -f "${'$'}ownership" ] && [ ! -L "${'$'}ownership" ] &&
       [ -f "${'$'}unit" ] && [ ! -L "${'$'}unit" ] &&
       grep -Fqx "${'$'}marker" "${'$'}ownership" &&
       grep -Fqx "# ${'$'}marker" "${'$'}unit"; then
      printf 'WDTT_STANDALONE_MANAGED=1\n'
    else
      printf 'WDTT_STANDALONE_MANAGED=0\n'
    fi
    android_marker_present=0
    if [ -f "${'$'}unit" ] && [ ! -L "${'$'}unit" ] &&
       grep -Fqx '# Managed by WDTT Plus Android deploy' "${'$'}unit" &&
       grep -Fqx '# WDTT deploy compatibility: 1' "${'$'}unit"; then
      android_marker_present=1
    fi
    android_deploy_managed=0
    if [ "${'$'}android_marker_present" = "1" ] &&
       [ -f "${'$'}binary" ] && [ ! -L "${'$'}binary" ] && [ -x "${'$'}binary" ] &&
       [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ] &&
       [ -f "${'$'}passwords" ] && [ ! -L "${'$'}passwords" ] && [ -s "${'$'}passwords" ] &&
       [ -f "${'$'}wg_keys" ] && [ ! -L "${'$'}wg_keys" ] && [ -s "${'$'}wg_keys" ] &&
       grep -Eq '^ExecStart=/usr/local/bin/wdtt-server([[:space:]]|${'$'})' "${'$'}unit" &&
       grep -Eq '(^|[[:space:]])-config-dir[[:space:]]+/etc/wdtt([[:space:]]|${'$'})' "${'$'}unit" &&
       grep -Fq 'wdtt0' "${'$'}unit"; then
      android_deploy_managed=1
      printf 'WDTT_ANDROID_DEPLOY_MANAGED=1\n'
    else
      printf 'WDTT_ANDROID_DEPLOY_MANAGED=0\n'
    fi
    if [ -f "${'$'}unit" ] && [ ! -L "${'$'}unit" ] &&
       [ -f "${'$'}binary" ] && [ ! -L "${'$'}binary" ] && [ -x "${'$'}binary" ] &&
       [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ] &&
       [ -f "${'$'}passwords" ] && [ ! -L "${'$'}passwords" ] && [ -s "${'$'}passwords" ] &&
       [ -f "${'$'}wg_keys" ] && [ ! -L "${'$'}wg_keys" ] && [ -s "${'$'}wg_keys" ] &&
       grep -Eq '^ExecStart=/usr/local/bin/wdtt-server([[:space:]]|${'$'})' "${'$'}unit" &&
       grep -Eq '(^|[[:space:]])-config-dir[[:space:]]+/etc/wdtt([[:space:]]|${'$'})' "${'$'}unit" &&
       grep -Fq 'wdtt0' "${'$'}unit"; then
      printf 'WDTT_LEGACY_ANDROID_DEPLOY_CANDIDATE=1\n'
    else
      printf 'WDTT_LEGACY_ANDROID_DEPLOY_CANDIDATE=0\n'
    fi
    preserved_extra=''
    if [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ]; then
      preserved_extra="${'$'}(find "${'$'}config" -mindepth 1 -maxdepth 1 \
        ! -path "${'$'}passwords" ! -path "${'$'}wg_keys" ! -path "${'$'}preserved_marker" \
        -print -quit 2>/dev/null || true)"
    fi
    if [ ! -e "${'$'}ownership" ] && [ ! -L "${'$'}ownership" ] &&
       [ ! -e "${'$'}unit" ] && [ ! -L "${'$'}unit" ] &&
       [ ! -e "${'$'}binary" ] && [ ! -L "${'$'}binary" ] &&
       [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ] &&
       [ -f "${'$'}passwords" ] && [ ! -L "${'$'}passwords" ] && [ -s "${'$'}passwords" ] &&
       [ -f "${'$'}wg_keys" ] && [ ! -L "${'$'}wg_keys" ] && [ -s "${'$'}wg_keys" ] &&
       [ "${'$'}(stat -c '%a' "${'$'}config" 2>/dev/null || true)" = "700" ] &&
       [ "${'$'}(stat -c '%a' "${'$'}passwords" 2>/dev/null || true)" = "600" ] &&
       [ "${'$'}(stat -c '%a' "${'$'}wg_keys" 2>/dev/null || true)" = "600" ] &&
       { { [ -f "${'$'}preserved_marker" ] && [ ! -L "${'$'}preserved_marker" ] &&
             [ "${'$'}(cat "${'$'}preserved_marker" 2>/dev/null)" = 'Preserved by WDTT Plus Android deploy' ]; } ||
         { [ ! -e "${'$'}preserved_marker" ] && [ -z "${'$'}preserved_extra" ]; }; }; then
      printf 'WDTT_ANDROID_DATA_PRESERVED=1\n'
    else
      printf 'WDTT_ANDROID_DATA_PRESERVED=0\n'
    fi
    incomplete_extra=''
    if [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ]; then
      incomplete_extra="${'$'}(find "${'$'}config" -mindepth 1 -maxdepth 1 ! -path "${'$'}passwords" -print -quit 2>/dev/null || true)"
    fi
    if [ "${'$'}android_deploy_managed" = "0" ] &&
       [ "${'$'}android_marker_present" = "1" ] &&
       [ ! -e "${'$'}ownership" ] && [ ! -L "${'$'}ownership" ] &&
       { { [ ! -e "${'$'}binary" ] && [ ! -L "${'$'}binary" ]; } ||
         { [ -f "${'$'}binary" ] && [ ! -L "${'$'}binary" ]; }; } &&
       { { [ ! -e "${'$'}config" ] && [ ! -L "${'$'}config" ]; } ||
         { [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ]; }; } &&
       { { [ ! -e "${'$'}passwords" ] && [ ! -L "${'$'}passwords" ]; } ||
         { [ -f "${'$'}passwords" ] && [ ! -L "${'$'}passwords" ]; }; } &&
       { { [ ! -e "${'$'}wg_keys" ] && [ ! -L "${'$'}wg_keys" ]; } ||
         { [ -f "${'$'}wg_keys" ] && [ ! -L "${'$'}wg_keys" ]; }; }; then
      printf 'WDTT_INCOMPLETE_ANDROID_DEPLOY_CANDIDATE=1\n'
    elif [ ! -e "${'$'}ownership" ] && [ ! -L "${'$'}ownership" ] &&
       [ ! -e "${'$'}unit" ] && [ ! -L "${'$'}unit" ] &&
       [ ! -e "${'$'}binary" ] && [ ! -L "${'$'}binary" ] &&
       [ -d "${'$'}config" ] && [ ! -L "${'$'}config" ] &&
       [ -f "${'$'}passwords" ] && [ ! -L "${'$'}passwords" ] && [ -s "${'$'}passwords" ] &&
       [ ! -e "${'$'}wg_keys" ] && [ -z "${'$'}incomplete_extra" ] &&
       [ "${'$'}(stat -c '%a' "${'$'}config" 2>/dev/null || true)" = "700" ] &&
       [ "${'$'}(stat -c '%a' "${'$'}passwords" 2>/dev/null || true)" = "600" ]; then
      printf 'WDTT_INCOMPLETE_ANDROID_DEPLOY_CANDIDATE=1\n'
    else
      printf 'WDTT_INCOMPLETE_ANDROID_DEPLOY_CANDIDATE=0\n'
    fi
    if [ -f "${'$'}unit" ] || [ -f "${'$'}binary" ] || [ -d "${'$'}config" ] ||
       [ -f "${'$'}passwords" ] || [ -f "${'$'}wg_keys" ]; then
      printf 'WDTT_INSTALL_TRACE=1\n'
    else
      printf 'WDTT_INSTALL_TRACE=0\n'
    fi
""".trimIndent()
}

internal fun deploymentOwnershipFromProbe(output: String): DeploymentOwnership {
    val requiredMarkers = listOf(
        "WDTT_STANDALONE_MANAGED",
        "WDTT_ANDROID_DEPLOY_MANAGED",
        "WDTT_LEGACY_ANDROID_DEPLOY_CANDIDATE",
        "WDTT_ANDROID_DATA_PRESERVED",
        "WDTT_INCOMPLETE_ANDROID_DEPLOY_CANDIDATE",
        "WDTT_INSTALL_TRACE",
    )
    if (requiredMarkers.any { markerValue(output, it) !in setOf("0", "1") }) {
        throw IllegalStateException("не удалось безопасно проверить владельца установки. Изменяющая операция остановлена.")
    }
    return when {
        markerValue(output, "WDTT_STANDALONE_MANAGED") == "1" -> DeploymentOwnership.StandaloneInstaller
        markerValue(output, "WDTT_ANDROID_DEPLOY_MANAGED") == "1" -> DeploymentOwnership.AndroidDeploy
        markerValue(output, "WDTT_LEGACY_ANDROID_DEPLOY_CANDIDATE") == "1" -> DeploymentOwnership.LegacyAndroidDeploy
        markerValue(output, "WDTT_ANDROID_DATA_PRESERVED") == "1" -> DeploymentOwnership.PreservedAndroidData
        markerValue(output, "WDTT_INCOMPLETE_ANDROID_DEPLOY_CANDIDATE") == "1" ->
            DeploymentOwnership.IncompleteAndroidDeploy
        markerValue(output, "WDTT_INSTALL_TRACE") == "0" -> DeploymentOwnership.NoInstall
        markerValue(output, "WDTT_INSTALL_TRACE") == "1" -> DeploymentOwnership.UnknownExisting
        else -> throw IllegalStateException("не удалось безопасно проверить владельца установки. Изменяющая операция остановлена.")
    }
}

internal fun validateRootAccessOutput(output: String) {
    // A missing marker does not prove a permission denial: the SSH command may
    // have timed out or lost its channel before returning a complete answer.
    friendlyAdminSudoError(output)?.let { throw IllegalStateException(it) }
    val commandErrors = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("error:", ignoreCase = true) }
        .joinToString("\n")
    check(commandErrors.isBlank()) { commandErrors.take(2000) }
    check(markerValue(output, "WDTT_ROOT_ACCESS") == "ok") {
        "Сервер не подтвердил проверку прав администратора. Установка не запущена; проверьте соединение и повторите проверку."
    }
}

internal fun assertDeploymentMayBeUpdated(
    ownership: DeploymentOwnership,
    mode: DeployMode,
) {
    when {
        ownership == DeploymentOwnership.StandaloneInstaller -> throw IllegalStateException(
            "сервер управляется отдельным standalone-инсталлером. Android-приложение не обновляет " +
                "и не перезаписывает такую установку; используйте server-installer/install.sh на VPS."
        )
        ownership == DeploymentOwnership.UnknownExisting -> throw IllegalStateException(
            "на сервере есть неизвестная установка WDTT без безопасных признаков Android-деплоя. " +
                "Изменяющая операция остановлена."
        )
        mode == DeployMode.FreshInstall && ownership != DeploymentOwnership.NoInstall ->
            throw IllegalStateException(
                "первичная установка остановлена: на сервере появились следы WDTT. " +
                    "Повторите проверку и выберите безопасное действие для найденной установки."
            )
        mode == DeployMode.PreserveData && ownership !in setOf(
            DeploymentOwnership.AndroidDeploy,
            DeploymentOwnership.LegacyAndroidDeploy,
            DeploymentOwnership.PreservedAndroidData,
        ) -> throw IllegalStateException(
            "обновление с сохранением разрешено только для подтверждённой Android-установки."
        )
        ownership == DeploymentOwnership.LegacyAndroidDeploy && mode == DeployMode.ResetAll -> {
            throw IllegalStateException(
                "старая Android-установка без метки допускает только обновление с сохранением. " +
                    "После успешного обновления появится метка, а сброс по-прежнему потребует отдельного подтверждения."
            )
        }
        mode == DeployMode.ResetAll && ownership !in setOf(
            DeploymentOwnership.AndroidDeploy,
            DeploymentOwnership.PreservedAndroidData,
            DeploymentOwnership.IncompleteAndroidDeploy,
        ) ->
            throw IllegalStateException(
                "полный сброс разрешён только для установки с подтверждённой меткой Android-деплоя."
            )
        else -> Unit
    }
}

// qThrone: java.util.Base64 needs API 26; a 32-byte key is 43 base64 characters and optional padding.
private fun String.isValidBase64Key(): Boolean = Regex("""^[A-Za-z0-9+/]{43}=?$""").matches(this)

private fun String.isValidPortsSpec(): Boolean {
    val parts = split(",").map { it.trim() }
    return parts.size == 3 && parts.all { it.toIntOrNull()?.let { port -> port in 1..65535 } == true }
}

private fun JSONObject.optStringLength(name: String, limit: Int, owner: String) {
    require(optString(name, "").length <= limit) { "$name у $owner слишком длинный" }
}

private fun validateBindHistoryEntry(pass: String, index: Int, event: JSONObject) {
    val owner = "bind_history[$index] пароля $pass"
    event.optStringLength("device_id", 256, owner)
    event.optStringLength("device_name", 120, owner)
    event.optStringLength("device_ip", 64, owner)
    event.optStringLength("remote_ip", 64, owner)
    event.optStringLength("country", 32, owner)
    event.optStringLength("note", 256, owner)
    val status = event.optString("status", "")
    require(status in setOf("active", "unbound", "denied_mismatch")) { "неизвестный status у $owner" }
    listOf("bound_at", "unbound_at", "event_at").forEach { field ->
        require(event.optLong(field, 0) >= 0) { "$field у $owner должен быть >= 0" }
    }
}

private fun validateTrafficBucket(owner: String, index: Int, bucket: JSONObject) {
    val date = bucket.optString("date", "")
    require(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) { "traffic[$index] у $owner должен содержать дату YYYY-MM-DD" }
    require(bucket.optLong("down_bytes", 0) >= 0) { "down_bytes traffic[$index] у $owner должен быть >= 0" }
    require(bucket.optLong("up_bytes", 0) >= 0) { "up_bytes traffic[$index] у $owner должен быть >= 0" }
}

private fun validatePasswordEntry(pass: String, entry: JSONObject) {
    require(pass.isNotBlank()) { "пустой пароль в passwords" }
    require(pass.length <= 256) { "пароль в passwords слишком длинный" }
    entry.optStringLength("device_id", 256, "пароля $pass")
    entry.optStringLength("label", 120, "пароля $pass")
    entry.optStringLength("vk_hash", 512, "пароля $pass")
    val expiresAt = entry.optLong("expires_at", 0)
    val purgeAfter = entry.optLong("purge_after", 0)
    require(expiresAt >= 0) { "expires_at у пароля $pass должен быть >= 0" }
    require(purgeAfter >= 0) { "purge_after у пароля $pass должен быть >= 0" }
    require(expiresAt != 0L || purgeAfter == 0L) {
        "purge_after у бессрочного пароля $pass должен быть 0"
    }
    require(purgeAfter == 0L || purgeAfter >= expiresAt) {
        "purge_after у пароля $pass не может быть раньше expires_at"
    }
    require(entry.optLong("down_bytes", 0) >= 0) { "down_bytes у пароля $pass должен быть >= 0" }
    require(entry.optLong("up_bytes", 0) >= 0) { "up_bytes у пароля $pass должен быть >= 0" }
    val ports = entry.optString("ports", "")
    require(ports.isBlank() || ports.isValidPortsSpec()) { "некорректные ports у пароля $pass" }
    val history = entry.optJSONArray("bind_history")
    if (history != null) {
        require(history.length() <= 500) { "bind_history у пароля $pass слишком большой" }
        for (i in 0 until history.length()) {
            val event = history.optJSONObject(i)
                ?: throw IllegalArgumentException("bind_history у пароля $pass должен содержать объекты")
            validateBindHistoryEntry(pass, i, event)
        }
    }
    val traffic = entry.optJSONArray("traffic")
    if (traffic != null) {
        require(traffic.length() <= 500) { "traffic у пароля $pass слишком большой" }
        for (i in 0 until traffic.length()) {
            val bucket = traffic.optJSONObject(i)
                ?: throw IllegalArgumentException("traffic у пароля $pass должен содержать объекты")
            validateTrafficBucket("пароля $pass", i, bucket)
        }
    }
    val trafficImports = if (entry.has("traffic_imports")) {
        entry.optJSONObject("traffic_imports")
            ?: throw IllegalArgumentException("traffic_imports у пароля $pass должен быть объектом")
    } else {
        null
    }
    if (trafficImports != null) {
        require(trafficImports.length() <= 1_000) { "traffic_imports у пароля $pass слишком большой" }
        trafficImports.keys().forEach { operationId ->
            require(operationId.isNotBlank() && operationId.length <= 160) {
                "некорректный operation-id в traffic_imports у пароля $pass"
            }
            val imported = trafficImports.optJSONObject(operationId)
                ?: throw IllegalArgumentException("traffic_imports у пароля $pass должен содержать объекты")
            require(imported.optLong("down_bytes", 0) >= 0) {
                "down_bytes в traffic_imports у пароля $pass должен быть >= 0"
            }
            require(imported.optLong("up_bytes", 0) >= 0) {
                "up_bytes в traffic_imports у пароля $pass должен быть >= 0"
            }
            require(imported.optLong("applied_at", 0) >= 0) {
                "applied_at в traffic_imports у пароля $pass должен быть >= 0"
            }
        }
    }
}

private fun validateDeviceEntry(deviceId: String, device: JSONObject) {
    require(deviceId.isNotBlank()) { "пустой ключ устройства в devices" }
    require(deviceId.length <= 256) { "ключ устройства слишком длинный" }
    val storedId = device.optString("device_id", deviceId)
    require(storedId.isNotBlank()) { "device_id устройства $deviceId пустой" }
    val ip = device.optString("ip")
    require(ip.isBlank() || Regex("^10\\.66\\.66\\.([2-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|250)$").matches(ip)) {
        "некорректный IP устройства $deviceId"
    }
    val priv = device.optString("priv_key")
    val pub = device.optString("pub_key")
    require(priv.isBlank() || priv.isValidBase64Key()) { "некорректный priv_key устройства $deviceId" }
    require(pub.isBlank() || pub.isValidBase64Key()) { "некорректный pub_key устройства $deviceId" }
    listOf("name", "model").forEach { device.optStringLength(it, 120, "устройства $deviceId") }
    listOf("manufacturer", "brand", "android_version", "abi", "app_version", "locale", "country").forEach {
        device.optStringLength(it, 64, "устройства $deviceId")
    }
    device.optStringLength("time_zone", 96, "устройства $deviceId")
    device.optStringLength("remote_ip", 64, "устройства $deviceId")
    require(device.optInt("sdk", 0) >= 0) { "sdk устройства $deviceId должен быть >= 0" }
    require(device.optLong("last_seen_at", 0) >= 0) { "last_seen_at устройства $deviceId должен быть >= 0" }
}

private fun validateAdminProfile(profile: JSONObject?) {
    if (profile == null) return
    profile.optStringLength("vk_hashes", 2048, "admin_profile")
    profile.optStringLength("secondary_vk_hash", 512, "admin_profile")
    profile.optStringLength("profile_name", 48, "admin_profile")
    profile.optStringLength("sni", 253, "admin_profile")
    profile.optStringLength("vpn_dns_selection", 32, "admin_profile")
    profile.optStringLength("vpn_dns_custom", 64, "admin_profile")
    if (profile.has("workers_per_hash")) {
        require(profile.optInt("workers_per_hash", 0) in 1..128) { "workers_per_hash в admin_profile должен быть 1..128" }
    }
    if (profile.has("protocol")) {
        require(profile.optString("protocol").lowercase() in setOf("udp", "tcp")) { "protocol в admin_profile должен быть udp или tcp" }
    }
    if (profile.has("listen_port")) {
        require(profile.optInt("listen_port", 0) in 1..65535) { "listen_port в admin_profile некорректен" }
    }
    if (profile.has("vpn_dns_selection")) {
        val rawSelection = profile.optString("vpn_dns_selection").trim().lowercase()
        require(normalizeVpnDnsSelectionId(rawSelection) == rawSelection) {
            "vpn_dns_selection в admin_profile некорректен"
        }
        val custom = decodeStoredCustomVpnDnsServers(profile.optString("vpn_dns_custom", ""))
        require(rawSelection != VPN_DNS_CUSTOM_ID || custom.isNotEmpty()) {
            "для custom DNS в admin_profile нужны IPv4-адреса"
        }
    }
    val ports = profile.optString("ports", "")
    require(ports.isBlank() || ports.isValidPortsSpec()) { "ports в admin_profile некорректны" }
    require(profile.optLong("updated_at", 0L) >= 0L) { "updated_at в admin_profile должен быть >= 0" }
    profile.optJSONArray("device_ids")?.let { ids ->
        require(ids.length() <= 64) { "слишком много device_ids в admin_profile" }
        for (index in 0 until ids.length()) {
            require(ids.optString(index).length <= 256) { "device_id в admin_profile слишком длинный" }
        }
    }
}

internal fun validatePasswordsDbStructure(db: JSONObject) {
    require(db.has("main_password")) { "в базе нет main_password" }
    require(db.optString("main_password").isNotBlank()) { "main_password пустой" }
    require(db.optString("main_password").length <= 256) { "main_password слишком длинный" }
    require(db.optString("admin_id", "").length <= 64) { "admin_id слишком длинный" }
    require(db.optString("bot_token", "").length <= 256) { "bot_token слишком длинный" }
    val passwords = db.optJSONObject("passwords") ?: throw IllegalArgumentException("passwords должен быть объектом")
    val devices = db.optJSONObject("devices") ?: throw IllegalArgumentException("devices должен быть объектом")
    require(passwords.length() <= 500) { "в бэкапе больше 500 паролей" }
    require(devices.length() <= 500) { "в бэкапе больше 500 устройств" }
    val dns = db.optString("dns", "")
    require(dns.isBlank() || dns.length <= 256) { "dns слишком длинный" }
    val publicHost = db.optString("public_ip", "")
    require(publicHost.isBlank() || publicHost.isValidPublicHost()) {
        "public_ip должен быть доменом или IPv4 без http:// и без порта"
    }
    val defaultPorts = db.optString("default_ports", "")
    require(defaultPorts.isBlank() || defaultPorts.isValidPortsSpec()) { "default_ports должен быть в формате DTLS,WG,TUN" }
    val maxPasswords = db.optInt("max_passwords", 50)
    require(maxPasswords in 0..500) { "max_passwords должен быть 0..500" }
    require(db.optLong("admin_down_bytes", 0) >= 0) { "admin_down_bytes должен быть >= 0" }
    require(db.optLong("admin_up_bytes", 0) >= 0) { "admin_up_bytes должен быть >= 0" }
    val adminTraffic = db.optJSONArray("admin_traffic")
    if (adminTraffic != null) {
        require(adminTraffic.length() <= 500) { "admin_traffic слишком большой" }
        for (i in 0 until adminTraffic.length()) {
            val bucket = adminTraffic.optJSONObject(i)
                ?: throw IllegalArgumentException("admin_traffic должен содержать объекты")
            validateTrafficBucket("admin_traffic", i, bucket)
        }
    }
    validateAdminProfile(db.optJSONObject("admin_profile"))
    passwords.keys().forEach { pass ->
        val entry = passwords.optJSONObject(pass) ?: throw IllegalArgumentException("passwords.$pass должен быть объектом")
        validatePasswordEntry(pass, entry)
        entry.optString("device_id", "").takeIf { it.isNotBlank() }?.let { deviceId ->
            require(devices.has(deviceId)) { "у пароля $pass указано отсутствующее устройство $deviceId" }
        }
    }
    devices.keys().forEach { deviceId ->
        val device = devices.optJSONObject(deviceId) ?: throw IllegalArgumentException("devices.$deviceId должен быть объектом")
        validateDeviceEntry(deviceId, device)
    }
}

internal fun validatePasswordsDbForPreserving(
    db: JSONObject,
    replacementMainPassword: String
): Boolean {
    val mainPasswordNeedsRecovery =
        !db.has("main_password") || db.optString("main_password").isBlank()
    if (!mainPasswordNeedsRecovery) {
        validatePasswordsDbStructure(db)
        return false
    }
    require(replacementMainPassword.isNotBlank()) {
        "main_password пустой; укажите главный пароль в параметрах сервера"
    }
    val validatedCopy = JSONObject(db.toString())
        .put("main_password", replacementMainPassword)
    validatePasswordsDbStructure(validatedCopy)
    return true
}

internal fun prepareDeployDatabaseJson(
    currentJson: String?,
    mainPassword: String,
    adminId: String,
    botToken: String,
    dns: String,
    ports: String
): String {
    val normalizedDns = normalizedDeployDns(dns)
    val database = if (currentJson == null) {
        JSONObject()
            .put("main_password", mainPassword)
            .put("admin_id", adminId)
            .put("bot_token", botToken)
            .put("passwords", JSONObject())
            .put("devices", JSONObject())
    } else {
        JSONObject(currentJson)
    }
    if (mainPassword.isNotBlank()) database.put("main_password", mainPassword)
    if (adminId.isNotBlank()) database.put("admin_id", adminId)
    if (botToken.isNotBlank()) database.put("bot_token", botToken)
    database.put("dns", normalizedDns)
    database.put("default_ports", ports)
    if (!database.has("max_passwords") || database.optInt("max_passwords", 0) <= 0) {
        database.put("max_passwords", 50)
    }
    validatePasswordsDbStructure(database)
    return database.toString(2)
}

internal fun normalizedDeployDns(first: String, second: String = ""): String {
    val values = listOf(first, second)
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotBlank() }
    require(values.isNotEmpty()) { "Укажите хотя бы один DNS" }
    require(values.size <= 4) { "Можно указать не больше четырёх DNS" }
    values.forEach { value ->
        require(value.length <= 64) { "DNS слишком длинный" }
        require(value.all { char ->
            char == '.' || char == ':' || char == '-' || char == '_' ||
                char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z'
        }) { "DNS содержит недопустимые символы" }
    }
    return values.joinToString(",")
}

private const val SERVER_UPDATE_BACKUP_DIR = "/var/tmp/wdtt-plus-update-backup"

internal fun serverUpdateRollbackProbeScript(
    backupDir: String = SERVER_UPDATE_BACKUP_DIR,
): String = serverUpdateShellContext(ServerUpdatePaths(backup = backupDir)) + "\n" + """
    wdtt_backup_result() {
      printf 'WDTT_UPDATE_BACKUP_STATUS=%s\n' "${'$'}1"
      printf 'WDTT_UPDATE_BACKUP_DIAGNOSTIC=%s\n' "${'$'}2"
      exit 0
    }
    if [ ! -e "${'$'}BACKUP" ] && [ ! -L "${'$'}BACKUP" ]; then
      wdtt_backup_result none absent
    fi
    if [ -L "${'$'}BACKUP" ] || [ ! -d "${'$'}BACKUP" ]; then
      wdtt_backup_result prepared_corrupted unsafe_backup_path
    fi
    wdtt_private_backup || wdtt_backup_result prepared_corrupted unsafe_backup_permissions
    if [ ! -f "${'$'}BACKUP/state" ] || [ -L "${'$'}BACKUP/state" ]; then
      wdtt_backup_result prepared_corrupted missing_or_unsafe_state
    fi
    backup_state=${'$'}(cat "${'$'}BACKUP/state" 2>/dev/null || true)
    if [ "${'$'}backup_state" = preparing ] || [ "${'$'}backup_state" = preparation_failed ]; then
      wdtt_regular "${'$'}BACKUP/format" && [ "${'$'}(cat "${'$'}BACKUP/format")" = 2 ] ||
        wdtt_backup_result unknown_state unknown_preparation
      wdtt_safe_tree "${'$'}BACKUP" || wdtt_backup_result prepared_corrupted unsafe_preparation
      unknown=${'$'}(find "${'$'}BACKUP" -mindepth 1 -maxdepth 1 \
        ! -name state ! -name state.next ! -name format ! -name attempt ! -name checksums ! -name failure \
        ! -name config ! -name wdtt-server ! -name wdtt.service \
        ! -name had_config ! -name had_binary ! -name had_service \
        ! -name was_active ! -name was_enabled -print -quit)
      [ -z "${'$'}unknown" ] || wdtt_backup_result prepared_corrupted unknown_backup_entry
      wdtt_backup_result preparation_incomplete preparation_not_finished
    fi
    if [ "${'$'}backup_state" != prepared ] && [ "${'$'}backup_state" != applying ] &&
       [ "${'$'}backup_state" != rolling_back ] && [ "${'$'}backup_state" != committed ]; then
      wdtt_backup_result unknown_state state_not_prepared
    fi
    if [ "${'$'}backup_state" != prepared ]; then
      wdtt_regular "${'$'}BACKUP/format" && [ "${'$'}(cat "${'$'}BACKUP/format")" = 2 ] ||
        wdtt_backup_result unknown_state legacy_state_not_prepared
    fi
    for marker in had_config had_binary had_service was_active was_enabled; do
      marker_path="${'$'}BACKUP/${'$'}marker"
      if [ -e "${'$'}marker_path" ] || [ -L "${'$'}marker_path" ]; then
        [ -f "${'$'}marker_path" ] && [ ! -L "${'$'}marker_path" ] ||
          wdtt_backup_result prepared_corrupted unsafe_marker
      fi
    done
    if [ -f "${'$'}BACKUP/had_config" ]; then
      [ -d "${'$'}BACKUP/config" ] && [ ! -L "${'$'}BACKUP/config" ] ||
        wdtt_backup_result prepared_corrupted missing_config
      unsafe=${'$'}(find "${'$'}BACKUP/config" -mindepth 1 \( -type l -o ! \( -type f -o -type d \) \) -print -quit) ||
        wdtt_backup_result prepared_corrupted unreadable_config_tree
      [ -z "${'$'}unsafe" ] || wdtt_backup_result prepared_corrupted unsafe_config_tree
    elif [ -e "${'$'}BACKUP/config" ] || [ -L "${'$'}BACKUP/config" ]; then
      wdtt_backup_result prepared_corrupted unexpected_config
    fi
    if [ -f "${'$'}BACKUP/had_binary" ]; then
      [ -f "${'$'}BACKUP/wdtt-server" ] && [ ! -L "${'$'}BACKUP/wdtt-server" ] ||
        wdtt_backup_result prepared_corrupted missing_binary
    elif [ -e "${'$'}BACKUP/wdtt-server" ] || [ -L "${'$'}BACKUP/wdtt-server" ]; then
      wdtt_backup_result prepared_corrupted unexpected_binary
    fi
    if [ -f "${'$'}BACKUP/had_service" ]; then
      [ -f "${'$'}BACKUP/wdtt.service" ] && [ ! -L "${'$'}BACKUP/wdtt.service" ] ||
        wdtt_backup_result prepared_corrupted missing_service
    elif [ -e "${'$'}BACKUP/wdtt.service" ] || [ -L "${'$'}BACKUP/wdtt.service" ]; then
      wdtt_backup_result prepared_corrupted unexpected_service
    fi
    if { [ -f "${'$'}BACKUP/was_active" ] || [ -f "${'$'}BACKUP/was_enabled" ]; } &&
       [ ! -f "${'$'}BACKUP/had_service" ]; then
      wdtt_backup_result prepared_corrupted service_state_without_service
    fi
    unknown=${'$'}(find "${'$'}BACKUP" -mindepth 1 -maxdepth 1 \
      ! -name state ! -name had_config ! -name config \
      ! -name had_binary ! -name wdtt-server \
      ! -name had_service ! -name wdtt.service \
      ! -name was_active ! -name was_enabled ! -name format ! -name attempt ! -name checksums \
      ! -name failure -print -quit)
    [ -z "${'$'}unknown" ] || wdtt_backup_result prepared_corrupted unknown_backup_entry
    ${serverUpdateManifestProbe()}
    if [ "${'$'}backup_state" = committed ]; then wdtt_backup_result committed_valid committed; fi
    printf 'WDTT_UPDATE_BACKUP_STATUS=prepared_valid\n'
    printf 'WDTT_UPDATE_BACKUP_DIAGNOSTIC=prepared\n'
    printf 'WDTT_UPDATE_BACKUP_HAD_CONFIG=%s\n' "$([ -f "${'$'}BACKUP/had_config" ] && echo 1 || echo 0)"
    printf 'WDTT_UPDATE_BACKUP_HAD_BINARY=%s\n' "$([ -f "${'$'}BACKUP/had_binary" ] && echo 1 || echo 0)"
    printf 'WDTT_UPDATE_BACKUP_HAD_SERVICE=%s\n' "$([ -f "${'$'}BACKUP/had_service" ] && echo 1 || echo 0)"
    printf 'WDTT_UPDATE_BACKUP_WAS_ACTIVE=%s\n' "$([ -f "${'$'}BACKUP/was_active" ] && echo 1 || echo 0)"
    printf 'WDTT_UPDATE_BACKUP_WAS_ENABLED=%s\n' "$([ -f "${'$'}BACKUP/was_enabled" ] && echo 1 || echo 0)"
""".trimIndent()

private data class PreservedTrafficCounter(
    val downBytes: Long,
    val upBytes: Long
)

private fun trafficCountersByDate(history: JSONArray?): Map<String, PreservedTrafficCounter> {
    if (history == null) return emptyMap()
    val result = linkedMapOf<String, PreservedTrafficCounter>()
    for (index in 0 until history.length()) {
        val bucket = history.optJSONObject(index) ?: continue
        val date = bucket.optString("date", "")
        val previous = result[date] ?: PreservedTrafficCounter(0L, 0L)
        result[date] = PreservedTrafficCounter(
            downBytes = Math.addExact(previous.downBytes, bucket.optLong("down_bytes", 0L)),
            upBytes = Math.addExact(previous.upBytes, bucket.optLong("up_bytes", 0L))
        )
    }
    return result
}

private fun requireCounterNotRegressed(
    expected: Long,
    actual: Long,
    description: String
) {
    require(actual >= expected) { "$description уменьшился" }
}

private fun requireTrafficHistoryNotRegressed(
    expected: JSONArray?,
    actual: JSONArray?,
    description: String
) {
    val actualByDate = trafficCountersByDate(actual)
    trafficCountersByDate(expected).forEach { (date, expectedCounter) ->
        val actualCounter = actualByDate[date]
        require(
            actualCounter != null &&
                actualCounter.downBytes >= expectedCounter.downBytes &&
                actualCounter.upBytes >= expectedCounter.upBytes
        ) {
            "$description за $date уменьшилась или пропала"
        }
    }
}

private fun bindHistoryEntries(history: JSONArray?): List<String> {
    if (history == null) return emptyList()
    return buildList(history.length()) {
        for (index in 0 until history.length()) {
            add(history.getJSONObject(index).toString())
        }
    }
}

private fun requireBindHistoryNotRegressed(
    expected: JSONArray?,
    actual: JSONArray?,
    description: String
) {
    val expectedEntries = bindHistoryEntries(expected)
    if (expectedEntries.isEmpty()) return
    val actualEntries = bindHistoryEntries(actual)

    if (actualEntries.size >= expectedEntries.size &&
        actualEntries.subList(0, expectedEntries.size) == expectedEntries
    ) {
        return
    }

    // Сервер хранит последние 50 событий. Новые события могут вытеснить
    // только начало истории, поэтому требуем непрерывное пересечение:
    // хвост прежней истории должен стать началом новой.
    if (actualEntries.size == 50) {
        val maxOverlap = minOf(expectedEntries.size, actualEntries.size)
        for (overlap in maxOverlap downTo 1) {
            if (expectedEntries.takeLast(overlap) == actualEntries.take(overlap)) {
                return
            }
        }
    }

    throw IllegalArgumentException("$description была изменена или потеряна")
}

private fun requireStringArrayContainsAll(
    expected: JSONArray?,
    actual: JSONArray?,
    description: String
) {
    val actualValues = buildSet {
        if (actual != null) {
            for (index in 0 until actual.length()) {
                actual.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
    if (expected != null) {
        for (index in 0 until expected.length()) {
            val value = expected.optString(index)
            require(value.isBlank() || value in actualValues) { "$description был сокращён" }
        }
    }
}

private fun requireDeviceBindingNotRegressed(
    expectedDeviceId: String,
    actualDeviceId: String,
    actualDevices: JSONObject,
    description: String
) {
    if (expectedDeviceId.isNotBlank()) {
        require(actualDeviceId == expectedDeviceId) { "$description была удалена или заменена" }
        return
    }
    require(actualDeviceId.isBlank() || actualDevices.has(actualDeviceId)) {
        "$description ссылается на отсутствующее устройство"
    }
}

internal fun validatePreservedServerState(beforeJson: String, afterJson: String) {
    val before = JSONObject(beforeJson)
    val after = JSONObject(afterJson)
    validatePasswordsDbStructure(after)
    val beforePasswords = before.optJSONObject("passwords") ?: JSONObject()
    val afterPasswords = after.optJSONObject("passwords") ?: JSONObject()
    val afterDevices = after.optJSONObject("devices") ?: JSONObject()
    val safeExpiryCutoff = System.currentTimeMillis() / 1000L + 300L
    beforePasswords.keys().forEach { password ->
        val oldEntry = beforePasswords.optJSONObject(password) ?: return@forEach
        if (!passwordShouldRemainAfterImport(oldEntry, safeExpiryCutoff)) return@forEach
        val newEntry = afterPasswords.optJSONObject(password)
            ?: throw IllegalStateException("после обновления пропал действующий клиент ${password.take(4)}…")
        requireDeviceBindingNotRegressed(
            expectedDeviceId = oldEntry.optString("device_id", ""),
            actualDeviceId = newEntry.optString("device_id", ""),
            actualDevices = afterDevices,
            description = "после обновления привязка клиента ${password.take(4)}…"
        )
        listOf("label", "vk_hash", "ports").forEach { field ->
            require(oldEntry.optString(field, "") == newEntry.optString(field, "")) {
                "после обновления изменилось поле $field у клиента ${password.take(4)}…"
            }
        }
        require(oldEntry.optLong("expires_at", 0) == newEntry.optLong("expires_at", 0)) {
            "после обновления изменился срок клиента ${password.take(4)}…"
        }
        require(oldEntry.optLong("purge_after", 0) == newEntry.optLong("purge_after", 0)) {
            "после обновления изменился срок хранения записи клиента ${password.take(4)}…"
        }
        require(oldEntry.optBoolean("is_deactivated", false) == newEntry.optBoolean("is_deactivated", false)) {
            "после обновления изменился статус клиента ${password.take(4)}…"
        }
        listOf("down_bytes", "up_bytes").forEach { field ->
            requireCounterNotRegressed(
                expected = oldEntry.optLong(field, 0L),
                actual = newEntry.optLong(field, 0L),
                description = "после обновления поле $field у клиента ${password.take(4)}…"
            )
        }
        requireTrafficHistoryNotRegressed(
            expected = oldEntry.optJSONArray("traffic"),
            actual = newEntry.optJSONArray("traffic"),
            description = "после обновления история traffic у клиента ${password.take(4)}…"
        )
        requireBindHistoryNotRegressed(
            expected = oldEntry.optJSONArray("bind_history"),
            actual = newEntry.optJSONArray("bind_history"),
            description = "после обновления история bind_history у клиента ${password.take(4)}…"
        )
        require(
            oldEntry.optJSONObject("traffic_imports")?.toString().orEmpty() ==
                newEntry.optJSONObject("traffic_imports")?.toString().orEmpty()
        ) {
            "после обновления изменилась история учёта перенесённого трафика клиента ${password.take(4)}…"
        }
        val deviceId = oldEntry.optString("device_id", "")
        if (deviceId.isNotBlank()) {
            require(afterDevices.has(deviceId)) { "после обновления пропала привязка устройства клиента ${password.take(4)}…" }
        }
    }
    listOf("admin_down_bytes", "admin_up_bytes").forEach { field ->
        requireCounterNotRegressed(
            expected = before.optLong(field, 0L),
            actual = after.optLong(field, 0L),
            description = "после обновления поле $field владельца"
        )
    }
    requireTrafficHistoryNotRegressed(
        expected = before.optJSONArray("admin_traffic"),
        actual = after.optJSONArray("admin_traffic"),
        description = "после обновления история трафика владельца"
    )
}

private fun passwordShouldRemainAfterImport(entry: JSONObject, safeExpiryCutoff: Long): Boolean {
    val expiresAt = entry.optLong("expires_at", 0L)
    if (expiresAt == 0L) return true
    val purgeAfter = entry.optLong("purge_after", 0L)
    return maxOf(expiresAt, purgeAfter) > safeExpiryCutoff
}

internal fun redactRemoteDiagnostics(text: String): String {
    var result = text.replace(Regex("""(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"""), "[приватный ключ скрыт]")
    result = result.replace(Regex("""(?i)([a-z][a-z0-9+.-]*://)[^\s/@]+:[^\s/@]+@"""), "$1[скрыто]@")
    result = result.replace(Regex("""(?im)^([^\n]*(?:password|passwd|token|secret|private[ _-]?key|preshared[ _-]?key|authorization)[^\n:=]*[:=])[^\n]*$"""), "$1 [скрыто]")
    result = result.replace(Regex("""(?m)^([A-Z][A-Z0-9_]*_B64=).*$"""), "$1[скрыто]")
    return result
}

internal fun friendlyAdminSudoError(raw: String): String? {
    val value = raw.lowercase()
    return when {
        "incorrect password" in value ||
            "sorry, try again" in value ||
            "a password is required" in value ||
            "no password was provided" in value ||
            "authentication failure" in value ->
            "Не удалось подтвердить права sudo. Проверьте SSH-пароль или пароль sudo в «Деплой → SSH»."
        "is not in the sudoers file" in value ||
            "is not allowed to execute" in value ||
            "may not run sudo" in value ->
            "У SSH-пользователя нет разрешения выполнять команды управления через sudo."
        "root privileges required" in value || "sudo not found" in value ->
            "Для управления сервером нужны root-права или установленный sudo."
        else -> null
    }
}

internal const val VPN_DNS_PROFILE_ID = "profile"
internal const val VPN_DNS_CUSTOM_ID = "custom"
internal const val MAX_CUSTOM_VPN_DNS_SERVERS = 2
// qThrone: only the ids of WDTT Plus's vpnDnsPresets are needed to validate admin_profile.
private val VPN_DNS_PRESET_IDS = setOf("cloudflare", "google", "quad9", "adguard", "xbox", "comss")

internal fun normalizeVpnDnsSelectionId(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return when {
        normalized == VPN_DNS_PROFILE_ID -> VPN_DNS_PROFILE_ID
        normalized == VPN_DNS_CUSTOM_ID -> VPN_DNS_CUSTOM_ID
        normalized in VPN_DNS_PRESET_IDS -> normalized
        else -> VPN_DNS_PROFILE_ID
    }
}

internal fun normalizeCustomVpnDnsServers(raw: String): List<String> {
    val values = raw
        .split(Regex("[,;\\s]+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
    require(values.isNotEmpty()) { "Укажите хотя бы один IPv4-адрес DNS." }
    require(values.size <= MAX_CUSTOM_VPN_DNS_SERVERS) {
        "Можно указать не больше двух DNS-серверов."
    }
    return values.map(::normalizeVpnDnsIpv4).distinct().also { normalized ->
        require(normalized.isNotEmpty()) { "Укажите хотя бы один IPv4-адрес DNS." }
    }
}

internal fun decodeStoredCustomVpnDnsServers(raw: String): List<String> =
    runCatching { normalizeCustomVpnDnsServers(raw) }.getOrDefault(emptyList())

private fun normalizeVpnDnsIpv4(value: String): String {
    val parts = value.split('.')
    require(parts.size == 4) { "Некорректный IPv4-адрес DNS: $value" }
    val octets = parts.map { part ->
        require(part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit)) {
            "Некорректный IPv4-адрес DNS: $value"
        }
        require(part.length == 1 || part.first() != '0') {
            "Некорректный IPv4-адрес DNS: $value"
        }
        part.toInt().also { octet ->
            require(octet in 0..255) { "Некорректный IPv4-адрес DNS: $value" }
        }
    }
    require(octets != listOf(0, 0, 0, 0)) { "Адрес 0.0.0.0 нельзя использовать как DNS." }
    require(octets.first() != 127) { "Локальный адрес 127.0.0.0/8 нельзя использовать как DNS VPN." }
    require(octets.first() !in 224..239) { "Multicast-адрес нельзя использовать как DNS." }
    require(octets != listOf(255, 255, 255, 255)) { "Широковещательный адрес нельзя использовать как DNS." }
    return octets.joinToString(".")
}
