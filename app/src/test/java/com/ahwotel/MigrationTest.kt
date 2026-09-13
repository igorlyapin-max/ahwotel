package com.ahwotel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MigrationTest {
    @Test fun upgradesRealV1SchemaWithoutLosingHistoryOrOutbox() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("com.ahwotel.MonitorDatabase/1.json")!!.bufferedReader().readText())
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (i in 0 until schema.length()) {
                val entity = schema.getJSONObject(i)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indexes.length()) old.execSQL(sql(indexes.getJSONObject(j).getString("createSql")))
            }
            old.execSQL("INSERT INTO sessions(id,deviceId,startedAt,status,reason,configuration,continuous,durationSeconds) VALUES('old','device',100,'FINISHED','','{}',0,300)")
            old.execSQL("INSERT INTO samples(sessionId,time,elapsed,segment,capabilities,screenOn,cpu) VALUES('old',101,1,0,'CPU=AVAILABLE',1,25.0)")
            old.execSQL("INSERT INTO outbox(createdAt,endpoint,payload,attempts,nextAttempt) VALUES(101,'https://example.org/v1/metrics',X'010203',0,0)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, MonitorDatabase::class.java, name).addMigrations(MonitorDatabase.MIGRATION_1_2)
            .allowMainThreadQueries().build()
        try {
                assertEquals(1L, db.dao().sampleCount())
                val sample = db.dao().page(0, "old", 0, 200).single()
                assertEquals(25.0, sample.cpu!!, 0.0)
                assertNull(sample.cpuWait); assertNull(sample.probeTime); assertNull(sample.sources)
                assertArrayEquals(byteArrayOf(1, 2, 3), db.dao().firstPending()!!.payload)
                assertEquals("FINISHED", db.dao().session("old")!!.status)
        } finally { db.close() }
        context.deleteDatabase(name)
        Unit
    }
}
