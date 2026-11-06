package app.rawline.mobile.ui.json

import android.os.Parcelable
import app.rawline.mobile.ui.json.engine.JsonType
import app.rawline.mobile.ui.json.engine.SchemaValidator
import kotlinx.parcelize.Parcelize

/** Per-editor schema relaxations (the desktop's AllowExtraType plus what Rawline's own stored forms need). */
@Parcelize
data class JsonRelaxations(
    /** Keys Rawline writes that the sing-box schema does not know, by instance pointer ("" = root, "/tls"). */
    val extraKeys: List<ExtraKey> = emptyList(),
    /** An extra accepted type for a property name anywhere in the document (desktop AllowExtraType). */
    val extraTypes: List<ExtraType> = emptyList(),
    /** Rawline type names of the root object mapped to the schema's (`openvpn` -> `openvpn-client`). */
    val typeAliases: Map<String, String> = emptyMap(),
    val unknownKeysAsWarnings: Boolean = false,
) : Parcelable {

    @Parcelize
    data class ExtraKey(val pointer: String, val key: String) : Parcelable

    @Parcelize
    data class ExtraType(val key: String, val type: JsonType) : Parcelable

    fun applyTo(validator: SchemaValidator) {
        for (extra in extraKeys) validator.allowExtraKey(extra.pointer, extra.key)
        for (extra in extraTypes) validator.allowExtraType(extra.key, extra.type)
        for ((type, schemaType) in typeAliases) validator.aliasType(type, schemaType)
        validator.unknownKeysAsWarnings = unknownKeysAsWarnings
    }
}
