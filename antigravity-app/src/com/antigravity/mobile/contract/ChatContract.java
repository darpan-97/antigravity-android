package com.antigravity.mobile.contract;

import com.antigravity.mobile.model.ServerConfig;

public interface ChatContract {

    interface View {
        void renderState(ChatViewState state);
        void showError(String message);
        void showServerConfigDialog(ServerConfig currentConfig);
        void clearInput();
        void scrollToBottom();
        void showThinking(boolean thinking, String text);
        void showTasks(java.util.List<String> tasks);
        void showConversations(String currentId, org.json.JSONArray items);
        void setHeader(String project, String title);
        void showNewConversationDialog();
    }

    interface Presenter {
        void attachView(View view);
        void detachView();
        void sendMessage(String text);
        void selectConversation(String id);
        void newConversation(String prompt);
        void onServerConfigUpdated(ServerConfig newConfig);
        void retryConnection();
        void openSettings();
    }
}
