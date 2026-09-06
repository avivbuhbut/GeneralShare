from pathlib import Path
import re, runpy

# Keep the v1.0.2 row-detection/OCR fixes first.
runpy.run_path('shift-sync-fix/patch_v102.py', run_name='__main__')

main = Path('shift-sync-phone/app/src/main/java/il/co/aviv/shiftsync/MainActivity.java')
s = main.read_text()

replacement = r'''    private void startSync() {
        Bitmap morningCrop = morningCard.cropView.getCroppedBitmap();
        Bitmap afternoonCrop = afternoonCard.cropView.getCroppedBitmap();
        if (morningCrop == null || afternoonCrop == null) return;
        resultBox.setVisibility(View.GONE);
        setWorking(true, "מזהה את אביב והשותפים…");

        worker.execute(() -> {
            try (OcrEngine ocr = new OcrEngine(this)) {
                List<Models.ParsedPlan> readablePlans = new ArrayList<>();
                List<String> skippedDiagnostics = new ArrayList<>();

                // Each file is independent. If Aviv is not in one shift, or that image
                // cannot be parsed, continue with the other shift instead of aborting.
                try {
                    Models.ParsedPlan morning = ImageParser.parse(morningCrop, "בוקר", ocr);
                    readablePlans.add(morning);
                } catch (Throwable e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    skippedDiagnostics.add("בוקר: דולג – " + msg);
                }

                runOnUiThread(() -> progressText.setText("מזהה משמרות צהריים…"));

                try {
                    Models.ParsedPlan afternoon = ImageParser.parse(afternoonCrop, "צהריים", ocr);
                    readablePlans.add(afternoon);
                } catch (Throwable e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    skippedDiagnostics.add("צהריים: דולג – " + msg);
                }

                Models.ParseBundle bundle = buildEvents(readablePlans.toArray(new Models.ParsedPlan[0]));
                bundle.diagnostics.addAll(skippedDiagnostics);

                if (bundle.events.isEmpty()) {
                    throw new IllegalStateException("לא נמצאה משמרת של אביב באף אחת מהתמונות.\n" +
                            String.join("\n", bundle.diagnostics));
                }

                runOnUiThread(() -> progressText.setText("מעדכן את Google Calendar…"));
                CalendarSync.Result syncResult = CalendarSync.sync(this, bundle.events);
                runOnUiThread(() -> showSuccess(bundle.events, syncResult));
            } catch (Throwable e) {
                runOnUiThread(() -> showError(e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                morningCrop.recycle();
                afternoonCrop.recycle();
                runOnUiThread(() -> {
                    setWorking(false, "");
                    updateSyncEnabled();
                });
            }
        });
    }
'''

s2, n = re.subn(
    r'    private void startSync\(\) \{.*?\n    \}\n\n(?=    private Models\.ParseBundle buildEvents)',
    replacement + '\n', s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f'startSync replacement failed: {n}')
main.write_text(s2)

gradle = Path('shift-sync-phone/app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('versionCode = 3', 'versionCode = 4', 1)
g = g.replace('versionName = "1.0.2"', 'versionName = "1.0.3"', 1)
gradle.write_text(g)

check = main.read_text()
assert 'Each file is independent.' in check
assert 'readablePlans.add(morning)' in check
assert 'readablePlans.add(afternoon)' in check
assert 'bundle.diagnostics.addAll(skippedDiagnostics)' in check
assert 'versionCode = 4' in gradle.read_text()
assert 'versionName = "1.0.3"' in gradle.read_text()
print('PATCH_V103_OK')
