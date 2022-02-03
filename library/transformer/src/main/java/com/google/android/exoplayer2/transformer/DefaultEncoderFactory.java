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

package com.google.android.exoplayer2.transformer;

import static com.google.android.exoplayer2.transformer.CodecFactoryUtil.createCodec;
import static com.google.android.exoplayer2.transformer.CodecFactoryUtil.createTransformationException;
import static com.google.android.exoplayer2.util.Assertions.checkArgument;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;
import static com.google.android.exoplayer2.util.Util.SDK_INT;
import static java.lang.Math.abs;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Pair;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** A default implementation of {@link Codec.EncoderFactory}. */
public final class DefaultEncoderFactory implements Codec.EncoderFactory {
  private static final int DEFAULT_COLOR_FORMAT =
      MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
  private static final int DEFAULT_FRAME_RATE = 60;
  private static final int DEFAULT_I_FRAME_INTERVAL_SECS = 1;

  @Nullable private final EncoderSelector videoEncoderSelector;
  private final boolean disableFallback;

  /**
   * Creates a new instance using the {@link EncoderSelector#DEFAULT default encoder selector}, and
   * format fallback enabled.
   *
   * <p>With format fallback enabled, and when the requested {@link Format} is not supported, {@code
   * DefaultEncoderFactory} finds a format that is supported by the device and configures the {@link
   * Codec} with it. The fallback process may change the requested {@link Format#sampleMimeType MIME
   * type}, resolution, {@link Format#bitrate bitrate}, {@link Format#codecs profile/level}, etc.
   */
  public DefaultEncoderFactory() {
    this(EncoderSelector.DEFAULT, /* disableFallback= */ false);
  }

  /** Creates a new instance. */
  public DefaultEncoderFactory(
      @Nullable EncoderSelector videoEncoderSelector, boolean disableFallback) {
    this.videoEncoderSelector = videoEncoderSelector;
    this.disableFallback = disableFallback;
  }

  @Override
  public Codec createForAudioEncoding(Format format, List<String> allowedMimeTypes)
      throws TransformationException {
    // TODO(b/210591626) Add encoder selection for audio.
    checkArgument(!allowedMimeTypes.isEmpty());
    if (!allowedMimeTypes.contains(format.sampleMimeType)) {
      // TODO(b/210591626): Pick fallback MIME type using same strategy as for encoder
      // capabilities limitations.
      format = format.buildUpon().setSampleMimeType(allowedMimeTypes.get(0)).build();
    }
    MediaFormat mediaFormat =
        MediaFormat.createAudioFormat(
            checkNotNull(format.sampleMimeType), format.sampleRate, format.channelCount);
    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate);

    return createCodec(
        format,
        mediaFormat,
        /* mediaCodecName= */ null,
        /* isVideo= */ false,
        /* isDecoder= */ false,
        /* outputSurface= */ null);
  }

  @Override
  public Codec createForVideoEncoding(Format format, List<String> allowedMimeTypes)
      throws TransformationException {
    checkArgument(format.width != Format.NO_VALUE);
    checkArgument(format.height != Format.NO_VALUE);
    // According to interface Javadoc, format.rotationDegrees should be 0. The video should always
    // be in landscape orientation.
    checkArgument(format.height <= format.width);
    checkArgument(format.rotationDegrees == 0);
    checkNotNull(format.sampleMimeType);
    checkArgument(!allowedMimeTypes.isEmpty());
    checkStateNotNull(videoEncoderSelector);

    @Nullable
    Pair<MediaCodecInfo, Format> encoderAndClosestFormatSupport =
        findEncoderWithClosestFormatSupport(
            format, videoEncoderSelector, allowedMimeTypes, disableFallback);
    if (encoderAndClosestFormatSupport == null) {
      throw createTransformationException(
          new IllegalArgumentException(
              "No encoder available that supports the requested output format."),
          format,
          /* isVideo= */ true,
          /* isDecoder= */ false,
          /* mediaCodecName= */ null);
    }

    format = encoderAndClosestFormatSupport.second;
    MediaFormat mediaFormat =
        MediaFormat.createVideoFormat(
            checkNotNull(format.sampleMimeType), format.width, format.height);
    mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate);
    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.averageBitrate);

    @Nullable
    Pair<Integer, Integer> codecProfileAndLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
    if (codecProfileAndLevel != null) {
      mediaFormat.setInteger(MediaFormat.KEY_PROFILE, codecProfileAndLevel.first);
      if (SDK_INT >= 23) {
        mediaFormat.setInteger(MediaFormat.KEY_LEVEL, codecProfileAndLevel.second);
      }
    }

    mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, DEFAULT_COLOR_FORMAT);
    mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL_SECS);

    return createCodec(
        format,
        mediaFormat,
        encoderAndClosestFormatSupport.first.getName(),
        /* isVideo= */ true,
        /* isDecoder= */ false,
        /* outputSurface= */ null);
  }

  /**
   * Finds a {@link MediaCodecInfo encoder} that supports the requested format most closely. Returns
   * the {@link MediaCodecInfo encoder} and the supported {@link Format} in a {@link Pair}, or
   * {@code null} if none is found.
   */
  @RequiresNonNull("#1.sampleMimeType")
  @Nullable
  private static Pair<MediaCodecInfo, Format> findEncoderWithClosestFormatSupport(
      Format requestedFormat,
      EncoderSelector encoderSelector,
      List<String> allowedMimeTypes,
      boolean disableFallback) {
    String requestedMimeType = requestedFormat.sampleMimeType;
    @Nullable
    String mimeType = findFallbackMimeType(encoderSelector, requestedMimeType, allowedMimeTypes);
    if (mimeType == null || (disableFallback && !requestedMimeType.equals(mimeType))) {
      return null;
    }

    List<MediaCodecInfo> encodersForMimeType = encoderSelector.selectEncoderInfos(mimeType);
    if (encodersForMimeType.isEmpty()) {
      return null;
    }
    if (disableFallback) {
      return Pair.create(encodersForMimeType.get(0), requestedFormat);
    }
    ImmutableList<MediaCodecInfo> filteredEncoders =
        filterEncoders(
            encodersForMimeType,
            /* cost= */ (encoderInfo) -> {
              @Nullable
              Pair<Integer, Integer> closestSupportedResolution =
                  EncoderUtil.getClosestSupportedResolution(
                      encoderInfo, mimeType, requestedFormat.width, requestedFormat.height);
              if (closestSupportedResolution == null) {
                // Drops encoder.
                return Integer.MAX_VALUE;
              }
              return abs(
                  requestedFormat.width * requestedFormat.height
                      - closestSupportedResolution.first * closestSupportedResolution.second);
            });
    if (filteredEncoders.isEmpty()) {
      return null;
    }
    // The supported resolution is the same for all remaining encoders.
    Pair<Integer, Integer> finalResolution =
        checkNotNull(
            EncoderUtil.getClosestSupportedResolution(
                filteredEncoders.get(0), mimeType, requestedFormat.width, requestedFormat.height));

    int requestedBitrate =
        requestedFormat.averageBitrate == Format.NO_VALUE
            ? getSuggestedBitrate(
                /* width= */ finalResolution.first,
                /* height= */ finalResolution.second,
                requestedFormat.frameRate == Format.NO_VALUE
                    ? DEFAULT_FRAME_RATE
                    : requestedFormat.frameRate)
            : requestedFormat.averageBitrate;
    filteredEncoders =
        filterEncoders(
            filteredEncoders,
            /* cost= */ (encoderInfo) -> {
              int achievableBitrate =
                  EncoderUtil.getClosestSupportedBitrate(encoderInfo, mimeType, requestedBitrate);
              return abs(achievableBitrate - requestedBitrate);
            });
    if (filteredEncoders.isEmpty()) {
      return null;
    }

    MediaCodecInfo pickedEncoder = filteredEncoders.get(0);
    @Nullable
    Pair<Integer, Integer> profileLevel = MediaCodecUtil.getCodecProfileAndLevel(requestedFormat);
    @Nullable String codecs = null;
    if (profileLevel != null
        && requestedFormat.sampleMimeType.equals(mimeType)
        && EncoderUtil.isProfileLevelSupported(
            pickedEncoder,
            mimeType,
            /* profile= */ profileLevel.first,
            /* level= */ profileLevel.second)) {
      codecs = requestedFormat.codecs;
    }

    Format encoderSupportedFormat =
        requestedFormat
            .buildUpon()
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setWidth(finalResolution.first)
            .setHeight(finalResolution.second)
            .setFrameRate(
                requestedFormat.frameRate != Format.NO_VALUE
                    ? requestedFormat.frameRate
                    : DEFAULT_FRAME_RATE)
            .setAverageBitrate(
                EncoderUtil.getClosestSupportedBitrate(pickedEncoder, mimeType, requestedBitrate))
            .build();
    return Pair.create(pickedEncoder, encoderSupportedFormat);
  }

  private interface EncoderFallbackCost {
    /**
     * Returns a cost that represents the gap between the requested encoding parameter(s) and the
     * {@link MediaCodecInfo encoder}'s support for them.
     *
     * <p>The method must return {@link Integer#MAX_VALUE} when the {@link MediaCodecInfo encoder}
     * does not support the encoding parameters.
     */
    int getParameterSupportGap(MediaCodecInfo encoderInfo);
  }

  private static ImmutableList<MediaCodecInfo> filterEncoders(
      List<MediaCodecInfo> encoders, EncoderFallbackCost cost) {
    List<MediaCodecInfo> filteredEncoders = new ArrayList<>(encoders.size());

    int minGap = Integer.MAX_VALUE;
    for (int i = 0; i < encoders.size(); i++) {
      MediaCodecInfo encoderInfo = encoders.get(i);
      int gap = cost.getParameterSupportGap(encoderInfo);
      if (gap == Integer.MAX_VALUE) {
        continue;
      }

      if (gap < minGap) {
        minGap = gap;
        filteredEncoders.clear();
        filteredEncoders.add(encoderInfo);
      } else if (gap == minGap) {
        filteredEncoders.add(encoderInfo);
      }
    }
    return ImmutableList.copyOf(filteredEncoders);
  }

  @Nullable
  private static String findFallbackMimeType(
      EncoderSelector encoderSelector, String requestedMimeType, List<String> allowedMimeTypes) {
    if (mimeTypeIsSupported(encoderSelector, requestedMimeType, allowedMimeTypes)) {
      return requestedMimeType;
    } else if (mimeTypeIsSupported(encoderSelector, MimeTypes.VIDEO_H265, allowedMimeTypes)) {
      return MimeTypes.VIDEO_H265;
    } else if (mimeTypeIsSupported(encoderSelector, MimeTypes.VIDEO_H264, allowedMimeTypes)) {
      return MimeTypes.VIDEO_H264;
    } else {
      for (int i = 0; i < allowedMimeTypes.size(); i++) {
        String allowedMimeType = allowedMimeTypes.get(i);
        if (mimeTypeIsSupported(encoderSelector, allowedMimeType, allowedMimeTypes)) {
          return allowedMimeType;
        }
      }
    }
    return null;
  }

  private static boolean mimeTypeIsSupported(
      EncoderSelector encoderSelector, String mimeType, List<String> allowedMimeTypes) {
    return !encoderSelector.selectEncoderInfos(mimeType).isEmpty()
        && allowedMimeTypes.contains(mimeType);
  }

  /** Computes the video bit rate using the Kush Gauge. */
  private static int getSuggestedBitrate(int width, int height, float frameRate) {
    // TODO(b/210591626) Implement bitrate estimation.
    // 1080p30 -> 6.2Mbps, 720p30 -> 2.7Mbps.
    return (int) (width * height * frameRate * 0.1);
  }
}
