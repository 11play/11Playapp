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

import com.elevenplay.app.R;
import com.elevenplay.app.config.AppConfig;

/* =========================================================
   11PLAY — DOWNLOAD CONTROLLER

   Responsibilities:
   - Handle WebView file downloads
   - Support HTTP and HTTPS downloads
   - Preserve WebView cookies
   - Preserve browser user-agent
   - Save files to:
       Downloads/11Play/
   - Support Android 7+
   - Handle legacy storage permission safely
   - Never automatically execute/open downloaded files

   Important:
   - DownloadManager accepts HTTP/HTTPS URLs.
   - Android 10+ does not require legacy storage permission
     for public Downloads.
========================================================= */

public final class DownloadController {

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
        if (activity == null) {
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
                download == null ||
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
            if (allowPending) {
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

            DownloadManager.Request request =
                    new DownloadManager.Request(
                            uri
                    );

            String fileName =
                    resolveFileName(
                            download.url,
                            download.contentDisposition,
                            download.mimeType
                    );

            String safeMimeType =
                    normalizeMimeType(
                            download.mimeType
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
                    cookies != null &&
                    !cookies.trim().isEmpty()
            ) {
                request.addRequestHeader(
                        "Cookie",
                        cookies
                );
            }


            /* =============================================
               DESTINATION

               Device storage:

               Downloads/
                   11Play/
                       filename.ext
            ============================================== */

            String relativePath =
                    AppConfig
                            .DOWNLOAD_DIRECTORY_NAME
                            + "/"
                            + fileName;

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    relativePath
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
                    downloadManager == null
            ) {
                return DownloadResult.FAILED;
            }

            downloadManager.enqueue(
                    request
            );

            pendingDownload =
                    null;

            return DownloadResult.STARTED;

        } catch (Exception error) {

            pendingDownload =
                    null;

            return DownloadResult.FAILED;
        }
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

       MainActivity will call this after legacy
       storage permission is approved.
    ===================================================== */

    public DownloadResult retryPendingDownload() {
        if (
                pendingDownload == null
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

        if (url.isEmpty()) {
            return false;
        }

        try {
            Uri uri =
                    Uri.parse(
                            url
                    );

            String scheme =
                    safeString(
                            uri.getScheme()
                    ).toLowerCase();

            return scheme.equals(
                    "https"
            ) ||
                    scheme.equals(
                            "http"
                    );

        } catch (Exception error) {
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
        } catch (Exception error) {
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


    private String sanitizeFileName(
            String value
    ) {
        String fileName =
                safeString(
                        value
                );

        if (fileName.isEmpty()) {
            return "";
        }

        /*
         * Remove directory traversal and characters that
         * should never appear inside our download filename.
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
                mimeType.isEmpty() ||
                mimeType.equalsIgnoreCase(
                        "application/octet-stream"
                )
        ) {
            return mimeType;
        }

        /*
         * Prevent malformed MIME values from becoming
         * request headers.
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
       SAFE STRING
    ===================================================== */

    private String safeString(
            Object value
    ) {
        if (value == null) {
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
