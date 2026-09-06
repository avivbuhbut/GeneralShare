from pathlib import Path
import re

parser = Path('shift-sync-phone/app/src/main/java/il/co/aviv/shiftsync/ImageParser.java')
s = parser.read_text()

detect = '''    private static int[] detectTwoRows(Mat gray) {
        List<Integer> lines = findHorizontalLines(gray);
        Collections.sort(lines);
        int h = gray.rows();

        // Always use the actual LAST two Excel grid rows.
        if (lines.size() >= 3) {
            for (int i = lines.size() - 3; i >= 0; i--) {
                int y0 = lines.get(i), y1 = lines.get(i + 1), y2 = lines.get(i + 2);
                int topHeight = y1 - y0;
                int bottomHeight = y2 - y1;
                if (Math.min(topHeight, bottomHeight) < 8) continue;
                double ratio = Math.max(topHeight, bottomHeight) /
                        (double)Math.max(1, Math.min(topHeight, bottomHeight));
                if (ratio <= 2.35) return new int[]{y0, y1, y2};
            }
        }

        // Bottom grid border may be clipped by the screenshot.
        if (lines.size() >= 2) {
            int y0 = lines.get(lines.size() - 2);
            int y1 = lines.get(lines.size() - 1);
            int firstHeight = y1 - y0;
            int edgeHeight = (h - 1) - y1;
            if (firstHeight >= 8 && edgeHeight >= 8) {
                double ratio = Math.max(firstHeight, edgeHeight) /
                        (double)Math.max(1, Math.min(firstHeight, edgeHeight));
                if (ratio <= 2.35) return new int[]{y0, y1, h - 1};
            }
        }

        // Tight crop with center and bottom borders but no top border.
        if (lines.size() >= 2) {
            int y1 = lines.get(lines.size() - 2);
            int y2 = lines.get(lines.size() - 1);
            int row = y2 - y1;
            if (row >= 8 && y2 >= h * 0.72) {
                int y0 = Math.max(0, y1 - row);
                if (y1 - y0 >= 8) return new int[]{y0, y1, y2};
            }
        }

        if (h >= 24 && gray.cols() / (double)Math.max(1, h) >= 4.0) {
            int mid = h / 2;
            return new int[]{0, mid, h - 1};
        }
        throw new IllegalStateException("קווי שתי השורות האחרונות לא זוהו בבטחה. השאר את כל רוחב הטבלה בתמונה.");
    }
'''

horizontal = '''    private static List<Integer> findHorizontalLines(Mat gray) {
        int h = gray.rows(), w = gray.cols();
        Mat inv = new Mat();
        int block = safeBlock(gray, 31);
        Imgproc.adaptiveThreshold(gray, inv, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV, block, 12);

        // Use the longest continuous dark run per row. This catches Excel grid lines
        // even when contour detection merges a horizontal border with vertical borders/text.
        List<Integer> ys = new ArrayList<>();
        int minRun = Math.max(18, (int)(w * 0.22));
        for (int y = 0; y < h; y++) {
            int run = 0, longest = 0;
            for (int x = 0; x < w; x++) {
                if (inv.get(y, x)[0] > 0) {
                    run++;
                    if (run > longest) longest = run;
                } else {
                    run = 0;
                }
            }
            if (longest >= minRun) ys.add(y);
        }
        inv.release();
        return clusterPositions(ys, Math.max(2, h / 100));
    }
'''

s2, n1 = re.subn(
    r'    private static int\[] detectTwoRows\(Mat gray\) \{.*?\n    \}\n\n(?=    private static List<Integer> findHorizontalLines)',
    detect + '\n', s, count=1, flags=re.S)
if n1 != 1:
    raise SystemExit(f'detectTwoRows replacement failed: {n1}')

s3, n2 = re.subn(
    r'    private static List<Integer> findHorizontalLines\(Mat gray\) \{.*?\n    \}\n\n(?=    private static List<Integer> findVerticalLines)',
    horizontal + '\n', s2, count=1, flags=re.S)
if n2 != 1:
    raise SystemExit(f'findHorizontalLines replacement failed: {n2}')

parser.write_text(s3)

ocr = Path('shift-sync-phone/app/src/main/java/il/co/aviv/shiftsync/OcrEngine.java')
o = ocr.read_text()
o = o.replace('if (!heb.exists() || heb.length() < 100_000) {', 'if (true) {', 1)
ocr.write_text(o)

gradle = Path('shift-sync-phone/app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('versionCode = 1', 'versionCode = 3', 1)
g = g.replace('versionName = "1.0.0"', 'versionName = "1.0.2"', 1)
if 'abiFilters += listOf("arm64-v8a")' not in g:
    marker = '        versionName = "1.0.2"\n'
    if marker not in g:
        raise SystemExit('version marker not found')
    g = g.replace(marker, marker + '        ndk {\n            abiFilters += listOf("arm64-v8a")\n        }\n', 1)
gradle.write_text(g)

assert 'Always use the actual LAST two Excel grid rows.' in parser.read_text()
assert 'int minRun = Math.max(18' in parser.read_text()
assert 'versionCode = 3' in gradle.read_text()
assert 'versionName = "1.0.2"' in gradle.read_text()
assert 'arm64-v8a' in gradle.read_text()
print('PATCH_OK')
