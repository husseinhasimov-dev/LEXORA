#!/usr/bin/env python3
"""
LEXORA — Local Android APK Builder, GitHub Release & Update Manifest Publisher

Builds the LEXORA Android APK locally via Gradle and publishes it together with
`update-manifest.json` to GitHub Releases.

Policy:
  - Static or recycled release notes are rejected. Every release must supply
    explicit notes via `--item`, `--notes`, `--notes-file`, or interactive input.
  - Authentication tokens are NEVER hardcoded in this script. They are resolved
    in strict priority order:
      1. `GITHUB_TOKEN` or `GH_TOKEN` environment variable
      2. `.github-token` file in the repository root (gitignored)
      3. Git Credential Manager (`git credential fill` for github.com)
      4. `~/.git-credentials`
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

APP_NAME = "LEXORA"
APK_ASSET_NAME = "LEXORA.apk"
DEFAULT_REPO = "husseinhasimov-dev/LEXORA"
USER_AGENT = "LEXORA-Release-Publisher"

STRINGS = {
    "en": {
        "changelog_heading": "### Changes (v{version_name} / Build {version_code})",
        "downloads_heading": "### Downloads",
        "apk_line": "- **Android APK:** `{asset_name}` — v{version_name} (Build {version_code}, {apk_size_mb:.2f} MB)",
        "min_sdk": "- **Minimum Android:** Android 8.0+ (API 26)",
        "recommended": "- **Target Android:** Android 15 (API 35)",
        "install": "- **Install:** Open the downloaded APK or run `adb install -r {asset_name}`.",
        "default_summary": "Offline-first German vocabulary trainer with FSRS-6 spaced repetition, TestDaF academic dictionary, and practice modes.",
    },
    "az": {
        "changelog_heading": "### Dəyişikliklər (v{version_name} / Build {version_code})",
        "downloads_heading": "### Yükləmə",
        "apk_line": "- **Android APK:** `{asset_name}` — v{version_name} (Build {version_code}, {apk_size_mb:.2f} MB)",
        "min_sdk": "- **Minimum Android:** Android 8.0+ (API 26)",
        "recommended": "- **Hədəf Android:** Android 15 (API 35)",
        "install": "- **Quraşdırma:** Yüklənmiş APK faylını açın və ya `adb install -r {asset_name}` əmrini icra edin.",
        "default_summary": "FSRS-6 aralıklı təkrar, TestDaF akademik lüğəti və oflayn məşq rejimləri ilə alman dili söz öyrənmə tətbiqi.",
    },
    "ru": {
        "changelog_heading": "### Изменения (v{version_name} / Сборка {version_code})",
        "downloads_heading": "### Загрузка",
        "apk_line": "- **Android APK:** `{asset_name}` — v{version_name} (Сборка {version_code}, {apk_size_mb:.2f} МБ)",
        "min_sdk": "- **Минимальная версия Android:** Android 8.0+ (API 26)",
        "recommended": "- **Целевая версия Android:** Android 15 (API 35)",
        "install": "- **Установка:** Откройте скачанный APK-файл или выполните `adb install -r {asset_name}`.",
        "default_summary": "Офлайн-приложение для изучения немецкой лексики с FSRS-6, академическим словарём TestDaF и режимами практики.",
    },
}


def configure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def get_git_repo(root: Path) -> str:
    env_repo = os.environ.get("GITHUB_REPO", "").strip()
    if env_repo:
        return env_repo
    try:
        res = subprocess.run(
            ["git", "-C", str(root), "config", "--get", "remote.origin.url"],
            capture_output=True,
            text=True,
            check=True,
        )
        m = re.search(r"github\.com[:/]([^/]+/[^/.]+)(?:\.git)?$", res.stdout.strip())
        if m:
            return m.group(1)
    except Exception:
        pass
    return DEFAULT_REPO


def get_git_branch(root: Path) -> str:
    try:
        res = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        branch = res.stdout.strip()
        if branch and branch != "HEAD":
            return branch
    except Exception:
        pass
    return "main"


def _token_from_git_credential_manager(root: Path) -> str | None:
    env = os.environ.copy()
    env["GCM_INTERACTIVE"] = "never"
    env["GIT_TERMINAL_PROMPT"] = "0"
    try:
        res = subprocess.run(
            ["git", "-C", str(root), "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True,
            text=True,
            timeout=10,
            env=env,
        )
        if res.returncode == 0:
            for line in res.stdout.splitlines():
                if line.startswith("password="):
                    tok = line.split("=", 1)[1].strip()
                    if tok:
                        return tok
    except Exception:
        pass
    return None


def get_git_token(root: Path) -> str | None:
    # 1. Environment variable
    for var in ("GITHUB_TOKEN", "GH_TOKEN"):
        token = os.environ.get(var, "").strip()
        if token:
            return token

    # 2. Gitignored .github-token file in repo root
    token_file = root / ".github-token"
    if token_file.is_file():
        tok = token_file.read_text(encoding="utf-8").strip()
        if tok:
            return tok

    # 3. Git Credential Manager / configured git credential helper
    gcm_token = _token_from_git_credential_manager(root)
    if gcm_token:
        return gcm_token

    # 4. ~/.git-credentials
    git_cred = Path.home() / ".git-credentials"
    if git_cred.is_file():
        try:
            lines = [ln.strip() for ln in git_cred.read_text(encoding="utf-8").splitlines() if "@github.com" in ln]
            for matcher in (lambda l: "lexora" in l.lower(), lambda _: True):
                for line in lines:
                    if matcher(line):
                        m = re.search(r":([^:@/]+)@github\.com", line)
                        if m:
                            return m.group(1).strip()
        except OSError:
            pass

    return None


def get_version_info(root: Path) -> tuple[str, int]:
    for rel in ("app/build.gradle.kts", "android/app/build.gradle.kts"):
        path = root / rel
        if path.is_file():
            content = path.read_text(encoding="utf-8")
            vname = re.search(r'versionName\s*=\s*"([^"]+)"', content)
            vcode = re.search(r"versionCode\s*=\s*(\d+)", content)
            if vname and vcode:
                return vname.group(1), int(vcode.group(1))
            print(f"[error] versionName or versionCode missing in {path}", file=sys.stderr)
            sys.exit(1)
    print("[error] Could not locate app/build.gradle.kts.", file=sys.stderr)
    sys.exit(1)


def compute_sha256(filepath: Path) -> str:
    digest = hashlib.sha256()
    with filepath.open("rb") as f:
        while chunk := f.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def detect_android_sdk(root: Path) -> Path | None:
    local_props = root / "local.properties"
    if local_props.is_file():
        for line in local_props.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.strip().startswith("sdk.dir="):
                raw = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                p = Path(raw)
                if p.is_dir():
                    return p

    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(env_var, "").strip()
        if val and Path(val).is_dir():
            return Path(val)

    candidates = []
    local_app_data = os.environ.get("LOCALAPPDATA", "").strip()
    if local_app_data:
        candidates.append(Path(local_app_data) / "Android" / "Sdk")
    candidates.extend([
        Path.home() / "AppData" / "Local" / "Android" / "Sdk",
        Path.home() / "Library" / "Android" / "sdk",
        Path.home() / "Android" / "Sdk",
    ])
    for cand in candidates:
        if cand.is_dir():
            return cand
    return None


def app_build_dir(root: Path) -> Path:
    custom = os.environ.get("LEXORA_BUILD_DIR", "").strip()
    if custom:
        return Path(custom)
    return root / "app" / "build"


def build_local_apk(root: Path, variant: str, version_name: str) -> Path:
    gradle_cmd = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))]
    task = ":app:assembleRelease" if variant == "release" else ":app:assembleDebug"
    gradle_cmd.append(task)

    env = os.environ.copy()
    sdk = detect_android_sdk(root)
    if sdk and not env.get("ANDROID_HOME"):
        env["ANDROID_HOME"] = str(sdk)
        env["ANDROID_SDK_ROOT"] = str(sdk)

    print(f"[build] Running {' '.join(gradle_cmd)}")
    result = subprocess.run(gradle_cmd, cwd=str(root), env=env)
    if result.returncode != 0:
        print(f"[error] Gradle build failed with exit code {result.returncode}.", file=sys.stderr)
        sys.exit(result.returncode)

    out_dir = app_build_dir(root) / "outputs" / "apk" / variant
    if variant == "release":
        signed_apk = out_dir / "app-release.apk"
        unsigned_apk = out_dir / "app-release-unsigned.apk"
        if signed_apk.is_file():
            built_apk = signed_apk
        elif unsigned_apk.is_file():
            print(
                "[error] Only unsigned release APK was produced (app-release-unsigned.apk). "
                "Configure release signing in app/build.gradle.kts or use --variant debug.",
                file=sys.stderr,
            )
            sys.exit(1)
        else:
            print(f"[error] Expected release APK not found in {out_dir}.", file=sys.stderr)
            sys.exit(1)
    else:
        built_apk = out_dir / "app-debug.apk"
        if not built_apk.is_file():
            print(f"[error] Expected debug APK not found at {built_apk}.", file=sys.stderr)
            sys.exit(1)

    delivery_dir = root / "delivery"
    delivery_dir.mkdir(parents=True, exist_ok=True)
    delivered_apk = delivery_dir / f"{APP_NAME}-{version_name}.apk"
    shutil.copy2(built_apk, delivered_apk)
    print(f"[ok] Built APK: {built_apk}")
    print(f"[ok] Copied to: {delivered_apk}")
    return built_apk


def resolve_apk(root: Path, explicit: str, variant: str, version_name: str) -> Path | None:
    bdir = app_build_dir(root)
    variant_candidates = (
        [bdir / "outputs" / "apk" / "release" / "app-release.apk"]
        if variant == "release"
        else [bdir / "outputs" / "apk" / "debug" / "app-debug.apk"]
    )
    candidates = [
        Path(explicit) if explicit else None,
        *variant_candidates,
        bdir / "outputs" / "apk" / "release" / "app-release.apk",
        bdir / "outputs" / "apk" / "debug" / "app-debug.apk",
        root / "delivery" / f"{APP_NAME}-{version_name}.apk",
        root / APK_ASSET_NAME,
        Path(APK_ASSET_NAME),
    ]
    for cand in candidates:
        if cand and cand.is_file() and cand.stat().st_size > 100_000:
            return cand
    return None


def parse_args() -> argparse.Namespace:
    default_lang = os.environ.get("LEXORA_LANG", "en").lower()
    if default_lang not in STRINGS:
        default_lang = "en"

    parser = argparse.ArgumentParser(
        description="LEXORA local APK builder, GitHub Release & update-manifest.json publisher"
    )
    parser.add_argument("-i", "--item", action="append", dest="items", default=[], help="Changelog bullet item (repeatable)")
    parser.add_argument("-n", "--notes", default="", help="Full Markdown changelog text")
    parser.add_argument("-f", "--notes-file", default="", help="Path to Markdown file containing release notes")
    parser.add_argument("-s", "--summary", default="", help="One-line release summary")
    parser.add_argument("--variant", choices=["debug", "release"], default="debug", help="Android build variant (default: debug)")
    parser.add_argument("--build", action="store_true", help="Force local Gradle build before publishing (even with --dry-run or --apk)")
    parser.add_argument("--no-build", action="store_true", help="Skip local Gradle build and reuse an existing APK")
    parser.add_argument("--notes-only", action="store_true", help="Update release body only without building or uploading assets")
    parser.add_argument("--apk", default="", help="Explicit path to a prebuilt APK artifact")
    parser.add_argument("--target", default="", help="Target branch or commit for new release tags (default: current git branch)")
    parser.add_argument("--lang", choices=["en", "az", "ru"], default=default_lang, help="Release body language (default: en)")
    parser.add_argument("--channel", choices=["stable", "beta"], default="stable", help="Update channel (default: stable)")
    parser.add_argument("--force-update", action="store_true", help="Set forceUpdate=true in update-manifest.json")
    parser.add_argument("--min-supported", type=int, default=1, help="Minimum supported versionCode (default: 1)")
    parser.add_argument("--notes-az", default="", help="Azerbaijani changelog for update-manifest.json")
    parser.add_argument("--notes-en", default="", help="English changelog for update-manifest.json")
    parser.add_argument("--notes-ru", default="", help="Russian changelog for update-manifest.json")
    parser.add_argument("--dry-run", action="store_true", help="Render release notes and manifest locally without calling GitHub")
    return parser.parse_args()


def collect_manual_changelog(args: argparse.Namespace) -> str:
    sections: list[str] = []

    if args.notes_file:
        p = Path(args.notes_file)
        if not p.is_file():
            print(f"[error] --notes-file not found: {p}", file=sys.stderr)
            sys.exit(1)
        text = p.read_text(encoding="utf-8").strip()
        if text:
            sections.append(text)

    if args.notes.strip():
        sections.append(args.notes.strip())

    if args.items:
        bullets = []
        for raw in args.items:
            cleaned = raw.strip()
            if not cleaned:
                continue
            if not cleaned.startswith(("-", "*")):
                cleaned = f"- {cleaned}"
            bullets.append(cleaned)
        if bullets:
            sections.append("\n".join(bullets))

    if not sections and sys.stdin.isatty():
        print("Enter release notes (one item per line, empty line to finish):")
        lines = []
        while True:
            try:
                line = input("> ").strip()
            except EOFError:
                break
            if not line:
                if lines:
                    break
                continue
            if not line.startswith(("-", "*")):
                line = f"- {line}"
            lines.append(line)
        if lines:
            sections.append("\n".join(lines))

    changelog = "\n\n".join(sections).strip()
    if not changelog:
        print(
            "[error] Manual release notes are required (--item, --notes, or --notes-file). "
            "Static or repeated release notes are not permitted.",
            file=sys.stderr,
        )
        sys.exit(1)

    return changelog


def build_release_body(
    version_name: str,
    version_code: int,
    summary: str,
    changelog: str,
    apk_size_mb: float,
    sha256_hash: str | None,
    lang: str,
) -> str:
    s = STRINGS[lang]
    resolved_summary = summary.strip() or s["default_summary"]
    sha_line = f"\n- **SHA-256:** `{sha256_hash}`" if sha256_hash else ""
    return (
        f"## {APP_NAME} v{version_name} (Build {version_code})\n\n"
        f"{resolved_summary}\n\n"
        f"{s['changelog_heading'].format(version_name=version_name, version_code=version_code)}\n"
        f"{changelog}\n\n"
        f"{s['downloads_heading']}\n"
        f"{s['apk_line'].format(asset_name=APK_ASSET_NAME, version_name=version_name, version_code=version_code, apk_size_mb=apk_size_mb)}"
        f"{sha_line}\n"
        f"{s['min_sdk']}\n"
        f"{s['recommended']}\n"
        f"{s['install'].format(asset_name=APK_ASSET_NAME)}\n"
    )


def build_update_manifest(
    repo: str,
    tag_name: str,
    version_name: str,
    version_code: int,
    channel: str,
    force_update: bool,
    min_supported: int,
    apk_name: str,
    apk_size_bytes: int,
    sha256_hash: str,
    notes_az: str,
    notes_en: str,
    notes_ru: str,
) -> dict:
    published_at = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "schemaVersion": 1,
        "channel": channel,
        "tag": tag_name,
        "versionName": version_name,
        "versionCode": version_code,
        "publishedAt": published_at,
        "minSdk": 26,
        "minSupportedVersionCode": max(1, min_supported),
        "forceUpdate": bool(force_update),
        "apk": {
            "name": apk_name,
            "url": f"https://github.com/{repo}/releases/download/{tag_name}/{apk_name}",
            "sizeBytes": apk_size_bytes,
            "sha256": sha256_hash,
        },
        "changelog": {
            "az": notes_az,
            "en": notes_en,
            "ru": notes_ru,
        },
        "releaseUrl": f"https://github.com/{repo}/releases/tag/{tag_name}",
    }


def upload_asset(upload_url_template: str, asset_name: str, file_path: Path, token: str, content_type: str) -> dict:
    upload_url = upload_url_template.split("{", 1)[0] + f"?name={asset_name}"
    file_size = file_path.stat().st_size
    print(f"[upload] {file_path} ({file_size / (1024 * 1024):.2f} MB) -> {asset_name}")

    headers = {
        "Authorization": f"Bearer {token}",
        "User-Agent": USER_AGENT,
        "Content-Type": content_type,
        "Content-Length": str(file_size),
        "Accept": "application/vnd.github+json",
    }
    req = urllib.request.Request(upload_url, data=file_path.read_bytes(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            info = json.loads(resp.read().decode("utf-8"))
            print(f"[ok] Uploaded {asset_name}: {info.get('browser_download_url')}")
            return info
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:400]
        print(f"[error] Failed to upload {asset_name} (HTTP {e.code}): {detail}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    configure_stdio()
    args = parse_args()
    root = repo_root()
    changelog = collect_manual_changelog(args)
    version_name, version_code = get_version_info(root)
    repo = get_git_repo(root)
    target_commitish = args.target.strip() or get_git_branch(root)

    tag_name = f"v{version_name}-b{version_code}"
    release_name = f"{APP_NAME} v{version_name} (Build {version_code})"

    should_build = (
        not args.notes_only
        and not args.no_build
        and (args.build or (not args.apk and not args.dry_run))
    )

    if should_build:
        apk_path: Path | None = build_local_apk(root, args.variant, version_name)
    else:
        apk_path = resolve_apk(root, args.apk, args.variant, version_name)

    if not apk_path and not args.notes_only and not args.dry_run:
        print(
            f"[error] {APK_ASSET_NAME} not found. Run without --no-build, build with Gradle first, or pass --apk <path>.",
            file=sys.stderr,
        )
        sys.exit(1)

    apk_size_bytes = apk_path.stat().st_size if apk_path else 0
    apk_size_mb = apk_size_bytes / (1024 * 1024) if apk_size_bytes else 0.0
    sha256_hash = compute_sha256(apk_path) if apk_path else ""

    body = build_release_body(
        version_name=version_name,
        version_code=version_code,
        summary=args.summary,
        changelog=changelog,
        apk_size_mb=apk_size_mb,
        sha256_hash=sha256_hash or None,
        lang=args.lang,
    )
    manifest = build_update_manifest(
        repo=repo,
        tag_name=tag_name,
        version_name=version_name,
        version_code=version_code,
        channel=args.channel,
        force_update=args.force_update,
        min_supported=args.min_supported,
        apk_name=APK_ASSET_NAME,
        apk_size_bytes=apk_size_bytes,
        sha256_hash=sha256_hash,
        notes_az=args.notes_az.strip() or changelog,
        notes_en=args.notes_en.strip() or changelog,
        notes_ru=args.notes_ru.strip() or changelog,
    )

    if args.dry_run:
        print(f"[dry-run] Repository: {repo} | Target: {target_commitish} | Tag: {tag_name} | APK: {apk_path or 'none'}")
        print("\n--- RELEASE BODY ---\n" + body)
        print("--- UPDATE MANIFEST ---\n" + json.dumps(manifest, ensure_ascii=False, indent=2))
        return

    token = get_git_token(root)
    if not token:
        print(
            "[error] GitHub token not found. Set GITHUB_TOKEN, create a gitignored .github-token file, "
            "sign in via Git Credential Manager, or configure ~/.git-credentials.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"[release] Preparing {release_name} ({tag_name}) on {repo}")
    headers = {
        "Authorization": f"Bearer {token}",
        "User-Agent": USER_AGENT,
        "Accept": "application/vnd.github+json",
    }
    releases_url = f"https://api.github.com/repos/{repo}/releases"
    try:
        with urllib.request.urlopen(urllib.request.Request(releases_url, headers=headers), timeout=30) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(
            f"[error] Could not list releases (HTTP {e.code}): {e.read().decode('utf-8', errors='replace')[:300]}",
            file=sys.stderr,
        )
        sys.exit(1)

    existing = next((r for r in releases if r.get("tag_name") == tag_name), None)
    is_prerelease = args.channel == "beta"
    make_latest = "false" if is_prerelease else "true"
    payload = {
        "name": release_name,
        "body": body,
        "draft": False,
        "prerelease": is_prerelease,
        "make_latest": make_latest,
    }

    if existing:
        release_id = existing["id"]
        patch_req = urllib.request.Request(
            f"{releases_url}/{release_id}",
            data=json.dumps(payload).encode("utf-8"),
            headers={**headers, "Content-Type": "application/json"},
            method="PATCH",
        )
        with urllib.request.urlopen(patch_req, timeout=30) as resp:
            updated = json.loads(resp.read().decode("utf-8"))
            upload_url_template = updated["upload_url"]
        print(f"[ok] Updated release {tag_name} (ID {release_id})")

        if args.notes_only:
            print(f"[ok] Release URL: https://github.com/{repo}/releases/tag/{tag_name}")
            return

        for asset in existing.get("assets", []):
            if asset.get("name") in (APK_ASSET_NAME, "update-manifest.json"):
                del_req = urllib.request.Request(
                    f"{releases_url}/assets/{asset['id']}", headers=headers, method="DELETE"
                )
                try:
                    with urllib.request.urlopen(del_req, timeout=30):
                        pass
                except Exception:
                    pass
    else:
        payload.update({"tag_name": tag_name, "target_commitish": target_commitish})
        post_req = urllib.request.Request(
            releases_url,
            data=json.dumps(payload).encode("utf-8"),
            headers={**headers, "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(post_req, timeout=30) as resp:
            created = json.loads(resp.read().decode("utf-8"))
            upload_url_template = created["upload_url"]
            print(f"[ok] Created release {tag_name} (ID {created['id']})")

        if args.notes_only:
            print(f"[ok] Release URL: https://github.com/{repo}/releases/tag/{tag_name}")
            return

    assert apk_path is not None
    upload_asset(upload_url_template, APK_ASSET_NAME, apk_path, token, "application/vnd.android.package-archive")

    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as tf:
        json.dump(manifest, tf, ensure_ascii=False, indent=2)
        manifest_tmp = Path(tf.name)
    try:
        upload_asset(upload_url_template, "update-manifest.json", manifest_tmp, token, "application/json")
    finally:
        manifest_tmp.unlink(missing_ok=True)

    print(f"[ok] Release: https://github.com/{repo}/releases/tag/{tag_name}")
    print(f"[ok] Latest:  https://github.com/{repo}/releases/latest")
    print(f"[ok] APK URL: https://github.com/{repo}/releases/latest/download/{APK_ASSET_NAME}")


if __name__ == "__main__":
    main()
