from pathlib import Path
import re, runpy

# Keep the working v1.0.2 row-selection fix and v1.0.3 per-file resilience.
runpy.run_path('shift-sync-fix/patch_v103.py', run_name='__main__')

# --- Modern OCR engine: Tesseract 5.5.1 + LSTM, tuned for the small Hebrew name cells. ---
ocr = Path('shift-sync-phone/app/src/main/java/il/co/aviv/shiftsync/OcrEngine.java')
ocr.write_text(r'''package il.co.aviv.shiftsync;

import android.content.Context;
import android.graphics.Bitmap;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class OcrEngine implements AutoCloseable {
    static final List<String> KNOWN_NAMES = Arrays.asList("אביב", "אלימלך", "אוקסנה", "מתן", "אשר");
    private static final String HEBREW_WHITELIST = "אבגדהוזחטיכךלמםנןסעפףצץקרשת";

    private final TessBaseAPI tess = new TessBaseAPI();

    OcrEngine(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "ocr_v5");
        File tessdata = new File(root, "tessdata");
        if (!tessdata.exists() && !tessdata.mkdirs()) {
            throw new IllegalStateException("לא ניתן להכין את רכיב זיהוי העברית.");
        }

        // Always refresh the bundled model so an older installation cannot keep the weak legacy model.
        File heb = new File(tessdata, "heb.traineddata");
        try (InputStream in = context.getAssets().open("tessdata/heb.traineddata");
             FileOutputStream out = new FileOutputStream(heb, false)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }

        if (!tess.init(root.getAbsolutePath(), "heb", TessBaseAPI.OEM_LSTM_ONLY)) {
            throw new IllegalStateException("זיהוי העברית לא הצליח להיטען.");
        }
        tess.setVariable("preserve_interword_spaces", "0");
        tess.setVariable("user_defined_dpi", "300");
        tess.setVariable("tessedit_char_whitelist", HEBREW_WHITELIST);
    }

    NameResult recognizeName(Bitmap source) {
        List<Bitmap> variants = makeVariants(source);
        List<Candidate> results = new ArrayList<>();
        try {
            // Names in the spreadsheet are one text line. PSM_SINGLE_LINE is materially
            // more accurate for this exact Excel font than SINGLE_WORD.
            for (Bitmap variant : variants) {
                results.add(run(variant, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE));
            }
            double bestKnown = bestKnownScore(results);
            if (bestKnown < 0.90) {
                for (Bitmap variant : variants) {
                    results.add(run(variant, TessBaseAPI.PageSegMode.PSM_SINGLE_WORD));
                }
            }
        } finally {
            for (Bitmap b : variants) {
                if (b != source && !b.isRecycled()) b.recycle();
            }
        }

        String bestRaw = "";
        String bestName = "";
        double bestScore = -999;
        for (Candidate candidate : results) {
            String cleaned = clean(candidate.text);
            NameMatch match = normalizeName(cleaned);
            double score = match.score * 100.0 + candidate.confidence * 0.18
                    + hebrewCount(cleaned) * 0.20 - nonHebrewLetters(cleaned) * 4.0;
            if (score > bestScore) {
                bestScore = score;
                bestRaw = cleaned;
                bestName = match.name;
            }
        }
        return new NameResult(bestName, bestRaw, Math.max(0, bestScore));
    }

    private Candidate run(Bitmap bitmap, int psm) {
        try {
            tess.setPageSegMode(psm);
            tess.setImage(bitmap);
            String text = tess.getUTF8Text();
            int confidence = tess.meanConfidence();
            tess.clear();
            return new Candidate(text == null ? "" : text, Math.max(0, confidence));
        } catch (Throwable ignored) {
            try { tess.clear(); } catch (Throwable ignored2) {}
            return new Candidate("", 0);
        }
    }

    private List<Bitmap> makeVariants(Bitmap source) {
        List<Bitmap> out = new ArrayList<>();
        Mat rgba = new Mat();
        Utils.bitmapToMat(source, rgba);
        Mat gray = new Mat();
        if (rgba.channels() == 4) Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        else Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGB2GRAY);

        // Small phone screenshots need aggressive enlargement before OCR.
        int scale = Math.max(7, Math.min(10, 210 / Math.max(1, source.getHeight())));
        Mat big = new Mat();
        Imgproc.resize(gray, big, new Size(gray.cols() * scale, gray.rows() * scale), 0, 0, Imgproc.INTER_CUBIC);

        Mat normalized = new Mat();
        Core.normalize(big, normalized, 0, 255, Core.NORM_MINMAX);

        Mat blur = new Mat();
        Imgproc.GaussianBlur(normalized, blur, new Size(0, 0), 1.0);
        Mat sharp = new Mat();
        Core.addWeighted(normalized, 1.65, blur, -0.65, 0, sharp);
        out.add(toBitmapWithBorder(sharp, 36));

        Mat otsu = new Mat();
        Imgproc.threshold(sharp, otsu, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        out.add(toBitmapWithBorder(otsu, 36));

        Mat adaptive = new Mat();
        int block = Math.min(41, Math.min(sharp.rows(), sharp.cols()));
        if (block % 2 == 0) block--;
        if (block < 3) block = 3;
        Imgproc.adaptiveThreshold(sharp, adaptive, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, block, 11);
        out.add(toBitmapWithBorder(adaptive, 36));

        rgba.release();
        gray.release();
        big.release();
        normalized.release();
        blur.release();
        sharp.release();
        otsu.release();
        adaptive.release();
        return out;
    }

    private static Bitmap toBitmapWithBorder(Mat gray, int border) {
        Mat bordered = new Mat();
        Core.copyMakeBorder(gray, bordered, border, border, border, border, Core.BORDER_CONSTANT,
                new org.opencv.core.Scalar(255));
        Bitmap b = Bitmap.createBitmap(bordered.cols(), bordered.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(bordered, b);
        bordered.release();
        return b;
    }

    private static double bestKnownScore(List<Candidate> results) {
        double best = 0;
        for (Candidate result : results) {
            String c = clean(result.text);
            for (String name : KNOWN_NAMES) best = Math.max(best, similarityFlexible(c, name));
        }
        return best;
    }

    private static NameMatch normalizeName(String text) {
        String cleaned = clean(text);
        if (cleaned.isEmpty()) return new NameMatch("", 0);
        String bestName = "";
        double best = 0;
        double second = 0;
        for (String name : KNOWN_NAMES) {
            double s = similarityFlexible(cleaned, name);
            if (s > best) {
                second = best;
                best = s;
                bestName = name;
            } else if (s > second) {
                second = s;
            }
        }
        // Finite known-name vocabulary lets us recover a name even when one Hebrew glyph is weak.
        if (best >= 0.50 && (best - second >= 0.05 || best >= 0.72)) {
            return new NameMatch(bestName, best);
        }
        return new NameMatch("", best);
    }

    private static double similarityFlexible(String text, String name) {
        String cleaned = clean(text);
        String target = compact(name);
        String whole = compact(cleaned);
        if (whole.isEmpty() || target.isEmpty()) return 0;
        if (whole.contains(target)) return 1.0;

        double best = similarity(whole, target);
        for (String token : cleaned.split("\\s+")) {
            best = Math.max(best, similarity(token, target));
        }

        int n = target.length();
        int minLen = Math.max(1, n - 1);
        int maxLen = Math.min(whole.length(), n + 2);
        for (int len = minLen; len <= maxLen; len++) {
            for (int i = 0; i + len <= whole.length(); i++) {
                best = Math.max(best, similarity(whole.substring(i, i + len), target));
            }
        }
        return best;
    }

    static double similarity(String a, String b) {
        String x = compact(a);
        String y = compact(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int d = levenshtein(x, y);
        return Math.max(0, 1.0 - d / (double)Math.max(x.length(), y.length()));
    }

    static String clean(String value) {
        if (value == null) return "";
        String v = value.replace('\n', ' ').replace('\r', ' ');
        v = v.replaceAll("[^\\u0590-\\u05FFa-zA-Z0-9 -]", "");
        return v.replaceAll("\\s+", " ").trim();
    }

    static String compact(String value) {
        return clean(value).replaceAll("[^\\u0590-\\u05FFa-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private static int hebrewCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0590' && c <= '\u05FF') n++;
        }
        return n;
    }

    private static int nonHebrewLetters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) && !(c >= '\u0590' && c <= '\u05FF')) n++;
        }
        return n;
    }

    @Override
    public void close() {
        try { tess.recycle(); } catch (Throwable ignored) {}
    }

    static final class NameResult {
        final String name;
        final String raw;
        final double score;
        NameResult(String name, String raw, double score) {
            this.name = name;
            this.raw = raw;
            this.score = score;
        }
    }

    private static final class Candidate {
        final String text;
        final int confidence;
        Candidate(String text, int confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    private static final class NameMatch {
        final String name;
        final double score;
        NameMatch(String name, double score) { this.name = name; this.score = score; }
    }
}
''')

# --- Fix grid-column detection for colored/red empty cells. ---
parser = Path('shift-sync-phone/app/src/main/java/il/co/aviv/shiftsync/ImageParser.java')
s = parser.read_text()
s = s.replace(
    'Mat rowCrop = gray.submat(Math.max(0, y0), Math.min(gray.rows(), y2 + 1), 0, gray.cols()).clone();',
    'Mat rowCrop = gray.submat(Math.max(0, y0), Math.min(gray.rows(), y2 + 1), 0, gray.cols()).clone();\n'
    '        Mat colorRowCrop = rgba.submat(Math.max(0, y0), Math.min(rgba.rows(), y2 + 1), 0, rgba.cols()).clone();',
    1)
s = s.replace('List<Integer> xs = findVerticalLines(rowCrop);', 'List<Integer> xs = findVerticalLinesColor(colorRowCrop);', 1)
s = s.replace('if (xs.size() < 9) {', 'if (xs.size() < 8) {', 1)
s = s.replace('release(rgba, gray, rowCrop);', 'release(rgba, gray, rowCrop, colorRowCrop);')

vertical = r'''    private static List<Integer> findVerticalLinesColor(Mat colorCrop) {
        int h = colorCrop.rows(), w = colorCrop.cols();
        List<Integer> xs = new ArrayList<>();

        // Excel grid borders are dark in all RGB channels. A red empty cell is dark only
        // after grayscale conversion, which used to make the entire red block look like a line.
        // Work in color and require a near-black/dark-neutral column through most of both rows.
        for (int x = 0; x < w; x++) {
            int dark = 0;
            for (int y = 0; y < h; y++) {
                double[] px = colorCrop.get(y, x);
                if (px == null || px.length < 3) continue;
                double max = Math.max(px[0], Math.max(px[1], px[2]));
                double min = Math.min(px[0], Math.min(px[1], px[2]));
                boolean darkNeutral = max < 138 && (max - min) < 62;
                if (darkNeutral) dark++;
            }
            if (dark >= h * 0.52) xs.add(x);
        }
        return clusterPositions(xs, Math.max(1, w / 1200));
    }
'''
s2, n = re.subn(
    r'    private static List<Integer> findVerticalLines\(Mat grayCrop\) \{.*?\n    \}\n\n(?=    private static int safeBlock)',
    vertical + '\n', s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f'findVerticalLines replacement failed: {n}')
parser.write_text(s2)

# --- Cleaner, readable result summary. ---
main = Path('shift-sync-phone/app/src/main/java/il/co/aviv/shiftsync/MainActivity.java')
m = main.read_text()
summary = r'''    private void showSuccess(List<Models.ShiftEvent> events, CalendarSync.Result syncResult) {
        resultBox.removeAllViews();
        resultBox.setVisibility(View.VISIBLE);
        resultBox.setPadding(dp(14), dp(14), dp(14), dp(14));
        resultBox.setBackground(roundRect(Color.rgb(18, 49, 35), Color.rgb(55, 134, 92), 16));

        TextView ok = text("היומן עודכן", 21, Color.WHITE, true);
        resultBox.addView(ok);

        TextView meta = text("נמצאו ונשמרו " + events.size() + " משמרות", 14,
                Color.rgb(206, 236, 220), false);
        meta.setPadding(0, dp(3), 0, dp(10));
        resultBox.addView(meta);

        List<Models.ShiftEvent> ordered = new ArrayList<>(events);
        ordered.sort((a, b) -> {
            int dateCompare = a.date.compareTo(b.date);
            if (dateCompare != 0) return dateCompare;
            if (a.shift.equals(b.shift)) return 0;
            return "בוקר".equals(a.shift) ? -1 : 1;
        });

        for (Models.ShiftEvent e : ordered) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(roundRect(Color.rgb(24, 43, 35), Color.rgb(66, 119, 92), 12));

            String dateShort = e.date.getDayOfMonth() + "/" + e.date.getMonthValue();
            TextView head = text("יום " + e.weekday + "  •  " + dateShort + "  •  " + e.shift,
                    16, Color.WHITE, true);
            card.addView(head);

            TextView detail = text(e.area + "  •  רכב " + e.car + "  •  עם " + e.partner,
                    14, Color.rgb(220, 235, 226), false);
            detail.setPadding(0, dp(3), 0, 0);
            card.addView(detail);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(5), 0, dp(5));
            resultBox.addView(card, lp);
        }
    }
'''
m2, n = re.subn(
    r'    private void showSuccess\(List<Models\.ShiftEvent> events, CalendarSync\.Result syncResult\) \{.*?\n    \}\n\n(?=    private void showError)',
    lambda match: summary + '\n', m, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f'showSuccess replacement failed: {n}')
main.write_text(m2)

# --- Dependency/repository/version update. ---
gradle = Path('shift-sync-phone/app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('implementation("com.rmtheis:tess-two:9.1.0")',
              'implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")', 1)
g = g.replace('versionCode = 4', 'versionCode = 5', 1)
g = g.replace('versionName = "1.0.3"', 'versionName = "1.0.4"', 1)
gradle.write_text(g)

settings = Path('shift-sync-phone/settings.gradle.kts')
if settings.exists():
    st = settings.read_text()
    if 'jitpack.io' not in st:
        if 'mavenCentral()' not in st:
            raise SystemExit('mavenCentral marker not found in settings.gradle.kts')
        st = st.replace('mavenCentral()', 'mavenCentral()\n        maven { url = uri("https://jitpack.io") }', 1)
    settings.write_text(st)
else:
    raise SystemExit('settings.gradle.kts not found')

# Sanity checks performed before Gradle gets a chance to compile.
assert 'OEM_LSTM_ONLY' in ocr.read_text()
assert 'PSM_SINGLE_LINE' in ocr.read_text()
assert 'findVerticalLinesColor' in parser.read_text()
assert 'if (xs.size() < 8)' in parser.read_text()
assert 'נמצאו ונשמרו' in main.read_text()
assert 'tesseract4android-openmp:4.9.0' in gradle.read_text()
assert 'versionCode = 5' in gradle.read_text()
assert 'versionName = "1.0.4"' in gradle.read_text()
assert 'jitpack.io' in settings.read_text()
print('PATCH_V104_OK')
