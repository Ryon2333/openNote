# Architecture decisions

## ADR-001: Platform PDF renderer for S1
Android PdfRenderer is available on target Android 13+, has seekable file-descriptor input and renders clipped regions through Matrix and destination clips. Constructor/file parsing may block and is on the worker. Page dimensions are obtained on demand in S1. ABI/native maintenance is supplied by the OS, with no bundled third-party PDF binary/license. API reference: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer . Password LoadParams is Android 15+; unsupported encrypted PDFs receive an error, not an unverified support claim. Text-preserving editing is not provided by the S1 adapter.

Alternatives: PDFium requires a maintained Android binding, ABI builds and license inventory; MuPDF has AGPL/commercial obligations (https://mupdf.com/licensing/) and cannot be assumed to be free for arbitrary distribution. Defer adoption until text-preserving export and large-file engine benchmarks justify it. Renderer interface should remain replaceable.

## ADR-002: Native View + Compose
Requirement: Kotlin + Compose shell; no web handwriting. Core algorithms already implemented in plain Java are retained through Kotlin interoperability. Native View owns historical stylus samples and foreground preview. No per-sample Compose state publication. Real low-latency front-buffer / AndroidX graphics integration is an S3 investigation, not a promised measurement.

## ADR-003: SQLite WAL + binary strokes
Replace prototype whole-document JSON saving with SQLiteOpenHelper, WAL, transactional commits. Rows use stable UUIDs and binary geometry. Version 1 is explicit; future versions need additive migrations and migration tests. Undo lifetime is current editor session; persistent state remains independent from undo snapshots. Page-scoped snapshots are bounded; delta commands are needed for large-scale editing.

## ADR-004: Initial raster export
Immutable source content is composited with gaps and ink, exported in bounded strips. This preserves visual placement but not selectable text or source vectors. Label the mode explicitly and provide output resolution choices. Source and editable note data remain separate.

## PDF engine evaluation scope

| Engine | File access/rendering | Text/edit/export | License and maintenance | Decision |
|---|---|---|---|---|
| Android PdfRenderer | Seekable descriptor; bitmap clip/matrix API; one worker per current adapter; dimensions from native page | Newer OS APIs add capabilities, but initial adapter only renders; raster export is explicit | OS-maintained platform implementation; no separate native binary shipped | S1 baseline; large-file/parser isolation tests pending |
| PDFium core | Public API exposes custom file access, bitmap/matrix rendering and page access; Android bindings need independent review | Core has richer PDF APIs; integration and object-preserving export not implemented here | Current upstream LICENSE includes BSD-style terms and Apache-2.0 text; actual build/third_party notices and wrapper licenses still require inventory | Candidate if S5 or vector export needs justify ABI/build work |
| MuPDF | Mature native engine candidate; no Android binding benchmark run here | Candidate for document operations; current project does not depend on it | Upstream offers AGPL or commercial license; no assumption that commercial distribution terms are free | Defer; license/distribution decision must be explicit |

Primary sources reviewed: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer ; https://pdfium.googlesource.com/pdfium/+/refs/heads/main/public/fpdfview.h ; https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE ; https://mupdf.readthedocs.io/en/1.28.5/license.html . No >2GB offset or security claim for a not-yet-selected binding.

## 0.2 Backup and restore

保留原始PDF与二进制笔迹；独立可移植ZIP v1，不直接复制WAL数据库。选择平台ZipOutputStream/ZipFile，避免新增原生依赖。逐文件SHA-256与声明大小验证；ZipFile中央目录先做路径/数量/压缩比/总量筛查，再临时解压。

本轮优先实现保留现有库的“添加副本”，替换/去重暂不开放。跨文件系统资源与数据库无法共同事务，使用持久恢复日志协调目录移动和SQLite事务，启动时清理未提交目录。原始ID保存在导出结构中，导入为新文档UUID允许重复恢复。SQLite大BLOB采用512KiB分块查询，避免CursorWindow限额。

单一进程IO队列串行化Activity的数据库工作，防止重建Activity时启动清理与旧恢复任务并发。返回编辑器前等待所有待保存页面提交；失败不关闭编辑页。正式进程杀死/断电/磁盘满与设备交互仍须验证。

## 0.3 编辑器交互

外层使用 Compose AnimatedVisibility 的尺寸和透明度过渡，180–220ms；保留模块恢复入口。Android 书写区域高度改变不重置缩放，宽度改变按适合宽度比例调整；已完成页面缓存复用窗口尺寸位图，避免动画帧逐次重新分配。

留白拖动不直接修改模型。原生 Canvas 分段裁剪绘制预览，松手才调用单个插入/扩展编辑命令；手势取消或双指接管放弃预览。缩小与已有笔迹/卡片相交时仍提示保留移动、删除或取消，避免隐藏的数据损失。

文件标签保留 Notebook 会话，当前页面位图只保留一份。切换捕获当前页并排队写库；关闭等待对应未提交快照，失败保留标签。标签/撤销会话不跨重启恢复，笔盒颜色/宽度/类型用 SharedPreferences 持久存储。未建立跨文件总内存预算，不宣称无限标签性能。

关闭标签、返回、重试与导出前使用单个外层 SQLite 事务提交该文件的待保存页面，随后写入当前标题和页码；事务成功才移除相应快照，回滚保留全部待保存项。避免较早页面覆盖重命名或阅读页码，避免导出落后的磁盘快照。保存状态按文件记录，过期失败不覆盖更新的快照状态；耗时任务立即停止笔迹并禁止原生输入。

阅读视口按文件/页缓存缩放比例与中心位置，切换或再打开会话内文件时恢复；与页面动画中的宽度变化分别处理，不跨重启持久化。

## 0.4 书写体验

压感模式为每笔属性，不重新解释旧版固定宽度笔迹；二进制笔迹版本2读取版本1，JSON新增可选字段，撤销/留白变换保持该属性。原始压力保留，渲染将压力限制到0–1并使用平方根响应；实时追加同一填充轮廓，回放和导出共用 InkBrush，避免逐段半透明叠加接缝。荧光笔固定宽度。

按触点ID和工具类型优先处理触控笔，次要手指的取消不会取消触控笔。误触过滤为可关闭的启发式（接触面积/笔尖接近后的500ms），默认禁用书写中的单指平移；双指移动缩放。临时笔迹独立于模型，完成才建立历史并保存；取消不产生撤销项，橡皮保留临时原列表以便取消。

沉浸模式使用系统 Insets 控制隐藏双栏，边缘滑动临时唤出；保留明确退出与保存状态。平板桌面窗口的系统标题栏由系统控制。上一版签名密钥未在重置后的环境中保留，0.4 Debug 使用并行预览包保护原库，并在项目外保留本地预览密钥以便后续复用。

## 2026-10-10 开源平滑

适配 perfect-freehand 的位置 streamline 步骤，MIT 声明随 APK 打包。先采用轻量增量平滑，不改变原始采样/可编辑数据，也不把设备没有报告的压力伪装为硬件压感；完整轮廓算法和 AndroidX Ink 仍需验证。笔刷模式逐笔持久化，旧数据默认保持原样，笔迹编码3兼容读取1/2。
