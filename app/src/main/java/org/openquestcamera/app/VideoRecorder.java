package org.openquestcamera.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class VideoRecorder {
    private final Context context;
    private final CameraModels.RecorderConfig config;
    private MediaCodec videoCodec;
    private Surface inputSurface;
    private Thread videoDrainThread;
    private MuxerCoordinator muxer;
    private AudioEncoder audio;
    private volatile boolean recording;
    private volatile boolean videoDrainDone;

    VideoRecorder(Context context, CameraModels.RecorderConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    void start() throws Exception {
        if (recording) return;
        String encoderName = findVideoEncoder(config.codec, config.width * 2, config.height, config.fps);
        if (encoderName == null) throw new IllegalStateException(context.getString(R.string.error_no_hardware_encoder));

        muxer = new MuxerCoordinator(context, config.mic);
        muxer.open();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(config.codec, config.width * 2, config.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            videoCodec = MediaCodec.createByCodecName(encoderName);
            videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoCodec.createInputSurface();
            videoCodec.start();
            recording = true;
            videoDrainDone = false;
            videoDrainThread = new Thread(new Runnable() {
                @Override public void run() { drainVideo(); }
            }, "video-surface-drain");
            videoDrainThread.start();

            if (config.mic) audio = new AudioEncoder(muxer);
        } catch (Throwable t) {
            stop(true);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException(t);
        }
    }

    Surface inputSurface() { return inputSurface; }
    boolean isRecording() { return recording; }

    void startAudio() throws Exception {
        if (audio != null) audio.start();
    }

    String stop(boolean discard) {
        if (!recording && muxer == null) return context.getString(R.string.status_stopped);
        recording = false;
        if (audio != null) {
            try { audio.stop(); } catch (Throwable ignored) {}
        }
        try { if (videoCodec != null) videoCodec.signalEndOfInputStream(); } catch (Throwable ignored) {}
        try { if (videoDrainThread != null) videoDrainThread.join(5000); } catch (InterruptedException ignored) {}
        if (audio != null) {
            try { audio.awaitStopped(); } catch (Throwable ignored) {}
            audio = null;
        }
        try { if (videoCodec != null) videoCodec.stop(); } catch (Throwable ignored) {}
        try { if (videoCodec != null) videoCodec.release(); } catch (Throwable ignored) {}
        videoCodec = null;
        try { if (inputSurface != null) inputSurface.release(); } catch (Throwable ignored) {}
        inputSurface = null;
        videoDrainThread = null;
        if (muxer != null) {
            String result = muxer.finish(discard);
            muxer = null;
            return result;
        }
        return context.getString(R.string.status_stopped);
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int out = videoCodec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!recording) continue;
                    continue;
                }
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxer.setVideoFormat(videoCodec.getOutputFormat());
                    continue;
                }
                if (out >= 0) {
                    ByteBuffer data = videoCodec.getOutputBuffer(out);
                    if (data != null && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        muxer.writeVideo(data, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    videoCodec.releaseOutputBuffer(out, false);
                    if (eos) {
                        videoDrainDone = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean supportsVideo(String mime, int width, int height, int fps) {
        return findVideoEncoder(mime, width, height, fps) != null;
    }

    private static String findVideoEncoder(String mime, int width, int height, int fps) {
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo info : infos) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        boolean surface = false;
                        for (int color : caps.colorFormats) {
                            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                                surface = true;
                                break;
                            }
                        }
                        if (!surface) continue;
                        MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                        if (vc != null && vc.areSizeAndRateSupported(width, height, fps)) return info.getName();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final class AudioEncoder {
        private static final int SAMPLE_RATE = 48_000;
        private static final int BIT_RATE = 192_000;
        private final MuxerCoordinator muxer;
        private MediaCodec codec;
        private AudioRecord record;
        private Thread thread;
        private volatile boolean running;
        private long samplesQueued;

        AudioEncoder(MuxerCoordinator muxer) { this.muxer = muxer; }

        void start() throws Exception {
            if (muxer.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(muxer.context.getString(R.string.status_microphone_permission_denied));
            }
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 4096;
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException(muxer.context.getString(R.string.error_microphone_initialization));

            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            samplesQueued = 0;
            running = true;
            record.startRecording();
            thread = new Thread(new Runnable() {
                @Override public void run() { audioLoop(); }
            }, "aac-audio");
            thread.start();
        }

        void stop() {
            running = false;
            try { if (record != null) record.stop(); } catch (Throwable ignored) {}
        }

        void awaitStopped() {
            try { if (thread != null) thread.join(3500); } catch (InterruptedException ignored) {}
        }

        private void audioLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eosQueued = false;
            try {
                while (running) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        if (buffer != null) {
                            buffer.clear();
                            int bytes = record.read(buffer, buffer.remaining());
                            if (bytes > 0) {
                                long pts = samplesQueued * 1_000_000L / SAMPLE_RATE;
                                samplesQueued += bytes / 2;
                                codec.queueInputBuffer(in, 0, bytes, pts, 0);
                            } else {
                                codec.queueInputBuffer(in, 0, 0, samplesQueued * 1_000_000L / SAMPLE_RATE, 0);
                            }
                        }
                    }
                    drainAudio(info, false);
                }
                while (!eosQueued) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        codec.queueInputBuffer(in, 0, 0, samplesQueued * 1_000_000L / SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                    drainAudio(info, false);
                }
                boolean eos = false;
                long end = System.currentTimeMillis() + 2500;
                while (!eos && System.currentTimeMillis() < end) eos = drainAudio(info, true);
            } catch (Throwable ignored) {
            } finally {
                try { if (record != null) record.release(); } catch (Throwable ignored) {}
                record = null;
                try { if (codec != null) codec.stop(); } catch (Throwable ignored) {}
                try { if (codec != null) codec.release(); } catch (Throwable ignored) {}
                codec = null;
            }
        }

        private boolean drainAudio(MediaCodec.BufferInfo info, boolean wait) {
            while (true) {
                int out = codec.dequeueOutputBuffer(info, wait ? 10_000 : 0);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxer.setAudioFormat(codec.getOutputFormat());
                    continue;
                }
                if (out >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(out);
                    if (data != null && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        muxer.writeAudio(data, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, false);
                    if (eos) return true;
                }
            }
        }
    }

    private static final class MuxerCoordinator {
        private final Context context;
        private final boolean expectAudio;
        private final Object lock = new Object();
        private MediaMuxer muxer;
        private ParcelFileDescriptor pfd;
        private Uri uri;
        private int videoTrack = -1;
        private int audioTrack = -1;
        private boolean started;
        private boolean wroteVideo;
        private boolean closed;

        MuxerCoordinator(Context context, boolean expectAudio) {
            this.context = context;
            this.expectAudio = expectAudio;
        }

        void open() throws Exception {
            ContentResolver cr = context.getContentResolver();
            ContentValues values = new ContentValues();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "OpenQuestCamera_SBS_" + stamp + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenQuestCamera");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException(context.getString(R.string.error_video_create));
            pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IllegalStateException(context.getString(R.string.error_video_open));
            FileDescriptor fd = pfd.getFileDescriptor();
            muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void setVideoFormat(MediaFormat format) {
            synchronized (lock) {
                if (videoTrack < 0 && !started && !closed) {
                    videoTrack = muxer.addTrack(format);
                    maybeStartLocked();
                }
            }
        }

        void setAudioFormat(MediaFormat format) {
            synchronized (lock) {
                if (audioTrack < 0 && !started && !closed) {
                    audioTrack = muxer.addTrack(format);
                    maybeStartLocked();
                }
            }
        }

        private void maybeStartLocked() {
            if (!started && videoTrack >= 0 && (!expectAudio || audioTrack >= 0)) {
                muxer.start();
                started = true;
                lock.notifyAll();
            }
        }

        private boolean awaitStarted() {
            synchronized (lock) {
                long end = System.currentTimeMillis() + 3000;
                while (!started && !closed) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    try { lock.wait(Math.min(200, left)); } catch (InterruptedException ignored) {}
                }
                return started && !closed;
            }
        }

        void writeVideo(ByteBuffer data, MediaCodec.BufferInfo info) {
            if (!awaitStarted()) return;
            synchronized (lock) {
                if (!started || closed) return;
                write(videoTrack, data, info);
                wroteVideo = true;
            }
        }

        void writeAudio(ByteBuffer data, MediaCodec.BufferInfo info) {
            if (!awaitStarted()) return;
            synchronized (lock) {
                if (!started || closed || audioTrack < 0) return;
                write(audioTrack, data, info);
            }
        }

        private void write(int track, ByteBuffer data, MediaCodec.BufferInfo info) {
            data.position(info.offset);
            data.limit(info.offset + info.size);
            muxer.writeSampleData(track, data, info);
        }

        String finish(boolean discard) {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
            try { if (started && muxer != null) muxer.stop(); } catch (Throwable ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
            muxer = null;
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
            pfd = null;

            ContentResolver cr = context.getContentResolver();
            if (uri != null) {
                if (discard || !wroteVideo) {
                    try { cr.delete(uri, null, null); } catch (Throwable ignored) {}
                    uri = null;
                    return context.getString(discard ? R.string.status_recording_aborted : R.string.error_no_video_frames);
                }
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                } catch (Throwable ignored) {}
                uri = null;
                return context.getString(R.string.status_saved);
            }
            return context.getString(R.string.status_stopped);
        }
    }
}
