package com.example.chinlern

import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
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
    private val root = db.collection("users").document(uid).collection("sync").document("state")
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ListenerRegistration? = null
    private var running = false
    private var syncAgain = false
    @Volatile private var closed = false
    private fun allowed() = !closed && store.syncAccess.enabled &&
        auth.currentUser?.let { it.uid == uid && it.isEmailVerified } == true
    private var scheduled = false
    private var retryDelay = 30_000L
    private val syncTask = Runnable { scheduled = false; sync() }

    private data class Result(val packs: Map<String, String>, val revision: String, val conflicts: Int)

    init {
        if (allowed()) {
            store.onSyncDataChanged = { schedule(5_000) }
            listen()
            schedule(500)
        }
    }

    private fun listen() {
        if (!allowed()) return
        listener = root.addSnapshotListener { snapshot, error ->
            if (allowed()) {
                if (error != null) {
                    listener?.remove(); listener = null
                    onStatus(errorText(error))
                    schedule(retryDelay)
                }
                else if (snapshot != null && !snapshot.metadata.isFromCache &&
                    snapshot.getString("revision") != store.syncRevision) schedule(500)
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
        val local = store.savedSyncSnapshot()
        val base = store.syncBase.toMap()
        val baseRevision = store.syncRevision
        val proposedRevision = UUID.randomUUID().toString()
        running = true
        syncAgain = false
        onStatus("Синхронизация…")
        db.runTransaction { transaction ->
            check(allowed()) { "Синхронизация выключена или почта не подтверждена." }
            val manifest = transaction.get(root)
            val oldCount = if (manifest.exists()) {
                require(manifest.getLong("schema") == SyncSnapshot.VERSION.toLong()) { "Обновите приложение для чтения облачных данных." }
                val count = manifest.getLong("chunks") ?: error("В облаке отсутствуют данные.")
                require(count in 1..SyncSnapshot.MAX_CHUNKS.toLong()) { "Некорректный размер облачных данных." }
                count.toInt()
            } else 0
            val remoteRevision = manifest.getString("revision")
            val remote = when {
                !manifest.exists() -> emptyMap()
                remoteRevision != null && remoteRevision == baseRevision -> base
                else -> SyncSnapshot.decompress((0 until oldCount).map { index ->
                    transaction.get(root.collection("chunks").document(index.toString()))
                        .getBlob("data")?.toBytes() ?: error("Облачная копия неполна. Повторите синхронизацию.")
                })
            }
            if (remote.isNotEmpty()) StudyStore.validateSyncPacks(remote)
            // A fresh install should download an existing library, without adding a second seed pack.
            val incoming = if (baseRevision == null && base.isEmpty() && remote.isNotEmpty() && untouchedStarter(local)) emptyMap() else local
            val merged = SyncSnapshot.merge(base, incoming, remote)
            StudyStore.validateSyncPacks(merged.packs)
            check(allowed()) { "Синхронизация выключена." }
            if (merged.packs == remote && remoteRevision != null) {
                Result(remote, remoteRevision, merged.conflicts)
            } else {
                val chunks = SyncSnapshot.compress(merged.packs)
                chunks.forEachIndexed { index, bytes -> transaction.set(
                    root.collection("chunks").document(index.toString()), mapOf("data" to Blob.fromBytes(bytes))
                ) }
                for (index in chunks.size until oldCount) transaction.delete(root.collection("chunks").document(index.toString()))
                transaction.set(root, mapOf("schema" to SyncSnapshot.VERSION, "chunks" to chunks.size, "revision" to proposedRevision))
                Result(merged.packs, proposedRevision, merged.conflicts)
            }
        }.addOnSuccessListener { result ->
            running = false
            if (!allowed()) return@addOnSuccessListener
            try {
                val before = if (result.packs == local) null else store.savedSyncSnapshot()
                val moreConflicts = if (before == null) {
                    store.acknowledgeSync(result.packs, result.revision)
                    0
                } else store.applySync(local, result.packs, result.revision)
                val after = store.savedSyncSnapshot()
                retryDelay = 30_000
                onStatus(if (after == result.packs) "Синхронизировано" else "Новые изменения ожидают отправки")
                onApplied(result.conflicts + moreConflicts, before != null && before != after)
                if (listener == null) listen()
                if (after != result.packs || syncAgain) schedule(5_000)
            } catch (error: Exception) {
                onStatus(errorText(error))
            }
        }.addOnFailureListener { error ->
            running = false
            if (!allowed()) return@addOnFailureListener
            onStatus(errorText(error))
            schedule(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(300_000)
        }
    }

    fun close() {
        closed = true
        store.onSyncDataChanged = null
        handler.removeCallbacks(syncTask)
        listener?.remove(); listener = null
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

        fun errorText(error: Exception): String {
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
