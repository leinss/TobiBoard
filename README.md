<div align="center">

<img src="docs/assets/readme-icon.png" alt="TobiBoard" width="132" />

# TobiBoard

### Everything runs on your device. Cloud is optional.

[HeliBoard](https://github.com/Helium314/HeliBoard) is already the best privacy-respecting Android keyboard. TobiBoard keeps everything that makes it good and adds what was missing: **on-device voice-to-text**, **on-device text fix**, and a **proper clipboard manager** — all running locally, no account, no key required. Cloud providers are available if you want more powerful models, but you never need one.

Along the way: fixed the caps-lock-gets-stuck bug, fixed the keyboard-stops-typing bug, and hardened the input connection so you never have to hide-and-reshow the keyboard to get it working again.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="64">](https://github.com/leinss/TobiBoard/releases/latest)

</div>

<br>

## Voice-to-Text

**Runs on your device. No account. No key. No audio leaves your phone.**

Long-press Return, tap the mic, speak. Your words land as polished text. Uses an on-device NVIDIA Parakeet TDT 0.6B v3 model (multilingual: English, German, Spanish, French) by default — nothing is sent anywhere. If you want a more powerful cloud model, switch to OpenRouter or PayPerQ and bring your own key.

- **On-device by default** — Parakeet runs locally, audio never leaves your phone
- Cloud providers (OpenRouter, PayPerQ) available for larger models — your choice
- Add a custom prompt or vocabulary so it nails names and jargon

<br>

## Text Fix

**Rewrites selected text locally. No key required.**

Select rough text, long-press Return, hit Fix. It comes back clean in the same language. Runs on-device by default — no API key, no data sent anywhere. Opt into a cloud model if you want something more capable.

- **On-device by default** — local model, nothing uploaded
- Works in any language your model supports
- Cleans typos, awkward phrasing, or shifts tone (formal ↔ casual)
- You review the rewrite before replacing the original

<br>

## Clipboard history

**Stays on your device. Always.**

Long-press the clipboard icon to browse everything you've copied. Every entry shows when it was copied and how many times you've pasted it. Long-press any entry to pin it to the top or delete it. Clipboard data is stored only on your device, in the app's private storage, and is excluded from all backups — it is never uploaded or backed up to the cloud.

- Search, label, and manage entries from **Settings → Preferences → Manage clipboard history**
- Pin entries that you paste often so they're always at the top
- Add labels to find things later

<br>

## When you do go cloud

If you choose a cloud provider, TobiBoard asks OpenRouter for **[zero-data-retention endpoints](https://openrouter.ai/docs/use-cases/zero-data-retention) by default** — your audio and text aren't logged, stored, or used for training when the model supports it. API keys are encrypted with the Android Keystore, excluded from cloud backups, and never written to logs.

<br>

## TobiBoard vs HeliBoard

|                                              | HeliBoard | TobiBoard |
| -------------------------------------------- | :-------: | :--------: |
| Everything HeliBoard does                    |     ✅     |     ✅      |
| Installs alongside HeliBoard                 |     —     |     ✅      |
| On-device voice-to-text (no key needed)      |     —     |     ✅      |
| On-device text fix (no key needed)           |     —     |     ✅      |
| Cloud voice/text via OpenRouter or PayPerQ   |     —     |     ✅      |
| Clipboard history with use counts + labels   |     —     |     ✅      |
| Zero Data Retention enforced by default      |     —     |     ✅      |
| API key encrypted on-device                  |     —     |     ✅      |

<br>

## Get started

1. **Install** — add the F-Droid repo `https://leinss.xyz/TobiBoard/repo` to your F-Droid client for automatic updates, or download the APK from [Releases](https://github.com/leinss/TobiBoard/releases/latest).
2. **Enable** TobiBoard in *Android Settings → General Management → Keyboard list and default → Add TobiBoard*, then select it as your default.
3. **Use it** — voice and text fix work out of the box with on-device models. No setup required.

> **Want a cloud model?** TobiBoard settings → Voice Input → Provider → choose OpenRouter or PayPerQ → paste your API key. The key field only appears once you select a cloud provider.

TobiBoard installs side-by-side with HeliBoard, so you can keep both.

<br>

## Privacy

Typing, glide, autocorrect, and dictionaries are **fully on-device**, exactly like HeliBoard. Voice and text fix are **also on-device by default**. If you switch to a cloud provider, audio or text is sent to that provider over HTTPS — TobiBoard has no backend, no analytics, no tracking. Once data reaches your provider, their policy applies. Read [OpenRouter's policy](https://openrouter.ai/privacy) and [PayPerQ's terms](https://ppq.ai/terms) before using them with anything sensitive.

<br>

## Build from source

```bash
git clone https://github.com/leinss/TobiBoard.git
cd TobiBoard
./gradlew assembleDebug
```

Needs JDK 17, Android SDK 35, NDK `28.0.13004108`. APK lands in `app/build/outputs/apk/debug/`.

<br>

## Open source, top to bottom

TobiBoard stands on the shoulders of giants:

- [**HeliBoard**](https://github.com/Helium314/HeliBoard), the keyboard this fork is built on.
- [**OpenBoard**](https://github.com/openboard-team/openboard) and [**AOSP LatinIME**](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/), the foundation of both.

<br>

## License

[GPL v3](/LICENSE). AOSP-derived portions are also available under [Apache 2.0](LICENSE-Apache-2.0).

<div align="center">
<br>
<sub>Code changes are AI-assisted but human-reviewed and tested before release; product and architecture decisions are mine.</sub>
</div>
