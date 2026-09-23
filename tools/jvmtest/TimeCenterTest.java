import android.content.Context;
import android.os.Handler;

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
