package cn.jiang.opennote

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    companion object { private val io=Executors.newSingleThreadExecutor();private val recovered=AtomicBoolean(false) }
    private lateinit var store:NoteStore
    private lateinit var root:File
    private var entries by mutableStateOf<List<NoteStore.Entry>>(emptyList())
    private var note by mutableStateOf<Notebook?>(null)
    private var revision by mutableIntStateOf(0)
    private var busyValue by mutableStateOf("")
    private var busy:String
        get()=busyValue
        set(value){if(value.isNotEmpty())reader?.finishGesture();reader?.isEnabled=value.isEmpty();busyValue=value}
    private val saveStates=mutableStateMapOf<String,String>()
    private var failedDocs by mutableStateOf<Set<String>>(emptySet())
    private var saveState:String
        get()=note?.let{saveStates[it.directory.name]}?:"已保存"
        set(value){note?.let{saveStates[it.directory.name]=value}}
    private var mode by mutableStateOf(ReaderView.Mode.READ)
    private var focusWriting by mutableStateOf(false)
    private var focusTools by mutableStateOf(false)
    private var palmProtection by mutableStateOf(true)
    private var pressureInk by mutableStateOf(true)
    private var smoothInk by mutableStateOf(true)
    private var finger by mutableStateOf(false)
    private var bitmap:Bitmap?=null
    private var renderVersion by mutableIntStateOf(0)
    private var reader:ReaderView?=null
    private var generation=0
    private var cancel=AtomicBoolean(false)
    private var search by mutableStateOf("")
    private lateinit var backup:LibraryBackup
    private var restorePreview:LibraryBackup.Preview?=null
    private var exportDpi=168
    private val viewports=HashMap<String,ReaderView.Viewport>()
    private val tabs=mutableStateListOf<Notebook>()
    private var tabsExpanded by mutableStateOf(true)
    private var toolbarExpanded by mutableStateOf(true)
    private var paletteExpanded by mutableStateOf(false)
    private var penBoxExpanded by mutableStateOf(true)
    private var notesExpanded by mutableStateOf(true)
    private var navigationExpanded by mutableStateOf(true)
    private var inkColor by mutableIntStateOf(0xff243746.toInt())
    private var inkWidth by mutableFloatStateOf(1.8f)
    private data class PenPreset(val color:Int,val width:Float,val highlight:Boolean)
    private val penBox=mutableStateListOf<PenPreset>()
    private val openPdf=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)importPdf(uri)}
    private val exportPdf=registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri->if(uri!=null)export(uri)}
    private val createBackup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)exportBackup(uri)}
    private val restoreBackup=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)inspectBackup(uri)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true){override fun handleOnBackPressed(){if(focusWriting){focusWriting=false;applySystemBars()}else if(note!=null&&busy.isEmpty())exitEditor()else if(busy.isNotEmpty())cancel.set(true)else finish()}})
        store=NoteStore(this);root=File(filesDir,"documents").apply{mkdirs()}
        backup=LibraryBackup(store,root,File(filesDir,"transfer-work").apply{mkdirs()})
        if(recovered.compareAndSet(false,true))io.execute{try{backup.recoverInterruptedRestore()}catch(e:Exception){error("未完成的恢复任务需要重试。",e)}}
        finger=getPreferences(MODE_PRIVATE).getBoolean("finger",false)
        val prefs=getPreferences(MODE_PRIVATE)
        palmProtection=prefs.getBoolean("palmProtection",true);pressureInk=prefs.getBoolean("pressureInk",true);smoothInk=prefs.getBoolean("smoothInk",true)
        inkColor=prefs.getInt("inkColor",inkColor);inkWidth=prefs.getFloat("inkWidth",1.8f).coerceIn(.5f,8f)
        try{val values=org.json.JSONArray(prefs.getString("penBox","[]"));repeat(values.length().coerceAtMost(12)){i->val v=values.getJSONObject(i);penBox.add(PenPreset(v.getInt("color"),v.getDouble("width").toFloat().coerceIn(.5f,8f),v.optBoolean("highlight")))}}catch(_:Exception){}
        if(!prefs.contains("penBox"))penBox.addAll(listOf(PenPreset(0xff243746.toInt(),1.8f,false),PenPreset(0xffd74444.toInt(),1.8f,false),PenPreset(0xfff5c542.toInt(),6f,true)))
        setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xff7719aa),secondaryContainer=Color(0xfff1e8f7),onSecondaryContainer=Color(0xff4c166e),background=Color(0xfff3f4f6))){Screen()}}
        refreshLibrary()
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
        if(palmProtection&&note!=null&&event.actionMasked!=android.view.MotionEvent.ACTION_CANCEL){
            val pen=(0 until event.pointerCount).any{event.getToolType(it)==android.view.MotionEvent.TOOL_TYPE_STYLUS||event.getToolType(it)==android.view.MotionEvent.TOOL_TYPE_ERASER}
            val finger=(0 until event.pointerCount).any{event.getToolType(it)==android.view.MotionEvent.TOOL_TYPE_FINGER}
            if(!pen&&finger&&reader?.stylusRecent()==true)return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun applySystemBars(){
        val controller=WindowCompat.getInsetsController(window,window.decorView)
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(focusWriting&&note!=null)controller.hide(WindowInsetsCompat.Type.systemBars())else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus)applySystemBars()}
    private fun ui(block:()->Unit){runOnUiThread{if(!isFinishing&&!isDestroyed)block()}}
    private fun error(message:String,e:Throwable?=null,clearBusy:Boolean=true){ui{if(clearBusy)busy="";AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message+when(e){is SecurityException->"\n此 PDF 可能需要密码；当前版本暂不支持。";is java.io.IOException->"\n请检查文件、可用空间和保存位置。";else->""}).setPositiveButton("知道了",null).show()}}
    private fun refreshLibrary(){io.execute{try{val data=store.list();ui{entries=data}}catch(e:Exception){error("无法读取笔记库。",e)}}}
    @Composable private fun Screen(){
        revision;val current=note
        LaunchedEffect(focusWriting,current?.directory?.name){applySystemBars()}
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()){
            Fold(!focusWriting||current==null){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(current!=null)TextButton(onClick={exitEditor()}){Text("‹ 笔记库")}
                Column(Modifier.weight(1f)){Text(current?.title?:"OpenNote",style=MaterialTheme.typography.titleLarge,maxLines=1);Text(if(current==null)"让题目之间，留出思考的空间" else "第 ${current.currentPage+1} / ${current.pages.size} 页 · $saveState",style=MaterialTheme.typography.bodySmall)}
                if(current!=null)TextButton(onClick={reader?.finishGesture();focusWriting=true;focusTools=false},enabled=busy.isEmpty()){Text("沉浸书写")}
                if(current==null)Button(onClick={openPdf.launch(arrayOf("application/pdf"))},enabled=busy.isEmpty()){Text("导入 PDF")}
            }
            }
            if(busy.isNotEmpty()){LinearProgressIndicator(Modifier.fillMaxWidth());Row(Modifier.padding(8.dp)){Text(busy,Modifier.weight(1f));TextButton(onClick={cancel.set(true)}){Text("取消")}}}
            if(current==null){
                Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={demo()},enabled=busy.isEmpty()){Text("试用示例")};OutlinedButton(onClick={createBackup.launch("OpenNote_"+java.time.LocalDate.now()+".zip")},enabled=busy.isEmpty()){Text("备份全部")};OutlinedButton(onClick={restoreBackup.launch(arrayOf("application/zip","application/octet-stream"))},enabled=busy.isEmpty()){Text("恢复备份")}}
                OutlinedTextField(value=search,onValueChange={search=it},label={Text("搜索笔记标题")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),enabled=busy.isEmpty())
                Text("${entries.size} 本笔记 · 全部保存在此设备，建议定期备份",Modifier.padding(horizontal=18.dp),style=MaterialTheme.typography.bodySmall)
                if(entries.isNotEmpty()&&entries.none{it.title.contains(search,true)})Text("没有找到匹配的笔记",Modifier.padding(24.dp))
                if(entries.isEmpty())Text("先导入一份试卷，或打开示例测试留白。",Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    items(entries.filter{it.title.contains(search,true)},key={it.id}){entry->OutlinedCard(onClick={open(entry.id)},enabled=busy.isEmpty(),modifier=Modifier.fillMaxWidth()){
                        Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(entry.title,style=MaterialTheme.typography.titleMedium);Text("${entry.count} 页 · 点击继续阅读",style=MaterialTheme.typography.bodySmall)};TextButton(onClick={rename(entry)},enabled=busy.isEmpty()){Text("重命名")}}
                    }}
                }
            }else{
                if(focusWriting)Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    TextButton(onClick={focusWriting=false},enabled=busy.isEmpty()){Text("退出沉浸")}
                    Text(saveState,style=MaterialTheme.typography.labelSmall)
                    Tool("钢笔",ReaderView.Mode.PEN);Tool("荧光笔",ReaderView.Mode.HIGHLIGHT);Tool("橡皮",ReaderView.Mode.ERASE)
                    TextButton(onClick={undo(false)},enabled=current.undo.isNotEmpty()&&busy.isEmpty()){Text("↶ 撤销")}
                    TextButton(onClick={reader?.finishGesture();focusTools=!focusTools}){Text(if(focusTools)"收起工具栏" else "展开工具栏")}
                }
                Fold(!focusWriting){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)){
                    ModuleToggle("文件 ${tabs.size}",tabsExpanded){tabsExpanded=!tabsExpanded}
                    ModuleToggle("工具栏",toolbarExpanded){toolbarExpanded=!toolbarExpanded}
                    ModuleToggle("画笔",paletteExpanded){paletteExpanded=!paletteExpanded}
                    ModuleToggle("笔盒",penBoxExpanded){penBoxExpanded=!penBoxExpanded}
                    ModuleToggle("页内笔记",notesExpanded){notesExpanded=!notesExpanded}
                    ModuleToggle("翻页",navigationExpanded){navigationExpanded=!navigationExpanded}
                }
                }
                Fold(tabsExpanded&&!focusWriting){
                    Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        tabs.forEach{tab->Surface(color=if(tab===current)Color(0xfff1e8f7)else Color(0xfff4f5f7),shape=RoundedCornerShape(3.dp)){
                            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){TextButton(onClick={switchTab(tab)},enabled=busy.isEmpty()){Text(tab.title.take(22)+if(tab.directory.name in failedDocs)" · 未保存" else "",maxLines=1)};TextButton(onClick={closeTab(tab)},enabled=busy.isEmpty()){Text("关闭")}}
                        }}
                        TextButton(onClick={exitEditor()},enabled=busy.isEmpty()){Text("＋ 打开文件")}
                    }
                }
                Fold(if(focusWriting)focusTools else toolbarExpanded){
                    Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        Tool("阅读",ReaderView.Mode.READ);Tool("钢笔",ReaderView.Mode.PEN);Tool("荧光笔",ReaderView.Mode.HIGHLIGHT);Tool("橡皮",ReaderView.Mode.ERASE);Tool("插入留白",ReaderView.Mode.SPACE);Tool("文字",ReaderView.Mode.TEXT)
                        TextButton(onClick={undo(false)},enabled=current.undo.isNotEmpty()&&busy.isEmpty()){Text("↶ 撤销")};TextButton(onClick={undo(true)},enabled=current.redo.isNotEmpty()&&busy.isEmpty()){Text("↷ 重做")}
                        TextButton(onClick={gaps()},enabled=busy.isEmpty()){Text("删除留白")};TextButton(onClick={beginExport()},enabled=busy.isEmpty()){Text("导出")}
                    }
                    if(focusWriting)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween){
                        TextButton(onClick={page(current.currentPage-1)},enabled=current.currentPage>0&&busy.isEmpty()){Text("上一页")}
                        Text("${current.currentPage+1} / ${current.pages.size}",Modifier.padding(top=12.dp),style=MaterialTheme.typography.labelMedium)
                        TextButton(onClick={reader?.reset()},enabled=busy.isEmpty()){Text("适合宽度")}
                        TextButton(onClick={page(current.currentPage+1)},enabled=current.currentPage<current.pages.size-1&&busy.isEmpty()){Text("下一页")}
                    }
                    Text(when(mode){ReaderView.Mode.READ->"单指平移 · 双指缩放";ReaderView.Mode.SPACE->"按住页面向下拉出留白 · 拖动蓝色 ↕ 手柄调整高度 · 双指缩放";ReaderView.Mode.TEXT->"点击页面添加文字笔记";else->if(finger)"手指或触控笔书写 · 双指缩放" else if(palmProtection)"触控笔优先 · 双指移动/缩放 · 手掌防误触" else "触控笔书写 · 手指平移 · 双指缩放"},Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
                }
                Fold(paletteExpanded&&(!focusWriting||focusTools)){PenPalette()}
                Fold(penBoxExpanded&&(!focusWriting||focusTools)){PenBox()}
                BoxWithConstraints(Modifier.weight(1f)){
                    val showSidebar=maxWidth>850.dp
                    Row(Modifier.fillMaxSize()){
                        val token=renderVersion
                        AndroidView(factory={context->ReaderView(context).also{v->reader=v;v.listener=object:ReaderView.Listener{
                            override fun changed(){save()}
                            override fun space(y:Double){insertSpace(y,96.0)}
                            override fun space(y:Double,height:Double){insertSpace(y,height)}
                            override fun resizeSpace(index:Int,height:Double){applyGapHeight(index,height)}
                            override fun text(x:Double,y:Double){addText(x,y)}
                            override fun clip(rect:android.graphics.RectF){}
                        }}},modifier=Modifier.weight(1f).fillMaxHeight(),update={v->
                            v.isEnabled=busy.isEmpty();v.fingerInk=finger;v.palmProtection=palmProtection;v.pressureInk=pressureInk;v.smoothInk=smoothInk;v.penColor=inkColor;v.penWidth=inkWidth.toDouble();if(v.mode!=mode)v.setMode(mode)
                            if(v.tag!=token){bitmap?.let{v.document(current,current.currentPage,it);v.restoreViewport(viewports[current.directory.name+":"+current.currentPage])}?:v.clear();v.tag=token}
                        })
                        AnimatedVisibility(visible=showSidebar&&notesExpanded&&!focusWriting,enter=expandHorizontally(animationSpec=tween(200))+fadeIn(),exit=shrinkHorizontally(animationSpec=tween(180))+fadeOut()){Column(Modifier.width(240.dp).fillMaxHeight().background(Color.White).padding(12.dp)){
                            Text("页内笔记",style=MaterialTheme.typography.titleMedium)
                            LazyColumn{items(current.cards.filter{it.page==current.currentPage},key={it.id}){card->
                                TextButton(onClick={reader?.jump(card.y)}){Text(card.text.take(100))};TextButton(onClick={editCard(card)}){Text("编辑 / 删除")}
                            }}
                            Spacer(Modifier.weight(1f));Text("文本卡片与页面位置关联\n插入留白后跟随下移",style=MaterialTheme.typography.bodySmall)
                        }}
                    }
                }
                Fold(!showNotesOnWide()&&notesExpanded&&!focusWriting){
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)){
                        if(current.cards.none{it.page==current.currentPage})Text("页内笔记 · 用文字工具点击页面添加",Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)
                        current.cards.filter{it.page==current.currentPage}.forEach{card->TextButton(onClick={editCard(card)},enabled=busy.isEmpty()){Text(card.text.take(24))}}
                    }
                }
                Fold(navigationExpanded&&!focusWriting){Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton(onClick={page(current.currentPage-1)},enabled=current.currentPage>0&&busy.isEmpty()){Text("上一页")}
                    TextButton(onClick={jumpDialog()},enabled=busy.isEmpty()){Text("${current.currentPage+1} / ${current.pages.size}")}
                    TextButton(onClick={reader?.reset()}){Text("适合宽度")}
                    TextButton(onClick={page(current.currentPage+1)},enabled=current.currentPage<current.pages.size-1&&busy.isEmpty()){Text("下一页")}
                }}
            }
        }
    }
    private fun showNotesOnWide()=resources.configuration.screenWidthDp>850
    @Composable private fun Fold(visible:Boolean,content:@Composable ColumnScope.()->Unit){
        AnimatedVisibility(visible=visible,enter=expandVertically(animationSpec=tween(220))+fadeIn(tween(160)),exit=shrinkVertically(animationSpec=tween(180))+fadeOut(tween(120))){Column(Modifier.fillMaxWidth(),content=content)}
    }
    @Composable private fun ModuleToggle(label:String,expanded:Boolean,action:()->Unit){TextButton(onClick={reader?.finishGesture();action()},enabled=busy.isEmpty()){Text("${if(expanded)"▾" else "▸"} $label",style=MaterialTheme.typography.labelMedium)}}
    @Composable private fun Tool(label:String,value:ReaderView.Mode){FilterChip(selected=mode==value,onClick={reader?.finishGesture();val same=mode==value;mode=value;if(value==ReaderView.Mode.PEN||value==ReaderView.Mode.HIGHLIGHT){paletteExpanded=if(same)!paletteExpanded else true;if(focusWriting)focusTools=true}},label={Text(label)},border=null,shape=RoundedCornerShape(4.dp),enabled=busy.isEmpty())}
    @Composable private fun PenPalette(){
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=16.dp,vertical=6.dp)){
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text("画笔",style=MaterialTheme.typography.titleSmall)
                Tool("钢笔",ReaderView.Mode.PEN);Tool("荧光笔",ReaderView.Mode.HIGHLIGHT)
                listOf(0xff243746,0xff0078d4,0xffd74444,0xff0f766e,0xff8e44ad,0xfff5c542).forEach{value->val color=value.toInt();OutlinedButton(onClick={reader?.finishGesture();inkColor=color;persistPens()},enabled=busy.isEmpty(),contentPadding=PaddingValues(8.dp),modifier=Modifier.size(48.dp).semantics{contentDescription="选择${colorName(color)}画笔"},shape=RoundedCornerShape(24.dp),border=androidx.compose.foundation.BorderStroke(if(inkColor==color)3.dp else 1.dp,if(inkColor==color)MaterialTheme.colorScheme.primary else Color.LightGray)){
                    Box(Modifier.size(24.dp).background(Color(color),RoundedCornerShape(12.dp)))
                }}
            }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                androidx.compose.foundation.Canvas(Modifier.size(48.dp,32.dp)){
                    repeat(20){i->
                        val t=(i+.5f)/20;val pressure=.1f+.9f*kotlin.math.sin(t*kotlin.math.PI).toFloat()
                        val width=inkWidth.dp.toPx()*if(pressureInk&&mode!=ReaderView.Mode.HIGHLIGHT)(.3f+.95f*kotlin.math.sqrt(pressure))else 1f
                        drawLine(Color(inkColor),androidx.compose.ui.geometry.Offset(4.dp.toPx()+(size.width-8.dp.toPx())*i/20,size.height/2),androidx.compose.ui.geometry.Offset(4.dp.toPx()+(size.width-8.dp.toPx())*(i+1)/20,size.height/2),strokeWidth=width,cap=androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                Text("粗细 ${"%.1f".format(inkWidth)}",style=MaterialTheme.typography.labelMedium)
                Slider(value=inkWidth,onValueChange={reader?.finishGesture();inkWidth=it},onValueChangeFinished={persistPens()},valueRange=.5f..8f,enabled=busy.isEmpty(),modifier=Modifier.weight(1f))
                TextButton(onClick={addPreset()},enabled=busy.isEmpty()&&penBox.size<12){Text("＋ 加入笔盒")}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text("压感钢笔（支持时）",style=MaterialTheme.typography.labelMedium);Switch(checked=pressureInk,onCheckedChange={reader?.finishGesture();pressureInk=it;getPreferences(MODE_PRIVATE).edit().putBoolean("pressureInk",it).apply()},enabled=busy.isEmpty())
                Text("笔迹平滑",style=MaterialTheme.typography.labelMedium);Switch(checked=smoothInk,onCheckedChange={reader?.finishGesture();smoothInk=it;getPreferences(MODE_PRIVATE).edit().putBoolean("smoothInk",it).apply()},enabled=busy.isEmpty())
                Text("手掌防误触",style=MaterialTheme.typography.labelMedium);Switch(checked=palmProtection,onCheckedChange={reader?.finishGesture();palmProtection=it;getPreferences(MODE_PRIVATE).edit().putBoolean("palmProtection",it).apply()},enabled=busy.isEmpty())
            }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("手指书写",style=MaterialTheme.typography.labelMedium);Switch(checked=finger,onCheckedChange={reader?.finishGesture();finger=it;getPreferences(MODE_PRIVATE).edit().putBoolean("finger",it).apply()},enabled=busy.isEmpty());Spacer(Modifier.weight(1f));if(saveState.contains("失败")||saveState.contains("待保存"))TextButton(onClick={retrySave()},enabled=busy.isEmpty()){Text("重试保存")}}
        }
    }
    @Composable private fun PenBox(){
        var managing by remember{mutableStateOf(false)}
        Row(Modifier.fillMaxWidth().background(Color(0xfffafbfc)).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text("笔盒",style=MaterialTheme.typography.labelMedium)
            penBox.toList().forEachIndexed{i,pen->FilterChip(selected=inkColor==pen.color&&inkWidth==pen.width&&((mode==ReaderView.Mode.HIGHLIGHT)==pen.highlight),onClick={if(managing){penBox.removeAt(i);persistPens()}else{reader?.finishGesture();inkColor=pen.color;inkWidth=pen.width;mode=if(pen.highlight)ReaderView.Mode.HIGHLIGHT else ReaderView.Mode.PEN;persistPens()}},label={Row(Modifier.semantics{contentDescription="${colorName(pen.color)}${if(pen.highlight)"荧光笔" else "钢笔"}，粗细 ${pen.width}"},verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Box(Modifier.size(12.dp).background(Color(pen.color),RoundedCornerShape(3.dp)));Text(" ${if(managing)"移除" else if(pen.highlight)"荧光" else "钢笔"} ${"%.1f".format(pen.width)}")}},enabled=busy.isEmpty())}
            TextButton(onClick={paletteExpanded=true},enabled=busy.isEmpty()){Text("＋ 配笔")};TextButton(onClick={managing=!managing},enabled=busy.isEmpty()){Text(if(managing)"完成" else "整理")}
        }
    }
    private fun colorName(color:Int)=when(color){0xff243746.toInt()->"墨蓝";0xff0078d4.toInt()->"蓝色";0xffd74444.toInt()->"红色";0xff0f766e.toInt()->"绿色";0xff8e44ad.toInt()->"紫色";else->"黄色"}
    private fun addPreset(){val preset=PenPreset(inkColor,inkWidth,mode==ReaderView.Mode.HIGHLIGHT);if(!penBox.contains(preset)&&penBox.size<12)penBox.add(preset);penBoxExpanded=true;persistPens()}
    private fun persistPens(){val values=org.json.JSONArray();penBox.forEach{values.put(org.json.JSONObject().put("color",it.color).put("width",it.width.toDouble()).put("highlight",it.highlight))};getPreferences(MODE_PRIVATE).edit().putString("penBox",values.toString()).putInt("inkColor",inkColor).putFloat("inkWidth",inkWidth).apply()}
    private fun registerTab(n:Notebook){if(tabs.none{it.directory==n.directory})tabs.add(n)}
    private fun rememberViewport(){val v=reader?:return;val key=v.viewportKey()?:return;v.viewport()?.let{viewports[key]=it}}
    private fun switchTab(n:Notebook){if(busy.isNotEmpty()||note===n)return;rememberViewport();reader?.finishGesture();save();note=n;page(n.currentPage)}
    private fun capturePending(n:Notebook):NoteStore.Save? {
        val p=n.pages[n.currentPage];if(!p.measured||!p.loaded)return null
        return store.capture(n).also{pendingSaves[saveKey(it)]=it}
    }
    private fun savedLabel(id:String){
        if(pendingSaves.values.none{it.doc==id}){failedDocs=failedDocs-id;saveStates[id]="已保存"}
        else saveStates[id]=if(id in failedDocs)"还有页面待保存 · 可重试" else "正在保存…"
    }
    private fun failedLabel(n:Notebook){failedDocs=failedDocs+n.directory.name;saveStates[n.directory.name]="保存失败 · 请重试"}
    private fun closeTab(n:Notebook){
        if(busy.isNotEmpty())return
        rememberViewport();reader?.finishGesture();val current=capturePending(n);busy="正在保存并关闭文件…"
        val title=n.title;val index=n.currentPage
        io.execute{try{store.flushPending(n.directory.name,title,index,pendingSaves,current);ui{savedLabel(n.directory.name);tabs.remove(n);busy="";if(note===n){reader?.clear();note=null;val next=tabs.lastOrNull();if(next!=null){note=next;page(next.currentPage)}else{generation++;bitmap?.recycle();bitmap=null;refreshLibrary()}}}}
            catch(e:Exception){ui{failedLabel(n)};error("《${n.title}》保存失败，文件标签已保留。",e)}}
    }
    private fun rename(entry:NoteStore.Entry){
        val input=EditText(this).apply{setText(entry.title);selectAll()}
        val dialog=AlertDialog.Builder(this).setTitle("笔记名称").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val title=input.text.toString().trim();if(title.isEmpty()||title.length>200){input.error="名称长度应为 1–200 个字符";return@setOnClickListener}
            busy="正在更新名称…";io.execute{try{store.rename(entry.id,title);ui{tabs.filter{it.directory.name==entry.id}.forEach{it.title=title};busy="";refreshLibrary()}}catch(e:Exception){error("重命名失败。",e)}};dialog.dismiss()
        }};dialog.show()
    }
    private fun transferProgress(label:String):BackupArchive.Progress {
        var last=0L
        return BackupArchive.Progress{_,bytes->
            if(cancel.get())throw java.io.InterruptedIOException("操作已取消")
            if(File(filesDir,"transfer-work").usableSpace<32L*1024*1024)throw java.io.IOException("可用空间不足")
            val now=System.currentTimeMillis();if(now-last>200){last=now;ui{busy="$label · ${bytes/1024/1024} MB"}}
        }
    }
    private fun exportBackup(uri:Uri){
        if(busy.isNotEmpty())return;busy="正在备份全部笔记…";cancel=AtomicBoolean(false)
        val prefs=org.json.JSONObject().put("version",1).put("finger",finger)
        io.execute{val temp=File(filesDir,"transfer-work/outgoing-${UUID.randomUUID()}.zip")
            try{
                backup.export(temp,prefs,transferProgress("正在备份"))
                contentResolver.openOutputStream(uri,"w")?.use{out->temp.inputStream().use{input->LibraryBackup.copy(input,out,transferProgress("正在保存备份"),"ZIP")}}?:throw java.io.IOException("无法写入")
                ui{busy="";AlertDialog.Builder(this).setTitle("备份已保存").setMessage("已包含源 PDF、可编辑笔迹、留白和文字笔记。ZIP 未加密，请妥善保管。").setPositiveButton("知道了",null).show()}
            }catch(e:Exception){try{contentResolver.delete(uri,null,null)}catch(_:Exception){};if(e is java.io.InterruptedIOException)ui{busy=""}else error("备份失败，原笔记不受影响。",e)}finally{temp.delete()}
        }
    }
    private fun inspectBackup(uri:Uri){
        if(busy.isNotEmpty())return;busy="正在读取备份…";cancel=AtomicBoolean(false)
        io.execute{val temp=File(filesDir,"transfer-work/incoming-${UUID.randomUUID()}.zip")
            try{
                contentResolver.openInputStream(uri)?.use{input->java.io.FileOutputStream(temp).use{out->LibraryBackup.copy(input,out,transferProgress("正在读取备份"),"ZIP");out.fd.sync()}}?:throw java.io.IOException("无法读取")
                val preview=backup.preview(temp,transferProgress("正在校验备份"));restorePreview=preview
                ui{busy="";val time=java.text.DateFormat.getDateTimeInstance().format(java.util.Date(preview.created))
                    AlertDialog.Builder(this).setTitle("恢复备份？").setMessage("${preview.notes.size} 本笔记 · ${preview.bytes/1024/1024} MB\n备份时间：$time\n\n已完成文件校验。恢复将添加副本，保留现有笔记；重复恢复会产生重复笔记。设置暂保留当前设备的偏好。")
                        .setNegativeButton("取消",null).setPositiveButton("添加到笔记库"){_,_->restorePreview=null;commitBackup(preview)}
                        .setOnDismissListener{if(restorePreview===preview){restorePreview=null;io.execute{preview.staging.deleteRecursively()}}}.show()
                }
            }catch(e:Exception){if(e is java.io.InterruptedIOException)ui{busy=""}else error("备份无法恢复："+(e.message?:"文件损坏或版本不兼容"),e)}finally{temp.delete()}
        }
    }
    private fun commitBackup(preview:LibraryBackup.Preview){
        busy="正在恢复笔记…";cancel=AtomicBoolean(false)
        io.execute{try{val count=backup.commit(preview,transferProgress("正在恢复"));ui{busy="";refreshLibrary();AlertDialog.Builder(this).setTitle("恢复完成").setMessage("已添加 $count 本可编辑笔记。可以继续书写或调整留白。").setPositiveButton("打开笔记库",null).show()}}
            catch(e:Exception){if(e is java.io.InterruptedIOException)ui{busy=""}else error("恢复未完成，原笔记未被替换。",e)}}
    }
    private fun importPdf(uri:Uri){
        if(busy.isNotEmpty())return;busy="正在读取文件信息…"
        io.execute{
            var title="未命名 PDF"
            try{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())title=it.getString(0)?:title}}
            catch(_:Exception){}
            ui{busy="";copyPdf(uri,title)}
        }
    }
    private fun copyPdf(uri:Uri,title:String){
        importSource(title){target->contentResolver.openInputStream(uri)?.use{input->target.outputStream().use{out->
            val buffer=ByteArray(1024*1024);var total=0L;var last=0L
            while(true){if(cancel.get())throw InterruptedException();val n=input.read(buffer);if(n<0)break
                if(target.parentFile!!.usableSpace<n+16L*1024*1024)throw java.io.IOException("空间不足")
                out.write(buffer,0,n);total+=n;val now=System.currentTimeMillis();if(now-last>250){last=now;ui{busy="正在复制：${total/1024/1024} MB"}}
            }
            out.flush();(out as? java.io.FileOutputStream)?.fd?.sync()
        }}?:throw java.io.IOException("文件无法读取")}
    }
    private fun importSource(title:String,copy:(File)->Unit){
        if(busy.isNotEmpty())return;busy="正在导入…";cancel=AtomicBoolean(false)
        io.execute{val id=UUID.randomUUID().toString();val stage=File(root,".$id.tmp");val dir=File(root,id);var committed=false
            try{check(stage.mkdirs());copy(File(stage,"original.pdf"));if(cancel.get())throw InterruptedException()
                val count=PdfEngine(File(stage,"original.pdf")).use{it.count()};require(count>0)
                val n=Notebook(dir,title.removeSuffix(".pdf"));repeat(count){n.pages.add(Notebook.PageData(612.0,792.0).apply{measured=false;loaded=true})}
                check(stage.renameTo(dir));store.create(n);committed=true;ui{busy="";note=n;registerTab(n);mode=ReaderView.Mode.READ;page(0);refreshLibrary()}
            }catch(e:InterruptedException){ui{busy=""}}catch(e:Exception){error("导入失败，源文件没有被修改。",e)}finally{stage.deleteRecursively();if(!committed)dir.deleteRecursively()}
        }
    }
    private fun demo(){importSource("留白与书写示例"){file->
        RasterPdf(file.outputStream()).use{pdf->
            repeat(2){index->
                if(cancel.get())throw InterruptedException()
                val image=Bitmap.createBitmap(1190,1684,Bitmap.Config.ARGB_8888)
                try{
                    image.eraseColor(AndroidColor.WHITE);val c=Canvas(image);c.scale(2f,2f)
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=AndroidColor.rgb(36,55,70);textSize=22f}
                    c.drawText("OpenNote · 离线留白练习",42f,64f,paint);paint.textSize=16f
                    val lines=if(index==0)listOf("18. 计算积分，并写出完整的推导过程。","I = ∫ x² dx","在下面横线处按住页面向下拖动，即可拉出留白。","19. 证明矩阵的秩与其转置矩阵的秩相等。","请先写几个字，再插入留白，观察笔迹位置。")else listOf("第二页：测试导出页序与保存。","使用触控笔写下：积分号、矩阵、长横线。","如用手指测试，请在「画笔面板」中开启。")
                    lines.forEachIndexed{i,line->c.drawText(line,42f,140f+i*85,paint)}
                    paint.color=AndroidColor.LTGRAY;c.drawLine(42f,330f,553f,330f,paint)
                    val bytes=java.io.ByteArrayOutputStream();check(image.compress(Bitmap.CompressFormat.JPEG,95,bytes)){"示例页面无法编码"}
                    pdf.addPage(595.0,842.0,1190,1684,1684){_,_,_->if(cancel.get())throw InterruptedException();bytes.toByteArray()}
                }finally{image.recycle()}
            }
            pdf.finish()
        }
    }}
    private fun open(id:String){if(busy.isNotEmpty())return;tabs.firstOrNull{it.directory.name==id}?.let{switchTab(it);return};busy="正在读取笔记…";val ticket=++generation;io.execute{try{val n=store.load(root,id);ui{if(ticket==generation){note=n;registerTab(n);busy="";mode=ReaderView.Mode.READ;page(n.currentPage)}}}catch(e:Exception){error("无法打开笔记。",e)}}}
    private fun page(index:Int){val n=note?:return;if(index !in n.pages.indices)return;rememberViewport();reader?.finishGesture();n.currentPage=index;val oldBitmap=bitmap;val ticket=++generation;busy="正在渲染第 ${index+1} 页…";bitmap=null;reader?.clear();oldBitmap?.recycle();renderVersion++
        io.execute{try{val p=n.pages[index];val ink=if(p.loaded)p.strokes else store.loadInk(n.directory.name,index)
            PdfEngine(File(n.directory,"original.pdf")).use{engine->val dimensions=if(p.measured)null else engine.dimensions(index);val rendered=engine.render(index,1600)
                runOnUiThread{if(isDestroyed||isFinishing||ticket!=generation){rendered.recycle();return@runOnUiThread};if(dimensions!=null){dimensions.id=p.id;dimensions.strokes=ink;dimensions.loaded=true;n.pages[index]=dimensions}else{p.strokes=ink;p.loaded=true};bitmap=rendered;busy="";renderVersion++;revision++;save()}
            }
        }catch(e:Exception){error("页面渲染失败，可返回笔记库重试。",e)}}
    }
    private fun save(){val n=note?:return;val snapshot=capturePending(n)?:return;saveStates[n.directory.name]="正在保存…";revision++
        io.execute{try{store.save(snapshot);pendingSaves.remove(saveKey(snapshot),snapshot);ui{savedLabel(n.directory.name)}}catch(e:Exception){ui{
            if(pendingSaves[saveKey(snapshot)]===snapshot){failedLabel(n);error("《${n.title}》还有未提交的笔迹，请打开该文件的画笔面板重试保存。",e,false)}
        }}}
    }
    private val pendingSaves=java.util.concurrent.ConcurrentHashMap<String,NoteStore.Save>()
    private fun saveKey(s:NoteStore.Save)=s.doc+":"+s.page
    private fun retrySave(){
        if(busy.isNotEmpty())return
        val n=note?:return;reader?.finishGesture();val current=capturePending(n);busy="正在重试保存…"
        val title=n.title;val index=n.currentPage
        io.execute{try{store.flushPending(n.directory.name,title,index,pendingSaves,current);ui{busy="";savedLabel(n.directory.name)}}
            catch(e:Exception){ui{failedLabel(n)};error("《${n.title}》保存仍未完成，当前编辑内容会保留。",e)}}
    }
    private fun change(action:()->Unit){try{reader?.finishGesture();action();reader?.refresh();revision++;save()}catch(e:Exception){error(e.message?:"编辑未完成。")}}
    private fun insertSpace(y:Double,height:Double){val n=note?:return;change{n.insert(n.currentPage,y,height)}}
    private fun applyGapHeight(index:Int,size:Double){val n=note?:return;val p=n.pages[n.currentPage];if(index !in p.layout.gaps.indices)return;val old=p.layout.gaps[index].height
        if(size>=old)change{n.growGap(index,size)}
        else{val top=p.layout.gapTop(index)+size;val bottom=p.layout.gapTop(index)+old
            val touches=p.strokes.any{ink->val low=ink.points.minOfOrNull{it.y}?:0.0;val high=ink.points.maxOfOrNull{it.y}?:0.0;high>=top&&low<bottom}||n.cards.any{it.page==n.currentPage&&it.y>=top&&it.y<bottom}
            if(touches)removalChoice(index,size)else remove(index,size,top,old-size,false)
        }
    }
    private fun gaps(){val n=note?:return;val p=n.pages[n.currentPage];if(p.layout.gaps.isEmpty()){error("本页还没有留白，请先使用「＋留白」。");return}
        AlertDialog.Builder(this).setTitle("选择要调整的留白").setItems(p.layout.gaps.mapIndexed{i,g->"留白 ${i+1} · ${"%.1f".format(g.height*25.4/72)} mm"}.toTypedArray()){_,index->gapAction(index)}.show()
    }
    private fun gapAction(index:Int){AlertDialog.Builder(this).setTitle("留白操作").setItems(arrayOf("拖动调整高度","删除留白")){_,choice->if(choice==0){mode=ReaderView.Mode.SPACE;reader?.jump(note!!.pages[note!!.currentPage].layout.gapTop(index))}else deleteGap(index)}.show()}
    private fun deleteGap(index:Int){removalChoice(index,0.0)}
    private fun removalChoice(index:Int,newHeight:Double){val n=note?:return;val p=n.pages[n.currentPage];val old=p.layout.gaps[index].height;val top=p.layout.gapTop(index)+newHeight;val removed=old-newHeight
        AlertDialog.Builder(this).setTitle(if(newHeight==0.0)"删除留白" else "缩小留白").setMessage("缩去区域中可能有笔迹。请选择处理方式。删除选项会删除与该区域相交的整笔及文字；可撤销。移动选项会将笔迹保留在邻近可见区域，可能与原内容重叠；无法完整容纳时会拒绝。").setNegativeButton("取消",null)
            .setNeutralButton("移动到邻近区域"){_,_->remove(index,newHeight,top,removed,false)}.setPositiveButton("删除相交笔迹"){_,_->remove(index,newHeight,top,removed,true)}.show()
    }
    private fun remove(index:Int,size:Double,top:Double,height:Double,delete:Boolean){val n=note?:return;val p=n.pages[n.currentPage]
        val moved=Ink.removeSpace(p.strokes,top,height,delete);val nextHeight=p.layout.height()-height
        if(!delete)for(ink in moved){val low=ink.points.minOfOrNull{it.y}?:0.0;val high=ink.points.maxOfOrNull{it.y}?:0.0
            if(high-low>nextHeight){error("笔迹高度超过删除后的页面，无法完整移动；请选择取消或删除相交笔迹。");return}
            val shift=if(high>nextHeight)nextHeight-high else if(low<0)-low else 0.0
            if(shift!=0.0)ink.points.forEach{it.y+=shift}
        }
        change{n.checkpoint();if(size==0.0)p.layout.remove(index)else p.layout.resize(index,size);p.strokes=moved
            val iterator=n.cards.iterator();while(iterator.hasNext()){val c=iterator.next();if(c.page!=n.currentPage)continue
                if(c.y>=top&&c.y<top+height&&delete)iterator.remove()
                else if(c.y>=top+height)c.y-=height
                else if(c.y>=top)c.y=c.y.coerceIn(0.0,(nextHeight-48).coerceAtLeast(0.0))
            }
        }
    }
    private fun addText(x:Double,y:Double){val field=EditText(this).apply{hint="写下思路、公式说明或复习提示";minLines=3};AlertDialog.Builder(this).setTitle("文字笔记").setView(field).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->if(field.text.isNotBlank())change{val n=note!!;n.checkpoint();n.cards.add(Notebook.Card().apply{page=n.currentPage;this.x=x;this.y=y;text=field.text.toString()})}}.show()}
    private fun editCard(card:Notebook.Card){val field=EditText(this).apply{setText(card.text)};AlertDialog.Builder(this).setTitle("编辑文字笔记").setView(field).setNegativeButton("取消",null).setNeutralButton("删除"){_,_->change{note!!.checkpoint();note!!.cards.remove(card)}}.setPositiveButton("保存"){_,_->change{note!!.checkpoint();card.text=field.text.toString()}}.show()}
    private fun undo(redo:Boolean){val n=note?:return;try{reader?.finishGesture();val previous=n.currentPage;val changed=if(redo)n.redo()else n.undo();if(changed){save();if(previous!=n.currentPage)page(n.currentPage)else reader?.refresh();revision++}}catch(e:Exception){error("撤销未完成。",e)}}
    private fun jumpDialog(){val n=note?:return;val field=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText((n.currentPage+1).toString())};AlertDialog.Builder(this).setTitle("跳转页码").setView(field).setNegativeButton("取消",null).setPositiveButton("跳转"){_,_->field.text.toString().toIntOrNull()?.let{if(it in 1..n.pages.size)page(it-1)}}.show()}
    private fun beginExport(){reader?.finishGesture();save();AlertDialog.Builder(this).setTitle("图像 PDF 导出").setMessage("保留留白、笔迹和页面外观。此版本会栅格化，导出文件不保留原文的可选择文本和矢量对象。").setItems(arrayOf("标准 · 168 dpi","较清晰 · 240 dpi")){_,i->exportDpi=if(i==0)168 else 240;exportPdf.launch((note?.title?:"OpenNote")+"_留白笔记.pdf")}.show()}
    private fun export(uri:Uri){val n=note?:return;reader?.finishGesture();val current=capturePending(n);val title=n.title;val index=n.currentPage;busy="正在导出…";cancel=AtomicBoolean(false);val dpi=exportDpi
        io.execute{val temp=File(cacheDir,"export-${UUID.randomUUID()}.pdf");try{
            store.flushPending(n.directory.name,title,index,pendingSaves,current)
            ui{savedLabel(n.directory.name)}
            val snapshot=store.load(root,n.directory.name)
            PdfEngine.export(temp,File(n.directory,"original.pdf"),snapshot,dpi,object:PdfEngine.Progress{
                override fun loadPage(index:Int,p:Notebook.PageData){if(cancel.get())throw InterruptedException();p.strokes=store.loadInk(n.directory.name,index)}
                override fun update(done:Int,total:Int){if(cancel.get())throw InterruptedException();ui{busy="正在导出 $done / $total 页…"}}
            })
            PdfEngine(temp).use{check(it.count()==snapshot.pages.size){"导出页数不一致"}}
            if(cancel.get())throw InterruptedException()
            contentResolver.openOutputStream(uri,"w")?.use{out->temp.inputStream().use{it.copyTo(out,1024*1024)}}?:throw java.io.IOException("无法写入导出位置")
            ui{busy="";AlertDialog.Builder(this).setTitle("导出完成").setMessage("已验证输出 PDF 可重新打开，页数为 ${snapshot.pages.size}。导出为图像 PDF。").setPositiveButton("知道了",null).show()}
        }catch(e:InterruptedException){ui{busy=""};try{contentResolver.delete(uri,null,null)}catch(_:Exception){}}
        catch(e:Exception){try{contentResolver.delete(uri,null,null)}catch(_:Exception){};ui{if(pendingSaves.values.any{it.doc==n.directory.name})failedLabel(n)};error("导出失败，源笔记不受影响。",e)}finally{temp.delete()}}
    }
    private fun exitEditor(){
        if(busy.isNotEmpty())return
        val n=note?:return;rememberViewport();reader?.finishGesture()
        val snapshot=capturePending(n);val title=n.title;val index=n.currentPage
        busy="正在保存并返回笔记库…"
        io.execute{try{store.flushPending(n.directory.name,title,index,pendingSaves,snapshot);ui{savedLabel(n.directory.name);generation++;reader?.clear();note=null;bitmap?.recycle();bitmap=null;revision++;busy="";refreshLibrary()}}
            catch(e:Exception){ui{failedLabel(n)};error("返回前保存失败，已保留当前页面供你重试。",e)}}
    }
    override fun onPause(){reader?.finishGesture();save();super.onPause()}
    override fun onDestroy(){reader?.finishGesture();generation++;reader?.clear();reader=null;bitmap?.recycle();bitmap=null;restorePreview?.let{p->io.execute{p.staging.deleteRecursively()}};io.execute{store.close()};super.onDestroy()}
}
