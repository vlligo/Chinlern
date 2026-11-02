package com.example.chinlern

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
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

class MainActivity : AppCompatActivity() {
    private lateinit var store: StudyStore
    private lateinit var voice: Pronunciation
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
    private lateinit var content: LinearLayout
    private lateinit var quizActions: LinearLayout
    private lateinit var keyboardQuestion: LinearLayout
    private var voiceMessage: String? = null
    private var audioNotice: TextView? = null
    private var answerInput: TextInputEditText? = null
    private var selectedTones = mutableListOf<Int>()
    private var tablet = false
    private var wideContent = false
    private var contentWidthDp = 0
    private var selectionLabel: TextView? = null
    private val jade = Color.rgb(36, 75, 68)
    private val ink = Color.rgb(43, 48, 43)
    private val muted = Color.rgb(105, 109, 99)
    private val paper = Color.rgb(249, 246, 238)
    private val pale = Color.rgb(234, 239, 228)
    private val orange = Color.rgb(152, 83, 36)
    private val red = Color.rgb(159, 41, 54)
    private val gold = Color.rgb(173, 126, 41)
    private val blush = Color.rgb(247, 232, 225)
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
        box.add(label("Готово наборов: ${items.size}. Каждый станет отдельным набором с новым прогрессом.", 16f))
        val selected = items.map { item ->
            CheckBox(this).apply {
                text = "${item.preview.name} · ${item.preview.words.size} слов\n${item.source}"
                isChecked = true; box.add(this, 8)
            }
        }
        if (errors.isNotEmpty()) box.add(label("Не удалось прочитать:\n${errors.joinToString("\n\n")}", 14f, red), 12)
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
                .setMessage("Новых наборов: ${preview.added}. Дополнений: ${preview.updated}. Отдельных версий: ${preview.conflicts}.\n\nСуществующие слова и результаты сохранятся. Если результаты одного задания отличаются, версия из файла сохранится в отдельном наборе. Повторный импорт того же файла не удваивает результаты.")
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
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        profileUid = auth.currentUser?.uid
        store = StudyStore(this, profileUid)
        connectCloud()
        voice = Pronunciation(this, { error -> voiceMessage = error; audioNotice?.text = error ?: "Китайский голос готов" })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (store.route != "learn") { store.route = "learn"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        render()
        authListener = FirebaseAuth.AuthStateListener { firebase ->
            val nextUid = firebase.currentUser?.uid
            if (nextUid != profileUid) {
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
        if (auth.currentUser?.isEmailVerified == false) refreshVerification(false)
        else cloud?.sync()
    }
    override fun onPause() { super.onPause(); if (::store.isInitialized) store.save(); cloud?.sync(); if (::voice.isInitialized) voice.stop() }
    override fun onDestroy() {
        authListener?.let { auth.removeAuthStateListener(it) }
        cloud?.close()
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }

    private fun connectCloud() {
        cloud?.close(); cloud = null
        syncStatus = when {
            !store.syncAccess.enabled -> "Синхронизация выключена — данные сохраняются на устройстве"
            profileUid == null -> "Войдите, чтобы синхронизировать наборы и прогресс"
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
            if (changed && store.route != "quiz") render()
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
    private fun LinearLayout.addCards(items: List<View>) {
        val columns = if (contentWidthDp >= 640 && resources.configuration.fontScale <= 1.3f) 2 else 1
        items.chunked(columns).forEach { group ->
            if (columns == 1) add(group.first(), 12)
            else {
                val line = row().apply { gravity = Gravity.TOP }
                group.forEachIndexed { index, item ->
                    line.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(14) })
                }
                if (group.size == 1) line.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                add(line, 12)
            }
        }
    }

    private fun render() {
        store.save()
        answerInput = null; audioNotice = null; syncStatusLabel = null
        val windowWidth = resources.configuration.screenWidthDp
        tablet = windowWidth >= 600
        val railWidth = if (windowWidth >= 1000) 160 else 112
        contentWidthDp = (windowWidth - (if (tablet) railWidth else 0) - (if (tablet) 48 else 36) - 16).coerceAtLeast(240).coerceAtMost(1040)
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
        val brand = column().apply { add(label("Chinlearn", 20f, ink, true)); add(label("Китайские слова", 12f, muted)) }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        selectionLabel = label("${if (store.route in listOf("learn", "quiz", "words")) store.studyWords.size else store.selected.size} слов", 13f, jade)
        header.addView(selectionLabel)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.visibility = if (store.route == "quiz" && keyboard.bottom > 0) View.GONE else View.VISIBLE
            keyboardQuestion.visibility = if (tablet && store.route == "quiz" && keyboard.bottom > 0 && keyboardQuestion.childCount > 0) View.VISIBLE else View.GONE
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        root.add(header)
        root.add(View(this).apply { setBackgroundColor(gold) }, height = 2)
        val body = row().apply { gravity = Gravity.TOP }
        if (tablet) {
            val rail = column().apply { setPadding(dp(10), dp(20), dp(10), dp(12)); setBackgroundColor(Color.rgb(240, 235, 222)) }
            listOf("learn" to "Учить", "words" to "Слова", "stats" to "Прогресс", "settings" to "Настройки").forEach { (route, title) ->
                rail.add(button(title, store.route == route || (route == "learn" && store.route == "quiz")) { store.route = route; render() }.apply { textSize = 14f; setPadding(dp(6), dp(10), dp(6), dp(10)) }, 8)
            }
            body.addView(rail, LinearLayout.LayoutParams(dp(railWidth), -1))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val holder = LinearLayout(this).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; setPadding(dp(if (tablet) 24 else 18), dp(8), dp(if (tablet) 24 else 18), dp(24)) }
        content = column()
        holder.addView(content, LinearLayout.LayoutParams(if (windowWidth - (if (tablet) railWidth else 0) - 48 > 1040) dp(1040) else -1, -2))
        scroll.addView(holder)
        val workspace = column()
        workspace.add(keyboardQuestion)
        workspace.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        quizActions = column().apply {
            visibility = View.GONE
            setPadding(dp(if (tablet) 24 else 18), dp(6), dp(if (tablet) 24 else 18), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(4).toFloat()
        }
        workspace.add(quizActions)
        body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!tablet && store.route != "quiz") {
            val nav = BottomNavigationView(this).apply {
                setBackgroundColor(Color.WHITE); labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
                itemActiveIndicatorColor = ColorStateList.valueOf(blush)
                val navColors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(red, muted))
                itemIconTintList = navColors; itemTextColor = navColors
                menu.add(0, 1, 0, "Учить").setIcon(android.R.drawable.ic_media_play)
                menu.add(0, 2, 1, "Слова").setIcon(android.R.drawable.ic_menu_agenda)
                menu.add(0, 3, 2, "Прогресс").setIcon(android.R.drawable.ic_menu_sort_by_size)
                menu.add(0, 4, 3, "Настройки").setIcon(android.R.drawable.ic_menu_preferences)
                selectedItemId = when (store.route) { "words" -> 2; "stats" -> 3; "settings" -> 4; else -> 1 }
                setOnItemSelectedListener { item -> store.route = when (item.itemId) { 2 -> "words"; 3 -> "stats"; 4 -> "settings"; else -> "learn" }; render(); true }
            }
            root.add(nav)
        }
        setContentView(root)
        ViewCompat.getRootWindowInsets(window.decorView)?.let { ViewCompat.dispatchApplyWindowInsets(root, it) }
        when (store.route) { "quiz" -> quiz(); "words" -> library(); "stats" -> stats(); "settings" -> settings(); else -> dashboard() }
        ViewCompat.requestApplyInsets(root)
    }
    private fun dashboard() {
        heading("Практика", "Выберите наборы и начните занятие.")
        if (!store.onboardingDone) {
            val welcome = card()
            welcome.add(label("Первое занятие", 22f, jade, true))
            welcome.add(label("Проверьте китайский звук. Пиньинь можно вводить цифрами: shui3 = shuǐ, вода. Сначала познакомимся со словами, затем ответим на 10 вопросов.", 16f), 8)
            welcome.add(button("▶ Проверить звук: 你好") { play(Word("test", "你好", "nǐ hǎo", "ni3 hao3", listOf("привет"), emptyList())) }, 8)
            welcome.add(button("Звук слышен — начать", true) { store.audioEnabled = true; store.onboardingDone = true; startBeginner() }, 8)
            welcome.add(button("Пока без заданий на слух") { store.audioEnabled = false; store.onboardingDone = true; startBeginner() }, 8)
            content.add(welcome, 16)
        }
        val packControls = if (wideContent) card() else content
        practicePicker(packControls)
        packControls.add(button("＋ Новый набор") { importHub() }, 10)
        val selectedWords = store.studyWords
        val knowledge = store.studyEngine.tasks(selectedWords, false, store.audioEnabled)
        val writing = store.studyEngine.tasks(selectedWords, true, false)
        val hero = card(jade)
        val heroTop = row()
        heroTop.addView(label("Слов в практике", 15f, Color.rgb(225, 235, 220)), LinearLayout.LayoutParams(0, -2, 1f))
        heroTop.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        hero.add(heroTop)
        hero.add(label(selectedWords.size.toString(), if (tablet) 48f else 38f, Color.rgb(255, 214, 107), true), 8)
        hero.add(label("${knowledge.count { store.studyEngine.state(it).mastered }} / ${knowledge.size} навыков закреплено", 15f, Color.WHITE), 20)
        hero.add(progress(knowledge), 12)
        if (wideContent) {
            val overview = row().apply { gravity = Gravity.TOP }
            overview.addView(packControls, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(18) })
            overview.addView(hero, LinearLayout.LayoutParams(0, -2, 1f))
            content.add(overview, 20)
        } else content.add(hero, 24)
        val short = card()
        short.add(label("Короткое занятие", 21f, ink, true))
        short.add(label("${store.dailyNewWords} новых слов в день. Результат — после занятия; прогресс цикла сохраняется.", 15f, muted), 8)
        short.twoButtons(button("10 вопросов", true) { startShort(false) }, button("5 минут") { startShort(true) })
        if (store.shortSession) short.add(button("Продолжить короткое занятие") { store.route = "quiz"; render() }, 8)
        content.add(short, 16)
        val learn = card()
        learn.add(label("Значение и произношение", 21f, ink, true))
        learn.add(label("Все выбранные слова без дневного лимита. Перевод, пиньинь и тоны. ${if (store.audioEnabled) "Задания на слух включены." else "Задания на слух выключены."}", 15f, muted), 8)
        learn.add(button(if (knowledge.isNotEmpty() && knowledge.all { store.studyEngine.state(it).mastered }) "Посмотреть результаты" else "Продолжить обучение", true) { start(false) }, 18)
        val write = card()
        write.add(label("Написание", 21f, ink, true))
        write.add(label("Напишите иероглиф по памяти, затем сравните с ответом.", 15f, muted), 8)
        write.add(label("${writing.count { store.studyEngine.state(it).mastered }} / ${writing.size} слов закреплено", 14f, jade), 12)
        write.add(button("Практиковать письмо") { start(true) }, 14)
        if (wideContent) { val line = row(); line.gravity = Gravity.TOP; line.addView(learn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14) }); line.addView(write, LinearLayout.LayoutParams(0, -2, 1f)); content.add(line, 16) }
        else { content.add(learn, 16); content.add(write, 14) }
        val due = (knowledge + writing).count { val p = store.studyEngine.state(it); p.mastered && p.due <= System.currentTimeMillis() }
        content.add(label(if (due > 0) "$due навыков пора повторить. Откройте режим и начните повторение на сегодня." else "Прогресс сохраняется на этом устройстве. Освоенные навыки возвращаются через 1, 3, 7, 15 и 30 дней.", 13f, muted), 20)
        if (store.studyWords.isEmpty()) content.add(button("Выбрать слова") { store.route = "words"; render() }, 12)
    }
    private fun progress(tasks: List<Task>, engine: LearningEngine = store.studyEngine) = LinearProgressIndicator(this).apply {
        max = maxOf(tasks.size, 1); progress = tasks.count { engine.state(it).mastered }
        setIndicatorColor(gold); trackColor = Color.rgb(226, 224, 207); trackThickness = dp(7); trackCornerRadius = dp(4)
        contentDescription = "Закреплено $progress из ${tasks.size} навыков"
    }
    private fun startBeginner() {
        val beginnerWords = store.studyWords.take(3)
        val focus = store.studyEngine.tasks(beginnerWords, false, false).filter {
            it.skill in setOf(Skill.CHARACTER_MEANING, Skill.CHARACTER_PINYIN, Skill.CHARACTER_TONE)
        }
        startShort(false, focus)
    }
    private fun startShort(minutes: Boolean, focus: List<Task>? = null, writing: Boolean = false) {
        if (store.studyWords.isEmpty()) { toast("Выберите хотя бы одно слово"); return }
        store.writing = writing
        store.beginSession(minutes, focus)
        store.route = "quiz"; store.nextQuestion(); render()
    }
    private fun shortResults() {
        heading("Занятие завершено", "Можно остановиться здесь. Прогресс сохранён.")
        val box = card()
        box.add(label("Ответов: ${store.sessionCount}", 28f, jade, true))
        box.add(label("Верно: ${store.sessionCorrect} · Ошибок: ${store.sessionCount - store.sessionCorrect}", 20f), 12)
        content.add(box, 20)
        content.add(button("Ещё 10 вопросов", true) {
            val focus = store.session.optJSONArray("tasks")?.let { json -> (0 until json.length()).map { json.getString(it) }.toSet() }
            startShort(false, focus?.let { keys -> store.tasks().filter { it.key in keys } }, store.writing)
        }, 12)
        content.add(button("Трудные слова") { store.leaveSession(); store.route = "stats"; render() }, 8)
        content.add(button("К обзору") { store.leaveSession(); store.route = "learn"; render() }, 8)
    }
    private fun start(writing: Boolean) {
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
        if (tasks.isEmpty()) {
            heading("На сегодня всё", "Нет доступных заданий. Выберите слова или измените дневной лимит новых слов в настройках.")
            if (store.shortSession) content.add(button("Результаты занятия") { store.endSession(); render() }, 12)
            content.add(button("К обзору") { store.route = "learn"; render() }, 12)
            return
        }
        if (store.current == null) store.nextQuestion()
        val task = store.current
        if (task == null) { results(tasks); return }
        quizActions.visibility = View.VISIBLE
        val word = store.studyWords.first { it.id == task.wordId }
        if (store.needsPreview(word) && store.feedback == null) {
            heading("Новое слово", "Познакомьтесь со словом перед первым вопросом.")
            val preview = card()
            preview.add(label(word.writtenForms.joinToString(" / "), 48f, jade, true))
            preview.add(label("${word.pinyin} · ${word.numbered}", 24f), 12)
            preview.add(label(word.meanings.joinToString(" / "), 20f), 12)
            preview.add(label(word.hint.ifBlank { "Произнесите слово и свяжите его написание со значением." }, 16f, muted), 12)
            preview.add(button("▶ Произношение") { play(word) }, 12)
            content.add(preview, 16)
            quizActions.add(button("Запомнил — к вопросам", true) { store.introduce(word); render() }, 8)
            return
        }
        if (store.shortSession) {
            content.add(label(if (store.session.optInt("limit") > 0) "Занятие: ${store.sessionCount} / 10 ответов" else "Занятие: 5 минут · ${store.sessionCount} ответов. Завершится после текущего вопроса.", 16f, jade, true), 8)
            content.add(button("Завершить занятие") { store.endSession(); render() }, 8)
        }
        val mastery = tasks.count { store.studyEngine.state(it).mastered }
        val top = row()
        top.addView(label(if (task.skill == Skill.WRITING) "НАПИСАНИЕ" else "ЗНАНИЕ", 12f, jade, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("$mastery / ${tasks.size}", 14f, muted))
        content.add(top, 6); content.add(progress(tasks), 12)
        content.add(label("Ошибок: ${tasks.sumOf { store.studyEngine.state(it).errors }} · ${task.skill.title}", 13f, muted), 12)
        val question = card()
        val answerKind = when (task.skill) {
            Skill.CHARACTER_MEANING, Skill.PINYIN_MEANING, Skill.AUDIO_MEANING -> "Ответ: перевод на русский"
            Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN -> "Ответ: пиньинь с тонами"
            Skill.CHARACTER_TONE -> "Ответ: тоны по порядку"
            Skill.WRITING -> "Ответ: иероглиф от руки"
            else -> "Ответ: выберите иероглиф"
        }
        question.add(label(answerKind, 14f, red, true).apply {
            background = bg(blush, 10); setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        question.add(label(task.skill.instruction, 20f, ink, true), 14)
        if (store.studyEngine.state(task).mastered && store.feedback == null) question.add(label("Закрепление между повторениями", 12f, jade), 6)
        val audio = task.skill in listOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)
        // Keep the actual question outside the answer scroller while the tablet IME is open.
        if (store.feedback == null) {
            val prompt = when (task.skill) {
                Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER -> "Послушайте запись"
                Skill.MEANING_PINYIN, Skill.MEANING_CHARACTER, Skill.WRITING -> word.meanings.first()
                Skill.PINYIN_MEANING, Skill.PINYIN_CHARACTER -> word.pinyin
                else -> word.writtenForms.joinToString(" / ")
            }
            val context = row()
            val text = column()
            text.add(label(task.skill.instruction, 13f, muted))
            text.add(label(prompt, 26f, jade, true).apply { contentDescription = "Текущий вопрос" }, 2)
            context.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            if (audio) context.addView(button("▶ Слушать") { play(word) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            keyboardQuestion.add(context)
        }
        if (audio) {
            question.add(label("♪", 64f, jade).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, 14)
            question.add(button("▶  Послушать", true) { play(word) }, 8)
            if (store.audioProvider == "hanping") question.add(button("Нет звука? Голос устройства") { useDeviceVoice(word) }, 8)
            question.add(label("Повторяйте запись столько раз, сколько нужно.", 13f, muted), 10)
            audioNotice = label(if (store.audioProvider == "hanping") "Звук воспроизводит Hanping" else voiceMessage ?: "Используется китайский голос устройства", 12f, muted)
            question.add(audioNotice!!, 8)
        } else {
            val prompt = when (task.skill) {
                Skill.MEANING_PINYIN, Skill.MEANING_CHARACTER, Skill.WRITING -> word.meanings.first()
                Skill.PINYIN_MEANING, Skill.PINYIN_CHARACTER -> word.pinyin
                else -> word.writtenForms.joinToString(" / ")
            }
            val isHanzi = task.skill in listOf(Skill.CHARACTER_MEANING, Skill.CHARACTER_PINYIN, Skill.CHARACTER_TONE)
            question.add(label(prompt, if (isHanzi) 64f else 32f, jade, isHanzi).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) }, 10)
        }
        val response = if (wideContent) card() else question
        if (store.feedback == null) {
            when (task.skill) {
                Skill.MEANING_CHARACTER, Skill.PINYIN_CHARACTER, Skill.AUDIO_CHARACTER -> {
                    store.choices.chunked(2).forEach { pair ->
                        val line = row()
                        pair.forEachIndexed { index, option -> line.addView(button(option) { store.draft = option; submit(word.acceptsForm(option)) }.apply { textSize = 30f; minHeight = dp(76) }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(8) }) }
                        if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                        response.add(line, 10)
                    }
                }
                Skill.CHARACTER_TONE -> {
                    selectedTones = store.toneDraft.toMutableList()
                    val tones = Answers.tones(word)
                    response.add(label("Слог ${selectedTones.size + 1} из ${tones.size}${if (selectedTones.isNotEmpty()) " · Выбрано: ${selectedTones.joinToString(" → ")}" else ""}", 14f, muted), 8)
                    val labels = listOf("1   mā  · ровный", "2   má  · восходящий", "3   mǎ  · нисходяще-восходящий", "4   mà  · нисходящий", "5   ma  · нейтральный")
                    labels.forEachIndexed { index, title -> response.add(button(title) {
                        store.toneDraft.add(index + 1)
                        if (store.toneDraft.size == tones.size) submit(store.toneDraft == tones) else { store.save(); render() }
                    }, 8) }
                    if (selectedTones.isNotEmpty()) response.add(button("Выбрать тоны заново") { store.toneDraft.clear(); render() }, 8)
                }
                Skill.WRITING -> {
                    val pad = WritingGrid(this, word.hanzi.codePointCount(0, word.hanzi.length), store.strokes) { store.strokes = it; store.saveDraft() }
                    response.add(pad, 12)
                    response.add(button("Очистить поле") { pad.clear() }, 8)
                    response.add(label("Вспомните также количество и порядок черт. Оценка самостоятельная: приложение не проверяет почерк или порядок черт.", 13f, muted), 10)
                    if (!store.revealed) quizActions.add(button("Показать ответ", true) { store.revealed = true; render() }, 14)
                    else {
                        response.add(label("${word.writtenForms.joinToString(" / ")}   ${word.pinyin}", 38f, jade), 18)
                        if (word.writtenForms.size > 1) response.add(label("Подходит любой из указанных вариантов написания.", 14f, muted), 6)
                        response.add(label(word.hint, 15f, muted), 6)
                        quizActions.twoButtons(button("Не помню") { submit(false) }, button("Помню", true) { submit(true) })
                    }
                }
                else -> {
                    val isPinyin = task.skill in listOf(Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN)
                    val input = field(if (isPinyin) "Пиньинь с тонами" else "Русский перевод", store.draft, response)
                    answerInput = input
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    input.imeOptions = EditorInfo.IME_ACTION_DONE
                    input.doAfterTextChanged { store.draft = it.toString(); store.saveDraft() }
                    fun check() { if (input.text.toString().isBlank()) { input.error = "Введите ответ"; return }; submit(if (isPinyin) Answers.pinyinMatches(input.text.toString(), word.numbered) else Answers.meaningMatches(input.text.toString(), word)) }
                    input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { check(); true } else false }
                    if (isPinyin) response.add(label("Можно использовать знаки тона или цифры 1–5. Для ü подходят v и u:.", 13f, muted), 8)
                    quizActions.add(button("Проверить", true) { check() }, 4)
                }
            }
            if (task.skill != Skill.WRITING) quizActions.add(button("Не знаю — показать ответ") { submit(false) }, 6)
        } else {
            val correct = store.feedback == true
            val result = card(if (store.answerIgnored) paper else if (correct) pale else Color.rgb(253, 236, 228))
            result.add(label(if (store.answerIgnored) "Ответ не учтён" else if (correct) "✓  Верно" else "Неверно", 22f, if (store.answerIgnored) ink else if (correct) jade else red, true))
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
            result.add(label(word.writtenForms.joinToString(" / "), 48f, jade), 12)
            result.add(label("${word.pinyin}  ·  ${Answers.tones(word).joinToString(" + ")}", 20f, ink, true), 4)
            result.add(label(word.meanings.joinToString(" / "), 18f), 8)
            if (word.example.isNotBlank()) {
                result.add(label("Пример: ${word.example}", 22f, jade), 16)
                result.add(label(word.examplePinyin, 17f), 6)
                result.add(label(word.exampleTranslation, 17f), 6)
                result.add(button("▶  Прослушать предложение") {
                    voice.play(word.copy(hanzi = word.example), "system", store.slowAudio)?.let { message("Звук недоступен", it) }
                }, 8)
            }
            if (!correct && !store.answerIgnored && task.skill in StudyStore.characterSkills && store.draft in store.choices) {
                result.add(label("Сравните: ${word.hanzi} ≠ ${store.draft}", 24f, jade), 12)
                store.studyWords.firstOrNull { it.acceptsForm(store.draft) }?.let { other ->
                    result.add(label("${other.hanzi} · ${other.pinyin} · ${other.meanings.joinToString(" / ")}", 17f), 6)
                }
            }
            if (word.hint.isNotBlank()) result.add(label(word.hint, 15f, muted), 12)
            result.add(label(if (store.answerIgnored) "Попытка отменена. Ошибки, серия ответов и срок повторения восстановлены." else if (!correct) "Вернётся после 4–7 других заданий. Для закрепления нужны два верных ответа подряд." else if (!store.studyEngine.state(task).mastered) "Нужен ещё один верный ответ после паузы." else "Навык закреплён в этом цикле.", 13f, if (store.answerIgnored) muted else if (correct) jade else red), 12)
            response.add(result, 14)
            if (!store.answerIgnored && store.canIgnoreAnswer && !correct && store.draft.isNotBlank() && task.skill in listOf(Skill.CHARACTER_MEANING, Skill.PINYIN_MEANING, Skill.AUDIO_MEANING)) {
                response.add(button("Засчитать мой ответ") { acceptSynonym(word, task) }, 10)
            }
            if (store.canIgnoreAnswer) quizActions.add(button("Ответил случайно — не учитывать") {
                voice.stop()
                store.ignoreAnswer(); render()
            }, 10)
            response.twoButtons(button("▶  Произношение") { play(word) }, button("Словарь") { dictionary(word) })
            if (store.audioProvider == "hanping") response.add(button("Нет звука? Голос устройства") { useDeviceVoice(word) }, 8)
            quizActions.add(button("Дальше", true) { voice.stop(); store.nextQuestion(); render() }, 6)
        }
        if (wideContent) {
            val panels = row().apply { gravity = Gravity.TOP }
            panels.addView(question, LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginEnd = dp(18) })
            panels.addView(response, LinearLayout.LayoutParams(0, -2, 1.1f))
            content.add(panels, 18)
        } else content.add(question, 18)
        content.add(button("К обзору") { store.route = "learn"; render() }, 14)
    }
    private fun submit(correct: Boolean) {
        val task = store.current ?: return
        if (store.feedback != null) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(answerInput?.windowToken, 0)
        store.recordAnswer(correct)
        render()
        if (store.speakAfterAnswer) store.studyWords.firstOrNull { it.id == task.wordId }?.let { play(it) }
    }
    private fun acceptSynonym(word: Word, task: Task) {
        MaterialAlertDialogBuilder(this).setTitle("Добавить синоним?")
            .setMessage("«${store.draft}» будет приниматься как перевод «${word.hanzi}». Этот ответ станет верным, а ошибочная отметка будет отменена.")
            .setPositiveButton("Засчитать") { _, _ ->
                if (!store.correctAnswer()) return@setPositiveButton
                store.addStudySynonym(word, store.draft.trim())
                store.feedback = true; store.save(); render()
            }.setNegativeButton("Отмена", null).show()
    }
    private fun results(tasks: List<Task>) {
        heading("Цикл завершён", if (store.writing) "Вы воспроизвели написание всех выбранных слов." else "Все доступные сегодня направления закреплены. Новые слова ограничены дневным лимитом.")
        val box = card(jade)
        box.add(label("${tasks.size} / ${tasks.size}", 48f, Color.WHITE, true))
        box.add(label("${if (store.writing) "слов" else "навыков"} закреплено", 18f, Color.WHITE), 8)
        box.add(label("Попыток: ${tasks.sumOf { store.studyEngine.state(it).attempts }} · Ошибок: ${tasks.sumOf { store.studyEngine.state(it).errors }}\nСтатистика накопительная, за все циклы.", 14f, Color.WHITE), 16)
        content.add(box, 24)
        val due = tasks.count { store.studyEngine.state(it).due <= System.currentTimeMillis() }
        content.add(button("Повторить на сегодня ($due)", true) {
            if (due == 0) toast("Сегодня повторений пока нет") else { store.studyEngine.restart(tasks, true); store.nextQuestion(); render() }
        }, 20)
        content.add(button("Новый цикл — все выбранные слова") {
            MaterialAlertDialogBuilder(this).setTitle("Начать новый цикл?").setMessage("Закрепление в этом режиме начнётся заново. Общая статистика и другой режим сохранятся.")
                .setPositiveButton("Начать") { _, _ -> store.studyEngine.restart(tasks); store.nextQuestion(); render() }.setNegativeButton("Отмена", null).show()
        }, 10)
        content.add(button(if (store.writing) "Перейти к знаниям" else "Перейти к написанию") { start(!store.writing) }, 10)
        content.add(button("Посмотреть трудные слова") { store.route = "stats"; render() }, 10)
        val earliest = tasks.map { store.studyEngine.state(it).due }.filter { it > 0 }.minOrNull()
        earliest?.let { content.add(label("Ближайшее повторение: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}", 14f, muted), 18) }
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
        val error = label("Можно вставить JSON или ответ с одним блоком кода JSON.", 13f, muted)
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
        box.add(label("${preview.words.size} слов. Создадим отдельный набор с новым прогрессом. Другие наборы сохранятся.", 15f, muted))
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
            val names = store.packNames
            val chosen = store.practicePackIds.toMutableSet()
            val dialog = MaterialAlertDialogBuilder(this).setTitle("Наборы для практики")
                .setMultiChoiceItems(names.map { it.second }.toTypedArray(), names.map { it.first in chosen }.toBooleanArray()) { _, index, checked ->
                    if (checked) chosen.add(names[index].first) else chosen.remove(names[index].first)
                }.setPositiveButton("Применить", null).setNegativeButton("Отмена", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (chosen.isEmpty()) toast("Выберите хотя бы один набор")
                else { voice.stop(); store.choosePracticePacks(chosen); dialog.dismiss(); render() }
            } }; dialog.show()
        }, 12)
        parent.add(label(store.practiceLabel, 14f, muted), 6)
    }
    private fun packPicker() {
        content.add(button("Набор: ${store.packName} ▾") {
            val names = store.packNames
            MaterialAlertDialogBuilder(this).setTitle("Выберите набор")
                .setSingleChoiceItems(names.map { it.second }.toTypedArray(), names.indexOfFirst { it.first == store.activePackId }) { dialog, index ->
                    voice.stop(); store.editPack(names[index].first); dialog.dismiss(); render()
                }.setPositiveButton("Закрыть", null)
                .setNeutralButton("Изменить") { _, _ -> managePack() }.show()
        }, 12)
    }
    private fun chooseEditingPack(action: () -> Unit) {
        val names = store.packNames.filter { it.first in store.practicePackIds }
        if (names.size == 1) { store.editPack(names.first().first); action(); return }
        MaterialAlertDialogBuilder(this).setTitle("Выберите набор для изменения")
            .setItems(names.map { it.second }.toTypedArray()) { _, index ->
                store.editPack(names[index].first); action()
            }.setNegativeButton("Отмена", null).show()
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
                    .setPositiveButton("Удалить") { _, _ -> store.deletePack(); render() }.setNegativeButton("Отмена", null).show()
            }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { store.renamePack(name.text.toString()); dialog.dismiss(); render() } catch (e: Exception) { name.error = e.message }
        } }; dialog.show()
    }
    private fun library() {
        heading("Ваши слова", "Отметьте слова, которые хотите учить.")
        content.add(button("＋ Новый набор", true) { importHub() }, 12)
        practicePicker()
        content.add(button("Управление набором") { chooseEditingPack { managePack() } }, 8)
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
        fun selectionText(): String {
            val packs = store.libraryPacks()
            return "Выбрано ${packs.sumOf { it.selected.size }} из ${packs.sumOf { it.words.size }}"
        }
        content.add(label("Отметьте отдельно: «Узнаю слово» — знание значения, пиньиня и произношения; «Умею писать» — написание по памяти. Отмеченные навыки считаются закреплёнными и вернутся на повторение завтра. Снимите отметку, чтобы учить их снова. Отметки действуют в этом наборе.", 13f, muted), 12)
        val selectedLabel = label(selectionText(), 14f, jade)
        content.add(selectedLabel, 16)
        val search = field("Найти слово, пиньинь или перевод", "", content)
        val list = column()
        content.add(list, 12)
        fun fill(query: String) {
            list.removeAllViews()
            val needle = Answers.meaning(query)
            val filtered = store.libraryPacks().flatMap { pack -> pack.words.map { pack to it } }.filter { (pack, word) ->
                needle.isEmpty() || Answers.meaning("${pack.name} ${word.hanzi} ${word.pinyin} ${word.numbered} ${word.meanings.joinToString(" ")}").contains(needle)
            }
            if (filtered.isEmpty()) list.add(label("Слова не найдены", 16f, muted), 12)
            if (filtered.size > 80) list.add(label("Показаны первые 80 слов. Уточните поиск, чтобы найти остальные.", 13f, muted), 8)
            val cards = filtered.take(80).map { (pack, word) ->
                val item = card()
                item.add(label(pack.name, 13f, muted), 4)
                val line = row()
                val check = CheckBox(this).apply {
                    isChecked = word.id in pack.selected; contentDescription = "Учить ${word.hanzi} · ${pack.name}"; minWidth = dp(48); minHeight = dp(48)
                    buttonTintList = ColorStateList.valueOf(jade)
                    setOnCheckedChangeListener { _, checked ->
                        store.selectLibraryWord(pack.id, word.id, checked)
                        selectedLabel.text = selectionText()
                        selectionLabel?.text = "${store.studyWords.size} слов"
                    }
                }
                line.addView(check)
                val info = column().apply { add(label("${word.hanzi}   ${word.pinyin}", 25f, jade, true)); add(label(word.meanings.joinToString(" / "), 15f, muted), 6) }
                line.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); item.add(line)
                WordKnowledge.entries.forEach { category ->
                    item.add(CheckBox(this).apply {
                        text = category.title
                        contentDescription = "${category.title}: ${word.hanzi} · ${pack.name}"
                        minHeight = dp(48); buttonTintList = ColorStateList.valueOf(jade)
                        isChecked = word.id in if (category == WordKnowledge.RECOGNITION) pack.recognized else pack.writable
                        setOnCheckedChangeListener { _, checked -> store.setWordKnown(pack.id, word.id, category, checked) }
                    }, 4)
                }
                item.twoButtons(button("▶ Слушать") { play(word) }, button("Словарь") { dictionary(word) })
                item.add(button("Изменить слово") { store.editPack(pack.id); editWord(word) }, 8)
                item.add(button("Навыки этого слова") {
                    store.editPack(pack.id)
                    val allowed = store.engine.tasks(store.words, false, store.audioEnabled).filter { it.wordId == word.id } + Task(word.id, Skill.WRITING)
                    val details = allowed.joinToString("\n\n") { t ->
                        val p = store.engine.state(t)
                        "${t.skill.title}\n${if (p.mastered) "Закреплено" else if (p.attempts == 0) "Новый" else "На повторении"} · попыток ${p.attempts}, ошибок ${p.errors}, серия ${p.streak}"
                    }
                    message("${word.hanzi} · ${word.pinyin}", details)
                }, 8)
                item
            }
            list.addCards(cards)
        }
        fill(""); search.doAfterTextChanged { fill(it.toString()) }
    }
    private fun stats() {
        heading("Прогресс", "Обзор всей библиотеки и подробности по набору.")
        val overview = store.overview()
        val combined = card()
        combined.add(label("Все наборы · ${overview.sumOf { it.total }} слов", 22f, jade, true))
        combined.add(label("Введено: ${overview.sumOf { it.introduced }} · Слов изучено: ${overview.sumOf { it.learned }}\nНавыков закреплено: ${overview.sumOf { it.mastered }} · Повторений пора: ${overview.sumOf { it.due }}", 17f), 12)
        combined.add(label("Слово изучено, когда закреплены все доступные направления, включая письмо и аудирование. Повторения считаются по навыкам. Одинаковые слова в разных наборах учитываются отдельно.", 13f, muted), 8)
        overview.forEach { pack -> combined.add(label("${pack.name}: введено ${pack.introduced}/${pack.total} · изучено ${pack.learned} · пора повторить ${pack.due}", 15f), 10) }
        Skill.entries.forEach { skill ->
            val states = overview.flatMap { it.skills[skill].orEmpty() }
            combined.add(label("${skill.title}: закреплено ${states.count { it.mastered }}/${states.size} · трудных ${states.count { it.errors > 0 && !it.mastered }} · ошибок ${states.sumOf { it.errors }}/${states.sumOf { it.attempts }}", 14f), 8)
        }
        content.add(combined, 16)
        packPicker()
        val all = store.engine.tasks(store.words, false, store.audioEnabled)
        val writing = store.engine.tasks(store.words, true, false)
        val summary = card()
        summary.add(label("Знание: ${all.count { store.engine.state(it).mastered }} / ${all.size}", 20f, jade, true))
        summary.add(progress(all, store.engine), 12)
        summary.add(label("Написание: ${writing.count { store.engine.state(it).mastered }} / ${writing.size}", 20f, jade, true), 20)
        summary.add(progress(writing, store.engine), 12)
        summary.add(label("В этом обзоре — все слова текущего набора. Обучение использует только выбранные слова.", 13f, muted), 14)
        content.add(summary, 20)
        content.add(label("Что сложнее запомнить", 22f, ink, true), 24)
        content.add(label("Сначала — незакреплённые задания. Чем чаще ошибки и короче серия верных ответов, тем выше задание в списке.", 13f, muted), 8)
        val pairs = store.engine.progress.entries.flatMap { (key, state) ->
            val word = store.words.firstOrNull { key.substringBefore(':') == it.id }
            state.confusions.entries.filter { word != null && !word.acceptsForm(it.key) }.map { Triple(word!!, it.key, it.value) }
        }.sortedByDescending { it.third }
        if (pairs.isNotEmpty()) {
            content.add(label("Часто путаю", 22f, ink, true), 16)
            pairs.take(20).forEach { (word, wrong, count) -> content.add(label("${word.hanzi} ↔ $wrong · $count ошибок", 17f), 6) }
            content.add(button("Практиковать похожие знаки") {
                val focus = store.engine.progress.filter { (key, p) -> p.confusions.isNotEmpty() && store.words.any { it.id == key.substringBefore(':') } }
                    .keys.mapNotNull { key -> Skill.entries.find { it.name == key.substringAfter(':') }?.let { Task(key.substringBefore(':'), it) } }
                val partners = focus.flatMap { task ->
                    val wrong = store.engine.state(task).confusions.keys
                    store.words.filter { word -> word.writtenForms.any { it in wrong } }.map { Task(it.id, task.skill) }
                }
                store.choosePracticePacks(setOf(store.activePackId))
                startShort(false, (focus + partners).distinct())
            }, 12)
        }
        val problematic = RecallDifficulty.rank(all + writing, store.engine.progress)
        if (problematic.isNotEmpty()) content.add(button("Практиковать трудные слова", true) {
            val groups = listOf("Все трудные задания", "Тоны", "Аудирование", "Значение", "Написание")
            MaterialAlertDialogBuilder(this).setTitle("Что практиковать?").setItems(groups.toTypedArray()) { _, index ->
                val skills = when (index) {
                    1 -> setOf(Skill.CHARACTER_TONE, Skill.CHARACTER_PINYIN, Skill.MEANING_PINYIN)
                    2 -> setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)
                    3 -> setOf(Skill.CHARACTER_MEANING, Skill.PINYIN_MEANING)
                    4 -> setOf(Skill.WRITING)
                    else -> Skill.entries.toSet()
                }
                val focus = problematic.filter { it.skill in skills }.take(100)
                if (focus.isEmpty()) toast("В этой группе нет трудных заданий") else {
                    store.choosePracticePacks(setOf(store.activePackId))
                    startShort(false, focus, index == 4)
                }
            }.setNegativeButton("Отмена", null).show()
        }, 12)
        if (problematic.isEmpty()) content.add(label("Заданий с ошибками пока нет.", 15f, muted), 12)
        if (problematic.size > 100) content.add(label("Показаны 100 самых сложных заданий.", 13f, muted), 10)
        val difficultCards = problematic.take(100).map { task ->
            val w = store.words.first { it.id == task.wordId }; val p = store.engine.state(task)
            val item = card()
            item.add(label("${w.hanzi}  ·  ${w.pinyin}", 24f, jade, true))
            item.add(label(task.skill.title, 16f, ink), 6)
            item.add(label("${if (p.mastered) "Закреплено" else "На повторении"} · Ошибок ${p.errors} / попыток ${p.attempts}\nВерных подряд: ${p.streak}", 14f, muted), 8)
            item
        }
        content.addCards(difficultCards)
        val excluded = store.words.size * (if (store.audioEnabled) 9 else 7) - all.size
        if (excluded > 0) content.add(label("Исключено неоднозначных направлений: $excluded. Одинаковое произношение или перевод не должны заставлять вас угадывать.", 14f, muted), 20)
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
                    .setMessage("Наборы и прогресс останутся на устройстве. Доступ к синхронизации сохранится для этого аккаунта; повторно вводить пароль доступа не потребуется.")
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
        about.add(label("После ошибки задание возвращается через 4–7 других ответов. Два верных ответа подряд закрепляют навык. В маленьком наборе пауза может быть короче. Знание и письмо учитываются отдельно.\n\nОсвоенные навыки назначаются на повторение через 1, 3, 7, 15 и 30 дней. Повторение на сегодня доступно на экране завершённого цикла.\n\nПиньинь проверяется по словарным тонам. В речи возможны изменения тонов: например, nǐ hǎo обычно звучит как ní hǎo.\n\nНаписание оцениваете вы сами; автоматической проверки черт нет.", 15f, muted), 12)
        optionsColumn.add(about, 16)
        val privacy = card()
        privacy.add(label("Ваши данные", 21f, ink, true))
        privacy.add(label("Слова, синонимы и результаты сохраняются на устройстве. Учётная запись необязательна. После подтверждения почты и включения синхронизации наборы и прогресс также хранятся в Google Firebase и доступны на ваших устройствах под тем же аккаунтом. Разные аккаунты имеют отдельные локальные библиотеки. При удалении приложения неотправленные данные могут быть потеряны. Экспорт JSON сохраняет словарь; результаты в него не входят. Разрешение на синхронизацию не переносится на другие устройства через резервную копию Android.\n\nПри использовании Hanping выбранное слово передаётся этому приложению. Системный голос может использовать интернет, если выбран сетевой голос.", 14f, muted), 12)
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
    private fun editWord(original: Word?) {
        val form = column().apply { setPadding(dp(22), dp(8), dp(22), dp(16)) }
        val hanzi = field("Иероглифы", original?.hanzi ?: "", form)
        val pinyin = field("Пиньинь с диакритикой", original?.pinyin ?: "", form)
        val numbered = field("Пиньинь с цифрами · ni3 hao3", original?.numbered ?: "", form)
        val meanings = field("Переводы / синонимы · через ;", original?.meanings?.joinToString("; ") ?: "", form)
        val distractors = field("Похожие неверные знаки · через ;", original?.distractors?.joinToString("; ") ?: "", form)
        val alternatives = field("Другие формы слова / радикала · через ;", original?.alternatives?.joinToString("; ") ?: "", form)
        form.add(label("Например, для радикала 水 можно указать 氵. Добавляйте только формы, допустимые именно для этой карточки: они будут приниматься в ответах и показываться при проверке письма.", 13f, muted), 6)
        val hint = field("Подсказка после ответа", original?.hint ?: "", form)
        val example = field("Пример на китайском (необязательно)", original?.example ?: "", form)
        val examplePinyin = field("Пиньинь примера", original?.examplePinyin ?: "", form)
        val exampleTranslation = field("Перевод примера на русский", original?.exampleTranslation ?: "", form)
        form.add(label("Не менее 3 неверных вариантов. Изменение карточки сбрасывает её учебный прогресс.", 13f, muted), 12)
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
                    require(store.words.none { it.id != word.id && it.hanzi == word.hanzi }) { "Это слово уже есть. Измените существующую карточку." }
                    store.replaceWord(word); shown.dismiss(); render()
                } catch (e: Exception) { message("Проверьте карточку", e.message ?: "Неверные данные") }
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
    private fun dictionary(word: Word) {
        MaterialAlertDialogBuilder(this).setTitle("Открыть ${word.hanzi}").setItems(arrayOf("Pleco", "Hanping")) { _, which -> voice.lookup(word, which == 0)?.let { toast(it) } }.show()
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
