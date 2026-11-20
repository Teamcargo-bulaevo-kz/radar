package kz.bulaevo.radar.ai;

import java.util.HashMap;
import java.util.Map;

/** Русские названия 80 предметов набора COCO. */
public final class CocoRu {
    private CocoRu() {
    }

    private static final String[] PAIRS = {
            "person", "человек", "bicycle", "велосипед", "car", "машина", "motorcycle", "мотоцикл",
            "airplane", "самолёт", "bus", "автобус", "train", "поезд", "truck", "грузовик", "boat", "лодка",
            "traffic light", "светофор", "fire hydrant", "гидрант", "stop sign", "знак стоп",
            "parking meter", "паркомат", "bench", "скамейка", "bird", "птица", "cat", "кошка", "dog", "собака",
            "horse", "лошадь", "sheep", "овца", "cow", "корова", "elephant", "слон", "bear", "медведь",
            "zebra", "зебра", "giraffe", "жираф", "backpack", "рюкзак", "umbrella", "зонт", "handbag", "сумка",
            "tie", "галстук", "suitcase", "чемодан", "frisbee", "фрисби", "skis", "лыжи", "snowboard", "сноуборд",
            "sports ball", "мяч", "kite", "воздушный змей", "baseball bat", "бита", "baseball glove", "перчатка",
            "skateboard", "скейт", "surfboard", "доска для сёрфа", "tennis racket", "ракетка", "bottle", "бутылка",
            "wine glass", "бокал", "cup", "чашка", "fork", "вилка", "knife", "нож", "spoon", "ложка", "bowl", "миска",
            "banana", "банан", "apple", "яблоко", "sandwich", "бутерброд", "orange", "апельсин",
            "broccoli", "брокколи", "carrot", "морковь", "hot dog", "хот-дог", "pizza", "пицца", "donut", "пончик",
            "cake", "торт", "chair", "стул", "couch", "диван", "potted plant", "растение в горшке", "bed", "кровать",
            "dining table", "стол", "toilet", "унитаз", "tv", "телевизор", "laptop", "ноутбук", "mouse", "мышь",
            "remote", "пульт", "keyboard", "клавиатура", "cell phone", "телефон", "microwave", "микроволновка",
            "oven", "духовка", "toaster", "тостер", "sink", "раковина", "refrigerator", "холодильник",
            "book", "книга", "clock", "часы", "vase", "ваза", "scissors", "ножницы", "teddy bear", "плюшевый мишка",
            "hair drier", "фен", "toothbrush", "зубная щётка",
    };

    private static final Map<String, String> MAP = new HashMap<>();

    static {
        for (int i = 0; i + 1 < PAIRS.length; i += 2) {
            MAP.put(PAIRS[i], PAIRS[i + 1]);
        }
    }

    public static String name(String en) {
        String ru = MAP.get(en);
        return ru != null ? ru : en;
    }
}
