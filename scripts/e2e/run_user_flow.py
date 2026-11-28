"""Fluxo de usuário ponta a ponta no emulador (sem chave de embedding; provedor de chat gratuito sem chave).

Etapas: criar caderno -> importar .txt pelo seletor do sistema -> configurar provedor de chat
(Kilo Gateway, rota gratuita, sem chave) -> embedding em BM25 (sem chave) -> perguntar.
Cada etapa grava print e textos da tela em --out. Relatório final em report.json.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from adb_ui import Ui  # noqa: E402

PKG = "com.sjos.folionote"
KILO_MODELS = "https://api.kilo.ai/api/gateway/models"
KILO_BASE = "https://api.kilo.ai/api/gateway/"
QUESTION = "Qual e o meu nome e minha idade?"  # sem acento: `input text` do adb não digita acentos
PROFILE_NAME = "perfil_usuario.txt"
PROFILE_CONTENT = "Nome: Samuka\nIdade: 30\n"


class Report:
    def __init__(self, path):
        self.path = path
        self.steps = []

    def ok(self, name, detail=""):
        self.steps.append({"step": name, "status": "ok", "detail": detail})
        print(f"[ok] {name} {detail}", flush=True)

    def fail(self, name, error):
        self.steps.append({"step": name, "status": "fail", "detail": str(error)[:500]})
        print(f"[FAIL] {name}: {error}", flush=True)

    def save(self):
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump(self.steps, fh, ensure_ascii=False, indent=2)


def free_kilo_model():
    """Escolhe um modelo gratuito do Kilo Gateway em tempo de execução (catálogo real, não fixo)."""
    with urllib.request.urlopen(KILO_MODELS, timeout=30) as resp:
        data = json.load(resp)
    items = data.get("data", data) if isinstance(data, dict) else data
    ids = [m.get("id", "") for m in items if isinstance(m, dict)]
    free = [i for i in ids if i.endswith(":free") or i.startswith("kilo-auto/free")]
    if not free:
        raise RuntimeError(f"nenhum modelo gratuito no catálogo ({len(ids)} modelos)")
    return free[0]


def run(args):
    ui = Ui(args.out, args.serial)
    rep = Report(os.path.join(args.out, "report.json"))
    try:
        model_id = free_kilo_model()
        rep.ok("catalogo_kilo", f"modelo gratuito escolhido: {model_id}")
    except Exception as exc:  # rede ou catálogo indisponível: relatório, não silêncio
        rep.fail("catalogo_kilo", exc)
        rep.save()
        return 1

    # Arquivo de perfil no dispositivo: o seletor do sistema o encontra em Downloads.
    local = os.path.join(args.out, PROFILE_NAME)
    with open(local, "w", encoding="utf-8") as fh:
        fh.write(PROFILE_CONTENT)
    ui.adb("push", local, f"/sdcard/Download/{PROFILE_NAME}")

    steps = [
        ("abrir_app", lambda: step_open(ui)),
        ("criar_caderno", lambda: step_create_notebook(ui)),
        ("importar_txt", lambda: step_import_txt(ui)),
        ("configurar_provedor_chat", lambda: step_configure_chat_provider(ui, model_id)),
        ("embedding_bm25", lambda: step_embedding_offline(ui)),
        ("perguntar", lambda: step_ask(ui, rep)),
    ]
    code = 0
    for name, fn in steps:
        try:
            detail = fn() or ""
            rep.ok(name, detail)
            ui.screenshot(name)
        except Exception as exc:
            ui.screenshot("erro_" + name)
            rep.fail(name, exc)
            code = 1
            break
        rep.save()
    rep.save()
    return code


def step_open(ui):
    ui.adb("shell", "am", "force-stop", PKG)
    ui.adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    ui.settle()
    closed = ui.dismiss_if_present(r"^Fechar$")
    return "app iniciado" + (" (aviso de atualização fechado)" if closed else "")


def step_create_notebook(ui):
    # Home: o cartão "Criar notebook" e o botão flutuante "Novo notebook" fazem a mesma coisa.
    ui.tap_text(r"Criar notebook|Novo notebook", timeout=40)
    ui.type_text("Perfil de teste")
    ui.tap_text(r"^Salvar$", timeout=20, clickable_only=True)
    ui.wait_for(r"Perfil de teste", timeout=40)
    return "caderno 'Perfil de teste' criado"


def step_import_txt(ui):
    ui.tap_text(r"^Fontes$", timeout=30, clickable_only=True)
    ui.tap_text(r"Importar primeira fonte|Importar fonte|Adicionar fonte", timeout=30)
    ui.tap_text(r"Arquivo|Documento|Escolher arquivo|Importar arquivo", timeout=30)
    # Seletor do sistema (Storage Access Framework): Downloads -> arquivo de perfil.
    ui.tap_text(r"Downloads|Transferências|Baixados", timeout=40)
    ui.tap_text(PROFILE_NAME.replace(".", r"\."), timeout=40)
    ui.wait_for(r"perfil_usuario", timeout=90)
    return "arquivo importado via seletor do sistema"


def step_configure_chat_provider(ui, model_id):
    # Provedores e Modelos: ícone da barra do workspace (descrição "Provedores e Modelos").
    ui.tap_text(r"Provedores e Modelos", timeout=30, clickable_only=True)
    ui.tap_text(r"Adicionar primeiro provedor", timeout=30, clickable_only=True)
    ui.tap_text(r"Nome do provedor", timeout=20)
    ui.type_text("Kilo Gateway")
    ui.tap_text(r"URL-base da API", timeout=20)
    ui.type_text(KILO_BASE)
    # Sem chave de API: o campo fica vazio e nenhum cabeçalho de autenticação é enviado.
    ui.tap_text(r"^Salvar provedor$", timeout=20, clickable_only=True)
    ui.wait_for(r"Kilo Gateway", timeout=40)
    if ui.dismiss_if_present(r"Modelos", timeout=3) is False:
        pass
    ui.tap_text(r"Cadastrar modelo manualmente", timeout=30, clickable_only=True)
    ui.tap_text(r"Model ID na API", timeout=20)
    ui.type_text(model_id)
    ui.tap_text(r"Nome de exibição", timeout=20)
    ui.type_text("Modelo gratuito Kilo")
    ui.tap_text(r"^Salvar modelo$", timeout=20, clickable_only=True)
    ui.wait_for(r"Modelo gratuito Kilo", timeout=40)
    return f"provedor sem chave e modelo {model_id} cadastrados"


def step_embedding_offline(ui):
    ui.tap_text(r"Embeddings", timeout=30, clickable_only=True)
    ui.tap_text(r"Apenas Busca Lexical Offline", timeout=30)
    return "embedding em BM25 offline (sem chave)"


def step_ask(ui, rep):
    ui.device_back()
    ui.tap_text(r"^Chat$", timeout=30, clickable_only=True)
    ui.tap_text(r"Faça uma pergunta ou crie algo", timeout=30)
    ui.type_text(QUESTION)
    ui.tap_text(r"Enviar mensagem", timeout=20, clickable_only=True)
    deadline = time.time() + 150
    answer = None
    while time.time() < deadline:
        texts = ui.texts()
        joined = " ".join(texts)
        if re.search(r"Erro na requisi", joined, re.IGNORECASE):
            answer = "ERRO: " + joined[:600]
            break
        if re.search(r"Samuka", joined, re.IGNORECASE):
            answer = joined[:800]
            break
        time.sleep(3)
    ui.screenshot("resposta")
    if answer is None:
        raise TimeoutError("nenhuma resposta do modelo em 150 s")
    if answer.startswith("ERRO"):
        raise RuntimeError(answer)
    ok = bool(re.search(r"\b30\b", answer))
    if not ok:
        raise AssertionError("resposta sem a idade esperada (30): " + answer[:300])
    return "resposta contém nome e idade"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="e2e-artifacts")
    parser.add_argument("--serial", default=None)
    sys.exit(run(parser.parse_args()))
