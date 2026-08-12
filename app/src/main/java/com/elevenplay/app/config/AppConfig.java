package com.elevenplay.app.config;

import com.elevenplay.app.BuildConfig;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/* =========================================================
   11PLAY — APPLICATION CONFIGURATION

   Responsibilities:
   - Keep the official 11Play website URL centralized
   - Define trusted 11Play web origins
   - Define external authentication / Live Chat hosts
   - Keep WebView behavior constants in one place

   Important:
   - Do not store passwords, API secrets or private keys here.
   - Firebase configuration remains separate.
========================================================= */

public final class AppConfig {

    private AppConfig() {
        // Utility class.
    }

    /* =====================================================
       APPLICATION
    ===================================================== */

    public static final String APP_NAME =
            "11Play";

    public static final String PACKAGE_NAME =
            "com.elevenplay.app";


    /* =====================================================
       OFFICIAL WEBSITE
    ===================================================== */

    public static final String START_URL =
            BuildConfig.START_URL;

    public static final String OFFICIAL_SCHEME =
            "https";

    public static final String OFFICIAL_HOST =
            "11play.github.io";

    public static final String OFFICIAL_PATH_PREFIX =
            "/11play/";

    public static final String OFFICIAL_ORIGIN =
            "https://11play.github.io";


    /* =====================================================
       WEBVIEW
    ===================================================== */

    public static final String USER_AGENT_SUFFIX =
            " 11PlayAndroid/1.0";

    public static final boolean JAVASCRIPT_ENABLED =
            true;

    public static final boolean DOM_STORAGE_ENABLED =
            true;

    public static final boolean DATABASE_STORAGE_ENABLED =
            true;

    public static final boolean COOKIES_ENABLED =
            true;

    public static final boolean THIRD_PARTY_COOKIES_ENABLED =
            true;

    public static final boolean MEDIA_PLAYBACK_REQUIRES_GESTURE =
            false;

    public static final boolean ALLOW_FILE_ACCESS =
            false;

    public static final boolean ALLOW_CONTENT_ACCESS =
            true;

    public static final boolean ALLOW_MIXED_CONTENT =
            false;


    /* =====================================================
       FILE UPLOAD

       Live Chat may request:
       - Images
       - Screenshots
       - Videos
       - Documents
       - Other supported files
    ===================================================== */

    public static final String FILE_CHOOSER_MIME_TYPE =
            "*/*";

    public static final boolean FILE_CHOOSER_MULTIPLE =
            true;


    /* =====================================================
       DOWNLOAD
    ===================================================== */

    public static final String DOWNLOAD_DIRECTORY_NAME =
            "11Play";


    /* =====================================================
       LIVE CHAT

       tawk.to is the current hosted Live Chat provider.
    ===================================================== */

    public static final String TAWK_HOST =
            "embed.tawk.to";

    public static final String TAWK_CDN_HOST =
            "va.tawk.to";


    /* =====================================================
       GOOGLE AUTHENTICATION

       Google authorization pages should NOT be forced
       through the embedded WebView.

       Native Google Sign-In / supported browser flow
       will handle authentication.
    ===================================================== */

    public static final String GOOGLE_ACCOUNTS_HOST =
            "accounts.google.com";

    public static final String GOOGLE_AUTH_HOST =
            "accounts.googleusercontent.com";


    /* =====================================================
       TRUSTED WEB HOSTS
    ===================================================== */

    private static final Set<String> TRUSTED_HOSTS;

    static {
        Set<String> hosts =
                new HashSet<>();

        hosts.add(
                OFFICIAL_HOST
        );

        hosts.add(
                TAWK_HOST
        );

        hosts.add(
                TAWK_CDN_HOST
        );

        TRUSTED_HOSTS =
                Collections.unmodifiableSet(
                        hosts
                );
    }

    public static boolean isTrustedHost(
            String host
    ) {
        if (
                host == null ||
                host.trim().isEmpty()
        ) {
            return false;
        }

        String normalizedHost =
                host.trim()
                        .toLowerCase();

        if (
                TRUSTED_HOSTS.contains(
                        normalizedHost
                )
        ) {
            return true;
        }

        /*
         * tawk.to may use regional/subdomain hosts.
         */
        return normalizedHost.endsWith(
                ".tawk.to"
        );
    }


    /* =====================================================
       OFFICIAL 11PLAY URL CHECK
    ===================================================== */

    public static boolean isOfficialHost(
            String host
    ) {
        if (
                host == null
        ) {
            return false;
        }

        return OFFICIAL_HOST.equalsIgnoreCase(
                host.trim()
        );
    }
}
