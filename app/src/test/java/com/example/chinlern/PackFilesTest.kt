package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackFilesTest {
    private val json = """{"name":"Test","words":[{"hanzi":"水","pinyin":"shuǐ","numbered":"shui3","meanings":["вода"]}]}"""
    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { z -> entries.forEach { (name, text) ->
            z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
        } }
    }.toByteArray()
    @Test fun readsUnknownExtensionAndBom() {
        assertEquals(1, PackFiles.read("download", ("\uFEFF" + json).byteInputStream()).items.size)
    }
    @Test fun archiveKeepsValidFilesAndReportsBadOne() {
        val result = PackFiles.read("download", zip("folder/one.json" to json, "two.JSON" to json,
            "broken.json" to "bad", "README.md" to "instructions").inputStream())
        assertEquals(2, result.items.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("broken.json"))
    }
    @Test fun zipDetectionHandlesShortProviderReads() {
        val bytes = zip("pack.json" to json)
        val provider = object : java.io.FilterInputStream(bytes.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals(1, PackFiles.read("download", provider).items.size)
    }
    @Test fun limitsExpandedDataAndEntryCounts() {
        val oversized = PackFiles.read("large.zip", zip("huge.json" to " ".repeat(2_000_001)).inputStream())
        assertTrue(oversized.items.isEmpty()); assertTrue(oversized.errors.single().contains("2 МБ"))
        val budget = PackFiles.Budget().apply { entries = 100 }
        assertTrue(PackFiles.read("file.json", json.byteInputStream(), budget).errors.isNotEmpty())
        val total = PackFiles.Budget().apply { bytes = PackFiles.MAX_TOTAL_BYTES }
        assertTrue(PackFiles.read("file.json", json.byteInputStream(), total).errors.single().contains("20 МБ"))
    }
    @Test fun initialismsDoNotNeedChineseTones() {
        assertTrue(Answers.pinyinMatches("CD pán", "CD pan2"))
        assertTrue(Answers.pinyinMatches("cd pan2", "CD pan2"))
        assertFalse(Answers.pinyinMatches("CD pan", "CD pan2"))
        assertFalse(Answers.pinyinMatches("CD pàn", "CD pan2"))
        assertFalse(Answers.pinyinMatches("DVD pán", "CD pan2"))
        assertFalse(Answers.pinyinMatches("pán", "CD pan2"))
        assertFalse(Answers.pinyinMatches("ni hao", "ni3 hao3"))
        val card = json.replace("水", "CD盘").replace("shuǐ", "CD pán").replace("shui3", "CD pan2")
        assertEquals("CD盘", PackImport.parse(card).words.single().hanzi)
    }
}
