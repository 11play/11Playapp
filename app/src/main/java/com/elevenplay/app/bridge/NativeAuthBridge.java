package com.elevenplay.app.bridge;

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
   11PLAY — NATIVE AUTH BRIDGE

   Responsibilities:
   - Create a secure WebView <-> Android authentication bridge
   - Expose the bridge ONLY to the official 11Play origin
   - Accept authentication commands from the main frame only
   - Start native Google Sign-In
   - Return Google ID token after successful authentication
   - Return Firebase user information
   - Handle native sign-out
   - Report native authentication state

   Security:
   - Uses WebViewCompat.addWebMessageListener()
   - No wildcard origin is used
   - Only HTTPS 11play.github.io is accepted
   - Only main-frame messages are accepted
   - tawk.to / external frames cannot call this bridge
   - Message payload size is limited

   JavaScript object exposed to the official page:

       window.ElevenPlayNativeAuth

   JavaScript can send:

       ElevenPlayNativeAuth.postMessage(
           JSON.stringify({
               action: "signIn",
               requestId: "..."
           })
       );

========================================================= */

public final class NativeAuthBridge
        implements WebViewCompat.WebMessageListener {

    /* =====================================================
       JAVASCRIPT OBJECT NAME
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


    /* =====================================================
       LIMITS
    ===================================================== */

    private static final int MAX_MESSAGE_LENGTH =
            8192;

    private static final int MAX_REQUEST_ID_LENGTH =
            128;


    /* =====================================================
       DEPENDENCIES
    ===================================================== */

    private final WebView webView;

    private final GoogleAuthManager
            googleAuthManager;


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
       ATTACH BRIDGE

       The object is injected ONLY into frames matching:

           https://11play.github.io

       We additionally reject:
       - non-main-frame messages
       - unexpected source origins
    ===================================================== */

    public boolean attach() {

        if (attached) {
            return true;
        }

        if (
                !WebViewFeature.isFeatureSupported(
                        WebViewFeature
                                .WEB_MESSAGE_LISTENER
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
       RECEIVE MESSAGE FROM JAVASCRIPT
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
                    "Authentication requests are only allowed from the main 11Play page."
            );

            return;
        }


        /* =============================================
           VERIFY SOURCE ORIGIN
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
                    "Authentication request origin is not allowed."
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
                    "Invalid authentication message."
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
                    "Authentication message is empty."
            );

            return;
        }


        /* =============================================
           MESSAGE SIZE LIMIT
        ============================================== */

        if (
                rawMessage.length() >
                        MAX_MESSAGE_LENGTH
        ) {

            sendError(
                    replyProxy,
                    "",
                    "",
                    "message-too-large",
                    "Authentication message is too large."
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
                    "Authentication message is invalid."
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
                    "Authentication action is missing."
            );

            return;
        }


        /* =============================================
           ACTION ROUTING
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


            default:

                sendError(
                        replyProxy,
                        action,
                        requestId,
                        "unsupported-action",
                        "Unsupported authentication action."
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
                    "native-auth-ready"
            );

            response.put(
                    "bridge",
                    JS_OBJECT_NAME
            );

            response.put(
                    "signedIn",
                    googleAuthManager
                            .isSignedIn()
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
       CURRENT AUTH STATE
    ===================================================== */

    private void handleGetAuthState(
            JavaScriptReplyProxy replyProxy,
            String requestId
    ) {

        FirebaseUser firebaseUser =
                googleAuthManager
                        .getCurrentUser();

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
                    firebaseUser !=
                            null
            ) {

                response.put(
                        "user",
                        buildUserJson(
                                firebaseUser
                        )
                );
            }

        } catch (Exception ignored) {
            // Send base response.
        }

        sendResponse(
                replyProxy,
                response
        );
    }


    /* =====================================================
       GOOGLE SIGN-IN

       On success the Google ID token is returned to the
       official 11Play page.

       The injected native-auth-bridge.js file will later
       use this token to establish the Firebase Web SDK
       session inside the WebView.
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
                                googleIdToken
                                        .trim()
                                        .isEmpty()
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

                            /*
                             * This token is deliberately returned
                             * only through the origin-restricted
                             * bridge to the official 11Play page.
                             */
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

                        /*
                         * Not expected during sign-in.
                         * Nothing is required here.
                         */
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
                        // Not expected during sign-out.
                    }


                    @Override
                    public void onGoogleSignInError(
                            String code,
                            String message
                    ) {

                        /*
                         * GoogleAuthManager currently treats
                         * Credential Manager cleanup failure as
                         * successful logout because Firebase has
                         * already been signed out.

                         * This callback is retained for future
                         * compatibility.
                         */

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
       FIREBASE USER JSON
    ===================================================== */

    private JSONObject buildUserJson(
            FirebaseUser firebaseUser
    ) {

        JSONObject user =
                new JSONObject();

        if (
                firebaseUser ==
                        null
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
                    firebaseUser
                            .isEmailVerified()
            );


            user.put(
                    "anonymous",
                    firebaseUser
                            .isAnonymous()
            );


        } catch (Exception ignored) {
            // Return whatever safe fields were created.
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
            // JSONObject remains usable.
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
                    "auth-error"
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
                sourceOrigin ==
                        null
        ) {
            return false;
        }

        String scheme =
                safeLower(
                        sourceOrigin
                                .getScheme()
                );

        String host =
                safeLower(
                        sourceOrigin
                                .getHost()
                );

        int port =
                sourceOrigin
                        .getPort();


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


        /*
         * Android Uri returns -1 when the standard HTTPS
         * port is omitted from the URL.
         */
        return port == -1 ||
                port == 443;
    }


    /* =====================================================
       HELPERS
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
            // Ignore optional field.
        }
    }


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


    private String safeErrorCode(
            String value
    ) {

        String code =
                safeString(
                        value
                );

        if (code.isEmpty()) {
            return "native-auth-error";
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
            return "Authentication could not be completed.";
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
                value ==
                        null
        ) {
            return "";
        }

        return String.valueOf(
                value
        ).trim();
    }


    /* =====================================================
       DETACH / CLEANUP
    ===================================================== */

    public void detach() {

        if (!attached) {
            return;
        }

        if (
                WebViewFeature.isFeatureSupported(
                        WebViewFeature
                                .WEB_MESSAGE_LISTENER
                )
        ) {

            try {

                WebViewCompat
                        .removeWebMessageListener(
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
