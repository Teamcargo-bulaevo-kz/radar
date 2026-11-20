package kz.bulaevo.radar.ai;

import android.content.Context;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Нейросеть глубины по одному кадру (MiDaS v2.1 small, лицензия MIT, intel-isl/MiDaS).
 * Модель не входит в APK: скачивается один раз при первом использовании.
 * Выход — относительная «обратная глубина»: чем больше число, тем ближе предмет. Метров модель не знает,
 * масштаб даёт сонар.
 */
public final class DepthModel {
    /** Откуда скачивать (по порядку, до первого удачного). */
    private static final String[] URLS = {
            "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite",
            "https://github.com/intel-isl/MiDaS/releases/download/v2_1/model_opt.tflite",
    };
    // Нормализация ImageNet, на которой учили MiDaS.
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    public interface Progress {
        void on(long done, long total);
    }

    private Interpreter tfl;
    private ByteBuffer in;
    private ByteBuffer out;
    private boolean inFloat;
    private boolean nchw;
    public int inW;
    public int inH;
    public int outW;
    public int outH;
    private boolean outFloat;
    private float outScale = 1f;
    private int outZero;

    public static File file(Context c) {
        return new File(new File(c.getFilesDir(), "depth"), "midas_v21_small.tflite");
    }

    public static boolean downloaded(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 1_000_000L && isTflite(f);
    }

    private static boolean isTflite(File f) {
        byte[] b = new byte[8];
        try (FileInputStream is = new FileInputStream(f)) {
            if (is.read(b) != 8) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        // У файлов TFLite на смещении 4 стоит метка "TFL3".
        return b[4] == 'T' && b[5] == 'F' && b[6] == 'L' && b[7] == '3';
    }

    /** Скачать модель (блокирует поток). Возвращает null или текст ошибки. */
    public static String download(Context c, Progress p) {
        File dst = file(c);
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return "не удалось создать папку";
        }
        File tmp = new File(dir, "download.tmp");
        String last = "нет адресов";
        for (String u : URLS) {
            try {
                fetch(u, tmp, p);
                if (!isTflite(tmp)) {
                    last = "скачан не тот файл";
                    continue;
                }
                if (dst.exists() && !dst.delete()) {
                    return "не удалось заменить старую модель";
                }
                if (!tmp.renameTo(dst)) {
                    return "не удалось сохранить модель";
                }
                return null;
            } catch (Throwable t) {
                last = t.getMessage() == null ? t.toString() : t.getMessage();
            } finally {
                if (tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
        return last;
    }

    private static void fetch(String url, File to, Progress p) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) {
                    throw new IOException("переадресация без адреса");
                }
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new IOException("сервер ответил " + code);
            }
            long total = con.getContentLengthLong();
            long done = 0;
            try (InputStream is = con.getInputStream(); OutputStream os = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long lastReport = 0;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (p != null && done - lastReport > 256 * 1024) {
                        p.on(done, total);
                        lastReport = done;
                    }
                }
            } finally {
                con.disconnect();
            }
            if (total > 0 && done != total) {
                throw new IOException("загрузка оборвалась");
            }
            return;
        }
        throw new IOException("слишком много переадресаций");
    }

    /** Загрузить модель в память. Возвращает null или текст ошибки. */
    public String load(Context c, int threads) {
        close();
        try (FileInputStream fis = new FileInputStream(file(c)); FileChannel ch = fis.getChannel()) {
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(threads);
            tfl = new Interpreter(mb, o);
        } catch (Throwable t) {
            tfl = null;
            return "модель не загрузилась: " + t.getMessage();
        }
        Tensor ti = tfl.getInputTensor(0);
        int[] s = ti.shape();
        if (s.length != 4) {
            return "неожиданная форма входа модели";
        }
        nchw = s[1] == 3 && s[3] != 3;
        inH = nchw ? s[2] : s[1];
        inW = nchw ? s[3] : s[2];
        inFloat = ti.dataType() == DataType.FLOAT32;
        in = ByteBuffer.allocateDirect(inW * inH * 3 * (inFloat ? 4 : 1)).order(ByteOrder.nativeOrder());

        Tensor to = tfl.getOutputTensor(0);
        int[] os = to.shape();
        int[] dims = new int[2];
        int k = 0;
        for (int i = os.length - 1; i >= 0 && k < 2; i--) {
            if (os[i] > 1) {
                dims[1 - k] = os[i];
                k++;
            }
        }
        if (k < 2) {
            return "неожиданная форма выхода модели";
        }
        outH = dims[0];
        outW = dims[1];
        outFloat = to.dataType() == DataType.FLOAT32;
        if (!outFloat) {
            outScale = to.quantizationParams().getScale();
            outZero = to.quantizationParams().getZeroPoint();
        }
        out = ByteBuffer.allocateDirect(to.numBytes()).order(ByteOrder.nativeOrder());
        return null;
    }

    public boolean loaded() {
        return tfl != null;
    }

    /**
     * Прогнать кадр. rgb — вертикальный кадр inW×inH, значения 0..255 по каналам (r,g,b подряд).
     * Результат: outW×outH, больше — ближе.
     */
    public void run(byte[] rgb, float[] result) {
        in.rewind();
        int n = inW * inH;
        if (nchw) {
            for (int ch = 0; ch < 3; ch++) {
                for (int i = 0; i < n; i++) {
                    put(rgb[i * 3 + ch] & 0xFF, ch);
                }
            }
        } else {
            for (int i = 0; i < n * 3; i++) {
                put(rgb[i] & 0xFF, i % 3);
            }
        }
        in.rewind();
        out.rewind();
        tfl.run(in, out);
        out.rewind();
        int m = outW * outH;
        if (outFloat) {
            out.asFloatBuffer().get(result, 0, m);
        } else {
            for (int i = 0; i < m; i++) {
                result[i] = ((out.get(i) & 0xFF) - outZero) * outScale;
            }
        }
    }

    private void put(int v, int ch) {
        if (inFloat) {
            in.putFloat((v / 255f - MEAN[ch]) / STD[ch]);
        } else {
            in.put((byte) v);
        }
    }

    public void close() {
        if (tfl != null) {
            tfl.close();
            tfl = null;
        }
    }
}
