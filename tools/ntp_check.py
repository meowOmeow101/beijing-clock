# -*- coding: utf-8 -*-
"""离线验证 SNTP 解析逻辑与服务器可达性（与 Java 端 SntpClient 同一套算法）。"""
import socket
import struct
import time
import sys

NTP_EPOCH_DIFF = 2208988800
SERVERS = ["ntp.aliyun.com", "cn.pool.ntp.org", "ntp.tencent.com", "ntp1.aliyun.com", "time.windows.com"]


def request(host, timeout=4.0):
    """返回 (server_millis, rtt_millis, stratum, mode)"""
    addr = socket.getaddrinfo(host, 123, socket.AF_INET, socket.SOCK_DGRAM)[0][4]
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        pkt = bytearray(48)
        pkt[0] = 0x1B  # LI=0 VN=3 Mode=3
        t0 = time.time()
        s.sendto(bytes(pkt), addr)
        data, _ = s.recvfrom(1024)
        t3 = time.time()
        if len(data) < 48:
            raise ValueError("short response: %d" % len(data))
        leap = (data[0] >> 6) & 0x3
        mode = data[0] & 0x7
        stratum = data[1]
        secs, frac = struct.unpack("!II", data[40:48])
        if leap == 3:
            raise ValueError("server clock unsynchronized (LI=3)")
        if mode not in (4, 5):
            raise ValueError("bad mode %d" % mode)
        if stratum == 0 or stratum > 15:
            raise ValueError("bad stratum %d" % stratum)
        server_millis = (secs - NTP_EPOCH_DIFF) * 1000 + (frac * 1000) // (1 << 32)
        return server_millis, int((t3 - t0) * 1000), stratum, mode
    finally:
        s.close()


def main():
    print("本地时钟: %s" % time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()))
    for host in SERVERS:
        try:
            server_ms, rtt, stratum, mode = request(host)
            offset = server_ms - int(time.time() * 1000)
            print("OK   %-18s stratum=%-2d mode=%d rtt=%4dms offset=%+6dms  北京时间=%s"
                  % (host, stratum, mode, rtt, offset,
                     time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(server_ms / 1000.0))))
        except Exception as e:
            print("FAIL %-18s %s: %s" % (host, type(e).__name__, e))
    return 0


if __name__ == "__main__":
    sys.exit(main())
