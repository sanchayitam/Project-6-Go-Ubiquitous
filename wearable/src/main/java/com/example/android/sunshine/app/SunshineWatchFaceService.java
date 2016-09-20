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

package com.example.android.sunshine.app;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

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
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "SunshineFaceService";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);


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

    private class Engine extends CanvasWatchFaceService.Engine  implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);

       /*
                 Weather information from the mobile app
        */
        private static final String WEATHER_PATH = "/weather";
        private static final String HIGH_TEMPERATURE = "high_temperature";
        private static final String LOW_TEMPERATURE = "low_temperature";
        private static final String WEATHER_CONDITION = "weather_condition";

        GoogleApiClient mGoogleApiClient;
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint timeTextPaint;
        Paint hourTextPaint;
        Paint minuteTextPaint;
        Paint dateTextPaint;
        Paint linePaint;
        Paint maxTempTextPaint;
        Paint minTempTextPaint;

        boolean mAmbient;

        Calendar mCalendar;

        float timeYOffset;
        float dateYOffset;
        float weatherYOffset;
        float lineYOffset;

        Bitmap weatherIcon;
        int weatherId;
        String maxTemp;
        String minTemp;

        Resources resources;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        int mDigitalTextColor = -1;
        int mDigitalTextLightColor = -1;

        private static final int SPACE_BETWEEN_TEMPERATURES = 10;

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };


        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {


                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().equals(
                            WEATHER_PATH)) {
                        DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                        DataMap config = dataMapItem.getDataMap();
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Config DataItem updated:" + config);
                        }
                        processConfigurationFor(config);
                        invalidate();
                    }
                }
            }
        }

        private void processConfigurationFor(final DataMap config) {


                this.maxTemp = config.getString(HIGH_TEMPERATURE);
                this.minTemp = config.getString(LOW_TEMPERATURE);
                this.weatherId = config.getInt(WEATHER_CONDITION);
                if (weatherId != 0) {
                    this.weatherIcon = BitmapFactory.decodeResource(resources, SunshineWatchFaceUtil.getIconResourceForWeatherCondition(weatherId));
                }
                if (this.weatherIcon == null) {
                    this.weatherIcon = BitmapFactory.decodeResource(getResources(),
                            R.mipmap.ic_launcher);
                }

        }



        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

             mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            resources = SunshineWatchFaceService.this.getResources();
            timeYOffset = resources.getDimension(R.dimen.time_y_offset);
            dateYOffset = resources.getDimension(R.dimen.date_y_offset);
            weatherYOffset = resources.getDimension(R.dimen.weather_y_offset);
            lineYOffset = resources.getDimension(R.dimen.line_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(SunshineWatchFaceService.this,R.color.digital_background));

            mDigitalTextColor = ContextCompat.getColor(SunshineWatchFaceService.this, R.color.digital_text);
            mDigitalTextLightColor = ContextCompat.getColor(SunshineWatchFaceService.this, R.color.digital_text_light);

            timeTextPaint = createTextPaint(mDigitalTextColor, NORMAL_TYPEFACE);
            hourTextPaint = createTextPaint(mDigitalTextColor, BOLD_TYPEFACE);
            minuteTextPaint = createTextPaint(mDigitalTextLightColor, NORMAL_TYPEFACE);

            dateTextPaint = createTextPaint(mDigitalTextLightColor, NORMAL_TYPEFACE);

            linePaint = new Paint();
            linePaint.setColor(mDigitalTextLightColor);

            maxTempTextPaint = createTextPaint(mDigitalTextColor, BOLD_TYPEFACE);
            minTempTextPaint = createTextPaint(mDigitalTextLightColor, NORMAL_TYPEFACE);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            mCalendar = Calendar.getInstance();

          //  weatherIcon = BitmapFactory.decodeResource(resources, SunshineWatchFaceUtil.getIconResourceForWeatherCondition(800));
         // Time mTime = new Time();
            //mTime = new GregorianCalendar(TimeZone.getDefault(),Locale.getDefault());

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor ,  Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
               // mTime.clear(TimeZone.getDefault().getID());
                //mTime.setToNow();
               mCalendar.setTimeZone(TimeZone.getDefault());
            } else {

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
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
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.

            float timeTextSize = resources.getDimension(R.dimen.time_text_size);
            timeTextPaint.setTextSize(timeTextSize);
            hourTextPaint.setTextSize(timeTextSize);
            minuteTextPaint.setTextSize(timeTextSize);

            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            dateTextPaint.setTextSize(dateTextSize);

            float tempTextSize = resources.getDimension(R.dimen.temp_text_size);
            maxTempTextPaint.setTextSize(tempTextSize);
            minTempTextPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            hourTextPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);


            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
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
                    timeTextPaint.setAntiAlias(!inAmbientMode);
                    hourTextPaint.setAntiAlias(!inAmbientMode);
                    minuteTextPaint.setAntiAlias(!inAmbientMode);
                    dateTextPaint.setAntiAlias(!inAmbientMode);
                    maxTempTextPaint.setAntiAlias(!inAmbientMode);
                    minTempTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + connectionResult);
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

         //   Date date = new Date();
        //    date.setTime(System.currentTimeMillis());
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            // Draw the background.
            if(isInAmbientMode()){
                canvas.drawColor(Color.BLACK);
            }
            else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }


            int width = bounds.width();
            float centerX = width / 2f;

            // Draw H:MM in ambient mode

            int seconds = mCalendar.get(Calendar.SECOND);
            int minutes = mCalendar.get(Calendar.MINUTE);
            int hours = mCalendar.get(Calendar.HOUR_OF_DAY);

         /*   String time = mAmbient
                    ? String.format("%d:%02d", hours, minutes)
                    : String.format("%d:%02d:%02d", hours, minutes, seconds);
           */

            if(mAmbient) {
                //Draw time text in the x-center of the screen
                String time = String.format("%02d:%02d", hours, minutes);
                float timeXOffset = bounds.centerX() - timeTextPaint.measureText(time) / 2;
                canvas.drawText(time, timeXOffset, timeYOffset, timeTextPaint);
           }
            else {

                // Draw the hours.

            /*    String hourString;
                if (is24Hour) {
                    hourString = String.format("%02d:", mCalendar.get(Calendar.HOUR_OF_DAY));
                } else {

                    if (hours == 0) {
                        hours = 12;
                    }
                    hourString = String.format("%02d:", hours);
                }
  */
                String hourString = String.format("%02d", hours);
                String colon = ":";
                canvas.drawText(hourString, centerX - hourTextPaint.measureText(hourString), timeYOffset, hourTextPaint);
                canvas.drawText(colon,centerX,timeYOffset,hourTextPaint);

                // Draw the minutes.
                String minuteString = String.format("%02d", minutes);
                canvas.drawText(minuteString, centerX + minuteTextPaint.measureText(colon), timeYOffset, minuteTextPaint);
            }

            //Draw date text in the x-center of the screen
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.US);
            String dateStr = dateFormat.format(mCalendar.getTime()).toUpperCase(Locale.US);
            float dateXOffset = bounds.centerX() - dateTextPaint.measureText(dateStr)/2;
            canvas.drawText(dateStr, dateXOffset, dateYOffset, dateTextPaint);
            canvas.drawLine(bounds.centerX() - (4 * SPACE_BETWEEN_TEMPERATURES), lineYOffset, bounds.centerX() + (4 * SPACE_BETWEEN_TEMPERATURES), lineYOffset, linePaint);

            if(weatherIcon != null && maxTemp != null && minTemp != null) {

                //Draw high and low temp, icon for weather
                Rect imgRect = new Rect();
                maxTempTextPaint.getTextBounds(maxTemp, 0, maxTemp.length(), imgRect);
                float maxTempXOffset;

                Log.i(TAG,"Value of mAmbient is :"+ mAmbient);
                if (mAmbient) {
                    maxTempXOffset = bounds.centerX() - ((maxTempTextPaint.measureText(maxTemp) + minTempTextPaint.measureText(minTemp) + SPACE_BETWEEN_TEMPERATURES) / 2);
                } else {
                    maxTempXOffset = bounds.centerX() - (maxTempTextPaint.measureText(maxTemp) / 2);

                    canvas.drawBitmap(weatherIcon, maxTempXOffset - weatherIcon.getWidth() - 2 * SPACE_BETWEEN_TEMPERATURES,
                            weatherYOffset - (imgRect.height() / 2) - (weatherIcon.getHeight() / 2), null);
                }

                float minTempXOffset = maxTempXOffset + maxTempTextPaint.measureText(maxTemp) + SPACE_BETWEEN_TEMPERATURES;

                canvas.drawText(maxTemp, maxTempXOffset, weatherYOffset, maxTempTextPaint);
                canvas.drawText(minTemp, minTempXOffset, weatherYOffset, minTempTextPaint);
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

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


}
