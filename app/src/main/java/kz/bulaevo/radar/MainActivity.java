package kz.bulaevo.radar;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Временный каркас: проверяет, что сборка и запуск работают. Будет заменён настоящим приложением. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Радар: каркас сборки работает");
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(22);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.BLACK);
        setContentView(tv);
    }
}
