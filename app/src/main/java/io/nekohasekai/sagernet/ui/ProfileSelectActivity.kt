package app.rawline.mobile.ui

import android.content.Intent
import android.os.Bundle
import app.rawline.mobile.R
import app.rawline.mobile.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                // both callers fill fixed-server slots (front / landing proxy, chain hops)
                ConfigurationFragment.forSelection(selected, R.string.select_profile, hideAutoSelectors = true)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}