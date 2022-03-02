/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.internal.telephony.data;

import com.android.internal.telephony.data.DataNetworkController.DataNetworkControllerCallback;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.NetworkAgent;
import android.telephony.Annotation.ValidationStatus;
import android.telephony.CarrierConfigManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.data.DataStallRecoveryManager.DataStallRecoveryManagerCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DataStallRecoveryManagerTest extends TelephonyTest {
    // Mocked classes
    private DataStallRecoveryManagerCallback mDataStallRecoveryManagerCallback;

    private DataStallRecoveryManager mDataStallRecoveryManager;

    @Before
    public void setUp() throws Exception {
        logd("DataStallRecoveryManagerTest +Setup!");
        super.setUp(getClass().getSimpleName());
        mDataStallRecoveryManagerCallback = mock(DataStallRecoveryManagerCallback.class);
        doReturn(true).when(mPhone).isUsingNewDataStack();
        mCarrierConfigManager = mPhone.getContext().getSystemService(CarrierConfigManager.class);
        long[] dataStallRecoveryTimersArray = new long[] {1, 1, 1};
        boolean[] dataStallRecoveryStepsArray = new boolean[] {false, false, false, false};
        doReturn(dataStallRecoveryTimersArray)
                .when(mDataConfigManager)
                .getDataStallRecoveryDelayMillis();
        doReturn(dataStallRecoveryStepsArray)
                .when(mDataConfigManager)
                .getDataStallRecoveryShouldSkipArray();
        doReturn(mSST).when(mPhone).getServiceStateTracker();
        doAnswer(
                invocation -> {
                    ((Runnable) invocation.getArguments()[0]).run();
                    return null;
                })
                .when(mDataStallRecoveryManagerCallback)
                .invokeFromExecutor(any(Runnable.class));
        doReturn("").when(mSubscriptionController).getDataEnabledOverrideRules(anyInt());

        mDataStallRecoveryManager =
                new DataStallRecoveryManager(
                        mPhone,
                        mDataNetworkController,
                        mMockedWwanDataServiceManager,
                        mTestableLooper.getLooper(),
                        mDataStallRecoveryManagerCallback);
        logd("DataStallRecoveryManagerTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        mDataStallRecoveryManager = null;
        super.tearDown();
    }

    private void sendValidationStatusCallback(@ValidationStatus int status) {
        ArgumentCaptor<DataNetworkControllerCallback> dataNetworkControllerCallbackCaptor =
                ArgumentCaptor.forClass(DataNetworkControllerCallback.class);
        verify(mDataNetworkController)
                .registerDataNetworkControllerCallback(
                        dataNetworkControllerCallbackCaptor.capture());
        DataNetworkControllerCallback dataNetworkControllerCallback =
                dataNetworkControllerCallbackCaptor.getValue();
        dataNetworkControllerCallback.onInternetDataNetworkValidationStatusChanged(status);
    }

    @Test
    public void testRecoveryStepPDPReset() throws Exception {
        mDataStallRecoveryManager.setRecoveryAction(1);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mDataStallRecoveryManagerCallback).onDataStallReestablishInternet();
    }

    @Test
    public void testRecoveryStepRestartRadio() throws Exception {
        mDataStallRecoveryManager.setRecoveryAction(2);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mSST, times(1)).powerOffRadioSafely();
    }

    @Test
    public void testRecoveryStepModemReset() throws Exception {
        mDataStallRecoveryManager.setRecoveryAction(3);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        processAllFutureMessages();

        verify(mPhone, times(1)).rebootModem(any());
    }

    @Test
    public void testDoNotDoRecoveryActionWhenPoorSignal() throws Exception {
        mDataStallRecoveryManager.setRecoveryAction(2);
        doReturn(1).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        processAllFutureMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
    }

    @Test
    public void testDoNotDoRecoveryActionWhenDialCall() throws Exception {
        mDataStallRecoveryManager.setRecoveryAction(2);
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.OFFHOOK).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        processAllFutureMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(2);
    }

    @Test
    public void testDoNotDoRecoveryBySendMessageDelayedWhenDialCall() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        mDataStallRecoveryManager.setRecoveryAction(0);
        doReturn(PhoneConstants.State.OFFHOOK).when(mPhone).getState();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);
        mDataStallRecoveryManager.sendMessageDelayed(
                mDataStallRecoveryManager.obtainMessage(2), 1000);
        moveTimeForward(15000);
        processAllMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(2);
    }
}
