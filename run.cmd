@echo off
rem One-command startup: emulator + install + launch.
rem Call Git Bash by full path so WSL's bash (System32) is never picked by accident.
"C:\Program Files\Git\bin\bash.exe" "%~dp0run.sh" %*
pause
