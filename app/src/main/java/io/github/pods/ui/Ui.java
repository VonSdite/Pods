package io.github.pods.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BACKGROUND = Color.rgb(245, 246, 248);
    public static final int INK = Color.rgb(27, 31, 39);
    public static final int SECONDARY = Color.rgb(105, 113, 126);
    public static final int BLUE = Color.rgb(39, 104, 232);
    public static final int PALE_BLUE = Color.rgb(232, 240, 255);
    public static final int GREEN = Color.rgb(39, 133, 88);

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable surface(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    public static TextView text(Context context, String value, float size, int color, boolean medium) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setFontFeatureSettings("tnum");
        text.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setIncludeFontPadding(false);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    public static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public static void touch(View view, int color, int radius) {
        Context context = view.getContext();
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182768E8),
                surface(context, color, radius), surface(context, Color.WHITE, radius)));
        view.setClickable(true);
        view.setFocusable(true);
    }

    public static TextView button(Context context, String value, int background, int color) {
        TextView view = text(context, value, 14, color, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 48));
        view.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        touch(view, background, 16);
        return view;
    }
}
