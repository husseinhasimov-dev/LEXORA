package org.lexora.study

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.*
import java.util.UUID

data class ReminderSchedule(
    val id:String, val category:String, val enabled:Boolean, val hour:Int, val minute:Int,
    val days:String, val title:String, val message:String, val name:String=title,
    val once:Boolean=false, val start:String="", val end:String="",
    val sound:Boolean=true, val vibration:Boolean=false
){
    fun json()=JSONObject().put("category",category).put("enabled",enabled).put("hour",hour).put("minute",minute)
        .put("days",days).put("title",title).put("message",message).put("name",name).put("once",once)
        .put("start",start).put("end",end).put("sound",sound).put("vibration",vibration).toString()

    fun validate(){
        require(id in Reminder.names || runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false))
        require(category in Reminder.names && hour in 0..23 && minute in 0..59)
        require((once || days.isNotEmpty()) && (days.isEmpty() || days.split(",").all{it.toIntOrNull() in 1..7}))
        require(name.isNotBlank() && name.length<=100 && title.isNotBlank() && title.length<=100 && message.length<=300)
        require(start.isEmpty() || LocalDate.parse(start).toString()==start)
        require(end.isEmpty() || LocalDate.parse(end).toString()==end)
        require(!once || start.isNotEmpty())
        require(end.isEmpty() || start.isEmpty() || end>=start)
    }

    companion object{
        fun parse(id:String,raw:String):ReminderSchedule{
            val j=JSONObject(raw)
            return ReminderSchedule(id,j.getString("category"),j.getBoolean("enabled"),j.getInt("hour"),j.getInt("minute"),
                j.getString("days"),j.getString("title"),j.getString("message"),j.optString("name",j.getString("title")),
                j.optBoolean("once"),j.optString("start"),j.optString("end"),j.optBoolean("sound",true),
                j.optBoolean("vibration")).also{it.validate()}
        }
    }
}

fun StudyRepository.reminderSchedule(id:String):ReminderSchedule?{
    val raw=pref("notify."+id)
    if(raw=="deleted")return null
    if(raw.isNotEmpty())return ReminderSchedule.parse(id,raw)
    if(id !in Reminder.names || id=="goalReminder" && pref(id)!="true")return null
    fun setting(key:String)=pref("$id.$key").ifEmpty{pref(key)}
    val days=(1..7).filter{Reminder.allowedDay(setting("days"),DayOfWeek.of(it))}.joinToString(",")
    val title=Reminder.names.getValue(id)
    return ReminderSchedule(id,id,pref(id)=="true",setting("hour").toInt(),setting("minute").toInt(),days,title,"")
}
fun StudyRepository.reminderSchedules()=(Reminder.names.keys+db.rows("SELECT substr(k,8) FROM pref WHERE k LIKE 'notify.%'"){it.getString(0)})
    .mapNotNull(::reminderSchedule)

fun StudyRepository.saveReminder(context:Context,s:ReminderSchedule){
    s.validate()
    val old=reminderSchedule(s.id)
    db.tx{
        set("notify."+s.id,s.json())
        if(s.once && old?.let{!it.once || it.start!=s.start || it.hour!=s.hour || it.minute!=s.minute}==true){
            set("finished."+s.id,"")
            set("considered."+s.id,"")
        }
    }
    ReminderLog.record(context,s.id,if(old==null)"Created" else "Edited")
    Reminder.plan(context,this,only=s.id)
}

fun StudyRepository.deleteReminder(context:Context,id:String){
    set("notify."+id,"deleted")
    Reminder.plan(context,this,only=id)
    context.getSystemService(NotificationManager::class.java).apply{
        cancel(id,0)
        cancel(id.hashCode()) // V5 posted without a tag.
    }
    ReminderLog.record(context,id,"Deleted")
}

data class QuietHours(val start:Int=0,val end:Int=0){
    init{require(start in 0..1439 && end in 0..1439)}
    fun resumeAt(time:ZonedDateTime):ZonedDateTime?{
        val minute=time.hour*60+time.minute
        val crosses=start>end
        if(start==end || !(if(crosses)minute>=start || minute<end else minute in start until end))return null
        val date=if(crosses && minute>=start)time.toLocalDate().plusDays(1) else time.toLocalDate()
        val local=date.atTime(end/60,end%60)
        val earlier=local.atZone(time.zone)
        return if(earlier.isAfter(time))earlier else earlier.withLaterOffsetAtOverlap().takeIf{it.isAfter(time)}
    }
}
fun StudyRepository.quietHours()=QuietHours(pref("quietStart").toIntOrNull()?:0,pref("quietEnd").toIntOrNull()?:0)
data class ReminderTime(val at:ZonedDateTime,val date:LocalDate)

fun nextReminder(s:ReminderSchedule,now:ZonedDateTime,quiet:QuietHours=QuietHours(),last:String=""):ReminderTime?{
    val today=now.toLocalDate()
    val start=s.start.takeIf{it.isNotEmpty()}?.let(LocalDate::parse)
    val end=s.end.takeIf{it.isNotEmpty()}?.let(LocalDate::parse)
    val first=maxOf(today.minusDays(1),start?:today.minusDays(1))
    for(offset in 0L..8){
        val date=first.plusDays(offset)
        if(s.once && date!=start || end!=null && date>end)return null
        if(last.isNotEmpty() && date.toString()<=last || !s.once && !Reminder.allowedDay(s.days,date.dayOfWeek))continue
        val local=date.atTime(s.hour,s.minute)
        val original=local.atZone(now.zone)
        val due=quiet.resumeAt(original)?:original
        if(due.isAfter(now))return ReminderTime(due,date)
    }
    return null
}

data class PlannedReminder(val token:String,val at:Long,val date:String,val settings:String){
    fun json()=JSONObject().put("token",token).put("at",at).put("date",date).put("settings",settings).toString()
    companion object{
        fun read(raw:String):PlannedReminder?=raw.takeIf{it.isNotEmpty()}?.let{
            val j=JSONObject(it);PlannedReminder(j.getString("token"),j.getLong("at"),j.getString("date"),j.getString("settings"))
        }
    }
}
fun StudyRepository.plannedReminder(id:String)=PlannedReminder.read(pref("alarm."+id))
fun StudyRepository.nextReminders()=db.rows("SELECT substr(k,7),v FROM pref WHERE k LIKE 'alarm.%' AND v<>''"){
    it.getString(0) to requireNotNull(PlannedReminder.read(it.getString(1))).at
}.toMap()

// A job persisted by V5 can already be running during an upgrade. It reconciles V5.1 alarms and never posts.
class Reminder(ctx:Context,params:WorkerParameters):Worker(ctx,params){
    override fun doWork():Result {
        StudyRepository(applicationContext).use{plan(applicationContext,it)}
        return Result.success()
    }
    companion object{
        val names=linkedMapOf("daily" to "Daily Vocabulary Learning","due" to "FSRS Due Reviews",
            "weekly" to "Weekly Learning Goals","streakReminder" to "Streak Reminder",
            "custom" to "Custom Reminder","goalReminder" to "Daily Vocabulary Goal (legacy)")
        val keys=names.keys+setOf("hour","minute","days","notificationsMaster","quietStart","quietEnd")
        const val TEST="delayed-test"
        private const val GRACE=6*60*60*1000L

        fun allowedDay(days:String,day:DayOfWeek)=when(days){
            "Weekdays"->day.value<=5
            "Weekends"->day.value>=6
            "Every day",""->true
            else->day.value.toString() in days.split(",")
        }

        fun next(now:ZonedDateTime,hour:Int,minute:Int,days:String):ZonedDateTime{
            require(DayOfWeek.values().any{allowedDay(days,it)})
            val s=ReminderSchedule("daily","daily",true,hour,minute,days,"Study","")
            return requireNotNull(nextReminder(s,now)).at
        }

        private fun intent(context:Context,owner:String?,id:String)=Intent(context,ReminderAlarm::class.java)
            .setAction("org.lexora.study.REMINDER").setData(Uri.Builder().scheme("lexora").authority("reminder")
                .appendPath(UUID.nameUUIDFromBytes(owner.orEmpty().toByteArray()).toString()).appendPath(id).build())

        private fun cancel(context:Context,repo:StudyRepository,id:String){
            val pending=PendingIntent.getBroadcast(context,0,intent(context,repo.owner,id),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if(pending!=null){
                context.getSystemService(AlarmManager::class.java).cancel(pending)
                pending.cancel()
            }
            if(repo.plannedReminder(id)!=null){
                repo.set("alarm."+id,"")
                ReminderLog.record(context,id,"Cancelled")
            }
        }

        private fun schedule(context:Context,repo:StudyRepository,id:String,plan:PlannedReminder,reason:String):Boolean{
            ReminderLog.record(context,id,"Schedule requested",reason)
            return try {
                val request=intent(context,repo.owner,id).putExtra("token",plan.token).putExtra("owner",repo.owner.orEmpty())
                val pending=PendingIntent.getBroadcast(context,0,request,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                repo.set("alarm."+id,plan.json())
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,maxOf(plan.at,System.currentTimeMillis()+250),pending)
                ReminderLog.record(context,id,"Schedule accepted",Instant.ofEpochMilli(plan.at).atZone(ZoneId.systemDefault()).toString())
                true
            }catch(e:RuntimeException){
                cancel(context,repo,id)
                ReminderLog.record(context,id,"Schedule error",e.javaClass.simpleName)
                false
            }
        }

        fun plan(context:Context,repo:StudyRepository,only:String?=null,reason:String="Opened app",clockChanged:Boolean=false,forceRegister:Boolean=false){
            val now=ZonedDateTime.now()
            val millis=now.toInstant().toEpochMilli()
            val prefs=context.getSharedPreferences("reminder-migration",Context.MODE_PRIVATE)
            if(!prefs.getBoolean("v51",false)){
                WorkManager.getInstance(context).cancelAllWorkByTag(Reminder::class.java.name)
                prefs.edit().putBoolean("v51",true).apply()
            }
            repo.db.tx{
                val quiet=repo.quietHours()
                val ids=only?.let(::listOf)?:repo.reminderSchedules().map{it.id}
                for(id in ids){
                    val s=repo.reminderSchedule(id)
                    if(s==null || !s.enabled || repo.pref("notificationsMaster")!="true" || s.once && repo.pref("finished."+id)=="true"){
                        cancel(context,repo,id);continue
                    }
                    val key=s.json()+"|"+quiet+"|"+now.zone.id
                    val old=repo.plannedReminder(id)
                    if(!clockChanged && old?.settings==key && millis-old.at<=GRACE){
                        val exists=PendingIntent.getBroadcast(context,0,intent(context,repo.owner,id),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                        if(forceRegister || exists==null)schedule(context,repo,id,old,reason)
                        continue
                    }
                    val due=nextReminder(s,now,quiet,repo.pref("considered."+id))
                    if(due==null){cancel(context,repo,id);continue}
                    schedule(context,repo,id,PlannedReminder(UUID.randomUUID().toString(),due.at.toInstant().toEpochMilli(),due.date.toString(),key),reason)
                }
                if(only==null){
                    val test=repo.plannedReminder(TEST)
                    if(test!=null && millis-test.at>GRACE)cancel(context,repo,TEST)
                    else if(test!=null && (forceRegister || PendingIntent.getBroadcast(context,0,intent(context,repo.owner,TEST),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)==null)){
                        schedule(context,repo,TEST,test,reason)
                    }
                }
            }
        }

        fun scheduleTest(context:Context,repo:StudyRepository):Boolean=repo.db.tx {
            val time=System.currentTimeMillis()+120_000
            schedule(context,repo,TEST,PlannedReminder(UUID.randomUUID().toString(),time,"","test"),"Two-minute test")
        }

        fun receive(context:Context,repo:StudyRepository,id:String,token:String,now:ZonedDateTime=ZonedDateTime.now()){
            ReminderLog.record(context,id,"Receiver executed")
            repo.db.tx{
                val saved=repo.plannedReminder(id)
                if(saved==null || saved.token!=token)return@tx
                val millis=now.toInstant().toEpochMilli()
                if(millis<saved.at){
                    schedule(context,repo,id,saved,"Early callback")
                    return@tx
                }
                if(id==TEST){
                    cancel(context,repo,id)
                    if(millis-saved.at<=GRACE)postTest(context,true)
                    else ReminderLog.record(context,id,"Skipped","Test expired")
                    return@tx
                }
                val s=repo.reminderSchedule(id)
                if(s==null || !s.enabled || repo.pref("notificationsMaster")!="true"){
                    cancel(context,repo,id);return@tx
                }
                val quiet=repo.quietHours()
                if(saved.settings!=s.json()+"|"+quiet+"|"+now.zone.id){
                    plan(context,repo,only=id,reason="Settings changed",clockChanged=true)
                    return@tx
                }
                val deferred=quiet.resumeAt(now)
                if(deferred!=null && millis-saved.at<=GRACE){
                    schedule(context,repo,id,saved.copy(at=deferred.toInstant().toEpochMilli(),token=UUID.randomUUID().toString()),"Quiet hours")
                    return@tx
                }
                cancel(context,repo,id)
                val considered=repo.pref("considered."+id)
                if(considered.isEmpty() || saved.date>considered){
                    repo.set("considered."+id,saved.date)
                    if(s.once)repo.set("finished."+id,"true")
                    val day=now.toLocalDate().toString()
                    val category=if(s.category=="goalReminder")"daily" else s.category
                    val throttle="last-category."+category
                    val duplicate=category!="custom" && repo.pref(throttle)==day
                    val stats=repo.stats()
                    when{
                        millis-saved.at>GRACE->ReminderLog.record(context,id,"Skipped","Delivery window expired")
                        duplicate->ReminderLog.record(context,id,"Skipped","Learning reminder already posted today")
                        !repo.reminderNeeded(s.category,stats)->ReminderLog.record(context,id,"Skipped","No eligible study work")
                        post(context,s,repo.reminderText(s,stats),false)->if(category!="custom")repo.set(throttle,day)
                    }
                }
                plan(context,repo,only=id,reason="After execution")
            }
        }

        fun previewText(s:ReminderSchedule,state:ScreenState)=s.message.ifBlank{when(s.category){
            "daily","goalReminder"->"${state.goalCounts.today} of ${state.prefs["goal"]} words recalled today."
            "due"->"${minOf(state.stats.due,((state.prefs["reviewLimit"]?.toIntOrNull()?:100)-state.stats.todayReview).coerceAtLeast(0))} words are ready for your scheduled reviews."
            "weekly"->"This week: ${state.weekly.learningDays} daily goals met and ${state.weekly.scheduled} of ${state.prefs["weeklyReviewGoal"]} scheduled reviews completed."
            "streakReminder"->"A short study session can count toward today’s activity."
            else->"It’s time for your vocabulary reminder."
        }}

        fun channel(s:ReminderSchedule)=when{
            s.sound && !s.vibration->s.category
            else->s.category+(if(s.sound)".sound" else ".silent")+(if(s.vibration)".vibrate" else "")
        }

        private fun makeChannel(context:Context,s:ReminderSchedule):NotificationChannel{
            val id=channel(s)
            val label=names.getValue(s.category)+(if(!s.sound) " · Silent" else "")+(if(s.vibration) " · Vibration" else "")
            val manager=context.getSystemService(NotificationManager::class.java)
            val configured=NotificationChannel(id,label,if(s.sound || s.vibration)NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW)
            if(s.sound)configured.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            else configured.setSound(null,null)
            configured.enableVibration(s.vibration)
            manager.createNotificationChannel(configured)
            return manager.getNotificationChannel(id)
        }

        fun permission(context:Context)=Build.VERSION.SDK_INT<33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED

        fun post(context:Context,s:ReminderSchedule,message:String,preview:Boolean):Boolean=
            publish(context,s,message,if(preview)"preview" else s.id,if(preview)"Preview · "+s.title else s.title)

        fun postTest(context:Context,delayed:Boolean=false):Boolean{
            val id=if(delayed)TEST else "immediate-test"
            return publish(context,testSchedule,"LEXORA submitted this notification to Android.",id,
                if(delayed)"LEXORA delayed notification test" else "LEXORA notification test")
        }

        val testSchedule=ReminderSchedule("custom","custom",true,0,0,"1","Test","")
        fun channelEnabled(context:Context,s:ReminderSchedule)=makeChannel(context,s).importance!=NotificationManager.IMPORTANCE_NONE

        private fun publish(context:Context,s:ReminderSchedule,message:String,id:String,title:String):Boolean{
            ReminderLog.record(context,id,"Post attempted")
            return try{
                val manager=context.getSystemService(NotificationManager::class.java)
                val channel=makeChannel(context,s)
                val reason=when{
                    !permission(context)->"Notification permission denied"
                    !manager.areNotificationsEnabled()->"App notifications blocked"
                    channel.importance==NotificationManager.IMPORTANCE_NONE->"Notification channel blocked"
                    else->null
                }
                if(reason!=null){
                    ReminderLog.record(context,id,"Post error",reason);return false
                }
                val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.notify(id,0,NotificationCompat.Builder(context,channel.id)
                    .setSmallIcon(R.drawable.ic_lexora).setContentTitle(title).setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(open)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
                ReminderLog.record(context,id,"Post submitted","Android API returned without an error")
                true
            }catch(e:RuntimeException){
                ReminderLog.record(context,id,"Post error",e.javaClass.simpleName);false
            }
        }
    }
}

fun StudyRepository.reminderNeeded(category:String,stats:Stats):Boolean=when(category){
    "daily","goalReminder"->learnedToday()<pref("goal").toInt()
    "due"->stats.due>0 && today("review")<pref("reviewLimit").toInt()
    "weekly"->weeklyGoals().let { it.learningDays<LocalDate.parse(day()).dayOfWeek.value || it.scheduled<pref("weeklyReviewGoal").toInt() }
    "streakReminder"->stats.streak>0 && stats.todayNew+stats.todayReview==0
    else->true
}
fun StudyRepository.reminderText(s:ReminderSchedule,stats:Stats)=s.message.ifBlank{when(s.category){
    "due"->"${minOf(stats.due,(pref("reviewLimit").toInt()-today("review")).coerceAtLeast(0))} words are ready for your scheduled reviews."
    "daily","goalReminder"->"${learnedToday()} of ${pref("goal")} words recalled today."
    "weekly"->weeklyGoals().let{"This week: ${it.learningDays} daily goals met and ${it.scheduled} of ${pref("weeklyReviewGoal")} scheduled reviews completed."}
    "streakReminder"->"A short study session can count toward today’s activity."
    else->"It’s time for your vocabulary reminder."
}}

object ReminderLog{
    private fun prefs(context:Context)=context.getSharedPreferences("reminder-diagnostics",Context.MODE_PRIVATE)
    @Synchronized fun record(context:Context,id:String,event:String,detail:String=""){
        val saved=prefs(context)
        val line=listOf(ZonedDateTime.now().toString(),id,event,detail).filter(String::isNotEmpty).joinToString(" | ")
        val all=(saved.getString("entries","").orEmpty().lineSequence().filter(String::isNotEmpty)+sequenceOf(line)).toList().takeLast(100)
        val edit=saved.edit().putString("entries",all.joinToString("\n")).putString("last."+event,line)
        if(event.endsWith("error"))edit.putString("last.error",line)
        edit.apply()
    }
    fun entries(context:Context)=prefs(context).getString("entries","").orEmpty().lines().filter(String::isNotEmpty)
    fun last(context:Context,event:String)=prefs(context).getString("last."+event,null)
    fun battery(context:Context)=listOf(
        "Background restriction" to if(Build.VERSION.SDK_INT>=28)context.getSystemService(ActivityManager::class.java).isBackgroundRestricted.toString() else "Unavailable",
        "Battery saver" to context.getSystemService(PowerManager::class.java).isPowerSaveMode.toString(),
        "Battery optimization exemption" to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName).toString())
}

class ReminderAlarm:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val id=intent.data?.lastPathSegment?:return
        val token=intent.getStringExtra("token")?:return
        if(intent.action!="org.lexora.study.REMINDER")return
        background(context) { StudyRepository(context).use { repo->
            if(repo.owner.orEmpty()==intent.getStringExtra("owner"))Reminder.receive(context,repo,id,token)
            else ReminderLog.record(context,id,"Skipped","Reminder belongs to another profile")
        } }
    }
}

class ClockChanged:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val action=intent.action?:return
        if(action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED))return
        background(context) { StudyRepository(context).use {
            Reminder.plan(context,it,reason=action.substringAfterLast('.'),
                clockChanged=action==Intent.ACTION_TIME_CHANGED || action==Intent.ACTION_TIMEZONE_CHANGED,forceRegister=true)
        } }
    }
}

private fun BroadcastReceiver.background(context:Context,work:()->Unit){
    val pending=goAsync()
    Thread { try{work()}catch(e:RuntimeException){ReminderLog.record(context,"system","Receiver error",e.javaClass.simpleName)}finally{pending.finish()} }.start()
}
