# QA de UX/UI em runtime (Issue #5)

Este documento é o **gate de evidência** da Issue #5. Ele registra o que foi verificado
automaticamente (CI) e o que exige execução em aparelho.

## Status

| Área | Verificado no CI | Verificado em aparelho |
| --- | --- | --- |
| Confirmação de exclusão (histórico e Studio) | Sim (`UxSafetyComposeTests`) | Pendente |
| Alvos de toque ≥ 48dp no diálogo de confirmação | Sim (`UxSafetyComposeTests`) | Pendente (abas, seletor de fontes, enviar) |
| CTA por aba em Provedores (aba 3 sem CTA) | Não (código revisado) | Pendente |
| Títulos do Studio com até duas linhas | Não (código revisado) | Pendente (duas larguras + fonte ampliada) |
| TalkBack, ordem de foco, contraste | Não | Pendente |

## Matriz de execução (a preencher em aparelho)

- Dispositivo compacto: ______ (resolução, Android)
- Dispositivo padrão: ______ (resolução, Android)
- Temas: claro / escuro / sistema
- Escala de fonte: padrão / ampliada
- Orientação: retrato / paisagem (quando suportada)

## Fluxos críticos

1. Criar e abrir notebook; navegar entre Fontes, Chat, Estúdio.
2. Enviar mensagem, trocar modelo, selecionar fontes; teclado não encobre o envio.
3. Importar e consultar fontes.
4. Provedores: abas Provedores, Modelos e Embeddings & Tools (sem CTA indevido).
5. Studio: criar material; excluir material com cancelar e com confirmar.
6. Chat: excluir histórico com cancelar (mensagens preservadas) e com confirmar.
7. Configurações e retorno ao contexto anterior.

Para cada fluxo: estados de carregamento, vazio, erro, sucesso e cancelado.

## Registro de defeitos

| ID | Tela | Passos | Esperado | Observado | Severidade | Evidência | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |

Defeitos novos fora do escopo desta Issue devem virar Issues específicas.
