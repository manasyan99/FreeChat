package com.freechat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity implements AdapterView.OnItemSelectedListener {
    private static final int ACCENT = 0xFF2481CC;
    private static final int BG = 0xFF0E1621;
    private static final int BOT_BG = 0xFF182533;
    private static final int DANGER = 0xFFF07070;
    private static final int DIM = 0xFF7D8B99;
    private static final int PANEL = 0xFF17212B;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int USER_BG = 0xFF2B5278;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int REQ_PHOTO = 101;
    private static final int REQ_DOC = 102;
    private static final int REQ_FILE = 103;
    private static final int MAX_FILE = 25 * 1024 * 1024; // 25 MB

    private static final String DEFAULT_SYSTEM = "Ты полезный ассистент. Отвечай на языке пользователя, кратко и по делу.";

    private LinearLayout attachRow;
    private HorizontalScrollView attachScroll;
    private EditText input;
    private LinearLayout messagesContainer;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private Spinner spinner;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<JSONObject> history = new ArrayList<>();
    private final List<Model> models = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<Attachment> inFlight = new ArrayList<>();

    private boolean sending = false;
    private boolean populating = false;

    public static class Attachment {
        final String name;
        final String mime;
        final byte[] data;
        final String kind; // "image", "text", "file"
        final String text;
        final Bitmap thumbnail;

        Attachment(String name, String mime, byte[] data, String kind, String text, Bitmap thumbnail) {
            this.name = name;
            this.mime = mime;
            this.data = data;
            this.kind = kind;
            this.text = text;
            this.thumbnail = thumbnail;
        }
    }

    public static class Model {
        final String id;
        final String name;
        final int ctx;
        final boolean hasVision;

        Model(String id, String name, int ctx, boolean hasVision) {
            this.id = id;
            this.name = name;
            this.ctx = ctx;
            this.hasVision = hasVision;
        }

        public String getDisplayName() {
            return (hasVision ? "📷 " : "") + name;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("freechat", 0);
        if (!prefs.contains("system")) {
            prefs.edit().putString("system", DEFAULT_SYSTEM).apply();
        }

        getWindow().setStatusBarColor(PANEL);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(16);

        buildUi();
        resetHistory();
        loadCachedModels();

        addBotBubble("Модель выбирается внизу (📷 — с поддержкой фото).\nНапишите сообщение и нажмите →.\n+ — прикрепить фото или файл. Настройки — ⋮");

        if (prefs.getString("api_key", "").isEmpty()) {
            showSettings(true);
        } else {
            refreshModels(false);
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(8), dp(8));
        header.setBackgroundColor(PANEL);

        TextView title = new TextView(this);
        title.setText("FreeChat");
        title.setTextColor(ACCENT);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        header.addView(makeBarButton("+", v -> newChat()));
        header.addView(makeBarButton("⋮", v -> showSettings(false)));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Chat scroll view
        scroll = new ScrollView(this);
        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
        scroll.addView(messagesContainer, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Bottom panel
        LinearLayout bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setBackgroundColor(PANEL);
        bottomPanel.setPadding(dp(10), dp(6), dp(10), dp(10));

        // Model selector row
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);

        spinner = new Spinner(this);
        modelRow.addView(spinner, new LinearLayout.LayoutParams(0, WRAP, 1f));
        modelRow.addView(makeBarButton("↻", v -> refreshModels(true)));
        bottomPanel.addView(modelRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Attachment preview horizontal scroll
        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachRow = new LinearLayout(this);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(0, dp(4), 0, dp(6));
        attachScroll.addView(attachRow, new ViewGroup.LayoutParams(WRAP, WRAP));
        attachScroll.setVisibility(View.GONE);
        bottomPanel.addView(attachScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Input row
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);

        Button attachBtn = new Button(this);
        attachBtn.setText("+");
        attachBtn.setTextSize(22f);
        attachBtn.setTextColor(TEXT);
        attachBtn.setTypeface(Typeface.DEFAULT_BOLD);
        attachBtn.setBackground(roundBg(BOT_BG, 18));
        attachBtn.setPadding(dp(12), 0, dp(12), 0);
        attachBtn.setMinWidth(0);
        attachBtn.setMinHeight(0);
        attachBtn.setMinimumWidth(0);
        attachBtn.setMinimumHeight(0);
        attachBtn.setOnClickListener(v -> showAttachMenu());

        LinearLayout.LayoutParams attachLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        attachLp.setMargins(0, 0, dp(8), 0);
        inputRow.addView(attachBtn, attachLp);

        input = new EditText(this);
        input.setHint("Сообщение…");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setTextSize(15f);
        input.setMaxLines(4);
        input.setMinLines(1);
        input.setInputType(147457);
        input.setBackground(roundBg(BOT_BG, 18));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        inputLp.setMargins(0, 0, dp(8), 0);
        inputRow.addView(input, inputLp);

        Button sendBtn = new Button(this);
        sendBtn.setText("→");
        sendBtn.setTextSize(20f);
        sendBtn.setTextColor(0xFF0E1621);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setBackground(roundBg(ACCENT, 18));
        sendBtn.setOnClickListener(v -> sendMessage());
        inputRow.addView(sendBtn, new LinearLayout.LayoutParams(WRAP, WRAP));

        bottomPanel.addView(inputRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(bottomPanel, new LinearLayout.LayoutParams(MATCH, WRAP));

        setContentView(root);
    }

    private TextView makeBarButton(String text, View.OnClickListener onClickListener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(22f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setOnClickListener(onClickListener);
        return tv;
    }

    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout addMessageBubble(String text, List<Attachment> list, boolean isUser) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(roundBg(isUser ? USER_BG : BOT_BG, 14));

        int maxContentWidth = getResources().getDisplayMetrics().widthPixels - dp(70);

        if (list != null && !list.isEmpty()) {
            for (Attachment att : list) {
                if ("image".equals(att.kind) && att.thumbnail != null) {
                    ImageView iv = new ImageView(this);
                    iv.setAdjustViewBounds(true);
                    iv.setMaxWidth(maxContentWidth);
                    iv.setMaxHeight(dp(260));
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setImageBitmap(att.thumbnail);
                    iv.setOutlineProvider(new RoundOutlineProvider(dp(8)));
                    iv.setClipToOutline(true);

                    LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(WRAP, WRAP);
                    imgLp.bottomMargin = (text != null && !text.isEmpty()) ? dp(6) : 0;
                    bubble.addView(iv, imgLp);
                } else if (!"image".equals(att.kind)) {
                    TextView fileTv = new TextView(this);
                    fileTv.setText("📄 " + att.name);
                    fileTv.setTextColor(DIM);
                    fileTv.setTextSize(13f);
                    fileTv.setPadding(0, dp(2), 0, (text != null && !text.isEmpty()) ? dp(4) : 0);
                    bubble.addView(fileTv);
                }
            }
        }

        if (text != null && !text.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextColor(TEXT);
            tv.setTextSize(15f);
            tv.setLineSpacing(dp(2), 1f);
            tv.setTextIsSelectable(true);
            tv.setMaxWidth(maxContentWidth);
            bubble.addView(tv);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = isUser ? Gravity.END : Gravity.START;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        messagesContainer.addView(bubble, lp);
        scrollDown();
        return bubble;
    }

    private TextView addBotBubble(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(15f);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(12), dp(9), dp(12), dp(9));
        tv.setBackground(roundBg(BOT_BG, 14));
        tv.setTextIsSelectable(true);
        tv.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(60));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = Gravity.START;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        messagesContainer.addView(tv, lp);
        scrollDown();
        return tv;
    }

    private void updateBubbleText(TextView tv, String text) {
        tv.setText(text);
        scrollDown();
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void sendMessage() {
        String trim = input.getText().toString().trim();
        if (trim.isEmpty() && attachments.isEmpty()) {
            return;
        }
        if (sending) {
            toast("Дождитесь ответа");
            return;
        }
        if (prefs.getString("api_key", "").isEmpty()) {
            showSettings(true);
            return;
        }

        Model selected = selectedModel();
        if (selected == null) {
            toast("Нет модели. Нажмите ↻");
            return;
        }

        boolean hasImage = false;
        for (Attachment a : attachments) {
            if ("image".equals(a.kind)) {
                hasImage = true;
                break;
            }
        }
        if (hasImage && !selected.hasVision) {
            toast("Выбрана модель без 📷. Если будет ошибка, переключите на модель с 📷");
        }

        List<Attachment> sentAttachments = new ArrayList<>(attachments);
        attachments.clear();
        renderAttachments();
        input.setText("");

        addMessageBubble(trim, sentAttachments, true);

        JSONObject userMessage = new JSONObject();
        try {
            userMessage.put("role", "user");
            userMessage.put("content", buildUserContent(trim, sentAttachments));
        } catch (JSONException ignored) {
        }
        history.add(userMessage);

        inFlight.clear();
        inFlight.addAll(sentAttachments);

        TextView botResponseView = addBotBubble("…");
        sending = true;

        pool.execute(() -> streamChat(selected.id, botResponseView));
    }

    private Object buildUserContent(String prompt, List<Attachment> list) throws JSONException {
        if (list.isEmpty()) {
            return prompt;
        }
        JSONArray array = new JSONArray();

        JSONObject textObj = new JSONObject();
        textObj.put("type", "text");
        textObj.put("text", prompt.isEmpty() ? "Опиши и проанализируй прикреплённое фото/файл." : prompt);
        array.put(textObj);

        for (Attachment att : list) {
            if ("image".equals(att.kind)) {
                JSONObject imgObj = new JSONObject();
                imgObj.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:" + att.mime + ";base64," + Base64.encodeToString(att.data, Base64.NO_WRAP));
                imgObj.put("image_url", urlObj);
                array.put(imgObj);
            } else if ("text".equals(att.kind)) {
                JSONObject docObj = new JSONObject();
                docObj.put("type", "text");
                docObj.put("text", "Файл \"" + att.name + "\":\n" + att.text);
                array.put(docObj);
            } else {
                JSONObject docObj = new JSONObject();
                docObj.put("type", "text");
                docObj.put("text", "[Файл: " + att.name + " (" + att.mime + "), размер " + (att.data.length / 1024) + " КБ]");
                array.put(docObj);
            }
        }
        return array;
    }

    private void showAttachMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Прикрепить")
                .setItems(new String[]{"Фото (Галерея)", "Документ", "Любой файл"}, (dialog, which) -> pickAttachment(which))
                .show();
    }

    private void pickAttachment(int which) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (which == 0) {
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp"});
            startActivityForResult(intent, REQ_PHOTO);
        } else if (which == 1) {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/pdf", "text/plain", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            });
            startActivityForResult(intent, REQ_DOC);
        } else {
            intent.setType("*/*");
            startActivityForResult(intent, REQ_FILE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) {
            return;
        }

        toast(uris.size() > 1 ? ("Читаю " + uris.size() + " файла…") : "Читаю файл…");

        pool.execute(() -> {
            for (Uri uri : uris) {
                try {
                    Attachment att = readAttachment(uri, requestCode == REQ_PHOTO);
                    main.post(() -> {
                        attachments.add(att);
                        renderAttachments();
                    });
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    main.post(() -> toast("Ошибка: " + msg));
                }
            }
        });
    }

    private Attachment readAttachment(Uri uri, boolean forceImage) throws IOException {
        String type = getContentResolver().getType(uri);
        String name = queryName(uri);
        String mime = (type != null && !type.isEmpty()) ? type : guessMime(name);

        boolean isImage = forceImage || mime.startsWith("image/") || isImageFile(name);

        if (isImage) {
            byte[] rawBytes = readRawStream(uri);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, opts);

            int w = opts.outWidth;
            int h = opts.outHeight;
            if (w > 0 && h > 0) {
                int maxDim = Math.max(w, h);
                int sample = 1;
                while (maxDim / (sample * 2) >= 1600) {
                    sample *= 2;
                }
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                Bitmap decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, opts);
                if (decoded != null) {
                    Bitmap scaled = scaleBitmap(decoded, 1600);
                    if (scaled != decoded) {
                        decoded.recycle();
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                    byte[] jpegBytes = baos.toByteArray();

                    Bitmap thumb = scaleBitmap(scaled, 240);
                    return new Attachment(name, "image/jpeg", jpegBytes, "image", null, thumb);
                }
            }
            return new Attachment(name, "image/jpeg", rawBytes, "image", null, null);
        }

        if (mime.startsWith("text/")) {
            byte[] bytes = readRawStream(uri);
            return new Attachment(name, mime, bytes, "text", new String(bytes, StandardCharsets.UTF_8), null);
        }

        byte[] bytes = readRawStream(uri);
        return new Attachment(name, mime, bytes, "file", null, null);
    }

    private Bitmap scaleBitmap(Bitmap src, int maxDimension) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxDimension && h <= maxDimension) {
            return src;
        }
        float ratio = (float) w / (float) h;
        int targetW, targetH;
        if (w > h) {
            targetW = maxDimension;
            targetH = Math.max(1, Math.round(maxDimension / ratio));
        } else {
            targetH = maxDimension;
            targetW = Math.max(1, Math.round(maxDimension * ratio));
        }
        return Bitmap.createScaledBitmap(src, targetW, targetH, true);
    }

    private String guessMime(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".csv") || lower.endsWith(".py") || lower.endsWith(".js")) return "text/plain";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private boolean isImageFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private byte[] readRawStream(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) {
            throw new IOException("Нет доступа к файлу");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int len;
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (total > MAX_FILE) {
                    throw new IOException("Файл больше 25 МБ");
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private String queryName(Uri uri) {
        String name = null;
        try {
            Cursor cursor = getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex("_display_name");
                        if (idx >= 0) {
                            name = cursor.getString(idx);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            return "файл";
        }
        return name;
    }

    private void renderAttachments() {
        attachRow.removeAllViews();
        if (attachments.isEmpty()) {
            attachScroll.setVisibility(View.GONE);
            return;
        }
        attachScroll.setVisibility(View.VISIBLE);

        for (int i = 0; i < attachments.size(); i++) {
            final int idx = i;
            Attachment att = attachments.get(i);

            if ("image".equals(att.kind) && att.thumbnail != null) {
                // Card with image thumbnail and close button
                FrameLayout card = new FrameLayout(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(54));
                lp.setMargins(0, 0, dp(8), 0);
                card.setLayoutParams(lp);

                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(att.thumbnail);
                iv.setBackground(roundBg(BOT_BG, 8));
                iv.setOutlineProvider(new RoundOutlineProvider(dp(8)));
                iv.setClipToOutline(true);
                card.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));

                TextView del = new TextView(this);
                del.setText("✕");
                del.setTextColor(TEXT);
                del.setTextSize(11f);
                del.setGravity(Gravity.CENTER);
                del.setBackground(roundBg(0xBB000000, 10));
                FrameLayout.LayoutParams delLp = new FrameLayout.LayoutParams(dp(20), dp(20));
                delLp.gravity = Gravity.TOP | Gravity.END;
                delLp.setMargins(0, dp(2), dp(2), 0);
                card.addView(del, delLp);

                card.setOnClickListener(v -> {
                    attachments.remove(idx);
                    renderAttachments();
                });
                attachRow.addView(card);
            } else {
                TextView tv = new TextView(this);
                tv.setText("📄 " + att.name + "  ✕");
                tv.setTextColor(TEXT);
                tv.setTextSize(12f);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tv.setMaxWidth(dp(200));
                tv.setPadding(dp(10), dp(6), dp(10), dp(6));
                tv.setBackground(roundBg(BOT_BG, 12));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.setMargins(0, 0, dp(6), 0);
                tv.setOnClickListener(v -> {
                    attachments.remove(idx);
                    renderAttachments();
                });
                attachRow.addView(tv, lp);
            }
        }
    }

    private void restoreInFlight() {
        if (!inFlight.isEmpty()) {
            attachments.addAll(0, inFlight);
            inFlight.clear();
            renderAttachments();
        }
    }

    private Model selectedModel() {
        int pos = spinner.getSelectedItemPosition();
        if (pos < 0 || pos >= models.size()) {
            return null;
        }
        return models.get(pos);
    }

    private void streamChat(String modelId, TextView textView) {
        StringBuilder responseAccumulator = new StringBuilder();
        HttpURLConnection conn = null;
        String errorMsg = null;

        try {
            JSONObject body = new JSONObject();
            body.put("model", modelId);
            body.put("messages", buildMessages());
            body.put("stream", true);
            body.put("temperature", getTemperature());

            byte[] jsonBytes = body.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + prefs.getString("api_key", ""));
            conn.setRequestProperty("User-Agent", "FreeChat/1.1");
            conn.setRequestProperty("X-Title", "FreeChat");
            conn.setFixedLengthStreamingMode(jsonBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String errResponse = readAll(conn.getErrorStream());
                throw new IOException(httpMessage(code, errResponse));
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String payload = line.substring(5).trim();
                        if ("[DONE]".equals(payload)) {
                            break;
                        }
                        try {
                            JSONObject chunk = new JSONObject(payload);
                            if (chunk.has("error")) {
                                throw new IOException(errorText(chunk.opt("error")));
                            }
                            JSONArray choices = chunk.optJSONArray("choices");
                            if (choices != null && choices.length() > 0) {
                                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                if (delta != null) {
                                    String text = delta.optString("content", "");
                                    if (!text.isEmpty()) {
                                        responseAccumulator.append(text);
                                        final String currentText = responseAccumulator.toString();
                                        main.post(() -> updateBubbleText(textView, currentText));
                                    }
                                }
                            }
                        } catch (JSONException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        final String finalError = errorMsg;
        final String finalResult = responseAccumulator.toString();
        main.post(() -> onChatFinished(finalError, textView, finalResult));
    }

    private void onChatFinished(String error, TextView textView, String text) {
        sending = false;
        if (error != null) {
            removeLastUser();
            restoreInFlight();
            textView.setTextColor(DANGER);
            updateBubbleText(textView, "Ошибка: " + error);
            return;
        }

        if (text.isEmpty()) {
            removeLastUser();
            restoreInFlight();
            textView.setTextColor(DIM);
            updateBubbleText(textView, "(пустой ответ — попробуйте другую модель)");
            return;
        }

        inFlight.clear();
        JSONObject assistantMessage = new JSONObject();
        try {
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", text);
        } catch (JSONException ignored) {
        }
        history.add(assistantMessage);
    }

    private JSONArray buildMessages() throws JSONException {
        JSONArray arr = new JSONArray();
        int max = Math.max(0, history.size() - 40);
        if (!history.isEmpty() && "system".equals(history.get(0).optString("role"))) {
            arr.put(history.get(0));
            max = Math.max(1, max);
        }
        while (max < history.size()) {
            arr.put(history.get(max));
            max++;
        }
        return arr;
    }

    private void removeLastUser() {
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).optString("role"))) {
            history.remove(history.size() - 1);
        }
    }

    private void refreshModels(boolean showToast) {
        String key = prefs.getString("api_key", "");
        if (key.isEmpty()) {
            showSettings(true);
            return;
        }
        if (showToast) {
            toast("Обновляю список моделей…");
        }

        pool.execute(() -> {
            try {
                List<Model> list = fetchModels(key);
                main.post(() -> {
                    models.clear();
                    models.addAll(list);
                    populateSpinner();
                    saveModelsCache();
                    toast("Доступно моделей: " + list.size());
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> toast("Ошибка обновления моделей: " + msg));
            }
        });
    }

    private List<Model> fetchModels(String apiKey) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/models").openConnection();
        try {
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("User-Agent", "FreeChat/1.1");
            conn.setRequestProperty("X-Title", "FreeChat");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException(httpMessage(code, readAll(conn.getErrorStream())));
            }

            JSONArray data = new JSONObject(readAll(conn.getInputStream())).optJSONArray("data");
            List<Model> list = new ArrayList<>();
            if (data == null) {
                return list;
            }

            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.getJSONObject(i);
                JSONObject pricing = m.optJSONObject("pricing");

                boolean isFree = pricing != null
                        && isZero(pricing.optString("prompt", "1"))
                        && isZero(pricing.optString("completion", "1"));

                if (!isFree) {
                    continue;
                }

                String id = m.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }

                // Check vision capability from architecture
                boolean vision = false;
                JSONObject arch = m.optJSONObject("architecture");
                if (arch != null) {
                    String mod = arch.optString("modality", "");
                    if (mod.contains("image")) {
                        vision = true;
                    }
                    JSONArray inputs = arch.optJSONArray("input_modalities");
                    if (inputs != null) {
                        for (int k = 0; k < inputs.length(); k++) {
                            if ("image".equals(inputs.optString(k))) {
                                vision = true;
                                break;
                            }
                        }
                    }
                }

                String name = m.optString("name", id);
                int ctx = m.optInt("context_length", 0);
                list.add(new Model(id, name, ctx, vision));
            }

            Collections.sort(list, (a, b) -> {
                // Models with vision first, then free suffix, then alphabetical
                if (a.hasVision != b.hasVision) {
                    return a.hasVision ? -1 : 1;
                }
                boolean aFree = a.id.endsWith(":free");
                boolean bFree = b.id.endsWith(":free");
                if (aFree != bFree) {
                    return aFree ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            });

            return list;
        } finally {
            conn.disconnect();
        }
    }

    private boolean isZero(String str) {
        try {
            return Double.parseDouble(str) == 0.0d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String readAll(InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }

    private String errorText(Object obj) {
        if (obj instanceof JSONObject) {
            JSONObject j = (JSONObject) obj;
            String msg = j.optString("message", "");
            if (!msg.isEmpty()) return msg;
            String code = j.optString("code", "");
            if (!code.isEmpty()) return code;
            return j.toString();
        }
        return String.valueOf(obj);
    }

    private String httpMessage(int code, String body) {
        String msg = "";
        try {
            msg = errorText(new JSONObject(body).opt("error"));
        } catch (Exception ignored) {
        }
        if (msg.isEmpty()) {
            msg = body.isEmpty() ? "ошибка сети" : body;
        }

        switch (code) {
            case 400:
                return "HTTP 400: " + msg + " (возможно, модель не поддерживает фото)";
            case 401:
                return "HTTP 401: " + msg + " — проверьте API-ключ в настройках (⋮)";
            case 402:
                return "HTTP 402: " + msg + " — выберите бесплатную модель";
            case 403:
                return "HTTP 403: " + msg + " — доступ запрещён";
            case 404:
                return "HTTP 404: " + msg + " — обновите список моделей (↻)";
            case 413:
                return "HTTP 413: Слишком большой размер вложения";
            case 429:
                return "HTTP 429: " + msg + " — лимит запросов, подождите или смените модель";
            case 502:
            case 503:
                return "HTTP " + code + ": " + msg + " — провайдер модели недоступен, смените модель";
            default:
                return "HTTP " + code + ": " + msg;
        }
    }

    private double getTemperature() {
        try {
            return Double.parseDouble(prefs.getString("temperature", "0.7"));
        } catch (NumberFormatException e) {
            return 0.7;
        }
    }

    private void resetHistory() {
        history.clear();
        String prompt = prefs.getString("system", "").trim();
        if (!prompt.isEmpty()) {
            JSONObject sys = new JSONObject();
            try {
                sys.put("role", "system");
                sys.put("content", prompt);
            } catch (JSONException ignored) {
            }
            history.add(sys);
        }
    }

    private void newChat() {
        resetHistory();
        attachments.clear();
        inFlight.clear();
        renderAttachments();
        messagesContainer.removeAllViews();
        addBotBubble("Новый чат. Модель выбирается внизу (📷 — с фото), + — прикрепить файл.");
        toast("Чат очищен");
    }

    private void populateSpinner() {
        populating = true;
        List<String> names = new ArrayList<>();
        for (Model m : models) {
            names.add(m.getDisplayName());
        }
        if (names.isEmpty()) {
            names.add("Нет моделей — нажмите ↻");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String savedId = prefs.getString("model", "");
        int selectedIndex = 0;
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(savedId)) {
                selectedIndex = i;
                break;
            }
        }
        if (!models.isEmpty()) {
            spinner.setSelection(selectedIndex, false);
        }
        populating = false;

        spinner.setOnItemSelectedListener(this);
    }

    private void loadCachedModels() {
        String cache = prefs.getString("models_cache", "");
        if (cache.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(cache);
            models.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                models.add(new Model(
                        obj.optString("id"),
                        obj.optString("name"),
                        obj.optInt("ctx"),
                        obj.optBoolean("vision", false)
                ));
            }
            populateSpinner();
        } catch (JSONException ignored) {
        }
    }

    private void saveModelsCache() {
        JSONArray arr = new JSONArray();
        for (Model m : models) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", m.id);
                obj.put("name", m.name);
                obj.put("ctx", m.ctx);
                obj.put("vision", m.hasVision);
            } catch (JSONException ignored) {
            }
            arr.put(obj);
        }
        prefs.edit().putString("models_cache", arr.toString()).apply();
    }

    private void showSettings(boolean firstRun) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(4), dp(20), 0);

        EditText keyInput = new EditText(this);
        keyInput.setHint("sk-or-v1-...");
        keyInput.setText(prefs.getString("api_key", ""));
        keyInput.setSingleLine(true);
        keyInput.setInputType(129); // textPassword
        keyInput.setTextColor(TEXT);
        keyInput.setHintTextColor(DIM);
        layout.addView(label("API-ключ OpenRouter:"));
        layout.addView(keyInput);

        EditText promptInput = new EditText(this);
        promptInput.setText(prefs.getString("system", ""));
        promptInput.setMinLines(2);
        promptInput.setMaxLines(5);
        promptInput.setInputType(131073); // textMultiLine
        promptInput.setTextColor(TEXT);
        promptInput.setHintTextColor(DIM);
        layout.addView(label("Системный промпт:"));
        layout.addView(promptInput);

        EditText tempInput = new EditText(this);
        tempInput.setText(prefs.getString("temperature", "0.7"));
        tempInput.setSingleLine(true);
        tempInput.setInputType(8194); // numberDecimal
        tempInput.setTextColor(TEXT);
        layout.addView(label("Температура (0.0 – 2.0):"));
        layout.addView(tempInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Добро пожаловать в FreeChat" : "Настройки")
                .setView(layout)
                .setPositiveButton("Сохранить", null)
                .setNeutralButton("Получить ключ", (d, w) -> openUrl("https://openrouter.ai/keys"));

        if (firstRun) {
            builder.setNegativeButton("Позже", (d, w) -> finish());
        } else {
            builder.setNegativeButton("Отмена", null);
        }

        AlertDialog dialog = builder.create();
        dialog.setCancelable(!firstRun);
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = keyInput.getText().toString().trim();
                if (key.isEmpty()) {
                    toast("Введите API-ключ");
                    return;
                }
                String oldKey = prefs.getString("api_key", "");
                String prompt = promptInput.getText().toString().trim();
                double temp;
                try {
                    temp = Double.parseDouble(tempInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    temp = 0.7;
                }
                temp = Math.max(0.0, Math.min(2.0, temp));

                prefs.edit()
                        .putString("api_key", key)
                        .putString("system", prompt)
                        .putString("temperature", String.valueOf(temp))
                        .apply();

                resetHistory();
                dialog.dismiss();
                toast("Настройки сохранены");

                if (firstRun || !key.equals(oldKey) || models.isEmpty()) {
                    refreshModels(!key.equals(oldKey));
                }
            });
        });
        dialog.show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(DIM);
        tv.setTextSize(13f);
        tv.setPadding(0, dp(12), 0, dp(2));
        return tv;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Не удалось открыть ссылку");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }

    public static class RoundOutlineProvider extends ViewOutlineProvider {
        private final int radius;

        public RoundOutlineProvider(int radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (populating || position < 0 || position >= models.size()) {
            return;
        }
        prefs.edit().putString("model", models.get(position).id).apply();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }
}
