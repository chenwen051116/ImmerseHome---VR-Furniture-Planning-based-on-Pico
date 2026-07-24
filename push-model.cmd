@echo off
rem push-model.cmd — PowerShell/CMD wrapper for push-model.sh (runs it through Git Bash)
setlocal
set "GIT_BASH=C:\Program Files\Git\bin\bash.exe"
if not exist "%GIT_BASH%" (
    echo ERROR: Git Bash not found at "%GIT_BASH%". 1>&2
    echo Run push-model.sh from a Git Bash window instead. 1>&2
    exit /b 1
)
"%GIT_BASH%" "%~dp0push-model.sh" %*
endlocal
