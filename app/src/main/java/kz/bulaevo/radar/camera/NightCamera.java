package kz.bulaevo.radar.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Камера для ночного режима: отдаёт яркостный (Y) кадр небольшого размера в обработку.
 * Режимы: 0 — авто, 1 — ночь (длинная выдержка за счёт низкой частоты кадров + компенсация экспозиции),
 * 2 — ночь+ (ручная выдержка ~1/8 с и максимальная чувствительность, если камера это умеет).
 */
public final class NightCamera {
    public interface Callback {
        /** Кадр яркости w*h подряд; массив переиспользуется — обработать немедленно. Вызывается из потока камеры. */
        void onFrame(byte[] y, int w, int h);

        void onInfo(String text);

        void onError(String message);
    }

    private final Context ctx;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Callback cb;
    private byte[] ybuf;
    private volatile boolean open;
    private volatile int mode;
    private volatile boolean aeLock;

    private int sensorOrientation = 90;
    private boolean frontFacing;
    private CameraCharacteristics chars;
    private String info = "";

    public NightCamera(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public int sensorOrientation() {
        return sensorOrientation;
    }

    public boolean isFront() {
        return frontFacing;
    }

    public String info() {
        return info;
    }

    public boolean isOpen() {
        return open;
    }

    public synchronized void open(final Callback callback, int startMode) {
        close();
        cb = callback;
        mode = startMode;
        thread = new HandlerThread("night-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = chooseCamera(cm);
            if (id == null) {
                fail("Камера не найдена");
                return;
            }
            chars = cm.getCameraCharacteristics(id);
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map);
            if (size == null) {
                fail("Камера не отдаёт кадры YUV");
                return;
            }
            final int w = size.getWidth();
            final int h = size.getHeight();
            ybuf = new byte[w * h];
            reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    handleImage(r, w, h);
                }
            }, handler);
            info = "камера " + id + ", кадр " + w + "×" + h;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    device = null;
                    open = false;
                    fail("Камера отключена");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    device = null;
                    open = false;
                    fail("Ошибка камеры (" + error + ")");
                }
            }, handler);
        } catch (SecurityException se) {
            fail("Нет разрешения на камеру");
        } catch (Throwable t) {
            fail("Камера: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }

    private void fail(String msg) {
        Callback c = cb;
        if (c != null) {
            c.onError(msg);
        }
    }

    private String chooseCamera(CameraManager cm) throws CameraAccessException {
        String any = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (any == null) {
                any = id;
            }
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return any;
    }

    /** Берём ближайший к 640×480 размер, не меньше него; иначе самый большой из доступных. */
    private Size chooseSize(StreamConfigurationMap map) {
        if (map == null) {
            return null;
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        long bestArea = Long.MAX_VALUE;
        Size biggest = sizes[0];
        for (Size s : sizes) {
            long a = (long) s.getWidth() * s.getHeight();
            if (a > (long) biggest.getWidth() * biggest.getHeight()) {
                biggest = s;
            }
            if (s.getWidth() >= 640 && s.getHeight() >= 480 && a < bestArea
                    && a <= 1280L * 960L) {
                best = s;
                bestArea = a;
            }
        }
        return best != null ? best : biggest;
    }

    private void startSession() {
        try {
            if (device == null || reader == null) {
                return;
            }
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            open = true;
                            applyMode();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Не удалось настроить камеру");
                        }
                    }, handler);
        } catch (Throwable t) {
            fail("Камера: " + t.getMessage());
        }
    }

    /** Заморозить экспозицию и баланс белого (для ИК-теста: камера не должна сама менять яркость). */
    public void setAeLock(boolean on) {
        aeLock = on;
        setMode(mode);
    }

    /** Сменить режим на лету: 0 авто, 1 ночь, 2 ночь+. */
    public void setMode(int m) {
        mode = m;
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    applyMode();
                }
            });
        }
    }

    private void applyMode() {
        CameraCaptureSession s = session;
        CameraDevice d = device;
        if (s == null || d == null || reader == null) {
            return;
        }
        try {
            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            String desc = "авто";

            if (mode >= 1) {
                // ночь: ловим самый низкий диапазон частоты кадров (длиннее выдержка) и поднимаем яркость
                Range<Integer>[] fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (fps != null && fps.length > 0) {
                    Range<Integer> low = fps[0];
                    for (Range<Integer> r : fps) {
                        if (r.getUpper() < low.getUpper()
                                || (r.getUpper().equals(low.getUpper()) && r.getLower() < low.getLower())) {
                            low = r;
                        }
                    }
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, low);
                }
                Range<Integer> comp = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (comp != null) {
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, comp.getUpper());
                }
                desc = "ночь";
                int[] nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
                if (nr != null) {
                    for (int v : nr) {
                        if (v == CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) {
                            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                            break;
                        }
                    }
                }
            }
            if (mode >= 2 && manualSupported()) {
                Range<Long> exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                Range<Integer> iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (exp != null && iso != null) {
                    long t = Math.min(exp.getUpper(), 125_000_000L); // не длиннее 1/8 с
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t);
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.getUpper());
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, t);
                    desc = "ночь+ (1/" + Math.round(1e9 / t) + " с, ISO " + iso.getUpper() + ")";
                }
            } else if (mode >= 2) {
                desc = "ночь (ручной режим камерой не поддерживается)";
            }
            if (aeLock) {
                b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                desc += ", экспозиция заморожена";
            }
            s.setRepeatingRequest(b.build(), null, handler);
            Callback c = cb;
            if (c != null) {
                c.onInfo(desc);
            }
        } catch (Throwable t) {
            fail("Режим камеры: " + t.getMessage());
        }
    }

    private boolean manualSupported() {
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) {
            return false;
        }
        for (int c : caps) {
            if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                return true;
            }
        }
        return false;
    }

    private void handleImage(ImageReader r, int w, int h) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) {
                return;
            }
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowStride = p.getRowStride();
            byte[] y = ybuf;
            if (y == null) {
                return;
            }
            if (rowStride == w) {
                buf.get(y, 0, Math.min(y.length, buf.remaining()));
            } else {
                for (int row = 0; row < h; row++) {
                    int pos = row * rowStride;
                    if (pos >= buf.limit()) {
                        break;
                    }
                    buf.position(pos);
                    buf.get(y, row * w, Math.min(w, buf.remaining()));
                }
            }
            Callback c = cb;
            if (c != null) {
                c.onFrame(y, w, h);
            }
        } catch (Throwable t) {
            // пропускаем кадр
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    public synchronized void close() {
        open = false;
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignored) {
            // ничего
        }
        session = null;
        try {
            if (device != null) {
                device.close();
            }
        } catch (Throwable ignored) {
            // ничего
        }
        device = null;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignored) {
            // ничего
        }
        reader = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
    }
}
