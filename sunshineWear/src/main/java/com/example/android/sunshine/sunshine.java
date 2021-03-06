/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class sunshine extends CanvasWatchFaceService {
    private static final String TAG = sunshine.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<sunshine.Engine> mWeakReference;

        public EngineHandler(sunshine.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            sunshine.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        private static final String WEATHER_PATH = "/weather-info";
        private static final String WEATHER_TEMP_HIGH_KEY = "high";
        private static final String WEATHER_TEMP_LOW_KEY = "low";
        private static final String WEATHER_ID = "weatherId";
        String weatherTempHigh;
        String weatherTempLow;
        Bitmap weatherTempIcon = null;

        private GoogleApiClient mGoogleApiClient;

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mMaxPaint;
        Paint mMinPaint;
        Paint linePaint;

        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mXOffset;
        float mYOffset;

        SimpleDateFormat format;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(sunshine.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = sunshine.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mMaxPaint = new Paint();
            mMaxPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mMinPaint = new Paint();
            mMinPaint = createTextPaint(resources.getColor(R.color.digital_text));

            linePaint = createLinePaint(resources.getColor(R.color.digital_text));

            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(mOnConnectionFailedListener)
                    .build();
            mGoogleApiClient.connect();
        }

        GoogleApiClient.ConnectionCallbacks mConnectionCallbacks = new GoogleApiClient.ConnectionCallbacks() {
            @Override
            public void onConnected(Bundle bundle) {
                Log.v(TAG, "onConnected: Successfully connected to Google API client");
                Wearable.DataApi.addListener(mGoogleApiClient, mDataListener);
            }

            @Override
            public void onConnectionSuspended(int i) {
                Log.v(TAG, "onConnectionSuspended");
            }
        };

        GoogleApiClient.OnConnectionFailedListener mOnConnectionFailedListener = new GoogleApiClient.OnConnectionFailedListener() {
            @Override
            public void onConnectionFailed(ConnectionResult connectionResult) {
                Log.e(TAG, "onConnectionFailed(): Failed to connect, with result: " + connectionResult);
            }
        };


        DataApi.DataListener mDataListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {

                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        String path = event.getDataItem().getUri().getPath();
                        if (WEATHER_PATH.equals(path)) {
                            Log.v(TAG, "Data Changed for " + WEATHER_PATH);
                            try {
                                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                                String tempData = dataMapItem.getDataMap().getString(WEATHER_TEMP_HIGH_KEY);
                                if (tempData != null){
                                    weatherTempHigh = tempData;
                                }

                                tempData = dataMapItem.getDataMap().getString(WEATHER_TEMP_LOW_KEY);
                                if (tempData != null){
                                    weatherTempLow = tempData;
                                }


                                Integer weatherId = dataMapItem.getDataMap().getInt(WEATHER_ID);
                                if (weatherId != null){
                                    Drawable b = getResources().getDrawable(loadBitmapResourceFromWeatherId(weatherId));
                                    weatherTempIcon = ((BitmapDrawable) b).getBitmap();
                                    }

                            } catch (Exception e) {
                                e.printStackTrace();

                                weatherTempHigh = null;
                                weatherTempLow  = null;
                                weatherTempIcon = null;
                            }

                        } else {
                            Log.e(TAG, "Unrecognized path:  \"" + path + "\"");
                        }

                    }
                }
            }

            /**
             * Helper method to provide the icon resource id according to the weather condition id returned
             * by the OpenWeatherMap call.
             * @param weatherId from OpenWeatherMap API response
             * @return resource id for the corresponding icon. -1 if no relation is found.
             */
            private int loadBitmapResourceFromWeatherId(int weatherId) {

                // Based on weather code data found at:
                // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
                if (weatherId >= 200 && weatherId <= 232) {
                    return R.drawable.ic_storm;
                } else if (weatherId >= 300 && weatherId <= 321) {
                    return R.drawable.ic_light_rain;
                } else if (weatherId >= 500 && weatherId <= 504) {
                    return R.drawable.ic_rain;
                } else if (weatherId == 511) {
                    return R.drawable.ic_snow;
                } else if (weatherId >= 520 && weatherId <= 531) {
                    return R.drawable.ic_rain;
                } else if (weatherId >= 600 && weatherId <= 622) {
                    return R.drawable.ic_snow;
                } else if (weatherId >= 701 && weatherId <= 761) {
                    return R.drawable.ic_fog;
                } else if (weatherId == 761 || weatherId == 781) {
                    return R.drawable.ic_storm;
                } else if (weatherId == 800) {
                    return R.drawable.ic_clear;
                } else if (weatherId == 801) {
                    return R.drawable.ic_light_clouds;
                } else if (weatherId >= 802 && weatherId <= 804) {
                    return R.drawable.ic_cloudy;
                }
                return -1;
            }

        };

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLinePaint(int color) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setDither(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, mDataListener);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            sunshine.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            sunshine.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = sunshine.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);

            mTextPaint.setTextSize(textSize);

            textSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);
            float temperatureSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mDatePaint.setTextSize(textSize);
            mMaxPaint.setTextSize(temperatureSize);
            mMinPaint.setTextSize(temperatureSize);

            format = new SimpleDateFormat("EEE, MMM dd", Locale.US);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = sunshine.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM
            mTime.setToNow();
            String time = String.format("%d:%02d", mTime.hour, mTime.minute);
            RectF boundsHour = new RectF(bounds);
            boundsHour.right = mTextPaint.measureText(time, 0, time.length());
            boundsHour.left += (bounds.width() - boundsHour.right) / 2.0f;

            canvas.drawText(time, boundsHour.left, mYOffset, mTextPaint);

            if(!isInAmbientMode()) {
                if(format != null){
                    Date actualDate = new Date(mTime.year, mTime.month, mTime.monthDay, mTime.hour, mTime.minute, mTime.second);
                    String dateString = format.format(actualDate);
                    RectF boundsHour2 = new RectF(bounds);
                    boundsHour2.right = mDatePaint.measureText(dateString, 0, dateString.length());
                    boundsHour2.left += (bounds.width() - boundsHour2.right) / 2.0f;
                    canvas.drawText(dateString , boundsHour2.left, mYOffset + ( mTextPaint.getTextSize() * 0.75f ), mDatePaint);
                }

                canvas.drawLine((bounds.width() / 2) - 30, (bounds.height() / 2) + 10, (bounds.width() / 2) + 30, (bounds.height() / 2) + 10, linePaint);



                if(weatherTempHigh != null && weatherTempLow != null){
                    float height = (bounds.height()/2.0f) + mXOffset + mMaxPaint.getTextSize() + 10;

                    canvas.drawText(weatherTempHigh, bounds.width() * 0.45f, height, mMaxPaint);
                    canvas.drawText(weatherTempLow, bounds.width() * 0.71f, height, mMinPaint);
                }
                else{
                    weatherTempHigh="-";
                    weatherTempLow="-";
                    float height = (bounds.height()/2.0f) + mXOffset + mMaxPaint.getTextSize() + 10;

                    canvas.drawText(weatherTempHigh, bounds.width() * 0.45f, height, mMaxPaint);
                    canvas.drawText(weatherTempLow, bounds.width() * 0.71f, height, mMinPaint);
                }

                if(weatherTempIcon != null){
                    Paint paint = new Paint();
                    canvas.drawBitmap(weatherTempIcon, bounds.width() / 10, (bounds.height() / 2.0f) + mXOffset, paint);
                }
                else{
                    Paint paint = new Paint();
                    Bitmap b=BitmapFactory.decodeResource(getResources(),R.mipmap.sample);
                    canvas.drawBitmap(b, bounds.width() / 10, (bounds.height() / 2.0f) + mXOffset, paint);
                }


            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

}
