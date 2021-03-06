package com.kaltura.dtg.clear;

import android.util.Log;

import com.kaltura.dtg.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by noamt on 5/13/15.
 */
class DownloadTask {
    static final String TAG = "DownloadTask";
    private static final int HTTP_READ_TIMEOUT_MS = 5000;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;
    private static final int PROGRESS_REPORT_COUNT = 20;

    // TODO: Hold url and targetFile as Strings, only convert to URL/File when used.
    final String taskId;
    final URL url;
    final File targetFile;
    String itemId;
    String trackRelativeId;

    private Listener listener;  // this is the service


    DownloadTask(URL url, File targetFile) {
        this.url = url;
        this.targetFile = targetFile;
        this.taskId = Utils.md5Hex(targetFile.getAbsolutePath());
    }

    DownloadTask(String url, String targetFile) throws MalformedURLException {
        this(new URL(url), new File(targetFile));
    }
    
    @Override
    public String toString() {
        return "<DownloadTask id='" + taskId + "' url='" + url + "' target='" + targetFile + "'>";
    }

    private boolean createParentDir(File targetFile) {
        File parent = targetFile.getParentFile();
        return parent.mkdirs() || parent.isDirectory();
    }
    
    void download() {
        
        URL url = this.url;
        File targetFile = this.targetFile;
        Log.d(TAG, "Task " + taskId + ": download " + url + " to " + targetFile);
        
        // Create parent dir if needed
        if (!createParentDir(targetFile)) {
            Log.e(TAG, "Can't create parent dir");
            reportProgress(State.ERROR, 0, new FileNotFoundException(targetFile.getAbsolutePath()));
            return;
        }

        reportProgress(State.STARTED, 0, null);
        long remoteFileSize;
        try {
            remoteFileSize = Utils.httpHeadGetLength(url);
        } catch (InterruptedIOException e) {
            Log.d(TAG, "Task " + taskId + " interrupted (1)");
            reportProgress(State.STOPPED, 0, null);
            return;
        } catch (IOException e) {
            Log.e(TAG, "HEAD request failed for " + url, e);
            remoteFileSize = -1;
        }

        long localFileSize = targetFile.exists() ? targetFile.length() : 0;

        // finish before even starting, if file is already complete.
        if (localFileSize == remoteFileSize) {
            // We're done.
            reportProgress(State.COMPLETED, 0, null);
            return;
        } else if (localFileSize > remoteFileSize) {
            // This is really odd. Delete and try again.
            Log.w(TAG, "Target file is longer than remote. Deleting the target.");
            if (!targetFile.delete()) {
                Log.w(TAG, "Can't delete targetFile");
            }
            localFileSize = 0;
        }

        // Start the actual download.
        InputStream inputStream = null;
        HttpURLConnection conn = null;
        FileOutputStream fileOutputStream = null;
        
        State stopReason = null;
        Exception stopError = null;

        int progressReportBytes = 0;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            conn.setDoInput(true);

            if (localFileSize > 0) {
                // Resume
                conn.setRequestProperty("Range", "Bytes=" + localFileSize + "-");
            }
            conn.connect();

            int response = conn.getResponseCode();
            if (response >= 400) {
                throw new IOException(Utils.format("Response code for %s is %d", url, response));
            }

            inputStream = conn.getInputStream();
            fileOutputStream = new FileOutputStream(targetFile, true);

            byte[] buffer = new byte[10240]; // 10k buffer

            int byteCount;
            progressReportBytes = 0;
            int progressReportCounter = 0;
            while (true) {
                byteCount = inputStream.read(buffer);

                progressReportCounter++;

                if (byteCount < 0) {
                    // EOF
                    break;
                }

                if (byteCount > 0) {
                    fileOutputStream.write(buffer, 0, byteCount);
                    progressReportBytes += byteCount;
                }

                if (progressReportBytes > 0 && progressReportCounter >= PROGRESS_REPORT_COUNT) {
                    Log.v(TAG, "progressReportBytes:" + progressReportBytes + "; progressReportCounter:" + progressReportCounter);
                    reportProgress(State.IN_PROGRESS, progressReportBytes, stopError);
                    progressReportBytes = 0;
                    progressReportCounter = 0;
                }
            }

            stopReason = State.COMPLETED;

        } catch (InterruptedIOException e) {
            // Not an error -- task is cancelled.
            Log.d(TAG, "Task " + taskId + " interrupted");
            stopReason = State.STOPPED;

        } catch (IOException e) {
            Log.d(TAG, "Task " + taskId + " failed", e);
            stopReason = State.ERROR;
            stopError = e;

        } finally {
            Utils.safeClose(inputStream, fileOutputStream);
            if (conn != null) {
                conn.disconnect();
            }

            // Maybe some bytes are still waiting to be reported
            if (progressReportBytes > 0) {
                reportProgress(State.IN_PROGRESS, progressReportBytes, stopError);
            }
            if (stopReason != null) {
                reportProgress(stopReason, 0, stopError);
            }
        }
    }

    private void reportProgress(final State state, final int newBytes, Exception stopError) {
        Log.d(TAG, "progress: " + state + ", " + newBytes);
        listener.onTaskProgress(this, state, newBytes, stopError);
    }

    public Listener getListener() {
        return listener;
    }
    
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DownloadTask) {
            DownloadTask otherTask = (DownloadTask) o;
            return this.url.equals(otherTask.url) && this.targetFile.equals(otherTask.targetFile);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int code = 17;
        code = 31 * code + (this.url == null ? 0 : this.url.hashCode());
        code = 31 * code + (this.targetFile == null ? 0 : this.targetFile.hashCode());
        return code;
    }

    enum State {
        IDLE, STARTED, IN_PROGRESS, COMPLETED, STOPPED, ERROR;
        private final static State[] values = values();
        static State fromOrdinal(int ordinal) {
            return values[ordinal];
        }
    }

    interface Listener {
        void onTaskProgress(DownloadTask task, State newState, int newBytes, Exception stopError);
    }
}
