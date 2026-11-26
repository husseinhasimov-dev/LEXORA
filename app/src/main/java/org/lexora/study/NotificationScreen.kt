package org.lexora.study

import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

private val dayNames=listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")

@Composable fun NotificationScreen(state:ScreenState,vm:StudyModel,close:()->Unit){
    val ctx=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var panel by rememberSaveable{mutableStateOf("")}
    var editing by rememberSaveable{mutableStateOf("")}
    var deleting by rememberSaveable{mutableStateOf("")}
    var preview by rememberSaveable{mutableStateOf("")}
    var requestFor by rememberSaveable{mutableStateOf("")}
    var refresh by remember{mutableIntStateOf(0)}
    val manager=ctx.getSystemService(NotificationManager::class.java)

    fun test(delayed:Boolean){
        vm.run{
            if(delayed){
                if(Reminder.scheduleTest(ctx,this))"Scheduled for two minutes from now. You can lock the phone. Android may deliver it later."
                else "Android did not accept the test schedule. Open Notification Health Check for details."
            }else if(Reminder.postTest(ctx))"Submitted to Android. Check your notification shade to see whether it appeared."
            else "Test could not be posted. See Notification Health Check for the reason and settings shortcuts."
        }
    }

    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        refresh++
        if(granted){
            when(requestFor){"immediate"->test(false);"delayed"->test(true);else->vm.run{Reminder.plan(ctx,this,reason="Permission granted");null}}
        }else vm.run{ReminderLog.record(ctx,"permission","Post error","Permission denied in Android");"Notification permission is off. You can change it in Notification Health Check."}
        requestFor=""
    }

    fun enablePermission(action:String=""){
        if(Build.VERSION.SDK_INT>=33 && !Reminder.permission(ctx)){
            requestFor=action
            request.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }else if(action.isNotEmpty())test(action=="delayed")
    }

    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){refresh++;vm.run{Reminder.plan(ctx,this,reason="Returned to notifications");null}}}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    fun back(){when{editing.isNotEmpty()->editing="";panel.isNotEmpty()->panel="";else->close()}}
    BackHandler{back()}
    if(editing.isNotEmpty()){
        val s=state.schedules.find{it.id==editing}?:ReminderSchedule(editing,"custom",true,19,0,"1,2,3,4,5,6,7","Custom Reminder","")
        key(editing){ReminderEditor(s,{editing=""}){changed->
            vm.run{saveReminder(ctx,changed);null}
            editing=""
            if(changed.enabled)enablePermission()
        }}
        return
    }

    val log=remember(refresh,state){ReminderLog.entries(ctx).asReversed()}
    val permission=remember(refresh,state){Reminder.permission(ctx)}
    val next=state.nextReminders
    val master=state.prefs["notificationsMaster"]=="true"
    if(panel=="health"){
        val clipboard=LocalClipboardManager.current
        val time=next.minByOrNull{it.value}?.let{entry->
            val name=state.schedules.firstOrNull{it.id==entry.key}?.name?: "Two-minute test"
            name+" · "+displayTime(entry.value)
        }?:"None scheduled"
        val channelStates=(state.schedules+Reminder.testSchedule).distinctBy(Reminder::channel).map{s->
            val channel=manager.getNotificationChannel(Reminder.channel(s))
            val status=when(channel?.importance){null->"Not created yet";NotificationManager.IMPORTANCE_NONE->"Blocked";else->"Enabled"}
            (channel?.name?.toString()?:Reminder.channel(s)) to status
        }
        val rows=listOf("Android notification permission" to if(Build.VERSION.SDK_INT<33)"Not required" else if(permission)"Allowed" else "Denied",
            "App notifications" to if(manager.areNotificationsEnabled())"Enabled" else "Blocked",
            "Scheduling method" to "Inexact wake-up alarm",
            "Exact-alarm access" to "Not used by LEXORA",
            "Next expected reminder" to time)+ReminderLog.battery(ctx)+channelStates+
            listOf("Schedule requested","Schedule accepted","Receiver executed","Post attempted","Post submitted","error").map{
                (if(it=="error")"Last error" else it) to (ReminderLog.last(ctx,it)?:"No recorded event")
            }
        Page{
            TextButton({back()}){Text("Back to notifications")}
            Heading("Notification Health Check")
            Hint("Android can delay an alarm. A submitted post only confirms that Android accepted the request.")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton({refresh++;vm.run{Reminder.plan(ctx,this,reason="Health Check",forceRegister=true);null}}){Text("Refresh")}
                TextButton({
                    val report=(rows.map{it.first+" · "+it.second}+log).joinToString("\n")
                    clipboard.setText(AnnotatedString(report))
                }){Text("Copy report")}
            }
            Secondary("Android notification settings"){ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,ctx.packageName))}
            Secondary("App and battery settings"){ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+ctx.packageName)))}
            rows.forEach{(name,value)->Sheet{Label(name);Text(value)}}
            Label("Recent events")
            if(log.isEmpty())Hint("No recorded events yet.")
            log.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
        }
        return
    }

    val quiet=QuietHours(state.prefs["quietStart"]?.toIntOrNull()?:0,state.prefs["quietEnd"]?.toIntOrNull()?:0)
    Page{
        TextButton(close){Text("Back to profile")}
        Heading("Notifications")
        Sheet{
            SwitchRow("Allow study reminders",master){on->vm.preference("notificationsMaster",on.toString());if(on)enablePermission()}
            Hint(when{!master->"Study reminders are paused.";!permission || !manager.areNotificationsEnabled()->"Android is blocking notification posts.";else->"Study reminders are on."})
        }
        Sheet{
            Primary("Send Test Notification"){enablePermission("immediate")}
            Secondary("Schedule Test in 2 Minutes"){enablePermission("delayed")}
            if(Reminder.TEST in next)Hint("Delayed test expected · "+displayTime(next.getValue(Reminder.TEST)))
            Secondary("Notification Health Check"){panel="health"}
        }
        Sheet{
            SwitchRow("Quiet hours",quiet.start!=quiet.end){enabled->
                vm.run{set("quietStart",if(enabled)"1380" else "0");set("quietEnd",if(enabled)"420" else "0");Reminder.plan(ctx,this,reason="Quiet hours edited");null}
            }
            if(quiet.start!=quiet.end){
                LocalTimeButton("Starts",quiet.start/60,quiet.start%60){h,m->vm.preference("quietStart",(h*60+m).toString())}
                LocalTimeButton("Ends",quiet.end/60,quiet.end%60){h,m->vm.preference("quietEnd",(h*60+m).toString())}
                Hint("Study reminders wait until quiet hours end. Test notifications run on their own schedule.")
            }
        }
        Label("Schedules")
        Primary("Add reminder"){editing=UUID.randomUUID().toString()}
        state.schedules.forEach{s->
            Sheet{
                SwitchRow(s.name,s.enabled){on->vm.run{saveReminder(ctx,s.copy(enabled=on));null};if(on)enablePermission()}
                Hint(Reminder.names.getValue(s.category)+" · "+if(s.once)"One time" else s.days.split(",").mapNotNull{it.toIntOrNull()?.let{n->dayNames.getOrNull(n-1)}}.joinToString(" · "))
                Hint(next[s.id]?.let{"Next expected · "+displayTime(it)}?:if(!s.enabled || !master)"Paused" else "No future occurrence")
                Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                    TextButton({editing=s.id}){Text("Edit")}
                    TextButton({preview=s.id}){Text("Preview")}
                    TextButton({deleting=s.id}){Text("Delete")}
                }
            }
        }
        Hint("Times use your device’s local time zone. Android may delay delivery when the device is idle.")
    }

    state.schedules.find{it.id==preview}?.let{s->
        AlertDialog(onDismissRequest={preview=""},title={Text("Notification preview")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Label("LEXORA");Text(s.title);Text(Reminder.previewText(s,state))
                Hint("Android controls sound and vibration for this notification category.")}},
            dismissButton={TextButton({preview=""}){Text("Close")}},
            confirmButton={TextButton({
                preview=""
                vm.run{
                    if(Reminder.post(ctx,s,reminderText(s,stats()),true))null
                    else "Preview blocked. Check Android permissions and notification categories."
                }
            }){Text("Send preview")}})
    }
    if(deleting.isNotEmpty()){val id=deleting;Confirm("Delete this schedule?","Its future reminders will be canceled.",{deleting=""}){
        vm.run{deleteReminder(ctx,id);null}
    }}
}

@Composable private fun LocalTimeButton(label:String,h:Int,m:Int,save:(Int,Int)->Unit){
    val ctx=LocalContext.current
    val formatted=LocalTime.of(h,m).format(DateTimeFormatter.ofPattern(if(DateFormat.is24HourFormat(ctx))"HH:mm" else "h:mm a"))
    Secondary("$label · $formatted"){TimePickerDialog(ctx,{_,hh,mm->save(hh,mm)},h,m,DateFormat.is24HourFormat(ctx)).show()}
}
private fun displayTime(time:Long)=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT))

@Composable private fun DateButton(label:String,value:String,change:(String)->Unit){
    val ctx=LocalContext.current
    Secondary("$label · "+value.ifEmpty{"None"}){
        val date=value.takeIf(String::isNotEmpty)?.let(LocalDate::parse)?:LocalDate.now()
        DatePickerDialog(ctx,{_,y,m,d->change(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()
    }
}

@Composable private fun ReminderEditor(s:ReminderSchedule,close:()->Unit,save:(ReminderSchedule)->Unit){
    val ctx=LocalContext.current
    var cat by rememberSaveable{mutableStateOf(s.category)}
    var name by rememberSaveable{mutableStateOf(s.name)}
    var title by rememberSaveable{mutableStateOf(s.title)}
    var message by rememberSaveable{mutableStateOf(s.message)}
    var h by rememberSaveable{mutableIntStateOf(s.hour)}
    var m by rememberSaveable{mutableIntStateOf(s.minute)}
    var days by rememberSaveable{mutableStateOf(s.days)}
    var on by rememberSaveable{mutableStateOf(s.enabled)}
    var once by rememberSaveable{mutableStateOf(s.once)}
    var start by rememberSaveable{mutableStateOf(s.start)}
    var end by rememberSaveable{mutableStateOf(s.end)}
    var sound by rememberSaveable{mutableStateOf(s.sound)}
    var vibrate by rememberSaveable{mutableStateOf(s.vibration)}
    val candidate=s.copy(category=cat,enabled=on,hour=h,minute=m,days=days,title=title.trim(),message=message.trim(),
        name=name.trim(),once=once,start=start,end=if(once)"" else end,sound=sound,vibration=vibrate)
    Page{
        TextButton(close){Text("Cancel")}
        Heading("Reminder schedule")
        SwitchRow("Enabled",on){on=it}
        Pick("Category",Reminder.names.getValue(cat),Reminder.names.filterKeys{it!="goalReminder" || it==s.category}.values.toList()){label->
            if(name==Reminder.names[cat])name=label
            if(title==Reminder.names[cat])title=label
            cat=Reminder.names.entries.first{it.value==label}.key
        }
        when(cat){
            "daily","goalReminder"->Hint("Sends if today’s vocabulary target is unfinished.")
            "due"->Hint("Sends when words are eligible for scheduled review.")
            "weekly"->Hint("Sends if this week’s learning or scheduled review goals are unfinished.")
            "streakReminder"->Hint("Sends if you have a study streak and no recorded study yet today.")
        }
        OutlinedTextField(name,{name=it.take(100)},label={Text("Reminder name")},singleLine=true)
        OutlinedTextField(title,{title=it.take(100)},label={Text("Notification title")},singleLine=true)
        OutlinedTextField(message,{message=it.take(300)},label={Text("Custom message")})
        LocalTimeButton("Time",h,m){hh,mm->h=hh;m=mm}
        SwitchRow("One-time reminder",once){once=it;if(it && start.isEmpty())start=LocalDate.now().plusDays(1).toString()}
        DateButton(if(once)"Date" else "Start date",start){start=it}
        if(!once){
            DateButton("End date",end){end=it}
            if(start.isNotEmpty() || end.isNotEmpty())TextButton({start="";end=""}){Text("Clear date limits")}
            Label("Days of the week")
            for(group in (1..7).chunked(4))Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                group.forEach{n->val selected=n.toString() in days.split(",")
                    FilterChip(selected,{val set=days.split(",").filter(String::isNotEmpty).toMutableSet()
                        if(selected)set.remove(n.toString())else set.add(n.toString());days=set.sorted().joinToString(",")},{Text(dayNames[n-1])})
                }
            }
        }
        SwitchRow("Play sound",sound){sound=it}
        SwitchRow("Vibrate",vibrate){vibrate=it}
        TextButton({
            Reminder.channelEnabled(ctx,candidate)
            ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,Reminder.channel(candidate)))
        }){Text("Android category settings")}
        val valid=runCatching{candidate.validate()}.isSuccess
        if(valid && on && nextReminder(candidate,ZonedDateTime.now())==null)Hint("Choose a future date and time, or disable this schedule.")
        Primary("Save reminder",valid && (!on || nextReminder(candidate,ZonedDateTime.now())!=null)){save(candidate)}
    }
}
