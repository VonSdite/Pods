package io.github.pods.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Objects;

/** 电池内腔按实际百分比连续填充，未知电量使用短横线。 */
public final class BatteryIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lightning = new Path();
    private Integer level;
    private boolean charging;
    private int color = Ui.SECONDARY;

    public BatteryIcon(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        lightning.moveTo(17, 2.5f);
        lightning.lineTo(11.5f, 9.7f);
        lightning.lineTo(15, 9.7f);
        lightning.lineTo(13.5f, 15.5f);
        lightning.lineTo(19.2f, 7.9f);
        lightning.lineTo(15.7f, 7.9f);
        lightning.close();
    }

    public void bind(Integer level, boolean charging, boolean stale) {
        int nextColor = level == null ? Ui.SECONDARY : level <= 20 ? 0xFFBF4A3A
                : level <= 50 ? 0xFFE3AC18 : Ui.GREEN;
        if (level != null && stale) nextColor = (nextColor & 0x00FFFFFF) | 0x99000000;
        boolean nextCharging = level != null && charging && !stale;
        if (Objects.equals(this.level, level) && this.charging == nextCharging && color == nextColor) return;
        this.level = level;
        this.charging = nextCharging;
        color = nextColor;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 32f, getHeight() / 18f);
        canvas.save();
        canvas.translate((getWidth() - 32 * scale) / 2, (getHeight() - 18 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f);
        canvas.drawRoundRect(0.8f, 2.8f, 28.8f, 15.2f, 3, 3, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(30.2f, 6.5f, 31.7f, 11.5f, 0.7f, 0.7f, paint);
        if (level == null) {
            canvas.drawRoundRect(11, 8.3f, 18, 9.7f, 0.7f, 0.7f, paint);
        } else {
            paint.setColor(0x1969717E);
            canvas.drawRoundRect(3, 5, 26.6f, 13, 1.2f, 1.2f, paint);
            float fraction = Math.max(0, Math.min(100, level)) / 100f;
            if (fraction > 0) {
                paint.setColor(color);
                canvas.drawRoundRect(3, 5, 3 + 23.6f * fraction, 13, 1.2f, 1.2f, paint);
            }
        }
        if (charging) {
            // 白色轮廓让闪电在高低电量的填充区域内都清晰可辨。
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(lightning, paint);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(lightning, paint);
        }
        canvas.restore();
    }
}
