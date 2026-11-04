"""扫描本地子网，查找开启无线调试(Android 11+)的设备。

Android 无线调试默认监听 37000-41000 区间内的随机端口，同时兼容
adb tcpip 常用的 5555/5037 等端口。
"""

import concurrent.futures as cf
import ipaddress
import socket
import sys

PORTS = [5555, 5556, 5557, 5558, 5559, 5560, 5561, 37000, 37100, 37200]
PORT_RANGE = range(37000, 41000, 1)

TIMEOUT = 0.35


def probe(host: str, port: int) -> tuple[str, int] | None:
    try:
        with socket.create_connection((host, port), timeout=TIMEOUT):
            return host, port
    except OSError:
        return None


def main() -> int:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        local_ip = s.getsockname()[0]
        s.close()
    except OSError:
        local_ip = "10.101.18.167"

    net = ipaddress.ip_network(f"{local_ip}/17", strict=False)
    hosts = [str(h) for h in net.hosts()]
    print(f"local={local_ip} scanning {len(hosts)} hosts on {net}", flush=True)

    found: list[tuple[str, int]] = []

    with cf.ThreadPoolExecutor(max_workers=512) as ex:
        # 先扫固定端口
        for r in ex.map(lambda p: probe(local_ip, p), PORTS):
            if r:
                found.append(r)
        print(f"phase1 done: {found}", flush=True)

        # 再扫动态端口区间（分批并行）
        print("phase2: scanning 37000-40999 ...", flush=True)
        for r in ex.map(lambda p: probe(local_ip, p), PORT_RANGE):
            if r:
                found.append(r)
                print(f"  HIT {r[0]}:{r[1]}", flush=True)

    print("RESULT:")
    for h, p in sorted(set(found)):
        print(f"  {h}:{p}")
    return 0 if found else 1


if __name__ == "__main__":
    sys.exit(main())
