package com.localcanvas.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive touch canvas for drawing Inpainting masks with brush/eraser, undo/redo, and clear.
 */
public final class MaskCanvasView extends View {
    private static final class Stroke {
        final Path path;
        final float width;
        final boolean erase;
        Stroke(Path p, float w, boolean e) { path = p; width = w; erase = e; }
    }

    private Bitmap backgroundBitmap;
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Stroke> redoStack = new ArrayList<>();
    private Path currentPath;
    private float brushRadiusPx = 32f;
    private boolean eraseMode = false;

    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MaskCanvasView(Context context) {
        super(context);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);

        previewPaint.setStyle(Paint.Style.STROKE);
        previewPaint.setStrokeCap(Paint.Cap.ROUND);
        previewPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setBackgroundBitmap(Bitmap bmp) {
        this.backgroundBitmap = bmp;
        strokes.clear();
        redoStack.clear();
        invalidate();
    }

    public void setBrushSize(float px) { this.brushRadiusPx = Math.max(6f, Math.min(140f, px)); }
    public void setEraseMode(boolean erase) { this.eraseMode = erase; }
    public boolean canUndo() { return !strokes.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void undo() {
        if (!strokes.isEmpty()) {
            redoStack.add(strokes.remove(strokes.size() - 1));
            invalidate();
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            strokes.add(redoStack.remove(redoStack.size() - 1));
            invalidate();
        }
    }

    public void clearMask() {
        strokes.clear();
        redoStack.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF101018);
        if (backgroundBitmap != null && !backgroundBitmap.isRecycled()) {
            RectF dst = new RectF(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(backgroundBitmap, null, dst, null);
        }
        for (Stroke s : strokes) {
            previewPaint.setColor(s.erase ? 0xAA000000 : 0xAAFFFFFF);
            previewPaint.setStrokeWidth(s.width);
            canvas.drawPath(s.path, previewPaint);
        }
        if (currentPath != null) {
            previewPaint.setColor(eraseMode ? 0xAA000000 : 0xAAFFFFFF);
            previewPaint.setStrokeWidth(brushRadiusPx);
            canvas.drawPath(currentPath, previewPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                currentPath = new Path();
                currentPath.moveTo(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (currentPath != null) {
                    currentPath.lineTo(x, y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (currentPath != null) {
                    strokes.add(new Stroke(currentPath, brushRadiusPx, eraseMode));
                    redoStack.clear();
                    currentPath = null;
                    invalidate();
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Exports a binary RGB mask Bitmap (black=preserve, white=inpaint) scaled to target size. */
    public Bitmap exportMaskBitmap(int targetW, int targetH) {
        int vw = Math.max(1, getWidth());
        int vh = Math.max(1, getHeight());
        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.scale((float) targetW / vw, (float) targetH / vh);
        for (Stroke s : strokes) {
            drawPaint.setColor(s.erase ? Color.BLACK : Color.WHITE);
            drawPaint.setStrokeWidth(s.width);
            c.drawPath(s.path, drawPaint);
        }
        return out;
    }
}
