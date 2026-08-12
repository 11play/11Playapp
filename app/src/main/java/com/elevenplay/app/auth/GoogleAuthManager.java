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
                                                resolveCredentialError
