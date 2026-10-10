package cn.jiang.opennote;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.Iterator;

public final class ReaderView extends View {
    public enum Mode { READ,PEN,HIGHLIGHT,ERASE,SPACE,TEXT,CLIP }
    public interface Listener {
        void changed(); void space(double y);
        default void space(double y,double height){space(y);}
        default void resizeSpace(int index,double height){}
        void text(double x,double y); void clip(RectF rect);
    }
    public Listener listener;public Mode mode=Mode.READ;public boolean fingerInk=false, palmProtection=true, pressureInk=true,smoothInk=true;
    public int penColor=0xff243746;public double penWidth=1.8;
    public static final class Viewport {
        public final float zoom,x,y;
        private Viewport(float zoom,float x,float y){this.zoom=zoom;this.x=x;this.y=y;}
    }
    private Viewport pendingViewport;
    public String viewportKey(){return notebook==null?null:notebook.directory.getName()+":"+page;}
    public Viewport viewport(){return notebook==null||bitmap==null||getWidth()==0||getHeight()==0?null:new Viewport(scale/fitScale,(getWidth()/2f-tx)/scale,(getHeight()/2f-ty)/scale);}
    public void restoreViewport(Viewport state){
        if(state==null||notebook==null)return;
        if(getWidth()==0||getHeight()==0){pendingViewport=state;return;}
        scale=fitScale*Math.max(.75f,Math.min(5,state.zoom));tx=getWidth()/2f-state.x*scale;ty=getHeight()/2f-state.y*scale;clamp();invalidate();
    }
    private int dragGap=-2; private double dragTop,dragOld,dragHeight; private float dragStart;
    private Bitmap settled;private boolean sceneDirty=true;
    private Notebook notebook;private Bitmap bitmap;private int page;
    private float scale=1,fitScale=1,tx,ty,lastX,lastY;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private Ink active;private InkBrush.LiveStroke liveStroke;private Path activePath;private boolean editing,selecting,stylusDown,moved,multiGesture;
    private int activePointer=-1;private java.util.List<Ink> eraseBefore;
    private long lastStylus;private float startX,startY,cutY=-1;private RectF selection;
    public ReaderView(Context context){super(context);setBackgroundColor(0xffe5e7eb);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector d){
                float old=scale;scale=Math.max(fitScale*.75f,Math.min(fitScale*5,scale*d.getScaleFactor()));
                tx=d.getFocusX()-(d.getFocusX()-tx)*scale/old;ty=d.getFocusY()-(d.getFocusY()-ty)*scale/old;clamp();invalidate();return true;
            }
        });
    }
    public void document(Notebook n,int index,Bitmap b){finishGesture();notebook=n;page=index;bitmap=b;pendingViewport=null;sceneDirty=true;selection=null;cutY=-1;reset();}
    public void clear(){finishGesture();notebook=null;bitmap=null;pendingViewport=null;sceneDirty=true;selection=null;cutY=-1;invalidate();}
    public void refresh(){sceneDirty=true;clamp();invalidate();}
    public void reset(){if(notebook==null||getWidth()==0)return;fitScale=(getWidth()-32)/((float)notebook.pages.get(page).width);scale=fitScale;tx=16;ty=16;clamp();invalidate();}
    public void jump(double y){if(notebook==null)return;ty=64-(float)y*scale;clamp();invalidate();}
    public void setMode(Mode m){finishGesture();mode=m;selection=null;cutY=-1;invalidate();}
    protected void onSizeChanged(int w,int h,int ow,int oh){
        if(notebook==null)return;
        if(ow==0){reset();if(pendingViewport!=null){Viewport saved=pendingViewport;pendingViewport=null;restoreViewport(saved);}return;}
        if(w!=ow){float next=(w-32)/(float)notebook.pages.get(page).width;float ratio=next/fitScale;
            scale*=ratio;fitScale=next;tx=w/2f-(ow/2f-tx)*ratio;}
        clamp();if(pendingViewport!=null&&h>0){Viewport saved=pendingViewport;pendingViewport=null;restoreViewport(saved);}invalidate();
    }
    private void clamp(){sceneDirty=true;if(notebook==null)return;float w=(float)notebook.pages.get(page).width*scale,h=(float)notebook.pages.get(page).layout.height()*scale;
        tx=w<getWidth()? (getWidth()-w)/2:Math.min(16,Math.max(getWidth()-w-16,tx));
        ty=h<getHeight()?16:Math.min(16,Math.max(getHeight()-h-16,ty));
    }
    protected void onDraw(Canvas c){super.onDraw(c);if(notebook==null||bitmap==null){paint.setColor(0xff6b7c75);paint.setTextSize(16*getResources().getDisplayMetrics().scaledDensity);c.drawText("正在载入页面…",24,60,paint);return;}
        Notebook.PageData p=notebook.pages.get(page);
        if(getWidth()==0||getHeight()==0)return;
        if(settled==null||settled.getWidth()<getWidth()||settled.getHeight()<getHeight()){
            // Reuse a window-sized buffer while panels animate; avoid a bitmap allocation on every frame.
            android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
            if(settled!=null)settled.recycle();settled=Bitmap.createBitmap(Math.max(getWidth(),metrics.widthPixels),Math.max(getHeight(),metrics.heightPixels),Bitmap.Config.ARGB_8888);sceneDirty=true;
        }
        if(sceneDirty){settled.eraseColor(0xffe5e7eb);Canvas base=new Canvas(settled);base.translate(tx,ty);base.scale(scale,scale);paint.setColor(Color.WHITE);base.drawRect(0,0,(float)p.width,(float)p.layout.height(),paint);base.clipRect(0,0,(float)p.width,(float)p.layout.height());PdfEngine.draw(base,bitmap,notebook,page,true,active);sceneDirty=false;}
        if(dragGap!=-2){
            c.save();c.translate(tx,ty);c.scale(scale,scale);
            paint.setColor(Color.WHITE);c.drawRect(0,0,(float)p.width,(float)(p.layout.height()+dragHeight-dragOld),paint);
            c.save();c.clipRect(0,0,(float)p.width,(float)(dragTop+Math.min(dragOld,dragHeight)));PdfEngine.draw(c,bitmap,notebook,page,true,null);c.restore();
            c.save();c.clipRect(0,(float)(dragTop+dragHeight),(float)p.width,(float)(p.layout.height()+dragHeight-dragOld));c.translate(0,(float)(dragHeight-dragOld));PdfEngine.draw(c,bitmap,notebook,page,true,null);c.restore();c.restore();
        }else c.drawBitmap(settled,0,0,null);
        c.save();c.translate(tx,ty);c.scale(scale,scale);c.clipRect(0,0,(float)p.width,(float)p.layout.height());
        if(active!=null){paint.setStyle(active.pressureSensitive?Paint.Style.FILL:Paint.Style.STROKE);paint.setColor(active.color);paint.setStrokeWidth((float)active.width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(active.pressureSensitive)c.drawPath(activePath,paint);else if(active.points.size()==1)c.drawPoint((float)active.points.get(0).x,(float)active.points.get(0).y,paint);else c.drawPath(activePath,paint);paint.setStyle(Paint.Style.FILL);
        }
        if(cutY>=0){paint.setColor(0xff0f766e);paint.setStrokeWidth(2/scale);c.drawLine(0,cutY,(float)p.width,cutY,paint);}
        if(selection!=null){paint.setStyle(Paint.Style.FILL);paint.setColor(0x3336a18b);c.drawRect(selection,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2/scale);paint.setColor(0xff0f766e);c.drawRect(selection,paint);}
        c.restore();paint.setStyle(Paint.Style.FILL);
        if(mode==Mode.SPACE){
            if(dragGap!=-2)drawGapGuide(c,dragTop,dragHeight,true);
            else for(int i=0;i<p.layout.gaps.size();i++)drawGapGuide(c,p.layout.gapTop(i),p.layout.gaps.get(i).height,false);
        }
    }
    private void drawGapGuide(Canvas c,double top,double height,boolean preview){
        float left=tx,right=tx+(float)notebook.pages.get(page).width*scale,bottom=ty+(float)(top+height)*scale;
        float density=getResources().getDisplayMetrics().density;
        paint.setColor(0x120078d4);c.drawRect(left,ty+(float)top*scale,right,bottom,paint);
        paint.setColor(0xff0078d4);paint.setStrokeWidth(2*density);c.drawLine(left,bottom,right,bottom,paint);
        float handleWidth=156*density,handleHeight=40*density;
        float edge=Math.min(getWidth()-8*density,right-8*density);
        RectF pill=new RectF(Math.max(8*density,edge-handleWidth),bottom-handleHeight/2,edge,bottom+handleHeight/2);
        c.drawRoundRect(pill,6*density,6*density,paint);paint.setColor(Color.WHITE);paint.setTextSize(12*density);
        c.drawText(String.format(java.util.Locale.CHINA,"↕ %.1f mm · %s",height*25.4/72,preview?"松手应用":"拖动调节"),pill.left+10*density,bottom+4*density,paint);
    }
    private void cancelGapDrag(){dragGap=-2;cutY=-1;invalidate();}
    private boolean beginGapDrag(float x,float y){
        Notebook.PageData p=notebook.pages.get(page);float slop=24*getResources().getDisplayMetrics().density/scale;
        for(int i=p.layout.gaps.size()-1;i>=0;i--){double top=p.layout.gapTop(i),height=p.layout.gaps.get(i).height;
            if(x>=0&&x<=p.width&&Math.abs(y-top-height)<=slop){dragGap=i;dragTop=top;dragOld=height;dragHeight=height;dragStart=y;return true;}
        }
        if(!inside(x,y))return false;
        // A tap within existing whitespace selects its lower resize handle.
        for(int i=0;i<p.layout.gaps.size();i++){double top=p.layout.gapTop(i),height=p.layout.gaps.get(i).height;
            if(y>=top&&y<=top+height){dragGap=i;dragTop=top;dragOld=height;dragHeight=height;dragStart=y;return true;}}
        dragGap=-1;dragTop=y;dragOld=0;dragStart=y;
        dragHeight=Math.min(1,Math.min(20000,p.layout.sourceHeight*8)-p.layout.height());if(dragHeight<1){cancelGapDrag();return false;}return true;
    }
    private boolean inside(float x,float y){return notebook!=null&&x>=0&&x<=notebook.pages.get(page).width&&y>=0&&y<=notebook.pages.get(page).layout.height();}
    private void checkpoint(){try{notebook.checkpoint();}catch(Exception e){throw new IllegalStateException(e);}}
    public boolean stylusRecent(){return stylusDown||android.os.SystemClock.uptimeMillis()-lastStylus<500;}
    public boolean onHoverEvent(MotionEvent e){if(e.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS)lastStylus=e.getEventTime();return true;}
    private void cancelInk(){
        if(eraseBefore!=null&&notebook!=null)notebook.pages.get(page).strokes=eraseBefore;
        eraseBefore=null;editing=false;active=null;activePath=null;liveStroke=null;activePointer=-1;stylusDown=false;sceneDirty=true;selecting=false;cancelGapDrag();invalidate();
    }
    public void finishGesture(){
        if(editing&&notebook!=null){
            if(active!=null){liveStroke.finish();checkpoint();notebook.pages.get(page).strokes.add(active);}
            else if(eraseBefore!=null){java.util.List<Ink> after=notebook.pages.get(page).strokes;notebook.pages.get(page).strokes=eraseBefore;checkpoint();notebook.pages.get(page).strokes=after;}
            editing=false;active=null;activePath=null;liveStroke=null;eraseBefore=null;sceneDirty=true;if(listener!=null)listener.changed();
        }
        activePointer=-1;selecting=false;stylusDown=false;cancelGapDrag();
    }
    public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled()||notebook==null||bitmap==null)return true;
        int action=e.getActionMasked(),index=0;boolean stylus=false;
        for(int i=0;i<e.getPointerCount();i++){int type=e.getToolType(i);if(type==MotionEvent.TOOL_TYPE_STYLUS||type==MotionEvent.TOOL_TYPE_ERASER){index=i;stylus=true;break;}}
        if(action==MotionEvent.ACTION_CANCEL){if(stylusDown&&!stylus)return true;cancelInk();selection=null;return true;}
        if(stylus){
            lastStylus=e.getEventTime();int pointer=e.getPointerId(index);
            if((action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_UP)&&e.getActionIndex()==index){
                if((e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){cancelInk();return true;}action=MotionEvent.ACTION_UP;
            }else if(action==MotionEvent.ACTION_POINTER_DOWN&&e.getActionIndex()==index){cancelInk();action=MotionEvent.ACTION_DOWN;}
            else if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP)return true;
            if(action==MotionEvent.ACTION_DOWN&&activePointer!=-1&&activePointer!=pointer)cancelInk();
            if(activePointer!=-1&&activePointer!=pointer)return true;
            stylusDown=action!=MotionEvent.ACTION_UP;
        }else{
            if(stylusDown)return true;
            if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){cancelInk();return true;}
            if(palmProtection&&(e.getSize(index)>.18f||stylusRecent())){if(editing)cancelInk();return true;}
            boolean inkMode=mode==Mode.PEN||mode==Mode.HIGHLIGHT||mode==Mode.ERASE;
            if(palmProtection&&inkMode&&!fingerInk&&e.getPointerCount()==1){pinch.onTouchEvent(e);lastX=e.getX();lastY=e.getY();return true;}
            pinch.onTouchEvent(e);
        }
        int tool=e.getToolType(index);
        float x=(e.getX(index)-tx)/scale,y=(e.getY(index)-ty)/scale;
        if(action==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();startX=x;startY=y;moved=false;multiGesture=false;
            boolean writing=stylus||fingerInk;activePointer=e.getPointerId(index);
            if(inside(x,y)&&(mode==Mode.PEN||mode==Mode.HIGHLIGHT||mode==Mode.ERASE)&&writing){
                editing=true;
                if(mode!=Mode.ERASE&&tool!=MotionEvent.TOOL_TYPE_ERASER){active=new Ink(mode==Mode.HIGHLIGHT?(penColor & 0x00ffffff)|0x66000000:penColor,mode==Mode.HIGHLIGHT?penWidth*4:penWidth);Ink.Point point=new Ink.Point(x,y);active.pressureSensitive=pressureInk&&stylus&&hasPressure(e,index)&&mode==Mode.PEN;active.streamlined=smoothInk&&mode==Mode.PEN;sample(point,e,index,-1);active.points.add(point);liveStroke=new InkBrush.LiveStroke(active);liveStroke.add(point);activePath=liveStroke.path;sceneDirty=true;}
                else {eraseBefore=new java.util.ArrayList<>(notebook.pages.get(page).strokes);erase(x,y);sceneDirty=true;}
            }else if(inside(x,y)&&mode==Mode.CLIP){selecting=true;selection=new RectF(x,y,x,y);}
            else if(mode==Mode.SPACE){selecting=beginGapDrag(x,y);}
            invalidate();return true;
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN){multiGesture=true;cancelInk();lastX=(e.getX(0)+e.getX(1))/2;lastY=(e.getY(0)+e.getY(1))/2;selection=null;cutY=-1;return true;}
        if(action==MotionEvent.ACTION_MOVE){
            if(Math.hypot(x-startX,y-startY)>5)moved=true;
            if(!stylus&&e.getPointerCount()>1){float cx=(e.getX(0)+e.getX(1))/2,cy=(e.getY(0)+e.getY(1))/2;tx+=cx-lastX;ty+=cy-lastY;lastX=cx;lastY=cy;clamp();invalidate();return true;}
            if(editing){if(active!=null){for(int i=0;i<e.getHistorySize();i++)addPoint((e.getHistoricalX(index,i)-tx)/scale,(e.getHistoricalY(index,i)-ty)/scale,e,index,i);addPoint(x,y,e,index,-1);}else {erase(x,y);sceneDirty=true;}}
            else if(selecting&&mode==Mode.SPACE){
                PageLayout layout=notebook.pages.get(page).layout;
                double limit=Math.min(20000,layout.sourceHeight*8)-layout.height()+dragOld;
                dragHeight=Math.max(1,Math.min(limit,dragGap<0?y-dragTop:dragOld+y-dragStart));
            }
            else if(selecting&&mode==Mode.CLIP)selection=new RectF(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y));
            else{tx+=e.getX()-lastX;ty+=e.getY()-lastY;clamp();}
            lastX=e.getX();lastY=e.getY();invalidate();return true;
        }
        if(action==MotionEvent.ACTION_UP){
            if(editing){if(active!=null)addPoint(x,y,e,index,-1);finishGesture();}
            else if(selecting&&mode==Mode.SPACE&&dragGap!=-2&&listener!=null){
                int gapIndex=dragGap;double top=dragTop,height=dragHeight,old=dragOld;
                if(gapIndex<0&&!moved){PageLayout layout=notebook.pages.get(page).layout;height=Math.min(96,Math.min(20000,layout.sourceHeight*8)-layout.height());}
                cancelGapDrag();
                if(gapIndex<0)listener.space(top,height);else if(Math.abs(height-old)>.01)listener.resizeSpace(gapIndex,height);
            }
            else if(selecting&&mode==Mode.CLIP&&selection!=null&&selection.width()>8&&selection.height()>8&&listener!=null){
                RectF r=new RectF(selection);r.intersect(0,0,(float)notebook.pages.get(page).width,(float)notebook.pages.get(page).layout.height());listener.clip(r);
            }else if(mode==Mode.TEXT&&!moved&&!multiGesture&&inside(x,y)&&listener!=null)listener.text(x,y);
            selecting=false;cancelGapDrag();selection=null;invalidate();performClick();return true;
        }
        return true;
    }
    private static boolean hasPressure(MotionEvent event,int index){
        float pressure=event.getPressure(index);
        if(Float.isFinite(pressure)&&pressure>0&&pressure<1)return true;
        InputDevice device=event.getDevice();if(device==null||!device.supportsSource(InputDevice.SOURCE_STYLUS))return false;
        InputDevice.MotionRange range=device.getMotionRange(MotionEvent.AXIS_PRESSURE,event.getSource());
        return range!=null&&range.getMax()>range.getMin();
    }
    private static void sample(Ink.Point point,MotionEvent e,int index,int history){
        point.t=history<0?e.getEventTime():e.getHistoricalEventTime(history);
        point.pressure=history<0?e.getPressure(index):e.getHistoricalPressure(index,history);
        if(!Float.isFinite(point.pressure))point.pressure=1;
        point.tilt=history<0?e.getAxisValue(MotionEvent.AXIS_TILT,index):e.getHistoricalAxisValue(MotionEvent.AXIS_TILT,index,history);
        point.orientation=history<0?e.getOrientation(index):e.getHistoricalOrientation(index,history);
    }
    private void addPoint(float x,float y,MotionEvent e,int index,int history){
        if(inside(x,y)){Ink.Point last=active.points.get(active.points.size()-1);long time=history<0?e.getEventTime():e.getHistoricalEventTime(history);
            if(time>=last.t){Ink.Point pt=new Ink.Point(x,y);sample(pt,e,index,history);
                if(pt.t!=last.t||pt.x!=last.x||pt.y!=last.y||pt.pressure!=last.pressure||pt.tilt!=last.tilt||pt.orientation!=last.orientation){active.points.add(pt);liveStroke.add(pt);}
            }
        }
    }
    private void erase(float x,float y){Iterator<Ink> it=notebook.pages.get(page).strokes.iterator();while(it.hasNext())if(it.next().near(x,y,10/scale))it.remove();}
    protected void onDetachedFromWindow(){if(settled!=null){settled.recycle();settled=null;}super.onDetachedFromWindow();}
    public boolean performClick(){super.performClick();return true;}
}
