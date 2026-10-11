package com.antigravity.mobile.model;

public class MessageItem {
    public static final String SENDER_USER = "user";
    public static final String SENDER_ASSISTANT = "assistant";
    public static final String SENDER_TOOL = "tool";
    public static final String SENDER_SYSTEM = "system";

    private long id;
    private String sender;
    private String content;
    private long timestamp;
    private boolean isStreaming;

    public MessageItem(long id, String sender, String content, long timestamp, boolean isStreaming) {
        this.id = id;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
        this.isStreaming = isStreaming;
    }

    public MessageItem(String sender, String content) {
        this(System.currentTimeMillis(), sender, content, System.currentTimeMillis(), false);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public boolean isStreaming() { return isStreaming; }
    public void setStreaming(boolean streaming) { isStreaming = streaming; }

    public void appendContent(String chunk) {
        if (this.content == null) {
            this.content = chunk;
        } else {
            this.content += chunk;
        }
    }
}
