package cn.jiang.opennote;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.*;

public final class PdfEngine implements AutoCloseable {
    private final PdfRenderer renderer;
    public PdfEngine(File file)throws IOException{renderer=new PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY));}
    public int count(){return renderer.getPageCount();}
    public Notebook.PageData dimensions(int index){try(PdfRenderer.Page p=renderer.openPage(index)){return new Notebook.PageData(p.getWidth(),p.getHeight());}}
    public Bitmap render(int index,int desiredWidth){
        try(PdfRenderer.Page p=renderer.openPage(index)){
            double scale=Math.min((double)desiredWidth/p.getWidth(),Math.sqrt(6000000.0/((double)p.getWidth()*p.getHeight())));
            int w=Math.max(1,(int)Math.ceil(p.getWidth()*scale)),h=Math.max(1,(int)Math.ceil(p.getHeight()*scale));
            Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
            Matrix m=new Matrix();m.setScale((float)w/p.getWidth(),(float)h/p.getHeight());
            p.render(b,null,m,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return b;
        }
    }
    public static void draw(Canvas canvas,Bitmap source,Notebook notebook,int index,boolean showNotePins){
        draw(canvas,source,notebook,index,showNotePins,null);
    }
    public static void draw(Canvas canvas,Bitmap source,Notebook notebook,int index,boolean showNotePins,Ink skip){
        Notebook.PageData page=notebook.pages.get(index);float w=(float)page.width;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        for(PageLayout.Slice s:page.layout.slices()){
            canvas.save();canvas.clipRect(0,(float)s.displayTop,w,(float)(s.displayTop+s.sourceBottom-s.sourceTop));
            RectF target=new RectF(0,(float)(s.displayTop-s.sourceTop),w,(float)(s.displayTop-s.sourceTop+page.layout.sourceHeight));
            canvas.drawBitmap(source,null,target,p);canvas.restore();
        }
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        for(Ink ink:page.strokes)if(ink!=skip)InkBrush.draw(canvas,ink,p);
        for(Notebook.Card card:notebook.cards)if(card.page==index&&card.image.isEmpty()){
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(15,118,110));p.setTextSize(12);
            float x=(float)Math.max(4,Math.min(w-30,card.x)),y=(float)card.y;
            String[] lines=wrap(card.text,p,w-x-8);
            for(String line:lines){y+=16;canvas.drawText(line,x,y,p);}
        }
        if(showNotePins){p.setStyle(Paint.Style.FILL);p.setColor(0xffeaae56);for(Notebook.Card c:notebook.cards)if(c.page==index&&!c.image.isEmpty())canvas.drawCircle(w-12,(float)c.y,5,p);}
    }
    private static String[] wrap(String text,Paint paint,float width){
        java.util.ArrayList<String> lines=new java.util.ArrayList<>();
        for(String paragraph:text.split("\n",-1)){int start=0;if(paragraph.isEmpty())lines.add("");while(start<paragraph.length()){
            int n=Math.max(1,paint.breakText(paragraph,start,paragraph.length(),true,Math.max(20,width),null));lines.add(paragraph.substring(start,start+n));start+=n;
        }}return lines.toArray(new String[0]);
    }
    public static void export(File output,File original,Notebook snapshot,int dpi,Progress progress)throws Exception{
        try(PdfEngine engine=new PdfEngine(original);RasterPdf writer=new RasterPdf(new FileOutputStream(output))){
            for(int i=0;i<snapshot.pages.size();i++){
                final int pageIndex=i;Notebook.PageData p=snapshot.pages.get(i);
                if(!p.measured){Notebook.PageData dims=engine.dimensions(i);p.width=dims.width;p.layout=dims.layout;p.measured=true;}
                progress.loadPage(i,p);
                int desiredWidth=Math.min(2400,Math.max(1,(int)Math.ceil(p.width*dpi/72.0)));final Bitmap source=engine.render(i,desiredWidth);int pw=desiredWidth;
                int ph=Math.max(1,(int)Math.ceil(p.layout.height()*pw/p.width));
                try{writer.addPage(p.width,p.layout.height(),pw,ph,1400,(tile,y,h)->{
                    Bitmap b=Bitmap.createBitmap(pw,h,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
                    try{Canvas c=new Canvas(b);c.translate(0,-y);c.scale((float)(pw/p.width),(float)(ph/p.layout.height()));draw(c,source,snapshot,pageIndex,false);
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!b.compress(Bitmap.CompressFormat.JPEG,95,bytes))throw new IOException("图像编码失败");return bytes.toByteArray();
                    }finally{b.recycle();}
                });}finally{source.recycle();}
                progress.update(i+1,snapshot.pages.size());p.strokes.clear();
            }writer.finish();
        }
    }
    public interface Progress{void update(int done,int total);default void loadPage(int index,Notebook.PageData page) throws Exception {}}
    public void close(){renderer.close();}
}
