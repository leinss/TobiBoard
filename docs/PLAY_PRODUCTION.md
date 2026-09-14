# Play production checklist

Written 2026-09-05 against 6.8.16 / 6816. Covers the eight items the 2026-09 audit found
missing for a production release (`docs/AUDIT_2026-09.md` § Track 3, G-18 to G-25).

Every answer below is derived from the code and cited. The intent is that the owner reads a
console field, finds it here, and copies the answer. Where a field needs a fact only the
console holds, the row says so and there is a blank to fill in.

Two related files: `docs/CLAIMS.md` (what the listing may claim) and `docs/PLAY_PUBLISHING.md`
(how `make publish-play` works, git-excluded, see G-1 at the end).

## 1. Current state (G-18, G-17, G-25): owner reads the console

Nothing in this repo records the live Play state, and the last written record
(`APP_READINESS.md:31`, "Play alpha 6816", dated 2026-07-12) predates the 6816 release of
2026-08-31, so it is not evidence. Fill this in from the console and keep it current.

| Field | Value | Read it from |
|---|---|---|
| Developer account type | | Play Console, Account details. Personal or organisation decides whether the 12-tester rule applies. |
| Account creation date | | same page. Personal accounts created after 2023-11-13 are subject to the closed-testing rule. |
| Highest track with a live release | | Release, Testing, and Production overview |
| versionCode live on that track | | should be 6816 |
| Review status | | Publishing overview |
| Testers opted in | | Testing, Closed testing, the track's tester list |
| Production access granted | | Release, Production. Blocked until section 4 is done. |

## 2. Data Safety form (G-19)

The developer receives nothing. TobiBoard has no backend, no analytics SDK and no crash
reporter. Every off-device transmission is to a service the user chose, using the user's own
credential, triggered by the user.

Four hosts appear anywhere in `app/src/main/java/helium314/keyboard/latin/voice/`:
`huggingface.co`, `openrouter.ai`, `api.ppq.ai` and `github.com`. Nothing else is contacted.

### Top-level answers

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | audio and typed text reach a third party when the user selects a cloud provider. Answer Yes even though the exemption in section 2.3 arguably applies; a No that a reviewer disagrees with costs a rejection. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | every request in `OpenRouterClient.kt` and the downloader is HTTPS |
| Do you provide a way for users to request that their data be deleted? | **No** | the developer holds no data to delete. Explain in the field: data is sent only to the user's chosen provider, under that provider's own controls. |
| Does your app have a privacy policy? | **Yes** | see section 5 |

### Per-type answers

For each type: **Collected** means it leaves the device. **Shared** means it reaches a third
party. **Ephemeral** means it is not retained after the request. **Optional** means the user
can use the app fully without it.

| Data type | Collected | Shared | Purpose | Optional | Ephemeral | Evidence |
|---|---|---|---|---|---|---|
| Audio, "Voice or sound recordings" | Yes | Yes | App functionality | Yes | Yes | sent only when the provider is OpenRouter or PayPerQ. On the default `local` provider (`Defaults.kt:184`) audio never leaves the device. The recording lives in the app cache for one request and is deleted (`PRIVACY.md:43-47`). |
| Messages, "Other in-app messages" | Yes | Yes | App functionality | Yes | Yes | the text being fixed: the selection, or the field's contents when nothing is selected. Sent only on a cloud provider. This is the closest type in Play's taxonomy for keyboard text; if the reviewer pushes back, "Files and docs" is the fallback. |
| Personal info, "Other info" | No | No | n/a | n/a | n/a | the OpenRouter / PayPerQ API key and the Hugging Face token are stored on device in `EncryptedSharedPreferences` (`SecretStore.kt:106-113`), excluded from backup, and sent only as an `Authorization` header to the service the user chose. The developer never receives them. |
| Contacts | No | No | n/a | n/a | n/a | `READ_CONTACTS` is the inherited contacts-dictionary feature. `ContactsContentObserver.java` reads names to build local typing suggestions. Nothing derived from contacts is transmitted. |
| App activity | No | No | n/a | n/a | n/a | no analytics or usage SDK in `app/build.gradle.kts` |
| App info and performance, crash logs, diagnostics | No | No | n/a | n/a | n/a | no crash reporter. `docs/AUDIT_2026-09.md` records the absence of telemetry as verified. |
| Device or other IDs | No | No | n/a | n/a | n/a | nothing reads an advertising ID, `ANDROID_ID` or an installation ID |
| Location, Financial, Health, Photos and videos, Calendar, Web browsing | No | No | n/a | n/a | n/a | no permission, no code path |

### The model download

The first time a user enables an on-device feature, the app fetches model files from
`huggingface.co` (`ModelInfo.kt`). This is a **download, not a data transmission**: the request
body is empty and the only header that identifies anything is the optional Hugging Face bearer
token on the Gemma path. **Do not declare it as data collection.** It is disclosed in the
listing and in `PRIVACY.md:11-24` because users deserve to know about a 670 MB download, not
because Data Safety requires it.

## 3. Content rating, IARC questionnaire (G-20)

| Question | Answer |
|---|---|
| App category | Utility, Productivity, Communication or Other |
| Violence, sexuality, profanity, controlled substances, gambling, horror | No to all. TobiBoard displays no content of its own. |
| Does the app let users interact, communicate or exchange content with each other? | No. There is no account, no server and no user-to-user channel. |
| Does the app share the user's location with other users? | No |
| Does the app allow purchases of digital goods? | No |
| Does the app contain user-generated content that is shared with other users? | No |
| **Does the app generate content using AI?** | **Yes.** Voice-to-text and text fix both produce model output. |
| Does the app provide a way for users to report or flag objectionable AI-generated content? | **Yes**, and it is shipped. See below. |
| Does the app contain ads? | No |

**The AI-reporting answer is backed by code, verified 2026-09-05 (this closes G-24).**
`ReportConfig.kt` holds the single destination, `inquiry@leinss.xyz`, and builds a pre-filled
`ACTION_SENDTO` mail intent carrying the AI output. Both AI surfaces call it through
`SuggestionStripView.launchAiOutputReport`: the text-fix result overlay
(`TextFixOverlayView.kt:48,119`) and the post-insertion undo bar (`UndoBarView.kt:25,53`). The
user reviews and sends the mail themselves, which is the consent step for sharing the quoted
text. A device with no mail app gets a toast, not a crash.

Expected outcome: Everyone / PEGI 3 / USK 0 / ESRB Everyone.

**Answer the questionnaire again after any change to the AI surfaces.** A rating is invalidated
by a changed answer, not by a new version.

## 4. Closed testing: 12 testers, 14 continuous days (G-21), owner work

Play requires personal developer accounts created after 2023-11-13 to run a closed test with
**at least 12 testers opted in continuously for 14 days** before production access is granted.
Neither this repo nor the hub records a roster or a start date, so as far as the written record
goes the clock has not started.

| Field | Value |
|---|---|
| Rule applies (personal account created after 2023-11-13) | yes / no: |
| Closed track name | |
| Tester list, 12 or more addresses | |
| Opt-in confirmed for each tester | |
| Day 1, all 12 opted in | |
| Day 14, earliest date to apply | |
| Production access applied for | |

Two traps. The count is **opted in**, not invited, so an invitation nobody accepted does not
count. And the 14 days are **continuous**: a tester who opts out resets the clock. Confirm the
count on day 1 and again on day 14.

## 5. Privacy policy URL (G-23)

Play requires a stable public URL because the app holds `RECORD_AUDIO`.

**Use now:** `https://github.com/leinss/TobiBoard/blob/main/PRIVACY.md`. It is public, stable,
and its content is current (the audit checked it and found it accurate in substance).

**Nicer, later:** the F-Droid workflow already publishes a GitHub Pages site to
`leinss.xyz/TobiBoard`, so `leinss.xyz/TobiBoard/privacy` would be on the same host as the repo
and the landing page. It needs one step in `.github/workflows/fdroid-repo.yml` to render
`PRIVACY.md` into `site/privacy.html` next to the existing `site/index.html`. Note that the
workflow only runs on a release or a manual dispatch, so the page would refresh on release
cadence rather than on every push to `PRIVACY.md`.

## 6. Screenshots (G-22)

Play shows three phone screenshots and nothing else. Without 7-inch and 10-inch sets the
listing is down-ranked on tablets and foldables. All existing images are en-US only; the
listing declares no other locale, so one locale is consistent, not a gap.

### Present

`fastlane/metadata/android/en-US/images/`: `icon.png` 512x512, `featureGraphic.png` 1024x500,
and three `phoneScreenshots` at 1080x2400.

### To capture

Play wants a minimum of two per form factor. Four each is better because the listing shows the
carousel. Capture with the keyboard visible and a real text field focused, not a settings
screen alone.

| # | Screen | State to set up |
|---|---|---|
| 1 | Keyboard over a text field, toolbar visible | default theme, default toolbar, no overlay |
| 2 | Long-press Return, the action popup open | a normal field, so the popup shows clipboard, mic, text fix and add-to-dictionary. Do not use a password field: since W7-P1 the popup correctly drops the mic there. |
| 3 | Recording overlay, mid-transcription | on-device provider. The PREPARING state added in W7-P5 is the honest first-use shot; the TRANSCRIBING state is the everyday one. Pick TRANSCRIBING for the listing. |
| 4 | Text-fix result overlay, Replace and Discard visible | on-device provider, a short rough sentence selected |
| 5 | Clipboard history with pinned and labelled entries | needs three or four clips, one pinned, one labelled. Do not shoot the empty state added in W7-P6 for the listing. |
| 6 | Voice settings screen | shows the on-device provider selected and no API-key field, which is the product's whole argument |

**Capture procedure**, once a suitable AVD exists:

```
make emulator-up          # boots the Pixel 6 API 34 AVD
make sim-install
make ime-enable
make launch-typing
make screenshot           # -> artifacts/screenshot.png, one frame per call
```

**Not captured in this package, and why.** `make screenshot` captures whatever device is
attached, and the only AVD the Makefile defines (`avd-create`) is the Pixel 6 phone the W7 UI
work used. There is no tablet AVD target, so the 7-inch and 10-inch sets need a new AVD
created by hand first. Three further constraints are on record from the W7 handoffs: the AVD
needs 4 GB of RAM or the 1.6 GB text-fix model kills the IME process, `screencap` needs about
10 seconds of settle time after a Compose screen appears or it returns the previous frame, and
`adb install -r` resets `default_input_method` so `ime-enable` has to be re-run after every
install. Host load during this package was 17 to 25, where the earlier sessions recorded
10 seconds per frame. **Owner or a follow-up package.**

## 7. Rollout steps

Do these in order. Nothing here is reversible by an agent, so all of it is owner work.

1. Confirm sections 1 to 5 are filled in and both forms are submitted and accepted. Publishing
   overview will show what is still blocking.
2. Finish the 14-day closed test (section 4) and apply for production access.
3. Cut the release that carries the W7 work. `make bump-patch`, edit the changelog stub with
   the draft in section 8, `make check`, then `make ship CONFIRM=1`.
4. `make publish-play TRACK=internal`. Note that since PR #18 `publish-play` sets
   `release_status: completed` for tester tracks, so it goes live to testers immediately rather
   than landing as a draft. `docs/PLAY_PUBLISHING.md:49-51` still says "draft" and is wrong.
5. Verify testers receive it and that the listing renders. `make store-listing VALIDATE=1` is a
   dry run that catches metadata Play would reject.
   **One trap.** `publish-play` checks the 500-character release-notes limit for the current
   versionCode only (`Makefile:483-490`). Four historical changelogs are over it: `1001.txt`
   (1163), `1003.txt` (501), `6400.txt` (841) and `6700.txt` (736). They are inert for a normal
   release, but `store-listing` uploads changelogs for every version it finds, so run it with
   `VALIDATE=1` first. If Play rejects them, trim those four files; they are the record of
   releases nobody will read release notes for.
6. Promote to production **staged at 20 percent**. Watch Android vitals, crash rate and ANR
   rate for 48 hours before widening.
7. Halt the rollout on any crash-rate spike. The internal track stays as the canary. There is
   no crash-reporting SDK by design, so vitals plus GitHub issues plus the `inquiry@leinss.xyz`
   inbox are the entire signal. Watch that inbox during the rollout.
8. Only after production is live and verified does the announcement gate in the hub's launch
   runbook open.

## 8. Release notes for the next version, draft

Goes in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` when the version is
bumped. Play's limit is 500 characters and `make ship` enforces it. Drafted from the W7 changes
in PRs #38 to #41. User-facing wording, no internal identifiers.

```
- The microphone is now refused in password and incognito fields, in the long-press menu too.
- Voice and text fix now tell you what is missing instead of silently opening Settings.
- A separate "Preparing the model" state, so a slow first run no longer looks like a hang.
- Text fix can be put on the toolbar, and the toolbar now scrolls visibly.
- The on-device models are released when memory runs short.
- Fixed a crash when switching away from TobiBoard to another keyboard.
```

That is 479 characters, so there are 21 to spare. If the bump also changes something else, cut
the memory line first: it is the least visible to a user.

Second draft, shorter, if the first is trimmed for a patch release:

```
- The microphone is refused in password and incognito fields.
- Voice and text fix now name the missing setting instead of silently opening Settings.
- A "Preparing the model" state, so a slow first run does not look like a hang.
- Fixed a crash when switching to another keyboard.
```

## 9. G-1: the three git-excluded docs, owner decides

`docs/PLAY_PUBLISHING.md`, `docs/STORE_LAUNCH.md` and `docs/EMULATOR.md` are listed in
`.git/info/exclude`, are untracked, and are absent from `origin/main`. They are invisible in
every worktree and to every future contributor, while `Makefile:467`, `fastlane/Fastfile:2`,
`app/build.gradle.kts:185` and the hub's `APP_READINESS.md:31` all cite them.

Recommendation, in one line each:

- **`docs/PLAY_PUBLISHING.md`: commit it, after fixing three things.** It documents
  `make publish-play`, which is in the tracked Makefile, so a contributor needs it. It names the
  package as `helium314.keyboard.tobiboard` (actual: `xyz.leinss.TobiBoard`), says uploads land
  as a draft (no longer true, see section 7 step 4), and its one-time-setup section names
  keystore and service-account paths. Those paths are filenames, not secrets, but check before
  committing.
- **`docs/STORE_LAUNCH.md`: delete it.** It opens with "Nothing here is implemented yet", says
  "Target SDK 35", carries the wrong applicationId, and its one piece of unique content, the
  Data Safety sketch at `:54-59`, is superseded by section 2 of this file. Delete the file and
  the references.
- **`docs/EMULATOR.md`: commit it.** `app/build.gradle.kts:185` cites it and the emulator traps
  it records cost several sessions to rediscover. No secrets.
