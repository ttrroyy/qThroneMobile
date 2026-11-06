package app.rawline.mobile.deploy

/** CSQTT 2.5.0 Legacy DeployOperations.kt environment and installer result contract. */
internal object CsqttDeployPolicy {
    fun password(): String {
        val alphabet="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random=java.security.SecureRandom()
        return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
    fun environmentValue(value: String, docker: Boolean): String {
        val clean=value.replace('\n',' ').replace('\r',' ')
        return if(docker) clean else "\""+clean.replace("\\","\\\\").replace("\"","\\\"")+"\""
    }
    fun failure(exit: Int, output: String): String {
        val detail=output.lineSequence().map(String::trim).lastOrNull { it.startsWith("CSQTT_DEPLOY_ERROR|") }
            ?.split('|',limit=3)?.getOrNull(2)?.take(360).orEmpty()
        val message=when {
            output.lineSequence().any { it.trim()=="CSQTT_DEPLOY_ROLLBACK_FAILED" } || exit==31 ->
                "Установка не завершена, автоматический откат не удался. Проверьте сервер по SSH"
            output.lineSequence().any { it.trim()=="CSQTT_DEPLOY_ROLLED_BACK" } || exit==30 ->
                "Установка не завершена; прежняя установка восстановлена"
            exit==20 -> "Новый сервер не прошёл предзапусковую проверку; работающая установка не останавливалась"
            exit==2 -> "Установщик отклонил параметры развёртывания"
            else -> "Установщик CSQTT завершился с кодом $exit"
        }
        return if(detail.isEmpty()) message else "$message: $detail"
    }
}
