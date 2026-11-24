# FolioNote — builds e releases

## Builds de teste

Cada push para `main` e cada pull request executam os testes unitários e geram um APK de debug. O APK fica disponível em **GitHub → Actions → execução → Artifacts** por 14 dias.

## Releases instaláveis

Uma versão pública é publicada em **GitHub → Releases** quando uma tag SemVer é enviada:

```bash
git tag v1.0.0
git push origin v1.0.0
```

A automação compila e testa o projeto, assina o APK com a chave privada de release e anexa `app-release.apk` ao release correspondente.

Atualize `versionName` e `versionCode` por meio das propriedades Gradle na automação; não reutilize o mesmo `versionCode` em versões diferentes. O workflow deriva os valores da tag `vMAJOR.MINOR.PATCH`.

## Assinatura

A chave privada `my-upload-key.jks` e as credenciais locais ficam fora do Git. O GitHub Actions recebe a cópia necessária por meio dos secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` e `ANDROID_KEY_PASSWORD`.

**Nunca** adicione o keystore, senhas ou arquivos `.env` ao repositório, nem os envie ao agente de programação. Mantenha uma cópia de segurança protegida da chave: perder a chave de assinatura impede atualizar a instalação existente com o mesmo certificado.
