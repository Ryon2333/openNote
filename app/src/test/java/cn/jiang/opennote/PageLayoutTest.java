package cn.jiang.opennote;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PageLayoutTest {
    @Test public void eightyMillimetresMovesSourceContent(){
        PageLayout p=new PageLayout(842);p.insert(330,80*72/25.4);
        assertEquals(1068.7716535433071,p.height(),1e-8);
        assertEquals(320,p.sourceToDisplay(320),1e-8);
        assertEquals(576.7716535433071,p.sourceToDisplay(350),1e-8);
        assertEquals(330,p.displayToSource(430),1e-8);
        assertEquals(842,p.slices().stream().mapToDouble(s->s.sourceBottom-s.sourceTop).sum(),1e-8);
    }
    @Test public void adjacentInsertionMergesAndPageEndsPreserveContent(){
        PageLayout p=new PageLayout(800);p.insert(0,10);p.insert(5,20);p.insert(p.height(),40);
        assertEquals(2,p.gaps.size());assertEquals(870,p.height(),0);assertEquals(30,p.gapTop(1)-800,0);
        assertEquals(800,p.slices().get(0).sourceBottom,0);
    }
    @Test public void resizeAndRemoveRoundTrip(){PageLayout p=new PageLayout(842);p.insert(200,120);p.resize(0,40);assertEquals(882,p.height(),0);p.remove(0);assertEquals(842,p.height(),0);assertEquals(1,p.slices().size());}
    @Test public void randomEditPropertyConservesAllSourceSlices(){
        Random r=new Random(92831);for(int document=0;document<100;document++){
            PageLayout p=new PageLayout(300+r.nextInt(1400));
            for(int edit=0;edit<100;edit++){
                if(!p.gaps.isEmpty()&&r.nextInt(3)==0)p.remove(r.nextInt(p.gaps.size()));
                else p.insert(r.nextDouble()*p.height(),1+r.nextDouble()*15);
                double source=0,previousDisplay=-1,total=0;
                for(PageLayout.Slice s:p.slices()){assertEquals(source,s.sourceTop,1e-7);assertTrue(s.sourceBottom>s.sourceTop);assertTrue(s.displayTop>previousDisplay);source=s.sourceBottom;previousDisplay=s.displayTop;total+=s.sourceBottom-s.sourceTop;
                    double y=(s.sourceTop+s.sourceBottom)/2;assertEquals(y,p.displayToSource(p.sourceToDisplay(y)),1e-7);
                }
                assertEquals(p.sourceHeight,total,1e-7);assertEquals(p.sourceHeight,source,1e-7);
                double sum=p.sourceHeight;for(PageLayout.Gap g:p.gaps)sum+=g.height;assertEquals(sum,p.height(),1e-7);
            }
        }
    }
    @Test public void invalidNumbersAndHugeBlankAreRejected(){PageLayout p=new PageLayout(800);for(double size:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,20000}){try{p.insert(100,size);fail();}catch(IllegalArgumentException expected){}}assertEquals(800,p.height(),0);}
}
