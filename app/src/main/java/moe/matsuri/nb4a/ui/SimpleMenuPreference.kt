/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import app.rawline.mobile.ui.MobileSelector

/** All profile and settings selectors share the same rounded, anchored menu. */
open class SimpleMenuPreference @JvmOverloads constructor(
    context: Context?, attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : ListPreference(context!!,attrs,defStyleAttr,defStyleRes) {
    var optionEnabled: (Int) -> Boolean = { true }
    protected var anchor: View? = null
    init { widgetLayoutResource = 0 }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<View>(app.rawline.mobile.R.id.spinner)?.visibility = View.GONE
        anchor = holder.itemView
        MobileSelector.bind(this,holder.itemView)
    }
    override fun onClick() {
        val row = anchor ?: return
        val labels = entries ?: return
        val values = entryValues ?: return
        MobileSelector.showAt(row,labels.toList(),findIndexOfValue(value),optionEnabled) { index ->
            val next = values[index].toString()
            if(callChangeListener(next)) value = next
        }
    }
}
