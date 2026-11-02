package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class PackOrderTest {
    @Test fun alphabetizesCyrillicAndLatinNamesIgnoringCase() {
        assertEquals(listOf("аист", "Буквы", "вода"), PackOrder.sort(listOf("v" to "вода", "b" to "Буквы", "a" to "аист")).map { it.second })
        assertEquals(listOf("apple", "Banana", "zebra"), PackOrder.sort(listOf("z" to "zebra", "b" to "Banana", "a" to "apple")).map { it.second })
    }
    @Test fun sameNamesKeepDeterministicIdsWithoutDroppingPacks() {
        val names = listOf("3" to "test", "2" to "TEST", "1" to " test ")
        assertEquals(listOf("1", "2", "3"), PackOrder.sort(names).map { it.first })
        assertEquals(names.toSet(), PackOrder.sort(names).toSet())
    }
}
