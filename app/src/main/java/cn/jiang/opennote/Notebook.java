package cn.jiang.opennote;

import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Notebook {
    public static final class PageData {
        public String id=UUID.randomUUID().toString(); public boolean measured=true,loaded=true; public double width; public PageLayout layout;
        public List<Ink> strokes=new ArrayList<>();
        public PageData(double width,double height){this.width=width;layout=new PageLayout(height);}
    }
    public static final class Card {
        public String id=UUID.randomUUID().toString(),text="",image="";
        public int page; public double x,y;
    }
    public File directory; public String title; public int currentPage;
    public final List<PageData> pages=new ArrayList<>();
    public final List<Card> cards=new ArrayList<>();
    public final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    public Notebook(File directory,String title){this.directory=directory;this.title=title;}
    private String pageSnapshot(int index) throws JSONException {
        Notebook n=new Notebook(directory,title);n.pages.add(pages.get(index));
        for(Card c:cards)if(c.page==index)n.cards.add(c);
        return new JSONObject(n.json()).put("targetPage",index).toString();
    }
    private void applySnapshot(String value) throws JSONException {
        int index=new JSONObject(value).getInt("targetPage");Notebook n=new Notebook(directory,title);n.restore(value);
        pages.set(index,n.pages.get(0));cards.removeIf(c->c.page==index);cards.addAll(n.cards);currentPage=index;
    }
    public void checkpoint() throws JSONException {
        String value=pageSnapshot(currentPage);undo.push(value);redo.clear();
        long bytes=0;java.util.Iterator<String> it=undo.iterator();while(it.hasNext()){bytes+=it.next().length()*2L;if(bytes>16*1024*1024)it.remove();}
        while(undo.size()>30)undo.removeLast();
    }
    public boolean undo() throws JSONException {if(undo.isEmpty())return false;String state=undo.pop();redo.push(pageSnapshot(new JSONObject(state).getInt("targetPage")));applySnapshot(state);return true;}
    public boolean redo() throws JSONException {if(redo.isEmpty())return false;String state=redo.pop();undo.push(pageSnapshot(new JSONObject(state).getInt("targetPage")));applySnapshot(state);return true;}
    public void insert(int page,double y,double size) throws JSONException {
        PageData p=pages.get(page);
        if(p.layout.height()+size>Math.min(20000,p.layout.sourceHeight*8))throw new IllegalArgumentException("页面过长，请减少留白高度");
        checkpoint();p.layout.insert(y,size);p.strokes=Ink.insertSpace(p.strokes,y,size);
        for(Card c:cards)if(c.page==page&&c.y>=y)c.y+=size;
    }
    /** Expanding whitespace is a single undoable command; validate before recording history. */
    public void growGap(int index,double height) throws JSONException {
        PageData p=pages.get(currentPage);double old=p.layout.gaps.get(index).height;
        if(!Double.isFinite(height)||height<old)throw new IllegalArgumentException("Expected growing gap");
        PageLayout validation=new PageLayout(p.layout.sourceHeight);
        for(PageLayout.Gap g:p.layout.gaps)validation.gaps.add(new PageLayout.Gap(g.sourceY,g.height));
        validation.resize(index,height);
        if(height==old)return;
        checkpoint();double cut=p.layout.gapTop(index)+old;p.layout.resize(index,height);
        p.strokes=Ink.insertSpace(p.strokes,cut,height-old);
        for(Card c:cards)if(c.page==currentPage&&c.y>=cut)c.y+=height-old;
    }
    public String json() throws JSONException {
        JSONObject root=new JSONObject();root.put("schema",1).put("title",title).put("currentPage",currentPage);
        JSONArray pp=new JSONArray();
        for(PageData p:pages){
            JSONObject j=new JSONObject().put("id",p.id).put("measured",p.measured).put("width",p.width).put("height",p.layout.sourceHeight);
            JSONArray gaps=new JSONArray(),strokes=new JSONArray();
            for(PageLayout.Gap g:p.layout.gaps)gaps.put(new JSONArray().put(g.sourceY).put(g.height));
            for(Ink ink:p.strokes){JSONArray points=new JSONArray();for(Ink.Point pt:ink.points)points.put(new JSONArray().put(pt.x).put(pt.y).put(pt.t).put(pt.pressure).put(pt.tilt).put(pt.orientation));
                strokes.put(new JSONObject().put("id",ink.id).put("created",ink.created).put("color",ink.color).put("width",ink.width).put("pressureSensitive",ink.pressureSensitive).put("streamlined",ink.streamlined).put("points",points));}
            j.put("gaps",gaps).put("strokes",strokes);pp.put(j);
        }
        JSONArray cc=new JSONArray();for(Card c:cards)cc.put(new JSONObject().put("id",c.id).put("text",c.text).put("image",c.image).put("page",c.page).put("x",c.x).put("y",c.y));
        return root.put("pages",pp).put("cards",cc).toString();
    }
    public void restore(String json) throws JSONException {
        JSONObject root=new JSONObject(json);if(root.getInt("schema")!=1)throw new JSONException("不支持的笔记版本");
        title=root.getString("title");pages.clear();cards.clear();JSONArray pp=root.getJSONArray("pages");
        for(int i=0;i<pp.length();i++){
            JSONObject j=pp.getJSONObject(i);PageData p=new PageData(j.getDouble("width"),j.getDouble("height"));
            p.id=j.optString("id",p.id);p.measured=j.optBoolean("measured",true);JSONArray gg=j.getJSONArray("gaps");for(int k=0;k<gg.length();k++){JSONArray g=gg.getJSONArray(k);p.layout.gaps.add(new PageLayout.Gap(g.getDouble(0),g.getDouble(1)));}
            JSONArray ss=j.getJSONArray("strokes");for(int k=0;k<ss.length();k++){
                JSONObject s=ss.getJSONObject(k);Ink ink=new Ink(s.getInt("color"),s.getDouble("width"));ink.pressureSensitive=s.optBoolean("pressureSensitive",false);ink.streamlined=s.optBoolean("streamlined",false);ink.id=s.optString("id",ink.id);ink.created=s.optLong("created",ink.created);JSONArray pts=s.getJSONArray("points");
                for(int t=0;t<pts.length();t++){JSONArray pt=pts.getJSONArray(t);Ink.Point point=new Ink.Point(pt.getDouble(0),pt.getDouble(1));point.t=pt.optLong(2,0);point.pressure=(float)pt.optDouble(3,1);point.tilt=(float)pt.optDouble(4,0);point.orientation=(float)pt.optDouble(5,0);ink.points.add(point);}p.strokes.add(ink);
            }pages.add(p);
        }
        JSONArray cc=root.getJSONArray("cards");for(int i=0;i<cc.length();i++){JSONObject j=cc.getJSONObject(i);Card c=new Card();c.id=j.getString("id");c.text=j.getString("text");c.image=j.getString("image");c.page=j.getInt("page");c.x=j.getDouble("x");c.y=j.getDouble("y");cards.add(c);}
        currentPage=Math.max(0,Math.min(pages.size()-1,root.optInt("currentPage",0)));
    }
    public static Notebook load(File dir) throws Exception {
        AtomicFile atomic=new AtomicFile(new File(dir,"state.json"));
        String json=new String(atomic.readFully(),StandardCharsets.UTF_8);Notebook n=new Notebook(dir,"");n.restore(json);return n;
    }
    public static void save(File dir,String json) throws IOException {
        AtomicFile atomic=new AtomicFile(new File(dir,"state.json"));FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(json.getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(IOException e){if(out!=null)atomic.failWrite(out);throw e;}
    }
}
