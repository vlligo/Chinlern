package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt in with -e firebaseEmulators true. Uses demo-chinlearn and local emulators only. */
@RunWith(AndroidJUnit4::class)
class FirebaseSyncTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class Client(val app: FirebaseApp, val auth: FirebaseAuth, val db: FirebaseFirestore)

    private fun client(name: String): Client {
        val host = InstrumentationRegistry.getArguments().getString("firebaseHost") ?: "10.0.2.2"
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId("demo-chinlearn").setApplicationId("1:123456789:android:test")
            .setApiKey("fake-emulator-api-key").build(), name)
        val auth = FirebaseAuth.getInstance(app).apply { useEmulator(host, 9099) }
        val db = FirebaseFirestore.getInstance(app).apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            useEmulator(host, 8080)
        }
        return Client(app, auth, db)
    }

    private fun verifyEmail(client: Client) {
        val user = client.auth.currentUser!!
        Tasks.await(user.sendEmailVerification(), 20, TimeUnit.SECONDS)
        val host = InstrumentationRegistry.getArguments().getString("firebaseHost") ?: "10.0.2.2"
        val codes = org.json.JSONObject(java.net.URL("http://$host:9099/emulator/v1/projects/demo-chinlearn/oobCodes").openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }.getInputStream().bufferedReader().use { it.readText() }).getJSONArray("oobCodes")
        val code = (0 until codes.length()).map { codes.getJSONObject(it) }
            .last { it.getString("email") == user.email && it.getString("requestType") == "VERIFY_EMAIL" }.getString("oobCode")
        Tasks.await(client.auth.applyActionCode(code), 20, TimeUnit.SECONDS)
        Tasks.await(user.reload(), 20, TimeUnit.SECONDS)
        Tasks.await(user.getIdToken(true), 20, TimeUnit.SECONDS)
        assertTrue(user.isEmailVerified)
    }

    @Test fun unverifiedAndDisabledClientsNeverStartSync() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firebaseEmulators") == "true")
        val suffix = UUID.randomUUID().toString()
        val client = client("gate-$suffix")
        val store = StudyStore(context, "gate-$suffix")
        try {
            Tasks.await(client.auth.createUserWithEmailAndPassword("gate-$suffix@example.test", "Test-password-123"), 20, TimeUnit.SECONDS)
            // Even with local opt-in, an unverified account cannot start any sync work.
            context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
            fun assertDoesNotStart() {
                instrumentation.runOnMainSync {
                    val sync = CloudSync(store, client.auth.currentUser!!.uid, { fail("Unexpected sync: $it") }, { _, _ -> fail("Unexpected upload") }, client.auth, client.db)
                    sync.sync(); sync.close()
                }
                assertNull(store.syncRevision)
            }
            assertDoesNotStart()
            verifyEmail(client)
            store.syncAccess.disable()
            assertDoesNotStart()
            context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
            assertEquals(0, sync(store, client))
            assertNotNull(store.syncRevision)
        } finally {
            store.syncAccess.disable()
            client.auth.signOut()
            Tasks.await(client.db.terminate(), 10, TimeUnit.SECONDS)
            // Named Firebase apps are released with the test process; Auth retains an emulator registry.
        }
    }

    private fun sync(store: StudyStore, client: Client): Int {
        val done = CountDownLatch(1)
        var status = ""
        var conflicts = -1
        lateinit var sync: CloudSync
        instrumentation.runOnMainSync {
            sync = CloudSync(store, client.auth.currentUser!!.uid, { status = it }, { count, _ ->
                conflicts = count; done.countDown()
            }, client.auth, client.db)
            sync.sync()
        }
        try {
            assertTrue("Sync did not finish: $status", done.await(35, TimeUnit.SECONDS))
            return conflicts
        } finally { instrumentation.runOnMainSync { sync.close() } }
    }

    @Test fun twoDevicesDownloadThenPreserveConcurrentOfflineProgress() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firebaseEmulators") == "true")
        val suffix = UUID.randomUUID().toString()
        val phone = client("phone-$suffix")
        val tablet = client("tablet-$suffix")
        val email = "test-$suffix@example.test"
        val password = "Test-password-123"
        try {
            Tasks.await(phone.auth.createUserWithEmailAndPassword(email, password), 20, TimeUnit.SECONDS)
            verifyEmail(phone)
            Tasks.await(tablet.auth.signInWithEmailAndPassword(email, password), 20, TimeUnit.SECONDS)
            context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
            context.getSharedPreferences("chinlearn_profiles", Context.MODE_PRIVATE).edit()
                .putString("guestTransferredTo", "emulator-test").commit()
            val phoneId = "firebase-test-phone-$suffix"
            val tabletId = "firebase-test-tablet-$suffix"
            listOf(phoneId, tabletId).forEach {
                context.getSharedPreferences("chinlearn_user_$it", Context.MODE_PRIVATE).edit().clear().commit()
            }
            val phoneStore = StudyStore(context, phoneId)
            val task = Task("water", Skill.CHARACTER_MEANING)
            phoneStore.renamePack("Shared vocabulary")
            phoneStore.engine.answer(task, false, delay = 4); phoneStore.save()
            assertEquals(0, sync(phoneStore, phone))

            val tabletStore = StudyStore(context, tabletId)
            assertEquals(0, sync(tabletStore, tablet))
            assertEquals("Shared vocabulary", tabletStore.packName)
            assertEquals(phoneStore.syncSnapshot(), tabletStore.syncSnapshot())
            assertEquals(1, tabletStore.engine.state(task).errors)

            // Two independent answers before either device sees the other's answer.
            phoneStore.engine.answer(task, false, delay = 4); phoneStore.save()
            tabletStore.engine.answer(task, true, delay = 4); tabletStore.save()
            assertEquals(0, sync(phoneStore, phone))
            assertEquals(0, sync(tabletStore, tablet))
            assertEquals(1, tabletStore.packNames.size)
            assertEquals(0, sync(phoneStore, phone))
            assertEquals(phoneStore.syncSnapshot(), tabletStore.syncSnapshot())
            assertEquals(2, phoneStore.engine.state(task).attempts)
            assertEquals(2, phoneStore.engine.state(task).errors)
            assertFalse(phoneStore.engine.state(task).mastered)
            // Repeating sync must neither add copies nor increment attempts.
            assertEquals(0, sync(phoneStore, phone))
            assertEquals(1, phoneStore.packNames.size)
            assertEquals(phoneStore.syncSnapshot(), StudyStore(context, phoneId).syncSnapshot())
        } finally {
            SyncAccess(context).disable()
            phone.auth.signOut(); tablet.auth.signOut()
            Tasks.await(phone.db.terminate(), 10, TimeUnit.SECONDS)
            Tasks.await(tablet.db.terminate(), 10, TimeUnit.SECONDS)
            // Keep the named apps alive until the instrumentation process exits.
        }
    }
}
