package app.qthrone.mobile.ui

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class SeasonalPolicyTest {
    @Test fun autoIsForcedOnlyByTheOneTimeMigration() {
        for(choice in SeasonalPolicy.manualThemes+setOf("none","auto")) {
            assertEquals("auto",SeasonalPolicy.migratedChoice(choice,false))
            assertEquals(choice,SeasonalPolicy.migratedChoice(choice,true))
        }
    }
    @Test fun everyDayUsesExactlyOneOfTheFourSeasonsIncludingLeapYears() {
        for(year in listOf(2027,2028)) {
            val date=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear();set(year,0,1) }
            var days=0
            while(date.get(Calendar.YEAR)==year) {
                val month=date.get(Calendar.MONTH)+1
                val expected=when(month) { 3,4,5 -> "easter";6,7,8 -> "kupala";9,10,11 -> "halloween";else -> "newyear" }
                assertEquals(expected,SeasonalPolicy.resolve("auto",date.timeInMillis,date.timeZone))
                date.add(Calendar.DAY_OF_MONTH,1);days++
            }
            assertEquals(if(year==2028) 366 else 365,days)
        }
        assertEquals(setOf("halloween","newyear","easter","kupala"),SeasonalPolicy.manualThemes)
    }
    @Test fun calendarChangesAtTheUsersLocalMidnight() {
        val instant=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear();set(2026,7,31,12,0,0) }.timeInMillis
        assertEquals("halloween",SeasonalPolicy.resolve("auto",instant,TimeZone.getTimeZone("Pacific/Kiritimati")))
        assertEquals("kupala",SeasonalPolicy.resolve("auto",instant,TimeZone.getTimeZone("Pacific/Pago_Pago")))
    }
    @Test fun retainedKeysAndDisabledChoiceSurviveWhileUnknownChoicesUseAuto() {
        for(choice in SeasonalPolicy.manualThemes+setOf("auto","none")) assertEquals(choice,SeasonalPolicy.retainedChoice(choice))
        assertEquals("auto",SeasonalPolicy.retainedChoice("unsupported"))
        assertEquals("none",SeasonalPolicy.resolve("unsupported"))
        assertEquals("none",SeasonalPolicy.resolve("none"))
    }
}
