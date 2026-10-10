# Licenses and dependency inventory

OpenNote authored code: MIT (LICENSE). Original name/icon; no Goodnotes/MarginNote code or brand assets.
Android SDK/Gradle build tools: development toolchain; Android SDK license terms apply, not bundled in source delivery.
Android framework PdfRenderer/Canvas/SQLite: platform APIs, no bundled third-party PDF native library.
AndroidX Activity and Compose: Apache-2.0; Kotlin runtime/compiler: Apache-2.0; Gradle: Apache-2.0; Android Gradle Plugin: Apache-2.0; JUnit: EPL-1.0. Exact versions pinned in gradle/libs.versions.toml. This is an initial inventory, not a completed automated transitive license audit.

Runtime coordinates are enumerated from the exact Gradle runtime lockfile in docs/third-party/runtime-inventory.tsv. Embedded LICENSE/NOTICE texts from resolved runtime AAR/JARs are retained in that directory. Missing embedded notices are explicitly flagged for upstream/POM review; this extraction is not described as a full legal audit. Platform PDF code is not separately redistributed.

POM声明许可清单在docs/third-party/pom-license-inventory.tsv；已保留Apache-2.0全文。二进制中未发现嵌入的LICENSE/NOTICE文本，不能把提取结果当作已经找到全部声明。

perfect-freehand position streamline adaptation: MIT, Copyright (c) 2021 Stephen Ruiz Ltd. The implementation scope and modifications are documented in docs/OPEN_SOURCE_INK.md; full copyright/permission text is bundled in app/src/main/assets/licenses/perfect-freehand.txt. No JavaScript runtime or entire upstream outline engine is bundled.
