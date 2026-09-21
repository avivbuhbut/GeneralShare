package com.aviv.roomsort;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_GALLERY = 1002;
    private static final String PREFS = "room_organizer_secure";
    private static final String PREF_KEY = "api_key_encrypted";
    private static final String KEY_ALIAS = "room_organizer_api_key";
    private static final String IMAGE_MODEL = "gpt-image-2";
    private static final String VISION_MODEL = "gpt-5.6-luna";

    private final int bg = Color.rgb(247, 246, 242);
    private final int card = Color.WHITE;
    private final int ink = Color.rgb(30, 36, 34);
    private final int muted = Color.rgb(96, 107, 102);
    private final int accent = Color.rgb(57, 96, 81);
    private final int accentSoft = Color.rgb(224, 234, 228);

    private ImageView originalImage;
    private ImageView resultImage;
    private TextView statusText;
    private TextView resultLabel;
    private TextView stepsLabel;
    private TextView stepsText;
    private LinearLayout stepsCard;
    private TextView feedbackLabel;
    private LinearLayout feedbackCard;
    private EditText feedbackInput;
    private Button feedbackButton;
    private Button alternateButton;
    private Button tidyModeButton;
    private Button redesignModeButton;
    private ProgressBar progress;
    private Button organizeButton;
    private Button retryButton;
    private Button saveButton;
    private LinearLayout resultButtonsRow;
    private File normalizedInput;
    private Bitmap originalBitmap;
    private Bitmap resultBitmap;
    private byte[] resultBytes;
    private Uri captureUri;
    private boolean running = false;
    private boolean redesignMode = true;

    private static final String BASE_EDIT_PROMPT =
            "EDIT ONLY this exact room photo. Reorganize the same real room using ONLY objects and furniture already visible in the input image.\n\n" +
            "OBJECT CONSERVATION IS MANDATORY:\n" +
            "- Keep the exact same room, architecture, walls, floor, ceiling, windows, doors, camera angle, perspective and geometry.\n" +
            "- Keep every existing furniture piece and every meaningful visible object. Preserve identity, color, material, approximate size and count.\n" +
            "- You may move, rotate, fold, stack, group, align, close, or neatly place existing movable things in physically plausible locations.\n" +
            "- Do NOT add, invent, duplicate, replace or redesign any furniture, storage, shelf, basket, box, decoration, plant, lamp, rug, appliance, artwork, container or object.\n" +
            "- Do NOT remove clutter by deleting objects. Every meaningful object visible in the input must still exist in the edited result.\n" +
            "- If an object cannot reasonably be moved, leave it where it is.\n" +
            "- Do not renovate, repaint, or alter architecture.\n" +
            "- Output one photorealistic edited version of the SAME photo. No text, labels, arrows, annotations or instructions.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        buildUi();

        if (loadApiKey().isEmpty()) {
            originalImage.postDelayed(() -> showApiKeyDialog(true), 350);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = text("מסדר לי", 30, ink, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        Button apiButton = button("מפתח API", false);
        apiButton.setOnClickListener(v -> showApiKeyDialog(false));
        header.addView(apiButton, new LinearLayout.LayoutParams(dp(112), dp(48)));
        root.addView(header);

        TextView subtitle = text("צלם את החדר כמו שהוא. קבל את אותו חדר מסודר + צעדים מדויקים איך להגיע לזה.", 18, muted, false);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        LinearLayout photoCard = cardContainer();
        originalImage = new ImageView(this);
        originalImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        originalImage.setAdjustViewBounds(true);
        originalImage.setMinimumHeight(dp(270));
        originalImage.setBackgroundColor(Color.rgb(238, 239, 235));
        photoCard.addView(originalImage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(310)));

        TextView emptyHint = text("התמונה שלך תופיע כאן", 15, muted, false);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, dp(10), 0, 0);
        photoCard.addView(emptyHint);
        root.addView(photoCard);

        LinearLayout sourceButtons = new LinearLayout(this);
        sourceButtons.setOrientation(LinearLayout.HORIZONTAL);
        sourceButtons.setPadding(0, dp(14), 0, 0);
        sourceButtons.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Button cameraButton = button("📷  צלם חדר", true);
        cameraButton.setOnClickListener(v -> takePhoto());
        sourceButtons.addView(cameraButton, weighted());

        Button galleryButton = button("🖼  גלריה", false);
        galleryButton.setOnClickListener(v -> chooseFromGallery());
        LinearLayout.LayoutParams galleryLp = weighted();
        galleryLp.setMarginStart(dp(10));
        sourceButtons.addView(galleryButton, galleryLp);
        root.addView(sourceButtons);

        TextView modeLabel = text("איך תרצה שאסדר?", 18, ink, true);
        modeLabel.setPadding(0, dp(18), 0, dp(8));
        root.addView(modeLabel);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        redesignModeButton = button("✓ ארגון מחדש", true);
        redesignModeButton.setOnClickListener(v -> setRedesignMode(true));
        modeRow.addView(redesignModeButton, weighted());

        tidyModeButton = button("סידור יסודי", false);
        tidyModeButton.setOnClickListener(v -> setRedesignMode(false));
        LinearLayout.LayoutParams tidyLp = weighted();
        tidyLp.setMarginStart(dp(10));
        modeRow.addView(tidyModeButton, tidyLp);
        root.addView(modeRow);

        organizeButton = button("תן לי סידור חכם", true);
        organizeButton.setEnabled(false);
        organizeButton.setAlpha(0.45f);
        organizeButton.setTextSize(19);
        organizeButton.setOnClickListener(v -> organizeRoom());
        LinearLayout.LayoutParams organizeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        organizeLp.setMargins(0, dp(16), 0, 0);
        root.addView(organizeButton, organizeLp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.setMargins(0, dp(18), 0, 0);
        root.addView(progress, progressLp);

        statusText = text("", 15, muted, false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(10), 0, dp(6));
        root.addView(statusText);

        resultLabel = text("אחרי", 23, ink, true);
        resultLabel.setPadding(0, dp(22), 0, dp(10));
        resultLabel.setVisibility(View.GONE);
        root.addView(resultLabel);

        LinearLayout resultCard = cardContainer();
        resultImage = new ImageView(this);
        resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImage.setAdjustViewBounds(true);
        resultImage.setMinimumHeight(dp(280));
        resultCard.addView(resultImage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(330)));
        resultCard.setVisibility(View.GONE);
        resultImage.setTag(resultCard);
        root.addView(resultCard);

        resultButtonsRow = new LinearLayout(this);
        resultButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        resultButtonsRow.setPadding(0, dp(12), 0, 0);
        resultButtonsRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        resultButtonsRow.setVisibility(View.GONE);

        retryButton = button("עוד סידור", false);
        retryButton.setOnClickListener(v -> organizeRoom());
        resultButtonsRow.addView(retryButton, weighted());

        saveButton = button("שמור תמונה", true);
        saveButton.setOnClickListener(v -> saveResult());
        LinearLayout.LayoutParams saveLp = weighted();
        saveLp.setMarginStart(dp(10));
        resultButtonsRow.addView(saveButton, saveLp);
        root.addView(resultButtonsRow);

        stepsLabel = text("איך לסדר את זה — צעד אחרי צעד", 23, ink, true);
        stepsLabel.setPadding(0, dp(24), 0, dp(10));
        stepsLabel.setVisibility(View.GONE);
        root.addView(stepsLabel);

        stepsCard = cardContainer();
        stepsCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        stepsText = text("", 17, ink, false);
        stepsText.setLineSpacing(dp(5), 1.0f);
        stepsText.setTextIsSelectable(true);
        stepsCard.addView(stepsText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        stepsCard.setVisibility(View.GONE);
        root.addView(stepsCard);

        feedbackLabel = text("רוצה לשנות משהו?", 23, ink, true);
        feedbackLabel.setPadding(0, dp(24), 0, dp(10));
        feedbackLabel.setVisibility(View.GONE);
        root.addView(feedbackLabel);

        feedbackCard = cardContainer();
        feedbackCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        feedbackInput = new EditText(this);
        feedbackInput.setTextDirection(View.TEXT_DIRECTION_RTL);
        feedbackInput.setGravity(Gravity.RIGHT | Gravity.TOP);
        feedbackInput.setHint("לדוגמה: תשאיר את השולחן במקום / תעשה את המטבח יותר פתוח / תעביר פחות דברים");
        feedbackInput.setMinLines(2);
        feedbackInput.setMaxLines(5);
        feedbackInput.setTextSize(16);
        feedbackInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        feedbackCard.addView(feedbackInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        feedbackButton = button("עדכן לפי הבקשה שלי", true);
        feedbackButton.setOnClickListener(v -> {
            String note = feedbackInput.getText().toString().trim();
            if (note.isEmpty()) {
                feedbackInput.setError("כתוב מה תרצה לשנות");
            } else {
                organizeRoom(note, false);
            }
        });
        LinearLayout.LayoutParams feedbackBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        feedbackBtnLp.setMargins(0, dp(12), 0, 0);
        feedbackCard.addView(feedbackButton, feedbackBtnLp);

        alternateButton = button("תן לי רעיון אחר לגמרי", false);
        alternateButton.setOnClickListener(v -> organizeRoom(
                "Create a clearly different but still physically plausible arrangement from the previous idea. " +
                "Use the same inventory. Prefer a noticeably different layout, especially for work surfaces, chairs, tables, movable storage and free walking space.", true));
        LinearLayout.LayoutParams altLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        altLp.setMargins(0, dp(10), 0, 0);
        feedbackCard.addView(alternateButton, altLp);

        feedbackCard.setVisibility(View.GONE);
        root.addView(feedbackCard);

        TextView privacy = text("התמונה נשלחת ל‑OpenAI רק אחרי לחיצה על כפתור יצירה או עדכון.", 13, muted, false);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(8), dp(18), dp(8), 0);
        root.addView(privacy);

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(52), 1f);
    }

    private LinearLayout cardContainer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(card);
        gd.setCornerRadius(dp(22));
        gd.setStroke(dp(1), Color.rgb(226, 228, 222));
        box.setBackground(gd);
        return box;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : accent);
        b.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(primary ? accent : accentSoft);
        gd.setCornerRadius(dp(16));
        b.setBackground(gd);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void takePhoto() {
        if (running) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "room_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/RoomOrganizer");
            captureUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (captureUri == null) throw new IllegalStateException("Cannot create image URI");

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            toast("לא הצלחתי לפתוח את המצלמה.");
        }
    }

    private void chooseFromGallery() {
        if (running) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_GALLERY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA && captureUri != null) {
                try { getContentResolver().delete(captureUri, null, null); } catch (Exception ignored) {}
                captureUri = null;
            }
            return;
        }

        Uri selected = null;
        if (requestCode == REQ_CAMERA) selected = captureUri;
        if (requestCode == REQ_GALLERY && data != null) selected = data.getData();
        if (selected != null) loadPhoto(selected);
    }

    private void loadPhoto(Uri uri) {
        setBusy(true, "מכין את התמונה…");
        new Thread(() -> {
            try {
                Bitmap bitmap = decodeScaled(uri, 1600);
                if (bitmap == null) throw new IllegalStateException("decode failed");

                File f = new File(getCacheDir(), "room_input.jpg");
                try (OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(f))) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        throw new IllegalStateException("compress failed");
                    }
                }
                normalizedInput = f;
                originalBitmap = bitmap;
                resultBitmap = null;
                resultBytes = null;

                runOnUiThread(() -> {
                    originalImage.setImageBitmap(bitmap);
                    hideResult();
                    organizeButton.setEnabled(true);
                    organizeButton.setAlpha(1f);
                    setBusy(false, "מוכן. לחץ “סדר לי את החדר”.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "לא הצלחתי לקרוא את התמונה."));
            }
        }, "photo-loader").start();
    }

    private Bitmap decodeScaled(Uri uri, int maxEdge) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            int longest = Math.max(w, h);
            if (longest > maxEdge) {
                float scale = maxEdge / (float) longest;
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private void organizeRoom() {
        organizeRoom("", false);
    }

    private void organizeRoom(String userFeedback, boolean forceDifferentIdea) {
        if (running || normalizedInput == null || !normalizedInput.exists()) return;

        String key = loadApiKey();
        if (key.isEmpty()) {
            showApiKeyDialog(true);
            return;
        }

        setBusy(true, "מסדר את אותם החפצים…");
        hideResult();

        new Thread(() -> {
            try {
                byte[] edited = requestEdit(normalizedInput, key, userFeedback, forceDifferentIdea);
                Bitmap candidate = BitmapFactory.decodeByteArray(edited, 0, edited.length);
                if (candidate == null) throw new IllegalStateException("Invalid generated image");

                Validation validation = validateSameInventory(normalizedInput, edited, key);
                if (!validation.valid) {
                    runOnUiThread(() -> {
                        setBusy(false, "פסלתי את התוצאה: היא שינתה חפץ שלא הייתה אמורה לשנות. לחץ “סדר לי” לניסיון חדש.");
                        organizeButton.setText("נסה סידור חדש");
                    });
                    return;
                }

                runOnUiThread(() -> statusText.setText("התמונה מוכנה. מכין עכשיו צעדים אחד־אחד…"));

                String generatedSteps = "";
                try {
                    generatedSteps = generateSteps(normalizedInput, edited, key);
                } catch (Exception ignored) {
                    generatedSteps = "";
                }

                resultBytes = edited;
                resultBitmap = candidate;
                final String stepsForUi = generatedSteps;
                runOnUiThread(() -> {
                    showResult(candidate);
                    showSteps(stepsForUi);
                    setBusy(false, validation.skipped
                            ? "מוכן. בדיקת הקשיחות לא הושלמה, אבל התמונה והצעדים מוכנים."
                            : "מוכן — קיבלת גם תמונה מסודרת וגם צעדים להגיע אליה.");
                    organizeButton.setText("סדר לי את החדר");
                });
            } catch (ApiException e) {
                runOnUiThread(() -> setBusy(false, friendlyApiError(e.code, e.getMessage())));
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "משהו נכשל בזמן יצירת התמונה. בדוק חיבור ונסה שוב."));
            }
        }, "room-editor").start();
    }

    private byte[] requestEdit(File image, String apiKey, String userFeedback, boolean forceDifferentIdea) throws Exception {
        String boundary = "----RoomOrganizer" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://api.openai.com/v1/images/edits").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setChunkedStreamingMode(16384);

        try (OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
            writeField(out, boundary, "model", IMAGE_MODEL);
            writeField(out, boundary, "prompt", buildEditPrompt(userFeedback, forceDifferentIdea));
            writeField(out, boundary, "quality", "high");
            writeField(out, boundary, "size", "auto");
            writeField(out, boundary, "output_format", "jpeg");
            writeField(out, boundary, "output_compression", "92");
            writeField(out, boundary, "n", "1");

            writeUtf8(out, "--" + boundary + "\r\n");
            writeUtf8(out, "Content-Disposition: form-data; name=\"image[]\"; filename=\"room.jpg\"\r\n");
            writeUtf8(out, "Content-Type: image/jpeg\r\n\r\n");
            try (InputStream in = new BufferedInputStream(new FileInputStream(image))) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            writeUtf8(out, "\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        int code = connection.getResponseCode();
        String body = readResponse(connection, code);
        if (code < 200 || code >= 300) {
            throw new ApiException(code, extractApiMessage(body));
        }

        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) throw new Exception("No image data");
        String b64 = data.getJSONObject(0).optString("b64_json", "");
        if (b64.isEmpty()) throw new Exception("No b64_json");
        return Base64.decode(b64, Base64.DEFAULT);
    }

    private String buildEditPrompt(String userFeedback, boolean forceDifferentIdea) {
        String modePrompt;
        if (redesignMode) {
            modePrompt =
                    "\n\nREARRANGEMENT MODE: Be thorough and imaginative with the EXISTING inventory. " +
                    "Look for a better overall layout, not just surface tidying. You may reposition movable tables, chairs, stools, small cabinets, " +
                    "movable shelving, appliances that are clearly movable, and other existing furniture when physically plausible. " +
                    "Improve walking space, grouping by function, accessibility, work surfaces and visual order. " +
                    "For a kitchen, consider a more useful arrangement of existing counter items, movable appliances, table/chairs and visible storage. " +
                    "For a bedroom/living room, consider a genuinely better layout of existing movable furniture. " +
                    "Still do not invent, delete, duplicate, resize or replace anything.";
        } else {
            modePrompt =
                    "\n\nDEEP TIDY MODE: Keep the main furniture layout mostly where it is, but organize the room thoroughly. " +
                    "Group related items, clear walking paths and work surfaces, fold/stack/alignment where appropriate, and make the room look genuinely finished.";
        }

        String feedbackPrompt = "";
        if (userFeedback != null && !userFeedback.trim().isEmpty()) {
            feedbackPrompt = "\n\nUSER FEEDBACK / REQUEST (follow this unless it conflicts with object conservation): " +
                    userFeedback.trim();
        }
        if (forceDifferentIdea) {
            feedbackPrompt += "\n\nIMPORTANT: Produce a substantially different valid arrangement idea, not a near-duplicate of the previous concept.";
        }
        return BASE_EDIT_PROMPT + modePrompt + feedbackPrompt;
    }

    private void setRedesignMode(boolean redesign) {
        redesignMode = redesign;
        if (redesignModeButton != null) {
            redesignModeButton.setText(redesign ? "✓ ארגון מחדש" : "ארגון מחדש");
            redesignModeButton.setTextColor(redesign ? Color.WHITE : accent);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(redesign ? accent : accentSoft);
            gd.setCornerRadius(dp(16));
            redesignModeButton.setBackground(gd);
        }
        if (tidyModeButton != null) {
            tidyModeButton.setText(redesign ? "סידור יסודי" : "✓ סידור יסודי");
            tidyModeButton.setTextColor(redesign ? accent : Color.WHITE);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(redesign ? accentSoft : accent);
            gd.setCornerRadius(dp(16));
            tidyModeButton.setBackground(gd);
        }
        if (organizeButton != null) {
            organizeButton.setText(redesign ? "תן לי סידור חכם" : "סדר לי יסודי");
        }
    }

    private Validation validateSameInventory(File original, byte[] edited, String apiKey) {
        try {
            byte[] originalBytes = readAllBytes(original);
            String originalB64 = Base64.encodeToString(originalBytes, Base64.NO_WRAP);
            String editedB64 = Base64.encodeToString(edited, Base64.NO_WRAP);

            JSONObject request = new JSONObject();
            request.put("model", VISION_MODEL);
            request.put("max_output_tokens", 80);

            JSONArray content = new JSONArray();
            content.put(new JSONObject()
                    .put("type", "input_text")
                    .put("text",
                            "Compare image 1 (original messy room) with image 2 (edited room). " +
                            "The edit is allowed to MOVE, rotate, fold, stack and rearrange existing objects. " +
                            "It is NOT allowed to add, delete, duplicate, replace, redesign or materially transform furniture or meaningful visible objects, " +
                            "and it must not change room architecture or camera viewpoint. " +
                            "Small lighting/rendering differences are okay. " +
                            "Reply with exactly VALID if the same meaningful inventory and room are preserved. " +
                            "Reply with exactly INVALID if anything meaningful was added, removed, duplicated or replaced. If uncertain, reply INVALID."));
            content.put(new JSONObject()
                    .put("type", "input_image")
                    .put("detail", "high")
                    .put("image_url", "data:image/jpeg;base64," + originalB64));
            content.put(new JSONObject()
                    .put("type", "input_image")
                    .put("detail", "high")
                    .put("image_url", "data:image/jpeg;base64," + editedB64));

            JSONArray input = new JSONArray();
            input.put(new JSONObject().put("role", "user").put("content", content));
            request.put("input", input);

            HttpURLConnection connection = (HttpURLConnection)
                    new URL("https://api.openai.com/v1/responses").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");

            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            String body = readResponse(connection, code);
            if (code < 200 || code >= 300) return new Validation(true, true);

            String answer = extractOutputText(new JSONObject(body)).trim().toUpperCase(Locale.ROOT);
            if (answer.startsWith("INVALID")) return new Validation(false, false);
            if (answer.startsWith("VALID")) return new Validation(true, false);
            return new Validation(true, true);
        } catch (Exception ignored) {
            return new Validation(true, true);
        }
    }

    private String generateSteps(File original, byte[] edited, String apiKey) throws Exception {
        byte[] originalBytes = readAllBytes(original);
        String originalB64 = Base64.encodeToString(originalBytes, Base64.NO_WRAP);
        String editedB64 = Base64.encodeToString(edited, Base64.NO_WRAP);

        JSONObject request = new JSONObject();
        request.put("model", VISION_MODEL);
        request.put("max_output_tokens", 700);

        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "input_text")
                .put("text",
                        "Image 1 is the original messy room. Image 2 is the target organized version. " +
                        "Create a practical sequence of steps that a person can physically follow to transform image 1 into image 2. " +
                        "Write in Hebrew. Use 8 to 15 steps, ordered sensibly and thoroughly. Start with large spatial moves, then surfaces, then smaller items and finishing touches. " +
                        "Each step must say exactly which existing object or small group of existing objects to move and where to place it, " +
                        "based only on visible differences between the two images. " +
                        "Do not suggest buying anything. Do not invent furniture, boxes, shelves, baskets, storage, or objects that are not visible. " +
                        "Do not tell the user to throw things away unless the target image clearly shows the same item in a different visible location. " +
                        "Explain destination locations precisely using visible landmarks such as table, counter, wall, shelf, chair, bed or cabinet. " +
                        "If a relocation is uncertain, omit it. Keep each step short, concrete and directly actionable. " +
                        "Return ONLY a numbered Hebrew list. No title, intro, summary, markdown bullets, or extra commentary."));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("detail", "high")
                .put("image_url", "data:image/jpeg;base64," + originalB64));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("detail", "high")
                .put("image_url", "data:image/jpeg;base64," + editedB64));

        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "user").put("content", content));
        request.put("input", input);

        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://api.openai.com/v1/responses").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
            out.write(payload);
        }

        int code = connection.getResponseCode();
        String body = readResponse(connection, code);
        if (code < 200 || code >= 300) return "";

        String answer = extractOutputText(new JSONObject(body)).trim();
        if (answer.length() > 5000) answer = answer.substring(0, 5000);
        return answer;
    }

    private String extractOutputText(JSONObject root) {
        StringBuilder text = new StringBuilder();
        JSONArray output = root.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) {
                    text.append(part.optString("text")).append(' ');
                }
            }
        }
        return text.toString();
    }

    private void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        writeUtf8(out, "--" + boundary + "\r\n");
        writeUtf8(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeUtf8(out, value + "\r\n");
    }

    private void writeUtf8(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String readResponse(HttpURLConnection connection, int code) throws Exception {
        InputStream stream = code >= 200 && code < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int count;
            while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private byte[] readAllBytes(File file) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int count;
            while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
            return out.toByteArray();
        }
    }

    private String extractApiMessage(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) return error.optString("message", "");
        } catch (Exception ignored) {}
        return "";
    }

    private String friendlyApiError(int code, String message) {
        if (code == 401) return "מפתח ה‑API לא תקין. לחץ “מפתח API” והחלף אותו.";
        if (code == 403) return "למפתח הזה אין כרגע גישה למודל התמונות.";
        if (code == 429) return "הגעת למגבלת שימוש או שאין יתרת API זמינה. בדוק Billing ונסה שוב.";
        if (code >= 500) return "שירות התמונות לא זמין כרגע. נסה שוב.";
        if (message != null && !message.isEmpty()) return "OpenAI החזיר שגיאה: " + message;
        return "בקשת התמונה נכשלה (HTTP " + code + ").";
    }

    private void setBusy(boolean busy, String message) {
        running = busy;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(message == null ? "" : message);
        organizeButton.setEnabled(!busy && normalizedInput != null);
        organizeButton.setAlpha(organizeButton.isEnabled() ? 1f : 0.45f);
        if (retryButton != null) retryButton.setEnabled(!busy);
        if (saveButton != null) saveButton.setEnabled(!busy);
        if (feedbackButton != null) feedbackButton.setEnabled(!busy);
        if (alternateButton != null) alternateButton.setEnabled(!busy);
        if (tidyModeButton != null) tidyModeButton.setEnabled(!busy);
        if (redesignModeButton != null) redesignModeButton.setEnabled(!busy);
    }

    private void hideResult() {
        resultLabel.setVisibility(View.GONE);
        View resultCard = (View) resultImage.getTag();
        if (resultCard != null) resultCard.setVisibility(View.GONE);
        if (resultButtonsRow != null) resultButtonsRow.setVisibility(View.GONE);
        if (stepsLabel != null) stepsLabel.setVisibility(View.GONE);
        if (stepsCard != null) stepsCard.setVisibility(View.GONE);
        if (stepsText != null) stepsText.setText("");
        if (feedbackLabel != null) feedbackLabel.setVisibility(View.GONE);
        if (feedbackCard != null) feedbackCard.setVisibility(View.GONE);
        resultImage.setImageDrawable(null);
    }

    private void showResult(Bitmap bitmap) {
        resultLabel.setVisibility(View.VISIBLE);
        resultImage.setImageBitmap(bitmap);
        View resultCard = (View) resultImage.getTag();
        if (resultCard != null) resultCard.setVisibility(View.VISIBLE);
        if (resultButtonsRow != null) resultButtonsRow.setVisibility(View.VISIBLE);
        if (feedbackLabel != null) feedbackLabel.setVisibility(View.VISIBLE);
        if (feedbackCard != null) feedbackCard.setVisibility(View.VISIBLE);
        resultLabel.post(() -> resultLabel.getParent().requestChildFocus(resultLabel, resultLabel));
    }

    private void showSteps(String steps) {
        if (steps == null || steps.trim().isEmpty()) {
            stepsLabel.setVisibility(View.VISIBLE);
            stepsCard.setVisibility(View.VISIBLE);
            stepsText.setText("לא הצלחתי לייצר את רשימת הצעדים הפעם. אפשר ללחוץ “עוד סידור” ולנסות שוב.");
            return;
        }
        stepsText.setText(steps.trim());
        stepsLabel.setVisibility(View.VISIBLE);
        stepsCard.setVisibility(View.VISIBLE);
    }

    private View findTaggedView(android.view.ViewGroup parent, Object tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
            if (child instanceof android.view.ViewGroup) {
                View found = findTaggedView((android.view.ViewGroup) child, tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void saveResult() {
        if (resultBytes == null) return;
        try {
            String name = "room_organized_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/RoomOrganizer");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("No destination");

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("No output stream");
                out.write(resultBytes);
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            toast("נשמר בגלריה בתיקיית RoomOrganizer.");
        } catch (Exception e) {
            toast("לא הצלחתי לשמור את התמונה.");
        }
    }

    private void showApiKeyDialog(boolean required) {
        final String existing = loadApiKey();
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        input.setHint(existing.isEmpty() ? "sk-..." : "יש מפתח שמור — הדבק חדש כדי להחליף");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(14), dp(8), dp(14), dp(8));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(4), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
        TextView note = text("המפתח נשמר מוצפן במכשיר ואינו מוטמע בתוך ה‑APK.", 13, muted, false);
        note.setPadding(0, dp(8), 0, 0);
        wrap.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("חיבור ל‑OpenAI")
                .setMessage("נדרש מפתח API אישי כדי לערוך את התמונות.")
                .setView(wrap)
                .setPositiveButton("שמור", null)
                .setNegativeButton(existing.isEmpty() && required ? "אחר כך" : "ביטול", null)
                .setNeutralButton("פתח דף API", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    if (!existing.isEmpty()) dialog.dismiss();
                    else input.setError("הדבק מפתח API");
                    return;
                }
                if (!value.startsWith("sk-")) {
                    input.setError("זה לא נראה כמו מפתח OpenAI");
                    return;
                }
                try {
                    saveApiKey(value);
                    toast("המפתח נשמר במכשיר.");
                    dialog.dismiss();
                } catch (Exception e) {
                    input.setError("לא הצלחתי להצפין את המפתח");
                }
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://platform.openai.com/api-keys")));
                } catch (Exception ignored) {}
            });
        });
        dialog.show();
    }

    private void saveApiKey(String plain) throws Exception {
        SecretKey key = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_KEY, packed).apply();
    }

    private String loadApiKey() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String packed = prefs.getString(PREF_KEY, "");
            if (packed == null || packed.isEmpty()) return "";
            String[] pieces = packed.split(":", 2);
            if (pieces.length != 2) return "";

            byte[] iv = Base64.decode(pieces[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(pieces[1], Base64.NO_WRAP);
            SecretKey key = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_KEY).apply();
            return "";
        }
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static class Validation {
        final boolean valid;
        final boolean skipped;
        Validation(boolean valid, boolean skipped) {
            this.valid = valid;
            this.skipped = skipped;
        }
    }

    private static class ApiException extends Exception {
        final int code;
        ApiException(int code, String message) {
            super(message == null ? "" : message);
            this.code = code;
        }
    }
}
