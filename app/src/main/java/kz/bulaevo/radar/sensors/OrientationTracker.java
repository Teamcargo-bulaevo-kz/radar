package kz.bulaevo.radar.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Азимут телефона относительно стартового положения: поворот вокруг вертикальной оси (по часовой, градусы).
 * Берётся из датчика вращения без компаса (гироскоп + акселерометр): он не боится металла и электроники рядом.
 */
public final class OrientationTracker implements SensorEventListener {
    private final SensorManager sm;
    private Sensor sensor;
    private final float[] r = new float[9];
    private final float[] r0 = new float[9];
    private final float[] vec = new float[4];
    private boolean haveZero;
    private volatile double azimuth;
    private volatile boolean active;
    private volatile String kind = "нет датчика";
    private volatile long lastEvent;

    public OrientationTracker(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
    }

    public synchronized boolean start() {
        if (active) {
            return true;
        }
        sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        kind = "гироскоп (game rotation)";
        if (sensor == null) {
            sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            kind = "вектор вращения";
        }
        if (sensor == null) {
            kind = "нет датчика вращения";
            return false;
        }
        haveZero = false;
        active = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        return active;
    }

    public synchronized void stop() {
        if (active) {
            sm.unregisterListener(this);
            active = false;
        }
    }

    /** Считать текущее направление нулём (север карты). */
    public synchronized void resetZero() {
        haveZero = false;
    }

    public boolean isActive() {
        return active;
    }

    public String kind() {
        return kind;
    }

    /** Азимут 0..360, по часовой стрелке. */
    public double azimuth() {
        return azimuth;
    }

    /** Приходят ли данные прямо сейчас. */
    public boolean isLive() {
        return active && (System.currentTimeMillis() - lastEvent) < 1500;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final float[] v = event.values;
        int len = Math.min(4, v.length);
        for (int i = 0; i < len; i++) {
            vec[i] = v[i];
        }
        try {
            if (len >= 4) {
                SensorManager.getRotationMatrixFromVector(r, vec);
            } else {
                float[] v3 = {vec[0], vec[1], vec[2]};
                SensorManager.getRotationMatrixFromVector(r, v3);
            }
        } catch (IllegalArgumentException e) {
            return;
        }
        synchronized (this) {
            if (!haveZero) {
                System.arraycopy(r, 0, r0, 0, 9);
                haveZero = true;
            }
            // Q = R * R0^T ; поворот вокруг вертикали = atan2(Q10, Q00)
            double q00 = r[0] * r0[0] + r[1] * r0[1] + r[2] * r0[2];
            double q10 = r[3] * r0[0] + r[4] * r0[1] + r[5] * r0[2];
            double yaw = Math.toDegrees(Math.atan2(q10, q00));
            double az = -yaw;
            az %= 360.0;
            if (az < 0) {
                az += 360.0;
            }
            azimuth = az;
            lastEvent = System.currentTimeMillis();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // не используется
    }
}
