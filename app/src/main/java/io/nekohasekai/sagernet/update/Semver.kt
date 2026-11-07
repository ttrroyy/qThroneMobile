package app.rawline.mobile.update

/** Semantic-version precedence (semver.org §11) of release tags such as `v1.7.0` and `1.7.0-pre.12`. */
object Semver {

    class Version(val core: List<Long>, val pre: List<String>)

    /** `[v]MAJOR[.MINOR[.PATCH]][-PRERELEASE][+BUILD]`, or null; build metadata is ignored. */
    fun parse(text: String): Version? {
        val s = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = s.substringBefore('-').split('.')
        if (core.size !in 1..3) return null
        val numbers = core.map { part -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return null }
        val pre = if ('-' in s) s.substringAfter('-').split('.') else emptyList()
        if (pre.any { it.isEmpty() }) return null
        return Version(numbers + List(3 - numbers.size) { 0L }, pre)
    }

    /** Rawline's release tags only: the repository also keeps releases of the earlier app, numbered higher. */
    const val TAG_PREFIX = "rawline-"
    fun ofTag(tag: String): Version? = if (tag.startsWith(TAG_PREFIX)) parse(tag.removePrefix(TAG_PREFIX)) else null

    fun compare(a: Version, b: Version): Int {
        for (i in 0 until 3) {
            val c = a.core[i].compareTo(b.core[i])
            if (c != 0) return c
        }
        // A release outranks its pre-releases.
        if (a.pre.isEmpty() || b.pre.isEmpty()) return b.pre.size.coerceAtMost(1) - a.pre.size.coerceAtMost(1)
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val c = compareIdentifier(a.pre[i], b.pre[i])
            if (c != 0) return c
        }
        return a.pre.size.compareTo(b.pre.size)
    }

    /** Positive when [a] is newer than [b]; null when either is not a version. */
    fun compare(a: String, b: String): Int? {
        return compare(parse(a) ?: return null, parse(b) ?: return null)
    }

    private fun compareIdentifier(a: String, b: String): Int {
        val na = a.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
        val nb = b.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> a.compareTo(b)
        }
    }
}
