package app.rawline.mobile.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.aidl.ISagerNetService
import app.rawline.mobile.database.SagerDatabase
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val tileIcon by lazy { Icon.createWithResource(this, R.drawable.ic_rawline_tile) }
    private var tapPending = false

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = SagerDatabase.proxyDao.getById(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            label = null
            icon = tileIcon
            when (serviceState) {
                BaseService.State.Idle -> state = Tile.STATE_INACTIVE
                BaseService.State.Connecting -> state = Tile.STATE_ACTIVE
                BaseService.State.Connected -> {
                    label = profileName
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Stopping -> state = Tile.STATE_UNAVAILABLE
                BaseService.State.Stopped -> state = Tile.STATE_INACTIVE
            }
            label = label ?: getString(R.string.app_name)
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) tapPending =
            true else BaseService.State.values()[service.state].let { state ->
            when {
                state.canStop -> SagerNet.stopService()
                state == BaseService.State.Stopped -> SagerNet.startService()
            }
        }
    }
}
