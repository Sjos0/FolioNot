"""Pequeno driver de UI via adb (sem dependências externas).

Lê a hierarquia da tela com `uiautomator dump`, encontra elementos por texto/descrição,
toca no centro deles, digita texto e tira capturas com nomes ordenados.
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Ui:
    def __init__(self, out_dir, serial=None):
        self.out = out_dir
        self.serial = serial
        self.shot_index = 0
        os.makedirs(out_dir, exist_ok=True)

    def adb(self, *args, capture=True, timeout=60):
        cmd = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        res = subprocess.run(cmd, capture_output=capture, timeout=timeout)
        if res.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} falhou: {res.stderr.decode(errors='replace')[:300]}")
        return res.stdout

    def dump(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
        xml = self.adb("exec-out", "cat", "/sdcard/window.xml")
        return ET.fromstring(xml)

    @staticmethod
    def _nodes(root):
        for node in root.iter("node"):
            yield node

    @staticmethod
    def _center(bounds):
        m = BOUNDS.search(bounds or "")
        if not m:
            return None
        x1, y1, x2, y2 = map(int, m.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2

    def find(self, pattern, root=None, clickable_only=False):
        root = root if root is not None else self.dump()
        rx = re.compile(pattern, re.IGNORECASE)
        for node in self._nodes(root):
            label = f"{node.get('text', '')} {node.get('content-desc', '')}"
            if rx.search(label):
                if clickable_only and node.get("clickable") != "true":
                    continue
                if self._center(node.get("bounds")):
                    return node
        return None

    def wait_for(self, pattern, timeout=30, clickable_only=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(pattern, clickable_only=clickable_only)
            if node is not None:
                return node
            time.sleep(1)
        raise TimeoutError(f"não apareceu na tela: {pattern!r}")

    def tap_text(self, pattern, timeout=30, clickable_only=False):
        node = self.wait_for(pattern, timeout, clickable_only=clickable_only)
        x, y = self._center(node.get("bounds"))
        self.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.2)
        return node

    def type_text(self, text):
        escaped = text.replace(" ", "%s")
        for ch in ("&", "<", ">", "(", ")", ";", "|", "*", "'", '"', "$", "`", "\\"):
            escaped = escaped.replace(ch, "\\" + ch)
        self.adb("shell", "input", "text", escaped)
        time.sleep(0.8)

    def screenshot(self, name, dump=True):
        self.shot_index += 1
        base = f"{self.shot_index:02d}-{name}"
        png = os.path.join(self.out, base + ".png")
        with open(png, "wb") as fh:
            fh.write(self.adb("exec-out", "screencap", "-p"))
        if dump:
            root = self.dump()
            texts = [n.get("text") for n in self._nodes(root) if n.get("text")]
            with open(os.path.join(self.out, base + ".txt"), "w", encoding="utf-8") as fh:
                fh.write("\n".join(texts))
            return png, texts
        return png, []

    def texts(self, root=None):
        root = root if root is not None else self.dump()
        return [n.get("text") for n in self._nodes(root) if n.get("text")]
