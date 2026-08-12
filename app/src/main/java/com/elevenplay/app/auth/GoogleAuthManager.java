package com.elevenplay.app.auth;

import android.app.Activity;
import android.os.Bundle;
import android.os.CancellationSignal;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;

import com.elevenplay.app.R;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* =========================================================
   11PLAY — GOOGLE AUTH MANAGER

   Responsibilities:
   - Launch native Google account selection
   - Support both Google Sign-In and first-time Sign-Up
   - Retrieve Google ID token through Credential Manager
   - Authenticate the selected Google account with Firebase
   - Return the Google ID token to the app bridge
   - Keep native Firebase authentication state available
   - Sign out safely
   - Clear Credential Manager session state on logout

   Important:
   - Uses the WEB OAuth Client ID generated as:
       R.string.default_web_client_id
   - google-services.json must later be added to:
       11PlayApp/app/google-services.json
   - Firebase Console must contain this Android app:
       com.elevenplay.app
   - SHA-1 must later be registered in Firebase.
========================================================= */

public final class GoogleAuthManager {

    /* =====================================================
       CALLBACK
    ===================================================== */

    public interface Listener {

        void onGoogleSignInSuccess(
                String googleIdToken,
                FirebaseUser firebaseUser
        );

        void onGoogleSignInError(
                String code,
                String message
        );

        void onGoogleSignOut();
    }


    /* =====================================================
       DEPENDENCIES
    ===================================================== */

    private final Activity activity;

    private final FirebaseAuth firebaseAuth;

    private final CredentialManager credentialManager;

    private final ExecutorService executorService;


    /* =====================================================
       STATE
    ===================================================== */

    private CancellationSignal
            activeCancellationSignal =
            null;

    private boolean signInInProgress =
            false;


    /* =====================================================
       CONSTRUCTOR
    ===================================================== */

    public GoogleAuthManager(
            Activity activity
    ) {
        if (activity == null) {
            throw new IllegalArgumentException(
                    "Activity is required."
            );
        }

        this.activity =
                activity;

        this.firebaseAuth =
                FirebaseAuth.getInstance();

        this.credentialManager =
                CredentialManager.create(
                        activity
                );

        this.executorService =
                Executors
                        .newSingleThreadExecutor();
    }


    /* =====================================================
       CURRENT FIREBASE USER
    ===================================================== */

    public FirebaseUser getCurrentUser() {
        return firebaseAuth
                .getCurrentUser();
    }


    public boolean isSignedIn() {
        return getCurrentUser() !=
                null;
    }


    /* =====================================================
       GOOGLE SIGN-IN / SIGN-UP

       filterByAuthorizedAccounts(false):

       This is intentional.

       It allows:
       - Existing authorized Google accounts
       - Google accounts that have not yet used 11Play

       Therefore the same flow supports both:
       Sign In + Sign Up.
    ===================================================== */

    public void signIn(
            Listener listener
    ) {
        if (
                signInInProgress
        ) {
            notifyError(
                    listener,
                    "sign-in-in-progress",
                    "Google Sign-In is already in progress."
            );

            return;
        }

        String webClientId;

        try {
            webClientId =
                    activity.getString(
                            R.string
                                    .default_web_client_id
                    );

        } catch (Exception error) {

            notifyError(
                    listener,
                    "missing-web-client-id",
                    "Google Sign-In configuration is missing."
            );

            return;
        }

        if (
                webClientId == null ||
                webClientId.trim().isEmpty()
        ) {
            notifyError(
                    listener,
                    "missing-web-client-id",
                    "Google Sign-In configuration is missing."
            );

            return;
        }


        /* =============================================
           GOOGLE ACCOUNT OPTION
        ============================================== */

        GetGoogleIdOption googleIdOption;

        try {
            googleIdOption =
                    new GetGoogleIdOption
                            .Builder()

                            /*
                             * false allows new users as well as
                             * previously authorized users.
                             */
                            .setFilterByAuthorizedAccounts(
                                    false
                            )

                            /*
                             * This MUST be the Web OAuth
                             * Client ID, not the Android
                             * OAuth Client ID.
                             */
                            .setServerClientId(
                                    webClientId
                            )

                            .setAutoSelectEnabled(
                                    false
                            )

                            .build();

        } catch (Exception error) {

            notifyError(
                    listener,
                    "google-request-failed",
                    safeMessage(
                            error,
                            "Google Sign-In could not be initialized."
                    )
            );

            return;
        }


        /* =============================================
           CREDENTIAL REQUEST
        ============================================== */

        GetCredentialRequest request =
                new GetCredentialRequest
                        .Builder()
                        .addCredentialOption(
                                googleIdOption
                        )
                        .build();


        signInInProgress =
                true;

        activeCancellationSignal =
                new CancellationSignal();


        /* =============================================
           OPEN NATIVE GOOGLE ACCOUNT PICKER
        ============================================== */

        credentialManager
                .getCredentialAsync(
                        activity,
                        request,
                        activeCancellationSignal,
                        executorService,

                        new CredentialManagerCallback<
                                GetCredentialResponse,
                                GetCredentialException
                                >() {

                            @Override
                            public void onResult(
                                    GetCredentialResponse result
                            ) {
                                activity.runOnUiThread(
                                        () -> {
                                            signInInProgress =
                                                    false;

                                            activeCancellationSignal =
                                                    null;

                                            if (
                                                    result ==
                                                            null
                                            ) {
                                                notifyError(
                                                        listener,
                                                        "empty-credential",
                                                        "Google Sign-In returned no credential."
                                                );

                                                return;
                                            }

                                            handleCredential(
                                                    result.getCredential(),
                                                    listener
                                            );
                                        }
                                );
                            }


                            @Override
                            public void onError(
                                    GetCredentialException error
                            ) {
                                activity.runOnUiThread(
                                        () -> {
                                            signInInProgress =
                                                    false;

                                            activeCancellationSignal =
                                                    null;

                                            notifyError(
                                                    listener,
                                                    resolveCredentialErrorCode(
                                                            error
                                                    ),
                                                    safeMessage(
                                                            error,
                                                            "Google Sign-In could not be completed."
                                                    )
                                            );
                                        }
                                );
                            }
                        }
                );
    }


    /* =====================================================
       HANDLE GOOGLE CREDENTIAL
    ===================================================== */

    private void handleCredential(
            Credential credential,
            Listener listener
    ) {
        if (
                !(credential instanceof
                        CustomCredential)
        ) {
            notifyError(
                    listener,
                    "unsupported-credential",
                    "The selected credential is not a Google credential."
            );

            return;
        }

        CustomCredential customCredential =
                (CustomCredential)
                        credential;

        if (
                !GoogleIdTokenCredential
                        .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(
                                customCredential
                                        .getType()
                        )
        ) {
            notifyError(
                    listener,
                    "unsupported-credential",
                    "The selected credential is not a Google ID credential."
            );

            return;
        }


        try {
            Bundle credentialData =
                    customCredential
                            .getData();

            GoogleIdTokenCredential
                    googleCredential =
                    GoogleIdTokenCredential
                            .createFrom(
                                    credentialData
                            );

            String googleIdToken =
                    googleCredential
                            .getIdToken();

            if (
                    googleIdToken == null ||
                    googleIdToken
                            .trim()
                            .isEmpty()
            ) {
                notifyError(
                        listener,
                        "missing-id-token",
                        "Google Sign-In did not return an ID token."
                );

                return;
            }

            firebaseAuthWithGoogle(
                    googleIdToken,
                    listener
            );

        } catch (
                GoogleIdTokenParsingException error
        ) {
            notifyError(
                    listener,
                    "invalid-google-token",
                    "Google returned an invalid ID token."
            );

        } catch (
                Exception error
        ) {
            notifyError(
                    listener,
                    "google-token-error",
                    safeMessage(
                            error,
                            "Google account information could not be processed."
                    )
            );
        }
    }


    /* =====================================================
       FIREBASE AUTHENTICATION

       Google ID token is exchanged for a Firebase
       Google credential.

       After success:
       - Native FirebaseUser exists
       - Google ID token is returned to NativeAuthBridge
       - The bridge can establish the matching Firebase
         JavaScript session inside the WebView
    ===================================================== */

    private void firebaseAuthWithGoogle(
            String googleIdToken,
            Listener listener
    ) {
        AuthCredential firebaseCredential =
                GoogleAuthProvider
                        .getCredential(
                                googleIdToken,
                                null
                        );

        firebaseAuth
                .signInWithCredential(
                        firebaseCredential
                )
                .addOnCompleteListener(
                        activity,
                        task -> {

                            if (
                                    !task.isSuccessful()
                            ) {
                                notifyError(
                                        listener,
                                        "firebase-auth-failed",
                                        safeMessage(
                                                task.getException(),
                                                "Firebase authentication could not be completed."
                                        )
                                );

                                return;
                            }

                            FirebaseUser firebaseUser =
                                    firebaseAuth
                                            .getCurrentUser();

                            if (
                                    firebaseUser ==
                                            null
                            ) {
                                notifyError(
                                        listener,
                                        "firebase-user-missing",
                                        "Firebase authentication completed without a user account."
                                );

                                return;
                            }

                            if (
                                    listener !=
                                            null
                            ) {
                                listener
                                        .onGoogleSignInSuccess(
                                                googleIdToken,
                                                firebaseUser
                                        );
                            }
                        }
                );
    }


    /* =====================================================
       SIGN OUT
    ===================================================== */

    public void signOut(
            Listener listener
    ) {
        cancelActiveSignIn();

        /*
         * Clear native Firebase session first.
         */
        firebaseAuth
                .signOut();


        ClearCredentialStateRequest request =
                new ClearCredentialStateRequest();


        credentialManager
                .clearCredentialStateAsync(
                        request,
                        new CancellationSignal(),
                        executorService,

                        new CredentialManagerCallback<
                                Void,
                                ClearCredentialException
                                >() {

                            @Override
                            public void onResult(
                                    Void result
                            ) {
                                activity.runOnUiThread(
                                        () -> {
                                            if (
                                                    listener !=
                                                            null
                                            ) {
                                                listener
                                                        .onGoogleSignOut();
                                            }
                                        }
                                );
                            }


                            @Override
                            public void onError(
                                    ClearCredentialException error
                            ) {
                                /*
                                 * Firebase is already signed out.
                                 *
                                 * Credential Manager cleanup
                                 * failure should therefore not
                                 * restore the Firebase session.
                                 */

                                activity.runOnUiThread(
                                        () -> {
                                            if (
                                                    listener !=
                                                            null
                                            ) {
                                                listener
                                                        .onGoogleSignOut();
                                            }
                                        }
                                );
                            }
                        }
                );
    }


    /* =====================================================
       CANCEL SIGN-IN
    ===================================================== */

    public void cancelActiveSignIn() {
        if (
                activeCancellationSignal !=
                        null
        ) {
            try {
                activeCancellationSignal
                        .cancel();
            } catch (
                    Exception ignored
            ) {
                // Nothing else required.
            }
        }

        activeCancellationSignal =
                null;

        signInInProgress =
                false;
    }


    /* =====================================================
       ERROR HELPERS
    ===================================================== */

    private String resolveCredentialErrorCode(
            GetCredentialException error
    ) {
        if (
                error ==
                        null
        ) {
            return "google-sign-in-failed";
        }

        String className =
                error.getClass()
                        .getSimpleName()
                        .toLowerCase();

        if (
                className.contains(
                        "cancellation"
                ) ||
                className.contains(
                        "cancelled"
                )
        ) {
            return "google-sign-in-cancelled";
        }

        if (
                className.contains(
                        "nocredential"
                )
        ) {
            return "no-google-account";
        }

        if (
                className.contains(
                        "providerconfiguration"
                )
        ) {
            return "google-provider-configuration";
        }

        return "google-sign-in-failed";
    }


    private void notifyError(
            Listener listener,
            String code,
            String message
    ) {
        if (
                listener ==
                        null
        ) {
            return;
        }

        activity.runOnUiThread(
                () ->
                        listener
                                .onGoogleSignInError(
                                        code == null
                                                ? "unknown"
                                                : code,

                                        message == null
                                                ? "Google Sign-In failed."
                                                : message
                                )
        );
    }


    private String safeMessage(
            Throwable error,
            String fallback
    ) {
        if (
                error ==
                        null
        ) {
            return fallback;
        }

        String message =
                error.getLocalizedMessage();

        if (
                message ==
                        null ||
                message.trim()
                        .isEmpty()
        ) {
            return fallback;
        }

        return message.trim();
    }


    /* =====================================================
       CLEANUP
    ===================================================== */

    public void destroy() {
        cancelActiveSignIn();

        try {
            executorService
                    .shutdownNow();
        } catch (
                Exception ignored
        ) {
            // Nothing else required.
        }
    }
}
