package com.example.chinlern

import java.text.Normalizer
import java.util.Locale

/** The external playback interface uses v for ü, unlike the displayed pinyin. */
object HanpingPinyin {
    fun encode(numbered: String): String = Normalizer.normalize(numbered, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace("u:", "v").replace("ü", "v")
        .trim().replace(Regex("\\s+"), " ")
}
