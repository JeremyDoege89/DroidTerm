package dev.dreamz.droidterm.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A task imported from copied commands, e.g. "Install Magisk" with its steps. */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Shown as the "next step" bar above the terminal. Only one task is pinned at a time. */
    val pinned: Boolean = false,
)

@Entity(
    tableName = "task_steps",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val position: Int,
    val text: String,
    /** A comment line kept from the source (not a command, can't be checked off). */
    val isNote: Boolean = false,
    val done: Boolean = false,
    val doneAt: Long? = null,
)

data class TaskSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val pinned: Boolean,
    val total: Int,
    val done: Int,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val command: String,
    val createdAt: Long = System.currentTimeMillis(),
    val uses: Int = 0,
)

@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** Key to authenticate with, or null for password auth. */
    val keyId: Long? = null,
    /** Password encrypted with the Keystore key (SecretBox), or null if not saved. */
    val passwordEnc: String? = null,
    val lastUsed: Long = 0,
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** OpenSSH one-line public key ("ssh-ed25519 AAAA... comment"). */
    val publicKey: String,
    val fingerprint: String,
    /** Private key file contents, encrypted with the Keystore key (SecretBox). */
    val privateKeyEnc: String,
    /** Passphrase for the private key, encrypted, or null if the key has none. */
    val passphraseEnc: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TaskDao {
    @Query(
        """SELECT t.id, t.name, t.createdAt, t.pinned,
           (SELECT COUNT(*) FROM task_steps s WHERE s.taskId = t.id AND s.isNote = 0) AS total,
           (SELECT COUNT(*) FROM task_steps s WHERE s.taskId = t.id AND s.isNote = 0 AND s.done = 1) AS done
           FROM tasks t ORDER BY t.pinned DESC, t.createdAt DESC"""
    )
    fun summaries(): Flow<List<TaskSummary>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun task(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE pinned = 1 LIMIT 1")
    fun pinned(): Flow<TaskEntity?>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY position")
    fun steps(taskId: Long): Flow<List<StepEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM task_steps WHERE taskId = :taskId")
    suspend fun maxPosition(taskId: Long): Int

    @Insert suspend fun insertTask(task: TaskEntity): Long
    @Insert suspend fun insertSteps(steps: List<StepEntity>)
    @Update suspend fun updateTask(task: TaskEntity)
    @Update suspend fun updateStep(step: StepEntity)
    @Delete suspend fun deleteStep(step: StepEntity)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: Long)
    @Query("UPDATE task_steps SET done = 0, doneAt = NULL WHERE taskId = :taskId") suspend fun resetProgress(taskId: Long)
    @Query("UPDATE task_steps SET done = :done, doneAt = :at WHERE id = :stepId") suspend fun setDone(stepId: Long, done: Boolean, at: Long?)
    @Query("UPDATE tasks SET pinned = 0") suspend fun unpinAll()
    @Query("UPDATE tasks SET pinned = 1 WHERE id = :id") suspend fun pinOnly(id: Long)
    @Query("UPDATE tasks SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)

    @Transaction
    suspend fun createTask(name: String, steps: List<Pair<String, Boolean>>): Long {
        val id = insertTask(TaskEntity(name = name))
        insertSteps(steps.mapIndexed { i, (text, note) -> StepEntity(taskId = id, position = i, text = text, isNote = note) })
        return id
    }

    @Transaction
    suspend fun appendSteps(taskId: Long, steps: List<Pair<String, Boolean>>) {
        val start = maxPosition(taskId) + 1
        insertSteps(steps.mapIndexed { i, (text, note) -> StepEntity(taskId = taskId, position = start + i, text = text, isNote = note) })
    }

    @Transaction
    suspend fun pin(id: Long) {
        unpinAll(); pinOnly(id)
    }
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY uses DESC, label COLLATE NOCASE")
    fun all(): Flow<List<BookmarkEntity>>
    @Insert suspend fun insert(b: BookmarkEntity): Long
    @Update suspend fun update(b: BookmarkEntity)
    @Delete suspend fun delete(b: BookmarkEntity)
    @Query("UPDATE bookmarks SET uses = uses + 1 WHERE id = :id") suspend fun used(id: Long)
}

@Dao
interface SshDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY lastUsed DESC, name COLLATE NOCASE")
    fun hosts(): Flow<List<SshHostEntity>>
    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    fun keys(): Flow<List<SshKeyEntity>>
    @Query("SELECT * FROM ssh_keys WHERE id = :id") suspend fun key(id: Long): SshKeyEntity?
    @Insert suspend fun insertHost(h: SshHostEntity): Long
    @Update suspend fun updateHost(h: SshHostEntity)
    @Delete suspend fun deleteHost(h: SshHostEntity)
    @Insert suspend fun insertKey(k: SshKeyEntity): Long
    @Delete suspend fun deleteKey(k: SshKeyEntity)
    @Query("UPDATE ssh_hosts SET keyId = NULL WHERE keyId = :keyId") suspend fun detachKey(keyId: Long)
    @Query("UPDATE ssh_hosts SET lastUsed = :at WHERE id = :id") suspend fun touch(id: Long, at: Long)
}

@Database(
    entities = [TaskEntity::class, StepEntity::class, BookmarkEntity::class, SshHostEntity::class, SshKeyEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun ssh(): SshDao

    companion object {
        lateinit var db: AppDb
            private set

        fun init(context: Context) {
            db = Room.databaseBuilder(context, AppDb::class.java, "droidterm.db").build()
        }
    }
}
