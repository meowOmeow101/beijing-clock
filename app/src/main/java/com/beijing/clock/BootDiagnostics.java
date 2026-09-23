package com.beijing.clock;

import android.os.SystemClock;

/**
 * 进程级诊断信息，用来判断「通知栏时间消失」到底是应用的问题还是系统把进程杀了。
 *
 * <p>关键指标是「本进程活了多久」。如果划掉后台之后日志里出现一条新的「服务创建」，
 * 而进程年龄只有一两秒，说明进程确实被杀掉并重建了；如果压根没有新日志，
 * 说明系统连重建的机会都没给（国产 ROM 的省电策略常见这种行为）。
 *
 * <p>取时间与格式化分开：{@link #formatAge(long)} 不依赖任何 Android API，
 * 可以直接在桌面 JVM 上测试。
 */
public final class BootDiagnostics {

    /** 本进程启动的单调时刻，类第一次被加载时记录 */
    private static final long PROCESS_START_ELAPSED = SystemClock.elapsedRealtime();

    private BootDiagnostics() {
    }

    /** 本进程已经运行了多少毫秒 */
    public static long processAgeMillis() {
        return SystemClock.elapsedRealtime() - PROCESS_START_ELAPSED;
    }

    /** 给日志用的一句话：进程年龄 + 是否刚被重建 */
    public static String describeProcessAge() {
        long age = processAgeMillis();
        return formatAge(age) + (isFreshProcess(age) ? "（进程刚被重建）" : "");
    }

    /** 进程年龄小于这个值就认为「刚被系统重建」 */
    public static final long FRESH_PROCESS_THRESHOLD_MS = 5000L;

    public static boolean isFreshProcess(long ageMillis) {
        return ageMillis >= 0 && ageMillis < FRESH_PROCESS_THRESHOLD_MS;
    }

    /** 把毫秒数写成人能读的时长，例如 "1.2 秒"、"3 分 05 秒"、"2 小时 07 分" */
    public static String formatAge(long millis) {
        if (millis < 0) {
            return "未知";
        }
        if (millis < 1000L) {
            return millis + " 毫秒";
        }
        long totalSeconds = millis / 1000L;
        if (totalSeconds < 60L) {
            return String.format(java.util.Locale.CHINA, "%.1f 秒", millis / 1000.0);
        }
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes < 60L) {
            return String.format(java.util.Locale.CHINA, "%d 分 %02d 秒", minutes, seconds);
        }
        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        return String.format(java.util.Locale.CHINA, "%d 小时 %02d 分", hours, restMinutes);
    }
}
