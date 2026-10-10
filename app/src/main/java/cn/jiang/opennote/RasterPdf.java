package cn.jiang.opennote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Streaming PDF writer: memory grows with object offsets, not full rasterized documents. */
public final class RasterPdf implements Closeable {
    public interface Tiles { byte[] jpeg(int tile,int pixelY,int pixelHeight) throws Exception; }
    private final OutputStream out; private long position; private final List<Long> offsets=new ArrayList<>();
    private final List<Integer> pages=new ArrayList<>();private boolean finished;
    public RasterPdf(OutputStream out)throws IOException{this.out=out;offsets.add(0L);reserve();reserve();write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");}
    private int reserve(){offsets.add(-1L);return offsets.size()-1;}
    private void write(String s)throws IOException{write(s.getBytes(StandardCharsets.ISO_8859_1));}
    private void write(byte[] b)throws IOException{out.write(b);position+=b.length;}
    private void start(int id)throws IOException{offsets.set(id,position);write(id+" 0 obj\n");}
    private void object(int id,String value)throws IOException{start(id);write(value+"\nendobj\n");}
    private void stream(int id,String dict,byte[] bytes)throws IOException{start(id);write("<< "+dict+" /Length "+bytes.length+" >>\nstream\n");write(bytes);write("\nendstream\nendobj\n");}
    private static String number(double d){return String.format(Locale.US,"%.5f",d);}
    public void addPage(double width,double height,int pixelWidth,int pixelHeight,int tileHeight,Tiles source)throws Exception{
        int page=reserve(),content=reserve();pages.add(page);
        StringBuilder resources=new StringBuilder(),commands=new StringBuilder();
        int index=0;
        for(int y=0;y<pixelHeight;y+=tileHeight){
            int h=Math.min(tileHeight,pixelHeight-y),image=reserve();byte[] jpeg=source.jpeg(index,y,h);
            stream(image,"/Type /XObject /Subtype /Image /Width "+pixelWidth+" /Height "+h+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode",jpeg);
            String name="T"+index++;resources.append('/').append(name).append(' ').append(image).append(" 0 R ");
            double top=height*y/pixelHeight,bottom=height*(y+h)/pixelHeight;
            commands.append("q ").append(number(width)).append(" 0 0 ").append(number(bottom-top)).append(" 0 ").append(number(height-bottom)).append(" cm /").append(name).append(" Do Q\n");
        }
        stream(content,"",commands.toString().getBytes(StandardCharsets.US_ASCII));
        object(page,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+number(width)+" "+number(height)+"] /Resources << /XObject << "+resources+" >> >> /Contents "+content+" 0 R >>");
    }
    public void finish()throws IOException{
        if(finished)return;object(1,"<< /Type /Catalog /Pages 2 0 R >>");StringBuilder kids=new StringBuilder();for(int p:pages)kids.append(p).append(" 0 R ");
        object(2,"<< /Type /Pages /Count "+pages.size()+" /Kids ["+kids+"] >>");long xref=position;
        write("xref\n0 "+offsets.size()+"\n0000000000 65535 f \n");
        for(int i=1;i<offsets.size();i++){if(offsets.get(i)<0)throw new IOException("Unwritten PDF object");write(String.format(Locale.US,"%010d 00000 n \n",offsets.get(i)));}
        write("trailer\n<< /Size "+offsets.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");out.flush();finished=true;
    }
    public void close()throws IOException{out.close();}
}
