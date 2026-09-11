package com.elevenplay.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
   - Support floating manual refresh button
   - Allow floating refresh button free movement
   - Keep floating button inside safe screen area
   - Remember floating button position
   - Handle browser-friendly Android back navigation

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
       FLOATING REFRESH PREFERENCES
    ===================================================== */

    private static final String
            UI_PREFERENCES =
            "elevenplay_ui_preferences";

    private static final String
            PREF_REFRESH_X_RATIO =
            "refresh_button_x_ratio";

    private static final String
            PREF_REFRESH_Y_RATIO =
            "refresh_button_y_ratio";


    /* =====================================================
       FLOATING REFRESH ANIMATION
    ===================================================== */

    private static final long
            REFRESH_ROTATION_DURATION_MS =
            350L;


    /* =====================================================
       VIEWS
    ===================================================== */

    private FrameLayout
            rootContainer;

    private WebView
            webView;

    private ProgressBar
            pageProgress;

    private ImageButton
            floatingRefreshButton;


    /* =====================================================
       FLOATING REFRESH TOUCH STATE
    ===================================================== */

    private float
            refreshTouchDownRawX =
            0f;

    private float
            refreshTouchDownRawY =
            0f;

    private float
            refreshButtonStartX =
            0f;

    private float
            refreshButtonStartY =
            0f;

    private boolean
            refreshButtonDragging =
            false;

    private int
            refreshTouchSlop =
            0;


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

        webView =
                findViewById(
                        R.id.webView
                );

        pageProgress =
                findViewById(
                        R.id.pageProgress
                );

        floatingRefreshButton =
                findViewById(
                        R.id.floatingRefreshButton
                );


        /* =============================================
           SYSTEM SAFE AREA
        ============================================== */

        configureSystemInsets();


        /* =============================================
           FLOATING REFRESH BUTTON
        ============================================== */

        configureFloatingRefreshButton();


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

       rootContainer receives safe padding for:
       - Status bar
       - Display cutout
       - Navigation bar

       Floating refresh button is additionally clamped
       inside these safe boundaries.
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


                    if (
                            floatingRefreshButton !=
                                    null
                    ) {

                        floatingRefreshButton.post(
                                this::clampRefreshButtonInsideSafeArea
                        );
                    }


                    return windowInsets;
                }
        );


        ViewCompat.requestApplyInsets(
                rootContainer
        );
    }


    /* =====================================================
       FLOATING MANUAL REFRESH BUTTON

       TAP:
           Reload current WebView page.

       DRAG:
           Move freely:
           - Left
           - Right
           - Up
           - Down
           - Diagonally

       RELEASE:
           Stay exactly where user releases it.

       LIMITS:
           Cannot move:
           - Into status/top bar
           - Into display cutout
           - Into navigation bar
           - Outside left/right screen boundary

       POSITION:
           Saved automatically.
    ===================================================== */

    private void configureFloatingRefreshButton() {

        if (
                floatingRefreshButton ==
                        null
        ) {
            return;
        }


        refreshTouchSlop =
                ViewConfiguration
                        .get(
                                this
                        )
                        .getScaledTouchSlop();


        /* =============================================
           TAP = REFRESH
        ============================================== */

        floatingRefreshButton
                .setOnClickListener(
                        view ->
                                refreshCurrentPage()
                );


        /* =============================================
           FREE 2D DRAG
        ============================================== */

        floatingRefreshButton
                .setOnTouchListener(
                        (
                                view,
                                event
                        ) -> {

                            if (
                                    event ==
                                            null
                            ) {
                                return false;
                            }


                            switch (
                                    event.getActionMasked()
                            ) {

                                /* =====================
                                   TOUCH START
                                ====================== */

                                case MotionEvent.ACTION_DOWN:

                                    refreshTouchDownRawX =
                                            event.getRawX();

                                    refreshTouchDownRawY =
                                            event.getRawY();

                                    refreshButtonStartX =
                                            view.getX();

                                    refreshButtonStartY =
                                            view.getY();

                                    refreshButtonDragging =
                                            false;


                                    view.animate()
                                            .cancel();


                                    if (
                                            view.getParent() !=
                                                    null
                                    ) {

                                        view.getParent()
                                                .requestDisallowInterceptTouchEvent(
                                                        true
                                                );
                                    }


                                    return true;


                                /* =====================
                                   MOVE
                                ====================== */

                                case MotionEvent.ACTION_MOVE:

                                    float deltaX =
                                            event.getRawX()
                                                    -
                                            refreshTouchDownRawX;

                                    float deltaY =
                                            event.getRawY()
                                                    -
                                            refreshTouchDownRawY;


                                    if (
                                            !refreshButtonDragging
                                    ) {

                                        float distance =
                                                (float)
                                                        Math.hypot(
                                                                deltaX,
                                                                deltaY
                                                        );


                                        if (
                                                distance >
                                                        refreshTouchSlop
                                        ) {

                                            refreshButtonDragging =
                                                    true;
                                        }
                                    }


                                    if (
                                            refreshButtonDragging
                                    ) {

                                        moveRefreshButton(
                                                refreshButtonStartX
                                                        +
                                                deltaX,

                                                refreshButtonStartY
                                                        +
                                                deltaY
                                        );
                                    }


                                    return true;


                                /* =====================
                                   TOUCH RELEASE
                                ====================== */

                                case MotionEvent.ACTION_UP:

                                    if (
                                            refreshButtonDragging
                                    ) {

                                        clampRefreshButtonInsideSafeArea();

                                        saveFloatingRefreshButtonPosition();

                                    } else {

                                        view.performClick();
                                    }


                                    refreshButtonDragging =
                                            false;


                                    if (
                                            view.getParent() !=
                                                    null
                                    ) {

                                        view.getParent()
                                                .requestDisallowInterceptTouchEvent(
                                                        false
                                                );
                                    }


                                    return true;


                                /* =====================
                                   TOUCH CANCEL
                                ====================== */

                                case MotionEvent.ACTION_CANCEL:

                                    if (
                                            refreshButtonDragging
                                    ) {

                                        clampRefreshButtonInsideSafeArea();

                                        saveFloatingRefreshButtonPosition();
                                    }


                                    refreshButtonDragging =
                                            false;


                                    if (
                                            view.getParent() !=
                                                    null
                                    ) {

                                        view.getParent()
                                                .requestDisallowInterceptTouchEvent(
                                                        false
                                                );
                                    }


                                    return true;


                                default:

                                    return true;
                            }
                        }
                );


        /* =============================================
           RESTORE LAST POSITION
        ============================================== */

        floatingRefreshButton.post(
                this::restoreFloatingRefreshButtonPosition
        );
    }


    /* =====================================================
       REFRESH CURRENT PAGE
    ===================================================== */

    private void refreshCurrentPage() {

        if (
                webView ==
                        null
        ) {
            return;
        }


        if (
                floatingRefreshButton !=
                        null
        ) {

            floatingRefreshButton
                    .animate()
                    .cancel();


            floatingRefreshButton
                    .animate()
                    .rotationBy(
                            360f
                    )
                    .setDuration(
                            REFRESH_ROTATION_DURATION_MS
                    )
                    .start();
        }


        webView.reload();
    }


    /* =====================================================
       MOVE FLOATING REFRESH BUTTON

       Supports full 2D movement.

       X:
           left ↔ right

       Y:
           top ↕ bottom
    ===================================================== */

    private void moveRefreshButton(
            float requestedX,
            float requestedY
    ) {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return;
        }


        float minX =
                getRefreshButtonMinX();

        float maxX =
                getRefreshButtonMaxX();

        float minY =
                getRefreshButtonMinY();

        float maxY =
                getRefreshButtonMaxY();


        float safeX =
                clamp(
                        requestedX,
                        minX,
                        maxX
                );

        float safeY =
                clamp(
                        requestedY,
                        minY,
                        maxY
                );


        floatingRefreshButton
                .setX(
                        safeX
                );

        floatingRefreshButton
                .setY(
                        safeY
                );
    }


    /* =====================================================
       FORCE BUTTON INSIDE SAFE AREA
    ===================================================== */

    private void clampRefreshButtonInsideSafeArea() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return;
        }


        if (
                rootContainer.getWidth()
                        <= 0 ||
                rootContainer.getHeight()
                        <= 0 ||
                floatingRefreshButton.getWidth()
                        <= 0 ||
                floatingRefreshButton.getHeight()
                        <= 0
        ) {
            return;
        }


        moveRefreshButton(
                floatingRefreshButton.getX(),
                floatingRefreshButton.getY()
        );
    }


    /* =====================================================
       RESTORE SAVED POSITION

       Position is stored as ratio rather than raw pixels.

       Benefit:
       - Works better after rotation
       - Works on different screen sizes
       - Stays inside available safe area
    ===================================================== */

    private void restoreFloatingRefreshButtonPosition() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return;
        }


        if (
                rootContainer.getWidth()
                        <= 0 ||
                rootContainer.getHeight()
                        <= 0 ||
                floatingRefreshButton.getWidth()
                        <= 0 ||
                floatingRefreshButton.getHeight()
                        <= 0
        ) {

            floatingRefreshButton.post(
                    this::restoreFloatingRefreshButtonPosition
            );

            return;
        }


        SharedPreferences preferences =
                getSharedPreferences(
                        UI_PREFERENCES,
                        MODE_PRIVATE
                );


        float minX =
                getRefreshButtonMinX();

        float maxX =
                getRefreshButtonMaxX();

        float minY =
                getRefreshButtonMinY();

        float maxY =
                getRefreshButtonMaxY();


        boolean hasSavedX =
                preferences.contains(
                        PREF_REFRESH_X_RATIO
                );

        boolean hasSavedY =
                preferences.contains(
                        PREF_REFRESH_Y_RATIO
                );


        /* =============================================
           FIRST RUN DEFAULT

           Right side + vertical center.
        ============================================== */

        if (
                !hasSavedX ||
                !hasSavedY
        ) {

            float defaultX =
                    maxX;

            float defaultY =
                    minY
                            +
                    (
                            (
                                    maxY
                                            -
                                    minY
                            )
                                    /
                            2f
                    );


            moveRefreshButton(
                    defaultX,
                    defaultY
            );


            return;
        }


        float savedXRatio =
                preferences.getFloat(
                        PREF_REFRESH_X_RATIO,
                        1f
                );

        float savedYRatio =
                preferences.getFloat(
                        PREF_REFRESH_Y_RATIO,
                        0.5f
                );


        savedXRatio =
                clamp(
                        savedXRatio,
                        0f,
                        1f
                );

        savedYRatio =
                clamp(
                        savedYRatio,
                        0f,
                        1f
                );


        float availableWidth =
                maxX
                        -
                minX;

        float availableHeight =
                maxY
                        -
                minY;


        float targetX =
                minX
                        +
                (
                        availableWidth
                                *
                        savedXRatio
                );

        float targetY =
                minY
                        +
                (
                        availableHeight
                                *
                        savedYRatio
                );


        moveRefreshButton(
                targetX,
                targetY
        );
    }


    /* =====================================================
       SAVE FLOATING BUTTON POSITION
    ===================================================== */

    private void saveFloatingRefreshButtonPosition() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return;
        }


        float minX =
                getRefreshButtonMinX();

        float maxX =
                getRefreshButtonMaxX();

        float minY =
                getRefreshButtonMinY();

        float maxY =
                getRefreshButtonMaxY();


        float availableWidth =
                maxX
                        -
                minX;

        float availableHeight =
                maxY
                        -
                minY;


        float xRatio;


        if (
                availableWidth >
                        0f
        ) {

            xRatio =
                    (
                            floatingRefreshButton.getX()
                                    -
                            minX
                    )
                            /
                    availableWidth;

        } else {

            xRatio =
                    0f;
        }


        float yRatio;


        if (
                availableHeight >
                        0f
        ) {

            yRatio =
                    (
                            floatingRefreshButton.getY()
                                    -
                            minY
                    )
                            /
                    availableHeight;

        } else {

            yRatio =
                    0f;
        }


        xRatio =
                clamp(
                        xRatio,
                        0f,
                        1f
                );

        yRatio =
                clamp(
                        yRatio,
                        0f,
                        1f
                );


        try {

            getSharedPreferences(
                    UI_PREFERENCES,
                    MODE_PRIVATE
            )
                    .edit()
                    .putFloat(
                            PREF_REFRESH_X_RATIO,
                            xRatio
                    )
                    .putFloat(
                            PREF_REFRESH_Y_RATIO,
                            yRatio
                    )
                    .apply();

        } catch (
                Exception ignored
        ) {
            // Position preference is non-critical.
        }
    }


    /* =====================================================
       SAFE LEFT BOUNDARY
    ===================================================== */

    private float getRefreshButtonMinX() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return 0f;
        }


        int leftMargin =
                0;


        if (
                floatingRefreshButton
                        .getLayoutParams()
                        instanceof
                        FrameLayout.LayoutParams
        ) {

            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams)
                            floatingRefreshButton
                                    .getLayoutParams();

            leftMargin =
                    params.leftMargin;
        }


        return rootContainer
                .getPaddingLeft()
                +
                leftMargin;
    }


    /* =====================================================
       SAFE RIGHT BOUNDARY
    ===================================================== */

    private float getRefreshButtonMaxX() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return 0f;
        }


        int rightMargin =
                0;


        if (
                floatingRefreshButton
                        .getLayoutParams()
                        instanceof
                        FrameLayout.LayoutParams
        ) {

            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams)
                            floatingRefreshButton
                                    .getLayoutParams();

            rightMargin =
                    params.rightMargin;
        }


        float maximumX =
                rootContainer.getWidth()
                        -
                rootContainer.getPaddingRight()
                        -
                rightMargin
                        -
                floatingRefreshButton.getWidth();


        return Math.max(
                getRefreshButtonMinX(),
                maximumX
        );
    }


    /* =====================================================
       SAFE TOP BOUNDARY

       Keeps button below:
       - Status bar
       - Display cutout
    ===================================================== */

    private float getRefreshButtonMinY() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return 0f;
        }


        int topMargin =
                0;


        if (
                floatingRefreshButton
                        .getLayoutParams()
                        instanceof
                        FrameLayout.LayoutParams
        ) {

            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams)
                            floatingRefreshButton
                                    .getLayoutParams();

            topMargin =
                    params.topMargin;
        }


        return rootContainer
                .getPaddingTop()
                +
                topMargin;
    }


    /* =====================================================
       SAFE BOTTOM BOUNDARY

       Keeps button above Android navigation bar.
    ===================================================== */

    private float getRefreshButtonMaxY() {

        if (
                floatingRefreshButton ==
                        null ||
                rootContainer ==
                        null
        ) {
            return 0f;
        }


        int bottomMargin =
                0;


        if (
                floatingRefreshButton
                        .getLayoutParams()
                        instanceof
                        FrameLayout.LayoutParams
        ) {

            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams)
                            floatingRefreshButton
                                    .getLayoutParams();

            bottomMargin =
                    params.bottomMargin;
        }


        float maximumY =
                rootContainer.getHeight()
                        -
                rootContainer.getPaddingBottom()
                        -
                bottomMargin
                        -
                floatingRefreshButton.getHeight();


        return Math.max(
                getRefreshButtonMinY(),
                maximumY
        );
    }


    /* =====================================================
       FLOAT CLAMP HELPER
    ===================================================== */

    private float clamp(
            float value,
            float minimum,
            float maximum
    ) {

        return Math.max(
                minimum,
                Math.min(
                        value,
                        maximum
                )
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
       BACK BUTTON — BROWSER FRIENDLY
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
                floatingRefreshButton !=
                        null
        ) {

            floatingRefreshButton
                    .animate()
                    .cancel();

            floatingRefreshButton
                    .setOnTouchListener(
                            null
                    );

            floatingRefreshButton
                    .setOnClickListener(
                            null
                    );

            floatingRefreshButton =
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
