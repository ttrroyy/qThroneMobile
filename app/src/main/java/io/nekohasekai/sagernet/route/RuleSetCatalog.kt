package app.qthrone.mobile.route

import java.util.regex.Pattern

/** The name → .srs URL map of the routeprofiles `srslist.h` (the desktop's compiled-in ruleSetList). */
class RuleSetCatalog(entries: List<Pair<String, String>>) {
    private val map = LinkedHashMap<String, String>()

    init {
        for ((name, url) in entries) if (!map.containsKey(name)) map[name] = url
    }

    private val nameList: List<String> = ArrayList(map.keys)

    val size: Int get() = map.size

    fun urlOf(name: String): String? = map[name]

    fun names(): List<String> = nameList

    /** Case-insensitive: exact match, then prefix matches, then substring matches, each in list order. */
    fun search(query: String, limit: Int): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return map.entries.take(limit).map { it.key to it.value }
        val exact = ArrayList<Pair<String, String>>()
        val prefix = ArrayList<Pair<String, String>>()
        val contains = ArrayList<Pair<String, String>>()
        for ((name, url) in map) {
            when {
                name.equals(q, ignoreCase = true) -> exact.add(name to url)
                name.startsWith(q, ignoreCase = true) -> prefix.add(name to url)
                name.contains(q, ignoreCase = true) -> contains.add(name to url)
            }
        }
        return (exact + prefix + contains).take(limit)
    }

    companion object {
        private val ENTRY = Pattern.compile("\\{\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\}")

        /**
         * One matcher for the whole text: Regex.findAll makes a new matcher per match, and each one copies the
         * whole text natively on Android, about a gigabyte for the ~2000 entries (fatal on 32-bit devices).
         */
        fun parseSrsList(text: String): RuleSetCatalog {
            val matcher = ENTRY.matcher(text)
            val entries = ArrayList<Pair<String, String>>()
            while (matcher.find()) entries.add(matcher.group(1)!! to matcher.group(2)!!)
            return RuleSetCatalog(entries)
        }
    }
}
