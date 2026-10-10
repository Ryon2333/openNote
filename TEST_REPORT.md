# OpenNote 0.4 验证报告

日期：2026-10-10；0.4.0-dev / versionCode 4。

## 本轮实现

触点 ID 跟踪与触控笔优先、可关闭的手掌启发式保护、双指平移缩放；取消事件回滚临时笔迹/擦除，不留下撤销记录。压感钢笔共用实时/回放/PDF渲染，并持久保存模式。沉浸模式隐藏双系统栏，保留保存状态和可独立展开的紧凑工具条，采用紫色平面视觉。完整 Goodnotes 工具集尚未完成。

原生适配 perfect-freehand 的位置 streamline 步骤（MIT）。新位置权重0.85，保留小笔画与真实压力，抬笔补齐终点；可关闭并逐笔保存模式，原始点不变。不是完整 perfect-freehand 轮廓引擎移植；当前压力曲线和轮廓仍由 OpenNote 实现。具体来源、修改和许可见 docs/OPEN_SOURCE_INK.md 与 APK 内 assets/licenses/perfect-freehand.txt。

笔迹编码3兼容读取1/2；旧笔迹不自动平滑。JSON、留白分割、撤销和备份保留模式；旧应用不能读取版本3备份。

## 主机与构建

完整执行 `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`，OpenJDK17、Gradle8.9、AGP8.7.3、SDK35。构建 `BUILD SUCCESSFUL in 51s`；27项主机测试全部通过，0失败/错误；Lint 0错误、21警告。

主机分布：BackupArchive8、Ink4、NotebookCommand4、PageLayout5、RasterPdf1、StrokeCodec3、PressureInk1、StrokeStreamline1。平滑用例包含五组检查：原始点/压力元数据、交替抖动、小于一个像素的短笔画、10000点增量/回放一致、留白保留模式。交替抖动的累计变化量比值约0.739；这是合成数据检查，不是手写评分或真机延迟指标。

本轮运行环境重置后缺少工具链；直接下载网络失败，经代理恢复Gradle/SDK，并补齐JDK工具入口。此前主机26项通过是开源平滑适配前的记录，本报告以上27项为当前代码结果。详见 docs/verification/gradle-0.4.txt、unit-0.4.txt、streamline-0.4.txt、lint-0.4.xml。

## Android

最终安装包完整12项全部通过，0失败，`Time: 145.2 / OK (12 tests)`：BackupRestore3、EditorUi1、InkBrush2、PendingFlush1、ReaderInput3、VerticalSlice2。原生 Canvas 像素一致与旧点画、历史样本、混合掌/笔触点、次要手掌取消、整笔取消回滚、实际压感粗细、留白拖动、保存/重启/导出、备份恢复全部通过。界面实际操作检查笔盒持久保存、模块折叠、正常工具栏已收起时独立展开沉浸工具栏、翻页控件、双系统栏同时隐藏及退出沉浸。真实编辑/沉浸截图与最终日志已保存。

初次完整12项为11通过/1失败：界面被“System UI isn't responding”系统弹窗阻塞；一次仅点击Wait的复验仍失败。读取系统截图后使用其Close app按钮重启System UI，应用正常恢复；单独界面复验通过（108.193秒）。随后改善设置行间距，重新构建最终安装包并完整运行12项，全部通过。测试中的命名System UI有限重试有冷却期，会记录处理次数，不处理OpenNote无响应；最终完整一轮未触发该重试。失败、恢复和通过记录均保留，不把系统问题当作应用功能通过。

最终记录：docs/verification/android-integration-0.4-final.txt；之前诊断：android-integration-0.4-native-first.txt、android-integration-0.4-ui-anr.txt、android-systemui-0.4.txt、android-systemui-recovery-0.4.txt、android-integration-0.4-ui-checkpoint.txt。
适配前初次10项有2项失败：界面示例导入超时，以及压力测试按非白像素计数忽略抗锯齿强度；诊断后改为红通道覆盖量比较。示例改用 RasterPdf 图像页生成，避免原先 PDF 字体生成流程；修复是否有效以本轮实际界面复验为准。早期失败日志保留于 docs/verification/android-integration-0.4-first.txt 与 android-integration-0.4-diagnostic.txt。

软件模拟器无KVM，关闭系统动画以稳定行为测试；不能推断 OPPO 的动画帧率、实际笔尖延迟、压力硬件或手掌识别效果。

## 安装与同步

原0.2/0.3开发签名不可用，Debug使用独立包 `cn.jiang.opennote.preview`（OpenNote 预览），与旧应用并行安装。使用旧版 ZIP 备份与预览版恢复添加副本迁移；保留旧应用。预览开发密钥保留在工程外，不交付。

本次已构建 APK SHA-256：`541cc702a26edfc6948a4ddb75991fc855f2694611df60abe049fe459a4f87a7`。签名验证通过，证书SHA-256：`2b11b2fd6b4165fe83abe6f77209734013aa86c1495d1b57ba3dd0446604a231`。功能提交 `61f7525` 已保留。GitHub推送返回128：无法读取用户名（未授权连接）；仍未同步，远程CI未运行。验收与间距改进提交 `717d5ea`；该提交再次推送仍返回同一授权错误。

## 尚未验收

- OPPO手掌接触角度、压力范围、取消信息、实际笔尖延迟和30分钟持续书写。
- 动画帧率、大文件/大量笔迹性能、Tile/front-buffer。
- AndroidX Ink后端、完整perfect-freehand轮廓、笔形/校准、局部擦/套索/形状等完整工具。
- 真机旧库迁移、低存储、系统强杀与设备重启。

人工验收见 docs/WRITING_INTERACTION.md，0.3报告保留于 docs/verification/TEST_REPORT-0.3.md。
