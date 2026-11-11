package com.example.chinlern

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Press
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixture tests must run in an emulator or the isolated phone test application. */
@RunWith(AndroidJUnit4::class)
class StrokeOrderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foregroundTestApp() {
        // Xiaomi blocks even instrumented background activity starts. The launcher
        // activity brings this isolated/emulator app to the foreground first.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.example.chinlern.MainActivity"
        )).use { it.readBytes() }
        instrumentation.waitForIdleSync()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun js(code: String): String {
        var result = ""
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<StrokeOrderActivity>().single()
            descendants(activity.window.decorView).filterIsInstance<WebView>().single().evaluateJavascript(code) {
                result = it; done.countDown()
            }
        }
        assertTrue("JavaScript callback: $code", done.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun awaitJs(expression: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (js(expression) == "true") return
            Thread.sleep(50)
        }
        fail("Timed out: $expression; status=${js("document.getElementById('status').textContent")}")
    }
    private fun ready() = awaitJs("!document.getElementById('play').disabled")
    private fun tap(id: String) { js("document.getElementById('$id').click()") }
    private fun swipeCharacter(left: Boolean, selector: String = "#stage", vertical: Boolean = false) {
        val rect = org.json.JSONObject(js("(() => { const r=document.querySelector('$selector').getBoundingClientRect(); return {x:r.x,y:r.y,w:r.width,h:r.height,viewport:innerWidth}; })()"))
        fun coordinates(view: View, fraction: Float): FloatArray {
            val location = IntArray(2); view.getLocationOnScreen(location)
            val scale = view.width / rect.getDouble("viewport")
            return floatArrayOf(
                (location[0] + (rect.getDouble("x") + rect.getDouble("w") * if (vertical) .5 else fraction.toDouble()) * scale).toFloat(),
                (location[1] + (rect.getDouble("y") + rect.getDouble("h") * if (vertical) fraction.toDouble() else .5) * scale).toFloat())
        }
        onView(isAssignableFrom(WebView::class.java)).perform(GeneralSwipeAction(Swipe.FAST,
            { coordinates(it, if (left) .8f else .2f) }, { coordinates(it, if (left) .2f else .8f) }, Press.FINGER))
    }

    @Test fun swipesOutsideDrawingKeepButtonTapsAndVerticalScrolling() {
        launch("你好水").use {
            ready(); swipeCharacter(left = true, selector = ".word-panel")
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '好'"); ready()
            swipeCharacter(left = false, selector = "#position")
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '你'"); ready()
            swipeCharacter(left = true, selector = "#play")
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '好'"); ready()
            Thread.sleep(300)
            assertEquals("\"▶ Смотреть\"", js("document.getElementById('play').textContent"))
            // A real tap after a swipe still activates its button.
            val point = org.json.JSONArray(js("(() => { const r=document.getElementById('play').getBoundingClientRect(); return [r.x+r.width/2,r.y+r.height/2,innerWidth]; })()"))
            onView(isAssignableFrom(WebView::class.java)).perform(androidx.test.espresso.action.GeneralClickAction(
                androidx.test.espresso.action.Tap.SINGLE, { view ->
                    val offset = IntArray(2); view.getLocationOnScreen(offset)
                    val scale = view.width / point.getDouble(2)
                    floatArrayOf((offset[0]+point.getDouble(0)*scale).toFloat(), (offset[1]+point.getDouble(1)*scale).toFloat())
                }, Press.FINGER))
            awaitJs("document.getElementById('play').textContent.includes('Пауза')")
            tap("play")
            js("document.getElementById('credits').open = true")
            awaitJs("document.querySelectorAll('#licenses pre').length === 2")
            swipeCharacter(left = true, selector = ".word-panel", vertical = true)
            assertEquals("\"好\"", js("document.querySelector('.chip[aria-pressed=true]').textContent"))
            awaitJs("scrollY > 0")
        }
    }
    private fun launch(vararg forms: String) = ActivityScenario.launch<StrokeOrderActivity>(
        StrokeOrderActivity.intent(context, forms.toList(), if (forms.firstOrNull() == "学习") "xué xí" else "shuǐ")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test fun offlineRenderingAndIndividualStrokes() {
        launch("水").use {
            ready()
            tap("show-guides")
            assertEquals("true", js("document.querySelectorAll('#target svg path').length > 0"))
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
                val web = descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                assertTrue(web.settings.blockNetworkLoads)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
            }
            assertEquals("\"Всего черт: 4\"", js("document.getElementById('status').textContent"))
            for (stroke in 1..4) {
                tap("next")
                awaitJs("document.getElementById('status').textContent === 'Черта $stroke из 4'")
            }
            assertEquals("4", js("document.querySelectorAll('.guide-number').length"))
            assertEquals("false", js("document.getElementById('next').disabled"))
            assertEquals("\"К первой черте\"", js("document.getElementById('next').textContent"))
            tap("replay")
            awaitJs("document.getElementById('status').textContent === 'Черта 4 из 4' && document.getElementById('play').textContent.includes('Повторить')")
        }
    }

    @Test fun nextStrokeWorksDuringPlaybackPauseAndRapidTaps() {
        launch("水").use {
            ready(); tap("slow"); ready(); tap("play"); tap("play")
            assertEquals("\"▶ Продолжить\"", js("document.getElementById('play').textContent"))
            assertEquals("false", js("document.getElementById('next').disabled"))
            tap("next")
            awaitJs("document.getElementById('status').textContent === 'Черта 2 из 4' && !document.getElementById('play').disabled")
            assertEquals("\"▶ Смотреть\"", js("document.getElementById('play').textContent"))
            js("document.getElementById('next').click(); document.getElementById('next').click()")
            awaitJs("document.getElementById('status').textContent === 'Черта 4 из 4'")
            tap("next")
            awaitJs("document.getElementById('status').textContent === 'Черта 1 из 4'")
            Thread.sleep(500)
            assertEquals("\"Черта 1 из 4\"", js("document.getElementById('status').textContent"))
        }
    }

    @Test fun numberedDirectionsFollowPlaybackAndSurviveRecreation() {
        launch("水永").use { scenario ->
            ready()
            assertEquals("\"false\"", js("document.getElementById('show-guides').getAttribute('aria-pressed')"))
            assertEquals("0", js("document.getElementById('guides').childElementCount"))
            tap("show-guides")
            assertEquals("\"1,2,3,4\"", js("Array.from(document.querySelectorAll('.guide-number text'), n => n.textContent).join(',')"))
            assertEquals("4", js("document.querySelectorAll('.guide-arrow').length"))
            tap("slow"); ready(); tap("play"); tap("play")
            awaitJs("document.querySelectorAll('.guide-number').length === 1")
            assertEquals("\"1\"", js("document.querySelector('.guide-number text').textContent"))
            tap("show-guides")
            assertEquals("0", js("document.getElementById('guides').childElementCount"))
            assertEquals("\"▶ Продолжить\"", js("document.getElementById('play').textContent"))
            tap("show-guides"); tap("next")
            awaitJs("document.getElementById('status').textContent === 'Черта 2 из 4' && !document.getElementById('play').disabled")
            assertEquals("\"2\"", js("document.querySelector('.guide-number text').textContent"))
            tap("show-guides")
            scenario.recreate(); ready()
            assertEquals("\"false\"", js("document.getElementById('show-guides').getAttribute('aria-pressed')"))
            js("document.querySelectorAll('.chip')[1].click()"); ready()
            assertEquals("0", js("document.getElementById('guides').childElementCount"))
            tap("show-guides")
            assertEquals("5", js("document.querySelectorAll('.guide-arrow').length"))
        }
    }

    @Test fun directionGuidesTrackMedianPathsAndResize() {
        launch("永").use {
            ready()
            tap("show-guides")
            // Compare every arrow with the bundled median, including the hook's
            // final segment, after transforming into the actual drawing bounds.
            js("window.guideData = null; fetch('data/6c38.json').then(r => r.json()).then(d => window.guideData = d)")
            awaitJs("window.guideData !== null")
            val aligned = """(() => {
                const size=document.getElementById('stage').clientWidth;
                const t=HanziWriter.getScalingTransform(size,size,18);
                return guideData.medians.every((m,i) => {
                    const arrow=document.querySelectorAll('.guide-arrow')[i];
                    const matrix=arrow.transform.baseVal.consolidate().matrix;
                    const end=m[m.length-1], prev=m[m.length-2];
                    const angle=Math.atan2(-(end[1]-prev[1]),end[0]-prev[0]);
                    return Math.abs(matrix.e-(t.x+end[0]*t.scale))<.01 &&
                        Math.abs(matrix.f-(size-t.y-end[1]*t.scale))<.01 &&
                        Math.abs(matrix.a-Math.cos(angle))<.01 && Math.abs(matrix.b-Math.sin(angle))<.01;
                });
            })()""".trimIndent()
            assertEquals("true", js(aligned))
            js("document.getElementById('stage').style.width='180px'")
            awaitJs("document.getElementById('guides').viewBox.baseVal.width === document.getElementById('stage').clientWidth")
            assertEquals("true", js(aligned))
            assertEquals("true", js("Array.from(document.querySelectorAll('.guide-number circle')).every(c => c.cx.baseVal.value-c.r.baseVal.value>=0 && c.cy.baseVal.value-c.r.baseVal.value>=0 && c.cx.baseVal.value+c.r.baseVal.value<=180 && c.cy.baseVal.value+c.r.baseVal.value<=180)"))
        }
    }

    @Test fun swipingTheDrawingMovesWithinTheWordAndKeepsVariantBoundaries() {
        launch("你好水", "妳好水").use {
            ready(); swipeCharacter(left = true)
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '好'"); ready()
            swipeCharacter(left = true)
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '水'"); ready()
            swipeCharacter(left = true)
            assertEquals("\"水 · знак 3 из 3\"", js("document.getElementById('position').textContent"))
            swipeCharacter(left = false)
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '好'"); ready()
            js("document.querySelectorAll('.chip')[3].click()"); ready()
            swipeCharacter(left = false)
            assertEquals("\"妳 · знак 1 из 3\"", js("document.getElementById('position').textContent"))
        }
    }

    @Test fun pauseResumeAndRapidCharacterChanges() {
        launch("水你好").use {
            ready(); tap("slow"); ready(); tap("play"); tap("play")
            assertEquals("\"▶ Продолжить\"", js("document.getElementById('play').textContent"))
            val status = js("document.getElementById('status').textContent")
            Thread.sleep(800)
            assertEquals(status, js("document.getElementById('status').textContent"))
            tap("play")
            awaitJs("document.getElementById('status').textContent === 'Черта 4 из 4'")
            js("document.querySelectorAll('.chip')[1].click(); document.querySelectorAll('.chip')[2].click(); document.querySelectorAll('.chip')[0].click()")
            ready()
            assertEquals("\"水 · знак 1 из 3\"", js("document.getElementById('position').textContent"))
            assertEquals("\"Всего черт: 4\"", js("document.getElementById('status').textContent"))
        }
    }

    @Test fun variantsSelectionAndSpeedSurviveRecreation() {
        launch("学习", "學習").use { scenario ->
            ready()
            js("document.querySelectorAll('.chip')[3].click()")
            ready(); tap("slow"); ready()
            scenario.recreate(); ready()
            assertEquals("\"習 · знак 2 из 2\"", js("document.getElementById('position').textContent"))
            assertEquals("\"true\"", js("document.getElementById('slow').getAttribute('aria-pressed')"))
            assertEquals("\"習\"", js("document.querySelector('.chip[aria-pressed=true]').textContent"))
        }
    }

    @Test fun portraitTabletUsesFullWidthDrawingAndCompactHeader() {
        org.junit.Assume.assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600)
        org.junit.Assume.assumeTrue(context.resources.configuration.orientation == 1)
        launch("学习", "學習").use {
            ready()
            Thread.sleep(500)
            assertEquals("true", js("innerWidth >= 600 && innerHeight > innerWidth"))
            assertEquals("true", js("(() => { const p=document.querySelector('.word-panel').getBoundingClientRect(); const c=document.querySelector('.card').getBoundingClientRect(); return p.bottom<=c.top && Math.abs(p.width-c.width)<2; })()"))
            assertEquals("true", js("document.getElementById('stage').getBoundingClientRect().width >= Math.min(520, innerWidth-100)"))
            assertEquals("true", js("document.querySelector('.controls').getBoundingClientRect().bottom <= innerHeight"))
            assertEquals("true", js("document.documentElement.scrollWidth <= innerWidth"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p")).use { input ->
                java.io.File(context.cacheDir, "strokes-tablet-portrait-full.png").outputStream().use { input.copyTo(it) }
            }
            swipeCharacter(left = true, selector = "#position")
            awaitJs("document.querySelector('.chip[aria-pressed=true]').textContent === '习'")
        }
    }

    @Test fun adaptiveLayoutAndTopLeftBackNavigation() {
        launch("学习", "學習").use { scenario ->
            ready()
            run {
                // Android 17 can ignore orientation requests on large screens.
                val orientations = if (context.resources.configuration.smallestScreenWidthDp >= 600) {
                    val current = if (js("innerWidth > innerHeight") == "true") "landscape" else "portrait"
                    listOf(current to null)
                } else listOf(
                    "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                )
                for ((name, orientation) in orientations) {
                    if (orientation != null) {
                        scenario.onActivity { it.requestedOrientation = orientation }
                        awaitJs("(innerWidth > innerHeight) === ${name == "landscape"}")
                        ready()
                    }
                    assertEquals("true", js("document.documentElement.scrollWidth <= innerWidth"))
                    assertEquals("true", js("(() => { const s=document.getElementById('stage').getBoundingClientRect(); return Math.abs(s.width-s.height)<2; })()"))
                    assertEquals("true", js("(() => { const panel=document.querySelector('.word-panel').getBoundingClientRect(); const card=document.querySelector('.card').getBoundingClientRect(); return innerWidth>=720 && innerWidth>innerHeight ? panel.right<card.left && Math.abs(panel.top-card.top)<12 : panel.bottom<=card.top; })()"))
                    if (js("innerWidth>=720 && innerHeight>=600") == "true") {
                        assertEquals("true", js("document.getElementById('stage').getBoundingClientRect().width > 320"))
                        assertEquals("true", js("document.querySelector('.controls').getBoundingClientRect().bottom <= innerHeight"))
                    }
                    scenario.onActivity { activity ->
                        val back = descendants(activity.window.decorView).single { it.contentDescription == "Закрыть порядок черт" }
                        val location = IntArray(2); back.getLocationOnScreen(location)
                        val toolbar = descendants(activity.window.decorView).filterIsInstance<com.google.android.material.appbar.MaterialToolbar>().single()
                        val window = IntArray(2); toolbar.getLocationOnScreen(window)
                        val density = activity.resources.displayMetrics.density
                        assertTrue("Back belongs at the leading edge of its window", location[0] - window[0] < 24 * density)
                        assertTrue("Back has a full touch target", back.width >= 48 * density && back.height >= 48 * density)
                    }
                    // UiAutomation.takeScreenshot crops the rotated Xiaomi display;
                    // screencap reads the compositor's full current display bounds.
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p")).use { input ->
                        java.io.File(context.cacheDir, "strokes-$name.png").outputStream().use { input.copyTo(it) }
                    }
                }
            }
            onView(withContentDescription("Закрыть порядок черт")).perform(click())
            val closedBy = SystemClock.elapsedRealtime() + 5_000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < closedBy) Thread.sleep(50)
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun unsupportedSupplementaryCharacterAndPunctuationRecover() {
        launch("𠀀水!").use {
            awaitJs("!document.getElementById('unavailable').hidden")
            assertEquals("3", js("document.querySelectorAll('.chip').length"))
            assertEquals("true", js("document.getElementById('play').disabled && document.getElementById('next').disabled"))
            assertEquals("0", js("document.getElementById('guides').childElementCount"))
            js("document.querySelectorAll('.chip')[1].click()")
            ready()
            assertEquals("true", js("document.getElementById('unavailable').hidden"))
            js("document.querySelectorAll('.chip')[2].click()")
            awaitJs("!document.getElementById('unavailable').hidden")
            assertEquals("\"!\"", js("document.getElementById('target').textContent"))
            assertEquals("0", js("document.getElementById('guides').childElementCount"))
        }
    }

    @Test fun licensesAreBundledAndVocabularyIsPlainText() {
        launch("水", "<img src=x onerror=alert(1)>").use {
            ready()
            assertEquals("0", js("document.querySelectorAll('img').length"))
            js("document.getElementById('credits').open = true")
            awaitJs("document.querySelectorAll('#licenses pre').length === 2")
            assertEquals("true", js("document.getElementById('licenses').textContent.includes('ARPHIC PUBLIC LICENSE')"))
        }
    }

    @Test fun revealedWritingAnswerOpensViewerAndRetainsDraftAndProgress() {
        val drawing = "[\"0.1,0.2|0.3,0.4\"]"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var before = ""
            scenario.onActivity { activity ->
                val data = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(activity) as StudyStore
                val word = data.words.first { it.id == "water" }
                data.onboardingDone = true; data.writing = true; data.introduce(word)
                data.route = "quiz"; data.current = Task(word.id, Skill.WRITING)
                data.strokes = drawing; data.revealed = false; data.feedback = null; data.save()
                before = data.studyEngine.peek(data.current!!).toString()
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            }
            onView(withText("Показать ответ")).perform(click())
            onView(withText("Порядок черт")).perform(scrollTo(), click())
            ready(); tap("next")
            awaitJs("document.getElementById('status').textContent === 'Черта 1 из 4'")
            onView(withContentDescription("Закрыть порядок черт")).perform(click())
            onView(withText("Помню")).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val store = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(activity) as StudyStore
                assertEquals(drawing, store.strokes)
                assertTrue(store.revealed)
                assertEquals(before, store.studyEngine.peek(store.current!!).toString())
            }
            val reloaded = StudyStore(context)
            assertEquals(drawing, reloaded.strokes)
            assertEquals(before, reloaded.studyEngine.peek(reloaded.current!!).toString())
        }
    }
}
