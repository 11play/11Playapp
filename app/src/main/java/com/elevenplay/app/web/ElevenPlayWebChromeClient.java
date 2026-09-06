package com.elevenplay.app.web;

import android.net.Uri;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.ProgressBar;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.elevenplay.app.R;

/* =========================================================
   11PLAY — WEB CHROME CLIENT

   Responsibilities:
   - Handle WebView file chooser requests
   - Support Live Chat file/image/video attachments
   - Update page loading progress
   - Stop pull-to-refresh indicator after page load
   - Keep file selection delegated to MainActivity

   Important:
   - MainActivity owns the Android Activity Result launcher.
   - This class does not request broad storage permission.
   - File access is performed through Android's system picker.
========================================================= */

public final class ElevenPlayWebChromeClient
        extends WebChromeClient {

    /* =====================================================
       FILE CHOOSER DELEGATE

       MainActivity implements this interface.

       It receives:
       - WebView file callback
       - FileChooserParams supplied by the web page

       MainActivity then opens Android's system picker.
    ===================================================== */

    public interface FileChooserDelegate {

        boolean openFileChooser(
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        );
    }


    /* =====================================================
       DEPENDENCIES
    ===================================================== */

    private final ProgressBar progressBar;

    private final FileChooserDelegate
            fileChooserDelegate;


    /* =====================================================
       CONSTRUCTOR
    ===================================================== */

    public ElevenPlayWebChromeClient(
            ProgressBar progressBar,
            FileChooserDelegate fileChooserDelegate
    ) {
        this.progressBar =
                progressBar;

        this.fileChooserDelegate =
                fileChooserDelegate;
    }


    /* =====================================================
       PAGE LOAD PROGRESS
    ===================================================== */

    @Override
    public void onProgressChanged(
            WebView view,
            int newProgress
    ) {
        super.onProgressChanged(
                view,
                newProgress
        );

        int safeProgress =
                Math.max(
                        0,
                        Math.min(
                                100,
                                newProgress
                        )
                );


        /* =============================================
           PAGE PROGRESS BAR
        ============================================== */

        if (
                progressBar !=
                        null
        ) {

            progressBar.setProgress(
                    safeProgress
            );

            if (
                    safeProgress >=
                            100
            ) {

                progressBar.setVisibility(
                        View.GONE
                );

            } else {

                if (
                        progressBar.getVisibility() !=
                                View.VISIBLE
                ) {

                    progressBar.setVisibility(
                            View.VISIBLE
                    );
                }
            }
        }


        /* =============================================
           PULL-TO-REFRESH COMPLETE

           When the WebView finishes loading, stop the
           SwipeRefreshLayout spinner.

           This also works for normal page loads and does
           nothing if SwipeRefreshLayout is not refreshing.
        ============================================== */

        if (
                safeProgress >=
                        100
        ) {

            stopSwipeRefresh(
                    view
            );
        }
    }


    /* =====================================================
       STOP SWIPE REFRESH
    ===================================================== */

    private void stopSwipeRefresh(
            WebView webView
    ) {

        if (
                webView ==
                        null
        ) {
            return;
        }


        try {

            View rootView =
                    webView.getRootView();

            if (
                    rootView ==
                            null
            ) {
                return;
            }


            SwipeRefreshLayout
                    swipeRefreshLayout =
                    rootView.findViewById(
                            R.id.swipeRefreshLayout
                    );


            if (
                    swipeRefreshLayout !=
                            null &&
                    swipeRefreshLayout
                            .isRefreshing()
            ) {

                swipeRefreshLayout
                        .setRefreshing(
                                false
                        );
            }

        } catch (
                Exception ignored
        ) {
            // Refresh UI failure must never break WebView.
        }
    }


    /* =====================================================
       FILE UPLOAD

       Triggered by HTML such as:

       <input type="file">

       This is also the mechanism commonly used by hosted
       Live Chat widgets when users attach screenshots,
       images, videos or documents.
    ===================================================== */

    @Override
    public boolean onShowFileChooser(
            WebView webView,
            ValueCallback<Uri[]>
                    filePathCallback,
            FileChooserParams
                    fileChooserParams
    ) {

        if (
                filePathCallback ==
                        null
        ) {
            return false;
        }


        if (
                fileChooserDelegate ==
                        null
        ) {

            filePathCallback
                    .onReceiveValue(
                            null
                    );

            return true;
        }


        try {

            boolean handled =
                    fileChooserDelegate
                            .openFileChooser(
                                    filePathCallback,
                                    fileChooserParams
                            );


            if (
                    !handled
            ) {

                filePathCallback
                        .onReceiveValue(
                                null
                        );
            }


            return true;

        } catch (
                Exception error
        ) {

            filePathCallback
                    .onReceiveValue(
                            null
                    );

            return true;
        }
    }
}
