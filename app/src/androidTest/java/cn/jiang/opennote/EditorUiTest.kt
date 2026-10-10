package cn.jiang.opennote

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private var systemUiAnrRetries=0
    private var lastSystemUiAnrRetry=0L
    private fun find(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(predicate(node))return node
            for(i in 0 until node.childCount){val child=node.getChild(i)?:continue;visit(child)?.let{return it}}
            return null
        }
        val info=automation.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        val root=automation.rootInActiveWindow ?: automation.windows.mapNotNull{it.root}.firstOrNull{it.packageName?.toString()?.startsWith("cn.jiang.opennote")==true}
        // Software AVDs can time out System UI on cold boot. Never dismiss an
        // OpenNote ANR; acknowledge only this named OS dialog and report it.
        if(root?.packageName?.toString()=="android"){
            fun locate(node:AccessibilityNodeInfo,value:String):AccessibilityNodeInfo? {
                if(node.text?.toString()==value)return node
                for(i in 0 until node.childCount)node.getChild(i)?.let{locate(it,value)?.let{return it}}
                return null
            }
            if(locate(root,"System UI isn't responding")!=null){
                val now=android.os.SystemClock.uptimeMillis()
                // Allow the dialog dismissal to finish before counting another retry.
                if(systemUiAnrRetries>0&&now-lastSystemUiAnrRetry<15000)return null
                assertTrue("System UI repeatedly failed on this AVD",systemUiAnrRetries<2)
                var wait=locate(root,"Wait")
                while(wait!=null&&!wait.isClickable)wait=wait.parent
                if(wait?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){
                    systemUiAnrRetries++
                    lastSystemUiAnrRetry=now
                    val status=android.os.Bundle();status.putInt("opennote_systemui_anr_handled",systemUiAnrRetries)
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,status)
                }
                return null
            }
        }
        return root?.let{visit(it)}
    }
    private fun waitFor(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.uptimeMillis()+90000
        while(android.os.SystemClock.uptimeMillis()<deadline){find(predicate)?.let{return it};Thread.sleep(250)}
        val labels=mutableListOf<String>();find{node->node.text?.toString()?.let{labels.add(it)};false}
        throw AssertionError("Expected editor control was not visible; active root="+automation.rootInActiveWindow?.packageName+"; controls="+labels.distinct().joinToString())
    }
    private fun text(value:String)=waitFor{it.text?.toString()?.contains(value)==true}
    private fun click(node:AccessibilityNodeInfo){
        val label=node.text?.toString();val description=node.contentDescription?.toString()
        val deadline=android.os.SystemClock.uptimeMillis()+90000
        while(android.os.SystemClock.uptimeMillis()<deadline){
            var target=find{(label!=null&&it.text?.toString()==label)||(description!=null&&it.contentDescription?.toString()==description)}
            while(target!=null){if(target.isClickable&&target.isEnabled&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;target=target.parent}
            Thread.sleep(250)
        }
        throw AssertionError("Unable to click $label / $description")
    }

    @Test fun editorModulesCollapseAndPersistentPenBoxSurvivesRecreation(){
        // Start with deterministic preset state, then exercise public UI actions.
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("MainActivity",0).edit().remove("penBox").commit()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            click(text("试用示例"));text("插入留白")
            click(text("钢笔"));waitFor{it.contentDescription?.toString()=="选择紫色画笔"}.let{click(it)}
            click(text("＋ 加入笔盒"));waitFor{it.contentDescription?.toString()?.startsWith("紫色钢笔") == true}
            click(text("▾ 工具栏"));text("▸ 工具栏")
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            while(find{it.text?.toString()=="插入留白"}!=null&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100)
            assertNull(find{it.text?.toString()=="插入留白"})
            click(text("▸ 工具栏"));text("插入留白")
            click(text("▾ 笔盒"));text("▸ 笔盒");click(text("▸ 笔盒"))
            waitFor{it.contentDescription?.toString()?.startsWith("紫色钢笔") == true}
            // Capture the actual editor while the app is the active window.
            Thread.sleep(400)
            if(InstrumentationRegistry.getArguments().getString("captureEditor")=="true")automation.takeScreenshot()?.let{image->
                val bytes=java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);image.recycle()
                val bundle=android.os.Bundle();bundle.putString("opennote_editor_png",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
                InstrumentationRegistry.getInstrumentation().sendStatus(2,bundle)
            }
            click(text("▾ 工具栏"));text("▸ 工具栏")
            click(text("沉浸书写"));text("退出沉浸");text("展开工具栏")
            val barsDeadline=android.os.SystemClock.uptimeMillis()+15000
            var hidden=false
            while(!hidden&&android.os.SystemClock.uptimeMillis()<barsDeadline){
                scenario.onActivity{activity->val insets=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView);hidden=insets!=null&&!insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())&&!insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars())}
                if(!hidden)Thread.sleep(100)
            }
            assertTrue("Both system bars must hide in immersive writing",hidden)
            if(InstrumentationRegistry.getArguments().getString("captureEditor")=="true")automation.takeScreenshot()?.let{image->
                val bytes=java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);image.recycle()
                val bundle=android.os.Bundle();bundle.putString("opennote_immersive_png",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));InstrumentationRegistry.getInstrumentation().sendStatus(2,bundle)
            }
            click(text("展开工具栏"));text("收起工具栏");text("插入留白");text("下一页");click(text("收起工具栏"));text("展开工具栏")
            click(text("退出沉浸"));text("沉浸书写")
            scenario.recreate();text("试用示例")
            click(text("留白与书写示例"));text("插入留白")
            waitFor{it.contentDescription?.toString()?.startsWith("紫色钢笔") == true}
        }
    }
}
