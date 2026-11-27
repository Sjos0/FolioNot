# Medição de memória (workflow `memory-profile.yml`)

## Arquitetura

- `memory-cases.json`: lista de casos (fonte única). O job `plan` gera a matriz a partir dele.
- `app/src/test/.../memory/MemoryCaseTests.kt`: um método `case_*` por caso. Cada método mede o
  heap da JVM (pico durante o caso e retenção após GC) e grava `app/build/memory-reports/<caso>.json`.
- `memory-budgets.json`: orçamentos por caso (pico Δ e retenção, em MB).
- `scripts/memory_budget_check.py`: compara relatórios com orçamentos (retenção medida como Δ em relação à linha de base do caso), gera a tabela do resumo do job
  e, em modo `enforce`, falha quando algum caso estoura.
- Jobs: `plan` → `measure` (um por caso, em paralelo) → `report` (resumo + artefato consolidado).

## Como rodar

1. Actions → "Memory profile" → Run workflow.
2. `budget_mode = report` (padrão): só mede e publica o resumo.
3. `budget_mode = enforce`: falha se algum orçamento for estourado. Use depois da calibração.

## Calibração (fazer antes de usar `enforce`)

1. Rodar o workflow em `report` algumas vezes na `main` e baixar o artefato `memory-reports-consolidated`.
2. Para cada caso, pegar o maior `peakDeltaMB` e `retainedMB` observados e somar uma folga de 30%.
3. Atualizar `memory-budgets.json` e trocar `"calibrated": true`.

## Limitações

- Mede o heap da JVM sob Robolectric, não a PSS do Android. Serve para detectar regressões de
  alocação (listas inteiras, cópias por token, clientes duplicados).
- A RAM real do aparelho exige teste em dispositivo (ver `docs/qa/ux-runtime-qa.md`).
