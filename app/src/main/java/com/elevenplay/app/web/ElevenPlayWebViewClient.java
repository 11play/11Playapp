package com.elevenplay.app.web;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/* =========================================================
   11PLAY — WEBVIEW CLIENT

   Responsibilities:
   - Keep 11Play website inside the APK
   - Keep normal third-party websites inside the APK
   - Keep HTTPS / HTTP browsing inside the same WebView
   - Preserve WebView history for Android Back navigation
   - Open Android/app-specific schemes externally
   - Handle intent:// links safely
   - Keep browser fallback URLs inside the WebView

   Intended behavior:

       11Play
           ↓
       Third-party website
           ↓
       Same 11Play WebView

   External Android apps are used only for schemes such as:

       tel:
       sms:
       mailto:
       market:
       whatsapp:
       tg:
       intent:

========================================================= */

public final class ElevenPlayWebViewClient
        extends WebViewClient {

    /* =====================================================
       ACTIVITY
    ===================================================== */

    private final Activity activity;


    /* =====================================================
       CONSTRUCTOR
    ===================================================== */

    public ElevenPlayWebViewClient(
            Activity activity
    ) {

        if (activity == null) {

            throw new IllegalArgumentException(
                    "Activity is required."
            );
        }

        this.activity =
                activity;
    }


    /* =====================================================
       MODERN URL HANDLER
    ===================================================== */

    @Override
    public boolean shouldOverrideUrlLoading(
            WebView view,
            WebResourceRequest request
    ) {

        if (
                view == null ||
                request == null ||
                request.getUrl() == null
        ) {

            return true;
        }


        return handleNavigation(
                view,
                request.getUrl()
        );
    }


    /* =====================================================
       LEGACY URL HANDLER
    ===================================================== */

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(
            WebView view,
            String url
    ) {

        if (
                view == null ||
                url == null ||
                url.trim().isEmpty()
        ) {

            return true;
        }


        try {

            return handleNavigation(
                    view,
                    Uri.parse(
                            url.trim()
                    )
            );

        } catch (Exception ignored) {

            return true;
        }
    }


    /* =====================================================
       NAVIGATION ROUTER
    ===================================================== */

    private boolean handleNavigation(
            WebView webView,
            Uri uri
    ) {

        if (
                webView == null ||
                uri == null
        ) {

            return true;
        }


        String scheme =
                safeLower(
                        uri.getScheme()
                );


        /* =================================================
           NORMAL WEB CONTENT

           CRITICAL:

           return false means:
           WebView itself loads the URL.

           Therefore:
           - 11Play stays inside app
           - Casino/site cards stay inside app
           - Third-party websites stay inside app
           - Redirects stay inside app
           - Back history remains inside WebView
        ================================================= */

        if (
                "https".equals(
                        scheme
                ) ||
                "http".equals(
                        scheme
                )
        ) {

            return false;
        }


        /* =================================================
           WEBVIEW INTERNAL SCHEMES
        ================================================= */

        if (
                "about".equals(
                        scheme
                ) ||
                "javascript".equals(
                        scheme
                ) ||
                "data".equals(
                        scheme
                ) ||
                "blob".equals(
                        scheme
                )
        ) {

            return false;
        }


        /* =================================================
           ANDROID INTENT LINKS

           Example:
               intent://...

           If target Android app exists:
               open target app.

           If target app does not exist but an HTTP/HTTPS
           browser_fallback_url exists:
               load fallback INSIDE 11Play WebView.
        ================================================= */

        if (
                "intent".equals(
                        scheme
                )
        ) {

            handleIntentUri(
                    webView,
                    uri.toString()
            );

            return true;
        }


        /* =================================================
           EXTERNAL APP SCHEMES

           These are not normal websites.

           They should be handed to Android so the correct
           installed application can handle them.
        ================================================= */

        if (
                isExternalAppScheme(
                        scheme
                )
        ) {

            openExternalUri(
                    uri
            );

            return true;
        }


        /* =================================================
           UNKNOWN CUSTOM SCHEME

           Do not force unknown custom protocols into the
           WebView.

           Example:
               customapp://something
        ================================================= */

        if (
                !scheme.isEmpty()
        ) {

            openExternalUri(
                    uri
            );

            return true;
        }


        return true;
    }


    /* =====================================================
       EXTERNAL APP SCHEME CHECK
    ===================================================== */

    private boolean isExternalAppScheme(
            String scheme
    ) {

        if (
                scheme == null ||
                scheme.isEmpty()
        ) {

            return false;
        }


        switch (scheme) {

            case "tel":

            case "sms":

            case "smsto":

            case "mailto":

            case "market":

            case "whatsapp":

            case "tg":

            case "viber":

            case "fb":

            case "fb-messenger":

                return true;


            default:

                return false;
        }
    }


    /* =====================================================
       OPEN EXTERNAL ANDROID APP
    ===================================================== */

    private boolean openExternalUri(
            Uri uri
    ) {

        if (
                uri == null
        ) {

            return false;
        }


        try {

            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            uri
                    );


            intent.addCategory(
                    Intent.CATEGORY_BROWSABLE
            );


            activity.startActivity(
                    intent
            );


            return true;

        } catch (
                ActivityNotFoundException ignored
        ) {

            return false;

        } catch (
                Exception ignored
        ) {

            return false;
        }
    }


    /* =====================================================
       HANDLE intent:// URL
    ===================================================== */

    private void handleIntentUri(
            WebView webView,
            String url
    ) {

        if (
                webView == null ||
                url == null ||
                url.trim().isEmpty()
        ) {

            return;
        }


        try {

            Intent intent =
                    Intent.parseUri(
                            url,
                            Intent.URI_INTENT_SCHEME
                    );


            /*
             * Security:
             * Never allow a web page to force an explicit
             * Android component or selector.
             */

            intent.setComponent(
                    null
            );

            intent.setSelector(
                    null
            );

            intent.addCategory(
                    Intent.CATEGORY_BROWSABLE
            );


            /* =============================================
               TRY TARGET ANDROID APP
            ============================================== */

            try {

                activity.startActivity(
                        intent
                );

                return;

            } catch (
                    ActivityNotFoundException ignored
            ) {

                /*
                 * Target app is not installed.
                 *
                 * Check browser fallback below.
                 */
            }


            /* =============================================
               FALLBACK URL
            ============================================== */

            String fallbackUrl =
                    intent.getStringExtra(
                            "browser_fallback_url"
                    );


            if (
                    fallbackUrl == null ||
                    fallbackUrl.trim().isEmpty()
            ) {

                return;
            }


            Uri fallbackUri =
                    Uri.parse(
                            fallbackUrl.trim()
                    );


            String fallbackScheme =
                    safeLower(
                            fallbackUri.getScheme()
                    );


            /*
             * IMPORTANT:
             *
             * HTTP/HTTPS fallback stays inside the APK.
             */

            if (
                    "https".equals(
                            fallbackScheme
                    ) ||
                    "http".equals(
                            fallbackScheme
                    )
            ) {

                webView.loadUrl(
                        fallbackUri.toString()
                );
            }


        } catch (
                Exception ignored
        ) {

            // Invalid intent URL.
        }
    }


    /* =====================================================
       SAFE STRING
    ===================================================== */

    private String safeLower(
            String value
    ) {

        if (
                value == null
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
