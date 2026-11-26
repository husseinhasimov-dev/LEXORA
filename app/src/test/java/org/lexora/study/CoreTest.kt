package org.lexora.study

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import io.github.openspacedrepetition.State

class CoreTest {
    private val at=Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
    private fun sample()=Word("1","Straße","street","A1","noun","die","Straßen","Travel",1,"","","","","","","","","","")
    @Test fun firstRatingsMatchReferenceSteps(){
        val expected=listOf(60L,330L,600L)
        for(i in 1..3){val card=Memory.review("w",null,i,at,90)
            assertEquals(at+expected[i-1]*1000,card.due.toEpochMilli());assertEquals(State.LEARNING,card.state)}
        val easy=Memory.review("w",null,4,at,90)
        assertEquals(16.1507,easy.stability,0.0001)
        assertEquals(State.REVIEW,easy.state)
        assertEquals(16L,Duration.between(easy.lastReview,easy.due).toDays())
    }
    @Test fun graduationLapseAndRetention(){
        val first=Memory.review("w",null,3,at,90)
        val grad=Memory.review("w",first,3,first.due.toEpochMilli(),90)
        assertEquals(State.REVIEW,grad.state);assertTrue(grad.stability>0)
        val again=Memory.review("w",grad,1,grad.due.toEpochMilli(),90)
        assertEquals(State.RELEARNING,again.state)
        assertEquals(600,Duration.between(again.lastReview,again.due).seconds)
        val easy=Memory.review("w",null,4,at,95)
        assertTrue(easy.due<Memory.review("w",null,4,at,85).due)
        assertEquals(1.0,Memory.scheduler(90).getCardRetrievability(first,Instant.ofEpochMilli(at)),1e-12)
    }
    @Test fun orthographyAndTolerance(){
        val w=sample()
        fun check(a:String,b:String=w.german)=Answers.check(a,b,w,true)
        assertEquals("Article",check("der Straße"));assertEquals("Article",check("Straße"))
        assertEquals("Capitalization",check("die straße"));assertEquals("Umlaut or ß",check("die Strasse"))
        assertEquals("Umlaut or ß",check("grun","grün"));assertEquals("Umlaut or ß",check("gruen","grün"))
        assertEquals("Correct",check("gru\u0308n","grün"));assertEquals("Correct",check(" die  Straße "))
        assertEquals("Minor spelling error",check("Lernan","Lernen"))
        assertEquals("Spelling",Answers.check("Lernan","Lernen",w,false))
    }
    @Test fun quietHoursAndDateBoundariesRetainOriginalWeekday(){
        val friday=ZonedDateTime.parse("2026-11-27T20:00:00+01:00[Europe/Berlin]")
        val fridayOnly=ReminderSchedule("custom","custom",true,23,15,"5","Custom","",end="2026-11-27")
        val due=nextReminder(fridayOnly,friday,QuietHours(1380,420))!!
        assertEquals("2026-11-27",due.date.toString())
        assertEquals("2026-11-28T07:00+01:00[Europe/Berlin]",due.at.toString())
        assertNull(nextReminder(fridayOnly,friday,QuietHours(1380,420),due.date.toString()))
        assertNull(nextReminder(fridayOnly,friday.plusDays(2)))
    }
    @Test fun oneTimeRemindersAndDaylightSavingUseFutureLocalTime(){
        val before=ZonedDateTime.parse("2026-03-28T20:00:00+01:00[Europe/Berlin]")
        val one=ReminderSchedule("custom","custom",true,2,30,"","One time","",once=true,start="2026-03-29")
        assertEquals("2026-03-29T03:30+02:00[Europe/Berlin]",nextReminder(one,before)!!.at.toString())
        val newZone=before.withZoneSameInstant(ZoneId.of("Asia/Baku"))
        assertEquals(2,nextReminder(one,newZone)!!.at.hour)
        assertNotEquals(nextReminder(one,before)!!.at.toInstant(),nextReminder(one,newZone)!!.at.toInstant())
        assertNull(nextReminder(one,before.plusDays(2)))
    }
    @Test fun calendarReminder(){
        val now=ZonedDateTime.parse("2025-03-28T22:00:00+01:00[Europe/Berlin]")
        val next=Reminder.next(now,9,15,"Weekdays")
        assertEquals(DayOfWeek.MONDAY,next.dayOfWeek);assertEquals(9,next.hour)
        assertEquals(ZoneOffset.ofHours(2),next.offset)
    }
    @Test fun initialStabilityFittingNeedsEvidenceAndImprovesValidation(){
        val defaults=Calibration.fit(emptyList())
        assertArrayEquals(defaults,Calibration.fit(List(12){FirstRecall(3,5,false)}),0.0)
        val learned=Calibration.fit(List(100){FirstRecall(3,5,it%4==0)})
        assertTrue(learned[2]<defaults[2]);assertTrue(learned[2]>=learned[1])
        val card=Memory.review("w",null,3,at,90,"initial="+learned[2])
        assertEquals(learned[2],card.stability,1e-10)
    }
}
