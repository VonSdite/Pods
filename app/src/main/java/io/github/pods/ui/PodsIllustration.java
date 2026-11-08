package io.github.pods.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

public final class PodsIllustration extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader aura = new RadialGradient(170, 92, 125, 0x1C9EBEFF, 0x009EBEFF, Shader.TileMode.CLAMP);
    private final Shader shadow = new RadialGradient(170, 171, 65, 0x20373E50, 0x00373E50, Shader.TileMode.CLAMP);
    private final Shader shell = new LinearGradient(110, 108, 230, 169,
            new int[]{Color.WHITE, 0xFFF7F8FA, 0xFFDDE2E9}, null, Shader.TileMode.CLAMP);
    private final Shader porcelain = new LinearGradient(0, 0, 23, 70, Color.WHITE, 0xFFDCE2EA, Shader.TileMode.CLAMP);
    private final Path earbudShape = new Path();
    private boolean connected;

    public PodsIllustration(Context context) {
        super(context);
        setContentDescription("耳机与充电盒示意图");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        earbudShape.moveTo(16, 0);
        earbudShape.cubicTo(-8, -4, -24, 8, -21, 25);
        earbudShape.cubicTo(-19, 40, 4, 43, 16, 31);
        earbudShape.cubicTo(28, 20, 31, 5, 16, 0);
    }

    public void setConnected(boolean connected) {
        if (this.connected != connected) { this.connected = connected; invalidate(); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 340f, getHeight() / 180f);
        canvas.save();
        canvas.translate((getWidth() - 340 * scale) / 2, (getHeight() - 180 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(aura);
        canvas.drawOval(38, 0, 302, 180, paint);
        paint.setShader(null);
        earbud(canvas, 106, 25, -22, false);
        earbud(canvas, 228, 22, 22, true);

        paint.setShader(shadow);
        canvas.save();
        canvas.scale(1, 0.16f, 170, 171);
        canvas.drawCircle(170, 171, 65, paint);
        canvas.restore();
        paint.setShader(shell);
        canvas.drawRoundRect(107, 106, 233, 167, 24, 24, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFFDBE0E7);
        paint.setStrokeWidth(0.8f);
        canvas.drawRoundRect(107, 106, 233, 167, 24, 24, paint);
        paint.setColor(0xFFC6CDD6);
        canvas.drawLine(110, 125, 230, 125, paint);
        paint.setColor(Color.WHITE);
        canvas.drawArc(109, 107, 231, 147, 190, 160, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(connected ? 0xFF55B888 : 0xFFB0B9C6);
        canvas.drawCircle(170, 139, 1.8f, paint);
        canvas.restore();
    }

    private void earbud(Canvas canvas, float x, float y, float rotation, boolean mirrored) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation, 8, 22);
        if (mirrored) canvas.scale(-1, 1, 8, 22);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(porcelain);
        canvas.drawRoundRect(5, 22, 20, 82, 7, 7, paint);
        canvas.drawPath(earbudShape, paint);
        paint.setShader(null);
        paint.setColor(0xFF1D2631);
        canvas.save();
        canvas.rotate(22, -12, 18);
        canvas.drawRoundRect(-20, 8, -7, 28, 6, 6, paint);
        paint.setColor(0xFF65707E);
        paint.setStrokeWidth(0.8f);
        canvas.drawLine(-16, 11, -16, 24, paint);
        canvas.restore();
        paint.setColor(0xFF47515E);
        canvas.drawRoundRect(11, 10, 15, 20, 2, 2, paint);
        paint.setColor(0xFFBCC5D0);
        canvas.drawRoundRect(7, 77, 18, 82, 3, 3, paint);
        canvas.restore();
    }
}
