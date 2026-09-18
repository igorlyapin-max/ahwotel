package com.ahwotel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ResumeTest {
    @Test fun optionsNeverArmAndStopWinsEveryTrigger() {
        val settings = Settings(continuous = true, resumeOnBoot = true, resumeOnOpen = true)
        for (trigger in ResumeTrigger.entries) {
            assertEquals("not_armed", ResumePolicy.rejection(null, settings, trigger, false))
            assertEquals("not_armed", ResumePolicy.rejection(ResumeRow(), settings, trigger, false))
            assertNull(ResumePolicy.rejection(ResumeRow(armed = true), settings, trigger, false))
            assertEquals("monitoring_disabled", ResumePolicy.rejection(ResumeRow(armed = true), settings.copy(monitoringEnabled = false), trigger, false))
        }
        val row = ResumeRow(armed = true)
        assertNull(ResumePolicy.rejection(row, settings.copy(resumeOnOpen = false), ResumeTrigger.BOOT, false))
        assertEquals("option_disabled", ResumePolicy.rejection(row, settings.copy(resumeOnOpen = false), ResumeTrigger.OPEN, false))
        assertEquals("option_disabled", ResumePolicy.rejection(row, settings.copy(resumeOnBoot = false, resumeOnOpen = false), ResumeTrigger.PROCESS, false))
    }
    @Test fun oldSettingsDoNotEnableResumptionAndNewOptionsRoundTrip() {
        val input = Settings(deviceId = "device-test", resumeOnBoot = true, resumeOnOpen = true)
        assertEquals(input, SettingsCodec.decode(SettingsCodec.encode(input)))
        val old = JSONObject(SettingsCodec.encode(input)).apply { remove("resumeOnBoot"); remove("resumeOnOpen") }
        assertFalse(SettingsCodec.decode(old.toString()).resumeOnBoot)
        assertFalse(SettingsCodec.decode(old.toString()).resumeOnOpen)
    }
    @Test fun migrationPreservesRunningSessionAndOutboxWithoutArming() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "resume-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("com.ahwotel.MonitorDatabase/4.json")!!.bufferedReader().readText()).getJSONObject("database")
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO sessions(id,deviceId,startedAt,status,reason,configuration,continuous,durationSeconds) VALUES('old','device',100,'RUNNING','','{}',1,300)")
            old.execSQL("INSERT INTO outbox(createdAt,endpoint,payload,attempts,nextAttempt) VALUES(101,'https://example.org/v1/metrics',X'0102',0,0)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, MonitorDatabase::class.java, name).addMigrations(MonitorDatabase.MIGRATION_4_5).build()
        try {
            assertNull(db.resumeDao().get())
            assertEquals("RUNNING", db.dao().session("old")!!.status)
            assertEquals(1L, db.dao().outboxCount())
            try { db.withTransaction { db.resumeDao().put(ResumeRow(armed = true)); error("rollback") } } catch (_: IllegalStateException) { }
            assertNull(db.resumeDao().get())
            db.resumeDao().put(ResumeRow(armed = true))
            db.dao().interrupt()
            assertEquals("INTERRUPTED", db.dao().session("old")!!.status)
            assertTrue(db.resumeDao().get()!!.armed)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
