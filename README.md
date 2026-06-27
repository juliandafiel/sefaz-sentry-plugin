# SEFAZ Sentry Self-Hosted (plugin IntelliJ)

Plugin próprio para integrar a IDE com o **Sentry self-hosted** da SEFAZ
(`https://sentry.sefaz.ba.gov.br`) — algo que os plugins do Marketplace não suportam
(uns só falam com `sentry.io`, outros estão abandonados/incompatíveis).

## O que faz
- **Configuração** (Settings → Tools → SEFAZ Sentry): URL do servidor, token (guardado no PasswordSafe), organização e projeto + botão **Testar conexão**.
- **Tool window "SEFAZ Sentry"** (rodapé): lista os issues (`is:unresolved` por padrão), com detalhe e link pro Sentry (duplo-clique no issue abre no navegador).
- **Navegação stacktrace → código:** ao selecionar um issue, mostra os frames do último evento; **duplo-clique num frame abre o arquivo na linha** (acha o arquivo por nome via `FilenameIndex`, desambiguando pelo pacote). Frames `in-app` em destaque, libs em cinza.

## Fase 3 (futuro)
- Múltiplas conexões/projetos, cache, filtros de query na própria tool window, marcação inline nas linhas.

## Build / instalação
Pré-requisitos: JDK 17+ (a IDE 2024.3+ usa JDK 21), internet para baixar a SDK.

```bash
./gradlew buildPlugin     # gera build/distributions/sefaz-sentry-plugin-0.1.0.zip
./gradlew runIde          # abre uma IDE de teste com o plugin instalado
```

Instalar na sua IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → selecione o `.zip` de `build/distributions`.

## Configurar (Settings → Tools → SEFAZ Sentry)
- **URL:** `https://sentry.sefaz.ba.gov.br`
- **Organização:** `sefaz`
- **Projeto:** `efiscalizacao` (182) ou `web-api` (127)
- **Token:** Personal Token do Sentry com escopos `org:read`, `project:read`, `event:read`.
- Requer **VPN** para alcançar o servidor.

## Stack
Kotlin · IntelliJ Platform Gradle Plugin 2.x · `java.net.http` + Gson · compilado em IC 2024.3
(`untilBuild` aberto → roda em 2026.1+).
