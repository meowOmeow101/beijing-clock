package android.os;

/**
 * JVM 测试桩：SystemClock。用真实单调时钟模拟。
 */
public class SystemClock {

    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }

    public static long uptimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
