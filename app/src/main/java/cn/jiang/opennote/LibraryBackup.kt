package cn.jiang.opennote

import java.io.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Serialized IO worker only. Restore stages everything, then commits all rows in one transaction. */
class LibraryBackup(private val store:NoteStore,private val root:File,private val work:File) {
    data class Preview(val staging:File,val notes:List<Notebook>,val bytes:Long,val created:Long)
    private fun checkCancel(progress:BackupArchive.Progress,path:String,bytes:Long){progress.update(path,bytes)}
    fun export(output:File,preferences:JSONObject,progress:BackupArchive.Progress) {
        val stage=File(work,"backup-${UUID.randomUUID()}").apply{check(mkdirs())}
        try {
            val index=JSONArray();val entries=store.list()
            val estimated=entries.sumOf{File(root,"${it.id}/original.pdf").length()}+store.storedInkBytes()
            require(estimated<=BackupArchive.Limits.standard().totalBytes){"备份超过当前 16 GB 安全限制"}
            require(estimated*2+64L*1024*1024<work.usableSpace){"备份临时空间不足，需要约 ${((estimated*2+64L*1024*1024)/1024/1024)} MB"}
            entries.forEach { entry->
                checkCancel(progress,entry.title,0)
                val n=store.load(root,entry.id)
                val layout=File(stage,"layouts/${entry.id}.json").apply{parentFile!!.mkdirs()}
                layout.writeText(n.json()) // unloaded page ink stays in binary annotations, never JSON
                require(layout.length()<=BackupArchive.JSON_LIMIT){"笔记元数据过大"}
                val source=File(n.directory,"original.pdf");check(source.isFile){"原始 PDF 缺失"}
                val target=File(stage,"documents/${entry.id}/original.pdf").apply{parentFile!!.mkdirs()}
                source.inputStream().use { input->FileOutputStream(target).use { out->copy(input,out,progress,entry.title);out.fd.sync()} }
                n.pages.indices.forEach { page->
                    val ink=File(stage,"annotations/${entry.id}/$page.ink").apply{parentFile!!.mkdirs()}
                    FileOutputStream(ink).use { raw->DataOutputStream(BufferedOutputStream(raw)).use { out->store.writeInk(entry.id,page,out);out.flush();raw.fd.sync()} }
                    progress.update(entry.title,page.toLong())
                }
                index.put(JSONObject().put("id",entry.id).put("pages",n.pages.size))
            }
            File(stage,"library.json").writeText(JSONObject().put("version",1).put("documents",index).put("folders",JSONArray()).toString())
            File(stage,"settings").mkdirs();File(stage,"settings/preferences.json").writeText(preferences.toString())
            File(stage,"checksums").mkdirs();File(stage,"checksums/algorithm.txt").writeText("SHA-256; hashes are recorded in manifest.json\n")
            BackupArchive.write(stage,output,progress)
        }finally{stage.deleteRecursively()}
    }
    fun preview(archive:File,progress:BackupArchive.Progress):Preview {
        val stage=File(work,"restore-${UUID.randomUUID()}")
        try {
            val manifest=BackupArchive.extract(archive,stage,BackupArchive.Limits.standard(),progress)
            val index=BackupArchive.readJson(File(stage,"library.json"));require(index.getInt("version")==1){"笔记索引版本不兼容"}
            require(index.getJSONArray("folders").length()==0){"当前版本不能恢复包含文件夹的备份"}
            val ids=HashSet<String>();val docs=index.getJSONArray("documents");require(docs.length()<=10000){"笔记数量过多"}
            val expected=hashSetOf("library.json","settings/preferences.json","checksums/algorithm.txt")
            val notes=ArrayList<Notebook>()
            for(i in 0 until docs.length()) {
                val entry=docs.getJSONObject(i);val id=entry.getString("id");require(UUID.fromString(id).toString()==id&&ids.add(id)){"笔记 ID 无效或重复"}
                val json=BackupArchive.readJson(File(stage,"layouts/$id.json"))
                val pages=json.getJSONArray("pages");require(pages.length() in 1..100000){"页面数量无效"}
                repeat(pages.length()){require(pages.getJSONObject(it).getJSONArray("strokes").length()==0){"笔迹必须使用二进制文件"}}
                require(json.getJSONArray("cards").length()<=100000){"文字笔记过多"}
                val n=Notebook(File(stage,"documents/$id"),"");n.restore(json.toString())
                require(n.pages.isNotEmpty()&&n.pages.size==entry.getInt("pages")&&n.pages.size<=100000){"页面数量无效"}
                validate(n)
                PdfEngine(File(n.directory,"original.pdf")).use{require(it.count()==n.pages.size){"原始 PDF 页数不一致"}}
                expected.add("layouts/$id.json");expected.add("documents/$id/original.pdf")
                n.pages.indices.forEach { p->
                    val path="annotations/$id/$p.ink";expected.add(path)
                    DataInputStream(BufferedInputStream(FileInputStream(File(stage,path)))).use { input->store.readInk(input){ ink->
                        require(ink.width.isFinite()&&ink.width>0&&ink.width<=1000){"画笔宽度无效"}
                        require(ink.points.all { it.x.isFinite()&&it.y.isFinite()&&it.pressure.isFinite()&&it.tilt.isFinite()&&it.orientation.isFinite() }){"笔迹坐标无效"}
                    } }
                }
                notes.add(n);progress.update(n.title,i.toLong())
            }
            val records=manifest.getJSONArray("files");require(records.length()==expected.size){"备份包含无法恢复的额外文件"}
            repeat(records.length()){require(expected.remove(records.getJSONObject(it).getString("path"))){"备份文件索引不一致"}}
            val prefs=BackupArchive.readJson(File(stage,"settings/preferences.json"));require(prefs.getInt("version")==1){"设置版本不兼容"}
            return Preview(stage,notes,manifest.getLong("totalBytes"),manifest.getLong("created"))
        }catch(e:Exception){stage.deleteRecursively();throw e}
    }
    private fun validate(n:Notebook) {
        val pageIds=HashSet<String>();n.pages.forEach { p->
            require(pageIds.add(p.id)&&p.width.isFinite()&&p.width>0&&p.width<=20000&&p.layout.sourceHeight<=20000){"页面尺寸或 ID 无效"}
            require(p.strokes.isEmpty()){"备份笔迹必须使用二进制文件"}
            var previous=-1.0;p.layout.gaps.forEach { g->require(g.sourceY.isFinite()&&g.sourceY>=0&&g.sourceY<=p.layout.sourceHeight&&g.sourceY>previous&&g.height.isFinite()&&g.height>0){"留白布局无效"};previous=g.sourceY }
            require(p.layout.height().isFinite()&&p.layout.height()<=20000){"页面过长"}
        }
        val cards=HashSet<String>();n.cards.forEach{c->require(cards.add(c.id)&&c.page in n.pages.indices&&c.x.isFinite()&&c.y.isFinite()&&c.image.isEmpty()){"文字卡片数据无效"}}
    }
    /** Merge preserving every existing notebook. Identity is remapped to permit duplicate restores. */
    fun commit(preview:Preview,progress:BackupArchive.Progress):Int {
        val mapped=preview.notes.map{n->n to File(root,UUID.randomUUID().toString())}
        val journal=File(root,".restore-${UUID.randomUUID()}.json")
        val bytes=JSONArray(mapped.map{it.second.name}).toString().toByteArray()
        FileOutputStream(journal).use{it.write(bytes);it.fd.sync()}
        var committed=false
        try {
            mapped.forEach { (n,target)->progress.update(n.title,0);check(n.directory.renameTo(target)){"恢复文件移动失败"} }
            store.transaction {
                mapped.forEachIndexed { i,(n,target)->
                    val old=n.directory.name;n.directory=target;store.create(n)
                    n.pages.indices.forEach { page->
                        DataInputStream(BufferedInputStream(FileInputStream(File(preview.staging,"annotations/$old/$page.ink")))).use{input->store.importInk(target.name,page,input)}
                        progress.update(n.title,page.toLong())
                    }
                    store.saveCards(n);progress.update(n.title,i.toLong())
                }
            }
            committed=true;journal.delete();return mapped.size
        }finally{
            if(!committed){val cleaned=mapped.map{!it.second.exists()||it.second.deleteRecursively()}.all{it};if(cleaned)journal.delete()}
            preview.staging.deleteRecursively()
        }
    }
    /** A process death between file renames and the DB commit leaves a journal, not live rows. */
    fun recoverInterruptedRestore() {
        val live=store.list().map{it.id}.toSet()
        root.listFiles()?.filter{it.name.startsWith(".restore-")&&it.extension=="json"}?.forEach { journal->
            val ids=JSONArray(journal.readText());repeat(ids.length()) { val id=ids.getString(it);require(UUID.fromString(id).toString()==id);if(id !in live)File(root,id).deleteRecursively() };journal.delete()
        }
        work.listFiles()?.filter{it.name.startsWith("restore-")||it.name.startsWith("backup-")||it.name.startsWith("incoming-")||it.name.startsWith("outgoing-")}?.forEach{it.deleteRecursively()}
    }
    companion object {
        fun copy(input:InputStream,out:OutputStream,progress:BackupArchive.Progress,label:String){val buffer=ByteArray(65536);var total=0L;while(true){val count=input.read(buffer);if(count<0)break;out.write(buffer,0,count);total+=count;progress.update(label,total)}}
    }
}
