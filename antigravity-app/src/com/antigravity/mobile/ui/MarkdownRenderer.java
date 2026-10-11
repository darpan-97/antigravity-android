package com.antigravity.mobile.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small markdown renderer: headers, bold, italic, inline/fenced code, bullets, links. */
public final class MarkdownRenderer {
    private static final int CODE_BG = Color.parseColor("#1E2230");
    private static final int CODE_FG = Color.parseColor("#E5C07B");
    private static final int LINK_FG = Color.parseColor("#60A5FA");
    private static final Pattern INLINE = Pattern.compile(
            "(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))|((?<![*\\w])\\*[^*\\n]+\\*(?![*\\w]))");

    private MarkdownRenderer() {}

    public static CharSequence render(String src) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (src == null) return out;
        String[] lines = src.replace("\r", "").split("\n", -1);
        boolean inCode = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            int start = out.length();
            if (inCode) {
                out.append(line.isEmpty() ? " " : line);
                int end = out.length();
                out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(CODE_FG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.9f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                String t = line;
                int headerLevel = 0;
                while (headerLevel < t.length() && headerLevel < 6 && t.charAt(headerLevel) == '#') headerLevel++;
                if (headerLevel > 0 && headerLevel < t.length() && t.charAt(headerLevel) == ' ') {
                    t = t.substring(headerLevel + 1);
                    appendInline(out, t);
                    int end = out.length();
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    float size = headerLevel == 1 ? 1.35f : headerLevel == 2 ? 1.2f : 1.08f;
                    out.setSpan(new RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    String trimmed = t.replaceFirst("^\\s+", "");
                    int indent = t.length() - trimmed.length();
                    String prefix = "";
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        prefix = (indent >= 2 ? "      ◦ " : "  • ");
                        trimmed = trimmed.substring(2);
                    } else if (trimmed.matches("^\\d+\\.\\s.*")) {
                        int dot = trimmed.indexOf('.');
                        prefix = "  " + trimmed.substring(0, dot + 1) + " ";
                        trimmed = trimmed.substring(dot + 2);
                    } else if (trimmed.startsWith("> ")) {
                        prefix = "▎ ";
                        trimmed = trimmed.substring(2);
                    }
                    out.append(prefix);
                    appendInline(out, trimmed);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }
        // trim trailing newlines
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    private static void appendInline(SpannableStringBuilder out, String text) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String tok = m.group();
            int s = out.length();
            if (m.group(1) != null) {
                out.append(tok.substring(1, tok.length() - 1));
                int e = out.length();
                out.setSpan(new TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(CODE_BG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(CODE_FG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                out.append(tok.substring(2, tok.length() - 2));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                int close = tok.indexOf("](");
                out.append(tok.substring(1, close));
                out.setSpan(new ForegroundColorSpan(LINK_FG), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new UnderlineSpan(), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(tok.substring(1, tok.length() - 1));
                out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        out.append(text, last, text.length());
    }
}
