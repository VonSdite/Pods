package io.github.pods.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.pods.core.ListeningMode;

// 仅通过代码创建，不供 XML 布局使用。
@SuppressLint("ViewConstructor")
public final class ModeButton extends LinearLayout {
    private final GlyphView glyph;
    private final TextView title;
    private final TextView detail;
    private final ListeningMode mode;
    private final boolean wide;
    private Boolean lastSelection;

    public ModeButton(Context context, ListeningMode mode, boolean wide) {
        super(context);
        this.mode = mode;
        this.wide = wide;
        setOrientation(wide ? HORIZONTAL : VERTICAL);
        setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        setPadding(Ui.dp(context, wide ? 20 : 4), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10));
        glyph = new GlyphView(context, mode);
        addView(glyph, new LayoutParams(Ui.dp(context, 28), Ui.dp(context, 28)));
        LinearLayout labels = Ui.column(context);
        labels.setGravity(wide ? Gravity.START : Gravity.CENTER);
        LayoutParams labelParams = new LayoutParams(wide ? 0 : LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, wide ? 1 : 0);
        if (wide) labelParams.setMarginStart(Ui.dp(context, 16));
        else labelParams.topMargin = Ui.dp(context, 7);
        addView(labels, labelParams);
        title = Ui.text(context, mode.label, wide ? 17 : 13, Ui.INK, true);
        if (!wide) title.setGravity(Gravity.CENTER);
        labels.addView(title);
        detail = Ui.text(context, wide ? "关闭降噪与通透" : "", 11, Ui.SECONDARY, false);
        if (wide) {
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = Ui.dp(context, 5);
            labels.addView(detail, params);
        }
        setContentDescription(wide ? "关闭，关闭降噪与通透" : mode.label);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        detail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        Ui.touch(this, Color.WHITE, 20);
    }

    // 当前只提供中文，省略号表示等待耳机确认。
    @SuppressLint("SetTextI18n")
    public void bind(boolean available, boolean selected, boolean pending) {
        setEnabled(available);
        setSelected(selected);
        setAlpha(available || selected ? 1f : 0.48f);
        if (lastSelection == null || lastSelection != selected) {
            Ui.touch(this, selected ? Ui.PALE_BLUE : Color.WHITE, 20);
            lastSelection = selected;
        }
        setEnabled(available);
        glyph.setColor(selected ? Ui.BLUE : Ui.INK);
        title.setTextColor(selected ? Ui.BLUE : Ui.INK);
        title.setText(mode.label + (pending && !wide ? "…" : ""));
        if (wide) detail.setText(pending ? "等待耳机确认…" : selected ? "保留自然的被动隔音" : "关闭降噪与通透");
        setContentDescription(mode.label + (selected ? "，已选中" : "") + (pending ? "，正在等待耳机确认" : "")
                + (!available && !selected ? "，暂不可用" : ""));
    }
}
