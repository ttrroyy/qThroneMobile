package app.rawline.mobile.ui

import android.content.Intent
import android.os.Bundle
import app.rawline.mobile.Action
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.ktx.runOnMainDispatcher

class SwitchActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment.forSelection(null, R.string.action_switch)
            )
            .commitAllowingStateLoss()
    }

    /** A running service switches in place (a no-op for its own profile); a stopped one only changes the selection. */
    override fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profileId, true)
        }
        sendBroadcast(
            Intent(Action.SWITCH_PROFILE).setPackage(packageName).putExtra(Action.EXTRA_PROFILE_ID, profileId)
        )
        finish()
    }
}
