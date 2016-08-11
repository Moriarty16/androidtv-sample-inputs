/*
 * Copyright 2016 The Android Open Source Project
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
package com.google.android.media.tv.companionlibrary;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.tv.TvContract;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.support.v4.content.LocalBroadcastManager;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.util.LongSparseArray;

import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.Program;
import com.google.android.media.tv.companionlibrary.utils.TvContractUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests the synchronization of the EpgSyncTask with the EPG, making sure repeated programs are
 * generated correctly for the entire syncing period.
 */
@RunWith(AndroidJUnit4.class)
public class EpgSyncJobServiceTest
        extends ActivityInstrumentationTestCase2<TestActivity> {
    private static final String TAG = EpgSyncJobServiceTest.class.getSimpleName();
    private String mInputId = "com.example.android.sampletvinput/.TestTvInputService";
    private List<Program> mProgramList;
    private List<Channel> mChannelList;
    private TestJobService mSampleJobService;
    private LongSparseArray<Channel> mChannelMap;
    private long mStartMs;
    private long mEndMs;
    private CountDownLatch mSyncStatusLatch;
    private final BroadcastReceiver mSyncStatusChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String syncStatusChangedInputId = intent.getStringExtra(
                            EpgSyncJobService.BUNDLE_KEY_INPUT_ID);
                    if (syncStatusChangedInputId != null) {
                        String syncStatus = intent.getStringExtra(EpgSyncJobService.SYNC_STATUS);
                        if (syncStatus.equals(EpgSyncJobService.SYNC_STARTED)) {
                            mSyncStatusLatch.countDown();
                        }
                        if (syncStatus.equals(EpgSyncJobService.SYNC_FINISHED)) {
                            mSyncStatusLatch.countDown();
                        }
                    }
                }
            });
        }
    };

    public EpgSyncJobServiceTest() {
        super(TestActivity.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        getActivity();
        // Delete all channels
        getActivity().getContentResolver().delete(TvContract.buildChannelsUriForInput(mInputId),
                null, null);

        mSampleJobService = new TestJobService();
        mSampleJobService.mContext = getActivity();
        mChannelList = mSampleJobService.getChannels();
        TvContractUtils.updateChannels(getActivity(), mInputId, mChannelList);
        mChannelMap = TvContractUtils.buildChannelMap(getActivity().getContentResolver(), mInputId);
        assertNotNull(mChannelMap);
        assertEquals(2, mChannelMap.size());

        // Round start time to the current hour
        mStartMs = System.currentTimeMillis() - System.currentTimeMillis() % (1000 * 60 * 60);
        mEndMs = mStartMs + 1000 * 60 * 60 * 24 * 7 * 2; // Two week long sync period
        assertTrue(mStartMs < mEndMs);

        Uri channelUri =  TvContract.buildChannelUri(mChannelMap.keyAt(0));
        Channel firstChannel = mChannelList.get(0);
        assertEquals("Test Channel", firstChannel.getDisplayName());
        assertNotNull(firstChannel.getInternalProviderData());
        assertTrue(firstChannel.getInternalProviderData().isRepeatable());

        mProgramList = mSampleJobService.getProgramsForChannel(channelUri, firstChannel, mStartMs,
                mEndMs);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        LocalBroadcastManager.getInstance(getActivity())
                .unregisterReceiver(mSyncStatusChangedReceiver);

        // Delete these programs
        List<Channel> channelList = TvContractUtils.getChannels(getActivity().getContentResolver());
        Uri sampleChannelUri = TvContract.buildProgramsUriForChannel(channelList.get(0).getId());
        getActivity().getContentResolver().delete(TvContract.buildChannelsUriForInput(mInputId),
                null, null);
        getActivity().getContentResolver().delete(
                TvContract.buildProgramsUriForChannel(sampleChannelUri), null, null);
    }

    @Test
    public void testChannelsProgramSync() {
        // Tests that programs and channels were correctly obtained from the EpgSyncJobService
        Uri channelUri =  TvContract.buildChannelUri(mChannelMap.keyAt(0));
        Channel firstChannel = mChannelList.get(0);
        assertEquals("Test Channel", firstChannel.getDisplayName());
        assertNotNull(firstChannel.getInternalProviderData());
        assertTrue(firstChannel.getInternalProviderData().isRepeatable());

        mProgramList = mSampleJobService.getProgramsForChannel(channelUri, firstChannel, mStartMs,
                mEndMs);
        assertEquals(1, mProgramList.size());

        channelUri =  TvContract.buildChannelUri(mChannelMap.keyAt(1));
        Channel secondChannel = mChannelList.get(1);
        assertEquals("XML Test Channel", secondChannel.getDisplayName());
        assertNotNull(secondChannel.getInternalProviderData());
        assertTrue(secondChannel.getInternalProviderData().isRepeatable());

        mProgramList = mSampleJobService.getProgramsForChannel(channelUri, secondChannel, mStartMs,
                mEndMs);
        assertEquals(5, mProgramList.size());
    }

    @Test
    public void testRepeatProgramDuration() {
        // If repeat-programs is on, schedule the programs sequentially in a loop. To make every
        // device play the same program in a given channel and time, we assumes the loop started
        // from the epoch time.
        long totalDurationMs = 0;
        for (Program program : mProgramList) {
            totalDurationMs += (program.getEndTimeUtcMillis() - program.getStartTimeUtcMillis());
            assertTrue(program.getEndTimeUtcMillis() >= program.getStartTimeUtcMillis());
        }
        long programStartTimeMs = mStartMs - mStartMs % totalDurationMs;
        assertNotSame(0, totalDurationMs);
        assertTrue(programStartTimeMs > 0);
        assertTrue(programStartTimeMs <= mStartMs);
    }

    @Test
    public void testRequestSync() throws InterruptedException {
        // Tests that a sync can be requested and complete
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(
                mSyncStatusChangedReceiver,
                new IntentFilter(EpgSyncJobService.ACTION_SYNC_STATUS_CHANGED));
        mSyncStatusLatch = new CountDownLatch(2);
        EpgSyncJobService.cancelAllSyncRequests(getActivity());
        EpgSyncJobService.requestImmediateSync(getActivity(), mInputId, 1000 * 60 * 60,
                // 1 hour sync period
                new ComponentName(getActivity(), TestJobService.class));
        assertTrue(mSyncStatusLatch.await(30, TimeUnit.SECONDS));
        // Sync is completed
        List<Channel> channelList = TvContractUtils.getChannels(getActivity().getContentResolver());
        Log.d("TvContractUtils", channelList.toString());
        assertEquals(2, channelList.size());
        List<Program> programList = TvContractUtils.getPrograms(getActivity().getContentResolver(),
                TvContract.buildChannelUri(channelList.get(0).getId()));
        assertEquals(2, programList.size());
    }

    @Test
    public void testJobService() {
        // Tests whether methods to get channels and programs are successful and valid
        List<Channel> channelList = mSampleJobService.getChannels();
        assertEquals(2, channelList.size());
        TvContractUtils.updateChannels(getActivity(), mInputId, channelList);
        LongSparseArray<Channel> channelMap = TvContractUtils.buildChannelMap(
                getActivity().getContentResolver(), mInputId);
        assertNotNull(channelMap);
        assertEquals(channelMap.size(), channelList.size());
    }

    @Test
    public void testEpgSyncTask_GetPrograms() {
        // For repeating channels, test that the program list will continually repeat for the
        // desired length of time
        Uri channelUri = TvContract.buildChannelUri(mChannelMap.keyAt(0));
        Channel firstChannel = mChannelList.get(0);
        TestJobService.TestEpgSyncTask epgSyncTask = mSampleJobService.getDefaultEpgSyncTask();
        mProgramList = mSampleJobService.getProgramsForChannel(channelUri, firstChannel, mStartMs,
                mEndMs);
        List<Program> continuousProgramsList = epgSyncTask.getPrograms(
                firstChannel, mProgramList, mStartMs, mEndMs);
        // There are 336 hours in a two week period, and each program is one hour long
        assertEquals(336, continuousProgramsList.size());
    }
}