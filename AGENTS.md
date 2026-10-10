# OpenNote project instructions

- Formal requirements: `docs/REQUIREMENTS.txt` (OpenNote Android specification v1.0).
- Canonical remote: https://github.com/Ryon2333/openNote.git. The user requires an initial sync and a push after every subsequent update. Commit tested changes and push this remote; never claim a push succeeded without checking its result. When authentication is unavailable, retain commits and clearly report the blocker.
- Prioritize PDF reading, editable vector ink, in-page dynamic whitespace and reliable saves. Native Kotlin/Compose outer UI; no WebView handwriting engine.
- Interaction must be clear and forgiving: show save/progress/failure states, support cancellation, block conflicting edits, confirm recovery/deletion choices, and keep existing data on failure.
- Run build, relevant unit tests and Lint. Android instrumentation / OPPO stylus performance must be reported separately from host tests; do not infer performance from compilation.
- Do not commit local.properties, credentials, private PDFs, Gradle caches or build directories.

- Editor interaction preference (2026-10-10): Goodnotes-like pen selection and persistent pen box; document tabs and tool modules may collapse with natural flat-style motion. Whitespace must use visible drag handles and live preview rather than numeric input as its primary action.

- Writing preference (2026-10-10): stylus-first palm rejection, pressure-sensitive ink, immersive writing with both system bars hidden, OneNote-like flat visual hierarchy and Goodnotes-like compact collapsible tool controls. Canceled input must not create saved ink or undo entries. Debug preview is a separate package to protect existing installations; reuse the locally retained preview signing key when available, never commit keys.

- Open-source preference (2026-10-10): use established open-source handwriting algorithms when suitable; record actual integration scope, upstream source and licenses, preserve raw editable samples, and verify live/replay/export consistency.
