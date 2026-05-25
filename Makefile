# TobiBoard build/test orchestration
# `make help` lists everything.

SHELL := /bin/bash

PKG_DEBUG := helium314.keyboard.tobiboard.debug
IME_COMPONENT := $(PKG_DEBUG)/helium314.keyboard.latin.LatinIME
APK_DEBUG_NO_MINIFY := app/build/outputs/apk/debugNoMinify/TobiBoard_6.6.0-debugNoMinify.apk
APK_DEBUG := app/build/outputs/apk/debug/TobiBoard_6.6.0-debug.apk

AVD_NAME ?= tobiboard_pixel6_api34
SYSTEM_IMAGE := system-images;android-34;aosp_atd;arm64-v8a
DEVICE_PROFILE := pixel_6

ANDROID_SDK_ROOT ?= /opt/homebrew/share/android-commandlinetools
ANDROID_HOME ?= $(ANDROID_SDK_ROOT)
export ANDROID_HOME
export ANDROID_SDK_ROOT

SDKMANAGER := $(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager
AVDMANAGER := $(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/avdmanager
EMULATOR := $(ANDROID_SDK_ROOT)/emulator/emulator
ADB := $(ANDROID_SDK_ROOT)/platform-tools/adb

.PHONY: help
help:
	@awk 'BEGIN {FS = ":.*##"; printf "Targets:\n"} /^[a-zA-Z0-9_.-]+:.*##/ { printf "  \033[36m%-26s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

## --- builds ---------------------------------------------------------------

.PHONY: build-debug
build-debug: ## Assemble the small (minified) debug APK
	./gradlew :app:assembleDebug

.PHONY: build-debug-fast
build-debug-fast: ## Assemble the unminified debug APK (fast iteration)
	./gradlew :app:assembleDebugNoMinify

.PHONY: clean
clean: ## Wipe build outputs
	./gradlew clean

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
install: build-debug-fast ## Install the unminified debug APK on the connected device
	$(ADB) install -r $(APK_DEBUG_NO_MINIFY)

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

.PHONY: test-unit
test-unit: ## Run JVM (Robolectric) unit tests
	./gradlew :app:testDebugUnitTest

.PHONY: test-managed
test-managed: ## Run instrumentation tests on the Gradle Managed Device (headless)
	./gradlew :app:pixel6Api34DebugAndroidTest

.PHONY: test-connected
test-connected: ## Run instrumentation tests on whatever device adb sees
	./gradlew :app:connectedDebugAndroidTest

## --- composite flows ------------------------------------------------------

.PHONY: emulator-bootstrap
emulator-bootstrap: emulator-system-image avd-create ## One-shot: ensure system image + AVD exist

.PHONY: emulator-up
emulator-up: emulator-bootstrap emulator-start emulator-wait ## Bring the emulator from cold to booted

.PHONY: dev-install
dev-install: emulator-up install ime-enable launch-typing ## Full path: boot emu, install, enable IME, open a typing surface

.PHONY: logcat
logcat: ## Tail logcat filtered to TobiBoard + IME plumbing
	$(ADB) logcat -v color LatinIME:V VoiceInputManager:V TextFixManager:V OpenRouterClient:V LocalSherpaEngine:V LocalMediaPipeEngine:V ModelDownloader:V AndroidRuntime:E *:S
