package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.PermissionHost;
import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.audio.AudioUtil;
import kz.bulaevo.radar.audio.BandCalibrator;
import kz.bulaevo.radar.camera.CameraProbe;
import kz.bulaevo.radar.dsp.Bands;
import kz.bulaevo.radar.dsp.ResponseAnalyzer;
import kz.bulaevo.radar.dsp.SelfTest;

/** Вкладка "Сервис": что умеет телефон, подбор диапазона сонара, самотест математики, инструкция. */
public final class ServiceTab implements Tab {
    private final Context c;
    private final SonarHub hub;
    private final PermissionHost host;
    private final BandCalibrator calibrator;
    private final ScrollView root;
    private final TextView status;
    private final TextView out;
    private final PlotView resp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button quietBtn;

    public ServiceTab(Context ctx, SonarHub hub, PermissionHost host) {
        this.c = ctx;
        this.hub = hub;
        this.host = host;
        this.calibrator = new BandCalibrator(ctx);

        root = new ScrollView(ctx);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.col(ctx);
        col.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 8), Ui.dpi(ctx, 8), Ui.dpi(ctx, 16));
        root.addView(col);

        TextView title = Ui.label(ctx, "Сервис и диагностика", 18f, Ui.ACC);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        LinearLayout r1 = Ui.row(ctx);
        r1.addView(Ui.button(ctx, "Подобрать диапазон (свип-тест)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCalibration();
            }
        }), Ui.cell(ctx));
        col.addView(r1);
        LinearLayout r2 = Ui.row(ctx);
        r2.addView(Ui.button(ctx, "Самотест математики", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSelfTest();
            }
        }), Ui.cell(ctx));
        r2.addView(Ui.button(ctx, "Об устройстве", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeviceInfo();
            }
        }), Ui.cell(ctx));
        col.addView(r2);
        LinearLayout r3 = Ui.row(ctx);
        r3.addView(Ui.button(ctx, "Громкость на максимум", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AudioUtil.setMusicVolumeMax(ServiceTab.this.c);
                showDeviceInfo();
            }
        }), Ui.cell(ctx));
        r3.addView(Ui.button(ctx, "Как пользоваться", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("");
                resp.setVisibility(View.GONE);
                out.setText(HELP);
            }
        }), Ui.cell(ctx));
        col.addView(r3);

        LinearLayout r4 = Ui.row(ctx);
        r4.addView(Ui.button(ctx, "Проверка наушников (как излучателей)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runHeadphoneTest();
            }
        }), Ui.cell(ctx));
        col.addView(r4);

        LinearLayout r6 = Ui.row(ctx);
        quietBtn = Ui.button(ctx, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ServiceTab.this.hub.setQuiet(!ServiceTab.this.hub.st.quiet);
                updateQuiet();
            }
        });
        r6.addView(quietBtn, Ui.cell(ctx));
        col.addView(r6, 1);
        updateQuiet();

        LinearLayout r5 = Ui.row(ctx);
        r5.addView(Ui.button(ctx, "Камеры: глубина, темнота, ИК", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCameraProbe();
            }
        }), Ui.cell(ctx));
        r5.addView(Ui.button(ctx, "Мигнуть ИК-портом (15 с)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runIrBlink();
            }
        }), Ui.cell(ctx));
        col.addView(r5);

        status = Ui.label(ctx, "", 13f, Ui.WARN);
        status.setPadding(0, Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 2));
        col.addView(status);

        resp = new PlotView(ctx);
        resp.setTitle("Что проходит «динамик → микрофон»: дБ над шумом по частоте");
        resp.setDataExtent(0f, 24000f);
        resp.setRange(0f, 24000f, 0f, 45f);
        resp.setGrid(4000f, "%.0f", 10f, "%.0f");
        resp.setVisibility(View.GONE);
        col.addView(resp, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpi(ctx, 190)));

        out = Ui.label(ctx, HELP, 12f, Ui.FG);
        out.setTypeface(Typeface.MONOSPACE);
        out.setPadding(0, Ui.dpi(ctx, 6), 0, 0);
        col.addView(out);
    }

    private static final String HELP =
            "КАК ПОЛЬЗОВАТЬСЯ\n\n"
                    + "1. Громкость медиа — на максимум, наушники отключить (иначе звук идёт не из динамика).\n"
                    + "2. Вкладка «Сервис» → «Подобрать диапазон»: 8 секунд телефон проверяет, какие частоты проходят "
                    + "динамик→микрофон, и сам выбирает лучший диапазон.\n"
                    + "3. Вкладка «Карта»: «Старт». Держите телефон горизонтально, нижним краем (там динамик) вперёд, "
                    + "как пульт, и медленно повернитесь на месте на 360° за 15–20 секунд. На карте проявятся стены "
                    + "и предметы, голубая линия — очертания комнаты. «Обнулить фон»: направьте в пустое пространство "
                    + "— уберёт ложное эхо корпуса.\n"
                    + "4. «Дальн.»: расстояние до ближайшего предмета, как лазерная рулетка. Парктроник: гудки и вибро.\n"
                    + "5. «Ходьба»: ориентирование в темноте и на улице. Водите телефоном влево-вправо, как фонарём, "
                    + "перед собой: гудки чаще и выше, чем ближе препятствие, веер показывает, где свободнее.\n"
                    + "6. «Движ.»: неподвижное исчезает, движущееся светится. «Охрана» включает сигнализацию.\n"
                    + "7. «Спектр»: все звуки вокруг до 24 кГц, в том числе ультразвук.\n"
                    + "8. «Камера»: ночное видение из слабого света. В полной темноте камера слепая, там работает сонар.\n"
                    + "9. «Металл»: магнитометр — поиск металла и проводки рядом.\n\n"
                    + "ЧЕСТНО О ВОЗМОЖНОСТЯХ\n\n"
                    + "Это звуковой радар (сонар), а не камера. Он измеряет расстояния до стен и предметов с точностью "
                    + "несколько сантиметров и рисует очертания, но картинку «как фото» не даёт. Лучше всего видны большие "
                    + "твёрдые поверхности: стены, шкафы, люди. Мягкое (диваны, шторы) поглощает звук. Дальность в комнате "
                    + "3–7 м. Ультразвук может беспокоить животных и некоторых людей: если слышен свист или мешает — "
                    + "уменьшите громкость.";

    private void updateQuiet() {
        quietBtn.setText(hub.st.quiet
                ? "🌙 Тихий режим (ночь): ультразвук, вибрация вместо звуков — нажать для обычного"
                : "🔊 Обычный режим звука — нажать для тихого (ночь)");
        Ui.setOn(quietBtn, hub.st.quiet);
    }

    @Override
    public String title() {
        return "Сервис";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        // ничего: экран статический
    }

    /** Запуск действий без нажатий: нужен для автопроверки в эмуляторе. */
    public void debugAction(String act) {
        if ("selftest".equals(act)) {
            runSelfTest();
        } else if ("calibrate".equals(act)) {
            runCalibration();
        } else if ("info".equals(act)) {
            showDeviceInfo();
        } else if ("headphones".equals(act)) {
            runHeadphoneTest();
        } else if ("cameras".equals(act)) {
            showCameraProbe();
        }
    }

    @Override
    public void onHide() {
        calibrator.cancel();
    }

    // ---- самотест ----

    private void runSelfTest() {
        status.setText("Самотест: идёт симуляция комнаты…");
        resp.setVisibility(View.GONE);
        out.setText("");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SelfTest.Report r = SelfTest.runAll();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(r.failures == 0 ? "Самотест: всё в порядке" : "Самотест: есть ошибки!");
                        out.setText(r.text() + String.format(Locale.US, "\n\nОбработка одного кадра на этом телефоне: %.2f мс", r.msPerFrame));
                    }
                });
            }
        }, "selftest").start();
    }

    // ---- проверка наушников: излучает ли каждый наушник ультразвук в воздух ----

    private final double[][] hp = new double[2][Bands.NAMES.length];

    private void runHeadphoneTest() {
        if (calibrator.isBusy()) {
            return;
        }
        resp.setVisibility(View.GONE);
        if (!AudioUtil.wiredHeadphones(c)) {
            status.setText("Подключите проводные наушники");
            out.setText("Нужны проводные наушники (разъём 3.5 мм или USB-C). Bluetooth не подходит: он режет высокие "
                    + "частоты и задерживает звук.\n\nЗачем тест: можно ли использовать левый и правый наушник как два "
                    + "излучателя вместо одного динамика телефона. Для этого они должны излучать ультразвук в воздух, "
                    + "а вставные наушники излучают в основном в ухо.\n\nКак проверить: СНИМИТЕ наушники с ушей, "
                    + "положите их на стол динамиками к нижнему краю телефона на расстоянии около 5 см, подключите и "
                    + "нажмите кнопку ещё раз. Будет тихий свист около полуминуты.");
            return;
        }
        host.requireAudio(new Runnable() {
            @Override
            public void run() {
                hub.stop();
                out.setText("Наушники НЕ должны быть на ушах: во время теста играет свист.\n"
                        + "Положите их динамиками к нижнему краю телефона (микрофон) на расстоянии около 5 см.");
                headphoneChannel(1);
            }
        });
    }

    private void headphoneChannel(final int channel) {
        final String name = channel == 1 ? "левый" : "правый";
        calibrator.start(new BandCalibrator.Callback() {
            @Override
            public void onProgress(int percent, String text) {
                status.setText("Наушники, " + name + ": " + text + " " + percent + "%");
            }

            @Override
            public void onDone(ResponseAnalyzer.Result r, ResponseAnalyzer.Pick pick, String source) {
                System.arraycopy(pick.scores, 0, hp[channel - 1], 0, Bands.NAMES.length);
                if (channel == 1) {
                    headphoneChannel(2);
                } else {
                    showHeadphoneResult();
                }
            }

            @Override
            public void onError(String message) {
                status.setText("Тест наушников не удался: " + message);
            }
        }, channel, 0.5, 12000);
    }

    private void showHeadphoneResult() {
        StringBuilder sb = new StringBuilder("Сила ультразвука из наушников у микрофона телефона (дБ, выше — лучше):\n\n");
        sb.append(String.format(Locale.US, "%-14s %6s %8s\n", "", "левый", "правый"));
        double bestL = 0;
        double bestR = 0;
        for (int b = 0; b < Bands.NAMES.length; b++) {
            if (Bands.AUDIBLE[b]) {
                continue;
            }
            sb.append(String.format(Locale.US, "%-14s %6.0f %8.0f\n", Bands.NAMES[b], hp[0][b], hp[1][b]));
            bestL = Math.max(bestL, hp[0][b]);
            bestR = Math.max(bestR, hp[1][b]);
        }
        sb.append('\n');
        if (bestL >= 15 && bestR >= 15) {
            status.setText("Оба наушника излучают ультразвук");
            sb.append("Хорошо: оба наушника годятся как два излучателя. Стерео-сонар с определением направления "
                    + "без вращения телефона можно делать.");
        } else if (bestL >= 15 || bestR >= 15) {
            status.setText("Ультразвук идёт только из одного наушника");
            sb.append("Излучает только один наушник (" + (bestL >= 15 ? "левый" : "правый") + "). Проверьте, как они лежат; "
                    + "если так и есть, для двух излучателей нужны другие наушники.");
        } else {
            status.setText("Наушники не излучают ультразвук в воздух");
            sb.append("Ультразвук из этих наушников почти не слышен. Обычно так у вставных: звук идёт в ухо, наружу "
                    + "почти ничего. Лучше подойдут накладные или большие закрытые наушники, повёрнутые от себя. "
                    + "Можно также попробовать слышимый диапазон: он проходит почти всегда, но свистит.");
        }
        out.setText(sb.toString());
    }

    // ---- подбор диапазона ----

    private void runCalibration() {
        if (calibrator.isBusy()) {
            return;
        }
        host.requireAudio(new Runnable() {
            @Override
            public void run() {
                hub.stop();
                if (AudioUtil.headphonesConnected(c)) {
                    status.setText("Отключите наушники: звук должен идти из динамика телефона");
                    return;
                }
                AudioUtil.setMusicVolumeMax(c);
                if (hub.st.quiet) {
                    android.widget.Toast.makeText(c, "Тихий режим, но этот тест 8 секунд посвистит — так он проверяет "
                            + "все частоты", android.widget.Toast.LENGTH_LONG).show();
                }
                out.setText("Не закрывайте динамик (нижний торец) и микрофон пальцами. Будет слышен свист — это нормально.");
                resp.setVisibility(View.GONE);
                calibrator.start(new BandCalibrator.Callback() {
                    @Override
                    public void onProgress(int percent, String text) {
                        status.setText(text + " " + percent + "%");
                    }

                    @Override
                    public void onDone(ResponseAnalyzer.Result r, ResponseAnalyzer.Pick pick, String source) {
                        showCalibration(r, pick, source);
                    }

                    @Override
                    public void onError(String message) {
                        status.setText("Тест не удался: " + message);
                    }
                });
            }
        });
    }

    private void showCalibration(ResponseAnalyzer.Result r, ResponseAnalyzer.Pick pick, String source) {
        float[] data = new float[r.count];
        for (int i = 0; i < r.count; i++) {
            data[i] = Float.isNaN(r.snr[i]) ? 0f : r.snr[i];
        }
        double bestScore = 0;
        for (double sc : pick.scores) {
            bestScore = Math.max(bestScore, sc);
        }
        resp.setDataExtent(0f, r.count * (float) ResponseAnalyzer.BIN_HZ);
        resp.setData(data, r.count);
        resp.setVisibility(View.VISIBLE);

        StringBuilder sb = new StringBuilder();
        sb.append("Микрофон: ").append(source).append("\n\nОценка диапазонов (дБ, выше — лучше):\n");
        for (int b = 0; b < Bands.NAMES.length; b++) {
            sb.append(b == pick.band && bestScore >= 8.0 ? " ► " : "   ")
                    .append(String.format(Locale.US, "%-14s %4.0f", Bands.NAMES[b], pick.scores[b])).append('\n');
        }
        if (bestScore < 8.0) {
            status.setText("Звук почти не дошёл до микрофона");
            sb.append("\nСигнал динамика микрофону не слышен, диапазон не менялся. Проверьте: громкость медиа на максимум, "
                    + "наушники отключены, динамик (нижний торец) и микрофон не закрыты пальцем или чехлом.");
        } else {
            hub.st.band = pick.band;
            hub.applySettings();
            sb.append("\nВыбран и применён: ").append(Bands.NAMES[pick.band]).append("\n");
            if (!Bands.AUDIBLE[pick.band]) {
                status.setText("Готово: тихий ультразвук работает");
                sb.append("\nУльтразвук проходит хорошо: сонар будет бесшумным.");
            } else {
                status.setText("Готово: ультразвук слабый, выбран слышимый диапазон");
                sb.append("\nУльтразвук через динамик/микрофон этого телефона проходит слабо, поэтому выбран слышимый диапазон: "
                        + "сонар будет свистеть, зато измеряет уверенно. Можно переключить диапазон кнопкой на любой вкладке.");
            }
        }
        out.setText(sb.toString());
    }

    // ---- камеры: глубина, темнота, ИК ----

    private void showCameraProbe() {
        status.setText("");
        resp.setVisibility(View.GONE);
        out.setText(CameraProbe.report(c));
    }

    private volatile boolean irBusy;

    private void runIrBlink() {
        if (irBusy) {
            return;
        }
        android.hardware.ConsumerIrManager ir = CameraProbe.irManager(c);
        resp.setVisibility(View.GONE);
        if (ir == null || !ir.hasIrEmitter()) {
            status.setText("ИК-порта в этом телефоне нет");
            return;
        }
        irBusy = true;
        status.setText("ИК-порт мигает 15 секунд…");
        out.setText("Видит ли камера инфракрасный свет:\n\n"
                + "• Есть второй телефон: откройте на нём камеру и направьте на верхний торец этого телефона "
                + "(там маленькое тёмное окошко ИК-порта). Видно мигающую точку — та камера видит ИК.\n\n"
                + "• Есть зеркало: откройте камеру этого телефона (сначала фронтальную, потом заднюю) и "
                + "направьте верхний торец на зеркало под углом, чтобы окошко было видно в кадре.\n\n"
                + "Глазом мигание обычно не видно (ИК 940 нм). Если в темноте видна тусклая красная точка — излучатель "
                + "около 850 нм, камеры видят его лучше. В камере точка должна быть ярче, чем глазом. Если не успели, "
                + "нажмите кнопку ещё раз.");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = CameraProbe.blinkIr(c, 15);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        irBusy = false;
                        status.setText(err == null ? "ИК-порт: мигание закончено" : err);
                    }
                });
            }
        }, "ir-blink").start();
    }

    // ---- сведения об устройстве ----

    private void showDeviceInfo() {
        status.setText("");
        resp.setVisibility(View.GONE);
        out.setText(deviceInfo());
    }

    private static String yn(String v) {
        if ("true".equals(v)) {
            return "да";
        }
        if ("false".equals(v)) {
            return "нет";
        }
        return v;
    }

    private String sensorLine(SensorManager sm, int type, String name) {
        return "  " + name + ": " + (sm.getDefaultSensor(type) != null ? "есть" : "нет") + "\n";
    }

    private String deviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Телефон: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

        sb.append("ЗВУК\n");
        sb.append("  Частота вывода: ").append(AudioUtil.property(c, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)).append(" Гц\n");
        sb.append("  Сырой микрофон: ").append(yn(AudioUtil.property(c, AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))).append("\n");
        sb.append("  Микрофон слышит около-ультразвук: ").append(yn(AudioUtil.property(c, AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND))).append("\n");
        sb.append("  Динамик играет около-ультразвук: ").append(yn(AudioUtil.property(c, AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND))).append("\n");
        sb.append("  Громкость медиа: ").append(AudioUtil.musicVolumePercent(c)).append("%");
        sb.append(AudioUtil.headphonesConnected(c) ? ", НАУШНИКИ ПОДКЛЮЧЕНЫ\n" : ", наушников нет\n");

        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        sb.append("\nДАТЧИКИ\n");
        sb.append(sensorLine(sm, Sensor.TYPE_GYROSCOPE, "Гироскоп"));
        sb.append(sensorLine(sm, Sensor.TYPE_GAME_ROTATION_VECTOR, "Вращение без компаса"));
        sb.append(sensorLine(sm, Sensor.TYPE_ROTATION_VECTOR, "Вектор вращения"));
        sb.append(sensorLine(sm, Sensor.TYPE_MAGNETIC_FIELD, "Магнитометр"));
        sb.append(sensorLine(sm, Sensor.TYPE_LIGHT, "Датчик света"));
        sb.append(sensorLine(sm, Sensor.TYPE_ACCELEROMETER, "Акселерометр"));

        sb.append("\nКАМЕРЫ\n");
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                String f = facing == null ? "?" : (facing == CameraCharacteristics.LENS_FACING_BACK ? "задняя"
                        : (facing == CameraCharacteristics.LENS_FACING_FRONT ? "фронтальная" : "внешняя"));
                Range<Integer> iso = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Range<Long> exp = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean manual = false;
                if (caps != null) {
                    for (int cp : caps) {
                        if (cp == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                            manual = true;
                        }
                    }
                }
                sb.append("  ").append(id).append(": ").append(f);
                if (iso != null) {
                    sb.append(", ISO до ").append(iso.getUpper());
                }
                if (exp != null) {
                    sb.append(", выдержка до 1/").append(Math.max(1, Math.round(1e9 / exp.getUpper()))).append(" с");
                }
                sb.append(manual ? ", ручной режим есть" : ", ручного режима нет").append("\n");
            }
        } catch (Throwable t) {
            sb.append("  не удалось прочитать: ").append(t.getMessage()).append("\n");
        }
        sb.append("\nТекущий диапазон сонара: ").append(Bands.NAMES[hub.st.band])
                .append(", дальность до ").append(Bands.RANGE_NAMES[hub.st.range]).append("\n");
        return sb.toString();
    }
}
