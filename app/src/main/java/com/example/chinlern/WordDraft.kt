package com.example.chinlern

/** Editable defaults for a new vocabulary card. */
internal data class WordDraft(
    val pinyin: String = "", val numbered: String = "", val meaning: String = "",
    val alternatives: List<String> = emptyList(), val distractors: List<String> = emptyList(),
    val hint: String = ""
)
