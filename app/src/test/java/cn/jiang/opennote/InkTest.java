package cn.jiang.opennote;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class InkTest {
    @Test public void crossingStrokeSplitsWithoutBridgeAndPreservesSamples(){
        Ink ink=new Ink(0xff000000,2);Ink.Point a=new Ink.Point(10,100);a.t=31;a.pressure=.4f;ink.points.add(a);Ink.Point b=new Ink.Point(30,300);b.t=45;b.pressure=.8f;ink.points.add(b);
        List<Ink> result=Ink.insertSpace(Collections.singletonList(ink),200,80);
        assertEquals(2,result.size());assertEquals(200,result.get(0).points.get(1).y,0);assertEquals(280,result.get(1).points.get(0).y,0);assertEquals(380,result.get(1).points.get(1).y,0);
        assertEquals(45,result.get(1).points.get(1).t);assertEquals(.8f,result.get(1).points.get(1).pressure,0);
        assertEquals(300,ink.points.get(1).y,0);assertEquals(38,result.get(0).points.get(1).t);assertEquals(ink.id,result.get(0).id);assertEquals(ink.created,result.get(1).created);
    }
    @Test public void eraseUsesSegmentsNotJustVertices(){Ink ink=new Ink(0,1);ink.points.add(new Ink.Point(0,0));ink.points.add(new Ink.Point(100,0));assertTrue(ink.near(50,2,3));assertFalse(ink.near(50,20,3));}
    @Test public void deletionRequiresAnExplicitPolicy(){Ink ink=new Ink(0,1);ink.points.add(new Ink.Point(0,90));ink.points.add(new Ink.Point(0,220));assertEquals(0,Ink.removeSpace(Collections.singletonList(ink),100,80,true).size());assertEquals(1,Ink.removeSpace(Collections.singletonList(ink),100,80,false).size());assertEquals(140,Ink.removeSpace(Collections.singletonList(ink),100,80,false).get(0).points.get(1).y,0);}
    @Test public void hundredThousandSamplesRemainOrderedAfterInsertion(){Ink ink=new Ink(0,1);for(int i=0;i<100000;i++)ink.points.add(new Ink.Point(i%500,i/200.0));List<Ink> out=Ink.insertSpace(Collections.singletonList(ink),200,100);assertEquals(2,out.size());assertEquals(100002,out.stream().mapToInt(s->s.points.size()).sum());}
}
