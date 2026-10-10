package cn.jiang.opennote;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Portable, bounded streaming container. The Android adapter owns database commit. */
public final class BackupArchive {
    public static final int VERSION=1;
    public static final long JSON_LIMIT=16L*1024*1024;
    public interface Progress { void update(String path,long bytes) throws IOException; }
    public static final class Limits {
        public final long totalBytes; public final int entries; public final double ratio;
        public Limits(long bytes,int entries,double ratio){this.totalBytes=bytes;this.entries=entries;this.ratio=ratio;}
        public static Limits standard(){return new Limits(16L*1024*1024*1024,100000,200);}
    }
    private static void require(boolean value,String message) throws IOException {if(!value)throw new IOException(message);}
    public static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    public static String sha256(File file) throws IOException {
        MessageDigest d=digest();try(InputStream in=new BufferedInputStream(new FileInputStream(file))){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)d.update(buf,0,n);}return hex(d.digest());
    }
    public static void safeName(String name) throws IOException {
        require(name!=null && name.length()<240 && !name.startsWith("/") && !name.contains("\\") && !name.contains(":"),"备份路径不安全");
        for(String part:name.split("/",-1))require(!part.isEmpty()&&!part.equals(".")&&!part.equals(".."),"备份路径不安全");
        require(name.equals("manifest.json")||name.equals("library.json")||name.matches("(documents|annotations|layouts|settings|checksums)/[A-Za-z0-9_./-]+"),"备份包含未知路径");
    }
    private static void collect(File root,File dir,Map<String,File> result) throws IOException {
        File[] files=dir.listFiles();require(files!=null,"无法读取备份文件");Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files){require(file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"备份路径不安全");
            if(file.isDirectory())collect(root,file,result);else{String path=root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');safeName(path);if(!path.equals("manifest.json"))result.put(path,file);}}
    }
    public static void write(File staging,File target,Progress progress) throws Exception {
        Map<String,File> files=new LinkedHashMap<>();collect(staging,staging,files);
        require(files.containsKey("library.json")&&files.containsKey("settings/preferences.json")&&files.containsKey("checksums/algorithm.txt"),"备份索引缺失");
        require(files.size()<100000,"备份条目太多");JSONArray records=new JSONArray();long total=0;
        try(FileOutputStream raw=new FileOutputStream(target);ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(raw))){
            // ZIP64 is emitted by the platform implementation for large entries / central directory.
            out.setLevel(1);byte[] buffer=new byte[65536];
            for(Map.Entry<String,File> entry:files.entrySet()){
                progress.update(entry.getKey(),total);out.putNextEntry(new ZipEntry(entry.getKey()));MessageDigest d=digest();long size=0;
                try(InputStream in=new BufferedInputStream(new FileInputStream(entry.getValue()))){int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);d.update(buffer,0,n);size+=n;total+=n;progress.update(entry.getKey(),total);}}
                out.closeEntry();records.put(new JSONObject().put("path",entry.getKey()).put("size",size).put("sha256",hex(d.digest())));
            }
            byte[] manifest=new JSONObject().put("format","OpenNote").put("version",VERSION).put("created",System.currentTimeMillis()).put("totalBytes",total).put("files",records).toString().getBytes(StandardCharsets.UTF_8);
            require(manifest.length<=JSON_LIMIT,"备份清单太大");out.putNextEntry(new ZipEntry("manifest.json"));out.write(manifest);out.closeEntry();out.finish();out.flush();raw.getFD().sync();
        }catch(Exception e){target.delete();throw e;}
    }
    private static byte[] readBounded(InputStream in,long max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){require(out.size()+(long)n<=max,"备份元数据过大");out.write(b,0,n);}return out.toByteArray();
    }
    public static JSONObject readJson(File file) throws Exception {try(InputStream in=new FileInputStream(file)){return new JSONObject(new String(readBounded(in,JSON_LIMIT),StandardCharsets.UTF_8));}}
    /** No live library writes. Caller must discard staging on any validation failure. */
    public static JSONObject extract(File archive,File staging,Limits limits,Progress progress) throws Exception {
        require(!staging.exists(),"恢复临时目录已存在");require(staging.mkdirs(),"无法创建恢复临时目录");
        try(ZipFile zip=new ZipFile(archive)){
            Map<String,ZipEntry> entries=new HashMap<>();Enumeration<? extends ZipEntry> enumeration=zip.entries();long declared=0;
            while(enumeration.hasMoreElements()){
                ZipEntry e=enumeration.nextElement();safeName(e.getName());require(!e.isDirectory(),"备份包含未知目录条目");require(entries.put(e.getName(),e)==null,"备份包含重复条目");
                require(entries.size()<=limits.entries,"备份条目太多");long size=e.getSize(),compressed=e.getCompressedSize();require(size>=0&&compressed>=0,"备份大小未知");
                require(size<=limits.totalBytes-declared,"备份解压大小超过限制");declared+=size;
                require(size<=1024*1024||size/(double)Math.max(1,compressed)<=limits.ratio,"备份压缩比异常");
            }
            ZipEntry manifestEntry=entries.remove("manifest.json");require(manifestEntry!=null && manifestEntry.getSize()<=JSON_LIMIT,"备份清单缺失或过大");
            JSONObject manifest;try(InputStream in=zip.getInputStream(manifestEntry)){manifest=new JSONObject(new String(readBounded(in,JSON_LIMIT),StandardCharsets.UTF_8));}
            require(manifest.getString("format").equals("OpenNote")&&manifest.getInt("version")==VERSION,"备份版本不兼容");
            JSONArray records=manifest.getJSONArray("files");require(records.length()==entries.size(),"备份清单与文件不一致");
            require(declared+16L*1024*1024<staging.getUsableSpace(),"恢复空间不足");Set<String> seen=new HashSet<>();long total=0;byte[] b=new byte[65536];
            for(int i=0;i<records.length();i++){
                JSONObject r=records.getJSONObject(i);String path=r.getString("path");safeName(path);ZipEntry e=entries.get(path);require(e!=null&&seen.add(path),"备份清单包含重复或缺失条目");
                long expected=r.getLong("size");String hash=r.getString("sha256");require(expected==e.getSize()&&hash.matches("[0-9a-f]{64}"),"备份清单大小或哈希无效");
                File file=new File(staging,path);require(file.getCanonicalPath().startsWith(staging.getCanonicalPath()+File.separator),"备份路径不安全");require(file.getParentFile().isDirectory()||file.getParentFile().mkdirs(),"无法创建恢复目录");
                MessageDigest d=digest();long count=0;progress.update(path,total);
                try(InputStream in=zip.getInputStream(e);FileOutputStream out=new FileOutputStream(file)){int n;while((n=in.read(b))!=-1){require(n<=expected-count&&n<=limits.totalBytes-total,"备份解压大小超出清单");require(file.getUsableSpace()>n+16L*1024*1024,"恢复空间不足");out.write(b,0,n);d.update(b,0,n);count+=n;total+=n;progress.update(path,total);}out.getFD().sync();}
                require(count==expected&&hex(d.digest()).equals(hash),"备份文件校验失败");
            }
            require(total==manifest.getLong("totalBytes"),"备份总大小不一致");require(seen.contains("library.json")&&seen.contains("settings/preferences.json")&&seen.contains("checksums/algorithm.txt"),"备份索引缺失");
            return manifest;
        }
    }
}
