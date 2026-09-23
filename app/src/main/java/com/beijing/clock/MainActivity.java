package com.beijing.clock;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

/**
 * 首页：进入即显示北京时间（精确到秒），并在每次打开 App 时校准一次网络时间。
 */
public class MainActivity extends AppCompatActivity implements TimeCenter.Listener {

    /** 权限请求回调：用户授权后直接开启常驻显示 */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    setSwitchChecked(true, false);
                    NotificationClockService.start(this, true);
                } else {
                    setSwitchChecked(false, false);
                    showMessage("没有通知权限就无法在通知栏显示时间，请在系统设置中允许本应用发送通知");
                }
                refreshState();
            });

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TimeCenter timeCenter;

    private TextView timeText;
    private TextView dateText;
    private TextView zoneText;
    private TextView statusText;
    private TextView detailText;
    private TextView serverText;
    private ImageView statusDot;
    private MaterialButton syncButton;
    private SwitchCompat serviceSwitch;
    private SwitchCompat iconSwitch;
    private SwitchCompat persistSwitch;
    private MaterialCardView serviceCard;
    private MaterialCardView iconCard;
    private MaterialCardView persistCard;
    private TextView runtimeStateText;

    /** 每秒刷新首页时间 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderTime();
            handler.postDelayed(this, Math.max(50L, timeCenter.millisToNextSecond()));
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        timeCenter = TimeCenter.get(this);

        timeText = findViewById(R.id.text_time);
        dateText = findViewById(R.id.text_date);
        zoneText = findViewById(R.id.text_zone);
        statusText = findViewById(R.id.text_status);
        detailText = findViewById(R.id.text_detail);
        serverText = findViewById(R.id.text_server);
        statusDot = findViewById(R.id.dot_status);
        syncButton = findViewById(R.id.button_sync);
        serviceSwitch = findViewById(R.id.switch_service);
        iconSwitch = findViewById(R.id.switch_icon);
        persistSwitch = findViewById(R.id.switch_persist);
        serviceCard = findViewById(R.id.card_service);
        iconCard = findViewById(R.id.card_icon);
        persistCard = findViewById(R.id.card_persist);
        runtimeStateText = findViewById(R.id.text_runtime_state);

        zoneText.setText(TimeFormatter.ZONE_NAME);
        serviceSwitch.setChecked(NotificationClockService.isWanted(this));

        syncButton.setOnClickListener(v -> {
            if (timeCenter.isSyncing()) {
                showMessage("正在对时，请稍候…");
                return;
            }
            timeCenter.syncNow();
            showMessage("已开始向 NTP 服务器校准北京时间");
        });

        serviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isSwitchStable) {
                // 代码同步开关状态时忽略回调
                return;
            }
            if (isChecked) {
                requestNotificationPermissionIfNeeded();
            } else {
                NotificationClockService.stop(this);
            }
            handler.postDelayed(this::refreshState, 400L);
        });

        iconSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isSwitchStable) {
                return;
            }
            NotificationClockService.setIconKept(this, isChecked);
            // 重新启动服务，让通知按新渠道重建
            if (NotificationClockService.isWanted(this)) {
                NotificationClockService.start(this, true);
            }
            handler.postDelayed(this::refreshState, 500L);
        });

        persistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isSwitchStable) {
                return;
            }
            NotificationClockService.setPersistAfterExit(this, isChecked);
            if (isChecked) {
                showMessage("已开启：从最近任务划掉本应用后，通知栏时间继续显示");
            } else {
                showMessage("已关闭：退出应用时通知栏时间会一起消失");
            }
            // 服务不需要重启，退出时的行为按读取到的最新偏好决定
            handler.postDelayed(this::refreshState, 200L);
        });

        findViewById(R.id.button_settings).setOnClickListener(v -> openBatterySettings());
    }

    /** 避免代码改动开关状态时触发监听器 */
    private boolean isSwitchStable = true;

    /** 只改 UI，不触发监听器 */
    private void setSwitchChecked(boolean checked, boolean fromUser) {
        if (serviceSwitch.isChecked() == checked) {
            return;
        }
        isSwitchStable = false;
        serviceSwitch.setChecked(checked);
        isSwitchStable = true;
    }

    /** 同步「退出后仍然显示」开关，同样不触发监听器 */
    private void setPersistSwitchChecked(boolean checked) {
        if (persistSwitch.isChecked() == checked) {
            return;
        }
        isSwitchStable = false;
        persistSwitch.setChecked(checked);
        isSwitchStable = true;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        NotificationClockService.start(this, true);
        handler.postDelayed(this::refreshState, 400L);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 每次打开 App 都校准一次北京时间
        boolean started = timeCenter.syncOnAppOpen();
        timeCenter.addListener(this);
        handler.removeCallbacks(ticker);
        ticker.run();
        refreshState();
        if (started) {
            setStatus("正在校准北京时间…", R.color.status_warning);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);
        timeCenter.removeListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    // ------------------------------------------------------------------ 渲染

    private void renderTime() {
        long now = timeCenter.now();
        timeText.setText(TimeFormatter.timeHms(now));
        dateText.setText(String.format(Locale.CHINA, "%s %s",
                TimeFormatter.dateCn(now), TimeFormatter.weekCn(now)));
    }

    @Override
    public void onTimeStateChanged(TimeCenter center) {
        runOnUiThread(this::refreshState);
    }

    private void refreshState() {
        renderTime();

        boolean syncing = timeCenter.isSyncing();
        boolean synced = timeCenter.hasSynced();
        boolean fresh = timeCenter.isSyncFresh();

        if (syncing) {
            setStatus("正在网络校准…", R.color.status_warning);
        } else if (synced && fresh) {
            setStatus("已与网络时间同步（" + timeCenter.timeSinceLastSync() + "校准）", R.color.status_ok);
        } else if (synced) {
            setStatus("使用上次的校准结果（" + timeCenter.timeSinceLastSync() + "），建议重新校准",
                    R.color.status_warning);
        } else if (timeCenter.getLastError() != null) {
            setStatus("网络对时失败，当前为手机本机时间", R.color.status_error);
        } else {
            setStatus("尚未校准，当前显示手机本机时间", R.color.status_warning);
        }

        String error = timeCenter.getLastError();
        detailText.setText(error == null ? "" : "上次对时异常：" + error);
        detailText.setVisibility(error == null ? View.GONE : View.VISIBLE);

        String server = timeCenter.getLastServer();
        if (server == null) {
            serverText.setText("对时服务器：ntp.aliyun.com 等（自动择优）");
        } else {
            serverText.setText(String.format(Locale.CHINA, "上次对时服务器：%s（偏移 %s）",
                    server, timeCenter.offsetDescription()));
        }

        syncButton.setEnabled(!syncing);
        syncButton.setText(syncing ? "正在对时…" : "立即校准北京时间");

        boolean serviceRunning = NotificationClockService.isRunning();
        setSwitchChecked(NotificationClockService.isWanted(this) && serviceRunning, false);
        boolean keepIcon = NotificationClockService.isIconKept(this);
        isSwitchStable = false;
        iconSwitch.setChecked(keepIcon);
        isSwitchStable = true;
        iconSwitch.setEnabled(serviceRunning);
        iconCard.setAlpha(serviceRunning ? 1f : 0.5f);

        setPersistSwitchChecked(NotificationClockService.isPersistAfterExit(this));
        persistSwitch.setEnabled(NotificationClockService.isWanted(this));
        persistCard.setAlpha(NotificationClockService.isWanted(this) ? 1f : 0.5f);

        boolean notificationsOn = NotificationClockService.notificationsEnabled(this);
        serviceCard.setAlpha(notificationsOn ? 1f : 0.85f);

        // 一句话说清「现在到底在不在显示」
        String state = "当前状态：" + NotificationClockService.describeState(this);
        if (NotificationClockService.isWanted(this) && !isIgnoringBatteryOptimizations()) {
            // 这一条很关键：没进白名单的应用在国产 ROM 上很容易被划掉后台时直接冻结
            state += "\n建议将本应用加入电池优化白名单，否则划掉后台后系统可能冻结它（点下方按钮）";
        }
        runtimeStateText.setText(state);
    }

    /** 是否已被系统列入电池优化白名单（未列入时后台更容易被冻结） */
    private boolean isIgnoringBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return true;
            }
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    private void setStatus(String text, int colorRes) {
        statusText.setText(text);
        int color = ContextCompat.getColor(this, colorRes);
        statusText.setTextColor(color);
        statusDot.setColorFilter(color);
    }

    private void showMessage(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 弹窗

    /** 后台保活设置：给出两个最常用的入口 */
    private void openBatterySettings() {
        String[] items = {"打开通知设置（允许通知栏显示）", "打开电池优化白名单（防止后台被清理）",
                "打开本应用详情页"};
        new AlertDialog.Builder(this)
                .setTitle("后台与通知设置")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        openAppNotificationSettings();
                    } else if (which == 1) {
                        openBatteryOptimizationSettings();
                    } else {
                        openAppDetailsSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            showMessage("请在列表中找到「北京时间」并设为「不优化」");
        } catch (Exception e) {
            openAppDetailsSettings();
        }
    }

    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            openAppDetailsSettings();
        }
    }

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception e) {
            showMessage("无法打开应用设置页");
        }
    }
}
