package cn.jiang.opennote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.*
import java.util.UUID

/** All methods run on the serialized IO worker. Commit boundaries are completed editor commands. */
class NoteStore(context: Context, databaseName: String = "opennote.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true);db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY,title TEXT NOT NULL,page_count INTEGER NOT NULL,current_page INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE pages(doc TEXT NOT NULL,page_no INTEGER NOT NULL,id TEXT NOT NULL,width REAL NOT NULL,height REAL NOT NULL,measured INTEGER NOT NULL,gaps TEXT NOT NULL,PRIMARY KEY(doc,page_no),FOREIGN KEY(doc) REFERENCES documents(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE strokes(doc TEXT NOT NULL,page_no INTEGER NOT NULL,id TEXT NOT NULL,position INTEGER NOT NULL,data BLOB NOT NULL,PRIMARY KEY(doc,id),FOREIGN KEY(doc,page_no) REFERENCES pages(doc,page_no) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX stroke_pages ON strokes(doc,page_no,position)")
        db.execSQL("CREATE TABLE cards(doc TEXT NOT NULL,id TEXT NOT NULL,page_no INTEGER NOT NULL,x REAL NOT NULL,y REAL NOT NULL,text TEXT NOT NULL,image TEXT NOT NULL,PRIMARY KEY(doc,id),FOREIGN KEY(doc,page_no) REFERENCES pages(doc,page_no) ON DELETE CASCADE)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 has no historical predecessor. Never destructively recreate a database on upgrade.
        error("缺少数据库迁移：$oldVersion → $newVersion")
    }
    data class Entry(val id: String, val title: String, val count: Int)
    fun list(): List<Entry> = readableDatabase.rawQuery("SELECT id,title,page_count FROM documents ORDER BY updated DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Entry(c.getString(0),c.getString(1),c.getInt(2))) }
    }
    private fun docId(n: Notebook) = n.directory.name
    private fun pageValues(id: String, index: Int, p: Notebook.PageData) = ContentValues().apply {
        put("doc",id);put("page_no",index);put("id",p.id);put("width",p.width);put("height",p.layout.sourceHeight);put("measured",if(p.measured)1 else 0)
        val gaps=JSONArray();p.layout.gaps.forEach { gaps.put(JSONArray().put(it.sourceY).put(it.height)) };put("gaps",gaps.toString())
    }
    fun create(n: Notebook) { val db=writableDatabase;db.beginTransaction();try {
        db.insertOrThrow("documents",null,ContentValues().apply { put("id",docId(n));put("title",n.title);put("page_count",n.pages.size);put("current_page",n.currentPage);put("updated",System.currentTimeMillis()) })
        n.pages.forEachIndexed { index,p->db.insertOrThrow("pages",null,pageValues(docId(n),index,p)) }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() } }
    fun load(root: File,id: String): Notebook {
        val n=Notebook(File(root,id),"")
        readableDatabase.rawQuery("SELECT title,current_page FROM documents WHERE id=?",arrayOf(id)).use { c -> check(c.moveToFirst());n.title=c.getString(0);n.currentPage=c.getInt(1) }
        readableDatabase.rawQuery("SELECT id,width,height,measured,gaps FROM pages WHERE doc=? ORDER BY page_no",arrayOf(id)).use { c ->
            while(c.moveToNext()) { val p=Notebook.PageData(c.getDouble(1),c.getDouble(2));p.id=c.getString(0);p.measured=c.getInt(3)==1;p.loaded=false
                val gaps=JSONArray(c.getString(4));for(i in 0 until gaps.length()){val g=gaps.getJSONArray(i);p.layout.gaps.add(PageLayout.Gap(g.getDouble(0),g.getDouble(1)))};n.pages.add(p) }
        }
        readableDatabase.rawQuery("SELECT id,page_no,x,y,text,image FROM cards WHERE doc=?",arrayOf(id)).use { c ->while(c.moveToNext()){val card=Notebook.Card();card.id=c.getString(0);card.page=c.getInt(1);card.x=c.getDouble(2);card.y=c.getDouble(3);card.text=c.getString(4);card.image=c.getString(5);n.cards.add(card)} }
        return n
    }
    fun loadInk(doc: String,index: Int): MutableList<Ink> = readableDatabase.rawQuery("SELECT id FROM strokes WHERE doc=? AND page_no=? ORDER BY position",arrayOf(doc,index.toString())).use { c->
        mutableListOf<Ink>().apply { while(c.moveToNext())add(decode(readBlob(doc,c.getString(0)))) }
    }
    // CursorWindow cannot hold arbitrary multi-MB BLOBs. Read each stroke in bounded slices.
    private fun readBlob(doc:String,id:String):ByteArray {
        val out=ByteArrayOutputStream();streamBlob(doc,id,out);return out.toByteArray()
    }
    private fun blobSize(doc:String,id:String):Int = readableDatabase.rawQuery("SELECT length(data) FROM strokes WHERE doc=? AND id=?",arrayOf(doc,id)).use{c->check(c.moveToFirst());c.getInt(0)}
    private fun streamBlob(doc:String,id:String,out:OutputStream) {
        val size=blobSize(doc,id);require(size in 1..40000000){"笔迹文件过大"};var offset=0
        while(offset<size){val chunk=readableDatabase.rawQuery("SELECT substr(data,?,?) FROM strokes WHERE doc=? AND id=?",arrayOf((offset+1).toString(),minOf(512*1024,size-offset).toString(),doc,id)).use{c->check(c.moveToFirst());c.getBlob(0)};check(chunk.isNotEmpty());out.write(chunk);offset+=chunk.size}
    }
    data class Save(val doc:String,val title:String,val page:Int,val data:Notebook.PageData,val cards:List<Notebook.Card>)
    fun capture(n:Notebook):Save {
        val source=n.pages[n.currentPage];val p=Notebook.PageData(source.width,source.layout.sourceHeight);p.id=source.id;p.measured=source.measured
        source.layout.gaps.forEach{p.layout.gaps.add(PageLayout.Gap(it.sourceY,it.height))};p.strokes=ArrayList(source.strokes)
        val cards=n.cards.filter { it.page==n.currentPage }.map { old->Notebook.Card().apply { id=old.id;page=old.page;x=old.x;y=old.y;text=old.text;image=old.image } }
        return Save(docId(n),n.title,n.currentPage,p,cards)
    }
    /** Flush as one transaction; older page snapshots must not overwrite current document metadata. */
    fun flushPending(doc:String,title:String,page:Int,pending:java.util.concurrent.ConcurrentHashMap<String,Save>,current:Save?) {
        require(current==null||current.doc==doc)
        val batch=pending.values.filter{it.doc==doc&&(current==null||it.page!=current.page)}.sortedBy{it.page}.toMutableList()
        if(current!=null)batch.add(current)
        val db=writableDatabase;db.beginTransaction()
        try {
            batch.forEach{save(it)}
            check(db.update("documents",ContentValues().apply{put("title",title);put("current_page",page);put("updated",System.currentTimeMillis())},"id=?",arrayOf(doc))==1)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        // Keep every snapshot on rollback, and retain any newer snapshot queued during the flush.
        batch.forEach{pending.remove(it.doc+":"+it.page,it)}
    }
    fun save(s:Save) {
        val db=writableDatabase;db.beginTransaction();try {
            db.update("documents",ContentValues().apply{put("title",s.title);put("current_page",s.page);put("updated",System.currentTimeMillis())},"id=?",arrayOf(s.doc))
            val p=pageValues(s.doc,s.page,s.data);p.remove("doc");p.remove("page_no");db.update("pages",p,"doc=? AND page_no=?",arrayOf(s.doc,s.page.toString()))
            val existing=HashSet<String>();db.rawQuery("SELECT id FROM strokes WHERE doc=? AND page_no=?",arrayOf(s.doc,s.page.toString())).use { c->while(c.moveToNext())existing.add(c.getString(0)) }
            s.data.strokes.forEachIndexed { index,ink-> val bytes=encode(ink);val had=existing.remove(ink.id)
                if(!had || blobSize(s.doc,ink.id)!=bytes.size || !readBlob(s.doc,ink.id).contentEquals(bytes)) db.insertWithOnConflict("strokes",null,ContentValues().apply{put("doc",s.doc);put("page_no",s.page);put("id",ink.id);put("position",index);put("data",bytes)},SQLiteDatabase.CONFLICT_REPLACE)
                else db.update("strokes",ContentValues().apply{put("position",index)},"doc=? AND id=?",arrayOf(s.doc,ink.id))
            }
            existing.forEach{db.delete("strokes","doc=? AND id=?",arrayOf(s.doc,it))}
            db.delete("cards","doc=? AND page_no=?",arrayOf(s.doc,s.page.toString()))
            s.cards.forEach{card->db.insertOrThrow("cards",null,ContentValues().apply{put("doc",s.doc);put("id",card.id);put("page_no",card.page);put("x",card.x);put("y",card.y);put("text",card.text);put("image",card.image)})}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun <T> transaction(block:()->T):T {
        val db=writableDatabase;db.beginTransaction()
        try{val result=block();db.setTransactionSuccessful();return result}finally{db.endTransaction()}
    }
    fun rename(id:String,title:String){require(title.isNotBlank()&&title.length<=200);check(writableDatabase.update("documents",ContentValues().apply{put("title",title);put("updated",System.currentTimeMillis())},"id=?",arrayOf(id))==1)}
    fun storedInkBytes():Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(length(data)),0) FROM strokes",null).use{c->c.moveToFirst();c.getLong(0)}
    fun writeInk(doc:String,page:Int,out:DataOutputStream) {
        out.writeInt(1)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM strokes WHERE doc=? AND page_no=?",arrayOf(doc,page.toString())).use{c->c.moveToFirst();out.writeInt(c.getInt(0))}
        readableDatabase.rawQuery("SELECT id FROM strokes WHERE doc=? AND page_no=? ORDER BY position",arrayOf(doc,page.toString())).use{c->while(c.moveToNext()){val id=c.getString(0);out.writeInt(blobSize(doc,id));streamBlob(doc,id,out)}}
    }
    fun readInk(input:DataInputStream,consume:(Ink)->Unit) {
        require(input.readInt()==1){"笔迹文件版本不兼容"};val count=input.readInt();require(count in 0..1000000){"笔迹数量异常"}
        val ids=HashSet<String>()
        repeat(count){val size=input.readInt();require(size in 1..40000000){"笔迹文件过大"};val bytes=ByteArray(size);input.readFully(bytes);val ink=decode(bytes);require(ids.add(ink.id)){"重复笔迹 ID"};consume(ink)}
        require(input.read()==-1){"笔迹文件存在额外数据"}
    }
    fun importInk(doc:String,page:Int,input:DataInputStream) {
        var position=0
        readInk(input){ink->writableDatabase.insertOrThrow("strokes",null,ContentValues().apply{put("doc",doc);put("page_no",page);put("id",ink.id);put("position",position++);put("data",encode(ink))})}
    }
    fun saveCards(n:Notebook) {
        n.cards.forEach{c->writableDatabase.insertOrThrow("cards",null,ContentValues().apply{put("doc",docId(n));put("id",c.id);put("page_no",c.page);put("x",c.x);put("y",c.y);put("text",c.text);put("image",c.image)})}
    }
    companion object {
        fun encode(ink:Ink):ByteArray = ByteArrayOutputStream().also { bytes->DataOutputStream(bytes).use { o ->
            o.writeInt(3);o.writeUTF(ink.id);o.writeLong(ink.created);o.writeInt(ink.color);o.writeDouble(ink.width);o.writeBoolean(ink.pressureSensitive);o.writeBoolean(ink.streamlined);o.writeInt(ink.points.size)
            ink.points.forEach { p->o.writeDouble(p.x);o.writeDouble(p.y);o.writeLong(p.t);o.writeFloat(p.pressure);o.writeFloat(p.tilt);o.writeFloat(p.orientation) }
        } }.toByteArray()
        fun decode(bytes:ByteArray):Ink = DataInputStream(ByteArrayInputStream(bytes)).use { i->
            val version=i.readInt();require(version in 1..3){"不支持的笔迹版本"};val id=i.readUTF();val created=i.readLong();val ink=Ink(i.readInt(),i.readDouble());ink.id=id;ink.created=created;ink.pressureSensitive=version>=2&&i.readBoolean();ink.streamlined=version>=3&&i.readBoolean()
            val count=i.readInt();require(count in 0..1_000_000 && count.toLong()*36 <= i.available()){ "笔迹数据损坏" }
            repeat(count){val p=Ink.Point(i.readDouble(),i.readDouble());p.t=i.readLong();p.pressure=i.readFloat();p.tilt=i.readFloat();p.orientation=i.readFloat();ink.points.add(p)};require(i.available()==0);ink
        }
    }
}
