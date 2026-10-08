@echo off
rem Double-cliquable : lance le serveur VibeCraft.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-server.ps1" %*
pause
