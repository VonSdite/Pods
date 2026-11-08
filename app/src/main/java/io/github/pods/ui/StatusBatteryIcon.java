package io.github.pods.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;

/** 状态栏由系统统一着色，只绘制电量数字的白色轮廓。 */
public final class StatusBatteryIcon {
    private StatusBatteryIcon() {}

    public static Icon create(int level) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(88);
        String value = Integer.toString(level);
        float width = paint.measureText(value);
        if (width > 92) paint.setTextSize(paint.getTextSize() * 92 / width);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        new Canvas(bitmap).drawText(value, 48, 48 - (metrics.ascent + metrics.descent) / 2, paint);
        return Icon.createWithBitmap(bitmap);
    }
}
