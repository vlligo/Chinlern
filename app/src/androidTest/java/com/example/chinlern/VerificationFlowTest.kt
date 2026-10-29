package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VerificationFlowTest {
    @Test fun registrationSendsLinkAndSyncRequiresVerificationAndSeparatePassword() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("firebaseEmulators") == "true")
        val password = args.getString("syncPassword") ?: error("Pass syncPassword to test the unlock flow")
        val host = args.getString("firebaseHost") ?: "10.0.2.2"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = FirebaseAuth.getInstance().apply { useEmulator(host, 9099); signOut() }
        FirebaseFirestore.getInstance().useEmulator(host, 8080)
        SyncAccess(context).disable()
        val email = "verify-${UUID.randomUUID()}@example.test"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("accountDialog").apply { isAccessible = true }.invoke(activity)
                }
                onView(withText("Создать аккаунт")).inRoot(isDialog()).perform(click())
                onView(withHint("Электронная почта")).inRoot(isDialog()).perform(replaceText(email))
                onView(withHint("Пароль")).inRoot(isDialog()).perform(replaceText("Test-password-123"))
                onView(withHint("Повторите пароль")).inRoot(isDialog()).perform(replaceText("Test-password-123"), closeSoftKeyboard())
                onView(withText("Зарегистрироваться")).inRoot(isDialog()).perform(click())
                val project = "demo-chinlearn" // Auth emulator uses its configured project for SDK API keys.
                var code: String? = null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (code == null && System.nanoTime() < deadline) {
                    val codes = org.json.JSONObject(java.net.URL("http://$host:9099/emulator/v1/projects/$project/oobCodes").openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }.getInputStream().bufferedReader().use { it.readText() }).getJSONArray("oobCodes")
                    code = (0 until codes.length()).map { codes.getJSONObject(it) }
                        .lastOrNull { it.getString("email") == email && it.getString("requestType") == "VERIFY_EMAIL" }?.getString("oobCode")
                    if (code == null) Thread.sleep(100)
                }
                assertNotNull("Registration should automatically send a verification email", code)
                assertFalse(auth.currentUser!!.isEmailVerified)
                assertFalse(SyncAccess(context).enabled)
                onView(withText("Включить синхронизацию")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                Tasks.await(auth.applyActionCode(code!!), 20, TimeUnit.SECONDS)
                onView(withText("Я подтвердил почту")).perform(scrollTo(), click())
                val verifiedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!auth.currentUser!!.isEmailVerified && System.nanoTime() < verifiedDeadline) Thread.sleep(100)
                assertTrue(auth.currentUser!!.isEmailVerified)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onView(withText("Включить синхронизацию")).perform(scrollTo(), click())
                onView(withHint("Пароль доступа к синхронизации")).inRoot(isDialog()).perform(replaceText("wrong"), closeSoftKeyboard())
                onView(withText("Включить")).inRoot(isDialog()).perform(click())
                assertFalse(SyncAccess(context).enabled)
                onView(withHint("Пароль доступа к синхронизации")).inRoot(isDialog()).check(matches(hasErrorText("Неверный пароль доступа")))
                onView(withHint("Пароль доступа к синхронизации")).inRoot(isDialog()).perform(replaceText(password), closeSoftKeyboard())
                onView(withText("Включить")).inRoot(isDialog()).perform(click())
                assertTrue(SyncAccess(context).enabled)
                scenario.recreate()
                assertTrue(SyncAccess(context).enabled)
                onView(withText("Выключить синхронизацию")).perform(scrollTo(), click())
                assertFalse(SyncAccess(context).enabled)
                scenario.recreate()
                assertFalse(SyncAccess(context).enabled)
            }
        } finally { SyncAccess(context).disable(); auth.signOut() }
    }
}
