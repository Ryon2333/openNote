package cn.jiang.opennote;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import static org.junit.Assert.*;

public class BackupArchiveTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private static final BackupArchive.Progress NOOP=(p,n)->{};
 private File stage() throws Exception {
  File s=temp.newFolder();new File(s,"documents/id").mkdirs();Files.write(new File(s,"documents/id/original.pdf").toPath(),new byte[]{1,2,3,4});
  Files.write(new File(s,"library.json").toPath(),"{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));new File(s,"settings").mkdirs();Files.write(new File(s,"settings/preferences.json").toPath(),"{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  new File(s,"checksums").mkdirs();Files.write(new File(s,"checksums/algorithm.txt").toPath(),"SHA-256".getBytes(java.nio.charset.StandardCharsets.UTF_8));return s;
 }
 private File output(){return new File(temp.getRoot(),"out.zip");}
 private File extracted(){return new File(temp.getRoot(),"extracted");}
 @Test public void roundTripHashesAndVersion() throws Exception {
  File s=stage(),out=output();BackupArchive.write(s,out,NOOP);JSONObject m=BackupArchive.extract(out,extracted(),BackupArchive.Limits.standard(),NOOP);
  assertEquals(1,m.getInt("version"));assertArrayEquals(Files.readAllBytes(new File(s,"documents/id/original.pdf").toPath()),Files.readAllBytes(new File(extracted(),"documents/id/original.pdf").toPath()));
 }
 @Test public void rejectsTraversalAbsoluteBackslashAndUnknownPaths() throws Exception {
  for(String path:new String[]{"documents/../secret","/secret","documents/id\\secret","documents/C:/a","documents//a","cache/private","layouts/./x"}){
   try{BackupArchive.safeName(path);fail(path);}catch(IOException expected){}
  }
 }
 private void rewrite(String name,String replacement) throws Exception {
  File old=output(),next=new File(temp.getRoot(),"tampered.zip");
  try(ZipFile z=new ZipFile(old);ZipOutputStream o=new ZipOutputStream(new FileOutputStream(next))){java.util.Enumeration<? extends ZipEntry> entries=z.entries();while(entries.hasMoreElements()){
   ZipEntry e=entries.nextElement();o.putNextEntry(new ZipEntry(e.getName()));if(e.getName().equals(name))o.write(replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8));else try(InputStream in=z.getInputStream(e)){in.transferTo(o);}o.closeEntry();}}
  Files.move(next.toPath(),old.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
 }
 @Test public void rejectsChangedContentWithOriginalHash() throws Exception {
  BackupArchive.write(stage(),output(),NOOP);rewrite("documents/id/original.pdf","abcd");
  try{BackupArchive.extract(output(),extracted(),BackupArchive.Limits.standard(),NOOP);fail();}catch(IOException e){assertTrue(e.getMessage().contains("校验"));}
 }
 @Test public void rejectsFutureVersionBeforeExtractingPayload() throws Exception {
  BackupArchive.write(stage(),output(),NOOP);JSONObject manifest;
  try(ZipFile z=new ZipFile(output())){manifest=new JSONObject(new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  manifest.put("version",99);rewrite("manifest.json",manifest.toString());
  try{BackupArchive.extract(output(),extracted(),BackupArchive.Limits.standard(),NOOP);fail();}catch(IOException e){assertTrue(e.getMessage().contains("版本"));}
  assertFalse(new File(extracted(),"documents").exists());
 }
 @Test public void appliesEntryAndByteBudgets() throws Exception {
  BackupArchive.write(stage(),output(),NOOP);
  for(BackupArchive.Limits limits:new BackupArchive.Limits[]{new BackupArchive.Limits(2,100,200),new BackupArchive.Limits(100000,2,200)}){
   File dir=new File(temp.getRoot(),"budget-"+limits.entries);
   try{BackupArchive.extract(output(),dir,limits,NOOP);fail();}catch(IOException expected){}
  }
 }
 @Test public void cancellationDeletesIncompleteOutput() throws Exception {
  try{BackupArchive.write(stage(),output(),(p,n)->{throw new InterruptedIOException();});fail();}catch(InterruptedIOException expected){}
  assertFalse(output().exists());
 }
 @Test public void rejectsExtremeCompressionRatio() throws Exception {
  File s=stage();Files.write(new File(s,"documents/id/original.pdf").toPath(),new byte[2*1024*1024]);BackupArchive.write(s,output(),NOOP);
  try{BackupArchive.extract(output(),extracted(),new BackupArchive.Limits(10000000,100,10),NOOP);fail();}catch(IOException e){assertTrue(e.getMessage().contains("压缩比"));}
 }
 @Test public void zip64CentralDirectoryRoundTripOver65535Entries() throws Exception {
  File s=stage();File dir=new File(s,"annotations/many");assertTrue(dir.mkdirs());
  for(int i=0;i<65536;i++)assertTrue(new File(dir,i+".ink").createNewFile());
  BackupArchive.write(s,output(),NOOP);
  try(ZipFile zip=new ZipFile(output())){assertEquals(65541,zip.size());assertNotNull(zip.getEntry("annotations/many/65535.ink"));}
  // Presence of ZIP64 end-of-central-directory signature, rather than filename-based assumptions.
  byte[] bytes=Files.readAllBytes(output().toPath());boolean found=false;
  for(int i=bytes.length-22;i>=Math.max(0,bytes.length-1024);i--)if(bytes[i]==0x50&&bytes[i+1]==0x4b&&bytes[i+2]==0x06&&bytes[i+3]==0x06){found=true;break;}
  assertTrue(found);
 }
}
