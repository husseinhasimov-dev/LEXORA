package org.lexora.study

import org.junit.Assert.*
import org.junit.Test

class AcademyRulesTest {
    @Test fun rewardUsesAccuracyDifficultyAndNovelty(){
        assertEquals(0,AcademyXp.points(25,"A1",0.0))
        val normal=AcademyXp.points(25,"A2",1.0)
        assertTrue(AcademyXp.points(25,"B2",1.0)>normal)
        assertTrue(AcademyXp.points(25,"A2",0.5)<normal)
        assertTrue(AcademyXp.points(25,"A2",1.0,0.5)<normal)
        assertEquals(1L,AcademyXp.level(99));assertEquals(2L,AcademyXp.level(100))
    }
    @Test fun keyedResponsesRespectSpellingAndNormalizePunctuation(){
        val q=AcademyQuestion("Order","", "Ich hätte Zeit",emptyList(),"")
        assertTrue(q.accepts(" ich  hätte Zeit. "));assertFalse(q.accepts("Ich hatte Zeit"))
    }
}
