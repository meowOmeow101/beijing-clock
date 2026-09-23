package com.beijing.clock;

/**
 * 常驻显示的保活策略。
 *
 * <p>这里集中放「退出应用后通知栏还要不要留着」相关的判断规则。规则本身不碰任何
 * Android API，所以可以直接在桌面 JVM 上跑测试（见 {@code tools/jvmtest}），
 * 免得这类逻辑只能靠真机反复试。
 *
 * <p>关于「划掉后台」在 Android 上的实际语义，值得写下来：
 * <ul>
 *   <li>从最近任务里划掉应用，只会回调 {@code onTaskRemoved}，默认并不销毁前台服务；
 *   <li>所以只要用户在界面里开启了「退出后仍然显示」，服务就继续跑，通知栏的时间不会断；
 *   <li>如果进程真的被系统回收，前台服务会被重建，服务在重建时会重新对时并刷新通知；
 *   <li>唯一救不回来的是用户在系统设置里点「强行停止」，那是系统层的禁令，
 *       任何保活手段都无效，只能等用户下次打开应用。
 * </ul>
 */
public final class ServicePolicy {

    /** 用户是否希望「退出应用后通知栏继续显示」 */
    public static final String KEY_PERSIST_AFTER_EXIT = "persist_after_exit";

    /** 用户是否希望把本应用从最近任务列表里隐藏 */
    public static final String KEY_HIDE_FROM_RECENTS = "hide_from_recents";

    /** 该偏好的默认值：默认保留，因为这就是要这个应用的意义所在 */
    public static final boolean DEFAULT_PERSIST_AFTER_EXIT = true;

    /** 默认不隐藏：隐藏后通知栏是唯一入口，误关通知会打不开应用，交给用户自己选 */
    public static final boolean DEFAULT_HIDE_FROM_RECENTS = false;

    /** WakeLock 每次申请的时长（毫秒），到期前必须续期 */
    public static final long WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L;

    /** 提前续期的阈值：持有时间超过这个值就重新申请，保证不会中途失效 */
    public static final long WAKE_LOCK_RENEW_AFTER_MS = 10L * 60L * 60L * 1000L;

    /**
     * 划掉最近任务后重新拉起服务的重试间隔。
     *
     * <p>用户划掉任务会给一个只有几秒的「允许从后台启动前台服务」窗口，重试必须落在窗口内，
     * 所以这里用秒级间隔，而不是分钟级。
     */
    public static final long RESTART_RETRY_DELAY_MS = 2000L;

    private ServicePolicy() {
    }

    /**
     * 划掉最近任务之后要不要把服务拉回来。
     *
     * @param persistAfterExit 用户是否开启了「退出后仍然显示」
     * @param serviceRunning   回调发生时服务是否仍在运行
     */
    public static boolean shouldRestartAfterTaskRemoved(boolean persistAfterExit, boolean serviceRunning) {
        return persistAfterExit && serviceRunning;
    }

    /**
     * 常驻开关打开时，服务是否应该在后台继续活着。
     *
     * @param clockEnabled   用户是否开了「通知栏显示北京时间」
     * @param persistAfterExit 用户是否开了「退出后仍然显示」
     */
    public static boolean shouldKeepRunningInBackground(boolean clockEnabled, boolean persistAfterExit) {
        return clockEnabled && persistAfterExit;
    }

    /**
     * 屏幕熄灭时是否需要重新申请 WakeLock。
     *
     * <p>WakeLock 带超时申请是为了避免异常情况下长期占用，但服务本身可能连续运行几天，
     * 所以在到期之前必须续期，否则息屏后 Handler 收不到调度，通知里的秒数会停住。
     *
     * @param heldMillis 本次 WakeLock 已经持有了多久
     */
    public static boolean wakeLockNeedsRenewal(long heldMillis) {
        return heldMillis >= WAKE_LOCK_RENEW_AFTER_MS;
    }

    /**
     * 是否把启动页从最近任务列表里隐藏。
     *
     * <p>这是这套保活里最有效的一招：应用不出现在最近任务里，用户就没有「划掉后台」这个动作，
     * 前台服务不会被 {@code onTaskRemoved} 打断，通知栏的时间自然一直在。
     * 代价是通知栏成为进入应用的唯一入口，所以留给用户自己决定。
     *
     * @param hideRequested    用户是否开了「从最近任务隐藏」
     * @param clockEnabled     通知栏时间开关是否开着（关了就没有入口，不能隐藏）
     */
    public static boolean shouldExcludeFromRecents(boolean hideRequested, boolean clockEnabled) {
        return hideRequested && clockEnabled;
    }

    /**
     * 把偏好和环境状态翻译成给用户看的一句话，界面和通知都直接用这句。
     *
     * @param clockEnabled     通知栏显示开关
     * @param persistAfterExit 退出后仍然显示开关
     * @param hideFromRecents  是否已从最近任务隐藏
     * @param serviceRunning   服务当前是否在运行
     * @param notificationsOn  系统通知权限是否已授予
     */
    public static String describeState(boolean clockEnabled,
                                       boolean persistAfterExit,
                                       boolean hideFromRecents,
                                       boolean serviceRunning,
                                       boolean notificationsOn) {
        if (!clockEnabled) {
            return "已关闭，通知栏不会显示时间";
        }
        if (!notificationsOn) {
            return "缺少通知权限，时间无法显示在通知栏";
        }
        if (!serviceRunning) {
            return "服务未在运行，重新打开应用即可恢复";
        }
        if (hideFromRecents) {
            return "正在显示，本应用不会出现在最近任务里，因而无法被划掉";
        }
        return persistAfterExit
                ? "正在显示，划掉后台也不会消失"
                : "正在显示，退出应用后会一起关闭";
    }
}
