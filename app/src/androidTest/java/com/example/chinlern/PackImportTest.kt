package com.example.chinlern

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackImportTest {
    private val json = """{"name":"Радикалы","words":[{"hanzi":"水","pinyin":"shuǐ","numbered":"shui3","meanings":["вода"],"alternatives":["氵"]}]}"""
    @Test fun acceptsJsonFilesClipboardAndOneFencedResponse() {
        listOf(json, "\uFEFF$json", "Вот набор:\n```json\n$json\n```\nГотово.").forEach { text ->
            val pack = PackImport.parse(text)
            assertEquals("Радикалы", pack.name)
            assertEquals(listOf("氵"), pack.words.single().alternatives)
            assertTrue(pack.words.single().distractors.size >= 3)
        }
    }
    @Test fun rejectsAmbiguousBrokenOrOversizedResponses() {
        listOf("", "$json trailing", "```json\n$json\n```\n```json\n$json\n```", "x".repeat(2_000_001), json.replace("shuǐ", "shui2")).forEach { input ->
            try { PackImport.parse(input); fail("Invalid response accepted") } catch (_: IllegalArgumentException) { }
        }
        try { PackImport.parse(json.replace("shuǐ", "shui2")); fail("Invalid tones") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Карточка 1")) }
    }
    @Test fun promptExampleIsImportable() {
        val example = PackImport.prompt.lineSequence().first { it.startsWith("{\"name\"") }
        assertEquals("水", PackImport.parse(example).words.single().hanzi)
    }
}
