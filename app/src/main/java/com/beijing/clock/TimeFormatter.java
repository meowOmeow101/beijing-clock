package com.beijing.clock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 北京时间格式化工具。
 *
 * <p>全部格式化都显式绑定 {@link #BEIJING} 时区，不依赖手机的系统时区，
 * 因此无论手机设置成哪个时区，显示的都是北京时间（UTC+8）。
 */
public final class TimeFormatter {

    /** 北京时间时区，中国全境统一使用 UTC+8，无夏令时 */
    public static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");

    public static final String ZONE_NAME = "中国标准时间 UTC+8";
    private static final String[] WEEK_CN = {"", "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};

    private static final ThreadLocal<SimpleDateFormat> HH_MM_SS =
            format("HH:mm:ss");
    private static final ThreadLocal<SimpleDateFormat> FULL =
            format("yyyy年M月d日 HH:mm:ss");
    private static final ThreadLocal<SimpleDateFormat> DATE_CN =
            format("yyyy年M月d日");
    private static final ThreadLocal<SimpleDateFormat> DATE_TIME_LONG =
            format("yyyy-MM-dd HH:mm:ss");

    private TimeFormatter() {
    }

    private static ThreadLocal<SimpleDateFormat> format(final String pattern) {
        return new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.CHINA);
                sdf.setTimeZone(BEIJING);
                return sdf;
            }
        };
    }

    /** 12:34:56 */
    public static String timeHms(long millis) {
        return HH_MM_SS.get().format(new Date(millis));
    }

    /** 2025年1月1日 12:34:56 */
    public static String full(long millis) {
        return FULL.get().format(new Date(millis));
    }

    /** 2025年1月1日 */
    public static String dateCn(long millis) {
        return DATE_CN.get().format(new Date(millis));
    }

    /** 2025-01-01 12:34:56 */
    public static String dateTimeLong(long millis) {
        return DATE_TIME_LONG.get().format(new Date(millis));
    }

    /** 星期三 */
    public static String weekCn(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance(BEIJING);
        c.setTimeInMillis(millis);
        int index = c.get(java.util.Calendar.DAY_OF_WEEK);
        return (index >= 1 && index <= 7) ? WEEK_CN[index] : "";
    }
}
