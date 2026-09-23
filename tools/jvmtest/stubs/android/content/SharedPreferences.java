package android.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JVM 测试桩：仅实现 TimeCenter 用到的最小接口，用于在电脑上跑真实 NTP 对时逻辑。
 */
public class SharedPreferences {

    public interface Editor {
        Editor putLong(String key, long value);

        Editor putString(String key, String value);

        Editor remove(String key);

        boolean commit();

        void apply();
    }

    private final Map<String, Object> values = new HashMap<>();

    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    public String getString(String key, String defValue) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public Set<String> keySet() {
        return new HashSet<>(values.keySet());
    }

    public Editor edit() {
        return new Editor() {
            @Override
            public Editor putLong(String key, long value) {
                values.put(key, value);
                return this;
            }

            @Override
            public Editor putString(String key, String value) {
                values.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                values.remove(key);
                return this;
            }

            @Override
            public boolean commit() {
                return true;
            }

            @Override
            public void apply() {
                // JVM 桩：直接落盘
            }
        };
    }

    /** 供测试打印全部键值 */
    public Map<String, Object> dump() {
        return new HashMap<>(values);
    }
}
