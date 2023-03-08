/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.internal.telephony.subscription;

import static com.android.internal.telephony.TelephonyTestUtils.waitForMs;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_CARRIER_ID2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_CARRIER_NAME1;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_COUNTRY_CODE2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_ICCID1;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_ICCID2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_MCC2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_MNC2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_PHONE_NUMBER1;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_PHONE_NUMBER2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_SUBSCRIPTION_INFO1;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_SUBSCRIPTION_INFO2;
import static com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.FAKE_UUID1;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.app.PropertyInvalidatedCache;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.test.mock.MockContentResolver;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.ContextFixture;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.subscription.SubscriptionDatabaseManagerTest.SubscriptionProvider;
import com.android.internal.telephony.subscription.SubscriptionManagerService.SubscriptionManagerServiceCallback;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class SubscriptionManagerServiceTest extends TelephonyTest {

    private static final String CALLING_PACKAGE = "calling_package";

    private static final String CALLING_FEATURE = "calling_feature";

    private SubscriptionManagerService mSubscriptionManagerServiceUT;

    private final SubscriptionProvider mSubscriptionProvider = new SubscriptionProvider();

    // mocked
    private SubscriptionManagerServiceCallback mMockedSubscriptionManagerServiceCallback;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Before
    public void setUp() throws Exception {
        logd("SubscriptionManagerServiceTest +Setup!");
        super.setUp(getClass().getSimpleName());
        setupMocksForTelephonyPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        PropertyInvalidatedCache.disableForCurrentProcess("cache_key.is_compat_change_enabled");

        doReturn(true).when(mTelephonyManager).isVoiceCapable();
        mMockedSubscriptionManagerServiceCallback = Mockito.mock(
                SubscriptionManagerServiceCallback.class);
        ((MockContentResolver) mContext.getContentResolver()).addProvider(
                Telephony.Carriers.CONTENT_URI.getAuthority(), mSubscriptionProvider);
        mSubscriptionManagerServiceUT = new SubscriptionManagerService(mContext, Looper.myLooper());

        doAnswer(invocation -> {
            ((Runnable) invocation.getArguments()[0]).run();
            return null;
        }).when(mMockedSubscriptionManagerServiceCallback).invokeFromExecutor(any(Runnable.class));

        mSubscriptionManagerServiceUT.registerCallback(mMockedSubscriptionManagerServiceCallback);
        // Database loading is on a different thread. Need to wait a bit.
        waitForMs(100);
        processAllFutureMessages();

        // Revoke all permissions.
        mContextFixture.removeCallingOrSelfPermission(ContextFixture.PERMISSION_ENABLE_ALL);
        doReturn(AppOpsManager.MODE_DEFAULT).when(mAppOpsManager).noteOpNoThrow(anyString(),
                anyInt(), nullable(String.class), nullable(String.class), nullable(String.class));
        setIdentifierAccess(false);
        setPhoneNumberAccess(PackageManager.PERMISSION_DENIED);

        logd("SubscriptionManagerServiceTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        super.tearDown();
    }

    /**
     * Insert the subscription info to the database.
     *
     * @param subInfo The subscription to be inserted.
     * @return The new sub id.
     */
    private int insertSubscription(@NonNull SubscriptionInfoInternal subInfo) {
        try {
            subInfo = new SubscriptionInfoInternal.Builder(subInfo)
                    .setId(SubscriptionManager.INVALID_SUBSCRIPTION_ID).build();

            Field field = SubscriptionManagerService.class.getDeclaredField(
                    "mSubscriptionDatabaseManager");
            field.setAccessible(true);
            SubscriptionDatabaseManager sdbm =
                    (SubscriptionDatabaseManager) field.get(mSubscriptionManagerServiceUT);

            Class[] cArgs = new Class[1];
            cArgs[0] = SubscriptionInfoInternal.class;
            Method method = SubscriptionDatabaseManager.class.getDeclaredMethod(
                    "insertSubscriptionInfo", cArgs);
            method.setAccessible(true);
            int subId = (int) method.invoke(sdbm, subInfo);

            Class<?> WatchedMapClass = Class.forName("com.android.internal.telephony.subscription"
                    + ".SubscriptionManagerService$WatchedMap");
            field = SubscriptionManagerService.class.getDeclaredField("mSlotIndexToSubId");
            field.setAccessible(true);
            Object map = field.get(mSubscriptionManagerServiceUT);
            cArgs = new Class[2];
            cArgs[0] = Object.class;
            cArgs[1] = Object.class;

            method = WatchedMapClass.getDeclaredMethod("put", cArgs);
            method.setAccessible(true);
            method.invoke(map, subInfo.getSimSlotIndex(), subId);
            return subId;
        } catch (Exception e) {
            fail("Failed to insert subscription. e=" + e);
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    @Test
    public void testAddSubInfo() {
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        mSubscriptionManagerServiceUT.addSubInfo(FAKE_ICCID1, FAKE_CARRIER_NAME1,
                0, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
        processAllMessages();

        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));

        SubscriptionInfoInternal subInfo = mSubscriptionManagerServiceUT
                .getSubscriptionInfoInternal(1);
        assertThat(subInfo.getIccId()).isEqualTo(FAKE_ICCID1);
        assertThat(subInfo.getDisplayName()).isEqualTo(FAKE_CARRIER_NAME1);
        assertThat(subInfo.getSimSlotIndex()).isEqualTo(0);
        assertThat(subInfo.getSubscriptionType()).isEqualTo(
                SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
    }

    @Test
    public void testSetMccMnc() {
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        mSubscriptionManagerServiceUT.addSubInfo(FAKE_ICCID1, FAKE_CARRIER_NAME1,
                0, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
        processAllMessages();

        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
        Mockito.clearInvocations(mMockedSubscriptionManagerServiceCallback);
        mSubscriptionManagerServiceUT.setMccMnc(1, FAKE_MCC2 + FAKE_MNC2);
        processAllMessages();

        SubscriptionInfoInternal subInfo = mSubscriptionManagerServiceUT
                .getSubscriptionInfoInternal(1);
        assertThat(subInfo).isNotNull();
        assertThat(subInfo.getMcc()).isEqualTo(FAKE_MCC2);
        assertThat(subInfo.getMnc()).isEqualTo(FAKE_MNC2);
        verify(mMockedSubscriptionManagerServiceCallback, times(2)).onSubscriptionChanged(eq(1));
    }

    @Test
    public void testSetCountryIso() {
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        mSubscriptionManagerServiceUT.addSubInfo(FAKE_ICCID1, FAKE_CARRIER_NAME1,
                0, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
        processAllMessages();

        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
        Mockito.clearInvocations(mMockedSubscriptionManagerServiceCallback);
        mSubscriptionManagerServiceUT.setCountryIso(1, FAKE_COUNTRY_CODE2);
        processAllMessages();

        SubscriptionInfoInternal subInfo = mSubscriptionManagerServiceUT
                .getSubscriptionInfoInternal(1);
        assertThat(subInfo).isNotNull();
        assertThat(subInfo.getCountryIso()).isEqualTo(FAKE_COUNTRY_CODE2);
        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
    }

    @Test
    public void testSetCarrierId() {
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        mSubscriptionManagerServiceUT.addSubInfo(FAKE_ICCID1, FAKE_CARRIER_NAME1,
                0, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
        processAllMessages();

        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
        Mockito.clearInvocations(mMockedSubscriptionManagerServiceCallback);
        mSubscriptionManagerServiceUT.setCarrierId(1, FAKE_CARRIER_ID2);
        processAllMessages();

        SubscriptionInfoInternal subInfo = mSubscriptionManagerServiceUT
                .getSubscriptionInfoInternal(1);
        assertThat(subInfo).isNotNull();
        assertThat(subInfo.getCarrierId()).isEqualTo(FAKE_CARRIER_ID2);
        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
    }

    @Test
    public void testSetPhoneNumber() {
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        mSubscriptionManagerServiceUT.addSubInfo(FAKE_ICCID1, FAKE_CARRIER_NAME1,
                0, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
        processAllMessages();

        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
        Mockito.clearInvocations(mMockedSubscriptionManagerServiceCallback);

        // Source IMS is not acceptable
        assertThrows(IllegalArgumentException.class,
                () -> mSubscriptionManagerServiceUT.setPhoneNumber(1,
                        SubscriptionManager.PHONE_NUMBER_SOURCE_IMS, FAKE_PHONE_NUMBER2,
                        CALLING_PACKAGE, CALLING_FEATURE));

        // Caller does not have carrier privilege
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.setPhoneNumber(1,
                        SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER, FAKE_PHONE_NUMBER2,
                        CALLING_PACKAGE, CALLING_FEATURE));

        // Grant carrier privilege
        setCarrierPrivilegesForSubId(true, 1);

        mSubscriptionManagerServiceUT.setPhoneNumber(1,
                SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER, FAKE_PHONE_NUMBER2,
                CALLING_PACKAGE, CALLING_FEATURE);
        processAllMessages();

        SubscriptionInfoInternal subInfo = mSubscriptionManagerServiceUT
                .getSubscriptionInfoInternal(1);
        assertThat(subInfo).isNotNull();
        assertThat(subInfo.getNumberFromCarrier()).isEqualTo(FAKE_PHONE_NUMBER2);
        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(1));
    }

    @Test
    public void testGetAllSubInfoList() {
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        doReturn(new int[]{1, 2}).when(mSubscriptionManager).getCompleteActiveSubscriptionIdList();
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(FAKE_SUBSCRIPTION_INFO2);
        processAllMessages();

        verify(mMockedSubscriptionManagerServiceCallback).onSubscriptionChanged(eq(2));

        // Should throw security exception if the caller does not have permission.
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.getAllSubInfoList(
                        CALLING_PACKAGE, CALLING_FEATURE));

        // Grant carrier privilege for sub 1
        setCarrierPrivilegesForSubId(true, 1);
        // Grant carrier privilege for sub 2
        setCarrierPrivilegesForSubId(true, 2);

        List<SubscriptionInfo> subInfos = mSubscriptionManagerServiceUT.getAllSubInfoList(
                CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(2);

        assertThat(subInfos.get(0)).isEqualTo(FAKE_SUBSCRIPTION_INFO1.toSubscriptionInfo());

        assertThat(subInfos.get(1)).isEqualTo(FAKE_SUBSCRIPTION_INFO2.toSubscriptionInfo());

        // Revoke carrier privilege for sub 2
        setCarrierPrivilegesForSubId(false, 2);

        subInfos = mSubscriptionManagerServiceUT.getAllSubInfoList(
                CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(2);

        assertThat(subInfos.get(0).getIccId()).isEqualTo(FAKE_ICCID1);
        assertThat(subInfos.get(0).getCardString()).isEqualTo(FAKE_ICCID1);
        assertThat(subInfos.get(0).getNumber()).isEqualTo(FAKE_PHONE_NUMBER1);
        // identifiers should be empty due to insufficient permission.
        assertThat(subInfos.get(1).getIccId()).isEmpty();
        assertThat(subInfos.get(1).getCardString()).isEmpty();
        assertThat(subInfos.get(1).getNumber()).isEmpty();

        // Grant READ_PHONE_STATE permission
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE);
        // Grant identifier access
        setIdentifierAccess(true);
        // Revoke carrier privileges.
        setCarrierPrivilegesForSubId(false, 1);
        setCarrierPrivilegesForSubId(false, 2);

        subInfos = mSubscriptionManagerServiceUT.getAllSubInfoList(
                CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(2);

        assertThat(subInfos.get(0).getIccId()).isEqualTo(FAKE_ICCID1);
        assertThat(subInfos.get(0).getCardString()).isEqualTo(FAKE_ICCID1);
        // Phone number should be empty
        assertThat(subInfos.get(0).getNumber()).isEmpty();
        assertThat(subInfos.get(1).getIccId()).isEqualTo(FAKE_ICCID2);
        assertThat(subInfos.get(1).getCardString()).isEqualTo(FAKE_ICCID2);
        // Phone number should be empty
        assertThat(subInfos.get(1).getNumber()).isEmpty();

        // Grant phone number access
        doReturn(PackageManager.PERMISSION_GRANTED).when(mMockLegacyPermissionManager)
                .checkPhoneNumberAccess(anyString(), anyString(), anyString(), anyInt(), anyInt());

        subInfos = mSubscriptionManagerServiceUT.getAllSubInfoList(
                CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(2);
        assertThat(subInfos.get(0).getNumber()).isEqualTo(FAKE_PHONE_NUMBER1);
        assertThat(subInfos.get(1).getNumber()).isEqualTo(FAKE_PHONE_NUMBER2);
    }

    @Test
    @EnableCompatChanges({SubscriptionManagerService.REQUIRE_DEVICE_IDENTIFIERS_FOR_GROUP_UUID})
    public void testGetSubscriptionsInGroup() {
        doReturn(new int[]{1, 2}).when(mSubscriptionManager).getCompleteActiveSubscriptionIdList();

        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        SubscriptionInfoInternal anotherSubInfo =
                new SubscriptionInfoInternal.Builder(FAKE_SUBSCRIPTION_INFO2)
                        .setGroupUuid(FAKE_UUID1)
                        .build();
        insertSubscription(anotherSubInfo);

        // Throw exception is the new behavior.
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.getSubscriptionsInGroup(
                        ParcelUuid.fromString(FAKE_UUID1), CALLING_PACKAGE, CALLING_FEATURE));

        // Grant carrier privilege on sub 1 and 2
        setCarrierPrivilegesForSubId(true, 1);
        setCarrierPrivilegesForSubId(true, 2);
        List<SubscriptionInfo> subInfos = mSubscriptionManagerServiceUT.getSubscriptionsInGroup(
                ParcelUuid.fromString(FAKE_UUID1), CALLING_PACKAGE, CALLING_FEATURE);

        assertThat(subInfos).hasSize(2);
        assertThat(subInfos.get(0)).isEqualTo(FAKE_SUBSCRIPTION_INFO1.toSubscriptionInfo());
        assertThat(subInfos.get(1)).isEqualTo(anotherSubInfo.toSubscriptionInfo());

        // Grant READ_PHONE_STATE permission
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE);
        setIdentifierAccess(false);
        setCarrierPrivilegesForSubId(false, 1);
        setCarrierPrivilegesForSubId(false, 2);
        doNothing().when(mContext).enforcePermission(
                eq(android.Manifest.permission.READ_PHONE_STATE), anyInt(), anyInt(), anyString());

        // Throw exception is the new behavior. Only has READ_PHONE_STATE is not enough. Need
        // identifier access as well.
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.getSubscriptionsInGroup(
                        ParcelUuid.fromString(FAKE_UUID1), CALLING_PACKAGE, CALLING_FEATURE));

        // Grant identifier access
        setIdentifierAccess(true);
        // Grant phone number access
        setPhoneNumberAccess(PackageManager.PERMISSION_GRANTED);

        subInfos = mSubscriptionManagerServiceUT.getSubscriptionsInGroup(
                ParcelUuid.fromString(FAKE_UUID1), CALLING_PACKAGE, CALLING_FEATURE);

        assertThat(subInfos).hasSize(2);
        assertThat(subInfos).containsExactlyElementsIn(
                List.of(FAKE_SUBSCRIPTION_INFO1.toSubscriptionInfo(),
                        anotherSubInfo.toSubscriptionInfo()));
    }

    @Test
    public void testGetAvailableSubscriptionInfoList() {
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        SubscriptionInfoInternal anotherSubInfo =
                new SubscriptionInfoInternal.Builder(FAKE_SUBSCRIPTION_INFO2)
                        .setSimSlotIndex(SubscriptionManager.INVALID_SIM_SLOT_INDEX)
                        .setType(SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM)
                        .build();
        insertSubscription(anotherSubInfo);

        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.getAvailableSubscriptionInfoList(
                        CALLING_PACKAGE, CALLING_FEATURE));
        // Grant carrier privilege for sub 1
        setCarrierPrivilegesForSubId(true, 1);

        // Not yet planned for carrier apps to access this API.
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.getAvailableSubscriptionInfoList(
                        CALLING_PACKAGE, CALLING_FEATURE));

        // Grant READ_PHONE_STATE permission, which is not enough for this API.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE);
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.getAvailableSubscriptionInfoList(
                        CALLING_PACKAGE, CALLING_FEATURE));

        // Grant READ_PRIVILEGED_PHONE_STATE permission
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        List<SubscriptionInfo> subInfos = mSubscriptionManagerServiceUT
                .getAvailableSubscriptionInfoList(CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(1);
        assertThat(subInfos.get(0)).isEqualTo(FAKE_SUBSCRIPTION_INFO1.toSubscriptionInfo());
    }

    @Test
    public void testSetDefaultVoiceSubId() throws Exception {
        // Grant MODIFY_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(FAKE_SUBSCRIPTION_INFO2);
        // Remove MODIFY_PHONE_STATE
        mContextFixture.removeCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        // Should fail without MODIFY_PHONE_STATE
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.setDefaultVoiceSubId(1));

        // Grant MODIFY_PHONE_STATE permission
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        mSubscriptionManagerServiceUT.setDefaultVoiceSubId(1);

        assertThat(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION)).isEqualTo(1);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(
                captorIntent.capture(), eq(UserHandle.ALL));

        Intent intent = captorIntent.getAllValues().get(0);
        assertThat(intent.getAction()).isEqualTo(
                TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);

        Bundle b = intent.getExtras();

        assertThat(b.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isTrue();
        assertThat(b.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isEqualTo(1);

        intent = captorIntent.getAllValues().get(1);
        assertThat(intent.getAction()).isEqualTo(
                SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);

        b = intent.getExtras();

        assertThat(b.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isTrue();
        assertThat(b.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isEqualTo(1);
    }

    @Test
    public void testSetDefaultDataSubId() throws Exception {
        doReturn(false).when(mTelephonyManager).isVoiceCapable();
        // Grant MODIFY_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(FAKE_SUBSCRIPTION_INFO2);
        // Remove MODIFY_PHONE_STATE
        mContextFixture.removeCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        // Should fail without MODIFY_PHONE_STATE
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.setDefaultDataSubId(1));

        // Grant MODIFY_PHONE_STATE permission
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        mSubscriptionManagerServiceUT.setDefaultDataSubId(1);

        assertThat(Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION)).isEqualTo(1);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(
                captorIntent.capture(), eq(UserHandle.ALL));

        Intent intent = captorIntent.getAllValues().get(0);
        assertThat(intent.getAction()).isEqualTo(
                TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);

        Bundle b = intent.getExtras();

        assertThat(b.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isTrue();
        assertThat(b.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isEqualTo(1);

        intent = captorIntent.getAllValues().get(1);
        assertThat(intent.getAction()).isEqualTo(
                SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);

        b = intent.getExtras();

        assertThat(b.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isTrue();
        assertThat(b.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isEqualTo(1);
    }

    @Test
    public void testSetDefaultSmsSubId() throws Exception {
        // Grant MODIFY_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(FAKE_SUBSCRIPTION_INFO2);
        // Remove MODIFY_PHONE_STATE
        mContextFixture.removeCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        // Should fail without MODIFY_PHONE_STATE
        assertThrows(SecurityException.class,
                () -> mSubscriptionManagerServiceUT.setDefaultSmsSubId(1));

        // Grant MODIFY_PHONE_STATE permission
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        mSubscriptionManagerServiceUT.setDefaultSmsSubId(1);

        assertThat(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION)).isEqualTo(1);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(captorIntent.capture(), eq(UserHandle.ALL));

        Intent intent = captorIntent.getValue();
        assertThat(intent.getAction()).isEqualTo(
                SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);

        Bundle b = intent.getExtras();

        assertThat(b.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isTrue();
        assertThat(b.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).isEqualTo(1);
    }

    @Test
    public void testIsActiveSubId() {
        // Grant MODIFY_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(new SubscriptionInfoInternal.Builder(FAKE_SUBSCRIPTION_INFO2)
                .setSimSlotIndex(SubscriptionManager.INVALID_SIM_SLOT_INDEX).build());
        // Remove MODIFY_PHONE_STATE
        mContextFixture.removeCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        // Should fail without READ_PHONE_STATE
        assertThrows(SecurityException.class, () -> mSubscriptionManagerServiceUT
                .isActiveSubId(1, CALLING_PACKAGE, CALLING_FEATURE));

        // Grant READ_PRIVILEGED_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        assertThat(mSubscriptionManagerServiceUT.isActiveSubId(
                1, CALLING_PACKAGE, CALLING_FEATURE)).isTrue();
        assertThat(mSubscriptionManagerServiceUT.isActiveSubId(
                2, CALLING_PACKAGE, CALLING_FEATURE)).isFalse();
    }

    @Test
    public void testGetActiveSubscriptionInfoList() {
        doReturn(new int[]{1}).when(mSubscriptionManager).getCompleteActiveSubscriptionIdList();
        // Grant MODIFY_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(new SubscriptionInfoInternal.Builder(FAKE_SUBSCRIPTION_INFO2)
                .setSimSlotIndex(SubscriptionManager.INVALID_SIM_SLOT_INDEX).build());
        // Remove MODIFY_PHONE_STATE
        mContextFixture.removeCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        // Should fail without READ_PHONE_STATE
        assertThrows(SecurityException.class, () -> mSubscriptionManagerServiceUT
                .getActiveSubscriptionInfoList(CALLING_PACKAGE, CALLING_FEATURE));

        // Grant READ_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE);

        List<SubscriptionInfo> subInfos = mSubscriptionManagerServiceUT
                .getActiveSubscriptionInfoList(CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(1);
        assertThat(subInfos.get(0).getIccId()).isEmpty();
        assertThat(subInfos.get(0).getCardString()).isEmpty();
        assertThat(subInfos.get(0).getNumber()).isEmpty();
        assertThat(subInfos.get(0).getGroupUuid()).isNull();

        // Grant carrier privilege
        setCarrierPrivilegesForSubId(true, 1);

        subInfos = mSubscriptionManagerServiceUT
                .getActiveSubscriptionInfoList(CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfos).hasSize(1);
        assertThat(subInfos.get(0)).isEqualTo(FAKE_SUBSCRIPTION_INFO1.toSubscriptionInfo());
    }

    @Test
    public void testGetActiveSubscriptionInfoForSimSlotIndex() {
        // Grant MODIFY_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);
        insertSubscription(FAKE_SUBSCRIPTION_INFO1);
        insertSubscription(FAKE_SUBSCRIPTION_INFO2);
        // Remove MODIFY_PHONE_STATE
        mContextFixture.removeCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        // Should fail without READ_PHONE_STATE
        assertThrows(SecurityException.class, () -> mSubscriptionManagerServiceUT
                .getActiveSubscriptionInfoForSimSlotIndex(0, CALLING_PACKAGE, CALLING_FEATURE));

        // Grant READ_PHONE_STATE permission for insertion.
        mContextFixture.addCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE);
        SubscriptionInfo subInfo = mSubscriptionManagerServiceUT
                .getActiveSubscriptionInfoForSimSlotIndex(0, CALLING_PACKAGE,
                        CALLING_FEATURE);
        assertThat(subInfo).isNotNull();
        assertThat(subInfo.getSubscriptionId()).isEqualTo(1);
        assertThat(subInfo.getIccId()).isEmpty();
        assertThat(subInfo.getNumber()).isEmpty();

        // Grant carrier privilege for sub 1
        setCarrierPrivilegesForSubId(true, 1);
        subInfo = mSubscriptionManagerServiceUT.getActiveSubscriptionInfoForSimSlotIndex(
                0, CALLING_PACKAGE, CALLING_FEATURE);
        assertThat(subInfo).isEqualTo(FAKE_SUBSCRIPTION_INFO1.toSubscriptionInfo());
    }
}
