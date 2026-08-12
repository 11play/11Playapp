package com.elevenplay.app.auth;

import android.app.Activity;
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
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* =========================================================
   11PLAY — GOOGLE AUTH MANAGER

   Responsibilities:
   - Launch native Google account chooser
   - Support Google Sign-In and first-time Sign-Up
   - Retrieve Google ID token through Credential Manager
   - Sign the selected Google account into Firebase
   - Return the Google ID token to NativeAuthBridge
   - Maintain native Firebase authentication state
   - Sign out from Firebase
   - Clear Credential Manager state

   Important:
   - Uses R.string.default_web_client_id generated from
     app/google-services.json.
   - Android package:
       com.elevenplay.app
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

    private CancellationSignal activeCancellationSignal;

    private boolean signInInProgress;


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
                Executors.newSingleThreadExecutor();

        this.activeCancellationSignal =
                null;

        this.signInInProgress =
                false;
    }


    /* =====================================================
       CURRENT USER
    ===================================================== */

    public FirebaseUser getCurrentUser() {

        return firebaseAuth
                .getCurrentUser();
    }


    public boolean isSignedIn() {

        return getCurrentUser() != null;
    }


    /* =====================================================
       GOOGLE SIGN-IN / SIGN-UP
    ===================================================== */

    public void signIn(
            Listener listener
    ) {

        if (signInInProgress) {

            notifyError(
                    listener,
                    "sign-in-in-progress",
                    "Google Sign-In is already in progress."
            );

            return;
        }


        /* =============================================
           WEB CLIENT ID
        ============================================== */

        final String webClientId;

        try {

            webClientId =
                    activity.getString(
                            R.string.default_web_client_id
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

           false means:
           - previously authorized accounts
           - new Google accounts

           Therefore this supports:
           Sign In + Sign Up.
        ============================================== */

        final GetGoogleIdOption googleIdOption;

        try {

            googleIdOption =
                    new GetGoogleIdOption.Builder()

                            .setFilterByAuthorizedAccounts(
                                    false
                            )

                            .setServerClientId(
                                    webClientId.trim()
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

        final GetCredentialRequest request;

        try {

            request =
                    new GetCredentialRequest.Builder()

                            .addCredentialOption(
                                    googleIdOption
                            )

                            .build();

        } catch (Exception error) {

            notifyError(
                    listener,
                    "credential-request-failed",
                    safeMessage(
                            error,
                            "Google credential request could not be created."
                    )
            );

            return;
        }


        /* =============================================
           START REQUEST
        ============================================== */

        signInInProgress =
                true;

        activeCancellationSignal =
                new CancellationSignal();


        try {

            credentialManager.getCredentialAsync(
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


                                        if (result == null) {

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

        } catch (Exception error) {

            signInInProgress =
                    false;

            activeCancellationSignal =
                    null;


            notifyError(
                    listener,
                    "credential-manager-failed",
                    safeMessage(
                            error,
                            "Google account chooser could not be opened."
                    )
            );
        }
    }


    /* =====================================================
       HANDLE RETURNED CREDENTIAL
    ===================================================== */

    private void handleCredential(
            Credential credential,
            Listener listener
    ) {

        if (
                !(credential instanceof CustomCredential)
        ) {

            notifyError(
                    listener,
                    "unsupported-credential",
                    "The selected credential is not a Google credential."
            );

            return;
        }


        CustomCredential customCredential =
                (CustomCredential) credential;


        if (
                !GoogleIdTokenCredential
                        .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(
                                customCredential.getType()
                        )
        ) {

            notifyError(
                    listener,
                    "unsupported-credential",
                    "The selected credential is not a Google ID credential."
            );

            return;
        }


        /*
         * IMPORTANT:
         *
         * Do NOT catch GoogleIdTokenParsingException directly
         * from Java here.
         *
         * GoogleIdTokenCredential.createFrom(Bundle) does not
         * declare that checked exception in its Java signature.
         *
         * Runtime parsing problems are safely handled by the
         * generic Exception catch below.
         */

        try {

            GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(
                            customCredential.getData()
                    );


            String googleIdToken =
                    googleCredential.getIdToken();


            if (
                    googleIdToken == null ||
                    googleIdToken.trim().isEmpty()
            ) {

                notifyError(
                        listener,
                        "missing-id-token",
                        "Google Sign-In did not return an ID token."
                );

                return;
            }


            firebaseAuthWithGoogle(
                    googleIdToken.trim(),
                    listener
            );

        } catch (Exception error) {

            String exceptionName =
                    error.getClass()
                            .getSimpleName();


            if (
                    exceptionName != null &&
                    exceptionName
                            .toLowerCase(Locale.ROOT)
                            .contains(
                                    "googleidtokenparsing"
                            )
            ) {

                notifyError(
                        listener,
                        "invalid-google-token",
                        "Google returned an invalid ID token."
                );

                return;
            }


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
    ===================================================== */

    private void firebaseAuthWithGoogle(
            String googleIdToken,
            Listener listener
    ) {

        final AuthCredential firebaseCredential;

        try {

            firebaseCredential =
                    GoogleAuthProvider.getCredential(
                            googleIdToken,
                            null
                    );

        } catch (Exception error) {

            notifyError(
                    listener,
                    "firebase-credential-failed",
                    safeMessage(
                            error,
                            "Firebase Google credential could not be created."
                    )
            );

            return;
        }


        firebaseAuth
                .signInWithCredential(
                        firebaseCredential
                )
                .addOnCompleteListener(
                        activity,
                        task -> {

                            if (!task.isSuccessful()) {

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


                            if (firebaseUser == null) {

                                notifyError(
                                        listener,
                                        "firebase-user-missing",
                                        "Firebase authentication completed without a user account."
                                );

                                return;
                            }


                            if (listener != null) {

                                listener.onGoogleSignInSuccess(
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


        /* =============================================
           FIREBASE LOGOUT
        ============================================== */

        try {

            firebaseAuth.signOut();

        } catch (Exception ignored) {
            // Continue Credential Manager cleanup.
        }


        /* =============================================
           CLEAR CREDENTIAL MANAGER STATE
        ============================================== */

        final ClearCredentialStateRequest request;

        try {

            request =
                    new ClearCredentialStateRequest();

        } catch (Exception error) {

            notifySignOutComplete(
                    listener
            );

            return;
        }


        try {

            credentialManager.clearCredentialStateAsync(
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
                                    () ->
                                            notifySignOutComplete(
                                                    listener
                                            )
                            );
                        }


                        @Override
                        public void onError(
                                ClearCredentialException error
                        ) {

                            /*
                             * Firebase has already been signed out.
                             * Credential Manager cleanup failure
                             * must not restore the Firebase session.
                             */

                            activity.runOnUiThread(
                                    () ->
                                            notifySignOutComplete(
                                                    listener
                                            )
                            );
                        }
                    }
            );

        } catch (Exception ignored) {

            notifySignOutComplete(
                    listener
            );
        }
    }


    private void notifySignOutComplete(
            Listener listener
    ) {

        if (listener == null) {
            return;
        }


        activity.runOnUiThread(
                listener::onGoogleSignOut
        );
    }


    /* =====================================================
       CANCEL ACTIVE LOGIN
    ===================================================== */

    public void cancelActiveSignIn() {

        CancellationSignal signal =
                activeCancellationSignal;


        activeCancellationSignal =
                null;

        signInInProgress =
                false;


        if (signal == null) {
            return;
        }


        try {

            signal.cancel();

        } catch (Exception ignored) {
            // Nothing else required.
        }
    }


    /* =====================================================
       CREDENTIAL ERROR CODE
    ===================================================== */

    private String resolveCredentialErrorCode(
            GetCredentialException error
    ) {

        if (error == null) {

            return "google-sign-in-failed";
        }


        String className =
                error.getClass()
                        .getSimpleName();


        if (className == null) {

            return "google-sign-in-failed";
        }


        String normalized =
                className.toLowerCase(
                        Locale.ROOT
                );


        if (
                normalized.contains(
                        "cancellation"
                ) ||
                normalized.contains(
                        "cancelled"
                )
        ) {

            return "google-sign-in-cancelled";
        }


        if (
                normalized.contains(
                        "nocredential"
                )
        ) {

            return "no-google-account";
        }


        if (
                normalized.contains(
                        "providerconfiguration"
                )
        ) {

            return "google-provider-configuration";
        }


        return "google-sign-in-failed";
    }


    /* =====================================================
       ERROR CALLBACK
    ===================================================== */

    private void notifyError(
            Listener listener,
            String code,
            String message
    ) {

        if (listener == null) {
            return;
        }


        String safeCode =
                code == null ||
                code.trim().isEmpty()
                        ? "native-auth-error"
                        : code.trim();


        String safeMessage =
                message == null ||
                message.trim().isEmpty()
                        ? "Google Sign-In failed."
                        : message.trim();


        activity.runOnUiThread(
                () ->
                        listener.onGoogleSignInError(
                                safeCode,
                                safeMessage
                        )
        );
    }


    /* =====================================================
       SAFE ERROR MESSAGE
    ===================================================== */

    private String safeMessage(
            Throwable error,
            String fallback
    ) {

        if (error == null) {

            return fallback;
        }


        String message =
                error.getLocalizedMessage();


        if (
                message == null ||
                message.trim().isEmpty()
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

            executorService.shutdownNow();

        } catch (Exception ignored) {
            // Nothing else required.
        }
    }
}
