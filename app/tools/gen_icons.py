#!/usr/bin/env python3
"""生成 mipmap 各密度的启动图标 PNG。

用纯 Python 生成 PNG（无第三方依赖），避免额外安装 Pillow。
图案：绿色圆角底 + 白色盾牌 + 禁止斜杠。
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"

# 各密度目录与边长
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

GREEN = (46, 125, 50)
WHITE = (255, 255, 255)


def write_png(path: Path, width: int, height: int, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type 0
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def rounded_rect_alpha(x: float, y: float, w: float, h: float, r: float) -> float:
    """圆角矩形覆盖度（0~1），用于抗锯齿。"""
    if x < 0 or y < 0 or x > w or y > h:
        return 0.0
    cx = min(max(x, r), w - r)
    cy = min(max(y, r), h - r)
    dx = x - cx
    dy = y - cy
    if dx * dx + dy * dy <= r * r:
        return 1.0
    # 边缘近似：距离圆角中心刚好在半径 +1 内
    d = (dx * dx + dy * dy) ** 0.5
    if d <= r + 1:
        return max(0.0, r + 1 - d)
    return 0.0


def shield_alpha(x: float, y: float, w: float, h: float) -> float:
    """盾牌形状覆盖度，坐标系归一到 0..1。"""
    u = x / w
    v = y / h
    # 盾牌轮廓：顶部宽，中部最宽，底部收成尖
    if v < 0.16 or v > 0.92:
        return 0.0
    if v <= 0.16:
        return 1.0 if 0.28 <= u <= 0.72 else 0.0
    if v <= 0.55:
        left = 0.22 - 0.04 * (v - 0.16) / 0.39
        right = 0.78 + 0.04 * (v - 0.16) / 0.39
        return 1.0 if left <= u <= right else 0.0
    t = (v - 0.55) / 0.37
    half = 0.26 * (1 - t)
    return 1.0 if abs(u - 0.5) <= half else 0.0


def build(size: int) -> list[list[tuple[int, int, int, int]]]:
    pixels: list[list[tuple[int, int, int, int]]] = []
    radius = size * 0.22
    s = size

    for py in range(s):
        row: list[tuple[int, int, int, int]] = []
        for px in range(s):
            bg_a = rounded_rect_alpha(px + 0.5, py + 0.5, s, s, radius)

            r, g, b = GREEN
            a = int(bg_a * 255)

            # 白色盾牌
            sh = shield_alpha(px + 0.5, py + 0.5, s, s)
            if sh > 0:
                # 内部镂空：画一个圆环 + 斜杠的反效果更复杂，这里用实心盾牌 + 深色斜杠
                r2, g2, b2 = WHITE
            else:
                r2, g2, b2 = GREEN

            # 深色斜杠（禁止符号）
            slash = abs((px + 0.5) - (py + 0.5) * 0.0 - size * 0.5)  # 占位
            # 斜杠：从左上到右下方向，穿过盾牌中心
            d = abs((px + 0.5) - (py + 0.5)) - size * 0.02
            in_slash = abs(d) < size * 0.055

            if sh > 0 and in_slash:
                r3, g3, b3 = GREEN
            else:
                r3, g3, b3 = r2, g2, b2

            row.append((r3, g3, b3, a))
        pixels.append(row)
    return pixels


def main() -> None:
    for folder, size in DENSITIES.items():
        out = RES / folder / "ic_launcher.png"
        write_png(out, size, size, build(size))
        print(f"generated {out} ({size}x{size})")

        out_round = RES / folder / "ic_launcher_round.png"
        write_png(out_round, size, size, build(size))
        print(f"generated {out_round} ({size}x{size})")


if __name__ == "__main__":
    main()
