package app.rawline.mobile.database.preference

import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row of the desktop's `settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)` table (SettingsRepo.cpp), the value
 * in the desktop encoding: "true"/"false", decimal integers, compact JSON arrays, raw text. The table lives in
 * SagerDatabase next to the profiles, as in the desktop's single database.
 */
@Entity(tableName = "settings")
class SettingEntry(
    @PrimaryKey var key: String = "",
    var value: String = "",
) {

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `settings` ORDER BY `key`")
        fun all(): List<SettingEntry>

        @Query("SELECT `value` FROM `settings` WHERE `key` = :key")
        operator fun get(key: String): String?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(entry: SettingEntry)

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun putAll(entries: List<SettingEntry>)

        @Query("DELETE FROM `settings` WHERE `key` = :key")
        fun delete(key: String): Int

        @Query("DELETE FROM `settings`")
        fun reset(): Int
    }
}
