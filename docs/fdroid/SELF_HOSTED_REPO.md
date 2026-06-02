# Self-hosted F-Droid repository

A self-hosted F-Droid repo is a static folder (`repo/`) with a **signed index**,
served over HTTPS. Users add its URL once in their F-Droid client and get
automatic TobiBoard updates. Nobody can reject or remove it — you own it.

The CI workflow [`.github/workflows/fdroid-repo.yml`](../../.github/workflows/fdroid-repo.yml)
rebuilds and publishes it to **GitHub Pages** on every published release.

```
F-Droid client  ──►  https://leinss.github.io/TobiBoard/repo  (Pages)
                          ▲
                          │ deploy-pages (site/ = repo/ + index.html)
                  fdroid update  ──signs index with the REPO key──►  repo/index-v2.jar
                          ▲
                  APKs downloaded from the latest 3 GitHub releases
```

## Key facts

- The **repo index key is NOT the app signing key.** It signs the repo *index*,
  not the APKs. APKs keep their own developer signature.
- The repo is pinned by this key's **fingerprint** — keep the key forever.
  Rotating it forces every user to re-add the repo.
- Because it's a real F-Droid repo (not F-Droid main), the APKs are *your*
  signed APKs — so a user can move between your GitHub releases and this repo
  without a signature-mismatch reinstall (unlike F-Droid main).

## One-time setup

### 1. Generate the repo index key (PKCS12, store pw == key pw)

```bash
keytool -genkeypair -v \
  -keystore fdroidrepo.p12 -storetype PKCS12 \
  -alias fdroidrepo -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=TobiBoard F-Droid Repo, O=leinss, C=DE"
chmod 600 fdroidrepo.p12
```

**Back it up** the same way as the app key (age-encrypt into chezmoi); the
password goes in Keychain only, never into a backup. See the app keystore notes.

### 2. Add the three repository secrets

```bash
base64 -i fdroidrepo.p12 | gh secret set FDROID_KEYSTORE_B64 --repo leinss/TobiBoard
gh secret set FDROID_KEYSTORE_PASS --repo leinss/TobiBoard   # paste the password
gh secret set FDROID_KEY_PASS      --repo leinss/TobiBoard   # same password (PKCS12)
```

### 3. Enable GitHub Pages

Repo **Settings → Pages → Build and deployment → Source = GitHub Actions**.

### 4. First run

The `release: published` trigger only fires from the workflow on the **default
branch (`main`)**, so it goes live after `dev` is merged to `main`. To test
earlier from `dev`:

```bash
gh workflow run "Self-hosted F-Droid repo" --ref dev --repo leinss/TobiBoard
gh run watch --repo leinss/TobiBoard
```

After a successful run, the landing page at `https://leinss.github.io/TobiBoard/`
shows the **repo URL + fingerprint**. Share the one-tap link
`https://leinss.github.io/TobiBoard/repo?fingerprint=<FP>` (or a QR of it).

## Local test (optional, before trusting CI)

```bash
# androguard 4.1.4 breaks APK signature parsing — install fdroidserver with the
# verified combo (the CI workflow pins the same versions):
uv tool install --with 'androguard==4.1.3' 'fdroidserver==2.4.4'
make fdroid-repo-local           # uses fdroid/config.yml + fdroid/keystore.p12 you place locally
```

`make fdroid-repo-local` expects you to have dropped a local `fdroid/config.yml`
(copy the template, add `keystorepass`/`keypass`), a `fdroid/keystore.p12`, and
at least one signed `*-release.apk` into `fdroid/repo/`. These local files are
git-ignored. It runs `fdroid update` and prints the resulting fingerprint.

## What is committed vs. secret

| Path | Committed? |
|------|-----------|
| `fdroid/config.template.yml` | ✅ yes (no secrets) |
| `fdroid/.gitignore` | ✅ yes |
| `fdroid/config.yml`, `fdroid/keystore.p12`, `fdroid/repo/` | ❌ never (gitignored / CI-only) |
| GitHub secrets `FDROID_*` | ❌ never in git |

The Pages deploy ships **only** `site/` (`repo/` + landing page) — the keystore
and password-bearing `config.yml` are never uploaded.
