package com.xatune.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.xatune.app.model.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "timetables", primaryKeys = ["account", "semesterId", "kind"])
data class CachedTable(val account: String, val semesterId: String, val kind: String, val payload: String, val fetchedAt: Long)
@Dao
interface TableDao {
    @Query("SELECT * FROM timetables WHERE account = :account AND semesterId = :semester AND kind = :kind LIMIT 1")
    suspend fun find(account: String, semester: String, kind: String): CachedTable?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(table: CachedTable)
    @Query("DELETE FROM timetables") suspend fun clear()
}
@Entity(tableName = "academic_cache", primaryKeys = ["account", "kind", "scope"])
data class CachedAcademic(val account: String, val kind: String, val scope: String, val payload: String)
@Dao
interface AcademicDao {
    @Query("SELECT * FROM academic_cache WHERE account = :account AND kind = :kind AND scope = :scope LIMIT 1")
    suspend fun find(account: String, kind: String, scope: String): CachedAcademic?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: CachedAcademic)
    @Query("DELETE FROM academic_cache") suspend fun clear()
}
@Entity(tableName = "electricity_daily", primaryKeys = ["account", "dorm", "day"])
data class ElectricDaily(val account: String, val dorm: String, val day: String, val balance: String)
@Dao
interface ElectricDao {
    @Query("SELECT * FROM electricity_daily WHERE account = :account AND dorm = :dorm ORDER BY day ASC")
    suspend fun history(account: String, dorm: String): List<ElectricDaily>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: ElectricDaily)
    @Query("DELETE FROM electricity_daily WHERE account = :account AND dorm = :dorm AND day NOT IN (SELECT day FROM electricity_daily WHERE account = :account AND dorm = :dorm ORDER BY day DESC LIMIT 400)")
    suspend fun trim(account: String, dorm: String)
    @Transaction suspend fun record(row: ElectricDaily) { save(row); trim(row.account, row.dorm) }
    @Query("DELETE FROM electricity_daily") suspend fun clear()
}
@Database(entities = [CachedTable::class, CachedAcademic::class, ElectricDaily::class], version = 3, exportSchema = true)
abstract class HelperDatabase : RoomDatabase() {
    abstract fun tables(): TableDao
    abstract fun academic(): AcademicDao
    abstract fun electricity(): ElectricDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `electricity_daily` (`account` TEXT NOT NULL, `dorm` TEXT NOT NULL, `day` TEXT NOT NULL, `balance` TEXT NOT NULL, PRIMARY KEY(`account`, `dorm`, `day`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `academic_cache` (`account` TEXT NOT NULL, `kind` TEXT NOT NULL, `scope` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`account`, `kind`, `scope`))")
            }
        }
    }
}
private val Context.settings by preferencesDataStore("settings")

class LocalStore(context: Context) {
    private val settings = context.settings
    private val database = Room.databaseBuilder(context, HelperDatabase::class.java, "helper.db").addMigrations(HelperDatabase.MIGRATION_1_2, HelperDatabase.MIGRATION_2_3).build()
    private val json = Gson()
    suspend fun account(): String = settings.data.first()[stringPreferencesKey("account")].orEmpty()
    suspend fun selection(): Pair<String, TableKind> {
        val p = settings.data.first()
        return p[stringPreferencesKey("semester")].orEmpty() to (runCatching { TableKind.valueOf(p[stringPreferencesKey("kind")].orEmpty()) }.getOrDefault(TableKind.STUDENT))
    }
    suspend fun select(account: String, semester: String, kind: TableKind) {
        settings.edit { it[stringPreferencesKey("account")] = account; it[stringPreferencesKey("semester")] = semester; it[stringPreferencesKey("kind")] = kind.name }
    }
    suspend fun semesters(): List<Semester> = runCatching { json.fromJson(settings.data.first()[stringPreferencesKey("semesters")], Array<Semester>::class.java)?.toList() }.getOrNull().orEmpty()
    suspend fun semesters(value: List<Semester>) { settings.edit { it[stringPreferencesKey("semesters")] = json.toJson(value) } }
    suspend fun baseTable(account: String, semester: String, kind: TableKind): Timetable? = database.tables().find(account, semester, kind.name)?.let { runCatching { json.fromJson(it.payload, Timetable::class.java) }.getOrNull() }
    suspend fun table(account: String, semester: String, kind: TableKind): Timetable? = baseTable(account, semester, kind)?.let { editTimetable(it, edits(account, semester)).table }
    suspend fun edits(account: String, semester: String): List<TimetableEdit> = extra("edits:$account:$semester", Array<TimetableEdit>::class.java)?.toList().orEmpty()
    suspend fun edits(account: String, semester: String, value: List<TimetableEdit>) { extra("edits:$account:$semester", value) }
    suspend fun save(table: Timetable) { database.tables().save(CachedTable(table.account, table.semester.id, table.kind.name, json.toJson(table), table.fetchedAt)) }
    suspend fun monday(account: String, semester: String): String? = settings.data.first()[stringPreferencesKey("monday:$account:$semester")]
    suspend fun monday(account: String, semester: String, value: String) { settings.edit { it[stringPreferencesKey("monday:$account:$semester")] = value } }
    suspend fun academic(account: String, kind: AcademicKind, scope: String): AcademicSnapshot? = database.academic().find(account, kind.name, scope)?.let { runCatching { json.fromJson(it.payload, AcademicSnapshot::class.java) }.getOrNull()?.takeIf { snapshot -> snapshot.account == account && snapshot.scope == scope } }
    suspend fun saveAcademic(account: String, kind: AcademicKind, scope: String, snapshot: AcademicSnapshot) {
        database.academic().save(CachedAcademic(account, kind.name, scope, json.toJson(snapshot.copy(account = account, scope = scope))))
    }
    suspend fun <T> extra(key: String, type: Class<T>): T? = settings.data.first()[stringPreferencesKey(key)]?.let { runCatching { json.fromJson(it, type) }.getOrNull() }
    suspend fun extra(key: String, value: Any?) { settings.edit { if (value == null) it.remove(stringPreferencesKey(key)) else it[stringPreferencesKey(key)] = json.toJson(value) } }
    suspend fun history(account: String, dorm: String): List<DailyBalance> = database.electricity().history(account, dorm).map { DailyBalance(it.day, it.balance) }
    suspend fun record(account: String, value: Electricity) { database.electricity().record(ElectricDaily(account, value.dorm.key, schoolToday().toString(), value.balance)) }
    suspend fun currentSemester(account: String): String = extra("current:$account", String::class.java) ?: ""
    suspend fun clear() { database.electricity().clear(); database.tables().clear(); database.academic().clear(); settings.edit { prefs -> prefs.asMap().keys.filterNot { it.name.startsWith("edits:") }.forEach { prefs.remove(it) } } }
}

data class Credentials(val account: String, val password: String)
data class CookieRecord(val url: String, val value: String, val hostOnly: Boolean? = null)
data class Secrets(val credentials: Credentials? = null, val cookies: List<CookieRecord> = emptyList())

/** Credentials and durable cookie snapshots are encrypted by a non-exportable Android Keystore key. */
class SecretVault(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "session.enc")
    private val json = Gson()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("xatu.session.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("xatu.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): Secrets = runCatching {
        if (!file.exists()) return Secrets()
        val parts = file.readText().split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        json.fromJson(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8), Secrets::class.java)
    }.getOrDefault(Secrets())
    @Synchronized fun save(value: Secrets) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(json.toJson(value).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun clear() { file.delete() }
}
