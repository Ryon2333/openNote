package cn.jiang.opennote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class PendingFlushTest {
    @Test fun pendingFlushIsAtomicAndPreservesLatestTitleAndReadingPage(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=UUID.randomUUID().toString();val dbName="flush-$suffix.db"
        val root=File(ctx.cacheDir,"flush-$suffix").apply{mkdirs()}
        val n=Notebook(File(root,suffix).apply{mkdirs()},"旧标题")
        repeat(2){n.pages.add(Notebook.PageData(400.0,600.0))}
        val store=NoteStore(ctx,dbName)
        try{
            store.create(n)
            fun ink(y:Double)=Ink(0xff243746.toInt(),1.8).apply{points.add(Ink.Point(20.0,y))}
            n.pages[0].strokes.add(ink(10.0));val old=store.capture(n)
            n.currentPage=1;n.title="新标题";n.pages[1].strokes.add(ink(20.0));n.pages[1].strokes.add(ink(40.0))
            val current=store.capture(n)
            val pending=ConcurrentHashMap<String,NoteStore.Save>()
            pending["$suffix:0"]=old;pending["$suffix:1"]=current
            store.writableDatabase.execSQL("CREATE TRIGGER reject_flush BEFORE UPDATE ON pages WHEN NEW.page_no=1 BEGIN SELECT RAISE(ABORT,'test failure'); END")
            try{store.flushPending(suffix,n.title,1,pending,current);fail("Expected injected failure")}catch(_:android.database.sqlite.SQLiteException){}
            assertEquals(2,pending.size)
            assertEquals("旧标题",store.load(root,suffix).title)
            assertTrue(store.loadInk(suffix,0).isEmpty());assertTrue(store.loadInk(suffix,1).isEmpty())
            store.writableDatabase.execSQL("DROP TRIGGER reject_flush")
            store.flushPending(suffix,n.title,1,pending,current)
            assertTrue(pending.isEmpty());val reopened=store.load(root,suffix)
            assertEquals("新标题",reopened.title);assertEquals(1,reopened.currentPage)
            assertEquals(1,store.loadInk(suffix,0).size);assertEquals(2,store.loadInk(suffix,1).size)
            // An older page queued before a rename must not revert title or the requested reading page.
            pending["$suffix:1"]=store.capture(n)
            n.title="再次改名";n.currentPage=0;n.pages[0].loaded=false
            store.flushPending(suffix,n.title,0,pending,null)
            val renamed=store.load(root,suffix)
            assertEquals("再次改名",renamed.title);assertEquals(0,renamed.currentPage)
            assertEquals(2,store.loadInk(suffix,1).size);assertTrue(pending.isEmpty())
        }finally{store.close();ctx.deleteDatabase(dbName);root.deleteRecursively()}
    }
}
