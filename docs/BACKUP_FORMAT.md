# OpenNote backup format v1

- `manifest.json`: format=OpenNote, version=1, created（Unix毫秒）, totalBytes（不含manifest自身）, files[{path,size,sha256}]。
- `library.json`: version=1, documents[{id,pages}], folders=[]。文档ID为标准UUID。
- `documents/<id>/original.pdf`: 未修改的源文件。
- `layouts/<id>.json`: Notebook schema1，页面UUID/尺寸/measured/gaps、当前页、标题和文字卡片。strokes数组必须空，笔迹单独存储。
- `annotations/<id>/<page>.ink`: 大端int32版本1、int32笔数，然后重复int32字节长度 + NoteStore stroke codec v1（二进制原始采样）。按存储顺序恢复。
- `settings/preferences.json`: version1、finger偏好。本轮恢复保留当前设备设置。
- `checksums/algorithm.txt`: SHA-256；每个payload实际哈希位于manifest。manifest不自我哈希；本格式无签名或加密。

Java/Android ZipOutputStream负责按需ZIP64；主机测试验证>65535条目的ZIP64 EOCD。>4GB文件仍须Android分档验证，不以接口名称推定已通过。

恢复流程：本地复制→扫描中央目录并应用资源门槛→逐文件流式解压与SHA-256→验证索引/布局/笔迹/源PDF→预览确认→生成新文档UUID与持久恢复日志→移动文件目录→SQLite单事务写入所有文档→删除日志。失败回滚数据库并清理新目录；重启通过日志判断目录是否属于已提交文档，保留已提交项、清理孤立项。突然断电的文件系统持久性仍需真机测试。

v1兼容矩阵：仅接受OpenNote manifest1 + library1 + Notebook schema1 + stroke codec1 + settings1；拒绝未来版本、未知额外文件和文件夹，防止静默丢字段。仅合并副本，没有整库替换/去重策略。
