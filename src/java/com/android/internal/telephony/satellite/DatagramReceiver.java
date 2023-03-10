/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.telephony.satellite;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Telephony;
import android.telephony.Rlog;
import android.telephony.satellite.ISatelliteDatagramCallback;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.telephony.ILongConsumer;
import com.android.internal.telephony.Phone;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Datagram receiver used to receive satellite datagrams and then,
 * deliver received datagrams to messaging apps.
 */
public class DatagramReceiver {
    private static final String TAG = "DatagramReceiver";
    /** File used to store shared preferences related to satellite. */
     private static final String SATELLITE_SHARED_PREF = "satellite_shared_pref";
    /** Key used to read/write satellite datagramId in shared preferences. */
    private static final String SATELLITE_DATAGRAM_ID_KEY = "satellite_datagram_id_key";
    private static AtomicLong mNextDatagramId = new AtomicLong(0);

    @NonNull private static DatagramReceiver sInstance;
    @NonNull private final Context mContext;
    @NonNull private final ContentResolver mContentResolver;
    @NonNull private SharedPreferences mSharedPreferences = null;

    /**
     * The background handler to perform database operations. This is running on a separate thread.
     */
    @NonNull private final Handler mBackgroundHandler;

    /**
     * Map key: subId, value: SatelliteDatagramListenerHandler to notify registrants.
     */
    private final ConcurrentHashMap<Integer, SatelliteDatagramListenerHandler>
            mSatelliteDatagramListenerHandlers = new ConcurrentHashMap<>();

    /**
     * Create the DatagramReceiver singleton instance.
     * @param context The Context to use to create the DatagramReceiver.
     * @param looper The looper for the handler.
     * @return The singleton instance of DatagramReceiver.
     */
    public static DatagramReceiver make(@NonNull Context context, @NonNull Looper looper) {
        if (sInstance == null) {
            sInstance = new DatagramReceiver(context, looper);
        }
        return sInstance;
    }

    /**
     * Create a DatagramReceiver to received satellite datagrams.
     * The received datagrams will be delivered to respective messaging apps.
     *
     * @param context The Context for the DatagramReceiver.
     * @param looper The looper for the handler.
     */
    private DatagramReceiver(@NonNull Context context, @NonNull Looper looper) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        HandlerThread backgroundThread = new HandlerThread(TAG);
        backgroundThread.start();
        mBackgroundHandler = new Handler(looper);
        try {
            mSharedPreferences = mContext.getSharedPreferences(SATELLITE_SHARED_PREF,
                    Context.MODE_PRIVATE);
        } catch (Exception e) {
            loge("Cannot get default shared preferences: " + e);
        }

        if ((mSharedPreferences != null) &&
                (!mSharedPreferences.contains(SATELLITE_DATAGRAM_ID_KEY))) {
            mSharedPreferences.edit().putLong(SATELLITE_DATAGRAM_ID_KEY, mNextDatagramId.get())
                    .commit();
        }
    }

    /**
     * Listeners are updated about incoming datagrams using a backgroundThread.
     */
    private static final class SatelliteDatagramListenerHandler extends Handler {
        public static final int EVENT_SATELLITE_DATAGRAM_RECEIVED = 1;
        public static final int EVENT_RETRY_DELIVERING_RECEIVED_DATAGRAM = 2;
        public static final int EVENT_RECEIVED_ACK = 3;

        @NonNull private final ConcurrentHashMap<IBinder, ISatelliteDatagramCallback> mListeners;
        private final int mSubId;

        private static final class DatagramRetryArgument {
            public long datagramId;
            @NonNull public SatelliteDatagram datagram;
            public int pendingCount;
            @NonNull public ISatelliteDatagramCallback listener;

            DatagramRetryArgument(long datagramId, @NonNull SatelliteDatagram datagram,
                    int pendingCount, @NonNull ISatelliteDatagramCallback listener) {
                this.datagramId = datagramId;
                this.datagram = datagram;
                this.pendingCount = pendingCount;
                this.listener = listener;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                DatagramRetryArgument that = (DatagramRetryArgument) other;
                return datagramId == that.datagramId
                        && datagram.equals(that.datagram)
                        && pendingCount  == that.pendingCount
                        && listener.equals(that.listener);
            }
        }

        SatelliteDatagramListenerHandler(@NonNull Looper looper, int subId) {
            super(looper);
            mSubId = subId;
            mListeners = new ConcurrentHashMap<>();
        }

        public void addListener(@NonNull ISatelliteDatagramCallback listener) {
            mListeners.put(listener.asBinder(), listener);
        }

        public void removeListener(@NonNull ISatelliteDatagramCallback listener) {
            mListeners.remove(listener.asBinder());
        }

        public boolean hasListeners() {
            return !mListeners.isEmpty();
        }

        private int getTimeoutToReceiveAck() {
            return sInstance.mContext.getResources().getInteger(
                    R.integer.config_timeout_to_receive_delivered_ack_millis);
        }

        private long getDatagramId() {
            long datagramId = 0;
            if (sInstance.mSharedPreferences == null) {
                try {
                    // Try to recreate if it is null
                    sInstance.mSharedPreferences = sInstance.mContext
                            .getSharedPreferences(SATELLITE_SHARED_PREF, Context.MODE_PRIVATE);
                } catch (Exception e) {
                    loge("Cannot get default shared preferences: " + e);
                }
            }

            if (sInstance.mSharedPreferences != null) {
                long prevDatagramId = sInstance.mSharedPreferences
                        .getLong(SATELLITE_DATAGRAM_ID_KEY, mNextDatagramId.get());
                datagramId = (prevDatagramId + 1) % DatagramController.MAX_DATAGRAM_ID;
                mNextDatagramId.set(datagramId);
                sInstance.mSharedPreferences.edit().putLong(SATELLITE_DATAGRAM_ID_KEY, datagramId)
                        .commit();
            } else {
                loge("Shared preferences is null - returning default datagramId");
                datagramId = mNextDatagramId.getAndUpdate(
                        n -> ((n + 1) % DatagramController.MAX_DATAGRAM_ID));
            }

            return datagramId;
        }

        private void insertDatagram(long datagramId, @NonNull SatelliteDatagram datagram) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(
                    Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID, datagramId);
            contentValues.put(
                    Telephony.SatelliteDatagrams.COLUMN_DATAGRAM, datagram.getSatelliteDatagram());
            Uri uri = sInstance.mContentResolver.insert(Telephony.SatelliteDatagrams.CONTENT_URI,
                    contentValues);
            if (uri == null) {
                loge("Cannot insert datagram with datagramId: " + datagramId);
            } else {
                logd("Inserted datagram with datagramId: " + datagramId);
            }
        }

        private void deleteDatagram(long datagramId) {
            String whereClause = (Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID
                    + "=" + datagramId);
            Cursor cursor =  sInstance.mContentResolver.query(
                    Telephony.SatelliteDatagrams.CONTENT_URI,
                    null, whereClause, null, null);
            if ((cursor != null) && (cursor.getCount() == 1)) {
                int numRowsDeleted = sInstance.mContentResolver.delete(
                        Telephony.SatelliteDatagrams.CONTENT_URI, whereClause, null);
                if (numRowsDeleted == 0) {
                    loge("Cannot delete datagram with datagramId: " + datagramId);
                } else {
                    logd("Deleted datagram with datagramId: " + datagramId);
                }
            } else {
                loge("Datagram with datagramId: " + datagramId + " is not present in DB.");
            }
        }

        private void onSatelliteDatagramReceived(@NonNull DatagramRetryArgument argument) {
            try {
                argument.listener.onSatelliteDatagramReceived(argument.datagramId,
                        argument.datagram, argument.pendingCount,
                        new ILongConsumer.Stub() {
                            /**
                             * This callback will be used by datagram receiver app
                             * to send ack back to Telephony. If the callback is not
                             * received within five minutes, then Telephony will
                             * resend the datagram again.
                             *
                             * @param datagramId An id that uniquely identifies
                             *                   datagram received by satellite
                             *                   datagram receiver app. This should
                             *                   match with datagramId passed in
                             *                   {@link android.telephony.satellite
                             *                   .SatelliteDatagramCallback
                             *                   #onSatelliteDatagramReceived(long,
                             *                   SatelliteDatagram, int,
                             *                   ISatelliteDatagramReceiverAck)}
                             *                   Upon receiving the ack, Telephony
                             *                   will remove the datagram from
                             *                   the persistent memory.
                             */
                            @Override
                            public void accept(long datagramId) {
                                logd("acknowledgeSatelliteDatagramReceived: "
                                        + "datagramId=" + datagramId);
                                sendMessage(obtainMessage(EVENT_RECEIVED_ACK, argument));
                            }
                        });
            } catch (RemoteException e) {
                logd("EVENT_SATELLITE_DATAGRAM_RECEIVED RemoteException: " + e);
            }
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case EVENT_SATELLITE_DATAGRAM_RECEIVED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    Pair<SatelliteDatagram, Integer> result =
                            (Pair<SatelliteDatagram, Integer>) ar.result;
                    SatelliteDatagram satelliteDatagram = result.first;
                    int pendingCount = result.second;
                    // TODO: update receivePendingCount to listeners using
                    // onDatagramTransferStateChanged

                    long datagramId = getDatagramId();
                    insertDatagram(datagramId, satelliteDatagram);
                    logd("Received EVENT_SATELLITE_DATAGRAM_RECEIVED for subId=" + mSubId);
                    mListeners.values().forEach(listener -> {
                        DatagramRetryArgument argument = new DatagramRetryArgument(datagramId,
                                satelliteDatagram, pendingCount, listener);
                        onSatelliteDatagramReceived(argument);
                        // wait for ack and retry after the timeout specified.
                        sendMessageDelayed(obtainMessage(EVENT_RETRY_DELIVERING_RECEIVED_DATAGRAM,
                                argument), getTimeoutToReceiveAck());
                    });
                    break;
                }

                case EVENT_RETRY_DELIVERING_RECEIVED_DATAGRAM: {
                    DatagramRetryArgument argument = (DatagramRetryArgument) msg.obj;
                    logd("Received EVENT_RETRY_DELIVERING_RECEIVED_DATAGRAM datagramId:"
                            + argument.datagramId);
                    onSatelliteDatagramReceived(argument);
                    break;
                }

                case EVENT_RECEIVED_ACK: {
                    DatagramRetryArgument argument = (DatagramRetryArgument) msg.obj;
                    logd("Received EVENT_RECEIVED_ACK datagramId:" + argument.datagramId);
                    removeMessages(EVENT_RETRY_DELIVERING_RECEIVED_DATAGRAM, argument);
                    deleteDatagram(argument.datagramId);
                }

                default:
                    loge("SatelliteDatagramListenerHandler unknown event: " + msg.what);
            }
        }
    }

    /**
     * Register to receive incoming datagrams over satellite.
     *
     * @param subId The subId of the subscription to register for incoming satellite datagrams.
     * @param datagramType datagram type indicating whether the datagram is of type
     *                     SOS_SMS or LOCATION_SHARING.
     * @param callback The callback to handle incoming datagrams over satellite.
     *
     * @return The {@link SatelliteManager.SatelliteError} result of the operation.
     */
    @SatelliteManager.SatelliteError public int registerForSatelliteDatagram(int subId,
            @SatelliteManager.DatagramType int datagramType,
            @NonNull ISatelliteDatagramCallback callback) {
        if (!SatelliteController.getInstance().isSatelliteSupported()) {
            return SatelliteManager.SATELLITE_NOT_SUPPORTED;
        }

        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        SatelliteDatagramListenerHandler satelliteDatagramListenerHandler =
                mSatelliteDatagramListenerHandlers.get(validSubId);
        if (satelliteDatagramListenerHandler == null) {
            satelliteDatagramListenerHandler = new SatelliteDatagramListenerHandler(
                    mBackgroundHandler.getLooper(), validSubId);
            if (SatelliteModemInterface.getInstance().isSatelliteServiceSupported()) {
                // TODO: remove this as SatelliteModemInterface can register for incoming datagrams
                // on boot up itself.
                SatelliteModemInterface.getInstance().registerForSatelliteDatagramsReceived(
                        satelliteDatagramListenerHandler,
                        SatelliteDatagramListenerHandler.EVENT_SATELLITE_DATAGRAM_RECEIVED, null);
            } else {
                Phone phone = SatelliteServiceUtils.getPhone();
                phone.registerForSatelliteDatagramsReceived(satelliteDatagramListenerHandler,
                        SatelliteDatagramListenerHandler.EVENT_SATELLITE_DATAGRAM_RECEIVED, null);
            }
        }

        satelliteDatagramListenerHandler.addListener(callback);
        mSatelliteDatagramListenerHandlers.put(validSubId, satelliteDatagramListenerHandler);
        return SatelliteManager.SATELLITE_ERROR_NONE;
    }

    /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId The subId of the subscription to unregister for incoming satellite datagrams.
     * @param callback The callback that was passed to
     *                 {@link #registerForSatelliteDatagram(int, int, ISatelliteDatagramCallback)}.
     */
    public void unregisterForSatelliteDatagram(int subId,
            @NonNull ISatelliteDatagramCallback callback) {
        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        SatelliteDatagramListenerHandler handler =
                mSatelliteDatagramListenerHandlers.get(validSubId);
        if (handler != null) {
            handler.removeListener(callback);

            if (!handler.hasListeners()) {
                mSatelliteDatagramListenerHandlers.remove(validSubId);
                if (SatelliteModemInterface.getInstance().isSatelliteServiceSupported()) {
                    SatelliteModemInterface.getInstance()
                            .unregisterForSatelliteDatagramsReceived(handler);
                } else {
                    Phone phone = SatelliteServiceUtils.getPhone();
                    if (phone != null) {
                        phone.unregisterForSatelliteDatagramsReceived(handler);
                    }
                }
            }
        }
    }

    /**
     * Poll pending satellite datagrams over satellite.
     *
     * This method requests modem to check if there are any pending datagrams to be received over
     * satellite. If there are any incoming datagrams, they will be received via
     * {@link android.telephony.satellite.SatelliteDatagramCallback
     * #onSatelliteDatagramReceived(long, SatelliteDatagram, int, ILongConsumer)}
     */
    public void pollPendingSatelliteDatagrams(@NonNull Message message, @Nullable Phone phone) {
        if (SatelliteModemInterface.getInstance().isSatelliteServiceSupported()) {
            SatelliteModemInterface.getInstance().pollPendingSatelliteDatagrams(message);
            return;
        }

        if (phone != null) {
            phone.pollPendingSatelliteDatagrams(message);
        } else {
            loge("pollPendingSatelliteDatagrams: No phone object");
            AsyncResult.forMessage(message, null, new SatelliteManager.SatelliteException(
                    SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE));
            message.sendToTarget();
        }
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }

    // TODO: An api change - do not pass the binder from Telephony to Applications
}