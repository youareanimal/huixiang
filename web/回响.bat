@echo off
chcp 936 >nul 2>&1
setlocal enableextensions

REM ============================================================
REM  回响 · 省内存启动器（通用版）
REM  1) 自动查找 Microsoft Edge 的安装位置
REM  2) 用独立配置启动回响，并禁用所有扩展以尽量节省内存
REM  首次使用需在弹出的回响页面里登录一次，之后会自动记住。
REM  如需修改服务器地址，只改下面 APP_URL 这一行即可。
REM ============================================================
set "APP_URL=https://ts.xinmouren.cn/"
set "UDD=%LocalAppData%\HuixiangLowMem"
REM ============================================================

set "EDGE="
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" set "EDGE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not defined EDGE if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" set "EDGE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not defined EDGE if exist "%LocalAppData%\Microsoft\Edge\Application\msedge.exe" set "EDGE=%LocalAppData%\Microsoft\Edge\Application\msedge.exe"
if not defined EDGE call :find_reg "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe"
if not defined EDGE call :find_reg "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe"
if not defined EDGE call :find_reg "HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe"

if not defined EDGE (
  echo.
  echo [错误] 没有找到 Microsoft Edge。
  echo 请先安装 Microsoft Edge，或用记事本打开本文件，手动填写 EDGE 路径。
  echo.
  pause
  exit /b 1
)

if not exist "%UDD%" mkdir "%UDD%" >nul 2>&1

echo 已找到 Edge: "%EDGE%"
echo 正在以省内存模式启动回响（独立配置，首次需登录一次）...
start "" "%EDGE%" --user-data-dir="%UDD%" --disable-extensions --app=%APP_URL% --no-first-run --no-default-browser-check
if errorlevel 1 (
  echo.
  echo [提示] 启动命令似乎失败，请确认 Edge 可正常使用后重试。
  pause
)
exit /b 0

:find_reg
for /f "tokens=2,*" %%A in ('reg query "%~1" /ve 2^>nul ^| findstr /i "REG_SZ"') do (
  if exist "%%B" set "EDGE=%%B"
)
exit /b 0
