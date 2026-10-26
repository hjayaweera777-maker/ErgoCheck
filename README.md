# Ergo Check (Android)

Take or choose a photo of a worker, and the app measures posture angles, flags
ergonomic problems (OK / CAUTION / RISK) and shows correction recommendations on
the annotated photo. Runs fully offline on the phone (MediaPipe Pose).

## Build the APK with GitHub (no coding tools needed)

1. Create a free account at github.com and click New repository (name: ErgoCheck, Public or Private, no README).
2. On the new repository page click "uploading an existing file".
3. Unzip ErgoCheck.zip on your PC and drag EVERYTHING inside the ErgoCheck folder
   into the page (including the hidden .github folder), then click Commit changes.
   - If the .github folder did not upload: click Add file > Create new file, type
     `.github/workflows/build.yml` as the name, paste the contents of that file from the zip, and commit.
4. Open the Actions tab. "Build APK" starts automatically (5-10 min).
   If it does not, open it and click Run workflow.
5. When it shows a green tick, open the run, scroll to Artifacts and download
   ErgoCheck-apk (a zip containing app-debug.apk).
6. Copy app-debug.apk to your phone, open it and allow "Install unknown apps" when asked.

If the build fails (red cross), open the failed run, copy the last ~30 lines of the
red log and send them to Claude.

## Tips for good photos
- Side view: camera at hip/chest height, 2-3 m away, whole body in frame.
- Front view: person faces the camera.
- Good light, plain background, camera held level.
