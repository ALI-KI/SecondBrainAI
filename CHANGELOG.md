# v0.4.0 — Direct Dictation & Task Routing

- Live streaming-like on-device Android SpeechRecognizer with partial transcription, voice stop command and optional system speech consent.
- Spoken command «وزع المهام» and one-tap distribution, plus explicit date extraction and editable due/follow-up dates.
- Conservative per-task workspace routing, never default to View; manual confirmation on ambiguous cases.
- No automatic API upload of projects; explicit toggle allows project-name context if requested.
- Manual task editor requires explicit workspace; never assumes the first workspace.
- New routing/voice/date unit tests; versionCode 5, same app ID and database schema.
- See `UPDATE_V04_AR.md` for testing and signing caveats.

# Changelog

## 0.3.0 — fixes release

**Bug fixes**
- Shared items (WhatsApp/others) are no longer imported twice when the screen rotates.
- Attachments keep their real file name and extension, and can be opened (and deleted) from the task editor.
- Notification icon is now a monochrome vector (`ic_stat_brain`), no more white square.
- "＋ موضوع" and "＋ مشروع" now ask which project / workspace they belong to instead of guessing.
- Deleting a task removes its history, attachment records, attachment files and its pending reminder; delete asks for confirmation.
- Task history logs only real changes (status, owner, due, follow-up, priority, move, details) with date and time; saving without changes writes nothing and does not touch `updated_at`.

**Improvements**
- Native date + time pickers for due date and follow-up (no more typing `yyyy-MM-dd`); quick follow-up chips: in 1 hour, tomorrow 9 AM, next Sunday 9 AM.
- App lock using the phone's own credential (fingerprint / face / PIN / pattern): required on open and after 1 minute in the background. While on, Recents preview and screenshots are blocked. It guards the UI only; data files are not encrypted.
- Voice and attachment files moved to private internal storage; v0.2 files migrate automatically (database version 2).
- Shared files are titled with their file name; failed attachment saves are reported.
- Unit tests moved to `app/src/test` and run in the GitHub workflow before the APK build.

**Known gaps (planned for 0.4)** — search, calendar, morning digest / overdue engine, people entity, topic story screen, link-to-existing-topic suggestions, richer Snapshot comparison, backup restore.

## 0.3.1 — reliability follow-up fixes (source)

- **Recorder & rotation:** Android configuration changes for orientation and screen dimensions no longer recreate the recording Activity; for other Activity destruction paths an active recording is finalized and linked before the database closes. This is not a foreground recording service; unexpected OS process termination can still interrupt a recording.
- **Explicit topic parent:** Adding a topic from the task list never chooses the first project. The user must select a project chip, then tap Save.
- **Dual reminders:** Follow-up time and due date are separate keyed alarms with separate notifications. Changes/deletion cancel both; stale broadcasts are rejected by checking their recorded timestamps against the current task. Rebuild alarms on reboot, app upgrade and time/time-zone changes.
- **Repeatable legacy migration:** Old v0.2 files in app-specific external storage are copied to internal storage, checksum-verified (SHA-256), linked by transactional database update and only then old copies are deleted. On failure the old reference remains and migration retries on the next database open; Settings shows the number of unresolved legacy attachments. Original v0.2 filenames cannot be recovered if they were not stored, but common formats are recognized by file signatures.
- **Lock after process death:** The UI's prior `unlocked` state is no longer restored from a Bundle. A fresh Activity requires device authentication whenever app lock is enabled.
- **GitHub Actions:** Exactly one workflow, `.github/workflows/build-apk.yml`, based on the pinned Ubuntu 24.04 runner with the preinstalled Android SDK; unit tests run before APK build. Removed the alternate `android-apk.yml` workflow.
- Android version `0.3.1`, code `4` so it does not downgrade a previously installed `0.3.0` by version number.

**Important signing caveat:** A fresh GitHub debug build may use a different debug certificate. Even with a higher versionCode Android refuses an in-place update signed with a different certificate. Do NOT uninstall a copy that contains valuable data; exporting a ZIP backup is supported but importing it back into the app is not yet implemented. For smooth future updates, establish a consistent signing keystore before relying on this app for irreplaceable data.
