package app.rawline.mobile.bg

/** Native probe subprocesses cannot protect their sockets; exclude the app UID from every TUN. */
internal object TunApplicationPolicy {
    data class Packages(val included: List<String>, val excluded: List<String>)
    fun resolve(app: String, included: List<String>, excluded: List<String>): Packages {
        if(included.isEmpty()) return Packages(emptyList(), (excluded+app).distinct())
        val effective=included.filter { it!=app }.distinct()
        require(effective.isNotEmpty()) { "Select at least one application other than Rawline" }
        return Packages(effective,emptyList())
    }
}
