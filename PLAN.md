# Stages and gates

S0: environment audit, decisions, architecture, formal requirements extraction; runnable Gradle initialization.
S1: Compose project; PDF SAF import, on-demand page rendering, native vector pen input, SQLite transaction saves; APK + host tests. Gate requires actual-device open/write/restart recovery; mark pending when device unavailable.
S2: insertion, resize, deletion choice, mapping, undo/redo; property/regression tests + editable persisted gaps. Gate requires visible source/ink movement and restart verification on Android.
S3: pressure refinements, foreground low-latency rendering, partial erase/lasso/shape recognition and real stylus endurance/latency measurements.
S4: raster PDF export validation; later full ZIP64 library backup/restore with manifest hashes and safe staging. End-to-end Android integration required.
S5: tile rendering/LRU/virtual scrolling; large-file benchmarks and library management.

Proceed with S0/S1 and the minimal S2 + export vertical slice, run available tests, and clearly separate code delivered from gates actually passed. Do not claim S3–S5 complete.
