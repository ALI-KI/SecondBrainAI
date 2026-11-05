package com.secondbrain.ai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import java.security.MessageDigest
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.secondbrain.ai.util.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** All records and imported files are private local app data; no cloud or network access. */
class BrainDatabase(private val context: Context) : SQLiteOpenHelper(context, "secondbrain.db", null, 2) {
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
        db.execSQL("""CREATE TABLE attachment(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,filename TEXT NOT NULL,path TEXT NOT NULL,
            created_at INTEGER NOT NULL,display_name TEXT NOT NULL DEFAULT '',mime TEXT NOT NULL DEFAULT '')""")
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are additive, never destructive.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE attachment ADD COLUMN display_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE attachment ADD COLUMN mime TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE attachment SET display_name=filename WHERE display_name=''")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Run after the schema migration transaction commits, including for existing v0.3 installs.
        // Retry on every fresh database open: a failed file copy is NOT treated as migrated.
        if (!db.isReadOnly) migrateFilesToPrivateStorage(db)
    }

    /** v0.2 kept files in external app storage; v0.3 keeps them in the private internal directory. */
    private fun migrateFilesToPrivateStorage(db: SQLiteDatabase) {
        val external = context.getExternalFilesDir(null) ?: return
        val canonicalExternal = try { external.canonicalPath + File.separator } catch (_: Exception) { return }
        val rows = db.rawQuery("SELECT id,path,display_name,mime FROM attachment", null).use { c ->
            buildList {
                while (c.moveToNext()) add(listOf(c.getString(0),c.getString(1),c.getString(2) ?: "",c.getString(3) ?: ""))
            }
        }
        for (row in rows) {
            val (id, path, oldName, oldMime) = row
            val src = File(path)
            val safePath = try { src.canonicalPath } catch (_: Exception) { continue }
            // Only move files in OUR old app-owned external directory, never arbitrary user files.
            if (!safePath.startsWith(canonicalExternal)) continue
            if (!src.isFile) {
                Log.w("SecondBrainMigration", "Legacy attachment missing: $id")
                continue
            }
            val voice = src.parentFile?.name == "Voice"
            val sniffedMime = if(oldMime.isNotBlank()) oldMime else guessLegacyMime(src)
            val inferredExt = when (sniffedMime) {
                "application/pdf" -> ".pdf"
                "image/png" -> ".png"
                "image/jpeg" -> ".jpg"
                "application/zip" -> ".zip"
                "audio/mp4" -> ".m4a"
                else -> ""
            }
            val fileName = src.name + if(src.extension.isBlank()) inferredExt else ""
            val destDir=if(voice) voiceDir() else attachmentDir()
            val dest=File(destDir,"${UUID.randomUUID().toString().take(8)}_$fileName")
            val temp=File(destDir,".${dest.name}.part")
            var committed = false
            try {
                // Never delete the legacy file until the copy is byte-for-byte verified AND DB commits.
                src.inputStream().use { input -> temp.outputStream().use { output ->
                    input.copyTo(output); output.fd.sync()
                } }
                check(src.length() == temp.length() && checksum(src).contentEquals(checksum(temp))) { "Copy mismatch" }
                check(temp.renameTo(dest)) { "Could not finalize migration file" }
                val name = when {
                    oldName.isBlank() || oldName.startsWith("attachment_") -> fileName
                    else -> oldName
                }
                db.beginTransaction()
                try {
                    db.execSQL("UPDATE attachment SET filename=?,path=?,display_name=?,mime=? WHERE id=?",
                        arrayOf(dest.name,dest.absolutePath,name,sniffedMime,id))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                committed = true
                // Deleting the old copy is best-effort; never risk losing the newly linked copy.
                runCatching { src.delete() }.onFailure { Log.w("SecondBrainMigration", "Could not remove old copy: $id",it) }
            } catch (e: Exception) {
                Log.w("SecondBrainMigration", "Will retry attachment migration for id=$id",e)
                if (!committed) dest.delete() // DB remains pointed to old path until commit succeeds.
            } finally { temp.delete() }
        }
    }

    private fun checksum(file: File): ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(32768)
            while (true) {
                val n=input.read(buffer)
                if(n < 0) break
                if(n > 0) digest.update(buffer,0,n)
            }
        }
        return digest.digest()
    }
    /** Original display names from v0.2 were irreversibly lost; identify common old types by magic. */
    private fun guessLegacyMime(file: File): String {
        if (file.extension.equals("m4a",ignoreCase=true)) return "audio/mp4"
        val bytes=ByteArray(12)
        val count=runCatching { file.inputStream().use { it.read(bytes) } }.getOrDefault(0)
        if (count>=4 && bytes.copyOfRange(0,4).contentEquals("%PDF".toByteArray())) return "application/pdf"
        if (count>=4 && bytes[0]==0x50.toByte() && bytes[1]==0x4B.toByte()) return "application/zip"
        if (count>=4 && bytes[0]==0x89.toByte() && bytes[1]==0x50.toByte() && bytes[2]==0x4E.toByte() && bytes[3]==0x47.toByte()) return "image/png"
        if (count>=3 && bytes[0]==0xFF.toByte() && bytes[1]==0xD8.toByte() && bytes[2]==0xFF.toByte()) return "image/jpeg"
        if (count>=8 && bytes.copyOfRange(4,8).contentEquals("ftyp".toByteArray())) return "audio/mp4"
        return "application/octet-stream"
    }

    fun unresolvedLegacyAttachmentCount(): Int {
        val prefix = (context.getExternalFilesDir(null)?.absolutePath ?: return 0) + File.separator
        return readableDatabase.rawQuery("SELECT path FROM attachment",null).use { c ->
            var remaining=0
            while(c.moveToNext()) if(c.getString(0).startsWith(prefix)) remaining++
            remaining
        }
    }

    private fun Cursor.str(column: String) = getString(getColumnIndexOrThrow(column)) ?: ""
    private fun Cursor.numberOrNull(column: String): Long? { val i = getColumnIndexOrThrow(column); return if (isNull(i)) null else getLong(i) }
    private fun readTask(c: Cursor) = BrainTask(
        id = c.str("id"), title = c.str("title"), description = c.str("description"),
        workspaceId = c.str("workspace_id"), projectId = c.str("project_id"), topicId = c.str("topic_id"),
        status = c.str("status"), priority = c.str("priority"), owner = c.str("owner"),
        dueAt = c.numberOrNull("due_at"), followUpAt = c.numberOrNull("follow_up_at"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")), updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

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
        buildList { while(c.moveToNext()) add(readTask(c)) }
    }
    fun task(id: String): BrainTask? = readableDatabase.rawQuery("SELECT * FROM task WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) readTask(c) else null
    }

    /** Saves a task and logs only the changes that really happened; a no-op save leaves updated_at untouched. */
    fun putTask(t: BrainTask) {
        val before = task(t.id)
        val changes = if (before == null) listOf("إنشاء المهمة") else TaskChanges.describe(before, t)
        if (before != null && changes.isEmpty()) return
        val values = ContentValues().apply {
            put("id",t.id);put("title",t.title);put("description",t.description)
            put("workspace_id",t.workspaceId);put("project_id",t.projectId);put("topic_id",t.topicId)
            put("status",t.status);put("priority",t.priority);put("owner",t.owner)
            if(t.dueAt==null) putNull("due_at") else put("due_at",t.dueAt)
            if(t.followUpAt==null) putNull("follow_up_at") else put("follow_up_at",t.followUpAt)
            put("created_at",t.createdAt);put("updated_at",System.currentTimeMillis())
        }
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.insertWithOnConflict("task",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            changes.forEach { addEventRaw(w,t.id,it) }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }
    private fun addEventRaw(db: SQLiteDatabase, taskId: String, action: String) {
        db.execSQL("INSERT INTO event(id,task_id,action,created_at) VALUES(?,?,?,?)",arrayOf(UUID.randomUUID().toString(),taskId,action,System.currentTimeMillis()))
    }

    /** Removes the task, its history, its attachment records and files, and its pending reminder. */
    fun deleteTask(id: String) {
        val files = attachmentInfos(id).map { File(it.path) }
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.delete("attachment","task_id=?",arrayOf(id))
            w.delete("event","task_id=?",arrayOf(id))
            w.delete("task","id=?",arrayOf(id))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        files.forEach { runCatching { it.delete() } }
        ReminderScheduler.cancel(context,id)
    }

    fun events(taskId: String): List<String> = readableDatabase.rawQuery("SELECT action,created_at FROM event WHERE task_id=? ORDER BY created_at DESC", arrayOf(taskId)).use { c ->
        val format = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        buildList { while(c.moveToNext()) add("${format.format(Date(c.getLong(1)))} — ${c.getString(0)}") }
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

    fun contextForScheduler(): Context = context

    /** Private internal storage: other apps and file managers cannot read it; the ZIP backup is the way out. */
    fun rootFolder(): File = File(context.filesDir, "SecondBrain").also { it.mkdirs() }
    fun voiceDir(): File = File(rootFolder(), "Voice").also { it.mkdirs() }
    fun attachmentDir(): File = File(rootFolder(), "Attachments").also { it.mkdirs() }

    fun displayName(uri: Uri): String {
        var name = ""
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: "" }
            }
        } catch (_: Exception) { }
        return name.trim()
    }

    /** Copies the file in with its real extension, and remembers the original name and MIME type so it can be opened later. */
    fun addAttachment(taskId: String, uri: Uri): File {
        val resolver = context.contentResolver
        val display = displayName(uri).ifBlank { "مرفق" }
        val mime = resolver.getType(uri).orEmpty()
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
        val stored = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}" + if (ext.isNotEmpty()) ".$ext" else ""
        val file = File(attachmentDir(), stored)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open file" }
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) { file.delete(); throw e }
        val resolvedMime = mime.ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty() }
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.execSQL("INSERT INTO attachment(id,task_id,filename,path,created_at,display_name,mime) VALUES(?,?,?,?,?,?,?)",
                arrayOf(UUID.randomUUID().toString(), taskId, stored, file.absolutePath, System.currentTimeMillis(), display, resolvedMime))
            addEventRaw(w, taskId, "إضافة مرفق: $display")
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        return file
    }

    fun linkExistingAttachment(taskId: String, file: File, displayName: String, mime: String) {
        writableDatabase.execSQL("INSERT INTO attachment(id,task_id,filename,path,created_at,display_name,mime) VALUES(?,?,?,?,?,?,?)", arrayOf(
            UUID.randomUUID().toString(), taskId, file.name, file.absolutePath, System.currentTimeMillis(), displayName, mime))
    }

    fun attachmentInfos(taskId: String): List<AttachmentInfo> = readableDatabase.rawQuery(
        "SELECT id,task_id,display_name,path,mime FROM attachment WHERE task_id=? ORDER BY created_at", arrayOf(taskId)).use { c ->
        buildList { while (c.moveToNext()) add(AttachmentInfo(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3), c.getString(4) ?: "")) }
    }

    fun deleteAttachment(id: String) {
        val info = readableDatabase.rawQuery("SELECT task_id,display_name,path FROM attachment WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getString(1) ?: "", c.getString(2)) else null
        } ?: return
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.delete("attachment", "id=?", arrayOf(id))
            addEventRaw(w, info.first, "حذف مرفق: ${info.second}")
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        runCatching { File(info.third).delete() }
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
