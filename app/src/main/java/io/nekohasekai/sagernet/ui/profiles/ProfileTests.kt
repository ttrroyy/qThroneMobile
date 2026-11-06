package app.rawline.mobile.ui.profiles

import app.rawline.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.rawline.mobile.R
import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.ktx.snackbar
import app.rawline.mobile.ui.ConfigurationFragment
import app.rawline.mobile.ui.test.TestSessionClient

/** The test entry points of the profiles screen (desktop TestRunner scopes: group, selection, one profile, current). */
internal object ProfileTests {

    /** Url Test / Speedtest / Resolve out IP for the group, in list order. */
    fun startGroup(host: ConfigurationFragment, kind: Int, groupId: Long) {
        confirmSpeed(host, kind, many = true) { TestSessionClient.startGroup(kind, groupId) }
    }

    /** Selected profiles (or one); [groupId] lets the panel offer "Sort" afterwards. */
    fun startProfiles(host: ConfigurationFragment, kind: Int, ids: List<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        confirmSpeed(host, kind, many = ids.size > 1) { TestSessionClient.startProfiles(kind, ids, groupId) }
    }

    /** "Speedtest Current": the running instance, whatever profile it carries. */
    fun startCurrent(host: ConfigurationFragment) {
        report(host, TestSessionClient.startCurrent(TestSpec.KIND_SPEED))
    }

    /** Speed tests of more than one profile ask first: they move a lot of data. */
    private fun confirmSpeed(host: ConfigurationFragment, kind: Int, many: Boolean, start: () -> Boolean) {
        if (kind != TestSpec.KIND_SPEED || !many) return report(host, start())
        MobileAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.speed_test_confirm_title)
            .setMessage(R.string.speed_test_confirm_message)
            .setPositiveButton(R.string.profiles_test_start) { _, _ -> report(host, start()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun report(host: ConfigurationFragment, started: Boolean) {
        if (!started) host.snackbar(R.string.profiles_test_busy).show()
    }
}
