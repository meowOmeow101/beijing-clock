package android.os;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 测试桩：Handler。
 *
 * <p>为了在测试里能确定性观察回调，这里把任务放进队列，由测试线程显式调用
 * {@link #drainMainThreadTasks()} 执行，模拟 Android 主线程的投递语义。
 */
public class Handler {

    private static final List<Runnable> PENDING = new ArrayList<>();

    public Handler() {
    }

    public Handler(Looper looper) {
    }

    public boolean post(Runnable r) {
        synchronized (PENDING) {
            PENDING.add(r);
        }
        return true;
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        return post(r);
    }

    public void removeCallbacks(Runnable r) {
        synchronized (PENDING) {
            PENDING.remove(r);
        }
    }

    public void removeCallbacksAndMessages(Object token) {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    /** 测试专用：执行所有排队的主线程任务 */
    public static int drainMainThreadTasks() {
        List<Runnable> tasks;
        synchronized (PENDING) {
            tasks = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Runnable r : tasks) {
            r.run();
        }
        return tasks.size();
    }

    /** 测试专用：等待并执行任务，直到满足条件或超时 */
    public static boolean drainUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            drainMainThreadTasks();
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        drainMainThreadTasks();
        return condition.getAsBoolean();
    }
}
