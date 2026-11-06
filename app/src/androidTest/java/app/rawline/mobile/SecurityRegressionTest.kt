package app.rawline.mobile

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.ui.QuickDisableShortcut
import app.rawline.mobile.utils.ShortcutAuthorization
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun shortcutRequiresSignatureAndBindsTheCommandAndProfile() {
        val intent = Intent(context, QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN).putExtra("profile", 123L)
        assertFalse(ShortcutAuthorization.isAuthorized(context, intent))
        ShortcutAuthorization.sign(context, intent)
        assertTrue(ShortcutAuthorization.isAuthorized(context, intent))
        assertFalse(ShortcutAuthorization.isAuthorized(context, Intent(intent).putExtra("profile", 124L)))
        assertFalse(ShortcutAuthorization.isAuthorized(context, Intent(intent).setClass(context, QuickDisableShortcut::class.java)))
        assertFalse(ShortcutAuthorization.isAuthorized(context, Intent(intent).setAction(Intent.ACTION_CREATE_SHORTCUT)))
    }

    private fun seed(name: String, version: Int) {
        context.deleteDatabase(name)
        val schema = instrumentation.context.assets.open("app.rawline.mobile.database.SagerDatabase/$version.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
            db.execSQL("INSERT INTO settings (`key`, `value`) VALUES ('security-test', 'preserved')")
            db.version = version
        }
    }

    private fun assertPreserved(name: String) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM settings WHERE `key`='security-test'", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals("preserved", rows.getString(0))
            }
        }
    }

    @Test fun supportedMigrationsPreserveSettings() {
        for (version in 12..15) {
            val name = "security-migration-$version.db"
            seed(name, version)
            SagerDatabase.openProfileDatabase(context, name).close()
            assertPreserved(name)
            context.deleteDatabase(name)
        }
    }

    @Test fun missingMigrationAndDowngradePreserveDatabase() {
        for (version in listOf(7, 16)) {
            val name = "security-unsupported-$version.db"
            seed(name, 15)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = version }
            try {
                SagerDatabase.openProfileDatabase(context, name).close()
                fail("Unsupported migration should fail")
            } catch (_: IllegalStateException) { }
            assertPreserved(name)
            context.deleteDatabase(name)
        }
    }

    @Test fun damagedDatabaseIsNotDeletedOrReplaced() {
        val name = "security-damaged.db"
        val file = context.getDatabasePath(name)
        val bytes = "damaged profile database".toByteArray()
        file.writeBytes(bytes)
        try {
            SagerDatabase.openProfileDatabase(context, name).close()
            fail("Damaged database should fail")
        } catch (_: IllegalStateException) { }
        assertArrayEquals(bytes, file.readBytes())
        context.deleteDatabase(name)
    }
}
