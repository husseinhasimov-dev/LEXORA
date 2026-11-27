package org.lexora.study

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.google.android.gms.auth.api.signin.*
import com.google.firebase.auth.*
import java.util.Locale

@Composable fun SwitchRow(label:String,on:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().toggleable(value=on,role=Role.Switch,onValueChange=change),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
        Text(label,Modifier.weight(1f));Switch(on,null)
    }
}
@Composable fun Profile(state:ScreenState,vm:StudyModel,placement:()->Unit){
    val p=state.prefs;val ctx=LocalContext.current
    var operation by rememberSaveable{mutableStateOf("")}
    var reset by rememberSaveable{mutableStateOf("")}
    var credit by rememberSaveable{mutableStateOf(false)}
    var notifications by rememberSaveable{mutableStateOf(false)}
    if(notifications){NotificationScreen(state,vm){notifications=false};return}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){it?.let { vm.transfer(it,operation) }}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let { vm.transfer(it,operation) }}
    Page {
        Label("Preferences");Heading("Your profile")
        Secondary("Manage notifications"){notifications=true}
        Sheet {
            Text("Learning goals",style=MaterialTheme.typography.titleLarge)
            Pick("Starting level",p.getValue("level"),levels){vm.preference("level",it)}
            Pick("New words daily",p.getValue("goal"),listOf("1","3","5","10","15","20","30","50")){vm.preference("goal",it)}
            Pick("Reviews daily",p.getValue("reviewLimit"),listOf("5","10","25","50","100","200","500")){vm.preference("reviewLimit",it)}
            Pick("Session size",p.getValue("size"),listOf("1","3","5","8","10","15","20","30")){vm.preference("size",it)}
            Pick("Desired retention (%)",p.getValue("retention"),listOf("75","80","85","90","92","95","97")){vm.preference("retention",it)}
            TextButton({vm.run{fitInitialMemory()}},enabled=!state.busy){Text("Fit initial stability from history")}
            SwitchRow("Written recall",p["writing"]=="true"){vm.preference("writing",it.toString())}
            SwitchRow("Context practice",p["context"]=="true"){vm.preference("context",it.toString())}
            SwitchRow("Explain minor typos",p["hints"]=="true"){vm.preference("hints",it.toString())}
            Pick("Speech speed",p.getValue("speed"),listOf("0.65","0.8","1.0","1.2")){vm.preference("speed",it)}
            Secondary("Take placement assessment",state.session==null,placement)
        }
        Account(vm)
        Sheet {
            Text("Local data",style=MaterialTheme.typography.titleLarge)
            Hint("Backups merge learning history, personal words, favorites and decks. CSV transfers personal vocabulary.")
            for(name in listOf("Backup","Restore","CSV export","CSV import","Anki export","Anki import"))Secondary(name,!state.busy){
                operation=name
                if(name=="Backup" || name.endsWith("export"))save.launch(when(name){"Backup"->"lexora-backup.json";"Anki export"->"lexora-words.txt";else->"lexora-words.csv"})
                else open.launch(arrayOf("*/*"))
            }
            if(Cloud.uid()==null){
                TextButton({reset="progress"}){Text("Reset learning progress",color=amber)}
                TextButton({reset="personal"}){Text("Delete personal vocabulary",color=amber)}
            }
            TextButton({credit=true}){Text("Sources and privacy")}
        }
    }
    if(reset.isNotEmpty()){
        val personal=reset=="personal"
        Confirm(if(personal)"Delete personal vocabulary?" else "Reset local progress?",
            if(personal)"This removes your personal entries, their history and deck memberships." else "This clears your ratings, answers, active session and study time on this device. Export a backup first.",{reset=""}){
            vm.run {
                if(personal)db.tx{
                    save(null);db.delete("undo_step",null,null)
                    for(table in listOf("deck","favorite","event","card"))db.execSQL("DELETE FROM $table WHERE word IN (SELECT id FROM word WHERE source='Personal')")
                    db.execSQL("DELETE FROM word WHERE source='Personal'")
                }else reset()
                "Changes saved."
            }
        }
    }
    if(credit)AlertDialog(onDismissRequest={credit=false},title={Text("Sources and privacy")},text={
        val notices=remember {ctx.assets.open("NOTICE.txt").bufferedReader().use {it.readText()}}
        Column(Modifier.verticalScroll(rememberScrollState())){Text(notices)}
    },confirmButton={TextButton({credit=false}){Text("Done")}})
}

@Suppress("DEPRECATION")
@Composable fun Account(vm:StudyModel){
    val ctx=LocalContext.current
    Sheet {
        Text("Account & sync",style=MaterialTheme.typography.titleLarge)
        if(!Cloud.enabled){Hint("Local profile · offline. This build has no cloud project configured.");return@Sheet}
        val auth=FirebaseAuth.getInstance()
        var user by remember {mutableStateOf(auth.currentUser)}
        var email by rememberSaveable{mutableStateOf("")};var password by remember {mutableStateOf("")}
        var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
        fun result(t:com.google.android.gms.tasks.Task<*>) {
            busy=true
            t.addOnCompleteListener { busy=false;if(it.isSuccessful){password="";message="Request completed.";vm.run{null}} else message=it.exception?.localizedMessage?:"Account request failed." }
        }
        DisposableEffect(auth){
            val listener=FirebaseAuth.AuthStateListener {user=it.currentUser;vm.run{null}}
            auth.addAuthStateListener(listener);onDispose {auth.removeAuthStateListener(listener)}
        }
        val google=remember {
            if(BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty())null else GoogleSignIn.getClient(ctx,GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail().requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID).build())
        }
        val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->
            runCatching{val token=GoogleSignIn.getSignedInAccountFromIntent(r.data).getResult(com.google.android.gms.common.api.ApiException::class.java).idToken
                result(auth.signInWithCredential(GoogleAuthProvider.getCredential(requireNotNull(token),null)))}.onFailure{message=it.localizedMessage?:"Google sign-in failed."}
        }
        if(user==null){
            Hint("Signing in opens a separate account profile. Your offline profile stays on this device. Use a local backup to merge its history.")
            OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true)
            OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            Secondary("Sign in",!busy && email.isNotBlank() && password.isNotBlank()){result(auth.signInWithEmailAndPassword(email.trim(),password))}
            Secondary("Create account",!busy && email.isNotBlank() && password.length>=6){result(auth.createUserWithEmailAndPassword(email.trim(),password))}
            if(google!=null)Secondary("Continue with Google",!busy){launcher.launch(google.signInIntent)}
            TextButton({result(auth.sendPasswordResetEmail(email.trim()))},enabled=!busy && email.isNotBlank()){Text("Reset password by email")}
        }else{
            Hint(user?.email?:"Signed in");Hint("Packaged vocabulary learning events sync automatically. Personal entries and decks use local backups.")
            Secondary("Sync now"){Cloud.plan(ctx);message="Sync queued."}
            TextButton({vm.run {pref("syncStatus").ifEmpty{"Awaiting first sync."}}}){Text("Check sync status")}
            TextButton({auth.signOut();google?.signOut();vm.run{null}}){Text("Sign out")}
            var delete by remember {mutableStateOf(false)}
            TextButton({delete=true}){Text("Delete account and history",color=amber)}
            if(delete)Confirm("Delete this account?","This permanently deletes the cloud learning history, sign-in account and this device’s account profile. Export a backup first. Firebase may require a recent sign-in.",{delete=false}){vm.deleteAccount()}
        }
        if(message.isNotBlank())Hint(message)
    }
}
@Composable fun Statistics(
    state:ScreenState,
    onSelectDay:(String)->Unit={},
    onOpenWord:(String)->Unit={},
){
    val s=state.stats
    val summary=remember(state.dailyHistory){summarizeHistory(state.dailyHistory)}
    val dailyGoal=state.prefs["goal"]?.toIntOrNull()?.coerceAtLeast(1) ?: 10
    Page {
        Label("Learning record");Heading("Statistics")
        TotalStatisticsOverview(s,state.goalCounts,summary)
        StudyCalendarAndTable(
            records=state.dailyHistory,
            selectedDay=state.selectedCalendarDay,
            selectedDayWords=state.selectedDayWords,
            dailyGoal=dailyGoal,
            onSelectDay=onSelectDay,
            onOpenWord=onOpenWord,
        )
        Sheet {Label("Last seven days");Bars(s.week);Hint("Daily rated words · oldest to newest")
            Hint("New words each day · "+s.newWeek.joinToString(" / ")) }
        Sheet {
            Label("Vocabulary coverage")
            for(c in s.coverage){
                Text("${c.level}   ${c.total-c.remaining} / ${c.total} covered")
                Progress((c.total-c.remaining).toFloat()/c.total.coerceAtLeast(1))
                Hint("${c.known} previously known · ${c.mastered} estimated mastered · ${c.remaining} remaining")
                Hint("${c.unseen} unseen · ${c.selected} selected · ${c.learning} learning · ${c.reviewing} reviewing")
            }
        }
        Sheet {Label("Memory estimates");Text((s.retention?.let{"$it%"}?:"—")+" predicted recall",fontSize=26.sp)
            Hint("FSRS estimates the chance you could recall each rated word today. Average stability: "+String.format(Locale.ENGLISH,"%.1f days",s.stability)+". Average difficulty: "+String.format(Locale.ENGLISH,"%.1f / 10",s.difficulty)+".")
            Hint("${s.mastered} estimated mastered · review state, at least 30 days of stability and within the scheduled interval.")
            Text("Observed review success · "+(s.success?.let{"$it%"}?:"No reviews yet"));Hint("Hard, Good and Easy ratings count as recalled.")
        }
        Sheet {Label("Upcoming reviews");Bars(s.forecast);Hint("Today, including overdue cards, followed by the next six days. Counts change after each rating.") }
        Sheet {Label("Mistake patterns");Hint("${s.mistakes} incorrect exercises · ${s.improved} missed words later answered correctly")
            s.missed.forEach{Text(it)};s.weak.forEach{Hint(it)} }
        Sheet {Label("Milestones");Text(if(s.started==0)"First word · ready to earn" else "First word ✓")
            Text(if(s.streak>=7)"Seven-day streak ✓" else "Seven-day streak · ${s.streak}/7")
            Text(if(s.started>=100)"One hundred words ✓" else "One hundred words · ${s.started}/100")
            Hint("10 XP for a newly rated word and 5 XP for a successful scheduled review. One level every 100 XP.") }
        Sheet {Label("Study suggestion");Text(when{ s.due>state.prefs.getValue("reviewLimit").toInt()->"Your review queue exceeds your daily limit. Focus on reviews or lower your new-word goal."
            s.mistakes>0->"Visit Mistake Lab for focused practice on words you missed.";s.started==0->"Start a new-word session to build your first review queue.";else->"Review due words before adding new vocabulary."})}
    }
}
@Composable private fun Bars(points:List<Int>){
    val max=points.maxOrNull()?.coerceAtLeast(1)?:1
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.Bottom){
        points.forEach{v->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
            Text(v.toString(),fontSize=12.sp);Spacer(Modifier.fillMaxWidth().height((3+44*v.toFloat()/max).dp).background(mint))
        }}
    }
}
