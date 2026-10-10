package cn.jiang.opennote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** All distances are PDF points (1/72 inch); gaps are anchored in original page coordinates. */
public final class PageLayout {
    public static final class Gap {
        public double sourceY, height;
        public Gap(double sourceY, double height) { this.sourceY = sourceY; this.height = height; }
    }
    public static final class Slice {
        public double sourceTop, sourceBottom, displayTop;
        Slice(double a, double b, double d) { sourceTop=a; sourceBottom=b; displayTop=d; }
    }
    public final double sourceHeight;
    public final List<Gap> gaps = new ArrayList<>();
    public PageLayout(double height) {
        if (!Double.isFinite(height) || height <= 0) throw new IllegalArgumentException("Invalid page height");
        sourceHeight = height;
    }
    public double height() { double h=sourceHeight; for(Gap g:gaps) h+=g.height; return h; }
    public double sourceToDisplay(double y) {
        double result=y; for(Gap g:gaps) if(g.sourceY<=y) result+=g.height; return result;
    }
    public double displayToSource(double y) {
        double offset=0;
        for(Gap g:gaps) {
            double start=g.sourceY+offset;
            if(y<start) break;
            if(y<=start+g.height) return g.sourceY;
            offset+=g.height;
        }
        return Math.max(0, Math.min(sourceHeight,y-offset));
    }
    public void insert(double displayY, double size) {
        if(!Double.isFinite(size)||size<=0||!Double.isFinite(displayY)||displayY<0||displayY>height())
            throw new IllegalArgumentException("Invalid gap");
        if(height()+size>20000 || height()+size>sourceHeight*8) throw new IllegalArgumentException("页面过长，请减少留白高度");
        double anchor=displayToSource(displayY);
        for(Gap g:gaps) if(Math.abs(g.sourceY-anchor)<0.00001) {g.height+=size;return;}
        gaps.add(new Gap(anchor,size));
        Collections.sort(gaps, Comparator.comparingDouble(g->g.sourceY));
    }
    public double gapTop(int index) {
        double top=gaps.get(index).sourceY;for(int i=0;i<index;i++)top+=gaps.get(i).height;return top;
    }
    public void resize(int index,double height) {
        if(!Double.isFinite(height)||height<1)throw new IllegalArgumentException("留白至少为 1 pt");
        double next=height()-gaps.get(index).height+height;
        if(next>20000||next>sourceHeight*8)throw new IllegalArgumentException("页面过长");
        gaps.get(index).height=height;
    }
    public void remove(int index){gaps.remove(index);}
    public List<Slice> slices() {
        ArrayList<Slice> out=new ArrayList<>(); double source=0, offset=0;
        for(Gap g:gaps) {
            if(g.sourceY>source) out.add(new Slice(source,g.sourceY,source+offset));
            source=g.sourceY; offset+=g.height;
        }
        if(source<sourceHeight) out.add(new Slice(source,sourceHeight,source+offset));
        return out;
    }
}
