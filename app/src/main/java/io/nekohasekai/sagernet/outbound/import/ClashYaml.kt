package app.rawline.mobile.outbound.import

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonNull
import app.rawline.mobile.outbound.json.JsonObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.math.BigDecimal
import java.math.BigInteger

/** The Clash YAML document reduced to its `proxies` sequence, each entry as a [JsonObject] tree. */
object ClashYaml {

    /**
     * Parser::clash (SubscriptionParser.cpp:415-442): null when the document is not a mapping with a `proxies`
     * sequence; the entries are returned untyped, [ClashProxy] applies the desktop's field conversions. YAML
     * errors propagate as exceptions (the caller reports them like the desktop's "YAML Exception" warning).
     */
    @JvmStatic
    fun proxies(text: String): List<JsonObject>? {
        val options = LoaderOptions().apply {
            nestingDepthLimit = 50
            maxAliasesForCollections = 50
            codePointLimit = 4 * 1024 * 1024
            setAllowRecursiveKeys(false)
        }
        val root = Yaml(SafeConstructor(options)).load<Any?>(sanitize(text)) as? Map<*, *> ?: return null
        val proxies = root["proxies"] as? List<*> ?: return null
        var remaining=200_000
        fun convert(value: Any?, depth:Int=0): Any {
            if(remaining--<=0 || depth>50) throw YAMLException("Clash YAML expanded structure is too large or recursive")
            return when(value) {
                null -> JsonNull
                is Map<*, *> -> JsonObject().also { obj -> for((k,v) in value) obj[k.toString()]=convert(v,depth+1) }
                is List<*> -> JsonArray().also { arr -> for(v in value) arr.add(convert(v,depth+1)) }
                is String, is Boolean, is Long, is Int, is Short, is Byte, is Double, is Float, is BigInteger, is BigDecimal -> value
                else -> value.toString()
            }
        }
        return proxies.map { (convert(it) as? JsonObject) ?: JsonObject() }
    }

    // sanitizeClashYaml (SubscriptionParser.cpp:242-247): NULs and a leading BOM confuse the YAML reader.
    private fun sanitize(text: String): String {
        var s = text.replace("\u0000", "")
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s
    }

}
