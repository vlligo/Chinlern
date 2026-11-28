package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.dictionary.OfflineDictionary
import com.example.chinlern.data.ProfileStorage
import org.junit.Assert.*
import org.junit.Test

class OfflineDictionaryTest {
    @Test fun russianPackProvidesAttributedDefinitionsAndReverseSearchOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionary = OfflineDictionary(context, OfflineDictionary.Source.RU_WIKTIONARY)
        val entry = dictionary.exact("圖書館").single()
        assertEquals("图书馆", entry.article)
        assertEquals("tu2 shu1 guan3", entry.pinyin)
        assertEquals(listOf("библиотека"), entry.meanings)
        assertTrue(dictionary.search("библиотека").any { it.article == "图书馆" })
        assertTrue(dictionary.search("tu2 shu1 guan3").any { it.article == "图书馆" })
        assertTrue(dictionary.notice().contains("Wiktextract"))
        assertTrue(OfflineDictionary(context).exact("圖書館").any { "library" in it.meanings })
    }

    @Test fun interruptedInstallationCanBeRetried() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.noBackupFilesDir, "dictionary").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        java.io.File(directory, "install.tmp").writeText("interrupted")
        assertTrue(OfflineDictionary(context).search("水").any { it.simplified == "水" })
        assertFalse(java.io.File(directory, "install.tmp").exists())
    }

    @Test fun bundledDictionaryFindsFormsReadingsAndEnglishWithoutChangingProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = ProfileStorage.open(context, "chinlearn_v1").all
        val dictionary = OfflineDictionary(context)
        assertTrue(dictionary.exact("電腦").any { it.simplified == "电脑" && it.traditional == "電腦" })
        assertTrue(dictionary.exact("电").all { it.simplified == "电" || it.traditional == "电" })
        assertTrue(dictionary.exact("行").map { it.pinyin }.distinct().size > 1)
        assertTrue(dictionary.exact("图书馆。").isEmpty())
        assertTrue(dictionary.search("電腦").any { it.simplified == "电脑" && it.traditional == "電腦" })
        assertTrue(dictionary.search("电脑").any { it.meanings.any { meaning -> "computer" in meaning } })
        for (query in listOf("dian4 nao3", "diàn nǎo", "diannao"))
            assertTrue(query, dictionary.search(query).any { it.simplified == "电脑" })
        assertTrue(dictionary.search("computer").isNotEmpty())
        assertTrue(dictionary.search("行").filter { it.simplified == "行" }.map { it.pinyin }.distinct().size > 1)
        for (query in listOf("xing2", "xíng", "hang2")) {
            val matches = dictionary.search(query)
            assertTrue(query, matches.isNotEmpty())
            assertTrue(query, matches.all { Answers.pinyinMatches(query, it.pinyin) })
            assertTrue(query, matches.any { it.simplified == "行" })
        }
        assertTrue(dictionary.search("𥻗").any { it.simplified == "𥻗粥" })
        assertTrue(dictionary.search(" ").isEmpty())
        assertTrue(dictionary.search("zzzzzznotaword").isEmpty())
        assertTrue(dictionary.search("' OR 1=1 --").size <= 50)
        assertEquals(before, ProfileStorage.open(context, "chinlearn_v1").all)
        assertTrue(dictionary.notice().contains("ShareAlike"))
    }
}
