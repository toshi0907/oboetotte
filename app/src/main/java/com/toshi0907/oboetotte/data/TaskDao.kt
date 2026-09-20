package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task): Long

    @Query("SELECT * FROM tasks ORDER BY isDone ASC, dueAt IS NULL ASC, dueAt ASC, id DESC")
    fun getAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: Long): Task?

    @Query("SELECT * FROM tasks WHERE dueAt IS NOT NULL AND isDone = 0")
    suspend fun getPendingWithDueDate(): List<Task>

    @Query(
        "SELECT * FROM tasks WHERE latitude IS NOT NULL AND longitude IS NOT NULL " +
            "AND radiusMeters IS NOT NULL AND isDone = 0"
    )
    suspend fun getPendingWithLocation(): List<Task>

    /**
     * WHERE句にisDone != :isDoneを含めることで、対象の行が実際にこの呼び出しで
     * 状態遷移した場合にのみ1を返す(既に同じ状態であれば0件更新・0を返す)。
     * [com.toshi0907.oboetotte.TaskCompletion.complete]が、同じタスクへ同時に
     * 呼ばれた複数の完了処理(例: 通知のみタスクの自動完了とアプリ内の手動完了が
     * ほぼ同時に発生した場合)のうち片方だけを実処理させ、繰り返しタスクの次回分が
     * 重複生成されないようにするために使う。
     */
    @Query("UPDATE tasks SET isDone = :isDone WHERE id = :taskId AND isDone != :isDone")
    suspend fun setDone(taskId: Long, isDone: Boolean): Int

    @Update
    suspend fun update(task: Task)

    @Query("UPDATE tasks SET listId = NULL WHERE listId = :listId")
    suspend fun clearListId(listId: Long)

    /**
     * [groupId]をattachmentGroupId()(=seriesId ?: id)として持つタスクの件数。
     * タスク削除時、同じ繰り返しシリーズの他のインスタンスがまだ残っているかどうかを判定し、
     * 共有中の添付ファイルを誤って削除しないために使う。
     */
    @Query("SELECT COUNT(*) FROM tasks WHERE seriesId = :groupId OR (seriesId IS NULL AND id = :groupId)")
    suspend fun countByAttachmentGroup(groupId: Long): Int

    @Delete
    suspend fun delete(task: Task)

    @Insert
    suspend fun insertAll(tasks: List<Task>)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
