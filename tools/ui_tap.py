#!/usr/bin/env python3
"""Нажимает на элемент экрана эмулятора по тексту (через uiautomator dump + adb input tap)."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=False)


def main():
    if len(sys.argv) < 2:
        print("usage: ui_tap.py TEXT")
        return 2
    want = sys.argv[1]
    sh("adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    sh("adb", "pull", "/sdcard/ui.xml", "/tmp/ui.xml")
    tree = ET.parse("/tmp/ui.xml")
    for node in tree.iter("node"):
        text = node.attrib.get("text", "")
        desc = node.attrib.get("content-desc", "")
        if text.strip() == want or desc.strip() == want:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            sh("adb", "shell", "input", "tap", str(cx), str(cy))
            print(f"tap '{want}' at {cx},{cy}")
            return 0
    print(f"not found: '{want}'")
    return 1


if __name__ == "__main__":
    sys.exit(main())
