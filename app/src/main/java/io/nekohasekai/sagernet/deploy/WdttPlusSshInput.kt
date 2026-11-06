package app.rawline.mobile.deploy

/** WDTT Plus v20's stdin framing keeps sudo's optional password read separate from admin JSON. */
internal class RootStdinPlan(val command: String, val input: String)

private const val FRAME_START = "__WDTT_ADMIN_STDIN_V1__"
private const val FRAME_END = "__WDTT_ADMIN_STDIN_END_V1__"

internal fun framedStdinCommand(command: String, shell: String = "bash"): String {
    require(shell in setOf("bash", "sh"))
    val start = DeployProtocol.quote(FRAME_START)
    val end = DeployProtocol.quote(FRAME_END)
    val invalid = "echo 'error: invalid WDTT admin stdin frame' >&2; exit 97"
    return "IFS= read -r wdtt_frame_first || { $invalid; }; " +
        "IFS= read -r wdtt_frame_second || { $invalid; }; " +
        "if [ \"\$wdtt_frame_first\" = $start ] && [ \"\$wdtt_frame_second\" = $end ]; then :; " +
        "elif [ \"\$wdtt_frame_second\" = $start ]; then " +
        "IFS= read -r wdtt_frame_third || { $invalid; }; " +
        "[ \"\$wdtt_frame_third\" = $end ] || { $invalid; }; " +
        "else $invalid; fi; exec $shell -c ${DeployProtocol.quote(command)}"
}

internal fun rootStdinPlan(command: String, sudoPassword: String, payload: String, shell: String = "bash"): RootStdinPlan {
    require(sudoPassword.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Пароль sudo не должен содержать перенос строки" }
    val forward = DeployProtocol.quote(framedStdinCommand(command, shell))
    val root = "if [ \"\$(id -u)\" = 0 ]; then exec $shell -c $forward; " +
        "elif command -v sudo >/dev/null 2>&1; then exec sudo -S -p '' $shell -c $forward; " +
        "else echo 'error: root privileges required and sudo not found'; exit 1; fi"
    return RootStdinPlan(root,"$sudoPassword\n$FRAME_START\n$FRAME_END\n$payload")
}

/** Long scripts travel through stdin, preserving the original command's privilege and shell selection. */
internal fun deployStdinPlan(command: String, sudoPassword: String): RootStdinPlan? =
    if ("sudo -S" in command) rootStdinPlan("exec sh -s",sudoPassword,"$command\n",shell="sh") else null
