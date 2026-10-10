# OpenNote 0.2 验证报告

日期：2026-10-10（北京时间）。正式需求仍为 OpenNote Android 规范 v1.0。阶段代码交付不代表完整验收。

环境：Ubuntu容器；完整Temurin JDK17.0.20.1；Gradle8.9、AGP8.7.3、Kotlin2.0.21；SDK35/BuildTools35.0.0；minSDK33、targetSDK35。

最终执行：`:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`。BUILD SUCCESSFUL，最后一轮1m40s。Debug APK版本0.2.0-dev/versionCode2、测试APK均真实生成。APK v2签名验证通过，没有INTERNET权限。构建时间不代表应用运行或书写延迟。

## 主机单元测试

| 测试类 | 用例数 | 失败 | 错误 | 秒数 |
|---|---:|---:|---:|---:|
| BackupArchiveTest | 8 | 0 | 0 | 3.489 |
| InkTest | 4 | 0 | 0 | 0.027 |
| NotebookCommandTest | 2 | 0 | 0 | 0.008 |
| PageLayoutTest | 5 | 0 | 0 | 0.051 |
| RasterPdfTest | 1 | 0 | 0 | 0.004 |
| StrokeCodecTest | 2 | 0 | 0 | 0.037 |

合计 22 项，全部通过。新增8项：备份哈希往返、路径攻击、内容篡改、未来版本、条目/字节门槛、取消清理、压缩比门槛、65,536条目ZIP64中央目录。旧布局随机编辑/笔迹/编码/PDF结构/撤销测试保留。

ZIP64用例创建65,536个空笔迹文件，生成65,541条目ZIP；Java ZipFile识别条目，独立检查尾部ZIP64 EOCD签名。该测试只证明本机Java17的多条目ZIP64，不证明Android平台或单个>4GB文件已通过。

Lint：0错误、11警告。警告为版本更新、可用空间API建议和中文/数字国际化；未声称零警告。XML与最终构建日志在docs/verification。

## Android仪器用例与运行阻碍

共6项已编译：原生笔历史采样；SQLite重开留白及导出位置；事务失败回滚；备份→恢复→继续编辑→再备份及取消事务；10万点单笔BLOB保存/读取/再次保存/流式备份；崩溃日志保留已提交目录并清理孤立目录。

**最终执行6项，全部通过，AndroidJUnitRunner输出 `OK (6 tests)`，74.641秒。**记录见 `docs/verification/android-integration-0.2.txt`。运行环境为无KVM的Android13 AOSP x86_64 TCG软件模拟；两份APK均安装成功。实际验证了：历史输入采样；保存重开/80mm留白导出色块及笔迹位置；SQLite失败回滚；ZIP备份恢复、原PDF哈希、笔迹二进制一致、继续编辑和再次备份、恢复中断回滚；10万点单笔分块数据库读取/保存/流式编码；恢复日志对孤立目录的处理。

早先尝试遇ADB离线，另两个AVD/GPU关闭重试遭宿主SIGSEGV；这些历史失败保留在android-attempt-0.2.txt。随后隔离AVD完成启动并取得上述成功结果。模拟器的系统进程出现ANR/崩溃；MainActivity启动命令已执行，但截图被系统蓝牙崩溃弹窗遮挡，主页视觉验收尚未完成。没有交付有效应用截图。上述执行秒数只是该软件模拟器的用例耗时，不是笔尖延迟或OPPO性能。

GitHub Actions另配置API33、pixel_c模拟器和KVM的Android仪器测试job；YAML解析通过，但仓库推送受身份验证阻碍，远程CI未运行。

## 功能交付与未验收

新增备份/恢复代码、首页搜索重命名、恢复预览、返回前等待保存/失败保留编辑页、未提交页面快照与批量重试、任务期间禁止落笔，以及SQLite BLOB 512KiB分块读取。新增数据库/备份/原生输入和导出位置已通过上述Android仪器用例。Compose主页交互、SAF文件选择器端到端、保存失败后返回的界面流程仍需UI与真机测试。

仍未完成：局部橡皮擦/套索/形状、连续阅读/Tile、标准分页与矢量文本导出、文件夹/回收站/空白笔记页、备份替换/去重/设置恢复；OPPO真实笔延迟/30分钟/发热、强杀断电/磁盘满和100MB–4GB PDF分档测试。

## 签名与同步

实际核对：0.1证书SHA-256为870155d1e47e4786025df5f3faddaa8bdae3f3fbe682259c5e69467f061a873f；0.2为2e4cecad9b46cc261ec73a2d4b0cf2609aa5155fcbdc33220b528dde6dafec55。不能覆盖升级旧版；不要卸载含笔记数据的0.1。需要旧密钥或无损数据迁移流程。

远程指定为 https://github.com/Ryon2333/openNote.git 。首次push失败：缺少GitHub身份验证。已经提交本地代码，不代表远程同步成功；后续每轮更新的同步要求已写入AGENTS.md。
