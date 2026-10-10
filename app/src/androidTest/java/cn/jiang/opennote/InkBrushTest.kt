package cn.jiang.opennote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test

class InkBrushTest {
    @Test fun completedLiveGeometryMatchesReplayAndKeepsPenUpEndpoint() {
        val ink=Ink(Color.BLACK,8.0).apply { pressureSensitive=true;streamlined=true }
        repeat(40){i->ink.points.add(Ink.Point(20.0+i*3,60.0+kotlin.math.sin(i*.3)*20).apply{pressure=.1f+i*.02f})}
        val live=InkBrush.LiveStroke(ink);ink.points.forEach { live.add(it) };live.finish();live.finish()
        val a=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888)
        val b=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888)
        try {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;style=Paint.Style.FILL}
            Canvas(a).drawPath(live.path,paint);InkBrush.draw(Canvas(b),ink,paint)
            val actual=IntArray(160*120);val expected=IntArray(actual.size)
            a.getPixels(actual,0,160,0,0,160,120);b.getPixels(expected,0,160,0,0,160,120)
            assertArrayEquals(expected,actual)
            val end=ink.points.last();assertTrue(Color.alpha(b.getPixel(end.x.toInt(),end.y.toInt()))>0)
            assertEquals(137.0,end.x,0.0);assertEquals(.88f,end.pressure,.0001f)
        } finally { a.recycle();b.recycle() }
    }

    @Test fun legacyDotRetainsItsOriginalPositionAndWidth() {
        val ink=Ink(Color.BLACK,8.0);ink.points.add(Ink.Point(40.0,40.0))
        assertFalse(ink.streamlined);assertFalse(ink.pressureSensitive)
        val bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        try {
            InkBrush.draw(Canvas(bitmap),ink,Paint(Paint.ANTI_ALIAS_FLAG))
            assertEquals(255,Color.alpha(bitmap.getPixel(40,40)))
            assertEquals(0,Color.alpha(bitmap.getPixel(50,40)))
        } finally { bitmap.recycle() }
    }
}
