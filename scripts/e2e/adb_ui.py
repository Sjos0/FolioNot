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

    def dump(self, attempts=6):
        """Lê a hierarquia da tela; o uiautomator às vezes falha com a tela ocupada, então tenta de novo."""
        last_error = None
        for _ in range(attempts):
            self.adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
            xml = self.adb("exec-out", "cat", "/sdcard/window.xml")
            try:
                return ET.fromstring(xml)
            except ET.ParseError as exc:
                last_error = exc
                time.sleep(1.5)
        raise RuntimeError(f"uiautomator não gerou a hierarquia após {attempts} tentativas: {last_error}")

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
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in self._nodes(root):
            label = " ".join(part for part in (node.get("text", ""), node.get("content-desc", "")) if part)
            if not rx.search(label) or not self._center(node.get("bounds")):
                continue
            if clickable_only and not self._is_clickable(node, parents):
                continue
            return node
        return None

    @staticmethod
    def _is_clickable(node, parents):
        # No Compose, o texto costuma ser filho de um nó clicável: sobe a hierarquia até achar.
        current = node
        for _ in range(6):
            if current.get("clickable") == "true":
                return True
            current = parents.get(current)
            if current is None:
                return False
        return False

    def wait_for(self, pattern, timeout=30, clickable_only=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(pattern, clickable_only=clickable_only)
            if node is not None:
                return node
            time.sleep(1)
        raise TimeoutError(f"não apareceu na tela: {pattern!r}")

    def tap_text(self, pattern, timeout=30, clickable_only=False):
        try:
            node = self.wait_for(pattern, timeout, clickable_only=clickable_only)
        except TimeoutError:
            if not clickable_only:
                raise
            # Fallback: em Compose o toque no texto alcança o botão pai; registra o que foi achado.
            node = self.wait_for(pattern, 8, clickable_only=False)
            print(f"[aviso] '{pattern}' sem ancestral clicável; tocando no texto: {dict(node.attrib)}", flush=True)
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

    def settle(self, timeout=20):
        """Espera a hierarquia da tela parar de mudar (evita print antes do primeiro frame)."""
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            current = tuple(self.texts())
            if current and current == last:
                time.sleep(1.0)
                return
            last = current
            time.sleep(1.0)

    def dismiss_if_present(self, pattern, timeout=8):
        """Fecha um diálogo opcional (ex.: aviso de atualização) se ele aparecer."""
        try:
            self.tap_text(pattern, timeout=timeout, clickable_only=True)
            return True
        except TimeoutError:
            return False

    def screenshot(self, name, dump=True):
        self.settle()
        self.shot_index += 1
        base = f"{self.shot_index:02d}-{name}"
        png = os.path.join(self.out, base + ".png")
        data = self.adb("exec-out", "screencap", "-p")
        if len(data) < 1000:
            raise RuntimeError(f"captura de tela vazia ({len(data)} bytes) em {name}")
        with open(png, "wb") as fh:
            fh.write(data)
        if dump:
            root = self.dump()
            texts = [n.get("text") for n in self._nodes(root) if n.get("text")]
            with open(os.path.join(self.out, base + ".txt"), "w", encoding="utf-8") as fh:
                fh.write("\n".join(texts))
            return png, texts
        return png, []

    def device_back(self):
        self.adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)

    def texts(self, root=None):
        root = root if root is not None else self.dump()
        return [n.get("text") for n in self._nodes(root) if n.get("text")]
