/* =========================================================
   11PLAY — NATIVE AUTH BRIDGE
   File: app/src/main/assets/native-auth-bridge.js

   Responsibilities:
   - Communicate with Android NativeAuthBridge
   - Launch native Google Sign-In from the WebView
   - Convert Google ID token into Firebase Web Auth session
   - Keep native Firebase Auth and Web Firebase Auth aligned
   - Intercept Firebase Google signInWithPopup inside APK
   - Synchronize Firebase Web sign-out with native sign-out
   - Expose a small safe API to the 11Play page

   Native bridge object:
       window.ElevenPlayNativeAuth

   Public helper:
       window.ElevenPlayAppAuth

   Important:
   - This script must only be injected on:
       https://11play.github.io
   - Never log Google ID tokens.
========================================================= */

(function () {
    "use strict";


    /* =====================================================
       PREVENT DUPLICATE INITIALIZATION
    ===================================================== */

    if (window.__ELEVENPLAY_NATIVE_AUTH_BRIDGE__) {
        return;
    }

    window.__ELEVENPLAY_NATIVE_AUTH_BRIDGE__ = true;


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


    /* =====================================================
       HELPERS
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
                "Authentication could not be completed."
            );

        error.code =
            safeString(code) ||
            "native-auth-error";

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
       SEND MESSAGE TO ANDROID
    ===================================================== */

    function sendNativeRequest(
        action,
        payload
    ) {
        return new Promise(
            (resolve, reject) => {

                if (
                    !nativeBridgeAvailable()
                ) {
                    reject(
                        createError(
                            "native-bridge-unavailable",
                            "Native authentication is unavailable."
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
                                    "native-auth-timeout",
                                    "Authentication request timed out."
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
       RECEIVE RESPONSE FROM ANDROID
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
            response.ok ===
                true
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
       ATTACH NATIVE MESSAGE RECEIVER
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
       FIREBASE DETECTION

       Current 11Play website uses Firebase Web Auth.

       We wait until the Firebase compatibility API exists:

           firebase.auth()
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


            if (!auth) {
                return null;
            }


            return auth;

        } catch (error) {

            return null;
        }
    }


    function waitForFirebaseAuth() {

        return new Promise(
            (resolve, reject) => {

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


        const providerId =
            safeString(
                provider.providerId
            );


        return providerId ===
            "google.com";
    }


    /* =====================================================
       CREATE FIREBASE WEB SESSION

       Android returns a Google ID token.

       Firebase Web SDK receives the same token:

           GoogleAuthProvider.credential(idToken)

       and creates the authenticated WebView Firebase session.
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


        /*
         * Use the original function when Firebase has
         * already been patched to avoid recursion.
         */

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


        dispatchAuthEvent(
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

       First close the Firebase Web SDK session.

       Then close Android native Firebase/Credential Manager
       authentication state.
    ===================================================== */

    async function signOut() {

        let auth =
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
                 * Web Firebase is already signed out.
                 *
                 * Do not restore the web session merely
                 * because native credential-state cleanup
                 * failed.
                 */
            }
        }


        dispatchAuthEvent(
            "11play:native-auth-signout",
            {}
        );
    }


    /* =====================================================
       NATIVE AUTH STATE
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
       CUSTOM EVENTS

       Existing or future website modules may listen to:

       11play:native-auth-ready
       11play:native-auth-success
       11play:native-auth-signout
    ===================================================== */

    function dispatchAuthEvent(
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
            // Optional event only.
        }
    }


    /* =====================================================
       PATCH FIREBASE GOOGLE POPUP

       Website code may currently call:

           firebase.auth()
               .signInWithPopup(
                   new firebase.auth.GoogleAuthProvider()
               )

       Google does not support normal OAuth login inside
       arbitrary embedded WebViews.

       Inside the Android APK only, we replace GOOGLE popup
       authentication with native Credential Manager.

       Non-Google providers retain Firebase's original
       behavior.
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
                auth.signInWithCredential
                    .bind(
                        auth
                    );
        }


        if (
            typeof auth.signInWithPopup ===
                "function"
        ) {

            originalSignInWithPopup =
                auth.signInWithPopup
                    .bind(
                        auth
                    );
        }


        if (
            typeof auth.signInWithRedirect ===
                "function"
        ) {

            originalSignInWithRedirect =
                auth.signInWithRedirect
                    .bind(
                        auth
                    );
        }


        if (
            typeof auth.signOut ===
                "function"
        ) {

            originalSignOut =
                auth.signOut
                    .bind(
                        auth
                    );
        }


        /* =============================================
           PATCH signInWithPopup
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
           PATCH signInWithRedirect

           For Google inside APK we perform native login
           immediately.

           The returned Promise resolves after the Firebase
           Web session exists.
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

                                    /*
                                     * Firebase redirect API
                                     * normally resolves void.
                                     */
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
           PATCH SIGN OUT

           Keeps native and Firebase Web sessions aligned.
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


                    dispatchAuthEvent(
                        "11play:native-auth-signout",
                        {}
                    );
                };
        }


        firebasePatched =
            true;


        dispatchAuthEvent(
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
       WAIT AND PATCH FIREBASE

       Script may run before Firebase scripts finish loading.
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
       PUBLIC API

       Available only inside the 11Play WebView page.
    ===================================================== */

    const publicApi = {

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
            ping
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

    startFirebasePatchWatcher();


    dispatchAuthEvent(
        "11play:native-auth-bridge-loaded",
        {
            native:
                nativeBridgeAvailable()
        }
    );

})();
