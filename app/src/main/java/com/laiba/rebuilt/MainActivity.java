package com.laiba.rebuilt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.widget.SeekBar;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** LAIBA AI 3D — HUD (HTML overlay on the 3D avatar), AI chat brain, voice, and phone control. */
public class MainActivity extends Activity {
    static volatile MainActivity cur;
    static volatile boolean resumed;
    static Context appCtx;

    static final int REQ_IMAGE = 11, REQ_GLB = 12, REQ_PERM_MIC = 1, REQ_PERM_ONE = 21, REQ_PERM_ALL = 22;
    static final int BG = Color.rgb(17, 11, 31);

    FrameLayout root;
    WebView web;
    ScrollView settingsView;
    LinearLayout sbox;
    SharedPreferences prefs;
    TextToSpeech tts;
    boolean ttsReady = false;
    SpeechRecognizer rec;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<Llm.Msg> history = new ArrayList<>();
    final AtomicBoolean busy = new AtomicBoolean(false);
    boolean codeMode = false;
    String pendingImageQuestion = "";

    EditText fKey, fModel, fEndpoint;
    Spinner sProvider, sLang;
    CheckBox cSpeak, cPhone, cVision, cGround;
    TextView tAcc, tModelInfo, tVoice;
    SeekBar sbPitch, sbRate;
    CheckBox cNatural;
    Spinner sNatVoice;
    volatile int speakGen = 0;
    static final String[] NAT_VOICES = {"Kore", "Aoede", "Leda", "Zephyr", "Puck", "Charon"};

    int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }

    String get(String k, String d) { return prefs.getString(k, d); }
    void save(String k, String v) { prefs.edit().putString(k, v).apply(); }
    boolean flag(String k, boolean d) { return "true".equals(get(k, d ? "true" : "false")); }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        appCtx = getApplicationContext();
        prefs = getSharedPreferences("laiba", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.BLACK);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                applyVoice();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { ui.post(() -> { js("Laiba.speak(60000)"); hud("speaking", "true"); }); }
                    @Override public void onDone(String id) { ui.post(() -> { js("Laiba.stopSpeaking()"); hud("speaking", "false"); }); }
                    @Override public void onError(String id) { onDone(id); }
                });
            }
        });

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        buildWeb();
        buildSettings();
        setContentView(root);
        applySafeInsets(root);
    }

    @Override protected void onResume() {
        super.onResume();
        cur = this;
        resumed = true;
        refreshAccStatus();
    }

    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override protected void onDestroy() {
        if (cur == this) cur = null;
        resumed = false;
        if (rec != null) rec.destroy();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (settingsView.getVisibility() == View.VISIBLE) { closeSettings(); return; }
        super.onBackPressed();
    }

    void applySafeInsets(View v) {
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            v.requestApplyInsets();
        }
    }

    // ------------------------------------------------------------------ WebView HUD + avatar

    class Bridge {
        @JavascriptInterface public void onAvatarStatus(String s) { ui.post(() -> hud("setSub", s)); }
        @JavascriptInterface public String getBundledGlb() {
            File custom = new File(getFilesDir(), "custom_avatar.glb");
            try (InputStream in = custom.exists() ? new java.io.FileInputStream(custom) : openBundled()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception ex) { android.util.Log.e("LAIBA_WEB", "GLB asset error", ex); return ""; }
        }
        @JavascriptInterface public String getMode() { return get("avatarMode", "portrait"); }
        @JavascriptInterface public String getAsset(String name) {
            if (name == null || !name.matches("[a-z_]+\\.(jpg|png)")) return "";
            try (InputStream in = getAssets().open("avatar/" + name)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception ex) { return ""; }
        }
        @JavascriptInterface public void send(String t) { ui.post(() -> { if (t != null && !t.trim().isEmpty()) ask(t.trim(), null); }); }
        @JavascriptInterface public void mic() { ui.post(() -> listen()); }
        @JavascriptInterface public void stopAll() { ui.post(() -> MainActivity.this.stopAll()); }
        @JavascriptInterface public void feature(String name, String arg) { ui.post(() -> MainActivity.this.feature(name, arg == null ? "" : arg.trim())); }
        @JavascriptInterface public void openSettings() { ui.post(() -> showSettings()); }
    }

    InputStream openBundled() throws java.io.IOException {
        try { return getAssets().open("avatar/laiba_human_v28.glb"); }
        catch (java.io.IOException e) { return getAssets().open("avatar/layaba_realistic.glb"); }
    }

    void buildWeb() {
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(22, 14, 42));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) { android.util.Log.i("LAIBA_WEB", m.message()); return true; }
        });
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/avatar/index.html");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }

    void js(String code) { if (web != null) web.evaluateJavascript("if(window.Laiba){" + code + "}", null); }

    void hud(String fn, String arg) {
        if (web == null) return;
        boolean raw = arg.equals("true") || arg.equals("false");
        web.evaluateJavascript("if(window.HUD){HUD." + fn + "(" + (raw ? arg : JSONObject.quote(arg)) + ")}", null);
    }

    void setState(String s) { ui.post(() -> hud("setStatus", s)); }
    void showReply(String s) { ui.post(() -> hud("setReply", s)); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ brain: chat or phone agent

    static final Pattern CONTROL = Pattern.compile(
            "(?i)^\\s*(please\\s+|laiba\\s+)?(open|launch|call|dial|phone|text|sms|whatsapp|set (an? )?(alarm|timer)|turn (on|off)|switch (on|off)|enable|disable|toggle|"
          + "increase|decrease|volume|brightness|flashlight|torch|take (a )?(screenshot|photo|picture|selfie)|screenshot|go (home|back)|scroll|swipe|tap|click|press|"
          + "navigate|directions|play|pause|lock|wake me|remind me)\\b|\\b(kholo|khol do|chalao|chala do|band karo|band kar do|on karo|off karo|bhejo|bhej do|call karo|laga do|lagao|badhao|ghatao)\\b");

    String systemPrompt(boolean phone) {
        StringBuilder sb = new StringBuilder(
                "You are LAIBA, a warm, smart, multilingual AI assistant with a friendly 3D avatar. Answer ANY question helpfully and accurately: "
              + "general knowledge, study help, coding, writing, math, advice, translation and more. "
              + "Reply in the user's language; if they write Roman Hindi (Hinglish), reply in Roman Hindi. "
              + "Be concise and conversational unless detail is requested. Do not invent facts; say when you are unsure. "
              + "Avoid markdown symbols and emojis in ordinary replies (they are spoken aloud), except an occasional heart.");
        if (phone) sb.append(" You can operate the user's phone (open apps, calls, messages, settings, flashlight, volume, alarms, taps). "
                + "If, and only if, the user wants such a phone action performed, reply with exactly [[PHONE]] and nothing else. "
                + "Never claim you performed a phone action in an ordinary reply.");
        if (codeMode) sb.append(" CODE MODE: the user wants programming help. Give complete, correct code in fenced blocks with a brief explanation.");
        return sb.toString();
    }

    void ask(String q, String img) {
        Llm llm = new Llm(prefs);
        if (!llm.ready()) { showReply("Please add your API key first: tap the gear icon (top right) and open Settings."); return; }
        if (!busy.compareAndSet(false, true)) { showReply("Still working on the previous request. Tap Stop to cancel it."); return; }
        showReply("You: " + q);
        setState("Thinking…");
        final boolean phone = flag("phone", true);
        new Thread(() -> {
            String ans;
            try {
                if (img == null && phone && CONTROL.matcher(q).find()) {
                    ans = runAgent(llm, q);
                } else {
                    List<Llm.Msg> msgs;
                    synchronized (history) { msgs = new ArrayList<>(history.subList(Math.max(0, history.size() - 12), history.size())); }
                    msgs.add(new Llm.Msg("user", q, img));
                    ans = llm.complete(systemPrompt(phone), msgs, true);
                    if (img == null && phone && ans.startsWith("[[PHONE]]")) ans = runAgent(llm, q);
                }
                synchronized (history) {
                    history.add(new Llm.Msg("user", q, null));
                    history.add(new Llm.Msg("assistant", ans, null));
                    while (history.size() > 24) history.remove(0);
                }
                finish(ans, false);
            } catch (Exception ex) {
                String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                finish("Request failed: " + m, true);
            } finally { busy.set(false); }
        }).start();
    }

    String runAgent(Llm llm, String q) {
        setState("Working on your phone…");
        PhoneAgent agent = new PhoneAgent(this, llm, new PhoneAgent.Ui() {
            @Override public void log(String s) { ui.post(() -> hud("setSub", s)); }
            @Override public boolean confirm(String message) { return askConfirm(message); }
        }, flag("vision", false));
        return agent.run(q);
    }

    void finish(String ans, boolean error) {
        ui.post(() -> {
            hud("setReply", ans);
            hud("setStatus", error ? "Error" : "Ready");
            speak(ans);
        });
    }

    String cleanForSpeech(String s) {
        String t = s.replaceAll("```[\\s\\S]*?```", " code block. ").replaceAll("[*#`_~|>]", "")
                .replaceAll("[^\\p{L}\\p{N}\\p{M}\\p{P}\\p{Z}\\p{Sc}\\n+=%]", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 3800 ? t.substring(0, 3800) : t;
    }

    void applyVoice() {
        if (!ttsReady) return;
        try {
            tts.setLanguage(Locale.forLanguageTag(get("lang", "hi-IN")));
            String vn = get("voiceName", "");
            if (!vn.isEmpty() && tts.getVoices() != null) for (Voice v : tts.getVoices()) if (v.getName().equals(vn)) { tts.setVoice(v); break; }
            tts.setPitch(Float.parseFloat(get("pitch", "1.1")));
            tts.setSpeechRate(Float.parseFloat(get("rate", "1.0")));
        } catch (Exception ignored) { }
    }

    void speak(String text) {
        String t = cleanForSpeech(text);
        if (!flag("speak", true) || t.isEmpty()) { js("Laiba.speak(" + Math.min(15000, 600 + text.length() * 45) + ")"); return; }
        if (flag("natural", false) && "3".equals(get("provider", "0")) && !get("key", "").isEmpty()) { speakNatural(t); return; }
        speakLocal(t);
    }

    void speakLocal(String t) {
        if (ttsReady) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "laiba");
        else js("Laiba.speak(" + Math.min(15000, 600 + t.length() * 45) + ")");
    }

    void speakNatural(String full) {
        final String t = full.length() > 1200 ? full.substring(0, 1200) : full;
        final int gen = ++speakGen;
        final String key = get("key", "").trim(), voice = get("natVoice", "Kore");
        if (tts != null) tts.stop();
        new Thread(() -> {
            try {
                byte[] pcm = GeminiTts.synth(key, "gemini-2.5-flash-preview-tts", voice, t);
                if (gen != speakGen) return;
                ui.post(() -> { js("Laiba.speak(" + (GeminiTts.durationMs(pcm) + 400) + ")"); hud("speaking", "true"); });
                GeminiTts.play(pcm);
                ui.post(() -> { if (gen == speakGen) { js("Laiba.stopSpeaking()"); hud("speaking", "false"); } });
            } catch (Exception e) {
                String m = e.getMessage() == null ? "error" : e.getMessage();
                ui.post(() -> { if (gen == speakGen) { hud("setSub", "Natural voice unavailable (" + m + ") — using phone voice"); speakLocal(t); } });
            }
        }).start();
    }

    void stopAll() {
        PhoneAgent.stop = true;
        speakGen++;
        GeminiTts.stop();
        if (tts != null) tts.stop();
        if (rec != null) rec.cancel();
        js("Laiba.stopSpeaking()");
        hud("speaking", "false");
        hud("listening", "false");
        hud("setStatus", "Stopped");
    }

    // ------------------------------------------------------------------ confirmation for risky actions

    static boolean askConfirm(String message) {
        boolean brought = false;
        MainActivity a = cur;
        if (a == null || !resumed) {
            Intent i = new Intent(appCtx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                LaibaService s = LaibaService.inst;
                if (s != null) s.startActivity(i); else appCtx.startActivity(i);
            } catch (Exception ignored) { }
            brought = true;
            for (int k = 0; k < 40 && !resumed; k++) { try { Thread.sleep(150); } catch (InterruptedException e) { return false; } }
        }
        final MainActivity act = cur;
        if (act == null || !resumed) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] allowed = {false};
        final boolean wasBrought = brought;
        act.runOnUiThread(() -> new AlertDialog.Builder(act).setTitle("Allow this action?").setMessage(message).setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> { allowed[0] = true; latch.countDown(); })
                .setNegativeButton("Deny", (d, w) -> latch.countDown()).show());
        try { if (!latch.await(90, TimeUnit.SECONDS)) allowed[0] = false; } catch (InterruptedException e) { return false; }
        if (wasBrought) { act.runOnUiThread(() -> act.moveTaskToBack(true)); try { Thread.sleep(900); } catch (InterruptedException ignored) { } }
        return allowed[0];
    }

    static void requestPerm(String perm) {
        final MainActivity a = cur;
        if (a != null && resumed) a.runOnUiThread(() -> a.requestPermissions(new String[]{perm}, REQ_PERM_ONE));
    }

    // ------------------------------------------------------------------ voice

    void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { showReply("Speech recognition is not available on this phone."); return; }
        if (tts != null) tts.stop();
        if (rec != null) rec.destroy();
        rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { hud("setStatus", "Listening…"); hud("listening", "true"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float r) { hud("level", String.valueOf(Math.max(0f, Math.min(1f, (r + 2f) / 12f))).replace(',', '.')); }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { hud("listening", "false"); }
            @Override public void onError(int e) {
                hud("listening", "false");
                hud("setStatus", e == 7 ? "Didn't catch that — tap the mic" : e == 6 ? "Speech timeout" : e == 9 ? "Mic permission denied" : "Mic error " + e);
            }
            @Override public void onResults(Bundle b) {
                hud("listening", "false");
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) ask(r.get(0), null);
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) hud("setInput", r.get(0));
            }
            @Override public void onEvent(int e, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, get("lang", "hi-IN"));
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        rec.startListening(i);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) listen();
        if (code == REQ_PERM_ALL) toast("Permissions updated");
    }

    // ------------------------------------------------------------------ HUD buttons

    void feature(String name, String arg) {
        switch (name) {
            case "home": js("Laiba.command('full')"); break;
            case "sparkle": synchronized (history) { history.clear(); } toast("New conversation started"); break;
            case "mic": listen(); break;
            case "avatar": avatarMenu(); break;
            case "tools": toolsMenu(); break;
            case "chat": hud("focusInput", "true"); break;
            case "vision": screenVision(arg); break;
            case "image": pendingImageQuestion = arg; pickImage(); break;
            case "video": {
                PhoneAgent pa = new PhoneAgent(this, null, null, false);
                if (arg.isEmpty()) { try { pa.exec(new JSONObject().put("action", "open_app").put("app", "YouTube")); } catch (Exception e) { toast("YouTube not found"); } }
                else pa.go(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(arg))));
                break;
            }
            case "code":
                codeMode = !codeMode;
                hud("setActive", "code");
                hud("setSub", codeMode ? "Code mode ON — ask for code" : "Code mode OFF");
                break;
            default: toast(name);
        }
    }

    void screenVision(String q) {
        LaibaService s = LaibaService.inst;
        if (s == null) { showReply("To look at your screen, enable LAIBA under Settings → Accessibility (gear icon → Enable phone control)."); return; }
        if (Build.VERSION.SDK_INT < 30) { showReply("Screen vision needs Android 11 or newer."); return; }
        hud("setSub", "Looking at your screen in 4 seconds — switch to what you want me to see");
        final String question = q.isEmpty() ? "Describe what is on this screen and point out anything important." : q;
        ui.postDelayed(() -> new Thread(() -> {
            String img = s.shot();
            ui.post(() -> { if (img == null) showReply("I could not capture the screen (some apps block screenshots)."); else ask(question, img); });
        }).start(), 4000);
    }

    void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Choose an image"), REQ_IMAGE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMAGE) {
            new Thread(() -> {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > 1400) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    Bitmap bmp;
                    try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
                    if (bmp == null) throw new Exception("not an image");
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo);
                    String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                    String q = pendingImageQuestion.isEmpty() ? "Describe this image and read any text in it." : pendingImageQuestion;
                    ui.post(() -> ask(q, b64));
                } catch (Exception e) { showReply("Could not read that image: " + e.getMessage()); }
            }).start();
        } else if (req == REQ_GLB) {
            new Thread(() -> {
                File tmp = new File(getFilesDir(), "custom_avatar.tmp");
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) { total += n; if (total > 80L * 1024 * 1024) throw new Exception("file larger than 80 MB"); out.write(buf, 0, n); }
                    out.flush();
                    byte[] head = new byte[4];
                    try (InputStream chk = new java.io.FileInputStream(tmp)) { if (chk.read(head) != 4 || head[0] != 'g' || head[1] != 'l' || head[2] != 'T' || head[3] != 'F') throw new Exception("not a binary glTF (.glb) file"); }
                    File dst = new File(getFilesDir(), "custom_avatar.glb");
                    if (dst.exists()) dst.delete();
                    if (!tmp.renameTo(dst)) throw new Exception("could not store the model");
                    save("avatarMode", "glb");
                    ui.post(() -> { toast("Avatar imported — reloading"); web.loadUrl("file:///android_asset/avatar/index.html"); });
                } catch (Exception e) { tmp.delete(); ui.post(() -> toast("Import failed: " + e.getMessage())); }
            }).start();
        }
    }

    void avatarMenu() {
        new AlertDialog.Builder(this).setTitle("Avatar").setItems(new String[]{"Realistic portrait avatar (default)", "3D model avatar (stylized / imported)", "Import my own 3D model (.glb)", "Full view", "Face view"}, (d, n) -> {
            if (n == 0) { save("avatarMode", "portrait"); web.loadUrl("file:///android_asset/avatar/index.html"); }
            else if (n == 1) { save("avatarMode", "glb"); web.loadUrl("file:///android_asset/avatar/index.html"); }
            else if (n == 2) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Choose a .glb model"), REQ_GLB);
            } else js("Laiba.command('" + (n == 3 ? "full" : "face") + "')");
        }).show();
    }

    void resetAvatar() {
        File f = new File(getFilesDir(), "custom_avatar.glb");
        if (f.exists()) f.delete();
        save("avatarMode", "portrait");
        web.loadUrl("file:///android_asset/avatar/index.html");
        toast("Realistic portrait avatar restored");
    }

    void toolsMenu() {
        final String[] names = {"Flashlight on", "Flashlight off", "Take screenshot", "Notifications", "Quick settings", "Wi-Fi settings", "Bluetooth settings", "Volume up", "Volume down", "Lock screen", "Go home", "Phone control setup…"};
        final String[] acts = {
                "{\"action\":\"flashlight\",\"on\":true}", "{\"action\":\"flashlight\",\"on\":false}", "{\"action\":\"screenshot\"}", "{\"action\":\"notifications\"}",
                "{\"action\":\"quick_settings\"}", "{\"action\":\"settings\",\"page\":\"wifi\"}", "{\"action\":\"settings\",\"page\":\"bluetooth\"}",
                "{\"action\":\"volume\",\"dir\":\"up\"}", "{\"action\":\"volume\",\"dir\":\"down\"}", "{\"action\":\"lock\"}", "{\"action\":\"home\"}", ""};
        new AlertDialog.Builder(this).setTitle("Phone tools").setItems(names, (d, n) -> {
            if (acts[n].isEmpty()) { showSettings(); return; }
            new Thread(() -> {
                try {
                    String r = new PhoneAgent(this, null, null, false).exec(new JSONObject(acts[n]));
                    ui.post(() -> hud("setSub", r));
                } catch (Exception e) { ui.post(() -> hud("setSub", "Failed: " + e.getMessage())); }
            }).start();
        }).show();
    }

    // ------------------------------------------------------------------ settings overlay

    GradientDrawable panel(int top, int bottom, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);
        return d;
    }

    TextView label(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(8), dp(4), dp(4));
        sbox.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    Button btn(String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xfff8efff);
        b.setBackground(panel(0xff43205f, 0xff21132f, 0xffa85bff, 14));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(6), 0, dp(2));
        sbox.addView(b, lp);
        return b;
    }

    EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xffa79bb8);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        sbox.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(Color.WHITE);
        c.setChecked(on);
        sbox.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    void buildSettings() {
        settingsView = new ScrollView(this);
        settingsView.setBackgroundColor(BG);
        settingsView.setVisibility(View.GONE);
        sbox = new LinearLayout(this);
        sbox.setOrientation(LinearLayout.VERTICAL);
        sbox.setPadding(dp(16), dp(12), dp(16), dp(24));
        settingsView.addView(sbox);
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));

        btn("← Back to LAIBA", this::closeSettings);
        label("Brain (AI provider)", 20, Color.WHITE);
        sProvider = new Spinner(this);
        sProvider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"DeepSeek", "OpenAI", "Custom OpenAI-compatible", "Google Gemini"}));
        sProvider.setSelection(Math.min(3, Math.max(0, Integer.parseInt(get("provider", "0")))));
        sbox.addView(sProvider, new LinearLayout.LayoutParams(-1, -2));
        fKey = field("API key", get("key", ""), true);
        fModel = field("Model", get("model", "deepseek-chat"), false);
        fEndpoint = field("API base URL", get("endpoint", "https://api.deepseek.com"), false);
        sProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> a) { }
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos == 3 && !get("provider", "0").equals("3")) { fModel.setText("gemini-2.5-flash"); fEndpoint.setText("https://generativelanguage.googleapis.com/v1beta"); }
                else if (pos == 0 && fModel.getText().toString().startsWith("gemini-")) { fModel.setText("deepseek-chat"); fEndpoint.setText("https://api.deepseek.com"); }
                else if (pos == 1 && (fModel.getText().toString().startsWith("gemini-") || fModel.getText().toString().startsWith("deepseek"))) { fModel.setText("gpt-4o-mini"); fEndpoint.setText("https://api.openai.com/v1"); }
            }
        });
        btn("Load available Gemini models", this::loadGeminiModels);
        tModelInfo = label("Gemini: tap the button above to list models your key supports.", 13, 0xffcfa9ff);
        cGround = check("Use Google Search for up-to-date answers (Gemini only)", flag("grounding", true));
        cVision = check("Let the AI see screenshots while controlling the phone (sends screen images to your AI provider)", flag("vision", false));

        label("Language", 18, Color.WHITE);
        sLang = new Spinner(this);
        sLang.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Hindi", "English (India)", "Urdu"}));
        sLang.setSelection(Math.max(0, Arrays.asList("hi-IN", "en-IN", "ur-PK").indexOf(get("lang", "hi-IN"))));
        sbox.addView(sLang, new LinearLayout.LayoutParams(-1, -2));
        cSpeak = check("Speak replies aloud", flag("speak", true));

        label("Voice", 18, Color.WHITE);
        tVoice = label("Phone voice: " + (get("voiceName", "").isEmpty() ? "system default" : get("voiceName", "")), 13, 0xffcfa9ff);
        btn("Choose phone voice…", this::chooseVoice);
        label("Pitch (higher = more feminine)", 13, 0xffcfa9ff);
        sbPitch = new SeekBar(this);
        sbPitch.setMax(100);
        sbPitch.setProgress(Math.round((Float.parseFloat(get("pitch", "1.1")) - 0.5f) * 100f / 1.5f));
        sbox.addView(sbPitch, new LinearLayout.LayoutParams(-1, -2));
        label("Speed", 13, 0xffcfa9ff);
        sbRate = new SeekBar(this);
        sbRate.setMax(100);
        sbRate.setProgress(Math.round((Float.parseFloat(get("rate", "1.0")) - 0.5f) * 100f / 1.5f));
        sbox.addView(sbRate, new LinearLayout.LayoutParams(-1, -2));
        cNatural = check("Natural voice with Gemini speech (Gemini provider only, uses your quota)", flag("natural", false));
        sNatVoice = new Spinner(this);
        sNatVoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, NAT_VOICES));
        sNatVoice.setSelection(Math.max(0, Arrays.asList(NAT_VOICES).indexOf(get("natVoice", "Kore"))));
        sbox.addView(sNatVoice, new LinearLayout.LayoutParams(-1, -2));
        btn("Test voice", () -> { applyVoiceFromUi(); speak("Hello! Main Laiba hoon. Aap kaise hain?"); });

        label("Phone control", 20, Color.WHITE);
        cPhone = check("Allow LAIBA to control my phone", flag("phone", true));
        tAcc = label("", 13, 0xffcfa9ff);
        btn("Enable phone control (Accessibility)", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btn("Grant permissions (mic, contacts, calls, SMS)", () -> requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS}, REQ_PERM_ALL));
        btn("Allow changing brightness", () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
        label("Calls, SMS and Send/Pay/Delete-type taps always ask for your confirmation first. Only enable Accessibility if you trust this app: it can read and tap whatever is on your screen.", 12, 0xffb6a0cb);

        label("3D avatar", 20, Color.WHITE);
        btn("Import my own realistic avatar (.glb)", () -> { closeSettings(); avatarMenu(); });
        btn("Use realistic portrait avatar", this::resetAvatar);

        label("Privacy: your API key is stored only on this phone (not encrypted). Messages and any images you send go to the AI provider you choose.", 12, 0xffb6a0cb);
        btn("Save and return", this::closeSettings);
    }

    void saveVoiceUi() {
        save("pitch", String.valueOf(0.5f + sbPitch.getProgress() * 1.5f / 100f));
        save("rate", String.valueOf(0.5f + sbRate.getProgress() * 1.5f / 100f));
        save("natural", cNatural.isChecked() ? "true" : "false");
        save("natVoice", NAT_VOICES[Math.max(0, sNatVoice.getSelectedItemPosition())]);
        save("speak", cSpeak.isChecked() ? "true" : "false");
        save("provider", "" + sProvider.getSelectedItemPosition());
        save("key", fKey.getText().toString().trim());
    }

    void applyVoiceFromUi() { saveVoiceUi(); applyVoice(); }

    void chooseVoice() {
        if (!ttsReady || tts.getVoices() == null) { toast("Text-to-speech is not ready"); return; }
        String lang = Locale.forLanguageTag(get("lang", "hi-IN")).getLanguage();
        final ArrayList<Voice> list = new ArrayList<>();
        for (Voice v : tts.getVoices()) if (v.getLocale().getLanguage().equals(lang)) list.add(v);
        if (list.isEmpty()) { toast("No installed voices for this language. Install more in Android Settings > Text-to-speech."); return; }
        java.util.Collections.sort(list, (x, y) -> x.getName().compareTo(y.getName()));
        final String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName() + (list.get(i).isNetworkConnectionRequired() ? " (online)" : "");
        new AlertDialog.Builder(this).setTitle("Pick a voice (tap to hear)").setItems(names, (d, n) -> {
            save("voiceName", list.get(n).getName());
            tVoice.setText("Phone voice: " + list.get(n).getName());
            applyVoiceFromUi();
            speak("Hello! Main Laiba hoon.");
        }).setNegativeButton("Cancel", null).show();
    }

    void refreshAccStatus() {
        if (tAcc != null) tAcc.setText(LaibaService.inst != null ? "● Accessibility: ON — screen reading and tapping available" : "○ Accessibility: OFF — turn on LAIBA in Accessibility settings for taps/typing/screen vision");
    }

    void showSettings() {
        refreshAccStatus();
        settingsView.setVisibility(View.VISIBLE);
    }

    void closeSettings() {
        save("provider", "" + sProvider.getSelectedItemPosition());
        save("key", fKey.getText().toString().trim());
        save("model", fModel.getText().toString().trim());
        save("endpoint", fEndpoint.getText().toString().trim());
        save("lang", new String[]{"hi-IN", "en-IN", "ur-PK"}[Math.max(0, sLang.getSelectedItemPosition())]);
        save("speak", cSpeak.isChecked() ? "true" : "false");
        save("phone", cPhone.isChecked() ? "true" : "false");
        save("vision", cVision.isChecked() ? "true" : "false");
        save("grounding", cGround.isChecked() ? "true" : "false");
        saveVoiceUi();
        applyVoice();
        settingsView.setVisibility(View.GONE);
    }

    void loadGeminiModels() {
        if (sProvider.getSelectedItemPosition() != 3) { tModelInfo.setText("Select Google Gemini first."); return; }
        final String token = fKey.getText().toString().trim();
        if (token.isEmpty()) { tModelInfo.setText("Enter your Gemini API key first."); return; }
        tModelInfo.setText("Loading models…");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100").openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(20000);
                c.setRequestProperty("x-goog-api-key", token);
                int code = c.getResponseCode();
                String raw = Llm.read(c, code);
                if (code != 200) throw new Exception("HTTP " + code + ": " + Llm.detail(raw));
                JSONArray models = new JSONObject(raw).optJSONArray("models");
                ArrayList<String> names = new ArrayList<>();
                if (models != null) for (int i = 0; i < models.length(); i++) {
                    JSONObject m = models.getJSONObject(i);
                    JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                    boolean ok = false;
                    if (methods != null) for (int j = 0; j < methods.length(); j++) if ("generateContent".equals(methods.optString(j))) ok = true;
                    String nm = m.optString("name", "");
                    if (ok && nm.startsWith("models/")) names.add(nm.substring(7));
                }
                ui.post(() -> {
                    if (names.isEmpty()) { tModelInfo.setText("No usable models returned. Check API access."); return; }
                    tModelInfo.setText("Models available: " + names.size());
                    String[] opts = names.toArray(new String[0]);
                    new AlertDialog.Builder(this).setTitle("Select Gemini model").setItems(opts, (d, w) -> {
                        fModel.setText(opts[w]);
                        fEndpoint.setText("https://generativelanguage.googleapis.com/v1beta");
                        tModelInfo.setText("Selected: " + opts[w]);
                    }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception ex) { String m = ex.getMessage(); ui.post(() -> tModelInfo.setText("Model list failed: " + m)); }
            finally { if (c != null) c.disconnect(); }
        }).start();
    }
}
