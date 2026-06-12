# Privacy Policy

This keyboard is a fork of HeliBoard with optional, opt-in AI **voice-to-text** and **text-fix**
features. Typing itself stays on-device exactly as in HeliBoard. Both AI features are off by default.

When enabled, they run **on-device by default**: voice-to-text and text-fix use models that execute
locally on your phone, and no audio or text leaves your device. You can optionally switch either
feature to a cloud provider (OpenRouter or PayPerQ) using your own API key — only then is data sent
off-device, and only for the feature you switched.

## On-device models

The on-device features rely on model files that are not bundled in the app. The first time you enable
an on-device feature, the required model is downloaded from Hugging Face
(`huggingface.co`) directly to your device and verified against a pinned checksum:

- **Voice-to-text** uses an NVIDIA Parakeet TDT 0.6B v3 model (sherpa-onnx export).
- **Text-fix** uses a Qwen2.5 Instruct model by default; an alternative Google Gemma 3 model is also
  available. The Gemma model is gated by Google — to download it you must accept Google's terms on
  the model page and supply a Hugging Face read-only access token, which is stored locally the same
  way as a provider API key (encrypted preferences, excluded from backups) and is sent only to
  Hugging Face as the download authorization header.

After download, on-device transcription and text-fix run entirely offline with no network access.

## What leaves your device

Nothing leaves your device while you use an on-device AI feature. Data is only sent off-device if you
explicitly select a cloud provider for that feature:

- **Voice-to-text (cloud provider selected)**: when you tap the voice key and speak, the recorded
  audio is sent over HTTPS to the AI provider you selected (OpenRouter or PayPerQ) for transcription.
- **Text-fix (cloud provider selected)**: when you trigger a fix, the text being fixed — your current
  selection, or the whole text field if nothing is selected — is sent over HTTPS to your selected
  provider.

Cloud requests are made directly from your device to the provider using the API key you supply. There
is no intermediary server operated by this app.

## What is stored, and where

- **API keys** are stored locally in Android's encrypted preferences (AndroidKeyStore, AES-256) and
  are excluded from cloud backup. They are never logged and never sent anywhere except as the
  authorization header to the provider you chose.
- **Audio recordings** are written to the app's private cache only for the duration of a single
  transcription request and deleted immediately afterward. If you enable "Auto-retry on reconnect",
  a recording may be held a little longer — only until the retry succeeds, you cancel, or it times
  out — and is then deleted. Audio is never persisted across app restarts.
- **Clipboard history** is stored in a local SQLite database on your device when the clipboard
  history feature is enabled (off by default). The database stores the text content of items you
  copy, the time they were copied, how many times you have pasted them, and any labels you add.
  Clipboard data never leaves your device and is not included in cloud backups. You can clear the
  history at any time from TobiBoard Settings → Preferences → Manage clipboard history, or by
  disabling clipboard history entirely.
- **Token usage counts** shown in settings are kept in memory only for the current session and are
  cleared when the app process ends or when you reset them.

## No tracking

This app contains no analytics, advertising, tracking, or telemetry SDKs. It does not collect usage
statistics or crash reports.

## Third-party providers

Once your audio or text reaches the provider you selected (OpenRouter, PayPerQ, or the underlying
model provider they route to), that provider's privacy policy governs it. For OpenRouter models that
support Zero Data Retention (ZDR), this app requests ZDR routing by default. Review your provider's
policy for details on how they handle request data.

## Permissions

- **Record audio** — only used while you are actively recording for voice-to-text.
- **Internet / network state** — only used to send AI requests to your selected provider and to
  check connectivity before doing so.
- Other permissions are standard keyboard (IME) permissions inherited from HeliBoard.

## Contact

Questions or concerns: please open an issue on the project's source repository.
