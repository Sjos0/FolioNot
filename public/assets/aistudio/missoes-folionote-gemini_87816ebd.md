# FolioNote — Missões para o Gemini (hydrogen)

Repo: https://github.com/Sjos0/FolioNot (branch `main`)
Stack: Kotlin, Jetpack Compose (Material 3), Room, WorkManager, OkHttp, ML Kit
minSdk: 24 (Android 7.0) · targetSdk: 36
Pacote: `com.sjos.folionote`

Regras para TODAS as missões:
- Não remova funcionalidades existentes. Faça mudanças mínimas e focadas na missão.
- Não toque em `signingConfigs`, keystore, `.env`, nem em `.signing/`.
- Não imprima nem grave tokens, chaves ou senhas em logs, arquivos ou commits.
- Faça um commit por missão, com mensagem clara em português.
- Ao final, entregue: arquivos alterados, motivo de cada mudança, e o resultado dos testes abaixo.

Testes locais padrão (rodar ao final de cada missão):
- `./gradlew testDebugUnitTest`
- `./gradlew lintRelease`
- `./gradlew assembleRelease` e `./gradlew assembleDebug`, informando o tamanho de cada APK antes e depois

---

## Diagnóstico resumido (base para as missões)

1. Crash no chat: `NetworkOnMainThreadException` aparece como "Erro na requisição" e fica gravado na resposta do assistente como "[Interrompido: ...]".
2. Teclado sobre o chat: ao abrir o teclado, a barra de modelo fica cortada e o layout se mexe.
3. Configuração de modelo: o campo de Context Window aceita qualquer número sem teto, e valor inválido volta silenciosamente para 8192.
4. APK grande: R8 desligado na release, sem redução de recursos, e `material-icons-extended` usado em ~174 pontos.
5. RAM alta: a cada mensagem o app carrega histórico inteiro, todos os chunks e todos os embeddings das fontes selecionadas. Também cria `OkHttpClient` novo a cada requisição.
6. Segundo plano: sync do Drive a cada 6 h e checagem automática de update ao abrir o app.

---

## MISSÃO 1 — Corrigir o crash `NetworkOnMainThreadException` no chat

Arquivo principal: `app/src/main/java/com/sjos/folionote/engine/ai/OpenAiCompatibleClient.kt`, função `streamChatCompletion` (por volta das linhas 210 a 260).

Diagnóstico: a chamada `call.execute()` roda em `Dispatchers.IO`, mas o `response.use { }`, a leitura de status e o fechamento do corpo ficam no dispatcher do coletor. Como `NotebookViewModel.sendMessage` roda em `viewModelScope` (Main), essas operações de socket acontecem na thread principal. Isso é hipótese forte, a confirmar com Logcat (`adb logcat | grep NetworkOnMainThread`).

Tarefa:
1. Mover todo o ciclo de requisição, leitura e fechamento para `Dispatchers.IO`, sem deixar I/O na Main.
2. Manter `trySend`, `close` e o cancelamento via `call.cancel()` funcionando.
3. Garantir que um erro de rede vire `ChatStreamEvent.Error` e não derrube o fluxo.
4. Criar teste com `MockWebServer` cobrindo: resposta OK em stream, resposta OK sem stream, erro HTTP 500 e cancelamento no meio do stream.

Critério de aceite: nenhum `NetworkOnMainThreadException` nos testes; stream completo funciona.

---

## MISSÃO 2 — Layout do chat com teclado e erros dentro da bolha

Arquivos:
- `app/src/main/java/com/sjos/folionote/ui/notebook/ChatTabContent.kt` (uso de `imePadding()` por volta da linha 116)
- `app/src/main/java/com/sjos/folionote/ui/notebook/NotebookWorkspaceScreen.kt` (abas Fontes/Chat/Estúdio e seletor de modelo)
- `app/src/main/AndroidManifest.xml` (`windowSoftInputMode="adjustResize"`)
- `MainActivity.kt` (edge-to-edge)

Diagnóstico: `adjustResize` no manifesto e `imePadding()` no Compose podem estar sendo aplicados juntos. Com targetSdk 36 o app é edge-to-edge, o que agrava. Resultado: a linha "modelo • provedor" fica cortada quando o teclado abre.

Tarefa:
1. Escolher uma única estratégia de insets (ou `imePadding`, ou `adjustResize`, não as duas) e aplicá-la de forma consistente.
2. Garantir que a barra superior e o seletor de modelo fiquem visíveis com teclado aberto e fechado.
3. Erros de requisição devem aparecer como banner ou snackbar com botão "Tentar novamente". Não devem ser gravados como texto da resposta do assistente. Se houver resposta parcial, mantê-la marcada como parcial, separada do erro.
4. Revisar a bolha de "Gerando resposta..." para não ficar presa após erro.

Critério de aceite: teste de UI (Compose test) ou captura de tela antes/depois com teclado aberto, mostrando o seletor visível.

---

## MISSÃO 3 — Configuração do modelo (Context Window e Max Output)

Arquivos:
- `app/src/main/java/com/sjos/folionote/ui/providers/ProviderAndModelSheets.kt` (campos por volta das linhas 371, 436 a 437 e 542 a 549)
- `app/src/main/java/com/sjos/folionote/engine/context/ContextEngine.kt` (linhas 81 a 84 e 129)
- `app/src/main/java/com/sjos/folionote/data/repository/ChatOrchestrator.kt` (cálculo de `budgetChars` por volta da linha 707)

Diagnóstico:
- O campo aceita só dígitos e não tem teto.
- `toIntOrNull() ?: 8192` troca silenciosamente entrada inválida ou grande demais pelo padrão.
- `coerceAtLeast(512)` e `coerceIn(64, ctxWin)` ajustam valores sem avisar.
- Um número enorme pode estourar `Int` ou gerar um orçamento de caracteres gigante em `budgetChars`.

Tarefa:
1. Validar o campo com erro inline e faixa explícita (justificar os limites no commit).
2. Impedir Max Output maior que Context Window, com mensagem clara.
3. Não trocar o valor digitado sem aviso.
4. Proteger contra overflow em `ContextEngine` e `ChatOrchestrator`.
5. Testes: vazio, zero, valor negativo, `99999999999`, alterar ctx de modelo existente, e persistência após fechar e reabrir o app.

Critério de aceite: todos os testes unitários novos passam e nenhuma conta estoura.

---

## MISSÃO 4 — Reduzir o APK (de ~67 MB para 137 MB, objetivo: o menor possível)

Arquivos: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`, `app/src/main/res/`.

Diagnóstico:
- `isMinifyEnabled = false` na release, sem `shrinkResources`.
- `material-icons-extended` usado em ~174 pontos. É uma das maiores causas de inchaço.
- ML Kit `text-recognition` na versão que empacota modelos no APK.
- `pdfbox-android` embute recursos.
- Hipótese: o APK de 137 MB pode ser o de debug. Confirmar pelo nome do arquivo gerado.

Tarefa:
1. Medir antes: APK debug e APK release atuais, com tamanho em MB.
2. Ativar R8 (`isMinifyEnabled = true`) e `isShrinkResources = true` na release, com regras `proguard-rules.pro` corretas para Room, Moshi, OkHttp, Credentials, WorkManager e ML Kit. Não quebrar reflexão de Moshi nem Room.
3. Substituir `material-icons-extended` por `material-icons-core` mais os ícones realmente usados, ou por vetores próprios.
4. Avaliar ML Kit: versão que usa Google Play Services (sem modelo embutido) versus a atual. Se mantiver, documentar o custo em MB.
5. Reportar a economia de cada passo, em MB.

Critério de aceite: release menor, sem regressão nos testes, e tabela antes/depois no commit ou no relatório.

---

## MISSÃO 5 — RAM, processos e trabalho em segundo plano

Arquivos:
- `app/src/main/java/com/sjos/folionote/data/repository/ChatOrchestrator.kt` (função `runCompletionTurn`)
- `app/src/main/java/com/sjos/folionote/data/local/Daos.kt` (`getChunksForSources`, `getEmbeddingsForSources`, `getMessagesForConversationOnce`)
- `app/src/main/java/com/sjos/folionote/engine/ai/OpenAiCompatibleClient.kt` (`clientForProvider` cria `OkHttpClient` novo a cada chamada)
- `app/src/main/java/com/sjos/folionote/engine/indexing/EmbeddingProvider.kt` (também faz `newBuilder()` a cada chamada)
- `app/src/main/java/com/sjos/folionote/FolioNoteApp.kt` (vários `OkHttpClient` criados por módulo)
- `app/src/main/java/com/sjos/folionote/engine/drive/DriveSyncEngine.kt` (sync periódico a cada 6 h)
- `app/src/main/java/com/sjos/folionote/engine/updater/AppUpdateManager.kt` (checagem automática na abertura)

Diagnóstico:
- A cada mensagem carrega-se todo o histórico, todos os chunks das fontes selecionadas e todos os vetores. O pico de RAM cresce com o tamanho do caderno.
- Clientes OkHttp criados por requisição, sem reaproveitamento de pool e threads.
- Vários `OkHttpClient()` independentes, cada um com seu próprio pool.
- Worker periódico e checagem de update rodam mesmo quando o usuário não quer.

Tarefa:
1. Perfilar com Android Studio Profiler (memória e threads) antes de mudar. Reportar números.
2. Carregar histórico com limite (últimas N mensagens mais o resumo progressivo que já existe) e chunks e vetores sob demanda, com top-K.
3. Reutilizar um único `OkHttpClient` com pool e dispatcher limitados. Eliminar `newBuilder()` por requisição.
4. Verificar que `DriveSyncWorker` não roda sem conta conectada e sem mudança pendente. Reduzir a checagem automática de update, ou deixá-la manual.
5. Garantir que nenhuma thread ou worker fique ativo após o app ir para segundo plano.
6. Reportar RAM ociosa e RAM durante uma resposta, antes e depois.

Critério de aceite: menor pico de RAM medido, sem perder funcionalidade de RAG.

---

## MISSÃO 6 — Compatibilidade com Android 7.0 (API 24) e aparelhos modestos

Contexto: `minSdk = 24`. Aparelho do usuário: Moto G8 Play com Android 10 (API 29). Testar em API 24 (mínimo suportado) e em API 29 (aparelho real). O histórico do repo tem commits "guard Android APIs for API 24", mas a validação em dispositivo físico está marcada como pendente (`ci: defer emulator validation to physical devices`).

Tarefa:
1. Rodar `./gradlew lintRelease` focando em `NewApi` e `ObsoleteSdkInt`. Listar cada API acima de 24 usada e protegê-la com checagem de versão, ou usar desugaring se for `java.time` ou similar.
2. Criar um AVD com API 24 e instalar o APK de release. Testar: abrir, criar caderno, importar um PDF, enviar mensagem no chat.
3. Verificar se Compose, Material 3, WorkManager, Credential Manager e Play Services funcionam em API 24, com fallback claro quando não houver suporte.
4. Verificar o comportamento sem Google Play Services (o app já tem mensagem para isso; conferir que os recursos locais continuam).
5. Relatar o que não pôde ser testado no emulador e precisa de aparelho físico.

Critério de aceite: lint sem erro de `NewApi` em API 24; teste no emulador API 24 registrado no relatório.

---

## MISSÃO 7 — Segurança do auto-update e revisão de permissões

Arquivos:
- `app/src/main/AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`, `INTERNET`, `ACCESS_NETWORK_STATE`)
- `app/src/main/java/com/sjos/folionote/engine/updater/AppUpdateManager.kt`
- `app/src/main/java/com/sjos/folionote/engine/updater/ApkSignatureVerifier.kt`
- `app/src/main/java/com/sjos/folionote/engine/updater/GitHubReleaseService.kt`
- `app/src/main/res/xml/network_security_config.xml`

Achados positivos (manter): o download só aceita HTTPS (fora de localhost de teste), o APK é verificado por assinatura e por SHA-256 antes de instalar, e o instalador do sistema é quem confirma.

Tarefa:
1. Revisar o fluxo do updater do início ao fim: de onde vem `expectedSha256`, se é da mesma release, e o que acontece se a verificação falhar (o APK parcial é apagado?).
2. Confirmar que a instalação só ocorre se a assinatura for igual à do app instalado.
3. Avaliar se `REQUEST_INSTALL_PACKAGES` é realmente necessário e se o fluxo funciona sem ele.
4. Verificar se a checagem automática pode ser desligada pelo usuário nas configurações.
5. Revisar `scripts/configure-github-secrets.sh` e `RELEASES.md` para garantir que nenhum segredo é impresso ou versionado.

Critério de aceite: relatório com cada risco, nível (alto, médio, baixo) e correção proposta ou aplicada.

---

## MISSÃO 8 — Mapa do fluxo do usuário e bugs de interface e backend (SOMENTE LEITURA)

Esta missão NÃO altera código. Entregue apenas um relatório.

Tarefa: percorrer o fluxo completo e registrar bugs com severidade, passos para reproduzir, arquivo e linha.

Fluxo a percorrer:
Home → criar caderno → Fontes (importar arquivo, web, Drive, nota) → processamento de fontes → Chat (enviar, parar, regenerar, erro) → Configurações do chat (ctx, temperatura, provedor) → Provedores e modelos (adicionar, testar conexão, buscar modelos, editar) → Estúdio (gerar artefato) → Configurações (Drive, backup, atualização).

Formato do relatório: tabela com ID, tela, passos, resultado esperado, resultado atual, severidade e arquivo.

Critério de aceite: cobrir todas as telas listadas e indicar o que não pôde ser verificado sem executar o app.
