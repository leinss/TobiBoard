# IzzyOnDroid submission — status and handoff

> Prepared 2026-07-10 (portfolio W5, B-1). **NOT SUBMITTED — blocked on two hard eligibility failures, see below.**
> This documents what was verified, why the app is currently ineligible, the exact
> submission steps for when/if it becomes eligible, and the ready-to-paste request text.

## TL;DR

TobiBoard **cannot be listed on IzzyOnDroid as it ships today**, for two independent reasons:

1. **APK size** — IzzyOnDroid's per-app hard limit is **30 MB per file**. TobiBoard's
   signed release APK is **~116 MB** (`TobiBoard_6.8.6-release.apk`, 115.9 MB). Even an
   arm64-only ABI split cannot get this under 30 MB — the bulk is the bundled MediaPipe
   GenAI runtime + sherpa-onnx native libs, not multi-ABI `.so` duplication.
2. **Non-free dependency** — IzzyOnDroid rejects proprietary components outright (no
   AntiFeature escape hatch, unlike a bare "NonFreeNet" disclosure). TobiBoard bundles
   `com.google.mediapipe:tasks-genai` (prebuilt proprietary Google native blobs) for the
   on-device LLM Text Fix, plus the prebuilt sherpa-onnx AAR for on-device STT. This is
   the **same blocker** that keeps the app off F-Droid main.

Both are the exact conditions the F-Droid recipe header already documents. **The path to
any FOSS-store channel (IzzyOnDroid or F-Droid main) is identical and XL**: a from-source,
blob-free build of sherpa-onnx + onnxruntime + a non-proprietary on-device LLM runtime,
AND getting the resulting APK under 30 MB (only feasible if models stay download-on-demand
AND the runtime libs shrink dramatically). Tracked as M2-2.

**Recommendation:** do NOT submit. The terminal FOSS distribution channels for TobiBoard
remain the **self-hosted F-Droid repo** (`leinss.xyz/TobiBoard/repo`) and **GitHub
Releases**. Google Play (internal → production) is the mainstream channel. Revisit
IzzyOnDroid only if the M2-2 blob-free + sub-30 MB build is ever done.

## What was verified (2026-07-10)

- IzzyOnDroid inclusion policy (source: https://izzyondroid.org/docs/general/AppInclusionPolicy/):
  - **30 MB max per file** (hard ceiling; runs on private resources, no funding).
  - **FOSS license mandatory** (OSI/FSF-approved SPDX). ✓ TobiBoard is GPL-3.0-or-later.
  - **No proprietary components / trackers** — rejected in general, no disclosure exemption. ✗ MediaPipe blob.
  - APKs **built and signed by the developer**, taken from tagged GitHub releases (preferred). ✓ we have this.
  - Fastlane metadata in the repo. ✓ present.
  - Reproducible builds strongly preferred, not mandatory.
- **Tracker moved**: the old GitLab `IzzyOnDroid/repo` issue tracker is **archived**. New
  inclusion requests go to **Codeberg**: https://codeberg.org/IzzyOnDroid/repodata/issues
  (a `.forgejo/issue_template` exists; PR-to-add-app is "planned for later", so file an issue).
  **Filing requires a Codeberg account** (the agent has none → owner action).
- App APK size: `gh release view v6.8.6 -R leinss/TobiBoard` → `TobiBoard_6.8.6-release.apk` = 115.9 MB.
- Latest published release = **v6.8.6** (v6.8.7 does not exist on `leinss/TobiBoard`; that
  tag belongs to upstream WisprBoard). `main` is at 6.8.8/6808, unreleased.

## Exact submission steps (ONLY if M2-2 blob-free + sub-30 MB build ever ships)

1. Confirm the release APK is ≤ 30 MB and contains **no proprietary native libs**
   (no MediaPipe `tasks-genai`, no closed sherpa/onnxruntime blobs).
2. Sign in / create a Codeberg account.
3. Open a new issue at https://codeberg.org/IzzyOnDroid/repodata/issues using the app-inclusion template.
4. Provide the request text below.
5. Respond to the maintainer's APK-scanner findings (they auto-scan for non-free libs/trackers).

## Ready-to-paste inclusion request (for the eligible build only)

```
App name: TobiBoard
Package ID: xyz.leinss.TobiBoard
License: GPL-3.0-or-later
Source code: https://github.com/leinss/TobiBoard
Release channel: GitHub Releases (signed APKs, tag vX.Y.Z, asset TobiBoard_X.Y.Z-release.apk)
Fastlane metadata: present at fastlane/metadata/android/en-US/ (summary, description, icon,
  feature graphic, 3 phone screenshots, per-version changelogs).
Summary: Privacy-first Android keyboard (HeliBoard fork) with on-device voice-to-text and
  on-device AI text-fix. Everything runs locally by default; no account, no API key required.
AntiFeatures: NonFreeNet — the OPTIONAL cloud voice/text-fix path sends data to a user-chosen
  provider (OpenRouter / PayPerQ) with the user's own key; off by default, all core features
  work fully offline.
Notes: dev-signed release APK taken from GitHub releases; reproducible-build metadata can be
  provided on request.
```

> If submitting a build that still bundles MediaPipe/sherpa blobs, expect rejection — do not
> waste the maintainer's scan time. The request above assumes the M2-2 blob-free build.
