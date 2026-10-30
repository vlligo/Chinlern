package com.example.chinlern

import org.junit.Assert.assertEquals
import org.junit.Test

class HanpingPinyinTest {
    @Test fun expandsErhuaWithoutChangingStandaloneEr() {
        assertEquals("zhe4 r5", HanpingPinyin.encode("zher4"))
        assertEquals("na3 r5", HanpingPinyin.encode("nar3"))
        assertEquals("yi4 dian3 r5", HanpingPinyin.encode("yi4 dianr3"))
        assertEquals("er2 zi5", HanpingPinyin.encode("er2 zi5"))
        assertEquals("er4", HanpingPinyin.encode("er4"))
        assertEquals("zhe4 r5", HanpingPinyin.encode("zhe4 r5"))
    }
    @Test fun encodesUmlautAliasesWithoutChangingTones() {
        listOf("nü3", "nv3", "nu:3", "NU\u03083").forEach { assertEquals("nv3", HanpingPinyin.encode(it)) }
        assertEquals("lv4 se4", HanpingPinyin.encode(" lü4  se4 "))
        assertEquals("nu3", HanpingPinyin.encode("nu3"))
        assertEquals("xue2 sheng5", HanpingPinyin.encode("xue2 sheng5"))
    }
}
