# Announcement drafts: what changed

Written 2026-09-05 against `main` after W7 packages P1 to P8 landed (PRs #38 to #41).

The four announcement drafts (Show HN, XDA, r/fdroid, r/privacy) live in the portfolio hub, in
`launch-runbooks/tobiboard.md`. That file is in another repo, so it is not edited here. This is
the list of every sentence in it that is now false, stale or incomplete, with the sentence that
replaces it. The orchestrator carries these to the hub.

Ordered by the runbook's own sections. Evidence for each verdict is in `docs/CLAIMS.md`.

## § 0 Current distribution state

The whole table was written on 2026-07-10 and is ten releases behind.

| Runbook says | Replace with |
|---|---|
| Self-hosted F-Droid repo, latest served **6.8.6** | Self-hosted F-Droid repo, latest served **6.8.16** |
| GitHub Releases, latest **v6.8.6**, signed APK **~116 MB** | GitHub Releases, latest **v6.8.16**, published 2026-08-31, signed APK **121.7 MB** (121 662 846 bytes) |
| IzzyOnDroid ineligible: **116 MB APK vs 30 MB limit** | IzzyOnDroid ineligible: **121.7 MB APK vs the 30 MB per-file limit**. The verdict is unchanged and the margin has widened. |
| Google Play (internal track), latest **6.8.x** | Blank it and re-read the console. Nothing in either repo records the live track, and the last written record predates the 6816 release. `docs/PLAY_PRODUCTION.md` § 1 is the table to fill in. |

Whole paragraph, delete and replace:

> **Runbook:** "`main` is at **6.8.8/6808** (unreleased) — ahead of the latest release (6.8.6).
> Optional: cut a v6.8.8 release (tests green) to align channels before announcing. Not
> required for launch."

> **Replacement:** "`main` and the tag agree at 6.8.16/6816, but `main` now carries the W7
> changes on top of that version: sensitive-field guards, the rewritten consent and settings
> copy, the model-preparing state, the toolbar work and the on-device model lifecycle. A
> version bump and a release are required before announcing, because the drafts below describe
> behaviour that is not in v6.8.16. Release notes are drafted in `docs/PLAY_PRODUCTION.md` § 8."

## § 1 Claims that are TRUE and safe to make

Three of the seven bullets need changing.

> **Runbook:** "On-device voice-to-text via NVIDIA Parakeet TDT 0.6B v3 (multilingual:
> en/de/es/fr), audio never leaves the device by default."

> **Replacement:** "On-device voice-to-text via NVIDIA Parakeet TDT 0.6B v3. The model is
> multilingual and the app applies no language gate. Audio never leaves the device on the
> default provider."

Reason: the four-language list was asserted only in a source comment and two in-app strings.
`LocalSherpaEngine` contains no language handling at all, so it was never enforceable, and
upstream Parakeet v3 covers many more than four. The list understates rather than overstates,
which is why it is a rewording and not a retraction.

> **Runbook:** "On-device text-fix via a local LLM (Gemma 3 1B INT4 / Qwen2.5 via MediaPipe),
> no key needed."

> **Replacement:** "On-device text fix via Qwen2.5 1.5B Instruct through MediaPipe, no key
> needed. A lighter Qwen2.5 0.5B is selectable. Google's Gemma 3 1B is available too but is
> opt-in: it needs the user to accept Google's terms on the model page and supply a Hugging
> Face token."

Reason: false as written. `Defaults.kt:233` is `qwen2.5-1.5b-instruct-q8`. Naming a gated
Google model first, in a post aimed partly at r/fdroid and r/privacy, is the version of this
error that costs the most.

> **Runbook:** "Clipboard history is **encrypted at rest with a hardware-backed key (Android
> 6+)**, never uploaded/backed-up. (This is now TRUE — M2-1 shipped AES-GCM; do NOT repeat the
> old README's retracted 'encrypted database' overclaim in any earlier form — the current claim
> is accurate.)"

> **Replacement:** "Clipboard history is encrypted at rest with AES-256-GCM using an Android
> Keystore key, hardware-backed where the device supports it, on Android 6 and newer. It is
> never uploaded and is excluded from backups. If the Keystore cannot produce a key, the clip
> is dropped rather than stored in plain text."

Reason: two corrections. `ClipboardCipher.kt:69-79` requests neither StrongBox nor attestation,
so hardware backing is whatever the device provides, not something the app guarantees. And
since W7-P1 and P7 the failure mode is worth stating outright, because dropping a clip rather
than saving it readable is the strongest privacy claim in the app.

Two bullets are unchanged and now carry more evidence than they did:

- "No account, no API key required for the default LOCAL path" stays. `Defaults.kt:183,184,230`.
- "API keys encrypted with Android Keystore, scrubbed from logs" stays, and is now verified
  rather than asserted: no `Log.*` call in `app/src/main/java` interpolates a key or token, and
  the four calls that print a response body go through `OpenRouterClient.sanitizeForLog` and are
  debug-gated.

## § 1 Claims to AVOID

The four existing entries all still hold. Add one.

> **New entry:** "Do NOT say the features work the moment you install. Both AI features ship
> switched off (`Defaults.kt:183,230`), and turning one on starts a one-time model download of
> 670 MB for speech, or 547 MB to 1.6 GB for text fix. Say this in the post rather than letting
> a reader find it. The store listing and the README now both state it."

## Show HN draft

> **Runbook:** "Select rough text → Fix → cleaned in place via a 1B-class local LLM
> (MediaPipe)."

> **Replacement:** "Select rough text, hit Fix, review the rewrite, then Replace or Discard.
> The default is Qwen2.5 1.5B Instruct through MediaPipe, running locally."

Reason: two errors in one sentence. The default is 1.5B, not 1B-class. And "cleaned in place"
skips the review step, which is the part a privacy-minded reader cares about: nothing replaces
the original text until the user says so.

> **Runbook:** "Long-press Return → speak → polished text lands, all on-device (sherpa-onnx +
> Parakeet)."

> **Replacement:** "Long-press Return, tap the mic, speak, and polished text lands, all
> on-device via sherpa-onnx and Parakeet, after a one-time 670 MB model download."

Reason: true but it implies zero setup. Add the download.

> **Runbook:** "Why I built it, the '1.5B LLM inside an IME without getting LMK-killed'
> memory-lifecycle problem, and why post-processing beats prompting for 1B models."

> **Replacement, stronger and now backed by code:** "Why I built it, the '1.5B LLM inside an
> IME without getting LMK-killed' memory-lifecycle problem and how it is solved: one refcounted
> native handle with a deferred release, a five-minute idle timer and an `onTrimMemory` rule,
> shared by both engines. And why post-processing beats prompting at this model size."

Reason: this was the draft's technical hook and it was aspirational when written. W7-P7 shipped
it, so the post can point at `SharedNativeHandle` instead of describing a problem.

> **Runbook:** "Links: GitHub + self-hosted F-Droid repo + Play."

> **Replacement:** "Links: GitHub and the self-hosted F-Droid repo. Add the Play link only once
> the listing is public; the announcement gate is a live production rollout."

## r/fdroid draft

> **Runbook:** "AntiFeatures (NonFreeNet for optional cloud, NonFreeDep for the on-device
> runtime blobs) are declared in the recipe."

> **Replacement:** "The AntiFeatures are documented in `docs/fdroid/xyz.leinss.TobiBoard.yml`,
> which is the reference recipe for a future F-Droid main submission."

Reason, and this is the one worth fixing in the app rather than in the post. The recipe's own
header says the file "is NOT consumed by this repository". The self-hosted repo is built by
`fdroid update --create-metadata`, which generates metadata from the APK, and there is no
`fdroid/metadata/` directory in the tree, so **the served index carries no AntiFeatures at
all**. r/fdroid is precisely the audience that will open the index and check. Either soften the
sentence as above, or commit `fdroid/metadata/xyz.leinss.TobiBoard.yml` carrying the two
AntiFeature blocks so the claim becomes true. The second is better and is listed as an owner
item below.

> **Runbook:** "give the exact repo-add URL + fingerprint"

> **Note, not a correction:** the URL is `https://leinss.xyz/TobiBoard/repo`. The fingerprint is
> computed inside `.github/workflows/fdroid-repo.yml` and printed on the landing page at
> `leinss.xyz/TobiBoard`; read it from there rather than from any doc, so the post cannot carry
> a stale one.

The rest of that draft, being upfront that the app is not on F-Droid main and why, is
unchanged and still the right call.

## r/privacy draft

Unchanged in substance. One addition.

> **Add:** "Say that both AI features ship off, not merely that the cloud path is off. The
> current draft says the optional cloud path is off by default, which is true, but the stronger
> and equally true claim is that nothing AI runs at all until the user turns it on and
> downloads a model."

## XDA draft

Unchanged in substance. One addition.

> **Add to the install-options table:** a first-run note that the AI features need a one-time
> model download over Wi-Fi, 670 MB for speech and 547 MB to 1.6 GB for text fix. An XDA
> audience installs an APK and expects to know the real on-disk cost.

## § 3 Rollback and monitoring

> **Runbook:** "**Known device-test gaps** (unit-tested only; verify on-device before
> production promote): fresh-install clipboard, at-rest encryption, download-failure UX,
> on-device DE/ES/FR voice WER, add-to-dictionary flows."

> **Replacement:** "Known device-test gaps, narrower than they were. W7 added a UI flow suite
> (`make test-ui`, 10 instrumentation tests) and captured emulator evidence for the
> download-failure path, the wizard and the clipboard empty state. Still unverified on a real
> device: the on-device text fix end to end (it OOMs on a 2 GB AVD and needs 4 GB), per-language
> transcription quality, and the at-rest clipboard encryption on a fresh install. Make no
> claim about per-language accuracy in any post."

> **Runbook:** "the in-app 'Report AI output' mailto (M2-3, → `inquiry@leinss.xyz`)"

> **Keep as written.** Verified against the code on 2026-09-05: `ReportConfig.kt` is the single
> destination and both AI surfaces route to it. This is also the answer to the Play content
> rating question about reporting AI content.

## § 4 Exact remaining owner clicks

> **Runbook, item 2:** "(Optional) Cut a **v6.8.8 release** to align all channels with `main`
> before announcing (tests green). Not required — launching on 6.8.6 is fine."

> **Replacement:** "Cut a release carrying the W7 work. This is **not optional**: the drafts
> above describe behaviour that shipped after v6.8.16. Release notes are drafted in
> `TobiBoard/docs/PLAY_PRODUCTION.md` § 8; the rollout steps are in § 7 of the same file."

Items 1, 3, 4 and 5 are unchanged.

## Donations (G-16)

The launch model is donations and reputation, not revenue. The announcement points at whatever
exists on the day it goes out, so this has to be settled before the posts.

### What exists

- `.github/FUNDING.yml` carries one entry, `ko_fi: leinss`, which renders the Sponsor button on
  the repo and is live.
- `README.md` § Support links `ko-fi.com/leinss`. That is the only donation link in any public
  artefact.
- Nothing else. No GitHub Sponsors, no Liberapay, no Open Collective. The runbook's metrics
  section already says so and is correct.

### What the owner must create

The FOSS and F-Droid audience the r/fdroid and r/privacy posts target expects Liberapay, and
often distrusts a Ko-fi-only project because Ko-fi is a hosted payment service rather than a
non-profit. Ko-fi alone will not lose the launch, but Liberapay is cheap to add and is the one
that matches the audience.

| R | Item | Effort | Note |
|---|---|---|---|
| R-1 | Create a Liberapay account and confirm the username | 15 min | needs an email and a payout method (SEPA works). Uncomment `liberapay:` in `.github/FUNDING.yml`. |
| R-2 | Enable GitHub Sponsors on the `leinss` account | 30 min, plus GitHub's review | needs Stripe Connect and a tax form. Uncomment `github: [leinss]`. The review can take days, so start it before the announcement, not during. |
| R-3 | Add the new links to `README.md` § Support | 5 min | agent work once R-1 or R-2 lands |
| R-4 | Decide whether to keep Ko-fi | 0 | keeping all three is fine; GitHub renders up to four buttons |

Both placeholder lines are already in `.github/FUNDING.yml`, commented out with the reason. An
entry pointing at an account that does not exist renders a dead button, which is worse than no
button, so do not uncomment ahead of the account.

**Do not add a donation link to the Play listing.** Play's payments policy treats an external
donation link in the store listing as a route around Play billing. The link belongs in the
README, the repo Sponsor button and the posts, not in `full_description.txt`.

## Owner items this raises

- Commit `fdroid/metadata/xyz.leinss.TobiBoard.yml` with the two AntiFeature blocks so the
  served index declares them, then re-run the F-Droid workflow and check the index. Until then
  the r/fdroid post must use the softened sentence above.
- Confirm the live Play track before the announcement gate is treated as open.
- R-1 and R-2 above: create the Liberapay and GitHub Sponsors accounts. Start R-2 early, its
  review is not instant.
