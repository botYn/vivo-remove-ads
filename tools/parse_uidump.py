"""解析 uiautomator dump 的 XML，按真实屏幕坐标列出可点击节点。

uiautomator 的 bounds 在部分设备上会输出归一化(缩放)坐标，需要按
实际分辨率换算回真实像素。
"""

import re
import sys
import xml.etree.ElementTree as ET

DEFAULT_SIZE = (1080, 2400)


def parse_bounds(raw: str) -> tuple[int, int, int, int] | None:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    if not m:
        return None
    return tuple(int(g) for g in m.groups())  # type: ignore[return-value]


def main() -> int:
    path = sys.argv[1]
    width = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_SIZE[0]
    height = int(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_SIZE[1]

    tree = ET.parse(path)
    root = tree.getroot()

    # uiautomator 的坐标是按 1/1000 或按实际像素；这里取最大边界推断缩放
    max_x = 0
    max_y = 0
    bounds_list = []
    for node in root.iter("node"):
        b = parse_bounds(node.get("bounds", ""))
        if b:
            bounds_list.append((node, b))
            max_x = max(max_x, b[2])
            max_y = max(max_y, b[3])

    scale_x = width / max_x if max_x else 1.0
    scale_y = height / max_y if max_y else 1.0

    print(f"raw_max=({max_x},{max_y}) scale=({scale_x:.4f},{scale_y:.4f})", file=sys.stderr)

    for node, b in bounds_list:
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        rid = node.get("resource-id", "")
        clickable = node.get("clickable") == "true"
        pkg = node.get("package", "")
        cls = node.get("class", "")

        label = text or desc
        if not label:
            continue

        x1, y1, x2, y2 = b
        cx = int((x1 + x2) / 2 * scale_x)
        cy = int((y1 + y2) / 2 * scale_y)

        print(
            f"({cx},{cy}) clickable={clickable} pkg={pkg.split('/')[-1]} "
            f"id={rid.split('/')[-1]} cls={cls.split('.')[-1]} "
            f"text={text!r} desc={desc!r}",
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
