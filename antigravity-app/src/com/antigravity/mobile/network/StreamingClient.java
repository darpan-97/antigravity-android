package com.antigravity.mobile.network;

import android.os.Handler;
import android.os.Looper;
import com.antigravity.mobile.model.ServerConfig;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamingClient {
    public interface StreamListener {
        void onConnected();
        void onConnecting(int attempt);
        void onDisconnected(String reason);
        void onTokenReceived(String token);
        void onMessageComplete(String fullText);
        void onError(Throwable error);
        void onStatus(String status, String text);
        void onTasks(java.util.List<String> tasks);
        void onEvent(JSONObject event);
    }

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private ServerConfig mConfig;
    private StreamListener mListener;
    private HttpURLConnection mActiveConnection;

    public StreamingClient(ServerConfig config, StreamListener listener) {
        this.mConfig = config;
        this.mListener = listener;
    }

    public void updateConfig(ServerConfig config) {
        this.mConfig = config;
        restart();
    }

    public synchronized void start() {
        if (mIsRunning.get()) return;
        mIsRunning.set(true);
        mExecutor.execute(this::runStreamLoop);
    }

    public synchronized void stop() {
        mIsRunning.set(false);
        if (mActiveConnection != null) {
            try {
                mActiveConnection.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public void restart() {
        stop();
        mMainHandler.postDelayed(this::start, 300);
    }

    private void runStreamLoop() {
        int attempt = 0;
        while (mIsRunning.get()) {
            attempt++;
            int currentAttempt = attempt;
            mMainHandler.post(() -> {
                if (mListener != null) mListener.onConnecting(currentAttempt);
            });

            boolean connectedSuccess = false;
            try {
                String baseUrl = mConfig.getServerUrl();
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    baseUrl = "http://" + baseUrl;
                }
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }

                URL streamUrl = new URL(baseUrl + "/events");
                mActiveConnection = (HttpURLConnection) streamUrl.openConnection();
                mActiveConnection.setRequestMethod("GET");
                mActiveConnection.setConnectTimeout(8000);
                mActiveConnection.setReadTimeout(0); // Infinite read timeout for SSE stream
                mActiveConnection.setRequestProperty("Accept", "text/event-stream");
                mActiveConnection.setRequestProperty("Cache-Control", "no-cache");
                if (mConfig.getAuthToken() != null && !mConfig.getAuthToken().isEmpty()) {
                    mActiveConnection.setRequestProperty("Authorization", "Bearer " + mConfig.getAuthToken());
                }

                int code = mActiveConnection.getResponseCode();
                if (code >= 200 && code < 300) {
                    connectedSuccess = true;
                    attempt = 0; // Reset backoff on successful connection
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onConnected();
                    });

                    InputStream in = mActiveConnection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while (mIsRunning.get() && (line = reader.readLine()) != null) {
                        handleSseLine(line);
                    }
                } else {
                    final String err = "HTTP Error " + code;
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onDisconnected(err);
                    });
                }
            } catch (Exception e) {
                if (mIsRunning.get()) {
                    final String msg = e.getMessage() != null ? e.getMessage() : "Network error";
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onDisconnected(msg);
                    });
                }
            } finally {
                if (mActiveConnection != null) {
                    try { mActiveConnection.disconnect(); } catch (Exception ignored) {}
                }
            }

            if (!mIsRunning.get()) break;

            // Exponential backoff: 1s, 2s, 4s, 8s, max 16s
            long backoffMs = Math.min(16000, (long) Math.pow(2, Math.min(attempt, 4)) * 1000L);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void handleSseLine(String line) {
        if (line.startsWith("data:")) {
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JSONObject json = new JSONObject(data);
                String type = json.optString("type", "");
                if ("history".equals(type) || "conversations".equals(type)
                        || "tool".equals(type) || "message".equals(type)) {
                    final JSONObject ev = json;
                    mMainHandler.post(() -> { if (mListener != null) mListener.onEvent(ev); });
                    return;
                }
                if ("status".equals(type)) {
                    final String st = json.optString("status", "idle");
                    final String tx = json.optString("message", "Antigravity is thinking...");
                    mMainHandler.post(() -> { if (mListener != null) mListener.onStatus(st, tx); });
                    return;
                }
                if ("tasks".equals(type)) {
                    final java.util.List<String> list = new java.util.ArrayList<>();
                    org.json.JSONArray arr = json.optJSONArray("tasks");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i));
                    mMainHandler.post(() -> { if (mListener != null) mListener.onTasks(list); });
                    return;
                }
                if (json.has("token")) {
                    String token = json.getString("token");
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onTokenReceived(token);
                    });
                } else if (json.has("content")) {
                    String content = json.getString("content");
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onTokenReceived(content);
                    });
                } else if (json.has("message")) {
                    String fullMsg = json.getString("message");
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onMessageComplete(fullMsg);
                    });
                }
            } catch (Exception e) {
                // If not JSON, treat raw text as token
                mMainHandler.post(() -> {
                    if (mListener != null) mListener.onTokenReceived(data);
                });
            }
        }
    }

    public void sendMessage(String text, MessageCallback callback) {
        mExecutor.execute(() -> {
            try {
                String baseUrl = mConfig.getServerUrl();
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    baseUrl = "http://" + baseUrl;
                }
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }

                URL url = new URL(baseUrl + "/message");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                if (mConfig.getAuthToken() != null && !mConfig.getAuthToken().isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + mConfig.getAuthToken());
                }

                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("timestamp", System.currentTimeMillis());

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                android.util.Log.i("Antigravity", "POST /message response code: " + code);
                if (code >= 200 && code < 300) {
                    mMainHandler.post(() -> {
                        if (callback != null) callback.onSuccess();
                    });
                } else {
                    android.util.Log.e("Antigravity", "POST /message failed with code " + code);
                    mMainHandler.post(() -> {
                        if (callback != null) callback.onError("Server returned " + code);
                    });
                }
                conn.disconnect();
            } catch (Exception e) {
                android.util.Log.e("Antigravity", "POST /message exception: " + e.getMessage(), e);
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage() != null ? e.getMessage() : "Send failed");
                });
            }
        });
    }

    public void post(String path, JSONObject body, MessageCallback callback) {
        mExecutor.execute(() -> {
            try {
                String baseUrl = mConfig.getServerUrl();
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) baseUrl = "http://" + baseUrl;
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                if (mConfig.getAuthToken() != null && !mConfig.getAuthToken().isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + mConfig.getAuthToken());
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                mMainHandler.post(() -> {
                    if (callback == null) return;
                    if (code >= 200 && code < 300) callback.onSuccess();
                    else callback.onError("Server returned " + code);
                });
            } catch (Exception e) {
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage() != null ? e.getMessage() : "Request failed");
                });
            }
        });
    }

    public interface MessageCallback {
        void onSuccess();
        void onError(String error);
    }
}
