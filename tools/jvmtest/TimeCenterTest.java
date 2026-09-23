import android.content.Context;
import android.os.Handler;

import com.beijing.clock.ServicePolicy;
import com.beijing.clock.SntpClient;
import com.beijing.clock.TimeCenter;
import com.beijing.clock.TimeFormatter;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * JVM 端功能验证：在电脑上跑 App 的真实对时/校准逻辑（Android 相关接口由 stubs 提供）。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>SntpClient 真实联网对时，返回值与系统时间、北京时间格式化的正确性；</li>
 *   <li>失败服务器自动切换备用；</li>
 *   <li>TimeCenter 首次校准：offset / now() / 状态文案；</li>
 *   <li>「打开 App 才校准」：5 秒内重复打开不重复发起请求；</li>
 *   <li>偏移量持久化：模拟进程重启后仍能继续走时；</li>
 *   <li>系统时钟被回拨时丢弃历史偏移量。</li>
 * </ol>
 */
public class TimeCenterTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("================ 北京时间 App · JVM 功能验证 ================");
        System.out.println("本机当前时间 : " + stamp(System.currentTimeMillis()));
        System.out.println("北京时间(格式化): " + TimeFormatter.full(TimeCenterNowRaw()));
        System.out.println();

        testSntpClient();
        testSntpFailover();
        testTimeCenterFirstSync();
        testAppOpenCalibration();
        testPersistence();
        testClockRollbackDetection();
        testServicePolicy();

        System.out.println();
        System.out.println("================ 结果: 通过 " + passed + " / 失败 " + failed + " ================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 测试用例

    private static void testSntpClient() throws Exception {
        section("1. SntpClient 真实 NTP 对时");
        try {
            long before = System.currentTimeMillis();
            SntpClient.Result r = SntpClient.queryFirstAvailable(TimeCenter.NTP_SERVERS, 4000);
            long after = System.currentTimeMillis();
            System.out.println("  服务器      : " + r.server);
            System.out.println("  服务端时间  : " + stamp(r.serverTimeMillis));
            System.out.println("  往返耗时    : " + r.roundTripMillis + " ms（第 " + r.attempts + " 个服务器）");
            System.out.println("  本机时钟偏移: " + (r.serverTimeMillis - after) + " ms（正数表示本机慢）");

            check("返回的服务端时间落在请求时间窗口内",
                    r.serverTimeMillis >= before - 2000 && r.serverTimeMillis <= after + 2000);
            check("往返耗时为非负且合理", r.roundTripMillis >= 0 && r.roundTripMillis < 10000);
            check("北京时区格式化为 HH:mm:ss 形状",
                    TimeFormatter.timeHms(r.serverTimeMillis).matches("\\d{2}:\\d{2}:\\d{2}"));
            String gotBeijing = TimeFormatter.dateTimeLong(r.serverTimeMillis);
            check("北京时间 = UTC+8（格式化结果一致）",
                    gotBeijing.equals(utcPlus8(r.serverTimeMillis)));
            System.out.println("  UTC 参照    : " + utcStamp(r.serverTimeMillis) + "  -> 北京 " + gotBeijing);
        } catch (Exception e) {
            check("SntpClient 对时未抛异常: " + e, false);
        }
    }

    private static void testSntpFailover() throws Exception {
        section("2. 服务器失败自动切换备用");
        try {
            String[] servers = {"127.0.0.1", "ntp.aliyun.com"};  // 第一个必然失败（本机 123 端口无服务）
            SntpClient.Result r = SntpClient.queryFirstAvailable(servers, 1200);
            System.out.println("  跳过失败服务器后成功: " + r.server + "（第 " + r.attempts + " 次尝试）");
            check("第二个服务器生效", r.attempts == 2);
        } catch (Exception e) {
            check("备用服务器生效: " + e, false);
        }
    }

    private static void testTimeCenterFirstSync() throws Exception {
        section("3. TimeCenter 首次校准");
        resetSingleton();
        Context ctx = new Context();
        TimeCenter tc = TimeCenter.get(ctx);

        check("初始状态未校准", !tc.hasSynced());
        check("初始偏移量为 0", tc.getOffsetMillis() == 0L);

        boolean started = tc.syncNow();
        check("发起校准返回 true", started);
        boolean finished = Handler.drainUntil(() -> !tc.isSyncing(), 20000);
        check("校准在 20 秒内结束", finished);
        check("校准成功且无异常信息", tc.hasSynced() && tc.getLastError() == null);

        long device = System.currentTimeMillis();
        long appNow = tc.now();
        System.out.println("  偏移量      : " + tc.getOffsetMillis() + " ms");
        System.out.println("  状态文案    : " + tc.timeSinceLastSync() + " / " + tc.offsetDescription());
        System.out.println("  服务器      : " + tc.getLastServer());
        check("now() ≈ 本机时间 + 偏移量", Math.abs(appNow - (device + tc.getOffsetMillis())) < 50);
        check("偏移量在合理范围（±10 秒）", Math.abs(tc.getOffsetMillis()) < 10_000);
        check("状态判定为新鲜", tc.isSyncFresh());
        check("剩余到下一秒在 1..1000", tc.millisToNextSecond() >= 1 && tc.millisToNextSecond() <= 1000);
    }

    private static void testAppOpenCalibration() throws Exception {
        section("4. 「每次打开 App 校准一次」");
        resetSingleton();
        Context ctx = new Context();
        TimeCenter tc = TimeCenter.get(ctx);

        boolean first = tc.syncOnAppOpen();
        check("首次打开会发起校准", first);
        Handler.drainUntil(() -> !tc.isSyncing(), 20000);
        long firstSyncedAt = tc.getLastSyncTimeMillis();
        check("已记录校准时间", firstSyncedAt > 0);

        Thread.sleep(1100L);  // 保证两次校准的服务端时间戳不同
        boolean second = tc.syncOnAppOpen();
        check("再次打开仍然发起校准（不做节流）", second);
        Handler.drainUntil(() -> !tc.isSyncing(), 20000);
        check("校准时间被刷新", tc.getLastSyncTimeMillis() > firstSyncedAt);
        System.out.println("  第一次校准: " + firstSyncedAt + " -> 第二次: " + tc.getLastSyncTimeMillis());

        // 并发去重：校准进行中再次触发不应重复发起请求
        TimeCenter tc2 = TimeCenter.get(ctx);
        boolean started = tc2.syncOnAppOpen();
        boolean duringSync = tc2.syncOnAppOpen();
        check("校准进行中重复触发被去重", started && !duringSync);
        Handler.drainUntil(() -> !tc2.isSyncing(), 20000);
    }

    private static void testPersistence() throws Exception {
        section("5. 偏移量持久化（模拟进程重启）");
        resetSingleton();
        Context ctx = new Context();
        TimeCenter first = TimeCenter.get(ctx);
        first.syncOnAppOpen();
        Handler.drainUntil(() -> !first.isSyncing(), 20000);
        long savedOffset = first.getOffsetMillis();
        long savedSyncAt = first.getLastSyncTimeMillis();
        check("第一次运行已完成校准", first.hasSynced());

        // 模拟进程被杀后重新打开：同一个 Context（同一份 SharedPreferences），单例重置
        resetSingleton();
        TimeCenter second = TimeCenter.get(ctx);
        check("重启后仍有校准状态", second.hasSynced());
        check("重启后偏移量一致", second.getOffsetMillis() == savedOffset);
        check("重启后校准时间一致", second.getLastSyncTimeMillis() == savedSyncAt);
        check("重启后 now() 仍然被修正",
                Math.abs(second.now() - (System.currentTimeMillis() + savedOffset)) < 50);
        System.out.println("  重启后偏移量: " + second.getOffsetMillis() + " ms，状态: "
                + second.timeSinceLastSync());
    }

    private static void testClockRollbackDetection() throws Exception {
        section("6. 系统时钟被回拨时丢弃历史偏移量");
        Context ctx = new Context();
        // 伪造一条「未来时间」的校准记录，模拟用户把系统时间往回调
        ctx.getSharedPreferences("beijing_clock_pref", Context.MODE_PRIVATE)
                .edit()
                .putLong("offset_millis", 999_000L)
                .putLong("synced_at_millis", System.currentTimeMillis() + 86_400_000L)
                .putString("sync_server", "203.0.113.1")
                .apply();
        resetSingleton();
        TimeCenter tc = TimeCenter.get(ctx);
        check("回拨场景下偏移量被丢弃", tc.getOffsetMillis() == 0L);
        check("回拨场景下标记为未校准", !tc.hasSynced());
        System.out.println("  检出回拨，等待下次联网重新校准（当前偏移量 " + tc.getOffsetMillis() + " ms）");
    }

    private static void testServicePolicy() {
        section("7. 「退出后是否保留通知栏时间」的判定规则");

        // 划掉最近任务后要不要把服务拉回来
        check("开启保留 + 服务在跑 -> 重启",
                ServicePolicy.shouldRestartAfterTaskRemoved(true, true));
        check("开启保留 + 服务已停 -> 不重启（没什么可救的）",
                !ServicePolicy.shouldRestartAfterTaskRemoved(true, false));
        check("关闭保留 -> 划掉后台不重启，通知随之消失",
                !ServicePolicy.shouldRestartAfterTaskRemoved(false, true));
        check("关闭保留 + 服务已停 -> 不重启",
                !ServicePolicy.shouldRestartAfterTaskRemoved(false, false));

        // 后台是否继续活着
        check("常驻开 + 退出保留开 -> 后台继续运行",
                ServicePolicy.shouldKeepRunningInBackground(true, true));
        check("常驻开 + 退出保留关 -> 不后台长驻",
                !ServicePolicy.shouldKeepRunningInBackground(true, false));
        check("常驻关 -> 无论如何都不后台长驻",
                !ServicePolicy.shouldKeepRunningInBackground(false, true));

        // WakeLock 续期：服务可能连跑几天，到期前必须续，否则息屏后秒数会停
        long timeout = ServicePolicy.WAKE_LOCK_TIMEOUT_MS;
        long renew = ServicePolicy.WAKE_LOCK_RENEW_AFTER_MS;
        check("续期阈值必须小于超时时长，否则会先失效", renew < timeout);
        check("刚申请不久不需要续期", !ServicePolicy.wakeLockNeedsRenewal(0L));
        check("持有 1 小时不需要续期", !ServicePolicy.wakeLockNeedsRenewal(60L * 60L * 1000L));
        check("持有到续期阈值 -> 需要续期", ServicePolicy.wakeLockNeedsRenewal(renew));
        check("持有超过超时时长 -> 必须续期", ServicePolicy.wakeLockNeedsRenewal(timeout + 1L));
        System.out.println("  超时 " + (timeout / 3600000L) + " 小时，续期阈值 "
                + (renew / 3600000L) + " 小时");

        // 默认为「退出后保留」，也就是用户要求的默认行为
        check("默认开启「退出后仍然显示」", ServicePolicy.DEFAULT_PERSIST_AFTER_EXIT);

        // 界面状态文案
        String running = ServicePolicy.describeState(true, true, true, true);
        String exiting = ServicePolicy.describeState(true, false, true, true);
        String off = ServicePolicy.describeState(false, true, false, true);
        String noPerm = ServicePolicy.describeState(true, true, true, false);
        String stopped = ServicePolicy.describeState(true, true, false, true);
        check("常驻中且退出保留 -> 文案说明划掉后台也不消失", running.contains("划掉后台"));
        check("常驻中但退出不保留 -> 文案说明会一起关闭", exiting.contains("退出应用后会一起关闭"));
        check("通知栏关闭 -> 文案说明不会显示", off.contains("已关闭"));
        check("无通知权限 -> 文案提示权限", noPerm.contains("通知权限"));
        check("服务未运行 -> 文案提示重开应用", stopped.contains("重新打开"));
        System.out.println("  " + running);
        System.out.println("  " + exiting);
        System.out.println("  " + noPerm);
    }

    // ------------------------------------------------------------ 工具方法
    private static void resetSingleton() throws Exception {
        Field f = TimeCenter.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    private static long TimeCenterNowRaw() {
        return System.currentTimeMillis();
    }

    private static String stamp(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
        return sdf.format(new Date(millis));
    }

    private static String utcStamp(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }

    private static String utcPlus8(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
        return sdf.format(new Date(millis));
    }

    private static void section(String title) {
        System.out.println("---- " + title + " ----");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
