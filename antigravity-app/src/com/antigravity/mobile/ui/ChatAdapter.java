package com.antigravity.mobile.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.antigravity.mobile.R;
import com.antigravity.mobile.model.MessageItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends BaseAdapter {
    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<MessageItem> mItems = new ArrayList<>();
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatAdapter(Context context) {
        this.mContext = context;
        this.mInflater = LayoutInflater.from(context);
    }

    public void setData(List<MessageItem> newItems) {
        mItems.clear();
        if (newItems != null) {
            mItems.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public MessageItem getItem(int position) {
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).getId();
    }

    static class ViewHolder {
        LinearLayout container;
        TextView tvSender;
        LinearLayout bubbleLayout;
        TextView tvContent;
        TextView tvTime;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.item_message, parent, false);
            holder = new ViewHolder();
            holder.container = convertView.findViewById(R.id.msg_container);
            holder.tvSender = convertView.findViewById(R.id.tv_sender);
            holder.bubbleLayout = convertView.findViewById(R.id.bubble_layout);
            holder.tvContent = convertView.findViewById(R.id.tv_content);
            holder.tvTime = convertView.findViewById(R.id.tv_time);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        MessageItem item = getItem(position);
        boolean isUser = MessageItem.SENDER_USER.equalsIgnoreCase(item.getSender());
        boolean isTool = MessageItem.SENDER_TOOL.equalsIgnoreCase(item.getSender());

        // Alignments
        if (isUser) {
            holder.container.setGravity(Gravity.RIGHT);
            holder.tvSender.setText("YOU");
            holder.tvSender.setTextColor(Color.parseColor("#9CA3AF"));
            holder.bubbleLayout.setBackgroundResource(R.drawable.bg_bubble_user_desktop);
        } else if (isTool) {
            holder.container.setGravity(Gravity.LEFT);
            holder.tvSender.setText("⚡ TOOL EXECUTION");
            holder.tvSender.setTextColor(Color.parseColor("#F59E0B"));
            holder.bubbleLayout.setBackgroundColor(Color.TRANSPARENT);
        } else {
            holder.container.setGravity(Gravity.LEFT);
            holder.tvSender.setText("ANTIGRAVITY");
            holder.tvSender.setTextColor(Color.parseColor("#3B82F6"));
            holder.bubbleLayout.setBackgroundColor(Color.TRANSPARENT);
        }

        String contentText = item.getContent() != null ? item.getContent() : "";
        CharSequence shown = isUser ? contentText : MarkdownRenderer.render(contentText);
        if (item.isStreaming()) {
            shown = TextUtils.concat(shown, " ▍");
        }
        holder.tvContent.setText(shown);
        holder.tvTime.setText(mTimeFormat.format(new Date(item.getTimestamp())));

        return convertView;
    }
}
