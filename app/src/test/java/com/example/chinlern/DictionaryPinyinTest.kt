package com.example.chinlern

import com.example.chinlern.dictionary.OfflineDictionary
import org.junit.Assert.*
import org.junit.Test

class DictionaryPinyinTest {
    @Test fun normalizesToneStylesAndPreservesUmlautDistinction() {
        for (value in listOf("nü3", "nu:3", "nǚ", "nv3")) assertEquals("nv", OfflineDictionary.pinyinKey(value))
        assertEquals("nu", OfflineDictionary.pinyinKey("nu3"))
        assertEquals("diannao", OfflineDictionary.pinyinKey("DIÀN NǍO"))
    }
}
