package kz.bulaevo.radar.dsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Магнитная карта поверхности: телефон проводят по стене строками (слева направо, ровно), каждая строка
 * растягивается на COLS ячеек. Получается картинка отклонений поля — где арматура, трубы, проводка, гвозди.
 * Положение телефона датчики не знают, поэтому важно вести его равномерно и одинаково для каждой строки.
 */
public final class MagScan {
    public static final int COLS = 40;

    private final List<float[]> rows = new ArrayList<>();
    private final List<Float> cur = new ArrayList<>();
    private boolean recording;
    public float baseline = Float.NaN;

    public synchronized void startRow() {
        cur.clear();
        recording = true;
    }

    /** Закончить строку. Возвращает false, если она слишком короткая и не сохранена. */
    public synchronized boolean endRow() {
        recording = false;
        if (cur.size() < 8) {
            return false;
        }
        float[] row = new float[COLS];
        int m = cur.size();
        for (int c = 0; c < COLS; c++) {
            // среднее по участку строки
            int a = c * m / COLS;
            int b = Math.max(a + 1, (c + 1) * m / COLS);
            float s = 0;
            for (int i = a; i < b; i++) {
                s += cur.get(i);
            }
            row[c] = s / (b - a);
        }
        rows.add(row);
        return true;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized void add(float fieldUt) {
        if (recording) {
            cur.add(fieldUt);
        }
    }

    public synchronized void clear() {
        rows.clear();
        cur.clear();
        recording = false;
    }

    public synchronized int rowCount() {
        return rows.size();
    }

    /**
     * Отклонения от общего среднего (µT) по сетке rowCount×COLS. Среднее по всей карте — это поле Земли
     * и самого телефона; оно вычитается.
     */
    public synchronized float[][] anomalies() {
        int r = rows.size();
        float[][] out = new float[r][COLS];
        if (r == 0) {
            return out;
        }
        double s = 0;
        for (float[] row : rows) {
            for (float v : row) {
                s += v;
            }
        }
        float mean = (float) (s / (r * COLS));
        for (int i = 0; i < r; i++) {
            for (int c = 0; c < COLS; c++) {
                out[i][c] = rows.get(i)[c] - mean;
            }
        }
        return out;
    }
}
