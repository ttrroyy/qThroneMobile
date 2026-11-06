package app.rawline.mobile.outbound.types

/** stripVkUrlStatic of the original qWDTT and CSQTT apps: a VK call link becomes its hash. */
object VkCallHash {
    private val prefixes = listOf(
        "https://vk.com/call/join/", "http://vk.com/call/join/",
        "https://m.vk.com/call/join/", "http://m.vk.com/call/join/",
        "https://vk.ru/call/join/", "http://vk.ru/call/join/",
        "https://m.vk.ru/call/join/", "http://m.vk.ru/call/join/",
        "m.vk.com/call/join/", "vk.com/call/join/", "m.vk.ru/call/join/", "vk.ru/call/join/",
    )

    fun strip(input: String): String {
        var hash = input.trim()
        prefixes.firstOrNull { hash.startsWith(it, ignoreCase = true) }?.let { hash = hash.substring(it.length) }
        hash = hash.substringBefore('?').substringBefore('#')
        return hash.trimEnd('/')
    }
}
