package app.ikev2manager

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ikev2manager.data.AppDatabase
import app.ikev2manager.data.ServerEntity
import app.ikev2manager.data.VpnTestEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room behaviour on a real device/emulator (in-memory DB). Run with: gradle :app:connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After fun close() = db.close()

    @Test fun uniqueHostnameAndCascadeDelete() = runTest {
        val ids = db.servers().insertAll(listOf(ServerEntity(hostname = "a.example.com", name = "a"), ServerEntity(hostname = "a.example.com", name = "dup")))
        assertEquals(1, ids.count { it != -1L })
        val id = ids.first { it != -1L }
        db.vpnTests().insert(VpnTestEntity(serverId = id, timestamp = 1, success = true, failure = null, detail = null, connectMs = 1, tunnelAvg = 1.0, tunnelMin = 1.0, tunnelMax = 1.0, lossPct = 0.0, stability = 100.0))
        assertEquals(1, db.vpnTests().observeLatest().first().size)
        db.servers().deleteByIds(listOf(id))
        assertEquals(0, db.vpnTests().observeLatest().first().size)
    }
}
