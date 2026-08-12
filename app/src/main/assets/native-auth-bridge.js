/* =========================================================
   11PLAY — NATIVE APP BRIDGE
   File: app/src/main/assets/native-auth-bridge.js

   Responsibilities:
   - Communicate with Android NativeAuthBridge
   - Handle native Google Sign-In for 11Play
   - Establish Firebase Web Auth session
   - Keep native Firebase + Web Firebase logout synchronized
   - Convert 11Play navigator.share() to Android Sharesheet
   - Handle 11Play Invite Your Friend share button
   - Never affect third-party websites

   Native object:
       window.ElevenPlayNativeAuth

   Public API:
       window.ElevenPlayAppAuth

   Supported native actions:
       ping
       getAuthState
       signIn
       signOut
       share

   Important:
   - This script is injected only on:
       https://11play.github.io
   - Google ID tokens are never logged.
========================================================= */

(function () {
    "use strict";


    /* =====================================================
       PREVENT DUPLICATE INITIALIZATION
    ===================================================== */

    if (window.__ELEVENPLAY_NATIVE_APP_BRIDGE__) {
        return;
    }

    window.__ELEVENPLAY_NATIVE_APP_BRIDGE__ = true;


    /* =====================================================
       CONSTANTS
    ===================================================== */

    const NATIVE_OBJECT_NAME =
        "ElevenPlayNativeAuth";

    const FIREBASE_WAIT_TIMEOUT =
        15000;

    const FIREBASE_CHECK_INTERVAL =
        100;

    const REQUEST_TIMEOUT =
        60000;


    /* =====================================================
       INTERNAL STATE
    ===================================================== */

    const pendingRequests =
        new Map();

    let firebaseAuthInstance =
        null;

    let firebasePatched =
        false;

    let originalSignInWithPopup =
        null;

    let originalSignInWithRedirect =
        null;

    let originalSignOut =
        null;

    let originalSignInWithCredential =
        null;

    let originalNavigatorShare =
        null;

    let nativeShareInstalled =
        false;

    let inviteClickInstalled =
        false;


    /* =====================================================
       BASIC HELPERS
    ===================================================== */

    function safeString(value) {

        if (
            value === null ||
            value === undefined
        ) {
            return "";
        }

        return String(value).trim();
    }


    function createRequestId() {

        const randomPart =
            Math.random()
                .toString(36)
                .slice(2);

        const timePart =
            Date.now()
                .toString(36);

        return (
            "req_" +
            timePart +
            "_" +
            randomPart
        );
    }


    function createError(
        code,
        message
    ) {

        const error =
            new Error(
                safeString(message) ||
                "Operation could not be completed."
            );

        error.code =
            safeString(code) ||
            "native-error";

        return error;
    }


    function nativeBridgeAvailable() {

        const bridge =
            window[NATIVE_OBJECT_NAME];

        return Boolean(
            bridge &&
            typeof bridge.postMessage ===
                "function"
        );
    }


    /* =====================================================
       NATIVE REQUEST
    ===================================================== */

    function sendNativeRequest(
        action,
        payload
    ) {

        return new Promise(
            function (
                resolve,
                reject
            ) {

                if (
                    !nativeBridgeAvailable()
                ) {

                    reject(
                        createError(
                            "native-bridge-unavailable",
                            "Native app bridge is unavailable."
                        )
                    );

                    return;
                }


                const requestId =
                    createRequestId();


                const request =
                    Object.assign(
                        {},
                        payload || {},
                        {
                            action:
                                safeString(action),

                            requestId:
                                requestId
                        }
                    );


                const timeoutId =
                    window.setTimeout(
                        function () {

                            if (
                                !pendingRequests.has(
                                    requestId
                                )
                            ) {
                                return;
                            }


                            pendingRequests.delete(
                                requestId
                            );


                            reject(
                                createError(
                                    "native-request-timeout",
                                    "Native request timed out."
                                )
                            );

                        },
                        REQUEST_TIMEOUT
                    );


                pendingRequests.set(
                    requestId,
                    {
                        resolve:
                            resolve,

                        reject:
                            reject,

                        timeoutId:
                            timeoutId
                    }
                );


                try {

                    window[NATIVE_OBJECT_NAME]
                        .postMessage(
                            JSON.stringify(
                                request
                            )
                        );

                } catch (error) {

                    window.clearTimeout(
                        timeoutId
                    );

                    pendingRequests.delete(
                        requestId
                    );


                    reject(
                        createError(
                            "native-message-failed",
                            error &&
                            error.message
                                ? error.message
                                : "Could not communicate with the Android app."
                        )
                    );
                }
            }
        );
    }


    /* =====================================================
       NATIVE RESPONSE
    ===================================================== */

    function handleNativeMessage(event) {

        let response =
            null;


        try {

            if (
                event &&
                typeof event.data ===
                    "string"
            ) {

                response =
                    JSON.parse(
                        event.data
                    );

            } else if (
                event &&
                event.data &&
                typeof event.data ===
                    "object"
            ) {

                response =
                    event.data;
            }

        } catch (error) {

            return;
        }


        if (
            !response ||
            typeof response !==
                "object"
        ) {

            return;
        }


        const requestId =
            safeString(
                response.requestId
            );


        if (
            !requestId ||
            !pendingRequests.has(
                requestId
            )
        ) {

            return;
        }


        const pending =
            pendingRequests.get(
                requestId
            );


        pendingRequests.delete(
            requestId
        );


        if (
            pending.timeoutId
        ) {

            window.clearTimeout(
                pending.timeoutId
            );
        }


        if (
            response.ok === true
        ) {

            pending.resolve(
                response
            );

            return;
        }


        pending.reject(
            createError(
                response.code,
                response.message
            )
        );
    }


    /* =====================================================
       ATTACH NATIVE RECEIVER
    ===================================================== */

    function attachNativeReceiver() {

        if (
            !nativeBridgeAvailable()
        ) {

            return false;
        }


        try {

            window[NATIVE_OBJECT_NAME]
                .onmessage =
                handleNativeMessage;


            return true;

        } catch (error) {

            return false;
        }
    }


    /* =====================================================
       FIREBASE AUTH DETECTION
    ===================================================== */

    function getFirebaseAuth() {

        try {

            if (
                !window.firebase ||
                typeof window.firebase.auth !==
                    "function"
            ) {

                return null;
            }


            const auth =
                window.firebase.auth();


            return auth || null;

        } catch (error) {

            return null;
        }
    }


    function waitForFirebaseAuth() {

        return new Promise(
            function (
                resolve,
                reject
            ) {

                const existingAuth =
                    getFirebaseAuth();


                if (existingAuth) {

                    resolve(
                        existingAuth
                    );

                    return;
                }


                const startedAt =
                    Date.now();


                const timer =
                    window.setInterval(
                        function () {

                            const auth =
                                getFirebaseAuth();


                            if (auth) {

                                window.clearInterval(
                                    timer
                                );

                                resolve(
                                    auth
                                );

                                return;
                            }


                            if (
                                Date.now() -
                                startedAt >=
                                FIREBASE_WAIT_TIMEOUT
                            ) {

                                window.clearInterval(
                                    timer
                                );


                                reject(
                                    createError(
                                        "firebase-auth-unavailable",
                                        "Firebase authentication is unavailable."
                                    )
                                );
                            }

                        },
                        FIREBASE_CHECK_INTERVAL
                    );
            }
        );
    }


    /* =====================================================
       GOOGLE PROVIDER CHECK
    ===================================================== */

    function isGoogleProvider(
        provider
    ) {

        if (!provider) {
            return false;
        }


        return safeString(
            provider.providerId
        ) === "google.com";
    }


    /* =====================================================
       FIREBASE WEB SESSION
    ===================================================== */

    async function authenticateFirebaseWeb(
        googleIdToken
    ) {

        const idToken =
            safeString(
                googleIdToken
            );


        if (!idToken) {

            throw createError(
                "missing-google-token",
                "Google authentication returned no ID token."
            );
        }


        const auth =
            await waitForFirebaseAuth();


        if (
            !window.firebase ||
            !window.firebase.auth ||
            !window.firebase.auth
                .GoogleAuthProvider
        ) {

            throw createError(
                "google-provider-unavailable",
                "Firebase Google provider is unavailable."
            );
        }


        const credential =
            window.firebase
                .auth
                .GoogleAuthProvider
                .credential(
                    idToken
                );


        if (!credential) {

            throw createError(
                "firebase-credential-failed",
                "Firebase Google credential could not be created."
            );
        }


        if (
            auth ===
                firebaseAuthInstance &&
            typeof originalSignInWithCredential ===
                "function"
        ) {

            return await originalSignInWithCredential(
                credential
            );
        }


        if (
            typeof auth.signInWithCredential !==
                "function"
        ) {

            throw createError(
                "firebase-sign-in-unavailable",
                "Firebase credential sign-in is unavailable."
            );
        }


        return await auth.signInWithCredential(
            credential
        );
    }


    /* =====================================================
       NATIVE GOOGLE SIGN-IN
    ===================================================== */

    async function signInWithGoogle() {

        attachNativeReceiver();


        const nativeResponse =
            await sendNativeRequest(
                "signIn"
            );


        const googleIdToken =
            safeString(
                nativeResponse &&
                nativeResponse.googleIdToken
            );


        if (!googleIdToken) {

            throw createError(
                "missing-google-token",
                "Google Sign-In completed without an ID token."
            );
        }


        const userCredential =
            await authenticateFirebaseWeb(
                googleIdToken
            );


        dispatchEventSafe(
            "11play:native-auth-success",
            {
                user:
                    nativeResponse.user ||
                    null
            }
        );


        return userCredential;
    }


    /* =====================================================
       SIGN OUT
    ===================================================== */

    async function signOut() {

        const auth =
            getFirebaseAuth();


        if (
            auth &&
            typeof originalSignOut ===
                "function"
        ) {

            await originalSignOut();

        } else if (
            auth &&
            typeof auth.signOut ===
                "function" &&
            !firebasePatched
        ) {

            await auth.signOut();
        }


        if (
            nativeBridgeAvailable()
        ) {

            attachNativeReceiver();


            try {

                await sendNativeRequest(
                    "signOut"
                );

            } catch (error) {

                /*
                 * Web Firebase session is already signed out.
                 */
            }
        }


        dispatchEventSafe(
            "11play:native-auth-signout",
            {}
        );
    }


    /* =====================================================
       AUTH STATE
    ===================================================== */

    async function getNativeAuthState() {

        if (
            !nativeBridgeAvailable()
        ) {

            return {
                ok:
                    false,

                signedIn:
                    false,

                native:
                    false
            };
        }


        attachNativeReceiver();


        return await sendNativeRequest(
            "getAuthState"
        );
    }


    /* =====================================================
       PING
    ===================================================== */

    async function ping() {

        if (
            !nativeBridgeAvailable()
        ) {

            return {
                ok:
                    false,

                native:
                    false
            };
        }


        attachNativeReceiver();


        return await sendNativeRequest(
            "ping"
        );
    }


    /* =====================================================
       NATIVE SHARE
    ===================================================== */

    async function shareNative(
        shareData
    ) {

        if (
            !nativeBridgeAvailable()
        ) {

            throw createError(
                "native-share-unavailable",
                "Native sharing is unavailable."
            );
        }


        attachNativeReceiver();


        const source =
            (
                shareData &&
                typeof shareData ===
                    "object"
            )
                ? shareData
                : {};


        const title =
            safeString(
                source.title
            );


        const text =
            safeString(
                source.text
            );


        let url =
            safeString(
                source.url
            );


        if (!url) {

            url =
                window.location.href ||
                "https://11play.github.io/11play/";
        }


        return await sendNativeRequest(
            "share",
            {
                title:
                    title,

                text:
                    text,

                url:
                    url
            }
        );
    }


    /* =====================================================
       INSTALL navigator.share OVERRIDE

       11Play website may use:

           navigator.share({
               title: "...",
               text: "...",
               url: "..."
           });

       Inside APK this becomes Android native Sharesheet.

       This script exists ONLY on the official 11Play origin,
       so third-party websites remain untouched.
    ===================================================== */

    function installNativeShare() {

        if (
            nativeShareInstalled ||
            !nativeBridgeAvailable()
        ) {

            return;
        }


        nativeShareInstalled =
            true;


        try {

            if (
                navigator &&
                typeof navigator.share ===
                    "function"
            ) {

                originalNavigatorShare =
                    navigator.share.bind(
                        navigator
                    );
            }

        } catch (error) {

            originalNavigatorShare =
                null;
        }


        const nativeShareFunction =
            function (
                shareData
            ) {

                return shareNative(
                    shareData || {}
                ).then(
                    function () {

                        /*
                         * Web Share API resolves void.
                         */
                        return undefined;
                    }
                );
            };


        /*
         * navigator.share may be defined on Navigator's
         * prototype and may not always be directly writable.
         */

        try {

            Object.defineProperty(
                navigator,
                "share",
                {
                    value:
                        nativeShareFunction,

                    writable:
                        false,

                    configurable:
                        true
                }
            );

            return;

        } catch (error) {
            // Try prototype fallback.
        }


        try {

            const navigatorPrototype =
                Object.getPrototypeOf(
                    navigator
                );


            if (navigatorPrototype) {

                Object.defineProperty(
                    navigatorPrototype,
                    "share",
                    {
                        value:
                            nativeShareFunction,

                        writable:
                            false,

                        configurable:
                            true
                    }
                );
            }

        } catch (error) {
            // Invite click fallback below still works.
        }
    }


    /* =====================================================
       INVITE BUTTON FALLBACK

       Supports elements such as:

           data-share-action="invite"
           data-share-action="share"

       This is useful if the 11Play UI has a dedicated
       Invite Your Friend button.

       We intentionally do NOT intercept generic links or
       third-party website content.
    ===================================================== */

    function installInviteClickHandler() {

        if (inviteClickInstalled) {
            return;
        }


        inviteClickInstalled =
            true;


        document.addEventListener(
            "click",
            function (
                event
            ) {

                if (
                    !event ||
                    !nativeBridgeAvailable()
                ) {

                    return;
                }


                let target =
                    event.target;


                if (
                    target &&
                    target.nodeType ===
                        Node.TEXT_NODE
                ) {

                    target =
                        target.parentElement;
                }


                if (
                    !target ||
                    typeof target.closest !==
                        "function"
                ) {

                    return;
                }


                const shareElement =
                    target.closest(
                        "[data-share-action]"
                    );


                if (!shareElement) {

                    return;
                }


                const shareAction =
                    safeString(
                        shareElement.getAttribute(
                            "data-share-action"
                        )
                    ).toLowerCase();


                if (
                    shareAction !==
                        "invite" &&
                    shareAction !==
                        "share"
                ) {

                    return;
                }


                /*
                 * Prevent the browser/WebView fallback from
                 * opening or doing nothing.
                 */

                event.preventDefault();

                event.stopPropagation();

                if (
                    typeof event.stopImmediatePropagation ===
                        "function"
                ) {

                    event.stopImmediatePropagation();
                }


                const title =
                    safeString(
                        shareElement.getAttribute(
                            "data-share-title"
                        )
                    ) ||
                    safeString(
                        document.title
                    ) ||
                    "11Play";


                const text =
                    safeString(
                        shareElement.getAttribute(
                            "data-share-text"
                        )
                    ) ||
                    "11Play দেখুন";


                const url =
                    safeString(
                        shareElement.getAttribute(
                            "data-share-url"
                        )
                    ) ||
                    window.location.href ||
                    "https://11play.github.io/11play/";


                shareNative(
                    {
                        title:
                            title,

                        text:
                            text,

                        url:
                            url
                    }
                ).catch(
                    function () {
                        /*
                         * Native share failure should not crash
                         * the website.
                         */
                    }
                );

            },
            true
        );
    }


    /* =====================================================
       FIREBASE PATCH
    ===================================================== */

    function patchFirebaseAuth(
        auth
    ) {

        if (
            firebasePatched ||
            !auth
        ) {

            return;
        }


        firebaseAuthInstance =
            auth;


        /* =============================================
           SAVE ORIGINAL METHODS
        ============================================== */

        if (
            typeof auth.signInWithCredential ===
                "function"
        ) {

            originalSignInWithCredential =
                auth.signInWithCredential.bind(
                    auth
                );
        }


        if (
            typeof auth.signInWithPopup ===
                "function"
        ) {

            originalSignInWithPopup =
                auth.signInWithPopup.bind(
                    auth
                );
        }


        if (
            typeof auth.signInWithRedirect ===
                "function"
        ) {

            originalSignInWithRedirect =
                auth.signInWithRedirect.bind(
                    auth
                );
        }


        if (
            typeof auth.signOut ===
                "function"
        ) {

            originalSignOut =
                auth.signOut.bind(
                    auth
                );
        }


        /* =============================================
           GOOGLE POPUP → NATIVE GOOGLE
        ============================================== */

        if (
            typeof originalSignInWithPopup ===
                "function"
        ) {

            auth.signInWithPopup =
                function (
                    provider
                ) {

                    if (
                        nativeBridgeAvailable() &&
                        isGoogleProvider(
                            provider
                        )
                    ) {

                        return signInWithGoogle();
                    }


                    return originalSignInWithPopup(
                        provider
                    );
                };
        }


        /* =============================================
           GOOGLE REDIRECT → NATIVE GOOGLE
        ============================================== */

        if (
            typeof originalSignInWithRedirect ===
                "function"
        ) {

            auth.signInWithRedirect =
                function (
                    provider
                ) {

                    if (
                        nativeBridgeAvailable() &&
                        isGoogleProvider(
                            provider
                        )
                    ) {

                        return signInWithGoogle()
                            .then(
                                function () {

                                    return undefined;
                                }
                            );
                    }


                    return originalSignInWithRedirect(
                        provider
                    );
                };
        }


        /* =============================================
           WEB LOGOUT → NATIVE LOGOUT
        ============================================== */

        if (
            typeof originalSignOut ===
                "function"
        ) {

            auth.signOut =
                async function () {

                    await originalSignOut();


                    if (
                        nativeBridgeAvailable()
                    ) {

                        attachNativeReceiver();


                        try {

                            await sendNativeRequest(
                                "signOut"
                            );

                        } catch (error) {
                            // Web logout already succeeded.
                        }
                    }


                    dispatchEventSafe(
                        "11play:native-auth-signout",
                        {}
                    );
                };
        }


        firebasePatched =
            true;


        dispatchEventSafe(
            "11play:native-auth-ready",
            {
                native:
                    nativeBridgeAvailable(),

                firebase:
                    true
            }
        );
    }


    /* =====================================================
       FIREBASE WATCHER
    ===================================================== */

    function startFirebasePatchWatcher() {

        const existing =
            getFirebaseAuth();


        if (existing) {

            patchFirebaseAuth(
                existing
            );

            return;
        }


        const startedAt =
            Date.now();


        const timer =
            window.setInterval(
                function () {

                    const auth =
                        getFirebaseAuth();


                    if (auth) {

                        window.clearInterval(
                            timer
                        );


                        patchFirebaseAuth(
                            auth
                        );


                        return;
                    }


                    if (
                        Date.now() -
                        startedAt >=
                        FIREBASE_WAIT_TIMEOUT
                    ) {

                        window.clearInterval(
                            timer
                        );
                    }

                },
                FIREBASE_CHECK_INTERVAL
            );
    }


    /* =====================================================
       SAFE CUSTOM EVENT
    ===================================================== */

    function dispatchEventSafe(
        eventName,
        detail
    ) {

        try {

            window.dispatchEvent(
                new CustomEvent(
                    eventName,
                    {
                        detail:
                            detail || {}
                    }
                )
            );

        } catch (error) {
            // Optional event.
        }
    }


    /* =====================================================
       PUBLIC API
    ===================================================== */

    const publicApi =
        {

            isNativeApp:
                function () {

                    return nativeBridgeAvailable();
                },


            signInWithGoogle:
                signInWithGoogle,


            signOut:
                signOut,


            getAuthState:
                getNativeAuthState,


            ping:
                ping,


            share:
                shareNative
        };


    try {

        Object.defineProperty(
            window,
            "ElevenPlayAppAuth",
            {
                value:
                    publicApi,

                writable:
                    false,

                configurable:
                    false,

                enumerable:
                    false
            }
        );

    } catch (error) {

        window.ElevenPlayAppAuth =
            publicApi;
    }


    /* =====================================================
       INITIALIZE
    ===================================================== */

    attachNativeReceiver();

    installNativeShare();

    installInviteClickHandler();

    startFirebasePatchWatcher();


    dispatchEventSafe(
        "11play:native-app-bridge-loaded",
        {
            native:
                nativeBridgeAvailable(),

            share:
                nativeBridgeAvailable()
        }
    );

})();
