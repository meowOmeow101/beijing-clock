package android.util;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 测试桩：Log。把日志收集起来供测试打印。
 */
public class Log {

    private static final List<String> LINES = new ArrayList<>();

    public static int v(String tag, String msg) {
        return log("V", tag, msg, null);
    }

    public static int d(String tag, String msg) {
        return log("D", tag, msg, null);
    }

    public static int i(String tag, String msg) {
        return log("I", tag, msg, null);
    }

    public static int w(String tag, String msg) {
        return log("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable t) {
        return log("W", tag, msg, t);
    }

    public static int e(String tag, String msg) {
        return log("E", tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable t) {
        return log("E", tag, msg, t);
    }

    private static int log(String level, String tag, String msg, Throwable t) {
        String line = level + "/" + tag + ": " + msg + (t == null ? "" : " <" + t + ">");
        synchronized (LINES) {
            LINES.add(line);
        }
        System.out.println("    [log] " + line);
        return 0;
    }

    public static List<String> drain() {
        synchronized (LINES) {
            List<String> copy = new ArrayList<>(LINES);
            LINES.clear();
            return copy;
        }
    }
}
