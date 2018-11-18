/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_TEMPORARY;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.dhcp.DhcpClient;
import android.net.ip.IpClient;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.INetworkManagementService;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.security.KeyStore;
import android.support.test.filters.SmallTest;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IState;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointProvisioningTestUtil;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Unit tests for {@link com.android.server.wifi.ClientModeImpl}.
 */
@SmallTest
public class ClientModeImplTest {
    public static final String TAG = "ClientModeImplTest";

    private static final int MANAGED_PROFILE_UID = 1100000;
    private static final int OTHER_USER_UID = 1200000;
    private static final int LOG_REC_LIMIT_IN_VERBOSE_MODE =
            (ActivityManager.isLowRamDeviceStatic()
                    ? ClientModeImpl.NUM_LOG_RECS_VERBOSE_LOW_MEMORY
                    : ClientModeImpl.NUM_LOG_RECS_VERBOSE);
    private static final int FRAMEWORK_NETWORK_ID = 0;
    private static final int TEST_RSSI = -54;
    private static final int TEST_NETWORK_ID = 54;
    private static final int WPS_SUPPLICANT_NETWORK_ID = 5;
    private static final int WPS_FRAMEWORK_NETWORK_ID = 10;
    private static final String DEFAULT_TEST_SSID = "\"GoogleGuest\"";
    private static final String OP_PACKAGE_NAME = "com.xxx";
    private static final int TEST_UID = Process.SYSTEM_UID + 1000;
    private static final MacAddress TEST_GLOBAL_MAC_ADDRESS =
            MacAddress.fromString("10:22:34:56:78:92");
    private static final MacAddress TEST_LOCAL_MAC_ADDRESS =
            MacAddress.fromString("2a:53:43:c3:56:21");

    // NetworkAgent creates threshold ranges with Integers
    private static final int RSSI_THRESHOLD_MAX = -30;
    private static final int RSSI_THRESHOLD_MIN = -76;
    // Threshold breach callbacks are called with bytes
    private static final byte RSSI_THRESHOLD_BREACH_MIN = -80;
    private static final byte RSSI_THRESHOLD_BREACH_MAX = -20;

    private long mBinderToken;

    private static <T> T mockWithInterfaces(Class<T> class1, Class<?>... interfaces) {
        return mock(class1, withSettings().extraInterfaces(interfaces));
    }

    private static <T, I> IBinder mockService(Class<T> class1, Class<I> iface) {
        T tImpl = mockWithInterfaces(class1, iface);
        IBinder binder = mock(IBinder.class);
        when(((IInterface) tImpl).asBinder()).thenReturn(binder);
        when(binder.queryLocalInterface(iface.getCanonicalName()))
                .thenReturn((IInterface) tImpl);
        return binder;
    }

    private void enableDebugLogs() {
        mCmi.enableVerboseLogging(1);
    }

    private FrameworkFacade getFrameworkFacade() throws Exception {
        FrameworkFacade facade = mock(FrameworkFacade.class);

        when(facade.getService(Context.NETWORKMANAGEMENT_SERVICE)).thenReturn(
                mockWithInterfaces(IBinder.class, INetworkManagementService.class));

        IBinder p2pBinder = mockService(WifiP2pServiceImpl.class, IWifiP2pManager.class);
        when(facade.getService(Context.WIFI_P2P_SERVICE)).thenReturn(p2pBinder);

        WifiP2pServiceImpl p2pm = (WifiP2pServiceImpl) p2pBinder.queryLocalInterface(
                IWifiP2pManager.class.getCanonicalName());

        final CountDownLatch untilDone = new CountDownLatch(1);
        mP2pThread = new HandlerThread("WifiP2pMockThread") {
            @Override
            protected void onLooperPrepared() {
                untilDone.countDown();
            }
        };

        mP2pThread.start();
        untilDone.await();

        Handler handler = new Handler(mP2pThread.getLooper());
        when(p2pm.getP2pStateMachineMessenger()).thenReturn(new Messenger(handler));

        IBinder batteryStatsBinder = mockService(BatteryStats.class, IBatteryStats.class);
        when(facade.getService(BatteryStats.SERVICE_NAME)).thenReturn(batteryStatsBinder);

        when(facade.makeIpClient(any(Context.class), anyString(), any(IpClient.Callback.class)))
                .then(new AnswerWithArguments() {
                    public IpClient answer(
                            Context context, String ifname, IpClient.Callback callback) {
                        mIpClientCallback = callback;
                        return mIpClient;
                    }
                });

        return facade;
    }

    private Context getContext() throws Exception {
        PackageManager pkgMgr = mock(PackageManager.class);
        when(pkgMgr.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)).thenReturn(true);

        Context context = mock(Context.class);
        when(context.getPackageManager()).thenReturn(pkgMgr);

        MockContentResolver mockContentResolver = new MockContentResolver();
        mockContentResolver.addProvider(Settings.AUTHORITY,
                new MockContentProvider(context) {
                    @Override
                    public Bundle call(String method, String arg, Bundle extras) {
                        return new Bundle();
                    }
                });
        when(context.getContentResolver()).thenReturn(mockContentResolver);

        when(context.getSystemService(Context.POWER_SERVICE)).thenReturn(
                new PowerManager(context, mock(IPowerManager.class), new Handler()));

        mAlarmManager = new TestAlarmManager();
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(
                mAlarmManager.getAlarmManager());

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
                mConnectivityManager);

        when(context.getOpPackageName()).thenReturn(OP_PACKAGE_NAME);

        return context;
    }

    private MockResources getMockResources() {
        MockResources resources = new MockResources();
        return resources;
    }

    private IState getCurrentState() throws
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = StateMachine.class.getDeclaredMethod("getCurrentState");
        method.setAccessible(true);
        return (IState) method.invoke(mCmi);
    }

    private static HandlerThread getCmiHandlerThread(ClientModeImpl cmi) throws
            NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Field field = StateMachine.class.getDeclaredField("mSmThread");
        field.setAccessible(true);
        return (HandlerThread) field.get(cmi);
    }

    private static void stopLooper(final Looper looper) throws Exception {
        new Handler(looper).post(new Runnable() {
            @Override
            public void run() {
                looper.quitSafely();
            }
        });
    }

    private void dumpState() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mCmi.dump(null, writer, null);
        writer.flush();
        Log.d(TAG, "ClientModeImpl state -" + stream.toString());
    }

    private static ScanDetail getGoogleGuestScanDetail(int rssi, String bssid, int freq) {
        ScanResult.InformationElement[] ie = new ScanResult.InformationElement[1];
        ie[0] = ScanResults.generateSsidIe(sSSID);
        NetworkDetail nd = new NetworkDetail(sBSSID, ie, new ArrayList<String>(), sFreq);
        ScanDetail detail = new ScanDetail(nd, sWifiSsid, bssid, "", rssi, freq,
                Long.MAX_VALUE, /* needed so that scan results aren't rejected because
                                   there older than scan start */
                ie, new ArrayList<String>());
        return detail;
    }

    private ArrayList<ScanDetail> getMockScanResults() {
        ScanResults sr = ScanResults.create(0, 2412, 2437, 2462, 5180, 5220, 5745, 5825);
        ArrayList<ScanDetail> list = sr.getScanDetailArrayList();

        list.add(getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        return list;
    }

    private void injectDhcpSuccess(DhcpResults dhcpResults) {
        mIpClientCallback.onNewDhcpResults(dhcpResults);
        mIpClientCallback.onProvisioningSuccess(new LinkProperties());
    }

    private void injectDhcpFailure() {
        mIpClientCallback.onNewDhcpResults(null);
        mIpClientCallback.onProvisioningFailure(new LinkProperties());
    }

    static final String   sSSID = "\"GoogleGuest\"";
    static final WifiSsid sWifiSsid = WifiSsid.createFromAsciiEncoded(sSSID);
    static final String   sBSSID = "01:02:03:04:05:06";
    static final String   sBSSID1 = "02:01:04:03:06:05";
    static final int      sFreq = 2437;
    static final int      sFreq1 = 5240;
    static final String   WIFI_IFACE_NAME = "mockWlan";

    ClientModeImpl mCmi;
    HandlerThread mWifiCoreThread;
    HandlerThread mP2pThread;
    HandlerThread mSyncThread;
    AsyncChannel  mCmiAsyncChannel;
    TestAlarmManager mAlarmManager;
    MockWifiMonitor mWifiMonitor;
    TestLooper mLooper;
    Context mContext;
    MockResources mResources;
    FrameworkFacade mFrameworkFacade;
    IpClient.Callback mIpClientCallback;
    PhoneStateListener mPhoneStateListener;
    OsuProvider mOsuProvider;
    ContentObserver mContentObserver;

    @Mock WifiScanner mWifiScanner;
    @Mock SupplicantStateTracker mSupplicantStateTracker;
    @Mock WifiMetrics mWifiMetrics;
    @Mock UserManager mUserManager;
    @Mock BackupManagerProxy mBackupManagerProxy;
    @Mock WifiCountryCode mCountryCode;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock PropertyService mPropertyService;
    @Mock BuildProperties mBuildProperties;
    @Mock IBinder mPackageManagerBinder;
    @Mock SarManager mSarManager;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiNative mWifiNative;
    @Mock WifiScoreCard mWifiScoreCard;
    @Mock WifiConnectivityManager mWifiConnectivityManager;
    @Mock WifiStateTracker mWifiStateTracker;
    @Mock PasspointManager mPasspointManager;
    @Mock SelfRecovery mSelfRecovery;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock IpClient mIpClient;
    @Mock TelephonyManager mTelephonyManager;
    @Mock WrongPasswordNotifier mWrongPasswordNotifier;
    @Mock Clock mClock;
    @Mock ScanDetailCache mScanDetailCache;
    @Mock BaseWifiDiagnostics mWifiDiagnostics;
    @Mock ConnectivityManager mConnectivityManager;
    @Mock IProvisioningCallback mProvisioningCallback;
    @Mock HandlerThread mWifiServiceHandlerThread;
    @Mock WifiPermissionsWrapper mWifiPermissionsWrapper;
    @Mock WakeupController mWakeupController;
    @Mock WifiDataStall mWifiDataStall;
    @Mock WifiNetworkFactory mWifiNetworkFactory;
    @Mock UntrustedWifiNetworkFactory mUntrustedWifiNetworkFactory;

    final ArgumentCaptor<WifiNative.InterfaceCallback> mInterfaceCallbackCaptor =
            ArgumentCaptor.forClass(WifiNative.InterfaceCallback.class);

    public ClientModeImplTest() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        // Ensure looper exists
        mLooper = new TestLooper();

        MockitoAnnotations.initMocks(this);

        /** uncomment this to enable logs from ClientModeImpls */
        // enableDebugLogs();

        mWifiMonitor = new MockWifiMonitor();
        when(mWifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(mWifiInjector.getClock()).thenReturn(new Clock());
        when(mWifiInjector.getWifiLastResortWatchdog()).thenReturn(mWifiLastResortWatchdog);
        when(mWifiInjector.getPropertyService()).thenReturn(mPropertyService);
        when(mWifiInjector.getBuildProperties()).thenReturn(mBuildProperties);
        when(mWifiInjector.getKeyStore()).thenReturn(mock(KeyStore.class));
        when(mWifiInjector.getWifiBackupRestore()).thenReturn(mock(WifiBackupRestore.class));
        when(mWifiInjector.getWifiDiagnostics()).thenReturn(mWifiDiagnostics);
        when(mWifiInjector.getWifiConfigManager()).thenReturn(mWifiConfigManager);
        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        when(mWifiInjector.makeWifiConnectivityManager(any()))
                .thenReturn(mWifiConnectivityManager);
        when(mWifiInjector.getPasspointManager()).thenReturn(mPasspointManager);
        when(mWifiInjector.getWifiStateTracker()).thenReturn(mWifiStateTracker);
        when(mWifiInjector.getWifiMonitor()).thenReturn(mWifiMonitor);
        when(mWifiInjector.getWifiNative()).thenReturn(mWifiNative);
        when(mWifiInjector.getSelfRecovery()).thenReturn(mSelfRecovery);
        when(mWifiInjector.getWifiPermissionsUtil()).thenReturn(mWifiPermissionsUtil);
        when(mWifiInjector.makeTelephonyManager()).thenReturn(mTelephonyManager);
        when(mWifiInjector.getClock()).thenReturn(mClock);
        when(mWifiServiceHandlerThread.getLooper()).thenReturn(mLooper.getLooper());
        when(mWifiInjector.getWifiServiceHandlerThread()).thenReturn(mWifiServiceHandlerThread);
        when(mWifiInjector.getWifiPermissionsWrapper()).thenReturn(mWifiPermissionsWrapper);
        when(mWifiInjector.getWakeupController()).thenReturn(mWakeupController);
        when(mWifiInjector.getScoringParams()).thenReturn(new ScoringParams());
        when(mWifiInjector.getWifiDataStall()).thenReturn(mWifiDataStall);
        when(mWifiInjector.makeWifiNetworkFactory(any(), any())).thenReturn(mWifiNetworkFactory);
        when(mWifiInjector.makeUntrustedWifiNetworkFactory(any(), any()))
                .thenReturn(mUntrustedWifiNetworkFactory);
        when(mWifiNative.initialize()).thenReturn(true);

        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(true);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(true);

        mFrameworkFacade = getFrameworkFacade();
        mContext = getContext();

        mResources = getMockResources();
        when(mContext.getResources()).thenReturn(mResources);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_FREQUENCY_BAND,
                WifiManager.WIFI_FREQUENCY_BAND_AUTO)).thenReturn(
                WifiManager.WIFI_FREQUENCY_BAND_AUTO);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED,
                0)).thenReturn(0);

        when(mFrameworkFacade.makeSupplicantStateTracker(
                any(Context.class), any(WifiConfigManager.class),
                any(Handler.class))).thenReturn(mSupplicantStateTracker);

        when(mUserManager.getProfileParent(11))
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "owner", 0));
        when(mUserManager.getProfiles(UserHandle.USER_SYSTEM)).thenReturn(Arrays.asList(
                new UserInfo(UserHandle.USER_SYSTEM, "owner", 0),
                new UserInfo(11, "managed profile", 0)));

        doAnswer(new AnswerWithArguments() {
            public void answer(PhoneStateListener phoneStateListener, int events)
                    throws Exception {
                mPhoneStateListener = phoneStateListener;
            }
        }).when(mTelephonyManager).listen(any(PhoneStateListener.class), anyInt());

        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiPermissionsWrapper.getLocalMacAddressPermission(anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        initializeCmi();

        mOsuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);

        /* Capture the ContentObserver for Connected MAC Randomization. */
        ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);
        verify(mFrameworkFacade).registerContentObserver(eq(mContext), eq(Settings.Global.getUriFor(
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED)), eq(false),
                observerCaptor.capture());
        mContentObserver = observerCaptor.getValue();
    }

    private void registerAsyncChannel(Consumer<AsyncChannel> consumer, Messenger messenger) {
        final AsyncChannel channel = new AsyncChannel();
        Handler handler = new Handler(mLooper.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            consumer.accept(channel);
                        } else {
                            Log.d(TAG, "Failed to connect Command channel " + this);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        Log.d(TAG, "Command channel disconnected" + this);
                        break;
                }
            }
        };

        channel.connect(mContext, handler, messenger);
        mLooper.dispatchAll();
    }

    private void initializeCmi() throws Exception {
        mCmi = new ClientModeImpl(mContext, mFrameworkFacade, mLooper.getLooper(),
                mUserManager, mWifiInjector, mBackupManagerProxy, mCountryCode, mWifiNative,
                mWifiScoreCard, mWrongPasswordNotifier, mSarManager);
        mWifiCoreThread = getCmiHandlerThread(mCmi);

        registerAsyncChannel((x) -> {
            mCmiAsyncChannel = x;
        }, mCmi.getMessenger());

        mBinderToken = Binder.clearCallingIdentity();

        /* Send the BOOT_COMPLETED message to setup some CMI state. */
        mCmi.sendMessage(ClientModeImpl.CMD_BOOT_COMPLETED);
        mLooper.dispatchAll();

        verify(mWifiNetworkFactory).register();
        verify(mUntrustedWifiNetworkFactory).register();

        mLooper.startAutoDispatch();
        mCmi.syncInitialize(mCmiAsyncChannel);
        mLooper.stopAutoDispatch();
    }

    @After
    public void cleanUp() throws Exception {
        Binder.restoreCallingIdentity(mBinderToken);

        if (mSyncThread != null) stopLooper(mSyncThread.getLooper());
        if (mWifiCoreThread != null) stopLooper(mWifiCoreThread.getLooper());
        if (mP2pThread != null) stopLooper(mP2pThread.getLooper());

        mWifiCoreThread = null;
        mP2pThread = null;
        mSyncThread = null;
        mCmiAsyncChannel = null;
        mCmi = null;
    }

    @Test
    public void createNew() throws Exception {
        assertEquals("DefaultState", getCurrentState().getName());

        mCmi.sendMessage(ClientModeImpl.CMD_BOOT_COMPLETED);
        mLooper.dispatchAll();
        assertEquals("DefaultState", getCurrentState().getName());
    }

    @Test
    public void loadComponentsInStaMode() throws Exception {
        startSupplicantAndDispatchMessages();
        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    @Test
    public void checkInitialStateStickyWhenDisabledMode() throws Exception {
        mLooper.dispatchAll();
        assertEquals("DefaultState", getCurrentState().getName());
        assertEquals(ClientModeImpl.DISABLED_MODE, mCmi.getOperationalModeForTest());

        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();
        assertEquals(ClientModeImpl.DISABLED_MODE, mCmi.getOperationalModeForTest());
        assertEquals("DefaultState", getCurrentState().getName());
    }

    @Test
    public void shouldStartSupplicantWhenConnectModeRequested() throws Exception {
        // The first time we start out in DefaultState, we sit around here.
        mLooper.dispatchAll();
        assertEquals("DefaultState", getCurrentState().getName());
        assertEquals(ClientModeImpl.DISABLED_MODE, mCmi.getOperationalModeForTest());

        // But if someone tells us to enter connect mode, we start up supplicant
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mLooper.dispatchAll();
        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     *  Test that mode changes accurately reflect the value for isWifiEnabled.
     */
    @Test
    public void checkIsWifiEnabledForModeChanges() throws Exception {
        // Check initial state
        mLooper.dispatchAll();
        assertEquals("DefaultState", getCurrentState().getName());
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());

        // switch to connect mode and verify wifi is reported as enabled
        startSupplicantAndDispatchMessages();

        assertEquals("DisconnectedState", getCurrentState().getName());
        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());

        // reset the expectations on mContext since we did get an expected broadcast, but we should
        // not on the next transition
        reset(mContext);

        // now disable wifi and verify the reported wifi state
        mCmi.setWifiStateForApiCalls(WifiManager.WIFI_STATE_DISABLED);
        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();
        assertEquals(ClientModeImpl.DISABLED_MODE, mCmi.getOperationalModeForTest());
        assertEquals("DefaultState", getCurrentState().getName());
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());
        verify(mContext, never()).sendStickyBroadcastAsUser(
                (Intent) argThat(new WifiEnablingStateIntentMatcher()), any());
    }

    private class WifiEnablingStateIntentMatcher implements ArgumentMatcher<Intent> {
        @Override
        public boolean matches(Intent intent) {
            if (WifiManager.WIFI_STATE_CHANGED_ACTION != intent.getAction()) {
                // not the correct type
                return false;
            }
            return WifiManager.WIFI_STATE_ENABLING
                    == intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                          WifiManager.WIFI_STATE_DISABLED);
        }
    }

    private void canRemoveNetwork() {
        boolean result;
        when(mWifiConfigManager.removeNetwork(eq(0), anyInt())).thenReturn(true);
        mLooper.startAutoDispatch();
        result = mCmi.syncRemoveNetwork(mCmiAsyncChannel, 0);
        mLooper.stopAutoDispatch();

        assertTrue(result);
        verify(mWifiConfigManager).removeNetwork(anyInt(), anyInt());
    }

    /**
     * Verifies that configs can be removed when not in client mode.
     */
    @Test
    public void canRemoveNetworkConfigWhenWifiDisabled() {
        canRemoveNetwork();
    }


    /**
     * Verifies that configs can be removed when in client mode.
     */
    @Test
    public void canRemoveNetworkConfigInClientMode() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        canRemoveNetwork();
    }

    private void canForgetNetwork() {
        when(mWifiConfigManager.removeNetwork(eq(0), anyInt())).thenReturn(true);
        mCmi.sendMessage(WifiManager.FORGET_NETWORK, 0, MANAGED_PROFILE_UID);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).removeNetwork(anyInt(), anyInt());
    }

    /**
     * Verifies that configs can be removed when not in client mode.
     */
    @Test
    public void canForgetNetworkConfigWhenWifiDisabled() throws Exception {
        canForgetNetwork();
    }

    /**
     * Verifies that configs can be forgotten when in client mode.
     */
    @Test
    public void canForgetNetworkConfigInClientMode() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        canForgetNetwork();
    }

    private void canSaveNetworkConfig() {
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();

        int networkId = TEST_NETWORK_ID;
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(networkId));
        when(mWifiConfigManager.enableNetwork(eq(networkId), eq(false), anyInt()))
                .thenReturn(true);

        mLooper.startAutoDispatch();
        Message reply = mCmiAsyncChannel.sendMessageSynchronously(WifiManager.SAVE_NETWORK, config);
        mLooper.stopAutoDispatch();
        assertEquals(WifiManager.SAVE_NETWORK_SUCCEEDED, reply.what);

        verify(mWifiConfigManager).addOrUpdateNetwork(any(WifiConfiguration.class), anyInt());
        verify(mWifiConfigManager).enableNetwork(eq(networkId), eq(false), anyInt());
    }

    /**
     * Verifies that configs can be saved when not in client mode.
     */
    @Test
    public void canSaveNetworkConfigWhenWifiDisabled() throws Exception {
        canSaveNetworkConfig();
    }

    /**
     * Verifies that configs can be saved when in client mode.
     */
    @Test
    public void canSaveNetworkConfigInClientMode() throws Exception {
        loadComponentsInStaMode();
        canSaveNetworkConfig();
    }

    /**
     * Verifies that null configs are rejected in SAVE_NETWORK message.
     */
    @Test
    public void saveNetworkConfigFailsWithNullConfig() throws Exception {
        mLooper.startAutoDispatch();
        Message reply = mCmiAsyncChannel.sendMessageSynchronously(WifiManager.SAVE_NETWORK, null);
        mLooper.stopAutoDispatch();
        assertEquals(WifiManager.SAVE_NETWORK_FAILED, reply.what);

        verify(mWifiConfigManager, never())
                .addOrUpdateNetwork(any(WifiConfiguration.class), anyInt());
        verify(mWifiConfigManager, never())
                .enableNetwork(anyInt(), anyBoolean(), anyInt());
    }

    /**
     * Verifies that configs save fails when the addition of network fails.
     */
    @Test
    public void saveNetworkConfigFailsWithConfigAddFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();

        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID));

        mLooper.startAutoDispatch();
        Message reply = mCmiAsyncChannel.sendMessageSynchronously(WifiManager.SAVE_NETWORK, config);
        mLooper.stopAutoDispatch();
        assertEquals(WifiManager.SAVE_NETWORK_FAILED, reply.what);

        verify(mWifiConfigManager).addOrUpdateNetwork(any(WifiConfiguration.class), anyInt());
        verify(mWifiConfigManager, never())
                .enableNetwork(anyInt(), anyBoolean(), anyInt());
    }

    /**
     * Verifies that configs save fails when the enable of network fails.
     */
    @Test
    public void saveNetworkConfigFailsWithConfigEnableFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();

        int networkId = 5;
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(networkId));
        when(mWifiConfigManager.enableNetwork(eq(networkId), eq(false), anyInt()))
                .thenReturn(false);

        mLooper.startAutoDispatch();
        Message reply = mCmiAsyncChannel.sendMessageSynchronously(WifiManager.SAVE_NETWORK, config);
        mLooper.stopAutoDispatch();
        assertEquals(WifiManager.SAVE_NETWORK_FAILED, reply.what);

        verify(mWifiConfigManager).addOrUpdateNetwork(any(WifiConfiguration.class), anyInt());
        verify(mWifiConfigManager).enableNetwork(eq(networkId), eq(false), anyInt());
    }

    /**
     * Helper method to move through startup states.
     */
    private void startSupplicantAndDispatchMessages() throws Exception {
        mCmi.setWifiStateForApiCalls(WifiManager.WIFI_STATE_ENABLED);
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);

        mLooper.dispatchAll();

        verify(mWifiLastResortWatchdog, atLeastOnce()).clearAllFailureCounts();

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    private void addNetworkAndVerifySuccess(boolean isHidden) throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = FRAMEWORK_NETWORK_ID;
        config.SSID = sSSID;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.hiddenSSID = isHidden;

        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(0));
        when(mWifiConfigManager.getSavedNetworks()).thenReturn(Arrays.asList(config));
        when(mWifiConfigManager.getConfiguredNetwork(0)).thenReturn(config);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);

        mLooper.startAutoDispatch();
        mCmi.syncAddOrUpdateNetwork(mCmiAsyncChannel, config);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).addOrUpdateNetwork(eq(config), anyInt());

        mLooper.startAutoDispatch();
        List<WifiConfiguration> configs = mCmi.syncGetConfiguredNetworks(-1, mCmiAsyncChannel);
        mLooper.stopAutoDispatch();
        assertEquals(1, configs.size());

        WifiConfiguration config2 = configs.get(0);
        assertEquals("\"GoogleGuest\"", config2.SSID);
        assertTrue(config2.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE));
    }

    private void initializeAndAddNetworkAndVerifySuccess() throws Exception {
        initializeAndAddNetworkAndVerifySuccess(false);
    }

    private void initializeAndAddNetworkAndVerifySuccess(boolean isHidden) throws Exception {
        loadComponentsInStaMode();
        addNetworkAndVerifySuccess(isHidden);
    }

    /**
     * Helper method to retrieve WifiConfiguration by SSID.
     *
     * Returns the associated WifiConfiguration if it is found, null otherwise.
     */
    private WifiConfiguration getWifiConfigurationForNetwork(String ssid) {
        mLooper.startAutoDispatch();
        List<WifiConfiguration> configs = mCmi.syncGetConfiguredNetworks(-1, mCmiAsyncChannel);
        mLooper.stopAutoDispatch();

        for (WifiConfiguration checkConfig : configs) {
            if (checkConfig.SSID.equals(ssid)) {
                return checkConfig;
            }
        }
        return null;
    }

    private void setupAndStartConnectSequence(WifiConfiguration config) throws Exception {
        when(mWifiConfigManager.enableNetwork(eq(config.networkId), eq(true), anyInt()))
                .thenReturn(true);
        when(mWifiConfigManager.updateLastConnectUid(eq(config.networkId), anyInt()))
                .thenReturn(true);
        when(mWifiConfigManager.getConfiguredNetwork(eq(config.networkId)))
                .thenReturn(config);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(
                eq(config.networkId))).thenReturn(config);

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        mLooper.startAutoDispatch();
        assertTrue(mCmi.syncEnableNetwork(mCmiAsyncChannel, config.networkId, true));
        mLooper.stopAutoDispatch();
    }

    private void validateSuccessfulConnectSequence(WifiConfiguration config) {
        verify(mWifiConfigManager).enableNetwork(eq(config.networkId), eq(true), anyInt());
        verify(mWifiConnectivityManager).setUserConnectChoice(eq(config.networkId));
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager).getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    private void validateFailureConnectSequence(WifiConfiguration config) {
        verify(mWifiConfigManager).enableNetwork(eq(config.networkId), eq(true), anyInt());
        verify(mWifiConnectivityManager).setUserConnectChoice(eq(config.networkId));
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager, never())
                .getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative, never()).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Tests the network connection initiation sequence with the default network request pending
     * from WifiNetworkFactory.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnect() throws Exception {
        loadComponentsInStaMode();
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID;
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);
    }

    /**
     * Tests the network connection initiation sequence with the default network request pending
     * from WifiNetworkFactory.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectFromNonSettingsApp() throws Exception {
        loadComponentsInStaMode();
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID;
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(Process.myUid()))
                .thenReturn(false);
        setupAndStartConnectSequence(config);
        verify(mWifiConfigManager).enableNetwork(eq(config.networkId), eq(true), anyInt());
        verify(mWifiConnectivityManager, never()).setUserConnectChoice(eq(config.networkId));
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager).getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Tests the network connection initiation sequence with no network request pending from
     * from WifiNetworkFactory.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we don't invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectWithNoNetworkRequest() throws Exception {
        loadComponentsInStaMode();
        // Remove the network requests.
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID;
        setupAndStartConnectSequence(config);
        validateFailureConnectSequence(config);
    }

    /**
     * Tests the entire successful network connection flow.
     */
    @Test
    public void connect() throws Exception {
        triggerConnect();
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);

        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("ObtainingIpState", getCurrentState().getName());

        DhcpResults dhcpResults = new DhcpResults();
        dhcpResults.setGateway("1.2.3.4");
        dhcpResults.setIpAddress("192.168.1.100", 0);
        dhcpResults.addDns("8.8.8.8");
        dhcpResults.setLeaseDuration(3600);

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mCmi.getWifiInfo();
        assertNotNull(wifiInfo);
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertEquals(sFreq, wifiInfo.getFrequency());
        assertTrue(sWifiSsid.equals(wifiInfo.getWifiSsid()));
        // Ensure the connection stats for the network is updated.
        verify(mWifiConfigManager).updateNetworkAfterConnect(FRAMEWORK_NETWORK_ID);

        verify(mWifiStateTracker).updateState(eq(WifiStateTracker.CONNECTED));
        assertEquals("ConnectedState", getCurrentState().getName());
    }

    /**
     * Verify that WifiStateTracker is called if wifi is disabled while connected.
     */
    @Test
    public void verifyWifiStateTrackerUpdatedWhenDisabled() throws Exception {
        connect();

        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();
        verify(mWifiStateTracker).updateState(eq(WifiStateTracker.DISCONNECTED));
    }

    /**
     * Tests the network connection initiation sequence with no network request pending from
     * from WifiNetworkFactory when we're already connected to a different network.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectWithNoNetworkRequestAndAlreadyConnected() throws Exception {
        // Simulate the first connection.
        connect();

        // Remove the network requests.
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID + 1;
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);
        verify(mWifiPermissionsUtil, atLeastOnce()).checkNetworkSettingsPermission(anyInt());
    }

    /**
     * Tests the network connection initiation sequence from a non-privileged app with no network
     * request pending from from WifiNetworkFactory when we're already connected to a different
     * network.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we don't invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectWithNoNetworkRequestAndAlreadyConnectedButNonPrivilegedApp()
            throws Exception {
        // Simulate the first connection.
        connect();

        // Remove the network requests.
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);

        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID + 1;
        setupAndStartConnectSequence(config);
        verify(mWifiConfigManager).enableNetwork(eq(config.networkId), eq(true), anyInt());
        verify(mWifiConnectivityManager, never()).setUserConnectChoice(eq(config.networkId));
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager, never())
                .getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative, never()).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
        verify(mWifiPermissionsUtil, times(4)).checkNetworkSettingsPermission(anyInt());
    }

    @Test
    public void enableWithInvalidNetworkId() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        when(mWifiConfigManager.getConfiguredNetwork(eq(0))).thenReturn(null);

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        mLooper.startAutoDispatch();
        assertFalse(mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true));
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager, never()).enableNetwork(eq(0), eq(true), anyInt());
        verify(mWifiConfigManager, never()).updateLastConnectUid(eq(0), anyInt());
    }

    /**
     * If caller tries to connect to a network that is already connected, the connection request
     * should succeed.
     *
     * Test: Create and connect to a network, then try to reconnect to the same network. Verify
     * that connection request returns with CONNECT_NETWORK_SUCCEEDED.
     */
    @Test
    public void reconnectToConnectedNetwork() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("ObtainingIpState", getCurrentState().getName());

        // try to reconnect
        mLooper.startAutoDispatch();
        Message reply = mCmiAsyncChannel.sendMessageSynchronously(WifiManager.CONNECT_NETWORK, 0);
        mLooper.stopAutoDispatch();

        assertEquals(WifiManager.CONNECT_NETWORK_SUCCEEDED, reply.what);
    }

    @Test
    public void testDhcpFailure() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("ObtainingIpState", getCurrentState().getName());

        injectDhcpFailure();
        mLooper.dispatchAll();

        assertEquals("DisconnectingState", getCurrentState().getName());
    }

    /**
     * Verify that the network selection status will be updated with DISABLED_AUTHENTICATION_FAILURE
     * when wrong password authentication failure is detected and the network had been
     * connected previously.
     */
    @Test
    public void testWrongPasswordWithPreviouslyConnected() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        WifiConfiguration config = new WifiConfiguration();
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        mLooper.dispatchAll();

        verify(mWrongPasswordNotifier, never()).onWrongPasswordError(anyString());
        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE));

        assertEquals("DisconnectedState", getCurrentState().getName());

    }

    /**
     * Verify that the network selection status will be updated with DISABLED_BY_WRONG_PASSWORD
     * when wrong password authentication failure is detected and the network has never been
     * connected.
     */
    @Test
    public void testWrongPasswordWithNeverConnected() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.getNetworkSelectionStatus().setHasEverConnected(false);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        mLooper.dispatchAll();

        verify(mWrongPasswordNotifier).onWrongPasswordError(eq(sSSID));
        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD));

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * Verify that the network selection status will be updated with DISABLED_BY_WRONG_PASSWORD
     * when wrong password authentication failure is detected and the network is unknown.
     */
    @Test
    public void testWrongPasswordWithNullNetwork() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(null);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD));

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * Verify that the function resetCarrierKeysForImsiEncryption() in TelephonyManager
     * is called when a Authentication failure is detected with a vendor specific EAP Error
     * of certification expired while using EAP-SIM
     * In this test case, it is assumed that the network had been connected previously.
     */
    @Test
    public void testEapSimErrorVendorSpecific() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        WifiConfiguration config = new WifiConfiguration();
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE,
                WifiNative.EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED);
        mLooper.dispatchAll();

        verify(mTelephonyManager).resetCarrierKeysForImsiEncryption();
    }

    /**
     * Verify that the function resetCarrierKeysForImsiEncryption() in TelephonyManager
     * is not called when a Authentication failure is detected with a vendor specific EAP Error
     * of certification expired while using other methods than EAP-SIM, EAP-AKA, or EAP-AKA'.
     */
    @Test
    public void testEapTlsErrorVendorSpecific() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        WifiConfiguration config = new WifiConfiguration();
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE,
                WifiNative.EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED);
        mLooper.dispatchAll();

        verify(mTelephonyManager, never()).resetCarrierKeysForImsiEncryption();
    }

    @Test
    public void testBadNetworkEvent() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        mLooper.startAutoDispatch();
        mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
    }


    @Test
    public void smToString() throws Exception {
        assertEquals("CMD_CHANNEL_HALF_CONNECTED", mCmi.smToString(
                AsyncChannel.CMD_CHANNEL_HALF_CONNECTED));
        assertEquals("CMD_PRE_DHCP_ACTION", mCmi.smToString(
                DhcpClient.CMD_PRE_DHCP_ACTION));
        assertEquals("CMD_IP_REACHABILITY_LOST", mCmi.smToString(
                ClientModeImpl.CMD_IP_REACHABILITY_LOST));
    }

    @Test
    public void disconnect() throws Exception {
        connect();

        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, -1, 3, sBSSID);
        mLooper.dispatchAll();
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        verify(mWifiStateTracker).updateState(eq(WifiStateTracker.DISCONNECTED));
        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * Successfully connecting to a network will set WifiConfiguration's value of HasEverConnected
     * to true.
     *
     * Test: Successfully create and connect to a network. Check the config and verify
     * WifiConfiguration.getHasEverConnected() is true.
     */
    @Test
    public void setHasEverConnectedTrueOnConnect() throws Exception {
        connect();
        verify(mWifiConfigManager, atLeastOnce()).updateNetworkAfterConnect(0);
    }

    /**
     * Fail network connection attempt and verify HasEverConnected remains false.
     *
     * Test: Successfully create a network but fail when connecting. Check the config and verify
     * WifiConfiguration.getHasEverConnected() is false.
     */
    @Test
    public void connectionFailureDoesNotSetHasEverConnectedTrue() throws Exception {
        testDhcpFailure();
        verify(mWifiConfigManager, never()).updateNetworkAfterConnect(0);
    }

    @Test
    public void iconQueryTest() throws Exception {
        // TODO(b/31065385): Passpoint config management.
    }

    @Test
    public void verboseLogRecSizeIsGreaterThanNormalSize() {
        assertTrue(LOG_REC_LIMIT_IN_VERBOSE_MODE > ClientModeImpl.NUM_LOG_RECS_NORMAL);
    }

    /**
     * Verifies that, by default, we allow only the "normal" number of log records.
     */
    @Test
    public void normalLogRecSizeIsUsedByDefault() {
        assertEquals(ClientModeImpl.NUM_LOG_RECS_NORMAL, mCmi.getLogRecMaxSize());
    }

    /**
     * Verifies that, in verbose mode, we allow a larger number of log records.
     */
    @Test
    public void enablingVerboseLoggingUpdatesLogRecSize() {
        mCmi.enableVerboseLogging(1);
        assertEquals(LOG_REC_LIMIT_IN_VERBOSE_MODE, mCmi.getLogRecMaxSize());
    }

    @Test
    public void disablingVerboseLoggingClearsRecords() {
        mCmi.sendMessage(ClientModeImpl.CMD_DISCONNECT);
        mLooper.dispatchAll();
        assertTrue(mCmi.getLogRecSize() >= 1);

        mCmi.enableVerboseLogging(0);
        assertEquals(0, mCmi.getLogRecSize());
    }

    @Test
    public void disablingVerboseLoggingUpdatesLogRecSize() {
        mCmi.enableVerboseLogging(1);
        mCmi.enableVerboseLogging(0);
        assertEquals(ClientModeImpl.NUM_LOG_RECS_NORMAL, mCmi.getLogRecMaxSize());
    }

    @Test
    public void logRecsIncludeDisconnectCommand() {
        // There's nothing special about the DISCONNECT command. It's just representative of
        // "normal" commands.
        mCmi.sendMessage(ClientModeImpl.CMD_DISCONNECT);
        mLooper.dispatchAll();
        assertEquals(1, mCmi.copyLogRecs()
                .stream()
                .filter(logRec -> logRec.getWhat() == ClientModeImpl.CMD_DISCONNECT)
                .count());
    }

    @Test
    public void logRecsExcludeRssiPollCommandByDefault() {
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL);
        mLooper.dispatchAll();
        assertEquals(0, mCmi.copyLogRecs()
                .stream()
                .filter(logRec -> logRec.getWhat() == ClientModeImpl.CMD_RSSI_POLL)
                .count());
    }

    @Test
    public void logRecsIncludeRssiPollCommandWhenVerboseLoggingIsEnabled() {
        mCmi.enableVerboseLogging(1);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL);
        mLooper.dispatchAll();
        assertEquals(1, mCmi.copyLogRecs()
                .stream()
                .filter(logRec -> logRec.getWhat() == ClientModeImpl.CMD_RSSI_POLL)
                .count());
    }

    /** Verifies that enabling verbose logging sets the hal log property in eng builds. */
    @Test
    public void enablingVerboseLoggingSetsHalLogPropertyInEngBuilds() {
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(mBuildProperties.isEngBuild()).thenReturn(true);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mCmi.enableVerboseLogging(1);
        verify(mPropertyService).set("log.tag.WifiHAL", "V");
    }

    /** Verifies that enabling verbose logging sets the hal log property in userdebug builds. */
    @Test
    public void enablingVerboseLoggingSetsHalLogPropertyInUserdebugBuilds() {
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(mBuildProperties.isUserdebugBuild()).thenReturn(true);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mCmi.enableVerboseLogging(1);
        verify(mPropertyService).set("log.tag.WifiHAL", "V");
    }

    /** Verifies that enabling verbose logging does NOT set the hal log property in user builds. */
    @Test
    public void enablingVerboseLoggingDoeNotSetHalLogPropertyInUserBuilds() {
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(mBuildProperties.isUserBuild()).thenReturn(true);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        mCmi.enableVerboseLogging(1);
        verify(mPropertyService, never()).set(anyString(), anyString());
    }

    private int testGetSupportedFeaturesCase(int supportedFeatures, boolean rttConfigured) {
        AsyncChannel channel = mock(AsyncChannel.class);
        Message reply = Message.obtain();
        reply.arg1 = supportedFeatures;
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(channel.sendMessageSynchronously(ClientModeImpl.CMD_GET_SUPPORTED_FEATURES))
                .thenReturn(reply);
        when(mPropertyService.getBoolean("config.disable_rtt", false))
                .thenReturn(rttConfigured);
        return mCmi.syncGetSupportedFeatures(channel);
    }

    /** Verifies that syncGetSupportedFeatures() masks out capabilities based on system flags. */
    @Test
    public void syncGetSupportedFeatures() {
        final int featureAware = WifiManager.WIFI_FEATURE_AWARE;
        final int featureInfra = WifiManager.WIFI_FEATURE_INFRA;
        final int featureD2dRtt = WifiManager.WIFI_FEATURE_D2D_RTT;
        final int featureD2apRtt = WifiManager.WIFI_FEATURE_D2AP_RTT;

        assertEquals(0, testGetSupportedFeaturesCase(0, false));
        assertEquals(0, testGetSupportedFeaturesCase(0, true));
        assertEquals(featureAware | featureInfra,
                testGetSupportedFeaturesCase(featureAware | featureInfra, false));
        assertEquals(featureAware | featureInfra,
                testGetSupportedFeaturesCase(featureAware | featureInfra, true));
        assertEquals(featureInfra | featureD2dRtt,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt, true));
        assertEquals(featureInfra | featureD2apRtt,
                testGetSupportedFeaturesCase(featureInfra | featureD2apRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCase(featureInfra | featureD2apRtt, true));
        assertEquals(featureInfra | featureD2dRtt | featureD2apRtt,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt | featureD2apRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt | featureD2apRtt, true));
    }

    /**
     * Verify that syncAddOrUpdatePasspointConfig will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager}.
     */
    @Test
    public void syncAddOrUpdatePasspointConfig() throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(config, MANAGED_PROFILE_UID)).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mCmi.syncAddOrUpdatePasspointConfig(
                mCmiAsyncChannel, config, MANAGED_PROFILE_UID));
        mLooper.stopAutoDispatch();
        reset(mPasspointManager);

        when(mPasspointManager.addOrUpdateProvider(config, MANAGED_PROFILE_UID)).thenReturn(false);
        mLooper.startAutoDispatch();
        assertFalse(mCmi.syncAddOrUpdatePasspointConfig(
                mCmiAsyncChannel, config, MANAGED_PROFILE_UID));
        mLooper.stopAutoDispatch();
    }

    /**
     * Verify that syncAddOrUpdatePasspointConfig will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager} when in client mode.
     */
    @Test
    public void syncAddOrUpdatePasspointConfigInClientMode() throws Exception {
        loadComponentsInStaMode();
        syncAddOrUpdatePasspointConfig();
    }

    /**
     * Verify that syncRemovePasspointConfig will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager}.
     */
    @Test
    public void syncRemovePasspointConfig() throws Exception {
        String fqdn = "test.com";
        when(mPasspointManager.removeProvider(fqdn)).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mCmi.syncRemovePasspointConfig(mCmiAsyncChannel, fqdn));
        mLooper.stopAutoDispatch();
        reset(mPasspointManager);

        when(mPasspointManager.removeProvider(fqdn)).thenReturn(false);
        mLooper.startAutoDispatch();
        assertFalse(mCmi.syncRemovePasspointConfig(mCmiAsyncChannel, fqdn));
        mLooper.stopAutoDispatch();
    }

    /**
     * Verify that syncRemovePasspointConfig will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager} when in client mode.
     */
    @Test
    public void syncRemovePasspointConfigInClientMode() throws Exception {
        loadComponentsInStaMode();
        syncRemovePasspointConfig();
    }

    /**
     * Verify that syncGetPasspointConfigs will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager}.
     */
    @Test
    public void syncGetPasspointConfigs() throws Exception {
        // Setup expected configs.
        List<PasspointConfiguration> expectedConfigs = new ArrayList<>();
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);
        expectedConfigs.add(config);

        when(mPasspointManager.getProviderConfigs()).thenReturn(expectedConfigs);
        mLooper.startAutoDispatch();
        assertEquals(expectedConfigs, mCmi.syncGetPasspointConfigs(mCmiAsyncChannel));
        mLooper.stopAutoDispatch();
        reset(mPasspointManager);

        when(mPasspointManager.getProviderConfigs())
                .thenReturn(new ArrayList<PasspointConfiguration>());
        mLooper.startAutoDispatch();
        assertTrue(mCmi.syncGetPasspointConfigs(mCmiAsyncChannel).isEmpty());
        mLooper.stopAutoDispatch();
    }

    /**
     * Verify that syncStartSubscriptionProvisioning will redirect calls with right parameters
     * to {@link PasspointManager} with expected true being returned when in client mode.
     */
    @Test
    public void syncStartSubscriptionProvisioningInClientMode() throws Exception {
        // syncInitialize is invoke in Setup.
        verify(mPasspointManager).initializeProvisioner(any(Looper.class));

        loadComponentsInStaMode();
        when(mPasspointManager.startSubscriptionProvisioning(anyInt(),
                any(OsuProvider.class), any(IProvisioningCallback.class))).thenReturn(true);
        mLooper.startAutoDispatch();
        assertEquals(true, mCmi.syncStartSubscriptionProvisioning(
                OTHER_USER_UID, mOsuProvider, mProvisioningCallback, mCmiAsyncChannel));
        verify(mPasspointManager).startSubscriptionProvisioning(OTHER_USER_UID, mOsuProvider,
                mProvisioningCallback);
        mLooper.stopAutoDispatch();
    }

    /**
     * Verify that syncStartSubscriptionProvisioning will be a no-op and return false before
     * SUPPLICANT_START command is received by the CMI.
     */
    @Test
    public void syncStartSubscriptionProvisioningBeforeSupplicantOrAPStart() throws Exception {
        mLooper.startAutoDispatch();
        assertEquals(false, mCmi.syncStartSubscriptionProvisioning(
                OTHER_USER_UID, mOsuProvider, mProvisioningCallback, mCmiAsyncChannel));
        mLooper.stopAutoDispatch();
        verify(mPasspointManager, never()).startSubscriptionProvisioning(
                anyInt(), any(OsuProvider.class), any(IProvisioningCallback.class));
    }

    /**
     * Verify that syncStartSubscriptionProvisioning will be a no-op and return false when not in
     * client mode.
     */
    @Test
    public void syncStartSubscriptionProvisioningNoOpWifiDisabled() throws Exception {
        mLooper.startAutoDispatch();
        assertEquals(false, mCmi.syncStartSubscriptionProvisioning(
                OTHER_USER_UID, mOsuProvider, mProvisioningCallback, mCmiAsyncChannel));
        mLooper.stopAutoDispatch();
        verify(mPasspointManager, never()).startSubscriptionProvisioning(
                anyInt(), any(OsuProvider.class), any(IProvisioningCallback.class));
    }

    /**
     *  Test that we disconnect from a network if it was removed while we are in the
     *  ObtainingIpState.
     */
    @Test
    public void disconnectFromNetworkWhenRemovedWhileObtainingIpAddr() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        when(mWifiConfigManager.enableNetwork(eq(0), eq(true), anyInt())).thenReturn(true);
        when(mWifiConfigManager.updateLastConnectUid(eq(0), anyInt())).thenReturn(true);

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        mLooper.startAutoDispatch();
        assertTrue(mCmi.syncEnableNetwork(mCmiAsyncChannel, 0, true));
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());
        verify(mWifiConnectivityManager).setUserConnectChoice(eq(0));
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);

        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("ObtainingIpState", getCurrentState().getName());

        // now remove the config
        when(mWifiConfigManager.removeNetwork(eq(FRAMEWORK_NETWORK_ID), anyInt()))
                .thenReturn(true);
        mCmi.sendMessage(WifiManager.FORGET_NETWORK, FRAMEWORK_NETWORK_ID, MANAGED_PROFILE_UID);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).removeNetwork(eq(FRAMEWORK_NETWORK_ID), anyInt());

        reset(mWifiConfigManager);

        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID)).thenReturn(null);

        DhcpResults dhcpResults = new DhcpResults();
        dhcpResults.setGateway("1.2.3.4");
        dhcpResults.setIpAddress("192.168.1.100", 0);
        dhcpResults.addDns("8.8.8.8");
        dhcpResults.setLeaseDuration(3600);

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        assertEquals("DisconnectingState", getCurrentState().getName());
    }

    /**
     * Test verifying that interface Supplicant update for inactive driver does not trigger
     * SelfRecovery when WifiNative reports the interface is up.
     */
    @Test
    public void testSupplicantUpdateDriverInactiveIfaceUpClientModeDoesNotTriggerSelfRecovery()
            throws Exception {
        // Trigger initialize to capture the death handler registration.
        loadComponentsInStaMode();

        when(mWifiNative.isInterfaceUp(eq(WIFI_IFACE_NAME))).thenReturn(true);

        // make sure supplicant has been reported as inactive
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, WifiSsid.createFromAsciiEncoded(""), null,
                        SupplicantState.INTERFACE_DISABLED));
        mLooper.dispatchAll();

        // CMI should trigger self recovery, but not disconnect until externally triggered
        verify(mSelfRecovery, never()).trigger(eq(SelfRecovery.REASON_STA_IFACE_DOWN));
    }

    /**
     * Verifies that WifiInfo is updated upon SUPPLICANT_STATE_CHANGE_EVENT.
     */
    @Test
    public void testWifiInfoUpdatedUponSupplicantStateChangedEvent() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        // Set the scan detail cache for roaming target.
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1));
        when(mScanDetailCache.getScanResult(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1).getScanResult());

        // This simulates the behavior of roaming to network with |sBSSID1|, |sFreq1|.
        // Send a SUPPLICANT_STATE_CHANGE_EVENT, verify WifiInfo is updated.
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID1, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mCmi.getWifiInfo();
        assertEquals(sBSSID1, wifiInfo.getBSSID());
        assertEquals(sFreq1, wifiInfo.getFrequency());
        assertEquals(SupplicantState.COMPLETED, wifiInfo.getSupplicantState());

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID1, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        wifiInfo = mCmi.getWifiInfo();
        assertEquals(null, wifiInfo.getBSSID());
        assertEquals(WifiSsid.NONE, wifiInfo.getSSID());
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID, wifiInfo.getNetworkId());
        assertEquals(SupplicantState.DISCONNECTED, wifiInfo.getSupplicantState());
    }

    /**
     * Verifies that WifiInfo is updated upon CMD_ASSOCIATED_BSSID event.
     */
    @Test
    public void testWifiInfoUpdatedUponAssociatedBSSIDEvent() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        // Set the scan detail cache for roaming target.
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1));
        when(mScanDetailCache.getScanResult(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1).getScanResult());

        // This simulates the behavior of roaming to network with |sBSSID1|, |sFreq1|.
        // Send a CMD_ASSOCIATED_BSSID, verify WifiInfo is updated.
        mCmi.sendMessage(ClientModeImpl.CMD_ASSOCIATED_BSSID, 0, 0, sBSSID1);
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mCmi.getWifiInfo();
        assertEquals(sBSSID1, wifiInfo.getBSSID());
        assertEquals(sFreq1, wifiInfo.getFrequency());
        assertEquals(SupplicantState.COMPLETED, wifiInfo.getSupplicantState());
    }

    /**
     * Verifies that WifiInfo is cleared upon exiting and entering WifiInfo, and that it is not
     * updated by SUPPLICAN_STATE_CHANGE_EVENTs in ScanModeState.
     * This protects ClientModeImpl from  getting into a bad state where WifiInfo says wifi is
     * already Connected or Connecting, (when it is in-fact Disconnected), so
     * WifiConnectivityManager does not attempt any new Connections, freezing wifi.
     */
    @Test
    public void testWifiInfoCleanedUpEnteringExitingConnectModeState() throws Exception {
        InOrder inOrder = inOrder(mWifiConnectivityManager);
        InOrder inOrderSarMgr = inOrder(mSarManager);
        InOrder inOrderMetrics = inOrder(mWifiMetrics);
        Log.i(TAG, mCmi.getCurrentState().getName());
        String initialBSSID = "aa:bb:cc:dd:ee:ff";
        WifiInfo wifiInfo = mCmi.getWifiInfo();
        wifiInfo.setBSSID(initialBSSID);

        // Set CMI to CONNECT_MODE and verify state, and wifi enabled in ConnectivityManager
        startSupplicantAndDispatchMessages();
        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());
        inOrder.verify(mWifiConnectivityManager).setWifiEnabled(eq(true));
        inOrderSarMgr.verify(mSarManager).setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        inOrderMetrics.verify(mWifiMetrics)
                .setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
        inOrderMetrics.verify(mWifiMetrics).logStaEvent(StaEvent.TYPE_WIFI_ENABLED);
        assertNull(wifiInfo.getBSSID());

        // Send a SUPPLICANT_STATE_CHANGE_EVENT, verify WifiInfo is updated
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertEquals(SupplicantState.COMPLETED, wifiInfo.getSupplicantState());

        // Set CMI to DISABLED_MODE, verify state and wifi disabled in ConnectivityManager, and
        // WifiInfo is reset() and state set to DISCONNECTED
        mCmi.setWifiStateForApiCalls(WifiManager.WIFI_STATE_DISABLED);
        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();

        assertEquals(ClientModeImpl.DISABLED_MODE, mCmi.getOperationalModeForTest());
        assertEquals("DefaultState", getCurrentState().getName());
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());
        inOrder.verify(mWifiConnectivityManager).setWifiEnabled(eq(false));
        inOrderSarMgr.verify(mSarManager).setClientWifiState(WifiManager.WIFI_STATE_DISABLED);
        inOrderMetrics.verify(mWifiMetrics).setWifiState(WifiMetricsProto.WifiLog.WIFI_DISABLED);
        inOrderMetrics.verify(mWifiMetrics).logStaEvent(StaEvent.TYPE_WIFI_DISABLED);
        assertNull(wifiInfo.getBSSID());
        assertEquals(SupplicantState.DISCONNECTED, wifiInfo.getSupplicantState());

        // Send a SUPPLICANT_STATE_CHANGE_EVENT, verify WifiInfo is not updated
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();
        assertNull(wifiInfo.getBSSID());
        assertEquals(SupplicantState.DISCONNECTED, wifiInfo.getSupplicantState());

        // Set the bssid to something, so we can verify it is cleared (just in case)
        wifiInfo.setBSSID(initialBSSID);

        // Set CMI to CONNECT_MODE and verify state, and wifi enabled in ConnectivityManager,
        // and WifiInfo has been reset
        startSupplicantAndDispatchMessages();

        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());
        inOrder.verify(mWifiConnectivityManager).setWifiEnabled(eq(true));
        inOrderSarMgr.verify(mSarManager).setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        inOrderMetrics.verify(mWifiMetrics)
                .setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
        inOrderMetrics.verify(mWifiMetrics).logStaEvent(StaEvent.TYPE_WIFI_ENABLED);
        assertEquals("DisconnectedState", getCurrentState().getName());
        assertEquals(SupplicantState.DISCONNECTED, wifiInfo.getSupplicantState());
        assertNull(wifiInfo.getBSSID());
    }

    /**
     * Test that connected SSID and BSSID are exposed to system server.
     * Also tests that {@link ClientModeImpl#syncRequestConnectionInfo(String)} always
     * returns a copy of WifiInfo.
     */
    @Test
    public void testConnectedIdsAreVisibleFromSystemServer() throws Exception {
        WifiInfo wifiInfo = mCmi.getWifiInfo();
        // Get into a connected state, with known BSSID and SSID
        connect();
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertEquals(sWifiSsid, wifiInfo.getWifiSsid());

        WifiInfo connectionInfo = mCmi.syncRequestConnectionInfo();

        assertNotEquals(wifiInfo, connectionInfo);
        assertEquals(wifiInfo.getSSID(), connectionInfo.getSSID());
        assertEquals(wifiInfo.getBSSID(), connectionInfo.getBSSID());
        assertEquals(wifiInfo.getMacAddress(), connectionInfo.getMacAddress());
    }

    /**
     * Test that reconnectCommand() triggers connectivity scan when ClientModeImpl
     * is in DisconnectedMode.
     */
    @Test
    public void testReconnectCommandWhenDisconnected() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|, and then disconnect.
        disconnect();

        mCmi.reconnectCommand(ClientModeImpl.WIFI_WORK_SOURCE);
        mLooper.dispatchAll();
        verify(mWifiConnectivityManager).forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
    }

    /**
     * Test that reconnectCommand() doesn't trigger connectivity scan when ClientModeImpl
     * is in ConnectedMode.
     */
    @Test
    public void testReconnectCommandWhenConnected() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        mCmi.reconnectCommand(ClientModeImpl.WIFI_WORK_SOURCE);
        mLooper.dispatchAll();
        verify(mWifiConnectivityManager, never())
                .forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
    }

    /**
     * Adds the network without putting ClientModeImpl into ConnectMode.
     */
    @Test
    public void addNetworkInDefaultState() throws Exception {
        // We should not be in initial state now.
        assertTrue("DefaultState".equals(getCurrentState().getName()));
        addNetworkAndVerifySuccess(false);
        verify(mWifiConnectivityManager, never()).setUserConnectChoice(eq(0));
    }

    /**
     * Test that DISABLE_NETWORK returns failure to public API when WifiConfigManager returns
     * failure.
     */
    @Test
    public void testSyncDisableNetwork_failure() throws Exception {
        loadComponentsInStaMode();
        when(mWifiConfigManager.disableNetwork(anyInt(), anyInt())).thenReturn(false);

        mLooper.startAutoDispatch();
        boolean succeeded = mCmi.syncDisableNetwork(mCmiAsyncChannel, 0);
        mLooper.stopAutoDispatch();
        assertFalse(succeeded);
    }

    /**
     * Test that we don't register the telephony call state listener on devices which do not support
     * setting/resetting Tx power limit.
     */
    @Test
    public void testVoiceCallSar_disabledTxPowerScenario_WifiOn() throws Exception {
        loadComponentsInStaMode();
        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals("DisconnectedState", getCurrentState().getName());
        assertNull(mPhoneStateListener);
    }

    /**
     * Verifies that a network disconnection event will result in ClientModeImpl invoking
     * {@link WifiConfigManager#removeAllEphemeralOrPasspointConfiguredNetworks()} to remove
     * any ephemeral or passpoint networks from it's internal database.
     */
    @Test
    public void testDisconnectionRemovesEphemeralAndPasspointNetworks() throws Exception {
        disconnect();
        verify(mWifiConfigManager).removeAllEphemeralOrPasspointConfiguredNetworks();
    }

    /**
     * Verifies that ClientModeImpl sets and unsets appropriate 'RecentFailureReason' values
     * on a WifiConfiguration when it fails association, authentication, or successfully connects
     */
    @Test
    public void testExtraFailureReason_ApIsBusy() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        // Trigger a connection to this (CMD_START_CONNECT will actually fail, but it sets up
        // targetNetworkId state)
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        // Simulate an ASSOCIATION_REJECTION_EVENT, due to the AP being busy
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT, 0,
                ISupplicantStaIfaceCallback.StatusCode.AP_UNABLE_TO_HANDLE_NEW_STA, sBSSID);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(eq(0),
                eq(WifiConfiguration.RecentFailure.STATUS_AP_UNABLE_TO_HANDLE_NEW_STA));
        assertEquals("DisconnectedState", getCurrentState().getName());

        // Simulate an AUTHENTICATION_FAILURE_EVENT, which should clear the ExtraFailureReason
        reset(mWifiConfigManager);
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT, 0, 0, null);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).clearRecentFailureReason(eq(0));
        verify(mWifiConfigManager, never()).setRecentFailureAssociationStatus(anyInt(), anyInt());

        // Simulate a NETWORK_CONNECTION_EVENT which should clear the ExtraFailureReason
        reset(mWifiConfigManager);
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, null);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).clearRecentFailureReason(eq(0));
        verify(mWifiConfigManager, never()).setRecentFailureAssociationStatus(anyInt(), anyInt());
    }

    /**
     * Test that the helper method
     * {@link ClientModeImpl#shouldEvaluateWhetherToSendExplicitlySelected(WifiConfiguration)}
     * returns true when we connect to the last selected network before expiration of
     * {@link ClientModeImpl#LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS}.
     */
    @Test
    public void testShouldEvaluateWhetherToSendExplicitlySelected_SameNetworkNotExpired() {
        long lastSelectedTimestamp = 45666743454L;
        int lastSelectedNetworkId = 5;

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                lastSelectedTimestamp
                        + ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS - 1);
        when(mWifiConfigManager.getLastSelectedTimeStamp()).thenReturn(lastSelectedTimestamp);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(lastSelectedNetworkId);

        WifiConfiguration currentConfig = new WifiConfiguration();
        currentConfig.networkId = lastSelectedNetworkId;
        assertTrue(mCmi.shouldEvaluateWhetherToSendExplicitlySelected(currentConfig));
    }

    /**
     * Test that the helper method
     * {@link ClientModeImpl#shouldEvaluateWhetherToSendExplicitlySelected(WifiConfiguration)}
     * returns false when we connect to the last selected network after expiration of
     * {@link ClientModeImpl#LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS}.
     */
    @Test
    public void testShouldEvaluateWhetherToSendExplicitlySelected_SameNetworkExpired() {
        long lastSelectedTimestamp = 45666743454L;
        int lastSelectedNetworkId = 5;

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                lastSelectedTimestamp
                        + ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS + 1);
        when(mWifiConfigManager.getLastSelectedTimeStamp()).thenReturn(lastSelectedTimestamp);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(lastSelectedNetworkId);

        WifiConfiguration currentConfig = new WifiConfiguration();
        currentConfig.networkId = lastSelectedNetworkId;
        assertFalse(mCmi.shouldEvaluateWhetherToSendExplicitlySelected(currentConfig));
    }

    /**
     * Test that the helper method
     * {@link ClientModeImpl#shouldEvaluateWhetherToSendExplicitlySelected(WifiConfiguration)}
     * returns false when we connect to a different network to the last selected network.
     */
    @Test
    public void testShouldEvaluateWhetherToSendExplicitlySelected_DifferentNetwork() {
        long lastSelectedTimestamp = 45666743454L;
        int lastSelectedNetworkId = 5;

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                lastSelectedTimestamp
                        + ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS - 1);
        when(mWifiConfigManager.getLastSelectedTimeStamp()).thenReturn(lastSelectedTimestamp);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(lastSelectedNetworkId);

        WifiConfiguration currentConfig = new WifiConfiguration();
        currentConfig.networkId = lastSelectedNetworkId - 1;
        assertFalse(mCmi.shouldEvaluateWhetherToSendExplicitlySelected(currentConfig));
    }

    /**
     * Verify that CMI dump includes WakeupController.
     */
    @Test
    public void testDumpShouldDumpWakeupController() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mCmi.dump(null, writer, null);
        verify(mWakeupController).dump(null, writer, null);
    }

    @Test
    public void takeBugReportCallsWifiDiagnostics() {
        mCmi.takeBugReport(anyString(), anyString());
        verify(mWifiDiagnostics).takeBugReport(anyString(), anyString());
    }

    /**
     * Verify that Rssi Monitoring is started and the callback registered after connecting.
     */
    @Test
    public void verifyRssiMonitoringCallbackIsRegistered() throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        verify(mConnectivityManager).registerNetworkAgent(messengerCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkMisc.class));

        ArrayList<Integer> thresholdsArray = new ArrayList();
        thresholdsArray.add(RSSI_THRESHOLD_MAX);
        thresholdsArray.add(RSSI_THRESHOLD_MIN);
        Bundle thresholds = new Bundle();
        thresholds.putIntegerArrayList("thresholds", thresholdsArray);
        Message message = new Message();
        message.what = NetworkAgent.CMD_SET_SIGNAL_STRENGTH_THRESHOLDS;
        message.obj  = thresholds;
        messengerCaptor.getValue().send(message);
        mLooper.dispatchAll();

        ArgumentCaptor<WifiNative.WifiRssiEventHandler> rssiEventHandlerCaptor =
                ArgumentCaptor.forClass(WifiNative.WifiRssiEventHandler.class);
        verify(mWifiNative).startRssiMonitoring(anyString(), anyByte(), anyByte(),
                rssiEventHandlerCaptor.capture());

        // breach below min
        rssiEventHandlerCaptor.getValue().onRssiThresholdBreached(RSSI_THRESHOLD_BREACH_MIN);
        mLooper.dispatchAll();
        WifiInfo wifiInfo = mCmi.getWifiInfo();
        assertEquals(RSSI_THRESHOLD_BREACH_MIN, wifiInfo.getRssi());

        // breach above max
        rssiEventHandlerCaptor.getValue().onRssiThresholdBreached(RSSI_THRESHOLD_BREACH_MAX);
        mLooper.dispatchAll();
        assertEquals(RSSI_THRESHOLD_BREACH_MAX, wifiInfo.getRssi());
    }

    /**
     * Verify that RSSI and link layer stats polling works in connected mode
     */
    @Test
    public void verifyConnectedModeRssiPolling() throws Exception {
        final long startMillis = 1_500_000_000_100L;
        WifiLinkLayerStats llStats = new WifiLinkLayerStats();
        llStats.txmpdu_be = 1000;
        llStats.rxmpdu_bk = 2000;
        WifiNative.SignalPollResult signalPollResult = new WifiNative.SignalPollResult();
        signalPollResult.currentRssi = -42;
        signalPollResult.txBitrate = 65;
        signalPollResult.associationFrequency = sFreq;
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(llStats);
        when(mWifiNative.signalPoll(any())).thenReturn(signalPollResult);
        when(mClock.getWallClockMillis()).thenReturn(startMillis + 0);
        mCmi.enableRssiPolling(true);
        connect();
        mLooper.dispatchAll();
        when(mClock.getWallClockMillis()).thenReturn(startMillis + 3333);
        mLooper.dispatchAll();
        WifiInfo wifiInfo = mCmi.getWifiInfo();
        assertEquals(llStats.txmpdu_be, wifiInfo.txSuccess);
        assertEquals(llStats.rxmpdu_bk, wifiInfo.rxSuccess);
        assertEquals(signalPollResult.currentRssi, wifiInfo.getRssi());
        assertEquals(signalPollResult.txBitrate, wifiInfo.getLinkSpeed());
        assertEquals(sFreq, wifiInfo.getFrequency());
        verify(mWifiScoreCard).noteSignalPoll(any());
    }

    /**
     * Verify that calls to start and stop filtering multicast packets are passed on to the IpClient
     * instance.
     */
    @Test
    public void verifyMcastLockManagerFilterControllerCallsUpdateIpClient() throws Exception {
        loadComponentsInStaMode();
        reset(mIpClient);
        WifiMulticastLockManager.FilterController filterController =
                mCmi.getMcastLockManagerFilterController();
        filterController.startFilteringMulticastPackets();
        verify(mIpClient).setMulticastFilter(eq(true));
        filterController.stopFilteringMulticastPackets();
        verify(mIpClient).setMulticastFilter(eq(false));
    }

    /**
     * Verifies that connected MAC randomization is handled correctly when it is enabled.
     */
    @Test
    public void testConnectedMacRandomization() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 0)).thenReturn(1);
        mContentObserver.onChange(false);

        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_GLOBAL_MAC_ADDRESS.toString());
        when(mWifiNative.setMacAddress(eq(WIFI_IFACE_NAME), anyObject()))
                .then(new AnswerWithArguments() {
                    public boolean answer(String iface, MacAddress mac) {
                        when(mWifiNative.getMacAddress(iface)).thenReturn(mac.toString());
                        return true;
                    }
                });

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        MacAddress newMac = MacAddress.fromString(mWifiNative.getMacAddress(WIFI_IFACE_NAME));
        assertNotEquals(TEST_GLOBAL_MAC_ADDRESS, newMac);
        verify(mWifiConfigManager).setNetworkRandomizedMacAddress(eq(0), eq(newMac));
        verify(mWifiNative).setMacAddress(eq(WIFI_IFACE_NAME), eq(newMac));
        verify(mWifiMetrics)
                .logStaEvent(eq(StaEvent.TYPE_MAC_CHANGE), any(WifiConfiguration.class));
        assertEquals(mCmi.getWifiInfo().getMacAddress(), newMac.toString());
    }

    /**
     * Verifies that WifiInfo returns DEFAULT_MAC_ADDRESS as mac address when Connected MAC
     * Randomization is on and the device is not connected to a wifi network.
     */
    @Test
    public void testWifiInfoReturnDefaultMacWhenDisconnectedWithRandomization() throws Exception {
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 0)).thenReturn(1);
        mContentObserver.onChange(false);
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());

        connect();
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mCmi.getWifiInfo().getMacAddress());

        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, -1, 3, sBSSID);
        mLooper.dispatchAll();
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, mCmi.getWifiInfo().getMacAddress());
        assertFalse(mCmi.getWifiInfo().hasRealMacAddress());
    }

    /**
     * Verifies that connected MAC randomization methods are not called
     * when the feature is off.
     */
    @Test
    public void testConnectedMacRandomizationWhenFeatureOff() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());
        String oldMac = mCmi.getWifiInfo().getMacAddress();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiConfigManager, never())
                .setNetworkRandomizedMacAddress(eq(0), any(MacAddress.class));
        verify(mWifiNative, never()).setMacAddress(eq(WIFI_IFACE_NAME), any(MacAddress.class));
        verify(mWifiMetrics, never())
                .logStaEvent(eq(StaEvent.TYPE_MAC_CHANGE), any(WifiConfiguration.class));
        assertEquals(mCmi.getWifiInfo().getMacAddress(), oldMac);
    }

    /**
     * Verifies that we don't set MAC address when config returns an invalid MAC address.
     */
    @Test
    public void testDoNotSetMacWhenInvalid() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        assertEquals(ClientModeImpl.CONNECT_MODE, mCmi.getOperationalModeForTest());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 0)).thenReturn(1);
        mContentObserver.onChange(false);
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_GLOBAL_MAC_ADDRESS.toString());

        WifiConfiguration config = mock(WifiConfiguration.class);
        when(config.getOrCreateRandomizedMacAddress())
                .thenReturn(MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS));
        when(config.getNetworkSelectionStatus())
                .thenReturn(new WifiConfiguration.NetworkSelectionStatus());
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(config).getOrCreateRandomizedMacAddress();
        verify(mWifiNative, never()).setMacAddress(eq(WIFI_IFACE_NAME), any(MacAddress.class));
    }

    /**
     * Verifies that turning on/off Connected MAC Randomization correctly updates metrics.
     */
    @Test
    public void testUpdateConnectedMacRandomizationSettingMetrics() throws Exception {
        // Called during setUp
        verify(mWifiMetrics).setIsMacRandomizationOn(false);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 0)).thenReturn(1);
        mContentObserver.onChange(false);
        verify(mWifiMetrics).setIsMacRandomizationOn(true);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 0)).thenReturn(0);
        mContentObserver.onChange(false);
        verify(mWifiMetrics, times(2)).setIsMacRandomizationOn(false);
    }

    /**
     * Verifies that CMD_START_CONNECT make WifiDiagnostics report
     * CONNECTION_EVENT_STARTED
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterCmdStartConnect() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_STARTED));
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_STARTED));
    }

    /**
     * Verifies that CMD_DIAG_CONNECT_TIMEOUT is processed after the timeout threshold if we
     * start a connection but do not finish it.
     * @throws Exception
     */
    @Test
    public void testCmdDiagsConnectTimeoutIsGeneratedAfterCmdStartConnect() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(ClientModeImpl.DIAGS_CONNECT_TIMEOUT_MILLIS);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(BaseWifiDiagnostics.CONNECTION_EVENT_TIMEOUT));
    }

    /**
     * Verifies that CMD_DIAG_CONNECT_TIMEOUT does not get processed before the timeout threshold.
     * @throws Exception
     */
    @Test
    public void testCmdDiagsConnectTimeoutIsNotProcessedBeforeTimerExpires() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(ClientModeImpl.DIAGS_CONNECT_TIMEOUT_MILLIS - 1000);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(BaseWifiDiagnostics.CONNECTION_EVENT_TIMEOUT));
    }

    private void verifyConnectionEventTimeoutDoesNotOccur() {
        mLooper.moveTimeForward(ClientModeImpl.DIAGS_CONNECT_TIMEOUT_MILLIS);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(BaseWifiDiagnostics.CONNECTION_EVENT_TIMEOUT));
    }

    /**
     * Verifies that association failures make WifiDiagnostics report CONNECTION_EVENT_FAILED
     * and then cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterAssociationFailure() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT, 0,
                ISupplicantStaIfaceCallback.StatusCode.AP_UNABLE_TO_HANDLE_NEW_STA, sBSSID);
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION);
        verify(mWifiNetworkFactory).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION), any());
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verifies that authentication failures make WifiDiagnostics report
     * CONNECTION_EVENT_FAILED and then cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterAuthenticationFailure() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE);
        verify(mWifiNetworkFactory).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE), any());
        verifyConnectionEventTimeoutDoesNotOccur();

    }

    /**
     * Verifies that dhcp failures make WifiDiagnostics report CONNECTION_EVENT_FAILED and then
     * cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterDhcpFailure() throws Exception {
        testDhcpFailure();
        verify(mWifiDiagnostics, atLeastOnce()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        verify(mWifiConnectivityManager, atLeastOnce()).handleConnectionAttemptEnded(
                WifiMetrics.ConnectionEvent.FAILURE_DHCP);
        verify(mWifiNetworkFactory, atLeastOnce()).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_DHCP), any());
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verifies that a successful connection make WifiDiagnostics report CONNECTION_EVENT_SUCCEEDED
     * and then cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterSuccessfulConnection() throws Exception {
        connect();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_SUCCEEDED));
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                WifiMetrics.ConnectionEvent.FAILURE_NONE);
        verify(mWifiNetworkFactory).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_NONE), any());
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verify that score card is notified of a connection attempt
     */
    @Test
    public void testScoreCardNoteConnectionAttemptAfterCmdStartConnect() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        verify(mWifiScoreCard, never()).noteConnectionAttempt(any());
        mLooper.dispatchAll();
        verify(mWifiScoreCard).noteConnectionAttempt(any());
    }

    /**
     * Verify that we do not crash on quick toggling wifi on/off
     */
    @Test
    public void quickTogglesDoNotCrash() throws Exception {
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();

        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mLooper.dispatchAll();

        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mLooper.dispatchAll();

        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mCmi.setOperationalMode(ClientModeImpl.CONNECT_MODE, WIFI_IFACE_NAME);
        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();
    }

    /**
     * Verify that valid calls to set the current wifi state are returned when requested.
     */
    @Test
    public void verifySetAndGetWifiStateCallsWorking() throws Exception {
        // we start off disabled
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());

        // now check after updating
        mCmi.setWifiStateForApiCalls(WifiManager.WIFI_STATE_UNKNOWN);
        assertEquals(WifiManager.WIFI_STATE_UNKNOWN, mCmi.syncGetWifiState());

        // check after two updates
        mCmi.setWifiStateForApiCalls(WifiManager.WIFI_STATE_ENABLING);
        mCmi.setWifiStateForApiCalls(WifiManager.WIFI_STATE_ENABLED);
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mCmi.syncGetWifiState());
    }

    /**
     * Verify that invalid states do not change the saved wifi state.
     */
    @Test
    public void verifyInvalidStatesDoNotChangeSavedWifiState() throws Exception {
        int invalidStateNegative = -1;
        int invalidStatePositive = 5;

        // we start off disabled
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());

        mCmi.setWifiStateForApiCalls(invalidStateNegative);
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());

        mCmi.setWifiStateForApiCalls(invalidStatePositive);
        assertEquals(WifiManager.WIFI_STATE_DISABLED, mCmi.syncGetWifiState());
    }

    /**
     * Verify that IPClient instance is shutdown when wifi is disabled.
     */
    @Test
    public void verifyIpClientShutdownWhenDisabled() throws Exception {
        loadComponentsInStaMode();

        mCmi.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);
        mLooper.dispatchAll();
        verify(mIpClient).shutdown();
        verify(mIpClient).awaitShutdown();
    }

    /**
     * Verify that WifiInfo's MAC address is updated when the state machine receives
     * NETWORK_CONNECTION_EVENT while in ConnectedState.
     */
    @Test
    public void verifyWifiInfoMacUpdatedWithNetworkConnectionWhileConnected() throws Exception {
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());
        connect();
        assertEquals("ConnectedState", getCurrentState().getName());
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mCmi.getWifiInfo().getMacAddress());

        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_GLOBAL_MAC_ADDRESS.toString());
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mCmi.getWifiInfo().getMacAddress());
    }

    /**
     * Verify that WifiInfo's MAC address is updated when the state machine receives
     * NETWORK_CONNECTION_EVENT while in DisconnectedState.
     */
    @Test
    public void verifyWifiInfoMacUpdatedWithNetworkConnectionWhileDisconnected() throws Exception {
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());
        disconnect();
        assertEquals("DisconnectedState", getCurrentState().getName());
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mCmi.getWifiInfo().getMacAddress());

        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_GLOBAL_MAC_ADDRESS.toString());
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mCmi.getWifiInfo().getMacAddress());
    }

    /**
     * Verify that we temporarily disable the network when auto-connected to a network
     * with no internet access.
     */
    @Test
    public void verifyAutoConnectedNetworkWithInternetValidationFailure() throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        verify(mConnectivityManager).registerNetworkAgent(messengerCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkMisc.class));

        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = FRAMEWORK_NETWORK_ID;
        currentNetwork.noInternetAccessExpected = false;
        currentNetwork.numNoInternetAccessReports = 1;
        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(currentNetwork);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID + 1);

        Message message = new Message();
        message.what = NetworkAgent.CMD_REPORT_NETWORK_STATUS;
        message.arg1 = NetworkAgent.INVALID_NETWORK;
        message.obj = new Bundle();
        messengerCaptor.getValue().send(message);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .incrementNetworkNoInternetAccessReports(FRAMEWORK_NETWORK_ID);
        verify(mWifiConfigManager).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NO_INTERNET_TEMPORARY);
    }

    /**
     * Verify that we don't temporarily disable the network when user selected to connect to a
     * network with no internet access.
     */
    @Test
    public void verifyLastSelectedNetworkWithInternetValidationFailure() throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        verify(mConnectivityManager).registerNetworkAgent(messengerCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkMisc.class));

        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = FRAMEWORK_NETWORK_ID;
        currentNetwork.noInternetAccessExpected = false;
        currentNetwork.numNoInternetAccessReports = 1;
        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(currentNetwork);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID);

        Message message = new Message();
        message.what = NetworkAgent.CMD_REPORT_NETWORK_STATUS;
        message.arg1 = NetworkAgent.INVALID_NETWORK;
        message.obj = new Bundle();
        messengerCaptor.getValue().send(message);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .incrementNetworkNoInternetAccessReports(FRAMEWORK_NETWORK_ID);
        verify(mWifiConfigManager, never()).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NO_INTERNET_TEMPORARY);
    }

    /**
     * Verify that we temporarily disable the network when auto-connected to a network
     * with no internet access.
     */
    @Test
    public void verifyAutoConnectedNoInternetExpectedNetworkWithInternetValidationFailure()
            throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        verify(mConnectivityManager).registerNetworkAgent(messengerCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkMisc.class));

        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = FRAMEWORK_NETWORK_ID;
        currentNetwork.noInternetAccessExpected = true;
        currentNetwork.numNoInternetAccessReports = 1;
        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(currentNetwork);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID + 1);

        Message message = new Message();
        message.what = NetworkAgent.CMD_REPORT_NETWORK_STATUS;
        message.arg1 = NetworkAgent.INVALID_NETWORK;
        message.obj = new Bundle();
        messengerCaptor.getValue().send(message);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .incrementNetworkNoInternetAccessReports(FRAMEWORK_NETWORK_ID);
        verify(mWifiConfigManager, never()).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NO_INTERNET_TEMPORARY);
    }

    /**
     * Verify that we enable the network when we detect validated internet access.
     */
    @Test
    public void verifyNetworkSelectionEnableOnInternetValidation() throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        verify(mConnectivityManager).registerNetworkAgent(messengerCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkMisc.class));

        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID + 1);

        Message message = new Message();
        message.what = NetworkAgent.CMD_REPORT_NETWORK_STATUS;
        message.arg1 = NetworkAgent.VALID_NETWORK;
        message.obj = new Bundle();
        messengerCaptor.getValue().send(message);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .setNetworkValidatedInternetAccess(FRAMEWORK_NETWORK_ID, true);
        verify(mWifiConfigManager).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, NETWORK_SELECTION_ENABLE);
    }

    /**
     * Verify that we check for data stall during rssi poll
     * and then check that wifi link layer usage data are being updated.
     */
    @Test
    public void verifyRssiPollChecksDataStall() throws Exception {
        mCmi.enableRssiPolling(true);
        connect();

        WifiLinkLayerStats oldLLStats = new WifiLinkLayerStats();
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(oldLLStats);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        WifiLinkLayerStats newLLStats = new WifiLinkLayerStats();
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(newLLStats);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        verify(mWifiDataStall).checkForDataStall(oldLLStats, newLLStats);
        verify(mWifiMetrics).incrementWifiLinkLayerUsageStats(newLLStats);
    }
}