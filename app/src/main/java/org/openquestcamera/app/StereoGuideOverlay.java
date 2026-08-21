package org.openquestcamera.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class StereoGuideOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile int gridMode;
    private volatile float levelRoll;

    StereoGuideOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        paint.setColor(Color.argb(210, 255, 255, 255));
        paint.setStrokeWidth(2f);
    }

    void setGridMode(int mode) {
        gridMode = Math.max(0, Math.min(2, mode));
        postInvalidate();
    }

    void setLevelRoll(float roll) {
        levelRoll = roll;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        int eyeWidth = width / 2;
        if (eyeWidth <= 0 || height <= 0) return;

        if (gridMode == 1) {
            for (int eye = 0; eye < 2; eye++) {
                float x0 = eye * eyeWidth;
                canvas.drawLine(x0 + eyeWidth / 3f, 0, x0 + eyeWidth / 3f, height, paint);
                canvas.drawLine(x0 + eyeWidth * 2f / 3f, 0, x0 + eyeWidth * 2f / 3f, height, paint);
                canvas.drawLine(x0, height / 3f, x0 + eyeWidth, height / 3f, paint);
                canvas.drawLine(x0, height * 2f / 3f, x0 + eyeWidth, height * 2f / 3f, paint);
            }
        } else if (gridMode == 2) {
            for (int eye = 0; eye < 2; eye++) {
                float cx = eye * eyeWidth + eyeWidth / 2f;
                float cy = height / 2f;
                canvas.drawLine(cx - 28, cy, cx + 28, cy, paint);
                canvas.drawLine(cx, cy - 28, cx, cy + 28, paint);
            }
        }

        float roll = Math.max(-45f, Math.min(45f, levelRoll));
        float y = height - 28f;
        float lineLength = Math.min(180f, eyeWidth * 0.58f);
        float dy = (float) Math.tan(Math.toRadians(roll)) * lineLength * 0.5f;
        for (int eye = 0; eye < 2; eye++) {
            float cx = eye * eyeWidth + eyeWidth / 2f;
            canvas.drawLine(cx - lineLength / 2f, y - dy, cx + lineLength / 2f, y + dy, paint);
        }
    }
}
