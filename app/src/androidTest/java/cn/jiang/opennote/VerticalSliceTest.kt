package cn.jiang.opennote

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VerticalSliceTest {
    @Test fun sqliteRestartGapAndRasterExportPreservePositions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"test-docs").apply{mkdirs()};val id=UUID.randomUUID().toString();val dir=File(root,id).apply{mkdirs()}
        val original=File(dir,"original.pdf")
        val pdf=PdfDocument();try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(400,600,1).create())
            val paint=Paint().apply{color=Color.RED};page.canvas.drawRect(30f,80f,100f,120f,paint)
            paint.color=Color.BLUE;page.canvas.drawRect(30f,300f,100f,340f,paint)
            pdf.finishPage(page);original.outputStream().use{pdf.writeTo(it)}
        }
        finally{pdf.close()}
        val note=Notebook(dir,"integration");note.pages.add(Notebook.PageData(400.0,600.0))
        val ink=Ink(Color.GREEN,5.0);ink.points.add(Ink.Point(150.0,310.0));ink.points.add(Ink.Point(210.0,330.0));note.pages[0].strokes.add(ink)
        val size=80*72/25.4;note.insert(0,200.0,size)
        var store=NoteStore(context,"instrumentation.db");store.create(note);store.save(store.capture(note));store.close()
        store=NoteStore(context,"instrumentation.db");val reopened=store.load(root,id);reopened.pages[0].strokes=store.loadInk(id,0)
        assertEquals(600+size,reopened.pages[0].layout.height(),1e-7)
        assertEquals(310+size,reopened.pages[0].strokes[0].points[0].y,1e-7)
        val output=File(dir,"output.pdf")
        PdfEngine.export(output,original,reopened,144,object:PdfEngine.Progress{override fun update(done:Int,total:Int){} })
        PdfEngine(output).use { engine ->
            assertEquals(1,engine.count());val dimensions=engine.dimensions(0);assertEquals(600+size,dimensions.layout.sourceHeight,1.5)
            val bitmap=engine.render(0,800)
            fun sample(x:Double,y:Double):Int=bitmap.getPixel((x/400*bitmap.width).toInt(),(y/dimensions.layout.sourceHeight*bitmap.height).toInt())
            assertTrue(Color.red(sample(50.0,100.0))>200)
            val blank=sample(50.0,250.0);assertTrue(Color.red(blank)>240&&Color.blue(blank)>240&&Color.green(blank)>240)
            assertTrue(Color.blue(sample(50.0,320+size))>180)
            val green=sample(180.0,320+size);assertTrue(Color.green(green)>70&&Color.red(green)<100)
            bitmap.recycle()
        }
        // Undo / redo snapshots and persisted modifications remain editable.
        reopened.checkpoint();reopened.pages[0].layout.resize(0,size+30);assertTrue(reopened.undo());assertEquals(600+size,reopened.pages[0].layout.height(),1e-7);assertTrue(reopened.redo());assertEquals(630+size,reopened.pages[0].layout.height(),1e-7)
        store.close();dir.deleteRecursively()
    }
    @Test fun transactionFailureDoesNotReplaceCommittedStroke(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val root=File(context.filesDir,"test-docs").apply{mkdirs()};val dir=File(root,UUID.randomUUID().toString()).apply{mkdirs()}
        val n=Notebook(dir,"atomic");n.pages.add(Notebook.PageData(400.0,600.0));val ink=Ink(Color.BLACK,2.0);ink.points.add(Ink.Point(20.0,30.0));n.pages[0].strokes.add(ink)
        val store=NoteStore(context,"instrumentation.db");store.create(n);store.save(store.capture(n));val c=Notebook.Card();c.page=0;n.cards.add(c);n.cards.add(c)
        try{store.save(store.capture(n));fail("duplicate card should roll back")}catch(_:android.database.sqlite.SQLiteConstraintException){}
        val restored=store.load(root,dir.name);assertEquals(0,restored.cards.size);assertEquals(1,store.loadInk(dir.name,0).size)
        store.close();dir.deleteRecursively()
    }
}
