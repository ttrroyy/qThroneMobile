package app.qthrone.mobile.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface RouteDao {

    @Query("SELECT * FROM route_profiles ORDER BY id")
    fun allProfiles(): List<RouteProfileEntity>

    @Query("SELECT id FROM route_profiles ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM route_profiles WHERE id = :id")
    fun getProfile(id: Long): RouteProfileEntity?

    @Query("SELECT COUNT(*) FROM route_profiles")
    fun count(): Long

    /** The profile's dns_json while custom_dns is on; null when it is off or the profile does not exist. */
    @Query("SELECT dns_json FROM route_profiles WHERE id = :id AND custom_dns != 0")
    fun customDnsJson(id: Long): String?

    @Query("SELECT * FROM route_rules ORDER BY route_profile_id, rule_order")
    fun allRules(): List<RouteRuleEntity>

    @Query("SELECT * FROM route_rules WHERE route_profile_id = :profileId ORDER BY rule_order")
    fun rulesOf(profileId: Long): List<RouteRuleEntity>

    @Insert
    fun insertProfile(profile: RouteProfileEntity): Long

    @Update
    fun updateProfile(profile: RouteProfileEntity): Int

    @Query("DELETE FROM route_profiles WHERE id = :id")
    fun deleteProfile(id: Long): Int

    @Query("DELETE FROM route_rules WHERE route_profile_id = :profileId")
    fun deleteRules(profileId: Long)

    @Insert
    fun insertRules(rules: List<RouteRuleEntity>)

    /** Every profile; their rules go by the foreign key cascade. */
    @Query("DELETE FROM route_profiles")
    fun reset()
}
