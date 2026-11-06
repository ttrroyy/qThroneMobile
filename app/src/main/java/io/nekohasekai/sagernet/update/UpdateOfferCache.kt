package app.rawline.mobile.update

import org.json.JSONArray
import org.json.JSONObject

/** A cached offer is device-local, tied to the installed build and the chosen release channel. */
internal object UpdateOfferCache {
    data class Snapshot(
        val installedCode: Long,
        val allowBeta: Boolean,
        val lastAttempt: Long,
        val lastSucceeded: Boolean,
        val offer: UpdateChecker.Offer?,
    )

    fun encode(snapshot: Snapshot): String = JSONObject().apply {
        put("installedCode", snapshot.installedCode)
        put("allowBeta", snapshot.allowBeta)
        put("lastAttempt", snapshot.lastAttempt)
        put("lastSucceeded", snapshot.lastSucceeded)
        snapshot.offer?.let { offer ->
            put("offer", JSONObject().apply {
                put("versionName", offer.versionName)
                put("versionCode", offer.versionCode)
                put("abi", offer.abi)
                put("browserReason", offer.browserReason)
                put("release", JSONObject().apply {
                    put("tag", offer.release.tag)
                    put("htmlUrl", offer.release.htmlUrl)
                    put("body", offer.release.body)
                    put("prerelease", offer.release.prerelease)
                    put("assets", JSONArray().apply { offer.release.assets.forEach { put(assetJson(it)) } })
                })
                offer.apk?.let { put("apk", assetJson(it)) }
                offer.expected?.let { expected ->
                    put("expected", JSONObject().put("name", expected.name).put("size", expected.size).put("sha256", expected.sha256))
                }
            })
        }
    }.toString()

    fun decode(text: String, installedCode: Long, allowBeta: Boolean): Snapshot? = runCatching {
        val json = JSONObject(text)
        if (json.getLong("installedCode") != installedCode || json.getBoolean("allowBeta") != allowBeta) return@runCatching null
        val offer = json.optJSONObject("offer")?.let { cached ->
            val release = cached.getJSONObject("release")
            val assets = release.getJSONArray("assets")
            UpdateChecker.Offer(
                UpdateChecker.Release(
                    release.getString("tag"), release.getString("htmlUrl"), release.getString("body"),
                    release.getBoolean("prerelease"), (0 until assets.length()).map { assetOf(assets.getJSONObject(it)) },
                ),
                cached.getString("versionName"), cached.getLong("versionCode"),
                cached.optString("abi").takeIf { it.isNotEmpty() },
                cached.optJSONObject("apk")?.let(::assetOf),
                cached.optJSONObject("expected")?.let {
                    UpdateChecker.ManifestAsset(it.getString("name"), it.getLong("size"), it.getString("sha256"))
                },
                cached.optString("browserReason").takeIf { it.isNotEmpty() },
            )
        }
        Snapshot(installedCode, allowBeta, json.getLong("lastAttempt"), json.getBoolean("lastSucceeded"), offer)
    }.getOrNull()

    private fun assetJson(asset: UpdateChecker.Asset) = JSONObject()
        .put("name", asset.name).put("size", asset.size).put("url", asset.url)

    private fun assetOf(json: JSONObject) = UpdateChecker.Asset(json.getString("name"), json.getLong("size"), json.getString("url"))
}
