/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package app.rawline.mobile

import app.rawline.mobile.utils.ShortcutAuthorization
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.rawline.mobile.aidl.ISagerNetService
import app.rawline.mobile.bg.BaseService
import app.rawline.mobile.bg.SagerConnection
import app.rawline.mobile.database.DataStore

@Suppress("DEPRECATION")
class QuickToggleShortcut : Activity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
    private var profileId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShortcutAuthorization.run(this, intent) {
            if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
                setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this,
                    ShortcutInfoCompat.Builder(this, "toggle")
                        .setIntent(Intent(this,
                            QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN).let { ShortcutAuthorization.sign(this, it) })
                        .setIcon(IconCompat.createWithResource(this,
                            R.drawable.ic_qu_shadowsocks_launcher))
                        .setShortLabel(getString(R.string.quick_toggle))
                        .build()))
                finish()
            } else {
                profileId = intent.getLongExtra("profile", -1L)
                connection.connect(this, this)
                if (Build.VERSION.SDK_INT >= 25) {
                    getSystemService<ShortcutManager>()!!.reportShortcutUsed(if (profileId >= 0) "shortcut-profile-$profileId" else "toggle")
                }
            }
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        val state = BaseService.State.values()[service.state]
        when {
            state.canStop -> {
                if (profileId == DataStore.selectedProxy || profileId == -1L) {
                    SagerNet.stopService()
                } else {
                    DataStore.selectedProxy = profileId
                    SagerNet.reloadService()
                }
            }
            state == BaseService.State.Stopped -> {
                if (profileId >= 0L) DataStore.selectedProxy = profileId
                SagerNet.startService()
            }
        }
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }
}
