package kz.bulaevo.radar.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.ConsumerIrManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Что камеры телефона умеют для «зрения в темноте» и измерения расстояний:
 * датчик глубины (ToF), RAW, длинная выдержка, калиброванный фокус (дальномер по фокусу), ИК-порт.
 * Ищет и «скрытые» камеры: прошивки (MIUI и др.) часто не показывают их в общем списке.
 */
public final class CameraProbe {
    private CameraProbe() {
    }

    // Константы API 29, записаны числами, чтобы собиралось и работало на старых Android.
    private static final int CAP_LOGICAL_MULTI = 11;
    private static final int CAP_MONOCHROME = 12;
    private static final int CFA_MONO = 5;
    private static final int CFA_NIR = 6;

    /** Итоги, которые нужны для вывода в конце отчёта. */
    private static final class Found {
        boolean depth;
        boolean nir;
        boolean raw;
        boolean longExposure;
        boolean calibratedFocus;
        int hidden;
    }

    public static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        Found f = new Found();
        sb.append("КАМЕРЫ: ГЛУБИНА, ТЕМНОТА, ИК\n");
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append("\n\n");

        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            sb.append("Камеры недоступны.\n");
        } else {
            Set<String> listed = new LinkedHashSet<>();
            try {
                for (String id : cm.getCameraIdList()) {
                    listed.add(id);
                }
            } catch (Throwable t) {
                sb.append("Список камер не прочитан: ").append(t.getMessage()).append("\n");
            }
            Set<String> all = new LinkedHashSet<>(listed);
            // Физические камеры внутри «логической» (Android 9+).
            List<String> probe = new ArrayList<>(listed);
            for (String id : probe) {
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        all.addAll(cm.getCameraCharacteristics(id).getPhysicalCameraIds());
                    } catch (Throwable ignored) {
                        // нет так нет
                    }
                }
            }
            // Скрытые камеры: пробуем номера, которых нет в списке.
            for (int i = 0; i <= 9; i++) {
                String id = Integer.toString(i);
                if (all.contains(id)) {
                    continue;
                }
                try {
                    cm.getCameraCharacteristics(id);
                    all.add(id);
                } catch (Throwable ignored) {
                    // такой камеры нет
                }
            }
            for (String id : all) {
                boolean hidden = !listed.contains(id);
                if (hidden) {
                    f.hidden++;
                }
                try {
                    describe(sb, id, cm.getCameraCharacteristics(id), hidden, f);
                } catch (Throwable t) {
                    sb.append("Камера ").append(id).append(": не прочитана (").append(t.getMessage()).append(")\n\n");
                }
            }
        }

        sb.append("ИК-ПОРТ (пульт)\n");
        sb.append("  ").append(irLine(c)).append("\n\n");

        sb.append("ВЫВОД\n");
        sb.append(f.depth
                ? "  ✔ Есть датчик глубины: можно мерить расстояние камерой даже в полной темноте.\n"
                : "  ✘ Датчика глубины (ToF) для приложений нет. Если в телефоне есть «камера глубины» 2 Мп — это "
                + "обычная камера для размытия фона: своей подсветки у неё нет, в темноте она слепа.\n");
        if (f.nir) {
            sb.append("  ✔ Есть инфракрасная или монохромная камера: она чувствительнее в темноте.\n");
        }
        sb.append(f.raw
                ? "  ✔ RAW есть: можно вытягивать картинку из почти чёрного кадра.\n"
                : "  ✘ RAW нет: ночное усиление только по обработанным кадрам.\n");
        sb.append(f.longExposure
                ? "  ✔ Длинная выдержка (от 1/4 с): больше света в каждом кадре.\n"
                : "  ✘ Длинной выдержки нет: свет добирается только накоплением кадров.\n");
        sb.append(f.calibratedFocus
                ? "  ✔ Фокус откалиброван: по положению линзы можно мерить расстояние до предмета (при свете).\n"
                : "  ✘ Фокус не откалиброван: «дальномер по фокусу» будет только примерным.\n");
        if (f.hidden > 0) {
            sb.append("  ℹ Скрытых камер: ").append(f.hidden).append(". Прошивка не показывает их приложениям, но "
                    + "открыть их, возможно, получится.\n");
        }
        sb.append("\nПРОВЕРКА ИК В КАМЕРЕ\n"
                + "Возьмите пульт от телевизора, откройте обычную камеру телефона, направьте пульт в объектив и "
                + "нажимайте кнопку. Видна вспышка — эта камера видит ближний ИК, и с ИК-фонариком (850 нм) она "
                + "станет прибором ночного видения. Проверьте отдельно заднюю и фронтальную камеры: фронтальная "
                + "чаще пропускает ИК.\n");
        return sb.toString();
    }

    private static void describe(StringBuilder sb, String id, CameraCharacteristics cc, boolean hidden, Found f) {
        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        String side = facing == null ? "?" : (facing == CameraCharacteristics.LENS_FACING_BACK ? "задняя"
                : (facing == CameraCharacteristics.LENS_FACING_FRONT ? "фронтальная" : "внешняя"));
        sb.append("Камера ").append(id).append(": ").append(side);
        if (hidden) {
            sb.append(" (СКРЫТАЯ)");
        }
        sb.append('\n');

        Size px = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (px != null) {
            sb.append(String.format(Locale.US, "  Матрица: %.1f Мп", px.getWidth() * (double) px.getHeight() / 1e6));
            if (phys != null && focals != null && focals.length > 0 && focals[0] > 0) {
                double fov = 2 * Math.toDegrees(Math.atan(phys.getWidth() / (2.0 * focals[0])));
                sb.append(String.format(Locale.US, ", угол обзора ~%.0f°", fov));
            }
            sb.append('\n');
        }

        Integer level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        sb.append("  Уровень Camera2: ").append(levelName(level)).append('\n');

        int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean raw = false;
        boolean manual = false;
        boolean depth = false;
        boolean logical = false;
        boolean mono = false;
        if (caps != null) {
            for (int cp : caps) {
                if (cp == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    raw = true;
                } else if (cp == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                    manual = true;
                } else if (cp == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) {
                    depth = true;
                } else if (cp == CAP_LOGICAL_MULTI) {
                    logical = true;
                } else if (cp == CAP_MONOCHROME) {
                    mono = true;
                }
            }
        }

        boolean depth16 = false;
        boolean rawFmt = false;
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            int[] fmts = map.getOutputFormats();
            if (fmts != null) {
                for (int fm : fmts) {
                    if (fm == ImageFormat.DEPTH16 || fm == ImageFormat.DEPTH_POINT_CLOUD) {
                        depth16 = true;
                    } else if (fm == ImageFormat.RAW_SENSOR || fm == ImageFormat.RAW10 || fm == ImageFormat.RAW12) {
                        rawFmt = true;
                    }
                }
            }
        }

        if (depth || depth16) {
            f.depth = true;
            sb.append("  ★ ДАТЧИК ГЛУБИНЫ: выдаёт расстояния").append(depth16 ? " (DEPTH16)" : "").append('\n');
        }
        if (raw || rawFmt) {
            f.raw = true;
        }
        sb.append("  RAW: ").append(raw || rawFmt ? "да" : "нет")
                .append(", ручной режим: ").append(manual ? "да" : "нет");
        if (logical) {
            sb.append(", объединяет несколько камер");
        }
        sb.append('\n');

        Integer cfa = cc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (mono || (cfa != null && (cfa == CFA_MONO || cfa == CFA_NIR))) {
            f.nir = true;
            sb.append("  ★ ").append(cfa != null && cfa == CFA_NIR ? "ИНФРАКРАСНАЯ камера" : "Монохромная камера").append('\n');
        }

        Range<Integer> iso = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exp = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (iso != null || exp != null) {
            sb.append("  ");
            if (iso != null) {
                sb.append("ISO до ").append(iso.getUpper());
            }
            if (exp != null) {
                double s = exp.getUpper() / 1e9;
                if (s >= 0.25) {
                    f.longExposure = true;
                }
                sb.append(iso != null ? ", " : "").append("выдержка до ").append(seconds(s));
            }
            sb.append('\n');
        }

        Float minFocus = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Integer calib = cc.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        if (minFocus == null || minFocus == 0f) {
            sb.append("  Фокус: постоянный\n");
        } else {
            boolean calibrated = calib != null && calib == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED;
            if (calibrated) {
                f.calibratedFocus = true;
            }
            sb.append(String.format(Locale.US, "  Фокус: от %.0f см, ", 100.0 / minFocus))
                    .append(calibName(calib)).append('\n');
        }
        sb.append('\n');
    }

    private static String seconds(double s) {
        if (s >= 1) {
            return String.format(Locale.US, "%.1f с", s);
        }
        return "1/" + Math.max(1, Math.round(1 / s)) + " с";
    }

    private static String levelName(Integer level) {
        if (level == null) {
            return "?";
        }
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY (самый простой)";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3 (максимальный)";
            default:
                return level == 4 ? "EXTERNAL" : "код " + level;
        }
    }

    private static String calibName(Integer calib) {
        if (calib == null) {
            return "калибровка неизвестна";
        }
        switch (calib) {
            case CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED:
                return "откалиброван (расстояние в метрах)";
            case CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE:
                return "калибровка примерная";
            default:
                return "не откалиброван";
        }
    }

    private static String irLine(Context c) {
        ConsumerIrManager ir = irManager(c);
        if (ir == null || !ir.hasIrEmitter()) {
            return "нет";
        }
        StringBuilder sb = new StringBuilder("есть");
        try {
            ConsumerIrManager.CarrierFrequencyRange[] fr = ir.getCarrierFrequencies();
            if (fr != null && fr.length > 0) {
                sb.append(String.format(Locale.US, ", частоты %.0f–%.0f кГц",
                        fr[0].getMinFrequency() / 1000.0, fr[fr.length - 1].getMaxFrequency() / 1000.0));
            }
        } catch (Throwable ignored) {
            // частоты не важны
        }
        sb.append(". Светит ИК-светодиодом на верхнем торце: для подсветки он слабый (пульт, а не фонарь), "
                + "но кнопка «Мигнуть ИК-портом» покажет, видит ли камера его свет.");
        return sb.toString();
    }

    public static ConsumerIrManager irManager(Context c) {
        try {
            return (ConsumerIrManager) c.getSystemService(Context.CONSUMER_IR_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Мигает ИК-портом заданное время (блокирует поток). Возвращает текст ошибки или null. */
    public static String blinkIr(Context c, int seconds) {
        ConsumerIrManager ir = irManager(c);
        if (ir == null || !ir.hasIrEmitter()) {
            return "ИК-порта нет";
        }
        // 38 кГц, как у пультов: 0,2 с свет, 0,1 с пауза, 6 раз — 1,8 с за один вызов (лимит — 2 с).
        int[] pattern = new int[12];
        for (int i = 0; i < pattern.length; i += 2) {
            pattern[i] = 200000;
            pattern[i + 1] = 100000;
        }
        long end = System.currentTimeMillis() + seconds * 1000L;
        try {
            while (System.currentTimeMillis() < end) {
                ir.transmit(38000, pattern);
            }
        } catch (Throwable t) {
            return "ИК-порт не ответил: " + t.getMessage();
        }
        return null;
    }
}
