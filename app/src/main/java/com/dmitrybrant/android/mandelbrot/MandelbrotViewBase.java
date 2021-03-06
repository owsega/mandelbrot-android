package com.dmitrybrant.android.mandelbrot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class MandelbrotViewBase extends View {
    public static final int DEFAULT_ITERATIONS = 128;
    public static final int MAX_ITERATIONS = 2048;
    public static final int MIN_ITERATIONS = 2;
    public static final double DEFAULT_X_CENTER = -0.5;
    public static final double DEFAULT_Y_CENTER = 0.0;
    public static final double DEFAULT_X_EXTENT = 3.0;
    public static final double DEFAULT_JULIA_X_CENTER = 0.0;
    public static final double DEFAULT_JULIA_Y_CENTER = 0.0;
    public static final double DEFAULT_JULIA_EXTENT = 3.0;
    private static final String TAG = "MandelbrotCanvas";
    private static final float CROSSHAIR_WIDTH = 16f;

    private boolean isJulia = false;
    private int paramIndex = 0;

    private List<Thread> currentThreads = new ArrayList<>();
    private volatile boolean terminateThreads = false;

    private Paint paint;
    private Bitmap theBitmap;
    private Rect theRect;
    private boolean showCrosshairs = false;

    private int screenWidth = 0;
    private int screenHeight = 0;

    private double xcenter;
    private double ycenter;
    private double xextent;
    private double xmin;
    private double xmax;
    private double ymin;
    private double ymax;
    private double jx;
    private double jy;
    private int numIterations = DEFAULT_ITERATIONS;
    private int startCoarseness = 16;
    private int endCoarseness = 1;
    private int touchStartX;
    private int touchStartY;
    private boolean zooming = false;
    private ScaleGestureDetector gesture;
    private float displayDensity;
    private OnPointSelected onPointSelected;
    private OnCoordinatesChanged onCoordinatesChanged;

    public MandelbrotViewBase(Context context) {
        super(context);
    }

    public MandelbrotViewBase(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MandelbrotViewBase(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public double getXCenter() {
        return xcenter;
    }

    public void setXCenter(double xcenter) {
        this.xcenter = xcenter;
    }

    public double getYCenter() {
        return ycenter;
    }

    public void setYCenter(double ycenter) {
        this.ycenter = ycenter;
    }

    public double getXExtent() {
        return xextent;
    }

    public void setXExtent(double xextent) {
        this.xextent = xextent;
    }

    public int getNumIterations() {
        return numIterations;
    }

    public void setNumIterations(int iter) {
        numIterations = iter;
        if (numIterations < MIN_ITERATIONS) {
            numIterations = MIN_ITERATIONS;
        }
        if (numIterations > MAX_ITERATIONS) {
            numIterations = MAX_ITERATIONS;
        }
    }

    public void setOnPointSelected(OnPointSelected listener) {
        onPointSelected = listener;
    }

    public void setOnCoordinatesChanged(OnCoordinatesChanged listener) {
        onCoordinatesChanged = listener;
    }

    protected void init(Context context, boolean isJulia) {
        if (isInEditMode()) {
            return;
        }
        this.isJulia = isJulia;
        this.paramIndex = isJulia ? 1 : 0;
        displayDensity = getResources().getDisplayMetrics().density;

        paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(1.5f * displayDensity);
        gesture = new ScaleGestureDetector(context, new ScaleListener());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isInEditMode()) {
            return;
        }
        if ((screenWidth != this.getWidth()) || (screenHeight != this.getHeight())) {
            screenWidth = this.getWidth();
            screenHeight = this.getHeight();
            if ((screenWidth == 0) || (screenHeight == 0)) {
                return;
            }

            terminateThreads();
            initMinMax();

            theBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
            mandelnative.SetBitmap(paramIndex, theBitmap);
            theRect = new Rect(0, 0, theBitmap.getWidth() - 1, theBitmap.getHeight() - 1);

            render();
            return;
        }
        if ((screenWidth == 0) || (screenHeight == 0)) {
            return;
        }

        mandelnative.UpdateBitmap(paramIndex, theBitmap);
        canvas.drawBitmap(theBitmap, theRect, theRect, paint);

        if (showCrosshairs) {
            canvas.drawLine(screenWidth / 2 - CROSSHAIR_WIDTH * displayDensity, screenHeight / 2, screenWidth / 2 + CROSSHAIR_WIDTH * displayDensity, screenHeight / 2, paint);
            canvas.drawLine(screenWidth / 2, screenHeight / 2 - CROSSHAIR_WIDTH * displayDensity, screenWidth / 2, screenHeight / 2 + CROSSHAIR_WIDTH * displayDensity, paint);
        }
    }

    public void terminateThreads() {
        try {
            mandelnative.SignalTerminate(paramIndex);
            terminateThreads = true;

            for (Thread t : currentThreads) {
                if (t.isAlive()) {
                    t.join(DateUtils.SECOND_IN_MILLIS);
                }
                if (t.isAlive()) {
                    Log.e(TAG, "Thread is still alive after 1sec...");
                }
            }

            terminateThreads = false;
            currentThreads.clear();
        } catch (Exception ex) {
            Log.w(TAG, "Exception while terminating threads: " + ex.getMessage());
        }
    }

    void initMinMax() {
        double ratio = (double) screenHeight / (double) (screenWidth == 0 ? 1 : screenWidth);
        xmin = xcenter - xextent / 2.0;
        xmax = xcenter + xextent / 2.0;
        ymin = ycenter - ratio * xextent / 2.0;
        ymax = ycenter + ratio * xextent / 2.0;
    }

    public void reset() {
        if (isJulia) {
            xcenter = DEFAULT_JULIA_X_CENTER;
            ycenter = DEFAULT_JULIA_Y_CENTER;
            xextent = DEFAULT_JULIA_EXTENT;
        } else {
            xcenter = DEFAULT_X_CENTER;
            ycenter = DEFAULT_Y_CENTER;
            xextent = DEFAULT_X_EXTENT;
        }
        numIterations = DEFAULT_ITERATIONS;
        initMinMax();
        render();
    }

    public void setColorScheme(int[] colors) {
        mandelnative.SetColorPalette(paramIndex, colors, colors.length);
    }

    public void setJuliaCoords(double jx, double jy) {
        this.jx = jx;
        this.jy = jy;
    }

    public void setCrosshairsEnabled(boolean enabled) {
        showCrosshairs = enabled;
    }

    public void savePicture(String fileName) {
        new SaveImageTask().execute(fileName);
    }

    public void requestCoordinates() {
        if (onCoordinatesChanged != null) {
            onCoordinatesChanged.newCoordinates(xmin, xmax, ymin, ymax);
        }
    }

    public void render() {
        terminateThreads();
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        if (onCoordinatesChanged != null) {
            onCoordinatesChanged.newCoordinates(xmin, xmax, ymin, ymax);
        }

        xextent = xmax - xmin;
        xcenter = xmin + xextent / 2.0;
        ycenter = ymin + (ymax - ymin) / 2.0;

        mandelnative.SetParameters(paramIndex, numIterations, xmin, xmax, ymin, ymax,
                isJulia ? 1 : 0, jx, jy, screenWidth, screenHeight);
        Thread t;

        t = new MandelThread(0, 0, screenWidth, screenHeight / 2, startCoarseness);
        t.start();
        currentThreads.add(t);
        t = new MandelThread(0, screenHeight / 2, screenWidth, screenHeight / 2, startCoarseness);
        t.start();
        currentThreads.add(t);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        gesture.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartX = (int) event.getX();
            touchStartY = (int) event.getY();
            zooming = false;
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            zooming = false;
            endCoarseness = 1;
            if (onPointSelected != null) {
                onPointSelected.pointSelected(xmin + ((double) event.getX() * (xmax - xmin) / screenWidth),
                        ymin + ((double) event.getY() * (ymax - ymin) / screenHeight));
            }
            render();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (onPointSelected != null) {
                onPointSelected.pointSelected(xmin + ((double) event.getX() * (xmax - xmin) / screenWidth),
                        ymin + ((double) event.getY() * (ymax - ymin) / screenHeight));
            }
            if (!zooming) {
                endCoarseness = startCoarseness;

                int dx = (int) event.getX() - touchStartX;
                int dy = (int) event.getY() - touchStartY;
                if ((dx != 0) || (dy != 0)) {
                    double amountX = ((double) dx / (double) screenWidth) * (xmax - xmin);
                    double amountY = ((double) dy / (double) screenHeight) * (ymax - ymin);
                    xmin -= amountX;
                    xmax -= amountX;
                    ymin -= amountY;
                    ymax -= amountY;
                    render();
                }
                touchStartX = (int) event.getX();
                touchStartY = (int) event.getY();
            }
            return true;
        }
        return false;
    }

    public interface OnPointSelected {
        void pointSelected(double x, double y);
    }

    public interface OnCoordinatesChanged {
        void newCoordinates(double xmin, double xmax, double ymin, double ymax);
    }

    private class MandelThread extends Thread {
        private int startX, startY;
        private int startWidth, startHeight;
        private int level;

        public MandelThread(int x, int y, int width, int height, int level) {
            startX = x;
            startY = y;
            startWidth = width;
            startHeight = height;
            this.level = level;
        }

        @Override
        public void run() {
            int curLevel = level;
            while (true) {
                mandelnative.DrawFractal(paramIndex, startX, startY, startWidth, startHeight, curLevel, curLevel == level ? 1 : 0);
                MandelbrotViewBase.this.postInvalidate();
                if (terminateThreads) {
                    break;
                }
                if (curLevel <= endCoarseness) {
                    break;
                }
                curLevel /= 2;
            }
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            double s = detector.getScaleFactor();
            if (s != 1.0 && s > 0.0) {
                zooming = true;
                endCoarseness = startCoarseness;

                double xoff = xmin + ((double) detector.getFocusX() * (xmax - xmin) / screenWidth);
                double yoff = ymin + ((double) detector.getFocusY() * (ymax - ymin) / screenHeight);

                xmax = xoff + ((xmax - xoff) / s);
                xmin = xoff + ((xmin - xoff) / s);
                ymax = yoff + ((ymax - yoff) / s);
                ymin = yoff + ((ymin - yoff) / s);

                render();
            }
            return true;
        }
    }

    private class SaveImageTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... fileName) {
            try {
                FileOutputStream fs = new FileOutputStream(fileName[0]);
                theBitmap.compress(Bitmap.CompressFormat.PNG, 100, fs);
                fs.flush();
                fs.close();
                return "Picture saved as: " + fileName[0];
            } catch (IOException e) {
                e.printStackTrace();
                return "Error saving file: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String message) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
