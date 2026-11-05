# Second Brain AI — العقل الثاني

**Owner:** محمود الجمصي — Mahmoud Elgamsy  
**Contact (supplied by owner):** 056228419  
**Version:** 0.2.0 Alpha — Android Kotlin / Jetpack Compose

## What you get

- Original dark navy + warm-gold RTL design, matching `docs/APPROVED_DARK_DESIGN.png` in visual direction, with real Compose components (not a screenshot).
- On-phone-only SQLite task/project/topic/workspace data; voice and attachments in app folders; no cloud database or sync.
- Tasks: status, priority, owner, dates, local reminders, history, sharing from other apps, file attachments.
- Normal offline mode: local tasks, calendar/reminders, WhatsApp-ready text reports and archived Executive Snapshots.
- Opt-in smart mode: user-entered API key, speech transcription (gpt-transcribe), Egyptian/Arabic task extraction from text, chat against **at most 25 locally selected task summaries**, text polishing of a selected report.
- AI-extracted tasks are **only saved after the user presses Approve**.
- OpenAI Responses requests use `store:false` to avoid persistent response storage by default.
- Estimated API spend locally tracked, and calls blocked at a conservative approx. **15 SAR** monthly buffer toward user's 20 SAR target. Provider billing is authoritative and may differ; also configure spending limits / no auto-recharge in provider billing settings.
- No PDF/Word/Excel export. Reports are editable text and Android Share Sheet opens WhatsApp/WhatsApp Business if installed.
- Copyright/contact credit included on home and settings screens.

## Build APK automatically on GitHub (without Android Studio)

1. Create a **new empty GitHub repository**. Public or private works; if private, review your Actions allowance.
2. Extract this ZIP on your computer. Upload **the contents**, including `.github/`, `app/`, `build.gradle.kts`, `settings.gradle.kts`, to the **repository root**. Do **not** upload the ZIP file as a single item.
3. Open the repository's **Actions** tab, enable workflows if GitHub asks, and select **Build Android APK**.
4. Select **Run workflow**, or let the workflow start automatically after a push.
5. After the job finishes **successfully**, open its **Artifacts** section and download **SecondBrainAI-debug-APK**. Extract it and install `app-debug.apk` on Android.
6. Allow installation of apps from your file manager if Android prompts. Keep the APK private if it contains private features; a debug APK is for **testing**, not a Play Store release.

The CI file is `.github/workflows/android-apk.yml`. Gradle is installed by the GitHub workflow; no wrapper JAR or local Gradle is needed. Minimum SDK API 26; compile/target API 35. For local build install Android SDK API 35 + Gradle 8.11.1 then run `gradle :app:assembleDebug`.

## Activate AI, safely

1. In the running app, go to **المزيد → اتصال OpenAI API** and paste **your own API key**. Never paste a key in source, Gradle, repo, issue or chat.
2. Save it on the **phone**; only the key is encrypted via Android Keystore. Task records/files are not app-encrypted as requested.
3. Toggle **الوضع الذكي**, then tap **محادثة** on Home / **فتح المساعد الذكي** in Settings.
4. To transcribe speech, record in the home screen and then open Smart Assistant → **تفريغ آخر تسجيل محفوظ**. The chosen audio recording is sent only after tapping this button.
5. For task extraction, paste/type your message, tap **استخرج المهام**, check proposals, then press **اعتماد وحفظ**.
6. Ask conversational questions within a selected workspace; only a small task-summary context is included in that explicit request.
7. In Reports, generate a **local** report; optionally tap **صياغة إدارية ذكية بالـ API** to send its text for polishing. Review it and save a Snapshot / open Android Share.

**Privacy / security limitations:** Calling a cloud API sends the selected data to OpenAI, even though the application has no cloud database. API keys embedded in client apps cannot be made as safe as keys held behind a server-side proxy; Android Keystore reduces risk at rest but cannot eliminate compromised-device and live-extraction risks. This personal app intentionally uses direct API calls, which should be restricted to a low-spend project key. Verify the service's current data-retention policy and limits yourself.

**Billing:** The local budget is an estimate, not a guaranteed hard ceiling. Tokens can differ from estimated characters, audio duration and model pricing can change, and provider usage records may be delayed. Do not use a high-balance API key; disable automatic recharge and configure provider limits/alerts. A successful request consumes credits even if you later discard the result.

**Storage:** The database lives in Android's private database directory (`secondbrain.db`), while Voice and Attachments are app-owned files. Their path is visible in Settings; Android's file manager may restrict direct access to the private directory. A manual unencrypted ZIP export can be saved anywhere through Android's document picker, and **that exported ZIP is not automatically removed when the app is uninstalled**.

## API implementation

- Text: `POST https://api.openai.com/v1/responses`, model `gpt-6-luna`, `store:false`, `instructions`, `input`, output parsed from `output[].content[].text`.
- Recorded speech: `POST https://api.openai.com/v1/audio/transcriptions`, model `gpt-transcribe`, multipart `.m4a`, 25 MB size guard.
- Requests are direct HTTPS, on `Dispatchers.IO`; errors are displayed in-app.
- Do not add actual API keys to the repo, Android resources, workflows or README.

## Current validation status

This is a **source alpha**, not an already tested production release. The environment used to prepare the source did not contain Android SDK/Gradle, so a device-validated APK could not be built here. GitHub Actions will compile it and report any build errors clearly; open the successful build artifact to get the APK. Logic tests for local reporting and frozen Snapshot comparisons run via `tests/CoreTest.kt`. Real device tests are required for mic permissions, reminders, API networking, Arabic recognition and file access.

## Planned improvement backlog

- Task editing from AI proposals before final approval; linking to an existing case/project by ID with ambiguity review.
- Smart follow-up rules, background-safe notification tuning, notification categories and exact/time-zone aware schedules.
- Optional local speech recognition engine when available, deeper topic memory and semantic search.
- Full unencrypted restore flow for manual ZIP backup (currently export only).
- Multi-format preview of attachments, UI responsiveness/accessibility audit on foldables, automated Compose UI tests and a signed release APK.

© 2026 Mahmoud Elgamsy — محمود الجمصي. All rights reserved.
