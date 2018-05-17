package com.felkertech.cumulustv.player;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioFormat;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.ConditionVariable;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;


import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import com.felkertech.cumulustv.player.source.PositionReference;
import com.felkertech.cumulustv.player.utils.TrickPlayController;
import com.felkertech.cumulustv.player.extractor.TvExtractor;

import java.util.ArrayList;
import java.util.List;


public class CumulusTvPlayer implements com.google.android.exoplayer2.Player.EventListener, TvExtractor.Listener, AudioRendererEventListener, VideoRendererEventListener {

    private static final String TAG = "CumulusTvPlayer";

    private static final boolean DEBUG = false;

    private static final int DEFAULT_MIN_BUFFER_MS = 3000;
    private static final int DEFAULT_MAX_BUFFER_MS = 5000;
    private static final int DEFAULT_BUFFER_FOR_PLAYBACK_MS = 1000;
    private static final int DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2000;

    public interface Listener  {

        void onPlayerStateChanged(boolean playWhenReady, int playbackState);

        void onPlayerError(Exception e);

        void onDisconnect();

        void onTracksChanged(StreamBundle bundle);

        void onAudioTrackChanged(Format format);

        void onVideoTrackChanged(Format format);

        void onRenderedFirstFrame();
    }

    private Listener listener;
    private Handler handler;

    private List<ErrorListener> mErrorListeners = new ArrayList<>();
    final private SimpleExoPlayer player;
    final private TvExtractor.Factory extractorFactory;
    final private PositionReference position;
    final private ConditionVariable openConditionVariable;
    final private TrickPlayController trickPlayController;
    private float mPlaybackSpeed;
    private Context mContext;

    public CumulusTvPlayer(Context context, String language, Listener listener, boolean audioPassthrough) {
        AudioCapabilities audioCapabilities = AudioCapabilities.getCapabilities(context);

        this.listener = listener;
        boolean passthrough = audioCapabilities.supportsEncoding(AudioFormat.ENCODING_AC3) && audioPassthrough;

        Log.i(TAG, "audio passthrough: " + (passthrough ? "enabled" : "disabled"));

        openConditionVariable = new ConditionVariable();
        handler = new Handler();

        position = new PositionReference();

        DefaultTrackSelector trackSelector = new DefaultTrackSelector();
        trackSelector.setParameters(new DefaultTrackSelector.Parameters().withPreferredAudioLanguage(language));

        player = ExoPlayerFactory.newSimpleInstance(
                new RoboTvRenderersFactory(context, audioPassthrough),
                trackSelector,
                new DefaultLoadControl(
                        new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                        DEFAULT_MIN_BUFFER_MS,
                        DEFAULT_MAX_BUFFER_MS,
                        DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                        C.LENGTH_UNSET,
                        false
                )
        );

        player.addListener(this);
        player.setVideoDebugListener(this);

        extractorFactory = new TvExtractor.Factory(position, this, language, passthrough);
        trickPlayController = new TrickPlayController(handler, position, player);
    }

    public void release() {
        stop();

        handler = null;

        player.removeListener(this);
        player.release();

    }

    public void setSurface(Surface surface) {
        player.setVideoSurface(surface);
    }

    public void setStreamVolume(float volume) {
        player.setVolume(volume);
    }

    public void seek(long position) {
        long p = this.position.timeUsFromPosition(Math.max(position, this.position.getStartPosition()));
        player.seekTo(p / 1000);
    }

    public void setPlaybackParams(PlaybackParams params) {
        Log.d(TAG, "speed: " + params.getSpeed());
        trickPlayController.start(params.getSpeed());
    }

    public void selectAudioTrack(String trackId) {
        extractorFactory.selectAudioTrack(trackId);
    }

    public long getStartPosition() {
        return position.getStartPosition();
    }

    public long getEndPosition() {
        return position.getEndPosition();
    }

    public long getCurrentPosition() {
        long timeUs = player.getCurrentPosition() * 1000;
        long startPos = position.getStartPosition();
        long endPos = position.getEndPosition();

        long pos = Math.max(position.positionFromTimeUs(timeUs), startPos);

        // clamp to end position (if we already have a valid endposition)
        if(endPos > startPos) {
            return Math.min(pos, endPos);
        }

        return pos;
    }

    public long getBufferedPosition() {
        long timeUs = player.getBufferedPosition() * 1000;
        return position.positionFromTimeUs(timeUs);
    }

    public long getDurationSinceStart() {
        return getCurrentPosition() - getStartPosition();
    }

    public long getDuration() {
        return position.getDuration();
    }

    public void play() {
        trickPlayController.stop();
        player.setPlayWhenReady(true);
    }

    public void pause() {
        trickPlayController.stop();
        player.setPlayWhenReady(false);
    }

    public boolean isPaused() {
        return !player.getPlayWhenReady();
    }

    public void stop() {
        trickPlayController.reset();
        player.stop();
        position.reset();
    }

    public void registerErrorListener(ErrorListener callback) {
        mErrorListeners.add(callback);
    }

    public void unregisterErrorListener(ErrorListener callback) {
        mErrorListeners.remove(callback);
    }

    public void startPlaying(Uri mediaUri) {
        // This is the MediaSource representing the media to be played.
        try {
            MediaSource videoSource = MediaSourceFactory.getMediaSourceFor(mContext, mediaUri);
            // Prepare the player with the source.
            player.prepare(videoSource);
        } catch (MediaSourceFactory.NotMediaException e) {
            for (ErrorListener listener : mErrorListeners) {
                listener.onError(e);
            }
        }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {

    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroupArray, TrackSelectionArray trackSelectionArray) {
        if(listener == null) {
            return;
        }

        for(int i = 0; i < trackSelectionArray.length; i++) {
            TrackSelection selection = trackSelectionArray.get(i);

            // skip disabled renderers
            if(selection == null) {
                continue;
            }

            Format format = selection.getSelectedFormat();

            // selected audio track
            if(MimeTypes.isAudio(format.sampleMimeType)) {
                listener.onAudioTrackChanged(format);
            }

            // selected video track
            if(MimeTypes.isVideo(format.sampleMimeType)) {
                listener.onVideoTrackChanged(format);
            }
        }
    }

    @Override
    public void onTracksChanged(StreamBundle bundle) {
        listener.onTracksChanged(bundle);
    }

    @Override
    public void onAudioTrackChanged(Format format) {
        if(format == null) {
            return;
        }
        listener.onAudioTrackChanged(format);
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {

    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        Log.i(TAG, "onPlayerStateChanged " + playWhenReady + " " + playbackState);
        listener.onPlayerStateChanged(playWhenReady, playbackState);
    }
    @Override
    public void onPlayerError(ExoPlaybackException error) {
        listener.onPlayerError(error);
    }

    @Override
    public void onRenderedFirstFrame(Surface surface) {
        if(trickPlayController.activated()) {
            trickPlayController.postTick();
            return;
        }

        listener.onRenderedFirstFrame();
    }

    @Override
    public void onVideoDisabled(DecoderCounters counters) {
    }

    @Override
    public void onDisconnect() {
        listener.onDisconnect();
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
    }

    @Override
    public void onSeekProcessed() {
    }

    @Override
    public void onVideoEnabled(DecoderCounters counters) {
    }

    @Override
    public void onVideoDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
    }

    @Override
    public void onVideoInputFormatChanged(Format format) {

    }

    @Override
    public void onDroppedFrames(int count, long elapsedMs) {
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
    }

    @Override
    public void onAudioEnabled(DecoderCounters counters) {
    }

    @Override
    public void onStreamError(int status) {
        if(listener != null) {
            listener.onStreamError(status);
        }
    }

    @Override
    public void onServerTuned(int status) {
        openConditionVariable.open();
    }

    @Override
    public void onAudioSessionId(int audioSessionId) {
    }

    @Override
    public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
    }

    @Override
    public void onAudioInputFormatChanged(Format format) {
        //listener.onAudioTrackChanged(format);
    }

    @Override
    public void onAudioSinkUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {

    }

    @Override
    public void onAudioDisabled(DecoderCounters counters) {
    }

    public interface ErrorListener {
        void onError(Exception error);
    }
}
