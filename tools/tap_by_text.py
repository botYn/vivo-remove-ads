"""按文本查找界面元素并输出其中心坐标。

用法: python tap_by_text.py ui.xml "拦截日志"
"""

import re
import sys
import xml.etree.ElementTree as ET


def parse_bounds(raw: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    return tuple(int(g) for g in m.groups()) if m else None


def main() -> int:
    path, target = sys.argv[1], sys.argv[2]
    tree = ET.parse(path)
    root = tree.getroot()

    for node in root.iter("node"):
        text = node.get("text", "") or node.get("content-desc", "")
        if target in text:
            b = parse_bounds(node.get("bounds", ""))
            if b:
                cx = (b[0] + b[2]) // 2
                cy = (b[1] + b[3]) // 2
                print(f"{cx} {cy}")
                return 0
    print("NOT_FOUND")
    return 1


if __name__ == "__main__":
    sys.exit(main())
