package com.posiciontelefono;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal slippy map: OpenStreetMap tiles plus the track, colored by GPS accuracy.
 * Drag to pan, pinch or double tap to zoom, tap near a point to inspect it.
 */
public final class TrackMapView extends View {

    private static final int[] QUALITY_COLORS = {0xFF1E88E5, 0xFFFB8C00, 0xFFE53935}; // good, fair, poor

    private final float density = getResources().getDisplayMetrics().density;
    private final float tilePx = TileCache.TILE_PX * density; // keeps street names readable
    private final TileCache tiles = new TileCache(this::invalidate);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint[] linePaints = new Paint[3];
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;

    private List<TrackPoint> points = List.of();
    private double[] xs = {}, ys = {};
    private byte[] segmentQuality = {}; // [i] = quality of the segment ending at point i
    private float[][] segments = {new float[0], new float[0], new float[0]};
    private final int[] segmentCount = new int[3];
    private double cx = 0.5, cy = 0.5, zoom = Mercator.MIN_ZOOM;
    private boolean needsFit;
    private int selected = -1;
    private Consumer<TrackPoint> onSelect = p -> {};

    public TrackMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (int q = 0; q < 3; q++) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(QUALITY_COLORS[q]);
            p.setStrokeWidth(5 * density);
            p.setStrokeCap(Paint.Cap.ROUND);
            linePaints[q] = p;
        }
        textPaint.setTextSize(11 * density);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(Color.DKGRAY);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                double world = worldPx();
                cx = Mercator.clamp(cx + dx / world, 0, 1);
                cy = Mercator.clamp(cy + dy / world, 0, 1);
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAround(1, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                select(e.getX(), e.getY());
                return true;
            }
        });
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(Math.log(d.getScaleFactor()) / Math.log(2), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    /** Shows a track; fit = move the camera to show all of it (false keeps the user's view, for live updates). */
    public void setTrack(List<TrackPoint> track, boolean fit) {
        int n = track.size();
        points = track;
        xs = new double[n];
        ys = new double[n];
        segmentQuality = new byte[n];
        int[] perQuality = new int[3];
        for (int i = 0; i < n; i++) {
            TrackPoint p = track.get(i);
            xs[i] = Mercator.x(p.lon);
            ys[i] = Mercator.y(p.lat);
            if (i > 0) { // a segment is as bad as its worse end
                segmentQuality[i] = (byte) Math.max(track.get(i - 1).quality(), p.quality());
                perQuality[segmentQuality[i]]++;
            }
        }
        for (int q = 0; q < 3; q++) segments[q] = new float[perQuality[q] * 4];
        if (selected >= n) selected = -1;
        if (fit) {
            needsFit = true;
            if (getWidth() > 0) fit();
        }
        invalidate();
    }

    public void setOnSelect(Consumer<TrackPoint> listener) {
        onSelect = listener;
    }

    /** Stops tile downloads; call from Activity.onDestroy. */
    public void release() {
        tiles.shutdown();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility") // map gestures have no click equivalent
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (needsFit) fit();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFFE5E3DF);
        int w = getWidth(), h = getHeight();
        int z = (int) Math.round(zoom);
        int n = 1 << z;
        double size = tilePx * Math.pow(2, zoom - z); // on-screen size of one tile
        double world = size * n;
        double left = cx * world - w / 2.0, top = cy * world - h / 2.0;

        int x0 = (int) Math.floor(left / size), x1 = (int) Math.floor((left + w) / size);
        int y0 = Math.max(0, (int) Math.floor(top / size)), y1 = Math.min(n - 1, (int) Math.floor((top + h) / size));
        tiles.setVisible(z, x0, x1, y0, y1);
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                dst.set((float) (tx * size - left), (float) (ty * size - top),
                        (float) ((tx + 1) * size - left), (float) ((ty + 1) * size - top));
                int x = Math.floorMod(tx, n);
                Bitmap b = tiles.get(z, x, ty);
                if (b != null) {
                    canvas.drawBitmap(b, null, dst, bitmapPaint);
                } else if (z > 0 && (b = tiles.peek(z - 1, x / 2, ty / 2)) != null) {
                    int half = TileCache.TILE_PX / 2; // upscale the parent tile while this one loads
                    src.set((x & 1) * half, (ty & 1) * half, (x & 1) * half + half, (ty & 1) * half + half);
                    canvas.drawBitmap(b, src, dst, bitmapPaint);
                }
            }
        }
        drawTrack(canvas, world, left, top);
        canvas.drawText("© OpenStreetMap", w - 6 * density, h - 6 * density, textPaint);
    }

    private void drawTrack(Canvas canvas, double world, double left, double top) {
        int n = xs.length;
        if (n == 0) return;
        segmentCount[0] = segmentCount[1] = segmentCount[2] = 0;
        for (int i = 1; i < n; i++) {
            int q = segmentQuality[i];
            float[] s = segments[q];
            int k = segmentCount[q];
            s[k] = (float) (xs[i - 1] * world - left);
            s[k + 1] = (float) (ys[i - 1] * world - top);
            s[k + 2] = (float) (xs[i] * world - left);
            s[k + 3] = (float) (ys[i] * world - top);
            segmentCount[q] = k + 4;
        }
        for (int q = 2; q >= 0; q--) canvas.drawLines(segments[q], 0, segmentCount[q], linePaints[q]);
        dot(canvas, 0, Color.rgb(67, 160, 71), world, left, top);
        dot(canvas, n - 1, Color.BLACK, world, left, top);
        if (selected >= 0) dot(canvas, selected, Color.MAGENTA, world, left, top);
    }

    private void dot(Canvas canvas, int i, int color, double world, double left, double top) {
        float x = (float) (xs[i] * world - left), y = (float) (ys[i] * world - top);
        dotPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 8 * density, dotPaint);
        dotPaint.setColor(color);
        canvas.drawCircle(x, y, 6 * density, dotPaint);
    }

    private double worldPx() {
        return tilePx * Math.pow(2, zoom);
    }

    private void zoomAround(double dz, float fx, float fy) {
        double before = worldPx();
        double ox = (fx - getWidth() / 2.0), oy = (fy - getHeight() / 2.0);
        double px = cx + ox / before, py = cy + oy / before; // world point under the fingers
        zoom = Mercator.clamp(zoom + dz, Mercator.MIN_ZOOM, Mercator.MAX_ZOOM);
        double after = worldPx();
        cx = Mercator.clamp(px - ox / after, 0, 1);
        cy = Mercator.clamp(py - oy / after, 0, 1);
        invalidate();
    }

    private void fit() {
        needsFit = false;
        if (xs.length == 0) return;
        double minX = 1, maxX = 0, minY = 1, maxY = 0;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        zoom = Mercator.fitZoom((maxX - minX) * 1.2, (maxY - minY) * 1.2, getWidth(), getHeight(), tilePx);
    }

    private void select(float sx, float sy) {
        double world = worldPx();
        double left = cx * world - getWidth() / 2.0, top = cy * world - getHeight() / 2.0;
        double best = Math.pow(24 * density, 2);
        selected = -1;
        for (int i = 0; i < xs.length; i++) {
            double dx = xs[i] * world - left - sx, dy = ys[i] * world - top - sy;
            double d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                selected = i;
            }
        }
        onSelect.accept(selected >= 0 ? points.get(selected) : null);
        invalidate();
    }
}
