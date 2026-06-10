# TobiBoard build/test/release orchestration
# `make help` lists everything.

SHELL := /bin/bash
GRADLE := ./gradlew

PKG := xyz.leinss.TobiBoard
PKG_DEBUG := $(PKG).debug
IME_COMPONENT := $(PKG_DEBUG)/helium314.keyboard.latin.LatinIME
IME_DEBUG := $(IME_COMPONENT)

# Derive the version from the single source of truth so APK/AAB paths never drift.
VERSION_NAME := $(shell grep -E 'versionName = ' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')
APK_DEBUG_NO_MINIFY := app/build/outputs/apk/debugNoMinify/TobiBoard_$(VERSION_NAME)-debugNoMinify.apk
APK_DEBUG := app/build/outputs/apk/debug/TobiBoard_$(VERSION_NAME)-debug.apk
APK_RELEASE := app/build/outputs/apk/release/TobiBoard_$(VERSION_NAME)-release.apk
# Glob fallbacks so install targets keep working even if the version-derived name shifts.
APK_DEBUG_NO_MINIFY_GLOB := app/build/outputs/apk/debugNoMinify/*-debugNoMinify.apk
APK_RELEASE_GLOB := app/build/outputs/apk/release/*-release.apk
AAB_RELEASE_GLOB := app/build/outputs/bundle/release/*.aab

AVD_NAME ?= tobiboard_pixel6_api34
# google_apis (vs aosp_atd) gives us a properly rendering framebuffer on macOS:
# the prior aosp_atd image had services + activities running, but `screencap -p`
# returned all-black under both swiftshader and host-GPU.
SYSTEM_IMAGE := system-images;android-34;google_apis;arm64-v8a
DEVICE_PROFILE := pixel_6

ANDROID_SDK_ROOT ?= /opt/homebrew/share/android-commandlinetools
ANDROID_HOME ?= $(ANDROID_SDK_ROOT)
export ANDROID_HOME
export ANDROID_SDK_ROOT

SDKMANAGER := $(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager
AVDMANAGER := $(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/avdmanager
EMULATOR := $(ANDROID_SDK_ROOT)/emulator/emulator
ADB := $(ANDROID_SDK_ROOT)/platform-tools/adb

# When both emulator and a wifi-paired phone are attached, `adb install` errors
# with "more than one device". Pick the right serial per make target so the APK
# never lands on the wrong device.
ADB_PHONE_SERIAL = $$($(ADB) devices | awk '/_adb-tls-connect|adb-.*-.* / && !/emulator-/ {print $$1; exit}')
ADB_SIM_SERIAL   = $$($(ADB) devices | awk '/^emulator-/ {print $$1; exit}')

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "Targets:\n"} /^[a-zA-Z0-9_.-]+:.*##/ { printf "  \033[36m%-26s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

.PHONY: version
version: ## Print current versionName / versionCode
	@grep -E 'version(Name|Code) =' app/build.gradle.kts | sed 's/^[[:space:]]*//'

.PHONY: devices
devices: ## List connected adb devices
	@$(ADB) devices -l

## --- builds ---------------------------------------------------------------

# Native libs that are too large for git but pinned by URL + size here so a
# fresh checkout can re-fetch them deterministically. Add new libs to NATIVE_LIBS
# and a matching `<name>_URL` and `<name>_SHA256` variable below.
SHERPA_ONNX_VERSION := 1.13.2
SHERPA_ONNX_AAR := app/libs/sherpa-onnx-$(SHERPA_ONNX_VERSION).aar
SHERPA_ONNX_URL := https://github.com/k2-fsa/sherpa-onnx/releases/download/v$(SHERPA_ONNX_VERSION)/sherpa-onnx-$(SHERPA_ONNX_VERSION).aar

$(SHERPA_ONNX_AAR):
	@mkdir -p app/libs
	@echo "Fetching $(notdir $@) (~54 MB)..."
	curl -L --fail "$(SHERPA_ONNX_URL)" -o "$@.part"
	mv "$@.part" "$@"

.PHONY: fetch-native-libs
fetch-native-libs: $(SHERPA_ONNX_AAR) ## Download native AARs (sherpa-onnx) into app/libs/

.PHONY: build
build: build-debug ## Build the small (minified) debug APK (alias for build-debug)

.PHONY: build-debug
build-debug: $(SHERPA_ONNX_AAR) ## Assemble the small (minified) debug APK
	$(GRADLE) :app:assembleDebug
	@ls -1 app/build/outputs/apk/debug/*-debug.apk

.PHONY: build-debug-fast build-fast
build-debug-fast build-fast: $(SHERPA_ONNX_AAR) ## Assemble the unminified debug APK (fast iteration)
	$(GRADLE) :app:assembleDebugNoMinify

.PHONY: build-release
build-release: $(SHERPA_ONNX_AAR) ## Build the signed release APK (needs KEYSTORE_FILE/PASSWORD, KEY_ALIAS/PASSWORD)
	REQUIRE_SIGNED_RELEASE=1 $(GRADLE) :app:assembleRelease
	@ls -1 $(APK_RELEASE_GLOB)

.PHONY: bundle-release
bundle-release: $(SHERPA_ONNX_AAR) ## Build the signed release AAB for Play Store
	REQUIRE_SIGNED_RELEASE=1 $(GRADLE) :app:bundleRelease
	@ls -1 $(AAB_RELEASE_GLOB)

.PHONY: apk
apk: build-debug-fast ## Build the sideloadable debug APK and print its path
	@echo ""
	@echo "Sideload-ready APK:"
	@echo "  $(abspath $(APK_DEBUG_NO_MINIFY))"
	@echo ""
	@echo "Transfer it to your phone (USB-MTP, Drive, Signal-to-self, ...) and"
	@echo "tap to install, or run 'make install' with the device connected."

.PHONY: apk-release
apk-release: build-release ## Build the signed release APK and print its path
	@echo ""
	@echo "Signed release APK:"
	@echo "  $(abspath $(APK_RELEASE))"

.PHONY: clean
clean: ## Wipe build outputs
	$(GRADLE) clean

.PHONY: update-fork
update-fork: ## Rebase TobiBoard onto upstream via the global update-forks binary; tail the result
	@command -v update-forks >/dev/null 2>&1 || { echo "update-forks not on PATH (expected ~/.local/bin/update-forks)"; exit 1; }
	update-forks
	@echo ""
	@echo "--- TobiBoard log entries (last run) ---"
	@grep -A0 -B0 "TobiBoard" ~/.local/log/update-forks.log | tail -30 || true

## --- emulator lifecycle ---------------------------------------------------

.PHONY: emulator-system-image
emulator-system-image: ## Install the AOSP ATD x86_64/arm64 system image used by the AVD
	yes | $(SDKMANAGER) --install "$(SYSTEM_IMAGE)"

.PHONY: avd-create
avd-create: ## Create the named AVD (skips if it already exists)
	@if $(AVDMANAGER) list avd | grep -q 'Name: $(AVD_NAME)$$'; then \
		echo "AVD $(AVD_NAME) already exists"; \
	else \
		echo "no" | $(AVDMANAGER) create avd -n $(AVD_NAME) -k "$(SYSTEM_IMAGE)" -d $(DEVICE_PROFILE); \
	fi

EMU_GPU ?= host
EMU_EXTRA ?=

.PHONY: emulator-start
emulator-start: ## Boot the AVD in the background. EMU_GPU=host (default) or swiftshader_indirect for headless framebuffer capture. EMU_EXTRA=... to append flags.
	@if pgrep -f "qemu-system.*$(AVD_NAME)" >/dev/null; then \
		echo "Emulator $(AVD_NAME) already running"; \
	else \
		nohup $(EMULATOR) -avd $(AVD_NAME) -no-snapshot -gpu $(EMU_GPU) -no-boot-anim $(EMU_EXTRA) >/tmp/$(AVD_NAME).log 2>&1 & \
		echo "Started $(AVD_NAME), log: /tmp/$(AVD_NAME).log, gpu=$(EMU_GPU)"; \
	fi

.PHONY: emulator-wait
emulator-wait: ## Block until adb sees the device as booted
	$(ADB) wait-for-device
	@until [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do \
		sleep 2; \
	done
	@echo "Device booted."

.PHONY: emulator-stop
emulator-stop: ## Power off the running emulator
	-$(ADB) emu kill

## --- install / IME wiring -------------------------------------------------

.PHONY: install
install: build-debug-fast ## Install on whatever single device is attached; fails loudly if both phone + emulator are attached.
	@DEVICES=$$($(ADB) devices | awk 'NR>1 && $$2=="device" {print $$1}' | wc -l | tr -d ' '); \
	if [ "$$DEVICES" -gt 1 ]; then \
		echo "✗ Multiple devices attached. Use 'make sim-install' or 'make phone-install' (or 'make install-wifi') to disambiguate."; \
		$(ADB) devices; \
		exit 1; \
	fi
	$(ADB) install -r $(APK_DEBUG_NO_MINIFY)

.PHONY: install-release
install-release: build-release ## Install the signed release APK on the connected device
	$(ADB) install -r $(APK_RELEASE)

## --- per-device targets (sim vs phone) -----------------------------------

.PHONY: sim-install
sim-install: build-debug-fast ## Install on the running emulator only.
	@SERIAL=$(ADB_SIM_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No emulator running. Try 'make emulator-up' first."; exit 1; fi; \
	echo "→ installing to $$SERIAL"; \
	$(ADB) -s "$$SERIAL" install -r $(APK_DEBUG_NO_MINIFY); \
	$(ADB) -s "$$SERIAL" shell am force-stop $(PKG_DEBUG)

.PHONY: phone-install
phone-install: install-wifi ## Alias for install-wifi — install on the WiFi-paired phone.

.PHONY: sim-restart-ime
sim-restart-ime: ## Force-stop the IME on the emulator so a freshly installed APK loads.
	@SERIAL=$(ADB_SIM_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No emulator running."; exit 1; fi; \
	$(ADB) -s "$$SERIAL" shell am force-stop $(PKG_DEBUG) && echo "✓ IME restarted on $$SERIAL"

.PHONY: phone-restart-ime
phone-restart-ime: ## Force-stop the IME on the WiFi phone so a freshly installed APK loads.
	@SERIAL=$(ADB_PHONE_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No WiFi phone connected. 'make wifi-connect' first."; exit 1; fi; \
	$(ADB) -s "$$SERIAL" shell am force-stop $(PKG_DEBUG) && echo "✓ IME restarted on $$SERIAL"

.PHONY: sim-logcat
sim-logcat: ## Tail TobiBoard logs from the emulator
	@SERIAL=$(ADB_SIM_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No emulator running."; exit 1; fi; \
	$(ADB) -s "$$SERIAL" logcat -v color LatinIME:V VoiceInputManager:V TextFixManager:V OpenRouterClient:V LocalSherpaEngine:V LocalLiteRtEngine:V ModelDownloader:V AndroidRuntime:E '*:S'

.PHONY: phone-logcat
phone-logcat: ## Tail TobiBoard logs from the WiFi phone
	@SERIAL=$(ADB_PHONE_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No WiFi phone connected."; exit 1; fi; \
	$(ADB) -s "$$SERIAL" logcat -v color LatinIME:V VoiceInputManager:V TextFixManager:V OpenRouterClient:V LocalSherpaEngine:V LocalLiteRtEngine:V ModelDownloader:V AndroidRuntime:E '*:S'

.PHONY: phone-status
phone-status: ## Show package version + permissions on the WiFi phone
	@SERIAL=$(ADB_PHONE_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No WiFi phone connected."; exit 1; fi; \
	echo "--- version ---"; \
	$(ADB) -s "$$SERIAL" shell dumpsys package $(PKG_DEBUG) | grep -E "versionName|lastUpdateTime" | head -2; \
	echo "--- runtime permissions ---"; \
	$(ADB) -s "$$SERIAL" shell dumpsys package $(PKG_DEBUG) | grep -E "RECORD_AUDIO|VIBRATE" | head -5; \
	echo "--- IME state ---"; \
	$(ADB) -s "$$SERIAL" shell settings get secure default_input_method

.PHONY: sim-status
sim-status: ## Show package version + permissions on the emulator
	@SERIAL=$(ADB_SIM_SERIAL); \
	if [ -z "$$SERIAL" ]; then echo "✗ No emulator running."; exit 1; fi; \
	echo "--- version ---"; \
	$(ADB) -s "$$SERIAL" shell dumpsys package $(PKG_DEBUG) | grep -E "versionName|lastUpdateTime" | head -2; \
	echo "--- IME state ---"; \
	$(ADB) -s "$$SERIAL" shell settings get secure default_input_method

## --- wifi pairing --------------------------------------------------------
# Pair once per device (the 6-digit code is one-shot, valid for ~30 s); after that
# `make wifi-connect` re-attaches and `make install-wifi` builds + installs.
# Phone: Settings → System → Developer options → Wireless debugging → ON.

.PHONY: wifi-pair
wifi-pair: ## One-time pair via WiFi. Open "Pair device with pairing code" on phone, then `make wifi-pair CODE=NNNNNN` — pair port auto-discovered via mDNS.
	@if [ -z "$(CODE)" ]; then \
		echo "Usage: make wifi-pair CODE=NNNNNN"; \
		echo "       (open 'Pair device with pairing code' on phone, copy the 6-digit code)"; \
		exit 1; \
	fi
	@PAIR_ADDR="$(PAIR_ADDR)"; \
	if [ -z "$$PAIR_ADDR" ]; then \
		echo "Discovering pair port via mDNS (Wireless debugging must be ON)..."; \
		PAIR_ADDR=$$($(ADB) mdns services 2>/dev/null | awk '/_adb-tls-pairing\._tcp/ {print $$NF; exit}'); \
		if [ -z "$$PAIR_ADDR" ]; then \
			echo "✗ No _adb-tls-pairing._tcp service found via mDNS."; \
			echo "  Make sure 'Pair device with pairing code' is open on the phone,"; \
			echo "  or pass PAIR_ADDR=ip:port manually (use the port from the PAIR dialog,"; \
			echo "  NOT the port from the main Wireless Debugging screen)."; \
			exit 1; \
		fi; \
		echo "✓ Found pair port: $$PAIR_ADDR"; \
	fi; \
	$(ADB) pair $$PAIR_ADDR $(CODE)

.PHONY: wifi-connect
wifi-connect: ## Re-attach to a previously paired phone. Connect port auto-discovered via mDNS, or pass CONNECT_ADDR=ip:port.
	@CONNECT_ADDR="$(CONNECT_ADDR)"; \
	if [ -z "$$CONNECT_ADDR" ]; then \
		CONNECT_ADDR=$$($(ADB) mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ {print $$NF; exit}'); \
		if [ -z "$$CONNECT_ADDR" ]; then \
			echo "✗ No _adb-tls-connect._tcp service found via mDNS."; \
			echo "  Make sure Wireless Debugging is ON, or pass CONNECT_ADDR=ip:port manually."; \
			exit 1; \
		fi; \
		echo "✓ Found connect port: $$CONNECT_ADDR"; \
	fi; \
	$(ADB) connect $$CONNECT_ADDR
	$(ADB) devices

.PHONY: install-wifi
install-wifi: build-debug-fast ## Connect over WiFi (auto-discover via mDNS) then install on the WiFi-paired phone (not the emulator).
	@CONNECT_ADDR="$(CONNECT_ADDR)"; \
	if [ -z "$$CONNECT_ADDR" ]; then \
		CONNECT_ADDR=$$($(ADB) mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ {print $$NF; exit}'); \
	fi; \
	if [ -n "$$CONNECT_ADDR" ]; then $(ADB) connect $$CONNECT_ADDR; fi
	@SERIAL=$(ADB_PHONE_SERIAL); \
	if [ -z "$$SERIAL" ]; then \
		echo "✗ No WiFi-attached phone found via adb devices (saw only emulator/USB)."; \
		exit 1; \
	fi; \
	echo "→ installing to $$SERIAL"; \
	$(ADB) -s "$$SERIAL" install -r $(APK_DEBUG_NO_MINIFY); \
	echo ""; \
	echo "Installed. The new APK loads the next time the IME is re-bound:"; \
	echo "  - Close + re-focus any text field (cheapest), OR"; \
	echo "  - 'make phone-restart-ime' (force-stop; small risk of a benign Dialog NPE on teardown)."

.PHONY: wifi-bootstrap-via-usb
wifi-bootstrap-via-usb: ## One-time setup: phone plugged in via USB, switch it into WiFi-debug mode on port 5555.
	@$(ADB) devices | grep -E "device$$" >/dev/null || { echo "No USB-attached device. Plug in S25 + accept the USB-debug prompt."; exit 1; }
	$(ADB) tcpip 5555
	@PHONE_IP=$$($(ADB) shell ip route 2>/dev/null | awk '/wlan0|wlan1/ {print $$9; exit}'); \
	echo ""; \
	echo "Phone IP on WiFi: $$PHONE_IP"; \
	echo "Unplug USB now. To install: make install-wifi CONNECT_ADDR=$$PHONE_IP:5555"

.PHONY: ime-enable
ime-enable: ## Enable + activate TobiBoard as the current IME
	$(ADB) shell ime enable $(IME_COMPONENT)
	$(ADB) shell ime set    $(IME_COMPONENT)
	@echo "TobiBoard is now the active IME on the device."

.PHONY: ime-status
ime-status: ## Show which IMEs are enabled + which is active
	@echo "--- enabled IMEs ---"
	@$(ADB) shell ime list -s
	@echo "--- current IME ---"
	@$(ADB) shell settings get secure default_input_method

.PHONY: launch-typing
launch-typing: ## Open a text-input surface so the IME is visible. Tries Messages first, then TobiBoard Settings on minimal AOSP images.
	$(ADB) shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.google.android.apps.messaging/.ui.ConversationListActivity 2>/dev/null \
	|| $(ADB) shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.android.messaging/.ui.conversationlist.ConversationListActivity 2>/dev/null \
	|| $(ADB) shell am start -n $(PKG_DEBUG)/helium314.keyboard.settings.SettingsActivity2

.PHONY: screenshot
screenshot: ## Capture the device screen to artifacts/screenshot.png
	@mkdir -p artifacts
	$(ADB) exec-out screencap -p > artifacts/screenshot.png
	@echo "saved -> artifacts/screenshot.png"

.PHONY: uninstall
uninstall: ## Remove the debug build from the device
	-$(ADB) uninstall $(PKG_DEBUG)

## --- tests ----------------------------------------------------------------

.PHONY: test test-unit
test test-unit: ## Run JVM (Robolectric) unit tests
	$(GRADLE) :app:testDebugUnitTest

.PHONY: test-managed
test-managed: ## Run instrumentation tests on the Gradle Managed Device (headless)
	$(GRADLE) :app:pixel6Api34DebugAndroidTest

.PHONY: test-connected
test-connected: ## Run instrumentation tests on whatever device adb sees
	$(GRADLE) :app:connectedDebugAndroidTest

.PHONY: lint
lint: ## Run Android lint
	$(GRADLE) lintDebug

.PHONY: check
check: test lint ## Run unit tests + lint

## --- composite flows ------------------------------------------------------

.PHONY: emulator-bootstrap
emulator-bootstrap: emulator-system-image avd-create ## One-shot: ensure system image + AVD exist

.PHONY: emulator-up
emulator-up: emulator-bootstrap emulator-start emulator-wait ## Bring the emulator from cold to booted

.PHONY: dev-install
dev-install: emulator-up install ime-enable launch-typing ## Full path: boot emu, install, enable IME, open a typing surface

.PHONY: logcat
logcat: ## Tail logcat filtered to TobiBoard + IME plumbing
	$(ADB) logcat -v color LatinIME:V VoiceInputManager:V TextFixManager:V OpenRouterClient:V LocalSherpaEngine:V LocalMediaPipeEngine:V ModelDownloader:V SecretStore:V AudioRecorder:V AndroidRuntime:E '*:S'

## --- versioning -----------------------------------------------------------

.PHONY: bump-patch
bump-patch: ## Bump patch version (x.y.Z+1) + changelog stub
	python3 tools/bump_version.py patch

.PHONY: bump-minor
bump-minor: ## Bump minor version (x.Y+1.0) + changelog stub
	python3 tools/bump_version.py minor

.PHONY: bump-major
bump-major: ## Bump major version (X+1.0.0) + changelog stub
	python3 tools/bump_version.py major

## --- one-time setup -------------------------------------------------------

KEYSTORE_PATH := $(HOME)/.keystores/tobiboard-release.jks
PLAY_SA_NAME  := tobiboard-play-publisher
PLAY_SA_JSON  := $(HOME)/.config/play-service-accounts/tobiboard.json

.PHONY: keystore-create
keystore-create: ## (one-time) Create the release signing keystore at ~/.keystores/tobiboard-release.jks
	@if [ -f "$(KEYSTORE_PATH)" ]; then \
		echo "✓ Keystore already exists at $(KEYSTORE_PATH)"; exit 0; \
	fi
	@mkdir -p $(HOME)/.keystores
	keytool -genkeypair \
		-alias tobiboard \
		-keyalg RSA \
		-keysize 4096 \
		-validity 10000 \
		-keystore $(KEYSTORE_PATH) \
		-storetype JKS
	@echo ""
	@echo "✓ Keystore created at $(KEYSTORE_PATH)"
	@echo "Store the password in Keychain, then add to .envrc:"
	@echo "  security add-generic-password -a own_projects -s tobiboard_keystore_password -w <password>"
	@echo "  export KEYSTORE_FILE=$(KEYSTORE_PATH)"
	@echo "  export KEY_ALIAS=tobiboard"
	@echo "  kc_env KEYSTORE_PASSWORD \"own_projects\" \"tobiboard_keystore_password\""
	@echo "  export KEY_PASSWORD=\"\$$KEYSTORE_PASSWORD\""

.PHONY: play-api-setup
play-api-setup: ## (one-time) Create the Google Play service account + download JSON key via gcloud
	@command -v gcloud >/dev/null || { echo "Install gcloud: brew install --cask google-cloud-sdk && gcloud init"; exit 1; }
	@if [ -z "$(PROJECT)" ]; then \
		echo "Usage: make play-api-setup PROJECT=<your-gcloud-project-id>"; \
		echo ""; \
		echo "Find your project ID:"; \
		echo "  1. Play Console → Setup → API access → linked Google Cloud project name"; \
		echo "  2. cloud.google.com/console → copy the project ID (not name)"; \
		exit 1; \
	fi
	@echo "→ Creating service account $(PLAY_SA_NAME) in project $(PROJECT)..."
	gcloud iam service-accounts create $(PLAY_SA_NAME) \
		--project "$(PROJECT)" \
		--display-name "TobiBoard Play Publisher" 2>/dev/null || true
	@echo "→ Downloading JSON key to $(PLAY_SA_JSON)..."
	@mkdir -p $(HOME)/.config/play-service-accounts
	gcloud iam service-accounts keys create $(PLAY_SA_JSON) \
		--iam-account "$(PLAY_SA_NAME)@$(PROJECT).iam.gserviceaccount.com" \
		--project "$(PROJECT)"
	@echo ""
	@echo "✓ Key saved to $(PLAY_SA_JSON)"
	@echo ""
	@echo "Manual step still required — grant Play Console access:"
	@echo "  Play Console → Setup → API access → find '$(PLAY_SA_NAME)' → Grant access → Release Manager"
	@echo ""
	@echo "Then add to .envrc and run 'direnv allow':"
	@echo "  export PLAY_SERVICE_ACCOUNT_JSON=$(PLAY_SA_JSON)"

## --- publishing -----------------------------------------------------------

.PHONY: tag
tag: ## Create a local annotated git tag vX.Y.Z from the current version
	@v=$$(grep -E 'versionName = ' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/'); \
	  git tag -a "v$$v" -m "Release $$v" && echo "Created tag v$$v (push with: git push origin v$$v)"

.PHONY: release
release: build-release ## Build signed release + GitHub release. Dry-run unless CONFIRM=1
	@v=$$(grep -E 'versionName = ' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/'); \
	  code=$$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+'); \
	  apk=$$(ls -1 app/build/outputs/apk/release/*.apk | head -1); \
	  notes="fastlane/metadata/android/en-US/changelogs/$$code.txt"; \
	  cmd="gh release create v$$v $$apk --title \"TobiBoard $$v\" --notes-file $$notes"; \
	  if [ "$(CONFIRM)" = "1" ]; then echo "+ $$cmd"; eval $$cmd; \
	  else echo "DRY-RUN (re-run with CONFIRM=1 to publish):"; echo "  $$cmd"; fi

.PHONY: ship
ship: ## One-shot release: tests + lint -> signed build -> tag + GitHub release (dry-run unless CONFIRM=1)
	$(MAKE) check
	$(MAKE) release CONFIRM=$(CONFIRM)

.PHONY: publish-checklist
publish-checklist: ## Print the release + store-publishing checklist
	@echo "Per release (auto-feeds the self-hosted F-Droid repo; F-Droid main once accepted):"; \
	  echo "  1. make bump-<patch|minor>   # bump version + changelog stub, then edit the stub"; \
	  echo "  2. KEYSTORE_* env set (signing) via direnv"; \
	  echo "  3. make ship CONFIRM=1       # tests+lint, signed build, tag + GitHub release"; \
	  echo ""; \
	  echo "Stores (one-time setup; pull-based per release afterwards):"; \
	  echo "  - F-Droid (self-hosted): auto-published to leinss.xyz/TobiBoard/repo on each release"; \
	  echo "  - F-Droid main:          submit docs/fdroid/*.yml to gitlab.com/fdroid/fdroiddata"; \
	  echo "  - Google Play: make publish-play TRACK=internal  (needs a one-time \$$25 dev account)"

.PHONY: publish-play
publish-play: bundle-release ## Upload signed AAB + listing to Google Play (needs fastlane + PLAY_SERVICE_ACCOUNT_JSON; TRACK=internal|production)
	@command -v fastlane >/dev/null || { echo "Install fastlane: brew install fastlane (or: gem install fastlane)"; exit 1; }
	@test -n "$$PLAY_SERVICE_ACCOUNT_JSON" || { echo "Set PLAY_SERVICE_ACCOUNT_JSON=/path/to/play-service-account.json — see docs/PLAY_PUBLISHING.md"; exit 1; }
	fastlane android play track:$(or $(TRACK),internal)

.PHONY: store-listing
store-listing: ## Upload Main store listing (title, descriptions, graphics, screenshots) to Google Play — no binary. VALIDATE=1 for dry run
	@command -v fastlane >/dev/null || { echo "Install fastlane: brew install fastlane (or: gem install fastlane)"; exit 1; }
	fastlane android listing validate:$(if $(VALIDATE),true,false)

# ---- Self-hosted F-Droid repo (local test) ----
.PHONY: fdroid-repo-local
fdroid-repo-local: ## Build the self-hosted F-Droid index locally (needs fdroidserver + local fdroid/config.yml, keystore.p12, repo/*.apk)
	@command -v fdroid >/dev/null || { echo "Install fdroidserver: pipx install fdroidserver (or: uv tool install fdroidserver)"; exit 1; }
	@test -f fdroid/config.yml || { echo "Missing fdroid/config.yml (cp fdroid/config.template.yml fdroid/config.yml and add keystorepass/keypass)"; exit 1; }
	@test -f fdroid/keystore.p12 || { echo "Missing fdroid/keystore.p12 (generate per docs/fdroid/SELF_HOSTED_REPO.md)"; exit 1; }
	@ls fdroid/repo/*.apk >/dev/null 2>&1 || { echo "Put at least one signed *-release.apk in fdroid/repo/"; exit 1; }
	cd fdroid && fdroid update --create-metadata --verbose
	@pass=$$(grep -E '^keystorepass:' fdroid/config.yml | sed -E 's/.*: *"?([^"]*)"?.*/\1/'); \
	  fp=$$(keytool -exportcert -alias fdroidrepo -keystore fdroid/keystore.p12 -storepass "$$pass" 2>/dev/null | openssl dgst -sha256 | awk '{print $$NF}'); \
	  echo "Repo fingerprint: $$fp"
