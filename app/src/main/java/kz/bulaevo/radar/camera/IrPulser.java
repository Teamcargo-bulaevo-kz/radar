package kz.bulaevo.radar.camera;

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.os.SystemClock;

/**
 * Включает ИК-порт циклами: PHASE_MS горит, PHASE_MS не горит. Для каждого момента времени
 * говорит, горел ли ИК (с запасом на задержку кадра камеры), и номер цикла.
 */
public final class IrPulser {
    public static final long PHASE_MS = 1200;
    /** Сколько миллисекунд после переключения кадры не учитываются: кадр мог сниматься ещё при старом состоянии. */
    private static final long GUARD_MS = 380;

    private final ConsumerIrManager ir;
    private Thread thread;
    private volatile boolean running;
    private volatile long t0;
    private volatile String error;

    public IrPulser(Context c) {
        this.ir = CameraProbe.irManager(c);
    }

    public boolean available() {
        return ir != null && ir.hasIrEmitter();
    }

    public String error() {
        return error;
    }

    public synchronized void start() {
        stop();
        if (!available()) {
            error = "ИК-порта нет";
            return;
        }
        error = null;
        running = true;
        t0 = SystemClock.elapsedRealtime();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ir-pulser");
        thread.start();
    }

    private void loop() {
        // одна непрерывная вспышка на всю фазу «вкл» (несущая 38 кГц, как у пульта)
        int[] pattern = {(int) (PHASE_MS * 1000)};
        long k = 0;
        while (running) {
            long onStart = t0 + k * 2 * PHASE_MS;
            sleepUntil(onStart);
            if (!running) {
                break;
            }
            try {
                ir.transmit(38000, pattern);
            } catch (Throwable e) {
                error = "ИК-порт не ответил: " + e.getMessage();
                running = false;
                break;
            }
            k++;
        }
    }

    private void sleepUntil(long t) {
        long d;
        while (running && (d = t - SystemClock.elapsedRealtime()) > 0) {
            try {
                Thread.sleep(Math.min(d, 50));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Номер цикла для кадра, пришедшего в момент now. */
    public int cycleAt(long now) {
        long dt = now - t0;
        return dt < 0 ? -1 : (int) (dt / (2 * PHASE_MS));
    }

    /** 1 — кадр снят при включённом ИК, -1 — при выключенном, 0 — около переключения, не учитывать. */
    public int phaseAt(long now) {
        long dt = now - t0;
        if (dt < 0) {
            return 0;
        }
        long inCycle = dt % (2 * PHASE_MS);
        if (inCycle < PHASE_MS) {
            return inCycle >= GUARD_MS ? 1 : 0;
        }
        return inCycle - PHASE_MS >= GUARD_MS ? -1 : 0;
    }
}
