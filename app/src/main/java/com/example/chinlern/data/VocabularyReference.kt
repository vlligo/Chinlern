package com.example.chinlern.data

import java.util.UUID

/** A stable personal-card reference, scoped to its profile's repository, not a dictionary identity.
 * Existing pack/word IDs survive edits, sync and backup. Learning equivalence remains separate.
 */
data class VocabularyReference(val packId: String, val wordId: String) {
    val stableId: String get() = UUID.nameUUIDFromBytes(
        "chinlern:vocabulary:v1:${packId.length}:$packId:${wordId.length}:$wordId".toByteArray(Charsets.UTF_8)
    ).toString()
}
