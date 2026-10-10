package cn.jiang.opennote
import org.junit.Assert.*
import org.junit.Test
class StrokeCodecTest {
    @org.junit.Test fun pressureCodecReadsOldInkAndPreservesNewMode(){
        val ink=Ink(0xff123456.toInt(),2.0);ink.pressureSensitive=true;ink.streamlined=true;ink.points.add(Ink.Point(10.0,20.0).apply{pressure=.3f})
        val bytes=NoteStore.encode(ink);val restored=NoteStore.decode(bytes)
        org.junit.Assert.assertTrue(restored.pressureSensitive);org.junit.Assert.assertTrue(restored.streamlined);org.junit.Assert.assertEquals(.3f,restored.points[0].pressure,.0001f)
        val old=java.io.ByteArrayOutputStream();java.io.DataOutputStream(old).use{out->out.writeInt(1);out.writeUTF(ink.id);out.writeLong(ink.created);out.writeInt(ink.color);out.writeDouble(ink.width);out.writeInt(0)}
        org.junit.Assert.assertFalse(NoteStore.decode(old.toByteArray()).pressureSensitive)
        val v2=java.io.ByteArrayOutputStream();java.io.DataOutputStream(v2).use{out->out.writeInt(2);out.writeUTF(ink.id);out.writeLong(ink.created);out.writeInt(ink.color);out.writeDouble(ink.width);out.writeBoolean(true);out.writeInt(0)}
        val oldPressure=NoteStore.decode(v2.toByteArray());assertTrue(oldPressure.pressureSensitive);assertFalse(oldPressure.streamlined)
    }

    @Test fun binaryRoundTripPreservesGeometryAndPenSamples() {
        val ink=Ink(0x6688ff11,3.4);repeat(10_000){i->ink.points.add(Ink.Point(i/3.0,i/7.0).apply{t=i.toLong();pressure=.47f;tilt=.23f;orientation=.9f})}
        val result=NoteStore.decode(NoteStore.encode(ink));assertEquals(ink.id,result.id);assertEquals(ink.created,result.created);assertEquals(ink.color,result.color);assertEquals(ink.width,result.width,0.0);assertEquals(10000,result.points.size)
        ink.points.zip(result.points).forEach{(a,b)->assertEquals(a.x,b.x,0.0);assertEquals(a.y,b.y,0.0);assertEquals(a.t,b.t);assertEquals(a.pressure,b.pressure,0f);assertEquals(a.tilt,b.tilt,0f)}
    }
    @Test fun truncatedAndFutureVersionRecordsAreRejected(){
        val ink=Ink(0,1.0);ink.points.add(Ink.Point(1.0,2.0));val bytes=NoteStore.encode(ink)
        for(b in listOf(bytes.copyOf(bytes.size-1),bytes.clone().apply{this[3]=4}))try{NoteStore.decode(b);fail("accepted invalid record")}catch(_:Exception){}
    }
}
