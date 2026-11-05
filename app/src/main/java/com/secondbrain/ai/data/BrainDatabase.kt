package com.secondbrain.ai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** All records and imported files are private local app data; no cloud or network access. */
class BrainDatabase(private val context: Context) : SQLiteOpenHelper(context, "secondbrain.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE workspace(id TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE project(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE topic(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,name TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE task(
            id TEXT PRIMARY KEY,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',
            workspace_id TEXT NOT NULL,project_id TEXT NOT NULL DEFAULT '',topic_id TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'todo',priority TEXT NOT NULL DEFAULT 'normal',owner TEXT NOT NULL DEFAULT '',
            due_at INTEGER,follow_up_at INTEGER,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE TABLE event(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,action TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE attachment(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,filename TEXT NOT NULL,path TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE snapshot(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,items_json TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_task_workspace ON task(workspace_id)")
        db.execSQL("CREATE INDEX idx_task_due ON task(due_at)")
        db.execSQL("CREATE INDEX idx_attachment_task ON attachment(task_id)")
        listOf(Workspace("view", "فيو المتحدة", "المشروعات • التراخيص • الاعتمادات"),
            Workspace("tech", "المشروعات التقنية", "QuantEx • التطوير • التطبيقات"),
            Workspace("consult", "الأعمال والاستشارات", "المكتب الهندسي • العملاء • العقود"),
            Workspace("personal", "شخصي وعائلي", "المواعيد • الأسرة • الالتزامات")
        ).forEach { w -> db.execSQL("INSERT INTO workspace(id,name,description) VALUES(?,?,?)", arrayOf(w.id, w.name, w.description)) }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* Future migrations, never destructive */ }
    private fun Cursor.str(column: String) = getString(getColumnIndexOrThrow(column)) ?: ""
    private fun Cursor.numberOrNull(column: String): Long? { val i = getColumnIndexOrThrow(column); return if (isNull(i)) null else getLong(i) }
    fun workspaces(): List<Workspace> = readableDatabase.rawQuery("SELECT * FROM workspace ORDER BY rowid", null).use { c ->
        buildList { while(c.moveToNext()) add(Workspace(c.str("id"),c.str("name"),c.str("description"))) }
    }
    fun projects(): List<Project> = readableDatabase.rawQuery("SELECT * FROM project ORDER BY name",null).use { c ->
        buildList { while(c.moveToNext()) add(Project(c.str("id"),c.str("workspace_id"),c.str("name"))) }
    }
    fun topics(): List<Topic> = readableDatabase.rawQuery("SELECT * FROM topic ORDER BY name",null).use { c ->
        buildList { while(c.moveToNext()) add(Topic(c.str("id"),c.str("project_id"),c.str("name"))) }
    }
    fun tasks(): List<BrainTask> = readableDatabase.rawQuery("SELECT * FROM task ORDER BY CASE WHEN due_at IS NULL THEN 1 ELSE 0 END,due_at,updated_at DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(BrainTask(
            id=c.str("id"),title=c.str("title"),description=c.str("description"),
            workspaceId=c.str("workspace_id"),projectId=c.str("project_id"),topicId=c.str("topic_id"),
            status=c.str("status"),priority=c.str("priority"),owner=c.str("owner"),
            dueAt=c.numberOrNull("due_at"),followUpAt=c.numberOrNull("follow_up_at"),
            createdAt=c.getLong(c.getColumnIndexOrThrow("created_at")),updatedAt=c.getLong(c.getColumnIndexOrThrow("updated_at"))
        )) }
    }
    fun putTask(t: BrainTask) {
        val before = tasks().firstOrNull { it.id == t.id }
        val values=ContentValues().apply {
            put("id",t.id);put("title",t.title);put("description",t.description)
            put("workspace_id",t.workspaceId);put("project_id",t.projectId);put("topic_id",t.topicId)
            put("status",t.status);put("priority",t.priority);put("owner",t.owner)
            if(t.dueAt==null) putNull("due_at") else put("due_at",t.dueAt)
            if(t.followUpAt==null) putNull("follow_up_at") else put("follow_up_at",t.followUpAt)
            put("created_at",t.createdAt);put("updated_at",System.currentTimeMillis())
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict("task",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            addEventRaw(writableDatabase,t.id,if(before==null) "إنشاء المهمة" else "تحديث المهمة: ${TaskStatus.label(before.status)} ← ${TaskStatus.label(t.status)}")
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    private fun addEventRaw(db: SQLiteDatabase, taskId: String, action: String) {
        db.execSQL("INSERT INTO event(id,task_id,action,created_at) VALUES(?,?,?,?)",arrayOf(UUID.randomUUID().toString(),taskId,action,System.currentTimeMillis()))
    }
    fun deleteTask(id: String) { writableDatabase.delete("task","id=?", arrayOf(id)) }
    fun events(taskId: String): List<String> = readableDatabase.rawQuery("SELECT action FROM event WHERE task_id=? ORDER BY created_at DESC", arrayOf(taskId)).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }
    fun addWorkspace(name: String, desc: String="") { writableDatabase.execSQL("INSERT INTO workspace VALUES(?,?,?)", arrayOf(UUID.randomUUID().toString(), name,desc)) }
    fun addProject(name: String, workspaceId: String) { writableDatabase.execSQL("INSERT INTO project VALUES(?,?,?)", arrayOf(UUID.randomUUID().toString(),workspaceId,name)) }
    fun addTopic(name: String, projectId: String) { writableDatabase.execSQL("INSERT INTO topic VALUES(?,?,?)", arrayOf(UUID.randomUUID().toString(),projectId,name)) }
    fun saveSnapshot(s: Snapshot) {
        val json=JSONArray();s.items.forEach { x -> json.put(JSONObject().apply { put("id",x.id);put("title",x.title);put("status",x.status);put("updated",x.updatedAt) }) }
        writableDatabase.execSQL("INSERT INTO snapshot VALUES(?,?,?,?,?,?)",arrayOf(s.id,s.workspaceId,s.title,s.text,json.toString(),s.createdAt))
    }
    fun snapshots(workspaceId: String): List<Snapshot> = readableDatabase.rawQuery("SELECT * FROM snapshot WHERE workspace_id=? ORDER BY created_at DESC", arrayOf(workspaceId)).use { c ->
        buildList { while(c.moveToNext()) {
            val json=JSONArray(c.str("items_json"));val items=buildList { for(i in 0 until json.length()) {
                val x=json.getJSONObject(i);add(SnapshotItem(x.getString("id"),x.getString("title"),x.getString("status"),x.getLong("updated")))
            } };add(Snapshot(c.str("id"),c.str("workspace_id"),c.str("title"),c.str("body"),c.getLong(c.getColumnIndexOrThrow("created_at")),items))
        } }
    }
    /** Keep attachments under app-owned directories so uninstall removes them. */
    fun contextForScheduler(): Context = context
    fun rootFolder(): File = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
    fun voiceDir(): File = File(rootFolder(), "Voice").also { it.mkdirs() }
    fun attachmentDir(): File = File(rootFolder(), "Attachments").also { it.mkdirs() }
    fun addAttachment(taskId: String, uri: Uri): File {
        val safeName = "attachment_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val file = File(attachmentDir(),safeName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open file" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        writableDatabase.execSQL("INSERT INTO attachment VALUES(?,?,?,?,?)",arrayOf(UUID.randomUUID().toString(),taskId,safeName,file.absolutePath,System.currentTimeMillis()))
        return file
    }
    fun linkExistingAttachment(taskId: String, file: File) {
        writableDatabase.execSQL("INSERT INTO attachment VALUES(?,?,?,?,?)", arrayOf(
            UUID.randomUUID().toString(),taskId,file.name,file.absolutePath,System.currentTimeMillis()))
    }
    fun attachments(taskId: String): List<File> = readableDatabase.rawQuery("SELECT path FROM attachment WHERE task_id=?", arrayOf(taskId)).use { c ->
        buildList { while(c.moveToNext()) add(File(c.getString(0))) }
    }
    /** Unencrypted manual ZIP backup chosen by the user with Android's document picker. */
    fun exportBackup(destination: Uri) {
        close() // Ensure the SQLite main file is consistent before copying.
        context.contentResolver.openOutputStream(destination).use { target ->
            requireNotNull(target) { "Could not create backup" }
            ZipOutputStream(target).use { zip ->
                val databaseFile=context.getDatabasePath("secondbrain.db")
                if(databaseFile.exists()) {
                    zip.putNextEntry(ZipEntry("database/secondbrain.db"))
                    databaseFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val root=rootFolder()
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative=file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("files/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
    fun storageUsage(): Pair<Int,Long> {
        val all=rootFolder().walkTopDown().filter { it.isFile }.toList()
        return all.size to all.sumOf { it.length() }
    }
}
