package kz.bulaevo.radar;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import kz.bulaevo.radar.ui.CameraTab;
import kz.bulaevo.radar.ui.MagTab;
import kz.bulaevo.radar.ui.MapTab;
import kz.bulaevo.radar.ui.MotionTab;
import kz.bulaevo.radar.ui.RangeTab;
import kz.bulaevo.radar.ui.ServiceTab;
import kz.bulaevo.radar.ui.SpectrumTab;
import kz.bulaevo.radar.ui.Tab;
import kz.bulaevo.radar.ui.Ui;

/** Единственный экран: семь вкладок радара. */
public class MainActivity extends Activity implements PermissionHost {
    private static final int REQ_AUDIO = 11;
    private static final int REQ_CAMERA = 12;
    private static final int REQ_STORAGE = 13;

    private SonarHub hub;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<TextView> tabButtons = new ArrayList<>();
    private FrameLayout content;
    private ServiceTab serviceTab;
    private int current = -1;
    private boolean resumed;

    private Runnable pendingAudio;
    private Runnable pendingCamera;
    private Runnable pendingStorage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        hub = new SonarHub(this);
        hub.host = this;

        tabs.add(new MapTab(this, hub));
        tabs.add(new RangeTab(this, hub));
        tabs.add(new MotionTab(this, hub));
        tabs.add(new SpectrumTab(this, hub, this));
        tabs.add(new CameraTab(this, hub, this));
        tabs.add(new MagTab(this));
        serviceTab = new ServiceTab(this, hub, this);
        tabs.add(serviceTab);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.PANEL);
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(tabs.get(i).title());
            tv.setTextSize(11.5f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setPadding(0, Ui.dpi(this, 11), 0, Ui.dpi(this, 11));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(idx);
                }
            });
            tabButtons.add(tv);
            bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        show(0);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Служебные параметры запуска для автопроверки: tab=N, start=true, act=selftest|calibrate|info. */
    private void handleIntent(android.content.Intent in) {
        if (in == null) {
            return;
        }
        int t = in.getIntExtra("tab", -1);
        if (t >= 0 && t < tabs.size()) {
            show(t);
        }
        final boolean start = in.getBooleanExtra("start", false);
        final String act = in.getStringExtra("act");
        if (start || act != null) {
            content.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (start) {
                        hub.start();
                    }
                    if (act != null) {
                        serviceTab.debugAction(act);
                    }
                }
            }, 800);
        }
    }

    private void show(int idx) {
        if (idx == current) {
            return;
        }
        if (current >= 0 && resumed) {
            tabs.get(current).onHide();
        }
        current = idx;
        content.removeAllViews();
        content.addView(tabs.get(idx).view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabButtons.size(); i++) {
            TextView tv = tabButtons.get(i);
            boolean on = i == idx;
            tv.setTextColor(on ? Ui.BG : Ui.DIM);
            tv.setBackgroundColor(on ? Ui.ACC : Ui.PANEL);
        }
        if (resumed) {
            tabs.get(idx).onShow();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (current >= 0) {
            tabs.get(current).onShow();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (current >= 0) {
            tabs.get(current).onHide();
        }
        hub.stop();
        hub.orient.stop();
        super.onPause();
    }

    // ---- разрешения ----

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void requireAudio(Runnable action) {
        if (has(Manifest.permission.RECORD_AUDIO)) {
            action.run();
        } else {
            pendingAudio = action;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void requireCamera(Runnable action) {
        if (has(Manifest.permission.CAMERA)) {
            action.run();
        } else {
            pendingCamera = action;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void requireStorage(Runnable action) {
        if (Build.VERSION.SDK_INT >= 29 || has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            action.run();
        } else {
            pendingStorage = action;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Runnable r = null;
        String need = "";
        if (requestCode == REQ_AUDIO) {
            r = pendingAudio;
            pendingAudio = null;
            need = "микрофон";
        } else if (requestCode == REQ_CAMERA) {
            r = pendingCamera;
            pendingCamera = null;
            need = "камеру";
        } else if (requestCode == REQ_STORAGE) {
            r = pendingStorage;
            pendingStorage = null;
            need = "память (для снимка)";
        }
        if (ok && r != null) {
            r.run();
        } else if (!ok) {
            Toast.makeText(this, "Без разрешения на " + need + " эта функция не работает. "
                    + "Разрешите в настройках приложения.", Toast.LENGTH_LONG).show();
        }
    }
}
