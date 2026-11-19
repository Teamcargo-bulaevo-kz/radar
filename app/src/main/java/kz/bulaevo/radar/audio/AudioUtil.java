package kz.bulaevo.radar.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.util.ArrayList;
import java.util.List;

/** Общие помощники для звука: открытие микрофона без обработки, громкость, наушники. */
public final class AudioUtil {
    private AudioUtil() {
    }

    public static final int FS = 48000;

    /** Открытый микрофон вместе с названием источника и отключёнными эффектами. */
    public static final class Rec {
        public AudioRecord rec;
        public String source = "?";
        public final List<AudioEffect> effects = new ArrayList<>();

        public void release() {
            for (AudioEffect e : effects) {
                try {
                    e.release();
                } catch (Throwable ignored) {
                    // ничего
                }
            }
            effects.clear();
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                    // уже остановлен
                }
                try {
                    rec.release();
                } catch (Throwable ignored) {
                    // ничего
                }
                rec = null;
            }
        }
    }

    /**
     * Открыть микрофон. Порядок: "сырой" (UNPROCESSED) → распознавание речи → обычный.
     * Сырой источник важен: обычный микрофон телефона режет ультразвук шумоподавлением и АРУ.
     *
     * @param minSamples сколько отсчётов должно помещаться в буфер (запас в 4 раза добавляется)
     */
    public static Rec openRecorder(Context ctx, int minSamples) throws Exception {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean rawOk = false;
        try {
            String p = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
            rawOk = "true".equals(p);
        } catch (Throwable ignored) {
            // нет свойства
        }
        int minBuf = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new Exception("Микрофон не поддерживает 48 кГц");
        }
        int buf = Math.max(minBuf, minSamples * 2 * 4);

        List<int[]> tries = new ArrayList<>();
        if (rawOk) {
            tries.add(new int[]{MediaRecorder.AudioSource.UNPROCESSED, 0});
        }
        tries.add(new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, 1});
        tries.add(new int[]{MediaRecorder.AudioSource.MIC, 2});
        String[] names = {"сырой микрофон", "микрофон (распознавание)", "обычный микрофон"};

        Exception last = null;
        for (int[] t : tries) {
            AudioRecord r = null;
            try {
                r = new AudioRecord(t[0], FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    Rec out = new Rec();
                    out.rec = r;
                    out.source = names[t[1]];
                    disableEffects(r.getAudioSessionId(), out.effects);
                    return out;
                }
                r.release();
            } catch (SecurityException se) {
                throw new Exception("Нет разрешения на микрофон");
            } catch (Exception e) {
                last = e;
                if (r != null) {
                    try {
                        r.release();
                    } catch (Throwable ignored) {
                        // ничего
                    }
                }
            }
        }
        throw new Exception("Не удалось открыть микрофон" + (last != null ? ": " + last.getMessage() : ""));
    }

    private static void disableEffects(int session, List<AudioEffect> keep) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl a = AutomaticGainControl.create(session);
                if (a != null) {
                    a.setEnabled(false);
                    keep.add(a);
                }
            }
        } catch (Throwable ignored) {
            // не критично
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor a = NoiseSuppressor.create(session);
                if (a != null) {
                    a.setEnabled(false);
                    keep.add(a);
                }
            }
        } catch (Throwable ignored) {
            // не критично
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler a = AcousticEchoCanceler.create(session);
                if (a != null) {
                    a.setEnabled(false);
                    keep.add(a);
                }
            }
        } catch (Throwable ignored) {
            // не критично
        }
    }

    /** Подключены ли наушники/гарнитура: тогда звук идёт не через динамик телефона. */
    public static boolean headphonesConnected(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devs) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // считаем, что нет
        }
        return false;
    }

    /** Проводные наушники (3.5 мм или USB). Bluetooth не считается: он режет частоты и добавляет задержку. */
    public static boolean wiredHeadphones(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devs) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // считаем, что нет
        }
        return false;
    }

    public static int musicVolumePercent(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
    }

    public static void setMusicVolumeMax(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
    }

    public static String property(Context ctx, String key) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            String v = am.getProperty(key);
            return v == null ? "нет данных" : v;
        } catch (Throwable t) {
            return "нет данных";
        }
    }
}
