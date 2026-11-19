package kz.bulaevo.radar;

/** Кто умеет запросить разрешения у пользователя (реализует MainActivity). */
public interface PermissionHost {
    /** Выполнить action, когда разрешение на микрофон есть (при необходимости спросит). */
    void requireAudio(Runnable action);

    /** Выполнить action, когда разрешение на камеру есть (при необходимости спросит). */
    void requireCamera(Runnable action);

    /** Выполнить action, когда можно сохранять снимки в галерею (на Android 9 и ниже нужно разрешение). */
    void requireStorage(Runnable action);
}
