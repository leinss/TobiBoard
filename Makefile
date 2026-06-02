# TobiBoard — developer convenience commands. Run `make` or `make help` for the list.
SHELL := /bin/bash
GRADLE := ./gradlew

PKG := helium314.keyboard.tobiboard
PKG_DEBUG := $(PKG).debug
IME_DEBUG := $(PKG_DEBUG)/helium314.keyboard.latin.LatinIME
APK_DEBUG_GLOB := app/build/outputs/apk/debug/*-debug.apk

# First matching adb device of each kind (physical vs emulator).
PHONE = $(shell adb devices | awk 'NR>1 && $$2=="device" && $$1 !~ /^emulator-/ {print $$1; exit}')
EMU   = $(shell adb devices | awk 'NR>1 && $$2=="device" && $$1 ~ /^emulator-/ {print $$1; exit}')

.DEFAULT_GOAL := help
.PHONY: help version devices build apk build-fast build-release bundle-release \
        install install-phone install-emu run-phone uninstall logcat \
        test lint check clean bump-patch bump-minor bump-major tag release \
        publish-checklist update-fork

help: ## Show this help
	@awk 'BEGIN{FS=":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

version: ## Print current versionName / versionCode
	@grep -E 'version(Name|Code) =' app/build.gradle.kts | sed 's/^[[:space:]]*//'

devices: ## List connected adb devices
	@adb devices -l

# ---- Build ----
build: ## Build the debug APK (minified, installable side-by-side)
	$(GRADLE) assembleDebug
	@ls -1 $(APK_DEBUG_GLOB)

apk: build ## Alias for `build`

build-fast: ## Build the debug-no-minify APK (faster, for iterating)
	$(GRADLE) assembleDebugNoMinify

build-release: ## Build the signed release APK (needs KEYSTORE_FILE/PASSWORD, KEY_ALIAS/PASSWORD)
	REQUIRE_SIGNED_RELEASE=1 $(GRADLE) assembleRelease
	@ls -1 app/build/outputs/apk/release/*.apk

bundle-release: ## Build the signed release AAB for Play Store
	REQUIRE_SIGNED_RELEASE=1 $(GRADLE) bundleRelease
	@ls -1 app/build/outputs/bundle/release/*.aab

# ---- Install / device ----
install: build ## Install the debug APK to ALL connected devices
	$(GRADLE) installDebug

install-phone: build ## Install the debug APK to the physical phone over adb
	@test -n "$(PHONE)" || { echo "No physical device (check: make devices)"; exit 1; }
	adb -s $(PHONE) install -r $$(ls -1t $(APK_DEBUG_GLOB) | head -1)
	@echo "Installed to $(PHONE)"

install-emu: build ## Install the debug APK to the running emulator
	@test -n "$(EMU)" || { echo "No emulator running"; exit 1; }
	adb -s $(EMU) install -r $$(ls -1t $(APK_DEBUG_GLOB) | head -1)

run-phone: install-phone ## Install, then enable + switch the IME on the phone
	-adb -s $(PHONE) shell ime enable $(IME_DEBUG)
	-adb -s $(PHONE) shell ime set $(IME_DEBUG)
	@echo "TobiBoard set as input method on $(PHONE)"

uninstall: ## Uninstall the debug build from all connected devices
	-adb shell pm uninstall $(PKG_DEBUG)

logcat: ## Tail logcat filtered to TobiBoard's tags
	adb logcat -v color VoiceInputManager:V OpenRouterClient:V TextFixManager:V LatinIME:V SecretStore:V AudioRecorder:V '*:S'

# ---- Quality ----
test: ## Run unit tests
	$(GRADLE) testDebugUnitTest

lint: ## Run Android lint
	$(GRADLE) lintDebug

check: test lint ## Run unit tests + lint

clean: ## Gradle clean
	$(GRADLE) clean

# ---- Versioning ----
bump-patch: ## Bump patch version (x.y.Z+1) + changelog stub
	python3 tools/bump_version.py patch

bump-minor: ## Bump minor version (x.Y+1.0) + changelog stub
	python3 tools/bump_version.py minor

bump-major: ## Bump major version (X+1.0.0) + changelog stub
	python3 tools/bump_version.py major

# ---- Publishing ----
tag: ## Create a local annotated git tag vX.Y.Z from the current version
	@v=$$(grep -E 'versionName = ' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/'); \
	  git tag -a "v$$v" -m "Release $$v" && echo "Created tag v$$v (push with: git push origin v$$v)"

release: build-release ## Build signed release + GitHub release. Dry-run unless CONFIRM=1
	@v=$$(grep -E 'versionName = ' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/'); \
	  code=$$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+'); \
	  apk=$$(ls -1 app/build/outputs/apk/release/*.apk | head -1); \
	  notes="fastlane/metadata/android/en-US/changelogs/$$code.txt"; \
	  cmd="gh release create v$$v $$apk --title \"TobiBoard $$v\" --notes-file $$notes"; \
	  if [ "$(CONFIRM)" = "1" ]; then echo "+ $$cmd"; eval $$cmd; \
	  else echo "DRY-RUN (re-run with CONFIRM=1 to publish):"; echo "  $$cmd"; fi

publish-checklist: ## Print the pre-publish checklist (IzzyOnDroid / F-Droid)
	@echo "Pre-publish:"; \
	  echo "  1. make check               # tests + lint pass"; \
	  echo "  2. make bump-<patch|minor>  # then edit the changelog stub"; \
	  echo "  3. KEYSTORE_* env set (signing) -> make build-release"; \
	  echo "  4. make tag && git push origin <tag>"; \
	  echo "  5. make release CONFIRM=1   # GitHub release; IzzyOnDroid auto-picks it up"; \
	  echo "  6. (once) request app inclusion at the IzzyOnDroid request tracker"

update-fork: ## Sync from the upstream WisprBoard fork
	update-forks || git fetch upstream
