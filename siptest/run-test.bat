@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem يستخدم JAVA_HOME لو كان معرّفاً، وإلا javac/java من PATH.
rem Uses JAVA_HOME when set, otherwise javac/java from PATH.
set "JAVAC=javac"
set "JAVA=java"
if defined JAVA_HOME set "JAVAC=%JAVA_HOME%\bin\javac"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"

echo === بناء ===
"%JAVAC%" -encoding UTF-8 -d out src\Main.java src\android\util\Log.java src\com\osaid\sawtsecretary\sip\*.java
if errorlevel 1 goto :eof
echo === تشغيل المحاكي ===
start /b cmd /c "node sipsim.js > sim.log 2>&1"
timeout /t 2 >nul
echo === تشغيل كود التطبيق ===
"%JAVA%" -Dfile.encoding=UTF-8 -cp out Main 127.0.0.1 5070
timeout /t 4 >nul
echo.
echo === تقرير المزوّد المحاكى ===
type sim.log
