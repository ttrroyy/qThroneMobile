package app.qthrone.mobile.ui

import java.util.Calendar
import java.util.TimeZone

/** Four local-calendar seasons, with stable saved keys for retained artwork. */
object SeasonalPolicy {
    val manualThemes=setOf("halloween","newyear","easter","kupala")
    fun automatic(month:Int,day:Int):String = when(month) {
        in 3..5 -> "easter"
        in 6..8 -> "kupala"
        in 9..11 -> "halloween"
        else -> "newyear"
    }
    fun retainedChoice(choice:String):String =
        if(choice in manualThemes || choice=="auto" || choice=="none") choice else "auto"
    fun resolve(choice:String,now:Long=System.currentTimeMillis(),zone:TimeZone=TimeZone.getDefault()):String {
        if(choice!="auto") return if(choice in manualThemes) choice else "none"
        val local=Calendar.getInstance(zone).apply { timeInMillis=now }
        return automatic(local.get(Calendar.MONTH)+1,local.get(Calendar.DAY_OF_MONTH))
    }
    fun migratedChoice(choice:String,migrated:Boolean)=if(migrated) choice else "auto"
}
