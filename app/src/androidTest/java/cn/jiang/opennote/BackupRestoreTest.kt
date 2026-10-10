package cn.jiang.opennote

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    @Test fun backupRestoreEditableInkAndLayoutAndInterruptedCommit() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(c.filesDir,"backup-test-${UUID.randomUUID()}").apply{mkdirs()}
        val root=File(folder,"docs").apply{mkdirs()};val work=File(folder,"work").apply{mkdirs()}
        val db="backup-${UUID.randomUUID()}.db";val store=NoteStore(c,db);val backup=LibraryBackup(store,root,work)
        try{
            val dir=File(root,UUID.randomUUID().toString()).apply{mkdirs()};val n=Notebook(dir,"数学测试")
            n.pages.add(Notebook.PageData(400.0,600.0));val pdf=PdfDocument();try{val p=pdf.startPage(PdfDocument.PageInfo.Builder(400,600,1).create());pdf.finishPage(p);File(dir,"original.pdf").outputStream().use{pdf.writeTo(it)}}finally{pdf.close()}
            val ink=Ink(Color.BLACK,2.0);ink.pressureSensitive=true;ink.streamlined=true;ink.points.add(Ink.Point(20.0,310.0).apply{pressure=.2f});ink.points.add(Ink.Point(80.0,330.0).apply{pressure=.9f});n.pages[0].strokes.add(ink)
            val card=Notebook.Card().apply{page=0;text="中文公式说明";x=30.0;y=400.0};n.cards.add(card)
            n.insert(0,200.0,80*72/25.4);store.create(n);store.save(store.capture(n))
            val archive=File(folder,"backup.zip");val noop=BackupArchive.Progress{_,_->}
            backup.export(archive,JSONObject().put("version",1).put("finger",false),noop)
            val preview=backup.preview(archive,noop);assertEquals(1,preview.notes.size)
            assertEquals(1,backup.commit(preview,noop));assertEquals(2,store.list().size)
            val restoredEntry=store.list().first{it.id!=dir.name};val restored=store.load(root,restoredEntry.id)
            assertEquals(BackupArchive.sha256(File(dir,"original.pdf")),BackupArchive.sha256(File(restored.directory,"original.pdf")))
            assertEquals(n.pages[0].layout.height(),restored.pages[0].layout.height(),1e-8)
            val strokes=store.loadInk(restoredEntry.id,0);assertTrue(strokes[0].pressureSensitive);assertTrue(strokes[0].streamlined);assertArrayEquals(NoteStore.encode(n.pages[0].strokes[0]),NoteStore.encode(strokes[0]));assertEquals(card.text,restored.cards[0].text);assertEquals(n.cards[0].y,restored.cards[0].y,1e-8)
            restored.pages[0].strokes=strokes;restored.pages[0].loaded=true;restored.insert(0,100.0,20.0);store.save(store.capture(restored))
            assertEquals(n.pages[0].layout.height()+20,store.load(root,restoredEntry.id).pages[0].layout.height(),1e-8)
            val failed=backup.preview(archive,noop);var calls=0
            try{backup.commit(failed,BackupArchive.Progress{_,_->if(++calls==3)throw InterruptedIOException()});fail()}catch(_:InterruptedIOException){}
            assertEquals(2,store.list().size);assertEquals(2,root.listFiles()!!.size)
            backup.export(File(folder,"second.zip"),JSONObject().put("version",1),noop)
        }finally{store.close();c.deleteDatabase(db);folder.deleteRecursively()}
    }
    @Test fun largeSingleStrokeSurvivesCursorWindowAndBackupStreaming(){
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val db="blob-${UUID.randomUUID()}.db";val dir=File(c.filesDir,"blob-${UUID.randomUUID()}").apply{mkdirs()};val store=NoteStore(c,db)
        try{
            val n=Notebook(dir,"100k points");n.pages.add(Notebook.PageData(400.0,600.0));val ink=Ink(Color.BLACK,2.0)
            repeat(100000){ink.points.add(Ink.Point((it%400).toDouble(),(it%600).toDouble()))};n.pages[0].strokes.add(ink)
            store.create(n);store.save(store.capture(n));assertEquals(100000,store.loadInk(dir.name,0)[0].points.size)
            store.save(store.capture(n)) // unchanged large BLOB comparison is also chunked
            val output=java.io.ByteArrayOutputStream();java.io.DataOutputStream(output).use{store.writeInk(dir.name,0,it)}
            var count=0;java.io.DataInputStream(java.io.ByteArrayInputStream(output.toByteArray())).use{store.readInk(it){s->count+=s.points.size}};assertEquals(100000,count)
        }finally{store.close();c.deleteDatabase(db);dir.deleteRecursively()}
    }
    @Test fun startupJournalReconcilesCommittedAndOrphanFiles(){
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val folder=File(c.filesDir,"journal-${UUID.randomUUID()}").apply{mkdirs()};val db="journal-${UUID.randomUUID()}.db";val store=NoteStore(c,db)
        try{
            val root=File(folder,"docs").apply{mkdirs()};val committed=File(root,UUID.randomUUID().toString()).apply{mkdirs()};val orphan=File(root,UUID.randomUUID().toString()).apply{mkdirs()}
            val n=Notebook(committed,"keep");n.pages.add(Notebook.PageData(400.0,600.0));store.create(n)
            val journal=File(root,".restore-test.json");journal.writeText(org.json.JSONArray(listOf(committed.name,orphan.name)).toString())
            LibraryBackup(store,root,File(folder,"work").apply{mkdirs()}).recoverInterruptedRestore()
            assertTrue(committed.exists());assertFalse(orphan.exists());assertFalse(journal.exists());assertEquals(1,store.list().size)
        }finally{store.close();c.deleteDatabase(db);folder.deleteRecursively()}
    }
}
