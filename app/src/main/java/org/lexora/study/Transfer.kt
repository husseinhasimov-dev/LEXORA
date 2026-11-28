package org.lexora.study

import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.io.Writer
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

private fun records(input:Reader,separator:Char=','):List<List<String>> {
    val text=input.buffered().use { r ->
        val b=CharArray(8192);val out=StringBuilder()
        while(true){val n=r.read(b);if(n<0)break;out.append(b,0,n);require(out.length<=4_000_000){"CSV exceeds 4 MB."}}
        out.toString().removePrefix("\uFEFF")
    }
    val rows=mutableListOf<List<String>>(); val row=mutableListOf<String>();val field=StringBuilder()
    var quoted=false;var ended=false;var i=0
    fun finish(){row+=field.toString();field.clear();ended=false}
    while(i<text.length){
        val c=text[i++]
        when {
            quoted && c=='"' && i<text.length && text[i]=='"' -> {field.append('"');i++}
            quoted && c=='"' -> {quoted=false;ended=true}
            quoted -> field.append(c)
            c==separator -> finish()
            c=='\n' || c=='\r' -> {if(c=='\r' && text.getOrNull(i)=='\n')i++;finish();rows+=row.toList();row.clear()}
            ended -> error("Unexpected text after closing quotation mark")
            c=='"' -> {require(field.isEmpty());quoted=true}
            else -> field.append(c)
        }
    }
    require(!quoted){"Unclosed quoted field"}
    if(row.isNotEmpty() || field.isNotEmpty() || ended){finish();rows+=row.toList()}
    return rows
}

val csvHeader=listOf("german","english","level","pos","article","topic")
fun StudyRepository.importCsv(input:Reader,anki:Boolean=false):String {
    val rows=records(input,if(anki)'\t' else ',')
    val data=if(anki){
        require(rows.take(3).any { it.firstOrNull()=="#columns:german" && it.drop(1)==csvHeader.drop(1) }){"Expected Lexora's six-field Anki text format."}
        rows.dropWhile { it.firstOrNull()?.startsWith("#")==true }
    }else{
        require(rows.firstOrNull()==csvHeader){"Expected header: "+csvHeader.joinToString(",")}
        rows.drop(1)
    }
    var added=0;var duplicates=0
    db.tx { for((index,r) in data.withIndex()){
        require(r.size==6){"Row ${index+2} must have six columns"}
        if(personal(r)) added++ else duplicates++
    } }
    return "$added added · $duplicates duplicates"
}
fun StudyRepository.personal(fields:List<String>,editId:String?=null):Boolean {
    require(fields.size==6)
    val (raw,en,level,pos,article)=fields.map(String::trim)
    val topic=fields[5].trim().ifEmpty { "Personal" }
    val de=Normalizer.normalize(raw,Normalizer.Form.NFC)
    require(de.isNotBlank() && de.length<=120 && en.isNotBlank() && en.length<=600 && level in levels && pos in classes && topic.length<=80){"Check the headword, English meaning, level and word class."}
    require(if(pos=="noun") article in listOf("der","die","das") && de.first().isUpperCase() else article.isEmpty()){"Nouns need a capital letter and an article. Leave the article empty for other words."}
    val existing=db.str("SELECT id FROM word WHERE lemma=? COLLATE NOCASE",de)
    if(existing!=null && existing!=editId) return false
    if(editId!=null){
        require(editId.startsWith("personal-"))
        db.execSQL("UPDATE word SET lemma=?,meaning=?,level=?,pos=?,article=?,topic=? WHERE id=?",arrayOf(de,en,level,pos,article,topic,editId))
    }else{
        val id="personal-"+UUID.randomUUID()
        db.execSQL("INSERT INTO word VALUES(?,?,?,?,?,?, '',?,0,'Personal','','','','','','','','','Personal')",arrayOf(id,de,en,level,pos,article,topic))
    }
    return true
}
fun StudyRepository.exportCsv(out:Writer,anki:Boolean=false){
    fun write(row:List<String>){out.write(row.joinToString(if(anki)"\t" else ","){"\""+it.replace("\"","\"\"")+"\""}+"\r\n")}
    if(anki)out.write("#separator:tab\r\n#html:false\r\n#columns:"+csvHeader.joinToString("\t")+"\r\n") else write(csvHeader)
    db.rawQuery("SELECT lemma,meaning,level,pos,article,topic FROM word WHERE source='Personal' ORDER BY lemma",null).use {c->
        while(c.moveToNext())write(List(6,c::getString))
    }
    out.flush()
}

private fun Cursor.jsonRow()=JSONObject().also { obj ->
    columnNames.forEachIndexed { i,name -> obj.put(name,when(getType(i)){
        Cursor.FIELD_TYPE_INTEGER -> getLong(i);Cursor.FIELD_TYPE_FLOAT -> getDouble(i);else -> getString(i)
    }) }
}
fun StudyRepository.backup():JSONObject = db.tx {
    val result=JSONObject().put("format","lexora-local-v1")
    val queries=mapOf("exploration" to "SELECT * FROM exploration ORDER BY at,id","personal" to "SELECT * FROM word WHERE source='Personal'","events" to "SELECT * FROM event ORDER BY at,id",
        "favorites" to "SELECT * FROM favorite","decks" to "SELECT * FROM deck","preferences" to "SELECT * FROM pref WHERE k!='session'")
    (queries + (academyTables + intelligenceTables).associateWith { "SELECT * FROM "+it }).forEach { (name,sql) -> result.put(name,JSONArray(db.rows(sql){it.jsonRow()})) }
    result
}
fun StudyRepository.mergeEvents(events:List<JSONObject>):Int=db.tx {
    val affected=mutableSetOf<String>()
    var added=0
    for(e in events){
        val id=e.getString("id");UUID.fromString(id)
        val existing=db.rows("SELECT * FROM event WHERE id=?",listOf(id)){it.jsonRow()}.firstOrNull()
        if(existing!=null){
            require(listOf("word","kind","score","detail","at","day","retention").all {existing.get(it).toString()==e.get(it).toString()}){"Conflicting event ID. Existing history preserved."}
            continue
        }
        val kind=e.getString("kind");val score=e.getInt("score");val t=e.getLong("at");val target=e.getInt("retention")
        val w=e.getString("word")
        require(kind in listOf("new","review","answer","time","known","later","assess","void","adjust") && target in 70..97 && t>=0)
        require(when(kind){"new","review","assess","adjust"->score in 1..4;"answer"->score in 0..1;else->score>=0})
        LocalDate.parse(e.getString("day"))
        db.execSQL("INSERT INTO event VALUES(?,?,?,?,?,?,?,?)",arrayOf(id,w,kind,score,e.getString("detail"),t,e.getString("day"),target))
        if(kind in listOf("new","review","void","adjust"))affected.add(w)
        added++
    }
    affected.forEach(::replayCard)
    added
}
fun StudyRepository.restore(backup:JSONObject):String=db.tx {
    require(backup.getString("format")=="lexora-local-v1"){"Unsupported backup"}
    fun items(name:String)=backup.getJSONArray(name).let { a->List(a.length(),a::getJSONObject) }
    for(p in items("personal")){
        val id=p.getString("id");require(id.startsWith("personal-"));UUID.fromString(id.removePrefix("personal-"))
        val fields=listOf("lemma","meaning","level","pos","article","topic").map(p::getString)
        val existing=db.str("SELECT id FROM word WHERE lemma=? COLLATE NOCASE",fields.first())
        if(existing==id){
            val w=word(id)
            require(listOf(w.lemma,w.meaning,w.level,w.pos,w.article,w.topic)==fields){"Personal entry has different edits. Existing data preserved."}
            continue
        }
        require(existing==null && db.long("SELECT count(*) FROM word WHERE id=?",id)==0L){"Personal entry conflict. Your existing data was preserved."}
        require(personal(fields))
        db.execSQL("UPDATE word SET id=? WHERE lemma=? AND source='Personal'",arrayOf(id,fields.first()))
    }
    items("favorites").forEach { db.execSQL("INSERT OR IGNORE INTO favorite VALUES(?)",arrayOf(it.getString("word"))) }
    items("decks").forEach { addDeck(it.getString("name"),it.getString("word")) }
    if(backup.has("exploration")) items("exploration").forEach { e ->
        UUID.fromString(e.getString("id"))
        db.execSQL("INSERT OR IGNORE INTO exploration VALUES(?,?,?,?,?,?,?,?,?)", arrayOf("id","mode","item","part","target","other","answer","correct","at").map { if(e.isNull(it))null else e.getString(it) }.toTypedArray())
    }
    val added=mergeEvents(items("events"))
    for(table in academyTables + intelligenceTables) {
        if(!backup.has(table))continue
        val columns=db.rows("PRAGMA table_info("+table+")"){it.getString(1)}
        val sql="INSERT OR IGNORE INTO "+table+"("+columns.joinToString(",")+") VALUES("+columns.joinToString(","){"?"}+")"
        items(table).forEach { row -> db.execSQL(sql,columns.map { if(row.isNull(it))null else row.get(it) }.toTypedArray()) }
    }
    for(p in items("preferences")){
        val k=p.getString("k"); val v=p.getString("v")
        val valid=when(k) {
            "weeklyReviewGoal"->v.toIntOrNull() in 1..500
            "quietStart","quietEnd"->v.toIntOrNull() in 0..1439
            "notificationsMaster"->v in listOf("true","false")
            "direction"->v in practiceDirections
            "goal","size","reviewLimit"->v.toIntOrNull() in 1..500
            "retention"->v.toIntOrNull() in 70..97
            "duration"->v in listOf("0","5","10","15")
            "level"->v in levels
            "speed"->v.toFloatOrNull()?.let { it in 0.5f..2f }==true
            "writing","context","hints","welcome"->v in listOf("true","false")
            else->when {
                k.startsWith("academy.") || k.startsWith("v61.")->true
                k.startsWith("revisit.")->db.long("SELECT count(*) FROM word WHERE id=?",k.removePrefix("revisit."))==1L && runCatching{LocalDate.parse(v)}.isSuccess
                k.startsWith("dailyTarget.")->runCatching{LocalDate.parse(k.removePrefix("dailyTarget."))}.isSuccess && v.toIntOrNull() in 1..500
                k.startsWith("notify.")->v=="deleted" || runCatching{ReminderSchedule.parse(k.removePrefix("notify."),v)}.isSuccess
                else->false
            }
        }
        if(valid && db.str("SELECT v FROM pref WHERE k=?",k)==null)set(k,v)
    }
    "$added learning events restored. Existing progress retained."
}
