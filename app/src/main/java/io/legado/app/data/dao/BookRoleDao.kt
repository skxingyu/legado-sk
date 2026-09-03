package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding

@Dao
interface BookRoleDao {

    @Query("select * from book_roles where workKey = :workKey and enabled = 1 order by roleId")
    fun getRoles(workKey: String): List<BookRole>

    @Query("select * from book_roles where workKey = :workKey order by roleId")
    fun getAllRoles(workKey: String): List<BookRole>

    @Query("select * from book_roles where roleId = :roleId")
    fun getRole(roleId: Long): BookRole?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRole(role: BookRole): Long

    @Update
    fun updateRole(role: BookRole)

    @Delete
    fun deleteRole(role: BookRole)

    // SK 保留说明：按 workKey 整作品批量删除，当前无调用者。
    // 严禁在「删书/换源」路径接线——workKey = MD5(书名+作者) 是作品级稳定键，跨书源/重加保持，
    // 接线会清掉同一作品的 AI 朗读角色与用户 AiMultiVoiceDialog 手工配音配置（换源重加后丢失）。
    // 单条 deleteRole/deleteCastRole/deleteBinding 有活跃调用者，属正常功能路径。
    @Query("delete from book_roles where workKey = :workKey")
    fun deleteRoles(workKey: String)

    @Query("select * from book_tts_cast_roles where workKey = :workKey order by castRoleId")
    fun getCastRoles(workKey: String): List<BookTtsCastRole>

    @Query("select * from book_tts_cast_roles where castRoleId = :castRoleId")
    fun getCastRole(castRoleId: Long): BookTtsCastRole?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCastRole(role: BookTtsCastRole): Long

    @Update
    fun updateCastRole(role: BookTtsCastRole)

    @Delete
    fun deleteCastRole(role: BookTtsCastRole)

    // SK 保留说明：按 workKey 整作品批量删除，当前无调用者。
    // 严禁在「删书/换源」路径接线——workKey = MD5(书名+作者) 是作品级稳定键，跨书源/重加保持，
    // 接线会清掉同一作品的 AI 朗读角色与用户 AiMultiVoiceDialog 手工配音配置（换源重加后丢失）。
    // 单条 deleteRole/deleteCastRole/deleteBinding 有活跃调用者，属正常功能路径。
    @Query("delete from book_tts_cast_roles where workKey = :workKey")
    fun deleteCastRoles(workKey: String)

    @Query("select * from book_tts_voice_bindings where workKey = :workKey")
    fun getBindings(workKey: String): List<BookTtsVoiceBinding>

    @Query(
        "select * from book_tts_voice_bindings where workKey = :workKey " +
            "and targetType = :targetType and targetId = :targetId limit 1"
    )
    fun getBinding(workKey: String, targetType: String, targetId: Long): BookTtsVoiceBinding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBinding(binding: BookTtsVoiceBinding)

    @Query(
        "delete from book_tts_voice_bindings where workKey = :workKey " +
            "and targetType = :targetType and targetId = :targetId"
    )
    fun deleteBinding(workKey: String, targetType: String, targetId: Long)

    // SK 保留说明：按 workKey 整作品批量删除，当前无调用者。
    // 严禁在「删书/换源」路径接线——workKey = MD5(书名+作者) 是作品级稳定键，跨书源/重加保持，
    // 接线会清掉同一作品的 AI 朗读角色与用户 AiMultiVoiceDialog 手工配音配置（换源重加后丢失）。
    // 单条 deleteRole/deleteCastRole/deleteBinding 有活跃调用者，属正常功能路径。
    @Query("delete from book_tts_voice_bindings where workKey = :workKey")
    fun deleteBindings(workKey: String)
}
