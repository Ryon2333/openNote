package cn.jiang.opennote;
import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;
public class NotebookCommandTest {
    @Test public void pageScopedUndoAndRedoKeepOtherPagesUntouched()throws Exception{
        Notebook n=new Notebook(new File("unused"),"commands");n.pages.add(new Notebook.PageData(595,842));n.pages.add(new Notebook.PageData(842,595));
        n.currentPage=1;n.insert(1,200,100);assertEquals(695,n.pages.get(1).layout.height(),0);n.currentPage=0;
        assertTrue(n.undo());assertEquals(1,n.currentPage);assertEquals(595,n.pages.get(1).layout.height(),0);assertEquals(842,n.pages.get(0).layout.height(),0);
        assertTrue(n.redo());assertEquals(695,n.pages.get(1).layout.height(),0);
    }
    @Test public void cardAndInkMoveTogetherAndUndoRestoresExactCoordinates()throws Exception{
        Notebook n=new Notebook(new File("unused"),"commands");n.pages.add(new Notebook.PageData(595,842));Notebook.Card c=new Notebook.Card();c.page=0;c.y=400;n.cards.add(c);
        Ink ink=new Ink(0,2);ink.points.add(new Ink.Point(10,400));n.pages.get(0).strokes.add(ink);
        n.insert(0,300,120);assertEquals(520,n.cards.get(0).y,0);assertEquals(520,n.pages.get(0).strokes.get(0).points.get(0).y,0);
        n.undo();assertEquals(400,n.cards.get(0).y,0);assertEquals(400,n.pages.get(0).strokes.get(0).points.get(0).y,0);
    }
    @Test public void expandingGapMovesLowerContentInOneUndoStep()throws Exception{
        Notebook n=new Notebook(new File("unused"),"drag");n.pages.add(new Notebook.PageData(400,600));n.insert(0,100,96);
        Ink ink=new Ink(0,2);ink.points.add(new Ink.Point(20,240));n.pages.get(0).strokes.add(ink);
        Notebook.Card c=new Notebook.Card();c.y=240;n.cards.add(c);int steps=n.undo.size();
        n.growGap(0,160);assertEquals(steps+1,n.undo.size());assertEquals(304,n.cards.get(0).y,0);assertEquals(304,n.pages.get(0).strokes.get(0).points.get(0).y,0);
        n.undo();assertEquals(96,n.pages.get(0).layout.gaps.get(0).height,0);assertEquals(240,n.cards.get(0).y,0);
        n.redo();assertEquals(160,n.pages.get(0).layout.gaps.get(0).height,0);
    }
    @Test public void rejectedGapGrowthDoesNotChangeHistoryOrPage()throws Exception{
        Notebook n=new Notebook(new File("unused"),"drag");n.pages.add(new Notebook.PageData(400,600));n.insert(0,100,96);int steps=n.undo.size();
        try{n.growGap(0,Double.NaN);fail();}catch(IllegalArgumentException expected){}
        try{n.growGap(0,6000);fail();}catch(IllegalArgumentException expected){}
        assertEquals(steps,n.undo.size());assertEquals(96,n.pages.get(0).layout.gaps.get(0).height,0);
    }
}
