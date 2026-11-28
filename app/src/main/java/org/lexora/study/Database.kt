package org.lexora.study

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.State
import java.time.Instant

class StudyDatabase private constructor(private val ctx:Context,name:String):SQLiteOpenHelper(ctx, name, null, 6) {
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        check(db.long("SELECT count(*) FROM word") > 0)
        db.execSQL("CREATE TABLE pref(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE card(word TEXT PRIMARY KEY REFERENCES word(id),state TEXT NOT NULL,step INTEGER,stability REAL NOT NULL,difficulty REAL NOT NULL,due INTEGER NOT NULL,last INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX card_due ON card(due)")
        db.execSQL("CREATE TABLE event(id TEXT PRIMARY KEY,word TEXT NOT NULL REFERENCES word(id),kind TEXT NOT NULL,score INTEGER NOT NULL,detail TEXT NOT NULL,at INTEGER NOT NULL,day TEXT NOT NULL,retention INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX event_word ON event(word,at,id)")
        db.execSQL("CREATE INDEX event_kind_day ON event(kind,day)")
        db.execSQL("CREATE TABLE favorite(word TEXT PRIMARY KEY REFERENCES word(id))")
        db.execSQL("CREATE TABLE deck(name TEXT NOT NULL,word TEXT NOT NULL REFERENCES word(id),PRIMARY KEY(name,word))")
        createSyncIndex(db)
        createLearningSchema(db)
        installContent(db)
        createExplore(db)
        createAcademy(db)
    }
    override fun onUpgrade(db:SQLiteDatabase, old:Int, new:Int) {
        check(old in 1..5 && new==6)
        if(old==1) createSyncIndex(db)
        if(old<3)createLearningSchema(db)
        if(old<4)installContent(db)
        if(old<5)createExplore(db)
        createAcademy(db)
    }
    private fun createExplore(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE exploration(id TEXT PRIMARY KEY, mode TEXT NOT NULL,item TEXT NOT NULL,part TEXT NOT NULL,target TEXT REFERENCES word(id) ON DELETE CASCADE,other TEXT REFERENCES word(id) ON DELETE CASCADE,answer TEXT NOT NULL,correct INTEGER NOT NULL,at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX explore_lookup ON exploration(mode,item,at)")
    }
    private fun installContent(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE content_v4(word TEXT PRIMARY KEY REFERENCES word(id),category TEXT NOT NULL,credit TEXT NOT NULL)")
        db.execSQL("CREATE INDEX content_category ON content_v4(category,word)")
        val update=db.compileStatement("UPDATE word SET example=?,translation=?,cloze=?,answer=? WHERE id=?")
        try {
            ctx.assets.open("content-v4.jsonl").bufferedReader().useLines { lines->lines.forEach {line->
                val r=org.json.JSONObject(line)
                listOf("example","translation","cloze","answer","id").forEachIndexed{i,key->update.bindString(i+1,r.getString(key))}
                check(update.executeUpdateDelete()==1)
                if(r.has("meaning"))db.execSQL("UPDATE word SET meaning=? WHERE id=?",arrayOf(r.getString("meaning"),r.getString("id")))
                db.execSQL("INSERT INTO content_v4 VALUES(?,?,?)",arrayOf(r.getString("id"),r.getString("category"),r.getString("credit")))
            }}
        }finally{update.close()}
    }
    private fun createSyncIndex(db:SQLiteDatabase)=db.execSQL("CREATE TABLE synced(id TEXT PRIMARY KEY REFERENCES event(id) ON DELETE CASCADE)")
    companion object {
        @Synchronized fun open(context:Context,owner:String?):StudyDatabase {
            val name=if(owner==null) "lexora.db" else "account-"+java.util.UUID.nameUUIDFromBytes(owner.toByteArray())+".db"
            val file=context.getDatabasePath(name)
            if(!file.exists()) {
                file.parentFile!!.mkdirs()
                val tmp=File(file.parentFile,"catalog.installing")
                context.assets.open("catalog.sqlite").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE)
            }
            return StudyDatabase(context.applicationContext,name)
        }
    }
}

inline fun <T> SQLiteDatabase.tx(block:()->T):T {
    beginTransaction()
    try { val value=block(); setTransactionSuccessful(); return value } finally { endTransaction() }
}
fun <T> SQLiteDatabase.rows(sql:String, args:List<String> = emptyList(), read:(Cursor)->T):List<T> =
    rawQuery(sql,args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(read(c)) } }
fun SQLiteDatabase.long(sql:String,vararg args:String) = rawQuery(sql,args).use { if(it.moveToFirst()) it.getLong(0) else 0L }
fun SQLiteDatabase.str(sql:String,vararg args:String) = rawQuery(sql,args).use { if(it.moveToFirst()) it.getString(0) else null }
fun Cursor.word()=Word(getString(0),getString(1),getString(2),getString(3),getString(4),getString(5),
    getString(6),getString(7),getInt(8),getString(9),getString(10),getString(11),getString(12),
    getString(13),getString(14),getString(15),getString(16),getString(17),getString(18))
fun Cursor.card()=Card.builder().cardId(getString(0).hashCode()).state(State.valueOf(getString(1)))
    .step(if(isNull(2)) null else getInt(2)).stability(getDouble(3)).difficulty(getDouble(4))
    .due(Instant.ofEpochMilli(getLong(5))).lastReview(Instant.ofEpochMilli(getLong(6))).build()
fun SQLiteDatabase.saveCard(word:String,c:Card) {
    execSQL("INSERT OR REPLACE INTO card VALUES(?,?,?,?,?,?,?)",arrayOf(word,c.state.name,c.step,c.stability,c.difficulty,c.due.toEpochMilli(),c.lastReview.toEpochMilli()))
}
