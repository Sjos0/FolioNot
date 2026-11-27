#!/usr/bin/env python3
"""Checa os relatórios de memória (JSON por caso) contra memory-budgets.json.

Uso:
  memory_budget_check.py --reports DIR --budgets FILE --mode report|enforce [--summary FILE]

Saída: tabela Markdown (pico, retenção, orçamento, status). Em modo enforce, sai com código 1 se
algum caso estourar o orçamento ou se algum caso não gerou relatório.
"""
import argparse
import glob
import json
import os
import sys


def load_reports(report_dir):
    reports = {}
    for path in glob.glob(os.path.join(report_dir, "**", "*.json"), recursive=True):
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        reports[data["case"]] = data
    return reports


def evaluate(reports, budgets):
    rows, failures = [], []
    for case, limits in budgets["cases"].items():
        rep = reports.get(case)
        if rep is None:
            rows.append((case, "-", "-", limits, "SEM RELATÓRIO"))
            failures.append(f"{case}: sem relatório")
            continue
        peak_ok = rep["peakDeltaMB"] <= limits["maxPeakDeltaMB"]
        ret_ok = rep["retainedMB"] <= limits["maxRetainedMB"]
        status = "OK" if peak_ok and ret_ok else "ESTOUROU"
        if not peak_ok:
            failures.append(f"{case}: pico {rep['peakDeltaMB']} MB > {limits['maxPeakDeltaMB']} MB")
        if not ret_ok:
            failures.append(f"{case}: retido {rep['retainedMB']} MB > {limits['maxRetainedMB']} MB")
        rows.append((case, rep["peakDeltaMB"], rep["retainedMB"], limits, status))
    for case in reports:
        if case not in budgets["cases"]:
            rows.append((case, reports[case]["peakDeltaMB"], reports[case]["retainedMB"], {}, "SEM ORÇAMENTO"))
    return rows, failures


def render(rows, mode, calibrated):
    lines = [
        "## Medição de memória (heap da JVM, Robolectric)",
        "",
        f"Modo: **{mode}** · orçamentos calibrados: **{'sim' if calibrated else 'não (estimativas iniciais)'}**",
        "",
        "| Caso | Pico Δ (MB) | Retido (MB) | Orçamento pico/retido (MB) | Status |",
        "| --- | ---: | ---: | --- | --- |",
    ]
    for case, peak, retained, limits, status in rows:
        budget = f"{limits.get('maxPeakDeltaMB', '-')} / {limits.get('maxRetainedMB', '-')}" if limits else "-"
        lines.append(f"| `{case}` | {peak} | {retained} | {budget} | {status} |")
    lines += [
        "",
        "Limitação: heap da JVM detecta regressões de alocação; a RAM real do aparelho (PSS) exige teste em dispositivo.",
    ]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", required=True)
    parser.add_argument("--budgets", required=True)
    parser.add_argument("--mode", choices=["report", "enforce"], default="report")
    parser.add_argument("--summary", default=None)
    args = parser.parse_args()

    with open(args.budgets, encoding="utf-8") as fh:
        budgets = json.load(fh)
    reports = load_reports(args.reports)
    rows, failures = evaluate(reports, budgets)
    markdown = render(rows, args.mode, budgets.get("calibrated", False))

    print(markdown)
    # Anotações visíveis na API do GitHub (um aviso por caso), além do resumo do job.
    for case, peak, retained, limits, status in rows:
        print(f"::notice title=memória {case}::pico {peak} MB, retido {retained} MB, status {status}")
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as fh:
            fh.write(markdown)
    if failures:
        print("Estouros:")
        for item in failures:
            print(f"  - {item}")
        if args.mode == "enforce":
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
