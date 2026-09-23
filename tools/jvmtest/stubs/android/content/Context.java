package android.content;

/**
 * JVM 测试桩：Context。只保留 TimeCenter 依赖的方法。
 */
public class Context {

    public static final int MODE_PRIVATE = 0;

    private final SharedPreferences prefs = new SharedPreferences();

    public Context getApplicationContext() {
        return this;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs;
    }

    public Object getSystemService(String name) {
        return null;
    }

    public String getPackageName() {
        return "com.beijing.clock.test";
    }
}
