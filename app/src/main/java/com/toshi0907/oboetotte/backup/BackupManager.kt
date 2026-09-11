package com.toshi0907.oboetotte.backup

import android.content.Context
import android.net.Uri
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskList
import java.io.IOException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

object BackupManager {
    private const val FORMAT_VERSION = 1

    suspend fun export(context: Context, uri: Uri) {
        val db = AppDatabase.getInstance(context)
        val lists = db.taskListDao().getAll().first()
        val tasks = db.taskDao().getAll().first()

        val json = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put(
                "lists",
                JSONArray(
                    lists.map { list ->
                        JSONObject().apply {
                            put("id", list.id)
                            put("name", list.name)
                        }
                    }
                )
            )
            put(
                "tasks",
                JSONArray(
                    tasks.map { task ->
                        JSONObject().apply {
                            put("id", task.id)
                            put("title", task.title)
                            put("isDone", task.isDone)
                            put("dueAt", task.dueAt ?: JSONObject.NULL)
                            put("listId", task.listId ?: JSONObject.NULL)
                            put("parentTaskId", task.parentTaskId ?: JSONObject.NULL)
                            put("repeatRule", task.repeatRule ?: JSONObject.NULL)
                            put("repeatDaysOfWeek", task.repeatDaysOfWeek ?: JSONObject.NULL)
                        }
                    }
                )
            )
        }

        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("出力先を開けませんでした")
        output.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    suspend fun import(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("ファイルを開けませんでした")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        val json = JSONObject(text)

        val listsJson = json.getJSONArray("lists")
        val lists = (0 until listsJson.length()).map { i ->
            val obj = listsJson.getJSONObject(i)
            TaskList(id = obj.getLong("id"), name = obj.getString("name"))
        }

        val tasksJson = json.getJSONArray("tasks")
        val tasks = (0 until tasksJson.length()).map { i ->
            val obj = tasksJson.getJSONObject(i)
            Task(
                id = obj.getLong("id"),
                title = obj.getString("title"),
                isDone = obj.getBoolean("isDone"),
                dueAt = if (obj.isNull("dueAt")) null else obj.getLong("dueAt"),
                listId = if (obj.isNull("listId")) null else obj.getLong("listId"),
                parentTaskId = if (obj.isNull("parentTaskId")) null else obj.getLong("parentTaskId"),
                repeatRule = if (obj.isNull("repeatRule")) null else obj.getString("repeatRule"),
                repeatDaysOfWeek = if (obj.isNull("repeatDaysOfWeek")) null else obj.getString("repeatDaysOfWeek")
            )
        }

        val db = AppDatabase.getInstance(context)
        db.taskDao().deleteAll()
        db.taskListDao().deleteAll()
        db.taskListDao().insertAll(lists)
        db.taskDao().insertAll(tasks)
    }
}
