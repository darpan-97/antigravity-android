package com.antigravity.mobile.presenter;

import android.content.Context;
import com.antigravity.mobile.contract.ChatContract;
import com.antigravity.mobile.contract.ChatViewState;
import com.antigravity.mobile.contract.ConnectionState;
import com.antigravity.mobile.data.TranscriptRepository;
import com.antigravity.mobile.model.MessageItem;
import com.antigravity.mobile.model.ServerConfig;
import com.antigravity.mobile.network.StreamingClient;

import java.lang.ref.WeakReference;
import java.util.List;

public class ChatPresenter implements ChatContract.Presenter,
        TranscriptRepository.OnDataChangeListener,
        StreamingClient.StreamListener {

    private WeakReference<ChatContract.View> mViewRef;
    private final TranscriptRepository mRepository;
    private final Context mContext;
    private ServerConfig mConfig;
    private StreamingClient mStreamingClient;

    private int mConnectionState = ConnectionState.DISCONNECTED;
    private String mStatusMessage = "Offline";
    private boolean mIsSending = false;
    private MessageItem mActiveAssistantMessage = null;

    public ChatPresenter(Context context, TranscriptRepository repository) {
        this.mContext = context.getApplicationContext();
        this.mRepository = repository;
        this.mConfig = ServerConfig.load(mContext);
        this.mStreamingClient = new StreamingClient(mConfig, this);
    }

    @Override
    public void attachView(ChatContract.View view) {
        this.mViewRef = new WeakReference<>(view);
        mRepository.addListener(this);
        mStreamingClient.start();
        emitViewState();
    }

    @Override
    public void detachView() {
        mRepository.removeListener(this);
        mStreamingClient.stop();
        if (mViewRef != null) {
            mViewRef.clear();
            mViewRef = null;
        }
    }

    private ChatContract.View getView() {
        return mViewRef != null ? mViewRef.get() : null;
    }

    private void emitViewState() {
        ChatContract.View view = getView();
        if (view != null) {
            List<MessageItem> messages = mRepository.getAllMessages();
            ChatViewState state = new ChatViewState(
                    mConnectionState,
                    messages,
                    mStatusMessage,
                    mIsSending
            );
            view.renderState(state);
        }
    }

    @Override
    public void sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String trimmed = text.trim();

        // 1. Persist user message locally immediately
        MessageItem userMsg = new MessageItem(MessageItem.SENDER_USER, trimmed);
        mRepository.insertOrUpdateMessage(userMsg);

        ChatContract.View view = getView();
        if (view != null) {
            view.clearInput();
            view.scrollToBottom();
            view.showThinking(true, "Antigravity is thinking...");
        }

        mIsSending = true;
        emitViewState();

        // 2. Dispatch via StreamingClient
        mStreamingClient.sendMessage(trimmed, new StreamingClient.MessageCallback() {
            @Override
            public void onSuccess() {
                mIsSending = false;
                emitViewState();
            }

            @Override
            public void onError(String error) {
                mIsSending = false;
                emitViewState();
                ChatContract.View v = getView();
                if (v != null) {
                    v.showError("Failed to deliver message: " + error);
                }
            }
        });
    }

    @Override
    public void onServerConfigUpdated(ServerConfig newConfig) {
        this.mConfig = newConfig;
        mConfig.save(mContext);
        mStreamingClient.updateConfig(newConfig);
    }

    @Override
    public void retryConnection() {
        mStreamingClient.restart();
    }

    @Override
    public void openSettings() {
        ChatContract.View view = getView();
        if (view != null) {
            view.showServerConfigDialog(mConfig);
        }
    }

    // --- TranscriptRepository.OnDataChangeListener ---
    @Override
    public void onDataChanged(List<MessageItem> messages) {
        emitViewState();
    }

    // --- StreamingClient.StreamListener ---
    @Override
    public void onConnected() {
        mConnectionState = ConnectionState.CONNECTED;
        mStatusMessage = "Connected";
        emitViewState();
    }

    @Override
    public void onConnecting(int attempt) {
        mConnectionState = ConnectionState.CONNECTING;
        mStatusMessage = attempt > 1 ? "Reconnecting (" + attempt + ")..." : "Connecting...";
        emitViewState();
    }

    @Override
    public void onDisconnected(String reason) {
        mConnectionState = ConnectionState.DISCONNECTED;
        mStatusMessage = "Disconnected: " + reason;
        mActiveAssistantMessage = null;
        emitViewState();
    }

    @Override
    public void onTokenReceived(String token) {
        if (mActiveAssistantMessage == null) {
            mActiveAssistantMessage = new MessageItem(MessageItem.SENDER_ASSISTANT, token);
            mActiveAssistantMessage.setStreaming(true);
        } else {
            mActiveAssistantMessage.appendContent(token);
        }
        mRepository.insertOrUpdateMessage(mActiveAssistantMessage);
    }

    @Override
    public void onMessageComplete(String fullText) {
        if (mActiveAssistantMessage != null) {
            mActiveAssistantMessage.setContent(fullText);
            mActiveAssistantMessage.setStreaming(false);
            mRepository.insertOrUpdateMessage(mActiveAssistantMessage);
            mActiveAssistantMessage = null;
        } else {
            MessageItem completeMsg = new MessageItem(MessageItem.SENDER_ASSISTANT, fullText);
            mRepository.insertOrUpdateMessage(completeMsg);
        }
    }

    @Override
    public void onError(Throwable error) {
        ChatContract.View v = getView();
        if (v != null) {
            v.showError(error.getMessage());
        }
    }

    @Override
    public void onStatus(String status, String text) {
        ChatContract.View v = getView();
        if (v != null) v.showThinking("thinking".equals(status), text);
    }

    @Override
    public void onTasks(List<String> tasks) {
        ChatContract.View v = getView();
        if (v != null) v.showTasks(tasks);
    }

    private String mCurrentId = "";
    private org.json.JSONArray mConvItems = new org.json.JSONArray();

    @Override
    public void onEvent(org.json.JSONObject ev) {
        String type = ev.optString("type", "");
        ChatContract.View v = getView();
        try {
            switch (type) {
                case "history": {
                    org.json.JSONArray arr = ev.optJSONArray("messages");
                    List<MessageItem> list = new java.util.ArrayList<>();
                    long base = System.currentTimeMillis() - 100000L;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject m = arr.getJSONObject(i);
                            String s = m.optString("sender", "assistant");
                            long ts = m.optLong("ts", base + i);
                            list.add(new MessageItem(base + i, s, m.optString("content", ""), ts, false));
                        }
                    }
                    mActiveAssistantMessage = null;
                    mRepository.replaceAll(list);
                    if (v != null) {
                        v.setHeader(ev.optString("project", ""), ev.optString("title", ""));
                        v.scrollToBottom();
                    }
                    break;
                }
                case "message": {
                    String sender = ev.optString("sender", "assistant");
                    String text = ev.optString("message", "");
                    List<MessageItem> all = mRepository.getAllMessages();
                    if (!all.isEmpty()) {
                        MessageItem last = all.get(all.size() - 1);
                        if (last.getSender().equals(sender) && text.trim().equals(last.getContent().trim())) break;
                    }
                    mActiveAssistantMessage = null;
                    mRepository.insertOrUpdateMessage(new MessageItem(sender, text));
                    if (v != null) v.scrollToBottom();
                    break;
                }
                case "conversations":
                    mCurrentId = ev.optString("current", "");
                    mConvItems = ev.optJSONArray("items") != null ? ev.optJSONArray("items") : new org.json.JSONArray();
                    if (v != null) v.showConversations(mCurrentId, mConvItems);
                    break;
                case "tool":
                    if (v != null) v.showThinking(true, ev.optString("text", "Working..."));
                    break;
            }
        } catch (Exception e) {
            android.util.Log.e("Antigravity", "onEvent failed", e);
        }
    }

    @Override
    public void selectConversation(String id) {
        try {
            org.json.JSONObject b = new org.json.JSONObject();
            b.put("id", id);
            mStreamingClient.post("/select", b, new StreamingClient.MessageCallback() {
                public void onSuccess() {}
                public void onError(String e) {
                    ChatContract.View v = getView();
                    if (v != null) v.showError("Switch failed: " + e);
                }
            });
        } catch (Exception ignored) {}
    }

    @Override
    public void newConversation(String prompt) {
        try {
            org.json.JSONObject b = new org.json.JSONObject();
            b.put("prompt", prompt);
            ChatContract.View v0 = getView();
            if (v0 != null) v0.showThinking(true, "Starting new conversation...");
            mStreamingClient.post("/new", b, new StreamingClient.MessageCallback() {
                public void onSuccess() {}
                public void onError(String e) {
                    ChatContract.View v = getView();
                    if (v != null) { v.showThinking(false, ""); v.showError("New conversation failed: " + e); }
                }
            });
        } catch (Exception ignored) {}
    }
}
