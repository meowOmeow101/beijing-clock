package com.beijing.clock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 时间中心：全应用唯一的「北京时间」来源。
 *
 * <p>时间策略（按需求实现）：
 * <ol>
 *   <li>通过 NTP 获取一次网络时间，算出本机时钟与标准时间的偏移量 offset；</li>
 *   <li>之后所有时间都用 {@code System.currentTimeMillis() + offset} 推算，无需持续联网；</li>
 *   <li>每次用户打开 App（{@code MainActivity.onStart}）都会重新校准一次；</li>
 *   <li>对时失败时沿用上次保存的偏移量，保证离线也能继续走时。</li>
 * </ol>
 *
 * <p>偏移量会持久化到 SharedPreferences，重启手机、杀掉进程都不会丢失；
 * 若检测到系统时钟被回拨（当前本机时间早于上次对时时间），则丢弃历史偏移量重新校准，
 * 避免用错误的偏移量把时间推得更偏。
 */
public final class TimeCenter {

    /** 主用 + 备用 NTP 服务器，均为国内可直连的地址 */
    public static final String[] NTP_SERVERS = {
            "ntp.aliyun.com",
            "cn.pool.ntp.org",
            "ntp.tencent.com",
            "ntp1.aliyun.com",
            "time.windows.com"
    };

    /** 单次 NTP 请求超时时间 */
    private static final int NTP_TIMEOUT_MS = 4000;

    private static final String PREF = "beijing_clock_pref";
    private static final String KEY_OFFSET = "offset_millis";
    private static final String KEY_SYNCED_AT = "synced_at_millis";
    private static final String KEY_SERVER = "sync_server";
    private static final String KEY_LAST_ERROR = "sync_last_error";

    /** 单例 */
    private static volatile TimeCenter instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ntp-sync");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    /** 本机时钟相对标准时间的偏移量：标准时间 = 本机时间 + offsetMillis */
    private volatile long offsetMillis = 0L;
    /** 上次校准成功时的标准时间 */
    private volatile long lastSyncTimeMillis = 0L;
    /** 上次校准使用的服务器 */
    private volatile String lastServer = null;
    /** 上次校准的失败原因（成功时为 null） */
    private volatile String lastError = null;
    /** 是否已经完成过至少一次校准 */
    private volatile boolean everSynced = false;

    /** 时间/状态变化回调 */
    public interface Listener {
        void onTimeStateChanged(TimeCenter center);
    }

    private TimeCenter(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        loadPersisted();
    }

    public static TimeCenter get(Context context) {
        if (instance == null) {
            synchronized (TimeCenter.class) {
                if (instance == null) {
                    instance = new TimeCenter(context);
                }
            }
        }
        return instance;
    }

    private void loadPersisted() {
        long storedOffset = prefs.getLong(KEY_OFFSET, 0L);
        long syncedAt = prefs.getLong(KEY_SYNCED_AT, 0L);
        if (syncedAt > 0L) {
            // 若当前本机时间早于上次对时时间，说明系统时钟被回拨过，
            // 旧偏移量已不可靠，丢弃后等下一次联网校准。
            if (System.currentTimeMillis() >= syncedAt) {
                offsetMillis = storedOffset;
                lastSyncTimeMillis = syncedAt;
                everSynced = true;
            } else {
                Log.w("TimeCenter", "检测到系统时钟回拨，已丢弃历史校准结果");
            }
        }
        lastServer = prefs.getString(KEY_SERVER, null);
        lastError = prefs.getString(KEY_LAST_ERROR, null);
    }

    // ------------------------------------------------------------------ 时间

    /** 当前北京时间（Unix 毫秒，已按偏移量修正） */
    public long now() {
        return System.currentTimeMillis() + offsetMillis;
    }

    /** 当前北京时间的整秒毫秒值 */
    public long nowSecond() {
        return now() / 1000L * 1000L;
    }

    /** 剩余到下一秒的毫秒数 */
    public long millisToNextSecond() {
        return 1000L - (now() % 1000L);
    }

    public long getOffsetMillis() {
        return offsetMillis;
    }

    public long getLastSyncTimeMillis() {
        return lastSyncTimeMillis;
    }

    public String getLastServer() {
        return lastServer;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    public boolean hasSynced() {
        return everSynced;
    }

    // -------------------------------------------------------------- 校准逻辑

    /**
     * 用户打开 App 时调用：每次打开都发起一次网络校准。
     *
     * <p>若上一次校准还在进行中（异步未返回），则直接复用，不重复请求。
     *
     * @return true 表示本次真的发起了网络对时
     */
    public boolean syncOnAppOpen() {
        return syncNow();
    }

    /** 立即发起一次网络校准（异步），已在校准中时直接返回 false */
    public boolean syncNow() {
        if (!syncing.compareAndSet(false, true)) {
            return false;
        }
        notifyListeners();
        syncExecutor.execute(() -> {
            try {
                SntpClient.Result result =
                        SntpClient.queryFirstAvailable(NTP_SERVERS, NTP_TIMEOUT_MS);
                applyResult(result);
            } catch (Exception e) {
                applyFailure(e);
            } finally {
                syncing.set(false);
                mainHandler.post(() -> notifyListeners());
            }
        });
        return true;
    }

    private void applyResult(SntpClient.Result result) {
        long deviceTime = System.currentTimeMillis();
        long newOffset = result.serverTimeMillis - deviceTime;
        offsetMillis = newOffset;
        lastSyncTimeMillis = result.serverTimeMillis;
        lastServer = result.server;
        lastError = null;
        everSynced = true;
        mainHandler.post(() -> notifyListeners());
        prefs.edit()
                .putLong(KEY_OFFSET, newOffset)
                .putLong(KEY_SYNCED_AT, result.serverTimeMillis)
                .putString(KEY_SERVER, result.server)
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    private void applyFailure(Exception e) {
        String message = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        lastError = message;
        mainHandler.post(() -> notifyListeners());
        prefs.edit().putString(KEY_LAST_ERROR, message).apply();
    }

    // ------------------------------------------------------------------ 监听

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            listener.onTimeStateChanged(this);
        }
    }

    // ------------------------------------------------------------------ 展示

    /** 把偏移量描述成「本机快/慢了多少」 */
    public String offsetDescription() {
        long offset = offsetMillis;
        long abs = Math.abs(offset);
        String direction = offset >= 0 ? "本机慢" : "本机快";
        if (abs < 1000L) {
            return direction + " " + abs + " 毫秒";
        }
        return direction + " " + (abs / 1000L) + " 秒 " + (abs % 1000L) + " 毫秒";
    }

    /** 距离上次成功校准过去了多久 */
    public String timeSinceLastSync() {
        if (!everSynced || lastSyncTimeMillis <= 0L) {
            return "尚未校准";
        }
        long elapsed = now() - lastSyncTimeMillis;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long seconds = elapsed / 1000L;
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return (hours / 24) + " 天前";
    }

    /**
     * 网络对时状态是否「新鲜」。超过 24 小时视为过期，界面给出提示。
     */
    public boolean isSyncFresh() {
        return everSynced && (now() - lastSyncTimeMillis) < 24L * 60L * 60L * 1000L;
    }
}
