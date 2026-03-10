@echo off
REM ============================================
REM JAR包停止脚本 - Windows版本
REM ============================================
chcp 65001 >nul
setlocal enabledelayedexpansion

REM 设置JAR文件名（与启动脚本保持一致）
set JAR_NAME=deploy-tool-1.0.0.jar

echo [信息] 正在查找运行中的Java进程...

REM 查找运行指定JAR的Java进程
for /f "tokens=2" %%i in ('jps -l ^| findstr "%JAR_NAME%"') do (
    set PID=%%i
    echo [信息] 找到进程 PID: !PID!
    echo [信息] 正在停止进程...
    taskkill /F /PID !PID! >nul 2>&1
    if !errorlevel! equ 0 (
        echo [成功] 进程 !PID! 已停止
    ) else (
        echo [错误] 停止进程 !PID! 失败
    )
)

REM 如果jps命令不可用，使用tasklist和findstr
if not defined PID (
    echo [信息] 使用备用方法查找进程...
    for /f "tokens=2" %%i in ('tasklist /FI "IMAGENAME eq java.exe" /FO CSV ^| findstr /C:"java.exe"') do (
        REM 这里需要更复杂的逻辑来匹配JAR文件，暂时使用端口方式
    )
)

REM 通过端口查找并停止（默认8080端口）
echo [信息] 检查端口8080占用情况...
for /f "tokens=5" %%i in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    set PORT_PID=%%i
    echo [信息] 找到占用8080端口的进程 PID: !PORT_PID!
    echo [信息] 正在停止进程...
    taskkill /F /PID !PORT_PID! >nul 2>&1
    if !errorlevel! equ 0 (
        echo [成功] 进程 !PORT_PID! 已停止
    ) else (
        echo [错误] 停止进程 !PORT_PID! 失败
    )
)

REM 再次检查是否还有进程运行
timeout /t 2 /nobreak >nul
netstat -ano | findstr ":8080" >nul
if %errorlevel% equ 0 (
    echo [警告] 端口8080仍被占用，可能还有其他进程
) else (
    echo [成功] 服务已完全停止
)

pause

