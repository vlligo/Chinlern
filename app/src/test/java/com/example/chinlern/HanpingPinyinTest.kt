package com.example.chinlern

import org.junit.Assert.assertEquals
import org.junit.Test

class HanpingPinyinTest {
    @Test fun encodesUmlautAliasesWithoutChangingTones() {
        listOf("nü3", "nv3", "nu:3", "NU\u03083").forEach { assertEquals("nv3", HanpingPinyin.encode(it)) }
        assertEquals("lv4 se4", HanpingPinyin.encode(" lü4  se4 "))
        assertEquals("nu3", HanpingPinyin.encode("nu3"))
        assertEquals("xue2 sheng5", HanpingPinyin.encode("xue2 sheng5"))
    }
}
