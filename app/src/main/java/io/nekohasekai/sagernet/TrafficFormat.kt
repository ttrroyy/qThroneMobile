package app.rawline.mobile

import java.text.NumberFormat
import java.util.Locale

object TrafficFormat {
    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val units = if (locale.language == "ru") arrayOf("Б","КБ","МБ","ГБ","ТБ","ПБ","ЭБ") else arrayOf("B","KB","MB","GB","TB","PB","EB")
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2; isGroupingUsed = false }.format(value)
        return "$number ${units[unit]}"
    }
}
