package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio capture for the host peer, in the exact wire format MiniRTC expects:
 * 48 kHz mono signed 16-bit PCM, delivered in 10 ms frames (480 samples /
 * 960 bytes). Two sources:
 *
 *  - system audio (Android 10+): AudioPlaybackCapture over the existing
 *    MediaProjection consent — media/game audio played on this device,
 *    the same approach RustDesk uses for remote sound;
 *  - microphone: VOICE_COMMUNICATION (AEC/NS where available) for voice
 *    calls, falling back to MIC.
 *
 * Only one source runs at a time; the microphone takes precedence.
 * Callers must hold RECORD_AUDIO before starting either source.
 */
final class HostAudio {
    interface Sink { void onPcmFrame(byte[] frame); }

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 480;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    private final AtomicBoolean running = new AtomicBoolean();
    private AudioRecord record;
    private Thread thread;

    boolean isRunning() { return running.get(); }

    @SuppressLint("MissingPermission")
    synchronized boolean startSystem(MediaProjection projection, Sink sink) {
        if (running.get() || projection == null || Build.VERSION.SDK_INT < 29) return false;
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioRecord built;
        try {
            built = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format())
                    .setBufferSizeInBytes(FRAME_BYTES * 8)
                    .build();
        } catch (IllegalArgumentException | UnsupportedOperationException error) {
            return false;
        }
        return start(built, sink);
    }

    @SuppressLint("MissingPermission")
    synchronized boolean startMic(Sink sink) {
        if (running.get()) return false;
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min, FRAME_BYTES * 8);
        AudioRecord built = trySource(MediaRecorder.AudioSource.VOICE_COMMUNICATION, buffer);
        if (built == null) built = trySource(MediaRecorder.AudioSource.MIC, buffer);
        return built != null && start(built, sink);
    }

    private AudioRecord trySource(int source, int buffer) {
        try {
            AudioRecord candidate = new AudioRecord(source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (candidate.getState() == AudioRecord.STATE_INITIALIZED) return candidate;
            candidate.release();
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private AudioFormat format() {
        return new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
    }

    private boolean start(AudioRecord built, Sink sink) {
        if (built.getState() != AudioRecord.STATE_INITIALIZED) { built.release(); return false; }
        record = built;
        running.set(true);
        try {
            built.startRecording();
        } catch (IllegalStateException error) {
            running.set(false);
            built.release();
            record = null;
            return false;
        }
        thread = new Thread(() -> captureLoop(built, sink), "CrossDesk host audio");
        thread.start();
        return true;
    }

    private void captureLoop(AudioRecord source, Sink sink) {
        short[] samples = new short[FRAME_SAMPLES];
        byte[] frame = new byte[FRAME_BYTES];
        while (running.get()) {
            int filled = 0;
            while (filled < FRAME_SAMPLES && running.get()) {
                int read = source.read(samples, filled, FRAME_SAMPLES - filled);
                if (read < 0) { filled = -1; break; }
                filled += read;
            }
            if (filled != FRAME_SAMPLES) break;
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                frame[i * 2] = (byte) (samples[i] & 0xff);
                frame[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
            }
            sink.onPcmFrame(frame.clone());
        }
    }

    synchronized void stop() {
        running.set(false);
        AudioRecord current = record;
        record = null;
        if (current != null) {
            try { current.stop(); } catch (IllegalStateException ignored) { }
            current.release();
        }
        Thread worker = thread;
        thread = null;
        if (worker != null) worker.interrupt();
    }
}
