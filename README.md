<div align="center">

<img src="docs/assets/readme-icon.png" alt="TobiBoard" width="132" />

# TobiBoard

### A private, fully on-device keyboard — with optional AI when you want it.

TobiBoard is a fast, private keyboard that runs **entirely on your device** — a fork of [HeliBoard](https://github.com/Helium314/HeliBoard) with all of its offline typing, glide, autocorrect, suggestions and dictionaries. On top of that it adds **two optional AI helpers** — Voice-to-Text and Text Fix — that you switch on with your own [OpenRouter](https://openrouter.ai/) or [PayPerQ](https://ppq.ai/) key, or ignore completely. Nothing leaves your device until you opt in, and OpenRouter routes default to **zero data retention**.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="64">](https://github.com/leinss/TobiBoard/releases/latest)

</div>

<br>

## Why I built this

I tried the trendy dictation apps everyone talks about. On Android they're rough: slow, account-locked, and with a very clunky, low-quality user experience. I wanted the same idea — talk, get clean text — but with a better UX and more control.

So I forked [HeliBoard](https://github.com/Helium314/HeliBoard) and added two AI buttons. You choose OpenRouter or PayPerQ, paste your own API key, and pick the speech or text model you want. TobiBoard asks OpenRouter for zero-data-retention endpoints by default, so nothing about you is logged or trained on when that route is available. Along the way I smoothed over rough edges in the HeliBoard codebase and hardened the backup-and-restore flow, so it's faster to live with and keeps your data more private.

No subscription. No account. No telemetry. Just a keyboard.

<br>

## Voice-to-Text

**For when you'd rather talk than type, but still want to send text.**

You're walking down the street. You're somewhere a voice note would be rude or out of place. Long-press Return, tap the mic, speak. Your words land in the chat as polished text. The recipient gets a normal message. You didn't thumb-type a paragraph.

- Pick OpenRouter chat audio models or PayPerQ speech models such as Nova
- Add a custom prompt or vocabulary so it nails names and jargon
- The local recording is deleted the moment it's sent

<br>

## AI Text Fix

**For when you're writing in a language that isn't yours, or just typed too fast.**

You're chatting in English, Italian, German, whatever isn't your strongest. You hammered out a rough draft. Select it, long-press Return, hit Fix. It comes back as a clean, well-formatted message in the same language. Typos gone. Grammar tidy. Tone intact.

- Works in every language your model supports
- Good for typos, clumsy phrasing, or shifting tone (formal ↔ casual)
- Review the message and decide if you want to replace the original one

<br>

## Zero Data Retention

OpenRouter is the default provider, and TobiBoard asks OpenRouter for **zero-data-retention endpoints by default**. When your model offers a [ZDR route](https://openrouter.ai/docs/use-cases/zero-data-retention), your audio and text aren't logged, stored, or used for training. If a model doesn't offer one, TobiBoard tells you and falls back so things still work. You can turn the strict setting off if you'd rather.

PayPerQ support uses PayPerQ's own API endpoints and policies. Your provider API keys are encrypted with the Android Keystore, excluded from cloud backups, and never written to logs. AI is opt-in: both features stay off until you paste a key in.

<br>

## TobiBoard vs HeliBoard

|                                         | HeliBoard | TobiBoard |
| --------------------------------------- | :-------: | :--------: |
| Everything HeliBoard does               |     ✅     |     ✅      |
| Installs alongside HeliBoard            |     —     |     ✅      |
| Voice-to-text via OpenRouter or PayPerQ |     —     |     ✅      |
| Text Fix (rewrite selected text)        |     —     |     ✅      |
| Zero Data Retention enforced by default |     —     |     ✅      |
| API key encrypted on-device             |     —     |     ✅      |

If you don't want AI features, stay on HeliBoard. It's wonderful as-is.

<br>

## Privacy footnote

Typing, glide typing, autocorrect, and dictionaries run **fully on-device and offline**, exactly like HeliBoard. The AI Voice-to-Text and Text Fix features are **cloud-based** — there is no local or on-device speech/language model, so the audio or text you run through them is sent to the provider you pick (OpenRouter or PayPerQ). TobiBoard itself has no backend, no analytics, no tracking. Once your audio or selected text reaches OpenRouter, PayPerQ, or the underlying model provider, their policies apply. Read [OpenRouter's policy](https://openrouter.ai/privacy) and [PayPerQ's terms](https://ppq.ai/terms) before pointing this at anything sensitive.


<br>

## Get started

1. **Install** — add the F-Droid repo `https://leinss.xyz/TobiBoard/repo` to your F-Droid client for automatic updates, or download the APK from [Releases](https://github.com/leinss/TobiBoard/releases/latest).
2. **Enable** TobiBoard in *Settings → System → Keyboards*.
3. **Choose a provider** in *TobiBoard Settings → Voice Input*, then add an OpenRouter or PayPerQ API key.
4. **Long-press Return**, tap the mic, start talking.

TobiBoard installs side-by-side with HeliBoard, so you can keep both.

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
<sub>Code changes are AI-assisted but human-reviewed and tested before release; product and architecture decisions are mine. Even with Zero Data Retention, always minimize sensitive data disclosure to 3rd parties.</sub>
</div>
