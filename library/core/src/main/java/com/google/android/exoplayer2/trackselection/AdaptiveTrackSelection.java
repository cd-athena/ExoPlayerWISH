/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.trackselection;

import static java.lang.Math.max;

import android.content.Context;
import android.util.Log;
import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunkIterator;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Clock;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.compatqual.NullableType;

// MINH [Add packages] - ADD - S
// MINH [Add packages] - ADD - E
// Ekrem [Add Packages] - Pensieve ABR

/**
 * A bandwidth based adaptive {@link TrackSelection}, whose selected track is updated to be the one
 * of highest quality given the current network conditions and the state of the buffer.
 */
public class AdaptiveTrackSelection extends BaseTrackSelection {
  // Minh - Get the time to download the first segment - ADD - S
  public static String startTime;
  // Minh - Get the time to download the first segment - ADD - E
  // Minh [Get battery info] - ADD - S
  public static int batteryLevel = 100;
  // Minh [Get battery info] - ADD - E
  private final boolean isSREnable = false;
  public static double lastThroughputMbps = 1;
  public static double last_dl_time = 0;

  // Minh - Some constant value for SR ABR - ADD - S
  public static double alpha; //0.2; // for bandwidth cost
  public static double beta; //0.3;  // for buffer cost
  public static double gamma; //0.5;
  public static double omega;
  public static double SD = 4 ; // second
  public static final double margin = 0.1;
  public static final double buffMax = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS/1000;
  public static final double SAFE_BUFFER_RATIO = (buffMax <= 20) ? 0.8 : 0.8*20/buffMax;
  public static final double TARGET_BUFFER_RATIO = 0.5;
  public static final double LOW_BUFFER_RATIO = (buffMax <= 20) ? 0.3 : 0.3*20/buffMax;
  public static final double MIN_BUFFER_RATIO = (buffMax <= 20) ? 0.2 : 0.2*20/buffMax;
  public static double       buffLowThresholdS = SD; // = SD
  public static int          optimal_last_idx = 0;
  public static int           num_of_quality = 0;
  public static double        maxBitrateKbps = 0;
  public static String segment_url = "";
  public static List<Double>  qualityLevelList = new ArrayList<>();

  private static int          lastQualityIndex = 0;
  private static double       smoothThroughputMbps = 0;
  private static double denominator_exp = 1;
  private boolean[]           srFlag = new boolean[length];
  private double  saraEstimatedThroughputbps = 0;
  private double  estimatedThroghputMbps = 0;
  private double  maxThroughputMbps = 0;

  private static double saraSumWeight = 0;
  private static double saraSumWeightDividedThroughput = 0;
  private static List<Double> saraLastThroughputMbpsList = new ArrayList<>();

  enum ABR {
    EXOPLAYER, SARA, BBA, PENSIEVE, BOLA, ELASTIC, FASTMPC, SQUAD, SR_1, SR, SR_2, WISH, SR_4, WISH_CONSERVATIVE
  }
  enum QUALITY_CONFIG{
    LINEAR, BITRATE_BASED
  }
  enum SQUAD_PLAY_STATE {
    INIT, SLOW_START, STEADY
  }

  public static final ABR implementedABR = ABR.WISH_CONSERVATIVE;  // WISH is the best
  public static final QUALITY_CONFIG quality_config = QUALITY_CONFIG.BITRATE_BASED;
  private static SQUAD_PLAY_STATE play_state = SQUAD_PLAY_STATE.INIT;
  private static double squad_spectrum = 0;
  private static double squad_sum_bitrateKbps = 0;
  private static int    squad_num_switch = 0;
  // Minh - Some constant value for SR ABR - ADD - E

  /** Factory for {@link AdaptiveTrackSelection} instances. */
  public static class Factory implements TrackSelection.Factory {

    private final int minDurationForQualityIncreaseMs;
    private final int maxDurationForQualityDecreaseMs;
    private final int minDurationToRetainAfterDiscardMs;
    private final float bandwidthFraction;
    private final float bufferedFractionToLiveEdgeForQualityIncrease;
    private final Clock clock;

    /** Creates an adaptive track selection factory with default parameters. */
    public Factory() {
      this(
          DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
          DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
          DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
          DEFAULT_BANDWIDTH_FRACTION,
          DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
          Clock.DEFAULT);
    }

    /**
     * Creates an adaptive track selection factory.
     *
     * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for the
     *     selected track to switch to one of higher quality.
     * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for the
     *     selected track to switch to one of lower quality.
     * @param minDurationToRetainAfterDiscardMs When switching to a track of significantly higher
     *     quality, the selection may indicate that media already buffered at the lower quality can
     *     be discarded to speed up the switch. This is the minimum duration of media that must be
     *     retained at the lower quality.
     * @param bandwidthFraction The fraction of the available bandwidth that the selection should
     *     consider available for use. Setting to a value less than 1 is recommended to account for
     *     inaccuracies in the bandwidth estimator.
     */
    public Factory(
        int minDurationForQualityIncreaseMs,
        int maxDurationForQualityDecreaseMs,
        int minDurationToRetainAfterDiscardMs,
        float bandwidthFraction) {
      this(
          minDurationForQualityIncreaseMs,
          maxDurationForQualityDecreaseMs,
          minDurationToRetainAfterDiscardMs,
          bandwidthFraction,
          DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
          Clock.DEFAULT);
    }

    /**
     * Creates an adaptive track selection factory.
     *
     * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for the
     *     selected track to switch to one of higher quality.
     * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for the
     *     selected track to switch to one of lower quality.
     * @param minDurationToRetainAfterDiscardMs When switching to a track of significantly higher
     *     quality, the selection may indicate that media already buffered at the lower quality can
     *     be discarded to speed up the switch. This is the minimum duration of media that must be
     *     retained at the lower quality.
     * @param bandwidthFraction The fraction of the available bandwidth that the selection should
     *     consider available for use. Setting to a value less than 1 is recommended to account for
     *     inaccuracies in the bandwidth estimator.
     * @param bufferedFractionToLiveEdgeForQualityIncrease For live streaming, the fraction of the
     *     duration from current playback position to the live edge that has to be buffered before
     *     the selected track can be switched to one of higher quality. This parameter is only
     *     applied when the playback position is closer to the live edge than {@code
     *     minDurationForQualityIncreaseMs}, which would otherwise prevent switching to a higher
     *     quality from happening.
     * @param clock A {@link Clock}.
     */
    public Factory(
        int minDurationForQualityIncreaseMs,
        int maxDurationForQualityDecreaseMs,
        int minDurationToRetainAfterDiscardMs,
        float bandwidthFraction,
        float bufferedFractionToLiveEdgeForQualityIncrease,
        Clock clock) {
      this.minDurationForQualityIncreaseMs = minDurationForQualityIncreaseMs;
      this.maxDurationForQualityDecreaseMs = maxDurationForQualityDecreaseMs;
      this.minDurationToRetainAfterDiscardMs = minDurationToRetainAfterDiscardMs;
      this.bandwidthFraction = bandwidthFraction;
      this.bufferedFractionToLiveEdgeForQualityIncrease =
          bufferedFractionToLiveEdgeForQualityIncrease;
      this.clock = clock;
    }

    @Override
    public final @NullableType TrackSelection[] createTrackSelections(
        @NullableType Definition[] definitions, BandwidthMeter bandwidthMeter) {
      TrackSelection[] selections = new TrackSelection[definitions.length];
      int totalFixedBandwidth = 0;
      for (int i = 0; i < definitions.length; i++) {
        Definition definition = definitions[i];
        if (definition != null && definition.tracks.length == 1) {
          // Make fixed selections first to know their total bandwidth.
          selections[i] =
              new FixedTrackSelection(
                  definition.group, definition.tracks[0], definition.reason, definition.data);
          int trackBitrate = definition.group.getFormat(definition.tracks[0]).bitrate;
          if (trackBitrate != Format.NO_VALUE) {
            totalFixedBandwidth += trackBitrate;
          }
        }
      }
      List<AdaptiveTrackSelection> adaptiveSelections = new ArrayList<>();
      for (int i = 0; i < definitions.length; i++) {
        Definition definition = definitions[i];
        if (definition != null && definition.tracks.length > 1) {
          AdaptiveTrackSelection adaptiveSelection =
              createAdaptiveTrackSelection(
                  definition.group, bandwidthMeter, definition.tracks, totalFixedBandwidth);
          adaptiveSelections.add(adaptiveSelection);
          selections[i] = adaptiveSelection;
        }
      }
      if (adaptiveSelections.size() > 1) {
        long[][] adaptiveTrackBitrates = new long[adaptiveSelections.size()][];
        for (int i = 0; i < adaptiveSelections.size(); i++) {
          AdaptiveTrackSelection adaptiveSelection = adaptiveSelections.get(i);
          adaptiveTrackBitrates[i] = new long[adaptiveSelection.length()];
          for (int j = 0; j < adaptiveSelection.length(); j++) {
            adaptiveTrackBitrates[i][j] =
                adaptiveSelection.getFormat(adaptiveSelection.length() - j - 1).bitrate;
          }
        }
        long[][][] bandwidthCheckpoints = getAllocationCheckpoints(adaptiveTrackBitrates);
        for (int i = 0; i < adaptiveSelections.size(); i++) {
          adaptiveSelections
              .get(i)
              .experimentalSetBandwidthAllocationCheckpoints(bandwidthCheckpoints[i]);
        }
      }
      return selections;
    }

    /**
     * Creates a single adaptive selection for the given group, bandwidth meter and tracks.
     *
     * @param group The {@link TrackGroup}.
     * @param bandwidthMeter A {@link BandwidthMeter} which can be used to select tracks.
     * @param tracks The indices of the selected tracks in the track group.
     * @param totalFixedTrackBandwidth The total bandwidth used by all non-adaptive tracks, in bits
     *     per second.
     * @return An {@link AdaptiveTrackSelection} for the specified tracks.
     */
    protected AdaptiveTrackSelection createAdaptiveTrackSelection(
        TrackGroup group,
        BandwidthMeter bandwidthMeter,
        int[] tracks,
        int totalFixedTrackBandwidth) {
      return new AdaptiveTrackSelection(
          group,
          tracks,
          new DefaultBandwidthProvider(bandwidthMeter, bandwidthFraction, totalFixedTrackBandwidth),
          minDurationForQualityIncreaseMs,
          maxDurationForQualityDecreaseMs,
          minDurationToRetainAfterDiscardMs,
          bufferedFractionToLiveEdgeForQualityIncrease,
          clock);
    }
  }

  public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10_000;
  public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25_000;
  public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25_000;
  public static final float DEFAULT_BANDWIDTH_FRACTION = 0.9f;
  public static final float DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE = 0.75f;

  private static final long MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS = 1000;

  // Minh [Create some evaluation parameters] - ADD - S
  private long first_timeStamp = 0;
  public  static String networkType= "4G";
  public  static List<Long> timestampMs = new ArrayList<>();
  public  static List<Integer> selectedQualityIndex = new ArrayList<>();
  public  static List<Integer> selectedQualityBitrateBitps = new ArrayList<>();
  public  static List<Long> averageThroughputBitps = new ArrayList<>();
  public  static List<Long> bufferLevelUs = new ArrayList<>();
  public  static List<Boolean> isSREnableList = new ArrayList<>();
  public static List<Integer> batteryLevellist = new ArrayList<>();
  // Minh [Create some evaluation parameters] - ADD - E

  private final BandwidthProvider bandwidthProvider;
  private final long minDurationForQualityIncreaseUs;
  private final long maxDurationForQualityDecreaseUs;
  private final long minDurationToRetainAfterDiscardUs;
  private final float bufferedFractionToLiveEdgeForQualityIncrease;
  private final Clock clock;

  private float playbackSpeed;
  private int selectedIndex;
  private int reason;
  private long lastBufferEvaluationMs;
  @Nullable private MediaChunk lastBufferEvaluationMediaChunk;

  /**
   * @param group The {@link TrackGroup}.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     empty. May be in any order.
   * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
   */
  public AdaptiveTrackSelection(TrackGroup group, int[] tracks,
      BandwidthMeter bandwidthMeter) {
    this(
        group,
        tracks,
        bandwidthMeter,
        /* reservedBandwidth= */ 0,
        DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
        DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        DEFAULT_BANDWIDTH_FRACTION,
        DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        Clock.DEFAULT);
  }

  /**
   * @param group The {@link TrackGroup}.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     empty. May be in any order.
   * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
   * @param reservedBandwidth The reserved bandwidth, which shouldn't be considered available for
   *     use, in bits per second.
   * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for the
   *     selected track to switch to one of higher quality.
   * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for the
   *     selected track to switch to one of lower quality.
   * @param minDurationToRetainAfterDiscardMs When switching to a track of significantly higher
   *     quality, the selection may indicate that media already buffered at the lower quality can be
   *     discarded to speed up the switch. This is the minimum duration of media that must be
   *     retained at the lower quality.
   * @param bandwidthFraction The fraction of the available bandwidth that the selection should
   *     consider available for use. Setting to a value less than 1 is recommended to account for
   *     inaccuracies in the bandwidth estimator.
   * @param bufferedFractionToLiveEdgeForQualityIncrease For live streaming, the fraction of the
   *     duration from current playback position to the live edge that has to be buffered before the
   *     selected track can be switched to one of higher quality. This parameter is only applied
   *     when the playback position is closer to the live edge than {@code
   *     minDurationForQualityIncreaseMs}, which would otherwise prevent switching to a higher
   *     quality from happening.
   */
  public AdaptiveTrackSelection(
      TrackGroup group,
      int[] tracks,
      BandwidthMeter bandwidthMeter,
      long reservedBandwidth,
      long minDurationForQualityIncreaseMs,
      long maxDurationForQualityDecreaseMs,
      long minDurationToRetainAfterDiscardMs,
      float bandwidthFraction,
      float bufferedFractionToLiveEdgeForQualityIncrease,
      Clock clock) {
    this(
        group,
        tracks,
        new DefaultBandwidthProvider(bandwidthMeter, bandwidthFraction, reservedBandwidth),
        minDurationForQualityIncreaseMs,
        maxDurationForQualityDecreaseMs,
        minDurationToRetainAfterDiscardMs,
        bufferedFractionToLiveEdgeForQualityIncrease,
        clock);
  }

  private AdaptiveTrackSelection(
      TrackGroup group,
      int[] tracks,
      BandwidthProvider bandwidthProvider,
      long minDurationForQualityIncreaseMs,
      long maxDurationForQualityDecreaseMs,
      long minDurationToRetainAfterDiscardMs,
      float bufferedFractionToLiveEdgeForQualityIncrease,
      Clock clock) {
    super(group, tracks);
    this.bandwidthProvider = bandwidthProvider;
    this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
    this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
    this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
    this.bufferedFractionToLiveEdgeForQualityIncrease =
        bufferedFractionToLiveEdgeForQualityIncrease;
    this.clock = clock;
    playbackSpeed = 1f;
    reason = C.SELECTION_REASON_UNKNOWN;
    lastBufferEvaluationMs = C.TIME_UNSET;
  }

  /**
   * Sets checkpoints to determine the allocation bandwidth based on the total bandwidth.
   *
   * @param allocationCheckpoints List of checkpoints. Each element must be a long[2], with [0]
   *     being the total bandwidth and [1] being the allocated bandwidth.
   */
  public void experimentalSetBandwidthAllocationCheckpoints(long[][] allocationCheckpoints) {
    ((DefaultBandwidthProvider) bandwidthProvider)
        .experimentalSetBandwidthAllocationCheckpoints(allocationCheckpoints);
  }

  @CallSuper
  @Override
  public void enable() {
    lastBufferEvaluationMs = C.TIME_UNSET;
    lastBufferEvaluationMediaChunk = null;
  }

  @CallSuper
  @Override
  public void disable() {
    // Avoid keeping a reference to a MediaChunk in case it prevents garbage collection.
    lastBufferEvaluationMediaChunk = null;
  }

  @Override
  public void onPlaybackSpeed(float playbackSpeed) {
    this.playbackSpeed = playbackSpeed;
  }

  @Override
  public void updateSelectedTrack(
      long playbackPositionUs,  // Minh: current playback position (microseconds)
      long bufferedDurationUs,  // Minh: current buffer level (microseconds)
      long availableDurationUs, // Minh: = buffer size - playbackPositionUs
      List<? extends MediaChunk> queue,
      MediaChunkIterator[] mediaChunkIterators) {
    long nowMs = clock.elapsedRealtime();

    Log.i("MINH", "******************************* current buffer: " + bufferedDurationUs);
    Log.i("MINH", "******************************* Playback Pos.: " + playbackPositionUs);

    // Make initial selection
    if (reason == C.SELECTION_REASON_UNKNOWN) {
      reason = C.SELECTION_REASON_INITIAL;

      reset_variables();
      selectedIndex = get_chosen_quality_index(implementedABR, nowMs, bufferedDurationUs, playbackPositionUs);
      maxBitrateKbps = getFormat(0).bitrate/1000.0;
      lastQualityIndex = selectedIndex;
      Log.i("MINH", "****** Initial selectedIndex: " + (length-selectedIndex) + " at time " + nowMs);
      first_timeStamp = nowMs;

//      timestampMs.add(nowMs);
//      selectedQualityIndex.add(length - selectedIndex);
//      selectedQualityBitrateBitps.add(getFormat(selectedIndex).bitrate);
//      averageThroughputBitps.add(bandwidthProvider.getAllocatedBandwidth());
////      averageThroughputBitps.add ((long)(estimatedThroghputMbps*1000000.0));
//      bufferLevelUs.add(bufferedDurationUs);
//      isSREnableList.add(false);
//      batteryLevellist.add(batteryLevel);

      Calendar calendar = Calendar.getInstance();
      startTime   = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY)) + "_" +
                    String.format("%02d", calendar.get(Calendar.MINUTE))+ "_" +
                    String.format("%02d", calendar.get(Calendar.SECOND));
      return;
    }
    int previousSelectedIndex = selectedIndex;
    int previousReason = reason;
    int formatIndexOfPreviousChunk =
        queue.isEmpty() ? C.INDEX_UNSET : indexOf(Iterables.getLast(queue).trackFormat);
    if (formatIndexOfPreviousChunk != C.INDEX_UNSET) {
      previousSelectedIndex = formatIndexOfPreviousChunk;
      previousReason = Iterables.getLast(queue).trackSelectionReason;
    }

    // MINH [Use SR ABR]- MOD - S
    int newSelectedIndex = 0;
    newSelectedIndex = get_chosen_quality_index(implementedABR, nowMs, bufferedDurationUs, playbackPositionUs);

    Log.i("MINH", "\tABR selected Idx " + (length - newSelectedIndex) +
              " for seg. " + selectedQualityIndex.size() +
              " bitrate " + getFormat(newSelectedIndex).bitrate/1000000.0 +
              " Buffer " + bufferedDurationUs/1000000.0 +
              " Thrp " + bandwidthProvider.getAllocatedBandwidth()/1000000.0 + " Mbps. Max Thrp: " + maxThroughputMbps);
    if (!queue.isEmpty() && segment_url == "") {
      get_video_name(queue.get(0).getUri().getSchemeSpecificPart());
    }
    // MINH [Use SR ABR]]- MOD - E

    // If we adapted, update the trigger.
    reason =
        newSelectedIndex == previousSelectedIndex ? previousReason : C.SELECTION_REASON_ADAPTIVE;

    boolean isLastChunkDuration0 = false;
    int tmp = timestampMs.size()-1;
    if (queue.size() > 0) {
      Chunk lastChunk = queue.get(queue.size() - 1);
      if (lastChunk.trackFormat.bitrate != selectedQualityBitrateBitps.get(tmp)) {
        isLastChunkDuration0 = true;
      }
    }

    // Minh [Record parameters] - ADD - S
//    if (bufferedDurationUs == 0 || isLastChunkDuration0) {
//    if (isLastChunkDuration0) { // before squad
    if (isLastChunkDuration0 && playbackPositionUs == 0){ // 16after squad
      timestampMs.set(tmp, nowMs-first_timeStamp);
      selectedQualityIndex.set(tmp, length - newSelectedIndex);
      selectedQualityBitrateBitps.set(tmp, getFormat(newSelectedIndex).bitrate);
      averageThroughputBitps.set(tmp, bandwidthProvider.getAllocatedBandwidth());
//      averageThroughputBitps.set(tmp, (long)(estimatedThroghputMbps*1000000.0));
      bufferLevelUs.set(tmp, bufferedDurationUs);
      isSREnableList.set(tmp, srFlag[newSelectedIndex]);
      batteryLevellist.set(tmp, batteryLevel);
    }
    else {
      timestampMs.add(nowMs-first_timeStamp);
      selectedQualityIndex.add(length - newSelectedIndex);
      selectedQualityBitrateBitps.add(getFormat(newSelectedIndex).bitrate);
      averageThroughputBitps.add(bandwidthProvider.getAllocatedBandwidth());
//      averageThroughputBitps.add((long)(estimatedThroghputMbps*1000000.0));
      bufferLevelUs.add(bufferedDurationUs);
      isSREnableList.add(srFlag[newSelectedIndex]);
      batteryLevellist.add(batteryLevel);
    }
    // Minh [Record parameters] - ADD - E

    selectedIndex = newSelectedIndex;
    lastQualityIndex = newSelectedIndex;
  }

  @Override
  public int getSelectedIndex() {
    return selectedIndex;
  }

  @Override
  public int getSelectionReason() {
    return reason;
  }

  @Override
  @Nullable
  public Object getSelectionData() {
    return null;
  }

  @Override
  public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    long nowMs = clock.elapsedRealtime();
    if (!shouldEvaluateQueueSize(nowMs, queue)) {
      return queue.size();
    }
    lastBufferEvaluationMs = nowMs;
    lastBufferEvaluationMediaChunk = queue.isEmpty() ? null : Iterables.getLast(queue);

    if (queue.isEmpty()) {
      return 0;
    }
    int queueSize = queue.size();
    // MINH [Prevent sending too many request] - DEL - S
    /*
    MediaChunk lastChunk = queue.get(queueSize - 1);
    long playoutBufferedDurationBeforeLastChunkUs =
        Util.getPlayoutDurationForMediaDuration(
            lastChunk.startTimeUs - playbackPositionUs, playbackSpeed);
    long minDurationToRetainAfterDiscardUs = getMinDurationToRetainAfterDiscardUs();
    if (playoutBufferedDurationBeforeLastChunkUs < minDurationToRetainAfterDiscardUs) {
      return queueSize;
    }

    int idealSelectedIndex = determineIdealSelectedIndex(nowMs);  // MINH: ??? What is this variable for?

    Format idealFormat = getFormat(idealSelectedIndex);
    // If the chunks contain video, discard from the first SD chunk beyond
    // minDurationToRetainAfterDiscardUs whose resolution and bitrate are both lower than the ideal
    // track.
    for (int i = 0; i < queueSize; i++) {
      MediaChunk chunk = queue.get(i);
      Format format = chunk.trackFormat;
      long mediaDurationBeforeThisChunkUs = chunk.startTimeUs - playbackPositionUs;
      long playoutDurationBeforeThisChunkUs =
          Util.getPlayoutDurationForMediaDuration(mediaDurationBeforeThisChunkUs, playbackSpeed);
      if (playoutDurationBeforeThisChunkUs >= minDurationToRetainAfterDiscardUs
          && format.bitrate < idealFormat.bitrate
          && format.height != Format.NO_VALUE && format.height < 720
          && format.width != Format.NO_VALUE && format.width < 1280
          && format.height < idealFormat.height) {
        Log.i("MINH", "++++++ evaluateQueueSize return i " + i);
        return i;
      }
    }
    */
    // MINH [Prevent sending too many request] - DEL - S
    return queueSize;
  }

  /**
   * Called when updating the selected track to determine whether a candidate track can be selected.
   *
   * @param format The {@link Format} of the candidate track.
   * @param trackBitrate The estimated bitrate of the track. May differ from {@link Format#bitrate}
   *     if a more accurate estimate of the current track bitrate is available.
   * @param playbackSpeed The current playback speed.
   * @param effectiveBitrate The bitrate available to this selection.
   * @return Whether this {@link Format} can be selected.
   */
  @SuppressWarnings("unused")
  protected boolean canSelectFormat(
      Format format, int trackBitrate, float playbackSpeed, long effectiveBitrate) {
    return Math.round(trackBitrate * playbackSpeed) <= effectiveBitrate;
  }

  /**
   * Called from {@link #evaluateQueueSize(long, List)} to determine whether an evaluation should be
   * performed.
   *
   * @param nowMs The current value of {@link Clock#elapsedRealtime()}.
   * @param queue The queue of buffered {@link MediaChunk MediaChunks}. Must not be modified.
   * @return Whether an evaluation should be performed.
   */
  protected boolean shouldEvaluateQueueSize(long nowMs, List<? extends MediaChunk> queue) {
    return lastBufferEvaluationMs == C.TIME_UNSET
        || nowMs - lastBufferEvaluationMs >= MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS
        || (!queue.isEmpty() && !Iterables.getLast(queue).equals(lastBufferEvaluationMediaChunk));
  }

  /**
   * Called from {@link #evaluateQueueSize(long, List)} to determine the minimum duration of buffer
   * to retain after discarding chunks.
   *
   * @return The minimum duration of buffer to retain after discarding chunks, in microseconds.
   */
  protected long getMinDurationToRetainAfterDiscardUs() {
    return minDurationToRetainAfterDiscardUs;
  }

  /**
   * Computes the ideal selected index ignoring buffer health.
   *
   * @param nowMs The current time in the timebase of {@link Clock#elapsedRealtime()}, or {@link
   *     Long#MIN_VALUE} to ignore track exclusion.
   */
  private int determineIdealSelectedIndex(long nowMs) {
    long effectiveBitrate = bandwidthProvider.getAllocatedBandwidth();
    int lowestBitrateAllowedIndex = 0;
    for (int i = 0; i < length; i++) {
      if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
        Format format = getFormat(i);
        srFlag[i] = false;
        if (canSelectFormat(format, format.bitrate, playbackSpeed, effectiveBitrate)) { //throughput-based
            return  i;
        } else {
          lowestBitrateAllowedIndex = i;
        }
      }
    }

    return lowestBitrateAllowedIndex;
  }

  // MINH [Proposed ABR algorithm] - ADD - S
  private void reset_variables() {
    segment_url = "";
    saraEstimatedThroughputbps = 0;
    saraSumWeight = 0;
    saraSumWeightDividedThroughput = 0;
    smoothThroughputMbps = 0;
    estimatedThroghputMbps = 0;
    num_of_quality = length;
    maxThroughputMbps = 0;
    squad_spectrum = 0;
    squad_num_switch = 0;
    squad_sum_bitrateKbps = 0;
    // clear the data from the previous run
    timestampMs.clear();
    selectedQualityIndex.clear();
    selectedQualityBitrateBitps.clear();
    averageThroughputBitps.clear();
    bufferLevelUs.clear();
    isSREnableList.clear();
    batteryLevellist.clear();
  }
  private int get_chosen_quality_index(ABR m_implementedABR, long nowMs, long bufferedDurationUs, long playbackPositionUs){
    int chosen_quality_index = 0;
    lastThroughputMbps = Math.max(lastThroughputMbps, bandwidthProvider.getAllocatedBandwidth()/1000000.0);
    Log.i("Minh", "===> Minh last thrp: " + lastThroughputMbps +  ". default: " + bandwidthProvider.getAllocatedBandwidth()/1000000.0);
    switch (m_implementedABR){
      case EXOPLAYER:
        chosen_quality_index = determineIdealSelectedIndex(nowMs);
        break;
      case SR:
        set_weights_1();
        chosen_quality_index = SR_ABR(nowMs, bufferedDurationUs, playbackPositionUs);
        break;
      case SR_2:
        set_weights_v2();
        chosen_quality_index = SR_ABR(nowMs, bufferedDurationUs, playbackPositionUs);
        break;
      case SR_1:
        set_weights_2costs();
        chosen_quality_index = SR_ABR_2costs(nowMs, bufferedDurationUs, playbackPositionUs);
        break;
      case WISH:
        set_weights_v3();
        chosen_quality_index = SR_ABR_v3(nowMs, bufferedDurationUs, playbackPositionUs);
        break;
      case WISH_CONSERVATIVE:
        set_weights_v3();
        chosen_quality_index = SR_ABR_v4(nowMs, bufferedDurationUs, playbackPositionUs);
        break;
//      case SR_4:
//        set_weights_v4();
//        chosen_quality_index = SR_ABR_v3(nowMs, bufferedDurationUs, playbackPositionUs);
//        break;
      case SARA:
        if (selectedQualityBitrateBitps.size() > saraLastThroughputMbpsList.size()) {
          saraLastThroughputMbpsList.add(lastThroughputMbps);
          Log.i("MINH", "SARA - Add new thrp: " + lastThroughputMbps);
        }
        chosen_quality_index = SARA_ABR(nowMs, (double)bufferedDurationUs/1000000.0, lastThroughputMbps*1000000.0);
        break;
      case BBA:
        chosen_quality_index = BBA_ABR(nowMs,(double)bufferedDurationUs/1000000.0);
        break;
      case PENSIEVE:
        chosen_quality_index = PENSIEVE_ABR(nowMs, bufferedDurationUs/100000);
        Log.e("MINH", "This ABR is not supported");
        break;
      case ELASTIC:
        Log.e("MINH", "This ABR is not supported");
        break;
      case FASTMPC:
        Log.e("MINH", "This ABR is not supported");
        break;
      case SQUAD:
//        Log.e("MINH", "This ABR is not supported");
        chosen_quality_index = SQUAD_ABR(nowMs, bufferedDurationUs);
//        SQUAD_get_spectrum((double)getFormat(lastQualityIndex).bitrate/1000000.0, (double)getFormat(chosen_quality_index).bitrate/1000000.0);
        break;
      default:
        chosen_quality_index = determineIdealSelectedIndex(nowMs);
        break;
    }
    return chosen_quality_index;
  }

  private int SR_ABR(long nowMs, long currentBufferUs, long playbackPositionUs) {
    estimatedThroghputMbps = lastThroughputMbps; //(double) bandwidthProvider.getAllocatedBandwidth()/1000000; //Mbps
//    double estimatedThroghputMbps = (double) bandwidthProvider.getEstimatedBandwidth()/1000000; //Mbps
//    estimatedThroghputMbps = (1 - margin) * Math.min(get_smooth_throughput(), (double) bandwidthProvider.getAllocatedBandwidth()/1000000);
//    estimatedThroghputMbps = get_smooth_throughput();
    Log.i("MINH", " -- ++ -- LastThrp: " + (double) bandwidthProvider.getAllocatedBandwidth()/1000000 +
                " EstThrp: " + estimatedThroghputMbps + "Mbps. currentBufferUs " + currentBufferUs/1000000+ "\n");

    int selectedBitrate = 0;
    int lowestCost = Integer.MAX_VALUE;

    if (currentBufferUs/1000000 <buffLowThresholdS) { // if buffer < min threshold || this's the first segment
      return length-1;
    }

    for (int i = 0; i < length; i++) {
      if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
        int currentTotalCost = (int)(100*getTotalCost(i, estimatedThroghputMbps, (double) currentBufferUs/1000000, networkType));
        if (lowestCost > currentTotalCost) {
          selectedBitrate = i;
          lowestCost = currentTotalCost;
        }
      }
    }

    return selectedBitrate;
  }

  private double get_smooth_throughput(double margin){
//    double last_thrp = (double) bandwidthProvider.getAllocatedBandwidth()/1000000;
    smoothThroughputMbps = (smoothThroughputMbps == 0) ? lastThroughputMbps : (1 - margin) * smoothThroughputMbps + margin* lastThroughputMbps;
    return smoothThroughputMbps;
  }

  private int SR_ABR_2costs(long nowMs, long currentBufferUs, long playbackPositionUs) {
    estimatedThroghputMbps = (double) bandwidthProvider.getAllocatedBandwidth()/1000000; //Mbps
    Log.i("MINH", " -- ++ -- EstThrp: " + estimatedThroghputMbps + "Mbps. currentBufferUs " + currentBufferUs/1000000+ "\n");

    int selectedBitrate = 0;
    int lowestCost = Integer.MAX_VALUE;

    if (currentBufferUs/1000000 <buffLowThresholdS) { // if buffer < min threshold || this's the first segment
      return length-1;
    }

    for (int i = 0; i < length; i++) {
      if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
        int currentTotalCost = (int)(100*getTotalCost_2costs(i, estimatedThroghputMbps, (double) currentBufferUs/1000000, networkType));
        if (lowestCost > currentTotalCost) {
          selectedBitrate = i;
          lowestCost = currentTotalCost;
        }
      }
    }

    return selectedBitrate;
  }

  void set_omega(){
    double[] omegaArray = {0.75, 1, 1.25}; // for 3G, 4G, and 5G, respectively
    switch (networkType) {
      case "3G":
        omega = omegaArray[0];
        break;
      case "4G":
        omega = omegaArray[1];
        break;
      case "5G":
        omega = omegaArray[2];
        break;
      default:
        omega = 1;
    }
  }
  double getTotalCost(int qualityIndex, double estimatedThroughputMbps, double currentbufferS, String networkType) {
    double totalCost;
    double powerCost;
    double bandwidthCost;
    double bufferCost;
    double qualityCost;
    double current_quality_level = qualityLevelList.get(qualityIndex);

    double[] powerConsumptionArray = {0, 0, 20, 25, 30, 40, 50, 55, 60, 60, 65, 65, 70, 75, 80, 82, 83, 84, 85, 86, 93}; // from highest quality version to lowest quality version.

    Format segmentFormat = getFormat(qualityIndex);

//    double temp = segmentFormat.bitrate*1.0/(estimatedThroughputMbps*1000000.0);  // bitrate is in bps
    double temp = segmentFormat.bitrate*1.0/(estimatedThroughputMbps*1000000.0);  // bitrate is in bps
    Log.i("Minh", "temp: " + temp);

    double bufferThreshold;
//    if (currentbufferS > 0.8*buffMax)
//      bufferThreshold = 0.4*buffMax;
//    else if (currentbufferS > 0.4*buffMax)
//      bufferThreshold =0.2*buffMax;
//    else
//      bufferThreshold = SD; //

    if (currentbufferS > 0.8*buffMax)
      bufferThreshold = 0.4*buffMax;
    else
      bufferThreshold = MIN_BUFFER_RATIO*buffMax;

    bandwidthCost = omega * temp;
//    bufferCost = temp*getQuantizationBuffer(SD*1.0/(currentbufferS - bufferThreshold));
    bufferCost = temp*(SD*1.0/(currentbufferS - bufferThreshold));
    qualityCost = Math.exp(qualityLevelList.get(0) + qualityLevelList.get(lastQualityIndex) - 2*current_quality_level)/denominator_exp;
    powerCost = 0;//(powerConsumptionArray[qualityIndex]*100.0)/(powerConsumptionArray[powerConsumptionArray.length-1]*batteryLevel);

    double subCost = alpha*bandwidthCost + beta*bufferCost;
    double conventionalCost = subCost + gamma*qualityCost;
    double srCost = subCost + gamma*powerCost;

    if (srCost < (1+margin)*(conventionalCost) && qualityIndex != 0 && isSREnable) {
      totalCost = srCost;
      srFlag[qualityIndex] = true;
    }
    else {
      totalCost = conventionalCost;
      srFlag[qualityIndex] = false;
    }

    Log.i("MINH", "\n Costs of quality " + (length - qualityIndex) + "\n\t==> powerCost: " + powerCost + "\n\t==> bandwidthCost: " + bandwidthCost +
        "\n\t==> bufferCost: " + bufferCost + "\n\t==> qualityCost: " + qualityCost);
    Log.i("MINH", "=======> Total cost of " + (length - qualityIndex) + ": " + totalCost + " Is SR enable? " + srFlag[qualityIndex] + " Last quality Index " + (length -lastQualityIndex));

    return totalCost;
  }

  double getTotalCost_2costs(int qualityIndex, double estimatedThroughputMbps, double currentbufferS, String networkType) {
    double totalCost;
    double bufferCost;
    double qualityCost;
    double current_quality_level = qualityLevelList.get(qualityIndex);

    Format segmentFormat = getFormat(qualityIndex);

    double temp = segmentFormat.bitrate*1.0/getQuantizationThrp(estimatedThroughputMbps*1000000.0);  // bitrate is in bps

    double bufferThreshold;
    if (currentbufferS > 0.8*buffMax)
      bufferThreshold = 0.4*buffMax;
    else if (currentbufferS > 0.4*buffMax)
      bufferThreshold =0.2*buffMax;
    else
      bufferThreshold = SD; //

    bufferCost = temp*getQuantizationBuffer(SD*1.0/(currentbufferS - bufferThreshold));
    qualityCost = Math.exp(qualityLevelList.get(0) + qualityLevelList.get(lastQualityIndex) - 2*current_quality_level)/denominator_exp;

    totalCost = beta*bufferCost + gamma*qualityCost;

    Log.i("MINH", "\n Costs of quality " + (length - qualityIndex) +
        "\n\t==> bufferCost: " + bufferCost +
        "\n\t==> qualityCost: " + qualityCost);
    Log.i("MINH", "=======> Total cost of " + (length - qualityIndex) + ": " + totalCost + " Is SR enable? " + srFlag[qualityIndex] + " Last quality Index " + (length -lastQualityIndex));

    return totalCost;
  }

  double getQuantizationBuffer(double input){
    int thresholdLength = 20;
    double[] thresholds = new double[thresholdLength];
    double granularity = 0.25;
    for (int i = 0; i < thresholdLength; i++){
      thresholds[i] = i*granularity;
    }

    for (int i = 0; i < thresholdLength; i++){
      if (thresholds[i] > input) return thresholds[i];
    }
    return thresholds[length-1];
  }

  double getQuantizationThrp(double thrp){
    if (thrp < getFormat(length-1).bitrate){
      return getFormat(length-1).bitrate/2;
    }
    else if (thrp > getFormat(0).bitrate){
      return thrp;
    }
    else {
      for (int i = length-2; i >= 0; i--){
        if (thrp < getFormat(i).bitrate){
          if (thrp*2.0 < getFormat(i).bitrate + getFormat(i+1).bitrate)
            return getFormat(i+1).bitrate;
          else
            return (getFormat(i).bitrate + getFormat(i+1).bitrate)/2.0;
        }
      }
    }
    return thrp;
  }

  void set_quality_function(){
    qualityLevelList.clear();
    switch (quality_config){
      case LINEAR:
        for (int i = 0; i < length; i ++){
          qualityLevelList.add((double)length-i);
        }
        break;
      case BITRATE_BASED:
        for (int i = 0; i < length; i ++){
          qualityLevelList.add((double)getFormat(i).bitrate/getFormat(0).bitrate);
        }
    }
    denominator_exp = 1; //Math.exp(2*qualityLevelList.get(0)-2*qualityLevelList.get(length-1));
  }

  void set_weights_1(){
    set_omega();
    set_quality_function();
    double optimal_delta_buffer = (double)(buffMax*(SAFE_BUFFER_RATIO-0.2))/SD; // want to have max quality when the buffer is 0.5 buffer max. 0.2*Buffer_max = bufferThreshold for this buffer level 0.5
    double delta_bitrate = 0;
    double delta_quality = 0;
    double big_gamma = 0;

    denominator_exp = Math.exp(2*qualityLevelList.get(0)-2*qualityLevelList.get(length-1));

    /** find big_gamma **/
//    for (int i = 0; i < length-1; i++){
//      delta_quality = qualityLevelList.get(i)-qualityLevelList.get(i+1);
//      delta_bitrate = (getFormat(i).bitrate - getFormat(i+1).bitrate);
//
//      big_gamma = Math.min(delta_quality*1.0/delta_bitrate, big_gamma);
//    }

    for (int i = 0; i < length-1; i ++){
      delta_bitrate = (getFormat(i).bitrate - getFormat(i+1).bitrate)/1000000.0;
      delta_quality = Math.exp(-2*qualityLevelList.get(i+1)) - Math.exp(-2*qualityLevelList.get(i));
      big_gamma = Math.max(delta_bitrate*1.0/delta_quality, big_gamma);
    }

    /** determine weights **/
//    alpha = (1-margin)/(1 + optimal_delta_buffer*omega + (omega*qualityLevelList.get(0))/(getFormat(0).bitrate*big_gamma));
    double  e_a = Math.exp(qualityLevelList.get(0) + qualityLevelList.get((int)((length-1)/2)));
    alpha = (1-margin)/(1 + optimal_delta_buffer*omega + (2.0*omega*big_gamma*denominator_exp)/(e_a*getFormat(0).bitrate/1000000.0));
    beta = alpha*omega*optimal_delta_buffer;
    gamma = 1 - alpha - beta;

    Log.i("Minh", "WEIGHT_1: alpha: " + alpha + ". beta: " + beta + ". gamma: " + gamma +
                      "\nbig_gamma: " + big_gamma +
                      "\ndelta_bufer: " + optimal_delta_buffer +
                      "\nbuffMax = " + buffMax + "\tSD: " + SD);
  }

  void set_weights_v2() {
    set_omega();
    set_quality_function();
    double R_max = getFormat(0).bitrate/1000000.0;
    double R_min = getFormat(length-2).bitrate/1000000.0; /** R2, not R1 becaue R1 is chosen when buffer < min **/
    int last_quality_1 = (int)((length-1)/2);
    int last_quality_2 = 0;
    double g_1 = 2.0/R_max * Math.exp(qualityLevelList.get(last_quality_1) - qualityLevelList.get(0));
    double g_2 = 2.0/R_max * Math.exp(qualityLevelList.get(0) + qualityLevelList.get(last_quality_2) - 2*qualityLevelList.get(length-1));

    double delta_buffer_max = (double)(buffMax*(SAFE_BUFFER_RATIO-MIN_BUFFER_RATIO));
    double delta_buffer_min = (double)(buffMax*(LOW_BUFFER_RATIO-MIN_BUFFER_RATIO));
    double temp_a = g_1 + 1.0/R_max;
    double temp_b = g_1 + SD*1.0/(delta_buffer_max*R_max);
    double temp_c = g_2 + 1.0/R_min;
    double temp_d = g_2 + SD*1.0/(delta_buffer_min*R_min);

    alpha = (double)(g_2 - g_1*temp_d/temp_b)/(temp_c - temp_a*temp_d/temp_b);
    beta = (double)(g_1 - alpha*temp_a)/temp_b;
    gamma = 1 - alpha - beta;

    Log.i("Minh", "WEIGHT_2: alpha: " + alpha + ". beta: " + beta + ". gamma: " + gamma);
  }

  void set_weights_v3() {
    set_omega();
    set_quality_function();

    double R_max_Mbps = getFormat(0).bitrate/1000000.0;
    double R_o_Mbps = getFormat(0).bitrate/1000000.0; //0.5*(getFormat(0).bitrate/1000000.0 + getFormat(1).bitrate/1000000.0);
    double thrp_optimal = getFormat(0).bitrate/1000000.0;
    double last_quality_1_Mbps = getFormat(1).bitrate/1000000.0;
    double optimal_delta_buffer_S = buffMax*(SAFE_BUFFER_RATIO-MIN_BUFFER_RATIO);//buffMax*(SAFE_BUFFER_RATIO-MIN_BUFFER_RATIO);

    double temp_beta_alpha = optimal_delta_buffer_S/SD;
    double temp_a = 2.0*Math.exp(1+ last_quality_1_Mbps/R_max_Mbps-2.0*R_o_Mbps/R_max_Mbps);
    double temp_b = (1 + temp_beta_alpha*SD/(optimal_delta_buffer_S))/thrp_optimal;

    denominator_exp = Math.exp(2*qualityLevelList.get(0)-2*qualityLevelList.get(length-1));
    alpha = 1.0/(1 + temp_beta_alpha + R_max_Mbps*temp_b*denominator_exp/temp_a);
    beta = temp_beta_alpha*alpha;
    gamma = 1 - alpha - beta;

    Log.i("Minh", "WEIGHT_3: alpha: " + alpha + ". beta: " + beta + ". gamma: " + gamma);
  }



  double getTotalCost_v3(int qualityIndex, double estimatedThroughputMbps, double currentbufferS, String networkType) {
    double totalCost;
    double bandwidthCost;
    double bufferCost;
    double qualityCost;
    double current_quality_level = qualityLevelList.get(qualityIndex);

    Format segmentFormat = getFormat(qualityIndex);

    double temp = segmentFormat.bitrate*1.0/(estimatedThroughputMbps*1000000.0);  // bitrate is in bps
    double bufferThreshold = MIN_BUFFER_RATIO*buffMax;

    double average_quality = 0;
    int downloaded_segments = averageThroughputBitps.size();
    int quality_window = Math.min(10, downloaded_segments);

    for (int i = 1; i <= quality_window; i++){
      average_quality += qualityLevelList.get(length - selectedQualityIndex.get(downloaded_segments-i));
    }
    average_quality = average_quality*1.0/quality_window;
    Log.i("Minh", "average quality: " + average_quality);

    bandwidthCost = omega * temp;
    bufferCost = temp*(SD*1.0/(currentbufferS - bufferThreshold));
//    qualityCost = Math.exp(qualityLevelList.get(0) + qualityLevelList.get(lastQualityIndex) - 2*current_quality_level)/denominator_exp;
    qualityCost = Math.exp(qualityLevelList.get(0) + average_quality - 2*current_quality_level)/denominator_exp;
    totalCost = alpha*bandwidthCost + beta*bufferCost + gamma*qualityCost;

    Log.i("MINH", "\n Costs of quality " + (length - qualityIndex) + "\t bitrate: " + getFormat(qualityIndex).bitrate/1000000.0 +
        "\n\t==> bandwidthCost: " + bandwidthCost +
        "\n\t==> bufferCost: " + bufferCost + "\n\t==> qualityCost: " + qualityCost);
    Log.i("MINH", "=======> Total cost of " + (length - qualityIndex) + ": " + totalCost);

    return totalCost;
  }

  private int SR_ABR_v3(long nowMs, long currentBufferUs, long playbackPositionUs) {
//    estimatedThroghputMbps = (double)(bandwidthProvider.getAllocatedBandwidth()/1000000.0); //Mbps
    estimatedThroghputMbps = (1 - margin) * Math.min(get_smooth_throughput(0.125), lastThroughputMbps);
    maxThroughputMbps = Math.max(estimatedThroghputMbps, maxThroughputMbps);
    Log.i("MINH", " -- ++ -- EstThrp: " + estimatedThroghputMbps + "Mbps. currentBuffer[s] " + currentBufferUs/1000000.0 + "\n");

    int selectedBitrate = 0;
    int lowestCost = Integer.MAX_VALUE;

//    if (selectedQualityIndex.size() < (int)buffLowThresholdS/SD + 1 &&
//        currentBufferUs <= buffLowThresholdS*1000000) { // in the startup phase
//      Log.i("Minh", "SR_v3: Startup phase. The first segments are downloaded by throughputbased");
//      return determineIdealSelectedIndex(nowMs);
//    }

    if (currentBufferUs <= buffLowThresholdS*1000000) { // if buffer < min threshold || this's the first segment
      return length-1;
    }

    int max_quality = length-1;

    if (estimatedThroghputMbps < maxThroughputMbps) {
      for (int i = length - 1; i >= 0 && getFormat(i).bitrate / 1000000.0 < maxThroughputMbps; i--) {
        if (getFormat(i).bitrate / 1000000.0 > estimatedThroghputMbps) {
          max_quality = i;
          break;
        }
        else {
          max_quality = i;
        }
      }
    }
    else {  // max bitrate but less than estimated thrp/max throughput because it is the first time the thrp is high like this. BE conservative.
      for (int i = 0; i < length; i++) {
        if (getFormat(i).bitrate / 1000000.0 < maxThroughputMbps) {
          max_quality = i;
          break;
        }
      }
    }

    for (int i = max_quality; i <= length-1; i++) {
      if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
        int currentTotalCost = (int) Math.round(100*getTotalCost_v3(i, estimatedThroghputMbps, (double)currentBufferUs/1000000.0, networkType));
        Log.i("Minh", "*** lowest cost: " + lowestCost +  " of quality " + (length - selectedBitrate) + " current Cost: " + currentTotalCost);
        if (currentTotalCost < lowestCost) {
          selectedBitrate = i;
          lowestCost = currentTotalCost;
        }
      }
    }

    return selectedBitrate;
  }

  private int SR_ABR_v4(long nowMs, long currentBufferUs, long playbackPositionUs) {
//    estimatedThroghputMbps = (double)(bandwidthProvider.getAllocatedBandwidth()/1000000.0); //Mbps
    double smooth_thrpMbps = get_smooth_throughput(0.125);
    estimatedThroghputMbps = Math.min(smooth_thrpMbps, lastThroughputMbps);
//    estimatedThroghputMbps = get_smooth_throughput(0.125);
//    maxThroughputMbps = Math.max(estimatedThroghputMbps, maxThroughputMbps);
    maxThroughputMbps = 0;
    int downloaded_segments = averageThroughputBitps.size();
    int throughput_window = Math.min(10, downloaded_segments);
    for (int i = 1; i <= throughput_window; i++){
      maxThroughputMbps += averageThroughputBitps.get(downloaded_segments - i)/1000000.0;
    }
    maxThroughputMbps = maxThroughputMbps*1.0/throughput_window;
    Log.i("MINH", " -- ++ -- EstThrp: " + estimatedThroghputMbps + "Mbps. currentBuffer[s] " + currentBufferUs/1000000.0 + " avg.thrp: " +maxThroughputMbps + "\n");

    int selectedBitrate = length-1;
    int lowestCost = Integer.MAX_VALUE;

//    if (selectedQualityIndex.size() < (int)buffLowThresholdS/SD + 1 &&
//        currentBufferUs <= buffLowThresholdS*1000000) { // in the startup phase
//      Log.i("Minh", "SR_v3: Startup phase. The first segments are downloaded by throughputbased");
//      for (int i = 0; i < length; i++) {
//        if (getFormat(i).bitrate*1.0 < (1-0.1)*bandwidthProvider.getAllocatedBandwidth()) {
//          selectedBitrate = i;
//          break;
//        }
//      }
//      return selectedBitrate;
//    }

    if (currentBufferUs <= buffLowThresholdS*1000000 && downloaded_segments < buffLowThresholdS/SD + 1) { // if buffer < min threshold || this's the first segment
      return length-1;
    }
    if (currentBufferUs <= buffLowThresholdS*1000000) {
      return length-1;
//      for (int i = 0; i < length; i++) {
//        if (getFormat(i).bitrate*1.0 < (1-0.1)*bandwidthProvider.getAllocatedBandwidth()) {
//          return i;
//        }
//      }
    }

    int max_quality = length-1;

    for (int i = 0; i < length; i++) {
      if (getFormat(i).bitrate / 1000000.0 < smooth_thrpMbps) {
        max_quality = i;
        break;
      }
//      else if ( currentBufferUs/1000000.0 > 0.5*buffMax && getFormat(i).bitrate / 1000000.0 > estimatedThroghputMbps) {
//        max_quality = i;
//      }
    }

    if (max_quality > length-2) { // 20210208: avoid choosing the lowest quality, same idea as BBA
      return max_quality;
    }

    for (int i = max_quality; i <= length-2; i++) {
      if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
        int currentTotalCost = (int)Math.round(100*getTotalCost_v3(i, estimatedThroghputMbps, (double)currentBufferUs/1000000.0, networkType));
        Log.i("Minh", "*** lowest cost: " + lowestCost +  " of quality " + (length - selectedBitrate) + " current Cost: " + currentTotalCost);
        if (currentTotalCost < lowestCost) {
          selectedBitrate = i;
          lowestCost = currentTotalCost;
        }
      }
    }

    return selectedBitrate;
  }

  void set_weights_2costs(){
    set_quality_function();
    double optimal_delta_buffer = (double)(buffMax*(0.5-0.2))/SD; // want to have max quality when the buffer is 0.5 buffer max. 0.2*Buffer_max = bufferThreshold for this buffer level 0.5
    double delta_bitrate = 0;
    double delta_quality = 0;
    double min_e_r = Double.MAX_VALUE;

    /** find big_gamma **/
    double A0 = (Math.exp(qualityLevelList.get(0) + qualityLevelList.get((int)(length-1)/2)))/denominator_exp;
    double A1 = SD/(optimal_delta_buffer*getFormat(0).bitrate/1000000.0);

    for (int i = 0; i < length-1; i ++){
      delta_bitrate = (getFormat(i).bitrate - getFormat(i+1).bitrate)/1000000.0;
      delta_quality = Math.exp(-2*qualityLevelList.get(i+1)) - Math.exp(-2*qualityLevelList.get(i));
      min_e_r = Math.min(delta_quality/delta_bitrate, min_e_r);
    }

    /** determine weights **/
    gamma = 1/(1 + (1+margin)*A0*min_e_r/A1);
    beta = 1 - gamma;


    Log.i("Minh", "NEW: beta: " + beta + ". gamma: " + gamma +
        "\nmin_e_r: " + min_e_r +
        "\ndelta_bufer: " + optimal_delta_buffer +
        "\nbuffMax = " + buffMax + "\tSD: " + SD);
  }
  public void get_video_name (String segmentUrl){ /** //192.168.0.104/ToS_youtube/480p_715kbps/tos_seg3.m4s **/
    segment_url =  segmentUrl.split("/")[3];
    Log.i("Minh", "get_segment_url: " + segment_url);
  }
  // MINH [Proposed ABR algorithm] - ADD - E

  // MINH [Compared ABR algorthms] - ADD - S
    // SARA ABR
  private int SARA_ABR(long nowMs, double currentBufferS, double lastThroughputbps) {
    int selectedIdx = lastQualityIndex;
    final double bufferI = (buffMax <= 20) ? SD : 2*SD; // From 26/01/2021 bufferI = SD (BuffSize = 20s), = 2SD (BuffSize = 40s). Before, bufferI = 2*SD
    final double bufferAlpha = buffMax*0.5;
    final double bufferBeta = buffMax;
    final int    SARA_SAMPLE_COUNT = 5; // # of segments for moving weighted average
    double available_buffer = currentBufferS - bufferI; // Atream player use this variable.

    saraEstimatedThroughputbps = 1000000.0*getWeightedHarmonicMeanThroughput_Mbps(selectedQualityBitrateBitps, saraLastThroughputMbpsList, SARA_SAMPLE_COUNT);
//    estimatedThroghputMbps = saraEstimatedThroughputbps/1000000.0;
    if (currentBufferS <= bufferI){
      Log.i("Minh", "\t\t SARA - 0");
      selectedIdx = length - 1;
    }
    else {
//      Log.i("Minh", "SARA_Wn/Hn:  Buffer: " + currentBufferS +
//                            "\n Last thrp [Mbps] " + lastThroughputbps/1000000.0 +
//                            "\n EstimatedThrp [Mbps] " + saraEstimatedThroughputbps/1000000.0 +
//                            "\n Estimated Download Time: " + getFormat(lastQualityIndex).bitrate*SD*1.0/saraEstimatedThroughputbps);
      if (getFormat(lastQualityIndex).bitrate*SD*1.0/saraEstimatedThroughputbps > available_buffer) {
        for (int i = lastQualityIndex+1; i < length; i++) {
          if (getFormat(i).bitrate*SD*1.0/saraEstimatedThroughputbps <= available_buffer) {
            selectedIdx = i;
            Log.i("Minh", "\t\t SARA - 1. bitrate = " + getFormat(selectedIdx).bitrate/1000000.0);
            break;
          }
          else {
            selectedIdx = length-1;
          }
        }
      }
      else if (available_buffer <= bufferAlpha) {
        if (lastQualityIndex == 0){
          selectedIdx = lastQualityIndex;
        }
        else {
          if (getFormat(lastQualityIndex-1).bitrate*SD*1.0/saraEstimatedThroughputbps < available_buffer)
            selectedIdx = lastQualityIndex-1;
          else
            selectedIdx = lastQualityIndex;
        }
        Log.i("Minh", "\t\t SARA - 2. bitrate = " + getFormat(selectedIdx).bitrate/1000000.0 + " thrp:" + saraEstimatedThroughputbps/1000000 + " delta_buf = " + (currentBufferS - bufferI));
      }
      else if (available_buffer <= bufferBeta) {
        if (lastQualityIndex == 0){
          selectedIdx = lastQualityIndex;
        }
        else {
          for (int i = 0; i <= lastQualityIndex; i++) {
            if (getFormat(i).bitrate*SD*1.0/saraEstimatedThroughputbps <= available_buffer) {
              selectedIdx = i;
              Log.i("Minh", "\t\t SARA - 3. bitrate = " + getFormat(selectedIdx).bitrate / 1000000.0
                  + " thrp:" + saraEstimatedThroughputbps / 1000000 + " delta_buf = " + (
                  currentBufferS - bufferI));
              break;
            } else {
              selectedIdx = lastQualityIndex;
            }
          }
        }
      }
      else if (available_buffer > bufferBeta) {
        for (int i = 0; i <= lastQualityIndex; i++) {
          if (getFormat(i).bitrate*SD*1.0/saraEstimatedThroughputbps <= (currentBufferS - bufferAlpha)) {
            selectedIdx = i;
            Log.i("Minh", "\t\t SARA - 4. bitrate = " + getFormat(selectedIdx).bitrate/1000000.0 + " thrp:" + saraEstimatedThroughputbps/1000000 + " delta_buf = " + (currentBufferS - bufferI));
            // TODO: currently, the sleep time is defined by exoplayer as default.
            break;
          }
          else {
            selectedIdx = lastQualityIndex;
          }
        }
      }
      else {
        Log.i("Minh", "\t\t SARA - 5");
        selectedIdx = lastQualityIndex;
      }
    }
    Log.i("Minh", "===> SARA Final selected quality: " + selectedIdx);
    return selectedIdx;
  }

  private double getWeightedHarmonicMeanThroughput_Mbps(List<Integer> selectedQualityBitrateBitps,
                                                        List<Double> lastThroughputMbpsList,
                                                        int num_samples) {
    Log.i("Minh", "Num of Selected Bitrate: " + selectedQualityBitrateBitps.size() +
                    "\t\t Num of last throughput: " + lastThroughputMbpsList.size());
    double estimatedThrougphut;
    int num_downloaded_segments = selectedQualityBitrateBitps.size();

    if (num_downloaded_segments == 0) {
      estimatedThrougphut = 1;
    } else {
      if (num_samples == 0) {
        if (saraLastThroughputMbpsList.get(num_downloaded_segments - 1) == 0)
          saraLastThroughputMbpsList.set(num_downloaded_segments - 1, 1.0); // for the first segment

        double lastbitrateMbps = (double) (
            selectedQualityBitrateBitps.get(selectedQualityBitrateBitps.size() - 1) / 1000000.0);
        saraSumWeight += lastbitrateMbps;
        saraSumWeightDividedThroughput += (double) (lastbitrateMbps / saraLastThroughputMbpsList
            .get(num_downloaded_segments - 1));
      } else {
        int final_samples = Math.min(num_samples, num_downloaded_segments);
        Log.i("Minh", "\t # of sample: " + final_samples );
        saraSumWeight = 0;
        saraSumWeightDividedThroughput = 0;

        for (int i = 1; i <= final_samples; i++) {
          double weight = (double) (
              selectedQualityBitrateBitps.get(num_downloaded_segments - i) / 1000000.0);
          saraSumWeight += weight;
          saraSumWeightDividedThroughput += (double) (weight / saraLastThroughputMbpsList
              .get(num_downloaded_segments - i));
        }
      }
      estimatedThrougphut = saraSumWeight/saraSumWeightDividedThroughput;
      Log.i("Minh", "Harmonic Mean: Est Thrp = " + estimatedThrougphut
          + "\n last bitrate [Mbps]: " + (selectedQualityBitrateBitps.get(selectedQualityBitrateBitps.size()-1)/1000000.0) +
                "\t Last thrp [Mbps]: " + saraLastThroughputMbpsList.get(num_downloaded_segments-1)
          + "\n SumWeight: " + saraSumWeight + "\t Sum w/d: " + saraSumWeightDividedThroughput);
    }

    return estimatedThrougphut;
  }

  // BBA-0
  private int BBA_ABR(long nowMs, double m_cur_buffS){
//    estimatedThroghputMbps = (double)(bandwidthProvider.getAllocatedBandwidth()/1000000.0); //Mbps
    final double BBA_rS = 0.2*buffMax;
    final double BBA_cuS = 0.7*buffMax;
    final double BBA_a = 1.0*(getFormat(0).bitrate - getFormat(length-1).bitrate)/BBA_cuS;
    final double BBA_b = getFormat(length-1).bitrate - BBA_rS*BBA_a;

    double  f_buff_value = 0;
    int     selectedQualityIndex = length-1;
    int     m_quality_plus = 0;
    int     m_quality_subtract = 0;
    f_buff_value = BBA_a*m_cur_buffS + BBA_b; // (kbps)
//    Log.i("Minh", "----- Current Bitrate fitting buff: " + f_buff_value + " current_buff: " + m_cur_buffS);
    //////////////////////////////////////////////////////////////////////////
    if (lastQualityIndex == 0)
      m_quality_plus = lastQualityIndex;
    else
      m_quality_plus = lastQualityIndex-1;
    //////////////////////////////////////////////////////////////////////////
    if (lastQualityIndex == length-1)
      m_quality_subtract = length-1;
    else
      m_quality_subtract = lastQualityIndex+1;
    //////////////////////////////////////////////////////////////////////////
    if (m_cur_buffS <= BBA_rS){
      selectedQualityIndex = length-1;
    }
    else if (m_cur_buffS >= (BBA_rS + BBA_cuS)){
      selectedQualityIndex = 0;
    }
    else if (f_buff_value >= getFormat(m_quality_plus).bitrate){
      for (int i = 0; i < length; i++){
        if (getFormat(i).bitrate < f_buff_value){
          selectedQualityIndex = i;
          break;
        }
      }
    }
    else if (f_buff_value <= getFormat(m_quality_subtract).bitrate){
      for (int i = length-1; i >= 0; i--){
        if (getFormat(i).bitrate > f_buff_value){
          selectedQualityIndex = i;
          break;
        }
      }
    }
    else {
      selectedQualityIndex = lastQualityIndex;
    }
    //////////////////////////////////////////////////////////////////////////
    return selectedQualityIndex;
  }

  // EKREM: Pensieve ABR
  private static String assetFilePath(Context context, String assetName) throws IOException {
    // we need the absolute path of input for model because Pytorch Mobile expects the absolute path
    File file = new File(context.getFilesDir(), assetName);
    if (file.exists() && file.length() > 0) {
      return file.getAbsolutePath();
    }

    // This is to get the input file
    try (InputStream is = context.getAssets().open(assetName)) {
      try (OutputStream os = new FileOutputStream(file)) {
        byte[] buffer = new byte[4 * 1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
          os.write(buffer, 0, read);
        }
        os.flush();
      }
      return file.getAbsolutePath();
    }
  }

  private int PENSIEVE_ABR(long nowMs, long currentBufferUs) {
    // Load the model here
//    Module model = Module.load(assetFilePath(this, "model_own.pt"));
//    double estimatedThroghputMbps = (double) bandwidthProvider.getAllocatedBandwidth()/1000000; //Mbps
//
//    int selectedBitrate = 0;
//    /**
//     * State is 6 vectors, each vector has 8 values
//     * 1 -> Last Bitrate/Quality level
//     * 2 -> Current Buffer level
//     * 3 -> Network throughput for past 8 chunks
//     * 4 -> Download time for past 8 chunks
//     * 5 -> Availabe sizes for next chunk (in MB)
//     * 6 -> Number of chunks remained in the video
//     * [[0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.1744],
//     *  [0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.4000],
//     *  [0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.1126],
//     *  [0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.3999],
//     *  [0.1556, 0.3989, 0.6111, 0.9577, 1.4318, 2.1231, 0.0000, 0.0000],
//     *  [0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.9792]
//     */
//    // Prepare the state here, we might need to find a way to keep track of past 8 chunks
//    double [] lastBitrate = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
//    double [] buffer = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
//    double [] throughput = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
//    double [] down_time = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
//    double [] next_sizes = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
//    double [] number_of_chunks = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
//    long[] shape = new long[]{1, lastBitrate.length};
//
//    Tensor s_bitrate = Tensor.fromBlob(lastBitrate, shape);
//    Tensor s_buffer = Tensor.fromBlob(buffer, shape);
//    Tensor s_through = Tensor.fromBlob(throughput, shape);
//    Tensor s_down = Tensor.fromBlob(down_time, shape);
//    Tensor s_next = Tensor.fromBlob(next_sizes, shape);
//    Tensor s_chunks = Tensor.fromBlob(number_of_chunks, shape);
//
//    Tensor [] stateTensor = {s_bitrate, s_buffer, s_through, s_down, s_next, s_chunks};
//
//    // pass the state to the model and get the prediction
//    Tensor predictedBitrate = model.forward(IValue.from(Arrays.toString(stateTensor))).toTensor();
//    int [] bitratePrediction = predictedBitrate.getDataAsIntArray();
    int selectedBitrate = 0; //bitratePrediction[0];

    return selectedBitrate;
  }

  private long get_percentile (List<Long> throughput_list, double percentile) {
    Collections.sort(throughput_list);
    int index = (int) Math.ceil(percentile / 100.0 * throughput_list.size());
    return throughput_list.get(index-1);
  }
  private int SQUAD_ABR(long nowMs, long currentBufferus) {
    int next_quality_idx = 0;
    int next_rate = 0;
    int num_downloaded_segments = selectedQualityBitrateBitps.size();

    // Determine the play state
    if (num_downloaded_segments < 5 ) {
      play_state = SQUAD_PLAY_STATE.INIT;
    }
    else {
      if (play_state == SQUAD_PLAY_STATE.INIT ||
          (play_state == SQUAD_PLAY_STATE.SLOW_START &&
              2*(length - lastQualityIndex) < length && last_dl_time < 2.0*SD)) {
          play_state = SQUAD_PLAY_STATE.SLOW_START;
      }
      else {
        play_state = SQUAD_PLAY_STATE.STEADY;
      }
    }

    Log.i("MINH", "====> SQUAD: play_state = " + play_state);
    switch (play_state) {
      case INIT:
        // TODO: init: a slow start of segment quality: the first W1=5 segments have the lowest quality.
        next_quality_idx = length-1;
        break;
      case SLOW_START:
        // TODO: from segment (W1 + 1), double the quality level until the highest possible quality.
        next_quality_idx = length - 2*(length - lastQualityIndex); //length - 2*(length-1 - lastQualityIndex);
        break;
      case STEADY:
        // TODO: steady state: slow start is finished or Download time of last segment > 2*SegmentDuration.
        double br_spectrum;
        List<Double> spectrums;
        List<Double> next_dl_times;
        List<Double> br_spectrum_key_weighted_spectrum = new ArrayList<>();
        List<Integer> br_spectrum_value_bitrate_bps = new ArrayList<>();

        for (int i = length-1; i >= 0; i--) { //from min to max quality
          double next_dl_rate_bps = bandwidthProvider.getAllocatedBandwidth();
          double br_weight = Math.pow(getFormat(length-2).bitrate*1.0/getFormat(0).bitrate, 1.0/(i+1));
          int considered_quality_idx = length-1;
          Log.i("MINH", "###########################");
          Log.i("MINH", "SQUAD: quality " + (length-i) + " next_dl_rate_kbps: " + next_dl_rate_bps/1000.0 + "\t br_weight: " + br_weight);

          if(getFormat(i).bitrate < next_dl_rate_bps)
            considered_quality_idx = i;
          else
            continue;

          // found a bitrate < next_dl_rate
          int bitrate_window4 = 4;
          int bitrate_window8 = 8;
          int bitrate_window16 = 16;
          double weighted_spectrum = br_weight*SQUAD_get_spectrum(bitrate_window4, considered_quality_idx) +
                                     br_weight*SQUAD_get_spectrum(bitrate_window8, considered_quality_idx) +
                                     br_weight*SQUAD_get_spectrum(bitrate_window16, considered_quality_idx);
          double next_dl_time = getFormat(considered_quality_idx).bitrate*1.0/next_dl_rate_bps;
          br_spectrum_key_weighted_spectrum.add(weighted_spectrum);
          br_spectrum_value_bitrate_bps.add(getFormat(considered_quality_idx).bitrate);
        }

        if (br_spectrum_value_bitrate_bps.size() == 0) {
          Log.i("MINH", "//////////// SQUAD -1- chooses quality " + (length-lastQualityIndex));
          return lastQualityIndex;
        }

        // sort br_spectrum_value_bitrate_bps from max to min
        List<Integer> next_spectrum_rates_bps = br_spectrum_value_bitrate_bps;
        Collections.reverse(next_spectrum_rates_bps);
        Log.i("MINH", "SQUAD: test next_spectrum_rates_bps.last (min): " + next_spectrum_rates_bps.get(next_spectrum_rates_bps.size()-1) +
                               " next_spectrum_rates_bps.first(max): " + next_spectrum_rates_bps.get(0));
        next_rate = next_spectrum_rates_bps.get(0); // max value;

        if (next_rate < getFormat(lastQualityIndex).bitrate) {
          long next_dl_rate_bps = getFormat(lastQualityIndex).bitrate; //get_percentile(averageThroughputBitps, 20);
          Log.i("MINH", "======== DECREASE CASE: net_rateMbps: " + next_rate/1000000.0 +
                                "\n\tLast rate: " + getFormat(lastQualityIndex).bitrate/1000000.0 +
                                "\n\tnext_dl_rate_Mps: " + next_dl_rate_bps/1000000.0);
//          for (int i = length-1; i >= 0; i--) { //from min to max quality
//            int considered_quality_idx = length - 1;
//            double br_weight = Math.pow(getFormat(length - 2).bitrate * 1.0 / getFormat(0).bitrate,
//                1.0 / (i + 1));
//
//            if (getFormat(i).bitrate < next_dl_rate_bps)
//              considered_quality_idx = i;
//            else
//              continue;
//
//            int bitrate_window4 = 4;
//            int bitrate_window8 = 8;
//            int bitrate_window16 = 16;
//            double weighted_spectrum =
//                br_weight * SQUAD_get_spectrum(bitrate_window4, considered_quality_idx) +
//                    br_weight * SQUAD_get_spectrum(bitrate_window8, considered_quality_idx) +
//                    br_weight * SQUAD_get_spectrum(bitrate_window16, considered_quality_idx);
//            double next_dl_time =
//                getFormat(considered_quality_idx).bitrate * 1.0 / next_dl_rate_bps;
//            br_spectrum_key_weighted_spectrum.add(weighted_spectrum);
//            br_spectrum_value_bitrate_bps.add(getFormat(considered_quality_idx).bitrate);
//          }
//
//          if (br_spectrum_value_bitrate_bps.size() == 0) {
//            Log.i("MINH", "//////////// SQUAD -2- chooses quality " + (length-lastQualityIndex));
//            return lastQualityIndex;
//          }
//
//          next_spectrum_rates_bps.clear();
//          next_spectrum_rates_bps = br_spectrum_value_bitrate_bps;
//          Log.i("MINH", "br_spectrum_value_bitrate_bps.size(): " + br_spectrum_value_bitrate_bps.size() + "\nnext_spectrum_rates_bps.size() + " + next_spectrum_rates_bps.size());
//          Collections.reverse(next_spectrum_rates_bps);
//          Log.i("MINH", "SQUAD: RECAL. test next_spectrum_rates_bps.last (min): " + next_spectrum_rates_bps.get(next_spectrum_rates_bps.size()-1) +
//              " next_spectrum_rates_bps.first(max): " + next_spectrum_rates_bps.get(0));
//
//          for (int i = 0; i < next_spectrum_rates_bps.size(); i++){
//            if(next_spectrum_rates_bps.get(i)/next_dl_rate_bps > 2) {
//              Log.i("MINH", "Download rate of quality " + i + " > 2");
//              next_spectrum_rates_bps.remove(i);
//            }
//          }
//          if (next_spectrum_rates_bps.size() == 0) {
//            next_rate = getFormat(0).bitrate;
//          }
//          else
//          next_rate = next_spectrum_rates_bps.get(0); // max value;
          double ch_s = buffMax*0.5;
          if (currentBufferus/1000000.0 > ch_s &&
              currentBufferus/1000000.0 + SD * (1 - next_rate*1.0/next_dl_rate_bps) > ch_s) {
            next_rate = (int)(0.5*(getFormat(lastQualityIndex).bitrate + next_rate));
            Log.i("MINH", "\t===========> nex_rate: " + next_rate/1000000.0 + " last_bitrate: " + getFormat(lastQualityIndex).bitrate/1000000.0);
            for (int i = length-1; i >= 0; i--) {
              if (getFormat(i).bitrate > next_rate) {
                next_rate = getFormat(i).bitrate;
                Log.i("MINH", "\t===========> FINALLY nex_rate: " + next_rate/1000000.0);
                break;
              }
            }
          }

        }

        // finally, find the index of quality to return
        for (int i = 0; i < length; i ++){
          if (next_rate == getFormat(i).bitrate) {
            next_quality_idx = i;
            break;
          }
        }
        break;
      default:
        next_quality_idx = lastQualityIndex;
        break;
    }
    Log.i("MINH", "//////////// SQUAD FINALLY chooses quality " + (length-next_quality_idx));
    return next_quality_idx;
  }

  private double SQUAD_get_avg_bitrateKbps(List<Long> bitrate_bpsList, double stall_duration_S) {
    int numSegments = bitrate_bpsList.size();
    int numStallSegment = (int)(stall_duration_S*1.0/SD);
    double sumBitrateKbps = 0;

    for (int i = 0; i < numSegments; i ++) {
      sumBitrateKbps += (double)(bitrate_bpsList.get(i)/1000.0);
    }

    return (double)(sumBitrateKbps*1.0/(numSegments + numStallSegment));
  }
  private double SQUAD_get_spectrum(int window_size, int next_quality) {
    Log.i("MINH", " **** Go to SQUAD_get_spectrum. window_size: " + window_size + ". Next_quality: " + (length-next_quality));
    List<Double> chosen_bitrate_Mbps_history_list = new ArrayList<>();
    int num_downloaded_segment = selectedQualityBitrateBitps.size();
    int squad_num_switch = 0;
    double squad_sum_bitrateKbps = 0;
    double squad_spectrum = 0;

    double zh = 0;
    int zt = 0;
    int zi = 0;
    double second_half_total = 0;
    double second_half = 0;

    if (num_downloaded_segment <= 1) {
      squad_spectrum = 0;
    }
    else {
      int actual_window_size = Math.min(window_size, num_downloaded_segment); // in case window_size > # of downloaded segments.

      for (int i = actual_window_size; i >= 1; i--) {
        chosen_bitrate_Mbps_history_list.add((double)(selectedQualityBitrateBitps.get(num_downloaded_segment-i)/1000000.0));
      }

      chosen_bitrate_Mbps_history_list.add(getFormat(next_quality).bitrate/1000000.0);

      for (int i = 1; i <= actual_window_size; i++) {  // now we have (actual_window_size + 1) quality history
        for (int j = 0; j < actual_window_size; j++) {
          zi = 0;
          if (chosen_bitrate_Mbps_history_list.get(j)
              .equals(chosen_bitrate_Mbps_history_list.get(j + 1))){
            zh += chosen_bitrate_Mbps_history_list.get(j);
            zt ++;
          }
          if (zi == 0)
            zi = 1;
        }

        second_half = Math.pow(chosen_bitrate_Mbps_history_list.get(i) - (1.0/zi)*zh, 2);
        second_half_total += second_half;
        zt += 1;
      }

      squad_spectrum = zt*second_half;

      Log.i("Minh", "SQUAD: sum of switch bitrate: " + zh + "\t # of switches: " + zt + "\t second_half_total: " + second_half_total);

    }
    Log.i("Minh", "SQUAD: squad_spectrum: " + squad_spectrum);
    return squad_spectrum;
  }
// MINH [Compared ABR algorthms] - ADD - E

  private long minDurationForQualityIncreaseUs(long availableDurationUs) {
    boolean isAvailableDurationTooShort = availableDurationUs != C.TIME_UNSET
        && availableDurationUs <= minDurationForQualityIncreaseUs;
    return isAvailableDurationTooShort
        ? (long) (availableDurationUs * bufferedFractionToLiveEdgeForQualityIncrease)
        : minDurationForQualityIncreaseUs;
  }

  /** Provides the allocated bandwidth. */
  private interface BandwidthProvider {

    /** Returns the allocated bitrate. */
    long getAllocatedBandwidth();
    // Minh [get estimated bandwidth] - ADD - S
    /** Returns the estimated bandwidth. */
    long getEstimatedBandwidth();
    // Minh [get estimated bandwidth] - ADD - E
  }

  private static final class DefaultBandwidthProvider implements BandwidthProvider {

    private final BandwidthMeter bandwidthMeter;
    private final float bandwidthFraction;
    private final long reservedBandwidth;

    @Nullable private long[][] allocationCheckpoints;

    /* package */ DefaultBandwidthProvider(
        BandwidthMeter bandwidthMeter, float bandwidthFraction, long reservedBandwidth) {
      this.bandwidthMeter = bandwidthMeter;
      this.bandwidthFraction = bandwidthFraction;
      this.reservedBandwidth = reservedBandwidth;
    }

    @Override
    public long getAllocatedBandwidth() {
      long totalBandwidth = (long) (bandwidthMeter.getBitrateEstimate() * bandwidthFraction);
//      Log.i("MINH", "bandwidthFraction: " + bandwidthFraction);
      long allocatableBandwidth = max(0L, totalBandwidth - reservedBandwidth);
      if (allocationCheckpoints == null) {
        return allocatableBandwidth;
      }
      int nextIndex = 1;
      while (nextIndex < allocationCheckpoints.length - 1
          && allocationCheckpoints[nextIndex][0] < allocatableBandwidth) {
        nextIndex++;
      }
      long[] previous = allocationCheckpoints[nextIndex - 1];
      long[] next = allocationCheckpoints[nextIndex];
      float fractionBetweenCheckpoints =
          (float) (allocatableBandwidth - previous[0]) / (next[0] - previous[0]);
      return previous[1] + (long) (fractionBetweenCheckpoints * (next[1] - previous[1]));
    }

    // Minh [get estimated bandwidth] - ADD - S
    public long getEstimatedBandwidth(){
      return bandwidthMeter.getBitrateEstimate();
    }
    // Minh [get estimated bandwidth] - ADD - E

    /* package */ void experimentalSetBandwidthAllocationCheckpoints(
        long[][] allocationCheckpoints) {
      Assertions.checkArgument(allocationCheckpoints.length >= 2);
      this.allocationCheckpoints = allocationCheckpoints;
    }
  }

  /**
   * Returns allocation checkpoints for allocating bandwidth between multiple adaptive track
   * selections.
   *
   * @param trackBitrates Array of [selectionIndex][trackIndex] -> trackBitrate.
   * @return Array of allocation checkpoints [selectionIndex][checkpointIndex][2] with [0]=total
   *     bandwidth at checkpoint and [1]=allocated bandwidth at checkpoint.
   */
  private static long[][][] getAllocationCheckpoints(long[][] trackBitrates) {
    // Algorithm:
    //  1. Use log bitrates to treat all resolution update steps equally.
    //  2. Distribute switch points for each selection equally in the same [0.0-1.0] range.
    //  3. Switch up one format at a time in the order of the switch points.
    double[][] logBitrates = getLogArrayValues(trackBitrates);
    double[][] switchPoints = getSwitchPoints(logBitrates);

    // There will be (count(switch point) + 3) checkpoints:
    // [0] = all zero, [1] = minimum bitrates, [2-(end-1)] = up-switch points,
    // [end] = extra point to set slope for additional bitrate.
    int checkpointCount = countArrayElements(switchPoints) + 3;
    long[][][] checkpoints = new long[logBitrates.length][checkpointCount][2];
    int[] currentSelection = new int[logBitrates.length];
    setCheckpointValues(checkpoints, /* checkpointIndex= */ 1, trackBitrates, currentSelection);
    for (int checkpointIndex = 2; checkpointIndex < checkpointCount - 1; checkpointIndex++) {
      int nextUpdateIndex = 0;
      double nextUpdateSwitchPoint = Double.MAX_VALUE;
      for (int i = 0; i < logBitrates.length; i++) {
        if (currentSelection[i] + 1 == logBitrates[i].length) {
          continue;
        }
        double switchPoint = switchPoints[i][currentSelection[i]];
        if (switchPoint < nextUpdateSwitchPoint) {
          nextUpdateSwitchPoint = switchPoint;
          nextUpdateIndex = i;
        }
      }
      currentSelection[nextUpdateIndex]++;
      setCheckpointValues(checkpoints, checkpointIndex, trackBitrates, currentSelection);
    }
    for (long[][] points : checkpoints) {
      points[checkpointCount - 1][0] = 2 * points[checkpointCount - 2][0];
      points[checkpointCount - 1][1] = 2 * points[checkpointCount - 2][1];
    }
    return checkpoints;
  }

  /** Converts all input values to Math.log(value). */
  private static double[][] getLogArrayValues(long[][] values) {
    double[][] logValues = new double[values.length][];
    for (int i = 0; i < values.length; i++) {
      logValues[i] = new double[values[i].length];
      for (int j = 0; j < values[i].length; j++) {
        logValues[i][j] = values[i][j] == Format.NO_VALUE ? 0 : Math.log(values[i][j]);
      }
    }
    return logValues;
  }

  /**
   * Returns idealized switch points for each switch between consecutive track selection bitrates.
   *
   * @param logBitrates Log bitrates with [selectionCount][formatCount].
   * @return Linearly distributed switch points in the range of [0.0-1.0].
   */
  private static double[][] getSwitchPoints(double[][] logBitrates) {
    double[][] switchPoints = new double[logBitrates.length][];
    for (int i = 0; i < logBitrates.length; i++) {
      switchPoints[i] = new double[logBitrates[i].length - 1];
      if (switchPoints[i].length == 0) {
        continue;
      }
      double totalBitrateDiff = logBitrates[i][logBitrates[i].length - 1] - logBitrates[i][0];
      for (int j = 0; j < logBitrates[i].length - 1; j++) {
        double switchBitrate = 0.5 * (logBitrates[i][j] + logBitrates[i][j + 1]);
        switchPoints[i][j] =
            totalBitrateDiff == 0.0 ? 1.0 : (switchBitrate - logBitrates[i][0]) / totalBitrateDiff;
      }
    }
    return switchPoints;
  }

  /** Returns total number of elements in a 2D array. */
  private static int countArrayElements(double[][] array) {
    int count = 0;
    for (double[] subArray : array) {
      count += subArray.length;
    }
    return count;
  }

  /**
   * Sets checkpoint bitrates.
   *
   * @param checkpoints Output checkpoints with [selectionIndex][checkpointIndex][2] where [0]=Total
   *     bitrate and [1]=Allocated bitrate.
   * @param checkpointIndex The checkpoint index.
   * @param trackBitrates The track bitrates with [selectionIndex][trackIndex].
   * @param selectedTracks The indices of selected tracks for each selection for this checkpoint.
   */
  private static void setCheckpointValues(
      long[][][] checkpoints, int checkpointIndex, long[][] trackBitrates, int[] selectedTracks) {
    long totalBitrate = 0;
    for (int i = 0; i < checkpoints.length; i++) {
      checkpoints[i][checkpointIndex][1] = trackBitrates[i][selectedTracks[i]];
      totalBitrate += checkpoints[i][checkpointIndex][1];
    }
    for (long[][] points : checkpoints) {
      points[checkpointIndex][0] = totalBitrate;
    }
  }
}
