package kz.bulaevo.radar.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

import kz.bulaevo.radar.dsp.Parking;

/**
 * Звуковой парктроник: короткие гудки, чем ближе препятствие — тем чаще и выше тон,
 * совсем близко — сплошной звук. Тон слышимый (0.6–1.5 кГц), поэтому он не мешает ультразвуковому сонару.
 */
public final class Beeper {
    /** Громкость гудка (доля шкалы). Вместе с сонаром сумма не должна превышать 1. */
    public static final float AMP = 0.26f;
    /** Во сколько раз уменьшается сила сонара, пока гудок включён (чтобы сумма не перегружала динамик). */
    public static final float SONAR_SCALE = 0.75f;

    private static final int FS = AudioUtil.FS;
    private static final int CHUNK = FS / 100;

    private volatile float distance = Float.NaN;
    private volatile float maxDistance = 3f;
    private volatile boolean running;
    private volatile String error;
    private Thread thread;
    private AudioTrack track;

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return error;
    }

    /** Расстояние до препятствия, м; NaN — тишина. */
    public void setDistance(float meters) {
        distance = meters;
    }

    /** С какого расстояния начинать гудеть. */
    public void setMaxDistance(float meters) {
        maxDistance = Math.max(0.5f, meters);
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }
        error = null;
        try {
            int minBuf = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(FS)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, CHUNK * 2 * 3))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = b.build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new Exception("не удалось открыть динамик для гудков");
            }
            running = true;
            track.play();
            final AudioTrack tr = track;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(tr);
                }
            }, "beeper");
            thread.start();
            return true;
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.toString() : t.getMessage();
            running = false;
            release();
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        release();
    }

    private void release() {
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
                // уже остановлен
            }
            try {
                track.release();
            } catch (Throwable ignored) {
                // ничего
            }
            track = null;
        }
    }

    private void loop(AudioTrack tr) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final short[] buf = new short[CHUNK];
        double phase = 0.0;
        double gain = 0.0;
        long cycle = 0;
        try {
            while (running) {
                final float d = distance;
                final float maxD = maxDistance;
                final boolean active = !Float.isNaN(d) && d <= maxD;
                final boolean continuous = active && d < Parking.CONTINUOUS_M;
                final long period = active ? (long) (Parking.intervalMs(d) * FS / 1000.0) : 1;
                final long onLen = Math.min((long) (0.070 * FS), (long) (period * 0.45));
                final double inc = 2.0 * Math.PI * (active ? Parking.pitchHz(d, maxD) : 1000f) / FS;
                for (int i = 0; i < CHUNK; i++) {
                    boolean gate;
                    if (!active) {
                        gate = false;
                        cycle = 0;
                    } else if (continuous) {
                        gate = true;
                    } else {
                        gate = cycle < onLen;
                        if (++cycle >= period) {
                            cycle = 0;
                        }
                    }
                    final double target = gate ? 1.0 : 0.0;
                    gain += Math.max(-1.0 / 192, Math.min(1.0 / 192, target - gain));
                    phase += inc;
                    if (phase > 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI;
                    }
                    buf[i] = (short) Math.round(Math.sin(phase) * AMP * gain * 32767.0);
                }
                int w = tr.write(buf, 0, CHUNK);
                if (w < 0) {
                    error = "Ошибка вывода гудков (" + w + ")";
                    break;
                }
            }
        } catch (Throwable t) {
            error = "Гудки: " + t.getMessage();
        }
    }
}
