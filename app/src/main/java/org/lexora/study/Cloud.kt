package org.lexora.study

import android.app.Application
import android.content.Context
import androidx.work.*
import com.google.firebase.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LexoraApp:Application(){
    override fun onCreate(){
        super.onCreate()
        if(BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()){
            FirebaseApp.initializeApp(this,FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build())
            Cloud.enabled=true
            FirebaseAuth.getInstance().addAuthStateListener { if(it.currentUser!=null)Cloud.plan(this) }
        }
    }
}
object Cloud{
    var enabled=false
    fun uid():String?=if(enabled)FirebaseAuth.getInstance().currentUser?.uid else null
    suspend fun deleteAccount(context:Context){
        val user=requireNotNull(FirebaseAuth.getInstance().currentUser)
        val uid=user.uid
        val work=WorkManager.getInstance(context)
        work.cancelUniqueWork("sync-$uid")
        work.cancelUniqueWork("periodic-$uid")
        val store=FirebaseFirestore.getInstance()
        val profile=store.collection("profiles").document(uid)
        // A tombstone prevents offline devices from resurrecting a deleted history.
        store.runTransaction { tx -> if(!tx.get(profile).exists())tx.set(profile,mapOf("deleting" to true));true }.await()
        while(true){
            val docs=profile.collection("events").limit(200).get(Source.SERVER).await().documents
            if(docs.isEmpty())break
            val batch=store.batch();docs.forEach{batch.delete(it.reference)};batch.commit().await()
        }
        user.delete().await()
    }
    fun plan(ctx:Context){
        val id=uid()?:return
        val connected=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val data=workDataOf("owner" to id)
        val work=WorkManager.getInstance(ctx)
        work.enqueueUniquePeriodicWork("periodic-$id",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6,TimeUnit.HOURS).setConstraints(connected).setInputData(data).build())
        work.enqueueUniqueWork("sync-$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).setInputData(data).build())
    }
}
class SyncWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result {
        val id=inputData.getString("owner")?:return Result.failure()
        if(Cloud.uid()!=id)return Result.success()
        return StudyRepository(applicationContext,id).use { repo ->
            try{
                syncProfile(repo,id)
                repo.set("syncStatus","Last synced "+java.time.Instant.now().toString())
                Result.success()
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){
                repo.set("syncStatus","Sync pending · "+(e.localizedMessage?:"Connection unavailable"))
                if(e is FirebaseFirestoreException && e.code in listOf(FirebaseFirestoreException.Code.PERMISSION_DENIED,FirebaseFirestoreException.Code.UNAUTHENTICATED))Result.failure() else Result.retry()
            }
        }
    }
}
internal suspend fun syncProfile(repo:StudyRepository,owner:String){
        val store=FirebaseFirestore.getInstance()
        val remote=store.collection("profiles").document(owner).collection("events")
        // Personal vocabulary travels in a local backup. Cloud events reference stable packaged IDs.
        while(Cloud.uid()==owner){
            val pending=repo.db.rows("SELECT e.* FROM event e LEFT JOIN synced s ON s.id=e.id WHERE s.id IS NULL AND e.word NOT LIKE 'personal-%' ORDER BY at,id LIMIT 60"){c->
                mapOf("id" to c.getString(0),"word" to c.getString(1),"kind" to c.getString(2),"score" to c.getLong(3),
                    "detail" to c.getString(4),"at" to c.getLong(5),"day" to c.getString(6),"retention" to c.getLong(7))
            }
            if(pending.isEmpty())break
            store.runTransaction { tx->
                val refs=pending.map{remote.document(it.getValue("id").toString())}
                val docs=refs.map(tx::get)
                docs.forEachIndexed {i,doc->if(!doc.exists()) tx.set(refs[i],mapOf("event" to pending[i],"ingestedAt" to FieldValue.serverTimestamp()))
                    else check(doc.get("event")==pending[i]){"Conflicting event ID"} }
                true
            }.await()
            repo.db.tx {pending.forEach{repo.db.execSQL("INSERT OR IGNORE INTO synced VALUES(?)",arrayOf(it.getValue("id"))) }}
        }
        while(Cloud.uid()==owner){
            var q:Query=remote.orderBy("ingestedAt").orderBy(FieldPath.documentId()).limit(100)
            val cursor=repo.pref("syncCursor")
            if(cursor.isNotEmpty()){
                val parts=cursor.split("|")
                q=q.startAfter(Timestamp(parts[0].toLong(),parts[1].toInt()),parts[2])
            }
            val docs=q.get(Source.SERVER).await().documents
            if(docs.isEmpty())break
            repo.db.tx {
                val events=docs.map{JSONObject(it.get("event") as Map<*,*>)}
                repo.mergeEvents(events)
                events.forEach {repo.db.execSQL("INSERT OR IGNORE INTO synced VALUES(?)",arrayOf(it.getString("id")))}
                val last=docs.last();val stamp=requireNotNull(last.getTimestamp("ingestedAt"))
                repo.set("syncCursor","${stamp.seconds}|${stamp.nanoseconds}|${last.id}")
            }
        }
    }
