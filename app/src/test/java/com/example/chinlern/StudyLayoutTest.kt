package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class StudyLayoutTest {
    @Test fun quizAndWritingUseCompactControlsInNarrowOrShortWindows() {
        assertFalse(StudyLayout.expanded(320, 480, 320))
        assertFalse(StudyLayout.expanded(800, 360, 360))
        assertFalse(StudyLayout.expanded(540, 900, 540))
        assertFalse(StudyLayout.expanded(840, 420, 600))
        assertTrue(StudyLayout.expanded(600, 960, 600))
        assertTrue(StudyLayout.expanded(1280, 800, 800))
    }

    @Test fun fiveToneButtonsShareARowOnlyWhenThereIsRoomForTheText() {
        assertEquals(3, StudyLayout.toneColumns(284, 1f))
        assertEquals(5, StudyLayout.toneColumns(700, 1f))
        assertEquals(3, StudyLayout.toneColumns(700, 1.5f))
    }
}
