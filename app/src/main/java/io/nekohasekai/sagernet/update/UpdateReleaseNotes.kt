package app.rawline.mobile.update

import org.json.JSONObject

/** Verification metadata travels in the GitHub release body, without a downloadable sidecar. */
object UpdateReleaseNotes {
    const val START = "<!-- rawline-update\n"
    const val END = "\n-->"
    private const val MAX_LENGTH = 256 * 1024

    fun containsMetadata(body: String) = body.contains(START)

    fun metadata(body: String): JSONObject? {
        val start = body.indexOf(START)
        if (start < 0) return null
        require(body.indexOf(START, start + START.length) < 0) { "Duplicate update metadata" }
        val content = start + START.length
        val end = body.indexOf(END, content)
        require(end >= content && end - content <= MAX_LENGTH) { "Invalid update metadata" }
        return JSONObject(body.substring(content, end))
    }

    fun visible(body: String): String {
        val start = body.indexOf(START)
        if (start < 0) return body.trim()
        val end = body.indexOf(END, start + START.length)
        return (body.substring(0, start) + if (end < 0) "" else body.substring(end + END.length)).trim()
    }
}
