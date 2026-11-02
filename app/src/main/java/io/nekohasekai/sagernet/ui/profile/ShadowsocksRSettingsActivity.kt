package app.qthrone.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.R
import app.qthrone.mobile.outbound.types.ShadowsocksR
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ShadowsocksRSettingsActivity : BindingSettingsActivity<ShadowsocksR>() {
    override fun createEntity()=ShadowsocksR()
    override val preferencesResource=R.xml.shadowsocksr_preferences
    init {
        pbm.text("name");pbm.text("server");pbm.int("serverPort")
        pbm.text("method");pbm.text("password");pbm.text("protocol");pbm.text("protocolParam")
        pbm.text("obfs");pbm.text("obfsParam")
    }
    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort");passwordSummary("password")
        for((key,values) in listOf("method" to ShadowsocksR.METHODS,"protocol" to ShadowsocksR.PROTOCOLS,"obfs" to ShadowsocksR.OBFS)) {
            findPreference<SimpleMenuPreference>(key)?.apply {
                entries=values.toTypedArray();entryValues=values.toTypedArray()
            }
        }
    }
}
