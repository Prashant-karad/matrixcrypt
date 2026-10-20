@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\*.java || exit /b 1
java -Djava.awt.headless=true -cp out Main %*
