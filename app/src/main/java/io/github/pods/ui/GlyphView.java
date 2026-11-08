package io.github.pods.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import io.github.pods.core.ListeningMode;

// 仅通过代码创建，不供 XML 布局使用。
@SuppressLint("ViewConstructor")
public final class GlyphView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ListeningMode mode;
    private int color = Ui.INK;

    public GlyphView(Context context, ListeningMode mode) {
        super(context);
        this.mode = mode;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setColor(int color) { this.color = color; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float scale = Math.min(getWidth(), getHeight()) / 32f;
        canvas.translate((getWidth() - scale * 32) / 2, (getHeight() - scale * 32) / 2);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        if (mode == ListeningMode.OFF) {
            canvas.drawArc(6, 6, 26, 26, -55, 290, false, paint);
            canvas.drawLine(16, 3.5f, 16, 15, paint);
        } else if (mode == ListeningMode.NOISE_CANCELLATION) {
            canvas.drawLine(16, 6, 16, 26, paint);
            canvas.drawLine(10, 11, 10, 21, paint);
            canvas.drawLine(22, 11, 22, 21, paint);
            canvas.drawLine(4, 14, 4, 18, paint);
            canvas.drawLine(28, 14, 28, 18, paint);
        } else if (mode == ListeningMode.TRANSPARENCY) {
            canvas.drawArc(6, 4, 26, 28, -55, 110, false, paint);
            canvas.drawArc(6, 4, 26, 28, 125, 110, false, paint);
            canvas.drawLine(13, 11, 13, 21, paint);
            canvas.drawLine(19, 8, 19, 24, paint);
        } else {
            canvas.drawArc(4, 7, 28, 25, 190, 160, false, paint);
            canvas.drawArc(8, 12, 24, 29, 190, 160, false, paint);
            canvas.drawCircle(16, 25, 1.4f, paint);
        }
        canvas.restore();
    }
}
