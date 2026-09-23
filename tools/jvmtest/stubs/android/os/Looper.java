package android.os;

/**
 * JVM 测试桩：Looper。
 */
public class Looper {

    private static final Looper MAIN = new Looper();

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }
}
