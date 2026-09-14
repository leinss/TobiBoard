# Public claims, re-verified

Re-verified 2026-09-05 against `main` after W7 packages P1 to P8 (PRs #38, #39, #40, #41)
landed. Version at the time of the check: `versionCode = 6816` / `versionName = "6.8.16"`
(`app/build.gradle.kts:51,52`), latest published release `v6.8.16` (2026-08-31).

This file replaces the claims table in `docs/AUDIT_2026-09.md` § Track 3, which was written
against 6.8.16 before P1 to P8 and is now a historical record. Where the two disagree, this
file wins.

**Scope.** Every claim TobiBoard makes in public: `README.md`, the Play listing
(`fastlane/metadata/android/en-US/`), the F-Droid recipe and the summary F-Droid reads from
the fastlane metadata, and the four announcement drafts in the portfolio hub's
`launch-runbooks/tobiboard.md`. `PRIVACY.md` is included where a store claim leans on it.

**Rule.** A claim ships only if this file names the file and line that makes it true. A claim
nobody can point code at is reworded or dropped, not softened.

## How to re-run this check

The claim set changes whenever copy or a default changes. Re-verify at least these:

| Fact | Where the truth lives |
|---|---|
| Both AI features ship off | `Defaults.kt:183,230` |
| The default provider is on-device | `Defaults.kt:184` (`PREF_AI_PROVIDER = "local"`) |
| Default on-device text-fix model | `Defaults.kt:233` (`qwen2.5-1.5b-instruct-q8`) |
| Model download sizes | `ModelInfo.kt` `sizeBytes` fields, summed by `ModelInfo.totalBytes` |
| What leaves the device | the four hosts in `latin/voice/`: `huggingface.co`, `openrouter.ai`, `api.ppq.ai`, `github.com` |
| No telemetry | no analytics or crash SDK in `app/build.gradle.kts` |
| Clipboard encryption | `ClipboardCipher.kt`, `ClipboardWriteMode` |
| Secrets never logged | no `Log.*` call in `app/src/main/java` interpolates a key or token |
| APK size, latest tag | `gh release view --repo leinss/TobiBoard` |

## Verdicts

23 rows carried forward from the audit, plus 4 found in this pass. Verdict key: **true** (the
evidence column names the source), **fixed** (was wrong, corrected in this package), **owner**
(cannot be settled from the code).

### README (`README.md`)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | On-device voice, on-device text fix and a clipboard manager, all local by default, no account, no key | true | `Defaults.kt:184` sets the provider to `local`; neither path reads a key on that provider |
| 2 | Fixed the caps-lock-gets-stuck and keyboard-stops-typing bugs, hardened the input connection | true | five commits on the caps-lock state machine (`a4d28591f`, `fe2248979`, `1e35003d7`, `95a09402a`) and `3da2aadea` "clear shift lock on hide and auto-reconnect broken input connection" |
| 3 | "No audio leaves your phone" on the default path | true | `LocalSherpaEngine` makes no network call; the only hosts in `latin/voice/` are reached by the cloud clients and the downloader |
| 4 | On-device model is NVIDIA Parakeet TDT 0.6B v3, sherpa-onnx export | true | `ModelInfo.kt:60-62` |
| 5 | The speech model is multilingual and the app applies no language gate | **fixed** | was "multilingual: English, German, Spanish, French", asserted only in a source comment and two strings. `LocalSherpaEngine` contains no language handling at all, so the four-language list was never enforceable. Reworded to state the model is multilingual and that the app gates nothing. |
| 6 | A custom transcription prompt is available, on the cloud providers only | **fixed** | was "Add a custom prompt or vocabulary". **There is no vocabulary feature anywhere in the tree.** The prompt settings are gated on a cloud provider (`VoiceScreen.kt:150,158`) and `LocalSherpaEngine` takes no prompt. |
| 7 | Text fix runs on-device by default, no key | true | `Defaults.kt:184,233`; `LocalLiteRtEngine` takes the system prompt and runs through MediaPipe |
| 8 | Text fix cleans typos and awkward phrasing; both prompts are editable | **fixed** | was "or shifts tone (formal to casual)". There is no tone control. There are two editable prompts (`Defaults.kt:234,236`), and neither default mentions tone. |
| 9 | You review the rewrite before it replaces the original | true | `TextFixOverlayView` offers Replace and Discard before any commit |
| 10 | Clipboard history is on-device only and excluded from backups | true | recorded as verified sound in `docs/AUDIT_2026-09.md`; `PRIVACY.md:47-56` |
| 11 | Clipboard text and labels are encrypted at rest, AES-256-GCM, Android Keystore, Android 6+ | **fixed** | true but incomplete. Now also states hardware-backing is device-dependent (`ClipboardCipher.kt:69-79` requests neither StrongBox nor attestation) and that a Keystore that cannot produce a key makes the clip be dropped rather than stored in plain text (`ClipboardWriteMode.REFUSE`, W7-P1 and P7). |
| 12 | Pin, label, use counts, search from Settings | true | `ClipboardManagementScreen` |
| 13 | Zero Data Retention requested where the model supports it | **fixed** | the comparison table read "Zero Data Retention **enforced** by default". `OpenRouterClient.kt:256-262` emits `provider.zdr: true` only for catalog models with a known ZDR route, and its own comment says the goal is "use ZDR where possible, not fail closed". |
| 14 | API keys encrypted with the Android Keystore, excluded from backups, never written to logs | true | `SecretStore.kt:106-113` uses `EncryptedSharedPreferences`. On the log half: no `Log.*` call in `app/src/main/java` interpolates a key, token or secret; the four calls that print a response body go through `OpenRouterClient.sanitizeForLog` (`:603`) and are `BuildConfig.DEBUG`-gated. This closes **G-9**. |
| 15 | No backend, no analytics, no tracking | true | recorded as verified sound in `docs/AUDIT_2026-09.md`; no analytics or crash-reporting dependency in `app/build.gradle.kts` |
| 16 | Both features ship off; first-run download over Wi-Fi | **fixed** in W7-P3, sizes corrected here | `Defaults.kt:183,230`. The sizes said "about 660 MB (speech) and about 550 MB to 1.6 GB". Summed from `ModelInfo.kt` they are 670 MB (Parakeet, four files) and 547 MB to 1.6 GB (Qwen2.5 0.5B to Qwen2.5 1.5B). |
| 17 | Build needs JDK 17, Android SDK 36, NDK 28.0.13004108 | true | `app/build.gradle.kts:12,50`; the stale "SDK 35" was corrected in W7-P3 |
| 18 | Installs side-by-side with HeliBoard | true | `applicationId = xyz.leinss.TobiBoard`, distinct from `helium314.keyboard` |
| 19 | Free, no ads, no tracking, no paid tiers; support on Ko-fi | true | `.github/FUNDING.yml:3` |

### Play listing (`fastlane/metadata/android/en-US/`)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 20 | Title, short description | true | 73 of 80 characters; unchanged |
| 21 | "Everything runs on your device. Cloud is optional." | true | `Defaults.kt:184` |
| 22 | The listing never disclosed the first-run model download | **fixed** | this was **G-3**, a gap rather than a false sentence. The description now states that both features ship off, that the model is downloaded once from Hugging Face over Wi-Fi, and the sizes; the two feature bullets repeat it. |
| 23 | Clipboard "encrypted at rest with a hardware-backed key (Android 6+)" | **fixed** | this was **G-4**. Replaced with the `PRIVACY.md:52-54` wording: hardware-backed where the device supports it, plus the drop-rather-than-plaintext rule. |
| 24 | Custom prompt or vocabulary (voice), adjusts tone (text fix) | **fixed** | same two defects as rows 6 and 8, corrected the same way |
| 25 | OpenRouter ZDR requested by default "when the model supports it" | true | already correctly hedged; `OpenRouterClient.kt:256-262` |
| 26 | Changelog for 6816 | true | checked against the diff `cc96179f2..b6054ffaa` in the audit; 118 bytes, well under Play's 500 |
| 27 | Screenshots | see `docs/PLAY_PRODUCTION.md` § Screenshots | three phone shots, no tablet shots (**G-22**, still open) |

### F-Droid (`docs/fdroid/`)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 28 | The on-device text-fix model is "Gemma 3 1B INT4" | **fixed** | this was **G-5**, false. `Defaults.kt:233` is `qwen2.5-1.5b-instruct-q8`. Gemma is opt-in, gated behind Google's terms and a Hugging Face token (`ModelInfo.kt`, `requiresLicense`/`requiresAuth`). The recipe header now names Parakeet and Qwen2.5 as the defaults and Gemma as opt-in. |
| 29 | IzzyOnDroid is an available FOSS channel | **fixed** | this was **G-7**, and the same file contradicted itself two blocks later. The header now names the self-hosted repo as the only FOSS channel and points at the submission doc for why. |
| 30 | Recipe pinned to 6.8.6 / 6806 | **fixed** | this was **G-12**. Now 6.8.16 / 6816, `commit: v6.8.16`, `CurrentVersion`/`CurrentVersionCode` to match. |
| 31 | IzzyOnDroid doc: APK ~116 MB, latest release v6.8.6, main at 6.8.8 | **fixed** | this was **G-13**. `gh release view` gives 121 662 846 bytes = 121.7 MB for `TobiBoard_6.8.16-release.apk`, published 2026-08-31; main and the tag agree at 6.8.16/6816. The verdict (do not submit) is unchanged and the margin against the 30 MB limit has widened. |
| 32 | AntiFeatures `NonFreeNet` and `NonFreeDep` | true | MediaPipe `tasks-genai` and the prebuilt sherpa-onnx AAR are both in the APK |

### Announcement drafts (portfolio hub, `launch-runbooks/tobiboard.md`)

Not fixed here: that file lives in another repo. Every changed sentence, with its
replacement, is in `docs/ANNOUNCEMENT_NOTES.md`.

| # | Claim | Verdict |
|---|---|---|
| 33 | "Gemma 3 1B INT4 / Qwen2.5 via MediaPipe" as the local text-fix model | false, same as row 28 |
| 34 | Clipboard "encrypted at rest with a hardware-backed key (Android 6+)" as a claim safe to make verbatim | overclaimed, same as row 11 |
| 35 | "multilingual: en/de/es/fr" | unverifiable, same as row 5 |
| 36 | The distribution table, "latest served 6.8.6, main 6.8.8" | stale by ten releases |
| 37 | "API keys encrypted with Android Keystore, scrubbed from logs" | true, row 14 |
| 38 | "No GitHub Sponsors account yet" | true, `.github/FUNDING.yml` has only `ko_fi` |

## Left open

- **Two in-app strings still name four speech languages** (`strings.xml:1153,1156`). They are
  the last place the retired four-language list survives. Not touched here: this package
  changes documentation and store metadata only, and a string change is a product change with
  a translation cost. It is a copy fix, not a correctness risk, because the claim understates
  what the model does.
- **G-8 as an assertion about which languages work well** cannot be settled from the code and
  is not settled here. There is no language gate, so the honest claim is the one now shipped:
  the model is multilingual. Per-language transcription quality has never been measured on
  device and no claim should be made about it.
- **G-1**, the three git-excluded docs, is an owner decision. See `docs/PLAY_PRODUCTION.md`.
