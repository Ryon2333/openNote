package cn.jiang.opennote;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class RasterPdfTest {
    @Test public void writesMultiPageWithExactMediaBoxesAndXrefOffsets()throws Exception {
        byte[] jpeg;try(InputStream input=getClass().getResourceAsStream("/tile.jpg")){assertNotNull(input);jpeg=input.readAllBytes();}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(RasterPdf writer=new RasterPdf(bytes)){writer.addPage(595,1068.77165,32,60,20,(i,y,h)->jpeg);writer.addPage(842,595,32,20,20,(i,y,h)->jpeg);writer.finish();}
        String pdf=bytes.toString("ISO-8859-1");assertTrue(pdf.startsWith("%PDF-1.4"));assertTrue(pdf.contains("/Count 2"));assertTrue(pdf.contains("/MediaBox [0 0 595.00000 1068.77165]"));
        int xref=pdf.lastIndexOf("xref\n0 ");String[] table=pdf.substring(xref).split("\n");int count=Integer.parseInt(table[1].split(" ")[1]);
        for(int i=1;i<count;i++){int offset=Integer.parseInt(table[i+2].substring(0,10));assertTrue(pdf.substring(offset).startsWith(i+" 0 obj\n"));}
        File f=new File("build/host-fixtures/streaming.pdf");f.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes.toByteArray());}
    }
}
