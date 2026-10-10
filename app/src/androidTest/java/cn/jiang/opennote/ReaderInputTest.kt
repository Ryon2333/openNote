package cn.jiang.opennote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderInputTest {
    @Test fun nativeViewConsumesHistoricalStylusSamplesAndRepaints(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync{
            val n=Notebook(File("/unused"),"input");n.pages.add(Notebook.PageData(400.0,600.0))
            val view=ReaderView(instrumentation.targetContext);view.layout(0,0,800,1000)
            val source=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);source.eraseColor(android.graphics.Color.WHITE)
            var saves=0;view.listener=object:ReaderView.Listener{override fun changed(){saves++};override fun space(y:Double){};override fun text(x:Double,y:Double){};override fun clip(rect:RectF){}}
            view.document(n,0,source);view.setMode(ReaderView.Mode.PEN)
            val now=SystemClock.uptimeMillis();val props=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            fun coords(x:Float,y:Float,p:Float)=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=p;size=.01f}
            fun event(action:Int,t:Long,x:Float,y:Float,p:Float)=MotionEvent.obtain(now,t,action,1,arrayOf(props),arrayOf(coords(x,y,p)),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            event(MotionEvent.ACTION_DOWN,now,208f,208f,.3f).useEvent{view.onTouchEvent(it)}
            val move=event(MotionEvent.ACTION_MOVE,now+10,227.2f,227.2f,.4f)
            move.addBatch(now+20,arrayOf(coords(246.4f,246.4f,.6f)),0);move.addBatch(now+30,arrayOf(coords(265.6f,265.6f,.7f)),0)
            move.useEvent{view.onTouchEvent(it)}
            val preview=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);view.draw(Canvas(preview))
            event(MotionEvent.ACTION_UP,now+40,284.8f,284.8f,.8f).useEvent{view.onTouchEvent(it)}
            assertEquals(1,saves);assertEquals(1,n.pages[0].strokes.size);val points=n.pages[0].strokes[0].points
            assertEquals(5,points.size);assertEquals(100.0,points.first().x,.001);assertEquals(140.0,points.last().x,.001)
            assertEquals(.6f,points[2].pressure,.001f);assertTrue(points.zipWithNext().all{(a,b)->a.t<=b.t})
            // Reopening a tab restores the visible reading position, rather than jumping to fit-width.
            view.setMode(ReaderView.Mode.READ)
            event(MotionEvent.ACTION_DOWN,now+50,208f,208f,.3f).useEvent{view.onTouchEvent(it)}
            event(MotionEvent.ACTION_MOVE,now+60,208f,112f,.3f).useEvent{view.onTouchEvent(it)}
            event(MotionEvent.ACTION_UP,now+70,208f,112f,.3f).useEvent{view.onTouchEvent(it)}
            val viewport=view.viewport();assertEquals("unused:0",view.viewportKey())
            view.reset();view.draw(Canvas(preview));assertEquals(android.graphics.Color.WHITE,preview.getPixel(208,112))
            view.restoreViewport(viewport);view.draw(Canvas(preview));assertNotEquals(android.graphics.Color.WHITE,preview.getPixel(208,112))
            preview.recycle();source.recycle();view.clear()
        }
    }
    @Test fun whitespaceDragPreviewsWithoutMutationAndCommitsOnce(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync{
            val n=Notebook(File("/unused"),"drag");n.pages.add(Notebook.PageData(400.0,600.0))
            val view=ReaderView(instrumentation.targetContext);view.layout(0,0,800,1000)
            val source=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);source.eraseColor(android.graphics.Color.WHITE)
            var inserts=0;var resizes=0;var size=0.0
            view.listener=object:ReaderView.Listener{
                override fun changed(){};override fun space(y:Double){}
                override fun space(y:Double,height:Double){inserts++;size=height;n.insert(0,y,height);view.refresh()}
                override fun resizeSpace(index:Int,height:Double){resizes++;size=height;n.growGap(index,height);view.refresh()}
                override fun text(x:Double,y:Double){};override fun clip(rect:RectF){}
            }
            view.document(n,0,source);view.setMode(ReaderView.Mode.SPACE)
            val now=SystemClock.uptimeMillis()
            fun touch(action:Int,y:Float){MotionEvent.obtain(now,now+10,action,208f,y,1f,.05f,0,1f,1f,0,0).useEvent{view.onTouchEvent(it)}}
            touch(MotionEvent.ACTION_DOWN,208f);touch(MotionEvent.ACTION_MOVE,400f)
            assertTrue(n.pages[0].layout.gaps.isEmpty());assertEquals(0,inserts)
            val preview=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);view.draw(Canvas(preview))
            touch(MotionEvent.ACTION_UP,400f);assertEquals(1,inserts);assertEquals(100.0,size,.01)
            // The lower handle is at source 100 + whitespace 100 = display 200.
            touch(MotionEvent.ACTION_DOWN,400f);touch(MotionEvent.ACTION_MOVE,496f)
            assertEquals(100.0,n.pages[0].layout.gaps[0].height,.01)
            touch(MotionEvent.ACTION_UP,496f);assertEquals(1,resizes);assertEquals(150.0,size,.01)
            val history=n.undo.size
            touch(MotionEvent.ACTION_DOWN,496f);touch(MotionEvent.ACTION_MOVE,592f);touch(MotionEvent.ACTION_CANCEL,592f)
            assertEquals(1,resizes);assertEquals(history,n.undo.size);assertEquals(150.0,n.pages[0].layout.gaps[0].height,.01)
            view.clear();source.recycle();preview.recycle()
        }
    }

    @Test fun palmCannotInterruptStylusAndCanceledInkLeavesNoHistory(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync{
            val n=Notebook(File("/unused"),"palm");n.pages.add(Notebook.PageData(400.0,600.0))
            val view=ReaderView(instrumentation.targetContext);view.layout(0,0,800,1000)
            val source=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);source.eraseColor(android.graphics.Color.WHITE)
            var saves=0
            view.listener=object:ReaderView.Listener{override fun changed(){saves++};override fun space(y:Double){};override fun text(x:Double,y:Double){};override fun clip(rect:RectF){}}
            view.document(n,0,source);view.setMode(ReaderView.Mode.PEN)
            val now=SystemClock.uptimeMillis();var time=now
            fun send(action:Int,tools:IntArray,xs:FloatArray,ys:FloatArray,pressures:FloatArray,flags:Int=0){
                val properties=tools.mapIndexed{i,tool->MotionEvent.PointerProperties().apply{id=if(tool==MotionEvent.TOOL_TYPE_STYLUS)7 else i;toolType=tool}}.toTypedArray()
                val coordinates=tools.mapIndexed{i,tool->MotionEvent.PointerCoords().apply{x=xs[i];y=ys[i];pressure=pressures[i];size=if(tool==MotionEvent.TOOL_TYPE_FINGER).3f else .01f}}.toTypedArray()
                MotionEvent.obtain(now,++time,action,tools.size,properties,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,flags).useEvent{view.onTouchEvent(it)}
            }
            val finger=MotionEvent.TOOL_TYPE_FINGER;val pen=MotionEvent.TOOL_TYPE_STYLUS
            send(MotionEvent.ACTION_DOWN,intArrayOf(finger),floatArrayOf(500f),floatArrayOf(500f),floatArrayOf(1f))
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),intArrayOf(finger,pen),floatArrayOf(500f,208f),floatArrayOf(500f,208f),floatArrayOf(1f,.1f))
            send(MotionEvent.ACTION_MOVE,intArrayOf(finger,pen),floatArrayOf(510f,300f),floatArrayOf(510f,208f),floatArrayOf(1f,.9f))
            send(MotionEvent.ACTION_CANCEL,intArrayOf(finger),floatArrayOf(510f),floatArrayOf(510f),floatArrayOf(1f))
            send(MotionEvent.ACTION_POINTER_UP,intArrayOf(finger,pen),floatArrayOf(510f,300f),floatArrayOf(510f,208f),floatArrayOf(1f,.9f),MotionEvent.FLAG_CANCELED)
            assertTrue(n.pages[0].strokes.isEmpty());assertEquals(0,saves)
            send(MotionEvent.ACTION_UP,intArrayOf(pen),floatArrayOf(400f),floatArrayOf(208f),floatArrayOf(.9f))
            assertEquals(1,saves);assertEquals(1,n.pages[0].strokes.size)
            val ink=n.pages[0].strokes[0];assertTrue(ink.pressureSensitive);assertEquals(100.0,ink.points[0].x,.01);assertTrue(ink.widthAt(ink.points.first())<ink.widthAt(ink.points.last()))
            val history=n.undo.size
            send(MotionEvent.ACTION_DOWN,intArrayOf(pen),floatArrayOf(208f),floatArrayOf(400f),floatArrayOf(.8f))
            send(MotionEvent.ACTION_UP,intArrayOf(pen),floatArrayOf(400f),floatArrayOf(400f),floatArrayOf(.8f),MotionEvent.FLAG_CANCELED)
            assertEquals(1,n.pages[0].strokes.size);assertEquals(history,n.undo.size);assertEquals(1,saves)
            send(MotionEvent.ACTION_DOWN,intArrayOf(pen),floatArrayOf(208f),floatArrayOf(400f),floatArrayOf(.8f))
            send(MotionEvent.ACTION_CANCEL,intArrayOf(pen),floatArrayOf(400f),floatArrayOf(400f),floatArrayOf(.8f))
            assertEquals(1,n.pages[0].strokes.size);assertEquals(history,n.undo.size);assertEquals(1,saves)
            view.palmProtection=false;view.fingerInk=true
            send(MotionEvent.ACTION_DOWN,intArrayOf(finger),floatArrayOf(208f),floatArrayOf(400f),floatArrayOf(.8f))
            send(MotionEvent.ACTION_CANCEL,intArrayOf(finger),floatArrayOf(400f),floatArrayOf(400f),floatArrayOf(.8f))
            assertEquals(1,n.pages[0].strokes.size);assertEquals(history,n.undo.size);assertEquals(1,saves)
            val output=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            fun thickness(x:Int)= (190..225).sumOf{255-android.graphics.Color.red(output.getPixel(x,it))}
            val light=thickness(215);val heavy=thickness(380)
            if(heavy<=light*1.3){
                val bytes=java.io.ByteArrayOutputStream();output.compress(Bitmap.CompressFormat.PNG,100,bytes)
                val bundle=android.os.Bundle();bundle.putString("opennote_reader_png",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));instrumentation.sendStatus(2,bundle)
            }
            assertTrue("Pressure must change rendered thickness: light=$light heavy=$heavy points="+ink.points.joinToString{ "${it.x},${it.y},${it.pressure}" },heavy>light*1.3)
            view.clear();source.recycle();output.recycle()
        }
    }
    private inline fun MotionEvent.useEvent(block:(MotionEvent)->Unit){try{block(this)}finally{recycle()}}
}
