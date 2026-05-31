# Privacy Policy

This keyboard is a fork of HeliBoard with optional, opt-in AI **voice-to-text** and **text-fix**
features. Typing itself stays on-device exactly as in HeliBoard. The AI features are off by default
and only do anything once you enable them and add your own provider API key.

## What leaves your device

Nothing leaves your device unless you actively use an AI feature:

- **Voice-to-text**: when you tap the voice key and speak, the recorded audio is sent over HTTPS to
  the AI provider you selected (OpenRouter or PayPerQ) for transcription.
- **Text-fix**: when you trigger a fix, the text being fixed — your current selection, or the whole
  text field if nothing is selected — is sent over HTTPS to your selected provider.

Requests are made directly from your device to the provider using the API key you supply. There is
no intermediary server operated by this app.

## What is stored, and where

- **API keys** are stored locally in Android's encrypted preferences (AndroidKeyStore, AES-256) and
  are excluded from cloud backup. They are never logged and never sent anywhere except as the
  authorization header to the provider you chose.
- **Audio recordings** are written to the app's private cache only for the duration of a single
  transcription request and deleted immediately afterward. If you enable "Auto-retry on reconnect",
  a recording may be held a little longer — only until the retry succeeds, you cancel, or it times
  out — and is then deleted. Audio is never persisted across app restarts.
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
