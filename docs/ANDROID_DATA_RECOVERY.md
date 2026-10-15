# MyCON Android 1.2.1 — Data-safe recovery

## Emergency rule

**Do not uninstall MyCON or clear its app data.** Even if you see a "416 MB session", old exports can be incomplete. Never uninstall to bypass Android's "App not installed / package conflicts" error: that error may mean the installed APK is signed by a different certificate.

## What changed

- Existing package name stays `ir.mycon.capture` and original capture location stays unchanged.
- Session browser now shows unfinished folders even without a valid `session.json`.
- Each recording opens a read-only **Files / ZIP** recovery view. It lists files and sizes.
- **Save ZIP** uses the Android system Save As picker (Storage Access Framework), streams directly to Downloads/user-chosen storage, reopens the saved ZIP, reads and verifies **every ZIP entry and CRC**, and shows success only if the saved archive includes all original files with matching lengths.
- **Save this file** exports any individual source file directly (e.g. `arcore_recording.mp4`) with SHA-256 re-read verification.
- Legacy ZIP sharing creates an entirely new archive using a fresh filename, validates it before making it available, and never sends a stale incomplete ZIP from a previous attempt.
- Delete action is temporarily disabled in the recovery build; source files are never migrated, overwritten or deleted.
- This update cannot reconstruct source bytes never recorded on disk, but allows salvaging everything still stored.

## Installation compatibility — check BEFORE any update

Google/Android requires the same `applicationId` **and the same signing certificate** to update in place. Matching the version number alone does not work.

APK build in GitHub Actions uses a cached debug keystore; cache eviction can cause it to disappear. The new builder **fails** if it cannot restore the existing key rather than quietly producing an APK signed with a different key.

For additional safety, compare the installed APK signer with the candidate update APK **without uninstalling** (requires Android SDK platform tools and build tools on a computer):

```bash
adb devices
adb shell pm path ir.mycon.capture
# Replace REMOTE_APK_PATH with the path output after package:, e.g.
adb pull REMOTE_APK_PATH old-mycon.apk
apksigner verify --print-certs old-mycon.apk
apksigner verify --print-certs new-mycon-1.2.1.apk
```

Compare the SHA-256 **certificate digest** from both commands. If different, **STOP** and do not uninstall. A direct update is impossible without the original signing key.

On compatible signatures, normal Android update should preserve app data because we keep the same `applicationId`, do not change storage roots and do not run any destructive migrations. Still back up the 416 MB recording before updating when possible.

## Independent emergency backup over USB/ADB

On some devices Android's file manager hides `Android/data`, but ADB may read the app-specific external storage. Try:

```bash
adb pull /sdcard/Android/data/ir.mycon.capture/files/sessions ./MYCON_BACKUP
```

If the USB method is denied, **do not root/uninstall** as a workaround. Report the Android version, installed app version and the exact ADB error. A debug APK may also expose selected internal data via `adb shell run-as ir.mycon.capture`, but availability depends on the installed build.

## Limitations

Successful Gradle CI build does not prove behavior on every phone or guarantee the contents of a recorded MP4. Large exports require sufficient free space in the **selected destination**, a stable storage provider and time to read back the full file. If the actual MP4 is truncated/corrupted, it can be copied without reconstructing missing bytes.

## Operator recovery flow

`برداشت‌ها → فایل‌ها / ZIP → ذخیره ZIP جدید → Downloads`

For partial recordings: inspect file sizes first; save `arcore_recording.mp4` separately if you urgently need the raw video. Only consider deleting old files **after** verifying independent copies; this recovery build does not offer delete.
