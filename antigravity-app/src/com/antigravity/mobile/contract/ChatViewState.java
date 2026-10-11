package com.antigravity.mobile.contract;

import com.antigravity.mobile.model.MessageItem;
import java.util.List;

public class ChatViewState {
    public final int connectionState;
    public final List<MessageItem> messages;
    public final String statusMessage;
    public final boolean isSending;

    public ChatViewState(int connectionState, List<MessageItem> messages, String statusMessage, boolean isSending) {
        this.connectionState = connectionState;
        this.messages = messages;
        this.statusMessage = statusMessage;
        this.isSending = isSending;
    }
}
