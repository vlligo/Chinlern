package com.example.chinlern

import java.text.Collator
import java.util.Locale

/** Shared by every pack picker, library and summary; IDs and saved selection stay intact. */
object PackOrder {
    fun sort(names: List<Pair<String, String>>): List<Pair<String, String>> {
        val alphabet = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
        return names.sortedWith { a, b ->
            val byName = alphabet.compare(a.second.trim(), b.second.trim())
            if (byName != 0) byName else a.first.compareTo(b.first)
        }
    }
}
