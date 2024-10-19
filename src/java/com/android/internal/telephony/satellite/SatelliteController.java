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

import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY;
import static android.provider.Settings.ACTION_SATELLITE_SETTING;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_EMERGENCY_CALL_TO_SATELLITE_T911_HANDOVER_TIMEOUT_MILLIS_INT;
import static android.telephony.CarrierConfigManager.KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ATTACH_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_NIDD_APN_NAME_STRING;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_ESOS_INACTIVITY_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_P2P_SMS_INACTIVITY_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_P2P_SMS_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_SCREEN_OFF_INACTIVITY_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL;
import static android.telephony.SubscriptionManager.SATELLITE_ATTACH_ENABLED_FOR_CARRIER;
import static android.telephony.SubscriptionManager.SATELLITE_ENTITLEMENT_STATUS;
import static android.telephony.SubscriptionManager.isValidSubscriptionId;
import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;
import static android.telephony.satellite.NtnSignalStrength.NTN_SIGNAL_STRENGTH_NONE;
import static android.telephony.satellite.SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS;
import static android.telephony.satellite.SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911;
import static android.telephony.satellite.SatelliteManager.KEY_NTN_SIGNAL_STRENGTH;
import static android.telephony.satellite.SatelliteManager.SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT;
import static android.telephony.satellite.SatelliteManager.SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_INVALID_ARGUMENTS;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_ERROR;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_TIMEOUT;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_ABORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.internal.telephony.configupdate.ConfigProviderAdaptor.DOMAIN_SATELLITE;

import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.DropBoxManagerLoggerBackend;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PersistentLogger;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.satellite.INtnSignalStrengthCallback;
import android.telephony.satellite.ISatelliteCapabilitiesCallback;
import android.telephony.satellite.ISatelliteDatagramCallback;
import android.telephony.satellite.ISatelliteModemStateCallback;
import android.telephony.satellite.ISatelliteProvisionStateCallback;
import android.telephony.satellite.ISatelliteSupportedStateCallback;
import android.telephony.satellite.ISatelliteTransmissionUpdateCallback;
import android.telephony.satellite.NtnSignalStrength;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteModemEnableRequestAttributes;
import android.telephony.satellite.SatelliteSubscriberInfo;
import android.telephony.satellite.SatelliteSubscriberProvisionStatus;
import android.telephony.satellite.SatelliteSubscriptionInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.uwb.UwbManager;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DeviceStateMonitor;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCountryDetector;
import com.android.internal.telephony.configupdate.ConfigParser;
import com.android.internal.telephony.configupdate.ConfigProviderAdaptor;
import com.android.internal.telephony.configupdate.TelephonyConfigUpdateInstallReceiver;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteControllerStats;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteSessionStats;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;
import com.android.internal.telephony.satellite.metrics.ProvisionMetricsStats;
import com.android.internal.telephony.satellite.metrics.SessionMetricsStats;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.internal.util.FunctionalUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Satellite controller is the backend service of
 * {@link android.telephony.satellite.SatelliteManager}.
 */
public class SatelliteController extends Handler {
    private static final String TAG = "SatelliteController";
    /** Whether enabling verbose debugging message or not. */
    private static final boolean DBG = false;
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final boolean DEBUG = !"user".equals(Build.TYPE);
    /** File used to store shared preferences related to satellite. */
    public static final String SATELLITE_SHARED_PREF = "satellite_shared_pref";
    public static final String SATELLITE_SUBSCRIPTION_ID = "satellite_subscription_id";
    /** Value to pass for the setting key SATELLITE_MODE_ENABLED, enabled = 1, disabled = 0 */
    public static final int SATELLITE_MODE_ENABLED_TRUE = 1;
    public static final int SATELLITE_MODE_ENABLED_FALSE = 0;
    public static final int INVALID_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE = -1;
    /**
     * This is used by CTS to override the timeout duration to wait for the response of the request
     * to enable satellite.
     */
    public static final int TIMEOUT_TYPE_WAIT_FOR_SATELLITE_ENABLING_RESPONSE = 1;
    /** This is used by CTS to override demo pointing aligned duration. */
    public static final int TIMEOUT_TYPE_DEMO_POINTING_ALIGNED_DURATION_MILLIS = 2;
    /** This is used by CTS to override demo pointing not aligned duration. */
    public static final int TIMEOUT_TYPE_DEMO_POINTING_NOT_ALIGNED_DURATION_MILLIS = 3;
    /** This is used by CTS to override evaluate esos profiles prioritization duration. */
    public static final int TIMEOUT_TYPE_EVALUATE_ESOS_PROFILES_PRIORITIZATION_DURATION_MILLIS = 4;
    /** Key used to read/write OEM-enabled satellite provision status in shared preferences. */
    private static final String OEM_ENABLED_SATELLITE_PROVISION_STATUS_KEY =
            "oem_enabled_satellite_provision_status_key";

    public static final long DEFAULT_CARRIER_EMERGENCY_CALL_WAIT_FOR_CONNECTION_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(30);

    /** Sets report entitled metrics cool down to 23 hours to help enforcing privacy requirement.*/
    private static final long WAIT_FOR_REPORT_ENTITLED_MERTICS_TIMEOUT_MILLIS =
            TimeUnit.HOURS.toMillis(23);

    /** Message codes used in handleMessage() */
    //TODO: Move the Commands and events related to position updates to PointingAppController
    private static final int CMD_START_SATELLITE_TRANSMISSION_UPDATES = 1;
    private static final int EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE = 2;
    private static final int CMD_STOP_SATELLITE_TRANSMISSION_UPDATES = 3;
    private static final int EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE = 4;
    private static final int CMD_PROVISION_SATELLITE_SERVICE = 7;
    private static final int EVENT_PROVISION_SATELLITE_SERVICE_DONE = 8;
    private static final int CMD_DEPROVISION_SATELLITE_SERVICE = 9;
    private static final int EVENT_DEPROVISION_SATELLITE_SERVICE_DONE = 10;
    private static final int CMD_SET_SATELLITE_ENABLED = 11;
    private static final int EVENT_SET_SATELLITE_ENABLED_DONE = 12;
    private static final int CMD_IS_SATELLITE_ENABLED = 13;
    private static final int EVENT_IS_SATELLITE_ENABLED_DONE = 14;
    private static final int CMD_IS_SATELLITE_SUPPORTED = 15;
    private static final int EVENT_IS_SATELLITE_SUPPORTED_DONE = 16;
    private static final int CMD_GET_SATELLITE_CAPABILITIES = 17;
    private static final int EVENT_GET_SATELLITE_CAPABILITIES_DONE = 18;
    private static final int CMD_GET_TIME_SATELLITE_NEXT_VISIBLE = 21;
    private static final int EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE = 22;
    private static final int EVENT_RADIO_STATE_CHANGED = 23;
    private static final int CMD_IS_SATELLITE_PROVISIONED = 24;
    private static final int EVENT_IS_SATELLITE_PROVISIONED_DONE = 25;
    private static final int EVENT_PENDING_DATAGRAMS = 27;
    private static final int EVENT_SATELLITE_MODEM_STATE_CHANGED = 28;
    private static final int EVENT_SET_SATELLITE_PLMN_INFO_DONE = 29;
    private static final int CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE = 30;
    private static final int EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE = 31;
    private static final int CMD_REQUEST_NTN_SIGNAL_STRENGTH = 32;
    private static final int EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE = 33;
    private static final int EVENT_NTN_SIGNAL_STRENGTH_CHANGED = 34;
    private static final int CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING = 35;
    private static final int EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE = 36;
    private static final int EVENT_SERVICE_STATE_CHANGED = 37;
    private static final int EVENT_SATELLITE_CAPABILITIES_CHANGED = 38;
    protected static final int EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT = 39;
    private static final int EVENT_SATELLITE_CONFIG_DATA_UPDATED = 40;
    private static final int EVENT_SATELLITE_SUPPORTED_STATE_CHANGED = 41;
    private static final int EVENT_NOTIFY_NTN_HYSTERESIS_TIMED_OUT = 42;
    private static final int CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION = 43;
    private static final int CMD_UPDATE_PROVISION_SATELLITE_TOKEN = 44;
    private static final int EVENT_UPDATE_PROVISION_SATELLITE_TOKEN_DONE = 45;
    private static final int EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT = 46;
    private static final int EVENT_WIFI_CONNECTIVITY_STATE_CHANGED = 47;
    private static final int EVENT_SATELLITE_ACCESS_RESTRICTION_CHECKING_RESULT = 48;
    protected static final int EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT = 49;
    private static final int CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES = 50;
    private static final int EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE = 51;
    protected static final int
            EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT = 52;
    private static final int EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT = 53;
    protected static final int EVENT_SATELLITE_REGISTRATION_FAILURE = 54;

    @NonNull private static SatelliteController sInstance;
    @NonNull private final Context mContext;
    @NonNull private final SatelliteModemInterface mSatelliteModemInterface;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull protected SatelliteSessionController mSatelliteSessionController;
    @NonNull private final PointingAppController mPointingAppController;
    @NonNull private final DatagramController mDatagramController;
    @NonNull private final ControllerMetricsStats mControllerMetricsStats;
    @NonNull private final ProvisionMetricsStats mProvisionMetricsStats;
    @NonNull private SessionMetricsStats mSessionMetricsStats;
    @NonNull private CarrierRoamingSatelliteControllerStats mCarrierRoamingSatelliteControllerStats;
    @NonNull private final SubscriptionManagerService mSubscriptionManagerService;
    @NonNull private final TelephonyCountryDetector mCountryDetector;
    @NonNull private final TelecomManager mTelecomManager;
    private final CommandsInterface mCi;
    private ContentResolver mContentResolver;
    private final DeviceStateMonitor mDSM;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected final Object mSatellitePhoneLock = new Object();
    @GuardedBy("mSatellitePhoneLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected Phone mSatellitePhone = null;

    private final Object mRadioStateLock = new Object();

    /** Flags to indicate whether the respective radio is enabled */
    @GuardedBy("mRadioStateLock")
    private boolean mBTStateEnabled = false;
    @GuardedBy("mRadioStateLock")
    private boolean mNfcStateEnabled = false;
    @GuardedBy("mRadioStateLock")
    private boolean mUwbStateEnabled = false;
    @GuardedBy("mRadioStateLock")
    private boolean mWifiStateEnabled = false;

    // Flags to indicate that respective radios need to be disabled when satellite is enabled
    private boolean mDisableBTOnSatelliteEnabled = false;
    private boolean mDisableNFCOnSatelliteEnabled = false;
    private boolean mDisableUWBOnSatelliteEnabled = false;
    private boolean mDisableWifiOnSatelliteEnabled = false;

    private final Object mSatelliteEnabledRequestLock = new Object();
    /* This variable is used to store the first enable request that framework has received in the
     * current session.
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private RequestSatelliteEnabledArgument mSatelliteEnabledRequest = null;
    /* This variable is used to store a disable request that framework has received.
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private RequestSatelliteEnabledArgument mSatelliteDisabledRequest = null;
    /* This variable is used to store an enable request that updates the enable attributes of an
     * existing satellite session.
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private RequestSatelliteEnabledArgument mSatelliteEnableAttributesUpdateRequest = null;
    /** Flag to indicate that satellite is enabled successfully
     * and waiting for all the radios to be disabled so that success can be sent to callback
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private boolean mWaitingForRadioDisabled = false;
    @GuardedBy("mSatelliteEnabledRequestLock")
    private boolean mWaitingForDisableSatelliteModemResponse = false;
    @GuardedBy("mSatelliteEnabledRequestLock")
    private boolean mWaitingForSatelliteModemOff = false;

    private final AtomicBoolean mRegisteredForPendingDatagramCountWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteModemStateChangedWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForNtnSignalStrengthChanged = new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteCapabilitiesChanged =
            new AtomicBoolean(false);
    private final AtomicBoolean mIsModemEnabledReportingNtnSignalStrength =
            new AtomicBoolean(false);
    private final AtomicBoolean mLatestRequestedStateForNtnSignalStrengthReport =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteSupportedStateChanged =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteRegistrationFailure =
            new AtomicBoolean(false);
    /**
     * Map key: subId, value: callback to get error code of the provision request.
     */
    private final ConcurrentHashMap<Integer, Consumer<Integer>> mSatelliteProvisionCallbacks =
            new ConcurrentHashMap<>();

    /**
     * Map key: binder of the callback, value: callback to receive provision state changed events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteProvisionStateCallback>
            mSatelliteProvisionStateChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive non-terrestrial signal strength
     * state changed events.
     */
    private final ConcurrentHashMap<IBinder, INtnSignalStrengthCallback>
            mNtnSignalStrengthChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive satellite capabilities changed
     * events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteCapabilitiesCallback>
            mSatelliteCapabilitiesChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive supported state changed events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteSupportedStateCallback>
            mSatelliteSupportedStateChangedListeners = new ConcurrentHashMap<>();

    /**
     * Map key: binder of the callback, value: callback to satellite registration failure
     */
    private final ConcurrentHashMap<IBinder, ISatelliteModemStateCallback>
            mSatelliteRegistrationFailureListeners = new ConcurrentHashMap<>();
    private final Object mIsSatelliteSupportedLock = new Object();
    @GuardedBy("mIsSatelliteSupportedLock")
    private Boolean mIsSatelliteSupported = null;
    private boolean mIsDemoModeEnabled = false;
    private boolean mIsEmergency = false;
    private final Object mIsSatelliteEnabledLock = new Object();
    @GuardedBy("mIsSatelliteEnabledLock")
    private Boolean mIsSatelliteEnabled = null;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected final Object mIsRadioOnLock = new Object();
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean mIsRadioOn;
    @GuardedBy("mIsRadioOnLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean mRadioOffRequested = false;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected final Object mSatelliteViaOemProvisionLock = new Object();
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @GuardedBy("mSatelliteViaOemProvisionLock")
    protected Boolean mIsSatelliteViaOemProvisioned = null;
    @GuardedBy("mSatelliteViaOemProvisionLock")
    private Boolean mOverriddenIsSatelliteViaOemProvisioned = null;
    private final Object mSatelliteCapabilitiesLock = new Object();
    @GuardedBy("mSatelliteCapabilitiesLock")
    private SatelliteCapabilities mSatelliteCapabilities;
    private final Object mNeedsSatellitePointingLock = new Object();
    @GuardedBy("mNeedsSatellitePointingLock")
    private boolean mNeedsSatellitePointing = false;
    private final Object mNtnSignalsStrengthLock = new Object();
    @GuardedBy("mNtnSignalsStrengthLock")
    private NtnSignalStrength mNtnSignalStrength =
            new NtnSignalStrength(NTN_SIGNAL_STRENGTH_NONE);
    /** Key: subId, value: (key: PLMN, value: set of
     * {@link android.telephony.NetworkRegistrationInfo.ServiceType})
     */
    @GuardedBy("mSupportedSatelliteServicesLock")
    @NonNull private final Map<Integer, Map<String, Set<Integer>>>
            mSatelliteServicesSupportedByCarriers = new HashMap<>();
    @NonNull private final Object mSupportedSatelliteServicesLock = new Object();
    @NonNull private final List<String> mSatellitePlmnListFromOverlayConfig;
    @NonNull private final CarrierConfigManager mCarrierConfigManager;
    @NonNull private final CarrierConfigManager.CarrierConfigChangeListener
            mCarrierConfigChangeListener;
    @NonNull private final ConfigProviderAdaptor.Callback mConfigDataUpdatedCallback;
    @NonNull private final Object mCarrierConfigArrayLock = new Object();
    @NonNull
    private final SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionsChangedListener;
    @GuardedBy("mCarrierConfigArrayLock")
    @NonNull private final SparseArray<PersistableBundle> mCarrierConfigArray = new SparseArray<>();
    @GuardedBy("mIsSatelliteEnabledLock")
    /** Key: Subscription ID, value: set of restriction reasons for satellite communication.*/
    @NonNull private final Map<Integer, Set<Integer>> mSatelliteAttachRestrictionForCarrierArray =
            new HashMap<>();
    @GuardedBy("mIsSatelliteEnabledLock")
    /** Key: Subscription ID, value: the actual satellite enabled state in the modem -
     * {@code true} for enabled and {@code false} for disabled. */
    @NonNull private final Map<Integer, Boolean> mIsSatelliteAttachEnabledForCarrierArrayPerSub =
            new HashMap<>();
    @NonNull private final FeatureFlags mFeatureFlags;
    @NonNull private final Object mSatelliteConnectedLock = new Object();
    /** Key: Subscription ID; Value: Last satellite connected time */
    @GuardedBy("mSatelliteConnectedLock")
    @NonNull private final SparseArray<Long> mLastSatelliteDisconnectedTimesMillis =
            new SparseArray<>();
    /**
     * Key: Subscription ID; Value: {@code true} if satellite was just connected,
     * {@code false} otherwise.
     */
    @GuardedBy("mSatelliteConnectedLock")
    @NonNull private final SparseBooleanArray
            mWasSatelliteConnectedViaCarrier = new SparseBooleanArray();

    @GuardedBy("mSatelliteConnectedLock")
    @NonNull private final SparseBooleanArray mLastNotifiedNtnMode = new SparseBooleanArray();

    @GuardedBy("mSatelliteConnectedLock")
    @NonNull private final SparseBooleanArray mInitialized = new SparseBooleanArray();

    /**
     * Boolean set to {@code true} when device is eligible to connect to carrier roaming
     * non-terrestrial network else set to {@code false}.
     */
    @GuardedBy("mSatellitePhoneLock")
    private Boolean mLastNotifiedNtnEligibility = null;
    @GuardedBy("mSatellitePhoneLock")
    private boolean mNtnEligibilityHysteresisTimedOut = false;
    @GuardedBy("mSatellitePhoneLock")
    private boolean mCheckingAccessRestrictionInProgress = false;

    @GuardedBy("mSatelliteConnectedLock")
    @NonNull private final Map<Integer, CarrierRoamingSatelliteSessionStats>
            mCarrierRoamingSatelliteSessionStatsMap = new HashMap<>();

    /**
     * Key: Subscription ID; Value: set of
     * {@link android.telephony.NetworkRegistrationInfo.ServiceType}
     */
    @GuardedBy("mSatelliteConnectedLock")
    @NonNull private final Map<Integer, List<Integer>>
            mSatModeCapabilitiesForCarrierRoaming = new HashMap<>();

    /**
     * This is used for testing only. When mEnforcedEmergencyCallToSatelliteHandoverType is valid,
     * Telephony will ignore the IMS registration status and cellular availability, and always send
     * the connection event EVENT_DISPLAY_EMERGENCY_MESSAGE to Dialer.
     */
    private int mEnforcedEmergencyCallToSatelliteHandoverType =
            INVALID_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE;
    private int mDelayInSendingEventDisplayEmergencyMessage = 0;
    @NonNull private SharedPreferences mSharedPreferences = null;

    @Nullable private PersistentLogger mPersistentLogger = null;

    /**
     * Key : Subscription ID, Value: {@code true} if the EntitlementStatus is enabled,
     * {@code false} otherwise.
     */
    @GuardedBy("mSupportedSatelliteServicesLock")
    private SparseBooleanArray mSatelliteEntitlementStatusPerCarrier = new SparseBooleanArray();
    /** Key Subscription ID, value : PLMN allowed list from entitlement. */
    @GuardedBy("mSupportedSatelliteServicesLock")
    private SparseArray<List<String>> mEntitlementPlmnListPerCarrier = new SparseArray<>();
    /** Key Subscription ID, value : PLMN barred list from entitlement. */
    @GuardedBy("mSupportedSatelliteServicesLock")
    private SparseArray<List<String>> mEntitlementBarredPlmnListPerCarrier = new SparseArray<>();
    /**
     * Key : Subscription ID, Value : If there is an entitlementPlmnList, use it. Otherwise, use the
     * carrierPlmnList. */
    @GuardedBy("mSupportedSatelliteServicesLock")
    private final SparseArray<List<String>> mMergedPlmnListPerCarrier = new SparseArray<>();
    private static AtomicLong sNextSatelliteEnableRequestId = new AtomicLong(0);
    // key : subscriberId, value : provisioned or not.
    @GuardedBy("mSatelliteTokenProvisionedLock")
    private Map<String, Boolean> mProvisionedSubscriberId = new HashMap<>();
    // key : subscriberId, value : subId
    @GuardedBy("mSatelliteTokenProvisionedLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected Map<String, Integer> mSubscriberIdPerSub = new HashMap<>();
    // key : priority, low value is high, value : List<SubscriptionInfo>
    @GuardedBy("mSatelliteTokenProvisionedLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected TreeMap<Integer, List<SubscriptionInfo>> mSubsInfoListPerPriority = new TreeMap<>();
    // The ID of the satellite subscription that has highest priority and is provisioned.
    @GuardedBy("mSatelliteTokenProvisionedLock")
    private int mSelectedSatelliteSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    // The last ICC ID that framework configured to modem.
    @GuardedBy("mSatelliteTokenProvisionedLock")
    private String mLastConfiguredIccId;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull protected final Object mSatelliteTokenProvisionedLock = new Object();
    private long mWaitTimeForSatelliteEnablingResponse;
    private long mDemoPointingAlignedDurationMillis;
    private long mDemoPointingNotAlignedDurationMillis;
    private long mEvaluateEsosProfilesPrioritizationDurationMillis;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private long mLastEmergencyCallTime;
    private long mSatelliteEmergencyModeDurationMillis;
    private static final int DEFAULT_SATELLITE_EMERGENCY_MODE_DURATION_SECONDS = 300;

    /** Key used to read/write satellite system notification done in shared preferences. */
    private static final String SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY =
            "satellite_system_notification_done_key";
    // The notification tag used when showing a notification. The combination of notification tag
    // and notification id should be unique within the phone app.
    private static final String NOTIFICATION_TAG = "SatelliteController";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL = "satelliteChannel";
    private static final String NOTIFICATION_CHANNEL_ID = "satellite";

    private final RegistrantList mSatelliteConfigUpdateChangedRegistrants = new RegistrantList();
    private final BTWifiNFCStateReceiver mBTWifiNFCSateReceiver;
    private final UwbAdapterStateCallback mUwbAdapterStateCallback;

    private long mSessionStartTimeStamp;
    private long mSessionProcessingTimeStamp;

    // Variable for backup and restore device's screen rotation settings.
    private String mDeviceRotationLockToBackupAndRestore = null;
    // This is used for testing only. Context#getSystemService is a final API and cannot be
    // mocked. Using this to inject a mock SubscriptionManager to work around this limitation.
    private SubscriptionManager mInjectSubscriptionManager = null;

    private final Object mIsWifiConnectedLock = new Object();
    @GuardedBy("mIsWifiConnectedLock")
    private boolean mIsWifiConnected = false;
    private boolean mHasSentBroadcast = false;
    // For satellite CTS test which to configure intent component with the necessary values.
    private boolean mChangeIntentComponent = false;
    private String mConfigSatelliteGatewayServicePackage = "";
    private String mConfigSatelliteCarrierRoamingEsosProvisionedClass = "";

    private boolean mIsNotificationShowing = false;
    private static final String OPEN_MESSAGE_BUTTON = "open_message_button";
    private static final String HOW_IT_WORKS_BUTTON = "how_it_works_button";
    private static final String ACTION_NOTIFICATION_CLICK = "action_notification_click";
    private static final String ACTION_NOTIFICATION_DISMISS = "action_notification_dismiss";
    private AtomicBoolean mOverrideNtnEligibility;
    private BroadcastReceiver
            mDefaultSmsSubscriptionChangedBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(
                            SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED)) {
                        plogd("Default SMS subscription changed");
                        sendRequestAsync(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION, null, null);
                    }
                }
            };

    // List of device states returned from DeviceStateManager to determine if running on a foldable
    // device.
    private List<DeviceState> mDeviceStates = new ArrayList();

    /**
     * @return The singleton instance of SatelliteController.
     */
    public static SatelliteController getInstance() {
        if (sInstance == null) {
            loge("SatelliteController was not yet initialized.");
        }
        return sInstance;
    }

    /**
     * Create the SatelliteController singleton instance.
     * @param context The Context to use to create the SatelliteController.
     * @param featureFlags The feature flag.
     */
    public static void make(@NonNull Context context, @NonNull FeatureFlags featureFlags) {
        if (sInstance == null) {
            HandlerThread satelliteThread = new HandlerThread(TAG);
            satelliteThread.start();
            sInstance = new SatelliteController(context, satelliteThread.getLooper(), featureFlags);
        }
    }

    /**
     * Create a SatelliteController to act as a backend service of
     * {@link android.telephony.satellite.SatelliteManager}
     *
     * @param context The Context for the SatelliteController.
     * @param looper The looper for the handler. It does not run on main thread.
     * @param featureFlags The feature flag.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteController(
            @NonNull Context context, @NonNull Looper looper, @NonNull FeatureFlags featureFlags) {
        super(looper);

        if (isSatellitePersistentLoggingEnabled(context, featureFlags)) {
            mPersistentLogger = new PersistentLogger(
                    DropBoxManagerLoggerBackend.getInstance(context));
        }

        mContext = context;
        mFeatureFlags = featureFlags;
        Phone phone = SatelliteServiceUtils.getPhone();
        synchronized (mSatellitePhoneLock) {
            mSatellitePhone = phone;
        }
        mCi = phone.mCi;
        mDSM = phone.getDeviceStateMonitor();
        // Create the SatelliteModemInterface singleton, which is used to manage connections
        // to the satellite service and HAL interface.
        mSatelliteModemInterface = SatelliteModemInterface.make(
                mContext, this, mFeatureFlags);
        mCountryDetector = TelephonyCountryDetector.getInstance(context, mFeatureFlags);
        mCountryDetector.registerForWifiConnectivityStateChanged(this,
                EVENT_WIFI_CONNECTIVITY_STATE_CHANGED, null);
        mTelecomManager = mContext.getSystemService(TelecomManager.class);

        // Create the PointingUIController singleton,
        // which is used to manage interactions with PointingUI app.
        mPointingAppController = PointingAppController.make(mContext, mFeatureFlags);

        // Create the SatelliteControllerMetrics to report controller metrics
        // should be called before making DatagramController
        mControllerMetricsStats = ControllerMetricsStats.make(mContext);
        mProvisionMetricsStats = ProvisionMetricsStats.getOrCreateInstance();
        mSessionMetricsStats = SessionMetricsStats.getInstance();
        mCarrierRoamingSatelliteControllerStats =
                CarrierRoamingSatelliteControllerStats.getOrCreateInstance();
        mSubscriptionManagerService = SubscriptionManagerService.getInstance();

        // Create the DatagramController singleton,
        // which is used to send and receive satellite datagrams.
        mDatagramController = DatagramController.make(
                mContext, looper, mFeatureFlags, mPointingAppController);

        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        synchronized (mIsRadioOnLock) {
            mIsRadioOn = phone.isRadioOn();
        }

        registerForPendingDatagramCount();
        registerForSatelliteModemStateChanged();
        registerForServiceStateChanged();
        mContentResolver = mContext.getContentResolver();
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);

        mBTWifiNFCSateReceiver = new BTWifiNFCStateReceiver();
        mUwbAdapterStateCallback = new UwbAdapterStateCallback();
        initializeSatelliteModeRadios();

        ContentObserver satelliteModeRadiosContentObserver = new ContentObserver(this) {
            @Override
            public void onChange(boolean selfChange) {
                initializeSatelliteModeRadios();
            }
        };
        if (mContentResolver != null) {
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.SATELLITE_MODE_RADIOS),
                    false, satelliteModeRadiosContentObserver);
        }

        mSatellitePlmnListFromOverlayConfig = readSatellitePlmnsFromOverlayConfig();
        updateSupportedSatelliteServicesForActiveSubscriptions();
        mCarrierConfigChangeListener =
                (slotIndex, subId, carrierId, specificCarrierId) ->
                        handleCarrierConfigChanged(slotIndex, subId, carrierId, specificCarrierId);
        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.registerCarrierConfigChangeListener(
                    new HandlerExecutor(new Handler(looper)), mCarrierConfigChangeListener);
        }

        mConfigDataUpdatedCallback = new ConfigProviderAdaptor.Callback() {
            @Override
            public void onChanged(@Nullable ConfigParser config) {
                SatelliteControllerHandlerRequest request =
                        new SatelliteControllerHandlerRequest(true,
                                SatelliteServiceUtils.getPhone());
                sendRequestAsync(EVENT_SATELLITE_CONFIG_DATA_UPDATED, request, null);
            }
        };
        TelephonyConfigUpdateInstallReceiver.getInstance()
                .registerCallback(Executors.newSingleThreadExecutor(), mConfigDataUpdatedCallback);

        mDSM.registerForSignalStrengthReportDecision(this, CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING,
                null);
        loadSatelliteSharedPreferences();
        mWaitTimeForSatelliteEnablingResponse = getWaitForSatelliteEnablingResponseTimeoutMillis();
        mDemoPointingAlignedDurationMillis = getDemoPointingAlignedDurationMillisFromResources();
        mDemoPointingNotAlignedDurationMillis =
                getDemoPointingNotAlignedDurationMillisFromResources();
        mSatelliteEmergencyModeDurationMillis =
                getSatelliteEmergencyModeDurationFromOverlayConfig(context);
        mEvaluateEsosProfilesPrioritizationDurationMillis =
                getEvaluateEsosProfilesPrioritizationDurationMillis();
        sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                mEvaluateEsosProfilesPrioritizationDurationMillis);

        SubscriptionManager subscriptionManager = mContext.getSystemService(
                SubscriptionManager.class);
        mSubscriptionsChangedListener = new SatelliteSubscriptionsChangedListener();
        if (subscriptionManager != null) {
            subscriptionManager.addOnSubscriptionsChangedListener(
                    new HandlerExecutor(new Handler(looper)), mSubscriptionsChangedListener);
        }
        registerDefaultSmsSubscriptionChangedBroadcastReceiver();
        updateSatelliteProvisionedStatePerSubscriberId();
        if (android.hardware.devicestate.feature.flags.Flags.deviceStatePropertyMigration()) {
            mDeviceStates = getSupportedDeviceStates();
        }
    }

    class SatelliteSubscriptionsChangedListener
            extends SubscriptionManager.OnSubscriptionsChangedListener {

        /**
         * Callback invoked when there is any change to any SubscriptionInfo.
         */
        @Override
        public void onSubscriptionsChanged() {
            handleSubscriptionsChanged();
        }
    }

    /**
     * Register a callback to get a updated satellite config data.
     * @param h Handler to notify
     * @param what msg.what when the message is delivered
     * @param obj AsyncResult.userObj when the message is delivered
     */
    public void registerForConfigUpdateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSatelliteConfigUpdateChangedRegistrants.add(r);
    }

    /**
     * Unregister a callback to get a updated satellite config data.
     * @param h Handler to notify
     */
    public void unregisterForConfigUpdateChanged(Handler h) {
        mSatelliteConfigUpdateChangedRegistrants.remove(h);
    }

    /**
     * Get satelliteConfig from SatelliteConfigParser
     */
    public SatelliteConfig getSatelliteConfig() {
        SatelliteConfigParser satelliteConfigParser = getSatelliteConfigParser();
        if (satelliteConfigParser == null) {
            Log.d(TAG, "satelliteConfigParser is not ready");
            return null;
        }
        return satelliteConfigParser.getConfig();
    }

    /**
     * Get SatelliteConfigParser from TelephonyConfigUpdateInstallReceiver
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteConfigParser getSatelliteConfigParser() {
        return (SatelliteConfigParser) TelephonyConfigUpdateInstallReceiver
                .getInstance().getConfigParser(DOMAIN_SATELLITE);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void initializeSatelliteModeRadios() {
        if (mContentResolver != null) {
            IntentFilter radioStateIntentFilter = new IntentFilter();

            synchronized (mRadioStateLock) {
                // Initialize radio states to default value
                mDisableBTOnSatelliteEnabled = false;
                mDisableNFCOnSatelliteEnabled = false;
                mDisableWifiOnSatelliteEnabled = false;
                mDisableUWBOnSatelliteEnabled = false;

                mBTStateEnabled = false;
                mNfcStateEnabled = false;
                mWifiStateEnabled = false;
                mUwbStateEnabled = false;

                // Read satellite mode radios from settings
                String satelliteModeRadios = Settings.Global.getString(mContentResolver,
                        Settings.Global.SATELLITE_MODE_RADIOS);
                if (satelliteModeRadios == null) {
                    ploge("initializeSatelliteModeRadios: satelliteModeRadios is null");
                    return;
                }
                plogd("Radios To be checked when satellite is on: " + satelliteModeRadios);

                if (satelliteModeRadios.contains(Settings.Global.RADIO_BLUETOOTH)) {
                    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter != null) {
                        mDisableBTOnSatelliteEnabled = true;
                        mBTStateEnabled = bluetoothAdapter.isEnabled();
                        radioStateIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                    }
                }

                if (satelliteModeRadios.contains(Settings.Global.RADIO_NFC)) {
                    Context applicationContext = mContext.getApplicationContext();
                    NfcAdapter nfcAdapter = null;
                    if (applicationContext != null) {
                        nfcAdapter = NfcAdapter.getDefaultAdapter(mContext.getApplicationContext());
                    }
                    if (nfcAdapter != null) {
                        mDisableNFCOnSatelliteEnabled = true;
                        mNfcStateEnabled = nfcAdapter.isEnabled();
                        radioStateIntentFilter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
                    }
                }

                if (satelliteModeRadios.contains(Settings.Global.RADIO_WIFI)) {
                    WifiManager wifiManager = mContext.getSystemService(WifiManager.class);
                    if (wifiManager != null) {
                        mDisableWifiOnSatelliteEnabled = true;
                        mWifiStateEnabled = wifiManager.isWifiEnabled();
                        radioStateIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                    }
                }

                try {
                    // Unregister receiver before registering it.
                    mContext.unregisterReceiver(mBTWifiNFCSateReceiver);
                } catch (IllegalArgumentException e) {
                    plogd("initializeSatelliteModeRadios: unregisterReceiver, e=" + e);
                }
                mContext.registerReceiver(mBTWifiNFCSateReceiver, radioStateIntentFilter);

                if (satelliteModeRadios.contains(Settings.Global.RADIO_UWB)) {
                    UwbManager uwbManager = mContext.getSystemService(UwbManager.class);
                    if (uwbManager != null) {
                        mDisableUWBOnSatelliteEnabled = true;
                        mUwbStateEnabled = uwbManager.isUwbEnabled();
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            // Unregister callback before registering it.
                            uwbManager.unregisterAdapterStateCallback(mUwbAdapterStateCallback);
                            uwbManager.registerAdapterStateCallback(mContext.getMainExecutor(),
                                    mUwbAdapterStateCallback);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                }

                plogd("mDisableBTOnSatelliteEnabled: " + mDisableBTOnSatelliteEnabled
                        + " mDisableNFCOnSatelliteEnabled: " + mDisableNFCOnSatelliteEnabled
                        + " mDisableWifiOnSatelliteEnabled: " + mDisableWifiOnSatelliteEnabled
                        + " mDisableUWBOnSatelliteEnabled: " + mDisableUWBOnSatelliteEnabled);

                plogd("mBTStateEnabled: " + mBTStateEnabled
                        + " mNfcStateEnabled: " + mNfcStateEnabled
                        + " mWifiStateEnabled: " + mWifiStateEnabled
                        + " mUwbStateEnabled: " + mUwbStateEnabled);
            }
        }
    }

    protected class UwbAdapterStateCallback implements UwbManager.AdapterStateCallback {

        public String toString(int state) {
            switch (state) {
                case UwbManager.AdapterStateCallback.STATE_DISABLED:
                    return "Disabled";

                case UwbManager.AdapterStateCallback.STATE_ENABLED_INACTIVE:
                    return "Inactive";

                case UwbManager.AdapterStateCallback.STATE_ENABLED_ACTIVE:
                    return "Active";

                default:
                    return "";
            }
        }

        @Override
        public void onStateChanged(int state, int reason) {
            plogd("UwbAdapterStateCallback#onStateChanged() called, state = " + toString(state));
            plogd("Adapter state changed reason " + String.valueOf(reason));
            synchronized (mRadioStateLock) {
                if (state == UwbManager.AdapterStateCallback.STATE_DISABLED) {
                    mUwbStateEnabled = false;
                    evaluateToSendSatelliteEnabledSuccess();
                } else {
                    mUwbStateEnabled = true;
                }
                plogd("mUwbStateEnabled: " + mUwbStateEnabled);
            }
        }
    }

    protected class BTWifiNFCStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                plogd("BTWifiNFCStateReceiver NULL action for intent " + intent);
                return;
            }

            switch (action) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    synchronized (mRadioStateLock) {
                        boolean currentBTStateEnabled = mBTStateEnabled;
                        if (btState == BluetoothAdapter.STATE_OFF) {
                            mBTStateEnabled = false;
                            evaluateToSendSatelliteEnabledSuccess();
                        } else if (btState == BluetoothAdapter.STATE_ON) {
                            mBTStateEnabled = true;
                        }
                        if (currentBTStateEnabled != mBTStateEnabled) {
                            plogd("mBTStateEnabled=" + mBTStateEnabled);
                        }
                    }
                    break;

                case NfcAdapter.ACTION_ADAPTER_STATE_CHANGED:
                    int nfcState = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1);
                    synchronized (mRadioStateLock) {
                        boolean currentNfcStateEnabled = mNfcStateEnabled;
                        if (nfcState == NfcAdapter.STATE_ON) {
                            mNfcStateEnabled = true;
                        } else if (nfcState == NfcAdapter.STATE_OFF) {
                            mNfcStateEnabled = false;
                            evaluateToSendSatelliteEnabledSuccess();
                        }
                        if (currentNfcStateEnabled != mNfcStateEnabled) {
                            plogd("mNfcStateEnabled=" + mNfcStateEnabled);
                        }
                    }
                    break;

                case WifiManager.WIFI_STATE_CHANGED_ACTION:
                    int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    synchronized (mRadioStateLock) {
                        boolean currentWifiStateEnabled = mWifiStateEnabled;
                        if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                            mWifiStateEnabled = true;
                        } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                            mWifiStateEnabled = false;
                            evaluateToSendSatelliteEnabledSuccess();
                        }
                        if (currentWifiStateEnabled != mWifiStateEnabled) {
                            plogd("mWifiStateEnabled=" + mWifiStateEnabled);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static final class SatelliteControllerHandlerRequest {
        /** The argument to use for the request */
        public @NonNull Object argument;
        /** The caller needs to specify the phone to be used for the request */
        public @NonNull Phone phone;
        /** The result of the request that is run on the main thread */
        public @Nullable Object result;

        SatelliteControllerHandlerRequest(Object argument, Phone phone) {
            this.argument = argument;
            this.phone = phone;
        }
    }

    private static final class RequestSatelliteEnabledArgument {
        public boolean enableSatellite;
        public boolean enableDemoMode;
        public boolean isEmergency;
        @NonNull public Consumer<Integer> callback;
        public long requestId;

        RequestSatelliteEnabledArgument(boolean enableSatellite, boolean enableDemoMode,
                boolean isEmergency, Consumer<Integer> callback) {
            this.enableSatellite = enableSatellite;
            this.enableDemoMode = enableDemoMode;
            this.isEmergency = isEmergency;
            this.callback = callback;
            this.requestId = sNextSatelliteEnableRequestId.getAndUpdate(
                    n -> ((n + 1) % Long.MAX_VALUE));
        }
    }

    private static final class RequestHandleSatelliteAttachRestrictionForCarrierArgument {
        public int subId;
        @SatelliteManager.SatelliteCommunicationRestrictionReason
        public int reason;
        @NonNull public Consumer<Integer> callback;

        RequestHandleSatelliteAttachRestrictionForCarrierArgument(int subId,
                @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
                Consumer<Integer> callback) {
            this.subId = subId;
            this.reason = reason;
            this.callback = callback;
        }
    }

    private static final class ProvisionSatelliteServiceArgument {
        @NonNull public String token;
        @NonNull public byte[] provisionData;
        @NonNull public Consumer<Integer> callback;
        public int subId;

        ProvisionSatelliteServiceArgument(String token, byte[] provisionData,
                Consumer<Integer> callback, int subId) {
            this.token = token;
            this.provisionData = provisionData;
            this.callback = callback;
            this.subId = subId;
        }
    }

    /**
     * Arguments to send to SatelliteTransmissionUpdate registrants
     */
    public static final class SatelliteTransmissionUpdateArgument {
        @NonNull public Consumer<Integer> errorCallback;
        @NonNull public ISatelliteTransmissionUpdateCallback callback;
        public int subId;

        SatelliteTransmissionUpdateArgument(Consumer<Integer> errorCallback,
                ISatelliteTransmissionUpdateCallback callback, int subId) {
            this.errorCallback = errorCallback;
            this.callback = callback;
            this.subId = subId;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        SatelliteControllerHandlerRequest request;
        Message onCompleted;
        AsyncResult ar;

        switch(msg.what) {
            case CMD_START_SATELLITE_TRANSMISSION_UPDATES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE, request);
                mPointingAppController.startSatelliteTransmissionUpdates(onCompleted);
                break;
            }

            case EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE: {
                handleStartSatelliteTransmissionUpdatesDone((AsyncResult) msg.obj);
                break;
            }

            case CMD_STOP_SATELLITE_TRANSMISSION_UPDATES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE, request);
                mPointingAppController.stopSatelliteTransmissionUpdates(onCompleted);
                break;
            }

            case EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "stopSatelliteTransmissionUpdates");
                ((Consumer<Integer>) request.argument).accept(error);
                break;
            }

            case CMD_PROVISION_SATELLITE_SERVICE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                ProvisionSatelliteServiceArgument argument =
                        (ProvisionSatelliteServiceArgument) request.argument;
                if (mSatelliteProvisionCallbacks.containsKey(argument.subId)) {
                    argument.callback.accept(
                            SatelliteManager.SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS);
                    notifyRequester(request);
                    break;
                }
                mSatelliteProvisionCallbacks.put(argument.subId, argument.callback);
                // Log the current time for provision triggered
                mProvisionMetricsStats.setProvisioningStartTime();
                Message provisionSatelliteServiceDoneEvent = this.obtainMessage(
                        EVENT_PROVISION_SATELLITE_SERVICE_DONE,
                        new AsyncResult(request, SATELLITE_RESULT_SUCCESS, null));
                provisionSatelliteServiceDoneEvent.sendToTarget();
                break;
            }

            case EVENT_PROVISION_SATELLITE_SERVICE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "provisionSatelliteService");
                handleEventProvisionSatelliteServiceDone(
                        (ProvisionSatelliteServiceArgument) request.argument, errorCode);
                notifyRequester(request);
                break;
            }

            case CMD_DEPROVISION_SATELLITE_SERVICE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                ProvisionSatelliteServiceArgument argument =
                        (ProvisionSatelliteServiceArgument) request.argument;
                if (argument.callback != null) {
                    mProvisionMetricsStats.setProvisioningStartTime();
                }
                Message deprovisionSatelliteServiceDoneEvent = this.obtainMessage(
                        EVENT_DEPROVISION_SATELLITE_SERVICE_DONE,
                        new AsyncResult(request, SATELLITE_RESULT_SUCCESS, null));
                deprovisionSatelliteServiceDoneEvent.sendToTarget();
                break;
            }

            case EVENT_DEPROVISION_SATELLITE_SERVICE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "deprovisionSatelliteService");
                handleEventDeprovisionSatelliteServiceDone(
                        (ProvisionSatelliteServiceArgument) request.argument, errorCode);
                break;
            }

            case CMD_SET_SATELLITE_ENABLED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                handleSatelliteEnabled(request);
                break;
            }

            case EVENT_SET_SATELLITE_ENABLED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;
                int error =  SatelliteServiceUtils.getSatelliteError(ar, "setSatelliteEnabled");
                plogd("EVENT_SET_SATELLITE_ENABLED_DONE = " + error);

                /*
                 * The timer to wait for EVENT_SET_SATELLITE_ENABLED_DONE might have expired and
                 * thus the request resources might have been cleaned up.
                 */
                if (!shouldProcessEventSetSatelliteEnabledDone(argument)) {
                    plogw("The request ID=" + argument.requestId + ", enableSatellite="
                            + argument.enableSatellite + " was already processed");
                    return;
                }
                if (shouldStopWaitForEnableResponseTimer(argument)) {
                    stopWaitForSatelliteEnablingResponseTimer(argument);
                } else {
                    plogd("Still waiting for the OFF state from modem");
                }

                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (argument.enableSatellite) {
                        synchronized (mSatelliteEnabledRequestLock) {
                            mWaitingForRadioDisabled = true;
                            setDemoModeEnabled(argument.enableDemoMode);
                        }
                        // TODO (b/361139260): Start a timer to wait for other radios off
                        setSettingsKeyForSatelliteMode(SATELLITE_MODE_ENABLED_TRUE);
                        setSettingsKeyToAllowDeviceRotation(SATELLITE_MODE_ENABLED_TRUE);
                        evaluateToSendSatelliteEnabledSuccess();
                    } else {
                        // Unregister importance listener for PointingUI when satellite is disabled
                        if (mNeedsSatellitePointing) {
                            mPointingAppController.removeListenerForPointingUI();
                        }
                        synchronized (mSatelliteEnabledRequestLock) {
                            if (!mWaitingForSatelliteModemOff) {
                                moveSatelliteToOffStateAndCleanUpResources(
                                        SATELLITE_RESULT_SUCCESS);
                            }
                            mWaitingForDisableSatelliteModemResponse = false;
                        }
                    }
                    // Request NTN signal strength report when satellite enabled or disabled done.
                    mLatestRequestedStateForNtnSignalStrengthReport.set(argument.enableSatellite);
                    updateNtnSignalStrengthReporting(argument.enableSatellite);
                } else {
                    if (argument.enableSatellite) {
                        /* Framework need to abort the enable attributes update request if any since
                         * modem failed to enable satellite.
                         */
                        abortSatelliteEnableAttributesUpdateRequest(
                                SATELLITE_RESULT_REQUEST_ABORTED);
                        resetSatelliteEnabledRequest();
                    } else {
                        resetSatelliteDisabledRequest();
                    }
                    notifyEnablementFailedToSatelliteSessionController(argument.enableSatellite);
                    // If Satellite enable/disable request returned Error, no need to wait for radio
                    argument.callback.accept(error);
                }

                if (argument.enableSatellite) {
                    mSessionMetricsStats.setInitializationResult(error)
                            .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                            .setInitializationProcessingTime(
                                    System.currentTimeMillis() - mSessionProcessingTimeStamp)
                            .setIsDemoMode(mIsDemoModeEnabled)
                            .setCarrierId(getSatelliteCarrierId());
                    mSessionProcessingTimeStamp = 0;

                    if (error == SATELLITE_RESULT_SUCCESS) {
                        mControllerMetricsStats.onSatelliteEnabled();
                        mControllerMetricsStats.reportServiceEnablementSuccessCount();
                    } else {
                        mSessionMetricsStats.reportSessionMetrics();
                        mSessionStartTimeStamp = 0;
                        mControllerMetricsStats.reportServiceEnablementFailCount();
                    }
                } else {
                    mSessionMetricsStats.setTerminationResult(error)
                            .setTerminationProcessingTime(System.currentTimeMillis()
                                    - mSessionProcessingTimeStamp)
                            .setSessionDurationSec(calculateSessionDurationTimeSec())
                            .reportSessionMetrics();
                    mSessionStartTimeStamp = 0;
                    mSessionProcessingTimeStamp = 0;
                    mControllerMetricsStats.onSatelliteDisabled();
                    handlePersistentLoggingOnSessionEnd(mIsEmergency);
                    synchronized (mSatelliteEnabledRequestLock) {
                        mWaitingForDisableSatelliteModemResponse = false;
                    }
                }
                break;
            }

            case EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT: {
                handleEventWaitForSatelliteEnablingResponseTimedOut(
                        (RequestSatelliteEnabledArgument) msg.obj);
                break;
            }

            case CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;

                if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
                    plogd("UpdateEnableAttributes: carrierRoamingNbIotNtn flag is disabled");
                    sendErrorAndReportSessionMetrics(
                            SatelliteManager.SATELLITE_RESULT_INVALID_ARGUMENTS, argument.callback);
                    synchronized (mSatelliteEnabledRequestLock) {
                        mSatelliteEnableAttributesUpdateRequest = null;
                    }
                    break;
                }

                synchronized (mSatelliteEnabledRequestLock) {
                    if (mSatelliteEnabledRequest != null) {
                        plogd("UpdateEnableAttributes: Satellite is being enabled. Need to "
                                + "wait until enable complete before updating attributes");
                        break;
                    }
                    if (isSatelliteBeingDisabled()) {
                        plogd("UpdateEnableAttributes: Satellite is being disabled. Aborting the "
                                + "enable attributes update request");
                        mSatelliteEnableAttributesUpdateRequest = null;
                        argument.callback.accept(SATELLITE_RESULT_REQUEST_ABORTED);
                        break;
                    }
                }
                onCompleted = obtainMessage(EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE, request);
                SatelliteModemEnableRequestAttributes enableRequestAttributes =
                    createModemEnableRequest(argument);
                if (enableRequestAttributes == null) {
                    plogw("UpdateEnableAttributes: enableRequestAttributes is null");
                    sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE,
                        argument.callback);
                    synchronized (mSatelliteEnabledRequestLock) {
                        mSatelliteEnableAttributesUpdateRequest = null;
                    }
                    break;
                }
                mSatelliteModemInterface.requestSatelliteEnabled(
                        enableRequestAttributes, onCompleted);
                startWaitForUpdateSatelliteEnableAttributesResponseTimer(argument);
                break;
            }

            case EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;
                int error =  SatelliteServiceUtils.getSatelliteError(
                        ar, "updateSatelliteEnableAttributes");
                plogd("EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE = " + error);

                /*
                 * The timer to wait for EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE might have
                 * expired and thus the request resources might have been cleaned up.
                 */
                if (!shouldProcessEventUpdateSatelliteEnableAttributesDone(argument)) {
                    plogw("The update request ID=" + argument.requestId + " was already processed");
                    return;
                }
                stopWaitForUpdateSatelliteEnableAttributesResponseTimer(argument);

                if (error == SATELLITE_RESULT_SUCCESS) {
                    setDemoModeEnabled(argument.enableDemoMode);
                    setEmergencyMode(argument.isEmergency);
                }
                synchronized (mSatelliteEnabledRequestLock) {
                    mSatelliteEnableAttributesUpdateRequest = null;
                }
                argument.callback.accept(error);
                break;
            }

            case EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT: {
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) msg.obj;
                plogw("Timed out to wait for the response from the modem for the request to "
                        + "update satellite enable attributes, request ID = " + argument.requestId);
                synchronized (mSatelliteEnabledRequestLock) {
                    mSatelliteEnableAttributesUpdateRequest = null;
                }
                argument.callback.accept(SATELLITE_RESULT_MODEM_TIMEOUT);
                break;
            }

            case CMD_IS_SATELLITE_ENABLED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_ENABLED_DONE, request);
                mSatelliteModemInterface.requestIsSatelliteEnabled(onCompleted);
                break;
            }

            case EVENT_IS_SATELLITE_ENABLED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "isSatelliteEnabled");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("isSatelliteEnabled: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean enabled = ((int[]) ar.result)[0] == 1;
                        if (DBG) plogd("isSatelliteEnabled: " + enabled);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_ENABLED, enabled);
                        updateSatelliteEnabledState(enabled, "EVENT_IS_SATELLITE_ENABLED_DONE");
                    }
                } else if (error == SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED) {
                    updateSatelliteSupportedState(false);
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_IS_SATELLITE_SUPPORTED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_SUPPORTED_DONE, request);
                mSatelliteModemInterface.requestIsSatelliteSupported(onCompleted);
                break;
            }

            case EVENT_IS_SATELLITE_SUPPORTED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar, "isSatelliteSupported");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("isSatelliteSupported: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean supported = (boolean) ar.result;
                        plogd("isSatelliteSupported: " + supported);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, supported);
                        updateSatelliteSupportedState(supported);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_GET_SATELLITE_CAPABILITIES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_GET_SATELLITE_CAPABILITIES_DONE, request);
                mSatelliteModemInterface.requestSatelliteCapabilities(onCompleted);
                break;
            }

            case EVENT_GET_SATELLITE_CAPABILITIES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "getSatelliteCapabilities");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("getSatelliteCapabilities: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        SatelliteCapabilities capabilities = (SatelliteCapabilities) ar.result;
                        synchronized (mNeedsSatellitePointingLock) {
                            mNeedsSatellitePointing = capabilities.isPointingRequired();
                        }
                        if (DBG) plogd("getSatelliteCapabilities: " + capabilities);
                        bundle.putParcelable(SatelliteManager.KEY_SATELLITE_CAPABILITIES,
                                capabilities);
                        synchronized (mSatelliteCapabilitiesLock) {
                            mSatelliteCapabilities = capabilities;
                        }
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_GET_TIME_SATELLITE_NEXT_VISIBLE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE,
                        request);
                mSatelliteModemInterface.requestTimeForNextSatelliteVisibility(onCompleted);
                break;
            }

            case EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error = SatelliteServiceUtils.getSatelliteError(ar,
                        "requestTimeForNextSatelliteVisibility");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("requestTimeForNextSatelliteVisibility: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        int nextVisibilityDuration = ((int[]) ar.result)[0];
                        if (DBG) {
                            plogd("requestTimeForNextSatelliteVisibility: "
                                    + nextVisibilityDuration);
                        }
                        bundle.putInt(SatelliteManager.KEY_SATELLITE_NEXT_VISIBILITY,
                                nextVisibilityDuration);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case EVENT_RADIO_STATE_CHANGED: {
                synchronized (mIsRadioOnLock) {
                    logd("EVENT_RADIO_STATE_CHANGED: radioState=" + mCi.getRadioState());
                    if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON) {
                        mIsRadioOn = true;
                    } else if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF) {
                        resetCarrierRoamingSatelliteModeParams();
                        synchronized (mIsRadioOnLock) {
                            if (mRadioOffRequested) {
                                logd("EVENT_RADIO_STATE_CHANGED: set mIsRadioOn to false");
                                stopWaitForCellularModemOffTimer();
                                mIsRadioOn = false;
                                mRadioOffRequested = false;
                            }
                        }
                    }
                }

                if (mCi.getRadioState() != TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                    if (mSatelliteModemInterface.isSatelliteServiceConnected()) {
                        synchronized (mIsSatelliteSupportedLock) {
                            if (mIsSatelliteSupported == null || !mIsSatelliteSupported) {
                                ResultReceiver receiver = new ResultReceiver(this) {
                                    @Override
                                    protected void onReceiveResult(
                                            int resultCode, Bundle resultData) {
                                        plogd("onRadioStateChanged.requestIsSatelliteSupported: "
                                                + "resultCode=" + resultCode
                                                + ", resultData=" + resultData);
                                    }
                                };
                                sendRequestAsync(CMD_IS_SATELLITE_SUPPORTED, receiver, null);
                            }
                        }
                    }
                }
                break;
            }

            case CMD_IS_SATELLITE_PROVISIONED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                Message isProvisionedDoneEvent = this.obtainMessage(
                        EVENT_IS_SATELLITE_PROVISIONED_DONE,
                        new AsyncResult(request, SATELLITE_RESULT_SUCCESS, null));
                isProvisionedDoneEvent.sendToTarget();
                break;
            }

            case EVENT_IS_SATELLITE_PROVISIONED_DONE: {
                handleIsSatelliteProvisionedDoneEvent((AsyncResult) msg.obj);
                break;
            }

            case EVENT_PENDING_DATAGRAMS:
                plogd("Received EVENT_PENDING_DATAGRAMS");
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        plogd("pollPendingSatelliteDatagram result: " + result);
                    }
                };
                pollPendingDatagrams(internalCallback);
                break;

            case EVENT_SATELLITE_MODEM_STATE_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_SATELLITE_MODEM_STATE_CHANGED: result is null");
                } else {
                    handleEventSatelliteModemStateChanged((int) ar.result);
                }
                break;

            case EVENT_SET_SATELLITE_PLMN_INFO_DONE:
                handleSetSatellitePlmnInfoDoneEvent(msg);
                break;

            case CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE: {
                plogd("CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE");
                request = (SatelliteControllerHandlerRequest) msg.obj;
                handleRequestSatelliteAttachRestrictionForCarrierCmd(request);
                break;
            }

            case EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestHandleSatelliteAttachRestrictionForCarrierArgument argument =
                        (RequestHandleSatelliteAttachRestrictionForCarrierArgument)
                                request.argument;
                int subId = argument.subId;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "requestSetSatelliteEnabledForCarrier");

                synchronized (mIsSatelliteEnabledLock) {
                    if (error == SATELLITE_RESULT_SUCCESS) {
                        boolean enableSatellite = mSatelliteAttachRestrictionForCarrierArray
                                .getOrDefault(argument.subId, Collections.emptySet()).isEmpty();
                        mIsSatelliteAttachEnabledForCarrierArrayPerSub.put(subId, enableSatellite);
                    } else {
                        mIsSatelliteAttachEnabledForCarrierArrayPerSub.remove(subId);
                    }
                }

                argument.callback.accept(error);
                break;
            }

            case CMD_REQUEST_NTN_SIGNAL_STRENGTH: {
                plogd("CMD_REQUEST_NTN_SIGNAL_STRENGTH");
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE, request);
                mSatelliteModemInterface.requestNtnSignalStrength(onCompleted);
                break;
            }

            case EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                ResultReceiver result = (ResultReceiver) request.argument;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "requestNtnSignalStrength");
                if (errorCode == SATELLITE_RESULT_SUCCESS) {
                    NtnSignalStrength ntnSignalStrength = (NtnSignalStrength) ar.result;
                    if (ntnSignalStrength != null) {
                        synchronized (mNtnSignalsStrengthLock) {
                            mNtnSignalStrength = ntnSignalStrength;
                        }
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(KEY_NTN_SIGNAL_STRENGTH, ntnSignalStrength);
                        result.send(SATELLITE_RESULT_SUCCESS, bundle);
                    } else {
                        synchronized (mNtnSignalsStrengthLock) {
                            if (mNtnSignalStrength.getLevel() != NTN_SIGNAL_STRENGTH_NONE) {
                                mNtnSignalStrength = new NtnSignalStrength(
                                        NTN_SIGNAL_STRENGTH_NONE);
                            }
                        }
                        ploge("EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE: ntnSignalStrength is null");
                        result.send(SatelliteManager.SATELLITE_RESULT_REQUEST_FAILED, null);
                    }
                } else {
                    synchronized (mNtnSignalsStrengthLock) {
                        if (mNtnSignalStrength.getLevel() != NTN_SIGNAL_STRENGTH_NONE) {
                            mNtnSignalStrength = new NtnSignalStrength(NTN_SIGNAL_STRENGTH_NONE);
                        }
                    }
                    result.send(errorCode, null);
                }
                break;
            }

            case EVENT_NTN_SIGNAL_STRENGTH_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_NTN_SIGNAL_STRENGTH_CHANGED: result is null");
                } else {
                    handleEventNtnSignalStrengthChanged((NtnSignalStrength) ar.result);
                }
                break;
            }

            case CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING: {
                ar = (AsyncResult) msg.obj;
                boolean shouldReport = (boolean) ar.result;
                if (DBG) {
                    plogd("CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING: shouldReport=" + shouldReport);
                }
                handleCmdUpdateNtnSignalStrengthReporting(shouldReport);
                break;
            }

            case EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                boolean shouldReport = (boolean) request.argument;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE: shouldReport="
                                + shouldReport);
                if (errorCode == SATELLITE_RESULT_SUCCESS) {
                    mIsModemEnabledReportingNtnSignalStrength.set(shouldReport);
                    if (mLatestRequestedStateForNtnSignalStrengthReport.get()
                            != mIsModemEnabledReportingNtnSignalStrength.get()) {
                        logd("mLatestRequestedStateForNtnSignalStrengthReport does not match with "
                                + "mIsModemEnabledReportingNtnSignalStrength");
                        updateNtnSignalStrengthReporting(
                                mLatestRequestedStateForNtnSignalStrengthReport.get());
                    }
                } else {
                    loge(((boolean) request.argument ? "startSendingNtnSignalStrength"
                            : "stopSendingNtnSignalStrength") + "returns " + errorCode);
                }
                break;
            }

            case EVENT_SERVICE_STATE_CHANGED: {
                handleEventServiceStateChanged();
                break;
            }

            case EVENT_SATELLITE_CAPABILITIES_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_SATELLITE_CAPABILITIES_CHANGED: result is null");
                } else {
                    handleEventSatelliteCapabilitiesChanged((SatelliteCapabilities) ar.result);
                }
                break;
            }

            case EVENT_SATELLITE_SUPPORTED_STATE_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_SATELLITE_SUPPORTED_STATE_CHANGED: result is null");
                } else {
                    handleEventSatelliteSupportedStateChanged((boolean) ar.result);
                }
                break;
            }

            case EVENT_SATELLITE_CONFIG_DATA_UPDATED: {
                handleEventConfigDataUpdated();
                mSatelliteConfigUpdateChangedRegistrants.notifyRegistrants();
                break;
            }

            case EVENT_NOTIFY_NTN_HYSTERESIS_TIMED_OUT: {
                int phoneId = (int) msg.obj;
                Phone phone = PhoneFactory.getPhone(phoneId);
                updateLastNotifiedNtnModeAndNotify(phone);
                break;
            }

            case EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT: {
                synchronized (mSatellitePhoneLock) {
                    mNtnEligibilityHysteresisTimedOut = true;
                    boolean eligible = isCarrierRoamingNtnEligible(mSatellitePhone);
                    plogd("EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT:"
                            + " isCarrierRoamingNtnEligible=" + eligible);
                    if (eligible) {
                        requestIsSatelliteAllowedForCurrentLocation();
                    }
                }
                break;
            }

            case CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION: {
                evaluateESOSProfilesPrioritization();
                break;
            }

            case CMD_UPDATE_PROVISION_SATELLITE_TOKEN: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                RequestProvisionSatelliteArgument argument =
                        (RequestProvisionSatelliteArgument) request.argument;
                onCompleted = obtainMessage(EVENT_UPDATE_PROVISION_SATELLITE_TOKEN_DONE, request);
                boolean provisionChanged = updateSatelliteSubscriptionProvisionState(
                        argument.mSatelliteSubscriberInfoList, argument.mProvisioned);
                selectBindingSatelliteSubscription();
                int subId = getSelectedSatelliteSubId();
                SubscriptionInfo subscriptionInfo =
                    mSubscriptionManagerService.getSubscriptionInfo(subId);
                if (subscriptionInfo == null) {
                    logw("updateSatelliteToken subId=" + subId + " is not found");
                } else {
                    String iccId = subscriptionInfo.getIccId();
                    argument.setIccId(iccId);
                    synchronized (mSatelliteTokenProvisionedLock) {
                        if (!iccId.equals(mLastConfiguredIccId)) {
                            logd("updateSatelliteSubscription subId=" + subId
                                    + ", iccId=" + iccId + " to modem");
                            mSatelliteModemInterface.updateSatelliteSubscription(
                                iccId, onCompleted);
                        }
                    }
                }
                if (provisionChanged) {
                    handleEventSatelliteSubscriptionProvisionStateChanged();
                }

                // The response is sent immediately because the ICCID has already been
                // delivered to the modem.
                Bundle bundle = new Bundle();
                bundle.putBoolean(
                        argument.mProvisioned ? SatelliteManager.KEY_PROVISION_SATELLITE_TOKENS
                                : SatelliteManager.KEY_DEPROVISION_SATELLITE_TOKENS, true);
                argument.mResult.send(SATELLITE_RESULT_SUCCESS, bundle);
                break;
            }

            case EVENT_UPDATE_PROVISION_SATELLITE_TOKEN_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestProvisionSatelliteArgument argument =
                        (RequestProvisionSatelliteArgument) request.argument;
                int error = SatelliteServiceUtils.getSatelliteError(ar,
                        "updateSatelliteSubscription");
                if (error == SATELLITE_RESULT_SUCCESS) {
                    synchronized (mSatelliteTokenProvisionedLock) {
                        mLastConfiguredIccId = argument.getIccId();
                    }
                }
                logd("updateSatelliteSubscription result=" + error);
                break;
            }

            case EVENT_WIFI_CONNECTIVITY_STATE_CHANGED: {
                synchronized (mIsWifiConnectedLock) {
                    ar = (AsyncResult) msg.obj;
                    mIsWifiConnected = (boolean) ar.result;
                    plogd("EVENT_WIFI_CONNECTIVITY_STATE_CHANGED: mIsWifiConnected="
                            + mIsWifiConnected);
                    handleStateChangedForCarrierRoamingNtnEligibility();
                }
                break;
            }
            case EVENT_SATELLITE_ACCESS_RESTRICTION_CHECKING_RESULT: {
                handleSatelliteAccessRestrictionCheckingResult((boolean) msg.obj);
                break;
            }

            case EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT: {
                plogw("Timed out to wait for cellular modem OFF state");
                synchronized (mIsRadioOnLock) {
                    mRadioOffRequested = false;
                }
                break;
            }

            case EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT: {
                // TODO: b/366329504 report carrier roaming metrics for multiple subscription IDs.
                synchronized (mSupportedSatelliteServicesLock) {
                    int defaultSubId = mSubscriptionManagerService.getDefaultSubId();
                    boolean isEntitled = mSatelliteEntitlementStatusPerCarrier.get(defaultSubId,
                            false);
                    mCarrierRoamingSatelliteControllerStats.reportIsDeviceEntitled(isEntitled);
                }
                sendMessageDelayed(obtainMessage(
                                EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT),
                        WAIT_FOR_REPORT_ENTITLED_MERTICS_TIMEOUT_MILLIS);
                break;
            }

            case EVENT_SATELLITE_REGISTRATION_FAILURE:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    loge("EVENT_SATELLITE_REGISTRATION_FAILURE: result is null");
                } else {
                    handleEventSatelliteRegistrationFailure((int) ar.result);
                }
                break;

            default:
                Log.w(TAG, "SatelliteControllerHandler: unexpected message code: " +
                        msg.what);
                break;
        }
    }

    private static final class RequestProvisionSatelliteArgument {
        public List<SatelliteSubscriberInfo> mSatelliteSubscriberInfoList;
        @NonNull
        public ResultReceiver mResult;
        public long mRequestId;
        public String mIccId;
        public boolean mProvisioned;

        RequestProvisionSatelliteArgument(List<SatelliteSubscriberInfo> satelliteSubscriberInfoList,
                ResultReceiver result, boolean provisioned) {
            this.mSatelliteSubscriberInfoList = satelliteSubscriberInfoList;
            this.mResult = result;
            this.mProvisioned = provisioned;
            this.mRequestId = sNextSatelliteEnableRequestId.getAndUpdate(
                    n -> ((n + 1) % Long.MAX_VALUE));
        }

        public void setIccId(String iccId) {
            mIccId = iccId;
        }

        public String getIccId() {
            return mIccId;
        }
    }

    private void handleEventConfigDataUpdated() {
        updateSupportedSatelliteServicesForActiveSubscriptions();
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null) {
            for (int subId : activeSubIds) {
                processNewCarrierConfigData(subId);
            }
        } else {
            ploge("updateSupportedSatelliteServicesForActiveSubscriptions: "
                    + "activeSubIds is null");
        }
    }

    private void notifyRequester(SatelliteControllerHandlerRequest request) {
        synchronized (request) {
            request.notifyAll();
        }
    }

    /**
     * Request to enable or disable the satellite modem and demo mode. If the satellite modem is
     * enabled, this will also disable the cellular modem, and if the satellite modem is disabled,
     * this will also re-enable the cellular modem.
     *
     * @param enableSatellite {@code true} to enable the satellite modem and
     *                        {@code false} to disable.
     * @param enableDemoMode {@code true} to enable demo mode and {@code false} to disable.
     * @param isEmergency {@code true} to enable emergency mode, {@code false} otherwise.
     * @param callback The callback to get the error code of the request.
     */
    public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            boolean isEmergency, @NonNull IIntegerConsumer callback) {
        plogd("requestSatelliteEnabled enableSatellite: " + enableSatellite
                + " enableDemoMode: " + enableDemoMode + " isEmergency: " + isEmergency);
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            sendErrorAndReportSessionMetrics(error, result);
            return;
        }

        if (enableSatellite) {
            synchronized (mIsRadioOnLock) {
                if (!mIsRadioOn) {
                    ploge("Radio is not on, can not enable satellite");
                    sendErrorAndReportSessionMetrics(
                            SatelliteManager.SATELLITE_RESULT_INVALID_MODEM_STATE, result);
                    return;
                }
                if (mRadioOffRequested) {
                    ploge("Radio is being powering off, can not enable satellite");
                    sendErrorAndReportSessionMetrics(
                            SatelliteManager.SATELLITE_RESULT_INVALID_MODEM_STATE, result);
                    return;
                }
            }

            if (mTelecomManager.isInEmergencyCall()) {
                plogd("requestSatelliteEnabled: reject as emergency call is ongoing.");
                sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_EMERGENCY_CALL_IN_PROGRESS, result);
                return;
            }
        } else {
            /* if disable satellite, always assume demo is also disabled */
            enableDemoMode = false;
        }

        RequestSatelliteEnabledArgument request =
                new RequestSatelliteEnabledArgument(enableSatellite, enableDemoMode, isEmergency,
                        result);
        /**
         * Multiple satellite enabled requests are handled as below:
         * 1. If there are no ongoing requests, store current request in mSatelliteEnabledRequest
         * 2. If there is a ongoing request, then:
         *      1. ongoing request = enable, current request = enable: return IN_PROGRESS error
         *      2. ongoing request = disable, current request = disable: return IN_PROGRESS error
         *      3. ongoing request = disable, current request = enable: return
         *      SATELLITE_RESULT_ERROR error
         *      4. ongoing request = enable, current request = disable: send request to modem
         */
        synchronized (mSatelliteEnabledRequestLock) {
            if (!isSatelliteEnabledRequestInProgress()) {
                synchronized (mIsSatelliteEnabledLock) {
                    if (mIsSatelliteEnabled != null && mIsSatelliteEnabled == enableSatellite) {
                        evaluateToUpdateSatelliteEnabledAttributes(result,
                                SatelliteManager.SATELLITE_RESULT_SUCCESS, request,
                                mIsDemoModeEnabled, mIsEmergency);
                        return;
                    }
                }
                if (enableSatellite) {
                    mSatelliteEnabledRequest = request;
                } else {
                    mSatelliteDisabledRequest = request;
                }
            } else if (isSatelliteBeingDisabled()) {
                int resultCode = SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS;
                if (enableSatellite) {
                    plogw("requestSatelliteEnabled: The enable request cannot be "
                            + "processed since disable satellite is in progress.");
                    resultCode = SatelliteManager.SATELLITE_RESULT_DISABLE_IN_PROGRESS;
                } else {
                    plogd("requestSatelliteEnabled: Disable is already in progress.");
                }
                sendErrorAndReportSessionMetrics(resultCode, result);
                return;
            } else {
                // Satellite is being enabled or satellite enable attributes are being updated
                if (enableSatellite) {
                    if (mSatelliteEnableAttributesUpdateRequest == null) {
                        /* Satellite is being enabled and framework receive a new enable request to
                         * update the enable attributes.
                         */
                        evaluateToUpdateSatelliteEnabledAttributes(result,
                                SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS,
                                request, mSatelliteEnabledRequest.enableDemoMode,
                                mSatelliteEnabledRequest.isEmergency);
                    } else {
                        /* The enable attributes update request is already being processed.
                         * Framework can't handle one more request to update enable attributes.
                         */
                        plogd("requestSatelliteEnabled: enable attributes update request is already"
                                + " in progress.");
                        sendErrorAndReportSessionMetrics(
                                SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS, result);
                    }
                    return;
                } else {
                    /* Users might want to end the satellite session while it is being enabled, or
                     * the satellite session need to be disabled for an emergency call. Note: some
                     * carriers want to disable satellite for prioritizing emergency calls. Thus,
                     * we need to push the disable request to modem while enable is in progress.
                     */
                    if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
                        plogd("requestSatelliteEnabled: carrierRoamingNbIotNtn flag is disabled");
                        sendErrorAndReportSessionMetrics(
                                SatelliteManager.SATELLITE_RESULT_ENABLE_IN_PROGRESS, result);
                        return;
                    }
                    mSatelliteDisabledRequest = request;
                }
            }
        }
        sendRequestAsync(CMD_SET_SATELLITE_ENABLED, request, null);
    }

    /**
     * Validate the newly-received enable attributes against the current ones. If the new attributes
     * are valid and different from the current ones, framework will send a request to update the
     * enable attributes to modem. Otherwise, framework will return
     * {@code SATELLITE_RESULT_INVALID_ARGUMENTS} to the requesting clients.
     *
     * @param result The callback that returns the result to the requesting client.
     * @param resultCode The result code to send back to the requesting client when framework does
     *                   not need to reconfigure modem.
     * @param enableRequest The new enable request to update satellite enable attributes.
     * @param currentDemoMode The current demo mode at framework.
     * @param currentEmergencyMode The current emergency mode at framework.
     */
    private void evaluateToUpdateSatelliteEnabledAttributes(@NonNull Consumer<Integer> result,
            @SatelliteManager.SatelliteResult int resultCode,
            @NonNull RequestSatelliteEnabledArgument enableRequest, boolean currentDemoMode,
            boolean currentEmergencyMode) {
        boolean needToReconfigureModem = false;
        if (enableRequest.enableDemoMode != currentDemoMode) {
            if (enableRequest.enableDemoMode) {
                ploge("Moving from real mode to demo mode is rejected");
                sendErrorAndReportSessionMetrics(SATELLITE_RESULT_INVALID_ARGUMENTS, result);
                return;
            } else {
                plogd("Moving from demo mode to real mode. Need to reconfigure"
                        + " modem with real mode");
                needToReconfigureModem = true;
            }
        } else if (enableRequest.isEmergency != currentEmergencyMode) {
            if (enableRequest.isEmergency) {
                plogd("Moving from non-emergency to emergency mode. Need to "
                        + "reconfigure modem");
                needToReconfigureModem = true;
            } else {
                plogd("Non-emergency requests can be served during an emergency"
                        + " satellite session. No need to reconfigure modem.");
            }
        }

        if (needToReconfigureModem) {
            synchronized (mSatelliteEnabledRequestLock) {
                mSatelliteEnableAttributesUpdateRequest = enableRequest;
            }
            sendRequestAsync(
                    CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES, enableRequest, null);
        } else {
            if (resultCode != SatelliteManager.SATELLITE_RESULT_SUCCESS) {
                plogd("requestSatelliteEnabled enable satellite is already in progress.");
            }
            sendErrorAndReportSessionMetrics(resultCode, result);
        }
        return;
    }

    /**
     * @return {@code true} when either enable request, disable request, or enable attributes update
     * request is in progress, {@code false} otherwise.
     */
    private boolean isSatelliteEnabledRequestInProgress() {
        synchronized (mSatelliteEnabledRequestLock) {
            plogd("mSatelliteEnabledRequest: " + (mSatelliteEnabledRequest != null)
                    + ", mSatelliteDisabledRequest: " + (mSatelliteDisabledRequest != null)
                    + ", mSatelliteEnableAttributesUpdateRequest: "
                    + (mSatelliteEnableAttributesUpdateRequest != null));
            return (mSatelliteEnabledRequest != null || mSatelliteDisabledRequest != null
                    || mSatelliteEnableAttributesUpdateRequest != null);
        }
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param result The result receiver that returns whether the satellite modem is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsSatelliteEnabled(@NonNull ResultReceiver result) {
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        synchronized (mIsSatelliteEnabledLock) {
            if (mIsSatelliteEnabled != null) {
                /* We have already successfully queried the satellite modem. */
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_ENABLED, mIsSatelliteEnabled);
                result.send(SATELLITE_RESULT_SUCCESS, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_IS_SATELLITE_ENABLED, result, null);
    }

    /**
     * Get whether the satellite modem is enabled.
     * This will return the cached value instead of querying the satellite modem.
     *
     * @return {@code true} if the satellite modem is enabled and {@code false} otherwise.
     */
    private boolean isSatelliteEnabled() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("isSatelliteEnabled: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        synchronized (mIsSatelliteEnabledLock) {
            if (mIsSatelliteEnabled == null) return false;
            return mIsSatelliteEnabled;
        }
    }

    /**
     * Get whether satellite modem is being enabled.
     *
     * @return {@code true} if the satellite modem is being enabled and {@code false} otherwise.
     */
    private boolean isSatelliteBeingEnabled() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("isSatelliteBeingEnabled: oemEnabledSatelliteFlag is disabled");
            return false;
        }

        if (mSatelliteSessionController != null) {
            return mSatelliteSessionController.isInEnablingState();
        }
        return false;
    }

    /**
     * Get whether the satellite modem is enabled or being enabled.
     * This will return the cached value instead of querying the satellite modem.
     *
     * @return {@code true} if the satellite modem is enabled or being enabled, {@code false}
     * otherwise.
     */
    public boolean isSatelliteEnabledOrBeingEnabled() {
        return isSatelliteEnabled() || isSatelliteBeingEnabled();
    }

    /**
     * Get whether satellite modem is being disabled.
     *
     * @return {@code true} if the satellite modem is being disabled and {@code false} otherwise.
     */
    public boolean isSatelliteBeingDisabled() {
        if (mSatelliteSessionController != null) {
            return mSatelliteSessionController.isInDisablingState();
        }
        return false;
    }

    /**
     * Request to get whether the satellite service demo mode is enabled.
     *
     * @param result The result receiver that returns whether the satellite demo mode is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsDemoModeEnabled(@NonNull ResultReceiver result) {
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        final Bundle bundle = new Bundle();
        bundle.putBoolean(SatelliteManager.KEY_DEMO_MODE_ENABLED, mIsDemoModeEnabled);
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    /**
     * Get whether the satellite service demo mode is enabled.
     *
     * @return {@code true} if the satellite demo mode is enabled and {@code false} otherwise.
     */
    public boolean isDemoModeEnabled() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("isDemoModeEnabled: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        return mIsDemoModeEnabled;
    }

    /**
     * Request to get whether the satellite enabled request is for emergency or not.
     *
     * @param result The result receiver that returns whether the request is for emergency
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsEmergencyModeEnabled(@NonNull ResultReceiver result) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("requestIsEmergencyModeEnabled: oemEnabledSatelliteFlag is disabled");
            result.send(SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED, null);
            return;
        }

        synchronized (mSatelliteEnabledRequestLock) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(SatelliteManager.KEY_EMERGENCY_MODE_ENABLED,
                    getRequestIsEmergency());
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
        }
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param result The result receiver that returns whether the satellite service is supported on
     *               the device if the request is successful or an error code if the request failed.
     */
    public void requestIsSatelliteSupported(@NonNull ResultReceiver result) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("requestIsSatelliteSupported: oemEnabledSatelliteFlag is disabled");
            result.send(SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED, null);
            return;
        }
        synchronized (mIsSatelliteSupportedLock) {
            if (mIsSatelliteSupported != null) {
                /* We have already successfully queried the satellite modem. */
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, mIsSatelliteSupported);
                bundle.putInt(SATELLITE_SUBSCRIPTION_ID, getSelectedSatelliteSubId());
                result.send(SATELLITE_RESULT_SUCCESS, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_IS_SATELLITE_SUPPORTED, result, null);
    }

    /**
     * Request to get the {@link SatelliteCapabilities} of the satellite service.
     *
     * @param result The result receiver that returns the {@link SatelliteCapabilities}
     *               if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteCapabilities(@NonNull ResultReceiver result) {
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        synchronized (mSatelliteCapabilitiesLock) {
            if (mSatelliteCapabilities != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(SatelliteManager.KEY_SATELLITE_CAPABILITIES,
                        mSatelliteCapabilities);
                result.send(SATELLITE_RESULT_SUCCESS, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_GET_SATELLITE_CAPABILITIES, result, null);
    }

    /**
     * Start receiving satellite transmission updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     *
     * @param errorCallback The callback to get the error code of the request.
     * @param callback The callback to notify of satellite transmission updates.
     */
    public void startSatelliteTransmissionUpdates(
            @NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(errorCallback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        final int validSubId = getSelectedSatelliteSubId();
        mPointingAppController.registerForSatelliteTransmissionUpdates(validSubId, callback);
        sendRequestAsync(CMD_START_SATELLITE_TRANSMISSION_UPDATES,
                new SatelliteTransmissionUpdateArgument(result, callback, validSubId), null);
    }

    /**
     * Stop receiving satellite transmission updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     *
     * @param errorCallback The callback to get the error code of the request.
     * @param callback The callback that was passed to {@link #startSatelliteTransmissionUpdates(
     *                 int, IIntegerConsumer, ISatelliteTransmissionUpdateCallback)}.
     */
    public void stopSatelliteTransmissionUpdates(@NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(errorCallback::accept);
        mPointingAppController.unregisterForSatelliteTransmissionUpdates(
                getSelectedSatelliteSubId(), result, callback);

        // Even if handler is null - which means there are no listeners, the modem command to stop
        // satellite transmission updates might have failed. The callers might want to retry
        // sending the command. Thus, we always need to send this command to the modem.
        sendRequestAsync(CMD_STOP_SATELLITE_TRANSMISSION_UPDATES, result, null);
    }

    /**
     * Register the subscription with a satellite provider.
     * This is needed to register the subscription if the provider allows dynamic registration.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning server
     * @param callback The callback to get the error code of the request.
     *
     * @return The signal transport used by the caller to cancel the provision request,
     *         or {@code null} if the request failed.
     */
    @Nullable public ICancellationSignal provisionSatelliteService(
            @NonNull String token, @NonNull byte[] provisionData,
            @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return null;
        }

        final int validSubId = getSelectedSatelliteSubId();
        if (mSatelliteProvisionCallbacks.containsKey(validSubId)) {
            result.accept(SatelliteManager.SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS);
            return null;
        }

        Boolean satelliteProvisioned = isSatelliteViaOemProvisioned();
        if (satelliteProvisioned != null && satelliteProvisioned) {
            result.accept(SATELLITE_RESULT_SUCCESS);
            return null;
        }

        sendRequestAsync(CMD_PROVISION_SATELLITE_SERVICE,
                new ProvisionSatelliteServiceArgument(token, provisionData, result, validSubId),
                null);

        ICancellationSignal cancelTransport = CancellationSignal.createTransport();
        CancellationSignal.fromTransport(cancelTransport).setOnCancelListener(() -> {
            sendRequestAsync(CMD_DEPROVISION_SATELLITE_SERVICE,
                    new ProvisionSatelliteServiceArgument(token, provisionData, null,
                            validSubId), null);
            mProvisionMetricsStats.setIsCanceled(true);
        });
        return cancelTransport;
    }

    /**
     * Unregister the device/subscription with the satellite provider.
     * This is needed if the provider allows dynamic registration. Once deprovisioned,
     * {@link android.telephony.satellite.SatelliteProvisionStateCallback
     * #onSatelliteProvisionStateChanged(boolean)}
     * should report as deprovisioned.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param callback The callback to get the error code of the request.
     */
    public void deprovisionSatelliteService(
            @NonNull String token, @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        if (Boolean.FALSE.equals(isSatelliteViaOemProvisioned())) {
            result.accept(SATELLITE_RESULT_SUCCESS);
            return;
        }

        sendRequestAsync(CMD_DEPROVISION_SATELLITE_SERVICE,
                new ProvisionSatelliteServiceArgument(token, null,
                        result, getSelectedSatelliteSubId()),
                null);
    }

    /**
     * Registers for the satellite provision state changed.
     *
     * @param callback The callback to handle the satellite provision state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForSatelliteProvisionStateChanged(
            @NonNull ISatelliteProvisionStateCallback callback) {
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            return error;
        }

        mSatelliteProvisionStateChangedListeners.put(callback.asBinder(), callback);

        boolean isProvisioned = Boolean.TRUE.equals(isSatelliteViaOemProvisioned());
        try {
            callback.onSatelliteProvisionStateChanged(isProvisioned);
        } catch (RemoteException ex) {
            loge("registerForSatelliteProvisionStateChanged: " + ex);
        }
        synchronized (mSatelliteViaOemProvisionLock) {
            plogd("registerForSatelliteProvisionStateChanged: report current provisioned "
                    + "state, state=" + isProvisioned);
        }

        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the satellite provision state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteProvisionStateChanged(int, ISatelliteProvisionStateCallback)}.
     */
    public void unregisterForSatelliteProvisionStateChanged(
            @NonNull ISatelliteProvisionStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("unregisterForSatelliteProvisionStateChanged: "
                    + "oemEnabledSatelliteFlag is disabled");
            return;
        }
        mSatelliteProvisionStateChangedListeners.remove(callback.asBinder());
    }

    /**
     * Request to get whether the device is provisioned with a satellite provider.
     *
     * @param result The result receiver that returns whether the device is provisioned with a
     *               satellite provider if the request is successful or an error code if the
     *               request failed.
     */
    public void requestIsSatelliteProvisioned(@NonNull ResultReceiver result) {
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        synchronized (mSatelliteViaOemProvisionLock) {
            if (mIsSatelliteViaOemProvisioned != null) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED,
                        mIsSatelliteViaOemProvisioned);
                result.send(SATELLITE_RESULT_SUCCESS, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_IS_SATELLITE_PROVISIONED, result, null);
    }

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param callback The callback to handle the satellite modem state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForSatelliteModemStateChanged(
            @NonNull ISatelliteModemStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("registerForSatelliteModemStateChanged: oemEnabledSatelliteFlag is disabled");
            return SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
        }
        if (mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("registerForSatelliteModemStateChanged: add RegistrationFailure Listeners");
            mSatelliteRegistrationFailureListeners.put(callback.asBinder(), callback);
        }
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.registerForSatelliteModemStateChanged(callback);
        } else {
            ploge("registerForSatelliteModemStateChanged: mSatelliteSessionController"
                    + " is not initialized yet");
            return SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
        }
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for modem state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteModemStateChanged(int, ISatelliteModemStateCallback)}.
     */
    public void unregisterForModemStateChanged(
            @NonNull ISatelliteModemStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("unregisterForModemStateChanged: oemEnabledSatelliteFlag is disabled");
            return;
        }
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.unregisterForSatelliteModemStateChanged(callback);
        } else {
            ploge("unregisterForModemStateChanged: mSatelliteSessionController"
                    + " is not initialized yet");
        }
        if (mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("unregisterForModemStateChanged: remove RegistrationFailure Listeners");
            mSatelliteRegistrationFailureListeners.remove(callback.asBinder());
        }
    }

    /**
     * Register to receive incoming datagrams over satellite.
     *
     * @param callback The callback to handle incoming datagrams over satellite.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForIncomingDatagram(
            @NonNull ISatelliteDatagramCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("registerForIncomingDatagram: oemEnabledSatelliteFlag is disabled");
            return SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
        }
        if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
            return SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
        }
        plogd("registerForIncomingDatagram: callback=" + callback);
        return mDatagramController.registerForSatelliteDatagram(
                getSelectedSatelliteSubId(), callback);
    }

    /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     *                 {@link #registerForIncomingDatagram(int, ISatelliteDatagramCallback)}.
     */
    public void unregisterForIncomingDatagram(
            @NonNull ISatelliteDatagramCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("unregisterForIncomingDatagram: oemEnabledSatelliteFlag is disabled");
            return;
        }
        if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
            return;
        }
        plogd("unregisterForIncomingDatagram: callback=" + callback);
        mDatagramController.unregisterForSatelliteDatagram(
                getSelectedSatelliteSubId(), callback);
    }

    /**
     * Poll pending satellite datagrams over satellite.
     *
     * This method requests modem to check if there are any pending datagrams to be received over
     * satellite. If there are any incoming datagrams, they will be received via
     * {@link android.telephony.satellite.SatelliteDatagramCallback#onSatelliteDatagramReceived(
     * long, SatelliteDatagram, int, Consumer)}
     *
     * @param callback The callback to get {@link SatelliteManager.SatelliteResult} of the request.
     */
    public void pollPendingDatagrams(@NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        mDatagramController.pollPendingSatelliteDatagrams(
                getSelectedSatelliteSubId(), result);
    }

    /**
     * Send datagram over satellite.
     *
     * Gateway encodes SOS message or location sharing message into a datagram and passes it as
     * input to this method. Datagram received here will be passed down to modem without any
     * encoding or encryption.
     *
     * @param datagramType datagram type indicating whether the datagram is of type
     *                     SOS_SMS or LOCATION_SHARING.
     * @param datagram encoded gateway datagram which is encrypted by the caller.
     *                 Datagram will be passed down to modem without any encoding or encryption.
     * @param needFullScreenPointingUI this is used to indicate pointingUI app to open in
     *                                 full screen mode.
     * @param callback The callback to get {@link SatelliteManager.SatelliteResult} of the request.
     */
    public void sendDatagram(@SatelliteManager.DatagramType int datagramType,
            SatelliteDatagram datagram, boolean needFullScreenPointingUI,
            @NonNull IIntegerConsumer callback) {
        plogd("sendSatelliteDatagram: datagramType: " + datagramType
                + " needFullScreenPointingUI: " + needFullScreenPointingUI);

        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        /**
         * TODO for NTN-based satellites: Check if satellite is acquired.
         */
        if (mNeedsSatellitePointing) {

            mPointingAppController.startPointingUI(needFullScreenPointingUI, mIsDemoModeEnabled,
                    mIsEmergency);
        }

        mDatagramController.sendSatelliteDatagram(getSelectedSatelliteSubId(), datagramType,
                datagram, needFullScreenPointingUI, result);
    }

    /**
     * Request to get the time after which the satellite will be visible.
     *
     * @param result The result receiver that returns the time after which the satellite will
     *               be visible if the request is successful or an error code if the request failed.
     */
    public void requestTimeForNextSatelliteVisibility(@NonNull ResultReceiver result) {
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        sendRequestAsync(CMD_GET_TIME_SATELLITE_NEXT_VISIBLE, result, null);
    }

    /**
     * Inform whether the device is aligned with the satellite in both real and demo mode.
     *
     * @param isAligned {@true} means device is aligned with the satellite, otherwise {@false}.
     */
    public void setDeviceAlignedWithSatellite(@NonNull boolean isAligned) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setDeviceAlignedWithSatellite: oemEnabledSatelliteFlag is disabled");
            return;
        }

        DemoSimulator.getInstance().setDeviceAlignedWithSatellite(isAligned);
        mDatagramController.setDeviceAlignedWithSatellite(isAligned);
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.setDeviceAlignedWithSatellite(isAligned);
        } else {
            ploge("setDeviceAlignedWithSatellite: mSatelliteSessionController"
                    + " is not initialized yet");
        }
    }

    /**
     * Add a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem. After updating restriction list, evaluate if satellite should be enabled/disabled,
     * and request modem to enable/disable satellite accordingly if the desired state does not match
     * the current state.
     *
     * @param subId The subId of the subscription to request for.
     * @param reason Reason for disallowing satellite communication for carrier.
     * @param callback The callback to get the result of the request.
     */
    public void addAttachRestrictionForCarrier(int subId,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        if (DBG) logd("addAttachRestrictionForCarrier(" + subId + ", " + reason + ")");
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            result.accept(SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED);
            logd("addAttachRestrictionForCarrier: carrierEnabledSatelliteFlag is "
                    + "disabled");
            return;
        }

        synchronized (mIsSatelliteEnabledLock) {
            if (mSatelliteAttachRestrictionForCarrierArray.getOrDefault(
                    subId, Collections.emptySet()).isEmpty()) {
                mSatelliteAttachRestrictionForCarrierArray.put(subId, new HashSet<>());
            } else if (mSatelliteAttachRestrictionForCarrierArray.get(subId).contains(reason)) {
                result.accept(SATELLITE_RESULT_SUCCESS);
                return;
            }
            mSatelliteAttachRestrictionForCarrierArray.get(subId).add(reason);
        }
        RequestHandleSatelliteAttachRestrictionForCarrierArgument request =
                new RequestHandleSatelliteAttachRestrictionForCarrierArgument(subId, reason,
                        result);
        sendRequestAsync(CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE, request,
                SatelliteServiceUtils.getPhone(subId));
    }

    /**
     * Remove a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem. After updating restriction list, evaluate if satellite should be enabled/disabled,
     * and request modem to enable/disable satellite accordingly if the desired state does not match
     * the current state.
     *
     * @param subId The subId of the subscription to request for.
     * @param reason Reason for disallowing satellite communication.
     * @param callback The callback to get the result of the request.
     */
    public void removeAttachRestrictionForCarrier(int subId,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        if (DBG) logd("removeAttachRestrictionForCarrier(" + subId + ", " + reason + ")");
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            result.accept(SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED);
            logd("removeAttachRestrictionForCarrier: carrierEnabledSatelliteFlag is "
                    + "disabled");
            return;
        }

        synchronized (mIsSatelliteEnabledLock) {
            if (mSatelliteAttachRestrictionForCarrierArray.getOrDefault(
                    subId, Collections.emptySet()).isEmpty()
                    || !mSatelliteAttachRestrictionForCarrierArray.get(subId).contains(reason)) {
                result.accept(SATELLITE_RESULT_SUCCESS);
                return;
            }
            mSatelliteAttachRestrictionForCarrierArray.get(subId).remove(reason);
        }
        RequestHandleSatelliteAttachRestrictionForCarrierArgument request =
                new RequestHandleSatelliteAttachRestrictionForCarrierArgument(subId, reason,
                        result);
        sendRequestAsync(CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE, request,
                SatelliteServiceUtils.getPhone(subId));
    }

    /**
     * Get reasons for disallowing satellite communication, as requested by
     * {@link #addAttachRestrictionForCarrier(int, int, IIntegerConsumer)}.
     *
     * @param subId The subId of the subscription to request for.
     *
     * @return Set of reasons for disallowing satellite attach for carrier.
     */
    @NonNull public Set<Integer> getAttachRestrictionReasonsForCarrier(int subId) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("getAttachRestrictionReasonsForCarrier: carrierEnabledSatelliteFlag is "
                    + "disabled");
            return new HashSet<>();
        }
        synchronized (mIsSatelliteEnabledLock) {
            Set<Integer> resultSet =
                    mSatelliteAttachRestrictionForCarrierArray.get(subId);
            if (resultSet == null) {
                return new HashSet<>();
            }
            return new HashSet<>(resultSet);
        }
    }

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * @param result Result receiver to get the error code of the request and the current signal
     * strength of the satellite connection.
     */
    public void requestNtnSignalStrength(@NonNull ResultReceiver result) {
        if (DBG) plogd("requestNtnSignalStrength()");

        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        /* In case cache is available, it is not needed to request non-terrestrial signal strength
        to modem */
        synchronized (mNtnSignalsStrengthLock) {
            if (mNtnSignalStrength.getLevel() != NTN_SIGNAL_STRENGTH_NONE) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(KEY_NTN_SIGNAL_STRENGTH, mNtnSignalStrength);
                result.send(SATELLITE_RESULT_SUCCESS, bundle);
                return;
            }
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        sendRequestAsync(CMD_REQUEST_NTN_SIGNAL_STRENGTH, result, phone);
    }

    /**
     * Registers for NTN signal strength changed from satellite modem. If the registration operation
     * is not successful, a {@link ServiceSpecificException} that contains
     * {@link SatelliteManager.SatelliteResult} will be thrown.
     *
     * @param callback The callback to handle the NTN signal strength changed event. If the
     * operation is successful, {@link INtnSignalStrengthCallback#onNtnSignalStrengthChanged(
     * NtnSignalStrength)} will return an instance of {@link NtnSignalStrength} with a value of
     * {@link NtnSignalStrength.NtnSignalStrengthLevel} when the signal strength of non-terrestrial
     * network has changed.
     *
     * @throws ServiceSpecificException If the callback registration operation fails.
     */
    public void registerForNtnSignalStrengthChanged(
            @NonNull INtnSignalStrengthCallback callback) throws RemoteException {
        if (DBG) plogd("registerForNtnSignalStrengthChanged()");

        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error == SATELLITE_RESULT_SUCCESS) {
            mNtnSignalStrengthChangedListeners.put(callback.asBinder(), callback);
            synchronized (mNtnSignalsStrengthLock) {
                try {
                    callback.onNtnSignalStrengthChanged(mNtnSignalStrength);
                    plogd("registerForNtnSignalStrengthChanged: " + mNtnSignalStrength);
                } catch (RemoteException ex) {
                    ploge("registerForNtnSignalStrengthChanged: RemoteException ex="
                            + ex);
                }
            }
        } else {
            throw new RemoteException(new IllegalStateException("registration fails: " + error));
        }
    }

    /**
     * Unregisters for NTN signal strength changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * changed event.
     * @param callback The callback that was passed to
     * {@link #registerForNtnSignalStrengthChanged(int, INtnSignalStrengthCallback)}
     */
    public void unregisterForNtnSignalStrengthChanged(
            @NonNull INtnSignalStrengthCallback callback) {
        if (DBG) plogd("unregisterForNtnSignalStrengthChanged()");
        mNtnSignalStrengthChangedListeners.remove(callback.asBinder());
    }

    /**
     * Registers for satellite capabilities change event from the satellite service.
     *
     * @param callback The callback to handle the satellite capabilities changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForCapabilitiesChanged(
            @NonNull ISatelliteCapabilitiesCallback callback) {
        if (DBG) plogd("registerForCapabilitiesChanged()");

        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) return error;

        mSatelliteCapabilitiesChangedListeners.put(callback.asBinder(), callback);
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for satellite capabilities change event from the satellite service.
     * If callback was not registered before, the request will be ignored.
     *
     * changed event.
     * @param callback The callback that was passed to
     * {@link #registerForCapabilitiesChanged(int, ISatelliteCapabilitiesCallback)}
     */
    public void unregisterForCapabilitiesChanged(
            @NonNull ISatelliteCapabilitiesCallback callback) {
        if (DBG) plogd("unregisterForCapabilitiesChanged()");
        mSatelliteCapabilitiesChangedListeners.remove(callback.asBinder());
    }

    /**
     * Registers for the satellite supported state changed.
     *
     * @param callback The callback to handle the satellite supported state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForSatelliteSupportedStateChanged(
            @NonNull ISatelliteSupportedStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("registerForSatelliteSupportedStateChanged: oemEnabledSatelliteFlag is disabled");
            return SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
        }

        mSatelliteSupportedStateChangedListeners.put(callback.asBinder(), callback);
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the satellite supported state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteSupportedStateChanged(int, ISatelliteSupportedStateCallback)}.
     */
    public void unregisterForSatelliteSupportedStateChanged(
            @NonNull ISatelliteSupportedStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("unregisterForSatelliteSupportedStateChanged: "
                    + "oemEnabledSatelliteFlag is disabled");
            return;
        }
        mSatelliteSupportedStateChangedListeners.remove(callback.asBinder());
    }

    /**
     * This API can be used by only CTS to update satellite vendor service package name.
     *
     * @param servicePackageName The package name of the satellite vendor service.
     * @param provisioned          Whether satellite should be provisioned or not.
     * @return {@code true} if the satellite vendor service is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatelliteServicePackageName(@Nullable String servicePackageName,
            String provisioned) {
        if (!isMockModemAllowed()) {
            plogd("setSatelliteServicePackageName: mock modem not allowed");
            return false;
        }

        // Cached states need to be cleared whenever switching satellite vendor services.
        plogd("setSatelliteServicePackageName: Resetting cached states, provisioned="
                + provisioned);
        synchronized (mIsSatelliteSupportedLock) {
            mIsSatelliteSupported = null;
        }
        synchronized (mSatelliteViaOemProvisionLock) {
            mIsSatelliteViaOemProvisioned = Optional.ofNullable(provisioned)
                    .filter(s -> s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))
                    .map(s -> s.equalsIgnoreCase("true"))
                    .orElse(null);
        }
        synchronized (mIsSatelliteEnabledLock) {
            mIsSatelliteEnabled = null;
        }
        synchronized (mSatelliteCapabilitiesLock) {
            mSatelliteCapabilities = null;
        }
        mSatelliteModemInterface.setSatelliteServicePackageName(servicePackageName);
        return true;
    }

    /**
     * This API can be used by only CTS to update the timeout duration in milliseconds that
     * satellite should stay at listening mode to wait for the next incoming page before disabling
     * listening mode.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteListeningTimeoutDuration(long timeoutMillis) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setSatelliteListeningTimeoutDuration: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        if (mSatelliteSessionController == null) {
            ploge("mSatelliteSessionController is not initialized yet");
            return false;
        }
        return mSatelliteSessionController.setSatelliteListeningTimeoutDuration(timeoutMillis);
    }

    /**
     * This API can be used by only CTS to control ingoring cellular service state event.
     *
     * @param enabled Whether to enable boolean config.
     * @return {@code true} if the value is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteIgnoreCellularServiceState(boolean enabled) {
        plogd("setSatelliteIgnoreCellularServiceState - " + enabled);
        if (mSatelliteSessionController == null) {
            ploge("setSatelliteIgnoreCellularServiceState is not initialized yet");
            return false;
        }
        return mSatelliteSessionController.setSatelliteIgnoreCellularServiceState(enabled);
    }

    /**
     * This API can be used by only CTS to override timeout durations used by DatagramController
     * module.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    public boolean setDatagramControllerTimeoutDuration(
            boolean reset, int timeoutType, long timeoutMillis) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setDatagramControllerTimeoutDuration: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        plogd("setDatagramControllerTimeoutDuration: reset=" + reset + ", timeoutType="
                + timeoutType + ", timeoutMillis=" + timeoutMillis);
        return mDatagramController.setDatagramControllerTimeoutDuration(
                reset, timeoutType, timeoutMillis);
    }

    /**
     * This API can be used by only CTS to override the boolean configs used by the
     * DatagramController module.
     *
     * @param enable Whether to enable or disable boolean config.
     * @return {@code true} if the boolean config is set successfully, {@code false} otherwise.
     */
    public boolean setDatagramControllerBooleanConfig(
            boolean reset, int booleanType, boolean enable) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            logd("setDatagramControllerBooleanConfig: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        logd("setDatagramControllerBooleanConfig: reset=" + reset + ", booleanType="
                + booleanType + ", enable=" + enable);
        return mDatagramController.setDatagramControllerBooleanConfig(
                reset, booleanType, enable);
    }

    /**
     * This API can be used by only CTS to override timeout durations used by SatelliteController
     * module.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteControllerTimeoutDuration(
            boolean reset, int timeoutType, long timeoutMillis) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setSatelliteControllerTimeoutDuration: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        if (!isMockModemAllowed()) {
            plogd("setSatelliteControllerTimeoutDuration: mock modem is not allowed");
            return false;
        }
        plogd("setSatelliteControllerTimeoutDuration: reset=" + reset + ", timeoutType="
                + timeoutType + ", timeoutMillis=" + timeoutMillis);
        if (timeoutType == TIMEOUT_TYPE_WAIT_FOR_SATELLITE_ENABLING_RESPONSE) {
            if (reset) {
                mWaitTimeForSatelliteEnablingResponse =
                        getWaitForSatelliteEnablingResponseTimeoutMillis();
            } else {
                mWaitTimeForSatelliteEnablingResponse = timeoutMillis;
            }
            plogd("mWaitTimeForSatelliteEnablingResponse=" + mWaitTimeForSatelliteEnablingResponse);
        } else if (timeoutType == TIMEOUT_TYPE_DEMO_POINTING_ALIGNED_DURATION_MILLIS) {
            if (reset) {
                mDemoPointingAlignedDurationMillis =
                        getDemoPointingAlignedDurationMillisFromResources();
            } else {
                mDemoPointingAlignedDurationMillis = timeoutMillis;
            }
        } else if (timeoutType == TIMEOUT_TYPE_DEMO_POINTING_NOT_ALIGNED_DURATION_MILLIS) {
            if (reset) {
                mDemoPointingNotAlignedDurationMillis =
                        getDemoPointingNotAlignedDurationMillisFromResources();
            } else {
                mDemoPointingNotAlignedDurationMillis = timeoutMillis;
            }
        } else if (timeoutType
                == TIMEOUT_TYPE_EVALUATE_ESOS_PROFILES_PRIORITIZATION_DURATION_MILLIS) {
            if (reset) {
                mEvaluateEsosProfilesPrioritizationDurationMillis =
                        getEvaluateEsosProfilesPrioritizationDurationMillis();
            } else {
                mEvaluateEsosProfilesPrioritizationDurationMillis = timeoutMillis;
            }
        } else {
            plogw("Invalid timeoutType=" + timeoutType);
            return false;
        }
        return true;
    }

    /**
     * This API can be used by only CTS to update satellite gateway service package name.
     *
     * @param servicePackageName The package name of the satellite gateway service.
     * @return {@code true} if the satellite gateway service is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatelliteGatewayServicePackageName(@Nullable String servicePackageName) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setSatelliteGatewayServicePackageName: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        if (mSatelliteSessionController == null) {
            ploge("mSatelliteSessionController is not initialized yet");
            return false;
        }
        return mSatelliteSessionController.setSatelliteGatewayServicePackageName(
                servicePackageName);
    }

    /**
     * This API can be used by only CTS to update satellite pointing UI app package and class names.
     *
     * @param packageName The package name of the satellite pointing UI app.
     * @param className The class name of the satellite pointing UI app.
     * @return {@code true} if the satellite pointing UI app package and class is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatellitePointingUiClassName(
            @Nullable String packageName, @Nullable String className) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setSatellitePointingUiClassName: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        return mPointingAppController.setSatellitePointingUiClassName(packageName, className);
    }

    /**
     * This API can be used in only testing to override connectivity status in monitoring emergency
     * calls and sending EVENT_DISPLAY_EMERGENCY_MESSAGE to Dialer.
     *
     * @param handoverType The type of handover from emergency call to satellite messaging. Use one
     *                     of the following values to enable the override:
     *                     0 - EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS
     *                     1 - EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911
     *                     To disable the override, use -1 for handoverType.
     * @param delaySeconds The event EVENT_DISPLAY_EMERGENCY_MESSAGE will be sent to Dialer
     *                     delaySeconds after the emergency call starts.
     * @return {@code true} if the handover type is set successfully, {@code false} otherwise.
     */
    public boolean setEmergencyCallToSatelliteHandoverType(int handoverType, int delaySeconds) {
        if (!isMockModemAllowed()) {
            ploge("setEmergencyCallToSatelliteHandoverType: mock modem not allowed");
            return false;
        }
        if (isHandoverTypeValid(handoverType)) {
            mEnforcedEmergencyCallToSatelliteHandoverType = handoverType;
            mDelayInSendingEventDisplayEmergencyMessage = delaySeconds > 0 ? delaySeconds : 0;
        } else {
            mEnforcedEmergencyCallToSatelliteHandoverType =
                    INVALID_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE;
            mDelayInSendingEventDisplayEmergencyMessage = 0;
        }
        return true;
    }

    /**
     * This API can be used in only testing to override oem-enabled satellite provision status.
     *
     * @param reset {@code true} mean the overriding status should not be used, {@code false}
     *              otherwise.
     * @param isProvisioned The overriding provision status.
     * @return {@code true} if the provision status is set successfully, {@code false} otherwise.
     */
    public boolean setOemEnabledSatelliteProvisionStatus(boolean reset, boolean isProvisioned) {
        if (!isMockModemAllowed()) {
            ploge("setOemEnabledSatelliteProvisionStatus: mock modem not allowed");
            return false;
        }
        synchronized (mSatelliteViaOemProvisionLock) {
            if (reset) {
                mOverriddenIsSatelliteViaOemProvisioned = null;
            } else {
                mOverriddenIsSatelliteViaOemProvisioned = isProvisioned;
            }
        }
        return true;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int getEnforcedEmergencyCallToSatelliteHandoverType() {
        return mEnforcedEmergencyCallToSatelliteHandoverType;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int getDelayInSendingEventDisplayEmergencyMessage() {
        return mDelayInSendingEventDisplayEmergencyMessage;
    }

    private boolean isHandoverTypeValid(int handoverType) {
        if (handoverType == EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS
                || handoverType == EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911) {
            return true;
        }
        return false;
    }

    /**
     * This function is used by {@link SatelliteModemInterface} to notify
     * {@link SatelliteController} that the satellite vendor service was just connected.
     * <p>
     * {@link SatelliteController} will send requests to satellite modem to check whether it support
     * satellite and whether it is provisioned. {@link SatelliteController} will use these cached
     * values to serve requests from its clients.
     * <p>
     * Because satellite vendor service might have just come back from a crash, we need to disable
     * the satellite modem so that resources will be cleaned up and internal states will be reset.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onSatelliteServiceConnected() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("onSatelliteServiceConnected: oemEnabledSatelliteFlag is disabled");
            return;
        }

        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            plogd("onSatelliteServiceConnected");
            // Vendor service might have just come back from a crash
            moveSatelliteToOffStateAndCleanUpResources(SATELLITE_RESULT_MODEM_ERROR);
            ResultReceiver receiver = new ResultReceiver(this) {
                @Override
                protected void onReceiveResult(
                        int resultCode, Bundle resultData) {
                    plogd("onSatelliteServiceConnected.requestIsSatelliteSupported:"
                            + " resultCode=" + resultCode);
                }
            };
            requestIsSatelliteSupported(receiver);
        } else {
            plogd("onSatelliteServiceConnected: Satellite vendor service is not supported."
                    + " Ignored the event");
        }
    }

    /**
     * This function is used by {@link com.android.internal.telephony.ServiceStateTracker} to notify
     * {@link SatelliteController} that it has received a request to power on or off the cellular
     * radio modem.
     *
     * @param powerOn {@code true} means cellular radio is about to be powered on, {@code false}
     *                 means cellular modem is about to be powered off.
     */
    public void onSetCellularRadioPowerStateRequested(boolean powerOn) {
        logd("onSetCellularRadioPowerStateRequested: powerOn=" + powerOn);
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("onSetCellularRadioPowerStateRequested: oemEnabledSatelliteFlag is disabled");
            return;
        }

        synchronized (mIsRadioOnLock) {
            mRadioOffRequested = !powerOn;
        }
        if (powerOn) {
            stopWaitForCellularModemOffTimer();
        } else {
            requestSatelliteEnabled(
                    false /* enableSatellite */, false /* enableDemoMode */,
                    false /* isEmergency */,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            plogd("onSetCellularRadioPowerStateRequested: requestSatelliteEnabled"
                                    + " result=" + result);
                        }
                    });
            startWaitForCellularModemOffTimer();
        }
    }

    /**
     * This function is used by {@link com.android.internal.telephony.ServiceStateTracker} to notify
     * {@link SatelliteController} that the request to power off the cellular radio modem has
     * failed.
     */
    public void onPowerOffCellularRadioFailed() {
        logd("onPowerOffCellularRadioFailed");
        synchronized (mIsRadioOnLock) {
            mRadioOffRequested = false;
            stopWaitForCellularModemOffTimer();
        }
    }

    /**
     * Notify SMS received.
     *
     * @param subId The subId of the subscription used to receive SMS
     */
    public void onSmsReceived(int subId) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            logd("onSmsReceived: carrierRoamingNbIotNtn is disabled");
            return;
        }

        if (!isSatelliteEnabled()) {
            logd("onSmsReceived: satellite is not enabled");
            return;
        }

        int satelliteSubId = getSelectedSatelliteSubId();
        if (subId != satelliteSubId) {
            logd("onSmsReceived: SMS received " + subId
                    + ", but not satellite subscription " + satelliteSubId);
            return;
        }

        if (mDatagramController != null) {
            mDatagramController.onSmsReceived(subId);
        } else {
            logd("onSmsReceived: DatagramController is not initialized");
        }
    }

    /**
     * @return {@code true} if satellite is supported via OEM on the device,
     * {@code  false} otherwise.
     */
    public boolean isSatelliteSupportedViaOem() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("isSatelliteSupported: oemEnabledSatelliteFlag is disabled");
            return false;
        }
        Boolean supported = isSatelliteSupportedViaOemInternal();
        return (supported != null ? supported : false);
    }

    /**
     * @param subId Subscription ID.
     * @return The list of satellite PLMNs used for connecting to satellite networks.
     */
    @NonNull
    public List<String> getSatellitePlmnsForCarrier(int subId) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("getSatellitePlmnsForCarrier: carrierEnabledSatelliteFlag is disabled");
            return new ArrayList<>();
        }

        if (!isSatelliteSupportedViaCarrier(subId)) {
            logd("Satellite for carrier is not supported.");
            return new ArrayList<>();
        }

        synchronized (mSupportedSatelliteServicesLock) {
            return mMergedPlmnListPerCarrier.get(subId, new ArrayList<>()).stream().toList();
        }
    }

    /**
     * @param subId Subscription ID.
     * @param plmn The satellite plmn.
     * @return The list of services supported by the carrier associated with the {@code subId} for
     * the satellite network {@code plmn}.
     */
    @NonNull
    public List<Integer> getSupportedSatelliteServices(int subId, String plmn) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("getSupportedSatelliteServices: carrierEnabledSatelliteFlag is disabled");
            return new ArrayList<>();
        }
        synchronized (mSupportedSatelliteServicesLock) {
            if (mSatelliteServicesSupportedByCarriers.containsKey(subId)) {
                Map<String, Set<Integer>> supportedServices =
                        mSatelliteServicesSupportedByCarriers.get(subId);
                if (supportedServices != null && supportedServices.containsKey(plmn)) {
                    return new ArrayList<>(supportedServices.get(plmn));
                } else {
                    loge("getSupportedSatelliteServices: subId=" + subId + ", supportedServices "
                            + "does not contain key plmn=" + plmn);
                }
            } else {
                loge("getSupportedSatelliteServices: mSatelliteServicesSupportedByCarriers does "
                        + "not contain key subId=" + subId);
            }

            /* Returns default capabilities when carrier config does not contain service
               capabilities for the given plmn */
            PersistableBundle config = getPersistableBundle(subId);
            int [] defaultCapabilities = config.getIntArray(
                    KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY);
            if (defaultCapabilities == null) {
                logd("getSupportedSatelliteServices: defaultCapabilities is null");
                return new ArrayList<>();
            }
            List<Integer> capabilitiesList = Arrays.stream(
                    defaultCapabilities).boxed().collect(Collectors.toList());
            logd("getSupportedSatelliteServices: subId=" + subId
                    + ", supportedServices does not contain key plmn=" + plmn
                    + ", return default values " + capabilitiesList);
            return capabilitiesList;
        }
    }

    /**
     * Check whether satellite modem has to attach to a satellite network before sending/receiving
     * datagrams.
     *
     * @return {@code true} if satellite attach is required, {@code false} otherwise.
     */
    public boolean isSatelliteAttachRequired() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("isSatelliteAttachRequired: oemEnabledSatelliteFlag is disabled");
            return false;
        }

        synchronized (mSatelliteCapabilitiesLock) {
            if (mSatelliteCapabilities == null) {
                ploge("isSatelliteAttachRequired: mSatelliteCapabilities is null");
                return false;
            }
            if (mSatelliteCapabilities.getSupportedRadioTechnologies().contains(
                    SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN)) {
                return true;
            }
            return false;
        }
    }

    /**
     * @return {@code true} if satellite is supported via carrier by any subscription on the device,
     * {@code false} otherwise.
     */
    public boolean isSatelliteSupportedViaCarrier() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("isSatelliteSupportedViaCarrier: carrierEnabledSatelliteFlag is disabled");
            return false;
        }
        for (Phone phone : PhoneFactory.getPhones()) {
            if (isSatelliteSupportedViaCarrier(phone.getSubId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if satellite emergency messaging is supported via carrier by any
     * subscription on the device, {@code false} otherwise.
     */
    public boolean isSatelliteEmergencyMessagingSupportedViaCarrier() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("isSatelliteEmergencyMessagingSupportedViaCarrier: carrierEnabledSatelliteFlag is"
                    + " disabled");
            return false;
        }
        for (Phone phone : PhoneFactory.getPhones()) {
            if (isSatelliteEmergencyMessagingSupportedViaCarrier(phone.getSubId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSatelliteEmergencyMessagingSupportedViaCarrier(int subId) {
        if (!isSatelliteSupportedViaCarrier(subId)) {
            return false;
        }
        PersistableBundle config = getPersistableBundle(subId);
        return config.getBoolean(KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL);
    }

    /**
     * @return {@code Pair<true, subscription ID>} if any subscription on the device is connected to
     * satellite, {@code Pair<false, null>} otherwise.
     */
    Pair<Boolean, Integer> isUsingNonTerrestrialNetworkViaCarrier() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("isUsingNonTerrestrialNetwork: carrierEnabledSatelliteFlag is disabled");
            return new Pair<>(false, null);
        }
        for (Phone phone : PhoneFactory.getPhones()) {
            ServiceState serviceState = phone.getServiceState();
            if (serviceState != null && serviceState.isUsingNonTerrestrialNetwork()) {
                return new Pair<>(true, phone.getSubId());
            }
        }
        return new Pair<>(false, null);
    }

    /**
     * @return {@code true} if the device is connected to satellite via any carrier within the
     * {@link CarrierConfigManager#KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT}
     * duration, {@code false} otherwise.
     */
    public boolean isSatelliteConnectedViaCarrierWithinHysteresisTime() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("isSatelliteConnectedViaCarrierWithinHysteresisTime: carrierEnabledSatelliteFlag"
                    + " is disabled");
            return false;
        }
        if (isUsingNonTerrestrialNetworkViaCarrier().first) {
            return true;
        }
        for (Phone phone : PhoneFactory.getPhones()) {
            if (isInSatelliteModeForCarrierRoaming(phone)) {
                logd("isSatelliteConnectedViaCarrierWithinHysteresisTime: "
                        + "subId:" + phone.getSubId()
                        + " is connected to satellite within hysteresis time");
                return true;
            }
        }
        return false;
    }

    /**
     * Get whether device is connected to satellite via carrier.
     *
     * @param phone phone object
     * @return {@code true} if the device is connected to satellite using the phone within the
     * {@link CarrierConfigManager#KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT}
     * duration, {@code false} otherwise.
     */
    public boolean isInSatelliteModeForCarrierRoaming(@Nullable Phone phone) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("isInSatelliteModeForCarrierRoaming: carrierEnabledSatelliteFlag is disabled");
            return false;
        }

        if (phone == null) {
            return false;
        }

        int subId = phone.getSubId();
        if (!isSatelliteSupportedViaCarrier(subId)) {
            return false;
        }

        ServiceState serviceState = phone.getServiceState();
        if (serviceState == null) {
            return false;
        }

        if (serviceState.isUsingNonTerrestrialNetwork()) {
            return true;
        }

        if (getWwanIsInService(serviceState)
                || serviceState.getState() == ServiceState.STATE_POWER_OFF) {
            // Device is connected to terrestrial network which has coverage or radio is turned off
            resetCarrierRoamingSatelliteModeParams(subId);
            return false;
        }

        synchronized (mSatelliteConnectedLock) {
            Long lastDisconnectedTime = mLastSatelliteDisconnectedTimesMillis.get(subId);
            long satelliteConnectionHysteresisTime =
                    getSatelliteConnectionHysteresisTimeMillis(subId);
            if (lastDisconnectedTime != null
                    && (getElapsedRealtime() - lastDisconnectedTime)
                    <= satelliteConnectionHysteresisTime) {
                logd("isInSatelliteModeForCarrierRoaming: " + "subId:" + subId
                        + " is connected to satellite within hysteresis time");
                return true;
            } else {
                resetCarrierRoamingSatelliteModeParams(subId);
                return false;
            }
        }
    }

    /**
     * @return {@code true} if should exit satellite mode unless already sent a datagram in this
     * esos session.
     */
    public boolean shouldTurnOffCarrierSatelliteForEmergencyCall() {
        synchronized (mSatellitePhoneLock) {
            if (mSatellitePhone == null) return false;
            return !mDatagramController.isEmergencyCommunicationEstablished()
                    && getConfigForSubId(mSatellitePhone.getSubId()).getBoolean(
                    KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL);
        }
    }

    /**
     * Return whether the satellite request is for an emergency or not.
     *
     * @return {@code true} if the satellite request is for an emergency and
     *                      {@code false} otherwise.
     */
    public boolean getRequestIsEmergency() {
        return mIsEmergency;
    }

    /**
     * @return {@code true} if device is in carrier roaming nb iot ntn mode,
     * else {@return false}
     */
    public boolean isInCarrierRoamingNbIotNtn() {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("isInCarrierRoamingNbIotNtn: carrier roaming nb iot ntn "
                    + "feature flag is disabled");
            return false;
        }

        if (!isSatelliteEnabled()) {
            plogd("iisInCarrierRoamingNbIotNtn: satellite is disabled");
            return false;
        }

        Phone satellitePhone = getSatellitePhone();
        if (!isCarrierRoamingNtnEligible(satellitePhone)) {
            plogd("isInCarrierRoamingNbIotNtn: not carrier roaming ntn eligible.");
            return false;
        }
        plogd("isInCarrierRoamingNbIotNtn: carrier roaming ntn eligible.");
        return true;
    }

    /**
     * @return {@code true} if phone is in carrier roaming nb iot ntn mode,
     * else {@return false}
     */
    public boolean isInCarrierRoamingNbIotNtn(@NonNull Phone phone) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("isInCarrierRoamingNbIotNtn: carrier roaming nb iot ntn "
                    + "feature flag is disabled");
            return false;
        }

        if (!isSatelliteEnabled()) {
            plogd("iisInCarrierRoamingNbIotNtn: satellite is disabled");
            return false;
        }

        if (!isCarrierRoamingNtnEligible(phone)) {
            plogd("isInCarrierRoamingNbIotNtn: phone associated with subId "
                      + phone.getSubId()
                      + " is not carrier roaming ntn eligible.");
            return false;
        }
        plogd("isInCarrierRoamingNbIotNtn: carrier roaming ntn eligible for phone"
                  + " associated with subId " + phone.getSubId());
        return true;
    }

    /**
     * Return capabilities of carrier roaming satellite network.
     *
     * @param phone phone object
     * @return The list of services supported by the carrier associated with the {@code subId}
     */
    @NonNull
    public List<Integer> getCapabilitiesForCarrierRoamingSatelliteMode(Phone phone) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("getCapabilitiesForCarrierRoamingSatelliteMode: carrierEnabledSatelliteFlag"
                    + " is disabled");
            return new ArrayList<>();
        }

        synchronized (mSatelliteConnectedLock) {
            int subId = phone.getSubId();
            if (mSatModeCapabilitiesForCarrierRoaming.containsKey(subId)) {
                return mSatModeCapabilitiesForCarrierRoaming.get(subId);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Request to get the {@link SatelliteSessionStats} of the satellite service.
     *
     * @param subId The subId of the subscription to the satellite session stats for.
     * @param result The result receiver that returns the {@link SatelliteSessionStats}
     *               if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteSessionStats(int subId, @NonNull ResultReceiver result) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            return;
        }
        mSessionMetricsStats.requestSatelliteSessionStats(subId, result);
    }

    /**
     * Get the carrier-enabled emergency call wait for connection timeout millis
     */
    public long getCarrierEmergencyCallWaitForConnectionTimeoutMillis() {
        long maxTimeoutMillis = 0;
        for (Phone phone : PhoneFactory.getPhones()) {
            if (!isSatelliteEmergencyMessagingSupportedViaCarrier(phone.getSubId())) {
                continue;
            }

            int timeoutMillis =
                    getCarrierEmergencyCallWaitForConnectionTimeoutMillis(phone.getSubId());
            // Prioritize getting the timeout duration from the phone that is in satellite mode
            // with carrier roaming
            if (isInSatelliteModeForCarrierRoaming(phone)) {
                return timeoutMillis;
            }
            if (maxTimeoutMillis < timeoutMillis) {
                maxTimeoutMillis = timeoutMillis;
            }
        }
        if (maxTimeoutMillis != 0) {
            return maxTimeoutMillis;
        }
        return DEFAULT_CARRIER_EMERGENCY_CALL_WAIT_FOR_CONNECTION_TIMEOUT_MILLIS;
    }

    private int getCarrierEmergencyCallWaitForConnectionTimeoutMillis(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return config.getInt(KEY_EMERGENCY_CALL_TO_SATELLITE_T911_HANDOVER_TIMEOUT_MILLIS_INT);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Register the handler for SIM Refresh notifications.
     * @param handler Handler for notification message.
     * @param what User-defined message code.
     */
    public void registerIccRefresh(Handler handler, int what) {
        for (Phone phone : PhoneFactory.getPhones()) {
            CommandsInterface ci = phone.mCi;
            ci.registerForIccRefresh(handler, what, null);
        }
    }

    /**
     * Unregister the handler for SIM Refresh notifications.
     * @param handler Handler for notification message.
     */
    public void unRegisterIccRefresh(Handler handler) {
        for (Phone phone : PhoneFactory.getPhones()) {
            CommandsInterface ci = phone.mCi;
            ci.unregisterForIccRefresh(handler);
        }
    }

    /**
     * To use the satellite service, update the EntitlementStatus and the PlmnAllowedList after
     * receiving the satellite configuration from the entitlement server. If satellite
     * entitlement is enabled, enable satellite for the carrier. Otherwise, disable satellite.
     *
     * @param subId              subId
     * @param entitlementEnabled {@code true} Satellite service enabled
     * @param allowedPlmnList    plmn allowed list to use the satellite service
     * @param barredPlmnList    plmn barred list to pass the modem
     * @param callback           callback for accept
     */
    public void onSatelliteEntitlementStatusUpdated(int subId, boolean entitlementEnabled,
            @Nullable List<String> allowedPlmnList, @Nullable List<String> barredPlmnList,
            @Nullable IIntegerConsumer callback) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("onSatelliteEntitlementStatusUpdated: carrierEnabledSatelliteFlag is not enabled");
            return;
        }

        if (callback == null) {
            callback = new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    logd("updateSatelliteEntitlementStatus:" + result);
                }
            };
        }
        if (allowedPlmnList == null) {
            allowedPlmnList = new ArrayList<>();
        }
        if (barredPlmnList == null) {
            barredPlmnList = new ArrayList<>();
        }
        logd("onSatelliteEntitlementStatusUpdated subId=" + subId + ", entitlementEnabled="
                + entitlementEnabled + ", allowedPlmnList=["
                + String.join(",", allowedPlmnList) + "]" + ", barredPlmnList=["
                + String.join(",", barredPlmnList) + "]");

        synchronized (mSupportedSatelliteServicesLock) {
            if (mSatelliteEntitlementStatusPerCarrier.get(subId, false) != entitlementEnabled) {
                logd("update the carrier satellite enabled to " + entitlementEnabled);
                mSatelliteEntitlementStatusPerCarrier.put(subId, entitlementEnabled);
                mCarrierRoamingSatelliteControllerStats.reportIsDeviceEntitled(entitlementEnabled);
                if (hasMessages(EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT)) {
                    removeMessages(EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT);
                    sendMessageDelayed(obtainMessage(
                                    EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT),
                            WAIT_FOR_REPORT_ENTITLED_MERTICS_TIMEOUT_MILLIS);
                }
                try {
                    mSubscriptionManagerService.setSubscriptionProperty(subId,
                            SATELLITE_ENTITLEMENT_STATUS, entitlementEnabled ? "1" : "0");
                } catch (IllegalArgumentException | SecurityException e) {
                    loge("onSatelliteEntitlementStatusUpdated: setSubscriptionProperty, e=" + e);
                }
            }

            if (isValidPlmnList(allowedPlmnList) && isValidPlmnList(barredPlmnList)) {
                mMergedPlmnListPerCarrier.remove(subId);
                mEntitlementPlmnListPerCarrier.put(subId, allowedPlmnList);
                mEntitlementBarredPlmnListPerCarrier.put(subId, barredPlmnList);
                updatePlmnListPerCarrier(subId);
                configureSatellitePlmnForCarrier(subId);
                mSubscriptionManagerService.setSatelliteEntitlementPlmnList(subId, allowedPlmnList);
            } else {
                loge("onSatelliteEntitlementStatusUpdated: either invalid allowedPlmnList "
                        + "or invalid barredPlmnList");
            }

            if (mSatelliteEntitlementStatusPerCarrier.get(subId, false)) {
                removeAttachRestrictionForCarrier(subId,
                        SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT, callback);
            } else {
                addAttachRestrictionForCarrier(subId,
                        SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT, callback);
            }
        }
    }

    /**
     * A list of PLMNs is considered valid if either the list is empty or all PLMNs in the list
     * are valid.
     */
    private boolean isValidPlmnList(@NonNull List<String> plmnList) {
        for (String plmn : plmnList) {
            if (!TelephonyUtils.isValidPlmn(plmn)) {
                ploge("Invalid PLMN = " + plmn);
                return false;
            }
        }
        return true;
    }

    /**
     * If we have not successfully queried the satellite modem for its satellite service support,
     * we will retry the query one more time. Otherwise, we will return the cached result.
     */
    private Boolean isSatelliteSupportedViaOemInternal() {
        synchronized (mIsSatelliteSupportedLock) {
            if (mIsSatelliteSupported != null) {
                /* We have already successfully queried the satellite modem. */
                return mIsSatelliteSupported;
            }
        }
        /**
         * We have not successfully checked whether the modem supports satellite service.
         * Thus, we need to retry it now.
         */
        requestIsSatelliteSupported(
                new ResultReceiver(this) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        plogd("isSatelliteSupportedViaOemInternal.requestIsSatelliteSupported:"
                                + " resultCode=" + resultCode);
                    }
                });
        return null;
    }

    private void handleEventProvisionSatelliteServiceDone(
            @NonNull ProvisionSatelliteServiceArgument arg,
            @SatelliteManager.SatelliteResult int result) {
        plogd("handleEventProvisionSatelliteServiceDone: result="
                + result + ", subId=" + arg.subId);

        Consumer<Integer> callback = mSatelliteProvisionCallbacks.remove(arg.subId);
        if (callback == null) {
            ploge("handleEventProvisionSatelliteServiceDone: callback is null for subId="
                    + arg.subId);
            mProvisionMetricsStats
                    .setResultCode(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE)
                    .setIsProvisionRequest(true)
                    .setCarrierId(getSatelliteCarrierId())
                    .reportProvisionMetrics();
            mControllerMetricsStats.reportProvisionCount(
                    SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
            return;
        }
        if (result == SATELLITE_RESULT_SUCCESS
                || result == SATELLITE_RESULT_REQUEST_NOT_SUPPORTED) {
            persistOemEnabledSatelliteProvisionStatus(true);
            synchronized (mSatelliteViaOemProvisionLock) {
                mIsSatelliteViaOemProvisioned = true;
            }
            callback.accept(SATELLITE_RESULT_SUCCESS);
            handleEventSatelliteProvisionStateChanged(true);
        } else {
            callback.accept(result);
        }
        mProvisionMetricsStats.setResultCode(result)
                .setIsProvisionRequest(true)
                .setCarrierId(getSatelliteCarrierId())
                .reportProvisionMetrics();
        mControllerMetricsStats.reportProvisionCount(result);
    }

    private void handleEventDeprovisionSatelliteServiceDone(
            @NonNull ProvisionSatelliteServiceArgument arg,
            @SatelliteManager.SatelliteResult int result) {
        if (arg == null) {
            ploge("handleEventDeprovisionSatelliteServiceDone: arg is null");
            return;
        }
        plogd("handleEventDeprovisionSatelliteServiceDone: result="
                + result + ", subId=" + arg.subId);

        if (result == SATELLITE_RESULT_SUCCESS
                || result == SATELLITE_RESULT_REQUEST_NOT_SUPPORTED) {
            persistOemEnabledSatelliteProvisionStatus(false);
            synchronized (mSatelliteViaOemProvisionLock) {
                mIsSatelliteViaOemProvisioned = false;
            }
            if (arg.callback != null) {
                arg.callback.accept(SATELLITE_RESULT_SUCCESS);
            }
            handleEventSatelliteProvisionStateChanged(false);
        } else if (arg.callback != null) {
            arg.callback.accept(result);
        }
        mProvisionMetricsStats.setResultCode(result)
                .setIsProvisionRequest(false)
                .setCarrierId(getSatelliteCarrierId())
                .reportProvisionMetrics();
        mControllerMetricsStats.reportDeprovisionCount(result);
    }

    private void handleStartSatelliteTransmissionUpdatesDone(@NonNull AsyncResult ar) {
        SatelliteControllerHandlerRequest request = (SatelliteControllerHandlerRequest) ar.userObj;
        SatelliteTransmissionUpdateArgument arg =
                (SatelliteTransmissionUpdateArgument) request.argument;
        int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                "handleStartSatelliteTransmissionUpdatesDone");
        arg.errorCallback.accept(errorCode);

        if (errorCode != SATELLITE_RESULT_SUCCESS) {
            mPointingAppController.setStartedSatelliteTransmissionUpdates(false);
            // We need to remove the callback from our listener list since the caller might not call
            // stopSatelliteTransmissionUpdates to unregister the callback in case of failure.
            mPointingAppController.unregisterForSatelliteTransmissionUpdates(arg.subId,
                    arg.errorCallback, arg.callback);
        } else {
            mPointingAppController.setStartedSatelliteTransmissionUpdates(true);
        }
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     * @param phone phone object used to perform the operation.
     */
    private void sendRequestAsync(int command, @NonNull Object argument, @Nullable Phone phone) {
        SatelliteControllerHandlerRequest request = new SatelliteControllerHandlerRequest(
                argument, phone);
        Message msg = this.obtainMessage(command, request);
        msg.sendToTarget();
    }

    /**
     * Check if satellite is provisioned for a subscription on the device.
     * @return true if satellite is provisioned on the given subscription else return false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Nullable
    protected Boolean isSatelliteViaOemProvisioned() {
        synchronized (mSatelliteViaOemProvisionLock) {
            if (mOverriddenIsSatelliteViaOemProvisioned != null) {
                return mOverriddenIsSatelliteViaOemProvisioned;
            }

            if (mIsSatelliteViaOemProvisioned == null) {
                mIsSatelliteViaOemProvisioned = getPersistedOemEnabledSatelliteProvisionStatus();
            }
            return mIsSatelliteViaOemProvisioned;
        }
    }

    private void handleSatelliteEnabled(SatelliteControllerHandlerRequest request) {
        RequestSatelliteEnabledArgument argument =
                (RequestSatelliteEnabledArgument) request.argument;
        handlePersistentLoggingOnSessionStart(argument);
        selectBindingSatelliteSubscription();
        SatelliteModemEnableRequestAttributes enableRequestAttributes =
                    createModemEnableRequest(argument);
        if (enableRequestAttributes == null) {
            plogw("handleSatelliteEnabled: enableRequestAttributes is null");
            sendErrorAndReportSessionMetrics(
                    SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE, argument.callback);
            synchronized (mSatelliteEnabledRequestLock) {
                if (argument.enableSatellite) {
                    mSatelliteEnabledRequest = null;
                } else {
                    mSatelliteDisabledRequest = null;
                }
            }
            return;
        }

        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnablementStarted(argument.enableSatellite);
        } else {
            ploge("handleSatelliteEnabled: mSatelliteSessionController is not initialized yet");
        }

        /* Framework will send back the disable result to the requesting client only after receiving
         * both confirmation for the disable request from modem, and OFF state from modem if the
         * modem is not in OFF state.
         */
        if (!argument.enableSatellite && mSatelliteModemInterface.isSatelliteServiceSupported()) {
            synchronized (mSatelliteEnabledRequestLock) {
                mWaitingForDisableSatelliteModemResponse = true;
                if (!isSatelliteDisabled()) mWaitingForSatelliteModemOff = true;
            }
        }

        Message onCompleted = obtainMessage(EVENT_SET_SATELLITE_ENABLED_DONE, request);
        mSatelliteModemInterface.requestSatelliteEnabled(
                enableRequestAttributes, onCompleted);
        startWaitForSatelliteEnablingResponseTimer(argument);
        // Logs satellite session timestamps for session metrics
        if (argument.enableSatellite) {
            mSessionStartTimeStamp = System.currentTimeMillis();
        }
        mSessionProcessingTimeStamp = System.currentTimeMillis();
    }

    /** Get the request attributes that modem needs to enable/disable satellite */
    @Nullable private SatelliteModemEnableRequestAttributes createModemEnableRequest(
            @NonNull RequestSatelliteEnabledArgument arg) {
        int subId = getSelectedSatelliteSubId();
        SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(subId);
        if (subInfo == null) {
            loge("createModemEnableRequest: no SubscriptionInfo found for subId=" + subId);
            return null;
        }
        String iccid = subInfo.getIccId();
        String apn = getConfigForSubId(subId).getString(KEY_SATELLITE_NIDD_APN_NAME_STRING, "");
        return new SatelliteModemEnableRequestAttributes(
                arg.enableSatellite, arg.enableDemoMode, arg.isEmergency,
                new SatelliteSubscriptionInfo(iccid, apn));
    }

    private void handleRequestSatelliteAttachRestrictionForCarrierCmd(
            SatelliteControllerHandlerRequest request) {
        RequestHandleSatelliteAttachRestrictionForCarrierArgument argument =
                (RequestHandleSatelliteAttachRestrictionForCarrierArgument) request.argument;

        if (argument.reason == SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER) {
            if (!persistSatelliteAttachEnabledForCarrierSetting(argument.subId)) {
                argument.callback.accept(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
                return;
            }
        }

        evaluateEnablingSatelliteForCarrier(argument.subId, argument.reason, argument.callback);
    }

    private void updateSatelliteSupportedState(boolean supported) {
        synchronized (mIsSatelliteSupportedLock) {
            mIsSatelliteSupported = supported;
        }
        mSatelliteSessionController = SatelliteSessionController.make(
                mContext, getLooper(), mFeatureFlags, supported);
        plogd("updateSatelliteSupportedState: create a new SatelliteSessionController because "
                + "satellite supported state has changed to " + supported);

        if (supported) {
            registerForPendingDatagramCount();
            registerForSatelliteModemStateChanged();
            registerForNtnSignalStrengthChanged();
            registerForCapabilitiesChanged();
            registerForSatelliteRegistrationFailure();

            requestIsSatelliteProvisioned(
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            plogd("updateSatelliteSupportedState.requestIsSatelliteProvisioned: "
                                    + "resultCode=" + resultCode + ", resultData=" + resultData);
                            requestSatelliteEnabled(false, false, false,
                                    new IIntegerConsumer.Stub() {
                                        @Override
                                        public void accept(int result) {
                                            plogd("updateSatelliteSupportedState."
                                                    + "requestSatelliteEnabled: result=" + result);
                                        }
                                    });
                        }
                    });
            requestSatelliteCapabilities(
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            plogd("updateSatelliteSupportedState.requestSatelliteCapabilities: "
                                    + "resultCode=" + resultCode + ", resultData=" + resultData);
                        }
                    });
        }
        registerForSatelliteSupportedStateChanged();
        selectBindingSatelliteSubscription();
    }

    private void updateSatelliteEnabledState(boolean enabled, String caller) {
        synchronized (mIsSatelliteEnabledLock) {
            mIsSatelliteEnabled = enabled;
        }
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnabledStateChanged(enabled);
            mSatelliteSessionController.setDemoMode(mIsDemoModeEnabled);
        } else {
            ploge(caller + ": mSatelliteSessionController is not initialized yet");
        }
        if (!enabled) {
            mIsModemEnabledReportingNtnSignalStrength.set(false);
        }
    }

    private void registerForPendingDatagramCount() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForPendingDatagramCountWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForPendingDatagrams(
                        this, EVENT_PENDING_DATAGRAMS, null);
                mRegisteredForPendingDatagramCountWithSatelliteService.set(true);
            }
        }
    }

    private void registerForSatelliteModemStateChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteModemStateChangedWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForSatelliteModemStateChanged(
                        this, EVENT_SATELLITE_MODEM_STATE_CHANGED, null);
                mRegisteredForSatelliteModemStateChangedWithSatelliteService.set(true);
            }
        }
    }

    private void registerForNtnSignalStrengthChanged() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("registerForNtnSignalStrengthChanged: oemEnabledSatelliteFlag is disabled");
            return;
        }

        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForNtnSignalStrengthChanged.get()) {
                mSatelliteModemInterface.registerForNtnSignalStrengthChanged(
                        this, EVENT_NTN_SIGNAL_STRENGTH_CHANGED, null);
                mRegisteredForNtnSignalStrengthChanged.set(true);
            }
        }
    }

    private void registerForCapabilitiesChanged() {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("registerForCapabilitiesChanged: oemEnabledSatelliteFlag is disabled");
            return;
        }

        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteCapabilitiesChanged.get()) {
                mSatelliteModemInterface.registerForSatelliteCapabilitiesChanged(
                        this, EVENT_SATELLITE_CAPABILITIES_CHANGED, null);
                mRegisteredForSatelliteCapabilitiesChanged.set(true);
            }
        }
    }

    private void registerForSatelliteSupportedStateChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteSupportedStateChanged.get()) {
                mSatelliteModemInterface.registerForSatelliteSupportedStateChanged(
                        this, EVENT_SATELLITE_SUPPORTED_STATE_CHANGED, null);
                mRegisteredForSatelliteSupportedStateChanged.set(true);
            }
        }
    }

    private void registerForSatelliteRegistrationFailure() {
        if (mFeatureFlags.carrierRoamingNbIotNtn()) {
            if (!mRegisteredForSatelliteRegistrationFailure.get()) {
                mSatelliteModemInterface.registerForSatelliteRegistrationFailure(this,
                        EVENT_SATELLITE_REGISTRATION_FAILURE, null);
                mRegisteredForSatelliteRegistrationFailure.set(true);
            }
        }
    }

    private void handleEventSatelliteProvisionStateChanged(boolean provisioned) {
        plogd("handleSatelliteProvisionStateChangedEvent: provisioned=" + provisioned);

        synchronized (mSatelliteViaOemProvisionLock) {
            persistOemEnabledSatelliteProvisionStatus(provisioned);
            mIsSatelliteViaOemProvisioned = provisioned;
        }

        List<ISatelliteProvisionStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteProvisionStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteProvisionStateChanged(provisioned);
            } catch (RemoteException e) {
                plogd("handleSatelliteProvisionStateChangedEvent RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteProvisionStateChangedListeners.remove(listener.asBinder());
        });
    }

    private boolean updateSatelliteSubscriptionProvisionState(List<SatelliteSubscriberInfo> newList,
            boolean provisioned) {
        logd("updateSatelliteSubscriptionProvisionState: List=" + newList + " , provisioned="
                + provisioned);
        boolean provisionChanged = false;
        synchronized (mSatelliteTokenProvisionedLock) {
            for (SatelliteSubscriberInfo subscriberInfo : newList) {
                if (mProvisionedSubscriberId.getOrDefault(subscriberInfo.getSubscriberId(), false)
                        == provisioned) {
                    continue;
                }
                provisionChanged = true;
                mProvisionedSubscriberId.put(subscriberInfo.getSubscriberId(), provisioned);
                int subId = mSubscriberIdPerSub.getOrDefault(subscriberInfo.getSubscriberId(),
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                try {
                    mSubscriptionManagerService.setIsSatelliteProvisionedForNonIpDatagram(subId,
                            provisioned);
                    plogd("updateSatelliteSubscriptionProvisionState: set Provision state to db "
                            + "subId=" + subId);
                } catch (IllegalArgumentException | SecurityException ex) {
                    ploge("setIsSatelliteProvisionedForNonIpDatagram: subId=" + subId + ", ex="
                            + ex);
                }
            }
        }
        return provisionChanged;
    }

    private void handleEventSatelliteSubscriptionProvisionStateChanged() {
        List<SatelliteSubscriberProvisionStatus> informList =
                getPrioritizedSatelliteSubscriberProvisionStatusList();
        plogd("handleEventSatelliteSubscriptionProvisionStateChanged: " + informList);
        notifySatelliteSubscriptionProvisionStateChanged(informList);
        // Report updated provisioned status
        synchronized (mSatelliteTokenProvisionedLock) {
            boolean isProvisioned = !mProvisionedSubscriberId.isEmpty()
                    && mProvisionedSubscriberId.containsValue(Boolean.TRUE);
            mControllerMetricsStats.setIsProvisioned(isProvisioned);
        }
        selectBindingSatelliteSubscription();
        handleStateChangedForCarrierRoamingNtnEligibility();
    }

    private void notifySatelliteSubscriptionProvisionStateChanged(
            @NonNull List<SatelliteSubscriberProvisionStatus> list) {
        List<ISatelliteProvisionStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteProvisionStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteSubscriptionProvisionStateChanged(list);
            } catch (RemoteException e) {
                plogd("notifySatelliteSubscriptionProvisionStateChanged: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteProvisionStateChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteModemStateChanged(
            @SatelliteManager.SatelliteModemState int state) {
        plogd("handleEventSatelliteModemStateChanged: state=" + state);
        if (state == SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE
                || state == SatelliteManager.SATELLITE_MODEM_STATE_OFF) {
            synchronized (mSatelliteEnabledRequestLock) {
                if (!mWaitingForDisableSatelliteModemResponse) {
                    moveSatelliteToOffStateAndCleanUpResources(
                            SATELLITE_RESULT_SUCCESS);
                } else {
                    notifyModemStateChangedToSessionController(
                            SatelliteManager.SATELLITE_MODEM_STATE_OFF);
                }
                mWaitingForSatelliteModemOff = false;
            }
        } else {
            if (isSatelliteEnabledOrBeingEnabled() || isSatelliteBeingDisabled()) {
                notifyModemStateChangedToSessionController(state);
            } else {
                // Telephony framework and modem are out of sync. We need to disable modem
                synchronized (mSatelliteEnabledRequestLock) {
                    plogw("Satellite modem is in a bad state. Disabling satellite modem now ...");
                    Consumer<Integer> result = integer -> plogd(
                            "handleEventSatelliteModemStateChanged: disabling satellite result="
                            + integer);
                    mSatelliteDisabledRequest = new RequestSatelliteEnabledArgument(
                            false /* enableSatellite */, false /* enableDemoMode */,
                            false /* isEmergency */, result);
                    sendRequestAsync(CMD_SET_SATELLITE_ENABLED, mSatelliteDisabledRequest, null);
                }
            }
        }
    }

    private void notifyModemStateChangedToSessionController(
            @SatelliteManager.SatelliteModemState int state) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteModemStateChanged(state);
        } else {
            ploge("notifyModemStateChangedToSessionController: mSatelliteSessionController is "
                    + "null");
        }
    }

    private void handleEventNtnSignalStrengthChanged(NtnSignalStrength ntnSignalStrength) {
        logd("handleEventNtnSignalStrengthChanged: ntnSignalStrength=" + ntnSignalStrength);
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            logd("handleEventNtnSignalStrengthChanged: oemEnabledSatelliteFlag is disabled");
            return;
        }

        synchronized (mNtnSignalsStrengthLock) {
            mNtnSignalStrength = ntnSignalStrength;
        }
        mSessionMetricsStats.updateMaxNtnSignalStrengthLevel(ntnSignalStrength.getLevel());

        List<INtnSignalStrengthCallback> deadCallersList = new ArrayList<>();
        mNtnSignalStrengthChangedListeners.values().forEach(listener -> {
            try {
                listener.onNtnSignalStrengthChanged(ntnSignalStrength);
            } catch (RemoteException e) {
                plogd("handleEventNtnSignalStrengthChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mNtnSignalStrengthChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteCapabilitiesChanged(SatelliteCapabilities capabilities) {
        plogd("handleEventSatelliteCapabilitiesChanged()");
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("handleEventSatelliteCapabilitiesChanged: oemEnabledSatelliteFlag is disabled");
            return;
        }

        synchronized (mSatelliteCapabilitiesLock) {
            mSatelliteCapabilities = capabilities;
        }

        List<ISatelliteCapabilitiesCallback> deadCallersList = new ArrayList<>();
        mSatelliteCapabilitiesChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteCapabilitiesChanged(capabilities);
            } catch (RemoteException e) {
                plogd("handleEventSatelliteCapabilitiesChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteCapabilitiesChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteSupportedStateChanged(boolean supported) {
        plogd("handleSatelliteSupportedStateChangedEvent: supported=" + supported);

        synchronized (mIsSatelliteSupportedLock) {
            if (mIsSatelliteSupported != null && mIsSatelliteSupported == supported) {
                if (DBG) {
                    plogd("current satellite support state and new supported state are matched,"
                            + " ignore update.");
                }
                return;
            }

            updateSatelliteSupportedState(supported);

            /* In case satellite has been reported as not support from modem, but satellite is
               enabled, request disable satellite. */
            synchronized (mIsSatelliteEnabledLock) {
                if (!supported && mIsSatelliteEnabled != null && mIsSatelliteEnabled) {
                    plogd("Invoke requestSatelliteEnabled(), supported=false, "
                            + "mIsSatelliteEnabled=true");
                    requestSatelliteEnabled(false /* enableSatellite */, false /* enableDemoMode */,
                            false /* isEmergency */,
                            new IIntegerConsumer.Stub() {
                                @Override
                                public void accept(int result) {
                                    plogd("handleSatelliteSupportedStateChangedEvent: request "
                                            + "satellite disable, result=" + result);
                                }
                            });

                }
            }
            mIsSatelliteSupported = supported;
        }

        List<ISatelliteSupportedStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteSupportedStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteSupportedStateChanged(supported);
            } catch (RemoteException e) {
                plogd("handleSatelliteSupportedStateChangedEvent RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteSupportedStateChangedListeners.remove(listener.asBinder());
        });
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSettingsKeyForSatelliteMode(int val) {
        plogd("setSettingsKeyForSatelliteMode val: " + val);
        Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.SATELLITE_MODE_ENABLED, val);
    }

    /**
     * Allow screen rotation temporary in rotation locked foldable device.
     * <p>
     * Temporarily allow screen rotation user to catch satellite signals properly by UI guide in
     * emergency situations. Unlock the setting value so that the screen rotation is not locked, and
     * return it to the original value when the satellite service is finished.
     * <p>
     * Note that, only the unfolded screen will be temporarily allowed screen rotation.
     *
     * @param val {@link SATELLITE_MODE_ENABLED_TRUE} if satellite mode is enabled,
     *     {@link SATELLITE_MODE_ENABLED_FALSE} satellite mode is not enabled.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSettingsKeyToAllowDeviceRotation(int val) {
        // Only allows on a foldable device type.
        if (!isFoldable(mContext, mDeviceStates)) {
            logd("setSettingsKeyToAllowDeviceRotation(" + val + "), device was not a foldable");
            return;
        }

        switch (val) {
            case SATELLITE_MODE_ENABLED_TRUE:
                mDeviceRotationLockToBackupAndRestore =
                        Settings.Secure.getString(mContentResolver,
                                Settings.Secure.DEVICE_STATE_ROTATION_LOCK);
                String unlockedRotationSettings = replaceDeviceRotationValue(
                        mDeviceRotationLockToBackupAndRestore == null
                                ? "" : mDeviceRotationLockToBackupAndRestore,
                        Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
                Settings.Secure.putString(mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK, unlockedRotationSettings);
                logd("setSettingsKeyToAllowDeviceRotation(TRUE), RotationSettings is changed"
                        + " from " + mDeviceRotationLockToBackupAndRestore
                        + " to " + unlockedRotationSettings);
                break;
            case SATELLITE_MODE_ENABLED_FALSE:
                if (mDeviceRotationLockToBackupAndRestore == null) {
                    break;
                }
                Settings.Secure.putString(mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        mDeviceRotationLockToBackupAndRestore);
                logd("setSettingsKeyToAllowDeviceRotation(FALSE), RotationSettings is restored to"
                        + mDeviceRotationLockToBackupAndRestore);
                mDeviceRotationLockToBackupAndRestore = "";
                break;
            default:
                loge("setSettingsKeyToAllowDeviceRotation(" + val + "), never reach here.");
                break;
        }
    }

    /**
     * If the device type is foldable.
     *
     * @param context context
     * @param deviceStates list of {@link DeviceState}s provided from {@link DeviceStateManager}
     * @return {@code true} if device type is foldable. {@code false} for otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isFoldable(Context context, List<DeviceState> deviceStates) {
        if (android.hardware.devicestate.feature.flags.Flags.deviceStatePropertyMigration()) {
            return deviceStates.stream().anyMatch(deviceState -> deviceState.hasProperty(
                    PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)
                    || deviceState.hasProperty(
                    PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY));
        } else {
            return context.getResources().getIntArray(R.array.config_foldedDeviceStates).length > 0;
        }
    }

    /**
     * Replaces a value of given a target key with a new value in a string of key-value pairs.
     * <p>
     * Replaces the value corresponding to the target key with a new value. If the key value is not
     * found in the device rotation information, it is not replaced.
     *
     * @param deviceRotationValue Device rotation key values separated by colon(':').
     * @param targetKey The key of the new item caller wants to add.
     * @param newValue  The value of the new item caller want to add.
     * @return A new string where all the key-value pairs.
     */
    private static String replaceDeviceRotationValue(
            @NonNull String deviceRotationValue, int targetKey, int newValue) {
        // Use list of Key-Value pair
        List<Pair<Integer, Integer>> keyValuePairs = new ArrayList<>();

        String[] pairs = deviceRotationValue.split(":");
        if (pairs.length % 2 != 0) {
            // Return without modifying. The key-value may be incorrect if length is an odd number.
            loge("The length of key-value pair do not match. Return without modification.");
            return deviceRotationValue;
        }

        // collect into keyValuePairs
        for (int i = 0; i < pairs.length; i += 2) {
            try {
                int key = Integer.parseInt(pairs[i]);
                int value = Integer.parseInt(pairs[i + 1]);
                keyValuePairs.add(new Pair<>(key, key == targetKey ? newValue : value));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Return without modifying if got exception.
                loge("got error while parsing key-value. Return without modification. e:" + e);
                return deviceRotationValue;
            }
        }

        return keyValuePairs.stream()
                .map(pair -> pair.first + ":" + pair.second) // Convert to "key:value" format
                .collect(Collectors.joining(":")); // Join pairs with colons
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean areAllRadiosDisabled() {
        synchronized (mRadioStateLock) {
            if ((mDisableBTOnSatelliteEnabled && mBTStateEnabled)
                    || (mDisableNFCOnSatelliteEnabled && mNfcStateEnabled)
                    || (mDisableWifiOnSatelliteEnabled && mWifiStateEnabled)
                    || (mDisableUWBOnSatelliteEnabled && mUwbStateEnabled)) {
                plogd("All radios are not disabled yet.");
                return false;
            }
            plogd("All radios are disabled.");
            return true;
        }
    }

    private void evaluateToSendSatelliteEnabledSuccess() {
        plogd("evaluateToSendSatelliteEnabledSuccess");
        synchronized (mSatelliteEnabledRequestLock) {
            if (areAllRadiosDisabled() && (mSatelliteEnabledRequest != null)
                    && mWaitingForRadioDisabled) {
                plogd("Sending success to callback that sent enable satellite request");
                synchronized (mIsSatelliteEnabledLock) {
                    mIsSatelliteEnabled = mSatelliteEnabledRequest.enableSatellite;
                }
                mSatelliteEnabledRequest.callback.accept(SATELLITE_RESULT_SUCCESS);
                updateSatelliteEnabledState(
                        mSatelliteEnabledRequest.enableSatellite,
                        "EVENT_SET_SATELLITE_ENABLED_DONE");
                setEmergencyMode(mSatelliteEnabledRequest.isEmergency);
                if (mSatelliteEnabledRequest.enableSatellite
                        && !mSatelliteEnabledRequest.isEmergency) {
                    plogd("Starting pointingUI needFullscreenPointingUI=" + true
                            + "mIsDemoModeEnabled=" + mIsDemoModeEnabled + ", isEmergency="
                            + mSatelliteEnabledRequest.isEmergency);
                    mPointingAppController.startPointingUI(true, mIsDemoModeEnabled, false);
                }
                mSatelliteEnabledRequest = null;
                mWaitingForRadioDisabled = false;

                if (mSatelliteEnableAttributesUpdateRequest != null) {
                    sendRequestAsync(CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES,
                            mSatelliteEnableAttributesUpdateRequest, null);
                }
            }
        }
    }

    private void resetSatelliteEnabledRequest() {
        plogd("resetSatelliteEnabledRequest");
        synchronized (mSatelliteEnabledRequestLock) {
            mSatelliteEnabledRequest = null;
            mWaitingForRadioDisabled = false;
        }
    }

    private void resetSatelliteDisabledRequest() {
        plogd("resetSatelliteDisabledRequest");
        synchronized (mSatelliteEnabledRequestLock) {
            mSatelliteDisabledRequest = null;
            mWaitingForDisableSatelliteModemResponse = false;
            mWaitingForSatelliteModemOff = false;
        }
    }

    /**
     * Move to OFF state and clean up resources.
     *
     * @param resultCode The result code will be returned to requesting clients.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void moveSatelliteToOffStateAndCleanUpResources(
            @SatelliteManager.SatelliteResult int resultCode) {
        plogd("moveSatelliteToOffStateAndCleanUpResources");
        synchronized (mIsSatelliteEnabledLock) {
            setDemoModeEnabled(false);
            handlePersistentLoggingOnSessionEnd(mIsEmergency);
            setEmergencyMode(false);
            mIsSatelliteEnabled = false;
            setSettingsKeyForSatelliteMode(SATELLITE_MODE_ENABLED_FALSE);
            setSettingsKeyToAllowDeviceRotation(SATELLITE_MODE_ENABLED_FALSE);
            abortSatelliteDisableRequest(resultCode);
            abortSatelliteEnableRequest(resultCode);
            abortSatelliteEnableAttributesUpdateRequest(resultCode);
            resetSatelliteEnabledRequest();
            resetSatelliteDisabledRequest();
            // TODO (b/361139260): Stop timer to wait for other radios off
            updateSatelliteEnabledState(
                    false, "moveSatelliteToOffStateAndCleanUpResources");
        }
        selectBindingSatelliteSubscription();
    }

    private void setDemoModeEnabled(boolean enabled) {
        mIsDemoModeEnabled = enabled;
        mDatagramController.setDemoMode(mIsDemoModeEnabled);
        plogd("setDemoModeEnabled: mIsDemoModeEnabled=" + mIsDemoModeEnabled);
    }

    private void setEmergencyMode(boolean isEmergency) {
        plogd("setEmergencyMode: mIsEmergency=" + mIsEmergency + ", isEmergency=" + isEmergency);
        if (mIsEmergency != isEmergency) {
            mIsEmergency = isEmergency;
            if (mSatelliteSessionController != null) {
                mSatelliteSessionController.onEmergencyModeChanged(mIsEmergency);
            } else {
                plogw("setEmergencyMode: mSatelliteSessionController is null");
            }
        }
    }

    private boolean isMockModemAllowed() {
        return (DEBUG || SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false));
    }

    private void configureSatellitePlmnForCarrier(int subId) {
        logd("configureSatellitePlmnForCarrier");
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("configureSatellitePlmnForCarrier: carrierEnabledSatelliteFlag is disabled");
            return;
        }
        synchronized (mSupportedSatelliteServicesLock) {
            List<String> carrierPlmnList = mMergedPlmnListPerCarrier.get(subId,
                    new ArrayList<>()).stream().toList();
            List<String> barredPlmnList = mEntitlementBarredPlmnListPerCarrier.get(subId,
                    new ArrayList<>()).stream().toList();
            int slotId = SubscriptionManager.getSlotIndex(subId);
            mSatelliteModemInterface.setSatellitePlmn(slotId, carrierPlmnList,
                    SatelliteServiceUtils.mergeStrLists(
                            carrierPlmnList, mSatellitePlmnListFromOverlayConfig, barredPlmnList),
                    obtainMessage(EVENT_SET_SATELLITE_PLMN_INFO_DONE));
        }
    }

    private void handleSetSatellitePlmnInfoDoneEvent(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        SatelliteServiceUtils.getSatelliteError(ar, "handleSetSatellitePlmnInfoCmd");
    }

    private void updateSupportedSatelliteServicesForActiveSubscriptions() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("updateSupportedSatelliteServicesForActiveSubscriptions: "
                    + "carrierEnabledSatelliteFlag is disabled");
            return;
        }

        synchronized (mSupportedSatelliteServicesLock) {
            mSatelliteServicesSupportedByCarriers.clear();
            mMergedPlmnListPerCarrier.clear();
            int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
            if (activeSubIds != null) {
                for (int subId : activeSubIds) {
                    updateSupportedSatelliteServices(subId);
                }
            } else {
                loge("updateSupportedSatelliteServicesForActiveSubscriptions: "
                        + "activeSubIds is null");
            }
        }
    }

    /**
     * If the entitlementPlmnList exist then used it.
     * Otherwise, If the carrierPlmnList exist then used it.
     */
    private void updatePlmnListPerCarrier(int subId) {
        plogd("updatePlmnListPerCarrier: subId=" + subId);
        synchronized (mSupportedSatelliteServicesLock) {
            List<String> carrierPlmnList, entitlementPlmnList;
            if (getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                    false)) {
                entitlementPlmnList = mEntitlementPlmnListPerCarrier.get(subId,
                        new ArrayList<>()).stream().toList();
                plogd("updatePlmnListPerCarrier: entitlementPlmnList="
                        + String.join(",", entitlementPlmnList)
                        + " size=" + entitlementPlmnList.size());
                if (!entitlementPlmnList.isEmpty()) {
                    mMergedPlmnListPerCarrier.put(subId, entitlementPlmnList);
                    plogd("mMergedPlmnListPerCarrier is updated by Entitlement");
                    mCarrierRoamingSatelliteControllerStats.reportConfigDataSource(
                            SatelliteConstants.CONFIG_DATA_SOURCE_ENTITLEMENT);
                    return;
                }
            }

            SatelliteConfig satelliteConfig = getSatelliteConfig();
            if (satelliteConfig != null) {
                TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
                int carrierId = tm.createForSubscriptionId(subId).getSimCarrierId();
                List<String> plmnList = satelliteConfig.getAllSatellitePlmnsForCarrier(carrierId);
                if (!plmnList.isEmpty()) {
                    plogd("mMergedPlmnListPerCarrier is updated by ConfigUpdater : "
                            + String.join(",", plmnList));
                    mMergedPlmnListPerCarrier.put(subId, plmnList);
                    mCarrierRoamingSatelliteControllerStats.reportConfigDataSource(
                            SatelliteConstants.CONFIG_DATA_SOURCE_CONFIG_UPDATER);
                    return;
                }
            }

            if (mSatelliteServicesSupportedByCarriers.containsKey(subId)
                    && mSatelliteServicesSupportedByCarriers.get(subId) != null) {
                carrierPlmnList =
                        mSatelliteServicesSupportedByCarriers.get(subId).keySet().stream().toList();
                plogd("mMergedPlmnListPerCarrier is updated by carrier config: "
                        + String.join(",", carrierPlmnList));
                mCarrierRoamingSatelliteControllerStats.reportConfigDataSource(
                        SatelliteConstants.CONFIG_DATA_SOURCE_CARRIER_CONFIG);
            } else {
                carrierPlmnList = new ArrayList<>();
                plogd("Empty mMergedPlmnListPerCarrier");
            }
            mMergedPlmnListPerCarrier.put(subId, carrierPlmnList);
        }
    }

    private void updateSupportedSatelliteServices(int subId) {
        plogd("updateSupportedSatelliteServices with subId " + subId);
        synchronized (mSupportedSatelliteServicesLock) {
            SatelliteConfig satelliteConfig = getSatelliteConfig();

            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            int carrierId = tm.createForSubscriptionId(subId).getSimCarrierId();

            if (satelliteConfig != null) {
                Map<String, Set<Integer>> supportedServicesPerPlmn =
                        satelliteConfig.getSupportedSatelliteServices(carrierId);
                if (!supportedServicesPerPlmn.isEmpty()) {
                    mSatelliteServicesSupportedByCarriers.put(subId, supportedServicesPerPlmn);
                    plogd("updateSupportedSatelliteServices using ConfigUpdater, "
                            + "supportedServicesPerPlmn = " + supportedServicesPerPlmn.size());
                    updatePlmnListPerCarrier(subId);
                    return;
                } else {
                    plogd("supportedServicesPerPlmn is empty");
                }
            }

            mSatelliteServicesSupportedByCarriers.put(
                    subId, readSupportedSatelliteServicesFromCarrierConfig(subId));
            updatePlmnListPerCarrier(subId);
            plogd("updateSupportedSatelliteServices using carrier config");
        }
    }

    @NonNull
    private List<String> readSatellitePlmnsFromOverlayConfig() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("readSatellitePlmnsFromOverlayConfig: carrierEnabledSatelliteFlag is disabled");
            return new ArrayList<>();
        }

        String[] devicePlmns = readStringArrayFromOverlayConfig(
                R.array.config_satellite_providers);
        return Arrays.stream(devicePlmns).toList();
    }

    @NonNull
    private Map<String, Set<Integer>> readSupportedSatelliteServicesFromCarrierConfig(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return SatelliteServiceUtils.parseSupportedSatelliteServices(
                config.getPersistableBundle(
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE));
    }

    @NonNull private PersistableBundle getConfigForSubId(int subId) {
        PersistableBundle config = null;
        if (mCarrierConfigManager != null) {
            try {
                config = mCarrierConfigManager.getConfigForSubId(subId,
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE,
                        KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                        KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL,
                        KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT,
                        KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                        KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                        KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL,
                        KEY_EMERGENCY_CALL_TO_SATELLITE_T911_HANDOVER_TIMEOUT_MILLIS_INT,
                        KEY_SATELLITE_ESOS_SUPPORTED_BOOL,
                        KEY_SATELLITE_ROAMING_P2P_SMS_SUPPORTED_BOOL,
                        KEY_SATELLITE_NIDD_APN_NAME_STRING,
                        KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT,
                        KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT,
                        KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT,
                        KEY_SATELLITE_ROAMING_SCREEN_OFF_INACTIVITY_TIMEOUT_SEC_INT,
                        KEY_SATELLITE_ROAMING_P2P_SMS_INACTIVITY_TIMEOUT_SEC_INT,
                        KEY_SATELLITE_ROAMING_ESOS_INACTIVITY_TIMEOUT_SEC_INT
                );
            } catch (Exception e) {
                logw("getConfigForSubId: " + e);
            }
        }
        if (config == null || config.isEmpty()) {
            config = CarrierConfigManager.getDefaultConfig();
        }
        return config;
    }

    private void handleCarrierConfigChanged(int slotIndex, int subId, int carrierId,
            int specificCarrierId) {
        plogd("handleCarrierConfigChanged(): slotIndex(" + slotIndex + "), subId("
                + subId + "), carrierId(" + carrierId + "), specificCarrierId("
                + specificCarrierId + ")");
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }

        updateCarrierConfig(subId);
        updateSatelliteESOSSupported(subId);
        updateSatelliteProvisionedStatePerSubscriberId();
        updateEntitlementPlmnListPerCarrier(subId);
        updateSupportedSatelliteServicesForActiveSubscriptions();
        processNewCarrierConfigData(subId);
        resetCarrierRoamingSatelliteModeParams(subId);
        handleStateChangedForCarrierRoamingNtnEligibility();
        sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                mEvaluateEsosProfilesPrioritizationDurationMillis);
    }

    // imsi, msisdn, default sms subId change
    private void handleSubscriptionsChanged() {
        sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                mEvaluateEsosProfilesPrioritizationDurationMillis);
    }

    private void processNewCarrierConfigData(int subId) {
        configureSatellitePlmnForCarrier(subId);
        setSatelliteAttachEnabledForCarrierOnSimLoaded(subId);
        updateRestrictReasonForEntitlementPerCarrier(subId);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void updateCarrierConfig(int subId) {
        synchronized (mCarrierConfigArrayLock) {
            mCarrierConfigArray.put(subId, getConfigForSubId(subId));
        }
    }

    /**
     * If there is no cached entitlement plmn list, read it from the db and use it if it is not an
     * empty list.
     */
    private void updateEntitlementPlmnListPerCarrier(int subId) {
        if (!getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false)) {
            plogd("don't support entitlement");
            return;
        }

        synchronized (mSupportedSatelliteServicesLock) {
            if (mEntitlementPlmnListPerCarrier.indexOfKey(subId) < 0) {
                plogd("updateEntitlementPlmnListPerCarrier: no correspondent cache, load from "
                        + "persist storage");
                List<String> entitlementPlmnList =
                        mSubscriptionManagerService.getSatelliteEntitlementPlmnList(subId);
                if (entitlementPlmnList.isEmpty()) {
                    plogd("updateEntitlementPlmnListPerCarrier: read empty list");
                    return;
                }
                plogd("updateEntitlementPlmnListPerCarrier: entitlementPlmnList="
                        + String.join(",", entitlementPlmnList));
                mEntitlementPlmnListPerCarrier.put(subId, entitlementPlmnList);
            }
        }
    }

    /**
     * When a SIM is loaded, we need to check if users has enabled satellite attach for the carrier
     * associated with the SIM, and evaluate if satellite should be enabled for the carrier.
     *
     * @param subId Subscription ID.
     */
    private void setSatelliteAttachEnabledForCarrierOnSimLoaded(int subId) {
        synchronized (mIsSatelliteEnabledLock) {
            if (isSatelliteAttachEnabledForCarrierByUser(subId)
                    && !mIsSatelliteAttachEnabledForCarrierArrayPerSub.getOrDefault(subId,
                    false)) {
                evaluateEnablingSatelliteForCarrier(subId,
                        SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, null);
            }
        }
    }

    /**
     * Update the value of SimInfo.COLUMN_SATELLITE_ESOS_SUPPORTED stored in the database based
     * on the value in the carrier config.
     */
    private void updateSatelliteESOSSupported(int subId) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            return;
        }

        boolean isSatelliteESosSupportedFromDB =
                mSubscriptionManagerService.getSatelliteESOSSupported(subId);
        boolean isSatelliteESosSupportedFromCarrierConfig = getConfigForSubId(subId).getBoolean(
                KEY_SATELLITE_ESOS_SUPPORTED_BOOL, false);
        if (isSatelliteESosSupportedFromDB != isSatelliteESosSupportedFromCarrierConfig) {
            mSubscriptionManagerService.setSatelliteESOSSupported(subId,
                    isSatelliteESosSupportedFromCarrierConfig);
            logd("updateSatelliteESOSSupported: " + isSatelliteESosSupportedFromCarrierConfig);
        }
    }

    /** If the provision state per subscriberId for the cached is not exist, check the database for
     * the corresponding value and use it. */
    private void updateSatelliteProvisionedStatePerSubscriberId() {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            return;
        }

        List<SubscriptionInfo> allSubInfos = mSubscriptionManagerService.getAllSubInfoList(
                mContext.getOpPackageName(), mContext.getAttributionTag());
        for (SubscriptionInfo info : allSubInfos) {
            int subId = info.getSubscriptionId();
            Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(
                    mSubscriptionManagerService.getSubscriptionInfo(subId));
            String subscriberId = subscriberIdPair.first;
            synchronized (mSatelliteTokenProvisionedLock) {
                if (mProvisionedSubscriberId.get(subscriberId) == null) {
                    boolean Provisioned = mSubscriptionManagerService
                            .isSatelliteProvisionedForNonIpDatagram(subId);
                    if (Provisioned) {
                        mProvisionedSubscriberId.put(subscriberId, true);
                        logd("updateSatelliteProvisionStatePerSubscriberId: " + subscriberId
                                + " set true");
                    }
                }
            }
        }
    }

    @NonNull
    private String[] readStringArrayFromOverlayConfig(@ArrayRes int id) {
        String[] strArray = null;
        try {
            strArray = mContext.getResources().getStringArray(id);
        } catch (Resources.NotFoundException ex) {
            ploge("readStringArrayFromOverlayConfig: id= " + id + ", ex=" + ex);
        }
        if (strArray == null) {
            strArray = new String[0];
        }
        return strArray;
    }

    private boolean isSatelliteSupportedViaCarrier(int subId) {
        return getConfigForSubId(subId)
                .getBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL);
    }

    /**
     * Return whether the device support P2P SMS mode from carrier config.
     *
     * @param subId Associated subscription ID
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isSatelliteRoamingP2pSmSSupported(int subId) {
        return getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ROAMING_P2P_SMS_SUPPORTED_BOOL);
    }

    /**
     * Return whether the device support ESOS mode from carrier config.
     *
     * @param subId Associated subscription ID
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isSatelliteEsosSupported(int subId) {
        return getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ESOS_SUPPORTED_BOOL);
    }

    /**
     * Return whether the device allows to turn off satellite session for emergency call.
     *
     * @param subId Associated subscription ID
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean turnOffSatelliteSessionForEmergencyCall(int subId) {
        return getConfigForSubId(subId).getBoolean(
                KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL);
    }

    private int getCarrierRoamingNtnConnectType(int subId) {
        return getConfigForSubId(subId).getInt(KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT);
    }

    protected int getCarrierRoamingNtnEmergencyCallToSatelliteHandoverType(int subId) {
        return getConfigForSubId(subId).getInt(
                KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT);
    }

    /**
     * Check if satellite attach is enabled by user for the carrier associated with the
     * {@code subId}.
     *
     * @param subId Subscription ID.
     *
     * @return Returns {@code true} if satellite attach for carrier is enabled by user,
     * {@code false} otherwise.
     */
    private boolean isSatelliteAttachEnabledForCarrierByUser(int subId) {
        synchronized (mIsSatelliteEnabledLock) {
            Set<Integer> cachedRestrictionSet =
                    mSatelliteAttachRestrictionForCarrierArray.get(subId);
            if (cachedRestrictionSet != null) {
                return !cachedRestrictionSet.contains(
                        SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER);
            } else {
                plogd("isSatelliteAttachEnabledForCarrierByUser() no correspondent cache, "
                        + "load from persist storage");
                try {
                    String enabled =
                            mSubscriptionManagerService.getSubscriptionProperty(subId,
                                    SATELLITE_ATTACH_ENABLED_FOR_CARRIER,
                                    mContext.getOpPackageName(), mContext.getAttributionTag());

                    if (enabled == null) {
                        ploge("isSatelliteAttachEnabledForCarrierByUser: invalid subId, subId="
                                + subId);
                        return false;
                    }

                    if (enabled.isEmpty()) {
                        ploge("isSatelliteAttachEnabledForCarrierByUser: no data for subId(" + subId
                                + ")");
                        return false;
                    }

                    synchronized (mIsSatelliteEnabledLock) {
                        boolean result = enabled.equals("1");
                        if (!result) {
                            mSatelliteAttachRestrictionForCarrierArray.put(subId, new HashSet<>());
                            mSatelliteAttachRestrictionForCarrierArray.get(subId).add(
                                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER);
                        }
                        return result;
                    }
                } catch (IllegalArgumentException | SecurityException ex) {
                    ploge("isSatelliteAttachEnabledForCarrierByUser: ex=" + ex);
                    return false;
                }
            }
        }
    }

    /**
     * Check whether there is any reason to restrict satellite communication for the carrier
     * associated with the {@code subId}.
     *
     * @param subId Subscription ID
     * @return {@code true} when there is at least on reason, {@code false} otherwise.
     */
    private boolean hasReasonToRestrictSatelliteCommunicationForCarrier(int subId) {
        synchronized (mIsSatelliteEnabledLock) {
            return !mSatelliteAttachRestrictionForCarrierArray
                    .getOrDefault(subId, Collections.emptySet()).isEmpty();
        }
    }

    private void updateRestrictReasonForEntitlementPerCarrier(int subId) {
        if (!getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false)) {
            plogd("don't support entitlement");
            return;
        }

        IIntegerConsumer callback = new IIntegerConsumer.Stub() {
            @Override
            public void accept(int result) {
                plogd("updateRestrictReasonForEntitlementPerCarrier:" + result);
            }
        };
        synchronized (mSupportedSatelliteServicesLock) {
            if (mSatelliteEntitlementStatusPerCarrier.indexOfKey(subId) < 0) {
                plogd("updateRestrictReasonForEntitlementPerCarrier: no correspondent cache, "
                        + "load from persist storage");
                String entitlementStatus = null;
                try {
                    entitlementStatus =
                            mSubscriptionManagerService.getSubscriptionProperty(subId,
                                    SATELLITE_ENTITLEMENT_STATUS, mContext.getOpPackageName(),
                                    mContext.getAttributionTag());
                } catch (IllegalArgumentException | SecurityException e) {
                    ploge("updateRestrictReasonForEntitlementPerCarrier, e=" + e);
                }

                if (entitlementStatus == null) {
                    ploge("updateRestrictReasonForEntitlementPerCarrier: invalid subId, subId="
                            + subId + " set to default value");
                    entitlementStatus = "0";
                }

                if (entitlementStatus.isEmpty()) {
                    ploge("updateRestrictReasonForEntitlementPerCarrier: no data for subId(" + subId
                            + "). set to default value");
                    entitlementStatus = "0";
                }
                boolean result = entitlementStatus.equals("1");
                mSatelliteEntitlementStatusPerCarrier.put(subId, result);
                mCarrierRoamingSatelliteControllerStats.reportIsDeviceEntitled(result);
                if (hasMessages(EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT)) {
                    removeMessages(EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT);
                    sendMessageDelayed(obtainMessage(
                                    EVENT_WAIT_FOR_REPORT_ENTITLED_TO_MERTICS_HYSTERESIS_TIMED_OUT),
                            WAIT_FOR_REPORT_ENTITLED_MERTICS_TIMEOUT_MILLIS);
                }
            }

            if (!mSatelliteEntitlementStatusPerCarrier.get(subId, false)) {
                addAttachRestrictionForCarrier(subId,
                        SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT, callback);
            }
        }
    }

    /**
     * Save user setting for enabling satellite attach for the carrier associated with the
     * {@code subId} to persistent storage.
     *
     * @param subId Subscription ID.
     *
     * @return {@code true} if persist successful, {@code false} otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean persistSatelliteAttachEnabledForCarrierSetting(int subId) {
        plogd("persistSatelliteAttachEnabledForCarrierSetting");
        if (!isValidSubscriptionId(subId)) {
            ploge("persistSatelliteAttachEnabledForCarrierSetting: subId is not valid,"
                    + " subId=" + subId);
            return false;
        }

        synchronized (mIsSatelliteEnabledLock) {
            try {
                mSubscriptionManagerService.setSubscriptionProperty(subId,
                        SATELLITE_ATTACH_ENABLED_FOR_CARRIER,
                        mSatelliteAttachRestrictionForCarrierArray.get(subId)
                                .contains(SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER)
                                ? "0" : "1");
            } catch (IllegalArgumentException | SecurityException ex) {
                ploge("persistSatelliteAttachEnabledForCarrierSetting, ex=" + ex);
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate whether satellite attach for carrier should be restricted.
     *
     * @param subId Subscription Id to evaluate for.
     * @return {@code true} satellite attach is restricted, {@code false} otherwise.
     */
    private boolean isSatelliteRestrictedForCarrier(int subId) {
        return !isSatelliteAttachEnabledForCarrierByUser(subId)
                || hasReasonToRestrictSatelliteCommunicationForCarrier(subId);
    }

    /**
     * Check whether satellite is enabled for carrier at modem.
     *
     * @param subId Subscription ID to check for.
     * @return {@code true} if satellite modem is enabled, {@code false} otherwise.
     */
    private boolean isSatelliteEnabledForCarrierAtModem(int subId) {
        synchronized (mIsSatelliteEnabledLock) {
            return mIsSatelliteAttachEnabledForCarrierArrayPerSub.getOrDefault(subId, false);
        }
    }

    /**
     * Evaluate whether satellite modem for carrier should be enabled or not.
     * <p>
     * Satellite will be enabled only when the following conditions are met:
     * <ul>
     * <li>Users want to enable it.</li>
     * <li>There is no satellite communication restriction, which is added by
     * {@link #addAttachRestrictionForCarrier(int, int, IIntegerConsumer)}</li>
     * <li>The carrier config {@link
     * android.telephony.CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} is set to
     * {@code true}.</li>
     * </ul>
     *
     * @param subId Subscription Id for evaluate for.
     * @param callback The callback for getting the result of enabling satellite.
     */
    private void evaluateEnablingSatelliteForCarrier(int subId, int reason,
            @Nullable Consumer<Integer> callback) {
        if (callback == null) {
            callback = errorCode -> plogd("evaluateEnablingSatelliteForCarrier: "
                    + "SetSatelliteAttachEnableForCarrier error code =" + errorCode);
        }

        if (!isSatelliteSupportedViaCarrier(subId)) {
            plogd("Satellite for carrier is not supported. Only user setting is stored");
            callback.accept(SATELLITE_RESULT_SUCCESS);
            return;
        }

        /* Request to enable or disable the satellite in the cellular modem only when the desired
        state and the current state are different. */
        boolean isSatelliteExpectedToBeEnabled = !isSatelliteRestrictedForCarrier(subId);
        if (isSatelliteExpectedToBeEnabled != isSatelliteEnabledForCarrierAtModem(subId)) {
            if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                int simSlot = SubscriptionManager.getSlotIndex(subId);
                RequestHandleSatelliteAttachRestrictionForCarrierArgument argument =
                        new RequestHandleSatelliteAttachRestrictionForCarrierArgument(subId,
                                reason, callback);
                SatelliteControllerHandlerRequest request =
                        new SatelliteControllerHandlerRequest(argument,
                                SatelliteServiceUtils.getPhone(subId));
                Message onCompleted = obtainMessage(
                        EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE, request);
                mSatelliteModemInterface.requestSetSatelliteEnabledForCarrier(simSlot,
                        isSatelliteExpectedToBeEnabled, onCompleted);
            } else {
                callback.accept(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
            }
        } else {
            callback.accept(SATELLITE_RESULT_SUCCESS);
        }
    }

    @SatelliteManager.SatelliteResult private int evaluateOemSatelliteRequestAllowed(
            boolean isProvisionRequired) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("oemEnabledSatelliteFlag is disabled");
            return SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
        }
        if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
            plogd("evaluateOemSatelliteRequestAllowed: satellite service is not supported");
            return SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
        }

        Boolean satelliteSupported = isSatelliteSupportedViaOemInternal();
        if (satelliteSupported == null) {
            plogd("evaluateOemSatelliteRequestAllowed: satelliteSupported is null");
            return SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
        }
        if (!satelliteSupported) {
            return SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
        }

        if (isProvisionRequired) {
            Boolean satelliteProvisioned = isSatelliteViaOemProvisioned();
            if (satelliteProvisioned == null) {
                plogd("evaluateOemSatelliteRequestAllowed: satelliteProvisioned is null");
                return SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
            }
            if (!satelliteProvisioned) {
                plogd("evaluateOemSatelliteRequestAllowed: satellite service is not provisioned");
                return SatelliteManager.SATELLITE_RESULT_SERVICE_NOT_PROVISIONED;
            }
        }

        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Returns the non-terrestrial network radio technology that the satellite modem currently
     * supports. If multiple technologies are available, returns the first supported technology.
     */
    @VisibleForTesting
    protected @SatelliteManager.NTRadioTechnology int getSupportedNtnRadioTechnology() {
        synchronized (mSatelliteCapabilitiesLock) {
            if (mSatelliteCapabilities != null) {
                return mSatelliteCapabilities.getSupportedRadioTechnologies()
                        .stream().findFirst().orElse(SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN);
            }
            return SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN;
        }
    }

    private void sendErrorAndReportSessionMetrics(@SatelliteManager.SatelliteResult int error,
            Consumer<Integer> result) {
        result.accept(error);
        mSessionMetricsStats.setInitializationResult(error)
                .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                .setIsDemoMode(mIsDemoModeEnabled)
                .setCarrierId(getSatelliteCarrierId())
                .reportSessionMetrics();
        mSessionStartTimeStamp = 0;
        mSessionProcessingTimeStamp = 0;
    }

    private void registerForServiceStateChanged() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            return;
        }
        for (Phone phone : PhoneFactory.getPhones()) {
            phone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
        }
    }

    private void handleEventServiceStateChanged() {
        handleStateChangedForCarrierRoamingNtnEligibility();
        handleServiceStateForSatelliteConnectionViaCarrier();
    }

    private void handleServiceStateForSatelliteConnectionViaCarrier() {
        for (Phone phone : PhoneFactory.getPhones()) {
            int subId = phone.getSubId();
            ServiceState serviceState = phone.getServiceState();
            if (serviceState == null || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                continue;
            }

            synchronized (mSatelliteConnectedLock) {
                CarrierRoamingSatelliteSessionStats sessionStats =
                        mCarrierRoamingSatelliteSessionStatsMap.get(subId);
                if (DEBUG) {
                    plogd("handleServiceStateForSatelliteConnectionViaCarrier : SubId = " + subId
                            + "  isUsingNonTerrestrialNetwork = "
                            + serviceState.isUsingNonTerrestrialNetwork());
                }
                if (serviceState.isUsingNonTerrestrialNetwork()) {
                    if (sessionStats != null) {
                        sessionStats.onSignalStrength(phone);
                        if (!mWasSatelliteConnectedViaCarrier.get(subId)) {
                            // Log satellite connection start
                            sessionStats.onConnectionStart(phone);
                        }
                    }

                    resetCarrierRoamingSatelliteModeParams(subId);
                    mWasSatelliteConnectedViaCarrier.put(subId, true);

                    for (NetworkRegistrationInfo nri
                            : serviceState.getNetworkRegistrationInfoList()) {
                        if (nri.isNonTerrestrialNetwork()) {
                            mSatModeCapabilitiesForCarrierRoaming.put(subId,
                                    nri.getAvailableServices());
                        }
                    }
                } else {
                    Boolean connected = mWasSatelliteConnectedViaCarrier.get(subId);
                    if (getWwanIsInService(serviceState)
                            || serviceState.getState() == ServiceState.STATE_POWER_OFF) {
                        resetCarrierRoamingSatelliteModeParams(subId);
                    } else if (connected != null && connected) {
                        // The device just got disconnected from a satellite network
                        // and is not connected to any terrestrial network that  has coverage
                        mLastSatelliteDisconnectedTimesMillis.put(subId, getElapsedRealtime());

                        plogd("sendMessageDelayed subId:" + subId
                                + " phoneId:" + phone.getPhoneId()
                                + " time:" + getSatelliteConnectionHysteresisTimeMillis(subId));
                        sendMessageDelayed(obtainMessage(EVENT_NOTIFY_NTN_HYSTERESIS_TIMED_OUT,
                                        phone.getPhoneId()),
                                getSatelliteConnectionHysteresisTimeMillis(subId));

                        if (sessionStats != null) {
                            // Log satellite connection end
                            sessionStats.onConnectionEnd();
                        }
                    }
                    mWasSatelliteConnectedViaCarrier.put(subId, false);
                }
                updateLastNotifiedNtnModeAndNotify(phone);
            }
        }
        determineAutoConnectSystemNotification();
    }

    private void updateLastNotifiedNtnModeAndNotify(@Nullable Phone phone) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) return;
        if (phone == null) {
            return;
        }

        int subId = phone.getSubId();
        synchronized (mSatelliteConnectedLock) {
            boolean initialized = mInitialized.get(subId);
            boolean lastNotifiedNtnMode = mLastNotifiedNtnMode.get(subId);
            boolean currNtnMode = isInSatelliteModeForCarrierRoaming(phone);
            if (!initialized || lastNotifiedNtnMode != currNtnMode) {
                if (!initialized) mInitialized.put(subId, true);
                mLastNotifiedNtnMode.put(subId, currNtnMode);
                phone.notifyCarrierRoamingNtnModeChanged(currNtnMode);
                logCarrierRoamingSatelliteSessionStats(phone, lastNotifiedNtnMode, currNtnMode);
                if(mIsNotificationShowing && !currNtnMode) {
                    dismissSatelliteNotification();
                }
            }
        }
    }

    private void logCarrierRoamingSatelliteSessionStats(@NonNull Phone phone,
            boolean lastNotifiedNtnMode, boolean currNtnMode) {
        synchronized (mSatelliteConnectedLock) {
            int subId = phone.getSubId();
            if (!lastNotifiedNtnMode && currNtnMode) {
                // Log satellite session start
                CarrierRoamingSatelliteSessionStats sessionStats =
                        CarrierRoamingSatelliteSessionStats.getInstance(subId);
                sessionStats.onSessionStart(phone.getCarrierId(), phone);
                mCarrierRoamingSatelliteSessionStatsMap.put(subId, sessionStats);
            } else if (lastNotifiedNtnMode && !currNtnMode) {
                // Log satellite session end
                CarrierRoamingSatelliteSessionStats sessionStats =
                        mCarrierRoamingSatelliteSessionStatsMap.get(subId);
                sessionStats.onSessionEnd();
                mCarrierRoamingSatelliteSessionStatsMap.remove(subId);
            }
        }
    }

    private void handleStateChangedForCarrierRoamingNtnEligibility() {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("handleStateChangedForCarrierRoamingNtnEligibility: "
                    + "carrierRoamingNbIotNtn flag is disabled");
            return;
        }

        synchronized (mSatellitePhoneLock) {
            boolean eligible = isCarrierRoamingNtnEligible(mSatellitePhone);
            plogd("handleStateChangedForCarrierRoamingNtnEligibility: "
                    + "isCarrierRoamingNtnEligible=" + eligible);

            if (eligible) {
                if (shouldStartNtnEligibilityHysteresisTimer(eligible)) {
                    startNtnEligibilityHysteresisTimer();
                }
            } else {
                mNtnEligibilityHysteresisTimedOut = false;
                stopNtnEligibilityHysteresisTimer();
                updateLastNotifiedNtnEligibilityAndNotify(false);
            }
        }
    }

    private boolean shouldStartNtnEligibilityHysteresisTimer(boolean eligible) {
        if (!eligible) {
            return false;
        }

        if (hasMessages(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT)) {
            plogd("shouldStartNtnEligibilityHysteresisTimer: Timer is already running.");
            return false;
        }

        synchronized (mSatellitePhoneLock) {
            if (mLastNotifiedNtnEligibility != null && mLastNotifiedNtnEligibility) {
                return false;
            }
        }

        return true;
    }

    private void startNtnEligibilityHysteresisTimer() {
        synchronized (mSatellitePhoneLock) {
            if (mSatellitePhone == null) {
                ploge("startNtnEligibilityHysteresisTimer: mSatellitePhone is null.");
                return;
            }

            int subId = getSelectedSatelliteSubId();
            long timeout = getCarrierSupportedSatelliteNotificationHysteresisTimeMillis(subId);
            mNtnEligibilityHysteresisTimedOut = false;
            plogd("startNtnEligibilityHysteresisTimer: sendMessageDelayed subId=" + subId
                    + ", phoneId=" + mSatellitePhone.getPhoneId() + ", timeout=" + timeout);
            sendMessageDelayed(obtainMessage(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT),
                    timeout);
        }
    }

    private void stopNtnEligibilityHysteresisTimer() {
        if (hasMessages(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT)) {
            removeMessages(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT);
        }
    }

    private void updateLastNotifiedNtnEligibilityAndNotify(boolean currentNtnEligibility) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("notifyNtnEligibility: carrierRoamingNbIotNtn flag is disabled");
            return;
        }

        if (mOverrideNtnEligibility != null) {
            mSatellitePhone.notifyCarrierRoamingNtnEligibleStateChanged(currentNtnEligibility);
            return;
        }

        synchronized (mSatellitePhoneLock) {
            if (mSatellitePhone == null) {
                ploge("notifyNtnEligibility: mSatellitePhone is null");
                return;
            }

            plogd("notifyNtnEligibility: phoneId=" + mSatellitePhone.getPhoneId()
                    + " currentNtnEligibility=" + currentNtnEligibility);
            if (mLastNotifiedNtnEligibility == null
                    || mLastNotifiedNtnEligibility != currentNtnEligibility) {
                mLastNotifiedNtnEligibility = currentNtnEligibility;
                mSatellitePhone.notifyCarrierRoamingNtnEligibleStateChanged(currentNtnEligibility);
                updateSatelliteSystemNotification(getSelectedSatelliteSubId(),
                        CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL,
                        currentNtnEligibility);
            }
        }
    }

    private long getSatelliteConnectionHysteresisTimeMillis(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return (config.getInt(
                KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT) * 1000L);
    }

    private long getCarrierSupportedSatelliteNotificationHysteresisTimeMillis(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return (config.getInt(
                KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT) * 1000L);
    }

    private void persistOemEnabledSatelliteProvisionStatus(boolean isProvisioned) {
        synchronized (mSatelliteViaOemProvisionLock) {
            plogd("persistOemEnabledSatelliteProvisionStatus: isProvisioned=" + isProvisioned);
            if (mFeatureFlags.carrierRoamingNbIotNtn()) {
                int subId = SatelliteServiceUtils.getNtnOnlySubscriptionId(mContext);
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    try {
                        mSubscriptionManagerService.setIsSatelliteProvisionedForNonIpDatagram(subId,
                                isProvisioned);
                        plogd("persistOemEnabledSatelliteProvisionStatus: subId=" + subId);
                    } catch (IllegalArgumentException | SecurityException ex) {
                        ploge("setIsSatelliteProvisionedForNonIpDatagram: subId=" + subId + ", ex="
                                + ex);
                    }
                } else {
                    plogd("persistOemEnabledSatelliteProvisionStatus: INVALID_SUBSCRIPTION_ID");
                }
            } else {
                if (!loadSatelliteSharedPreferences()) return;

                if (mSharedPreferences == null) {
                    ploge("persistOemEnabledSatelliteProvisionStatus: mSharedPreferences is null");
                } else {
                    mSharedPreferences.edit().putBoolean(
                            OEM_ENABLED_SATELLITE_PROVISION_STATUS_KEY, isProvisioned).apply();
                }
            }
        }
    }

    @Nullable
    private Boolean getPersistedOemEnabledSatelliteProvisionStatus() {
        plogd("getPersistedOemEnabledSatelliteProvisionStatus:");
        synchronized (mSatelliteViaOemProvisionLock) {
            if (mFeatureFlags.carrierRoamingNbIotNtn()) {
                int subId = SatelliteServiceUtils.getNtnOnlySubscriptionId(mContext);
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    return mSubscriptionManagerService.isSatelliteProvisionedForNonIpDatagram(
                            subId);
                } else {
                    plogd("getPersistedOemEnabledSatelliteProvisionStatus: "
                            + "subId=INVALID_SUBSCRIPTION_ID, return null");
                    return null;
                }
            } else {
                if (!loadSatelliteSharedPreferences()) return false;

                if (mSharedPreferences == null) {
                    ploge("getPersistedOemEnabledSatelliteProvisionStatus: mSharedPreferences is "
                            + "null");
                    return false;
                } else {
                    return mSharedPreferences.getBoolean(
                            OEM_ENABLED_SATELLITE_PROVISION_STATUS_KEY, false);
                }
            }
        }
    }

    private boolean loadSatelliteSharedPreferences() {
        if (mSharedPreferences == null) {
            try {
                mSharedPreferences =
                        mContext.getSharedPreferences(SATELLITE_SHARED_PREF,
                                Context.MODE_PRIVATE);
            } catch (Exception e) {
                ploge("loadSatelliteSharedPreferences: Cannot get default "
                        + "shared preferences, e=" + e);
                return false;
            }
        }
        return true;
    }

    private void handleIsSatelliteProvisionedDoneEvent(@NonNull AsyncResult ar) {
        logd("handleIsSatelliteProvisionedDoneEvent:");
        SatelliteControllerHandlerRequest request = (SatelliteControllerHandlerRequest) ar.userObj;

        Bundle bundle = new Bundle();
        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED,
                Boolean.TRUE.equals(isSatelliteViaOemProvisioned()));
        ((ResultReceiver) request.argument).send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    private long getWaitForSatelliteEnablingResponseTimeoutMillis() {
        return mContext.getResources().getInteger(
                R.integer.config_wait_for_satellite_enabling_response_timeout_millis);
    }

    private long getWaitForCellularModemOffTimeoutMillis() {
        return mContext.getResources().getInteger(
                R.integer.config_satellite_wait_for_cellular_modem_off_timeout_millis);
    }

    private void startWaitForCellularModemOffTimer() {
        synchronized (mIsRadioOnLock) {
            if (hasMessages(EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT)) {
                plogd("startWaitForCellularModemOffTimer: the timer was already started");
                return;
            }
            long timeoutMillis = getWaitForCellularModemOffTimeoutMillis();
            plogd("Start timer to wait for cellular modem OFF state, timeoutMillis="
                    + timeoutMillis);
            sendMessageDelayed(obtainMessage(EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT),
                    timeoutMillis);
        }
    }

    private void stopWaitForCellularModemOffTimer() {
        synchronized (mSatelliteEnabledRequestLock) {
            plogd("Stop timer to wait for cellular modem OFF state");
            removeMessages(EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT);
        }
    }

    private void startWaitForSatelliteEnablingResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (hasMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT, argument)) {
                plogd("WaitForSatelliteEnablingResponseTimer of request ID "
                        + argument.requestId + " was already started");
                return;
            }
            plogd("Start timer to wait for response of the satellite enabling request ID="
                    + argument.requestId + ", enableSatellite=" + argument.enableSatellite
                    + ", mWaitTimeForSatelliteEnablingResponse="
                    + mWaitTimeForSatelliteEnablingResponse);
            sendMessageDelayed(obtainMessage(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT,
                            argument), mWaitTimeForSatelliteEnablingResponse);
        }
    }

    private void stopWaitForSatelliteEnablingResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            plogd("Stop timer to wait for response of the satellite enabling request ID="
                    + argument.requestId + ", enableSatellite=" + argument.enableSatellite);
            removeMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT, argument);
        }
    }

    private boolean shouldProcessEventSetSatelliteEnabledDone(
            @NonNull RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (hasMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT, argument)) {
                return true;
            }
            return false;
        }
    }

    private void startWaitForUpdateSatelliteEnableAttributesResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (hasMessages(EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT,
                    argument)) {
                plogd("WaitForUpdateSatelliteEnableAttributesResponseTimer of request ID "
                        + argument.requestId + " was already started");
                return;
            }
            plogd("Start timer to wait for response of the update satellite enable attributes"
                    + " request ID=" + argument.requestId
                    + ", enableSatellite=" + argument.enableSatellite
                    + ", mWaitTimeForSatelliteEnablingResponse="
                    + mWaitTimeForSatelliteEnablingResponse);
            sendMessageDelayed(obtainMessage(
                    EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT,
                    argument), mWaitTimeForSatelliteEnablingResponse);
        }
    }

    private void stopWaitForUpdateSatelliteEnableAttributesResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            plogd("Stop timer to wait for response of the enable attributes update request ID="
                    + argument.requestId + ", enableSatellite=" + argument.enableSatellite);
            removeMessages(
                    EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT, argument);
        }
    }

    private boolean shouldProcessEventUpdateSatelliteEnableAttributesDone(
            @NonNull RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (hasMessages(EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT,
                    argument)) {
                return true;
            }
            return false;
        }
    }

    private void handleEventWaitForSatelliteEnablingResponseTimedOut(
            @NonNull RequestSatelliteEnabledArgument argument) {
        plogw("Timed out to wait for response of the satellite enabling request ID="
                + argument.requestId + ", enableSatellite=" + argument.enableSatellite);

        argument.callback.accept(SATELLITE_RESULT_MODEM_TIMEOUT);
        synchronized (mIsSatelliteEnabledLock) {
            if (argument.enableSatellite) {
                resetSatelliteEnabledRequest();
                abortSatelliteEnableAttributesUpdateRequest(SATELLITE_RESULT_REQUEST_ABORTED);
                synchronized (mSatelliteEnabledRequestLock) {
                    if (mSatelliteDisabledRequest == null) {
                        IIntegerConsumer callback = new IIntegerConsumer.Stub() {
                            @Override
                            public void accept(int result) {
                                plogd("handleEventWaitForSatelliteEnablingResponseTimedOut: "
                                        + "disable satellite result=" + result);
                            }
                        };
                        Consumer<Integer> result =
                                FunctionalUtils.ignoreRemoteException(callback::accept);
                        mSatelliteDisabledRequest = new RequestSatelliteEnabledArgument(
                                false, false, false, result);
                        sendRequestAsync(CMD_SET_SATELLITE_ENABLED, mSatelliteDisabledRequest,
                                null);
                    }
                }

                mControllerMetricsStats.reportServiceEnablementFailCount();
                mSessionMetricsStats.setInitializationResult(SATELLITE_RESULT_MODEM_TIMEOUT)
                        .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                        .setInitializationProcessingTime(
                                System.currentTimeMillis() - mSessionProcessingTimeStamp)
                        .setIsDemoMode(mIsDemoModeEnabled)
                        .setCarrierId(getSatelliteCarrierId())
                        .reportSessionMetrics();
            } else {
                resetSatelliteDisabledRequest();
                mControllerMetricsStats.onSatelliteDisabled();
                mSessionMetricsStats.setTerminationResult(SATELLITE_RESULT_MODEM_TIMEOUT)
                        .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                        .setTerminationProcessingTime(
                                System.currentTimeMillis() - mSessionProcessingTimeStamp)
                        .setSessionDurationSec(calculateSessionDurationTimeSec())
                        .reportSessionMetrics();
            }
            notifyEnablementFailedToSatelliteSessionController(argument.enableSatellite);
            mSessionStartTimeStamp = 0;
            mSessionProcessingTimeStamp = 0;
        }
    }

    private void handleCmdUpdateNtnSignalStrengthReporting(boolean shouldReport) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("handleCmdUpdateNtnSignalStrengthReporting: oemEnabledSatelliteFlag is "
                    + "disabled");
            return;
        }

        if (!isSatelliteEnabledOrBeingEnabled()) {
            plogd("handleCmdUpdateNtnSignalStrengthReporting: ignore request, satellite is "
                    + "disabled");
            return;
        }

        mLatestRequestedStateForNtnSignalStrengthReport.set(shouldReport);
        if (mIsModemEnabledReportingNtnSignalStrength.get() == shouldReport) {
            plogd("handleCmdUpdateNtnSignalStrengthReporting: ignore request. "
                    + "mIsModemEnabledReportingNtnSignalStrength="
                    + mIsModemEnabledReportingNtnSignalStrength.get());
            return;
        }

        updateNtnSignalStrengthReporting(shouldReport);
    }

    private void updateNtnSignalStrengthReporting(boolean shouldReport) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("updateNtnSignalStrengthReporting: oemEnabledSatelliteFlag is "
                    + "disabled");
            return;
        }

        SatelliteControllerHandlerRequest request = new SatelliteControllerHandlerRequest(
                shouldReport, SatelliteServiceUtils.getPhone());
        Message onCompleted = obtainMessage(EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE,
                request);
        if (shouldReport) {
            plogd("updateNtnSignalStrengthReporting: startSendingNtnSignalStrength");
            mSatelliteModemInterface.startSendingNtnSignalStrength(onCompleted);
        } else {
            plogd("updateNtnSignalStrengthReporting: stopSendingNtnSignalStrength");
            mSatelliteModemInterface.stopSendingNtnSignalStrength(onCompleted);
        }
    }

    /**
     * This API can be used by only CTS to override the cached value for the device overlay config
     * value : config_send_satellite_datagram_to_modem_in_demo_mode, which determines whether
     * outgoing satellite datagrams should be sent to modem in demo mode.
     *
     * @param shouldSendToModemInDemoMode Whether send datagram in demo mode should be sent to
     * satellite modem or not.
     *
     * @return {@code true} if the operation is successful, {@code false} otherwise.
     */
    public boolean setShouldSendDatagramToModemInDemoMode(boolean shouldSendToModemInDemoMode) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            plogd("setShouldSendDatagramToModemInDemoMode: oemEnabledSatelliteFlag is disabled");
            return false;
        }

        if (!isMockModemAllowed()) {
            plogd("setShouldSendDatagramToModemInDemoMode: mock modem not allowed.");
            return false;
        }

        mDatagramController.setShouldSendDatagramToModemInDemoMode(shouldSendToModemInDemoMode);
        return true;
    }

    private void determineAutoConnectSystemNotification() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) {
            logd("determineSystemNotification: carrierEnabledSatelliteFlag is disabled");
            return;
        }

        Pair<Boolean, Integer> isNtn = isUsingNonTerrestrialNetworkViaCarrier();
        boolean notificationKeyStatus = mSharedPreferences.getBoolean(
                SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY, false);
        if (DEBUG) {
            logd("determineAutoConnectSystemNotification: isNtn.first = " + isNtn.first
                    + " IsNotiToShow = " + !notificationKeyStatus + " mIsNotificationShowing = "
                    + mIsNotificationShowing);
        }
        if (isNtn.first) {
            if (!notificationKeyStatus) {
                updateSatelliteSystemNotification(isNtn.second,
                        CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC,
                        /*visible*/ true);
            }
        } else if (mIsNotificationShowing
                && !isSatelliteConnectedViaCarrierWithinHysteresisTime()) {
            // Dismiss the notification if it is still displaying.
            dismissSatelliteNotification();
        }
    }

    private void dismissSatelliteNotification() {
        mIsNotificationShowing = false;
        updateSatelliteSystemNotification(-1, -1,/*visible*/ false);
    }

    /**
     * Update the system notification to reflect the current satellite status, that's either already
     * connected OR needs to be manually enabled. The device should only display one notification
     * at a time to prevent confusing the user, so the same NOTIFICATION_CHANNEL and NOTIFICATION_ID
     * are used.
     *
     * @param subId The subId that provides the satellite connection.
     * @param carrierRoamingNtnConnectType {@link CarrierConfigManager
     * .CARRIER_ROAMING_NTN_CONNECT_TYPE}
     * @param visible {@code true} to show the notification, {@code false} to cancel it.
     */
    private void updateSatelliteSystemNotification(int subId,
            @CARRIER_ROAMING_NTN_CONNECT_TYPE int carrierRoamingNtnConnectType, boolean visible) {
        plogd("updateSatelliteSystemNotification subId=" + subId + ", carrierRoamingNtnConnectType="
                + SatelliteServiceUtils.carrierRoamingNtnConnectTypeToString(
                carrierRoamingNtnConnectType) + ", visible=" + visible);
        final NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL,
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setSound(null, null);
        NotificationManager notificationManager = mContext.getSystemService(
                NotificationManager.class);
        if (notificationManager == null) {
            ploge("updateSatelliteSystemNotification: notificationManager is null");
            return;
        }
        if (!visible) { // Cancel if any.
            notificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID, UserHandle.ALL);
            return;
        }
        notificationManager.createNotificationChannel(notificationChannel);

        // if carrierRoamingNtnConnectType is CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC
        int title = R.string.satellite_notification_title;
        int summary = R.string.satellite_notification_summary;
        if (carrierRoamingNtnConnectType
                == CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
            title = R.string.satellite_notification_manual_title;
            summary = R.string.satellite_notification_manual_summary;
        }

        Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                NOTIFICATION_CHANNEL_ID)
                .setContentTitle(mContext.getResources().getString(title))
                .setContentText(mContext.getResources().getString(summary))
                .setSmallIcon(R.drawable.ic_android_satellite_24px)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        // Intent for `Open Messages` [Button 1]
        Intent openMessageIntent = new Intent();
        openMessageIntent.setAction(OPEN_MESSAGE_BUTTON);
        PendingIntent openMessagePendingIntent = PendingIntent.getBroadcast(mContext, 0,
                openMessageIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Action actionOpenMessage = new Notification.Action.Builder(0,
                mContext.getResources().getString(R.string.satellite_notification_open_message),
                openMessagePendingIntent).build();
        notificationBuilder.addAction(actionOpenMessage);   // Handle `Open Messages` button

        // Button for `How it works` [Button 2]
        Intent howItWorksIntent = new Intent();
        howItWorksIntent.setAction(HOW_IT_WORKS_BUTTON);
        howItWorksIntent.putExtra("SUBID", subId);
        PendingIntent howItWorksPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                howItWorksIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Action actionHowItWorks = new Notification.Action.Builder(0,
                mContext.getResources().getString(R.string.satellite_notification_how_it_works),
                howItWorksPendingIntent).build();
        notificationBuilder.addAction(actionHowItWorks);    // Handle `How it works` button

        // Intent for clicking the main notification body
        Intent notificationClickIntent = new Intent(ACTION_NOTIFICATION_CLICK);
        PendingIntent notificationClickPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                notificationClickIntent, PendingIntent.FLAG_IMMUTABLE);
        notificationBuilder.setContentIntent(
                notificationClickPendingIntent); // Handle notification body click

        // Intent for dismissing/swiping the notification
        Intent deleteIntent = new Intent(ACTION_NOTIFICATION_DISMISS);
        PendingIntent deletePendingIntent = PendingIntent.getBroadcast(mContext, 0, deleteIntent,
                PendingIntent.FLAG_IMMUTABLE);
        notificationBuilder.setDeleteIntent(
                deletePendingIntent);  // Handle notification swipe/dismiss

        notificationManager.notifyAsUser(NOTIFICATION_TAG, NOTIFICATION_ID,
                notificationBuilder.build(), UserHandle.ALL);

        // The Intent filter is to receive the above four events.
        IntentFilter filter = new IntentFilter();
        filter.addAction(OPEN_MESSAGE_BUTTON);
        filter.addAction(HOW_IT_WORKS_BUTTON);
        filter.addAction(ACTION_NOTIFICATION_CLICK);
        filter.addAction(ACTION_NOTIFICATION_DISMISS);
        mContext.registerReceiver(mNotificationInteractionBroadcastReceiver, filter,
                Context.RECEIVER_EXPORTED);

        mIsNotificationShowing = true;
        mCarrierRoamingSatelliteControllerStats.reportCountOfSatelliteNotificationDisplayed();
        mCarrierRoamingSatelliteControllerStats.reportCarrierId(getSatelliteCarrierId());
        mSessionMetricsStats.addCountOfSatelliteNotificationDisplayed();
    }

    private final BroadcastReceiver mNotificationInteractionBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent receivedIntent) {
                    String intentAction = receivedIntent.getAction();
                    if (TextUtils.isEmpty(intentAction)) {
                        loge("Received empty action from the notification");
                        return;
                    }
                    if (DBG) {
                        plogd("Notification Broadcast recvd action = "
                                + receivedIntent.getAction());
                    }
                    boolean closeStatusBar = true;
                    switch (intentAction) {
                        case OPEN_MESSAGE_BUTTON -> {
                            // Add action to invoke message application.
                            // getDefaultSmsPackage and getLaunchIntentForPackage are nullable.
                            Optional<Intent> nullableIntent = Optional.ofNullable(
                                    Telephony.Sms.getDefaultSmsPackage(context)).flatMap(
                                    packageName -> {
                                        PackageManager pm = context.getPackageManager();
                                        return Optional.ofNullable(
                                                pm.getLaunchIntentForPackage(packageName));
                                    });
                            // If nullableIntent is null, create new Intent for most common way to
                            // invoke
                            // message app.
                            Intent finalIntent = nullableIntent.map(intent -> {
                                // Invoke the home screen of default message application.
                                intent.setAction(Intent.ACTION_MAIN);
                                intent.addCategory(Intent.CATEGORY_HOME);
                                return intent;
                            }).orElseGet(() -> {
                                ploge("showSatelliteSystemNotification: no default sms package "
                                        + "name, Invoke default sms compose window instead");
                                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                                newIntent.setData(Uri.parse("sms:"));
                                return newIntent;
                            });
                            context.startActivity(finalIntent);
                        }
                        case HOW_IT_WORKS_BUTTON -> {
                            int subId = receivedIntent.getIntExtra("SUBID", -1);
                            Intent intentSatelliteSetting = new Intent(ACTION_SATELLITE_SETTING);
                            intentSatelliteSetting.putExtra("sub_id", subId);
                            intentSatelliteSetting.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intentSatelliteSetting);

                        }
                        case ACTION_NOTIFICATION_DISMISS -> closeStatusBar = false;
                    }
                    // Note : ACTION_NOTIFICATION_DISMISS is not required to handled
                    dismissNotificationAndUpdatePref(closeStatusBar);
                }
            };

    private void dismissNotificationAndUpdatePref(boolean closeStatusBar) {
        dismissSatelliteNotification();
        if (closeStatusBar) {
            // Collapse the status bar once user interact with notification.
            StatusBarManager statusBarManager = mContext.getSystemService(StatusBarManager.class);
            if (statusBarManager != null) {
                statusBarManager.collapsePanels();
            }
        }
        // update the sharedpref only when user interacted with the notification.
        mSharedPreferences.edit().putBoolean(SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY, true).apply();
        mContext.unregisterReceiver(mNotificationInteractionBroadcastReceiver);
    }

    private void resetCarrierRoamingSatelliteModeParams() {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) return;

        for (Phone phone : PhoneFactory.getPhones()) {
            resetCarrierRoamingSatelliteModeParams(phone.getSubId());
        }
    }

    private void resetCarrierRoamingSatelliteModeParams(int subId) {
        if (!mFeatureFlags.carrierEnabledSatelliteFlag()) return;

        synchronized (mSatelliteConnectedLock) {
            mLastSatelliteDisconnectedTimesMillis.put(subId, null);
            mSatModeCapabilitiesForCarrierRoaming.remove(subId);
            mWasSatelliteConnectedViaCarrier.put(subId, false);
        }
    }

    /**
     * Read carrier config items for satellite
     *
     * @param subId Associated subscription ID
     * @return PersistableBundle including carrier config values
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @NonNull
    public PersistableBundle getPersistableBundle(int subId) {
        synchronized (mCarrierConfigArrayLock) {
            PersistableBundle config = mCarrierConfigArray.get(subId);
            if (config == null) {
                config = getConfigForSubId(subId);
                mCarrierConfigArray.put(subId, config);
            }
            return config;
        }
    }

    // Should be invoked only when session termination done or session termination failed.
    private int calculateSessionDurationTimeSec() {
        return (int) (
                (System.currentTimeMillis() - mSessionStartTimeStamp
                - mSessionMetricsStats.getSessionInitializationProcessingTimeMillis()
                - mSessionMetricsStats.getSessionTerminationProcessingTimeMillis()) / 1000);
    }

    private void notifyEnablementFailedToSatelliteSessionController(boolean enabled) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnablementFailed(enabled);
        } else {
            ploge("notifyEnablementFailedToSatelliteSessionController: mSatelliteSessionController"
                    + " is not initialized yet");
        }
    }

    private void abortSatelliteEnableRequest(@SatelliteManager.SatelliteResult int resultCode) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (mSatelliteEnabledRequest != null) {
                plogw("abortSatelliteEnableRequest");
                if (resultCode == SATELLITE_RESULT_SUCCESS) {
                    resultCode = SATELLITE_RESULT_REQUEST_ABORTED;
                }
                mSatelliteEnabledRequest.callback.accept(resultCode);
                stopWaitForSatelliteEnablingResponseTimer(mSatelliteEnabledRequest);
                mSatelliteEnabledRequest = null;
            }
        }
    }

    private void abortSatelliteDisableRequest(@SatelliteManager.SatelliteResult int resultCode) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (mSatelliteDisabledRequest != null) {
                plogd("abortSatelliteDisableRequest");
                mSatelliteDisabledRequest.callback.accept(resultCode);
                stopWaitForSatelliteEnablingResponseTimer(mSatelliteDisabledRequest);
                mSatelliteDisabledRequest = null;
            }
        }
    }

    private void abortSatelliteEnableAttributesUpdateRequest(
            @SatelliteManager.SatelliteResult int resultCode) {
        synchronized (mSatelliteEnabledRequestLock) {
            if (mSatelliteEnableAttributesUpdateRequest != null) {
                plogd("abortSatelliteEnableAttributesUpdateRequest");
                if (resultCode == SATELLITE_RESULT_SUCCESS) {
                    resultCode = SATELLITE_RESULT_REQUEST_ABORTED;
                }
                mSatelliteEnableAttributesUpdateRequest.callback.accept(resultCode);
                stopWaitForUpdateSatelliteEnableAttributesResponseTimer(
                        mSatelliteEnableAttributesUpdateRequest);
                mSatelliteEnableAttributesUpdateRequest = null;
            }
        }
    }

    private void stopWaitForEnableResponseTimers() {
        plogd("stopWaitForEnableResponseTimers");
        removeMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT);
    }

    private long getDemoPointingAlignedDurationMillisFromResources() {
        long durationMillis = 15000L;
        try {
            durationMillis = mContext.getResources().getInteger(
                    R.integer.config_demo_pointing_aligned_duration_millis);
        } catch (Resources.NotFoundException ex) {
            loge("getPointingAlignedDurationMillis: ex=" + ex);
        }

        return durationMillis;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public long getDemoPointingAlignedDurationMillis() {
        return mDemoPointingAlignedDurationMillis;
    }

    private long getDemoPointingNotAlignedDurationMillisFromResources() {
        long durationMillis = 30000L;
        try {
            durationMillis = mContext.getResources().getInteger(
                    R.integer.config_demo_pointing_not_aligned_duration_millis);
        } catch (Resources.NotFoundException ex) {
            loge("getPointingNotAlignedDurationMillis: ex=" + ex);
        }

        return durationMillis;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public long getDemoPointingNotAlignedDurationMillis() {
        return mDemoPointingNotAlignedDurationMillis;
    }

    /** Returns {@code true} if WWAN is in service, else {@code false}.*/
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getWwanIsInService(@NonNull ServiceState serviceState) {
        List<NetworkRegistrationInfo> nriList = serviceState
                .getNetworkRegistrationInfoListForTransportType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        for (NetworkRegistrationInfo nri : nriList) {
            if (nri.isInService()) {
                logv("getWwanIsInService: return true");
                return true;
            }
        }

        logv("getWwanIsInService: return false");
        return false;
    }

    private static void logv(@NonNull String log) {
        Rlog.v(TAG, log);
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void logw(@NonNull String log) {
        Rlog.w(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }

    private boolean isSatellitePersistentLoggingEnabled(
            @NonNull Context context, @NonNull FeatureFlags featureFlags) {
        if (featureFlags.satellitePersistentLogging()) {
            return true;
        }
        try {
            return context.getResources().getBoolean(
                    R.bool.config_dropboxmanager_persistent_logging_enabled);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void plogd(@NonNull String log) {
        Rlog.d(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.debug(TAG, log);
        }
    }

    private void plogw(@NonNull String log) {
        Rlog.w(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.warn(TAG, log);
        }
    }

    private void ploge(@NonNull String log) {
        Rlog.e(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.error(TAG, log);
        }
    }

    private void handlePersistentLoggingOnSessionStart(RequestSatelliteEnabledArgument argument) {
        if (mPersistentLogger == null) {
            return;
        }
        if (argument.isEmergency) {
            DropBoxManagerLoggerBackend.getInstance(mContext).setLoggingEnabled(true);
        }
    }

    private void handlePersistentLoggingOnSessionEnd(boolean isEmergency) {
        if (mPersistentLogger == null) {
            return;
        }
        DropBoxManagerLoggerBackend loggerBackend =
                DropBoxManagerLoggerBackend.getInstance(mContext);
        // Flush persistent satellite logs on eSOS session end
        if (isEmergency) {
            loggerBackend.flushAsync();
        }
        // Also turn off persisted logging until new session is started
        loggerBackend.setLoggingEnabled(false);
    }

    /**
     * Set last emergency call time to the current time.
     */
    public void setLastEmergencyCallTime() {
        synchronized (mLock) {
            mLastEmergencyCallTime = getElapsedRealtime();
            plogd("mLastEmergencyCallTime=" + mLastEmergencyCallTime);
        }
    }

    /**
     * Check if satellite is in emergency mode.
     */
    public boolean isInEmergencyMode() {
        synchronized (mLock) {
            if (mLastEmergencyCallTime == 0) return false;

            long currentTime = getElapsedRealtime();
            if ((currentTime - mLastEmergencyCallTime) <= mSatelliteEmergencyModeDurationMillis) {
                plogd("Satellite is in emergency mode");
                return true;
            }
            return false;
        }
    }

    private long getSatelliteEmergencyModeDurationFromOverlayConfig(@NonNull Context context) {
        Integer duration = DEFAULT_SATELLITE_EMERGENCY_MODE_DURATION_SECONDS;
        try {
            duration = context.getResources().getInteger(com.android.internal.R.integer
                    .config_satellite_emergency_mode_duration);
        } catch (Resources.NotFoundException ex) {
            ploge("getSatelliteEmergencyModeDurationFromOverlayConfig: got ex=" + ex);
        }
        return TimeUnit.SECONDS.toMillis(duration);
    }

    private long getEvaluateEsosProfilesPrioritizationDurationMillis() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    /**
     * Calculate priority
     * 1. Active eSOS profiles are higher priority than inactive eSOS profiles.
     * 2. Carrier Enabled eSOS profile is higher priority than OEM enabled eSOS profile.
     * 3. Among active carrier eSOS profiles user selected(default SMS SIM) eSOS profile will be
     * the highest priority.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected void evaluateESOSProfilesPrioritization() {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("evaluateESOSProfilesPrioritization: Flag CarrierRoamingNbIotNtn is disabled");
            return;
        }
        boolean isChanged = false;
        List<SubscriptionInfo> allSubInfos = mSubscriptionManagerService.getAllSubInfoList(
                mContext.getOpPackageName(), mContext.getAttributionTag());
        // Key : priority - lower value has higher priority; Value : List<SubscriptionInfo>
        TreeMap<Integer, List<SubscriptionInfo>> newSubsInfoListPerPriority = new TreeMap<>();
        plogd("evaluateESOSProfilesPrioritization: allSubInfos.size()=" + allSubInfos.size());
        synchronized (mSatelliteTokenProvisionedLock) {
            for (SubscriptionInfo info : allSubInfos) {
                int subId = info.getSubscriptionId();
                boolean isActive = info.isActive();
                boolean isDefaultSmsSubId =
                        mSubscriptionManagerService.getDefaultSmsSubId() == subId;
                boolean isNtnOnly = info.isOnlyNonTerrestrialNetwork();
                boolean isESOSSupported = info.isSatelliteESOSSupported();
                if (!isNtnOnly && !isESOSSupported) {
                    continue;
                }

                int keyPriority = (isESOSSupported && isActive && isDefaultSmsSubId) ? 0
                        : (isESOSSupported && isActive) ? 1
                                : (isNtnOnly) ? 2 : (isESOSSupported) ? 3 : -1;
                if (keyPriority != -1) {
                    newSubsInfoListPerPriority.computeIfAbsent(keyPriority,
                            k -> new ArrayList<>()).add(info);
                } else {
                    plogw("evaluateESOSProfilesPrioritization: Got -1 keyPriority for subId="
                            + info.getSubscriptionId());
                }

                Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(info);
                String newSubscriberId = subscriberIdPair.first;
                Optional<String> oldSubscriberId = mSubscriberIdPerSub.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(subId))
                        .map(Map.Entry::getKey).findFirst();

                if (oldSubscriberId.isPresent()
                        && !newSubscriberId.equals(oldSubscriberId.get())) {
                    mSubscriberIdPerSub.remove(oldSubscriberId.get());
                    mProvisionedSubscriberId.remove(oldSubscriberId.get());
                    logd("Old phone number is removed: id = " + subId);
                    isChanged = true;
                }
            }
        }
        plogd("evaluateESOSProfilesPrioritization: newSubsInfoListPerPriority.size()="
                  + newSubsInfoListPerPriority.size());

        if (!mHasSentBroadcast && newSubsInfoListPerPriority.size() == 0) {
            logd("evaluateESOSProfilesPrioritization: no satellite subscription available");
            return;
        }

        // If priority has changed, send broadcast for provisioned ESOS subs IDs
        synchronized (mSatelliteTokenProvisionedLock) {
            if (isPriorityChanged(mSubsInfoListPerPriority, newSubsInfoListPerPriority)
                    || isChanged) {
                mSubsInfoListPerPriority = newSubsInfoListPerPriority;
                sendBroadCastForProvisionedESOSSubs();
                mHasSentBroadcast = true;
                selectBindingSatelliteSubscription();
            }
        }
    }

    // The subscriberId for ntnOnly SIMs is the Iccid, whereas for ESOS supported SIMs, the
    // subscriberId is the Imsi prefix 6 digit + phone number.
    private Pair<String, Integer> getSubscriberIdAndType(SubscriptionInfo info) {
        String subscriberId = "";
        @SatelliteSubscriberInfo.SubscriberIdType int subscriberIdType =
                SatelliteSubscriberInfo.ICCID;
        if (info.isSatelliteESOSSupported()) {
            subscriberId = getPhoneNumberBasedCarrier(info.getSubscriptionId());
            subscriberIdType = SatelliteSubscriberInfo.IMSI_MSISDN;
        }
        if (info.isOnlyNonTerrestrialNetwork()) {
            subscriberId = info.getIccId();
        }
        logd("getSubscriberIdAndType: subscriberId=" + subscriberId + ", subscriberIdType="
                + subscriberIdType);
        return new Pair<>(subscriberId, subscriberIdType);
    }

    private String getPhoneNumberBasedCarrier(int subId) {
        SubscriptionInfoInternal internal = mSubscriptionManagerService.getSubscriptionInfoInternal(
                subId);
        SubscriptionManager subscriptionManager = mContext.getSystemService(
                SubscriptionManager.class);
        if (mInjectSubscriptionManager != null) {
            logd("getPhoneNumberBasedCarrier: InjectSubscriptionManager");
            subscriptionManager = mInjectSubscriptionManager;
        }
        String phoneNumber = subscriptionManager.getPhoneNumber(subId);
        if (phoneNumber == null) {
            logd("getPhoneNumberBasedCarrier: phoneNumber null");
            return "";
        }
        return internal.getImsi() == null ? "" : internal.getImsi().substring(0, 6)
                + phoneNumber.replaceFirst("^\\+", "");
    }

    private boolean isPriorityChanged(Map<Integer, List<SubscriptionInfo>> currentMap,
            Map<Integer, List<SubscriptionInfo>> newMap) {
        if (currentMap.size() == 0 || currentMap.size() != newMap.size()) {
            return true;
        }

        for (Map.Entry<Integer, List<SubscriptionInfo>> entry : currentMap.entrySet()) {
            List<SubscriptionInfo> currentList = entry.getValue();
            List<SubscriptionInfo> newList = newMap.get(entry.getKey());
            if (newList == null || currentList == null || currentList.size() != newList.size()) {
                return true;
            }
            for (int i = 0; i < currentList.size(); i++) {
                if (currentList.get(i).getSubscriptionId() != newList.get(i).getSubscriptionId()) {
                    logd("isPriorityChanged: cur=" + currentList.get(i) + " , new=" + newList.get(
                            i));
                    return true;
                }
            }
        }
        return false;
    }

    private void sendBroadCastForProvisionedESOSSubs() {
        String packageName = getConfigSatelliteGatewayServicePackage();
        String className = getConfigSatelliteCarrierRoamingEsosProvisionedClass();
        if (packageName == null || className == null || packageName.isEmpty()
                || className.isEmpty()) {
            logd("sendBroadCastForProvisionedESOSSubs: packageName or className is null or empty.");
            return;
        }
        String action = SatelliteManager.ACTION_SATELLITE_SUBSCRIBER_ID_LIST_CHANGED;

        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(packageName, className));
        if (mFeatureFlags.hsumBroadcast()) {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } else {
            mContext.sendBroadcast(intent);
        }
        logd("sendBroadCastForProvisionedESOSSubs" + intent);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected String getStringFromOverlayConfig(int resourceId) {
        String name;
        try {
            name = mContext.getResources().getString(resourceId);
        } catch (Resources.NotFoundException ex) {
            loge("getStringFromOverlayConfig: ex=" + ex);
            name = null;
        }
        return name;
    }

    /**
     * Request to get list of prioritized satellite tokens to be used for provision.
     *
     * @param result The result receiver, which returns the list of prioritized satellite tokens
     * to be used for provision if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteSubscriberProvisionStatus(@NonNull ResultReceiver result) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            return;
        }
        List<SatelliteSubscriberProvisionStatus> list =
                getPrioritizedSatelliteSubscriberProvisionStatusList();
        logd("requestSatelliteSubscriberProvisionStatus: " + list);
        final Bundle bundle = new Bundle();
        bundle.putParcelableList(SatelliteManager.KEY_REQUEST_PROVISION_SUBSCRIBER_ID_TOKEN, list);
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    private List<SatelliteSubscriberProvisionStatus>
            getPrioritizedSatelliteSubscriberProvisionStatusList() {
        List<SatelliteSubscriberProvisionStatus> list = new ArrayList<>();
        synchronized (mSatelliteTokenProvisionedLock) {
            for (int priority : mSubsInfoListPerPriority.keySet()) {
                List<SubscriptionInfo> infoList = mSubsInfoListPerPriority.get(priority);
                if (infoList == null) {
                    logd("getPrioritySatelliteSubscriberProvisionStatusList: no exist this "
                            + "priority " + priority);
                    continue;
                }
                for (SubscriptionInfo info : infoList) {
                    Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(info);
                    String subscriberId = subscriberIdPair.first;
                    int carrierId = info.getCarrierId();
                    String apn = getConfigForSubId(info.getSubscriptionId())
                            .getString(KEY_SATELLITE_NIDD_APN_NAME_STRING, "");
                    logd("getPrioritySatelliteSubscriberProvisionStatusList: subscriberId:"
                            + subscriberId + " , carrierId=" + carrierId + " , apn=" + apn);
                    if (subscriberId.isEmpty()) {
                        logd("getPrioritySatelliteSubscriberProvisionStatusList: getSubscriberId "
                                + "failed skip this subscriberId.");
                        continue;
                    }
                    SatelliteSubscriberInfo satelliteSubscriberInfo =
                            new SatelliteSubscriberInfo.Builder().setSubscriberId(subscriberId)
                                    .setCarrierId(carrierId).setNiddApn(apn)
                                    .setSubId(info.getSubscriptionId())
                                    .setSubscriberIdType(subscriberIdPair.second)
                                    .build();
                    boolean provisioned = mProvisionedSubscriberId.getOrDefault(subscriberId,
                            false);
                    logd("getPrioritySatelliteSubscriberProvisionStatusList: "
                            + "satelliteSubscriberInfo=" + satelliteSubscriberInfo
                            + ", provisioned=" + provisioned);
                    list.add(new SatelliteSubscriberProvisionStatus.Builder()
                            .setSatelliteSubscriberInfo(satelliteSubscriberInfo)
                            .setProvisionStatus(provisioned).build());
                    mSubscriberIdPerSub.put(subscriberId, info.getSubscriptionId());
                }
            }
        }
        return list;
    }

    public int getSelectedSatelliteSubId() {
        synchronized (mSatelliteTokenProvisionedLock) {
            return mSelectedSatelliteSubId;
        }
    }

    private void selectBindingSatelliteSubscription() {
        if (isSatelliteEnabled() || isSatelliteBeingEnabled()) {
            plogd("selectBindingSatelliteSubscription: satellite subscription will be selected "
                    + "once the satellite session ends");
            return;
        }

        int selectedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        List<SatelliteSubscriberProvisionStatus> satelliteSubscribers =
                getPrioritizedSatelliteSubscriberProvisionStatusList();
        for (SatelliteSubscriberProvisionStatus status : satelliteSubscribers) {
            // TODO: need to check if satellite is allowed at current location for the subscription
            int subId = getSubIdFromSubscriberId(
                    status.getSatelliteSubscriberInfo().getSubscriberId());
            if (status.getProvisionStatus() && isActiveSubId(subId)) {
                selectedSubId = subId;
                break;
            }
        }

        synchronized (mSatelliteTokenProvisionedLock) {
            if (selectedSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && isSatelliteSupportedViaOem()) {
                selectedSubId = SatelliteServiceUtils.getNtnOnlySubscriptionId(mContext);
            }
            mSelectedSatelliteSubId = selectedSubId;
            setSatellitePhone(selectedSubId);
        }
        plogd("selectBindingSatelliteSubscription: SelectedSatelliteSubId="
                + mSelectedSatelliteSubId);
    }

    private int getSubIdFromSubscriberId(String subscriberId) {
        synchronized (mSatelliteTokenProvisionedLock) {
            return mSubscriberIdPerSub.getOrDefault(subscriberId,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
    }

    private boolean isActiveSubId(int subId) {
        return mSubscriptionManagerService.getSubscriptionInfo(subId).isActive();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean isSubscriptionProvisioned(int subId) {
        plogd("isSubscriptionProvisioned: subId=" + subId);
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("isSubscriptionProvisioned: carrierRoamingNbIotNtn flag is disabled");
            return false;
        }

        String subscriberId = getSubscriberIdAndType(
                mSubscriptionManagerService.getSubscriptionInfo(subId)).first;
        if (subscriberId.isEmpty()) {
            plogd("isSubscriptionProvisioned: subId=" + subId + " subscriberId is empty.");
            return false;
        }

        synchronized (mSatelliteTokenProvisionedLock) {
            return mProvisionedSubscriberId.getOrDefault(subscriberId, false);
        }
    }

    /**
     * Deliver the list of provisioned satellite subscriber ids.
     *
     * @param list List of provisioned satellite subscriber ids.
     * @param result The result receiver that returns whether deliver success or fail.
     */
    public void provisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull ResultReceiver result) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            logd("provisionSatellite: carrierRoamingNbIotNtn not support");
            return;
        }
        if (list.isEmpty()) {
            result.send(SATELLITE_RESULT_INVALID_ARGUMENTS, null);
            logd("provisionSatellite: SatelliteSubscriberInfo list is empty");
            return;
        }

        logd("provisionSatellite:" + list);
        RequestProvisionSatelliteArgument request = new RequestProvisionSatelliteArgument(list,
                result, true);
        sendRequestAsync(CMD_UPDATE_PROVISION_SATELLITE_TOKEN, request, null);
    }

    /**
     * Deliver the list of deprovisioned satellite subscriber ids.
     *
     * @param list List of deprovisioned satellite subscriber ids.
     * @param result The result receiver that returns whether deliver success or fail.
     */
    public void deprovisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull ResultReceiver result) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            logd("deprovisionSatellite: carrierRoamingNbIotNtn not support");
            return;
        }
        if (list.isEmpty()) {
            result.send(SATELLITE_RESULT_INVALID_ARGUMENTS, null);
            logd("deprovisionSatellite: SatelliteSubscriberInfo list is empty");
            return;
        }

        logd("deprovisionSatellite:" + list);
        RequestProvisionSatelliteArgument request = new RequestProvisionSatelliteArgument(list,
                result, false);
        sendRequestAsync(CMD_UPDATE_PROVISION_SATELLITE_TOKEN, request, null);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSatellitePhone(int subId) {
        synchronized (mSatellitePhoneLock) {
            mSatellitePhone = SatelliteServiceUtils.getPhone(subId);
            if (mSatellitePhone == null) {
                mSatellitePhone = SatelliteServiceUtils.getPhone();
            }
            plogd("mSatellitePhone:" + (mSatellitePhone != null) + ", subId=" + subId);
            int carrierId = mSatellitePhone.getCarrierId();
            if (carrierId != UNKNOWN_CARRIER_ID) {
                mControllerMetricsStats.setCarrierId(carrierId);
            } else {
                logd("setSatellitePhone: Carrier ID is UNKNOWN_CARRIER_ID");
            }
        }
    }

    /** Return the carrier ID of the binding satellite subscription. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public int getSatelliteCarrierId() {
        synchronized (mSatelliteTokenProvisionedLock) {
            SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(
                    mSelectedSatelliteSubId);
            if (subInfo == null) {
                logd("getSatelliteCarrierId: returns UNKNOWN_CARRIER_ID");
                return UNKNOWN_CARRIER_ID;
            }
            return subInfo.getCarrierId();
        }
    }

    /**
     * Get whether phone is eligible to connect to carrier roaming non-terrestrial network.
     *
     * @param phone phone object
     * return {@code true} when the subscription is eligible for satellite
     * communication if all the following conditions are met:
     * <ul>
     * <li>Subscription supports P2P satellite messaging which is defined by
     * {@link CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} </li>
     * <li>{@link CarrierConfigManager#KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT} set to
     * {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_MANUAL} </li>
     * <li>The device is in {@link ServiceState#STATE_OUT_OF_SERVICE}, not connected to Wi-Fi. </li>
     * </ul>
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isCarrierRoamingNtnEligible(@Nullable Phone phone) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("isCarrierRoamingNtnEligible: carrierRoamingNbIotNtn flag is disabled");
            return false;
        }

        if (phone == null) {
            plogd("isCarrierRoamingNtnEligible: phone is null");
            return false;
        }

        int subId = phone.getSubId();
        if (!isSatelliteRoamingP2pSmSSupported(subId)) {
            plogd("isCarrierRoamingNtnEligible: doesn't support P2P SMS");
            return false;
        }

        if (!isSatelliteSupportedViaCarrier(subId)) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: satellite is not supported via carrier");
            return false;
        }

        if (!isSubscriptionProvisioned(subId)) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: subscription is not provisioned to use satellite.");
            return false;
        }

        if (!isSatelliteServiceSupportedByCarrier(subId,
                NetworkRegistrationInfo.SERVICE_TYPE_SMS)) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: SMS is not supported by carrier");
            return false;
        }

        int carrierRoamingNtnConnectType = getCarrierRoamingNtnConnectType(subId);
        if (carrierRoamingNtnConnectType != CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId() + "]: not manual "
                    + "connect. carrierRoamingNtnConnectType = " + carrierRoamingNtnConnectType);
            return false;
        }

        if (mOverrideNtnEligibility != null) {
            // TODO need to send the value from `mOverrideNtnEligibility` or simply true ?
            return true;
        }

        if (SatelliteServiceUtils.isCellularAvailable()) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: cellular is available");
            return false;
        }

        synchronized (mIsWifiConnectedLock) {
            if (mIsWifiConnected) {
                plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                        + "]: Wi-Fi is connected");
                return false;
            }
        }

        return true;
    }

    private boolean isSatelliteServiceSupportedByCarrier(int subId,
            @NetworkRegistrationInfo.ServiceType int serviceType) {
        List<String> satellitePlmnList = getSatellitePlmnsForCarrier(subId);
        for (String satellitePlmn : satellitePlmnList) {
            if (getSupportedSatelliteServices(subId, satellitePlmn).contains(serviceType)) {
                return true;
            }
        }
        return false;
    }

    /** return satellite phone */
    @Nullable
    public Phone getSatellitePhone() {
        synchronized (mSatellitePhoneLock) {
            return mSatellitePhone;
        }
    }

    /** Start PointingUI if it is required. */
    public void startPointingUI() {
        synchronized (mNeedsSatellitePointingLock) {
            plogd("startPointingUI: mNeedsSatellitePointing=" + mNeedsSatellitePointing
                    + ", mIsDemoModeEnabled=" + mIsDemoModeEnabled
                    + ", mIsEmergency=" + mIsEmergency);
            if (mNeedsSatellitePointing) {
                mPointingAppController.startPointingUI(false /*needFullScreenPointingUI*/,
                        mIsDemoModeEnabled, mIsEmergency);
            }
        }
    }

    private void requestIsSatelliteAllowedForCurrentLocation() {
        plogd("requestIsSatelliteAllowedForCurrentLocation()");
        synchronized (mSatellitePhoneLock) {
            if (mCheckingAccessRestrictionInProgress) {
                plogd("requestIsSatelliteCommunicationAllowedForCurrentLocation was already sent");
                return;
            }
            mCheckingAccessRestrictionInProgress = true;
        }

        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> callback =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Boolean result) {
                        plogd("requestIsSatelliteAllowedForCurrentLocation: result=" + result);
                        sendMessage(obtainMessage(
                                EVENT_SATELLITE_ACCESS_RESTRICTION_CHECKING_RESULT, result));
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException ex) {
                        plogd("requestIsSatelliteAllowedForCurrentLocation: onError, ex=" + ex);
                        sendMessage(obtainMessage(
                                EVENT_SATELLITE_ACCESS_RESTRICTION_CHECKING_RESULT, false));
                    }
                };
        requestIsSatelliteCommunicationAllowedForCurrentLocation(callback);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> callback) {
        SatelliteManager satelliteManager = mContext.getSystemService(SatelliteManager.class);
        if (satelliteManager == null) {
            ploge("requestIsSatelliteCommunicationAllowedForCurrentLocation: "
                    + "SatelliteManager is null");
            return;
        }

        satelliteManager.requestIsCommunicationAllowedForCurrentLocation(
                this::post, callback);
    }

    private void handleSatelliteAccessRestrictionCheckingResult(boolean satelliteAllowed) {
        synchronized (mSatellitePhoneLock) {
            mCheckingAccessRestrictionInProgress = false;
            boolean eligible = isCarrierRoamingNtnEligible(mSatellitePhone);
            plogd("handleSatelliteAccessRestrictionCheckingResult:"
                    + " satelliteAllowed=" + satelliteAllowed
                    + ", isCarrierRoamingNtnEligible=" + eligible
                    + ", mNtnEligibilityHysteresisTimedOut=" + mNtnEligibilityHysteresisTimedOut);
            if (satelliteAllowed && eligible && mNtnEligibilityHysteresisTimedOut) {
                updateLastNotifiedNtnEligibilityAndNotify(true);
                mNtnEligibilityHysteresisTimedOut = false;
            }
        }
    }

    private void handleEventSatelliteRegistrationFailure(int causeCode) {
        plogd("handleEventSatelliteRegistrationFailure: " + causeCode);

        List<ISatelliteModemStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteRegistrationFailureListeners.values().forEach(listener -> {
            try {
                listener.onRegistrationFailure(causeCode);
            } catch (RemoteException e) {
                logd("handleEventSatelliteRegistrationFailure RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteRegistrationFailureListeners.remove(listener.asBinder());
        });
    }

    /**
     * This API can be used by only CTS to override the cached value for the device overlay config
     * value :
     * config_satellite_gateway_service_package and
     * config_satellite_carrier_roaming_esos_provisioned_class.
     * These values are set before sending an intent to broadcast there are any change to list of
     * subscriber informations.
     *
     * @param name the name is one of the following that constitute an intent.
     *             component package name, or component class name.
     * @return {@code true} if the setting is successful, {@code false} otherwise.
     */
    public boolean setSatelliteSubscriberIdListChangedIntentComponent(String name) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            logd("setSatelliteSubscriberIdListChangedIntentComponent: carrierRoamingNbIotNtn is "
                    + "disabled");
            return false;
        }
        if (!isMockModemAllowed()) {
            logd("setSatelliteSubscriberIdListChangedIntentComponent: mock modem is not allowed");
            return false;
        }
        logd("setSatelliteSubscriberIdListChangedIntentComponent:" + name);

        if (name.contains("/")) {
            mChangeIntentComponent = true;
        } else {
            mChangeIntentComponent = false;
            return true;
        }
        boolean result = true;
        String[] cmdPart = name.split("/");
        switch (cmdPart[0]) {
            case "-p": {
                mConfigSatelliteGatewayServicePackage = cmdPart[1];
                break;
            }
            case "-c": {
                mConfigSatelliteCarrierRoamingEsosProvisionedClass = cmdPart[1];
                break;
            }
            default:
                logd("setSatelliteSubscriberIdListChangedIntentComponent: invalid name " + name);
                result = false;
                break;
        }
        return result;
    }

    private String getConfigSatelliteGatewayServicePackage() {
        if (!mChangeIntentComponent) {
            return getStringFromOverlayConfig(
                    R.string.config_satellite_gateway_service_package);
        }
        logd("getConfigSatelliteGatewayServicePackage: " + mConfigSatelliteGatewayServicePackage);
        return mConfigSatelliteGatewayServicePackage;
    }

    private String getConfigSatelliteCarrierRoamingEsosProvisionedClass() {
        if (!mChangeIntentComponent) {
            return getStringFromOverlayConfig(
                    R.string.config_satellite_carrier_roaming_esos_provisioned_class);
        }
        logd("getConfigSatelliteCarrierRoamingEsosProvisionedClass: "
                + mConfigSatelliteCarrierRoamingEsosProvisionedClass);
        return mConfigSatelliteCarrierRoamingEsosProvisionedClass;
    }

    private void registerDefaultSmsSubscriptionChangedBroadcastReceiver() {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("registerDefaultSmsSubscriptionChangedBroadcastReceiver: Flag "
                    + "CarrierRoamingNbIotNtn is disabled");
            return;
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);
        mContext.registerReceiver(mDefaultSmsSubscriptionChangedBroadcastReceiver, intentFilter);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected List<DeviceState> getSupportedDeviceStates() {
        return mContext.getSystemService(DeviceStateManager.class).getSupportedDeviceStates();
    }

    FeatureFlags getFeatureFlags() {
        return mFeatureFlags;
    }

    private boolean isSatelliteDisabled() {
        synchronized (mIsSatelliteEnabledLock) {
            return ((mIsSatelliteEnabled != null) && !mIsSatelliteEnabled);
        }
    }

    private boolean shouldStopWaitForEnableResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        if (argument.enableSatellite) return true;
        synchronized (mSatelliteEnabledRequestLock) {
            return !mWaitingForSatelliteModemOff;
        }
    }

    /**
     * Method to override the Carrier roaming Non-terrestrial network eligibility check
     *
     * @param state         flag to enable or disable the Ntn eligibility check.
     * @param resetRequired reset overriding the check with adb command.
     */
    public boolean overrideCarrierRoamingNtnEligibilityChanged(boolean state,
            boolean resetRequired) {
        Log.d(TAG, "overrideCarrierRoamingNtnEligibilityChanged state = " + state
                + "  resetRequired = " + resetRequired);
        if (resetRequired) {
            mOverrideNtnEligibility = null;
        } else {
            if (mOverrideNtnEligibility == null) {
                mOverrideNtnEligibility = new AtomicBoolean(state);
            } else {
                mOverrideNtnEligibility.set(state);
            }
            if (this.mSatellitePhone != null) {
                updateLastNotifiedNtnEligibilityAndNotify(state);
            }
        }
        return true;
    }
}
