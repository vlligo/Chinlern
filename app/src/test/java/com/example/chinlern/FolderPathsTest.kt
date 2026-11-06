package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class FolderPathsTest {
    @Test fun inferredAncestorsAndBoundaryMatching() {
        assertEquals(listOf("A", "A/B", "A/B/C", "AB"), FolderPaths.all(listOf("A/B/C", "AB")))
        assertTrue(FolderPaths.contains("A", "A/B/C"))
        assertFalse(FolderPaths.contains("A", "AB"))
        assertFalse(FolderPaths.contains("", "A"))
        assertEquals(listOf("A", "A/B"), FolderPaths.ancestors("A/B/C"))
        assertEquals("Z/B/C", FolderPaths.replace("A/B/C", "A", "Z"))
    }
    @Test fun pathAndNameLimitsRejectMalformedHierarchy() {
        for (path in listOf("/A", "A/", "A//B", "A/ B", "A/" + "x".repeat(81), (1..9).joinToString("/") { "A" })) {
            try { FolderPaths.validate(path); fail(path) } catch (_: IllegalArgumentException) { }
        }
        FolderPaths.validate((1..8).joinToString("/") { "x".repeat(80) })
        FolderPaths.validate("")
        try { FolderPaths.child("A", "B/C"); fail() } catch (_: IllegalArgumentException) { }
    }
}
