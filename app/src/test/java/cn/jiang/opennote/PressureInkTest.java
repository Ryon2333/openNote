package cn.jiang.opennote;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class PressureInkTest {
    @Test public void pressureIsBoundedAndSurvivesWhitespaceAndUndo() throws Exception {
        Ink ink=new Ink(0xff123456,2);ink.pressureSensitive=true;ink.streamlined=true;
        Ink.Point light=new Ink.Point(10,50);light.pressure=.1f;
        Ink.Point heavy=new Ink.Point(10,150);heavy.pressure=.9f;ink.points.add(light);ink.points.add(heavy);
        assertTrue(ink.widthAt(light)<ink.widthAt(heavy));
        light.pressure=-2;assertEquals(.6,ink.widthAt(light),.0001);
        light.pressure=Float.NaN;assertEquals(2.5,ink.widthAt(light),.0001);light.pressure=.1f;
        for(Ink part:Ink.insertSpace(Collections.singletonList(ink),100,50))assertTrue(part.pressureSensitive&&part.streamlined);
        for(Ink part:Ink.removeSpace(Collections.singletonList(ink),80,20,false))assertTrue(part.pressureSensitive&&part.streamlined);
        Notebook n=new Notebook(new java.io.File("unused"),"pressure");n.pages.add(new Notebook.PageData(400,600));n.pages.get(0).strokes.add(ink);
        n.insert(0,100,40);n.undo();assertTrue(n.pages.get(0).strokes.get(0).pressureSensitive&&n.pages.get(0).strokes.get(0).streamlined);
        Notebook restored=new Notebook(new java.io.File("unused"),"");restored.restore(n.json());assertTrue(restored.pages.get(0).strokes.get(0).pressureSensitive&&restored.pages.get(0).strokes.get(0).streamlined);
    }
}
