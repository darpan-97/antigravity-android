package com.antigravity.mobile.model;

import android.content.Context;
import android.content.SharedPreferences;

public class ServerConfig {
    private static final String PREF_NAME = "antigravity_config";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "auth_token";

    private String serverUrl;
    private String authToken;

    public ServerConfig(String serverUrl, String authToken) {
        this.serverUrl = serverUrl != null ? serverUrl.trim() : "";
        this.authToken = authToken != null ? authToken.trim() : "";
    }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public static ServerConfig load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String url = sp.getString(KEY_URL, "http://127.0.0.1:8080");
        String token = sp.getString(KEY_TOKEN, "");
        return new ServerConfig(url, token);
    }

    public void save(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
          .putString(KEY_URL, serverUrl)
          .putString(KEY_TOKEN, authToken)
          .apply();
    }
}
