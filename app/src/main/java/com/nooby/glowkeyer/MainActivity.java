package com.nooby.glowkeyer;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ = 1;
    private WebView web;
    private ValueCallback<Uri[]> callback;
    private OutputStream out;
    private Uri outUri;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setDownloadListener((url, ua, cd, mime, len) ->
            web.evaluateJavascript("if(typeof lastBlob!=='undefined'&&lastBlob)saveBlob(lastBlob,'glow-keyed.webm')", null));
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (callback != null) callback.onReceiveValue(null);
                callback = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ);
                } catch (Exception e) {
                    callback = null;
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ && callback != null) {
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            callback = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    class Bridge {
        @JavascriptInterface
        public void begin(String name, String mime) {
            try {
                if (out != null) out.close();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                outUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                out = getContentResolver().openOutputStream(outUri);
            } catch (Exception e) {
                out = null;
            }
        }

        @JavascriptInterface
        public void chunk(String b64) {
            try {
                if (out != null) out.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                out = null;
            }
        }

        @JavascriptInterface
        public void end() {
            final boolean ok = out != null;
            try {
                if (out != null) {
                    out.close();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(outUri, v, null, null);
                }
            } catch (Exception e) {
            }
            out = null;
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    ok ? "Saved to Downloads" : "Save failed", Toast.LENGTH_LONG).show());
        }
    }
}
