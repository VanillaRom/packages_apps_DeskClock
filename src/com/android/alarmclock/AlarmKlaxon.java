/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.alarmclock;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;

/**
 * Manages alarms and vibe.  Singleton, so it can be initiated in
 * AlarmReceiver and shut down in the AlarmAlert activity
 */
class AlarmKlaxon implements Alarms.AlarmSettings {

    interface KillerCallback {
        public void onKilled();
    }

    /** Play alarm up to 10 minutes before silencing */
    final static int ALARM_TIMEOUT_SECONDS = 10 * 60;

    private static long[] sVibratePattern = new long[] { 500, 500 };

    private static AlarmKlaxon sInstance;

    private int mAlarmId;
    private String mAlert;
    private Alarms.DaysOfWeek mDaysOfWeek;
    private boolean mVibrate;

    private boolean mPlaying = false;

    private Vibrator mVibrator;
    private MediaPlayer mMediaPlayer;

    private Handler mTimeout;
    private KillerCallback mKillerCallback;


    static synchronized AlarmKlaxon getInstance() {
        if (sInstance == null) sInstance = new AlarmKlaxon();
        return sInstance;
    }

    private AlarmKlaxon() {
        mVibrator = new Vibrator();
    }

    public void reportAlarm(
            int idx, boolean enabled, int hour, int minutes,
            Alarms.DaysOfWeek daysOfWeek, boolean vibrate, String message,
            String alert) {
        if (Log.LOGV) Log.v("AlarmKlaxon.reportAlarm: " + idx + " " + hour +
                            " " + minutes + " dow " + daysOfWeek);
        mAlert = alert;
        mDaysOfWeek = daysOfWeek;
        mVibrate = vibrate;
    }

    synchronized void play(Context context, int alarmId) {
        ContentResolver contentResolver = context.getContentResolver();

        if (mPlaying) stop(context, false);

        mAlarmId = alarmId;

        /* this will call reportAlarm() callback */
        Alarms.getAlarm(contentResolver, this, mAlarmId);

        if (Log.LOGV) Log.v("AlarmKlaxon.play() " + mAlarmId + " alert " + mAlert);

        /* play audio alert */
        if (mAlert == null) {
            Log.e("Unable to play alarm: no audio file available");
        } else {
            /* we need a new MediaPlayer when we change media URLs */
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setOnErrorListener(new OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e("Error occurred while playing audio.");
                    mp.stop();
                    mp.release();
                    mMediaPlayer = null;
                    return true;
                }
            });

            try {
                mMediaPlayer.setDataSource(context, Uri.parse(mAlert));
            } catch (Exception ex) {
                Log.v("Using the fallback ringtone");
                /* The alert may be on the sd card which could be busy right
                 * now. Use the fallback ringtone. */
                AssetFileDescriptor afd =
                        context.getResources().openRawResourceFd(
                                com.android.internal.R.raw.fallbackring);
                if (afd != null) {
                    try {
                        mMediaPlayer.setDataSource(afd.getFileDescriptor(),
                                afd.getStartOffset(), afd.getLength());
                        afd.close();
                    } catch (Exception ex2) {
                        Log.e("Failed to play fallback ringtone", ex2);
                        /* At this point we just don't play anything */
                    }
                }
            }
            /* Now try to play the alert. */
            try {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                mMediaPlayer.setLooping(true);
                mMediaPlayer.prepare();
                mMediaPlayer.start();
            } catch (Exception ex) {
                Log.e("Error playing alarm: " + mAlert, ex);
            }
        }

        /* Start the vibrator after everything is ok with the media player */
        if (mVibrate) {
            mVibrator.vibrate(sVibratePattern, 0);
        } else {
            mVibrator.cancel();
        }

        enableKiller();
        mPlaying = true;
    }


    /**
     * Stops alarm audio and disables alarm if it not snoozed and not
     * repeating
     */
    synchronized void stop(Context context, boolean snoozed) {
        if (Log.LOGV) Log.v("AlarmKlaxon.stop() " + mAlarmId);
        if (mPlaying) {
            mPlaying = false;

            // Stop audio playing
            if (mMediaPlayer != null) {
                mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
            }

            // Stop vibrator
            mVibrator.cancel();

            /* disable alarm only if it is not set to repeat */
            if (!snoozed && ((mDaysOfWeek == null || !mDaysOfWeek.isRepeatSet()))) {
                Alarms.enableAlarm(context, mAlarmId, false);
            }
        }
        disableKiller();
    }

    /**
     * This callback called when alarm killer times out unattended
     * alarm
     */
    void setKillerCallback(KillerCallback killerCallback) {
        mKillerCallback = killerCallback;
    }

    /**
     * Kills alarm audio after ALARM_TIMEOUT_SECONDS, so the alarm
     * won't run all day.
     *
     * This just cancels the audio, but leaves the notification
     * popped, so the user will know that the alarm tripped.
     */
    private void enableKiller() {
        mTimeout = new Handler();
        mTimeout.postDelayed(new Runnable() {
                public void run() {
                    if (Log.LOGV) Log.v("*********** Alarm killer triggered *************");
                    if (mKillerCallback != null) mKillerCallback.onKilled();
                }
            }, 1000 * ALARM_TIMEOUT_SECONDS);
    }

    private void disableKiller() {
        if (mTimeout != null) {
            mTimeout.removeCallbacksAndMessages(null);
            mTimeout = null;
        }
    }


}