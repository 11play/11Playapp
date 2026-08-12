package com.elevenplay.app.bridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.elevenplay.app.auth.GoogleAuthManager;
import com.elevenplay.app.config.AppConfig;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/* =========================================================
   11PLAY — NATIVE BRIDGE

   Responsibilities:
   - Secure WebView <-> Android communication
   - Expose bridge ONLY to official 11Play origin
   - Handle 11Play native Google authentication
   - Handle 11Play native Android sharing
   - Return Firebase/native authentication state
   - Handle native sign-out

   Security:
   - Uses WebViewCompat.addWebMessageListener()
   - No wildcard origin
   - Only https://11play.github.io
   - Main frame only
   - Third-party websites cannot call this bridge

   JavaScript object:

       window.ElevenPlayNativeAuth

========================================================= */

public final class NativeAuthBridge
        implements WebViewCompat.WebMessageListener {

    /* =====================================================
       JAVASCRIPT OBJECT
    ===================================================== */

    public static final String JS_OBJECT_NAME =
            "ElevenPlayNativeAuth";


    /* =====================================================
       ACTIONS
    ===================================================== */

    public static final String ACTION_PING =
            "ping";

    public static final String ACTION_GET_AUTH_STATE =
            "getAuthState";

    public static final String ACTION_SIGN_IN =
            "signIn";

    public static final String ACTION_SIGN_OUT =
            "signOut";

    public static final String ACTION_SHARE =
            "share";


    /* =====================================================
       LIMITS
    ===================================================== */

    private static final int MAX_MESSAGE_LENGTH =
            16384;

    private static final int MAX_REQUEST_ID_LENGTH =
            128;

    private static final int MAX_SHARE_TITLE_LENGTH =
            200;

    private static final int MAX_SHARE_TEXT_LENGTH =
            5000;

    private static final int MAX_SHARE_URL_LENGTH =
            2048;


    /* =====================================================
       DEPENDENCIES
    ===================================================== */

    private final WebView webView;

    private final GoogleAuthManager googleAuthManager;


    /* =====================================================
       STATE
    ===================================================== */

    private boolean attached =
            false;


    /* =====================================================
       CONSTRUCTOR
    ===================================================== */

    public NativeAuthBridge(
            WebView webView,
            GoogleAuthManager googleAuthManager
    ) {

        if (webView == null) {

            throw new IllegalArgumentException(
                    "WebView is required."
            );
        }


        if (googleAuthManager == null) {

            throw new IllegalArgumentException(
                    "GoogleAuthManager is required."
            );
        }


        this.webView =
                webView;

        this.googleAuthManager =
                googleAuthManager;
    }


    /* =====================================================
       ATTACH
    ===================================================== */

    public boolean attach() {

        if (attached) {

            return true;
        }


        if (
                !WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_MESSAGE_LISTENER
                )
        ) {

            return false;
        }


        try {

            Set<String> allowedOrigins =
                    Collections.singleton(
                            AppConfig.OFFICIAL_ORIGIN
                    );


            WebViewCompat.addWebMessageListener(
                    webView,
                    JS_OBJECT_NAME,
                    allowedOrigins,
                    this
            );


            attached =
                    true;


            return true;

        } catch (Exception error) {

            attached =
                    false;

            return false;
        }
    }


    /* =====================================================
       RECEIVE JAVASCRIPT MESSAGE
    ===================================================== */

    @Override
    public void onPostMessage(
            WebView view,
            WebMessageCompat message,
            Uri sourceOrigin,
            boolean isMainFrame,
            JavaScriptReplyProxy replyProxy
    ) {

        /* =============================================
           MAIN FRAME ONLY
        ============================================== */

        if (!isMainFrame) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "frame-not-allowed",
                    "Native requests are only allowed from the main 11Play page."
            );

            return;
        }


        /* =============================================
           OFFICIAL ORIGIN ONLY
        ============================================== */

        if (
                !isOfficialSourceOrigin(
                        sourceOrigin
                )
        ) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "origin-not-allowed",
                    "Request origin is not allowed."
            );

            return;
        }


        /* =============================================
           STRING MESSAGE ONLY
        ============================================== */

        if (
                message == null ||
                message.getType() !=
                        WebMessageCompat.TYPE_STRING
        ) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "invalid-message-type",
                    "Invalid native message."
            );

            return;
        }


        String rawMessage =
                message.getData();


        if (
                rawMessage == null ||
                rawMessage.trim().isEmpty()
        ) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "empty-message",
                    "Native message is empty."
            );

            return;
        }


        if (
                rawMessage.length() >
                        MAX_MESSAGE_LENGTH
        ) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "message-too-large",
                    "Native message is too large."
            );

            return;
        }


        /* =============================================
           PARSE JSON
        ============================================== */

        JSONObject request;


        try {

            request =
                    new JSONObject(
                            rawMessage
                    );

        } catch (Exception error) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "invalid-json",
                    "Native message is invalid."
            );

            return;
        }


        String action =
                safeString(
                        request.optString(
                                "action",
                                ""
                        )
                );


        String requestId =
                sanitizeRequestId(
                        request.optString(
                                "requestId",
                                ""
                        )
                );


        if (action.isEmpty()) {

            sendError(
                    replyProxy,
                    "",
                    requestId,
                    "missing-action",
                    "Native action is missing."
            );

            return;
        }


        /* =============================================
           ACTION ROUTER
        ============================================== */

        switch (action) {

            case ACTION_PING:

                handlePing(
                        replyProxy,
                        requestId
                );

                break;


            case ACTION_GET_AUTH_STATE:

                handleGetAuthState(
                        replyProxy,
                        requestId
                );

                break;


            case ACTION_SIGN_IN:

                handleSignIn(
                        replyProxy,
                        requestId
                );

                break;


            case ACTION_SIGN_OUT:

                handleSignOut(
                        replyProxy,
                        requestId
                );

                break;


            case ACTION_SHARE:

                handleShare(
                        request,
                        replyProxy,
                        requestId
                );

                break;


            default:

                sendError(
                        replyProxy,
                        action,
                        requestId,
                        "unsupported-action",
                        "Unsupported native action."
                );

                break;
        }
    }


    /* =====================================================
       PING
    ===================================================== */

    private void handlePing(
            JavaScriptReplyProxy replyProxy,
            String requestId
    ) {

        JSONObject response =
                createBaseResponse(
                        ACTION_PING,
                        requestId,
                        true
                );


        try {

            response.put(
                    "type",
                    "native-ready"
            );

            response.put(
                    "bridge",
                    JS_OBJECT_NAME
            );

            response.put(
                    "signedIn",
                    googleAuthManager.isSignedIn()
            );

            response.put(
                    "shareSupported",
                    true
            );

        } catch (Exception ignored) {
            // Base response remains valid.
        }


        sendResponse(
                replyProxy,
                response
        );
    }


    /* =====================================================
       AUTH STATE
    ===================================================== */

    private void handleGetAuthState(
            JavaScriptReplyProxy replyProxy,
            String requestId
    ) {

        FirebaseUser firebaseUser =
                googleAuthManager.getCurrentUser();


        JSONObject response =
                createBaseResponse(
                        ACTION_GET_AUTH_STATE,
                        requestId,
                        true
                );


        try {

            response.put(
                    "type",
                    "auth-state"
            );

            response.put(
                    "signedIn",
                    firebaseUser != null
            );


            if (
                    firebaseUser != null
            ) {

                response.put(
                        "user",
                        buildUserJson(
                                firebaseUser
                        )
                );
            }

        } catch (Exception ignored) {
            // Base response remains valid.
        }


        sendResponse(
                replyProxy,
                response
        );
    }


    /* =====================================================
       GOOGLE SIGN-IN
    ===================================================== */

    private void handleSignIn(
            JavaScriptReplyProxy replyProxy,
            String requestId
    ) {

        googleAuthManager.signIn(
                new GoogleAuthManager.Listener() {

                    @Override
                    public void onGoogleSignInSuccess(
                            String googleIdToken,
                            FirebaseUser firebaseUser
                    ) {

                        if (
                                googleIdToken == null ||
                                googleIdToken.trim().isEmpty()
                        ) {

                            sendError(
                                    replyProxy,
                                    ACTION_SIGN_IN,
                                    requestId,
                                    "missing-google-token",
                                    "Google authentication completed without an ID token."
                            );

                            return;
                        }


                        JSONObject response =
                                createBaseResponse(
                                        ACTION_SIGN_IN,
                                        requestId,
                                        true
                                );


                        try {

                            response.put(
                                    "type",
                                    "auth-success"
                            );

                            response.put(
                                    "signedIn",
                                    true
                            );

                            response.put(
                                    "googleIdToken",
                                    googleIdToken
                            );

                            response.put(
                                    "user",
                                    buildUserJson(
                                            firebaseUser
                                    )
                            );

                        } catch (Exception error) {

                            sendError(
                                    replyProxy,
                                    ACTION_SIGN_IN,
                                    requestId,
                                    "response-build-failed",
                                    "Authentication response could not be created."
                            );

                            return;
                        }


                        sendResponse(
                                replyProxy,
                                response
                        );
                    }


                    @Override
                    public void onGoogleSignInError(
                            String code,
                            String message
                    ) {

                        sendError(
                                replyProxy,
                                ACTION_SIGN_IN,
                                requestId,
                                safeErrorCode(
                                        code
                                ),
                                safeErrorMessage(
                                        message
                                )
                        );
                    }


                    @Override
                    public void onGoogleSignOut() {
                        // Not expected during sign-in.
                    }
                }
        );
    }


    /* =====================================================
       SIGN OUT
    ===================================================== */

    private void handleSignOut(
            JavaScriptReplyProxy replyProxy,
            String requestId
    ) {

        googleAuthManager.signOut(
                new GoogleAuthManager.Listener() {

                    @Override
                    public void onGoogleSignInSuccess(
                            String googleIdToken,
                            FirebaseUser firebaseUser
                    ) {
                        // Not expected.
                    }


                    @Override
                    public void onGoogleSignInError(
                            String code,
                            String message
                    ) {

                        sendError(
                                replyProxy,
                                ACTION_SIGN_OUT,
                                requestId,
                                safeErrorCode(
                                        code
                                ),
                                safeErrorMessage(
                                        message
                                )
                        );
                    }


                    @Override
                    public void onGoogleSignOut() {

                        JSONObject response =
                                createBaseResponse(
                                        ACTION_SIGN_OUT,
                                        requestId,
                                        true
                                );


                        try {

                            response.put(
                                    "type",
                                    "auth-sign-out"
                            );

                            response.put(
                                    "signedIn",
                                    false
                            );

                        } catch (Exception ignored) {
                            // Base response remains valid.
                        }


                        sendResponse(
                                replyProxy,
                                response
                        );
                    }
                }
        );
    }


    /* =====================================================
       11PLAY NATIVE SHARE

       Only the official 11Play page can reach this method.

       Result:
       Android system Sharesheet opens with compatible apps:

       - WhatsApp
       - Messenger
       - Telegram
       - Gmail
       - Messages
       - Other installed sharing apps

       Third-party websites cannot call this bridge.
    ===================================================== */

    private void handleShare(
            JSONObject request,
            JavaScriptReplyProxy replyProxy,
            String requestId
    ) {

        if (request == null) {

            sendError(
                    replyProxy,
                    ACTION_SHARE,
                    requestId,
                    "invalid-share-request",
                    "Share request is invalid."
            );

            return;
        }


        String title =
                truncate(
                        safeString(
                                request.optString(
                                        "title",
                                        ""
                                )
                        ),
                        MAX_SHARE_TITLE_LENGTH
                );


        String text =
                truncate(
                        safeString(
                                request.optString(
                                        "text",
                                        ""
                                )
                        ),
                        MAX_SHARE_TEXT_LENGTH
                );


        String url =
                truncate(
                        safeString(
                                request.optString(
                                        "url",
                                        ""
                                )
                        ),
                        MAX_SHARE_URL_LENGTH
                );


        /* =============================================
           DEFAULT SHARE URL

           If website supplies no URL, use official 11Play.
        ============================================== */

        if (url.isEmpty()) {

            url =
                    AppConfig.START_URL;
        }


        /* =============================================
           ONLY ALLOW HTTP/HTTPS SHARE URL
        ============================================== */

        if (
                !url.isEmpty() &&
                !isHttpUrl(
                        url
                )
        ) {

            url =
                    AppConfig.START_URL;
        }


        /* =============================================
           BUILD SHARE CONTENT
        ============================================== */

        String shareContent =
                buildShareContent(
                        text,
                        url
                );


        if (
                shareContent.isEmpty()
        ) {

            shareContent =
                    AppConfig.START_URL;
        }


        /* =============================================
           SEND INTENT
        ============================================== */

        Intent sendIntent =
                new Intent(
                        Intent.ACTION_SEND
                );


        sendIntent.setType(
                "text/plain"
        );


        sendIntent.putExtra(
                Intent.EXTRA_TEXT,
                shareContent
        );


        if (
                !title.isEmpty()
        ) {

            sendIntent.putExtra(
                    Intent.EXTRA_SUBJECT,
                    title
            );
        }


        Intent chooser =
                Intent.createChooser(
                        sendIntent,
                        title.isEmpty()
                                ? "Share 11Play"
                                : title
                );


        Context context =
                webView.getContext();


        if (
                !(context instanceof Activity)
        ) {

            chooser.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
            );
        }


        try {

            context.startActivity(
                    chooser
            );


            JSONObject response =
                    createBaseResponse(
                            ACTION_SHARE,
                            requestId,
                            true
                    );


            try {

                response.put(
                        "type",
                        "share-opened"
                );

            } catch (Exception ignored) {
                // Base response remains valid.
            }


            sendResponse(
                    replyProxy,
                    response
            );


        } catch (
                ActivityNotFoundException error
        ) {

            sendError(
                    replyProxy,
                    ACTION_SHARE,
                    requestId,
                    "share-app-unavailable",
                    "No compatible sharing application was found."
            );


        } catch (
                Exception error
        ) {

            sendError(
                    replyProxy,
                    ACTION_SHARE,
                    requestId,
                    "share-failed",
                    "Android sharing could not be opened."
            );
        }
    }


    /* =====================================================
       BUILD SHARE CONTENT
    ===================================================== */

    private String buildShareContent(
            String text,
            String url
    ) {

        String safeText =
                safeString(
                        text
                );


        String safeUrl =
                safeString(
                        url
                );


        if (
                safeText.isEmpty() &&
                safeUrl.isEmpty()
        ) {

            return "";
        }


        if (
                safeText.isEmpty()
        ) {

            return safeUrl;
        }


        if (
                safeUrl.isEmpty()
        ) {

            return safeText;
        }


        /*
         * Avoid duplicating URL if website already included
         * it in the share text.
         */

        if (
                safeText.contains(
                        safeUrl
                )
        ) {

            return safeText;
        }


        return safeText
                + "\n\n"
                + safeUrl;
    }


    /* =====================================================
       HTTP URL CHECK
    ===================================================== */

    private boolean isHttpUrl(
            String value
    ) {

        String url =
                safeString(
                        value
                );


        if (url.isEmpty()) {

            return false;
        }


        try {

            Uri uri =
                    Uri.parse(
                            url
                    );


            String scheme =
                    safeLower(
                            uri.getScheme()
                    );


            return "https".equals(
                    scheme
            ) ||
                    "http".equals(
                            scheme
                    );


        } catch (Exception ignored) {

            return false;
        }
    }


    /* =====================================================
       FIREBASE USER JSON
    ===================================================== */

    private JSONObject buildUserJson(
            FirebaseUser firebaseUser
    ) {

        JSONObject user =
                new JSONObject();


        if (
                firebaseUser == null
        ) {

            return user;
        }


        try {

            user.put(
                    "uid",
                    safeString(
                            firebaseUser.getUid()
                    )
            );


            putNullable(
                    user,
                    "email",
                    firebaseUser.getEmail()
            );


            putNullable(
                    user,
                    "displayName",
                    firebaseUser.getDisplayName()
            );


            Uri photoUri =
                    firebaseUser.getPhotoUrl();


            putNullable(
                    user,
                    "photoUrl",
                    photoUri == null
                            ? null
                            : photoUri.toString()
            );


            user.put(
                    "emailVerified",
                    firebaseUser.isEmailVerified()
            );


            user.put(
                    "anonymous",
                    firebaseUser.isAnonymous()
            );


        } catch (Exception ignored) {
            // Return available safe fields.
        }


        return user;
    }


    /* =====================================================
       RESPONSE
    ===================================================== */

    private JSONObject createBaseResponse(
            String action,
            String requestId,
            boolean success
    ) {

        JSONObject response =
                new JSONObject();


        try {

            response.put(
                    "ok",
                    success
            );

            response.put(
                    "action",
                    safeString(
                            action
                    )
            );

            response.put(
                    "requestId",
                    sanitizeRequestId(
                            requestId
                    )
            );


        } catch (Exception ignored) {
            // JSONObject remains valid.
        }


        return response;
    }


    private void sendError(
            JavaScriptReplyProxy replyProxy,
            String action,
            String requestId,
            String code,
            String message
    ) {

        JSONObject response =
                createBaseResponse(
                        action,
                        requestId,
                        false
                );


        try {

            response.put(
                    "type",
                    "native-error"
            );

            response.put(
                    "code",
                    safeErrorCode(
                            code
                    )
            );

            response.put(
                    "message",
                    safeErrorMessage(
                            message
                    )
            );


        } catch (Exception ignored) {
            // Send base response.
        }


        sendResponse(
                replyProxy,
                response
        );
    }


    private void sendResponse(
            JavaScriptReplyProxy replyProxy,
            JSONObject response
    ) {

        if (
                replyProxy == null ||
                response == null
        ) {

            return;
        }


        try {

            replyProxy.postMessage(
                    response.toString()
            );


        } catch (Exception ignored) {
            // Page may have navigated away.
        }
    }


    /* =====================================================
       SOURCE ORIGIN SECURITY
    ===================================================== */

    private boolean isOfficialSourceOrigin(
            Uri sourceOrigin
    ) {

        if (
                sourceOrigin == null
        ) {

            return false;
        }


        String scheme =
                safeLower(
                        sourceOrigin.getScheme()
                );


        String host =
                safeLower(
                        sourceOrigin.getHost()
                );


        int port =
                sourceOrigin.getPort();


        if (
                !"https".equals(
                        scheme
                )
        ) {

            return false;
        }


        if (
                !AppConfig.isOfficialHost(
                        host
                )
        ) {

            return false;
        }


        return port == -1 ||
                port == 443;
    }


    /* =====================================================
       JSON HELPERS
    ===================================================== */

    private void putNullable(
            JSONObject object,
            String key,
            String value
    ) {

        if (
                object == null ||
                key == null
        ) {

            return;
        }


        try {

            if (
                    value == null ||
                    value.trim().isEmpty()
            ) {

                object.put(
                        key,
                        JSONObject.NULL
                );


            } else {

                object.put(
                        key,
                        value.trim()
                );
            }


        } catch (Exception ignored) {
            // Optional field.
        }
    }


    /* =====================================================
       REQUEST ID
    ===================================================== */

    private String sanitizeRequestId(
            String value
    ) {

        String requestId =
                safeString(
                        value
                );


        if (
                requestId.length() >
                        MAX_REQUEST_ID_LENGTH
        ) {

            requestId =
                    requestId.substring(
                            0,
                            MAX_REQUEST_ID_LENGTH
                    );
        }


        return requestId;
    }


    /* =====================================================
       ERROR HELPERS
    ===================================================== */

    private String safeErrorCode(
            String value
    ) {

        String code =
                safeString(
                        value
                );


        if (code.isEmpty()) {

            return "native-error";
        }


        if (
                code.length() >
                        100
        ) {

            code =
                    code.substring(
                            0,
                            100
                    );
        }


        return code;
    }


    private String safeErrorMessage(
            String value
    ) {

        String message =
                safeString(
                        value
                );


        if (message.isEmpty()) {

            return "Native operation could not be completed.";
        }


        if (
                message.length() >
                        500
        ) {

            message =
                    message.substring(
                            0,
                            500
                    );
        }


        return message;
    }


    /* =====================================================
       STRING HELPERS
    ===================================================== */

    private String truncate(
            String value,
            int maxLength
    ) {

        String safeValue =
                safeString(
                        value
                );


        if (
                maxLength <= 0 ||
                safeValue.length() <=
                        maxLength
        ) {

            return safeValue;
        }


        return safeValue.substring(
                0,
                maxLength
        );
    }


    private String safeLower(
            String value
    ) {

        return safeString(
                value
        ).toLowerCase(
                Locale.ROOT
        );
    }


    private String safeString(
            Object value
    ) {

        if (
                value == null
        ) {

            return "";
        }


        return String.valueOf(
                value
        ).trim();
    }


    /* =====================================================
       DETACH
    ===================================================== */

    public void detach() {

        if (!attached) {

            return;
        }


        if (
                WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_MESSAGE_LISTENER
                )
        ) {

            try {

                WebViewCompat.removeWebMessageListener(
                        webView,
                        JS_OBJECT_NAME
                );


            } catch (Exception ignored) {
                // WebView may already be destroyed.
            }
        }


        attached =
                false;
    }


    public boolean isAttached() {

        return attached;
    }
}
