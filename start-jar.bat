@echo off
REM ============================================
REM JAR包启动脚本 - Windows版本
REM ============================================
chcp 65001 >nul
setlocal enabledelayedexpansion

REM 获取脚本所在目录（无论脚本在哪里都能正确找到JAR文件）
set SCRIPT_DIR=%~dp0
REM 移除末尾的反斜杠
set SCRIPT_DIR=%SCRIPT_DIR:~0,-1%

REM 设置JAR文件名（根据实际打包后的文件名修改）
set JAR_NAME=war-deploy-tool-1.0.0.jar

REM 自动查找JAR文件（按优先级顺序）
REM 1. 首先在脚本所在目录查找
set JAR_PATH=%SCRIPT_DIR%\%JAR_NAME%
if exist "%JAR_PATH%" goto :found_jar

REM 2. 在脚本所在目录的target子目录查找
set JAR_PATH=%SCRIPT_DIR%\target\%JAR_NAME%
if exist "%JAR_PATH%" goto :found_jar

REM 3. 在当前工作目录查找
set JAR_PATH=%CD%\%JAR_NAME%
if exist "%JAR_PATH%" goto :found_jar

REM 4. 在当前工作目录的target子目录查找
set JAR_PATH=%CD%\target\%JAR_NAME%
if exist "%JAR_PATH%" goto :found_jar

REM 如果都找不到，报错退出
echo [错误] 未找到JAR文件: %JAR_NAME%
echo 已搜索以下位置:
echo   1. %SCRIPT_DIR%\%JAR_NAME%
echo   2. %SCRIPT_DIR%\target\%JAR_NAME%
echo   3. %CD%\%JAR_NAME%
echo   4. %CD%\target\%JAR_NAME%
echo.
echo 请确保JAR文件与启动脚本在同一目录，或修改脚本中的JAR_NAME变量
pause
exit /b 1

:found_jar
REM 将路径转换为绝对路径（避免相对路径问题）
for %%I in ("%JAR_PATH%") do set JAR_PATH=%%~fI

REM 检查Java环境
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到Java环境，请确保已安装JDK 1.8或更高版本
    echo 请设置JAVA_HOME环境变量或将java添加到PATH
    pause
    exit /b 1
)

REM 显示Java版本
echo [信息] 检测Java环境...
java -version
echo.

REM 设置JVM参数（可根据需要调整）
REM -Xms: 初始堆内存大小
REM -Xmx: 最大堆内存大小
REM -XX:MetaspaceSize: 元空间初始大小
REM -XX:MaxMetaspaceSize: 元空间最大大小
set JVM_OPTS=-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m

REM 设置应用参数（如需要指定配置文件等）
REM set APP_OPTS=--spring.config.location=classpath:/application.yml

REM 设置日志文件路径（在脚本所在目录创建logs文件夹）
set LOG_DIR=%SCRIPT_DIR%\logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
set LOG_FILE=%LOG_DIR%\app-%date:~0,4%%date:~5,2%%date:~8,2%-%time:~0,2%%time:~3,2%%time:~6,2%.log
set LOG_FILE=%LOG_FILE: =0%

REM 检查端口是否被占用（默认8080端口）
echo [信息] 检查端口8080是否被占用...
netstat -ano | findstr ":8080" >nul
if %errorlevel% equ 0 (
    echo [警告] 端口8080已被占用，启动可能会失败
    echo 请先停止占用该端口的程序，或修改application.yml中的端口配置
    echo.
    choice /C YN /M "是否继续启动"
    if errorlevel 2 exit /b 1
)

REM 启动应用
echo [信息] 正在启动应用...
echo [信息] JAR文件: %JAR_PATH%
echo [信息] 日志文件: %LOG_FILE%
echo [信息] 访问地址: http://localhost:8080
echo.
echo ============================================
echo 启动中，请稍候...
echo 按 Ctrl+C 可停止服务
echo ============================================
echo.

REM 切换到脚本所在目录（确保相对路径正确）
cd /d "%SCRIPT_DIR%"

REM 启动JAR（输出到控制台）
REM 注意：Windows批处理文件不能直接同时输出到控制台和文件
REM 如果需要保存日志，请使用: java %JVM_OPTS% -jar "%JAR_PATH%" %APP_OPTS% > "%LOG_FILE%" 2>&1
REM 或者使用PowerShell: powershell -Command "java %JVM_OPTS% -jar '%JAR_PATH%' %APP_OPTS% 2>&1 | Tee-Object -FilePath '%LOG_FILE%'"
java %JVM_OPTS% -jar "%JAR_PATH%" %APP_OPTS%

REM 如果程序退出，显示退出信息
echo.
echo [信息] 应用已停止
pause

