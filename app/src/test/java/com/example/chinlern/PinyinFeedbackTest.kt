package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class PinyinFeedbackTest {
    @Test fun toneErrorsIdentifyOnlyTheAffectedSyllable() {
        val feedback = Answers.pinyinFeedback("ni3hao2", "ni3 hao3")
        assertEquals(1, feedback.size)
        assertTrue(feedback.single().contains("hao2 → hao3"))
        assertTrue(feedback.single().startsWith("Тон 2"))
    }
    @Test fun spellingAndToneErrorsAreSeparate() {
        val feedback = Answers.pinyinFeedback("mi3 hao2", "ni3 hao3")
        assertTrue(feedback[0].contains("неверный слог"))
        assertTrue(feedback[1].startsWith("Тон"))
    }
    @Test fun marksAliasesAndNeutralTonesRemainAccepted() {
        listOf("nǚ", "nv3", "nu:3", "nü3").forEach { assertTrue(Answers.pinyinFeedback(it, "nü3").isEmpty()) }
        assertTrue(Answers.pinyinFeedback("xuésheng", "xue2 sheng5").isEmpty())
        assertTrue(Answers.pinyinFeedback("ni3 háo", "ni3 hao3").single().contains("háo → hao3"))
    }
    @Test fun missingAndDuplicateTonesProduceFeedback() {
        assertTrue(Answers.pinyinFeedback("hao", "hao3").single().startsWith("Тон"))
        assertTrue(Answers.pinyinFeedback("hao33", "hao3").isNotEmpty())
        assertTrue(Answers.pinyinFeedback("", "hao3").isEmpty())
        assertTrue(Answers.pinyinFeedback("nu3", "nü3").single().contains("неверный слог"))
    }
}
