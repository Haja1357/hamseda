package com.hamseda.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.print.PrintHelper;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * هم‌صدا for Android: the whole web app ships inside the APK (assets/), so it works
 * with no website and no internet. The page talks to this class through window.HamsedaAndroid.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final String OLD_WEBVIEW_URL = "https://" + HOST + "/assets/old.html";
    private static final int MIN_CHROME = 80;
    private static final int REQ_FILE = 41;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    private TextToSpeech tts;
    private volatile boolean persianVoice = false;
    private List<TextToSpeech.EngineInfo> engines;
    private int engineIndex = -1;
    private int utterance = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFile(callback, params);
            }
        });

        web.addJavascriptInterface(new Bridge(), "HamsedaAndroid");

        int chrome = chromeMajor(s.getUserAgentString());
        web.loadUrl(chrome == 0 || chrome >= MIN_CHROME ? START_URL : OLD_WEBVIEW_URL);

        startVoice();
    }

    /* ---------- back button: the page decides; the child's board never closes ---------- */
    @Override
    public void onBackPressed() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript(
                "(function(){try{return window.hamsedaBack?window.hamsedaBack():'exit';}catch(e){return 'exit';}})()",
                value -> {
                    if (value != null && value.contains("handled")) return;
                    if (web != null && web.canGoBack()) web.goBack();
                    else finish();
                });
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.shutdown(); tts = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    /* ---------- photos and recordings from the page's file inputs ---------- */
    private boolean chooseFile(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        cameraUri = null;
        String[] types = params.getAcceptTypes();
        String accept = (types != null && types.length > 0 && types[0] != null) ? types[0] : "";

        Intent intent = null;
        if (params.isCaptureEnabled() && accept.startsWith("audio")) {
            intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
        } else if (params.isCaptureEnabled() && accept.startsWith("image")) {
            intent = cameraIntent();
        }
        if (intent == null) intent = params.createIntent();

        try {
            startActivityForResult(intent, REQ_FILE);
        } catch (ActivityNotFoundException e) {
            cameraUri = null;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e2) {
                fileCallback = null;
                callback.onReceiveValue(null);
            }
        }
        return true;
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return i;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                result = new Uri[]{data.getClipData().getItemAt(0).getUri()};
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraUri = null;
    }

    /* ---------- Persian voice: use any installed text-to-speech engine that speaks Persian ---------- */
    private void startVoice() {
        tts = new TextToSpeech(this, this::onVoiceInit);
    }

    private void onVoiceInit(int status) {
        if (tts == null) return;
        if (status == TextToSpeech.SUCCESS && speaksPersian(tts)) {
            voiceReady();
            return;
        }
        if (engines == null) {
            try { engines = tts.getEngines(); } catch (Exception e) { engines = new ArrayList<>(); }
        }
        tryNextEngine();
    }

    private boolean speaksPersian(TextToSpeech t) {
        try {
            Locale[] candidates = {new Locale("fa", "IR"), new Locale("fa")};
            for (Locale l : candidates) {
                if (t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) {
                    t.setLanguage(l);
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void tryNextEngine() {
        if (tts != null) { tts.shutdown(); tts = null; }
        engineIndex++;
        if (engines == null || engineIndex >= engines.size()) {
            persianVoice = false;
            js("window.hamsedaVoiceReady && window.hamsedaVoiceReady(false)");
            return;
        }
        tts = new TextToSpeech(this, this::onVoiceInit, engines.get(engineIndex).name);
    }

    private void voiceReady() {
        persianVoice = true;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { speechDone(); }
            @Override public void onError(String id) { speechDone(); }
            @Override public void onStop(String id, boolean interrupted) { speechDone(); }
        });
        js("window.hamsedaVoiceReady && window.hamsedaVoiceReady(true)");
    }

    private void speechDone() {
        js("window.hamsedaSpeechDone && window.hamsedaSpeechDone()");
    }

    /* ---------- helpers ---------- */
    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void openExternal(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
    }

    private static int chromeMajor(String userAgent) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(userAgent == null ? "" : userAgent);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return 0; }
    }

    /* ---------- what the page can ask for ---------- */
    private class Bridge {

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public boolean ttsAvailable() {
            return persianVoice;
        }

        /* the adult area's exit button (the child's board never closes by itself) */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> finish());
        }

        @JavascriptInterface
        public void speak(final String text, final float rate) {
            runOnUiThread(() -> {
                if (tts == null || !persianVoice) { speechDone(); return; }
                tts.setSpeechRate(rate > 0 ? rate : 1f);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "h" + (++utterance));
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }

        /** Saves to Download/Hamseda (Android 10+); on older phones opens the share sheet. */
        @JavascriptInterface
        public String saveFile(String name, String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, type);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hamseda");
                    ContentResolver resolver = getContentResolver();
                    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "error";
                    try (OutputStream out = resolver.openOutputStream(uri)) {
                        if (out == null) return "error";
                        out.write(bytes);
                    }
                    return "saved";
                }
                File dir = new File(getCacheDir(), "shared");
                if (!dir.exists() && !dir.mkdirs()) return "error";
                File f = new File(dir, safe);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                final Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(type);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try { startActivity(Intent.createChooser(send, safe)); } catch (Exception ignored) { }
                });
                return "shared";
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public void printImage(String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;
                runOnUiThread(() -> {
                    PrintHelper helper = new PrintHelper(MainActivity.this);
                    helper.setScaleMode(PrintHelper.SCALE_MODE_FIT);
                    helper.printBitmap(getString(R.string.app_name), bitmap);
                });
            } catch (Exception ignored) { }
        }
    }
}
