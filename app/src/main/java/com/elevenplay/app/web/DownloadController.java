package com.elevenplay.app.web;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import com.elevenplay.app.config.AppConfig;

import java.util.Locale;

/* =========================================================
   11PLAY — DOWNLOAD CONTROLLER

   Responsibilities:
   - Handle WebView file downloads
   - Support HTTP and HTTPS downloads
   - Preserve WebView cookies
   - Preserve browser user-agent
   - Save files directly to:
       Downloads/
   - Correctly detect APK downloads
   - Force Android APK MIME type when required
   - Support Android 7+
   - Handle legacy storage permission safely
   - Never automatically execute/open downloaded files

   Important:
   - DownloadManager accepts HTTP/HTTPS URLs.
   - Android 10+ does not require legacy storage permission
     for public Downloads.
   - APK files use:
       application/vnd.android.package-archive
========================================================= */

public final class DownloadController {

    /* =====================================================
       APK MIME TYPE
    ===================================================== */

    private static final String APK_MIME_TYPE =
            "application/vnd.android.package-archive";


    /* =====================================================
       RESULT
    ===================================================== */

    public enum DownloadResult {
        STARTED,
        PERMISSION_REQUIRED,
        INVALID_URL,
        FAILED
    }


    /* =====================================================
       ACTIVITY
    ===================================================== */

    private final Activity activity;


    /* =====================================================
       PENDING DOWNLOAD

       Used only when Android 7-9 requires
       WRITE_EXTERNAL_STORAGE runtime permission.
    ===================================================== */

    private PendingDownload pendingDownload =
            null;


    /* =====================================================
       CONSTRUCTOR
    ===================================================== */

    public DownloadController(
            Activity activity
    ) {

        if (
                activity ==
                        null
        ) {

            throw new IllegalArgumentException(
                    "Activity is required."
            );
        }

        this.activity =
                activity;
    }


    /* =====================================================
       START DOWNLOAD
    ===================================================== */

    public DownloadResult startDownload(
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType
    ) {

        PendingDownload download =
                new PendingDownload(
                        url,
                        userAgent,
                        contentDisposition,
                        mimeType
                );

        return startDownloadInternal(
                download,
                true
        );
    }


    /* =====================================================
       INTERNAL DOWNLOAD
    ===================================================== */

    private DownloadResult startDownloadInternal(
            PendingDownload download,
            boolean allowPending
    ) {

        if (
                download ==
                        null ||
                !isValidHttpUrl(
                        download.url
                )
        ) {

            return DownloadResult.INVALID_URL;
        }


        /*
         * Android 9 and below require legacy
         * WRITE_EXTERNAL_STORAGE permission when
         * DownloadManager writes to public Downloads.
         */

        if (
                requiresLegacyStoragePermission() &&
                !hasLegacyStoragePermission()
        ) {

            if (
                    allowPending
            ) {

                pendingDownload =
                        download;
            }

            return DownloadResult.PERMISSION_REQUIRED;
        }


        try {

            Uri uri =
                    Uri.parse(
                            download.url
                    );


            /* =============================================
               RESOLVE FILE NAME
            ============================================== */

            String fileName =
                    resolveFileName(
                            download.url,
                            download.contentDisposition,
                            download.mimeType
                    );


            /* =============================================
               APK DETECTION
            ============================================== */

            boolean apkDownload =
                    isApkDownload(
                            download.url,
                            download.contentDisposition,
                            download.mimeType,
                            fileName
                    );


            /*
             * If the server gives a generic filename but
             * the download is clearly an APK, ensure the
             * final saved file ends with .apk.
             */

            if (
                    apkDownload
            ) {

                fileName =
                        ensureApkExtension(
                                fileName
                        );
            }


            /* =============================================
               FINAL MIME TYPE
            ============================================== */

            String safeMimeType =
                    resolveDownloadMimeType(
                            download.mimeType,
                            apkDownload
                    );


            /* =============================================
               DOWNLOAD REQUEST
            ============================================== */

            DownloadManager.Request request =
                    new DownloadManager.Request(
                            uri
                    );


            /* =============================================
               REQUEST METADATA
            ============================================== */

            request.setTitle(
                    fileName
            );

            request.setDescription(
                    AppConfig.APP_NAME
                            + " download"
            );

            request.setNotificationVisibility(
                    DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );

            request.setAllowedOverMetered(
                    true
            );

            request.setAllowedOverRoaming(
                    true
            );


            /* =============================================
               MIME TYPE

               APK files must use Android's package MIME.
            ============================================== */

            if (
                    !safeMimeType.isEmpty()
            ) {

                request.setMimeType(
                        safeMimeType
                );
            }


            /* =============================================
               USER AGENT
            ============================================== */

            String safeUserAgent =
                    safeString(
                            download.userAgent
                    );

            if (
                    !safeUserAgent.isEmpty()
            ) {

                request.addRequestHeader(
                        "User-Agent",
                        safeUserAgent
                );
            }


            /* =============================================
               COOKIES

               Important for authenticated/session-based
               downloads opened from the WebView.
            ============================================== */

            String cookies =
                    CookieManager
                            .getInstance()
                            .getCookie(
                                    download.url
                            );

            if (
                    cookies !=
                            null &&
                    !cookies.trim().isEmpty()
            ) {

                request.addRequestHeader(
                        "Cookie",
                        cookies
                );
            }


            /* =============================================
               DESTINATION

               FINAL LOCATION:

               Downloads/
                   filename.apk

               No extra 11Play subfolder.
            ============================================== */

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
            );


            /* =============================================
               MEDIA SCANNER — LEGACY ANDROID
            ============================================== */

            if (
                    Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.Q
            ) {

                request.allowScanningByMediaScanner();
            }


            /* =============================================
               ENQUEUE
            ============================================== */

            DownloadManager downloadManager =
                    (DownloadManager)
                            activity.getSystemService(
                                    Context.DOWNLOAD_SERVICE
                            );


            if (
                    downloadManager ==
                            null
            ) {

                return DownloadResult.FAILED;
            }


            downloadManager.enqueue(
                    request
            );


            pendingDownload =
                    null;


            return DownloadResult.STARTED;

        } catch (
                Exception error
        ) {

            pendingDownload =
                    null;

            return DownloadResult.FAILED;
        }
    }


    /* =====================================================
       APK DETECTION

       APK is detected from any reliable signal:

       - MIME type
       - resolved filename
       - Content-Disposition
       - download URL
    ===================================================== */

    private boolean isApkDownload(
            String url,
            String contentDisposition,
            String mimeType,
            String fileName
    ) {

        String safeMime =
                safeLower(
                        mimeType
                );

        String safeFileName =
                safeLower(
                        fileName
                );

        String safeDisposition =
                safeLower(
                        contentDisposition
                );

        String safeUrl =
                safeLower(
                        url
                );


        /* =============================================
           MIME SIGNAL
        ============================================== */

        if (
                safeMime.startsWith(
                        APK_MIME_TYPE
                ) ||
                safeMime.startsWith(
                        "application/x-android-package"
                )
        ) {

            return true;
        }


        /* =============================================
           FILE NAME SIGNAL
        ============================================== */

        if (
                safeFileName.endsWith(
                        ".apk"
                )
        ) {

            return true;
        }


        /* =============================================
           CONTENT-DISPOSITION SIGNAL
        ============================================== */

        if (
                safeDisposition.contains(
                        ".apk"
                )
        ) {

            return true;
        }


        /* =============================================
           URL SIGNAL

           Covers:
           /app.apk
           /app.apk?version=123
           ?file=app.apk
        ============================================== */

        return safeUrl.contains(
                ".apk"
        );
    }


    /* =====================================================
       ENSURE APK EXTENSION
    ===================================================== */

    private String ensureApkExtension(
            String fileName
    ) {

        String safeFileName =
                sanitizeFileName(
                        fileName
                );


        if (
                safeFileName.isEmpty()
        ) {

            return "11play-download-"
                    + System.currentTimeMillis()
                    + ".apk";
        }


        if (
                safeFileName
                        .toLowerCase(
                                Locale.ROOT
                        )
                        .endsWith(
                                ".apk"
                        )
        ) {

            return safeFileName;
        }


        /*
         * Keep final filename within a safe length after
         * appending the APK extension.
         */

        if (
                safeFileName.length() >
                        176
        ) {

            safeFileName =
                    safeFileName.substring(
                            0,
                            176
                    );
        }


        return safeFileName
                + ".apk";
    }


    /* =====================================================
       DOWNLOAD MIME TYPE
    ===================================================== */

    private String resolveDownloadMimeType(
            String originalMimeType,
            boolean apkDownload
    ) {

        /*
         * APK always receives the correct Android MIME,
         * even if the web server reports:
         *
         * application/octet-stream
         */

        if (
                apkDownload
        ) {

            return APK_MIME_TYPE;
        }


        return normalizeMimeType(
                originalMimeType
        );
    }


    /* =====================================================
       LEGACY STORAGE PERMISSION
    ===================================================== */

    public boolean requiresLegacyStoragePermission() {

        return Build.VERSION.SDK_INT <=
                Build.VERSION_CODES.P;
    }


    public boolean hasLegacyStoragePermission() {

        if (
                !requiresLegacyStoragePermission()
        ) {

            return true;
        }


        return activity.checkSelfPermission(
                Manifest.permission
                        .WRITE_EXTERNAL_STORAGE
        ) ==
                PackageManager.PERMISSION_GRANTED;
    }


    /* =====================================================
       RETRY PENDING DOWNLOAD

       MainActivity calls this after legacy
       storage permission is approved.
    ===================================================== */

    public DownloadResult retryPendingDownload() {

        if (
                pendingDownload ==
                        null
        ) {

            return DownloadResult.FAILED;
        }


        PendingDownload download =
                pendingDownload;

        pendingDownload =
                null;


        return startDownloadInternal(
                download,
                false
        );
    }


    public boolean hasPendingDownload() {

        return pendingDownload !=
                null;
    }


    public void clearPendingDownload() {

        pendingDownload =
                null;
    }


    /* =====================================================
       URL VALIDATION
    ===================================================== */

    private boolean isValidHttpUrl(
            String value
    ) {

        String url =
                safeString(
                        value
                );


        if (
                url.isEmpty()
        ) {

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


            return scheme.equals(
                    "https"
            ) ||
                    scheme.equals(
                            "http"
                    );

        } catch (
                Exception error
        ) {

            return false;
        }
    }


    /* =====================================================
       FILE NAME
    ===================================================== */

    private String resolveFileName(
            String url,
            String contentDisposition,
            String mimeType
    ) {

        String fileName;


        try {

            fileName =
                    URLUtil.guessFileName(
                            url,
                            contentDisposition,
                            mimeType
                    );

        } catch (
                Exception error
        ) {

            fileName =
                    "";
        }


        fileName =
                sanitizeFileName(
                        fileName
                );


        if (
                fileName.isEmpty()
        ) {

            fileName =
                    "11play-download-"
                            + System.currentTimeMillis();
        }


        return fileName;
    }


    /* =====================================================
       SANITIZE FILE NAME
    ===================================================== */

    private String sanitizeFileName(
            String value
    ) {

        String fileName =
                safeString(
                        value
                );


        if (
                fileName.isEmpty()
        ) {

            return "";
        }


        /*
         * Remove directory traversal and characters that
         * should never appear inside a download filename.
         */

        fileName =
                fileName.replace(
                        "\\",
                        "_"
                );

        fileName =
                fileName.replace(
                        "/",
                        "_"
                );

        fileName =
                fileName.replace(
                        ":",
                        "_"
                );

        fileName =
                fileName.replace(
                        "*",
                        "_"
                );

        fileName =
                fileName.replace(
                        "?",
                        "_"
                );

        fileName =
                fileName.replace(
                        "\"",
                        "_"
                );

        fileName =
                fileName.replace(
                        "<",
                        "_"
                );

        fileName =
                fileName.replace(
                        ">",
                        "_"
                );

        fileName =
                fileName.replace(
                        "|",
                        "_"
                );


        while (
                fileName.contains(
                        ".."
                )
        ) {

            fileName =
                    fileName.replace(
                            "..",
                            "."
                    );
        }


        if (
                fileName.length() >
                        180
        ) {

            fileName =
                    fileName.substring(
                            0,
                            180
                    );
        }


        return fileName.trim();
    }


    /* =====================================================
       MIME TYPE
    ===================================================== */

    private String normalizeMimeType(
            String value
    ) {

        String mimeType =
                safeString(
                        value
                );


        if (
                mimeType.isEmpty()
        ) {

            return "";
        }


        /*
         * Remove MIME parameters such as:
         *
         * application/pdf; charset=utf-8
         */

        int separator =
                mimeType.indexOf(
                        ';'
                );


        if (
                separator >=
                        0
        ) {

            mimeType =
                    mimeType.substring(
                            0,
                            separator
                    ).trim();
        }


        /*
         * Prevent malformed MIME values.
         */

        if (
                !mimeType.contains(
                        "/"
                ) ||
                mimeType.contains(
                        "\n"
                ) ||
                mimeType.contains(
                        "\r"
                )
        ) {

            return "";
        }


        return mimeType;
    }


    /* =====================================================
       SAFE LOWERCASE STRING
    ===================================================== */

    private String safeLower(
            Object value
    ) {

        return safeString(
                value
        ).toLowerCase(
                Locale.ROOT
        );
    }


    /* =====================================================
       SAFE STRING
    ===================================================== */

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
       PENDING DOWNLOAD MODEL
    ===================================================== */

    private static final class PendingDownload {

        private final String url;

        private final String userAgent;

        private final String contentDisposition;

        private final String mimeType;


        private PendingDownload(
                String url,
                String userAgent,
                String contentDisposition,
                String mimeType
        ) {

            this.url =
                    url;

            this.userAgent =
                    userAgent;

            this.contentDisposition =
                    contentDisposition;

            this.mimeType =
                    mimeType;
        }
    }
}
