package app.rawline.mobile.ui.json.engine

import java.io.InputStream

/**
 * The bundled sing-box schema (`assets/schema/sing-box.json`), loaded once per process (SchemaStore.cpp). A missing or
 * broken asset leaves the editors with syntax checking only.
 */
object SchemaStore {

    const val CONFIG = ""
    const val OUTBOUND = "#/\$defs/Outbound"
    const val ENDPOINT = "#/\$defs/Endpoint"

    const val ASSET = "schema/sing-box.json"

    // The core's stub files register these without the build tags, but the Android core cannot run them.
    private val UNSUPPORTED = mapOf(
        "Endpoint" to setOf("tailscale"),
        "DNSServer" to setOf("dhcp", "tailscale"),
        "Service" to setOf("derp"),
    )

    @Volatile
    private var loaded = false
    private var document: SchemaDocument? = null

    var loadError: String? = null
        private set

    fun get(open: () -> InputStream): SchemaDocument? {
        if (loaded) return document
        synchronized(this) {
            if (!loaded) {
                document = try {
                    open().use { parse(it.readBytes().toString(Charsets.UTF_8)) }
                } catch (e: Exception) {
                    loadError = e.toString()
                    null
                }
                loaded = true
            }
        }
        return document
    }

    fun parse(text: String): SchemaDocument? {
        val parsed = JsonTree.parse(text)
        val root = parsed.root
        if (root == null) {
            loadError = parsed.error
            return null
        }
        val plain = SchemaDocument.obj(JsonTree.toPlain(root))
        if (plain.isNullOrEmpty()) return null
        strip(plain)
        return SchemaDocument(plain)
    }

    /** A list of roots becomes a oneOf union (SchemaStore.cpp:37-43); no roots means no schema. */
    fun validator(schema: SchemaDocument, roots: List<String>): SchemaValidator? = when (roots.size) {
        0 -> null
        1 -> SchemaValidator.create(schema, roots[0])
        else -> SchemaValidator.createForNode(schema, mapOf("oneOf" to roots.map { mapOf("\$ref" to it) }))
    }

    @Suppress("UNCHECKED_CAST")
    private fun strip(root: Map<String, Any?>) {
        val defs = SchemaDocument.obj(root["\$defs"]) ?: return
        for ((name, types) in UNSUPPORTED) {
            val def = defs[name] as? MutableMap<String, Any?> ?: continue
            removeVariants(def, types)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeVariants(union: MutableMap<String, Any?>, types: Set<String>) {
        for (key in listOf("oneOf", "anyOf")) {
            val branches = union[key] as? MutableList<Any?> ?: continue
            branches.removeAll { branch ->
                val node = branch as? MutableMap<String, Any?> ?: return@removeAll false
                if (node.size == 1 && (node.containsKey("oneOf") || node.containsKey("anyOf"))) {
                    removeVariants(node, types)
                    false
                } else {
                    val type = SchemaDocument.obj(SchemaDocument.obj(node["properties"])?.get("type"))
                    (type?.get("const") as? String) in types
                }
            }
        }
    }
}
