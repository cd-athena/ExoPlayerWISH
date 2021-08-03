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
package com.google.android.exoplayer2.demo;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackPreparer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsCollector;
import com.google.android.exoplayer2.analytics.PlaybackStats;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer2.offline.DownloadRequest;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ads.AdsLoader;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.DebugTextViewHelper;
import com.google.android.exoplayer2.ui.StyledPlayerControlView;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.util.ErrorMessageProvider;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.Util;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

//MINH [ADD a function that write log file to the SD card] - ADD - S
//MINH [ADD a function that write log file to the SD card] - ADD - E

/** An activity that plays media using {@link SimpleExoPlayer}. */
public class PlayerActivity extends AppCompatActivity
    implements OnClickListener, PlaybackPreparer, StyledPlayerControlView.VisibilityListener {

  // Saved instance state keys.
  // Minh [get permission to create to folder] - ADD - S
  public static final int MULTIPLE_PERMISSIONS = 10;
  String[] permissions= new String[]{
      Manifest.permission.WRITE_EXTERNAL_STORAGE,
      Manifest.permission.WRITE_EXTERNAL_STORAGE
  };

  public static final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1;
  // Minh [get permission to create to folder] - ADD - E

  private static final String KEY_TRACK_SELECTOR_PARAMETERS = "track_selector_parameters";
  private static final String KEY_WINDOW = "window";
  private static final String KEY_POSITION = "position";
  private static final String KEY_AUTO_PLAY = "auto_play";

  private static final CookieManager DEFAULT_COOKIE_MANAGER;

  static {
    DEFAULT_COOKIE_MANAGER = new CookieManager();
    DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
  }

  protected StyledPlayerView playerView;
  protected LinearLayout debugRootView;
  protected TextView debugTextView;
  protected SimpleExoPlayer player;

  private boolean isShowingTrackSelectionDialog;
  private Button selectTracksButton;
  private DataSource.Factory dataSourceFactory;
  private List<MediaItem> mediaItems;
  private DefaultTrackSelector trackSelector;
  private DefaultTrackSelector.Parameters trackSelectorParameters;
  private DebugTextViewHelper debugViewHelper;
  private TrackGroupArray lastSeenTrackGroupArray;
  private boolean startAutoPlay;
  private int startWindow;
  private long startPosition;
  //MINH [ADD a function that write log file to the SD card] - ADD - S
  private AdaptiveTrackSelection adaptiveTrackSelection;
  private PlaybackStats playbackStats;
  //MINH [ADD a function that write log file to the SD card] - ADD - E

  // Fields used only for ad playback.

  private AdsLoader adsLoader;
  private Uri loadedAdTagUri;

  // Activity lifecycle

  // Minh [Get battery info] - ADD - S
  private BroadcastReceiver batInfoReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      AdaptiveTrackSelection.batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
      Log.i("Minh", "Battery level: " + AdaptiveTrackSelection.batteryLevel);
    }
  };
  // Minh [Get battery info] - ADD - E

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dataSourceFactory = DemoUtil.getDataSourceFactory(/* context= */ this);
    if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
      CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
    }

    setContentView();
    debugRootView = findViewById(R.id.controls_root);
    debugTextView = findViewById(R.id.debug_text_view);
    selectTracksButton = findViewById(R.id.select_tracks_button);
    selectTracksButton.setOnClickListener(this);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();

    if (savedInstanceState != null) {
      trackSelectorParameters = savedInstanceState.getParcelable(KEY_TRACK_SELECTOR_PARAMETERS);
      startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY);
      startWindow = savedInstanceState.getInt(KEY_WINDOW);
      startPosition = savedInstanceState.getLong(KEY_POSITION);
    } else {
      DefaultTrackSelector.ParametersBuilder builder =
          new DefaultTrackSelector.ParametersBuilder(/* context= */ this);
      trackSelectorParameters = builder.build();
      clearStartPosition();
    }
    // Minh [Get battery info] - ADD - S
//    this.registerReceiver(this.batInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    // Minh [Get battery info] - ADD - E
  }


  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    releasePlayer();
    releaseAdsLoader();
    clearStartPosition();
    setIntent(intent);
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Util.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Util.SDK_INT <= 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Util.SDK_INT <= 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Util.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    releaseAdsLoader();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    updateTrackSelectorParameters();
    updateStartPosition();
    outState.putParcelable(KEY_TRACK_SELECTOR_PARAMETERS, trackSelectorParameters);
    outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay);
    outState.putInt(KEY_WINDOW, startWindow);
    outState.putLong(KEY_POSITION, startPosition);
  }

  // Activity input

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // See whether the player view wants to handle media or DPAD keys events.
    return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  // OnClickListener methods

  @Override
  public void onClick(View view) {
    if (view == selectTracksButton
        && !isShowingTrackSelectionDialog
        && TrackSelectionDialog.willHaveContent(trackSelector)) {
      isShowingTrackSelectionDialog = true;
      TrackSelectionDialog trackSelectionDialog =
          TrackSelectionDialog.createForTrackSelector(
              trackSelector,
              /* onDismissListener= */ dismissedDialog -> isShowingTrackSelectionDialog = false);
      trackSelectionDialog.show(getSupportFragmentManager(), /* tag= */ null);
    }
  }

  // PlaybackPreparer implementation

  @Override
  public void preparePlayback() {
    player.prepare();
  }

  // PlayerControlView.VisibilityListener implementation

  @Override
  public void onVisibilityChange(int visibility) {
    debugRootView.setVisibility(visibility);
  }

  // Internal methods

  protected void setContentView() {
    setContentView(R.layout.player_activity);
  }

  /** @return Whether initialization was successful. */
  protected boolean initializePlayer() {
    if (player == null) {
      Intent intent = getIntent();

      mediaItems = createMediaItems(intent);
      if (mediaItems.isEmpty()) {
        return false;
      }

      boolean preferExtensionDecoders =
          intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false);
      RenderersFactory renderersFactory =
          DemoUtil.buildRenderersFactory(/* context= */ this, preferExtensionDecoders);
      MediaSourceFactory mediaSourceFactory =
          new DefaultMediaSourceFactory(dataSourceFactory)
              .setAdsLoaderProvider(this::getAdsLoader)
              .setAdViewProvider(playerView);

      trackSelector = new DefaultTrackSelector(/* context= */ this);
      trackSelector.setParameters(trackSelectorParameters);
      lastSeenTrackGroupArray = null;
      player =
          new SimpleExoPlayer.Builder(/* context= */ this, renderersFactory)
              .setMediaSourceFactory(mediaSourceFactory)
              .setTrackSelector(trackSelector)
              .build();
      player.addListener(new PlayerEventListener());
      player.addAnalyticsListener(new EventLogger(trackSelector));
      player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
      player.setPlayWhenReady(startAutoPlay);
      playerView.setPlayer(player);
      playerView.setPlaybackPreparer(this);
      debugViewHelper = new DebugTextViewHelper(player, debugTextView);
      debugViewHelper.start();
    }
    boolean haveStartPosition = startWindow != C.INDEX_UNSET;
    if (haveStartPosition) {
      player.seekTo(startWindow, startPosition);
    }
    player.setMediaItems(mediaItems, /* resetPosition= */ !haveStartPosition);
    player.prepare();
    updateButtonVisibility();
    return true;
  }

  private List<MediaItem> createMediaItems(Intent intent) {
    String action = intent.getAction();
    boolean actionIsListView = IntentUtil.ACTION_VIEW_LIST.equals(action);
    if (!actionIsListView && !IntentUtil.ACTION_VIEW.equals(action)) {
      showToast(getString(R.string.unexpected_intent_action, action));
      finish();
      return Collections.emptyList();
    }

    List<MediaItem> mediaItems =
        createMediaItems(intent, DemoUtil.getDownloadTracker(/* context= */ this));
    boolean hasAds = false;
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);

      if (!Util.checkCleartextTrafficPermitted(mediaItem)) {
        showToast(R.string.error_cleartext_not_permitted);
        return Collections.emptyList();
      }
      if (Util.maybeRequestReadExternalStoragePermission(/* activity= */ this, mediaItem)) {
        // The player will be reinitialized if the permission is granted.
        return Collections.emptyList();
      }

      MediaItem.DrmConfiguration drmConfiguration =
          checkNotNull(mediaItem.playbackProperties).drmConfiguration;
      if (drmConfiguration != null) {
        if (Util.SDK_INT < 18) {
          showToast(R.string.error_drm_unsupported_before_api_18);
          finish();
          return Collections.emptyList();
        } else if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.uuid)) {
          showToast(R.string.error_drm_unsupported_scheme);
          finish();
          return Collections.emptyList();
        }
      }
      hasAds |= mediaItem.playbackProperties.adTagUri != null;
    }
    if (!hasAds) {
      releaseAdsLoader();
    }
    return mediaItems;
  }

  private AdsLoader getAdsLoader(Uri adTagUri) {
    if (mediaItems.size() > 1) {
      showToast(R.string.unsupported_ads_in_playlist);
      releaseAdsLoader();
      return null;
    }
    if (!adTagUri.equals(loadedAdTagUri)) {
      releaseAdsLoader();
      loadedAdTagUri = adTagUri;
    }
    // The ads loader is reused for multiple playbacks, so that ad playback can resume.
    if (adsLoader == null) {
      adsLoader = new ImaAdsLoader.Builder(/* context= */ this).build();
    }
    adsLoader.setPlayer(player);
    return adsLoader;
  }

  protected void releasePlayer() {
    if (player != null) {
      updateTrackSelectorParameters();
      updateStartPosition();
      debugViewHelper.stop();
      debugViewHelper = null;
      player.release();
      player = null;
      mediaItems = Collections.emptyList();
      trackSelector = null;
    }
    if (adsLoader != null) {
      adsLoader.setPlayer(null);
    }
  }

  private void releaseAdsLoader() {
    if (adsLoader != null) {
      adsLoader.release();
      adsLoader = null;
      loadedAdTagUri = null;
      playerView.getOverlayFrameLayout().removeAllViews();
    }
  }

  private void updateTrackSelectorParameters() {
    if (trackSelector != null) {
      trackSelectorParameters = trackSelector.getParameters();
    }
  }

  private void updateStartPosition() {
    if (player != null) {
      startAutoPlay = player.getPlayWhenReady();
      startWindow = player.getCurrentWindowIndex();
      startPosition = Math.max(0, player.getContentPosition());
    }
  }

  protected void clearStartPosition() {
    startAutoPlay = true;
    startWindow = C.INDEX_UNSET;
    startPosition = C.TIME_UNSET;
  }

  // User controls

  private void updateButtonVisibility() {
    selectTracksButton.setEnabled(
        player != null && TrackSelectionDialog.willHaveContent(trackSelector));
  }

  private void showControls() {
    debugRootView.setVisibility(View.VISIBLE);
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

  private static boolean isBehindLiveWindow(ExoPlaybackException e) {
    if (e.type != ExoPlaybackException.TYPE_SOURCE) {
      return false;
    }
    Throwable cause = e.getSourceException();
    while (cause != null) {
      if (cause instanceof BehindLiveWindowException) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private class PlayerEventListener implements Player.EventListener {

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      if (playbackState == Player.STATE_ENDED) {
        //MINH [ADD a function that write log file to the SD card] - ADD - S
        writeLogFile();
        //MINH [ADD a function that write log file to the SD card] - ADD - E
        showControls();
      }
      updateButtonVisibility();
    }

    //MINH [ADD a function that write log file to the SD card] - ADD - S
    private double getYinQoE(List<Integer> qualityBitrate_bps, double stallDurationS, double startupDelayS) {
      double sigma_1 = 1;
      double sigma_2 = AdaptiveTrackSelection.maxBitrateKbps;
      double sigma_3 = AdaptiveTrackSelection.maxBitrateKbps;

      double YinQoE = -sigma_2*stallDurationS - sigma_3*startupDelayS;
      int numOfSegments = qualityBitrate_bps.size();

      for (int i = 0; i < numOfSegments-1; i++) {
        YinQoE += (double)(qualityBitrate_bps.get(i) - sigma_1*Math.abs(qualityBitrate_bps.get(i+1) - qualityBitrate_bps.get(i)))/1000.0;
      }
      YinQoE += (double)qualityBitrate_bps.get(numOfSegments-1)/1000.0;

      return YinQoE;
    }
    @Override
    public void writeLogFile(){
      Calendar calendar = Calendar.getInstance();
      String day    = String.format("%02d", calendar.get(Calendar.DAY_OF_MONTH));
      String month  = String.format("%02d", calendar.get(Calendar.MONTH)+1);
      String year   = String.format("%04d", calendar.get(Calendar.YEAR));
      String hour   = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY));
      String minute = String.format("%02d", calendar.get(Calendar.MINUTE));
      String second = String.format("%02d", calendar.get(Calendar.SECOND));

      List<Long>    timestamp = AdaptiveTrackSelection.timestampMs;
      List<Integer> selectedQualityIndex = AdaptiveTrackSelection.selectedQualityIndex;
      List<Integer> selectedQualityBitrate = AdaptiveTrackSelection.selectedQualityBitrateBitps;
      List<Long>    estimatedThroughput = new ArrayList<>();
      List<Long>    bufferLevel = AdaptiveTrackSelection.bufferLevelUs;
      List<Boolean> srFlag = AdaptiveTrackSelection.isSREnableList;
      List<Integer> batteryLevel = AdaptiveTrackSelection.batteryLevelList;

      int[] quality_distribution = new int[AdaptiveTrackSelection.num_of_quality];
      int   adaptive_thrp_size = AdaptiveTrackSelection.averageThroughputBitps.size();

      // Preprocess
      for (int i = 1; i < adaptive_thrp_size; i++) {
        estimatedThroughput.add(AdaptiveTrackSelection.averageThroughputBitps.get(i));
      }
      estimatedThroughput.add(AdaptiveTrackSelection.averageThroughputBitps.get(adaptive_thrp_size-1));

      double avg_bitrate = 0;
      double avg_throughput = 0;
      double avg_quality_idx = 0;
      double avg_video_instability = 0;
      double YinQoE = 0;
      int num_downward_switches = 0;
      int num_stall = AnalyticsCollector.getNumOfStalls();
      long stall_duration = AnalyticsCollector.getStallDuration();
      long startup_duration = AnalyticsCollector.getStartupDuration();
      List<Long> instantStallDuration = AnalyticsCollector.getInstantStallDuration();
      List<Long> startStallTimestamps = AnalyticsCollector.getStartStallTimestampMs();

      int _length = timestamp.size();
      String currentTime = year + '_' + month + '_' + day + "__" + hour + '_' + minute;
      String basicParamDir = AdaptiveTrackSelection.segment_url +
                              "/BufMax" + AdaptiveTrackSelection.buffMax + "/" +
                              AdaptiveTrackSelection.implementedABR + "/";
      final File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/MinhExoPlayerSR/" +
                            basicParamDir + currentTime + "/");

      Log.i("MINH", "dir: " + dir.getPath());

        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            Log.e("ALERT", "could not create the directories");
          }
        }

      final File logFile = new File(dir, "statistics_" + currentTime + '_' +
                            AdaptiveTrackSelection.implementedABR + ".csv");
      final File summaryFile = new File(dir, "summary_" + currentTime + '_' +
                            AdaptiveTrackSelection.implementedABR + ".csv");

      if (!logFile.exists() && !summaryFile.exists()){
        try {
          logFile.createNewFile();
          summaryFile.createNewFile();
        }
        catch (IOException e) {
          e.printStackTrace();
        }
      }
      try {
        Writer buf_log = new OutputStreamWriter(new FileOutputStream(logFile), "UTF-8");
        Writer buf_summary = new OutputStreamWriter(new FileOutputStream(summaryFile), "UTF-8");
        buf_log.write("Id,Time[s],EstThroughput,QualityIdx,Bitrate,Buffer,IsSREnabled,Battery\n");
        Log.i("Minh", "Id\tTime[s]\tEstThroughput\tQualityIdx\tBitrate\tBuffer\tIsSREnabled\tBattery\n");

        for (int i = 0; i < _length; i++) {
          // quality distribution - S
          quality_distribution[selectedQualityIndex.get(i)-1] ++;
          // quality distribution - E
          avg_bitrate +=  selectedQualityBitrate.get(i)/1000.0;
          avg_throughput +=  estimatedThroughput.get(i)/1000.0;
          avg_quality_idx += selectedQualityIndex.get(i);

          if (i < _length-1) {
            int diff_quality = selectedQualityIndex.get(i)-selectedQualityIndex.get(i+1);
            avg_video_instability += Math.abs(diff_quality);
            if (diff_quality > 0) {
              num_downward_switches ++;
            }
          }

          // record segment download statistic
          String _string = String.valueOf(i+1) + "," +
                           String.valueOf(timestamp.get(i)/1000.0) + "," +
                           String.valueOf((float)estimatedThroughput.get(i)/1000) + "," +
                           String.valueOf(selectedQualityIndex.get(i)) + "," +
                           String.valueOf((float)selectedQualityBitrate.get(i)/1000) + "," +
                           String.valueOf((float)bufferLevel.get(i)/1000000) + "," +
                           String.valueOf(srFlag.get(i))  + "," +
                           String.valueOf(batteryLevel.get(i))  + "\n";
          Log.i("MINH", _string);
          buf_log.write(_string);
          buf_log.flush();
        }

        avg_bitrate = avg_bitrate/_length;
        avg_throughput = avg_throughput/_length;
        avg_quality_idx = avg_quality_idx/_length;
        avg_video_instability = avg_video_instability/(_length-1);
        YinQoE = getYinQoE(selectedQualityBitrate, stall_duration/1000.0, startup_duration/1000.0);

        buf_summary.write("Default parameters\n");
        buf_summary.write("ABR," + AdaptiveTrackSelection.implementedABR + "\n");
        buf_summary.write("Quality function," + AdaptiveTrackSelection.quality_config + "\n");
        buf_summary.write("Video," + AdaptiveTrackSelection.segment_url + "\n");
        for (int i = 0; i < AdaptiveTrackSelection.qualityLevelList.size(); i ++){
          buf_summary.write(" Quality " + (AdaptiveTrackSelection.qualityLevelList.size()-i) + "," + AdaptiveTrackSelection.qualityLevelList.get(i) + "\n");
        }
        buf_summary.write("* Buffer size [ms]," + DefaultLoadControl.DEFAULT_MAX_BUFFER_MS + "\n");
        buf_summary.write("* Buffer min [ms]," + DefaultLoadControl.DEFAULT_MIN_BUFFER_MS + "\n");
        buf_summary.write("* Buffer save level," + AdaptiveTrackSelection.SAFE_BUFFER_RATIO + "\n");
        buf_summary.write("* Buffer low level," + AdaptiveTrackSelection.LOW_BUFFER_RATIO + "\n");
        buf_summary.write("* Buffer min level," + AdaptiveTrackSelection.MIN_BUFFER_RATIO + "\n");
        buf_summary.write("* Buffer for playback to start/resume [ms]," + DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS + "\n");
        buf_summary.write("* Buffer for playback after stall [ms]," + DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS + "\n");
        buf_summary.write("* xi delta, slice window" + AdaptiveTrackSelection.xi + ',' + AdaptiveTrackSelection.delta + ',' + AdaptiveTrackSelection.slice_window + "\n");
        buf_summary.write("alpha," + AdaptiveTrackSelection.alpha + "\n");
        buf_summary.write("beta," + AdaptiveTrackSelection.beta + "\n");
        buf_summary.write("gamma," + AdaptiveTrackSelection.gamma + "\n");
        buf_summary.write("margin," + AdaptiveTrackSelection.margin + "\n");
        buf_summary.write("LowBuffThreshold[ms]," + AdaptiveTrackSelection.buffLowThresholdS*1000 + "\n");


        buf_summary.write("******************************************\n");
        buf_summary.write("Stream session from," + AdaptiveTrackSelection.startTime + " to " +
                              hour + "_" + minute + "_" + second + "\n");
        buf_summary.write("avg_bitrate," + avg_bitrate + "\n");
        buf_summary.write("avg_throughput," + avg_throughput + "\n");
        buf_summary.write("avg_quality_idx," + avg_quality_idx + "\n");
        buf_summary.write("avg_video_instability," + avg_video_instability + "\n");
        buf_summary.write("num_downward_switches," + num_downward_switches + "\n");
        buf_summary.write("startup_phaseS," + startup_duration/1000.0 + "\n");
        buf_summary.write("num_stall," + num_stall + "\n");
        buf_summary.write("stall_durationS," + stall_duration/1000.0 + "\n");
        buf_summary.write("Yin_QoE," + YinQoE + "\n");
        buf_summary.write("File_name," + currentTime + "\n");

        if (instantStallDuration.size() > 0) {
          buf_summary.write("************* Stall events ************\n");
          buf_summary.write("StartStall,StallDuration\n");
          for (int i = 0; i < instantStallDuration.size(); i++){
            buf_summary.write((startStallTimestamps.get(i+1)-startStallTimestamps.get(0))/1000 + "," + instantStallDuration.get(i)/1000.0 + "\n");
          }
        }

        buf_summary.write("quality,No\n");
        for (int i = 0; i < quality_distribution.length; i++){
          buf_summary.write(i+1 + "," + quality_distribution[i] + "\n");
        }
        buf_summary.flush();

        Log.i("MINH", "******************************************");
        Log.i("MINH", "avg_bitrate: " + avg_bitrate);
        Log.i("MINH", "avg_throughput: " + avg_throughput);
        Log.i("MINH", "avg_quality_idx: " + avg_quality_idx);
        Log.i("MINH", "avg_video_instability: " + avg_video_instability);
        Log.i("MINH", "num_downward_switches: " + num_downward_switches);
        Log.i("Minh", "startup_phaseS: " + startup_duration/1000.0 + "\n");
        Log.i("MINH", "num_stall: " + num_stall);
        Log.i("MINH", "stall_durationS: " + stall_duration/1000.0);
        Log.i("Minh", "Yin_QoE: " + YinQoE);
        Log.i("Minh", "File_name: " + currentTime + "\n");
        Log.i("MINH", "******************************************");
        Log.i("MINH", "alpha: " + AdaptiveTrackSelection.alpha + "\n");
        Log.i("MINH", "beta: " + AdaptiveTrackSelection.beta + "\n");
        Log.i("MINH", "gamma: " + AdaptiveTrackSelection.gamma + "\n");

      } catch (IOException e) {
        e.printStackTrace();
      }

      Log.i("MINH", "////////////// STREAMING SESSION ENDED /////////////");
    }
    //MINH [ADD a function that write log file to the SD card] - ADD - E

    @Override
    public void onPlayerError(@NonNull ExoPlaybackException e) {
      if (isBehindLiveWindow(e)) {
        clearStartPosition();
        initializePlayer();
      } else {
        updateButtonVisibility();
        showControls();
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void onTracksChanged(
        @NonNull TrackGroupArray trackGroups, @NonNull TrackSelectionArray trackSelections) {
      updateButtonVisibility();
      if (trackGroups != lastSeenTrackGroupArray) {
        MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedTrackInfo != null) {
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO)
              == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            showToast(R.string.error_unsupported_video);
          }
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO)
              == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            showToast(R.string.error_unsupported_audio);
          }
        }
        lastSeenTrackGroupArray = trackGroups;
      }
    }
  }

  private class PlayerErrorMessageProvider implements ErrorMessageProvider<ExoPlaybackException> {

    @Override
    @NonNull
    public Pair<Integer, String> getErrorMessage(@NonNull ExoPlaybackException e) {
      String errorString = getString(R.string.error_generic);
      if (e.type == ExoPlaybackException.TYPE_RENDERER) {
        Exception cause = e.getRendererException();
        if (cause instanceof DecoderInitializationException) {
          // Special case for decoder initialization failures.
          DecoderInitializationException decoderInitializationException =
              (DecoderInitializationException) cause;
          if (decoderInitializationException.codecInfo == null) {
            if (decoderInitializationException.getCause() instanceof DecoderQueryException) {
              errorString = getString(R.string.error_querying_decoders);
            } else if (decoderInitializationException.secureDecoderRequired) {
              errorString =
                  getString(
                      R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
            } else {
              errorString =
                  getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
            }
          } else {
            errorString =
                getString(
                    R.string.error_instantiating_decoder,
                    decoderInitializationException.codecInfo.name);
          }
        }
      }
      return Pair.create(0, errorString);
    }
  }

  private static List<MediaItem> createMediaItems(Intent intent, DownloadTracker downloadTracker) {
    List<MediaItem> mediaItems = new ArrayList<>();
    for (MediaItem item : IntentUtil.createMediaItemsFromIntent(intent)) {
      @Nullable
      DownloadRequest downloadRequest =
          downloadTracker.getDownloadRequest(checkNotNull(item.playbackProperties).uri);

      if (downloadRequest != null) {
        MediaItem.Builder builder = item.buildUpon();
        Log.i("MINH", "__;;__ Download Request uri:"  + downloadRequest.uri);
        builder
            .setMediaId(downloadRequest.id)
            .setUri(downloadRequest.uri)
            .setCustomCacheKey(downloadRequest.customCacheKey)
            .setMimeType(downloadRequest.mimeType)
            .setStreamKeys(downloadRequest.streamKeys)
            .setDrmKeySetId(downloadRequest.keySetId);
        mediaItems.add(builder.build());
      } else {
        mediaItems.add(item);
      }
    }
    return mediaItems;
  }
}
