package kz.bulaevo.radar.ai;

import android.content.Context;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Распознавание предметов (SSD MobileNet, обучена на наборе COCO: 80 видов предметов — люди, мебель, техника,
 * животные, транспорт…). Модель скачивается один раз (~4 МБ). Возвращает рамки и названия по-русски.
 */
public final class ObjectDetector {
    private static final String URL =
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/coco_ssd_mobilenet_v1_1.0_quant_2018_06_29.zip";

    public static final class Det {
        /** Рамка в долях кадра (0..1): левый, верхний, правый, нижний край. */
        public float l;
        public float t;
        public float r;
        public float b;
        public float score;
        public String name;
    }

    private Interpreter tfl;
    private ByteBuffer in;
    public int inW;
    public int inH;
    private boolean inFloat;
    private final List<String> labels = new ArrayList<>();
    private int iBoxes = -1;
    private int iClasses = -1;
    private int iScores = -1;
    private int iCount = -1;
    private int maxDet;

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "objects");
    }

    private static File modelFile(Context c) {
        return new File(dir(c), "detect.tflite");
    }

    private static File labelFile(Context c) {
        return new File(dir(c), "labelmap.txt");
    }

    public static boolean downloaded(Context c) {
        File m = modelFile(c);
        return m.isFile() && m.length() > 500_000 && DepthModel.isTflite(m) && labelFile(c).isFile();
    }

    /** Скачать и распаковать. Возвращает null или текст ошибки. */
    public static String download(Context c, DepthModel.Progress p) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            return "не удалось создать папку";
        }
        File zip = new File(d, "download.zip");
        try {
            DepthModel.fetch(URL, zip, p);
            boolean gotModel = false;
            boolean gotLabels = false;
            try (ZipInputStream zs = new ZipInputStream(new FileInputStream(zip))) {
                ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zs.getNextEntry()) != null) {
                    String name = new File(e.getName()).getName();
                    File out;
                    if (name.endsWith(".tflite")) {
                        out = modelFile(c);
                        gotModel = true;
                    } else if (name.endsWith(".txt")) {
                        out = labelFile(c);
                        gotLabels = true;
                    } else {
                        continue;
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zs.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                }
            }
            if (!gotModel || !gotLabels || !downloaded(c)) {
                return "в архиве нет модели";
            }
            return null;
        } catch (Throwable t) {
            return t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
    }

    public String load(Context c) {
        close();
        try (FileInputStream fis = new FileInputStream(modelFile(c)); FileChannel ch = fis.getChannel()) {
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(2);
            tfl = new Interpreter(mb, o);
        } catch (Throwable t) {
            tfl = null;
            return "модель предметов не загрузилась: " + t.getMessage();
        }
        labels.clear();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(labelFile(c)),
                Charset.forName("UTF-8")))) {
            String line;
            while ((line = br.readLine()) != null) {
                labels.add(line.trim());
            }
        } catch (Throwable t) {
            return "нет списка предметов";
        }
        Tensor ti = tfl.getInputTensor(0);
        int[] s = ti.shape();
        inH = s[1];
        inW = s[2];
        inFloat = ti.dataType() == DataType.FLOAT32;
        in = ByteBuffer.allocateDirect(inW * inH * 3 * (inFloat ? 4 : 1)).order(ByteOrder.nativeOrder());
        // выходы: рамки [1,N,4], число [1], и два [1,N] — классы и уверенность (разберёмся по значениям)
        List<Integer> pair = new ArrayList<>();
        for (int i = 0; i < tfl.getOutputTensorCount(); i++) {
            int[] os = tfl.getOutputTensor(i).shape();
            if (os.length == 3 && os[2] == 4) {
                iBoxes = i;
                maxDet = os[1];
            } else if (os.length == 1 || (os.length == 2 && os[1] == 1)) {
                iCount = i;
            } else if (os.length == 2) {
                pair.add(i);
            }
        }
        if (iBoxes < 0 || iCount < 0 || pair.size() != 2) {
            return "неизвестный формат модели предметов";
        }
        iClasses = pair.get(0);
        iScores = pair.get(1);
        return null;
    }

    public boolean loaded() {
        return tfl != null;
    }

    /** rgb — вертикальный кадр inW×inH (r,g,b подряд, 0..255). */
    public List<Det> detect(byte[] rgb, float minScore) {
        in.rewind();
        if (inFloat) {
            for (int i = 0; i < inW * inH * 3; i++) {
                in.putFloat(((rgb[i] & 0xFF) - 127.5f) / 127.5f);
            }
        } else {
            in.put(rgb, 0, inW * inH * 3);
        }
        in.rewind();
        float[][][] boxes = new float[1][maxDet][4];
        float[][] a = new float[1][maxDet];
        float[][] b = new float[1][maxDet];
        float[] cnt = new float[1];
        Map<Integer, Object> outs = new HashMap<>();
        outs.put(iBoxes, boxes);
        outs.put(iClasses, a);
        outs.put(iScores, b);
        outs.put(iCount, cnt);
        tfl.runForMultipleInputsOutputs(new Object[]{in}, outs);
        // где целые числа — там номера классов, где доли 0..1 — уверенность
        float[] classes = a[0];
        float[] scores = b[0];
        if (!integers(a[0]) && integers(b[0])) {
            classes = b[0];
            scores = a[0];
        }
        int n = Math.min(maxDet, Math.max(0, Math.round(cnt[0])));
        List<Det> out = new ArrayList<>();
        // в этой модели список начинается с "???" — номер класса сдвинут на 1
        int offset = !labels.isEmpty() && labels.get(0).startsWith("???") ? 1 : 0;
        for (int i = 0; i < n; i++) {
            if (scores[i] < minScore) {
                continue;
            }
            int k = Math.round(classes[i]) + offset;
            String en = k >= 0 && k < labels.size() ? labels.get(k) : "?";
            if (en.startsWith("???")) {
                continue;
            }
            Det d = new Det();
            d.t = clamp(boxes[0][i][0]);
            d.l = clamp(boxes[0][i][1]);
            d.b = clamp(boxes[0][i][2]);
            d.r = clamp(boxes[0][i][3]);
            d.score = scores[i];
            d.name = CocoRu.name(en);
            out.add(d);
        }
        return out;
    }

    private static boolean integers(float[] v) {
        for (float x : v) {
            if (Math.abs(x - Math.round(x)) > 1e-3) {
                return false;
            }
        }
        return true;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public void close() {
        if (tfl != null) {
            tfl.close();
            tfl = null;
        }
    }
}
