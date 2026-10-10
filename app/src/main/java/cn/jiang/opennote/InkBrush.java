package cn.jiang.opennote;

import android.graphics.*;

/** One filled outline avoids dark overlap seams and matches live ink to PDF export. */
public final class InkBrush {
    private InkBrush(){}
    public static void append(Path path,Ink ink,Ink.Point previous,Ink.Point point){
        float r=(float)ink.widthAt(point)/2;
        if(previous!=null){
            double dx=point.x-previous.x,dy=point.y-previous.y,length=Math.hypot(dx,dy);
            if(length>0){float a=(float)ink.widthAt(previous)/2;float nx=(float)(-dy/length),ny=(float)(dx/length);
                path.moveTo((float)previous.x+nx*a,(float)previous.y+ny*a);
                path.lineTo((float)point.x+nx*r,(float)point.y+ny*r);
                path.lineTo((float)point.x-nx*r,(float)point.y-ny*r);
                path.lineTo((float)previous.x-nx*a,(float)previous.y-ny*a);path.close();
            }
        }
        path.addCircle((float)point.x,(float)point.y,r,Path.Direction.CCW);
    }
    /** Append-only live geometry; replay/export uses exactly the same builder. */
    public static final class LiveStroke {
        public final Path path=new Path();
        private final Ink ink;
        private final StrokeStreamline filter=new StrokeStreamline();
        private Ink.Point previous,rawLast;
        private boolean finished;
        public LiveStroke(Ink ink){this.ink=ink;}
        public void add(Ink.Point raw){
            if(finished)throw new IllegalStateException("Stroke already finished");
            Ink.Point point=ink.streamlined?filter.add(raw):raw;
            addGeometry(point);rawLast=raw;
        }
        private void addGeometry(Ink.Point point){
            if(ink.pressureSensitive)append(path,ink,previous,point);
            else if(previous==null)path.moveTo((float)point.x,(float)point.y);
            else path.lineTo((float)point.x,(float)point.y);
            previous=point;
        }
        public void finish(){
            if(finished)return;
            // Preserve the pen-up endpoint, including very short strokes.
            if(ink.streamlined&&rawLast!=null&&(previous.x!=rawLast.x||previous.y!=rawLast.y))addGeometry(rawLast);
            finished=true;
        }
    }
    public static void draw(Canvas canvas,Ink ink,Paint paint){
        if(ink.points.isEmpty())return;
        paint.setColor(ink.color);LiveStroke stroke=new LiveStroke(ink);
        for(Ink.Point point:ink.points)stroke.add(point);stroke.finish();
        if(ink.pressureSensitive){paint.setStyle(Paint.Style.FILL);canvas.drawPath(stroke.path,paint);}
        else{paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((float)ink.width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(ink.points.size()==1){Ink.Point point=ink.points.get(0);canvas.drawPoint((float)point.x,(float)point.y,paint);}else canvas.drawPath(stroke.path,paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
