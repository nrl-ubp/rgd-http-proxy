@echo off
rem Installs the driver when needed and creates the ODBC datasource.
rem Parameters are passed to Install-RgdFlightSqlDsn.ps1, e.g.  install.cmd -?  shows its help through Get-Help.
setlocal
set "RGD_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
rem From a 32-bit process, Sysnative is the way to the 64-bit PowerShell that sees the 64-bit ODBC driver.
if exist "%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe" set "RGD_PS=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"

set "RGD_SCRIPT=%~dp0Install-RgdFlightSqlDsn.ps1"
if "%~1"=="-?" goto :help

"%RGD_PS%" -NoProfile -ExecutionPolicy Bypass -File "%RGD_SCRIPT%" %*
set "RGD_RC=%ERRORLEVEL%"

rem Keep the window open when started by a double-click in the Explorer (cmd /c ""...""), not from a prompt.
rem Set RGD_NO_PAUSE=1 when a scheduler or deployment tool runs this file without parameters.
if "%~1"=="" if not defined RGD_NO_PAUSE call :pauseIfDoubleClicked
exit /b %RGD_RC%

:pauseIfDoubleClicked
setlocal EnableDelayedExpansion
set "RGD_LINE=!cmdcmdline!"
if not "!RGD_LINE:""=!"=="!RGD_LINE!" pause
endlocal
exit /b 0

:help
"%RGD_PS%" -NoProfile -ExecutionPolicy Bypass -Command "Get-Help -Full -Name $env:RGD_SCRIPT"
exit /b 0
