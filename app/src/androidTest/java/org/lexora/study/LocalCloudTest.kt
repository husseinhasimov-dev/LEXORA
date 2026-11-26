package org.lexora.study

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalCloudTest{
    @Test fun emailAuthRulesAndTwoDeviceOfflineSync()=runBlocking {
        assumeEmulators()
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        FirebaseApp.initializeApp(ctx,FirebaseOptions.Builder().setApplicationId("1:1234:android:1234")
            .setProjectId("demo-lexora").setApiKey("fake-api-key").build())
        val auth=FirebaseAuth.getInstance()
        auth.useEmulator("127.0.0.1",9099)
        val store=FirebaseFirestore.getInstance()
        store.useEmulator("127.0.0.1",8080)
        Cloud.enabled=true
        val email=UUID.randomUUID().toString()+"@example.test"
        val password=UUID.randomUUID().toString()
        try {
            val uid=auth.createUserWithEmailAndPassword(email,password).await().user!!.uid
            auth.signOut()
            assertEquals(uid,auth.signInWithEmailAndPassword(email,password).await().user!!.uid)
            auth.sendPasswordResetEmail(email).await()
            StudyRepository(ctx,"device-a-"+uid).use { a->
                StudyRepository(ctx,"device-b-"+uid).use { b->
                    val w=a.words(Filters()).first().id
                    a.event(w,"new",3);syncProfile(a,uid);syncProfile(b,uid)
                    assertEquals(1,b.stats().started)
                    a.event(w,"answer",0,"Article");b.event(w,"answer",1,"Write")
                    syncProfile(b,uid);syncProfile(a,uid);syncProfile(b,uid);syncProfile(a,uid)
                    assertEquals(3,a.db.long("SELECT count(*) FROM event"))
                    assertEquals(3,b.db.long("SELECT count(*) FROM event"))
                    assertEquals(3,store.collection("profiles").document(uid).collection("events").get(Source.SERVER).await().size())
                    val fresh=a.words(Filters())[1].id
                    a.reclassify(fresh,4);syncProfile(a,uid);syncProfile(b,uid);assertEquals(4,b.grade(fresh))
                    a.undo(a.latestStep());syncProfile(a,uid);syncProfile(b,uid);assertEquals(0,b.grade(fresh))
                    val old=b.lastRating(w)!!
                    b.correctRating(w,old.first,1);syncProfile(b,uid);syncProfile(a,uid)
                    assertEquals(1,a.lastRating(w)!!.second)
                    assertEquals(1,a.db.long("SELECT count(*) FROM effective_event WHERE kind='new'"))

                }
            }
            auth.signOut()
            auth.createUserWithEmailAndPassword("other-"+email,password).await()
            assertTrue(runCatching {store.collection("profiles").document(uid).collection("events").get(Source.SERVER).await()}.isFailure)
            auth.signOut()
            auth.signInWithEmailAndPassword(email,password).await()
            Cloud.deleteAccount(ctx)
            assertNull(auth.currentUser)
            assertTrue(runCatching{auth.signInWithEmailAndPassword(email,password).await()}.isFailure)
        } finally {
            auth.signOut();Cloud.enabled=false
        }
    }
    private fun assumeEmulators() {
        Assume.assumeTrue("Start the local Firebase emulators and set localBackend=true.",InstrumentationRegistry.getArguments().getString("localBackend")=="true")
        check(BuildConfig.FIREBASE_PROJECT_ID.isEmpty())
    }
}
