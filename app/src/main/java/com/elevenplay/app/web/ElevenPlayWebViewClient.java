package com.elevenplay.app.web;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.elevenplay.app.config.AppConfig;

import java.util.Locale;

/* =========================================================
   11PLAY — WEBVIEW CLIENT

   Responsibilities:
   - Keep official 11Play pages inside the app
   - Keep tawk.to Live Chat pages inside the app
   - Open Google authentication in a supported external browser
   - Open normal external websites in the device browser
   - Handle intent:, mailto:, tel:, sms: safely
   - Block unsupported / malformed navigation
   - Prevent clear-text HTTP navigation inside the WebView

   Important:
   - Google OAuth should not be forced through an embedded
     Android WebView.
   - Native Google authentication will be added separately.
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
       PAGE START
    ===================================================== */

    @Override
    public void onPageStarted(
            WebView view,
            String url,
            Bitmap favicon
    ) {
        super.onPageStarted(
                view,
                url,
                favicon
        );
    }


    /* =====================================================
       MODERN URL OVERRIDE
    ===================================================== */

    @Override
    public boolean shouldOverrideUrlLoading(
            WebView view,
            WebResourceRequest request
    ) {
        if (
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
       LEGACY URL OVERRIDE
    ===================================================== */

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(
            WebView view,
            String url
    ) {
        if (
                url == null ||
                url.trim().isEmpty()
        ) {
            return true;
        }

        try {
            return handleNavigation(
                    view,
                    Uri.parse(
                            url
                    )
            );
        } catch (Exception error) {
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
                uri == null
        ) {
            return true;
        }

        String scheme =
                safeLower(
                        uri.getScheme()
                );

        String host =
                safeLower(
                        uri.getHost()
                );


        /* =============================================
           ABOUT / JAVASCRIPT

           WebView internal URLs may be required by
           embedded widgets.
        ============================================== */

        if (
                "about".equals(
                        scheme
                ) ||
                "javascript".equals(
                        scheme
                )
        ) {
            return false;
        }


        /* =============================================
           OFFICIAL 11PLAY

           Keep inside WebView.
        ============================================== */

        if (
                "https".equals(
                        scheme
                ) &&
                AppConfig.isOfficialHost(
                        host
                )
        ) {
            return false;
        }


        /* =============================================
           TAWK.TO LIVE CHAT

           Keep inside WebView so chat remains part of
           the 11Play app experience.
        ============================================== */

        if (
                "https".equals(
                        scheme
                ) &&
                (
                    AppConfig.isTrustedHost(
                            host
                    ) ||
                    host.endsWith(
                            ".tawk.to"
                    )
                )
        ) {
            return false;
        }


        /* =============================================
           GOOGLE AUTHENTICATION

           Do NOT force Google OAuth pages into WebView.
           Open them using the device browser.

           Native Firebase Google Sign-In will later be
           used as the primary app authentication path.
        ============================================== */

        if (
                isGoogleAuthenticationHost(
                        host
                )
        ) {
            openExternalUri(
                    uri
            );

            return true;
        }


        /* =============================================
           HTTPS EXTERNAL WEBSITE

           Normal external websites should open in the
           user's browser instead of taking over the app.
        ============================================== */

        if (
                "https".equals(
                        scheme
                )
        ) {
            openExternalUri(
                    uri
            );

            return true;
        }


        /* =============================================
           HTTP

           11Play WebView remains HTTPS-only.
           HTTP external URLs may be handed to another
           installed browser if one supports them.
        ============================================== */

        if (
                "http".equals(
                        scheme
                )
        ) {
            openExternalUri(
                    uri
            );

            return true;
        }


        /* =============================================
           ANDROID INTENT URL
        ============================================== */

        if (
                "intent".equals(
                        scheme
                )
        ) {
            handleIntentUri(
                    uri.toString()
            );

            return true;
        }


        /* =============================================
           TELEPHONE / SMS / EMAIL
        ============================================== */

        if (
                "tel".equals(
                        scheme
                ) ||
                "sms".equals(
                        scheme
                ) ||
                "smsto".equals(
                        scheme
                ) ||
                "mailto".equals(
                        scheme
                )
        ) {
            openExternalUri(
                    uri
            );

            return true;
        }


        /* =============================================
           MARKET / APP LINKS
        ============================================== */

        if (
                "market".equals(
                        scheme
                )
        ) {
            openExternalUri(
                    uri
            );

            return true;
        }


        /* =============================================
           UNKNOWN SCHEME

           Attempt external handling only.

           Never inject unknown custom schemes into
           the WebView itself.
        ============================================== */

        openExternalUri(
                uri
        );

        return true;
    }


    /* =====================================================
       GOOGLE AUTH HOST CHECK
    ===================================================== */

    private boolean isGoogleAuthenticationHost(
            String host
    ) {
        if (
                host == null ||
                host.isEmpty()
        ) {
            return false;
        }

        return host.equals(
                "accounts.google.com"
        ) ||
                host.equals(
                        "oauth2.googleapis.com"
                ) ||
                host.equals(
                        "accounts.googleusercontent.com"
                ) ||
                host.endsWith(
                        ".googleusercontent.com"
                );
    }


    /* =====================================================
       EXTERNAL URI
    ===================================================== */

    private void openExternalUri(
            Uri uri
    ) {
        if (
                uri == null
        ) {
            return;
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

        } catch (
                ActivityNotFoundException ignored
        ) {
            // No compatible external application.
        } catch (
                Exception ignored
        ) {
            // Invalid or unsupported URI.
        }
    }


    /* =====================================================
       INTENT URL
    ===================================================== */

    private void handleIntentUri(
            String url
    ) {
        if (
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

            intent.addCategory(
                    Intent.CATEGORY_BROWSABLE
            );

            intent.setComponent(
                    null
            );

            intent.setSelector(
                    null
            );

            try {
                activity.startActivity(
                        intent
                );

                return;
            } catch (
                    ActivityNotFoundException ignored
            ) {
                // Try browser fallback URL below.
            }


            /* =========================================
               BROWSER FALLBACK
            ========================================== */

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
                            fallbackUrl
                    );

            String fallbackScheme =
                    safeLower(
                            fallbackUri.getScheme()
                    );

            if (
                    "https".equals(
                            fallbackScheme
                    ) ||
                    "http".equals(
                            fallbackScheme
                    )
            ) {
                openExternalUri(
                        fallbackUri
                );
            }

        } catch (
                Exception ignored
        ) {
            // Invalid intent URL.
        }
    }


    /* =====================================================
       RESOURCE SECURITY

       Block clear-text HTTP subresources inside WebView.
    ===================================================== */

    @Override
    public WebResourceResponse shouldInterceptRequest(
            WebView view,
            WebResourceRequest request
    ) {
        if (
                request != null &&
                request.getUrl() != null
        ) {
            Uri uri =
                    request.getUrl();

            String scheme =
                    safeLower(
                            uri.getScheme()
                    );

            if (
                    "http".equals(
                            scheme
                    )
            ) {
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        null
                );
            }
        }

        return super.shouldInterceptRequest(
                view,
                request
        );
    }


    /* =====================================================
       SAFE LOWERCASE
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
