package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.json.JsonInput
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSelectorGroupsTest {
    @Test fun singleGroupProfilesKeepTracking() {
        val old = AutoSelector()
        old.parseFromJson(JsonInput.parseObject("""{"type":"autoselector","name":"a","gid":5}"""))
        assertEquals(listOf(5L), old.gids)
        val exported = old.exportToJson()
        assertEquals(5L, (exported["gid"] as Number).toLong())

        val multi = AutoSelector()
        multi.parseFromJson(JsonInput.parseObject("""{"type":"autoselector","name":"a","gid":5,"gids":[3,5,3,-1]}"""))
        multi.normalize()
        assertEquals(listOf(3L, 5L), multi.gids)
        val restored = AutoSelector()
        restored.parseFromJson(multi.exportToJson())
        assertEquals(listOf(3L, 5L), restored.gids)
    }
}
