package com.freechat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    // Theme Colors (Modern Telegram Dark palette)
    private static final int BG_COLOR = 0xFF0E1621;
    private static final int PANEL_COLOR = 0xFF17212B;
    private static final int PANEL_HOVER = 0xFF202B36;
    private static final int BOT_BG = 0xFF182533;
    private static final int USER_BG = 0xFF2B5278;
    private static final int ACCENT_COLOR = 0xFF2481CC;
    private static final int ACCENT_LIGHT = 0xFF52A5E0;
    private static final int TEXT_PRIMARY = 0xFFE6E6E6;
    private static final int TEXT_DIM = 0xFF7D8B99;
    private static final int DANGER_COLOR = 0xFFF07070;
    private static final int SUCCESS_COLOR = 0xFF4CAF50;
    private static final int CODE_BG = 0xFF10161D;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int REQ_PHOTO = 101;
    private static final int REQ_DOC = 102;
    private static final int REQ_FILE = 103;
    private static final int MAX_FILE_SIZE = 25 * 1024 * 1024; // 25 MB

    private static final String DEFAULT_SYSTEM = "Ты полезный ассистент. Отвечай на языке пользователя, подробно и структурированно.";

    // Views
    private TextView activeModelBadge;
    private TextView activeModelSub;
    private LinearLayout attachRow;
    private HorizontalScrollView attachScroll;
    private EditText input;
    private LinearLayout messagesContainer;
    private ScrollView scroll;
    private TextView statusSubtitle;

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private final List<JSONObject> history = new ArrayList<>();
    private final List<Model> allModels = new ArrayList<>();
    private final List<Attachment> currentAttachments = new ArrayList<>();
    private final List<Attachment> inFlightAttachments = new ArrayList<>();

    private boolean isSending = false;
    // Gemini-style UI elements
    private TextView modelPillText;
    private TextView langBtn;
    private LinearLayout greetingContainer;
    private TextView greetingTitle;
    private TextView greetingSub;
    private TextView chipCode;
    private TextView chipExplain;
    private TextView chipTranslate;
    private TextView chipSummarize;
    private Model currentSelectedModel = null;

    // --- Data Classes ---

    public static class Attachment {
        final String name;
        final String mime;
        final byte[] data;
        final String kind; // "image", "text", "file"
        final String text;
        final Bitmap thumbnail;
        final Bitmap fullBitmap;

        Attachment(String name, String mime, byte[] data, String kind, String text, Bitmap thumbnail, Bitmap fullBitmap) {
            this.name = name;
            this.mime = mime;
            this.data = data;
            this.kind = kind;
            this.text = text;
            this.thumbnail = thumbnail;
            this.fullBitmap = fullBitmap;
        }
    }

    public static class Model {
        final String id;
        final String name;
        final String provider;
        final int ctx;
        final boolean hasVision;
        final boolean isFree;

        Model(String id, String name, String provider, int ctx, boolean hasVision, boolean isFree) {
            this.id = id;
            this.name = name;
            this.provider = provider;
            this.ctx = ctx;
            this.hasVision = hasVision;
            this.isFree = isFree;
        }

        public String getShortContext() {
            if (ctx >= 1000000) {
                return (ctx / 1000000) + "M ctx";
            } else if (ctx >= 1000) {
                return (ctx / 1000) + "K ctx";
            } else if (ctx > 0) {
                return ctx + " ctx";
            }
            return "";
        }
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

    public static class SimpleTextWatcher implements TextWatcher {
        private final Runnable callback;

        public SimpleTextWatcher(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (callback != null) callback.run();
        }

        @Override
        public void afterTextChanged(Editable s) {}
    }

    public static class BotBubbleHolder {
        final LinearLayout bubbleView;
        final LinearLayout bodyContainer;
        final TextView streamingTv;
        final LinearLayout footer;

        BotBubbleHolder(LinearLayout bubbleView, LinearLayout bodyContainer, TextView streamingTv, LinearLayout footer) {
            this.bubbleView = bubbleView;
            this.bodyContainer = bodyContainer;
            this.streamingTv = streamingTv;
            this.footer = footer;
        }
    }

    // --- Lifecycle ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("freechat", MODE_PRIVATE);
        if (!prefs.contains("system")) {
            prefs.edit().putString("system", DEFAULT_SYSTEM).apply();
        }

        // Auto-detect and initialize interface language
        String savedLang = prefs.getString("language", null);
        if (savedLang == null) {
            String sysLang = Locale.getDefault().getLanguage().toLowerCase();
            savedLang = "en";
            for (String code : I18n.CODES) {
                if (code.equals(sysLang)) {
                    savedLang = code;
                    break;
                }
            }
            prefs.edit().putString("language", savedLang).apply();
        }
        I18n.currentLang = savedLang;

        getWindow().setStatusBarColor(PANEL_COLOR);
        getWindow().setNavigationBarColor(BG_COLOR);
        getWindow().setSoftInputMode(16);

        buildUi();
        resetHistory();
        initDefaultModels();
        loadCachedModels();

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

    // --- UI Building ---

    private void buildUi() {
        statusSubtitle = new TextView(this);
        activeModelBadge = new TextView(this);
        activeModelSub = new TextView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);

        // --- Gemini Header ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        header.setBackgroundColor(PANEL_COLOR);

        // 1. Gemini Hamburger Menu button (Left)
        header.addView(makeIconButton(AppIcons.createMenuIcon(dp(20), TEXT_PRIMARY), "Menu", v -> showGeminiMenu()));

        // 2. Gemini Model Selector Pill (Dropdown in header)
        LinearLayout modelPill = new LinearLayout(this);
        modelPill.setOrientation(LinearLayout.HORIZONTAL);
        modelPill.setGravity(Gravity.CENTER_VERTICAL);
        modelPill.setBackground(roundBg(0x22FFFFFF, 16));
        modelPill.setPadding(dp(10), dp(6), dp(10), dp(6));
        modelPill.setOnClickListener(v -> showModelPickerDialog());

        ImageView sparkIcon = new ImageView(this);
        sparkIcon.setImageDrawable(AppIcons.createSparkleIcon(dp(16), 0xFFA5B4FC));
        modelPill.addView(sparkIcon);

        modelPillText = new TextView(this);
        modelPillText.setText(currentSelectedModel != null ? currentSelectedModel.name : "FreeChat AI");
        modelPillText.setTextColor(TEXT_PRIMARY);
        modelPillText.setTextSize(13f);
        modelPillText.setTypeface(Typeface.DEFAULT_BOLD);
        modelPillText.setSingleLine(true);
        modelPillText.setEllipsize(TextUtils.TruncateAt.END);
        modelPillText.setMaxWidth(dp(140));
        modelPillText.setPadding(dp(6), 0, dp(4), 0);
        modelPill.addView(modelPillText);

        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(AppIcons.createChevronDown(dp(10), TEXT_DIM));
        modelPill.addView(chevron);

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        pillLp.leftMargin = dp(6);
        header.addView(modelPill, pillLp);

        // Spacer to push right buttons
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        // 3. Language Selector Pill [ 🌐 RU ]
        langBtn = new TextView(this);
        langBtn.setText(I18n.getFlag(I18n.currentLang) + " " + I18n.currentLang.toUpperCase());
        langBtn.setTextColor(TEXT_PRIMARY);
        langBtn.setTextSize(11f);
        langBtn.setTypeface(Typeface.DEFAULT_BOLD);
        langBtn.setBackground(roundBg(0x22FFFFFF, 14));
        langBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        langBtn.setOnClickListener(v -> showLanguageDialog());
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        langLp.rightMargin = dp(4);
        header.addView(langBtn, langLp);

        // 4. New Chat Button (+)
        header.addView(makeIconButton(AppIcons.createNewChatIcon(dp(18), TEXT_PRIMARY), I18n.get("NEW_CHAT"), v -> newChat()));

        // 5. Settings Button (gear/sliders)
        header.addView(makeIconButton(AppIcons.createSettingsIcon(dp(18), TEXT_PRIMARY), I18n.get("MENU_SETTINGS"), v -> showSettings(false)));

        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // --- Chat Scroll View ---
        scroll = new ScrollView(this);
        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(dp(10), dp(8), dp(10), dp(8));

        // Build Gemini Empty Greeting
        buildGreetingView();
        messagesContainer.addView(greetingContainer);

        scroll.addView(messagesContainer, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // --- Floating Bottom Input Bar (Gemini Style) ---
        LinearLayout bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(10), dp(4), dp(10), dp(8));

        // Attachment Preview Row
        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachRow = new LinearLayout(this);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(0, dp(4), 0, dp(4));
        attachScroll.addView(attachRow, new ViewGroup.LayoutParams(WRAP, WRAP));
        attachScroll.setVisibility(View.GONE);
        bottomPanel.addView(attachScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Floating Pill Container
        LinearLayout inputPill = new LinearLayout(this);
        inputPill.setOrientation(LinearLayout.HORIZONTAL);
        inputPill.setGravity(Gravity.CENTER_VERTICAL);
        inputPill.setBackground(roundBg(PANEL_COLOR, 24));
        inputPill.setPadding(dp(6), dp(4), dp(6), dp(4));

        // Attachment Button
        ImageView attachBtn = new ImageView(this);
        attachBtn.setImageDrawable(AppIcons.createAttachIcon(dp(20), TEXT_PRIMARY));
        attachBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        attachBtn.setBackground(roundBg(0x22FFFFFF, 20));
        attachBtn.setOnClickListener(v -> showAttachMenu());
        LinearLayout.LayoutParams attachLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        inputPill.addView(attachBtn, attachLp);

        // Text Input
        input = new EditText(this);
        input.setHint(I18n.get("INPUT_HINT"));
        input.setHintTextColor(TEXT_DIM);
        input.setTextColor(TEXT_PRIMARY);
        input.setTextSize(15f);
        input.setMaxLines(5);
        input.setMinLines(1);
        input.setInputType(147457);
        input.setBackground(null);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        inputPill.addView(input, inputLp);

        // Send Button
        ImageView sendBtn = new ImageView(this);
        sendBtn.setImageDrawable(AppIcons.createSendIcon(dp(18), Color.WHITE));
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setPadding(dp(2), 0, 0, 0);
        sendBtn.setBackground(roundBg(ACCENT_COLOR, 20));
        sendBtn.setOnClickListener(v -> sendMessage());

        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        inputPill.addView(sendBtn, sendLp);

        bottomPanel.addView(inputPill, new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(bottomPanel, new LinearLayout.LayoutParams(MATCH, WRAP));

        setContentView(root);
    }

    private void buildGreetingView() {
        greetingContainer = new LinearLayout(this);
        greetingContainer.setOrientation(LinearLayout.VERTICAL);
        greetingContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        greetingContainer.setPadding(dp(16), dp(32), dp(16), dp(20));

        // Gemini Sparkle Logo
        ImageView spark = new ImageView(this);
        spark.setImageDrawable(AppIcons.createSparkleIcon(dp(44), 0xFFA5B4FC));
        greetingContainer.addView(spark);

        greetingTitle = new TextView(this);
        greetingTitle.setText(I18n.get("GREETING_TITLE"));
        greetingTitle.setTextColor(TEXT_PRIMARY);
        greetingTitle.setTextSize(22f);
        greetingTitle.setTypeface(Typeface.DEFAULT_BOLD);
        greetingTitle.setGravity(Gravity.CENTER);
        greetingTitle.setPadding(0, dp(14), 0, dp(6));
        greetingContainer.addView(greetingTitle);

        greetingSub = new TextView(this);
        greetingSub.setText(I18n.get("GREETING_SUB"));
        greetingSub.setTextColor(TEXT_DIM);
        greetingSub.setTextSize(13f);
        greetingSub.setGravity(Gravity.CENTER);
        greetingSub.setPadding(0, 0, 0, dp(24));
        greetingContainer.addView(greetingSub);

        // Starter Chips Column
        LinearLayout chipsCol = new LinearLayout(this);
        chipsCol.setOrientation(LinearLayout.VERTICAL);

        chipCode = createStarterChip(I18n.get("CHIP_CODE"));
        chipExplain = createStarterChip(I18n.get("CHIP_EXPLAIN"));
        chipTranslate = createStarterChip(I18n.get("CHIP_TRANSLATE"));
        chipSummarize = createStarterChip(I18n.get("CHIP_SUMMARIZE"));

        chipsCol.addView(chipCode);
        chipsCol.addView(chipExplain);
        chipsCol.addView(chipTranslate);
        chipsCol.addView(chipSummarize);

        greetingContainer.addView(chipsCol, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private TextView createStarterChip(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(TEXT_PRIMARY);
        chip.setTextSize(13.5f);
        chip.setBackground(roundBg(PANEL_COLOR, 14));
        chip.setPadding(dp(14), dp(11), dp(14), dp(11));
        chip.setOnClickListener(v -> {
            input.setText(chip.getText());
            input.setSelection(chip.getText().length());
            input.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void updateLocalizedUi() {
        if (langBtn != null) {
            langBtn.setText(I18n.getFlag(I18n.currentLang) + " " + I18n.currentLang.toUpperCase());
        }
        if (input != null) {
            input.setHint(I18n.get("INPUT_HINT"));
        }
        if (greetingTitle != null) {
            greetingTitle.setText(I18n.get("GREETING_TITLE"));
        }
        if (greetingSub != null) {
            greetingSub.setText(I18n.get("GREETING_SUB"));
        }
        if (chipCode != null) chipCode.setText(I18n.get("CHIP_CODE"));
        if (chipExplain != null) chipExplain.setText(I18n.get("CHIP_EXPLAIN"));
        if (chipTranslate != null) chipTranslate.setText(I18n.get("CHIP_TRANSLATE"));
        if (chipSummarize != null) chipSummarize.setText(I18n.get("CHIP_SUMMARIZE"));
    }

    private void showLanguageDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(I18n.get("MENU_LANG"));

        String[] displayItems = new String[I18n.CODES.length];
        for (int i = 0; i < I18n.CODES.length; i++) {
            boolean isCur = I18n.CODES[i].equals(I18n.currentLang);
            displayItems[i] = I18n.FLAGS[i] + "  " + I18n.NAMES[i] + (isCur ? "  ✓" : "");
        }

        b.setItems(displayItems, (dialog, which) -> {
            String selected = I18n.CODES[which];
            I18n.currentLang = selected;
            prefs.edit().putString("language", selected).apply();
            updateLocalizedUi();
            toast(I18n.get("LANG_CHANGED") + ": " + I18n.NAMES[which]);
        });
        b.setNegativeButton(I18n.get("CANCEL"), null);
        b.show();
    }

    private void showGeminiMenu() {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        // Header with Close
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView spark = new ImageView(this);
        spark.setImageDrawable(AppIcons.createSparkleIcon(dp(24), 0xFFA5B4FC));
        topRow.addView(spark);

        TextView title = new TextView(this);
        title.setText("  FreeChat");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageView closeBtn = makeIconButton(AppIcons.createCloseIcon(dp(18), TEXT_PRIMARY), I18n.get("CLOSE"), v -> d.dismiss());
        topRow.addView(closeBtn);

        root.addView(topRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Active Model Card
        LinearLayout activeCard = new LinearLayout(this);
        activeCard.setOrientation(LinearLayout.VERTICAL);
        activeCard.setBackground(roundBg(PANEL_COLOR, 14));
        activeCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        acLp.topMargin = dp(16);
        acLp.bottomMargin = dp(16);
        activeCard.setLayoutParams(acLp);

        TextView mLabel = new TextView(this);
        mLabel.setText(I18n.get("ACTIVE_MODEL").toUpperCase());
        mLabel.setTextColor(TEXT_DIM);
        mLabel.setTextSize(11f);
        mLabel.setTypeface(Typeface.DEFAULT_BOLD);
        activeCard.addView(mLabel);

        TextView mName = new TextView(this);
        mName.setText(currentSelectedModel != null ? currentSelectedModel.name : "FreeChat AI");
        mName.setTextColor(TEXT_PRIMARY);
        mName.setTextSize(16f);
        mName.setTypeface(Typeface.DEFAULT_BOLD);
        mName.setPadding(0, dp(2), 0, dp(4));
        activeCard.addView(mName);

        TextView mSub = new TextView(this);
        String subInfo = (currentSelectedModel != null)
                ? (currentSelectedModel.provider.toUpperCase() + " • " + currentSelectedModel.getShortContext() + (currentSelectedModel.hasVision ? " • VISION" : "") + (currentSelectedModel.isFree ? " • FREE" : ""))
                : "OpenRouter 400+ Models";
        mSub.setText(subInfo);
        mSub.setTextColor(ACCENT_LIGHT);
        mSub.setTextSize(11.5f);
        activeCard.addView(mSub);

        root.addView(activeCard);

        // Menu Items List
        ScrollView menuScroll = new ScrollView(this);
        LinearLayout menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);

        menuItems.addView(createMenuItem(AppIcons.createNewChatIcon(dp(20), TEXT_PRIMARY), I18n.get("NEW_CHAT"), v -> {
            d.dismiss();
            newChat();
        }));

        menuItems.addView(createMenuItem(AppIcons.createSparkleIcon(dp(20), 0xFFA5B4FC), I18n.get("MENU_MODELS") + " (" + allModels.size() + ")", v -> {
            d.dismiss();
            showModelPickerDialog();
        }));

        menuItems.addView(createMenuItem(AppIcons.createGlobeIcon(dp(20), TEXT_PRIMARY), I18n.get("MENU_LANG") + " (" + I18n.getFlag(I18n.currentLang) + " " + I18n.currentLang.toUpperCase() + ")", v -> {
            d.dismiss();
            showLanguageDialog();
        }));

        menuItems.addView(createMenuItem(AppIcons.createSettingsIcon(dp(20), TEXT_PRIMARY), I18n.get("MENU_SETTINGS"), v -> {
            d.dismiss();
            showSettings(false);
        }));

        menuItems.addView(createMenuItem(AppIcons.createTrashIcon(dp(20), DANGER_COLOR), I18n.get("MENU_CLEAR"), v -> {
            d.dismiss();
            newChat();
        }));

        menuItems.addView(createMenuItem(AppIcons.createCopyIcon(dp(20), ACCENT_LIGHT), I18n.get("MENU_GET_KEY"), v -> {
            d.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")));
            } catch (Exception e) {
                toast("https://openrouter.ai/keys");
            }
        }));

        menuScroll.addView(menuItems, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(menuScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Footer version info
        TextView footer = new TextView(this);
        footer.setText("FreeChat v1.2 • Gemini-style UI • Pure Android");
        footer.setTextColor(TEXT_DIM);
        footer.setTextSize(11f);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));

        d.setContentView(root);
        d.show();
    }

    private LinearLayout createMenuItem(Drawable icon, String text, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundBg(PANEL_COLOR, 12));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setOnClickListener(listener);

        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ivLp.rightMargin = dp(14);
            row.addView(iv, ivLp);
        }

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_PRIMARY);
        tv.setTextSize(15f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tv, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageView arr = new ImageView(this);
        arr.setImageDrawable(AppIcons.createChevronDown(dp(10), TEXT_DIM));
        arr.setRotation(-90);
        row.addView(arr);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private ImageView makeIconButton(Drawable icon, String tooltip, View.OnClickListener listener) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setBackground(roundBg(0x22FFFFFF, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.setMargins(dp(6), 0, 0, 0);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(listener);
        return iv;
    }

    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable strokeBg(int color, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    // --- Message Bubbles Rendering ---

    private void addMessageBubble(String text, List<Attachment> list, boolean isUser) {
        LinearLayout bubbleWrapper = new LinearLayout(this);
        bubbleWrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(roundBg(isUser ? USER_BG : BOT_BG, 14));

        int maxContentWidth = getResources().getDisplayMetrics().widthPixels - dp(64);

        // Attachments (Images & Files)
        if (list != null && !list.isEmpty()) {
            for (Attachment att : list) {
                if ("image".equals(att.kind) && att.thumbnail != null) {
                    ImageView iv = new ImageView(this);
                    iv.setAdjustViewBounds(true);
                    iv.setMaxWidth(maxContentWidth);
                    iv.setMaxHeight(dp(280));
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setImageBitmap(att.thumbnail);
                    iv.setOutlineProvider(new RoundOutlineProvider(dp(10)));
                    iv.setClipToOutline(true);

                    iv.setOnClickListener(v -> showFullscreenImage(att));

                    LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(WRAP, WRAP);
                    imgLp.bottomMargin = (text != null && !text.isEmpty()) ? dp(6) : 0;
                    bubble.addView(iv, imgLp);
                } else if (!"image".equals(att.kind)) {
                    TextView fileTv = new TextView(this);
                    fileTv.setText("📄 " + att.name + " (" + (att.data.length / 1024) + " КБ)");
                    fileTv.setTextColor(TEXT_DIM);
                    fileTv.setTextSize(13f);
                    fileTv.setBackground(roundBg(0x22000000, 6));
                    fileTv.setPadding(dp(8), dp(4), dp(8), dp(4));
                    bubble.addView(fileTv);
                }
            }
        }

        // Message Text
        if (text != null && !text.isEmpty()) {
            renderFormattedText(bubble, text, maxContentWidth);
        }

        // Message Footer (Timestamp & 1-Click Copy Prompt Button)
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(6), 0, 0);

        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        TextView timeTv = new TextView(this);
        timeTv.setText(time);
        timeTv.setTextColor(TEXT_DIM);
        timeTv.setTextSize(10f);
        footer.addView(timeTv, new LinearLayout.LayoutParams(0, WRAP, 1f));

        // 1-Click Copy Prompt Button
        if (isUser && text != null && !text.isEmpty()) {
            final String promptToCopy = text;
            TextView copyPromptBtn = createActionButton("Скопировать", AppIcons.createCopyIcon(dp(12), 0xFFB0B0C0), 0x2AFFFFFF);
            copyPromptBtn.setOnClickListener(v -> copyWithFeedback(copyPromptBtn, "Промпт", promptToCopy));
            footer.addView(copyPromptBtn);
        }

        bubble.addView(footer);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = isUser ? Gravity.END : Gravity.START;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        bubbleWrapper.addView(bubble, lp);

        messagesContainer.addView(bubbleWrapper);
        scrollDown();
    }

    private BotBubbleHolder addBotBubble(String text, String error) {
        LinearLayout bubbleWrapper = new LinearLayout(this);
        bubbleWrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(roundBg(BOT_BG, 14));

        int maxContentWidth = getResources().getDisplayMetrics().widthPixels - dp(64);

        // Bot Header
        LinearLayout botHeader = new LinearLayout(this);
        botHeader.setOrientation(LinearLayout.HORIZONTAL);
        botHeader.setGravity(Gravity.CENTER_VERTICAL);
        botHeader.setPadding(0, 0, 0, dp(4));

        TextView botIcon = new TextView(this);
        botIcon.setText("🤖");
        botIcon.setTextSize(14f);
        botHeader.addView(botIcon);

        TextView botName = new TextView(this);
        String mName = (currentSelectedModel != null) ? currentSelectedModel.name : "FreeChat AI";
        botName.setText(" " + mName);
        botName.setTextColor(ACCENT_LIGHT);
        botName.setTextSize(12f);
        botName.setTypeface(Typeface.DEFAULT_BOLD);
        botHeader.addView(botName, new LinearLayout.LayoutParams(0, WRAP, 1f));

        bubble.addView(botHeader);

        // Body Container
        LinearLayout bodyContainer = new LinearLayout(this);
        bodyContainer.setOrientation(LinearLayout.VERTICAL);

        TextView streamingTv = new TextView(this);
        streamingTv.setText(text);
        streamingTv.setTextColor(error != null ? DANGER_COLOR : TEXT_PRIMARY);
        streamingTv.setTextSize(15f);
        streamingTv.setLineSpacing(dp(2), 1f);
        streamingTv.setTextIsSelectable(true);
        streamingTv.setMaxWidth(maxContentWidth);
        bodyContainer.addView(streamingTv);

        bubble.addView(bodyContainer);

        // Footer
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(6), 0, 0);

        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        TextView timeTv = new TextView(this);
        timeTv.setText(time);
        timeTv.setTextColor(TEXT_DIM);
        timeTv.setTextSize(10f);
        footer.addView(timeTv, new LinearLayout.LayoutParams(0, WRAP, 1f));

        bubble.addView(footer);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = Gravity.START;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        bubbleWrapper.addView(bubble, lp);

        messagesContainer.addView(bubbleWrapper);
        scrollDown();

        return new BotBubbleHolder(bubble, bodyContainer, streamingTv, footer);
    }

    private void finishBotBubbleFormatting(BotBubbleHolder holder, String fullText) {
        if (holder == null || fullText == null) return;

        holder.bodyContainer.removeAllViews();
        int maxContentWidth = getResources().getDisplayMetrics().widthPixels - dp(64);
        renderFormattedText(holder.bodyContainer, fullText, maxContentWidth);

        holder.footer.removeAllViews();

        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        TextView timeTv = new TextView(this);
        timeTv.setText(time);
        timeTv.setTextColor(TEXT_DIM);
        timeTv.setTextSize(10f);
        holder.footer.addView(timeTv, new LinearLayout.LayoutParams(0, WRAP, 1f));

        // If there's code, add a 1-click "Весь код" button
        String allCode = extractAllCode(fullText);
        if (!allCode.isEmpty()) {
            TextView copyAllCodeBtn = createActionButton("Весь код", AppIcons.createCopyIcon(dp(12), 0xFFB0B0C0), 0xFF2E7D32);
            copyAllCodeBtn.setOnClickListener(v -> copyWithFeedback(copyAllCodeBtn, "Весь код", allCode));
            holder.footer.addView(copyAllCodeBtn);
        }

        // 1-Click "Скопировать ответ" button
        TextView copyAnswerBtn = createActionButton("Скопировать ответ", AppIcons.createCopyIcon(dp(12), 0xFFB0B0C0), 0x2AFFFFFF);
        copyAnswerBtn.setOnClickListener(v -> copyWithFeedback(copyAnswerBtn, "Ответ", fullText));
        holder.footer.addView(copyAnswerBtn);

        scrollDown();
    }

    private TextView createActionButton(String label, Drawable icon, int bgColor) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(TEXT_PRIMARY);
        btn.setTextSize(11f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(roundBg(bgColor, 6));
        btn.setPadding(dp(8), dp(4), dp(8), dp(4));
        if (icon != null) {
            btn.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
            btn.setCompoundDrawablePadding(dp(5));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(dp(6), 0, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void copyWithFeedback(TextView button, String label, String textToCopy) {
        if (textToCopy == null || textToCopy.isEmpty()) {
            toast("Нечего копировать");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, textToCopy));
            toast(label + " скопирован в буфер обмена");
        }
        if (button != null) {
            button.setCompoundDrawablesWithIntrinsicBounds(AppIcons.createCheckIcon(dp(12), SUCCESS_COLOR), null, null, null);
            button.setText("Скопировано!");
            button.setTextColor(SUCCESS_COLOR);
            main.postDelayed(() -> {
                button.setCompoundDrawablesWithIntrinsicBounds(AppIcons.createCopyIcon(dp(12), 0xFFB0B0C0), null, null, null);
                button.setText(label.equals("Промпт") ? "Скопировать" : (label.equals("Ответ") ? "Скопировать ответ" : (label.equals("Код") ? "Скопировать код" : label)));
                button.setTextColor(TEXT_PRIMARY);
            }, 1500);
        }
    }

    private String extractAllCode(String rawText) {
        if (rawText == null) return "";
        Pattern p = Pattern.compile("```(?:[a-zA-Z0-9_]*)\\n?([\\s\\S]*?)```");
        Matcher m = p.matcher(rawText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m.group(1).trim());
        }
        return sb.toString();
    }

    private void renderFormattedText(ViewGroup parent, String rawText, int maxWidth) {
        Pattern codeBlockPattern = Pattern.compile("```([a-zA-Z0-9_]*)\\n?([\\s\\S]*?)```");
        Matcher matcher = codeBlockPattern.matcher(rawText);

        int lastIndex = 0;
        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                String regularText = rawText.substring(lastIndex, matcher.start()).trim();
                if (!regularText.isEmpty()) {
                    addFormattedTextBlock(parent, regularText, maxWidth);
                }
            }

            String lang = matcher.group(1);
            String code = matcher.group(2);
            addCodeBlockView(parent, lang, code, maxWidth);

            lastIndex = matcher.end();
        }

        if (lastIndex < rawText.length()) {
            String trailing = rawText.substring(lastIndex).trim();
            if (!trailing.isEmpty()) {
                addFormattedTextBlock(parent, trailing, maxWidth);
            }
        }
    }

    private void addFormattedTextBlock(ViewGroup parent, String text, int maxWidth) {
        TextView tv = new TextView(this);
        tv.setText(formatInlineMarkdown(text));
        tv.setTextColor(TEXT_PRIMARY);
        tv.setTextSize(15f);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextIsSelectable(true);
        tv.setMaxWidth(maxWidth);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.bottomMargin = dp(4);
        parent.addView(tv, lp);
    }

    private CharSequence formatInlineMarkdown(String text) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);

        // Bold **text**
        Pattern bold = Pattern.compile("\\*\\*(.*?)\\*\\*");
        Matcher m = bold.matcher(ssb);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            String content = m.group(1);
            ssb.replace(start, end, content);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), start, start + content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            m = bold.matcher(ssb);
        }

        // Inline code `code`
        Pattern inlineCode = Pattern.compile("`([^`]+)`");
        Matcher mc = inlineCode.matcher(ssb);
        while (mc.find()) {
            int start = mc.start();
            int end = mc.end();
            String content = mc.group(1);
            ssb.replace(start, end, content);
            ssb.setSpan(new TypefaceSpan("monospace"), start, start + content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new BackgroundColorSpan(0x33000000), start, start + content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mc = inlineCode.matcher(ssb);
        }

        return ssb;
    }

    private void addCodeBlockView(ViewGroup parent, String lang, String code, int maxWidth) {
        LinearLayout codeCard = new LinearLayout(this);
        codeCard.setOrientation(LinearLayout.VERTICAL);
        codeCard.setBackground(roundBg(CODE_BG, 8));
        codeCard.setPadding(dp(10), dp(8), dp(10), dp(8));

        // Code Card Header with Language & One-Tap Copy Button
        LinearLayout cardHeader = new LinearLayout(this);
        cardHeader.setOrientation(LinearLayout.HORIZONTAL);
        cardHeader.setGravity(Gravity.CENTER_VERTICAL);
        cardHeader.setPadding(0, 0, 0, dp(6));

        TextView langTv = new TextView(this);
        langTv.setText("⚡ " + ((lang != null && !lang.isEmpty()) ? lang.toUpperCase() : "КОД"));
        langTv.setTextColor(ACCENT_LIGHT);
        langTv.setTextSize(11.5f);
        langTv.setTypeface(Typeface.DEFAULT_BOLD);
        cardHeader.addView(langTv, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final String codeToCopy = (code != null) ? code.trim() : "";
        TextView copyCodeBtn = createActionButton("Скопировать код", AppIcons.createCopyIcon(dp(12), Color.WHITE), ACCENT_COLOR);
        copyCodeBtn.setOnClickListener(v -> copyWithFeedback(copyCodeBtn, "Код", codeToCopy));
        cardHeader.addView(copyCodeBtn);

        codeCard.addView(cardHeader);

        // Code Content (Horizontal scrollable)
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(true);
        TextView codeTv = new TextView(this);
        codeTv.setText(codeToCopy);
        codeTv.setTextColor(0xFFD4D4D4);
        codeTv.setTextSize(13f);
        codeTv.setTypeface(Typeface.MONOSPACE);
        codeTv.setTextIsSelectable(true);
        codeTv.setPadding(dp(2), dp(2), dp(2), dp(2));
        hScroll.addView(codeTv);

        codeCard.addView(hScroll);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(6);
        parent.addView(codeCard, lp);
    }

    private void showFullscreenImage(Attachment att) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(0xF0000000);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(att.fullBitmap != null ? att.fullBitmap : att.thumbnail);
        layout.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕ Закрыть");
        closeBtn.setTextColor(TEXT_PRIMARY);
        closeBtn.setTextSize(16f);
        closeBtn.setPadding(dp(16), dp(24), dp(16), dp(16));
        closeBtn.setOnClickListener(v -> d.dismiss());
        layout.addView(closeBtn, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));

        d.setContentView(layout);
        d.show();
    }

    private void updateBubbleText(TextView tv, String text) {
        tv.setText(text);
        scrollDown();
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // --- Message Sending ---

    private void sendMessage() {
        String trim = input.getText().toString().trim();
        if (trim.isEmpty() && currentAttachments.isEmpty()) {
            return;
        }
        if (greetingContainer != null) {
            greetingContainer.setVisibility(View.GONE);
        }
        if (isSending) {
            toast("Дождитесь окончания ответа нейросети");
            return;
        }
        if (prefs.getString("api_key", "").isEmpty()) {
            showSettings(true);
            return;
        }

        if (currentSelectedModel == null) {
            toast("Модель не выбрана. Нажмите на плашку модели внизу");
            return;
        }

        boolean hasImage = false;
        for (Attachment a : currentAttachments) {
            if ("image".equals(a.kind)) {
                hasImage = true;
                break;
            }
        }
        if (hasImage && !currentSelectedModel.hasVision) {
            toast("⚠️ Модель " + currentSelectedModel.name + " не поддерживает фото. Рекомендуется выбрать модель с 📷");
        }

        List<Attachment> sentList = new ArrayList<>(currentAttachments);
        currentAttachments.clear();
        renderAttachments();
        input.setText("");

        addMessageBubble(trim, sentList, true);

        JSONObject userObj = new JSONObject();
        try {
            userObj.put("role", "user");
            userObj.put("content", buildUserContent(trim, sentList));
        } catch (JSONException ignored) {
        }
        history.add(userObj);

        inFlightAttachments.clear();
        inFlightAttachments.addAll(sentList);

        BotBubbleHolder botHolder = addBotBubble("…", null);
        isSending = true;

        pool.execute(() -> streamChat(currentSelectedModel.id, botHolder));
    }

    private Object buildUserContent(String prompt, List<Attachment> list) throws JSONException {
        if (list.isEmpty()) {
            return prompt;
        }
        JSONArray arr = new JSONArray();

        JSONObject textObj = new JSONObject();
        textObj.put("type", "text");
        textObj.put("text", prompt.isEmpty() ? "Опиши и подробно проанализируй прикреплённое фото/файл." : prompt);
        arr.put(textObj);

        for (Attachment att : list) {
            if ("image".equals(att.kind)) {
                JSONObject imgObj = new JSONObject();
                imgObj.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:" + att.mime + ";base64," + Base64.encodeToString(att.data, Base64.NO_WRAP));
                imgObj.put("image_url", urlObj);
                arr.put(imgObj);
            } else if ("text".equals(att.kind)) {
                JSONObject docObj = new JSONObject();
                docObj.put("type", "text");
                docObj.put("text", "Файл \"" + att.name + "\":\n" + att.text);
                arr.put(docObj);
            } else {
                JSONObject docObj = new JSONObject();
                docObj.put("type", "text");
                docObj.put("text", "[Прикреплён файл: " + att.name + " (" + att.mime + "), размер: " + (att.data.length / 1024) + " КБ]");
                arr.put(docObj);
            }
        }
        return arr;
    }

    private void streamChat(String modelId, BotBubbleHolder holder) {
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
            conn.setRequestProperty("User-Agent", "FreeChat/1.2");
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
                                        main.post(() -> updateBubbleText(holder.streamingTv, currentText + " ▌"));
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
        main.post(() -> onChatFinished(finalError, holder, finalResult));
    }

    private void onChatFinished(String error, BotBubbleHolder holder, String text) {
        isSending = false;
        if (error != null) {
            removeLastUser();
            restoreInFlight();
            holder.streamingTv.setTextColor(DANGER_COLOR);
            updateBubbleText(holder.streamingTv, "Ошибка: " + error);
            return;
        }

        if (text.isEmpty()) {
            removeLastUser();
            restoreInFlight();
            holder.streamingTv.setTextColor(TEXT_DIM);
            updateBubbleText(holder.streamingTv, "(получен пустой ответ от модели — попробуйте другую модель)");
            return;
        }

        inFlightAttachments.clear();

        // Render full rich formatted message with individual code cards and copy buttons!
        finishBotBubbleFormatting(holder, text);

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

    private void restoreInFlight() {
        if (!inFlightAttachments.isEmpty()) {
            currentAttachments.addAll(0, inFlightAttachments);
            inFlightAttachments.clear();
            renderAttachments();
        }
    }

    // --- Attachments Handling ---

    private void showAttachMenu() {
        new AlertDialog.Builder(this)
                .setTitle("📎 Прикрепить")
                .setItems(new String[]{"📷 Фотография (Галерея)", "📄 Документ (PDF, TXT, DOC)", "📊 Любой файл"}, (dialog, which) -> pickAttachment(which))
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

        if (uris.isEmpty()) return;

        toast(uris.size() > 1 ? ("Читаю " + uris.size() + " фото/файлов…") : "Обработка файла…");

        pool.execute(() -> {
            for (Uri uri : uris) {
                try {
                    Attachment att = readAttachment(uri, requestCode == REQ_PHOTO);
                    main.post(() -> {
                        currentAttachments.add(att);
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
                    return new Attachment(name, "image/jpeg", jpegBytes, "image", null, thumb, scaled);
                }
            }
            return new Attachment(name, "image/jpeg", rawBytes, "image", null, null, null);
        }

        if (mime.startsWith("text/")) {
            byte[] bytes = readRawStream(uri);
            return new Attachment(name, mime, bytes, "text", new String(bytes, StandardCharsets.UTF_8), null, null);
        }

        byte[] bytes = readRawStream(uri);
        return new Attachment(name, mime, bytes, "file", null, null, null);
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
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".csv") || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".java") || lower.endsWith(".html") || lower.endsWith(".css"))
            return "text/plain";
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
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Нет доступа к файлу");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int len;
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (total > MAX_FILE_SIZE) {
                    throw new IOException("Файл больше 25 МБ");
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex("_display_name");
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return (name == null || name.isEmpty()) ? "файл" : name;
    }

    private void renderAttachments() {
        attachRow.removeAllViews();
        if (currentAttachments.isEmpty()) {
            attachScroll.setVisibility(View.GONE);
            return;
        }
        attachScroll.setVisibility(View.VISIBLE);

        for (int i = 0; i < currentAttachments.size(); i++) {
            final int idx = i;
            Attachment att = currentAttachments.get(i);

            if ("image".equals(att.kind) && att.thumbnail != null) {
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
                del.setTextColor(TEXT_PRIMARY);
                del.setTextSize(11f);
                del.setGravity(Gravity.CENTER);
                del.setBackground(roundBg(0xCC000000, 10));
                FrameLayout.LayoutParams delLp = new FrameLayout.LayoutParams(dp(20), dp(20));
                delLp.gravity = Gravity.TOP | Gravity.END;
                delLp.setMargins(0, dp(2), dp(2), 0);
                card.addView(del, delLp);

                card.setOnClickListener(v -> {
                    currentAttachments.remove(idx);
                    renderAttachments();
                });
                attachRow.addView(card);
            } else {
                TextView tv = new TextView(this);
                tv.setText("📄 " + att.name + "  ✕");
                tv.setTextColor(TEXT_PRIMARY);
                tv.setTextSize(12f);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tv.setMaxWidth(dp(200));
                tv.setPadding(dp(10), dp(6), dp(10), dp(6));
                tv.setBackground(roundBg(BOT_BG, 12));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.setMargins(0, 0, dp(6), 0);
                tv.setOnClickListener(v -> {
                    currentAttachments.remove(idx);
                    renderAttachments();
                });
                attachRow.addView(tv, lp);
            }
        }
    }

    // --- Model Management & Modern Picker Dialog ---

    private void initDefaultModels() {
        allModels.clear();
        // Top curated free & popular models available immediately
        allModels.add(new Model("google/gemma-4-26b-a4b-it:free", "Google: Gemma 4 26B (free)", "Google", 262144, true, true));
        allModels.add(new Model("google/gemma-4-31b-it:free", "Google: Gemma 4 31B (free)", "Google", 262144, true, true));
        allModels.add(new Model("nvidia/nemotron-3.5-super-vl:free", "NVIDIA: Nemotron 3.5 Super VL (free)", "NVIDIA", 1000000, true, true));
        allModels.add(new Model("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", "NVIDIA: Nemotron 3 Nano Omni (free)", "NVIDIA", 256000, true, true));
        allModels.add(new Model("stealth/crouton-alpha", "Crouton Alpha (free)", "Stealth", 1048576, true, true));
        allModels.add(new Model("deepseek/deepseek-r1:free", "DeepSeek: R1 Reasoning (free)", "DeepSeek", 128000, false, true));
        allModels.add(new Model("deepseek/deepseek-chat:free", "DeepSeek: V3 Chat (free)", "DeepSeek", 64000, false, true));
        allModels.add(new Model("meta-llama/llama-3.3-70b-instruct:free", "Meta: Llama 3.3 70B (free)", "Meta", 128000, false, true));
        allModels.add(new Model("qwen/qwen-2.5-coder-32b-instruct:free", "Qwen 2.5 Coder 32B (free)", "Qwen", 128000, false, true));
        allModels.add(new Model("mistralai/mistral-small-24b-instruct-2501:free", "Mistral Small 24B (free)", "Mistral", 32768, false, true));
        allModels.add(new Model("cohere/north-mini-code:free", "Cohere: North Mini Code (free)", "Cohere", 256000, false, true));
        allModels.add(new Model("liquid/lfm-3-1.7b:free", "LiquidAI: LFM 3 1.7B (free)", "LiquidAI", 262144, false, true));

        // Popular Vision Flagships
        allModels.add(new Model("google/gemini-2.0-flash-exp:free", "Google: Gemini 2.0 Flash (free)", "Google", 1048576, true, true));
        allModels.add(new Model("anthropic/claude-3.5-sonnet", "Anthropic: Claude 3.5 Sonnet", "Anthropic", 200000, true, false));
        allModels.add(new Model("openai/gpt-4o", "OpenAI: GPT-4o Omni", "OpenAI", 128000, true, false));

        selectDefaultModel();
    }

    private void selectDefaultModel() {
        String savedId = prefs.getString("model", "");
        for (Model m : allModels) {
            if (m.id.equals(savedId)) {
                setSelectedModel(m);
                return;
            }
        }
        if (!allModels.isEmpty()) {
            setSelectedModel(allModels.get(0));
        }
    }

    private void setSelectedModel(Model m) {
        currentSelectedModel = m;
        prefs.edit().putString("model", m.id).apply();

        if (modelPillText != null) {
            modelPillText.setText(m.name);
        }
        if (activeModelBadge != null) {
            String freeTag = m.isFree ? " [FREE]" : "";
            String visionTag = m.hasVision ? " [VISION]" : "";
            activeModelBadge.setText(m.name + freeTag + visionTag);
        }
        if (activeModelSub != null) {
            activeModelSub.setText(m.provider.toUpperCase() + " • " + m.getShortContext());
        }
    }

    private void showModelPickerDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Каталог моделей (" + allModels.size() + ")");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageView refreshBtn = makeIconButton(AppIcons.createRefreshIcon(dp(18), TEXT_PRIMARY), "Обновить каталог", v -> {
            refreshModels(true);
            dialog.dismiss();
        });
        header.addView(refreshBtn);

        ImageView closeBtn = makeIconButton(AppIcons.createCloseIcon(dp(18), TEXT_PRIMARY), "Закрыть", v -> dialog.dismiss());
        header.addView(closeBtn);

        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Search bar
        EditText searchInput = new EditText(this);
        searchInput.setHint("Поиск моделей (Gemini, Llama, DeepSeek, Claude…)");
        searchInput.setHintTextColor(TEXT_DIM);
        searchInput.setTextColor(TEXT_PRIMARY);
        searchInput.setTextSize(14f);
        searchInput.setBackground(roundBg(PANEL_COLOR, 10));
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchInput.setCompoundDrawablesWithIntrinsicBounds(AppIcons.createSearchIcon(dp(16), TEXT_DIM), null, null, null);
        searchInput.setCompoundDrawablePadding(dp(8));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        searchLp.topMargin = dp(10);
        searchLp.bottomMargin = dp(8);
        root.addView(searchInput, searchLp);

        // Filter chips (All / Free / Vision)
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);

        final int[] filterMode = {prefs.getBoolean("filter_free_only", true) ? 1 : 0}; // 0 = all, 1 = free, 2 = vision

        Button btnAll = createFilterChip(I18n.get("ALL_MODELS"), filterMode[0] == 0);
        Button btnFree = createFilterChip(I18n.get("FREE_MODELS"), filterMode[0] == 1);
        Button btnVision = createFilterChip(I18n.get("VISION_MODELS"), filterMode[0] == 2);

        filterRow.addView(btnAll);
        filterRow.addView(btnFree);
        filterRow.addView(btnVision);
        filterScroll.addView(filterRow);
        root.addView(filterScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Models List
        ListView listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(dp(6));

        final List<Model> filtered = new ArrayList<>();
        ModelAdapter adapter = new ModelAdapter(this, filtered, dialog);
        listView.setAdapter(adapter);

        Runnable applyFilter = () -> {
            String query = searchInput.getText().toString().trim().toLowerCase();
            filtered.clear();
            for (Model m : allModels) {
                if (filterMode[0] == 1 && !m.isFree) continue;
                if (filterMode[0] == 2 && !m.hasVision) continue;
                if (!query.isEmpty()) {
                    boolean match = m.name.toLowerCase().contains(query)
                            || m.id.toLowerCase().contains(query)
                            || m.provider.toLowerCase().contains(query);
                    if (!match) continue;
                }
                filtered.add(m);
            }
            adapter.notifyDataSetChanged();
            title.setText("Каталог моделей (" + filtered.size() + "/" + allModels.size() + ")");
        };

        btnAll.setOnClickListener(v -> {
            filterMode[0] = 0;
            updateFilterChipStyles(btnAll, btnFree, btnVision, 0);
            applyFilter.run();
        });
        btnFree.setOnClickListener(v -> {
            filterMode[0] = 1;
            updateFilterChipStyles(btnAll, btnFree, btnVision, 1);
            applyFilter.run();
        });
        btnVision.setOnClickListener(v -> {
            filterMode[0] = 2;
            updateFilterChipStyles(btnAll, btnFree, btnVision, 2);
            applyFilter.run();
        });

        searchInput.addTextChangedListener(new SimpleTextWatcher(applyFilter));
        applyFilter.run();

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        listLp.topMargin = dp(8);
        root.addView(listView, listLp);

        // Custom model manual input
        Button customBtn = new Button(this);
        customBtn.setText(I18n.get("CUSTOM_MODEL"));
        customBtn.setTextColor(ACCENT_LIGHT);
        customBtn.setTextSize(13f);
        customBtn.setBackground(roundBg(PANEL_COLOR, 8));
        customBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomModelDialog();
        });
        root.addView(customBtn, new LinearLayout.LayoutParams(MATCH, WRAP));

        dialog.setContentView(root);
        dialog.show();
    }

    private Button createFilterChip(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setTextColor(active ? 0xFF0E1621 : TEXT_PRIMARY);
        b.setBackground(roundBg(active ? ACCENT_COLOR : PANEL_COLOR, 14));
        b.setPadding(dp(12), dp(2), dp(12), dp(2));
        b.setMinHeight(dp(32));
        b.setMinimumHeight(dp(32));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(0, dp(4), dp(6), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void updateFilterChipStyles(Button bAll, Button bFree, Button bVis, int activeIdx) {
        bAll.setBackground(roundBg(activeIdx == 0 ? ACCENT_COLOR : PANEL_COLOR, 14));
        bAll.setTextColor(activeIdx == 0 ? 0xFF0E1621 : TEXT_PRIMARY);

        bFree.setBackground(roundBg(activeIdx == 1 ? ACCENT_COLOR : PANEL_COLOR, 14));
        bFree.setTextColor(activeIdx == 1 ? 0xFF0E1621 : TEXT_PRIMARY);

        bVis.setBackground(roundBg(activeIdx == 2 ? ACCENT_COLOR : PANEL_COLOR, 14));
        bVis.setTextColor(activeIdx == 2 ? 0xFF0E1621 : TEXT_PRIMARY);
    }

    private void showCustomModelDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Своя модель OpenRouter");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));

        final EditText edit = new EditText(this);
        edit.setHint("например: qwen/qwen-2.5-72b-instruct");
        edit.setText(currentSelectedModel != null ? currentSelectedModel.id : "");
        edit.setTextColor(TEXT_PRIMARY);
        edit.setHintTextColor(TEXT_DIM);
        layout.addView(edit);

        final CheckBox cbVision = new CheckBox(this);
        cbVision.setText("Поддерживает изображения (Vision)");
        cbVision.setTextColor(TEXT_PRIMARY);
        layout.addView(cbVision);

        b.setView(layout);
        b.setPositiveButton("Применить", (dialog, which) -> {
            String id = edit.getText().toString().trim();
            if (!id.isEmpty()) {
                String provider = id.contains("/") ? id.split("/")[0] : "Custom";
                Model custom = new Model(id, id, provider, 0, cbVision.isChecked(), id.endsWith(":free"));
                allModels.add(0, custom);
                setSelectedModel(custom);
                toast("Модель установлена: " + id);
            }
        });
        b.setNegativeButton("Отмена", null);
        b.show();
    }

    public static class ModelAdapter extends BaseAdapter {
        private final MainActivity activity;
        private final List<Model> list;
        private final Dialog parentDialog;

        public ModelAdapter(MainActivity activity, List<Model> list, Dialog dialog) {
            this.activity = activity;
            this.list = list;
            this.parentDialog = dialog;
        }

        @Override
        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int position) {
            return list.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10));

            Model m = list.get(position);
            boolean isSelected = (activity.currentSelectedModel != null && activity.currentSelectedModel.id.equals(m.id));

            item.setBackground(isSelected ? activity.strokeBg(PANEL_COLOR, ACCENT_COLOR, 10) : activity.roundBg(PANEL_COLOR, 10));

            // Provider Monogram Badge
            ProviderInfo pInfo = getProviderInfo(m.provider);
            TextView providerBadge = new TextView(activity);
            providerBadge.setText(pInfo.tag);
            providerBadge.setTextColor(Color.WHITE);
            providerBadge.setTextSize(11f);
            providerBadge.setTypeface(Typeface.DEFAULT_BOLD);
            providerBadge.setGravity(Gravity.CENTER);
            providerBadge.setBackground(activity.roundBg(pInfo.color, 15));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(activity.dp(30), activity.dp(30));
            badgeLp.rightMargin = activity.dp(10);
            providerBadge.setLayoutParams(badgeLp);
            item.addView(providerBadge);

            // Model Details
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = new TextView(activity);
            nameTv.setText(m.name);
            nameTv.setTextColor(isSelected ? ACCENT_LIGHT : TEXT_PRIMARY);
            nameTv.setTextSize(14f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(nameTv);

            TextView idTv = new TextView(activity);
            idTv.setText(m.id);
            idTv.setTextColor(TEXT_DIM);
            idTv.setTextSize(11f);
            idTv.setSingleLine(true);
            idTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            col.addView(idTv);

            // Tags row (FREE, VISION, CTX)
            LinearLayout tagsRow = new LinearLayout(activity);
            tagsRow.setOrientation(LinearLayout.HORIZONTAL);
            tagsRow.setPadding(0, activity.dp(3), 0, 0);

            if (m.isFree) {
                tagsRow.addView(createTag("FREE", SUCCESS_COLOR, null));
            }
            if (m.hasVision) {
                tagsRow.addView(createTag("VISION", ACCENT_COLOR, AppIcons.createCameraIcon(activity.dp(11), Color.WHITE)));
            }
            if (!m.getShortContext().isEmpty()) {
                tagsRow.addView(createTag(m.getShortContext(), 0xFF5C6B73, null));
            }

            col.addView(tagsRow);
            item.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

            if (isSelected) {
                TextView check = new TextView(activity);
                check.setText("✓");
                check.setTextColor(ACCENT_COLOR);
                check.setTextSize(20f);
                check.setTypeface(Typeface.DEFAULT_BOLD);
                check.setPadding(activity.dp(8), 0, 0, 0);
                item.addView(check);
            }

            item.setOnClickListener(v -> {
                activity.setSelectedModel(m);
                parentDialog.dismiss();
                activity.toast("Выбрана модель: " + m.name);
            });

            return item;
        }

        private TextView createTag(String text, int color, Drawable icon) {
            TextView t = new TextView(activity);
            t.setText(text);
            t.setTextColor(TEXT_PRIMARY);
            t.setTextSize(10f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setBackground(activity.roundBg(color, 4));
            t.setPadding(activity.dp(5), activity.dp(1), activity.dp(5), activity.dp(1));
            if (icon != null) {
                t.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
                t.setCompoundDrawablePadding(activity.dp(3));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.setMargins(0, 0, activity.dp(4), 0);
            t.setLayoutParams(lp);
            return t;
        }
    }

    private void refreshModels(boolean showToast) {
        String key = prefs.getString("api_key", "");
        if (key.isEmpty()) {
            showSettings(true);
            return;
        }
        if (showToast) {
            toast("Связываюсь с OpenRouter…");
        }
        if (statusSubtitle != null) statusSubtitle.setText("Обновление каталога…");

        pool.execute(() -> {
            try {
                List<Model> fetched = fetchModels(key);
                main.post(() -> {
                    allModels.clear();
                    allModels.addAll(fetched);
                    selectDefaultModel();
                    saveModelsCache();
                    if (statusSubtitle != null) statusSubtitle.setText("Доступно моделей: " + allModels.size());
                    if (showToast) {
                        toast("Каталог обновлён: " + allModels.size() + " моделей");
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> {
                    if (statusSubtitle != null) statusSubtitle.setText("Ошибка каталога (работаем офлайн)");
                    if (showToast) {
                        toast("Ошибка обновления: " + msg);
                    }
                });
            }
        });
    }

    private List<Model> fetchModels(String apiKey) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/models").openConnection();
        try {
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("User-Agent", "FreeChat/1.2");
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
                String id = m.optString("id", "");
                if (id.isEmpty()) continue;

                JSONObject pricing = m.optJSONObject("pricing");
                boolean isFree = id.endsWith(":free") || (pricing != null
                        && isZero(pricing.optString("prompt", "1"))
                        && isZero(pricing.optString("completion", "1")));

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
                String provider = id.contains("/") ? id.split("/")[0] : "AI";
                provider = Character.toUpperCase(provider.charAt(0)) + provider.substring(1);

                list.add(new Model(id, name, provider, ctx, vision, isFree));
            }

            // Sort: Free first, Vision first, then by name
            Collections.sort(list, (a, b) -> {
                if (a.isFree != b.isFree) {
                    return a.isFree ? -1 : 1;
                }
                if (a.hasVision != b.hasVision) {
                    return a.hasVision ? -1 : 1;
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
                return "HTTP 400: " + msg + " (выбранная модель может не поддерживать фото)";
            case 401:
                return "HTTP 401: " + msg + " — проверьте API-ключ в настройках (⚙️)";
            case 402:
                return "HTTP 402: " + msg + " — выберите бесплатную модель (:free) или пополните баланс";
            case 403:
                return "HTTP 403: " + msg + " — доступ запрещён";
            case 404:
                return "HTTP 404: " + msg + " — обновите каталог моделей (↻)";
            case 413:
                return "HTTP 413: Слишком большой размер вложения";
            case 429:
                return "HTTP 429: " + msg + " — лимит запросов, подождите или смените модель";
            case 502:
            case 503:
                return "HTTP " + code + ": " + msg + " — провайдер модели временно недоступен";
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
        currentAttachments.clear();
        inFlightAttachments.clear();
        renderAttachments();
        messagesContainer.removeAllViews();
        if (greetingContainer != null) {
            if (greetingContainer.getParent() != null) {
                ((ViewGroup) greetingContainer.getParent()).removeView(greetingContainer);
            }
            messagesContainer.addView(greetingContainer);
            greetingContainer.setVisibility(View.VISIBLE);
        }
        toast(I18n.get("MENU_CLEAR"));
    }

    private void loadCachedModels() {
        String cache = prefs.getString("models_cache", "");
        if (cache.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(cache);
            if (arr.length() > 0) {
                allModels.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    allModels.add(new Model(
                            obj.optString("id"),
                            obj.optString("name"),
                            obj.optString("provider", "AI"),
                            obj.optInt("ctx"),
                            obj.optBoolean("vision", false),
                            obj.optBoolean("free", true)
                    ));
                }
                selectDefaultModel();
                if (statusSubtitle != null) statusSubtitle.setText("Загружено моделей: " + allModels.size());
            }
        } catch (JSONException ignored) {
        }
    }

    private void saveModelsCache() {
        JSONArray arr = new JSONArray();
        for (Model m : allModels) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", m.id);
                obj.put("name", m.name);
                obj.put("provider", m.provider);
                obj.put("ctx", m.ctx);
                obj.put("vision", m.hasVision);
                obj.put("free", m.isFree);
            } catch (JSONException ignored) {
            }
            arr.put(obj);
        }
        prefs.edit().putString("models_cache", arr.toString()).apply();
    }

    // --- Settings & Presets Dialog ---

    private void showSettings(boolean firstRun) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(firstRun ? "⚡ Добро пожаловать в FreeChat" : "⚙️ Настройки");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        if (!firstRun) {
            ImageView closeBtn = makeIconButton(AppIcons.createCloseIcon(dp(18), TEXT_PRIMARY), "Закрыть", v -> dialog.dismiss());
            header.addView(closeBtn);
        }
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView contentScroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // 1. API Key Card
        content.addView(label("🔑 API-КЛЮЧ OPENROUTER:"));
        EditText keyInput = new EditText(this);
        keyInput.setHint("sk-or-v1-...");
        keyInput.setText(prefs.getString("api_key", ""));
        keyInput.setSingleLine(true);
        keyInput.setInputType(129); // textPassword
        keyInput.setTextColor(TEXT_PRIMARY);
        keyInput.setHintTextColor(TEXT_DIM);
        keyInput.setBackground(roundBg(PANEL_COLOR, 8));
        keyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(keyInput);

        Button getKeyBtn = new Button(this);
        getKeyBtn.setText("Получить бесплатный API-ключ (openrouter.ai)");
        getKeyBtn.setTextColor(ACCENT_LIGHT);
        getKeyBtn.setTextSize(12f);
        getKeyBtn.setBackground(roundBg(PANEL_HOVER, 6));
        getKeyBtn.setOnClickListener(v -> openUrl("https://openrouter.ai/keys"));
        LinearLayout.LayoutParams getKeyLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        getKeyLp.topMargin = dp(6);
        content.addView(getKeyBtn, getKeyLp);

        // 2. System Prompt & Quick Presets
        content.addView(label("🧠 СИСТЕМНЫЙ ПРОМПТ (РОЛЬ):"));
        EditText promptInput = new EditText(this);
        promptInput.setText(prefs.getString("system", DEFAULT_SYSTEM));
        promptInput.setMinLines(2);
        promptInput.setMaxLines(6);
        promptInput.setInputType(131073);
        promptInput.setTextColor(TEXT_PRIMARY);
        promptInput.setHintTextColor(TEXT_DIM);
        promptInput.setBackground(roundBg(PANEL_COLOR, 8));
        promptInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(promptInput);

        // Preset Chips
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);

        presetRow.addView(createPresetChip("💬 Ассистент", promptInput, "Ты полезный ассистент. Отвечай кратко, чётко и по делу."));
        presetRow.addView(createPresetChip("💻 Программист", promptInput, "Ты Senior разработчик. Пиши чистый, оптимизированный код с краткими пояснениями."));
        presetRow.addView(createPresetChip("🌍 Переводчик", promptInput, "Ты профессиональный переводчик. Переводи точно, сохраняя стиль и терминологию."));
        presetRow.addView(createPresetChip("📝 Редактор", promptInput, "Исправляй грамматику и стиль, делай текст выразительным и лаконичным."));

        presetScroll.addView(presetRow);
        content.addView(presetScroll);

        // 3. Temperature & Creativity
        content.addView(label("🌡️ КРЕАТИВНОСТЬ (ТЕМПЕРАТУРА: 0.0 - 2.0):"));
        EditText tempInput = new EditText(this);
        tempInput.setText(prefs.getString("temperature", "0.7"));
        tempInput.setSingleLine(true);
        tempInput.setInputType(8194);
        tempInput.setTextColor(TEXT_PRIMARY);
        tempInput.setBackground(roundBg(PANEL_COLOR, 8));
        tempInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(tempInput);

        HorizontalScrollView tempScroll = new HorizontalScrollView(this);
        tempScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.addView(createPresetChip("0.2 (Код / Факты)", tempInput, "0.2"));
        tempRow.addView(createPresetChip("0.7 (Баланс)", tempInput, "0.7"));
        tempRow.addView(createPresetChip("1.2 (Творчество)", tempInput, "1.2"));
        tempScroll.addView(tempRow);
        content.addView(tempScroll);

        // 4. Free Models Filter Toggle
        CheckBox cbFreeOnly = new CheckBox(this);
        cbFreeOnly.setText("Показывать только бесплатные модели по умолчанию");
        cbFreeOnly.setTextColor(TEXT_PRIMARY);
        cbFreeOnly.setChecked(prefs.getBoolean("filter_free_only", true));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        cbLp.topMargin = dp(14);
        content.addView(cbFreeOnly, cbLp);

        // 5. Save Button
        Button saveBtn = new Button(this);
        saveBtn.setText("💾 Сохранить настройки");
        saveBtn.setTextColor(0xFF0E1621);
        saveBtn.setTextSize(15f);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        saveBtn.setBackground(roundBg(ACCENT_COLOR, 10));
        saveBtn.setOnClickListener(v -> {
            String key = keyInput.getText().toString().trim();
            if (key.isEmpty()) {
                toast("Пожалуйста, введите API-ключ OpenRouter");
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
                    .putBoolean("filter_free_only", cbFreeOnly.isChecked())
                    .apply();

            resetHistory();
            dialog.dismiss();
            toast("Настройки сохранены!");

            if (firstRun || !key.equals(oldKey) || allModels.isEmpty()) {
                refreshModels(!key.equals(oldKey));
            }
        });

        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(MATCH, dp(48));
        saveLp.topMargin = dp(20);
        saveLp.bottomMargin = dp(24);
        content.addView(saveBtn, saveLp);

        contentScroll.addView(content);
        root.addView(contentScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        dialog.setContentView(root);
        dialog.setCancelable(!firstRun);
        dialog.show();
    }

    private Button createPresetChip(String title, EditText target, String value) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(11f);
        b.setTextColor(TEXT_PRIMARY);
        b.setBackground(roundBg(PANEL_HOVER, 8));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(dp(30));
        b.setMinimumHeight(dp(30));
        b.setOnClickListener(v -> target.setText(value));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(0, dp(6), dp(6), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Не удалось открыть браузер");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }

    public static class ProviderInfo {
        public final int color;
        public final String tag;
        public ProviderInfo(int color, String tag) {
            this.color = color;
            this.tag = tag;
        }
    }

    public static ProviderInfo getProviderInfo(String provider) {
        String p = provider == null ? "" : provider.toLowerCase();
        if (p.contains("google")) return new ProviderInfo(0xFF4285F4, "G");
        if (p.contains("meta")) return new ProviderInfo(0xFF1877F2, "M");
        if (p.contains("deepseek")) return new ProviderInfo(0xFF8B5CF6, "DS");
        if (p.contains("anthropic")) return new ProviderInfo(0xFFD97706, "AN");
        if (p.contains("openai")) return new ProviderInfo(0xFF10A37F, "OA");
        if (p.contains("mistral")) return new ProviderInfo(0xFFF59E0B, "MS");
        if (p.contains("nvidia")) return new ProviderInfo(0xFF76B900, "NV");
        if (p.contains("qwen") || p.contains("alibaba")) return new ProviderInfo(0xFF6366F1, "QW");
        if (p.contains("microsoft")) return new ProviderInfo(0xFF00A4EF, "MS");
        if (p.contains("cohere")) return new ProviderInfo(0xFFD946EF, "CO");
        return new ProviderInfo(0xFF64748B, "AI");
    }

    public static class AppIcons {
        public static Drawable createMenuIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.10f));
            p.setStrokeCap(Paint.Cap.ROUND);

            float left = size * 0.16f;
            float right = size * 0.84f;
            c.drawLine(left, size * 0.28f, right, size * 0.28f, p);
            c.drawLine(left, size * 0.50f, right, size * 0.50f, p);
            c.drawLine(left, size * 0.72f, right, size * 0.72f, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createSparkleIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);

            float cx = size * 0.50f;
            float cy = size * 0.50f;
            float rOuter = size * 0.44f;

            Path path = new Path();
            path.moveTo(cx, cy - rOuter);
            path.quadTo(cx, cy, cx + rOuter, cy);
            path.quadTo(cx, cy, cx, cy + rOuter);
            path.quadTo(cx, cy, cx - rOuter, cy);
            path.quadTo(cx, cy, cx, cy - rOuter);
            path.close();

            c.drawPath(path, p);
            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createNewChatIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.10f));
            p.setStrokeCap(Paint.Cap.ROUND);

            float pad = size * 0.22f;
            c.drawLine(size * 0.50f, pad, size * 0.50f, size - pad, p);
            c.drawLine(pad, size * 0.50f, size - pad, size * 0.50f, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createGlobeIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.8f, size * 0.08f));
            p.setStrokeCap(Paint.Cap.ROUND);

            float cx = size * 0.50f;
            float cy = size * 0.50f;
            float r = size * 0.38f;

            c.drawCircle(cx, cy, r, p);
            c.drawLine(cx - r, cy, cx + r, cy, p);
            RectF oval = new RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r);
            c.drawOval(oval, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createChevronDown(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.12f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            Path path = new Path();
            path.moveTo(size * 0.22f, size * 0.38f);
            path.lineTo(size * 0.50f, size * 0.66f);
            path.lineTo(size * 0.78f, size * 0.38f);
            c.drawPath(path, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createSendIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            Path path = new Path();
            path.moveTo(size * 0.18f, size * 0.16f);
            path.lineTo(size * 0.88f, size * 0.50f);
            path.lineTo(size * 0.18f, size * 0.84f);
            path.lineTo(size * 0.38f, size * 0.50f);
            path.close();
            c.drawPath(path, p);
            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createAttachIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.08f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            Path path = new Path();
            path.moveTo(size * 0.50f, size * 0.60f);
            path.lineTo(size * 0.50f, size * 0.35f);
            RectF r1 = new RectF(size * 0.36f, size * 0.22f, size * 0.50f, size * 0.48f);
            path.arcTo(r1, 0, -180);
            path.lineTo(size * 0.36f, size * 0.65f);
            RectF r2 = new RectF(size * 0.36f, size * 0.52f, size * 0.64f, size * 0.78f);
            path.arcTo(r2, 180, -180);
            path.lineTo(size * 0.64f, size * 0.25f);
            c.drawPath(path, p);
            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createSettingsIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.09f));
            p.setStrokeCap(Paint.Cap.ROUND);

            Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
            pDot.setColor(color);
            pDot.setStyle(Paint.Style.FILL);

            float dotR = size * 0.12f;

            // Line 1
            c.drawLine(size * 0.15f, size * 0.28f, size * 0.85f, size * 0.28f, p);
            c.drawCircle(size * 0.38f, size * 0.28f, dotR, pDot);

            // Line 2
            c.drawLine(size * 0.15f, size * 0.50f, size * 0.85f, size * 0.50f, p);
            c.drawCircle(size * 0.68f, size * 0.50f, dotR, pDot);

            // Line 3
            c.drawLine(size * 0.15f, size * 0.72f, size * 0.85f, size * 0.72f, p);
            c.drawCircle(size * 0.32f, size * 0.72f, dotR, pDot);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createTrashIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.08f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            // Lid
            c.drawLine(size * 0.20f, size * 0.30f, size * 0.80f, size * 0.30f, p);
            // Handle
            c.drawLine(size * 0.40f, size * 0.22f, size * 0.60f, size * 0.22f, p);

            // Body
            Path body = new Path();
            body.moveTo(size * 0.26f, size * 0.34f);
            body.lineTo(size * 0.30f, size * 0.82f);
            body.lineTo(size * 0.70f, size * 0.82f);
            body.lineTo(size * 0.74f, size * 0.34f);
            c.drawPath(body, p);

            // Slots
            c.drawLine(size * 0.43f, size * 0.42f, size * 0.43f, size * 0.72f, p);
            c.drawLine(size * 0.57f, size * 0.42f, size * 0.57f, size * 0.72f, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createCopyIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.8f, size * 0.09f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            // Back sheet
            Path back = new Path();
            back.moveTo(size * 0.58f, size * 0.15f);
            back.lineTo(size * 0.22f, size * 0.15f);
            back.lineTo(size * 0.22f, size * 0.68f);
            c.drawPath(back, p);

            // Front sheet
            RectF front = new RectF(size * 0.34f, size * 0.28f, size * 0.84f, size * 0.84f);
            c.drawRoundRect(front, size * 0.08f, size * 0.08f, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createCheckIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.12f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            Path path = new Path();
            path.moveTo(size * 0.20f, size * 0.52f);
            path.lineTo(size * 0.44f, size * 0.76f);
            path.lineTo(size * 0.82f, size * 0.26f);
            c.drawPath(path, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createCloseIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.10f));
            p.setStrokeCap(Paint.Cap.ROUND);

            c.drawLine(size * 0.26f, size * 0.26f, size * 0.74f, size * 0.74f, p);
            c.drawLine(size * 0.74f, size * 0.26f, size * 0.26f, size * 0.74f, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createRefreshIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.09f));
            p.setStrokeCap(Paint.Cap.ROUND);

            RectF arcRect = new RectF(size * 0.20f, size * 0.20f, size * 0.80f, size * 0.80f);
            c.drawArc(arcRect, 45, 270, false, p);

            p.setStyle(Paint.Style.FILL);
            Path arrow = new Path();
            arrow.moveTo(size * 0.70f, size * 0.32f);
            arrow.lineTo(size * 0.86f, size * 0.22f);
            arrow.lineTo(size * 0.86f, size * 0.44f);
            arrow.close();
            c.drawPath(arrow, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createSearchIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.10f));
            p.setStrokeCap(Paint.Cap.ROUND);

            c.drawCircle(size * 0.44f, size * 0.44f, size * 0.24f, p);
            c.drawLine(size * 0.62f, size * 0.62f, size * 0.82f, size * 0.82f, p);

            return new BitmapDrawable(null, bmp);
        }

        public static Drawable createCameraIcon(int size, int color) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.8f, size * 0.09f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            RectF body = new RectF(size * 0.16f, size * 0.32f, size * 0.84f, size * 0.80f);
            c.drawRoundRect(body, size * 0.08f, size * 0.08f, p);

            c.drawCircle(size * 0.50f, size * 0.56f, size * 0.14f, p);
            c.drawLine(size * 0.30f, size * 0.24f, size * 0.42f, size * 0.24f, p);

            return new BitmapDrawable(null, bmp);
        }
    }

    public static class I18n {
        public static final String[] CODES = {"en", "ru", "es", "de", "fr", "zh", "tr", "kk", "uz", "ar"};
        public static final String[] NAMES = {"English (US)", "Русский", "Español", "Deutsch", "Français", "中文 (简体)", "Türkçe", "Қазақша", "O'zbekcha", "العربية"};
        public static final String[] FLAGS = {"🇬🇧", "🇷🇺", "🇪🇸", "🇩🇪", "🇫🇷", "🇨🇳", "🇹🇷", "🇰🇿", "🇺🇿", "🇸🇦"};

        public static String currentLang = "en";

        public static String getFlag(String code) {
            if (code == null) return "🌐";
            for (int i = 0; i < CODES.length; i++) {
                if (CODES[i].equalsIgnoreCase(code)) return FLAGS[i];
            }
            return "🌐";
        }

        public static String get(String key) {
            return get(key, currentLang);
        }

        public static String get(String key, String lang) {
            if (lang == null) lang = "en";
            switch (key) {
                case "GREETING_TITLE":
                    switch (lang) {
                        case "ru": return "Чем я могу помочь?";
                        case "es": return "¿En qué puedo ayudarte?";
                        case "de": return "Wie kann ich dir helfen?";
                        case "fr": return "Comment puis-je vous aider ?";
                        case "zh": return "今天我能为您提供什么帮助？";
                        case "tr": return "Nasıl yardımcı olabilirim?";
                        case "kk": return "Сізге қалай көмектесе аламын?";
                        case "uz": return "Qanday yordam bera olaman?";
                        case "ar": return "كيف يمكنني مساعدتك اليوم؟";
                        default: return "How can I help you today?";
                    }
                case "GREETING_SUB":
                    switch (lang) {
                        case "ru": return "Выберите модель вверху и задайте вопрос или отправьте фото";
                        case "es": return "Selecciona un modelo arriba y pregunta o envía una foto";
                        case "de": return "Wähle oben ein Modell und frage oder sende ein Bild";
                        case "fr": return "Sélectionnez un modèle ci-dessus et discutez ou envoyez une photo";
                        case "zh": return "在顶部选择模型并提问，支持发送图片";
                        case "tr": return "Yukarıdan model seçin ve soru sorun veya fotoğraf gönderin";
                        case "kk": return "Жоғарыдан модель таңдап, сұрақ қойыңыз немесе фото жіберіңіз";
                        case "uz": return "Yuqoridan modelni tanlang va savol bering yoki rasm yuboring";
                        case "ar": return "اختر نموذجًا من الأعلى واطرح سؤالاً أو أرسل صورة";
                        default: return "Select a model from the top pill and ask anything or attach an image";
                    }
                case "CHIP_CODE":
                    switch (lang) {
                        case "ru": return "💻 Напиши функцию для…";
                        case "es": return "💻 Escribe una función para…";
                        case "de": return "💻 Schreibe eine Funktion für…";
                        case "fr": return "💻 Écris une fonction pour…";
                        case "zh": return "💻 用Python编写一个函数…";
                        case "tr": return "💻 Şunun için bir fonksiyon yaz…";
                        case "kk": return "💻 Функция жаз…";
                        case "uz": return "💻 Funksiya yozib ber…";
                        case "ar": return "💻 اكتب دالة برمجية لـ…";
                        default: return "💻 Write a function to…";
                    }
                case "CHIP_EXPLAIN":
                    switch (lang) {
                        case "ru": return "💡 Объясни простыми словами…";
                        case "es": return "💡 Explica de forma sencilla…";
                        case "de": return "💡 Erkläre einfach…";
                        case "fr": return "💡 Explique simplement…";
                        case "zh": return "💡 通俗易懂地解释…";
                        case "tr": return "💡 Basitçe açıkla…";
                        case "kk": return "💡 Қарапайым тілмен түсіндір…";
                        case "uz": return "💡 Oddiy qilib tushuntir…";
                        case "ar": return "💡 اشرح ببساطة…";
                        default: return "💡 Explain simply how…";
                    }
                case "CHIP_TRANSLATE":
                    switch (lang) {
                        case "ru": return "🌍 Переведи этот текст на…";
                        case "es": return "🌍 Traduce este texto a…";
                        case "de": return "🌍 Übersetze diesen Text in…";
                        case "fr": return "🌍 Traduis ce texte en…";
                        case "zh": return "🌍 将文本翻译成…";
                        case "tr": return "🌍 Bu metni şuna çevir…";
                        case "kk": return "🌍 Мәтінді аудар…";
                        case "uz": return "🌍 Matnni tarjima qil…";
                        case "ar": return "🌍 ترجم هذا النص إلى…";
                        default: return "🌍 Translate this text to…";
                    }
                case "CHIP_SUMMARIZE":
                    switch (lang) {
                        case "ru": return "📝 Составь краткий план для…";
                        case "es": return "📝 Haz un resumen de…";
                        case "de": return "📝 Erstelle einen Plan für…";
                        case "fr": return "📝 Fais un plan rapide pour…";
                        case "zh": return "📝 总结并制定一个计划…";
                        case "tr": return "📝 Şunun için bir plan yap…";
                        case "kk": return "📝 Қысқаша жоспар құр…";
                        case "uz": return "📝 Qisqacha reja tuz…";
                        case "ar": return "📝 لخص وأعد خطة لـ…";
                        default: return "📝 Outline a quick plan for…";
                    }
                case "INPUT_HINT":
                    switch (lang) {
                        case "ru": return "Спросите что-нибудь…";
                        case "es": return "Pregunta a FreeChat…";
                        case "de": return "Frag FreeChat etwas…";
                        case "fr": return "Demandez à FreeChat…";
                        case "zh": return "向 FreeChat 提问…";
                        case "tr": return "FreeChat'e bir şey sor…";
                        case "kk": return "FreeChat-тен сұраңыз…";
                        case "uz": return "FreeChat-dan so'rang…";
                        case "ar": return "اسأل FreeChat أي شيء…";
                        default: return "Ask FreeChat anything…";
                    }
                case "NEW_CHAT":
                    switch (lang) {
                        case "ru": return "Новый чат";
                        case "es": return "Nuevo chat";
                        case "de": return "Neuer Chat";
                        case "fr": return "Nouveau chat";
                        case "zh": return "新建对话";
                        case "tr": return "Yeni sohbet";
                        case "kk": return "Жаңа чат";
                        case "uz": return "Yangi chat";
                        case "ar": return "محادثة جديدة";
                        default: return "New chat";
                    }
                case "MENU_MODELS":
                    switch (lang) {
                        case "ru": return "Каталог моделей";
                        case "es": return "Explorar modelos";
                        case "de": return "Modelle durchsuchen";
                        case "fr": return "Catalogue des modèles";
                        case "zh": return "浏览 400+ 模型";
                        case "tr": return "Modelleri keşfet";
                        case "kk": return "Модельдер каталогы";
                        case "uz": return "Modellar katalogi";
                        case "ar": return "كتالوج النмаذج";
                        default: return "Explore 400+ Models";
                    }
                case "MENU_LANG":
                    switch (lang) {
                        case "ru": return "Язык интерфейса";
                        case "es": return "Idioma de la interfaz";
                        case "de": return "Sprache";
                        case "fr": return "Langue de l'interface";
                        case "zh": return "界面语言";
                        case "tr": return "Arayüz Dili";
                        case "kk": return "Интерфейс тілі";
                        case "uz": return "Interfeys tili";
                        case "ar": return "لغة الواجهة";
                        default: return "Interface Language";
                    }
                case "MENU_SETTINGS":
                    switch (lang) {
                        case "ru": return "Настройки и API-ключ";
                        case "es": return "Ajustes y clave API";
                        case "de": return "Einstellungen & API-Key";
                        case "fr": return "Paramètres et clé API";
                        case "zh": return "设置与 API 密钥";
                        case "tr": return "Ayarlar ve API Anahtarı";
                        case "kk": return "Баптаулар және API кілті";
                        case "uz": return "Sozlamalar va API kaliti";
                        case "ar": return "الإعدادات ومفتاح API";
                        default: return "Settings & API Key";
                    }
                case "MENU_CLEAR":
                    switch (lang) {
                        case "ru": return "Очистить переписку";
                        case "es": return "Limpiar conversación";
                        case "de": return "Chat leeren";
                        case "fr": return "Effacer la discussion";
                        case "zh": return "清空当前会话";
                        case "tr": return "Sohbeti temizle";
                        case "kk": return "Чатты тазалау";
                        case "uz": return "Chatni tozalash";
                        case "ar": return "مسح المحادثة";
                        default: return "Clear conversation";
                    }
                case "MENU_GET_KEY":
                    switch (lang) {
                        case "ru": return "Получить ключ OpenRouter";
                        case "es": return "Obtener clave OpenRouter";
                        case "de": return "OpenRouter Key holen";
                        case "fr": return "Obtenir la clé OpenRouter";
                        case "zh": return "获取 OpenRouter 密钥";
                        case "tr": return "OpenRouter Anahtarı Al";
                        case "kk": return "OpenRouter кілтін алу";
                        case "uz": return "OpenRouter kalitini olish";
                        case "ar": return "الحصول على مفتاح OpenRouter";
                        default: return "Get OpenRouter API Key";
                    }
                case "ACTIVE_MODEL":
                    switch (lang) {
                        case "ru": return "Активная модель";
                        case "es": return "Modelo activo";
                        case "de": return "Aktives Modell";
                        case "fr": return "Modèle actif";
                        case "zh": return "当前选定模型";
                        case "tr": return "Aktif model";
                        case "kk": return "Белсенді модель";
                        case "uz": return "Faol model";
                        case "ar": return "النموذج النشط";
                        default: return "Active Model";
                    }
                case "ALL_MODELS":
                    switch (lang) {
                        case "ru": return "Все модели";
                        case "es": return "Todos los modelos";
                        case "de": return "Alle Modelle";
                        case "fr": return "Tous les modèles";
                        case "zh": return "全部模型";
                        case "tr": return "Tüm Modeller";
                        case "kk": return "Барлық модельдер";
                        case "uz": return "Barcha modellar";
                        case "ar": return "جميع النماذج";
                        default: return "All Models";
                    }
                case "FREE_MODELS":
                    switch (lang) {
                        case "ru": return "Бесплатные";
                        case "es": return "Gratis";
                        case "de": return "Kostenlos";
                        case "fr": return "Gratuits";
                        case "zh": return "免费";
                        case "tr": return "Ücretsiz";
                        case "kk": return "Тегін";
                        case "uz": return "Bepul";
                        case "ar": return "مجانية";
                        default: return "Free";
                    }
                case "VISION_MODELS":
                    switch (lang) {
                        case "ru": return "С фото (Vision)";
                        case "es": return "Con fotos (Vision)";
                        case "de": return "Mit Fotos (Vision)";
                        case "fr": return "Avec photos (Vision)";
                        case "zh": return "支持图像 (Vision)";
                        case "tr": return "Fotoğraflı (Vision)";
                        case "kk": return "Фотомен (Vision)";
                        case "uz": return "Surat bilan (Vision)";
                        case "ar": return "مع الصور (Vision)";
                        default: return "With Vision";
                    }
                case "SEARCH_MODELS":
                    switch (lang) {
                        case "ru": return "Поиск моделей (Gemini, Llama, DeepSeek…)";
                        case "es": return "Buscar modelos (Gemini, Llama, DeepSeek…)";
                        case "de": return "Modelle suchen (Gemini, Llama, DeepSeek…)";
                        case "fr": return "Rechercher des modèles (Gemini, Llama…)";
                        case "zh": return "搜索模型 (Gemini, Llama, DeepSeek…)";
                        case "tr": return "Model ara (Gemini, Llama, DeepSeek…)";
                        case "kk": return "Модель іздеу (Gemini, Llama, DeepSeek…)";
                        case "uz": return "Model qidirish (Gemini, Llama, DeepSeek…)";
                        case "ar": return "البحث عن النماذج (Gemini, Llama…)";
                        default: return "Search models (Gemini, Llama, DeepSeek…)";
                    }
                case "CUSTOM_MODEL":
                    switch (lang) {
                        case "ru": return "Ввести ID модели вручную";
                        case "es": return "Introducir ID de modelo manual";
                        case "de": return "Modell-ID manuell eingeben";
                        case "fr": return "Entrer l'ID manuellement";
                        case "zh": return "手动输入模型 ID";
                        case "tr": return "Model kimliğini elle gir";
                        case "kk": return "Модель ID-ін қолмен енгізу";
                        case "uz": return "Model ID-ni qo'lda kiritish";
                        case "ar": return "إدخال معرف النموذج يدويًا";
                        default: return "Enter custom model ID";
                    }
                case "CLOSE":
                    switch (lang) {
                        case "ru": return "Закрыть";
                        case "es": return "Cerrar";
                        case "de": return "Schließen";
                        case "fr": return "Fermer";
                        case "zh": return "关闭";
                        case "tr": return "Kapat";
                        case "kk": return "Жабу";
                        case "uz": return "Yopish";
                        case "ar": return "إغلاق";
                        default: return "Close";
                    }
                case "CANCEL":
                    switch (lang) {
                        case "ru": return "Отмена";
                        case "es": return "Cancelar";
                        case "de": return "Abbrechen";
                        case "fr": return "Annuler";
                        case "zh": return "取消";
                        case "tr": return "İptal";
                        case "kk": return "Бас тарту";
                        case "uz": return "Bekor qilish";
                        case "ar": return "إلغاء";
                        default: return "Cancel";
                    }
                case "SAVE":
                    switch (lang) {
                        case "ru": return "Сохранить";
                        case "es": return "Guardar";
                        case "de": return "Speichern";
                        case "fr": return "Enregistrer";
                        case "zh": return "保存";
                        case "tr": return "Kaydet";
                        case "kk": return "Сақтау";
                        case "uz": return "Saqlash";
                        case "ar": return "حفظ";
                        default: return "Save";
                    }
                case "LANG_CHANGED":
                    switch (lang) {
                        case "ru": return "Язык интерфейса изменён";
                        case "es": return "Idioma cambiado";
                        case "de": return "Sprache geändert";
                        case "fr": return "Langue modifiée";
                        case "zh": return "界面语言已切换";
                        case "tr": return "Arayüz dili değiştirildi";
                        case "kk": return "Интерфейс тілі өзгертілді";
                        case "uz": return "Interfeys tili o'zgartirildi";
                        case "ar": return "تم تغيير لغة الواجهة";
                        default: return "Interface language changed";
                    }
                default:
                    return key;
            }
        }
    }

}
