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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.telephony.Rlog;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteManager.SatelliteException;
import android.telephony.satellite.stub.ISatellite;
import android.telephony.satellite.stub.ISatelliteCapabilitiesConsumer;
import android.telephony.satellite.stub.ISatelliteListener;
import android.telephony.satellite.stub.SatelliteService;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.telephony.ExponentialBackoff;
import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntegerConsumer;

import java.util.Arrays;

/**
 * Satellite modem interface to manage connections with the satellite service and HAL interface.
 */
public class SatelliteModemInterface {
    private static final String TAG = "SatelliteModemInterface";
    private static final long REBIND_INITIAL_DELAY = 2 * 1000; // 2 seconds
    private static final long REBIND_MAXIMUM_DELAY = 64 * 1000; // 1 minute
    private static final int REBIND_MULTIPLIER = 2;

    @NonNull private static SatelliteModemInterface sInstance;
    @NonNull private final Context mContext;
    @NonNull private final ExponentialBackoff mExponentialBackoff;
    @NonNull private final Object mLock = new Object();
    /**
     * {@code true} to use the vendor satellite service and {@code false} to use the HAL.
     */
    private final boolean mIsSatelliteServiceSupported = false;
    @Nullable private ISatellite mSatelliteService;
    @Nullable private SatelliteServiceConnection mSatelliteServiceConnection;
    private boolean mIsBound;
    private boolean mIsBinding;

    @NonNull private final RegistrantList mSatelliteProvisionStateChangedRegistrants =
            new RegistrantList();
    @NonNull private final RegistrantList mSatellitePositionInfoChangedRegistrants =
            new RegistrantList();
    @NonNull private final RegistrantList mDatagramTransferStateChangedRegistrants =
            new RegistrantList();
    @NonNull private final RegistrantList mSatelliteModemStateChangedRegistrants =
            new RegistrantList();
    @NonNull private final RegistrantList mPendingDatagramCountRegistrants = new RegistrantList();
    @NonNull private final RegistrantList mSatelliteDatagramsReceivedRegistrants =
            new RegistrantList();
    @NonNull private final RegistrantList mSatelliteRadioTechnologyChangedRegistrants =
            new RegistrantList();

    @NonNull private final ISatelliteListener mListener = new ISatelliteListener.Stub() {
        @Override
        public void onSatelliteProvisionStateChanged(boolean provisioned) {
            mSatelliteProvisionStateChangedRegistrants.notifyResult(provisioned);
        }

        @Override
        public void onSatelliteDatagramReceived(
                android.telephony.satellite.stub.SatelliteDatagram datagram, int pendingCount) {
            mSatelliteDatagramsReceivedRegistrants.notifyResult(new Pair<>(
                    SatelliteServiceUtils.fromSatelliteDatagram(datagram), pendingCount));
        }

        @Override
        public void onPendingDatagramCount(int count) {
            mPendingDatagramCountRegistrants.notifyResult(count);
        }

        @Override
        public void onSatellitePositionChanged(
                android.telephony.satellite.stub.PointingInfo pointingInfo) {
            mSatellitePositionInfoChangedRegistrants.notifyResult(
                    SatelliteServiceUtils.fromPointingInfo(pointingInfo));
        }

        @Override
        public void onSatelliteModemStateChanged(int state) {
            mSatelliteModemStateChangedRegistrants.notifyResult(
                    SatelliteServiceUtils.fromSatelliteModemState(state));
            int datagramTransferState = SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_UNKNOWN;
            switch (state) {
                case SatelliteManager.SATELLITE_MODEM_STATE_IDLE:
                    datagramTransferState = SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE;
                    break;
                case SatelliteManager.SATELLITE_MODEM_STATE_LISTENING:
                    datagramTransferState =
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING;
                    break;
                case SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING:
                    datagramTransferState =
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING;
                    break;
                case SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_RETRYING:
                    // keep previous state as this could be retrying sending or receiving
                    break;
            }
            // TODO: properly notify the rest of the datagram transfer state changed parameters
            mDatagramTransferStateChangedRegistrants.notifyResult(datagramTransferState);
        }

        @Override
        public void onSatelliteRadioTechnologyChanged(int technology) {
            mSatelliteRadioTechnologyChangedRegistrants.notifyResult(
                    SatelliteServiceUtils.fromSatelliteRadioTechnology(technology));
        }
    };

    /**
     * @return The singleton instance of SatelliteModemInterface.
     */
    public static SatelliteModemInterface getInstance() {
        if (sInstance == null) {
            loge("SatelliteModemInterface was not yet initialized.");
        }
        return sInstance;
    }

    /**
     * Create the SatelliteModemInterface singleton instance.
     * @param context The Context to use to create the SatelliteModemInterface.
     * @return The singleton instance of SatelliteModemInterface.
     */
    public static SatelliteModemInterface make(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new SatelliteModemInterface(context, Looper.getMainLooper());
        }
        return sInstance;
    }

    /**
     * Create a SatelliteModemInterface to manage connections to the SatelliteService.
     *
     * @param context The Context for the SatelliteModemInterface.
     * @param looper The Looper to run binding retry on.
     */
    private SatelliteModemInterface(@NonNull Context context, @NonNull Looper looper) {
        mContext = context;
        mExponentialBackoff = new ExponentialBackoff(REBIND_INITIAL_DELAY, REBIND_MAXIMUM_DELAY,
                REBIND_MULTIPLIER, looper, () -> {
            synchronized (mLock) {
                if ((mIsBound && mSatelliteService != null) || mIsBinding) {
                    return;
                }
            }
            if (mSatelliteServiceConnection != null) {
                synchronized (mLock) {
                    mIsBound = false;
                    mIsBinding = false;
                }
                unbindService();
            }
            bindService();
        });
        mExponentialBackoff.start();
        logd("Created SatelliteModemInterface. Attempting to bind to SatelliteService.");
        bindService();
    }

    /**
     * Get the SatelliteService interface, if it exists.
     *
     * @return The bound ISatellite, or {@code null} if it is not yet connected.
     */
    @Nullable public ISatellite getService() {
        return mSatelliteService;
    }

    @NonNull private String getSatellitePackageName() {
        return TextUtils.emptyIfNull(mContext.getResources().getString(
                com.android.internal.R.string.config_satellite_service_package));
    }

    private void bindService() {
        synchronized (mLock) {
            if (mIsBinding || mIsBound) return;
            mIsBinding = true;
        }
        String packageName = getSatellitePackageName();
        if (TextUtils.isEmpty(packageName)) {
            loge("Unable to bind to the satellite service because the package is undefined.");
            // Since the package name comes from static device configs, stop retry because
            // rebind will continue to fail without a valid package name.
            synchronized (mLock) {
                mIsBinding = false;
            }
            mExponentialBackoff.stop();
            return;
        }
        Intent intent = new Intent(SatelliteService.SERVICE_INTERFACE);
        intent.setPackage(packageName);

        mSatelliteServiceConnection = new SatelliteServiceConnection();
        try {
            boolean success = mContext.bindService(
                    intent, mSatelliteServiceConnection, Context.BIND_AUTO_CREATE);
            if (success) {
                logd("Successfully bound to the satellite service.");
            } else {
                synchronized (mLock) {
                    mIsBinding = false;
                }
                mExponentialBackoff.notifyFailed();
                loge("Error binding to the satellite service. Retrying in "
                        + mExponentialBackoff.getCurrentDelay() + " ms.");
            }
        } catch (Exception e) {
            synchronized (mLock) {
                mIsBinding = false;
            }
            mExponentialBackoff.notifyFailed();
            loge("Exception binding to the satellite service. Retrying in "
                    + mExponentialBackoff.getCurrentDelay() + " ms. Exception: " + e);
        }
    }

    private void unbindService() {
        disconnectSatelliteService();
        mContext.unbindService(mSatelliteServiceConnection);
        mSatelliteServiceConnection = null;
    }

    private void disconnectSatelliteService() {
        // TODO: clean up any listeners and return failed for pending callbacks
        mSatelliteService = null;
    }

    private class SatelliteServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            logd("onServiceConnected: ComponentName=" + name);
            synchronized (mLock) {
                mIsBound = true;
                mIsBinding = false;
            }
            mSatelliteService = ISatellite.Stub.asInterface(service);
            mExponentialBackoff.stop();
            try {
                mSatelliteService.setSatelliteListener(mListener);
            } catch (RemoteException e) {
                // TODO: Retry setSatelliteListener
                logd("setSatelliteListener: RemoteException " + e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            loge("onServiceDisconnected: Waiting for reconnect.");
            synchronized (mLock) {
                mIsBinding = false;
            }
            // Since we are still technically bound, clear the service and wait for reconnect.
            disconnectSatelliteService();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            loge("onBindingDied: Unbinding and rebinding service.");
            synchronized (mLock) {
                mIsBound = false;
                mIsBinding = false;
            }
            unbindService();
            mExponentialBackoff.start();
        }
    }

    /**
     * Registers for the satellite provision state changed.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSatelliteProvisionStateChanged(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mSatelliteProvisionStateChangedRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for the satellite provision state changed.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSatelliteProvisionStateChanged(@NonNull Handler h) {
        mSatelliteProvisionStateChangedRegistrants.remove(h);
    }

    /**
     * Registers for satellite position info changed from satellite modem.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSatellitePositionInfoChanged(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mSatellitePositionInfoChangedRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for satellite position info changed from satellite modem.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSatellitePositionInfoChanged(@NonNull Handler h) {
        mSatellitePositionInfoChangedRegistrants.remove(h);
    }

    /**
     * Registers for datagram transfer state changed.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForDatagramTransferStateChanged(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mDatagramTransferStateChangedRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for datagram transfer state changed.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForDatagramTransferStateChanged(@NonNull Handler h) {
        mDatagramTransferStateChangedRegistrants.remove(h);
    }

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSatelliteModemStateChanged(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mSatelliteModemStateChangedRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for modem state changed from satellite modem.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSatelliteModemStateChanged(@NonNull Handler h) {
        mSatelliteModemStateChangedRegistrants.remove(h);
    }

    /**
     * Registers for pending datagram count from satellite modem.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForPendingDatagramCount(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mPendingDatagramCountRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for pending datagram count from satellite modem.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForPendingDatagramCount(@NonNull Handler h) {
        mPendingDatagramCountRegistrants.remove(h);
    }

    /**
     * Registers for new datagrams received from satellite modem.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSatelliteDatagramsReceived(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mSatelliteDatagramsReceivedRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for new datagrams received from satellite modem.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSatelliteDatagramsReceived(@NonNull Handler h) {
        mSatelliteDatagramsReceivedRegistrants.remove(h);
    }

    /**
     * Registers for satellite radio technology changed from satellite modem.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSatelliteRadioTechnologyChanged(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mSatelliteRadioTechnologyChangedRegistrants.add(h, what, obj);
    }

    /**
     * Unregisters for satellite radio technology changed from satellite modem.
     *
     * @param h Handler to be removed from the registrant list.
     */
    public void unregisterForSatelliteRadioTechnologyChanged(@NonNull Handler h) {
        mSatelliteRadioTechnologyChangedRegistrants.remove(h);
    }

    /**
     * Request to enable or disable the satellite service listening mode.
     * Listening mode allows the satellite service to listen for incoming pages.
     *
     * @param enable True to enable satellite listening mode and false to disable.
     * @param timeout How long the satellite modem should wait for the next incoming page before
     *                disabling listening mode.
     * @param isSatelliteDemoModeEnabled True if satellite demo mode is enabled
     * @param message The Message to send to result of the operation to.
     */
    public void requestSatelliteListeningEnabled(boolean enable, int timeout,
            boolean isSatelliteDemoModeEnabled, @NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestSatelliteListeningEnabled(
                        enable, isSatelliteDemoModeEnabled,
                        timeout, new IIntegerConsumer.Stub() {
                            @Override
                            public void accept(int result) {
                                int error = SatelliteServiceUtils.fromSatelliteError(result);
                                logd("requestSatelliteListeningEnabled: " + error);
                                Binder.withCleanCallingIdentity(() ->
                                        sendMessageWithResult(message, null, error));
                            }
                        });
            } catch (RemoteException e) {
                loge("requestSatelliteListeningEnabled: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestSatelliteListeningEnabled: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to enable or disable the satellite modem. If the satellite modem is enabled,
     * this will also disable the cellular modem, and if the satellite modem is disabled,
     * this will also re-enable the cellular modem.
     *
     * @param enable True to enable the satellite modem and false to disable.
     * @param message The Message to send to result of the operation to.
     */
    public void requestSatelliteEnabled(boolean enable, @NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestSatelliteEnabled(enable, new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("setSatelliteEnabled: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                });
            } catch (RemoteException e) {
                loge("setSatelliteEnabled: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("setSatelliteEnabled: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestIsSatelliteEnabled(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestIsSatelliteEnabled(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("requestIsSatelliteEnabled: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                }, new IBooleanConsumer.Stub() {
                    @Override
                    public void accept(boolean result) {
                        // Convert for compatibility with SatelliteResponse
                        // TODO: This should just report result instead.
                        int[] enabled = new int[] {result ? 1 : 0};
                        logd("requestIsSatelliteEnabled: " + Arrays.toString(enabled));
                        Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                message, enabled, SatelliteManager.SATELLITE_ERROR_NONE));
                    }
                });
            } catch (RemoteException e) {
                loge("requestIsSatelliteEnabled: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestIsSatelliteEnabled: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestIsSatelliteSupported(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestIsSatelliteSupported(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("requestIsSatelliteSupported: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                }, new IBooleanConsumer.Stub() {
                    @Override
                    public void accept(boolean result) {
                        logd("requestIsSatelliteSupported: " + result);
                        Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                message, result, SatelliteManager.SATELLITE_ERROR_NONE));
                    }
                });
            } catch (RemoteException e) {
                loge("requestIsSatelliteSupported: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestIsSatelliteSupported: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get the SatelliteCapabilities of the satellite service.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestSatelliteCapabilities(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestSatelliteCapabilities(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("requestSatelliteCapabilities: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                }, new ISatelliteCapabilitiesConsumer.Stub() {
                    @Override
                    public void accept(android.telephony.satellite.stub.SatelliteCapabilities
                            result) {
                        SatelliteCapabilities capabilities =
                                SatelliteServiceUtils.fromSatelliteCapabilities(result);
                        logd("requestSatelliteCapabilities: " + capabilities);
                        Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                message, capabilities, SatelliteManager.SATELLITE_ERROR_NONE));
                    }
                });
            } catch (RemoteException e) {
                loge("requestSatelliteCapabilities: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestSatelliteCapabilities: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * User started pointing to the satellite.
     * The satellite service should report the satellite pointing info via
     * ISatelliteListener#onSatellitePositionChanged as the user device/satellite moves.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void startSendingSatellitePointingInfo(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.startSendingSatellitePointingInfo(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("startSendingSatellitePointingInfo: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                });
            } catch (RemoteException e) {
                loge("startSendingSatellitePointingInfo: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("startSendingSatellitePointingInfo: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * User stopped pointing to the satellite.
     * The satellite service should stop reporting satellite pointing info to the framework.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void stopSendingSatellitePointingInfo(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.stopSendingSatellitePointingInfo(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("stopSendingSatellitePointingInfo: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                });
            } catch (RemoteException e) {
                loge("stopSendingSatellitePointingInfo: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("stopSendingSatellitePointingInfo: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get the maximum number of characters per MO text message on satellite.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestMaxCharactersPerMOTextMessage(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestMaxCharactersPerMOTextMessage(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("requestMaxCharactersPerMOTextMessage: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                }, new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        // Convert for compatibility with SatelliteResponse
                        // TODO: This should just report result instead.
                        int[] maxCharacters = new int[] {result};
                        logd("requestMaxCharactersPerMOTextMessage: "
                                + Arrays.toString(maxCharacters));
                        Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                message, maxCharacters, SatelliteManager.SATELLITE_ERROR_NONE));
                    }
                });
            } catch (RemoteException e) {
                loge("requestMaxCharactersPerMOTextMessage: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestMaxCharactersPerMOTextMessage: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once provisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report true.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param message The Message to send to result of the operation to.
     */
    public void provisionSatelliteService(@NonNull String token, @NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.provisionSatelliteService(token, new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("provisionSatelliteService: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                });
            } catch (RemoteException e) {
                loge("provisionSatelliteService: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("provisionSatelliteService: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once deprovisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report false.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param message The Message to send to result of the operation to.
     */
    public void deprovisionSatelliteService(@NonNull String token, @NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.deprovisionSatelliteService(token, new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("deprovisionSatelliteService: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                });
            } catch (RemoteException e) {
                loge("deprovisionSatelliteService: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("deprovisionSatelliteService: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestIsSatelliteProvisioned(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestIsSatelliteProvisioned(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("requestIsSatelliteProvisioned: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                }, new IBooleanConsumer.Stub() {
                    @Override
                    public void accept(boolean result) {
                        // Convert for compatibility with SatelliteResponse
                        // TODO: This should just report result instead.
                        int[] provisioned = new int[] {result ? 1 : 0};
                        logd("requestIsSatelliteProvisioned: " + Arrays.toString(provisioned));
                        Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                message, provisioned, SatelliteManager.SATELLITE_ERROR_NONE));
                    }
                });
            } catch (RemoteException e) {
                loge("requestIsSatelliteProvisioned: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestIsSatelliteProvisioned: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Poll the pending datagrams to be received over satellite.
     * The satellite service should check if there are any pending datagrams to be received over
     * satellite and report them via ISatelliteListener#onSatelliteDatagramsReceived.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void pollPendingSatelliteDatagrams(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.pollPendingSatelliteDatagrams(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("pollPendingSatelliteDatagrams: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                });
            } catch (RemoteException e) {
                loge("pollPendingSatelliteDatagrams: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("pollPendingSatelliteDatagrams: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Send datagram over satellite.
     *
     * @param datagram Datagram to send in byte format.
     * @param isEmergency Whether this is an emergency datagram.
     * @param needFullScreenPointingUI this is used to indicate pointingUI app to open in
     *                                 full screen mode.
     * @param isSatelliteDemoModeEnabled True if satellite demo mode is enabled
     * @param message The Message to send to result of the operation to.
     */
    public void sendSatelliteDatagram(@NonNull SatelliteDatagram datagram, boolean isEmergency,
            boolean needFullScreenPointingUI, boolean isSatelliteDemoModeEnabled,
            @NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.sendSatelliteDatagram(
                        SatelliteServiceUtils.toSatelliteDatagram(datagram),
                        isSatelliteDemoModeEnabled, isEmergency,
                        new IIntegerConsumer.Stub() {
                            @Override
                            public void accept(int result) {
                                int error = SatelliteServiceUtils.fromSatelliteError(result);
                                logd("sendSatelliteDatagram: " + error);
                                Binder.withCleanCallingIdentity(() ->
                                        sendMessageWithResult(message, null, error));
                            }
                        });
            } catch (RemoteException e) {
                loge("sendSatelliteDatagram: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("sendSatelliteDatagram: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request the current satellite modem state.
     * The satellite service should report the current satellite modem state via
     * ISatelliteListener#onSatelliteModemStateChanged.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestSatelliteModemState(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestSatelliteModemState(new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        int error = SatelliteServiceUtils.fromSatelliteError(result);
                        logd("requestSatelliteModemState: " + error);
                        Binder.withCleanCallingIdentity(() ->
                                sendMessageWithResult(message, null, error));
                    }
                }, new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        // Convert SatelliteModemState from service to frameworks definition.
                        int modemState = SatelliteServiceUtils.fromSatelliteModemState(result);
                        logd("requestSatelliteModemState: " + modemState);
                        Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                message, modemState, SatelliteManager.SATELLITE_ERROR_NONE));
                    }
                });
            } catch (RemoteException e) {
                loge("requestSatelliteModemState: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestSatelliteModemState: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestIsSatelliteCommunicationAllowedForCurrentLocation(
                        new IIntegerConsumer.Stub() {
                            @Override
                            public void accept(int result) {
                                int error = SatelliteServiceUtils.fromSatelliteError(result);
                                logd("requestIsSatelliteCommunicationAllowedForCurrentLocation: "
                                        + error);
                                Binder.withCleanCallingIdentity(() ->
                                        sendMessageWithResult(message, null, error));
                            }
                        }, new IBooleanConsumer.Stub() {
                            @Override
                            public void accept(boolean result) {
                                logd("requestIsSatelliteCommunicationAllowedForCurrentLocation: "
                                        + result);
                                Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                        message, result, SatelliteManager.SATELLITE_ERROR_NONE));
                            }
                        });
            } catch (RemoteException e) {
                loge("requestIsSatelliteCommunicationAllowedForCurrentLocation: RemoteException "
                        + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestIsSatelliteCommunicationAllowedForCurrentLocation: "
                    + "Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    /**
     * Request to get the time after which the satellite will be visible. This is an int
     * representing the duration in seconds after which the satellite will be visible.
     * This will return 0 if the satellite is currently visible.
     *
     * @param message The Message to send to result of the operation to.
     */
    public void requestTimeForNextSatelliteVisibility(@NonNull Message message) {
        if (mSatelliteService != null) {
            try {
                mSatelliteService.requestTimeForNextSatelliteVisibility(
                        new IIntegerConsumer.Stub() {
                            @Override
                            public void accept(int result) {
                                int error = SatelliteServiceUtils.fromSatelliteError(result);
                                logd("requestTimeForNextSatelliteVisibility: " + error);
                                Binder.withCleanCallingIdentity(() ->
                                        sendMessageWithResult(message, null, error));
                            }
                        }, new IIntegerConsumer.Stub() {
                            @Override
                            public void accept(int result) {
                                // Convert for compatibility with SatelliteResponse
                                // TODO: This should just report result instead.
                                int[] time = new int[] {result};
                                logd("requestTimeForNextSatelliteVisibility: "
                                        + Arrays.toString(time));
                                Binder.withCleanCallingIdentity(() -> sendMessageWithResult(
                                        message, time, SatelliteManager.SATELLITE_ERROR_NONE));
                            }
                        });
            } catch (RemoteException e) {
                loge("requestTimeForNextSatelliteVisibility: RemoteException " + e);
                sendMessageWithResult(message, null, SatelliteManager.SATELLITE_SERVICE_ERROR);
            }
        } else {
            loge("requestTimeForNextSatelliteVisibility: Satellite service is unavailable.");
            sendMessageWithResult(message, null, SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED);
        }
    }

    public boolean isSatelliteServiceSupported() {
        // TODO: update this method
        return mIsSatelliteServiceSupported;
    }

    private static void sendMessageWithResult(@NonNull Message message, @Nullable Object result,
            @SatelliteManager.SatelliteError int error) {
        AsyncResult.forMessage(message, result, new SatelliteException(error));
        message.sendToTarget();
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }
}
