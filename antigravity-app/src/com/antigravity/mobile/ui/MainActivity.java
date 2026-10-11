package com.antigravity.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.antigravity.mobile.AntigravityApp;
import com.antigravity.mobile.R;
import com.antigravity.mobile.contract.ChatContract;
import com.antigravity.mobile.model.ServerConfig;
import com.antigravity.mobile.presenter.ChatPresenter;

public class MainActivity extends Activity implements ChatContract.View {

    private ChatPresenter mPresenter;
    private ChatAdapter mAdapter;

    private ListView mListView;
    private EditText mEtMessage;
    private FrameLayout mBtnSend;
    private View mStatusDot;
    private TextView mStatusText;
    private LinearLayout mStatusPill;
    private TextView mBtnSettings;
    private LinearLayout mEmptyState;
    private View mDrawer, mDim, mBannerTasks, mBannerThinking, mSpinner, mTasksSpinner;
    private LinearLayout mTasksList;
    private TextView mTasksTitle, mTasksChevron, mThinkingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge dark theme window bars
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#0A0D14"));
        window.setNavigationBarColor(Color.parseColor("#0A0D14"));

        setContentView(R.layout.activity_main);

        // Initialize UI Elements
        mListView = findViewById(R.id.lv_messages);
        mEtMessage = findViewById(R.id.et_message);
        mBtnSend = findViewById(R.id.btn_send);
        mStatusDot = findViewById(R.id.status_dot);
        mStatusText = findViewById(R.id.status_text);
        mStatusPill = findViewById(R.id.status_pill);
        mBtnSettings = findViewById(R.id.btn_settings);
        mEmptyState = findViewById(R.id.empty_state);

        // Set up ChatAdapter
        mAdapter = new ChatAdapter(this);
        mListView.setAdapter(mAdapter);

        // Presenter
        mPresenter = new ChatPresenter(this, AntigravityApp.getInstance().getRepository());

        // Event Listeners
        View.OnClickListener sendClickListener = v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            String text = mEtMessage.getText().toString();
            android.util.Log.i("Antigravity", "Send button clicked with text: " + text);
            if (text.trim().isEmpty()) {
                Toast.makeText(MainActivity.this, "Please enter a message", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(MainActivity.this, "Sending to Antigravity...", Toast.LENGTH_SHORT).show();
            mPresenter.sendMessage(text);
        };

        mBtnSend.setOnClickListener(sendClickListener);
        View sendIcon = findViewById(R.id.btn_send_icon);
        if (sendIcon != null) {
            sendIcon.setOnClickListener(sendClickListener);
        }

        mEtMessage.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnterKey = (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_NULL ||
                isEnterKey) {
                String text = mEtMessage.getText().toString();
                android.util.Log.i("Antigravity", "Editor action triggered (actionId=" + actionId + "): " + text);
                if (!text.trim().isEmpty()) {
                    mEtMessage.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    Toast.makeText(MainActivity.this, "Sending to Antigravity...", Toast.LENGTH_SHORT).show();
                    mPresenter.sendMessage(text);
                    return true;
                }
            }
            return false;
        });

        mBtnSettings.setOnClickListener(v -> mPresenter.openSettings());

        mStatusPill.setOnClickListener(v -> mPresenter.retryConnection());

        // Desktop-style sidebar drawer
        mDrawer = findViewById(R.id.left_drawer);
        mDim = findViewById(R.id.drawer_dim_overlay);
        findViewById(R.id.btn_menu).setOnClickListener(v -> toggleDrawer(true));
        findViewById(R.id.btn_close_drawer).setOnClickListener(v -> toggleDrawer(false));
        mDim.setOnClickListener(v -> toggleDrawer(false));
        findViewById(R.id.drawer_settings_item).setOnClickListener(v -> {
            toggleDrawer(false);
            mPresenter.openSettings();
        });
        findViewById(R.id.btn_new_conversation).setOnClickListener(v -> {
            toggleDrawer(false);
            showNewConversationDialog();
        });

        // Running tasks + thinking banners
        mTasksList = findViewById(R.id.tasks_content_list);
        mTasksTitle = (TextView) findViewById(R.id.tv_tasks_title);
        mTasksChevron = (TextView) findViewById(R.id.tv_tasks_chevron);
        mBannerTasks = findViewById(R.id.banner_tasks);
        mBannerThinking = findViewById(R.id.banner_thinking);
        mThinkingText = (TextView) findViewById(R.id.tv_thinking_text);
        mSpinner = findViewById(R.id.tv_thinking_spinner);
        mTasksSpinner = findViewById(R.id.tasks_spinner_icon);
        findViewById(R.id.tasks_header).setOnClickListener(v -> {
            boolean show = mTasksList.getVisibility() != View.VISIBLE;
            mTasksList.setVisibility(show ? View.VISIBLE : View.GONE);
            mTasksChevron.setText(show ? "⌵" : "⌃");
        });
        mTasksList.setVisibility(View.GONE);
        mBannerTasks.setVisibility(View.GONE);
        mTasksChevron.setText("⌃");
        startSpin(mTasksSpinner);
    }

    private void startSpin(View v) {
        android.view.animation.RotateAnimation a = new android.view.animation.RotateAnimation(
                0, 360, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        a.setDuration(1100);
        a.setRepeatCount(android.view.animation.Animation.INFINITE);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        v.startAnimation(a);
    }

    private void toggleDrawer(boolean open) {
        mDrawer.setVisibility(open ? View.VISIBLE : View.GONE);
        mDim.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            mDrawer.setTranslationX(-mDrawer.getLayoutParams().width * 3f);
            mDrawer.animate().translationX(0).setDuration(200).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawer != null && mDrawer.getVisibility() == View.VISIBLE) {
            toggleDrawer(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void showThinking(boolean thinking, String text) {
        mBannerThinking.setVisibility(thinking ? View.VISIBLE : View.GONE);
        if (thinking) {
            mThinkingText.setText(text);
            mSpinner.clearAnimation();
            startSpin(mSpinner);
            scrollToBottom();
        } else {
            mSpinner.clearAnimation();
        }
    }

    @Override
    public void showTasks(java.util.List<String> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            mBannerTasks.setVisibility(View.GONE);
            return;
        }
        mBannerTasks.setVisibility(View.VISIBLE);
        mTasksTitle.setText(tasks.size() + (tasks.size() == 1 ? " task running" : " tasks running"));
        mTasksList.removeAllViews();
        for (String t : tasks) {
            TextView tv = new TextView(this);
            tv.setText("⟳ " + t);
            tv.setTextColor(Color.parseColor("#6B7280"));
            tv.setTextSize(11);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tv.setPadding(0, 0, 0, 8);
            mTasksList.addView(tv);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mPresenter.attachView(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPresenter.detachView();
    }

    // --- ChatContract.View Implementation ---

    @Override
    public void renderState(com.antigravity.mobile.contract.ChatViewState state) {
        // 1. Connection Status Dot and Text
        int dotColor;
        String label;
        switch (state.connectionState) {
            case com.antigravity.mobile.contract.ConnectionState.CONNECTED:
                dotColor = Color.parseColor("#10B981"); // Emerald green
                label = "Live";
                break;
            case com.antigravity.mobile.contract.ConnectionState.CONNECTING:
                dotColor = Color.parseColor("#F59E0B"); // Amber
                label = "Connecting";
                break;
            case com.antigravity.mobile.contract.ConnectionState.DISCONNECTED:
            default:
                dotColor = Color.parseColor("#EF4444"); // Red
                label = "Offline";
                break;
        }

        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(dotColor);
        mStatusDot.setBackground(dotDrawable);
        mStatusText.setText(label);

        // 2. Transcript message list
        mAdapter.setData(state.messages);

        if (state.messages == null || state.messages.isEmpty()) {
            mEmptyState.setVisibility(View.VISIBLE);
            mListView.setVisibility(View.GONE);
        } else {
            mEmptyState.setVisibility(View.GONE);
            mListView.setVisibility(View.VISIBLE);
        }

        // 3. Send button state
        mBtnSend.setAlpha(state.isSending ? 0.5f : 1.0f);
        mBtnSend.setEnabled(!state.isSending);
    }

    @Override
    public void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void showServerConfigDialog(ServerConfig currentConfig) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_server_config, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        EditText etUrl = dialogView.findViewById(R.id.cfg_et_url);
        EditText etToken = dialogView.findViewById(R.id.cfg_et_token);
        Button btnCancel = dialogView.findViewById(R.id.cfg_btn_cancel);
        Button btnSave = dialogView.findViewById(R.id.cfg_btn_save);
        Button btnDisconnect = dialogView.findViewById(R.id.cfg_btn_disconnect);
        View btnQuickTailscale = dialogView.findViewById(R.id.cfg_btn_quick_tailscale);

        etUrl.setText(currentConfig.getServerUrl());
        etToken.setText(currentConfig.getAuthToken());

        btnQuickTailscale.setOnClickListener(v -> {
            etUrl.setText("http://100.100.225.42:8080");
            Toast.makeText(MainActivity.this, "Set Tailscale address (100.100.225.42)", Toast.LENGTH_SHORT).show();
        });

        btnDisconnect.setOnClickListener(v -> {
            mPresenter.onServerConfigUpdated(new ServerConfig("", ""));
            dialog.dismiss();
            Toast.makeText(MainActivity.this, "Logged out / Disconnected", Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String newUrl = etUrl.getText().toString();
            String newToken = etToken.getText().toString();
            mPresenter.onServerConfigUpdated(new ServerConfig(newUrl, newToken));
            dialog.dismiss();
            Toast.makeText(MainActivity.this, "Connected", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    @Override
    public void clearInput() {
        mEtMessage.setText("");
    }

    @Override
    public void scrollToBottom() {
        mListView.post(() -> mListView.setSelection(mAdapter.getCount() - 1));
    }

    @Override
    public void setHeader(String project, String title) {
        TextView p = findViewById(R.id.tv_project_name);
        TextView t = findViewById(R.id.tv_convo_title);
        if (p != null) p.setText(project == null || project.isEmpty() ? "No project" : project);
        if (t != null) t.setText(title == null || title.isEmpty() ? "Conversation" : title);
    }

    private static String relTime(long ms) {
        long d = Math.max(0, System.currentTimeMillis() - ms) / 1000;
        if (d < 60) return "now";
        if (d < 3600) return (d / 60) + "m";
        if (d < 86400) return (d / 3600) + "h";
        if (d < 86400 * 30) return (d / 86400) + "d";
        return (d / (86400 * 30)) + "mo";
    }

    @Override
    public void showConversations(String currentId, org.json.JSONArray items) {
        LinearLayout box = findViewById(R.id.drawer_projects);
        if (box == null) return;
        box.removeAllViews();
        java.util.LinkedHashMap<String, java.util.List<org.json.JSONObject>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("title", "");
            if (title.isEmpty() || "Untitled Conversation".equals(title)) {
                if (!o.optString("id").equals(currentId)) continue;
            }
            String proj = o.optString("project", "No project");
            if (proj.isEmpty()) proj = "No project";
            if (!groups.containsKey(proj)) groups.put(proj, new java.util.ArrayList<>());
            groups.get(proj).add(o);
        }
        float dp = getResources().getDisplayMetrics().density;
        for (java.util.Map.Entry<String, java.util.List<org.json.JSONObject>> g : groups.entrySet()) {
            TextView h = new TextView(this);
            h.setText("\uD83D\uDCC1 " + g.getKey());
            h.setTextColor(Color.parseColor("#E5C07B"));
            h.setTextSize(12);
            h.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (4 * dp));
            box.addView(h);
            for (org.json.JSONObject o : g.getValue()) {
                final String id = o.optString("id");
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding((int) (12 * dp), (int) (9 * dp), (int) (12 * dp), (int) (9 * dp));
                if (id.equals(currentId)) row.setBackgroundResource(R.drawable.bg_sidebar_item_active);
                if (o.optBoolean("running")) {
                    TextView dot = new TextView(this);
                    dot.setText("\u25CF ");
                    dot.setTextColor(Color.parseColor("#3B82F6"));
                    dot.setTextSize(10);
                    row.addView(dot);
                }
                TextView tt = new TextView(this);
                String title = o.optString("title", "");
                tt.setText(title.isEmpty() ? "Untitled Conversation" : title);
                tt.setTextColor(Color.parseColor(id.equals(currentId) ? "#FFFFFF" : "#C9D1D9"));
                tt.setTextSize(13);
                tt.setSingleLine(true);
                tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(tt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView tm = new TextView(this);
                tm.setText(relTime(o.optLong("time", 0)));
                tm.setTextColor(Color.parseColor("#6B7280"));
                tm.setTextSize(11);
                row.addView(tm);
                row.setOnClickListener(v -> {
                    toggleDrawer(false);
                    mPresenter.selectConversation(id);
                });
                box.addView(row);
            }
        }
    }

    @Override
    public void showNewConversationDialog() {
        final EditText et = new EditText(this);
        et.setHint("What should Antigravity work on?");
        et.setMinLines(2);
        new AlertDialog.Builder(this)
                .setTitle("New conversation")
                .setView(et)
                .setPositiveButton("Start", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) mPresenter.newConversation(t);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
