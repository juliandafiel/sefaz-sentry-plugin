@echo off
setlocal
rem ============================================================
rem  Lista os JARs da plataforma IntelliJ instalada localmente.
rem  Uso:
rem    listar-jars-intellij.bat "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3"
rem  (se nao passar o caminho, usa o default abaixo)
rem ============================================================

set "IDEA=%~1"
if "%IDEA%"=="" set "IDEA=C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3"

if not exist "%IDEA%\lib" (
    echo [ERRO] A pasta "%IDEA%\lib" nao existe.
    echo Passe o caminho da sua IntelliJ, por exemplo:
    echo     listar-jars-intellij.bat "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3"
    echo Dica: IntelliJ ^> Help ^> About mostra a versao; ou clique direito no atalho ^> Abrir local do arquivo.
    exit /b 1
)

> lib-jars.txt echo IDEA_HOME=%IDEA%
>> lib-jars.txt echo ---LIB---
dir /b "%IDEA%\lib\*.jar" >> lib-jars.txt
>> lib-jars.txt echo ---MODULES---
if exist "%IDEA%\lib\modules" dir /b "%IDEA%\lib\modules\*.jar" >> lib-jars.txt

echo.
type lib-jars.txt
echo.
echo ============================================================
echo Gerado: lib-jars.txt
echo Cole o conteudo no chat, OU envie pelo git:
echo     git add lib-jars.txt ^&^& git commit -m "lista jars do IntelliJ" ^&^& git push
echo ============================================================
endlocal
