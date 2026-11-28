package com.example.chinlern

import android.os.Handler
import android.os.Looper
import com.example.chinlern.texts.TextSync
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

/** Explicit server transactions keep offline edits local until they can be safely merged. */
class CloudSync(
    private val store: StudyStore,
    private val uid: String,
    private val onStatus: (String) -> Unit,
    private val onApplied: (Int, Boolean) -> Unit,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val syncCollection = db.collection("users").document(uid).collection("sync")
    private val wordsRoot = syncCollection.document("state")
    private val textsRoot = syncCollection.document("texts")
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableMapOf<DocumentReference, ListenerRegistration>()
    private var running = false
    private var syncAgain = false
    @Volatile private var closed = false
    private fun allowed() = !closed && store.syncAccess.enabled &&
        auth.currentUser?.let { it.uid == uid && it.isEmailVerified } == true
    private var scheduled = false
    private var retryDelay = 30_000L
    private val syncTask = Runnable { scheduled = false; sync() }

    private data class Result(val snapshot: Map<String, String>, val revision: String?)

    init {
        if (allowed()) {
            store.onSyncDataChanged = { schedule(5_000) }
            listen()
            schedule(500)
        }
    }

    private fun listen() {
        listen(wordsRoot) { store.syncRevision }
        listen(textsRoot) { store.textSyncRevision }
    }

    private fun listen(root: DocumentReference, revision: () -> String?) {
        if (!allowed() || root in listeners) return
        listeners[root] = root.addSnapshotListener { snapshot, error ->
            if (allowed()) {
                if (error != null) {
                    listeners.remove(root)?.remove()
                    schedule(retryDelay)
                } else if (snapshot != null && !snapshot.metadata.isFromCache &&
                    snapshot.getString("revision") != revision()) schedule(500)
            }
        }
    }

    private fun schedule(delay: Long) {
        if (!allowed() || scheduled) return
        scheduled = true
        handler.postDelayed(syncTask, delay)
    }

    fun sync() {
        if (!allowed()) return
        if (running) { syncAgain = true; return }
        handler.removeCallbacks(syncTask); scheduled = false
        running = true
        syncAgain = false
        onStatus("Синхронизация…")
        try {
            val localWords = store.savedSyncSnapshot()
            val localTexts = store.savedTextSyncSnapshot()
            val textsBase = store.textSyncBase()
            val wordsBaseRevision = store.syncRevision
            val words = transfer(wordsRoot, localWords, store.syncBase, wordsBaseRevision, StudyStore::validateSyncPacks) { base, local, remote ->
                // A fresh install should download the library without adding its seed pack.
                val incoming = if (wordsBaseRevision == null && base.isEmpty() && remote.isNotEmpty() &&
                    untouchedStarter(local)) emptyMap() else local
                SyncSnapshot.merge(base, incoming, remote).packs
            }
            val texts = transfer(textsRoot, localTexts, textsBase, store.textSyncRevision,
                TextSync::validate, TextSync::merge)
            Tasks.whenAllComplete(listOf(words, texts)).addOnCompleteListener {
                running = false
                if (!allowed()) return@addOnCompleteListener
                var changed = false
                var pending = syncAgain
                val wordsError = runCatching {
                    val result = words.result
                    val revision = checkNotNull(result.revision)
                    if (result.snapshot == localWords) store.acknowledgeSync(result.snapshot, revision)
                    else {
                        val before = store.savedSyncSnapshot()
                        store.applySync(localWords, result.snapshot, revision)
                        changed = before != store.savedSyncSnapshot()
                    }
                    pending = pending || store.savedSyncSnapshot() != result.snapshot
                }.exceptionOrNull()
                val textsError = runCatching {
                    val result = texts.result
                    result.revision?.let { revision ->
                        changed = store.applyTextSync(localTexts, result.snapshot, revision) || changed
                    }
                    pending = pending || store.savedTextSyncSnapshot() != result.snapshot
                }.exceptionOrNull()
                val failure = wordsError ?: textsError
                if (failure != null) {
                    val message = errorText(failure)
                    onStatus(if (wordsError == null) "Тексты: $message" else message)
                    retry()
                } else {
                    retryDelay = 30_000
                    onStatus(if (pending) "Новые изменения ожидают отправки" else "Синхронизировано")
                    listen()
                }
                if (failure == null || changed) onApplied(0, changed)
                if (pending && failure == null) schedule(5_000)
            }
        } catch (error: Exception) {
            running = false
            onStatus(errorText(error))
            retry()
        }
    }

    private fun retry() {
        schedule(retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(300_000)
    }

    private fun transfer(
        root: DocumentReference,
        local: Map<String, String>,
        base: Map<String, String>,
        baseRevision: String?,
        validate: (Map<String, String>) -> Unit,
        merge: (Map<String, String>, Map<String, String>, Map<String, String>) -> Map<String, String>
    ): com.google.android.gms.tasks.Task<Result> {
        val proposedRevision = UUID.randomUUID().toString()
        return db.runTransaction { transaction ->
            check(allowed()) { "Синхронизация выключена или почта не подтверждена." }
            val manifest = transaction.get(root)
            val oldCount = if (manifest.exists()) {
                require(manifest.getLong("schema") == SyncSnapshot.VERSION.toLong()) { "Обновите приложение для чтения облачных данных." }
                val count = manifest.getLong("chunks") ?: error("В облаке отсутствуют данные.")
                require(count in 1..SyncSnapshot.MAX_CHUNKS.toLong()) { "Некорректный размер облачных данных." }
                count.toInt()
            } else 0
            val remoteRevision = if (manifest.exists()) {
                requireNotNull(manifest.getString("revision")) { "В облаке отсутствует версия данных." }
            } else null
            val remote = when {
                !manifest.exists() -> emptyMap()
                remoteRevision != null && remoteRevision == baseRevision -> base
                else -> SyncSnapshot.decompress((0 until oldCount).map { index ->
                    transaction.get(root.collection("chunks").document(index.toString()))
                        .getBlob("data")?.toBytes() ?: error("Облачная копия неполна. Повторите синхронизацию.")
                })
            }
            if (remote.isNotEmpty()) validate(remote)
            // An absent manifest has no published state. An intentional deletion is a new, empty snapshot.
            val merged = if (manifest.exists()) merge(base, local, remote) else local
            if (merged != remote) validate(merged)
            check(allowed()) { "Синхронизация выключена." }
            if (merged == remote) {
                Result(remote, remoteRevision)
            } else {
                val chunks = SyncSnapshot.compress(merged)
                chunks.forEachIndexed { index, bytes -> transaction.set(
                    root.collection("chunks").document(index.toString()), mapOf("data" to Blob.fromBytes(bytes))
                ) }
                for (index in chunks.size until oldCount) transaction.delete(root.collection("chunks").document(index.toString()))
                transaction.set(root, mapOf("schema" to SyncSnapshot.VERSION, "chunks" to chunks.size, "revision" to proposedRevision))
                Result(merged, proposedRevision)
            }
        }
    }

    fun close() {
        closed = true
        store.onSyncDataChanged = null
        handler.removeCallbacks(syncTask)
        listeners.values.forEach(ListenerRegistration::remove)
        listeners.clear()
    }

    companion object {
        private fun untouchedStarter(packs: Map<String, String>): Boolean {
            if (packs.keys != setOf("default")) return false
            val pack = org.json.JSONObject(packs.getValue("default"))
            val words = StudyStore.decodeWords(pack.getString("words"))
            return pack.getString("name") == "Мои слова" && pack.getInt("step") == 0 &&
                pack.getJSONObject("progress").length() == 0 &&
                pack.getJSONArray("selected").length() == words.size &&
                words == StudyStore.decodeWords(StudyStore.seedJson)
        }

        fun errorText(error: Throwable): String {
            val firestore = error as? FirebaseFirestoreException
                ?: (error.cause as? FirebaseFirestoreException)
            return when {
                error is FirebaseNetworkException || firestore?.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "Нет связи с облаком. Изменения сохранены на устройстве; повторим позже."
                firestore?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "Нет доступа к Firestore. Опубликуйте правила из firestore.rules в Firebase Console."
                firestore?.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                    "Достигнут лимит Firebase. Изменения сохранены на устройстве."
                firestore?.code == FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "Войдите в аккаунт ещё раз. Изменения сохранены на устройстве."
                else -> "Не удалось синхронизировать: ${error.cause?.message ?: error.message ?: "попробуйте позже"}. Локальные данные сохранены."
            }
        }
    }
}
