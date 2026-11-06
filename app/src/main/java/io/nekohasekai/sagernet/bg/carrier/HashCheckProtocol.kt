package app.rawline.mobile.bg.carrier

internal object HashCheckProtocol {
    data class Update(val slot: Int, val message: String, val finished: Boolean)
    fun parse(line: String, hashes: List<Pair<Int,String>>): Update? {
        val start=line.startsWith("HASH_CHECK_START|")
        if(!start && !line.startsWith("HASH_CHECK|")) return null
        val parts=line.split('|',limit=if(start) 3 else 5)
        if(parts.size!=(if(start) 3 else 5)) return null
        val entry=hashes.getOrNull((parts[1].toIntOrNull() ?: return null)-1) ?: return null
        if(parts[2]!=entry.second) return null
        if(start) return Update(entry.first,"Проверяется…",false)
        // Provider errors can contain credentials: never display native diagnostic text.
        return Update(entry.first,when(parts[3]) {
            "ok" -> "TURN доступен"
            "dead" -> "Звонок закрыт или хеш недействителен"
            "captcha" -> "Нужна капча VK"
            "limited" -> "VK ограничил частоту запросов"
            "network" -> "Не достучались по сети/DNS"
            "full" -> "Звонок переполнен"
            "blocked" -> "VK закрыл доступ к звонку"
            "auth_required" -> "Нужен вход в VK"
            else -> "Диагностика не удалась"
        },true)
    }
}
