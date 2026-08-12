package com.elevenplay.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.elevenplay.app.auth.GoogleAuthManager;
import com.elevenplay.app.bridge.NativeAuthBridge;
import com.elevenplay.app.config.AppConfig;
import com.elevenplay.app.web.DownloadController;
import com.elevenplay.app.web.ElevenPlayWebChromeClient;
import com.elevenplay.app.web.ElevenPlayWebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/* =========================================================
   11PLAY — MAIN ACTIVITY

   Responsibilities:
   - Load official 11Play website inside WebView
   - Configure secure modern WebView settings
   - Enable JavaScript / DOM storage / cookies
   - Support tawk.to Live Chat
   - Support screenshot/photo/video/file upload
   - Support website downloads
   - Handle legacy Android download permission
   - Attach secure native authentication bridge
   - Inject native-auth-bridge.js at document start
   - Preserve WebView navigation state
   - Handle Android back navigation

   Official website:
       https://11play.github.io/11play/

========================================================= */

public final class MainActivity
        extends Activity
        implements
        ElevenPlayWebChromeClient.FileChooserDelegate {

    /* =====================================================
       REQUEST CODES
    ===================================================== */

    private static final int
            REQUEST_FILE_CHOOSER =
            1101;

    private static final int
            REQUEST_LEGACY_STORAGE_PERMISSION =
            1102;


    /* =====================================================
       FILE SELECTION LIMIT
    ===================================================== */

    private static final int
            MAX_SELECTED_FILES =
            20;


    /* =====================================================
       VIEWS
    ===================================================== */

    private WebView webView;

    private ProgressBar pageProgress;


    /* =====================================================
       CONTROLLERS
    ===================================================== */

    private DownloadController
            downloadController;

    private GoogleAuthManager
            googleAuthManager;

    private NativeAuthBridge
            nativeAuthBridge;


    /* =====================================================
       FILE CHOOSER STATE
    ===================================================== */

    private ValueCallback<Uri[]>
            activeFileCallback =
            null;


    /* =====================================================
       ACTIVITY CREATE
    ===================================================== */

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(
                savedInstanceState
        );

        setContentView(
                R.layout.activity_main
        );


        /* =============================================
           FIND VIEWS
        ============================================== */

        webView =
                findViewById(
                        R.id.webView
                );

        pageProgress =
                findViewById(
                        R.id.pageProgress
                );


        /* =============================================
           INITIALIZE CONTROLLERS
        ============================================== */

        downloadController =
                new DownloadController(
                        this
                );

        googleAuthManager =
                new GoogleAuthManager(
                        this
                );

        nativeAuthBridge =
                new NativeAuthBridge(
                        webView,
                        googleAuthManager
                );


        /* =============================================
           CONFIGURE WEBVIEW
        ============================================== */

        configureWebView();

        configureWebViewClients();

        configureDownloads();


        /* =============================================
           NATIVE AUTH BRIDGE

           Must be attached BEFORE page load.
        ============================================== */

        nativeAuthBridge.attach();


        /* =============================================
           NATIVE AUTH JAVASCRIPT

           Must be registered BEFORE loadUrl/restoreState.
        ============================================== */

        installNativeAuthJavaScript();


        /* =============================================
           LOAD / RESTORE WEBSITE
        ============================================== */

        restoreOrLoadWebsite(
                savedInstanceState
        );
    }


    /* =====================================================
       WEBVIEW SETTINGS
    ===================================================== */

    private void configureWebView() {

        WebSettings settings =
                webView.getSettings();


        /* =============================================
           JAVASCRIPT
        ============================================== */

        settings.setJavaScriptEnabled(
                AppConfig.JAVASCRIPT_ENABLED
        );

        settings.setJavaScriptCanOpenWindowsAutomatically(
                true
        );


        /* =============================================
           STORAGE
        ============================================== */

        settings.setDomStorageEnabled(
                AppConfig.DOM_STORAGE_ENABLED
        );

        settings.setDatabaseEnabled(
                AppConfig.DATABASE_STORAGE_ENABLED
        );


        /* =============================================
           FILE / CONTENT ACCESS

           Direct file:// access is disabled.

           content:// access is required for selected
           Android files uploaded through WebView.
        ============================================== */

        settings.setAllowFileAccess(
                AppConfig.ALLOW_FILE_ACCESS
        );

        settings.setAllowContentAccess(
                AppConfig.ALLOW_CONTENT_ACCESS
        );


        /* =============================================
           MIXED CONTENT

           Main WebView remains HTTPS-only.
        ============================================== */

        if (
                AppConfig.ALLOW_MIXED_CONTENT
        ) {

            settings.setMixedContentMode(
                    WebSettings
                            .MIXED_CONTENT_COMPATIBILITY_MODE
            );

        } else {

            settings.setMixedContentMode(
                    WebSettings
                            .MIXED_CONTENT_NEVER_ALLOW
            );
        }


        /* =============================================
           MEDIA

           Allows hosted chat/media content to play
           without unnecessary WebView restrictions.
        ============================================== */

        settings.setMediaPlaybackRequiresUserGesture(
                AppConfig
                        .MEDIA_PLAYBACK_REQUIRES_GESTURE
        );


        /* =============================================
           DISPLAY
        ============================================== */

        settings.setLoadsImagesAutomatically(
                true
        );

        settings.setUseWideViewPort(
                true
        );

        settings.setLoadWithOverviewMode(
                false
        );

        settings.setSupportZoom(
                false
        );

        settings.setBuiltInZoomControls(
                false
        );

        settings.setDisplayZoomControls(
                false
        );

        settings.setTextZoom(
                100
        );


        /* =============================================
           CACHE
        ============================================== */

        settings.setCacheMode(
                WebSettings.LOAD_DEFAULT
        );


        /* =============================================
           MULTIPLE WINDOWS

           External navigation is controlled by our
           WebViewClient instead of arbitrary child
           WebViews.
        ============================================== */

        settings.setSupportMultipleWindows(
                false
        );


        /* =============================================
           CHARACTER ENCODING
        ============================================== */

        settings.setDefaultTextEncodingName(
                "UTF-8"
        );


        /* =============================================
           USER AGENT
        ============================================== */

        String currentUserAgent =
                settings.getUserAgentString();

        String suffix =
                AppConfig.USER_AGENT_SUFFIX;

        if (
                currentUserAgent != null &&
                suffix != null &&
                !suffix.trim().isEmpty() &&
                !currentUserAgent.contains(
                        suffix.trim()
                )
        ) {

            settings.setUserAgentString(
                    currentUserAgent +
                    suffix
            );
        }


        /* =============================================
           COOKIES

           Third-party cookies are required by some
           hosted integrations such as Live Chat.
        ============================================== */

        CookieManager cookieManager =
                CookieManager.getInstance();

        cookieManager.setAcceptCookie(
                AppConfig.COOKIES_ENABLED
        );

        cookieManager.setAcceptThirdPartyCookies(
                webView,
                AppConfig
                        .THIRD_PARTY_COOKIES_ENABLED
        );


        /* =============================================
           DEBUGGING

           Enabled only for debug builds.
        ============================================== */

        WebView.setWebContentsDebuggingEnabled(
                BuildConfig.DEBUG
        );
    }


    /* =====================================================
       WEBVIEW CLIENTS
    ===================================================== */

    private void configureWebViewClients() {

        webView.setWebViewClient(
                new ElevenPlayWebViewClient(
                        this
                )
        );


        webView.setWebChromeClient(
                new ElevenPlayWebChromeClient(
                        pageProgress,
                        this
                )
        );
    }


    /* =====================================================
       DOWNLOAD HANDLING
    ===================================================== */

    private void configureDownloads() {

        webView.setDownloadListener(
                (
                        url,
                        userAgent,
                        contentDisposition,
                        mimeType,
                        contentLength
                ) -> {

                    DownloadController.DownloadResult
                            result =
                            downloadController
                                    .startDownload(
                                            url,
                                            userAgent,
                                            contentDisposition,
                                            mimeType
                                    );

                    handleDownloadResult(
                            result
                    );
                }
        );
    }


    /* =====================================================
       DOWNLOAD RESULT
    ===================================================== */

    private void handleDownloadResult(
            DownloadController.DownloadResult result
    ) {

        if (
                result ==
                        null
        ) {

            showToast(
                    R.string.download_failed
            );

            return;
        }


        switch (result) {

            case STARTED:

                showToast(
                        R.string.download_started
                );

                break;


            case PERMISSION_REQUIRED:

                requestLegacyDownloadPermission();

                break;


            case INVALID_URL:

            case FAILED:

            default:

                showToast(
                        R.string.download_failed
                );

                break;
        }
    }


    /* =====================================================
       LEGACY DOWNLOAD PERMISSION

       Android 9 and below only.
    ===================================================== */

    private void requestLegacyDownloadPermission() {

        if (
                !downloadController
                        .requiresLegacyStoragePermission()
        ) {

            handleDownloadResult(
                    downloadController
                            .retryPendingDownload()
            );

            return;
        }


        if (
                downloadController
                        .hasLegacyStoragePermission()
        ) {

            handleDownloadResult(
                    downloadController
                            .retryPendingDownload()
            );

            return;
        }


        requestPermissions(
                new String[] {
                        Manifest.permission
                                .WRITE_EXTERNAL_STORAGE
                },
                REQUEST_LEGACY_STORAGE_PERMISSION
        );
    }


    /* =====================================================
       PERMISSION RESULT
    ===================================================== */

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );


        if (
                requestCode !=
                        REQUEST_LEGACY_STORAGE_PERMISSION
        ) {
            return;
        }


        boolean granted =
                grantResults != null &&
                grantResults.length > 0 &&
                grantResults[0] ==
                        PackageManager.PERMISSION_GRANTED;


        if (granted) {

            handleDownloadResult(
                    downloadController
                            .retryPendingDownload()
            );

        } else {

            downloadController
                    .clearPendingDownload();

            showToast(
                    R.string.download_failed
            );
        }
    }


    /* =====================================================
       FILE CHOOSER

       Called by ElevenPlayWebChromeClient when the website
       or tawk.to Live Chat opens:

           <input type="file">

       Android system picker can provide:
       - Screenshot
       - Photo
       - Video
       - Document
       - Other supported files
    ===================================================== */

    @Override
    public boolean openFileChooser(
            ValueCallback<Uri[]> filePathCallback,
            WebChromeClient.FileChooserParams
                    fileChooserParams
    ) {

        if (
                filePathCallback ==
                        null
        ) {
            return false;
        }


        /* =============================================
           CANCEL PREVIOUS UNFINISHED REQUEST
        ============================================== */

        cancelActiveFileChooser();


        activeFileCallback =
                filePathCallback;


        try {

            Intent pickerIntent;


            if (
                    fileChooserParams !=
                            null
            ) {

                pickerIntent =
                        fileChooserParams
                                .createIntent();


                if (
                        pickerIntent ==
                                null
                ) {

                    throw new ActivityNotFoundException(
                            "File picker intent is unavailable."
                    );
                }


                if (
                        fileChooserParams.getMode() ==
                                WebChromeClient
                                        .FileChooserParams
                                        .MODE_OPEN_MULTIPLE
                ) {

                    pickerIntent.putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            true
                    );
                }

            } else {

                pickerIntent =
                        createFallbackFilePickerIntent();
            }


            startActivityForResult(
                    pickerIntent,
                    REQUEST_FILE_CHOOSER
            );


            return true;

        } catch (
                ActivityNotFoundException error
        ) {

            cancelActiveFileChooser();

            showToast(
                    R.string.file_picker_error
            );

            return false;

        } catch (
                Exception error
        ) {

            cancelActiveFileChooser();

            showToast(
                    R.string.file_picker_error
            );

            return false;
        }
    }


    /* =====================================================
       FALLBACK FILE PICKER
    ===================================================== */

    private Intent createFallbackFilePickerIntent() {

        Intent intent =
                new Intent(
                        Intent.ACTION_GET_CONTENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType(
                AppConfig.FILE_CHOOSER_MIME_TYPE
        );

        intent.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                AppConfig.FILE_CHOOSER_MULTIPLE
        );

        return intent;
    }


    /* =====================================================
       FILE PICKER RESULT
    ===================================================== */

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );


        if (
                requestCode !=
                        REQUEST_FILE_CHOOSER
        ) {
            return;
        }


        if (
                activeFileCallback ==
                        null
        ) {
            return;
        }


        Uri[] selectedUris =
                null;


        try {

            selectedUris =
                    WebChromeClient
                            .FileChooserParams
                            .parseResult(
                                    resultCode,
                                    data
                            );

        } catch (
                Exception ignored
        ) {

            selectedUris =
                    null;
        }


        selectedUris =
                sanitizeSelectedUris(
                        selectedUris
                );


        deliverFileChooserResult(
                selectedUris
        );
    }


    /* =====================================================
       SANITIZE SELECTED URIS

       Only content:// URIs returned by Android's picker
       are accepted.

       Duplicate entries are removed and the result count
       is capped.
    ===================================================== */

    private Uri[] sanitizeSelectedUris(
            Uri[] source
    ) {

        if (
                source ==
                        null ||
                source.length ==
                        0
        ) {

            return null;
        }


        ArrayList<Uri> accepted =
                new ArrayList<>();

        Set<String> seen =
                new HashSet<>();


        for (
                Uri uri :
                source
        ) {

            if (
                    uri ==
                            null
            ) {
                continue;
            }


            String scheme =
                    safeLower(
                            uri.getScheme()
                    );


            if (
                    !"content".equals(
                            scheme
                    )
            ) {
                continue;
            }


            String uriValue =
                    uri.toString();


            if (
                    uriValue == null ||
                    uriValue.trim().isEmpty()
            ) {
                continue;
            }


            if (
                    seen.contains(
                            uriValue
                    )
            ) {
                continue;
            }


            seen.add(
                    uriValue
            );

            accepted.add(
                    uri
            );


            if (
                    accepted.size() >=
                            MAX_SELECTED_FILES
            ) {
                break;
            }
        }


        if (
                accepted.isEmpty()
        ) {
            return null;
        }


        return accepted.toArray(
                new Uri[0]
        );
    }


    /* =====================================================
       DELIVER FILE RESULT
    ===================================================== */

    private void deliverFileChooserResult(
            Uri[] selectedUris
    ) {

        ValueCallback<Uri[]> callback =
                activeFileCallback;

        activeFileCallback =
                null;


        if (
                callback ==
                        null
        ) {
            return;
        }


        try {

            callback.onReceiveValue(
                    selectedUris
            );

        } catch (
                Exception ignored
        ) {
            // Web page may already have navigated away.
        }
    }


    /* =====================================================
       CANCEL FILE REQUEST
    ===================================================== */

    private void cancelActiveFileChooser() {

        ValueCallback<Uri[]> callback =
                activeFileCallback;

        activeFileCallback =
                null;


        if (
                callback ==
                        null
        ) {
            return;
        }


        try {

            callback.onReceiveValue(
                    null
            );

        } catch (
                Exception ignored
        ) {
            // Nothing else required.
        }
    }


    /* =====================================================
       NATIVE AUTH JAVASCRIPT

       Reads:

           assets/native-auth-bridge.js

       and injects it only into:

           https://11play.github.io

       The script is registered before any page is loaded.
    ===================================================== */

    private void installNativeAuthJavaScript() {

        if (
                !WebViewFeature
                        .isFeatureSupported(
                                WebViewFeature
                                        .DOCUMENT_START_SCRIPT
                        )
        ) {
            return;
        }


        String script =
                readAssetText(
                        "native-auth-bridge.js"
                );


        if (
                script == null ||
                script.trim().isEmpty()
        ) {
            return;
        }


        try {

            WebViewCompat
                    .addDocumentStartJavaScript(
                            webView,
                            script,
                            Collections.singleton(
                                    AppConfig.OFFICIAL_ORIGIN
                            )
                    );

        } catch (
                Exception ignored
        ) {
            /*
             * Do not crash the app if the installed
             * WebView implementation cannot register
             * the document-start script.
             */
        }
    }


    /* =====================================================
       READ ASSET TEXT
    ===================================================== */

    private String readAssetText(
            String fileName
    ) {

        if (
                fileName ==
                        null ||
                fileName.trim().isEmpty()
        ) {
            return "";
        }


        StringBuilder content =
                new StringBuilder();


        try (
                InputStream inputStream =
                        getAssets()
                                .open(
                                        fileName
                                );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;


            while (
                    (line =
                            reader.readLine())
                            != null
            ) {

                content.append(
                        line
                );

                content.append(
                        '\n'
                );
            }


            return content.toString();

        } catch (
                Exception ignored
        ) {

            return "";
        }
    }


    /* =====================================================
       RESTORE OR LOAD WEBSITE
    ===================================================== */

    private void restoreOrLoadWebsite(
            Bundle savedInstanceState
    ) {

        boolean restored =
                false;


        if (
                savedInstanceState !=
                        null
        ) {

            try {

                WebBackForwardList history =
                        webView.restoreState(
                                savedInstanceState
                        );

                restored =
                        history !=
                                null;

            } catch (
                    Exception ignored
            ) {

                restored =
                        false;
            }
        }


        if (
                !restored
        ) {

            webView.loadUrl(
                    AppConfig.START_URL
            );
        }
    }


    /* =====================================================
       SAVE WEBVIEW STATE
    ===================================================== */

    @Override
    protected void onSaveInstanceState(
            Bundle outState
    ) {

        if (
                webView !=
                        null
        ) {

            try {

                webView.saveState(
                        outState
                );

            } catch (
                    Exception ignored
            ) {
                // Activity state will still be saved.
            }
        }


        super.onSaveInstanceState(
                outState
        );
    }


    /* =====================================================
       BACK BUTTON

       Navigate backward inside WebView first.

       Exit Activity only when no WebView history remains.
    ===================================================== */

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {

        if (
                webView !=
                        null &&
                webView.canGoBack()
        ) {

            webView.goBack();

            return;
        }


        super.onBackPressed();
    }


    /* =====================================================
       ACTIVITY RESUME
    ===================================================== */

    @Override
    protected void onResume() {

        super.onResume();


        if (
                webView !=
                        null
        ) {

            webView.onResume();
        }
    }


    /* =====================================================
       ACTIVITY PAUSE
    ===================================================== */

    @Override
    protected void onPause() {

        if (
                webView !=
                        null
        ) {

            webView.onPause();
        }


        super.onPause();
    }


    /* =====================================================
       DESTROY
    ===================================================== */

    @Override
    protected void onDestroy() {

        cancelActiveFileChooser();


        if (
                downloadController !=
                        null
        ) {

            downloadController
                    .clearPendingDownload();
        }


        if (
                nativeAuthBridge !=
                        null
        ) {

            nativeAuthBridge.detach();

            nativeAuthBridge =
                    null;
        }


        if (
                googleAuthManager !=
                        null
        ) {

            googleAuthManager.destroy();

            googleAuthManager =
                    null;
        }


        if (
                webView !=
                        null
        ) {

            try {

                webView.stopLoading();

                webView.setDownloadListener(
                        null
                );

                webView.removeAllViews();

                webView.destroy();

            } catch (
                    Exception ignored
            ) {
                // WebView may already be destroyed.
            }


            webView =
                    null;
        }


        super.onDestroy();
    }


    /* =====================================================
       TOAST
    ===================================================== */

    private void showToast(
            int stringResource
    ) {

        try {

            Toast.makeText(
                    this,
                    stringResource,
                    Toast.LENGTH_SHORT
            ).show();

        } catch (
                Exception ignored
        ) {
            // Non-critical UI message.
        }
    }


    /* =====================================================
       STRING HELPER
    ===================================================== */

    private String safeLower(
            String value
    ) {

        if (
                value ==
                        null
        ) {
            return "";
        }


        return value
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }
}
