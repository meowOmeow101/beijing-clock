package com.beijing.clock;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * 通知栏时钟前台服务。
 *
 * <p>职责：
 * <ul>
 *   <li>以每秒一次、且对齐到整秒边界的节奏刷新通知内容；</li>
 *   <li>通知正文就是「北京时间 HH:mm:ss」，手机状态栏 / 下拉通知栏都能看到；</li>
 *   <li>服务处于前台，系统不会在后台把它回收掉，从而保证一直显示。</li>
 * </ul>
 */
public class NotificationClockService extends Service {

    private static final String TAG = "BeijingClockService";

    public static final String ACTION_START = "com.beijing.clock.action.START";
    public static final String ACTION_STOP = "com.beijing.clock.action.STOP";

    /** 用户「是否希望常驻显示」的偏好，开机自启据此判断 */
    public static final String PREFS = "beijing_clock_settings";
    public static final String KEY_WANT_RUNNING = "want_running";
    public static final String KEY_KEEP_ICON = "keep_status_icon";

    private static final String CHANNEL_SILENT = "beijing_clock_silent";
    private static final String CHANNEL_NORMAL = "beijing_clock_normal";
    private static final int NOTIFICATION_ID = 1001;

    /** 服务是否在运行，供界面判断按钮状态 */
    private static volatile boolean running = false;
    /** 应用上下文引用，供静态方法查询服务状态使用 */
    private static volatile Context appContextRef = null;
    /** 是否已经成功进入前台；startForeground 失败时界面不应该显示「正在显示」 */
    private static volatile boolean foregroundAlive = false;
    /** isRunning() 的查询缓存，避免界面每秒刷新时反复枚举系统服务 */
    private static final long ALIVE_CHECK_INTERVAL_MS = 1000L;
    private static volatile long lastAliveCheckElapsed = 0L;
    private static volatile boolean aliveCache = false;

    /** 任何静态入口（含界面查询）都能拿到上下文 */
    private static Context appContext(Context context) {
        Context app = context.getApplicationContext();
        appContextRef = app == null ? context : app;
        return app == null ? context : app;
    }

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private long wakeLockAcquiredElapsed = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TimeCenter timeCenter;

    /** 每秒对齐刷新的任务 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // WakeLock 是带超时申请的，服务可能连续跑好几天，到期前在这里续期，
            // 否则息屏后 Handler 收不到调度，通知里的秒数会停在原地。
            if (ServicePolicy.wakeLockNeedsRenewal(
                    SystemClock.elapsedRealtime() - wakeLockAcquiredElapsed)) {
                releaseWakeLock();
                acquireWakeLock();
            }
            updateNotification();
            foregroundAlive = true;
            handler.postDelayed(this, Math.max(50L, timeCenter.millisToNextSecond()));
        }
    };

    /** 对时完成后立刻按新偏移量刷新一次通知 */
    private final TimeCenter.Listener timeListener = center -> updateNotification();

    /**
     * 服务是否正在运行。
     *
     * <p>静态标记只在「本进程亲手启动过服务」时为真；若 App 进程被系统回收后服务仍在运行，
     * 进程重建后标记会丢失，因此再补一次 {@link ActivityManager} 查询（只查自己的服务，
     * 不需要任何特权）。这样首页开关展示的状态才能和真实情况一致。
     */
    public static boolean isRunning() {
        if (running) {
            return true;
        }
        // ActivityManager 枚举有一定开销，界面每秒都会查询，这里做 1 秒缓存
        long elapsed = SystemClock.elapsedRealtime();
        if (elapsed - lastAliveCheckElapsed > ALIVE_CHECK_INTERVAL_MS) {
            lastAliveCheckElapsed = elapsed;
            aliveCache = isServiceProcessAlive();
        }
        return aliveCache;
    }

    private static boolean isServiceProcessAlive() {
        Context context = appContextRef;
        if (context == null) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            String className = NotificationClockService.class.getName();
            String packageName = context.getPackageName();
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service != null
                        && className.equals(info.service.getClassName())
                        && packageName.equals(info.service.getPackageName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "查询服务运行状态失败", e);
        }
        return false;
    }

    /** 读取用户偏好：是否需要常驻通知栏 */
    public static boolean isWanted(Context context) {
        appContext(context);
        return prefs(context).getBoolean(KEY_WANT_RUNNING, false);
    }

    /** 是否使用会占位的「常驻通知」（关闭后通知仍可下拉看到，但状态栏不显示图标） */
    public static boolean isIconKept(Context context) {
        appContext(context);
        return prefs(context).getBoolean(KEY_KEEP_ICON, true);
    }

    public static void setIconKept(Context context, boolean keep) {
        appContext(context);
        prefs(context).edit().putBoolean(KEY_KEEP_ICON, keep).apply();
    }

    /** 是否「退出应用后通知栏仍然显示」 */
    public static boolean isPersistAfterExit(Context context) {
        appContext(context);
        return prefs(context).getBoolean(ServicePolicy.KEY_PERSIST_AFTER_EXIT,
                ServicePolicy.DEFAULT_PERSIST_AFTER_EXIT);
    }

    public static void setPersistAfterExit(Context context, boolean persist) {
        appContext(context);
        prefs(context).edit().putBoolean(ServicePolicy.KEY_PERSIST_AFTER_EXIT, persist).apply();
    }

    /** 界面状态文案：把开关状态翻译成一句人话 */
    public static String describeState(Context context) {
        appContext(context);
        return ServicePolicy.describeState(
                isWanted(context),
                isPersistAfterExit(context),
                isRunning() && foregroundAlive,
                notificationsEnabled(context));
    }

    private static SharedPreferences prefs(Context context) {
        return appContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 启动服务（界面与开机广播共用） */
    public static void start(Context context, boolean userInitiated) {
        prefs(context).edit().putBoolean(KEY_WANT_RUNNING, true).apply();
        Intent intent = new Intent(context, NotificationClockService.class);
        intent.setAction(ACTION_START);
        try {
            if (userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 前台启动，允许使用 startForegroundService
                context.startForegroundService(intent);
            } else {
                // 开机广播 / 服务自愈等后台场景：服务若已在前台运行，startService 不会抛异常；
                // 若已停止则会被系统拒绝，交由用户下次打开 App 时恢复，避免违反后台启动限制。
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败", e);
        }
    }

    /** 停止服务并清除通知 */
    public static void stop(Context context) {
        prefs(context).edit().putBoolean(KEY_WANT_RUNNING, false).apply();
        Intent intent = new Intent(context, NotificationClockService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "停止服务失败", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        appContextRef = getApplicationContext();
        timeCenter = TimeCenter.get(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
        acquireWakeLock();
        // 服务自身也跟随时间中心的校准结果刷新
        timeCenter.addListener(timeListener);
        Log.i(TAG, "服务创建，退出后保留常驻 = " + isPersistAfterExit(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs(this).edit().putBoolean(KEY_WANT_RUNNING, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 被系统重建时 intent 会是 null，这里按「用户仍希望常驻」来判断是否继续
        if (intent == null && !isWanted(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 先进入前台，避免 Android 8+ 的 5 秒超时崩溃
        if (!startForegroundCompat()) {
            // 进不了前台就别硬撑，否则进程会被反复重建
            stopSelf();
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(ticker);
        ticker.run();

        // 服务被系统重启后同样校准一次，保证走时准确
        timeCenter.syncOnAppOpen();

        // START_STICKY：进程被系统回收后由系统重建服务；
        // 划掉最近任务不会销毁前台服务，所以通知栏时间不会中断。
        return START_STICKY;
    }

    /**
     * 进入前台并挂上通知。
     *
     * @return 是否成功。Android 14 起如果前台服务类型不可用，startForeground 会直接抛异常，
     * 这里必须兜住，否则服务在系统重建时会陷入「崩溃-重启」循环。
     */
    private boolean startForegroundCompat() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundAlive = true;
            return true;
        } catch (Throwable t) {
            foregroundAlive = false;
            Log.e(TAG, "进入前台失败", t);
            return false;
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        // 常驻版：状态栏显示图标
        NotificationChannel normal = new NotificationChannel(
                CHANNEL_NORMAL, "北京时间常驻显示", NotificationManager.IMPORTANCE_DEFAULT);
        normal.setDescription("在状态栏常驻显示北京时间的通知");
        normal.setShowBadge(false);
        normal.enableLights(false);
        normal.enableVibration(false);
        normal.setSound(null, null);
        notificationManager.createNotificationChannel(normal);

        // 静音版：不占状态栏图标，仅在下拉通知栏可见（部分机型会隐藏，属系统行为）
        NotificationChannel silent = new NotificationChannel(
                CHANNEL_SILENT, "北京时间静默显示", NotificationManager.IMPORTANCE_LOW);
        silent.setDescription("下拉通知栏可见、状态栏不显示图标的静默通知");
        silent.setShowBadge(false);
        silent.enableLights(false);
        silent.enableVibration(false);
        silent.setSound(null, null);
        notificationManager.createNotificationChannel(silent);
    }

    private Notification buildNotification() {
        boolean keepIcon = isIconKept(this);
        String channelId = keepIcon ? CHANNEL_NORMAL : CHANNEL_SILENT;

        long now = timeCenter.now();
        String time = TimeFormatter.timeHms(now);
        String dateLine = TimeFormatter.dateCn(now) + " " + TimeFormatter.weekCn(now);

        Intent launch = new Intent(this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launch, pendingFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_clock)
                .setContentTitle(time)
                .setContentText(dateLine + " · " + TimeFormatter.ZONE_NAME)
                .setContentIntent(contentIntent)
                .setColor(Color.parseColor("#12B7F5"))
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(keepIcon ? NotificationCompat.PRIORITY_DEFAULT
                        : NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void updateNotification() {
        try {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification());
                return;
            }
            // 用户关掉了通知权限：正文刷不出来，但至少要保证服务还在前台，
            // 这样重新打开权限之后时间能立刻恢复显示。
            if (!foregroundAlive) {
                startForegroundCompat();
            }
        } catch (Throwable t) {
            Log.e(TAG, "刷新通知失败", t);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeijingClock::ticker");
                wakeLock.setReferenceCounted(false);
            }
            // 带超时申请，避免异常退出时长期占用；ticker 会在到期前自动续期
            wakeLock.acquire(ServicePolicy.WAKE_LOCK_TIMEOUT_MS);
            wakeLockAcquiredElapsed = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            Log.e(TAG, "申请 WakeLock 失败", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务被销毁");
        running = false;
        foregroundAlive = false;
        handler.removeCallbacks(ticker);
        if (timeCenter != null) {
            timeCenter.removeListener(timeListener);
        }
        releaseWakeLock();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        boolean persist = isPersistAfterExit(this);
        Log.i(TAG, "任务被移除，退出后保留常驻 = " + persist);

        if (!ServicePolicy.shouldRestartAfterTaskRemoved(persist, true)) {
            // 用户选择了「退出后不保留」，划掉后台就把服务收掉，通知栏一起消失
            stopSelf();
            return;
        }

        // 划掉最近任务本身不会销毁前台服务，这里只是兜底：
        // 万一某些 ROM 顺手把服务停了，延迟一下再拉回来。
        handler.postDelayed(() -> {
            try {
                startService(new Intent(this, NotificationClockService.class)
                        .setAction(ACTION_START));
            } catch (Exception e) {
                Log.w(TAG, "划掉任务后重启服务失败（下次打开应用会自动恢复）", e);
            }
        }, 800L);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 供界面查询：通知权限是否开启 */
    public static boolean notificationsEnabled(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /** 广播：开机后恢复常驻时钟 */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            Log.i(TAG, "收到广播: " + action);
            if (action == null) {
                return;
            }
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                    || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                if (isWanted(context)) {
                    start(context, false);
                }
            }
        }
    }
}
