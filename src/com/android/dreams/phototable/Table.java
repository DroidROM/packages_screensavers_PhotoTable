/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.dreams.phototable;

import android.service.dreams.Dream;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;

import java.util.LinkedList;
import java.util.Random;

/**
 * A surface where photos sit.
 */
public class Table extends FrameLayout {
    private static final String TAG = "PhotoTable.Table";
    private static final boolean DEBUG = false;

    class Launcher implements Runnable {
        private final Table mTable;
        public Launcher(Table table) {
            mTable = table;
        }

        @Override
        public void run() {
            mTable.scheduleNext(mDropPeriod);
            mTable.launch();
        }
    }

    private static final long MAX_SELECTION_TIME = 10000L;
    private static Random sRNG = new Random();

    private final Launcher mLauncher;
    private final LinkedList<View> mOnTable;
    private final Dream mDream;
    private final int mDropPeriod;
    private final int mFastDropPeriod;
    private final int mNowDropDelay;
    private final float mImageRatio;
    private final float mTableRatio;
    private final float mImageRotationLimit;
    private final boolean mTapToExit;
    private final int mTableCapacity;
    private final int mInset;
    private final PhotoSourcePlexor mPhotoSource;
    private final Resources mResources;
    private PhotoLaunchTask mPhotoLaunchTask;
    private boolean mStarted;
    private boolean mIsLandscape;
    private BitmapFactory.Options mOptions;
    private int mLongSide;
    private int mShortSide;
    private int mWidth;
    private int mHeight;
    private View mSelected;
    private long mSelectedTime;

    public Table(Dream dream, AttributeSet as) {
        super(dream, as);
        mDream = dream;
        mResources = getResources();
        setBackground(mResources.getDrawable(R.drawable.table));
        mInset = mResources.getDimensionPixelSize(R.dimen.photo_inset);
        mDropPeriod = mResources.getInteger(R.integer.drop_period);
        mFastDropPeriod = mResources.getInteger(R.integer.fast_drop);
        mNowDropDelay = mResources.getInteger(R.integer.now_drop);
        mImageRatio = mResources.getInteger(R.integer.image_ratio) / 1000000f;
        mTableRatio = mResources.getInteger(R.integer.table_ratio) / 1000000f;
        mImageRotationLimit = (float) mResources.getInteger(R.integer.max_image_rotation);
        mTableCapacity = mResources.getInteger(R.integer.table_capacity);
        mTapToExit = mResources.getBoolean(R.bool.enable_tap_to_exit);
        mOnTable = new LinkedList<View>();
        mOptions = new BitmapFactory.Options();
        mOptions.inTempStorage = new byte[32768];
        mPhotoSource = new PhotoSourcePlexor(getContext());
        mLauncher = new Launcher(this);
        mStarted = false;
    }

    public boolean hasSelection() {
        return mSelected != null;
    }

    public View getSelected() {
        return mSelected;
    }

    public void clearSelection() {
        mSelected = null;
    }

    public void setSelection(View selected) {
        assert(selected != null);
        if (mSelected != null) {
            dropOnTable(mSelected);
        }
        mSelected = selected;
        mSelectedTime = System.currentTimeMillis();
        bringChildToFront(selected);
        pickUp(selected);
    }

    static float lerp(float a, float b, float f) {
        return (b-a)*f + a;
    }

    static float randfrange(float a, float b) {
        return lerp(a, b, sRNG.nextFloat());
    }

    static PointF randFromCurve(float t, PointF[] v) {
        PointF p = new PointF();
        if (v.length == 4 && t >= 0f && t <= 1f) {
            float a = (float) Math.pow(1f-t, 3f);
            float b = (float) Math.pow(1f-t, 2f) * t;
            float c = (1f-t) * (float) Math.pow(t, 2f);
            float d = (float) Math.pow(t, 3f);

            p.x = a * v[0].x + 3 * b * v[1].x + 3 * c * v[2].x + d * v[3].x;
            p.y = a * v[0].y + 3 * b * v[1].y + 3 * c * v[2].y + d * v[3].y;
        }
        return p;
    }

    private static PointF randInCenter(float i, float j, int width, int height) {
        log("randInCenter (" + i + ", " + j + ", " + width + ", " + height + ")");
        PointF p = new PointF();
        p.x = 0.5f * width + 0.15f * width * i;
        p.y = 0.5f * height + 0.15f * height * j;
        log("randInCenter returning " + p.x + "," + p.y);
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (hasSelection()) {
                dropOnTable(getSelected());
                clearSelection();
            } else  {
                if (mTapToExit) {
                    mDream.finish();
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        log("onLayout (" + left + ", " + top + ", " + right + ", " + bottom + ")");

        mHeight = bottom - top;
        mWidth = right - left;

        mLongSide = (int) (mImageRatio * Math.max(mWidth, mHeight));
        mShortSide = (int) (mImageRatio * Math.min(mWidth, mHeight));

        boolean isLandscape = mWidth > mHeight;
        if (mIsLandscape != isLandscape) {
            for (View photo: mOnTable) {
                if (photo == getSelected()) {
                    pickUp(photo);
                } else {
                    dropOnTable(photo);
                }
            }
            mIsLandscape = isLandscape;
        }
        start();
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    private class PhotoLaunchTask extends AsyncTask<Void, Void, View> {
        private int mTries;
        public PhotoLaunchTask() {
            mTries = 0;
        }

        @Override
        public View doInBackground(Void... unused) {
            log("load a new photo");
            LayoutInflater inflater = (LayoutInflater) Table.this.getContext()
                   .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View photo = inflater.inflate(R.layout.photo, null);
            ImageView image = (ImageView) photo;
            Drawable[] layers = new Drawable[2];
            Bitmap decodedPhoto = Table.this.mPhotoSource.next(Table.this.mOptions,
                    Table.this.mLongSide, Table.this.mShortSide);
            int photoWidth = Table.this.mOptions.outWidth;
            int photoHeight = Table.this.mOptions.outHeight;
            if (Table.this.mOptions.outWidth <= 0 || Table.this.mOptions.outHeight <= 0) {
                photo = null;
            } else {
                layers[0] = new BitmapDrawable(Table.this.mResources, decodedPhoto);
                layers[1] = Table.this.mResources.getDrawable(R.drawable.frame);
                LayerDrawable layerList = new LayerDrawable(layers);
                layerList.setLayerInset(0, Table.this.mInset, Table.this.mInset,
                                        Table.this.mInset, Table.this.mInset);
                image.setImageDrawable(layerList);

                photo.setTag(R.id.photo_width, new Integer(photoWidth));
                photo.setTag(R.id.photo_height, new Integer(photoHeight));

                photo.setOnTouchListener(new PhotoTouchListener(Table.this.getContext(),
                                                                Table.this));
            }

            return photo;
        }

        @Override
        public void onPostExecute(View photo) {
            if (photo != null) {
                Table.this.addView(photo, new LayoutParams(LayoutParams.WRAP_CONTENT,
                                                       LayoutParams.WRAP_CONTENT));
                if (Table.this.hasSelection()) {
                    Table.this.bringChildToFront(Table.this.getSelected());
                }
                int width = ((Integer) photo.getTag(R.id.photo_width)).intValue();
                int height = ((Integer) photo.getTag(R.id.photo_height)).intValue();

                log("drop it");
                Table.this.throwOnTable(photo);

                if(Table.this.mOnTable.size() < Table.this.mTableCapacity) {
                    Table.this.scheduleNext(Table.this.mFastDropPeriod);
                }
            } else if (mTries < 3) {
                mTries++;
                this.execute();
            }
        }
    };

    public void launch() {
        log("launching");
        setSystemUiVisibility(View.STATUS_BAR_HIDDEN);
        if (hasSelection() &&
                (System.currentTimeMillis() - mSelectedTime) > MAX_SELECTION_TIME) {
            dropOnTable(getSelected());
            clearSelection();
        } else {
            log("inflate it");
            if (mPhotoLaunchTask == null ||
                mPhotoLaunchTask.getStatus() == AsyncTask.Status.FINISHED) {
                mPhotoLaunchTask = new PhotoLaunchTask();
                mPhotoLaunchTask.execute();
            }
        }
    }
    public void fadeAway(final View photo, final boolean replace) {
        // fade out of view
        mOnTable.remove(photo);
        photo.animate().cancel();
        photo.animate()
                .withLayer()
                .alpha(0f)
                .setDuration(1000)
                .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            removeView(photo);
                            recycle(photo);
                            if (replace) {
                                scheduleNext(mNowDropDelay);
                            }
                        }
                    });
    }

    public void moveToBackOfQueue(View photo) {
        // make this photo the last to be removed.
        bringChildToFront(photo);
        invalidate();
        mOnTable.remove(photo);
        mOnTable.offer(photo);
    }

    private void throwOnTable(final View photo) {
        mOnTable.offer(photo);
        log("start offscreen");
        int width = ((Integer) photo.getTag(R.id.photo_width));
        int height = ((Integer) photo.getTag(R.id.photo_height));
        photo.setRotation(-100.0f);
        photo.setX(-mLongSide);
        photo.setY(-mLongSide);
        dropOnTable(photo);
    }

    public void dropOnTable(final View photo) {
        float angle = randfrange(-mImageRotationLimit, mImageRotationLimit);
        PointF p = randInCenter((float) sRNG.nextGaussian(), (float) sRNG.nextGaussian(),
                                mWidth, mHeight);
        float x = p.x;
        float y = p.y;

        log("drop it at " + x + ", " + y);

        float x0 = photo.getX();
        float y0 = photo.getY();
        float width = (float) ((Integer) photo.getTag(R.id.photo_width)).intValue();
        float height = (float) ((Integer) photo.getTag(R.id.photo_height)).intValue();

        x -= mTableRatio * mLongSide / 2f;
        y -= mTableRatio * mLongSide / 2f;
        log("fixed offset is " + x + ", " + y);

        float dx = x - x0;
        float dy = y - y0;

        float dist = (float) (Math.sqrt(dx * dx + dy * dy));
        int duration = (int) (1000f * dist / 400f);
        duration = Math.max(duration, 1000);

        log("animate it");
        // toss onto table
        photo.animate()
                .withLayer()
                .scaleX(mTableRatio / mImageRatio)
                .scaleY(mTableRatio / mImageRatio)
                .rotation(angle)
                .x(x)
                .y(y)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(3f))
                .withEndAction(new Runnable() {
                        @Override
                            public void run() {
                            while (mOnTable.size() > mTableCapacity) {
                                fadeAway(mOnTable.poll(), false);
                            }
                        }
                    });
    }

    /** wrap all orientations to the interval [-180, 180). */
    private float wrapAngle(float angle) {
        float result = angle + 180;
        result = ((result % 360) + 360) % 360; // catch negative numbers
        result -= 180;
        return result;
    }

    private void pickUp(final View photo) {
        float photoWidth = photo.getWidth();
        float photoHeight = photo.getHeight();

        float scale = Math.min(getHeight() / photoHeight, getWidth() / photoWidth);

        log("target it");
        float x = (getWidth() - photoWidth) / 2f;
        float y = (getHeight() - photoHeight) / 2f;

        float x0 = photo.getX();
        float y0 = photo.getY();
        float dx = x - x0;
        float dy = y - y0;

        float dist = (float) (Math.sqrt(dx * dx + dy * dy));
        int duration = (int) (1000f * dist / 1000f);
        duration = Math.max(duration, 500);

        photo.setRotation(wrapAngle(photo.getRotation()));

        log("animate it");
        // toss onto table
        photo.animate()
                .withLayer()
                .rotation(0f)
                .scaleX(scale)
                .scaleY(scale)
                .x(x)
                .y(y)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(new Runnable() {
                        @Override
                            public void run() {
                            log("endtimes: " + photo.getX());
                        }
                    });
    }

    private void recycle(View photo) {
        ImageView image = (ImageView) photo;
        LayerDrawable layers = (LayerDrawable) image.getDrawable();
        BitmapDrawable bitmap = (BitmapDrawable) layers.getDrawable(0);
        bitmap.getBitmap().recycle();
    }

    public void start() {
        if (!mStarted) {
            log("kick it");
            mStarted = true;
            scheduleNext(mDropPeriod);
            launch();
        }
    }

    public void scheduleNext(int delay) {
        removeCallbacks(mLauncher);
        postDelayed(mLauncher, delay);
    }

    private static void log(String message) {
        if (DEBUG) {
            Log.i(TAG, message);
        }
    }
}