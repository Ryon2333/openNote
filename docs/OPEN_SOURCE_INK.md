# 开源笔迹平滑适配

本轮原生适配 [perfect-freehand](https://github.com/steveruizok/perfect-freehand) 的 `getStrokePoints` 位置 streamline 步骤。检视的上游提交为 `176e00f2399f4969e1b0965c5921d96a3e50ce9f`。原作者 Stephen Ruiz Ltd，MIT；完整许可随 APK 打包于 `assets/licenses/perfect-freehand.txt`。

这不是完整 perfect-freehand 引擎移植：当前轮廓、压力曲线和 Canvas 渲染仍由 OpenNote 实现；没有引入其速度模拟压力、taper、拐角轮廓或全部参数。设备不报告压感时，仍使用固定粗细，不伪造硬件压力。

位置处理采用上游的逐点线性插值递推，OpenNote 参数为新位置权重 0.85，偏向跟手。为保留小字，不采用最短距离丢点、单点偏移或双点补点规则；抬笔后补齐真实终点。实时路径逐点追加，回放及 PDF 导出使用同一个 `InkBrush.LiveStroke` 构造器；没有每次触摸重建整条笔迹。

原始点的坐标、压感、时间、倾角始终保存；平滑结果只用于绘制。设置在画笔面板中，可关闭并持久保存。设置切换前先结束当前笔迹，已有笔迹保持落笔时的模式。新笔迹编码为版本3，向后读取1/2；JSON、留白分割、撤销和备份均保存 `streamlined`。旧笔迹默认不平滑，旧应用不能读取版本3备份。

## 无 Android 依赖的检查

从工程根目录执行（JDK17）：

```sh
mkdir -p /tmp/opennote-check
javac -d /tmp/opennote-check app/src/main/java/cn/jiang/opennote/Ink.java app/src/main/java/cn/jiang/opennote/StrokeStreamline.java tools/StrokeStreamlineCheck.java
java -cp /tmp/opennote-check cn.jiang.opennote.StrokeStreamlineCheck
```

五组检查覆盖原始点不变和真实压力保持、交替抖动、小于一个像素的短笔画、一万采样点的增量/回放一致，以及留白保存笔刷模式。`StrokeStreamlineTest` 将同一检查纳入 Gradle 主机测试。

这些检查不能代替 OPPO 笔尖延迟、动画帧率、Canvas 像素一致性及持续书写验收。下一步可继续评估 AndroidX Ink 的低延迟输入/渲染，但需要在真实设备上验证依赖、数据模型迁移和渲染后端后再替换。
