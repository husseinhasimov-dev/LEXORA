package org.lexora.study

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderTest{
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val manager=ctx.getSystemService(NotificationManager::class.java)

    private fun grant(){
        if(Build.VERSION.SDK_INT>=33)inst.uiAutomation.grantRuntimePermission(ctx.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun schedule()=ReminderSchedule(UUID.randomUUID().toString(),"custom",true,8,15,
        "1,2,3,4,5,6,7","Vocabulary seminar","Review the examples.",name="Seminar",sound=false,vibration=true)

    @Test fun independentSettingsPersistAndEditingCancelsOldTrigger(){
        val owner=UUID.randomUUID().toString()
        val first=schedule()
        val second=schedule()
        val initial=StudyRepository(ctx,owner).use{repo->
            repo.saveReminder(ctx,first);repo.saveReminder(ctx,second)
            val a=repo.plannedReminder(first.id)!!
            assertTrue(a.at>System.currentTimeMillis())
            assertNotEquals(a.token,repo.plannedReminder(second.id)!!.token)
            a
        }
        StudyRepository(ctx,owner).use{repo->
            try{
                assertEquals(first,repo.reminderSchedule(first.id))
                assertEquals(initial,repo.plannedReminder(first.id))
                val other=repo.plannedReminder(second.id)
                val changed=first.copy(hour=9,minute=27)
                repo.saveReminder(ctx,changed)
                assertNotEquals(initial.token,repo.plannedReminder(first.id)!!.token)
                assertEquals(other,repo.plannedReminder(second.id))
                Reminder.plan(ctx,repo,reason="BOOT_COMPLETED",forceRegister=true)
                Reminder.plan(ctx,repo,reason="MY_PACKAGE_REPLACED",forceRegister=true)
                assertEquals(other,repo.plannedReminder(second.id))
                repo.saveReminder(ctx,changed.copy(enabled=false))
                assertNull(repo.plannedReminder(first.id))
                assertNotNull(repo.plannedReminder(second.id))
                repo.deleteReminder(ctx,second.id)
                assertNull(repo.reminderSchedule(second.id))
                assertNull(repo.plannedReminder(second.id))
            }finally{repo.deleteReminder(ctx,first.id);repo.deleteReminder(ctx,second.id)}
        }
    }

    @Test fun olderReminderJsonIsScheduledOnUpdate(){
        val id=UUID.randomUUID().toString()
        val raw="""{"category":"daily","enabled":true,"hour":19,"minute":0,"days":"1,2,3,4,5,6,7","title":"Old reminder","message":"Private text"}"""
        StudyRepository(ctx,UUID.randomUUID().toString()).use{repo->
            try{
                repo.set("notify."+id,raw)
                Reminder.plan(ctx,repo,reason="MY_PACKAGE_REPLACED",forceRegister=true)
                val old=repo.reminderSchedule(id)!!
                assertEquals("Old reminder",old.name)
                assertEquals("Private text",old.message)
                assertTrue(old.sound)
                assertNotNull(repo.plannedReminder(id))
                assertEquals(raw,repo.pref("notify."+id))
                assertTrue(ReminderLog.entries(ctx).filter{it.contains(id)}.none{it.contains("Private text")})
            }finally{repo.deleteReminder(ctx,id)}
        }
    }

    @Test fun receiverPostsOnceAndRejectsRepeatedCallback(){
        grant()
        val s=schedule()
        StudyRepository(ctx,UUID.randomUUID().toString()).use{repo->
            try{
                repo.saveReminder(ctx,s)
                val alarm=repo.plannedReminder(s.id)!!
                val time=Instant.ofEpochMilli(alarm.at+1000).atZone(ZoneId.systemDefault())
                Reminder.receive(ctx,repo,s.id,alarm.token,time)
                val notification=manager.activeNotifications.single{it.tag==s.id}.notification
                assertEquals(s.title,notification.extras.getString(Notification.EXTRA_TITLE))
                assertEquals(s.message,notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(0,notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
                val channel=manager.getNotificationChannel(Reminder.channel(s))
                assertNull(channel.sound)
                assertTrue(channel.shouldVibrate())
                assertEquals(alarm.date,repo.pref("considered."+s.id))
                val posted=ReminderLog.entries(ctx).count{it.contains(s.id+" | Post submitted")}
                Reminder.receive(ctx,repo,s.id,alarm.token,time)
                assertEquals(posted,ReminderLog.entries(ctx).count{it.contains(s.id+" | Post submitted")})
                assertNotEquals(alarm.token,repo.plannedReminder(s.id)?.token)
            }finally{repo.deleteReminder(ctx,s.id)}
        }
    }

    @Test fun immediateAndDelayedTestsCheckPermissionAndUseRealScheduler(){
        grant()
        manager.cancel("immediate-test",0)
        manager.cancel(Reminder.TEST,0)
        StudyRepository(ctx,UUID.randomUUID().toString()).use{repo->
            assertTrue(Reminder.postTest(ctx))
            assertTrue(manager.activeNotifications.any{it.tag=="immediate-test"})
            val before=System.currentTimeMillis()
            assertTrue(Reminder.scheduleTest(ctx,repo))
            val plan=repo.plannedReminder(Reminder.TEST)!!
            assertTrue(plan.at-before in 120_000..121_000)
            assertFalse(manager.activeNotifications.any{it.tag==Reminder.TEST})
            Reminder.receive(ctx,repo,Reminder.TEST,plan.token,Instant.ofEpochMilli(plan.at).atZone(ZoneId.systemDefault()))
            assertTrue(manager.activeNotifications.any{it.tag==Reminder.TEST})
            assertNull(repo.plannedReminder(Reminder.TEST))
        }
        manager.cancel("immediate-test",0)
        manager.cancel(Reminder.TEST,0)
    }

    @Test fun learningConditionsUseRealGoalsAndDueCards(){
        StudyRepository(ctx,UUID.randomUUID().toString()).use{repo->
            repo.setDailyGoal(1)
            assertTrue(repo.reminderNeeded("daily",repo.stats()))
            assertFalse(repo.reminderNeeded("due",repo.stats()))
            val word=repo.words(Filters()).first()
            repo.event(word.id,"new",3)
            val earlier=repo.now()-30L*24*60*60*1000
            repo.db.saveCard(word.id,Memory.review(word.id,null,3,earlier,90))
            assertFalse(repo.reminderNeeded("daily",repo.stats()))
            assertTrue(repo.reminderNeeded("due",repo.stats()))
            repo.set("reviewLimit","1")
            repo.event(word.id,"review",4,"scheduled")
            assertFalse(repo.reminderNeeded("due",repo.stats()))
        }
    }

    @Test fun previewHonorsCurrentPermission(){
        manager.cancel("preview",0)
        val granted=Reminder.permission(ctx)
        val expectation=InstrumentationRegistry.getArguments().getString("expectPermission")
        if(expectation!=null)assertEquals(expectation=="granted",granted)
        val s=schedule()
        val posted=Reminder.post(ctx,s,"Preview wording",true)
        assertEquals(granted && manager.areNotificationsEnabled(),posted)
        if(!granted){
            assertFalse(manager.activeNotifications.any{it.tag=="preview"})
            assertTrue(ReminderLog.last(ctx,"error")!!.contains("Notification permission denied"))
        }
        manager.cancel("preview",0)
    }
}
