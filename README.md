# SEFAZ Sentry Self-Hosted (plugin IntelliJ)

Plugin próprio para integrar a IDE com o **Sentry self-hosted** da SEFAZ
(`https://sentry.sefaz.ba.gov.br`) — algo que os plugins do Marketplace não suportam
(uns só falam com `sentry.io`, outros estão abandonados/incompatíveis).

> **Build com Maven**, referenciando os JARs do **IntelliJ instalado localmente**
> (não baixa a SDK → não esbarra na interceptação SSL corporativa). Em troca,
> não há `runIde`/sandbox: você gera o `.zip` e instala manualmente.

## O que faz
- **Configuração** (Settings → Tools → SEFAZ Sentry): URL do servidor, token (no PasswordSafe), organização e projeto + botão **Testar conexão**.
- **Tool window "SEFAZ Sentry"** (rodapé): lista os issues (`is:unresolved`), com detalhe e link pro Sentry.
- **Navegação stacktrace → código:** ao selecionar um issue mostra os frames; **duplo-clique num frame abre o arquivo na linha**.

## Pré-requisitos
- **JDK 21** (ex.: `C:/Program Files/Java/jdk-21`).
- **Maven** (pode usar o offline da SEFAZ).
- **IntelliJ IDEA instalado** (os JARs da plataforma vêm dele).

## Build (Maven)
1. No `pom.xml`, ajuste a propriedade **`intellij.home`** para a pasta da sua instalação do IntelliJ (a que contém `lib`). Ex.:
   ```xml
   <intellij.home>C:/Program Files/JetBrains/IntelliJ IDEA 2026.1</intellij.home>
   ```
2. Empacote:
   ```bash
   mvn -o clean package          # gera target/sefaz-sentry-plugin-0.1.0.zip
   ```
3. Instale na IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → selecione o `.zip` de `target/`.

## Configurar (Settings → Tools → SEFAZ Sentry)
- **URL:** `https://sentry.sefaz.ba.gov.br`
- **Organização:** `sefaz`
- **Projeto:** `efiscalizacao` (182) ou `web-api` (127)
- **Token:** Personal Token do Sentry (`org:read`, `project:read`, `event:read`).
- Requer **VPN** para alcançar o servidor.

## Troubleshooting
**Erro de compilação "unresolved reference" / classe da plataforma não encontrada**
Falta algum JAR do IntelliJ no classpath. Liste os JARs da sua versão:
```bat
dir /b "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\lib\*.jar"
```
e adicione os que faltam como `<dependency>` `scope=system` no `pom.xml` (seguindo o padrão dos que já estão lá).

## Stack
Kotlin · **Maven** (`kotlin-maven-plugin` + `maven-assembly-plugin`) · JARs do IntelliJ via `scope=system` · `java.net.http` + Gson · JDK 21.
