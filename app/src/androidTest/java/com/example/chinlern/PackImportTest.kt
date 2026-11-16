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
    @Test fun errorsIdentifyTheCardAndFieldToFix() {
        val invalid = listOf(
            json.replace("\"meanings\":[\"вода\"]", "\"meanings\":42") to "meanings",
            json.replace("\"pinyin\":\"shuǐ\",", "") to "pinyin",
            json.replace("\"alternatives\":[\"氵\"]", "\"alternatives\":[42]") to "alternatives"
        )
        invalid.forEach { (input, field) ->
            try { PackImport.parse(input); fail("Invalid field accepted") }
            catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.contains("Карточка 1 «水»"))
                assertTrue(e.message!!, e.message!!.contains(field))
            }
        }
        val missingHanzi = """{"pinyin":"huo3","meanings":["огонь"]}"""
        val first = org.json.JSONObject(json).getJSONArray("words").getJSONObject(0)
        try { PackImport.parse("[$first,$missingHanzi]"); fail("Missing hanzi accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("Карточка 2")) }
        try { PackImport.parse("[$first,$first]"); fail("Duplicate accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("повторяет карточку 1")) }
    }

    @Test fun promptExampleIsImportable() {
        val example = PackImport.prompt.lineSequence().first { it.startsWith("{\"name\"") }
        assertEquals("水", PackImport.parse(example).words.single().hanzi)
    }
}
