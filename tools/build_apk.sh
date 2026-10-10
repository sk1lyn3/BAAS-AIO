#!/usr/bin/env bash
# Shared by local builds and GitHub Actions. Package a committed BAAS snapshot.
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
baas_source=$(cd -- "${1:-$project_dir/../blue_archive_auto_script}" && pwd)
variant=${2:-release}
baas_ref=${3:-HEAD}
aio_branch=$(git -C "$project_dir" symbolic-ref --quiet --short HEAD || printf '%s' "${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-detached}}")
aio_branch_name=$(printf '%s' "$aio_branch" | tr -c '[:alnum:]_.-' '-')
case "$variant" in
  debug) task=assembleDebug; source_apk=app-debug.apk ;;
  release) task=assembleRelease; source_apk=app-release-unsigned.apk ;;
  *) printf 'Variant must be debug or release\n' >&2; exit 2 ;;
esac
aio_version=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$project_dir/app/build.gradle.kts")
[[ "$aio_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'Expected a three-part versionName\n' >&2; exit 2; }
if [[ "$variant" == release ]]; then
    local_signing_env=${AIO_SIGNING_ENV:-${XDG_DATA_HOME:-$HOME/.local/share}/baas-release-signing/baas-aio/signing.env}
    if [[ -z ${APK_KEYSTORE:-} && -f "$local_signing_env" ]]; then
        set -a
        source "$local_signing_env"
        set +a
    fi
    : "${APK_KEYSTORE:?Release builds require a signing keystore}"
    : "${APK_KEYSTORE_PASSWORD:?Set APK_KEYSTORE_PASSWORD}"
    : "${APK_KEY_ALIAS:?Set APK_KEY_ALIAS}"
    [[ -f "$APK_KEYSTORE" ]] || { printf 'Signing keystore does not exist\n' >&2; exit 2; }
fi
baas_sha=$(git -C "$baas_source" rev-parse --verify --end-of-options "${baas_ref}^{commit}")
ocr_prebuild=${OCR_PREBUILD:-$baas_source/core/ocr/baas_ocr_client/bin}
ocr_sha=$(git -C "$ocr_prebuild" rev-parse --verify origin/android-arm64-v8a)
android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${android_sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
ocr_ndk=${OCR_NDK:-$android_sdk/ndk/27.2.12479018}
build_python=${BUILD_PYTHON:-python3}
output_dir=${APK_OUTPUT_DIR:-$project_dir/artifacts/apk}
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
printf 'Building AIO %s with BAAS %s and OCR %s\n' "$variant" "$baas_sha" "$ocr_sha"
cd -- "$project_dir"
./gradlew ":app:$task" --no-daemon --max-workers=1 \
    "-PbaasSource=$baas_source" "-PbaasRevision=$baas_sha" \
    "-PocrPrebuild=$ocr_prebuild" "-PocrNdk=$ocr_ndk" "-PbuildPython=$build_python"
artifact="$output_dir/baas-aio-$aio_branch_name-$variant-$aio_version-${baas_sha:0:7}.apk"
cp -- "$project_dir/app/build/outputs/apk/$variant/$source_apk" "$artifact"
signature=$([[ "$variant" == debug ]] && printf debug-key || printf unsigned)
if [[ -n ${APK_KEYSTORE:-} ]]; then
    : "${APK_KEYSTORE_PASSWORD:?Set APK_KEYSTORE_PASSWORD}"
    : "${APK_KEY_ALIAS:?Set APK_KEY_ALIAS}"
    export APK_KEY_PASSWORD=${APK_KEY_PASSWORD:-$APK_KEYSTORE_PASSWORD}
    "$android_sdk/build-tools/35.0.0/apksigner" sign --ks "$APK_KEYSTORE" \
        --ks-key-alias "$APK_KEY_ALIAS" --ks-pass env:APK_KEYSTORE_PASSWORD \
        --key-pass env:APK_KEY_PASSWORD "$artifact"
    "$android_sdk/build-tools/35.0.0/apksigner" verify "$artifact"
    signature=$([[ "$variant" == release ]] && printf release-key || printf custom-key)
fi
export AIO_BUILD_ARTIFACT="$artifact" AIO_BUILD_VARIANT="$variant" AIO_BUILD_SIGNATURE="$signature"
export AIO_BUILD_BAAS_SHA="$baas_sha" AIO_BUILD_OCR_SHA="$ocr_sha" AIO_BUILD_BRANCH="$aio_branch"
export AIO_BUILD_VERSION="$aio_version" AIO_BUILD_SDK="$android_sdk"
python3 - <<'PY'
import hashlib, json, os, pathlib, re, subprocess, zipfile
artifact = pathlib.Path(os.environ['AIO_BUILD_ARTIFACT'])
toolchain = pathlib.Path(os.environ['AIO_BUILD_SDK']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(toolchain / 'aapt'), 'dump', 'badging', str(artifact)], text=True)
version = re.search(r"versionName='([^']+)'", badging).group(1)
version_code = int(re.search(r"versionCode='(\d+)'", badging).group(1))
assert version == os.environ['AIO_BUILD_VERSION'], 'Wrong version in APK'
certificate = subprocess.check_output([str(toolchain / 'apksigner'), 'verify', '--print-certs', str(artifact)], text=True)
certificate_sha256 = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', certificate).group(1)
with zipfile.ZipFile(artifact) as apk:
    import io
    with zipfile.ZipFile(io.BytesIO(apk.read('assets/baas-source.zip'))) as source:
        manifest = json.loads(source.read('source-manifest.json'))
        assert manifest['head'] == os.environ['AIO_BUILD_BAAS_SHA'], 'Wrong BAAS snapshot in APK'
        for name, digest in manifest['files'].items():
            assert hashlib.sha256(source.read(name)).hexdigest() == digest, name
info = {'aioCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        'aioBranch': os.environ['AIO_BUILD_BRANCH'], 'baasCommit': manifest['head'], 'ocrCommit': os.environ['AIO_BUILD_OCR_SHA'],
        'variant': os.environ['AIO_BUILD_VARIANT'], 'signature': os.environ['AIO_BUILD_SIGNATURE'],
        'versionName': version, 'versionCode': version_code, 'certificateSha256': certificate_sha256,
        'apk': artifact.name, 'bytes': artifact.stat().st_size,
        'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
        'baasFiles': len(manifest['files'])}
(artifact.parent / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
print(json.dumps(info, indent=2))
PY
