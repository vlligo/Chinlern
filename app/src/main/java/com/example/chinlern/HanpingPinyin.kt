package com.example.chinlern

import java.text.Normalizer
import java.util.Locale

/** The external playback interface uses v for ü, unlike the displayed pinyin. */
object HanpingPinyin {
    fun encode(numbered: String): String = Normalizer.normalize(numbered, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace("u:", "v").replace("ü", "v")
        .trim().replace(Regex("\\s+"), " ")
        // Hanping counts 儿 as a separate phonetic unit, including in erhua.
        // zher4 is rejected for 这儿; zhe4 r5 selects its recorded pronunciation.
        .replace(Regex("([a-z]+)r([1-5])")) { match ->
            val stem = match.groupValues[1]
            if (stem == "e") match.value else "$stem${match.groupValues[2]} r5"
        }
}
