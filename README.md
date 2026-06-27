# SEFAZ Sentry Self-Hosted (plugin IntelliJ)

Plugin próprio para integrar a IDE com o **Sentry self-hosted** da SEFAZ
(`https://sentry.sefaz.ba.gov.br`) — algo que os plugins do Marketplace não suportam
(uns só falam com `sentry.io`, outros estão abandonados/incompatíveis).

> **Build com Maven**, referenciando os JARs do **IntelliJ instalado localmente**
> (não baixa a SDK → não esbarra na interceptação SSL corporativa). Em troca,
> não há `runIde`/sandbox: você gera o `.zip` e instala manualmente.

## O que faz
- **Múltiplos ambientes** (Settings → Tools → SEFAZ Sentry): Produção (`sentry...`), Homologação (`hsentry...`) e Desenvolvimento (`dsentry...`) — cada um com URL e token próprios, habilitáveis individualmente. Botão **Testar conexão** testa todos os habilitados.
- **Múltiplos projetos** num mesmo ambiente (slugs separados por vírgula, ex.: `efiscalizacao, web-api`).
- **Tool window "SEFAZ Sentry"** (rodapé) com toolbar: seletor de **Ambiente**, **Fonte** (Issues/Traces/Logs), **Projeto** (Todos ou um), **Ordenação** (Recentes/Frequência/Novos/Usuários) e **Busca** por palavra (ex.: `NullPointer`).
- **Datas** em `dd/MM/yyyy HH:mm:ss` (fuso local).
- **Auto-atualização** (checkbox **Auto** no toolbar): re-consulta a cada N segundos (configurável). O Sentry não faz push pra IDE, então é *polling* — fica quase-tempo-real.
- **Notificações de erro novo** (Settings → grupo *Notificações*): um serviço de fundo vigia os ambientes que você marcar (Produção/Homologação/Desenvolvimento) e, ao surgir um issue novo, mostra um **balloon** no IntelliJ (com ação "Abrir no Sentry"). Intervalo configurável; roda mesmo com a tool window fechada.
- **Navegação stacktrace → código:** ao selecionar um issue mostra os frames; **duplo-clique num frame abre o arquivo na linha**.

> **Traces** e **Logs** usam a API Discover/events do Sentry (best-effort; nomes de campo/dataset podem variar por versão).

## Pré-requisitos
- **JDK 21** (ex.: `C:/Program Files/Java/jdk-21`).
- **Maven** (pode usar o offline da SEFAZ).
- **IntelliJ IDEA instalado** (os JARs da plataforma vêm dele).

## Build (Maven)
1. Confira a propriedade **`intellij.home`** no `pom.xml` (já vem `C:/Program Files/JetBrains/IntelliJ IDEA 2026.1.3`). Ajuste se sua instalação for outra.
2. Empacote (o `MAVEN_OPTS` faz o Maven baixar `kotlin-maven-plugin`/`gson`/`assembly` confiando na CA corporativa):
   ```bat
   set MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT
   mvn clean package
   ```
   Gera **`target/sefaz-sentry-plugin-0.1.0.zip`**.
3. Instale na IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → selecione o `.zip` de `target/`.

## Configurar (Settings → Tools → SEFAZ Sentry)
- **URL:** `https://sentry.sefaz.ba.gov.br`
- **Organização:** `sefaz`
- **Projeto:** `efiscalizacao` (182) ou `web-api` (127)
- **Token:** Personal Token do Sentry (`org:read`, `project:read`, `event:read`).
- Requer **VPN** para alcançar o servidor.

## Troubleshooting
**Erro de compilação "unresolved reference" / classe da plataforma não encontrada**
Falta algum JAR do IntelliJ no classpath. Rode `listar-jars-intellij.bat` e mande a saída,
ou adicione o jar faltante como `<dependency>` `scope=system` no `pom.xml` (seguindo o padrão dos que já estão lá).

## Stack
Kotlin · **Maven** (`kotlin-maven-plugin` + `maven-assembly-plugin`) · JARs do IntelliJ via `scope=system` · `java.net.http` + Gson · JDK 21.
