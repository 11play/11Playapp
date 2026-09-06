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
import android.webkit.WebHistoryItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
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
   - Protect website UI from Android status/navigation bars
   - Configure secure modern WebView settings
   - Enable JavaScript / DOM storage / cookies
   - Support tawk.to Live Chat
   - Support screenshot/photo/video/file upload
   - Support website downloads
   - Handle legacy Android download permission
   - Attach secure native authentication bridge
   - Inject native-auth-bridge.js at document start
   - Preserve WebView navigation state
   - Support pull-to-refresh
   - Handle smart Android back navigation

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

    private FrameLayout rootContainer;

    private SwipeRefreshLayout
            swipeRefreshLayout;

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

        rootContainer =
                findViewById(
                        R.id.rootContainer
                );

        swipeRefreshLayout =
                findViewById(
                        R.id.swipeRefreshLayout
                );

        webView =
                findViewById(
                        R.id.webView
                );

        pageProgress =
                findViewById(
                        R.id.pageProgress
                );


        /* =============================================
           SYSTEM SAFE AREA
        ============================================== */

        configureSystemInsets();


        /* =============================================
           PULL TO REFRESH
        ============================================== */

        configureSwipeRefresh();


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
        ============================================== */

        nativeAuthBridge.attach();


        /* =============================================
           NATIVE AUTH JAVASCRIPT
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
       SYSTEM WINDOW INSETS
    ===================================================== */

    private void configureSystemInsets() {

        if (
                rootContainer ==
                        null
        ) {
            return;
        }


        ViewCompat.setOnApplyWindowInsetsListener(
                rootContainer,
                (
                        view,
                        windowInsets
                ) -> {

                    Insets safeInsets =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            |
                                    WindowInsetsCompat.Type.displayCutout()
                            );


                    view.setPadding(
                            safeInsets.left,
                            safeInsets.top,
                            safeInsets.right,
                            safeInsets.bottom
                    );


                    return windowInsets;
                }
        );


        ViewCompat.requestApplyInsets(
                rootContainer
        );
    }


    /* =====================================================
       PULL TO REFRESH

       Refresh is enabled only when WebView is already
       at the top of the current page.

       This prevents normal page scrolling from being
       interpreted as a refresh gesture.
    ===================================================== */

    private void configureSwipeRefresh() {

        if (
                swipeRefreshLayout ==
                        null ||
                webView ==
                        null
        ) {
            return;
        }


        /* =============================================
           ONLY ALLOW REFRESH AT TOP
        ============================================== */

        swipeRefreshLayout
                .setOnChildScrollUpCallback(
                        (
                                parent,
                                child
                        ) -> {

                            return webView != null &&
                                    webView.canScrollVertically(
                                            -1
                                    );
                        }
                );


        /* =============================================
           REFRESH CURRENT WEB PAGE
        ============================================== */

        swipeRefreshLayout
                .setOnRefreshListener(
                        () -> {

                            if (
                                    webView !=
                                            null
                            ) {

                                webView.reload();

                            } else {

                                swipeRefreshLayout
                                        .setRefreshing(
                                                false
                                        );
                            }
                        }
                );
    }


    /* =====================================================
       STOP PULL REFRESH INDICATOR

       This method can also be called by WebView loading
       callbacks if required.
    ===================================================== */

    public void stopSwipeRefresh() {

        if (
                swipeRefreshLayout !=
                        null &&
                swipeRefreshLayout
                        .isRefreshing()
        ) {

            swipeRefreshLayout
                    .setRefreshing(
                            false
                    );
        }
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
        ============================================== */

        settings.setAllowFileAccess(
                AppConfig.ALLOW_FILE_ACCESS
        );

        settings.setAllowContentAccess(
                AppConfig.ALLOW_CONTENT_ACCESS
        );


        /* =============================================
           MIXED CONTENT
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
            // Page may already have navigated away.
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
            // Do not crash if unsupported.
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
                // Activity state still saves.
            }
        }


        super.onSaveInstanceState(
                outState
        );
    }


    /* =====================================================
       SMART BACK BUTTON

       Behavior:

       OFFICIAL 11PLAY PAGE:
       - Normal WebView Back behavior.

       THIRD-PARTY WEBSITE:
       - Skip all third-party browsing history.
       - Jump directly back to the most recent
         official 11Play history entry.

       Example:

       11Play
         ↓
       Casino home
         ↓
       Page 1
         ↓
       Page 2
         ↓
       Page 3

       Back:
       Page 3 → 11Play

       NOT:
       Page 3 → Page 2 → Page 1 → Casino → 11Play
    ===================================================== */

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {

        if (
                webView ==
                        null
        ) {

            super.onBackPressed();

            return;
        }


        String currentUrl =
                webView.getUrl();


        /* =============================================
           CURRENT PAGE IS THIRD-PARTY
        ============================================== */

        if (
                !isOfficial11PlayUrl(
                        currentUrl
                )
        ) {

            if (
                    jumpBackToPrevious11PlayPage()
            ) {
                return;
            }


            /*
             * Safety fallback:
             *
             * If no 11Play entry exists in WebView history,
             * return to the official app home instead of
             * forcing the user through third-party history.
             */

            webView.loadUrl(
                    AppConfig.START_URL
            );

            return;
        }


        /* =============================================
           CURRENT PAGE IS 11PLAY

           Keep normal back behavior.
        ============================================== */

        if (
                webView.canGoBack()
        ) {

            webView.goBack();

            return;
        }


        super.onBackPressed();
    }


    /* =====================================================
       JUMP BACK TO PREVIOUS 11PLAY HISTORY ENTRY
    ===================================================== */

    private boolean jumpBackToPrevious11PlayPage() {

        if (
                webView ==
                        null
        ) {
            return false;
        }


        try {

            WebBackForwardList history =
                    webView.copyBackForwardList();


            if (
                    history ==
                            null
            ) {
                return false;
            }


            int currentIndex =
                    history.getCurrentIndex();


            if (
                    currentIndex <=
                            0
            ) {
                return false;
            }


            /*
             * Walk backwards through the WebView history
             * until the most recent official 11Play URL
             * is found.
             */

            for (
                    int index =
                            currentIndex - 1;
                    index >= 0;
                    index--
            ) {

                WebHistoryItem historyItem =
                        history.getItemAtIndex(
                                index
                        );


                if (
                        historyItem ==
                                null
                ) {
                    continue;
                }


                String historyUrl =
                        historyItem.getUrl();


                if (
                        !isOfficial11PlayUrl(
                                historyUrl
                        )
                ) {
                    continue;
                }


                int offset =
                        index -
                        currentIndex;


                if (
                        offset <
                                0
                ) {

                    webView.goBackOrForward(
                            offset
                    );

                    return true;
                }
            }

        } catch (
                Exception ignored
        ) {
            // Fall through to START_URL fallback.
        }


        return false;
    }


    /* =====================================================
       OFFICIAL 11PLAY URL CHECK

       Official URLs include:

       https://11play.github.io/11play/
       https://11play.github.io/11play/pages/...
       https://11play.github.io/11play/...

       Third-party domains are NOT considered official.
    ===================================================== */

    private boolean isOfficial11PlayUrl(
            String url
    ) {

        if (
                url ==
                        null ||
                url.trim().isEmpty()
        ) {
            return false;
        }


        try {

            Uri currentUri =
                    Uri.parse(
                            url
                    );

            Uri officialUri =
                    Uri.parse(
                            AppConfig.START_URL
                    );


            String currentScheme =
                    safeLower(
                            currentUri.getScheme()
                    );

            String officialScheme =
                    safeLower(
                            officialUri.getScheme()
                    );


            if (
                    !currentScheme.equals(
                            officialScheme
                    )
            ) {
                return false;
            }


            String currentHost =
                    safeLower(
                            currentUri.getHost()
                    );

            String officialHost =
                    safeLower(
                            officialUri.getHost()
                    );


            if (
                    !currentHost.equals(
                            officialHost
                    )
            ) {
                return false;
            }


            String currentPath =
                    currentUri.getPath();

            String officialPath =
                    officialUri.getPath();


            if (
                    currentPath ==
                            null
            ) {
                currentPath =
                        "/";
            }


            if (
                    officialPath ==
                            null ||
                    officialPath.trim().isEmpty()
            ) {
                officialPath =
                        "/";
            }


            /*
             * START_URL currently uses:
             *
             * /11play/
             *
             * Therefore any URL inside /11play/ is an
             * official 11Play WebView page.
             */

            if (
                    !officialPath.endsWith(
                            "/"
                    )
            ) {

                officialPath =
                        officialPath +
                        "/";
            }


            String officialPathWithoutSlash =
                    officialPath.length() > 1
                            ?
                            officialPath.substring(
                                    0,
                                    officialPath.length() - 1
                            )
                            :
                            officialPath;


            return currentPath.equals(
                    officialPathWithoutSlash
            ) ||
                    currentPath.startsWith(
                            officialPath
                    );

        } catch (
                Exception ignored
        ) {

            return false;
        }
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
                swipeRefreshLayout !=
                        null
        ) {

            swipeRefreshLayout
                    .setOnRefreshListener(
                            null
                    );

            swipeRefreshLayout =
                    null;
        }


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


        rootContainer =
                null;

        pageProgress =
                null;


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
