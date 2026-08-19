@echo off
REM Duplo clique neste arquivo para configurar um tablet.
REM Ele so chama o script principal com a permissao necessaria.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0provisionar.ps1"
pause
