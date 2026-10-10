package cn.jiang.opennote;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Ink {
    public static final class Point { public double x,y; public long t; public float pressure=1,tilt,orientation; public Point(double x,double y){this.x=x;this.y=y;} public Point copy(double newY){Point p=new Point(x,newY);p.t=t;p.pressure=pressure;p.tilt=tilt;p.orientation=orientation;return p;} }
    public String id=UUID.randomUUID().toString(); public long created=System.currentTimeMillis(); public int color; public double width; public boolean pressureSensitive,streamlined;
    public final List<Point> points=new ArrayList<>();
    public Ink(int color,double width){this.color=color;this.width=width;}
    /** Split strokes crossing a cut so inserted whitespace never draws a connecting line. */
    public static List<Ink> insertSpace(List<Ink> input,double cut,double size){
        List<Ink> output=new ArrayList<>();
        for(Ink old:input){
            Ink current=new Ink(old.color,old.width);current.pressureSensitive=old.pressureSensitive;current.streamlined=old.streamlined;current.id=old.id;current.created=old.created;
            for(int i=0;i<old.points.size();i++){
                Point p=old.points.get(i);
                if(i>0){
                    Point previous=old.points.get(i-1);
                    if((previous.y<cut)!=(p.y<cut)){
                        double t=(cut-previous.y)/(p.y-previous.y);
                        double x=previous.x+t*(p.x-previous.x);
                        current.points.add(interpolate(previous,p,t,x,cut+(previous.y>=cut?size:0)));
                        output.add(current);current=new Ink(old.color,old.width);current.created=old.created;current.pressureSensitive=old.pressureSensitive;current.streamlined=old.streamlined;
                        current.points.add(interpolate(previous,p,t,x,cut+(p.y>=cut?size:0)));
                    }
                }
                current.points.add(p.copy(p.y+(p.y>=cut?size:0)));
            }
            if(!current.points.isEmpty())output.add(current);
        }
        return output;
    }
    private static Point interpolate(Point a,Point b,double t,double x,double y){
        Point p=new Point(x,y);p.t=a.t+Math.round(t*(b.t-a.t));p.pressure=(float)(a.pressure+t*(b.pressure-a.pressure));p.tilt=(float)(a.tilt+t*(b.tilt-a.tilt));p.orientation=(float)(a.orientation+t*(b.orientation-a.orientation));return p;
    }
    public static List<Ink> removeSpace(List<Ink> input,double top,double height,boolean deleteIntersecting){
        List<Ink> result=new ArrayList<>();double bottom=top+height;
        for(Ink old:input){
            boolean intersects=false;
            for(int i=0;i<old.points.size();i++){
                Point p=old.points.get(i);if(p.y>=top&&p.y<bottom)intersects=true;
                if(i>0){Point a=old.points.get(i-1);if(Math.min(a.y,p.y)<bottom&&Math.max(a.y,p.y)>=top)intersects=true;}
            }
            if(deleteIntersecting&&intersects)continue;
            Ink ink=new Ink(old.color,old.width);ink.pressureSensitive=old.pressureSensitive;ink.streamlined=old.streamlined;ink.id=old.id;ink.created=old.created;
            for(Point p:old.points)ink.points.add(p.copy(p.y>=bottom?p.y-height:p.y));
            result.add(ink);
        }return result;
    }
    public double widthAt(Point point){
        if(!pressureSensitive)return width;
        double pressure=Float.isFinite(point.pressure)?Math.max(0,Math.min(1,point.pressure)):1;
        return width*(.3+.95*Math.sqrt(pressure));
    }
    public boolean near(double x,double y,double radius){
        for(int i=0;i<points.size();i++){
            Point p=points.get(i);
            if(Math.hypot(x-p.x,y-p.y)<=radius+width/2)return true;
            if(i>0){
                Point a=points.get(i-1);double dx=p.x-a.x,dy=p.y-a.y,d=dx*dx+dy*dy;
                double t=d==0?0:Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy)/d));
                if(Math.hypot(x-a.x-t*dx,y-a.y-t*dy)<=radius+width/2)return true;
            }
        }return false;
    }
}
