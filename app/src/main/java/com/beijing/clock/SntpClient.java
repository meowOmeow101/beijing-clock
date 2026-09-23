package com.beijing.clock;

/**
 * SNTP 客户端。
 *
 * <p>自行实现 RFC 4330 的简化 SNTP 请求，直接使用 JDK 自带的 UDP Socket，
 * 避免引入任何第三方依赖。核心是取回服务端的「当前 UTC 毫秒时间戳」，
 * 再与本机时钟做差，得到可用于后续推算的时间偏移量。
 *
 * <p>计算 NTP 时间戳的标准公式（1900-01-01 到 1970-01-01 之间相差 2208988800 秒）：
 * <pre>unixMillis = seconds * 1000 + (fraction * 1000) / 2^32 - 2208988800_000</pre>
 */
public final class SntpClient {

    /** NTP 时间戳起点（1900-01-01）与 Unix 纪元（1970-01-01）之间的秒数差 */
    private static final long NTP_EPOCH_DIFF_SECONDS = 2208988800L;

    /** 一次成功的校时结果 */
    public static final class Result {
        /** 服务端返回的 UTC 时间（Unix 毫秒） */
        public final long serverTimeMillis;
        /** 服务端 IP，便于排查问题 */
        public final String server;
        /** 一次网络往返耗时（毫秒） */
        public final long roundTripMillis;
        /** 覆盖的请求次数（含失败重试） */
        public final int attempts;

        Result(long serverTimeMillis, String server, long roundTripMillis, int attempts) {
            this.serverTimeMillis = serverTimeMillis;
            this.server = server;
            this.roundTripMillis = roundTripMillis;
            this.attempts = attempts;
        }
    }

    private SntpClient() {
    }

    /**
     * 依次尝试给定的 NTP 服务器，返回第一个成功的结果。
     *
     * @param servers   候选服务器地址列表
     * @param timeoutMs 单次请求超时（毫秒）
     * @throws Exception 全部服务器都失败时抛出最后一个异常
     */
    public static Result queryFirstAvailable(String[] servers, int timeoutMs) throws Exception {
        Exception last = null;
        int attempts = 0;
        for (String server : servers) {
            attempts++;
            try {
                return requestTime(server, timeoutMs, attempts);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("没有可用的 NTP 服务器");
    }

    /**
     * 向单个 NTP 服务器发起一次时间请求。
     *
     * @param host      服务器域名或 IP
     * @param timeoutMs 接收超时时间（毫秒）
     * @param attempts  当前是第几次尝试，仅用于回传给调用方展示
     */
    public static Result requestTime(String host, int timeoutMs, int attempts) throws Exception {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            byte[] buffer = new byte[48];
            // LI = 0（无告警）, VN = 3（版本 3）, Mode = 3（客户端）
            buffer[0] = 0x1B;

            long requestTime = System.currentTimeMillis();
            writeTimestamp(buffer, 40, requestTime);
            java.net.DatagramPacket out =
                    new java.net.DatagramPacket(buffer, buffer.length, address, 123);
            socket.send(out);

            java.net.DatagramPacket in = new java.net.DatagramPacket(buffer, buffer.length);
            socket.receive(in);
            long responseTime = System.currentTimeMillis();

            if (in.getLength() < 48) {
                throw new java.io.IOException("NTP 响应长度异常: " + in.getLength());
            }
            int leap = (buffer[0] >> 6) & 0x3;
            if (leap == 3) {
                throw new java.io.IOException("NTP 服务端时钟未同步 (LI=3): " + host);
            }
            int mode = buffer[0] & 0x7;
            if (mode != 4 && mode != 5) {
                throw new java.io.IOException("NTP 响应模式异常: " + mode);
            }
            int stratum = buffer[1] & 0xFF;
            if (stratum == 0 || stratum > 15) {
                throw new java.io.IOException("NTP 层级异常 (stratum=" + stratum + "): " + host);
            }

            long serverTime = readTimestamp(buffer, 40);
            if (serverTime <= 0L) {
                throw new java.io.IOException("NTP 服务端时间无效: " + host);
            }
            return new Result(serverTime, address.getHostAddress(), responseTime - requestTime, attempts);
        }
    }

    /** 把 Unix 毫秒写入缓冲区指定下标处的 8 字节 NTP 时间戳（大端） */
    private static void writeTimestamp(byte[] buffer, int offset, long unixMillis) {
        long seconds = unixMillis / 1000L + NTP_EPOCH_DIFF_SECONDS;
        long fraction = (unixMillis % 1000L) * 0x100000000L / 1000L;
        writeInt(buffer, offset, seconds);
        writeInt(buffer, offset + 4, fraction);
    }

    /** 读取缓冲区指定下标处的 8 字节 NTP 时间戳，转换为 Unix 毫秒 */
    private static long readTimestamp(byte[] buffer, int offset) {
        long seconds = readInt(buffer, offset);
        long fraction = readInt(buffer, offset + 4);
        if (seconds == 0L) {
            return 0L;
        }
        return (seconds - NTP_EPOCH_DIFF_SECONDS) * 1000L + (fraction * 1000L) / 0x100000000L;
    }

    private static void writeInt(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) ((value >> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 3] = (byte) (value & 0xFF);
    }

    private static long readInt(byte[] buffer, int offset) {
        return ((long) (buffer[offset] & 0xFF) << 24)
                | ((long) (buffer[offset + 1] & 0xFF) << 16)
                | ((long) (buffer[offset + 2] & 0xFF) << 8)
                | ((long) (buffer[offset + 3] & 0xFF));
    }
}
