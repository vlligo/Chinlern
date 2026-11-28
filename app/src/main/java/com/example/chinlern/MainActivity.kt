package com.example.chinlern

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.InputType
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.doAfterTextChanged
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.example.chinlern.data.VocabularyReference
import com.example.chinlern.dictionary.DictionaryResult
import com.example.chinlern.dictionary.DictionaryScreen
import com.example.chinlern.dictionary.SourcedDefinition
import com.example.chinlern.dictionary.dictionaryReadingKey
import com.example.chinlern.dictionary.dictionaryResults
import com.example.chinlern.dictionary.readingLabel
import com.example.chinlern.dictionary.OfflineDictionary
import com.example.chinlern.dictionary.Pinyin
import com.example.chinlern.texts.ReaderDialogs
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.FirebaseNetworkException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var store: StudyStore
    private lateinit var voice: Pronunciation
    private var readerDialogs: ReaderDialogs? = null
    private val dictionaryScreens = mutableListOf<DictionaryScreen>()
    private val librarySearchWorker = Executors.newSingleThreadExecutor()
    private val librarySearchHandler = Handler(Looper.getMainLooper())
    private val libraryDictionaries by lazy {
        listOf(OfflineDictionary.Source.RU_WIKTIONARY, OfflineDictionary.Source.CEDICT).map { OfflineDictionary(this, it) }
    }
    private var cancelLibrarySearch: (() -> Unit)? = null
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var cloud: CloudSync? = null
    private var profileUid: String? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var verificationMessage: String? = null
    private var verificationBusy = false
    private var lastVerificationSentAt = 0L
    private var accessRestoreUid: String? = null
    private var syncStatus = "Синхронизация выключена"
    private var syncStatusLabel: TextView? = null
    private lateinit var pageScroll: ScrollView
    private lateinit var pageHolder: LinearLayout
    private lateinit var pageHost: SectionSwipeLayout
    private lateinit var pageFrame: FrameLayout
    private val sectionPages = mutableMapOf<String, SectionPage>()
    private data class SectionPage(
        val frame: FrameLayout, val scroll: ScrollView, val holder: LinearLayout,
        val content: LinearLayout, val list: ListView?, val syncLabel: TextView?, val ready: Boolean = true
    )
    private var bottomNavigation: BottomNavigationView? = null
    private var sectionList: ListView? = null
    private val listScrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    private var sectionScrollState = ViewPager.SCROLL_STATE_IDLE
    private var sectionRefreshPending = false
    private val railItems = mutableMapOf<String, MaterialButton>()
    private val pageScrollPositions = mutableMapOf<String, Int>()
    private val navigationRoutes = listOf("learn", "words", "stats", "settings")
    private val backMotion = BackNavigationMotion()
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            backMotion.start(pageHost)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            backMotion.progress(backEvent)
        }

        override fun handleOnBackCancelled() {
            backMotion.cancel()
            if (sectionRefreshPending) postSectionRefresh()
        }

        override fun handleOnBackPressed() {
            backMotion.reset()
            if (store.route in navigationRoutes) {
                // Reuse the live destination, including its scroll position. Jump directly
                // home instead of scrolling past every intervening section.
                pageHost.setCurrentItem(0, false)
            } else {
                store.route = "learn"
                render()
            }
            backMotion.enter(pageFrame)
            if (sectionRefreshPending) postSectionRefresh()
        }
    }
    private lateinit var content: LinearLayout
    private lateinit var quizActions: LinearLayout
    private lateinit var quizDock: QuizActionDock
    private var lastListeningPrompt: String? = null
    private var pendingListening: Runnable? = null
    private lateinit var keyboardQuestion: LinearLayout
    private var voiceMessage: String? = null
    private var audioNotice: TextView? = null
    private var answerInput: TextInputEditText? = null
    private var tablet = false
    private var wideContent = false
    private var contentWidthDp = 0
    private var selectionLabel: TextView? = null
    private val jade by lazy { getColor(R.color.chinlern_jade) }
    private val ink by lazy { getColor(R.color.chinlern_ink) }
    private val muted by lazy { getColor(R.color.chinlern_muted) }
    private val paper by lazy { getColor(R.color.chinlern_paper) }
    private val pale by lazy { getColor(R.color.chinlern_pale_jade) }
    private val orange = Color.rgb(152, 83, 36)
    private val red by lazy { getColor(R.color.chinlern_red) }
    private val gold by lazy { getColor(R.color.chinlern_gold) }
    private val blush by lazy { getColor(R.color.chinlern_blush) }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) readPackFiles(uris.distinct())
    }
    private fun readPackFiles(uris: List<android.net.Uri>) {
        if (uris.size > PackFiles.MAX_FILES) { message("Импорт не выполнен", "Выберите не более 100 файлов."); return }
        val owner = profileUid
        toast("Читаем файлы…")
        Thread {
            val items = mutableListOf<PackFiles.Item>()
            val errors = mutableListOf<String>()
            val budget = PackFiles.Budget()
            for ((index, uri) in uris.withIndex()) {
                var name = "Файл ${index + 1}"
                try {
                    runCatching {
                        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) name = it.getString(0) ?: name
                        }
                    }
                    val result = contentResolver.openInputStream(uri)?.use { PackFiles.read(name, it, budget) }
                        ?: error("Не удалось открыть файл. Скачайте его на устройство и выберите снова.")
                    items += result.items; errors += result.errors
                } catch (e: Exception) { errors += "$name: ${e.message ?: "Не удалось прочитать файл"}" }
                if (budget.bytes > PackFiles.MAX_TOTAL_BYTES || budget.entries > PackFiles.MAX_FILES) break
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && owner == profileUid) {
                    if (items.size == 1 && errors.isEmpty()) previewImport(items.single().preview)
                    else previewFileBatch(items, errors, owner)
                }
            }
        }.start()
    }
    private fun previewFileBatch(items: List<PackFiles.Item>, errors: List<String>, owner: String?) {
        val box = column().apply { setPadding(dp(24), dp(12), dp(24), dp(16)) }
        box.add(label("Готово наборов: ${items.size}. Каждый станет отдельным набором. У одинаковых карточек будет общий прогресс.", 16f))
        val selected = items.map { item ->
            CheckBox(this).apply {
                text = "${item.preview.name} · ${item.preview.words.size} слов\n${item.source}"
                isChecked = true; box.add(this, 8)
            }
        }
        if (errors.isNotEmpty()) box.add(label("Не удалось прочитать:\n${errors.joinToString("\n\n")}", 14f, red).apply { setTextIsSelectable(true) }, 12)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Проверьте наборы")
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Отмена", null)
            .setPositiveButton("Создать выбранные", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = items.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (owner != profileUid) { dialog.dismiss(); message("Импорт не выполнен", "Аккаунт изменился. Выберите файлы снова."); return@setOnClickListener }
                val chosen = items.filterIndexed { index, _ -> selected[index].isChecked }
                if (chosen.isEmpty()) { toast("Выберите хотя бы один набор"); return@setOnClickListener }
                var created = 0
                try {
                    chosen.forEach { store.importPack(it.preview.name, it.preview.words); created++ }
                    store.route = "words"; dialog.dismiss(); render(); toast("Создано наборов: $created")
                } catch (e: Exception) {
                    dialog.dismiss(); render(); message("Создано наборов: $created", e.message ?: "Не удалось сохранить остальные наборы")
                }
            }
        }
        dialog.show()
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { it.write(store.exportPack().toByteArray()) } ?: error("Не удалось записать файл")
            toast("Список слов сохранён")
        } catch (e: Exception) { message("Экспорт не выполнен", e.message ?: "Ошибка записи") }
    }
    private val importBackupFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val owner = profileUid
            Thread {
                val result = runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(LibraryBackup.MAX_BYTES + 1) }
                        ?: error("Не удалось открыть файл")
                    require(bytes.size <= LibraryBackup.MAX_BYTES) { "Файл превышает 32 МБ." }
                    LibraryBackup.decode(bytes.toString(Charsets.UTF_8)).also { StudyStore.validateSyncPacks(it.packs) }
                }
                runOnUiThread {
                    if (!isDestroyed && owner == profileUid) result.fold(::previewBackup) {
                        message("Импорт не выполнен", it.message ?: "Проверьте файл резервной копии.")
                    }
                }
            }.start()
        }
    }
    private val exportBackupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val snapshot = store.exportBackup().toByteArray(Charsets.UTF_8)
            Thread {
                val result = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(snapshot) } ?: error("Не удалось записать файл")
                }
                runOnUiThread {
                    if (!isDestroyed) result.fold({ toast("Наборы и прогресс сохранены") }, {
                        message("Экспорт не выполнен", it.message ?: "Ошибка записи")
                    })
                }
            }.start()
        } catch (e: Exception) { message("Экспорт не выполнен", e.message ?: "Ошибка резервной копии") }
    }
    private fun previewBackup(data: LibraryBackup.Data) {
        try {
            val owner = profileUid
            val preview = store.previewBackup(data)
            MaterialAlertDialogBuilder(this).setTitle("Объединить данные?")
                .setMessage("Новых наборов: ${preview.added}. Дополнений: ${preview.updated}. Отдельных версий: ${preview.conflicts}.\n\nВерсии карточек из файла сохраняются отдельными наборами. У одинаковых карточек прогресс общий: используется последнее расписание повторений, счётчики попыток не складываются. Повторный импорт того же файла не удваивает результаты.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Объединить") { _, _ ->
                    try {
                        check(owner == profileUid) { "Аккаунт изменился. Откройте файл ещё раз." }
                        store.importBackup(data); render()
                        toast("Наборы и прогресс объединены")
                    } catch (e: Exception) { message("Импорт не выполнен", e.message ?: "Данные не изменены") }
                }.show()
        } catch (e: Exception) { message("Импорт не выполнен", e.message ?: "Проверьте резервную копию") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Chinlern)
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        profileUid = auth.currentUser?.uid
        store = StudyStore(this, profileUid)
        connectCloud()
        voice = Pronunciation(this, { error -> voiceMessage = error; audioNotice?.text = error ?: "Китайский голос готов" })
        onBackPressedDispatcher.addCallback(this, backCallback)
        libraryCardFilter = savedInstanceState?.takeIf { it.getString("dictionaryProfile") == profileUid }
            ?.getString("libraryCardFilter").orEmpty()
        render()
        savedInstanceState?.takeIf { it.getString("readerProfile") == profileUid }?.let { state ->
            state.getString("readerDocument")?.let { id ->
                reader().restore(id, state.getInt("readerRevision"), state.getInt("readerOffset"),
                    state.getString("readerRevisionId"))
            }
        }
        savedInstanceState?.takeIf { it.getString("dictionaryProfile") == profileUid }?.let { state ->
            val source = OfflineDictionary.Source.entries.firstOrNull { it.id == state.getString("dictionarySource") }
            state.getString("dictionaryQuery")?.let { vocabularySearch(it, source) }
        }
        authListener = FirebaseAuth.AuthStateListener { firebase ->
            val nextUid = firebase.currentUser?.uid
            if (nextUid != profileUid) {
                dictionaryScreens.toList().asReversed().forEach(DictionaryScreen::close)
                readerDialogs?.close(); readerDialogs = null
                libraryCardFilter = ""
                cloud?.close(); cloud = null
                store.save()
                accessRestoreUid = null
                verificationMessage = null
                lastVerificationSentAt = 0L
                profileUid = nextUid
                store = StudyStore(this, nextUid)
                store.route = "settings"
                connectCloud()
                render()
            }
        }.also { auth.addAuthStateListener(it) }
    }
    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && store.refreshPractice()) render()
        scheduleListeningAudio()
        if (auth.currentUser?.isEmailVerified == false) refreshVerification(false)
        else cloud?.sync()
    }
    override fun onPause() {
        backMotion.reset()
        readerDialogs?.savePosition()
        pendingListening?.let { window.decorView.removeCallbacks(it) }; pendingListening = null
        // Hanping briefly opens an external activity. Do not replay again on its return.
        if (::store.isInitialized && store.audioProvider != "hanping") lastListeningPrompt = null
        super.onPause(); if (::store.isInitialized) store.save(); cloud?.sync(); if (::voice.isInitialized) voice.stop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (libraryCardFilter.isNotEmpty()) {
            outState.putString("dictionaryProfile", profileUid)
            outState.putString("libraryCardFilter", libraryCardFilter)
        }
        super.onSaveInstanceState(outState)
        dictionaryScreens.lastOrNull()?.currentQuery()?.let { query ->
            outState.putString("dictionaryProfile", profileUid)
            outState.putString("dictionaryQuery", query)
            outState.putString("dictionarySource", dictionaryScreens.lastOrNull()?.selectedSource?.id)
        }
        readerDialogs?.currentReading()?.let { (document, offset) ->
            outState.putString("readerProfile", profileUid)
            outState.putString("readerDocument", document.id)
            outState.putInt("readerRevision", document.revision)
            outState.putString("readerRevisionId", document.revisionId)
            outState.putInt("readerOffset", offset)
        }
    }
    override fun onDestroy() {
        cancelLibrarySearch?.invoke()
        librarySearchWorker.shutdownNow()
        dictionaryScreens.toList().asReversed().forEach(DictionaryScreen::close)
        readerDialogs?.close()
        authListener?.let { auth.removeAuthStateListener(it) }
        cloud?.close()
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }

    private fun connectCloud() {
        cloud?.close(); cloud = null
        syncStatus = when {
            !store.syncAccess.enabled -> "Синхронизация выключена — данные сохраняются на устройстве"
            profileUid == null -> "Войдите, чтобы синхронизировать наборы, тексты и прогресс"
            auth.currentUser?.isEmailVerified != true -> "Подтвердите почту, чтобы начать синхронизацию"
            else -> "Ожидание синхронизации…"
        }
        if (!store.syncAccess.hasAccess && profileUid != null && auth.currentUser?.isEmailVerified == true && accessRestoreUid != profileUid) {
            val uid = profileUid!!
            val targetStore = store
            accessRestoreUid = uid
            syncStatus = "Проверяем прежний доступ к синхронизации…"
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("sync").document("state").get(com.google.firebase.firestore.Source.SERVER)
                .addOnSuccessListener { snapshot ->
                    if (store !== targetStore || profileUid != uid || auth.currentUser?.isEmailVerified != true) return@addOnSuccessListener
                    if (snapshot.exists() && snapshot.getLong("schema") == SyncSnapshot.VERSION.toLong()) {
                        store.syncAccess.rememberPreviousAccess()
                    }
                    connectCloud()
                    if (store.route == "settings") render()
                }.addOnFailureListener {
                    if (store !== targetStore || profileUid != uid) return@addOnFailureListener
                    accessRestoreUid = null
                    syncStatus = "Не удалось проверить прежний доступ. Подключитесь к сети и повторите проверку."
                    syncStatusLabel?.text = syncStatus
                }
        }
        if (!store.syncAccess.enabled || auth.currentUser?.isEmailVerified != true) return
        profileUid?.let { uid -> cloud = CloudSync(store, uid, { status ->
            syncStatus = status
            syncStatusLabel?.text = status
        }, { _, changed ->
            if (changed) {
                readerDialogs?.refreshAfterSync()
                if (store.route != "quiz") render()
            }
        }) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.add(view: View, top: Int = 0, height: Int = -2) { addView(view, LinearLayout.LayoutParams(-1, if (height >= 0) dp(height) else height).apply { topMargin = dp(top) }) }
    private fun label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun heading(value: String, subtitle: String = "") {
        content.add(label(value, 30f, ink, true), 4)
        if (subtitle.isNotBlank()) content.add(label(subtitle, 15f, muted), 8)
    }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
        text = title; isAllCaps = false; setSingleLine(false); maxLines = Int.MAX_VALUE; minWidth = 0; ellipsize = null; textSize = 16f; cornerRadius = dp(14); minHeight = dp(52)
        setPadding(dp(16), dp(8), dp(16), dp(8)); insetTop = 0; insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(if (primary) red else pale)
        setTextColor(if (primary) Color.WHITE else jade)
        setOnClickListener { action() }
    }
    private fun listIcon(icon: Int, description: String, action: () -> Unit) =
        DetailUi.icon(this, icon, description, R.color.chinlern_muted, action)

    private fun LinearLayout.addWithIcon(control: View, icon: View, top: Int) {
        val line = row()
        line.addView(control, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
        add(line, top)
    }

    private fun LinearLayout.addIcon(icon: View, top: Int, alignment: Int = Gravity.START) {
        addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            topMargin = dp(top)
            gravity = alignment
        })
    }
    private fun card(color: Int = Color.WHITE) = column().apply { background = bg(color, 18).apply { if (color == Color.WHITE) setStroke(dp(1), Color.rgb(231, 224, 209)) }; setPadding(dp(22), dp(22), dp(22), dp(22)) }
    private fun LinearLayout.twoButtons(first: MaterialButton, second: MaterialButton) {
        if (resources.configuration.fontScale > 1.2f || (!wideContent && (first.text.length > 12 || second.text.length > 12))) {
            add(first, 12); add(second, 8); return
        }
        val line = row()
        line.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        line.addView(second, LinearLayout.LayoutParams(0, -2, 1f))
        add(line, 12)
    }
    private fun LinearLayout.quizButtonRow(buttons: List<MaterialButton>) {
        val line = row()
        buttons.forEachIndexed { index, control ->
            control.minHeight = dp(48)
            control.setPadding(dp(6), dp(4), dp(6), dp(4))
            line.addView(control, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
        }
        add(line, 4)
    }
    private fun cardColumns() = if (contentWidthDp >= 640 && resources.configuration.fontScale <= 1.3f) 2 else 1

    private fun switchDestination(route: String) {
        if (store.route == route) return
        backMotion.reset()
        if (store.route == "quiz" || route !in navigationRoutes) {
            store.route = route; render(); return
        }
        val motion = android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        pageHost.setCurrentItem(navigationRoutes.indexOf(route), motion)
    }

    private fun captureSection() = SectionPage(pageFrame, pageScroll, pageHolder, content, sectionList, syncStatusLabel)

    private fun useSection(page: SectionPage) {
        pageFrame = page.frame; pageScroll = page.scroll; pageHolder = page.holder
        content = page.content; sectionList = page.list; syncStatusLabel = page.syncLabel
        syncStatusLabel?.text = syncStatus
    }

    private fun rememberSectionScroll(route: String) {
        val list = sectionList
        if (list == null) pageScrollPositions[route] = pageScroll.scrollY
        else list.getChildAt(0)?.let { listScrollPositions[route] = list.firstVisiblePosition to (it.top - list.paddingTop) }
    }

    private fun createSection(route: String): SectionPage {
        val active = captureSection()
        try {
            pageScroll = ScrollView(this).apply {
                isFillViewport = true; clipToPadding = false
                setPadding(0, 0, 0, active.scroll.paddingBottom)
            }
            pageHolder = LinearLayout(this).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(dp(if (tablet) 24 else 18), dp(8), dp(if (tablet) 24 else 18), dp(24))
            }
            content = column()
            pageHolder.addView(content, LinearLayout.LayoutParams(if (contentWidthDp >= 1040) dp(1040) else -1, -2))
            pageScroll.addView(pageHolder)
            pageFrame = sectionFrame(pageScroll)
            sectionList = null; syncStatusLabel = null
            // ViewPager lays out all its pages in the destination's first frame.
            // Offscreen pages get only a light shell until explicitly prepared.
            return captureSection().copy(ready = false)
        } finally { useSection(active) }
    }

    private fun prepareSection(route: String) {
        if (route !in navigationRoutes) return
        val page = sectionPages.getOrPut(route) { createSection(route) }
        if (page.ready) return
        val active = captureSection()
        val prepared = try {
            useSection(page)
            renderDestination(route)
            val scroll = pageScroll
            scroll.post { scroll.scrollTo(0, pageScrollPositions[route] ?: 0) }
            captureSection()
        } finally { useSection(active) }
        sectionPages[route] = prepared
        if (store.route == route) useSection(prepared)
    }

    private fun warmSectionsAfterDraw() {
        val host = pageHost
        // Let the destination and its entrance animation finish before preparing
        // other screens. Each screen gets its own turn, rather than one long layout.
        fun warmNext() {
            if (host !== pageHost || store.route == "quiz" || isDestroyed || !host.isAttachedToWindow) return
            if (sectionScrollState != ViewPager.SCROLL_STATE_IDLE || backMotion.inProgress) {
                host.postDelayed({ warmNext() }, 64)
                return
            }
            val route = navigationRoutes.sortedBy { kotlin.math.abs(navigationRoutes.indexOf(it) - host.currentItem) }
                .firstOrNull { sectionPages[it]?.ready == false } ?: return
            prepareSection(route)
            host.postDelayed({ warmNext() }, 48)
        }
        host.doOnPreDraw { host.postDelayed({ warmNext() }, 260) }
    }

    private fun sectionFrame(scroll: ScrollView) = FrameLayout(this).apply {
        // Landing on a page must not focus its search field and pull the list back to its header.
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun installSectionPager() {
        val initialRoute = store.route
        sectionScrollState = ViewPager.SCROLL_STATE_IDLE
        sectionRefreshPending = false
        sectionPages.clear()
        sectionPages[initialRoute] = captureSection()
        pageHost.pagingEnabled = initialRoute in navigationRoutes
        // Keep live page shells for swiping, without building three hidden screens
        // inside the destination's first layout pass.
        pageHost.offscreenPageLimit = navigationRoutes.lastIndex
        pageHost.adapter = object : PagerAdapter() {
            override fun getCount() = if (initialRoute == "quiz") 1 else navigationRoutes.size
            override fun isViewFromObject(view: View, item: Any) = view === item
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                val route = if (initialRoute == "quiz") initialRoute else navigationRoutes[position]
                val page = sectionPages.getOrPut(route) { createSection(route) }
                container.addView(page.frame)
                return page.frame
            }
            override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                container.removeView(item as View)
            }
            override fun getItemPosition(item: Any) = if (sectionPages.values.any { it.frame === item }) POSITION_UNCHANGED else POSITION_NONE
        }
        pageHost.setCurrentItem(navigationRoutes.indexOf(initialRoute).coerceAtLeast(0), false)
        pageHost.preparePageForDrag = { position -> navigationRoutes.getOrNull(position)?.let(::prepareSection) }
        pageHost.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrollStateChanged(state: Int) {
                sectionScrollState = state
                if (state == ViewPager.SCROLL_STATE_IDLE && sectionRefreshPending) postSectionRefresh()
            }

            override fun onPageSelected(position: Int) {
                if (initialRoute == "quiz") return
                val route = navigationRoutes[position]
                if (store.route == route) return
                rememberSectionScroll(store.route)
                prepareSection(route)
                sectionPages[route]?.let(::useSection)
                store.route = route
                backMotion.reset()
                backCallback.isEnabled = route != "learn"
                store.saveScreen()
                answerInput = null; audioNotice = null
                bottomNavigation?.let { if (it.selectedItemId != position + 1) it.selectedItemId = position + 1 }
                railItems.forEach { (destination, item) ->
                    val selected = destination == route
                    item.isSelected = selected
                    item.setTypeface(item.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    item.setTextColor(if (selected) red else muted)
                }
                selectionLabel?.text = "${if (route == "settings") store.selected.size else store.selectedStudyWords.size} слов"
            }
        })
        if (initialRoute != "quiz") warmSectionsAfterDraw()
    }

    private fun refreshAdjacentSections() {
        if (sectionRefreshPending) return
        sectionRefreshPending = true
        postSectionRefresh()
    }

    private fun postSectionRefresh() {
        val host = pageHost
        host.post {
            if (host !== pageHost || !sectionRefreshPending || sectionScrollState != ViewPager.SCROLL_STATE_IDLE || backMotion.inProgress) return@post
            sectionRefreshPending = false
            // Only Practice and Progress display data changed by a word checkbox.
            val active = store.route
            if (active == "learn" || active == "stats") rememberSectionScroll(active)
            sectionPages.remove("learn")
            sectionPages.remove("stats")
            host.adapter?.notifyDataSetChanged()
            prepareSection(active)
            sectionPages[active]?.let(::useSection)
            warmSectionsAfterDraw()
        }
    }

    private fun renderDestination(route: String = store.route) {
        when (route) { "quiz" -> quiz(); "words" -> library(); "stats" -> stats(); "settings" -> settings(); else -> dashboard() }
    }

    private fun installSectionList(route: String, adapter: BaseAdapter, footer: View? = null, searchBar: View? = null) {
        // The header and cards share one bounded scrolling surface. Offscreen rows are recycled.
        pageHolder.removeView(content)
        pageScroll.visibility = View.GONE
        val list = ListView(this).apply {
            divider = null; dividerHeight = 0
            clipToPadding = false
            val side = dp(if (tablet) 24 else 18)
            setPadding(side, dp(8), side, pageScroll.paddingBottom + dp(24))
            addHeaderView(FrameLayout(this@MainActivity).apply {
                addView(content, FrameLayout.LayoutParams(-1, -2))
            }, null, false)
            footer?.let { addFooterView(it, null, false) }
            this.adapter = adapter
        }
        sectionList = list
        val listContent = if (searchBar == null) list else column().apply {
            addView(searchBar, LinearLayout.LayoutParams(-1, -2))
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        pageFrame.addView(listContent, FrameLayout.LayoutParams(
            if (contentWidthDp >= 1040) dp(1088) else -1, -1, Gravity.CENTER_HORIZONTAL))
        // In non-touch mode ListView otherwise skips the non-selectable header,
        // hiding search/reader actions completely when large fonts make it tall.
        list.requestFocusFromTouch()
        val (row, top) = listScrollPositions[route] ?: (0 to 0)
        list.setSelectionFromTop(row.coerceAtMost(list.count - 1), top)
    }

    private fun render() {
        backMotion.reset()
        // At the root Android owns Back, including its back-to-home animation.
        backCallback.isEnabled = store.route != "learn"
        store.saveScreen()
        if (store.route != "quiz") { lastListeningPrompt = null; voice.stop() }
        answerInput = null; audioNotice = null; syncStatusLabel = null
        if (store.route == "quiz" && "quiz" in sectionPages) {
            pageFrame.requestFocus()
            content.removeAllViews()
            keyboardQuestion.removeAllViews()
            quizActions.removeAllViews()
            quizActions.visibility = View.GONE
            quizDock.scrollTo(0, 0)
            pageScroll.scrollTo(0, 0)
            selectionLabel?.text = "${store.studyWords.size} слов"
            quiz()
            scheduleListeningAudio()
            ViewCompat.requestApplyInsets(pageFrame.rootView)
            return
        }
        cancelLibrarySearch?.invoke()
        cancelLibrarySearch = null
        val config = resources.configuration
        val windowWidth = config.screenWidthDp
        tablet = if (store.route == "quiz") StudyLayout.expanded(windowWidth, config.screenHeightDp, config.smallestScreenWidthDp)
            else windowWidth >= 600
        val railWidth = if (windowWidth >= 1000) 160 else 112
        val showRail = tablet && store.route != "quiz"
        contentWidthDp = (windowWidth - (if (showRail) railWidth else 0) - (if (tablet) 48 else 36) - 16).coerceAtLeast(240).coerceAtMost(1040)
        wideContent = contentWidthDp >= 720 && resources.configuration.fontScale <= 1.3f
        val root = column().apply { setBackgroundColor(paper) }
        keyboardQuestion = column().apply {
            visibility = View.GONE
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundColor(pale)
        }
        val header = row().apply { setPadding(dp(22), dp(12), dp(22), dp(12)) }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        val brand = label("Chinlearn", 20f, ink, true)
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        selectionLabel = label("${when (store.route) { "quiz" -> store.studyWords.size; "settings" -> store.selected.size; else -> store.selectedStudyWords.size }} слов", 13f, jade)
        header.addView(selectionLabel)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (!tablet && store.route == "quiz") {
                v.findViewWithTag<View>("quizSessionBar")?.visibility = if (keyboard.bottom > 0) View.GONE else View.VISIBLE
            }
            val typing = keyboard.bottom > 0
            header.visibility = if ((store.route == "quiz" && (!tablet || typing)) ||
                (typing && config.screenHeightDp < 480)) View.GONE else View.VISIBLE
            bottomNavigation?.let { nav ->
                val visibility = if (typing) View.GONE else View.VISIBLE
                if (nav.visibility != visibility) {
                    nav.visibility = visibility
                    setNavigationClearance(if (typing) 0 else nav.height + dp(16))
                }
            }
            keyboardQuestion.visibility = if (tablet && store.route == "quiz" && keyboard.bottom > 0 && keyboardQuestion.childCount > 0) View.VISIBLE else View.GONE
            val bottom = maxOf(bars.bottom, keyboard.bottom)
            if (v.paddingLeft != bars.left || v.paddingTop != bars.top ||
                v.paddingRight != bars.right || v.paddingBottom != bottom) {
                v.setPadding(bars.left, bars.top, bars.right, bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
        root.add(header)
        root.add(View(this).apply { setBackgroundColor(gold) }, height = 2)
        val body = row().apply { gravity = Gravity.TOP }
        railItems.clear()
        if (showRail) {
            val rail = column().apply { setPadding(dp(10), dp(20), dp(10), dp(12)); setBackgroundColor(Color.rgb(240, 235, 222)) }
            listOf("learn" to "Учить", "words" to "Слова", "stats" to "Прогресс", "settings" to "Настройки").forEach { (route, title) ->
                val selected = store.route == route || (route == "learn" && store.route == "quiz")
                val item = button(title) { switchDestination(route) }.apply {
                    textSize = 14f; setPadding(dp(6), dp(10), dp(6), dp(10))
                    isSelected = selected
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) red else muted)
                    icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity,
                        when (route) { "words" -> R.drawable.ic_nav_words; "stats" -> R.drawable.ic_nav_stats; "settings" -> R.drawable.ic_nav_settings; else -> R.drawable.ic_nav_learn })
                    iconTint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(red, muted))
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                    iconSize = dp(24)
                }
                railItems[route] = item
                rail.add(item, 8)
            }
            body.addView(ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(rail)
            }, LinearLayout.LayoutParams(dp(railWidth), -1))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; tag = "questionScroll" }
        pageScroll = scroll
        val phoneQuiz = !tablet && store.route == "quiz"
        val side = dp(if (tablet) 24 else if (phoneQuiz) 10 else 18)
        val holder = LinearLayout(this).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(side, dp(8), side, dp(if (phoneQuiz) 8 else 24))
        }
        pageHolder = holder
        content = column()
        holder.addView(content, LinearLayout.LayoutParams(if (windowWidth - (if (showRail) railWidth else 0) - 48 > 1040) dp(1040) else -1, -2))
        scroll.addView(holder)
        val workspace = column()
        workspace.add(keyboardQuestion)
        pageFrame = sectionFrame(scroll)
        pageHost = SectionSwipeLayout(this)
        sectionList = null
        workspace.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        quizActions = column().apply {
            tag = "quizActions"
            visibility = View.GONE
            setPadding(dp(if (tablet) 24 else 18), dp(6), dp(if (tablet) 24 else 18), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        quizDock = QuizActionDock(this).apply {
            addView(StudyLayout.centered(quizActions, 1088))
            setBackgroundColor(Color.WHITE)
            elevation = dp(4).toFloat()
        }
        workspace.add(quizDock)
        body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))
        val bodyLayer = FrameLayout(this)
        bodyLayer.addView(body, FrameLayout.LayoutParams(-1, -1))
        root.addView(bodyLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNavigation = null
        if (!tablet && store.route != "quiz") {
            val nav = BottomNavigationView(this).apply {
                background = bg(Color.WHITE, 28).apply { setStroke(dp(1), Color.rgb(231, 224, 209)) }
                clipToOutline = true
                elevation = dp(3).toFloat()
                labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
                isItemActiveIndicatorEnabled = false
                itemRippleColor = null
                val checked = intArrayOf(android.R.attr.state_checked)
                val pressed = intArrayOf(android.R.attr.state_pressed)
                val pressedTint = androidx.core.graphics.ColorUtils.blendARGB(red, ink, 0.28f)
                val navColors = ColorStateList(arrayOf(pressed, checked, intArrayOf()), intArrayOf(pressedTint, red, muted))
                itemIconTintList = navColors
                itemTextColor = navColors
                fun destination(id: Int, title: String, outline: Int, filled: Int) {
                    menu.add(0, id, id - 1, title).icon = StateListDrawable().apply {
                        addState(checked, getDrawable(filled))
                        addState(intArrayOf(), getDrawable(outline))
                    }
                }
                destination(1, "Учить", R.drawable.ic_nav_learn, R.drawable.ic_nav_learn_selected)
                destination(2, "Слова", R.drawable.ic_nav_words, R.drawable.ic_nav_words_selected)
                destination(3, "Прогресс", R.drawable.ic_nav_stats, R.drawable.ic_nav_stats_selected)
                destination(4, "Настройки", R.drawable.ic_nav_settings, R.drawable.ic_nav_settings_selected)
                selectedItemId = when (store.route) { "words" -> 2; "stats" -> 3; "settings" -> 4; else -> 1 }
                setOnItemReselectedListener { /* Keep the current page and scroll position. */ }
                setOnItemSelectedListener { item -> switchDestination(when (item.itemId) { 2 -> "words"; 3 -> "stats"; 4 -> "settings"; else -> "learn" }); true }
            }
            bottomNavigation = nav
            bodyLayer.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                marginStart = dp(12); marginEnd = dp(12)
                topMargin = dp(8); bottomMargin = dp(8)
            })
            // Draw the page behind the dock, but allow its last item to scroll clear of it.
            nav.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                if (nav.visibility == View.VISIBLE) setNavigationClearance(bottom - top + dp(16))
            }
        }
        setContentView(root)
        ViewCompat.getRootWindowInsets(window.decorView)?.let { ViewCompat.dispatchApplyWindowInsets(root, it) }
        renderDestination()
        installSectionPager()
        scheduleListeningAudio()
        ViewCompat.requestApplyInsets(root)
    }

    private fun setNavigationClearance(clearance: Int) {
        sectionPages.values.forEach { page ->
            if (page.scroll.paddingBottom != clearance) page.scroll.setPadding(0, 0, 0, clearance)
            page.list?.let { list ->
                val bottomPadding = clearance + dp(24)
                if (list.paddingBottom != bottomPadding) {
                    list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottomPadding)
                }
            }
        }
    }
    private var activityMonthOffset = 0

    private fun motivationCard(summary: StudyActivity.Summary): LinearLayout = card().apply {
        add(label("好好学习，天天向上", 26f, jade, true))
        add(label("Хорошо учись и каждый день стремись вперёд. — Мао Цзэдун", 14f, muted), 6)
        add(label("Серия: ${summary.streak} дн. · Лучшая: ${summary.best} дн.", 20f, ink, true), 16)
        if (summary.incompleteHistory) add(label("Серии рассчитаны по сохранившимся датам занятий.", 13f, muted), 6)
        val today = summary.days[StudyActivity.date(System.currentTimeMillis())]?.answers ?: 0
        add(label(if (today > 0) "Ответов сегодня: $today. Вы уже сделали шаг вперёд!"
            else if (summary.streak > 0) "Ответьте хотя бы на один вопрос сегодня, чтобы продолжить серию."
            else "Один ответ сегодня — начало новой серии.", 15f, muted), 8)
    }

    private fun activityCalendar(summary: StudyActivity.Summary): LinearLayout {
        val box = card().apply { tag = "activity-calendar" }
        val monthHeader = row()
        val monthTitle = label("", 22f, jade, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        val monthHost = FrameLayout(this)
        val dayHeight = dp(if (tablet) 48 else 40)
        val dayTextSize = if (tablet) 16f else 15f
        var outgoingMonth: View? = null
        // Month navigation only touches this card; reuse the already computed activity summary.
        val months = summary.days.entries.groupBy { it.key.take(7) }
        fun showMonth(direction: Int = 0) {
            val monthContent = column()
            val month = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_MONTH, 1); add(java.util.Calendar.MONTH, activityMonthOffset)
            }
            val prefix = StudyActivity.date(month.timeInMillis).take(7)
            val days = months[prefix].orEmpty().associate { it.key to it.value }
            val answers = days.values.sumOf { it.answers }
            val correct = days.values.sumOf { it.correct }
            monthTitle.text = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.forLanguageTag("ru")).format(month.time)
            val totals = label("Дней занятий: ${days.size} · Ответов: $answers", 17f, ink, true)
            val accuracy = label(if (answers > 0) "Верно: $correct · Точность: ${correct.toLong() * 100 / answers}%" else "Пока нет ответов за этот месяц.", 15f, muted)
            val details = label("Выберите день, чтобы увидеть результаты.", 15f, jade).apply {
                tag = "activity-day-details"
                minLines = 2
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            fun addMonthDetails() {
                monthContent.add(totals, 12)
                monthContent.add(accuracy, 6)
                monthContent.add(details, 8)
            }
            if (!tablet) addMonthDetails()
            val headers = row()
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { name ->
                headers.addView(label(name, 12f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(32), 1f))
            }
            monthContent.add(headers, 8)
            val first = (month.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            val length = month.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val today = StudyActivity.date(System.currentTimeMillis())
            var selected: TextView? = null
            fun background(cell: TextView, key: String, count: Int, active: Boolean) {
                cell.isSelected = active
                val fill = if (count > 0) jade else if (active) pale else Color.TRANSPARENT
                val pressedFill = if (count > 0) ColorUtils.blendARGB(jade, Color.BLACK, 0.18f)
                    else ColorUtils.blendARGB(if (active) pale else Color.WHITE, jade, 0.16f)
                fun dayShape(color: Int) = bg(color, 8).apply {
                    if (active || key == today) setStroke(dp(if (active) 3 else 2), if (active) ink else gold)
                }
                cell.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), dayShape(pressedFill))
                    addState(intArrayOf(), dayShape(fill))
                }
            }
            // A fixed six-week grid keeps the card height and scroll position stable across months.
            repeat(6) { week ->
                val line = row()
                repeat(7) { column ->
                    val day = week * 7 + column - first + 1
                    val key = "$prefix-${day.toString().padStart(2, '0')}"
                    val result = days[key] ?: StudyActivity.Day()
                    val cell = label(if (day in 1..length) day.toString() else "", dayTextSize,
                        if (result.answers > 0) Color.WHITE else ink, key == today).apply {
                        gravity = Gravity.CENTER
                        if (day in 1..length) {
                            tag = key
                            background(this, key, result.answers, false)
                            contentDescription = "$key: ${result.answers} ответов${if (key == today) ", сегодня" else ""}"
                            setOnClickListener {
                                selected?.let { previous ->
                                    val previousKey = previous.tag as String
                                    background(previous, previousKey, days[previousKey]?.answers ?: 0, false)
                                }
                                selected = this
                                background(this, key, result.answers, true)
                                val date = (month.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, day) }
                                details.text = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.forLanguageTag("ru")).format(date.time) + "\n" +
                                    if (result.answers > 0) "Ответов: ${result.answers} · Верно: ${result.correct} · ${result.correct.toLong() * 100 / result.answers}%"
                                    else "В этот день занятий не было."
                            }
                        }
                    }
                    line.addView(cell, LinearLayout.LayoutParams(0, dayHeight, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                }
                monthContent.add(line)
            }
            if (tablet) addMonthDetails()
            outgoingMonth?.let {
                it.animate().cancel()
                monthHost.overlay.remove(it)
                outgoingMonth = null
            }
            val previous = monthHost.getChildAt(0)
            previous?.animate()?.cancel()
            val animate = direction != 0 && previous != null && monthHost.isLaidOut &&
                (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
            monthTitle.animate().cancel()
            monthTitle.alpha = 1f
            if (animate) {
                // The overlay keeps the outgoing page visible without its touch targets.
                monthHost.overlay.add(previous)
                outgoingMonth = previous
                val distance = direction * dp(32).toFloat()
                val interpolator = FastOutSlowInInterpolator()
                previous.animate().translationX(-distance).alpha(0f).setDuration(220)
                    .setInterpolator(interpolator).withEndAction {
                        monthHost.overlay.remove(previous)
                        outgoingMonth = null
                    }.start()
                monthContent.translationX = distance
                monthContent.alpha = 0f
                monthContent.animate().translationX(0f).alpha(1f).setDuration(220)
                    .setInterpolator(interpolator).start()
                monthTitle.alpha = 0.35f
                monthTitle.animate().alpha(1f).setDuration(220).setInterpolator(interpolator).start()
            } else {
                monthHost.removeAllViews()
            }
            monthHost.addView(monthContent, FrameLayout.LayoutParams(-1, -2))
        }
        fun monthArrow(drawable: Int, description: String, action: () -> Unit) =
            DetailUi.icon(this, drawable, description, action = action).apply {
                background = null
                val disabled = intArrayOf(-android.R.attr.state_enabled)
                val pressed = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
                val focused = intArrayOf(android.R.attr.state_focused)
                val idle = intArrayOf()
                val disabledTint = requireNotNull(imageTintList).getColorForState(disabled, jade)
                imageTintList = ColorStateList(
                    arrayOf(disabled, pressed, focused, idle), intArrayOf(disabledTint, red, gold, jade)
                )
                if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false

                fun scaleTo(scale: Float, durationMs: Long) = ObjectAnimator.ofPropertyValuesHolder(
                    this,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)
                ).setDuration(durationMs)
                stateListAnimator = StateListAnimator().apply {
                    addState(pressed, scaleTo(0.9f, 70))
                    addState(idle, scaleTo(1f, 140))
                }
            }
        lateinit var next: View
        val previous = monthArrow(R.drawable.ic_stroke_back, "Предыдущий месяц") {
            activityMonthOffset--
            next.isEnabled = true
            showMonth(-1)
        }
        next = monthArrow(R.drawable.ic_reader_next, "Следующий месяц") {
            if (activityMonthOffset < 0) {
                activityMonthOffset++
                next.isEnabled = activityMonthOffset < 0
                showMonth(1)
            }
        }
        next.isEnabled = activityMonthOffset < 0
        monthHeader.addView(previous, dp(48), dp(48))
        monthHeader.addView(monthTitle, LinearLayout.LayoutParams(0, -2, 1f))
        monthHeader.addView(next, dp(48), dp(48))
        box.add(monthHeader)
        box.add(monthHost)
        box.add(label("Зелёный — день занятий · золотая рамка — сегодня. Письмо тоже считается.", 13f, muted), 8)
        showMonth()
        return box
    }

    private fun activityStatistics(summary: StudyActivity.Summary) {
        val streak = motivationCard(summary)
        val calendar = activityCalendar(summary)
        val all = card()
        all.add(label("За всё время · вся библиотека", 22f, jade, true))
        all.add(label("Ответов: ${summary.answers} · Верно: ${summary.correct}\nПисьменных ответов: ${summary.writing} · Дней занятий: ${summary.days.size}", 17f), 12)
        all.add(label(if (summary.answers > 0) "Точность: ${summary.correct * 100 / summary.answers}%" else "Начните занятие — здесь появятся ваши результаты.", 15f, muted), 8)
        all.add(label("Одинаковые карточки в разных наборах учитываются один раз. Итоги относятся к сохранённой библиотеке; отменённые ответы не считаются.", 13f, muted), 8)
        if (summary.incompleteHistory) all.add(label("Для старых занятий сохранилась лишь часть дат: календарь, дни занятий и рекорд серии могут быть неполными. Общее число ответов сохранено.", 13f, muted), 8)
        val calendarMaxWidth = 460
        if (wideContent) {
            val activity = row().apply { gravity = Gravity.TOP }
            val calendarWidth = minOf(calendarMaxWidth, contentWidthDp * 3 / 5)
            activity.addView(calendar, LinearLayout.LayoutParams(dp(calendarWidth), -2).apply { marginEnd = dp(18) })
            val summaryColumn = column().apply {
                add(streak)
                add(all, 16)
            }
            activity.addView(summaryColumn, LinearLayout.LayoutParams(0, -2, 1f))
            content.add(activity, 16)
        } else {
            content.add(streak, 16)
            content.add(if (tablet) StudyLayout.centered(calendar, calendarMaxWidth) else calendar, 16)
            content.add(all, 16)
        }
    }

    private fun dashboard() {
        heading("Практика", "Выберите наборы и начните занятие.")
        content.add(motivationCard(store.activitySummary()), 16)
        if (!store.onboardingDone) {
            val welcome = card()
            welcome.add(label("Первое занятие", 22f, jade, true))
            welcome.add(label("Проверьте китайский звук. Пиньинь можно вводить цифрами: shui3 = shuǐ, вода. Сначала познакомимся со словами, затем ответим на 10 вопросов.", 16f), 8)
            welcome.add(button("▶ Проверить звук: 你好") { play(Word("test", "你好", "nǐ hǎo", "ni3 hao3", listOf("привет"), emptyList())) }, 8)
            welcome.add(button("Звук слышен — начать", true) { store.audioEnabled = true; store.onboardingDone = true; startBeginner() }, 8)
            welcome.add(button("Пока без заданий на слух") { store.audioEnabled = false; store.onboardingDone = true; startBeginner() }, 8)
            content.add(welcome, 16)
        }
        content.add(button("Трудные слова") { chooseHardReview() }, 12)
        val packControls = if (wideContent) card() else content
        practicePicker(packControls)
        packControls.add(button("＋ Новый набор") { importHub() }, 10)
        val selectedWords = store.selectedStudyWords
        val selectionEngine = store.selectionEngine
        val knowledge = selectionEngine.tasks(selectedWords, false, store.audioEnabled)
        val writing = selectionEngine.tasks(selectedWords, true, false)
        val hero = card(jade)
        val heroTop = row()
        heroTop.addView(label("Слов в практике", 15f, Color.rgb(225, 235, 220)), LinearLayout.LayoutParams(0, -2, 1f))
        heroTop.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        hero.add(heroTop)
        hero.add(label(selectedWords.size.toString(), if (tablet) 48f else 38f, Color.rgb(255, 214, 107), true), 8)
        hero.add(label("${knowledge.count { selectionEngine.peek(it).mastered }} / ${knowledge.size} навыков закреплено", 15f, Color.WHITE), 20)
        hero.add(progress(knowledge, selectionEngine), 12)
        if (wideContent) {
            val overview = row().apply { gravity = Gravity.TOP }
            overview.addView(packControls, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(18) })
            overview.addView(hero, LinearLayout.LayoutParams(0, -2, 1f))
            content.add(overview, 20)
        } else content.add(hero, 24)
        val short = card()
        short.add(label("Короткое занятие", 21f, ink, true))
        short.add(label("${store.dailyNewWords} новых слов в день. Сначала повторение, затем новые слова. Можно закончить раньше, если всё повторено.", 15f, muted), 8)
        short.twoButtons(button("10 вопросов", true) { startShort(false) }, button("5 минут") { startShort(true) })
        if (store.shortSession && (!store.sessionFinished || store.feedback != null)) short.add(button("Продолжить короткое занятие") { store.route = "quiz"; render() }, 8)
        content.add(short, 16)
        val extra = card()
        extra.add(label("Хочу заниматься ещё", 21f, ink, true))
        extra.add(label("Новые слова сверх дневного плана или дополнительная практика знакомых. Можно продолжать столько, сколько хочется.", 15f, muted), 8)
        extraPractice(extra, writing = false)
        content.add(extra, 16)
        val learn = card()
        learn.add(label("Значение и произношение", 21f, ink, true))
        learn.add(label("Повторения по вашему расписанию. Перевод, пиньинь и тоны. ${if (store.audioEnabled) "Задания на слух включены." else "Задания на слух выключены."}", 15f, muted), 8)
        learn.add(button("Продолжить обучение", true) { start(false) }, 18)
        val write = card()
        write.add(label("Написание", 21f, ink, true))
        write.add(label("Напишите иероглиф по памяти, затем сравните с ответом.", 15f, muted), 8)
        write.add(label("${writing.count { selectionEngine.peek(it).mastered }} / ${writing.size} слов закреплено", 14f, jade), 12)
        write.add(button("Практиковать письмо") { start(true) }, 14)
        if (wideContent) { val line = row(); line.gravity = Gravity.TOP; line.addView(learn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14) }); line.addView(write, LinearLayout.LayoutParams(0, -2, 1f)); content.add(line, 16) }
        else { content.add(learn, 16); content.add(write, 14) }
        val due = (knowledge + writing).count { val p = selectionEngine.peek(it); (p.attempts > 0 || p.mastered) && p.due <= System.currentTimeMillis() }
        content.add(label(if (due > 0) "$due навыков пора повторить. Откройте режим и начните повторение на сегодня." else "Интервалы подстраиваются под ответы и время между повторениями.", 13f, muted), 20)
        if (selectedWords.isEmpty()) content.add(button("Выбрать слова") { store.route = "words"; render() }, 12)
    }
    private fun progress(tasks: List<Task>, engine: LearningEngine = store.studyEngine) = LinearProgressIndicator(this).apply {
        max = maxOf(tasks.size, 1); progress = tasks.count { engine.peek(it).mastered }
        setIndicatorColor(gold); trackColor = Color.rgb(226, 224, 207); trackThickness = dp(7); trackCornerRadius = dp(4)
        contentDescription = "Закреплено $progress из ${tasks.size} навыков"
    }
    private fun startBeginner() {
        val beginnerWords = store.selectedStudyWords.take(3)
        val focus = store.selectionEngine.tasks(beginnerWords, false, false).filter {
            it.skill in setOf(Skill.CHARACTER_MEANING, Skill.CHARACTER_PINYIN, Skill.CHARACTER_TONE)
        }
        startShort(false, focus)
    }
    private fun startShort(minutes: Boolean, focus: List<Task>? = null, writing: Boolean = false, scheduled: Boolean = false,
        packReview: Boolean = false, reviewPacks: Set<String>? = null, selectedReview: Boolean = false
    ) {
        if (focus?.isEmpty() ?: store.selectedStudyWords.isEmpty()) {
            toast("Выберите хотя бы одно слово")
            store.route = "words"; render(); return
        }
        store.writing = writing
        store.beginSession(minutes, focus, scheduled, packReview, reviewPacks, selectedReview)
        store.route = "quiz"; store.nextQuestion(); render()
    }
    private fun startExtra(newWords: Boolean, writing: Boolean) {
        if (!store.beginExtraStudy(newWords, writing)) { render(); return }
        store.route = "quiz"; store.nextQuestion(); render()
    }
    private fun extraPractice(target: LinearLayout, writing: Boolean = store.writing, afterSession: Boolean = false) {
        val options = store.extraStudyOptions(writing)
        val fresh = options.fresh
        if (fresh.isNotEmpty()) {
            val count = fresh.map { it.wordId }.distinct().size
            val words = when (count) {
                1 -> "1 новое слово"
                2, 3, 4 -> "$count новых слова"
                else -> "$count новых слов"
            }
            target.add(button("Учить ещё: $words", true) {
                startExtra(newWords = true, writing = writing)
            }, 12)
        }
        if (options.familiar.isNotEmpty()) {
            target.add(label("Знакомые слова доступны и до срока повторения. Ответы учитываются в прогрессе.", 13f, muted), 8)
            target.add(button(if (afterSession && options.familiar.size >= 10) "Ещё 10 вопросов" else "Практиковать знакомые слова") {
                startExtra(newWords = false, writing = writing)
            }, 8)
        }
    }
    private fun shortResults() {
        heading("Занятие завершено", "Можно остановиться здесь. Прогресс сохранён.")
        val box = card()
        box.add(label("Ответов: ${store.sessionCount}", 28f, jade, true))
        box.add(label("Верно: ${store.sessionCorrect} · Ошибок: ${store.sessionCount - store.sessionCorrect}", 20f), 12)
        content.add(box, 20)
        store.studyEngine.dueSchedule(store.tasks()).values.filter { it > System.currentTimeMillis() }.minOrNull()?.let {
            content.add(label("Ближайшее повторение: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}", 15f, jade), 12)
        }
        if (store.packReview) {
            val missed = store.missedReviewTasks()
            if (missed.isNotEmpty()) content.add(button("Повторить ошибки (${missed.size})", true) {
                startShort(false, missed, packReview = true, reviewPacks = store.hardReviewPackIds, selectedReview = store.selectedReview)
            }, 12)
        }
        extraPractice(content, afterSession = true)
        if (store.selectedStudyWords.isEmpty()) content.add(button("Выбрать слова") { store.route = "words"; render() }, 12)
        content.add(button("Трудные слова") { store.leaveSession(); store.route = "stats"; render() }, 8)
        content.add(button("К обзору") { store.leaveSession(); store.route = "learn"; render() }, 8)
    }
    private fun start(writing: Boolean) {
        store.refreshPractice()
        if (store.shortSession) store.leaveSession()
        if (store.studyWords.isEmpty()) { toast("Выберите хотя бы одно слово"); store.route = "words"; render(); return }
        if (store.writing != writing) { store.writing = writing; store.clearQuestion() }
        store.route = "quiz"
        if (store.current == null) store.nextQuestion()
        render()
    }
    private fun quiz() {
        if (store.sessionFinished && store.feedback == null) { shortResults(); return }
        val tasks = store.tasks()
        // Recheck restored questions too, including completed prompts saved before a voice change or update.
        if (store.current != null && store.current !in tasks) store.clearQuestion()
        if (tasks.isEmpty()) {
            if (store.studyWords.isEmpty()) heading("Выберите слова для практики", "Отметьте слова в выбранных наборах, чтобы начать занятие.")
            else heading("План на сегодня выполнен", "Можно продолжить учиться сверх дневного плана. Обычный дневной лимит останется прежним.")
            extraPractice(content)
            content.add(button("Выбрать слова") { store.route = "words"; render() }, 12)
            if (store.shortSession) content.add(button("Результаты занятия") { store.endSession(); render() }, 12)
            content.add(button("К обзору") { store.route = "learn"; render() }, 12)
            return
        }
        if (store.current == null) store.nextQuestion()
        val task = store.current
        if (task == null) { if (store.shortSession) shortResults() else results(tasks); return }
        quizActions.visibility = View.VISIBLE
        val word = store.studyWords.first { it.id == task.wordId }
        if (store.needsPreview(word) && store.feedback == null) {
            if (!tablet) content.add(label("Новое слово", 20f, jade, true)) else heading("Новое слово", "Познакомьтесь со словом перед первым вопросом.")
            val preview = card()
            val pronounce = DetailUi.icon(this, R.drawable.ic_speaker, "Произношение") { play(word) }
            preview.add(WordCardUi.titleRow(label(word.writtenForms.joinToString(" / "), 48f, jade, true), pronounce))
            preview.add(label("${word.pinyin} · ${word.numbered}", 24f), 12)
            preview.add(label(word.meanings.joinToString(" / "), 20f), 12)
            quizActions.add(button("Порядок черт") { strokeOrder(word) }, 4)
            content.add(preview, 16)
            content.addSpeechExample(word)
            quizActions.add(button("Запомнил — к вопросам", true) { store.introduce(word); render() }, 8)
            quizFooter(word)
            return
        }
        if (!tablet && store.shortSession) {
            val limit = store.session.optInt("limit")
            val count = if (limit > 0) "Ответов: ${store.sessionCount} из $limit" else "5 минут · Ответов: ${store.sessionCount}"
            val bar = row().apply { tag = "quizSessionBar" }
            bar.addView(label(count, 14f, jade, true), LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(button("Завершить занятие") { store.endSession(); render() }.apply {
                textSize = 14f; minHeight = dp(48)
            })
            content.add(bar)
        }
        if (tablet) {
            if (store.shortSession) {
                content.add(label(if (store.session.optInt("limit") > 0) "Ответов: ${store.sessionCount} из ${store.session.optInt("limit")}" else "Занятие: 5 минут · ${store.sessionCount} ответов. Завершится после текущего вопроса.", 16f, jade, true), 8)
                content.add(button("Завершить занятие") { store.endSession(); render() }, 8)
            }
            val mastery = tasks.count { store.studyEngine.peek(it).mastered }
            val top = row()
            top.addView(label(if (task.skill == Skill.WRITING) "НАПИСАНИЕ" else "ЗНАНИЕ", 12f, jade, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label("$mastery / ${tasks.size}", 14f, muted))
            content.add(top, 6); content.add(progress(tasks), 12)
            content.add(label("Ошибок: ${tasks.sumOf { store.studyEngine.peek(it).errors }} · ${task.skill.title}", 13f, muted), 12)
        }
        val question = if (tablet) card() else column()
        val answerKind = when (task.skill) {
            Skill.CHARACTER_MEANING, Skill.PINYIN_MEANING, Skill.AUDIO_MEANING -> "Ответ: перевод на русский"
            Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN -> "Ответ: пиньинь с тонами"
            Skill.CHARACTER_TONE -> "Ответ: тоны по порядку"
            Skill.WRITING -> "Ответ: иероглиф от руки"
            else -> "Ответ: выберите иероглиф"
        }
        if (tablet) {
            question.add(label(answerKind, 14f, jade, true))
        }
        if (tablet || store.feedback == null) question.add(label(task.skill.instruction, if (tablet) 20f else 16f, ink, true), if (tablet) 14 else 4)
        if (tablet && store.studyEngine.peek(task).mastered && store.feedback == null) question.add(label("Плановое повторение", 12f, jade), 6)
        val audio = task.skill in listOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)
        if (tablet && store.feedback == null) {
            val prompt = when (task.skill) {
                Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER -> "Послушайте запись"
                Skill.MEANING_PINYIN, Skill.MEANING_CHARACTER, Skill.WRITING -> word.meanings.first()
                Skill.PINYIN_MEANING, Skill.PINYIN_CHARACTER -> word.pinyin
                else -> word.writtenForms.joinToString(" / ")
            }
            val context = row()
            val text = column()
            text.add(label(task.skill.instruction, 13f, muted))
            text.add(label(prompt, 26f, jade, true).apply { contentDescription = "Текущий вопрос: $prompt" }, 2)
            context.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            keyboardQuestion.add(context)
        }
        if (audio && (tablet || store.feedback == null)) {
            if (store.feedback == null) {
                quizActions.addIcon(DetailUi.icon(this, R.drawable.ic_speaker, "Послушать") { play(word) }, 4, Gravity.CENTER_HORIZONTAL)
            }
            audioNotice = label(if (store.audioProvider == "hanping") "Звук воспроизводит Hanping" else voiceMessage ?: "Используется китайский голос устройства", 12f, muted)
            question.add(audioNotice!!, 8)
        } else if (!audio && (tablet || store.feedback == null)) {
            val prompt = when (task.skill) {
                Skill.MEANING_PINYIN, Skill.MEANING_CHARACTER, Skill.WRITING -> word.meanings.first()
                Skill.PINYIN_MEANING, Skill.PINYIN_CHARACTER -> word.pinyin
                else -> word.writtenForms.joinToString(" / ")
            }
            val isHanzi = task.skill in listOf(Skill.CHARACTER_MEANING, Skill.CHARACTER_PINYIN, Skill.CHARACTER_TONE)
            question.add(label(prompt, if (isHanzi && tablet) 64f else if (isHanzi) 46f else 28f, jade, isHanzi).apply {
                gravity = Gravity.CENTER
                if (!tablet) contentDescription = "Текущий вопрос: $prompt"
                val space = dp(if (tablet) 24 else 4)
                setPadding(0, space, 0, space)
            }, if (tablet) 10 else 4)
        }
        val splitQuestion = tablet && wideContent && (task.skill == Skill.WRITING || store.feedback != null)
        val response = if (splitQuestion) card() else question
        if (store.feedback == null) {
            when (task.skill) {
                Skill.MEANING_CHARACTER, Skill.PINYIN_CHARACTER, Skill.AUDIO_CHARACTER -> {
                    val longChoices = store.choices.any { it.codePointCount(0, it.length) > 8 }
                    val columns = if (longChoices) 1 else 2
                    store.choices.chunked(columns).forEach { pair ->
                        val line = row()
                        pair.forEachIndexed { index, option -> line.addView(button(option) { store.draft = option; submit(word.acceptsForm(option)) }.apply { textSize = if (longChoices) 22f else 30f; minHeight = dp(if (tablet) 76 else 52) }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index < pair.lastIndex) marginEnd = dp(8) }) }
                        if (pair.size == 1 && columns == 2) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                        quizActions.add(line, 6)
                    }
                }
                Skill.CHARACTER_TONE -> {
                    val tones = Answers.tones(word)
                    val picker = quizActions
                    val status = label("", 14f, muted)
                    val history = label("", 14f, jade).apply {
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.START
                        gravity = Gravity.END
                    }
                    val summary = row()
                    summary.addView(status)
                    summary.addView(history, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
                    picker.add(summary, 4)
                    lateinit var reset: MaterialButton
                    fun updateTones() {
                        val draft = store.toneDraft
                        status.text = "Слог ${draft.size + 1} из ${tones.size}"
                        history.text = draft.joinToString(" → ")
                        reset.isEnabled = draft.isNotEmpty()
                    }
                    val labels = listOf("1   mā  · ровный", "2   má  · восходящий", "3   mǎ  · нисходяще-восходящий", "4   mà  · нисходящий", "5   ma  · нейтральный")
                    if (tablet) question.add(label(labels.joinToString("\n"), 14f, muted), 8)
                    val controls = labels.mapIndexed { index, title ->
                        val caption = title.substringBefore("  ·").replace("   ", " ")
                        button(caption) {
                            store.toneDraft.add(index + 1)
                            if (store.toneDraft.size == tones.size) submit(store.toneDraft == tones)
                            else { store.saveScreen(); updateTones() }
                        }.apply {
                            contentDescription = title
                            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, title)
                        }
                    }
                    reset = button(if (tablet) "Выбрать тоны заново" else "Заново") {
                        store.toneDraft.clear(); store.saveScreen(); updateTones()
                    }.apply { contentDescription = "Выбрать тоны заново" }
                    val columns = StudyLayout.toneColumns(contentWidthDp, resources.configuration.fontScale)
                    controls.chunked(columns).forEach { picker.quizButtonRow(it) }
                    picker.quizButtonRow(listOf(reset, button("Не знаю") { submit(false) }))
                    updateTones()
                }
                Skill.WRITING -> {
                    val pad = WritingGrid(this, word.hanzi.codePointCount(0, word.hanzi.length), store.strokes) { store.strokes = it; store.saveDraft() }
                    if (!tablet && store.revealed) response.add(label("${word.writtenForms.joinToString(" / ")}   ${word.pinyin}", 32f, jade, true), 6)
                    response.add(pad, if (tablet) 12 else 4)
                    quizActions.add(button("Очистить поле") { pad.clear() }, 4)
                    response.add(label("Вспомните также количество и порядок черт. Оценка самостоятельная: приложение не проверяет почерк или порядок черт.", 13f, muted), 10)
                    if (!store.revealed) quizActions.add(button("Показать ответ", true) { store.revealed = true; render() }, 14)
                    else {
                        if (tablet) response.add(label("${word.writtenForms.joinToString(" / ")}   ${word.pinyin}", 38f, jade), 18)
                        if (word.writtenForms.size > 1) response.add(label("Подходит любой из указанных вариантов написания.", 14f, muted), 6)
                        response.add(label(word.hint, 15f, muted), 6)
                        quizActions.quizButtonRow(listOf(button("Не помню") { submit(false) }, button("Помню", true) { submit(true) }))
                    }
                }
                else -> {
                    val isPinyin = task.skill in listOf(Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN)
                    val input = field(if (isPinyin) "Пиньинь с тонами" else "Русский перевод", store.draft, quizActions)
                    answerInput = input
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    input.imeOptions = EditorInfo.IME_ACTION_DONE
                    input.doAfterTextChanged { store.draft = it.toString(); store.saveDraft() }
                    fun check() { if (input.text.toString().isBlank()) { input.error = "Введите ответ"; return }; submit(if (isPinyin) Answers.pinyinMatches(input.text.toString(), word.numbered) else Answers.meaningMatches(input.text.toString(), word)) }
                    input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { check(); true } else false }
                    if (isPinyin) response.add(label("Можно использовать знаки тона или цифры 1–5. Для ü подходят v и u:.", 13f, muted), 8)
                    val verify = button("Проверить", true) { check() }
                    quizActions.quizButtonRow(listOf(button("Не знаю") { submit(false) }, verify))
                }
            }
            if (task.skill in StudyStore.characterSkills) quizActions.add(button("Не знаю — показать ответ") { submit(false) }, 6)
        } else {
            val correct = store.feedback == true
            val result = card(if (store.answerIgnored) paper else if (correct) pale else blush)
            result.add(label(if (store.answerIgnored) "Ответ не учтён" else if (correct) "✓  Верно" else "Неверно", 22f, if (store.answerIgnored) ink else if (correct) jade else red, true))
            fun showSolution() {
                result.add(label(word.writtenForms.joinToString(" / "), 48f, jade), 12)
                result.add(label("${word.pinyin}  ·  ${Answers.tones(word).joinToString(" + ")}", 20f, ink, true), 4)
                result.add(label(word.meanings.joinToString(" / "), 18f), 8)
            }
            if (!tablet) showSolution()
            if (store.draft.isNotBlank()) result.add(label("Ваш ответ: ${store.draft}", 14f, muted), 8)
            if (store.toneDraft.isNotEmpty()) result.add(label("Ваши тоны: ${store.toneDraft.joinToString(" → ")}", 14f, muted), 8)
            if (!correct && !store.answerIgnored) {
                if (task.skill in listOf(Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN)) {
                    Answers.pinyinFeedback(store.draft, word.numbered).forEach { result.add(label(it, 18f, red, true), 8) }
                }
                if (task.skill == Skill.CHARACTER_TONE && store.toneDraft.isNotEmpty()) {
                    Answers.tones(word).forEachIndexed { index, tone ->
                        val given = store.toneDraft.getOrNull(index)
                        if (given != tone) result.add(label("Слог ${index + 1}: ${given ?: "—"} → $tone", 18f, red, true), 8)
                    }
                }
            }
            if (tablet) showSolution()
            if (!correct && !store.answerIgnored && task.skill in StudyStore.characterSkills && store.draft in store.choices) {
                result.add(label("Сравните: ${word.hanzi} ≠ ${store.draft}", 24f, jade), 12)
                store.studyWords.firstOrNull { it.acceptsForm(store.draft) }?.let { other ->
                    result.add(label("${other.hanzi} · ${other.pinyin} · ${other.meanings.joinToString(" / ")}", 17f), 6)
                }
            }
            result.add(label(if (store.answerIgnored) "Попытка отменена. Ошибки, серия ответов и срок повторения восстановлены." else "Следующее повторение: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.studyEngine.peek(task).due))}.", 13f, if (store.answerIgnored) muted else if (correct) jade else red), 12)
            response.add(result, if (tablet) 14 else 0)
            response.addSpeechExample(word)
            if (!store.answerIgnored && store.canIgnoreAnswer && !correct && store.draft.isNotBlank() && task.skill in listOf(Skill.CHARACTER_MEANING, Skill.PINYIN_MEANING, Skill.AUDIO_MEANING)) {
                quizActions.add(button("Засчитать мой ответ") { acceptSynonym(word, task) }, 4)
            }
            val undoTitle = "Ответил случайно — не учитывать"
            val undo = if (store.canIgnoreAnswer) button(if (tablet) undoTitle else "Отменить ответ") {
                voice.stop()
                store.ignoreAnswer(); render()
            }.apply { contentDescription = undoTitle } else null
            val next = button("Дальше", true) { voice.stop(); store.nextQuestion(); render() }
            quizActions.quizButtonRow(listOfNotNull(undo, next))
        }
        if (splitQuestion) {
            val panels = row().apply { gravity = Gravity.TOP }
            panels.addView(question, LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginEnd = dp(18) })
            panels.addView(response, LinearLayout.LayoutParams(0, -2, 1.1f))
            content.add(panels, 18)
        } else content.add(question, if (tablet) 18 else 4)
        quizFooter(word)
    }
    private fun quizFooter(word: Word) {
        val overview = button("К обзору") { store.route = "learn"; render() }
        val toolsAvailable = store.shortSession || store.needsPreview(word) || store.feedback != null ||
            store.revealed || store.current?.skill in setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)
        if (toolsAvailable) {
            quizActions.addWithIcon(overview, listIcon(R.drawable.ic_list_more, "Ещё") { quizTools(word) }, 4)
        } else quizActions.quizButtonRow(listOf(overview))
    }

    private fun quizTools(word: Word) {
        val sheet = BottomSheetDialog(this)
        val tools = column().apply { setPadding(dp(18), dp(12), dp(18), dp(24)) }
        fun action(title: String, block: () -> Unit) { tools.add(button(title) { sheet.dismiss(); block() }, 6) }
        val answerVisible = store.feedback != null || store.needsPreview(word) || store.revealed
        if (answerVisible || store.current?.skill in setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)) {
            action("Произношение") { play(word) }
            if (store.audioProvider == "hanping") action("Нет звука? Голос устройства") { useDeviceVoice(word) }
        }
        if (answerVisible) {
            action("Порядок черт") { strokeOrder(word) }
            action("Словарь") { wordDetails(word) }
            tools.addSpeechExample(word)
        }
        if (store.shortSession) action("Завершить занятие") { voice.stop(); store.endSession(); render() }
        tools.addIcon(DetailUi.icon(this, R.drawable.ic_close, "Закрыть меню", action = sheet::dismiss), 6, Gravity.END)
        sheet.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(tools) })
        sheet.behavior.maxHeight = resources.displayMetrics.heightPixels * 4 / 5
        sheet.show()
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun scheduleListeningAudio() {
        pendingListening?.let { window.decorView.removeCallbacks(it) }; pendingListening = null
        if (!::store.isInitialized || !::voice.isInitialized) return
        val task = store.current
        val word = store.studyWords.firstOrNull { it.id == task?.wordId }
        if (store.route != "quiz" || store.feedback != null || store.sessionFinished || word == null ||
            task?.skill !in setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER) || store.needsPreview(word)) {
            lastListeningPrompt = null
            return
        }
        val key = "${System.identityHashCode(store)}:${store.activePackId}:${task?.key}:${store.studyEngine.step}"
        if (lastListeningPrompt == key) return
        pendingListening = Runnable {
            pendingListening = null
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
                store.route != "quiz" || store.current != task || store.feedback != null) return@Runnable
            lastListeningPrompt = key
            // Autoplay reports a failure inline; the replay button remains available.
            voice.play(word, store.audioProvider, store.slowAudio)?.let {
                voiceMessage = it; audioNotice?.text = it
            }
        }.also { window.decorView.post(it) }
    }
    private fun submit(correct: Boolean) {
        val task = store.current ?: return
        if (store.feedback != null) return
        voice.stop()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(answerInput?.windowToken, 0)
        store.recordAnswer(correct)
        render()
        if (store.speakAfterAnswer) store.studyWords.firstOrNull { it.id == task.wordId }?.let { play(it) }
    }
    private val starterExamples by lazy { StudyStore.decodeWords(StudyStore.seedJson).associateBy(SharedWords::identity) }

    private fun LinearLayout.addSpeechExample(word: Word) {
        val source = if (word.example.isBlank()) starterExamples[SharedWords.identity(word)] ?: word else word
        if (source.example.isBlank() && word.hint.isBlank()) return
        val panel = card()
        add(panel, 12)
        if (source.example.isBlank()) {
            panel.add(label("Пояснение к слову", 18f, jade, true))
            panel.add(label(word.hint), 8)
            return
        }

        panel.add(label("Слово в речи", 18f, jade, true))
        val sentence = SpannableString(source.example)
        // Match longer forms first when an alternative contains another written form.
        val forms = word.writtenForms.sortedByDescending { it.length }
        if (forms.isNotEmpty()) {
            val pattern = Regex(forms.joinToString("|") { Regex.escape(it) })
            for (match in pattern.findAll(source.example)) {
                val start = match.range.first
                val end = match.range.last + 1
                sentence.setSpan(BackgroundColorSpan(pale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sentence.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val example = label("", 28f).apply {
            text = sentence
            WordDetailsSheet.enableWordLookup(this, { vocabularySearch(it) })
        }
        val pronounce = DetailUi.icon(this@MainActivity, R.drawable.ic_speaker, "Слушать фразу") {
            voice.play(word.copy(hanzi = source.example), "system", store.slowAudio)?.let { message("Звук недоступен", it) }
        }
        panel.add(WordCardUi.titleRow(example, pronounce), 12)
        panel.add(label("Выделите слово в примере и нажмите «Найти слово».", 14f, muted), 6)
        panel.add(label("Произнесите фразу и попробуйте её перевести.", 15f, muted), 12)

        val details = column().apply {
            visibility = View.GONE
            if (source.examplePinyin.isNotBlank()) add(label(source.examplePinyin, 18f, jade))
            if (source.exampleTranslation.isNotBlank()) add(label(source.exampleTranslation, 18f), 6)
            if (word.hint.isNotBlank()) {
                add(label("Пояснение к слову", 15f, jade, true), 12)
                add(label(word.hint), 4)
            }
        }
        val reveal = button("Показать разбор") { }
        reveal.setOnClickListener {
            val opening = details.visibility == View.GONE
            details.visibility = if (opening) View.VISIBLE else View.GONE
            reveal.text = if (opening) "Скрыть разбор" else "Показать разбор"
        }
        panel.add(reveal, 8)
        panel.add(details, 12)
    }

    private fun acceptSynonym(word: Word, task: Task) {
        MaterialAlertDialogBuilder(this).setTitle("Добавить синоним?")
            .setMessage("«${store.draft}» будет приниматься как перевод «${word.hanzi}» во всех наборах с такой же карточкой. Этот ответ станет верным, а ошибочная отметка будет отменена.")
            .setPositiveButton("Засчитать") { _, _ ->
                if (!store.correctAnswer()) return@setPositiveButton
                store.addStudySynonym(word, store.draft.trim())
                store.feedback = true; store.save(); render()
            }.setNegativeButton("Отмена", null).show()
    }
    private fun results(tasks: List<Task>) {
        heading("Сейчас повторений нет", "Прогресс сохранён. Можно выучить новые слова или дополнительно попрактиковать знакомые.")
        val box = card(jade)
        val learned = tasks.count { store.studyEngine.peek(it).mastered }
        box.add(label("$learned / ${tasks.size} навыков изучено", 28f, Color.WHITE, true))
        box.add(label("Попыток: ${tasks.sumOf { store.studyEngine.peek(it).attempts }} · Ошибок: ${tasks.sumOf { store.studyEngine.peek(it).errors }}", 14f, Color.WHITE), 16)
        content.add(box, 24)
        val earliest = store.studyEngine.dueSchedule(tasks).values.filter { it > System.currentTimeMillis() }.minOrNull()
        earliest?.let { content.add(label("Ближайшее повторение: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}", 16f, jade), 18) }
        content.add(button("Проверить повторения", true) { store.nextQuestion(); render() }, 20)
        extraPractice(content)
        content.add(button(if (store.writing) "Перейти к знаниям" else "Перейти к написанию") { start(!store.writing) }, 10)
        content.add(button("Посмотреть трудные слова") { store.route = "stats"; render() }, 10)
        content.add(button("К обзору") { store.route = "learn"; render() }, 10)
    }
    private fun importHub() {
        lateinit var hub: AlertDialog
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        box.add(label("1. Скопируйте правила и отправьте их любой внешней модели вместе со своими словами.\n2. Скопируйте её ответ или скачайте файл.\n3. Вернитесь сюда, вставьте ответ и проверьте набор.", 16f, ink))
        box.add(button("1 · Правила для модели", true) { showPackPrompt() }, 16)
        box.add(button("2 · Вставить ответ модели") { hub.dismiss(); pastePack() }, 10)
        box.add(button("Или выбрать файлы / ZIP") { hub.dismiss(); importFile.launch(arrayOf("*/*")) }, 10)
        box.add(label("Модель выбираете вы. Приложение само ничего ей не отправляет. Перед созданием проверьте переводы и тоны.", 13f, muted), 12)
        hub = MaterialAlertDialogBuilder(this).setTitle("Новый набор с помощью ИИ")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Закрыть", null).create()
        hub.show()
    }
    private fun showPackPrompt() {
        val text = label(PackImport.prompt, 15f, ink).apply { setPadding(dp(24), dp(12), dp(24), dp(12)); setTextIsSelectable(true) }
        MaterialAlertDialogBuilder(this).setTitle("Правила для модели")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Скопировать правила") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Правила Chinlearn", PackImport.prompt))
                toast("Правила скопированы. Добавьте к ним свои слова в чате с моделью.")
            }.setNegativeButton("Назад", null).show()
    }
    private fun pastePack() {
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val input = field("Ответ модели (JSON)", "", box).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5; maxLines = 10; gravity = Gravity.TOP
        }
        val error = label("Можно вставить JSON или ответ с одним блоком кода JSON.", 13f, muted).apply { setTextIsSelectable(true) }
        box.add(button("Вставить из буфера") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val value = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
            if (value.length > 2_000_000) error.text = "Ответ слишком большой: максимум 2 МБ."
            else if (value.isBlank()) error.text = "Буфер пуст. Скопируйте ответ модели."
            else input.setText(value)
        }, 10)
        box.add(error, 10)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Вставить набор")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Проверить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { previewImport(PackImport.parse(input.text.toString())) { dialog.dismiss() } }
            catch (e: Exception) { error.text = e.message ?: "Проверьте JSON."; error.setTextColor(red) }
        } }; dialog.show()
    }
    private fun previewImport(preview: PackImport.Preview, created: () -> Unit = {}) {
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        box.add(label("${preview.words.size} слов. Создадим отдельный набор. Слова с одинаковыми написанием, произношением и переводами получат общий прогресс с существующими карточками. Остальные слова начнутся с нуля.", 15f, muted))
        var importDialog: AlertDialog? = null
        val warnings = store.duplicateWarnings(preview.words)
        if (warnings.isNotEmpty()) box.add(label("Возможные дубликаты (${warnings.size}):\n${warnings.take(20).joinToString("\n")}", 15f, red), 12)
        val name = field("Название набора", preview.name, box)
        box.add(button("Обновить существующий набор…") {
            val packs = store.packNames
            MaterialAlertDialogBuilder(this).setTitle("Какой набор обновить?")
                .setItems(packs.map { it.second }.toTypedArray()) { _, index ->
                    previewPackUpdate(packs[index].first, name.text.toString(), preview.words) { importDialog?.dismiss(); created() }
                }.setNegativeButton("Отмена", null).show()
        }, 12)
        preview.words.take(8).forEach { word ->
            box.add(label("${word.writtenForms.joinToString(" / ")} · ${word.pinyin}\n${word.meanings.joinToString(" / ")}", 16f, ink), 12)
        }
        if (preview.words.size > 8) box.add(label("И ещё ${preview.words.size - 8} слов. Все слова можно просмотреть и изменить после импорта.", 13f, muted), 12)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Проверьте набор")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Создать набор", null).setNegativeButton("Назад", null).create()
        importDialog = dialog
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { store.importPack(name.text.toString(), preview.words); store.route = "words"; dialog.dismiss(); created(); render(); toast("Набор создан") }
            catch (e: Exception) { name.error = e.message }
        } }; dialog.show()
    }
    private fun previewPackUpdate(target: String, name: String, words: List<Word>, completed: () -> Unit) {
        try {
            val update = store.previewUpdate(words, target)
            val box = column().apply { setPadding(dp(24), dp(12), dp(24), dp(16)) }
            box.add(label("${store.packNames.first { it.first == target }.second} → $name\nДобавится: ${update.added.size} · Изменится: ${update.changed.size}\nСлов вне файла останется: ${update.retained.size}\nПрогресс и выбор существующих слов сохранятся. Удалений нет.", 16f))
            update.added.forEach { box.add(label("+ ${it.hanzi} · ${it.pinyin} · ${it.meanings.joinToString(" / ")}"), 12) }
            update.changed.forEach { (old, new) ->
                fun details(w: Word) = "${w.hanzi} · ${w.pinyin} (${w.numbered}) · ${w.meanings.joinToString(" / ")}\nФормы: ${w.alternatives.joinToString()}\nВарианты: ${w.distractors.joinToString()}\n${w.hint}\n${w.example} ${w.examplePinyin} ${w.exampleTranslation}"
                box.add(label("Было: ${details(old)}\nСтало: ${details(new)}", 15f), 16)
            }
            MaterialAlertDialogBuilder(this).setTitle("Изменения набора")
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton("Применить") { _, _ ->
                    try {
                        store.editPack(target); store.updatePack(name, words); store.route = "words"
                        completed(); render(); toast("Набор обновлён, прогресс сохранён")
                    } catch (e: Exception) { message("Проверьте набор", e.message ?: "Ошибка обновления") }
                }.setNegativeButton("Отмена", null).show()
        } catch (e: Exception) { message("Проверьте набор", e.message ?: "Ошибка обновления") }
    }

    private fun practicePicker(parent: LinearLayout = content) {
        parent.add(button("Наборы для практики: ${store.practicePackIds.size} ▾") {
            val names = store.visiblePackNames
            val chosen = store.practicePackIds.toMutableSet()
            val packsByFolder = names.groupBy { store.packFolder(it.first) }
            val allFolders = listOf("").filter { it in packsByFolder } + FolderPaths.all(packsByFolder.keys.toList())
            val totals = mutableMapOf<String, Int>()
            val selectedCounts = mutableMapOf<String, Int>()
            val expanded = mutableSetOf("")
            for ((path, members) in packsByFolder) {
                val count = members.count { it.first in chosen }
                for (folder in FolderPaths.ancestors(path) + path) {
                    totals[folder] = totals.getOrDefault(folder, 0) + members.size
                    selectedCounts[folder] = selectedCounts.getOrDefault(folder, 0) + count
                    if (count > 0) expanded.add(folder)
                }
            }
            var folders = emptyList<String>()
            var groups = emptyList<List<Pair<String, String>>>()
            val tree = ExpandableListView(this).apply {
                dividerHeight = 0; setGroupIndicator(null)
                setChildDivider(ColorDrawable(Color.TRANSPARENT))
                isNestedScrollingEnabled = true
            }
            lateinit var adapter: BaseExpandableListAdapter
            fun refresh() {
                folders = allFolders.filter { FolderPaths.ancestors(it).all(expanded::contains) }
                groups = folders.map { packsByFolder[it].orEmpty() }
                adapter.notifyDataSetChanged()
                folders.indices.forEach { if (folders[it] in expanded) tree.expandGroup(it) else tree.collapseGroup(it) }
            }
            adapter = object : BaseExpandableListAdapter() {
                override fun getGroupCount() = folders.size
                override fun getChildrenCount(groupPosition: Int) = groups[groupPosition].size
                override fun getGroup(groupPosition: Int) = folders[groupPosition]
                override fun getChild(groupPosition: Int, childPosition: Int) = groups[groupPosition][childPosition]
                override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
                override fun getChildId(groupPosition: Int, childPosition: Int) = childPosition.toLong()
                override fun hasStableIds() = false
                override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true
                override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup): View {
                    val path = folders[groupPosition]
                    val title = FolderPaths.title(path).ifEmpty { "Без папки" }
                    val total = totals.getValue(path)
                    val selected = selectedCounts.getValue(path)
                    return label("${if (isExpanded) "▾" else "▸"} $title · $selected/$total", 17f, jade, true).apply {
                        setPadding(dp(16 + FolderPaths.depth(path) * 16), dp(14), dp(16), dp(14))
                        minimumHeight = dp(56)
                        contentDescription = "Папка: ${path.ifEmpty { title }}, выбрано $selected из $total"
                        ViewCompat.setStateDescription(this, if (isExpanded) "Развёрнута" else "Свёрнута")
                    }
                }
                override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean,
                    convertView: View?, parent: ViewGroup): View {
                    val (id, name) = getChild(groupPosition, childPosition)
                    return CheckBox(this@MainActivity).apply {
                        text = name; textSize = 16f; setTextColor(ink)
                        setPadding(dp(32 + FolderPaths.depth(folders[groupPosition]) * 16), dp(8), dp(16), dp(8)); minHeight = dp(56)
                        buttonTintList = ColorStateList.valueOf(jade)
                        contentDescription = "Учить набор: ${store.packLabel(id, name)}"
                        isChecked = id in chosen
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen.add(id) else chosen.remove(id)
                            val path = store.packFolder(id)
                            for (folder in FolderPaths.ancestors(path) + path) {
                                selectedCounts[folder] = selectedCounts.getValue(folder) + if (checked) 1 else -1
                            }
                            notifyDataSetChanged()
                        }
                    }
                }
            }
            tree.setAdapter(adapter)
            tree.setOnGroupClickListener { _, _, index, _ ->
                val path = folders[index]
                if (!expanded.add(path)) expanded.remove(path)
                refresh(); true
            }
            refresh()
            val box = column().apply {
                add(label("Раскройте папку и отметьте наборы. Сворачивание папки не меняет выбор.", 14f, muted).apply {
                    setPadding(dp(24), dp(8), dp(24), dp(12))
                })
                addView(tree, LinearLayout.LayoutParams(-1,
                    minOf(dp(420), (resources.displayMetrics.heightPixels * 0.5f).toInt())))
            }
            val dialog = MaterialAlertDialogBuilder(this).setTitle("Наборы для практики")
                .setView(box).setPositiveButton("Применить", null).setNegativeButton("Отмена", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (chosen.isEmpty()) toast("Выберите хотя бы один набор")
                else { voice.stop(); store.choosePracticePacks(chosen); dialog.dismiss(); render() }
            } }; dialog.show()
        }, 12)
        parent.add(label(store.practiceLabel, 14f, muted), 6)
    }
    private fun chooseEditingPack(action: () -> Unit) {
        val names = store.packNames.filter { it.first in store.practicePackIds }
        if (names.size == 1) { store.editPack(names.first().first); action(); return }
        MaterialAlertDialogBuilder(this).setTitle("Выберите набор для изменения")
            .setItems(names.map { it.second }.toTypedArray()) { _, index ->
                store.editPack(names[index].first); action()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun manageLibrary(folder: String? = null, hidden: Boolean = false) {
        val box = column().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val chosen = mutableSetOf<String>()
        val expanded = mutableSetOf("").apply { folder?.let { addAll(FolderPaths.ancestors(it) + it) } }
        var includeHidden = hidden
        var folders = emptyList<String>()
        var groups = emptyList<List<Pair<String, String>>>()
        val tree = ExpandableListView(this).apply { dividerHeight = 0; setGroupIndicator(null) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Наборы и папки")
            .setView(box).setPositiveButton("Готово") { _, _ -> render() }.create()
        box.add(label("Удерживайте ручку справа, чтобы перенести набор в папку. Для нескольких наборов используйте отметки.", 13f, muted), 4)
        val move = button("Переместить выбранные (0)") {
            val ids = chosen.toSet()
            dialog.dismiss(); movePacksDialog(ids)
        }.apply { isEnabled = false }
        box.add(move, 6)
        fun updateSelection() {
            move.text = "Переместить выбранные (${chosen.size})"
            move.isEnabled = chosen.isNotEmpty()
        }
        var rebuilding = false
        lateinit var adapter: BaseExpandableListAdapter
        fun refresh() {
            rebuilding = true
            folders = (listOf("") + store.folderNames).filter { FolderPaths.ancestors(it).all(expanded::contains) }
            groups = folders.map { name -> store.packNames.filter { (id, _) ->
                store.packFolder(id) == name && (includeHidden || !store.isPackHidden(id))
            } }
            chosen.retainAll(store.packNames.filter { includeHidden || !store.isPackHidden(it.first) }.map { it.first }.toSet())
            updateSelection()
            adapter.notifyDataSetChanged()
            folders.indices.forEach { if (folders[it] in expanded) tree.expandGroup(it) else tree.collapseGroup(it) }
            rebuilding = false
        }
        fun packActions(id: String, name: String) {
            dialog.dismiss()
            MaterialAlertDialogBuilder(this).setTitle(name)
                .setItems(arrayOf("Переименовать / удалить", "Переместить в папку", if (store.isPackHidden(id)) "Показать" else "Скрыть")) { _, which ->
                    when (which) {
                        0 -> { store.editPack(id); managePack() }
                        1 -> movePacksDialog(setOf(id))
                        2 -> try {
                            voice.stop(); store.setPackHidden(id, !store.isPackHidden(id)); render()
                            manageLibrary(store.packFolder(id), includeHidden)
                        } catch (e: IllegalArgumentException) { message("Не удалось скрыть", e.message ?: "") }
                    }
                }.setNegativeButton("Назад") { _, _ -> manageLibrary(store.packFolder(id), includeHidden) }.show()
        }
        adapter = object : BaseExpandableListAdapter() {
            override fun getGroupCount() = folders.size
            override fun getChildrenCount(groupPosition: Int) = groups[groupPosition].size
            override fun getGroup(groupPosition: Int) = folders[groupPosition]
            override fun getChild(groupPosition: Int, childPosition: Int) = groups[groupPosition][childPosition]
            override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
            override fun getChildId(groupPosition: Int, childPosition: Int) = childPosition.toLong()
            override fun hasStableIds() = false
            override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true
            override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup): View {
                val name = folders[groupPosition]
                val title = FolderPaths.title(name).ifEmpty { "Без папки" }
                val count = store.packNames.count { FolderPaths.contains(name, store.packFolder(it.first)) && (includeHidden || !store.isPackHidden(it.first)) }
                return row().apply {
                    setPadding(dp(FolderPaths.depth(name) * 16), 0, 0, 0)
                    val heading = label("${if (isExpanded) "▾" else "▸"} $title · $count", 17f, jade, true).apply {
                        setPadding(dp(8), dp(12), dp(8), dp(12)); minimumHeight = dp(56)
                        contentDescription = "Папка: ${name.ifEmpty { title }}"
                        ViewCompat.setStateDescription(this, if (isExpanded) "Развёрнута" else "Свёрнута")
                        setOnClickListener {
                            if (!expanded.add(name)) expanded.remove(name)
                            refresh()
                        }
                    }
                    addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
                    if (name.isNotEmpty()) addView(listIcon(R.drawable.ic_list_more, "Действия с папкой: $name") {
                        MaterialAlertDialogBuilder(this@MainActivity).setTitle(name)
                            .setItems(arrayOf("Переименовать папку", "Переместить папку", "Убрать папку, сохранить наборы")) { _, which ->
                                dialog.dismiss()
                                when (which) {
                                    0 -> folderNameDialog(FolderPaths.title(name)) {
                                        store.renameFolder(name, it)
                                        manageLibrary(FolderPaths.child(FolderPaths.parent(name), it), includeHidden)
                                    }
                                    1 -> moveFolderDialog(name, includeHidden)
                                    else -> try { store.removeFolder(name); render(); manageLibrary(FolderPaths.parent(name), includeHidden) }
                                        catch (e: IllegalArgumentException) { message("Не удалось убрать папку", e.message ?: "") }
                                }
                            }.setNegativeButton("Отмена", null).show()
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    setOnDragListener { view, event ->
                        val ids = (event.localState as? Set<*>)?.filterIsInstance<String>()?.toSet()
                        if (ids.isNullOrEmpty() || ids.any { id -> store.packNames.none { it.first == id } }) false
                        else when (event.action) {
                            DragEvent.ACTION_DRAG_STARTED -> true
                            DragEvent.ACTION_DRAG_ENTERED -> { view.setBackgroundColor(pale); true }
                            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.setBackgroundColor(Color.TRANSPARENT); true }
                            DragEvent.ACTION_DROP -> {
                                store.movePacks(ids, name)
                                chosen.clear(); expanded.addAll(FolderPaths.ancestors(name) + name)
                                view.setBackgroundColor(Color.TRANSPARENT)
                                tree.post { refresh(); render() }
                                toast("Перемещено наборов: ${ids.size}")
                                true
                            }
                            else -> true
                        }
                    }
                }
            }
            override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean,
                convertView: View?, parent: ViewGroup): View {
                val (id, name) = getChild(groupPosition, childPosition)
                fun drag(view: View): Boolean {
                    val ids = if (id in chosen) chosen.toSet() else setOf(id)
                    return view.startDragAndDrop(ClipData.newPlainText("Наборы", ""), View.DragShadowBuilder(view), ids, 0)
                }
                return row().apply {
                    setPadding(dp(20 + FolderPaths.depth(folders[groupPosition]) * 16), 0, 0, 0)
                    addView(CheckBox(this@MainActivity).apply {
                        minWidth = dp(48); minHeight = dp(56); buttonTintList = ColorStateList.valueOf(jade)
                        contentDescription = "Выбрать набор: $name"; isChecked = id in chosen
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen.add(id) else chosen.remove(id)
                            updateSelection()
                        }
                    }, LinearLayout.LayoutParams(dp(48), -2))
                    addView(label(name + if (store.isPackHidden(id)) " · скрыт" else "", 16f, ink).apply {
                        minimumHeight = dp(56); gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        contentDescription = "Набор: $name"
                        setOnClickListener { packActions(id, name) }
                        setOnLongClickListener { drag(it) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(listIcon(R.drawable.ic_list_drag, "Перетащить набор: $name") { toast("Удерживайте, чтобы перетащить на папку") }.apply {
                        setOnLongClickListener { drag(it) }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
            }
        }
        tree.setAdapter(adapter)
        tree.setOnGroupClickListener { _, _, index, _ ->
            if (!rebuilding) { val path = folders[index]; if (!expanded.add(path)) expanded.remove(path); refresh() }
            true
        }
        tree.setOnDragListener { _, event ->
            if (event.localState !is Set<*>) false else {
                if (event.action == DragEvent.ACTION_DRAG_LOCATION) {
                    if (event.y < dp(56)) tree.smoothScrollBy(-dp(40), 120)
                    else if (event.y > tree.height - dp(56)) tree.smoothScrollBy(dp(40), 120)
                }
                event.action != DragEvent.ACTION_DROP
            }
        }
        box.add(CheckBox(this).apply {
            text = "Показывать скрытые наборы"; isChecked = includeHidden
            minHeight = dp(48); buttonTintList = ColorStateList.valueOf(jade)
            setOnCheckedChangeListener { _, checked -> includeHidden = checked; refresh() }
        })
        box.addView(tree, LinearLayout.LayoutParams(-1, minOf(dp(420), (resources.displayMetrics.heightPixels * 0.5f).toInt())))
        refresh(); dialog.show()
    }

    private fun folderNameDialog(initial: String = "", action: (String) -> Unit) {
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val name = field("Название папки", initial, box)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Папка").setView(box)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена") { _, _ -> manageLibrary() }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text.toString().trim()
            if (value.length !in 1..80) name.error = "Введите от 1 до 80 знаков"
            else try { action(value); dialog.dismiss(); render() } catch (e: IllegalArgumentException) { name.error = e.message }
        } }; dialog.show()
    }

    private fun moveFolderDialog(source: String, includeHidden: Boolean) {
        val targets = listOf("") + store.folderNames.filterNot { FolderPaths.contains(source, it) }
        MaterialAlertDialogBuilder(this).setTitle("Переместить папку «${FolderPaths.title(source)}»")
            .setItems(targets.map { it.ifEmpty { "В корень" } }.toTypedArray()) { _, index ->
                try {
                    store.moveFolder(source, targets[index]); render()
                    manageLibrary(FolderPaths.child(targets[index], FolderPaths.title(source)), includeHidden)
                } catch (e: IllegalArgumentException) { message("Не удалось переместить", e.message ?: "") }
            }.setNegativeButton("Отмена") { _, _ -> manageLibrary(source, includeHidden) }.show()
    }

    private fun movePacksDialog(ids: Set<String>) {
        val folders = store.folderNames
        MaterialAlertDialogBuilder(this).setTitle("Переместить наборы: ${ids.size}")
            .setItems((listOf("Без папки", "＋ Новая папка") + folders).toTypedArray()) { _, index ->
                if (index == 1) folderNameDialog { store.movePacks(ids, it); manageLibrary(it) }
                else {
                    val folder = if (index == 0) "" else folders[index - 2]
                    store.movePacks(ids, folder); render(); manageLibrary(folder)
                }
            }.setNegativeButton("Отмена") { _, _ -> manageLibrary() }.show()
    }

    private fun managePack() {
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val name = field("Название набора", store.packName, box)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Набор слов").setView(box)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить набор") { _, _ ->
                if (store.packNames.size == 1) message("Нельзя удалить", "Должен остаться хотя бы один набор.")
                else MaterialAlertDialogBuilder(this).setTitle("Удалить «${store.packName}»?")
                    .setMessage("Слова и прогресс этого набора будут удалены. Остальные наборы сохранятся.")
                    .setPositiveButton("Удалить") { _, _ -> try { voice.stop(); store.deletePack(); render() } catch (e: IllegalArgumentException) { message("Нельзя удалить", e.message ?: "") } }.setNegativeButton("Отмена", null).show()
            }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { store.renamePack(name.text.toString()); dialog.dismiss(); render() } catch (e: Exception) { name.error = e.message }
        } }; dialog.show()
    }
    private var libraryCardFilter = ""

    private fun library() {
        heading("Ваши слова", "Отметьте слова, которые хотите учить.")
        content.add(button("＋ Новый набор", true) { importHub() }, 12)
        content.add(button("Мои тексты") { reader().library() }, 8)
        practicePicker()
        content.add(button("Управление наборами и папками") { manageLibrary() }, 8)
        content.twoButtons(button("＋ Слово") { chooseEditingPack { editWord(null) } }, button("Файлы") {
            MaterialAlertDialogBuilder(this).setTitle("Наборы слов").setItems(arrayOf("Создать набор с помощью ИИ / вставить ответ", "Импортировать файл", "Экспортировать набор")) { _, which ->
                when (which) {
                    0 -> importHub()
                    1 -> importFile.launch(arrayOf("*/*"))
                    2 -> chooseEditingPack { exportFile.launch("chinlearn-words.json") }
                }
            }.show()
        })
        content.twoButtons(button("Выбрать все") { store.selectLibraryWords(true); render() }, button("Снять выбор") { store.selectLibraryWords(false); render() })
        // Read the library once. Searching and updating counters must not serialize progress again.
        val sharedPacks = store.sharedPackNames()
        val packs = store.libraryPacks()
        val entries = packs.flatMap { pack -> pack.words.map { word -> LibraryEntry(pack, word, sharedPacks[SharedWords.identity(word)].orEmpty()) } }
        var selectedCount = entries.count { it.selected }
        fun selectionText() = "Выбрано $selectedCount из ${entries.size}"
        content.add(label("Отметьте отдельно: «Узнаю слово» — знание значения, пиньиня и произношения; «Умею писать» — написание по памяти. Отмеченные навыки вернутся на повторение завтра. У одинаковых слов прогресс общий во всех наборах. Выбор «Учить» остаётся отдельным; в совместной практике слово появляется один раз.", 13f, muted), 12)
        val selectedLabel = label(selectionText(), 14f, jade)
        content.add(selectedLabel, 16)
        val searchBar = column().apply {
            val side = dp(if (tablet) 24 else 18)
            setPadding(side, dp(8), side, 0)
        }
        val search = searchField("Найти слово, пиньинь или перевод", libraryCardFilter, searchBar)
        search.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) false else {
                hideLibrarySearchKeyboard(search)
                true
            }
        }
        val status = label("", 14f, muted).apply { visibility = View.GONE }
        searchBar.add(status, 4)
        val owner = store
        val savedEntries = entries.associateBy { VocabularyReference(it.pack.id, it.word.id) }.toMutableMap()
        val loadedPacks = packs.associateBy { it.id }.toMutableMap()
        val allCards = entries.map { LibraryCardResult(it.word, saved = it) }
        val snapshot by lazy { owner.vocabularyLookup() }
        var filtered = allCards
        val columns = cardColumns()
        val adapter = object : BaseAdapter() {
            override fun getCount() = (filtered.size + columns - 1) / columns
            override fun getItem(position: Int) = filtered[position * columns]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val line = convertView as? LinearLayout ?: row().apply {
                    gravity = Gravity.TOP
                    setPadding(0, dp(12), 0, 0)
                    tag = List(columns) { index ->
                        LibraryWordCard(onSelectionChanged = { checked ->
                            selectedCount += if (checked) 1 else -1
                            selectedLabel.text = selectionText()
                            selectionLabel?.text = "${store.selectedStudyWords.size} слов"
                        }, onKnowledgeChanged = { word, category, checked ->
                            val identity = SharedWords.identity(word)
                            savedEntries.values.filter { SharedWords.identity(it.word) == identity }.forEach { entry ->
                                if (category == WordKnowledge.RECOGNITION) entry.recognized = checked else entry.writable = checked
                            }
                            notifyDataSetChanged()
                        }).also { holder ->
                            addView(holder.view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                                if (index < columns - 1) marginEnd = dp(14)
                            })
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val holders = line.tag as List<LibraryWordCard>
                holders.forEachIndexed { index, holder ->
                    val entry = filtered.getOrNull(position * columns + index)
                    holder.view.visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
                    if (entry != null) holder.bind(entry)
                }
                return line
            }
        }
        installSectionList("words", adapter, searchBar = searchBar)
        val list = checkNotNull(sectionList)
        val libraryHeader = content
        cancelLibrarySearch?.invoke()
        var pending: Runnable? = null
        var work: java.util.concurrent.Future<*>? = null
        var signal: CancellationSignal? = null
        var generation = 0
        fun cancel() {
            generation++
            pending?.let(librarySearchHandler::removeCallbacks)
            work?.cancel(false)
            signal?.cancel()
        }
        cancelLibrarySearch = ::cancel
        fun update(rows: List<LibraryCardResult>, message: String) {
            filtered = rows
            status.text = message
            status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            adapter.notifyDataSetChanged()
        }
        fun find() {
            cancel()
            val token = generation
            libraryCardFilter = search.text.toString()
            val query = libraryCardFilter.trim()
            val wasSearching = libraryHeader.visibility == View.GONE
            libraryHeader.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
            if (query.isEmpty()) {
                update(allCards, "")
                if (wasSearching) list.setSelectionFromTop(0, 0)
                return
            }
            if (filtered.isNotEmpty()) update(emptyList(), "Поиск…")
            else if (status.text != "Поиск…") {
                status.text = "Поиск…"
                status.visibility = View.VISIBLE
            }
            pending = Runnable {
                if (token != generation || owner !== store) return@Runnable
                val vocabulary = snapshot
                val request = CancellationSignal().also { signal = it }
                work = librarySearchWorker.submit {
                    val matches = vocabulary.search(query)
                    if (request.isCanceled) return@submit
                    val found = libraryDictionaries.map { dictionary -> runCatching {
                        dictionary.search(query, request).map { SourcedDefinition(dictionary.source, it) }
                    } }
                    if (request.isCanceled) return@submit
                    val groups = dictionaryResults(matches, found.flatMap { it.getOrDefault(emptyList()) }, query)
                    librarySearchHandler.post {
                        if (isDestroyed || token != generation || owner !== store) return@post
                        val missingPacks = matches.mapTo(mutableSetOf()) { it.packId } - loadedPacks.keys
                        if (missingPacks.isNotEmpty()) {
                            owner.libraryPacks(missingPacks).associateByTo(loadedPacks) { it.id }
                        }
                        val rows = groups.flatMap { result ->
                            if (result.cards.isEmpty()) {
                                val primary = result.forms.preferred(query)
                                val reading = result.definitions.firstOrNull { it.entry.pinyin.isNotBlank() }
                                    ?.entry?.pinyin.orEmpty()
                                val word = Word("", primary, Pinyin.marked(reading), reading,
                                    emptyList(), emptyList(), alternatives = result.forms.values.filterNot { it == primary })
                                listOf(LibraryCardResult(word, dictionary = result))
                            } else result.cards.mapNotNull { card ->
                                val pack = loadedPacks[card.packId] ?: return@mapNotNull null
                                val saved = savedEntries.getOrPut(card.reference) {
                                    LibraryEntry(pack, card.word, sharedPacks[SharedWords.identity(card.word)].orEmpty(),
                                        inPractice = false)
                                }
                                LibraryCardResult(card.word, saved, result)
                            }
                        }
                        val message = when {
                            rows.isEmpty() && found.all { it.isFailure } -> "Словари недоступны."
                            rows.isEmpty() -> "Совпадений нет."
                            found.any { it.isFailure } -> "Один из словарей недоступен."
                            else -> ""
                        }
                        update(rows, message)
                    }
                }
            }.also { librarySearchHandler.postDelayed(it, 200) }
        }
        search.doAfterTextChanged { find() }
        if (libraryCardFilter.isNotBlank()) find()
    }

    private fun hideLibrarySearchKeyboard(search: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private data class LibraryCardResult(val word: Word, val saved: LibraryEntry? = null,
        val dictionary: DictionaryResult? = null)

    private class LibraryEntry(val pack: StudyStore.LibraryPack, val word: Word, val sharedPacks: List<String>,
        val inPractice: Boolean = true) {
        var selected = word.id in pack.selected
        var recognized = word.id in pack.recognized
        var writable = word.id in pack.writable
    }

    private inner class LibraryWordCard(onSelectionChanged: (Boolean) -> Unit,
        onKnowledgeChanged: (Word, WordKnowledge, Boolean) -> Unit) {
        val view = WordCardUi.surface(this@MainActivity)
        private lateinit var result: LibraryCardResult
        private var binding = false
        private val packLabel = label("", 13f, muted)
        private val title = WordCardUi.title(this@MainActivity, "")
        private val meaning = label("", 15f, muted)
        private val selected = CheckBox(this@MainActivity).apply {
            minWidth = dp(48); minHeight = dp(48); buttonTintList = ColorStateList.valueOf(jade)
            setOnCheckedChangeListener { _, checked -> if (!binding) result.saved?.let { entry ->
                store.selectLibraryWord(entry.pack.id, entry.word.id, checked)
                entry.selected = checked
                onSelectionChanged(checked)
                refreshAdjacentSections()
            } }
        }
        private val known = WordKnowledge.entries.map { category ->
            CheckBox(this@MainActivity).apply {
                text = category.title; minHeight = dp(48); buttonTintList = ColorStateList.valueOf(jade)
                setOnCheckedChangeListener { _, checked -> if (!binding) result.saved?.let { entry ->
                    store.setWordKnown(entry.pack.id, entry.word.id, category, checked)
                    if (category == WordKnowledge.RECOGNITION) entry.recognized = checked else entry.writable = checked
                    onKnowledgeChanged(entry.word, category, checked)
                    refreshAdjacentSections()
                } }
            }
        }
        private val edit = button("Изменить слово") {
            result.saved?.let { entry -> store.editPack(entry.pack.id); editWord(entry.word) }
        }
        private val skills = button("Навыки этого слова") {
            result.saved?.let { entry ->
                val word = entry.word
                store.editPack(entry.pack.id)
                val allowed = store.engine.tasks(store.words, false, store.audioEnabled).filter { it.wordId == word.id } + Task(word.id, Skill.WRITING)
                val details = allowed.joinToString("\n\n") { t ->
                    val p = store.engine.peek(t)
                    "${t.skill.title}\n${if (p.mastered) "Закреплено" else if (p.attempts == 0) "Новый" else "На повторении"} · попыток ${p.attempts}, ошибок ${p.errors}, серия ${p.streak}"
                }
                message("${word.hanzi} · ${word.pinyin}", details)
            }
        }
        private val listen = DetailUi.icon(this@MainActivity, R.drawable.ic_speaker, "Слушать") { play(result.word) }

        private val details = button("Подробнее") {
            hideLibrarySearchKeyboard(view)
            result.dictionary?.let { createDictionaryScreen().open(it, result.word) }
                ?: wordDetails(result.word)
        }
        private val strokes = button("Порядок черт") {
            hideLibrarySearchKeyboard(view)
            val word = result.word
            val forms = word.writtenForms + result.dictionary?.forms?.values.orEmpty()
            strokeOrder(word.copy(alternatives = forms.distinct().filterNot { it == word.hanzi }))
        }

        init {
            view.add(packLabel, 4)
            val line = row().apply { gravity = Gravity.TOP }
            line.addView(selected)
            val info = column().apply {
                add(WordCardUi.titleRow(title, listen))
                add(meaning, 6)
            }
            line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            view.add(line)
            known.forEach { view.add(it, 4) }
            view.add(details, 12)
            view.add(strokes, 8)
            view.add(edit, 8)
            view.add(skills, 8)
        }

        fun bind(value: LibraryCardResult) {
            binding = true
            result = value
            val entry = value.saved
            val word = value.word
            val reading = word.numbered.ifBlank { word.pinyin }
            val readingKey = dictionaryReadingKey(reading)
            val definitions = value.dictionary?.definitions.orEmpty()
            val matching = definitions.filter {
                it.entry.pinyin.isBlank() || dictionaryReadingKey(it.entry.pinyin) == readingKey
            }
            packLabel.text = if (entry == null) matching.map { it.source.label }.distinct().joinToString(" / ")
                else entry.pack.name + if (entry.sharedPacks.size > 1) "\nОбщий прогресс: ${entry.sharedPacks.joinToString(", ")}" else ""
            title.text = "${word.hanzi}   ${word.pinyin.ifBlank { readingLabel(reading) }}"
            meaning.text = (listOf(word.meanings.joinToString(" / ")) + matching.distinctBy { it.source }
                .mapNotNull { it.entry.meanings.firstOrNull() }).filter { it.isNotBlank() }.distinct().joinToString("\n")
            listen.isEnabled = reading.isNotBlank()
            details.contentDescription = "Подробнее · ${word.hanzi}"
            strokes.contentDescription = "Порядок черт · ${word.hanzi}"
            selected.visibility = if (entry?.inPractice == true) View.VISIBLE else View.GONE
            (listOf(edit, skills) + known).forEach { it.visibility = if (entry == null) View.GONE else View.VISIBLE }
            if (entry != null) {
                selected.contentDescription = "Учить ${word.hanzi} · ${entry.pack.name}"
                selected.isChecked = entry.selected
                WordKnowledge.entries.forEachIndexed { index, category ->
                    known[index].contentDescription = "${category.title}: ${word.hanzi} · ${entry.pack.name}"
                    known[index].isChecked = if (category == WordKnowledge.RECOGNITION) entry.recognized else entry.writable
                }
            }
            binding = false
        }
    }
    private fun stats() {
        heading("Прогресс", "Прогресс по словам, отмеченным в разделе «Слова».")
        val words = store.selectedStudyWords
        val engine = store.selectionEngine
        val knowledgeTasks = engine.tasks(words, false, store.audioEnabled)
        val all = ListeningAvailability.filter(knowledgeTasks, words, store.audioProvider)
        val writing = engine.tasks(words, true, false)
        val tasks = all + writing
        val problematic = store.hardReviewTasks(tasks, engine)
        content.add(button("Практиковать трудные слова", true) { chooseHardReview(selectedReview = true) }, 12)
        activityStatistics(store.activitySummary())
        val summary = card()
        summary.add(label("Выбранные слова · ${words.size}", 22f, jade, true))
        val introduced = words.count { !store.needsPreview(it, engine) }
        val learned = tasks.groupBy { it.wordId }.values.count { wordTasks -> wordTasks.all { engine.peek(it).mastered } }
        val now = System.currentTimeMillis()
        val due = tasks.count { engine.peek(it).due in 1L..now }
        summary.add(label("Введено: $introduced · Слов изучено: $learned\nНавыков закреплено: ${tasks.count { engine.peek(it).mastered }} · Повторений пора: $due", 17f), 12)
        summary.add(label("Знание: ${all.count { engine.peek(it).mastered }} / ${all.size}", 20f, jade, true), 20)
        summary.add(progress(all, engine), 12)
        summary.add(label("Написание: ${writing.count { engine.peek(it).mastered }} / ${writing.size}", 20f, jade, true), 20)
        summary.add(progress(writing, engine), 12)
        tasks.groupBy { it.skill }.entries.sortedBy { it.key }.forEach { (skill, skillTasks) ->
            val states = skillTasks.map(engine::peek)
            summary.add(label("${skill.title}: закреплено ${states.count { it.mastered }}/${states.size} · трудных ${states.count { it.errors > 0 && !it.mastered }} · ошибок ${states.sumOf { it.errors }}/${states.sumOf { it.attempts }}", 14f), 8)
        }
        content.add(summary, 20)
        content.add(label("Что сложнее запомнить", 22f, ink, true), 24)
        content.add(label("Здесь задания с ошибками. Чем чаще вы ошибаетесь и чем короче серия верных ответов, тем выше задание в списке. Закреплённые задания исчезают из списка.", 13f, muted), 8)
        val wordsById = words.associateBy { it.id }
        val pairs = all.flatMap { task ->
            val word = wordsById.getValue(task.wordId)
            engine.peek(task).confusions.entries.filterNot { word.acceptsForm(it.key) }.map { Triple(word, it.key, it.value) }
        }.sortedByDescending { it.third }
        if (pairs.isNotEmpty()) {
            content.add(label("Часто путаю", 22f, ink, true), 16)
            pairs.take(20).forEach { (word, wrong, count) -> content.add(label("${word.hanzi} ↔ $wrong · $count ошибок", 17f), 6) }
            content.add(button("Практиковать похожие знаки") {
                val focus = all.filter { task -> engine.peek(task).confusions.keys.any { !wordsById.getValue(task.wordId).acceptsForm(it) } }
                val partners = focus.flatMap { task ->
                    val wrong = engine.peek(task).confusions.keys
                    words.filter { word -> word.writtenForms.any { it in wrong } }.map { Task(it.id, task.skill) }
                }
                val valid = all.toSet()
                startShort(false, (focus + partners).distinct().filter { it in valid }, packReview = true, selectedReview = true)
            }, 12)
        }
        if (problematic.isEmpty()) content.add(label("Среди выбранных слов пока нет трудных заданий для повторения.", 15f, muted), 12)
        if (problematic.size > 100) content.add(label("Показаны 100 самых сложных заданий.", 13f, muted), 10)
        val excluded = words.size * (if (store.audioEnabled) 9 else 7) - knowledgeTasks.size
        val footer = if (excluded > 0) column().apply {
            add(label("Исключено неоднозначных направлений: $excluded. Одинаковое произношение или перевод не должны заставлять вас угадывать.", 14f, muted), 20)
        } else null
        val difficult = problematic.take(100).map { task ->
            DifficultWord(wordsById.getValue(task.wordId), task.skill, engine.peek(task).copy())
        }
        val columns = cardColumns()
        val adapter = object : BaseAdapter() {
            override fun getCount() = (difficult.size + columns - 1) / columns
            override fun getItem(position: Int) = difficult[position * columns]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val line = convertView as? LinearLayout ?: row().apply {
                    gravity = Gravity.TOP
                    setPadding(0, dp(12), 0, 0)
                    tag = List(columns) { index ->
                        DifficultWordCard().also { holder ->
                            addView(holder.view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                                if (index < columns - 1) marginEnd = dp(14)
                            })
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val holders = line.tag as List<DifficultWordCard>
                holders.forEachIndexed { index, holder -> holder.bind(difficult.getOrNull(position * columns + index)) }
                return line
            }
        }
        installSectionList("stats", adapter, footer)
    }

    private fun chooseHardReview(selectedReview: Boolean = false) {
        val packs = if (selectedReview) emptyList() else store.visiblePackNames
        val chosenPacks = packs.mapNotNull { (id, _) -> id.takeIf { it in store.practicePackIds } }.toMutableSet()
        val chosenSkills = Skill.entries.toMutableSet()
        val groups = listOf(
            "Пиньинь и тоны" to setOf(Skill.CHARACTER_TONE, Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN),
            "Аудирование" to setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER),
            "Перевод" to setOf(Skill.CHARACTER_MEANING, Skill.PINYIN_MEANING),
            "Выбор иероглифа" to setOf(Skill.MEANING_CHARACTER, Skill.PINYIN_CHARACTER),
            "Написание" to setOf(Skill.WRITING)
        )
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val packHeading = if (selectedReview) null else label("", 18f, ink, true)
        val summary = label("", 15f, muted)
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        var ranked = if (selectedReview) store.selectedHardReviewTasks() else store.hardReviewTasks(chosenPacks)
        fun refresh() {
            packHeading?.text = "Наборы слов · ${chosenPacks.size} из ${packs.size}"
            val count = ranked.count { it.skill in chosenSkills }
            summary.text = when {
                !selectedReview && chosenPacks.isEmpty() -> "Выберите хотя бы один набор."
                chosenSkills.isEmpty() -> "Выберите хотя бы один вид занятий."
                count == 0 && selectedReview -> "Нет трудных заданий для выбранных слов и занятий."
                count == 0 -> "Нет трудных заданий для выбранных наборов и занятий."
                else -> "$count трудных заданий · ${minOf(10, count)} в занятии"
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = count > 0
        }
        if (packHeading != null) {
            box.add(packHeading)
            box.add(label("Отметьте один или несколько наборов для повторения.", 14f, muted), 4)
        }
        packs.forEach { (id, name) ->
            box.add(CheckBox(this).apply {
                text = store.packLabel(id, name); isChecked = id in chosenPacks
                minHeight = dp(48); buttonTintList = ColorStateList.valueOf(jade)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) chosenPacks.add(id) else chosenPacks.remove(id)
                    ranked = store.hardReviewTasks(chosenPacks)
                    refresh()
                }
            })
        }
        box.add(label("Виды занятий", 18f, ink, true), 16)
        groups.forEach { (name, skills) ->
            box.add(CheckBox(this).apply {
                text = name; isChecked = true; minHeight = dp(48)
                buttonTintList = ColorStateList.valueOf(jade)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) chosenSkills.addAll(skills) else chosenSkills.removeAll(skills)
                    refresh()
                }
            })
        }
        box.add(summary, 12)
        dialog = MaterialAlertDialogBuilder(this).setTitle("Трудные слова")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Начать", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            refresh()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (selectedReview) {
                    ranked = store.selectedHardReviewTasks()
                    val focus = ranked.filter { it.skill in chosenSkills }.take(10)
                    if (focus.isNotEmpty()) {
                        voice.stop(); dialog.dismiss()
                        startShort(false, focus, packReview = true, selectedReview = true)
                    } else refresh()
                } else if (store.beginHardReview(chosenPacks, chosenSkills)) {
                    voice.stop(); dialog.dismiss()
                    store.writing = false
                    store.route = "quiz"; store.nextQuestion(); render()
                } else { ranked = store.hardReviewTasks(chosenPacks); refresh() }
            }
        }
        dialog.show()
    }

    private data class DifficultWord(val word: Word, val skill: Skill, val progress: Progress)

    private inner class DifficultWordCard {
        private val title = label("", 24f, jade, true)
        private val skill = label("", 16f, ink)
        private val result = label("", 14f, muted)
        val view = card().apply {
            add(title)
            add(skill, 6)
            add(result, 8)
        }

        private var bound: DifficultWord? = null
        init {
            view.add(button("Повторить задание") {
                bound?.let { startShort(false, listOf(Task(it.word.id, it.skill)), packReview = true, selectedReview = true) }
            }, 10)
        }

        fun bind(item: DifficultWord?) {
            bound = item
            view.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
            title.text = item?.word?.let { "${it.hanzi}  ·  ${it.pinyin}" }.orEmpty()
            skill.text = item?.let { "${it.word.meanings.joinToString(" / ")}\n${it.skill.title}" }.orEmpty()
            result.text = item?.progress?.let { p ->
                "На повторении · Ошибок ${p.errors} / попыток ${p.attempts}\nВерных подряд: ${p.streak}"
            }.orEmpty()
        }
    }
    private fun settings() {
        heading("Настройки")
        content.add(button("Новых слов в день: ${store.dailyNewWords}") {
            val limits = listOf(0, 3, 5, 10, 20)
            MaterialAlertDialogBuilder(this).setTitle("Сколько новых слов вводить в день?")
                .setSingleChoiceItems(limits.map { if (it == 0) "0 — только знакомые слова" else "$it слов" }.toTypedArray(), limits.indexOf(store.dailyNewWords)) { dialog, which ->
                    store.dailyNewWords = limits[which]; store.save(); dialog.dismiss(); render()
                }.setNegativeButton("Отмена", null).show()
        }, 12)
        content.add(button("Цель запоминания: ${kotlin.math.round(store.desiredRetention * 100).toInt()}%") {
            val targets = listOf(0.85, 0.90, 0.95)
            MaterialAlertDialogBuilder(this).setTitle("Вероятность вспомнить к сроку повторения")
                .setSingleChoiceItems(arrayOf("85% — меньше повторений", "90% — рекомендуется", "95% — больше повторений"),
                    targets.indices.minByOrNull { kotlin.math.abs(targets[it] - store.desiredRetention) } ?: 1) { dialog, which ->
                    store.desiredRetention = targets[which]; store.save(); dialog.dismiss(); render()
                }.setNegativeButton("Отмена", null).show()
        }, 12)
        content.add(label("Цель применяется после следующего ответа. История и уже назначенные повторения сохраняются.", 13f, muted), 8)
        content.add(button("Как начать: звук и пиньинь") { store.onboardingDone = false; store.route = "learn"; render() }, 8)
        val accountColumn = if (wideContent) column() else content
        val optionsColumn = if (wideContent) column() else content
        if (wideContent) {
            val panels = row().apply { gravity = Gravity.TOP }
            panels.addView(accountColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(18) })
            panels.addView(optionsColumn, LinearLayout.LayoutParams(0, -2, 1f))
            content.add(panels)
        }
        val account = card()
        account.add(label("Аккаунт и синхронизация", 21f, ink, true))
        account.add(label(auth.currentUser?.email ?: "Без аккаунта", 16f, jade), 12)
        syncStatusLabel = label(syncStatus, 14f, muted)
        account.add(syncStatusLabel!!, 8)
        account.add(label("Доступ запоминается для аккаунта. После обновления или повторного входа пароль доступа больше не нужен. При первом включении подтвердите почту и введите пароль доступа.", 14f, muted), 10)
        if (profileUid == null) {
            account.add(label("Ваши локальные наборы и результаты присоединятся к первому аккаунту на этом устройстве.", 14f, muted), 10)
            account.add(button("Войти / создать аккаунт", true) { accountDialog() }, 14)
        } else {
            val verified = auth.currentUser?.isEmailVerified == true
            account.add(label(if (verified) "Почта подтверждена" else "Подтвердите электронную почту по ссылке в письме.", 15f, if (verified) jade else orange), 12)
            verificationMessage?.let { account.add(label(it, 14f, muted), 8) }
            if (!verified) {
                account.add(button("Отправить письмо подтверждения") { sendVerification() }.apply { isEnabled = !verificationBusy }, 10)
                account.add(button("Я подтвердил почту", true) { refreshVerification(true) }.apply { isEnabled = !verificationBusy }, 8)
            }
            if (store.lastSyncedAt > 0) account.add(label("Последняя синхронизация: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(store.lastSyncedAt))}", 13f, muted), 8)
            if (store.syncAccess.enabled) {
                account.add(button("Синхронизировать сейчас", true) { cloud?.sync() }.apply { isEnabled = verified }, 14)
                account.add(button("Выключить синхронизацию") {
                    store.syncAccess.disable(); connectCloud(); render()
                }, 8)
            } else {
                account.add(button("Включить синхронизацию") { unlockSync() }.apply { isEnabled = verified }, 14)
                if (!store.syncAccess.hasAccess) account.add(button("Проверить прежний доступ") { accessRestoreUid = null; connectCloud(); render() }.apply { isEnabled = verified }, 8)
            }
            account.add(button("Выйти из аккаунта") {
                MaterialAlertDialogBuilder(this).setTitle("Выйти из аккаунта?")
                    .setMessage("Наборы, тексты и прогресс останутся на устройстве. Доступ к синхронизации сохранится для этого аккаунта; повторно вводить пароль доступа не потребуется.")
                    .setPositiveButton("Выйти") { _, _ -> auth.signOut() }.setNegativeButton("Отмена", null).show()
            }, 8)
        }
        account.add(label("Сохраняются текущие наборы и прогресс, без постоянного журнала ответов. Без сети можно продолжать учиться. Если один набор изменился на двух устройствах, сохраняются обе версии. Незавершённый ответ и настройки голоса остаются на устройстве.", 13f, muted), 12)
        accountColumn.add(account, 20)
        val backup = card()
        backup.add(label("Резервная копия", 21f, ink, true))
        backup.add(label("Все наборы, выбранные слова и результаты с расписанием повторения — в одном файле. Переносите его на другое устройство без аккаунта и пароля синхронизации. Импорт объединяет данные; ничего не удаляется. Пароли, аккаунт и настройки устройства в файл не входят.", 14f, muted), 10)
        backup.add(button("Экспорт всех данных", true) { exportBackupFile.launch("chinlearn-backup.json") }, 12)
        backup.add(button("Импорт и объединение") { importBackupFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, 8)
        accountColumn.add(backup, 16)
        val audio = card()
        audio.add(label("Аудирование", 21f, ink, true))
        audio.add(Switch(this).apply {
            text = "Аудио → перевод и иероглифы"; textSize = 16f; setTextColor(ink); minHeight = dp(56); isChecked = store.audioEnabled
            setOnCheckedChangeListener { _, checked -> store.audioEnabled = checked; store.clearQuestion(); store.save() }
        }, 12)
        audio.add(Switch(this).apply {
            text = "Произносить слово после ответа"; textSize = 16f; setTextColor(ink); minHeight = dp(56); isChecked = store.speakAfterAnswer
            setOnCheckedChangeListener { _, checked -> store.speakAfterAnswer = checked; store.save() }
        }, 12)
        audio.add(label("После любого ответа, включая ошибку и самооценку письма. Используется выбранный ниже голос. Эта настройка не зависит от аудиозаданий.", 13f, muted), 6)
        audio.add(label("Источник произношения", 15f, muted), 16)
        val group = RadioGroup(this)
        val system = RadioButton(this).apply { id = View.generateViewId(); text = "Китайский голос устройства"; minHeight = dp(52); setTextColor(ink) }
        val hanping = RadioButton(this).apply { id = View.generateViewId(); text = "Hanping (нужно установить)"; minHeight = dp(52); setTextColor(ink) }
        group.addView(system); group.addView(hanping); group.check(if (store.audioProvider == "system") system.id else hanping.id)
        group.setOnCheckedChangeListener { _, id -> voice.stop(); store.audioProvider = if (id == system.id) "system" else "hanping"; store.save() }
        audio.add(group, 6)
        audio.add(Switch(this).apply {
            text = "Медленнее (системный голос)"; textSize = 16f; setTextColor(ink); minHeight = dp(56); isChecked = store.slowAudio
            setOnCheckedChangeListener { _, checked -> store.slowAudio = checked; store.save() }
        }, 8)
        audio.add(button("Проверить звук: 你好") {
            play(Word("voice-check", "你好", "nǐ hǎo", "ni3 hao3", listOf("привет"), emptyList()))
        }, 12)
        audio.add(button("Восстановить звук Hanping") {
            MaterialAlertDialogBuilder(this).setTitle("Если Hanping не слышно")
                .setMessage("Уведомление Hanping не гарантирует, что звук воспроизводится. Если его аудиослужба зависает, выберите голос устройства: он будет использоваться и после ответа. Hanping останется доступен для поиска слов.\n\nДля временного восстановления записей откройте настройки Hanping и нажмите «Остановить». Не очищайте данные. Зависание может повториться.")
                .setPositiveButton("Голос устройства") { _, _ ->
                    voice.stop(); store.audioProvider = "system"; store.save()
                    group.check(system.id)
                    play(Word("voice-check", "你好", "nǐ hǎo", "ni3 hao3", listOf("привет"), emptyList()))
                }
                .setNeutralButton("Настройки Hanping") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:com.embermitre.hanping.app.pro"))) }
                    catch (_: Exception) { toast("Откройте настройки Android → Приложения → Hanping") }
                }.setNegativeButton("Отмена", null).show()
        }, 10)
        audio.add(label("Если уведомление Hanping появляется без звука, выберите голос устройства. Chinlearn не может проверить, слышна ли запись Hanping, или автоматически перезапустить его. Выбор голоса не влияет на поиск слов в словаре.", 13f, muted), 12)
        audio.add(button("Настройки голоса Android") { try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("Откройте системные настройки → Язык → Синтез речи") } }, 10)
        audio.add(label("Для работы без интернета скачайте китайский голос в Android. Доступность и качество зависят от установленного движка. Hanping использует свои голоса; замедление для него не применяется.", 13f, muted), 14)
        optionsColumn.add(audio, 20)
        val about = card()
        about.add(label("Как устроено повторение", 21f, ink, true))
        about.add(label("Повторения подстраиваются под ваши ответы и время между занятиями. Новые навыки проверяются повторно после паузы; ошибки возвращаются через 1–10 минут. Знание и письмо учитываются отдельно.\n\nДальнейшие интервалы растут по мере запоминания. Повторения появляются автоматически. Когда заданий на сейчас нет, можно остановиться. На следующий день или после долгого перерыва занятие обновляется, а знания и сроки повторений сохраняются.\n\nПиньинь проверяется по словарным тонам. В речи возможны изменения тонов: например, nǐ hǎo обычно звучит как ní hǎo.\n\nНаписание оцениваете вы сами; автоматической проверки черт нет.", 15f, muted), 12)
        optionsColumn.add(about, 16)
        val privacy = card()
        privacy.add(label("Ваши данные", 21f, ink, true))
        privacy.add(label("Слова, синонимы, тексты и результаты сохраняются на устройстве. Учётная запись необязательна. После подтверждения почты и включения синхронизации наборы, тексты и прогресс также хранятся в Google Firebase и доступны на ваших устройствах под тем же аккаунтом. Разные аккаунты имеют отдельные локальные библиотеки. При удалении приложения неотправленные данные могут быть потеряны. Экспорт JSON сохраняет словарь; результаты в него не входят. Разрешение на синхронизацию не переносится на другие устройства через резервную копию Android.\n\nПри использовании Hanping выбранное слово передаётся этому приложению. Системный голос может использовать интернет, если выбран сетевой голос.", 14f, muted), 12)
        accountColumn.add(privacy, 16)
    }

    private fun accountDialog() {
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val email = field("Электронная почта", "", box).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        fun passwordField(title: String): TextInputEditText = field(title, "", box).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            (parent.parent as TextInputLayout).endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val password = passwordField("Пароль")
        val confirmation = passwordField("Повторите пароль")
        val confirmationLayout = confirmation.parent.parent as TextInputLayout
        confirmationLayout.visibility = View.GONE
        var creatingAccount = false
        val result = label("Для нового аккаунта: пароль от 6 символов.", 13f, muted)
        box.add(result, 10)
        val reset = button("Забыли пароль?") { }
        box.add(reset, 10)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Вход в Chinlearn").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Войти", null).setNeutralButton("Создать аккаунт", null)
            .setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            fun busy(value: Boolean) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !value
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !value
                reset.isEnabled = !value
            }
            fun authenticate(create: Boolean) {
                val address = email.text.toString().trim()
                val secret = password.text.toString()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.error = "Введите электронную почту"; return }
                if (secret.isBlank() || (create && secret.length < 6)) { password.error = "Введите пароль${if (create) " от 6 символов" else ""}"; return }
                if (create && secret != confirmation.text.toString()) { confirmation.error = "Пароли не совпадают"; confirmation.requestFocus(); return }
                confirmation.error = null
                busy(true); result.text = "Подключение…"
                val task = if (create) auth.createUserWithEmailAndPassword(address, secret) else auth.signInWithEmailAndPassword(address, secret)
                task.addOnCompleteListener { completed ->
                    if (completed.isSuccessful && create) {
                        // Send even if registration completes while the Activity is stopped.
                        auth.setLanguageCode("ru")
                        completed.result.user?.sendEmailVerification()?.addOnCompleteListener { sent ->
                            if (!isDestroyed) {
                                verificationMessage = if (sent.isSuccessful) "Письмо отправлено. Откройте ссылку, затем вернитесь в приложение. Проверьте также папку «Спам»."
                                    else "Аккаунт создан, но письмо не отправлено. ${authError(sent.exception)} Повторите отправку в настройках."
                                if (sent.isSuccessful) lastVerificationSentAt = android.os.SystemClock.elapsedRealtime()
                                if (store.route == "settings") render()
                            }
                        }
                    }
                    if (!isDestroyed) {
                        busy(false)
                        if (completed.isSuccessful) { dialog.dismiss(); toast(if (create) "Аккаунт создан. Подтвердите почту." else "Вы вошли в аккаунт") }
                        else result.text = authError(completed.exception)
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { authenticate(creatingAccount) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                creatingAccount = !creatingAccount
                confirmation.text?.clear(); confirmation.error = null; password.error = null
                confirmationLayout.visibility = if (creatingAccount) View.VISIBLE else View.GONE
                reset.visibility = if (creatingAccount) View.GONE else View.VISIBLE
                dialog.setTitle(if (creatingAccount) "Создать аккаунт" else "Вход в Chinlearn")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = if (creatingAccount) "Зарегистрироваться" else "Войти"
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text = if (creatingAccount) "Уже есть аккаунт" else "Создать аккаунт"
                result.text = if (creatingAccount) "Введите пароль от 6 символов дважды." else "Введите почту и пароль."
            }
            reset.setOnClickListener {
                val address = email.text.toString().trim()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.error = "Сначала введите электронную почту"; return@setOnClickListener }
                busy(true)
                auth.sendPasswordResetEmail(address).addOnCompleteListener(this) { completed ->
                    busy(false)
                    result.text = if (completed.isSuccessful) "Если аккаунт существует, письмо для восстановления отправлено. Проверьте почту." else authError(completed.exception)
                }
            }
        }
        dialog.show()
    }

    private fun unlockSync() {
        if (auth.currentUser?.isEmailVerified != true) return
        if (store.syncAccess.resume()) { connectCloud(); render(); return }
        val uid = profileUid
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        box.add(label("Введите отдельный пароль доступа к синхронизации. Он отличается от пароля аккаунта.", 14f, muted))
        val password = field("Пароль доступа к синхронизации", "", box).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            (parent.parent as TextInputLayout).endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Включить синхронизацию")
            .setView(box).setPositiveButton("Включить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (uid != profileUid || auth.currentUser?.isEmailVerified != true) { dialog.dismiss(); return@setOnClickListener }
            if (!store.syncAccess.enable(password.text.toString())) password.error =
                if (BuildConfig.SYNC_PASSWORD_SHA256.isBlank()) "В этой сборке не настроен первый доступ к синхронизации"
                else "Пароль доступа не подходит к этой сборке. Если вы уже синхронизировались, повторите проверку прежнего доступа."
            else { password.text?.clear(); dialog.dismiss(); connectCloud(); render() }
        } }
        dialog.show()
    }

    private fun sendVerification() {
        val user = auth.currentUser ?: return
        if (verificationBusy) return
        if (lastVerificationSentAt != 0L && android.os.SystemClock.elapsedRealtime() - lastVerificationSentAt < 60_000) {
            toast("Повторная отправка доступна через минуту после предыдущей"); return
        }
        verificationBusy = true
        auth.setLanguageCode("ru")
        user.sendEmailVerification().addOnCompleteListener { sent ->
            verificationBusy = false
            if (isDestroyed || auth.currentUser?.uid != user.uid) return@addOnCompleteListener
            verificationMessage = if (sent.isSuccessful) "Письмо отправлено. Перейдите по ссылке и нажмите «Я подтвердил почту». Проверьте также папку «Спам»."
                else "Не удалось отправить письмо. ${authError(sent.exception)}"
            if (sent.isSuccessful) lastVerificationSentAt = android.os.SystemClock.elapsedRealtime()
            render()
        }
        render()
    }

    private fun refreshVerification(showResult: Boolean) {
        val user = auth.currentUser ?: return
        if (verificationBusy) return
        verificationBusy = true
        user.reload().continueWithTask { reloaded ->
            if (!reloaded.isSuccessful) throw reloaded.exception ?: IllegalStateException("Не удалось проверить почту")
            user.getIdToken(true)
        }.addOnCompleteListener { refreshed ->
            verificationBusy = false
            if (isDestroyed || auth.currentUser?.uid != user.uid) return@addOnCompleteListener
            if (refreshed.isSuccessful && user.isEmailVerified) {
                verificationMessage = "Почта подтверждена. Можно включить синхронизацию."
                if (cloud == null) connectCloud()
            } else if (showResult) {
                verificationMessage = if (refreshed.isSuccessful) "Почта пока не подтверждена. Откройте ссылку из письма и повторите проверку."
                    else authError(refreshed.exception)
            }
            if (store.route == "settings") render()
        }
        if (showResult) render()
    }

    private fun authError(error: Exception?): String = when {
        error is FirebaseNetworkException -> "Нет соединения. Проверьте интернет и повторите попытку."
        else -> when ((error as? FirebaseAuthException)?.errorCode) {
            "ERROR_EMAIL_ALREADY_IN_USE" -> "Этот адрес уже зарегистрирован. Нажмите «Войти»."
            "ERROR_INVALID_EMAIL" -> "Проверьте адрес электронной почты."
            "ERROR_WEAK_PASSWORD" -> "Пароль не соответствует требованиям Firebase. Выберите более сложный пароль."
            "ERROR_OPERATION_NOT_ALLOWED" -> "Включите Email/Password в Firebase Console → Authentication."
            "ERROR_TOO_MANY_REQUESTS" -> "Слишком много попыток. Попробуйте позже."
            "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_LOGIN_CREDENTIALS" -> "Проверьте почту и пароль или создайте аккаунт."
            else -> "Не удалось войти: ${error?.localizedMessage ?: "повторите попытку"}"
        }
    }
    private fun field(title: String, initial: String, parent: LinearLayout): TextInputEditText {
        val layout = TextInputLayout(this).apply {
            hint = title
            val neutral = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_hovered), intArrayOf()), intArrayOf(Color.LTGRAY, jade, muted, Color.rgb(142, 146, 137)))
            setBoxStrokeColorStateList(neutral)
            defaultHintTextColor = ColorStateList.valueOf(muted)
            hintTextColor = ColorStateList.valueOf(jade)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat()) }
        val input = TextInputEditText(layout.context).apply { textSize = 17f; setTextColor(ink); setText(initial); minHeight = dp(56); maxLines = 4; inputType = InputType.TYPE_CLASS_TEXT }
        layout.addView(input); parent.add(layout, 14)
        return input
    }
    private fun searchField(hint: String, initial: String, parent: LinearLayout): TextInputEditText {
        val input = field(hint, initial, parent)
        val layout = parent.getChildAt(parent.childCount - 1) as TextInputLayout
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setSelection(input.length())

        // Keep the clear icon in the search field without changing the editor's measurement.
        input.setPaddingRelative(input.paddingStart, input.paddingTop, dp(54), input.paddingBottom)
        val clear = listIcon(R.drawable.ic_close, "Очистить поиск") {
            input.requestFocus()
            input.text?.clear()
        }
        val outer = layout.layoutParams
        parent.removeView(layout)
        parent.addView(FrameLayout(this).apply {
            addView(layout, FrameLayout.LayoutParams(-1, -2))
            addView(clear, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }, outer)

        fun updateClearVisibility() { clear.visibility = if (input.text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE }
        input.doAfterTextChanged { updateClearVisibility() }
        updateClearVisibility()
        return input
    }

    private fun editWord(
        original: Word?, initialHanzi: String = "", initialExample: String = "",
        prefill: WordDraft? = null, saveToPack: ((Word, () -> Unit) -> Unit)? = null
    ): AlertDialog {
        val form = column().apply { setPadding(dp(22), dp(8), dp(22), dp(16)) }
        val hanzi = field("Иероглифы", original?.hanzi ?: initialHanzi, form)
        val pinyin = field("Пиньинь с диакритикой", original?.pinyin ?: prefill?.pinyin.orEmpty(), form)
        val numbered = field("Пиньинь с цифрами · ni3 hao3", original?.numbered ?: prefill?.numbered.orEmpty(), form)
        val meanings = field("Переводы / синонимы · через ;", original?.meanings?.joinToString("; ") ?: prefill?.meaning.orEmpty(), form)
        val distractors = field("Похожие неверные знаки · через ;", original?.distractors?.joinToString("; ") ?: prefill?.distractors.orEmpty().joinToString("; "), form)
        val alternatives = field("Другие формы слова / радикала · через ;", original?.alternatives?.joinToString("; ") ?: prefill?.alternatives.orEmpty().joinToString("; "), form)
        form.add(label("Например, для радикала 水 можно указать 氵. Добавляйте только формы, допустимые именно для этой карточки: они будут приниматься в ответах и показываться при проверке письма.", 13f, muted), 6)
        val hint = field("Пояснение к слову или примеру", original?.hint ?: prefill?.hint.orEmpty(), form)
        val example = field("Пример на китайском (необязательно)", original?.example ?: initialExample, form)
        val examplePinyin = field("Пиньинь примера", original?.examplePinyin ?: "", form)
        val exampleTranslation = field("Перевод примера на русский", original?.exampleTranslation ?: "", form)
        if (initialExample.isNotBlank()) form.add(label("Для примера заполните пиньинь и перевод предложения.", 13f, muted), 6)
        form.add(label(if (original == null) "Не менее 3 неверных вариантов."
            else "Не менее 3 неверных вариантов. Изменение карточки сбрасывает её учебный прогресс.", 13f, muted), 12)
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (original == null) "Новое слово" else "Изменить слово")
            .setView(scroll).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null)
        if (original != null) dialog.setNeutralButton("Удалить", null)
        val shown = dialog.create()
        shown.setOnShowListener {
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    fun parts(input: TextInputEditText) = input.text.toString().split(';').map { it.trim() }.filter { it.isNotBlank() }
                    val word = Word(original?.id ?: UUID.randomUUID().toString(), hanzi.text.toString().trim(), pinyin.text.toString().trim(), numbered.text.toString().trim(), parts(meanings), parts(distractors), hint.text.toString().trim(), parts(alternatives), example.text.toString().trim(), examplePinyin.text.toString().trim(), exampleTranslation.text.toString().trim())
                    StudyStore.validate(word)
                    if (saveToPack != null) saveToPack(word, shown::dismiss)
                    else {
                        require(store.words.none { it.id != word.id && it.hanzi == word.hanzi }) { "Это слово уже есть. Измените существующую карточку." }
                        store.replaceWord(word); shown.dismiss(); render()
                    }
                } catch (e: Exception) {
                    if (saveToPack == null) message("Проверьте карточку", e.message ?: "Неверные данные")
                    else toast(e.message ?: "Проверьте карточку")
                }
            }
            if (original != null) shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                MaterialAlertDialogBuilder(this).setTitle("Удалить ${original.hanzi}?").setMessage("Карточка и её статистика будут удалены.")
                    .setPositiveButton("Удалить") { _, _ ->
                        if (store.words.size == 1) { toast("Оставьте хотя бы одну карточку"); return@setPositiveButton }
                        store.invalidatePractice(); store.words.removeAll { it.id == original.id }; store.selected.remove(original.id)
                        store.engine.progress.keys.removeAll { it.startsWith("${original.id}:") }; store.clearQuestion(); store.save(); shown.dismiss(); render()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
        shown.show()
        return shown
    }
    private fun useDeviceVoice(word: Word) {
        voice.stop()
        store.audioProvider = "system"
        store.save()
        render()
        play(word)
        toast("Выбран голос устройства. Вернуть Hanping можно в настройках.")
    }
    private fun play(word: Word) {
        val error = voice.play(word, store.audioProvider, store.slowAudio)
        if (error != null) { audioNotice?.text = error; message("Звук недоступен", "$error\n\nАудирование можно временно выключить на вкладке «Настройки».") }
    }
    private fun wordDetails(word: Word): BottomSheetDialog {
        val source = if (word.example.isBlank()) starterExamples[SharedWords.identity(word)] ?: word else word
        return WordDetailsSheet.show(this, word.copy(example = source.example, examplePinyin = source.examplePinyin,
            exampleTranslation = source.exampleTranslation), store.vocabularyLookup().memberships(word),
            ::play, ::dictionary, { vocabularySearch(it) })
    }

    private fun reader(): ReaderDialogs {
        readerDialogs?.close()
        return ReaderDialogs(this, store, { word ->
            voice.play(word, store.audioProvider, store.slowAudio)?.let(::toast)
        }, { sentence -> voice.playText(sentence, store.slowAudio)?.let(::toast) },
            voice::stop, ::strokeOrder, { selection, prefill, save ->
            editWord(null, selection.quote, selection.sentence, prefill, save)
        }, ::render).also { readerDialogs = it }
    }

    private fun vocabularySearch(initial: String = "", source: OfflineDictionary.Source? = null) {
        createDictionaryScreen().show(initial, source)
    }

    private fun createDictionaryScreen(): DictionaryScreen {
        val owner = store
        val page = DictionaryScreen(this, owner.dictionaryBookmarks, owner::vocabularyLookup,
            { value, parent -> searchField("Слово или перевод", value, parent) },
            ::wordDetails, { word -> voice.play(word, owner.audioProvider, owner.slowAudio)?.let(::toast) },
            { form, entry, meaning, track, saved -> addDictionaryWord(owner, form, entry, meaning, track, saved) },
            { dictionaryScreens.remove(it) })
        dictionaryScreens.add(page)
        return page
    }

    private fun addDictionaryWord(
        owner: StudyStore, form: String, entry: OfflineDictionary.Entry, meaning: String,
        track: (android.app.Dialog) -> Unit, saved: () -> Unit
    ) {
        val packs = owner.packNames
        val labels = packs.map { (id, name) -> owner.packLabel(id, name) }
        track(MaterialAlertDialogBuilder(this).setTitle("Выберите набор")
            .setItems(labels.toTypedArray()) { _, index ->
                val id = packs[index].first
                val name = labels[index]
                val alternatives = listOf(entry.simplified, entry.traditional).distinct().filter { it != form }
                try {
                    val words = owner.packWords(id)
                    val draft = WordDraft(Pinyin.marked(entry.pinyin), entry.pinyin, meaning, alternatives,
                        StudyStore.suggestDistractors(form, alternatives, words.map { it.hanzi }),
                        OfflineDictionary.wiktionaryCredit(entry.article))
                    track(editWord(null, initialHanzi = form, prefill = draft) { word, dismissEditor ->
                        check(store === owner) { "Профиль изменился. Откройте словарь заново." }
                        val identity = SharedWords.identity(word)
                        val duplicate = owner.packWords(id).any { SharedWords.identity(it) == identity }
                        if (!duplicate) owner.addWordToPack(id, word)
                        dismissEditor()
                        saved()
                        if (!duplicate) render()
                        toast(if (duplicate) "Карточка уже есть · $name" else "Слово добавлено · $name")
                    })
                } catch (error: Exception) { toast(error.message ?: "Не удалось открыть набор.") }
            }.setNegativeButton("Отмена", null).show())
    }

    private fun dictionary(word: Word) {
        MaterialAlertDialogBuilder(this).setTitle("Открыть ${word.hanzi}").setItems(arrayOf("Pleco", "Hanping")) { _, which -> ExternalDictionary(this).lookup(word.hanzi, which == 0)?.let { toast(it) } }.show()
    }
    private fun strokeOrder(word: Word) {
        voice.stop()
        startActivity(StrokeOrderActivity.intent(this, word.writtenForms, word.pinyin))
    }
    private fun message(title: String, body: String) { MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setPositiveButton("Понятно", null).show() }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
