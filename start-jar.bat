@echo off
REM ============================================
REM JAR包启动脚本 - Windows精简稳定版
REM 说明：只做三件事——定位JAR、定位Java、打印关键信息并启动
REM ============================================

REM 使用UTF-8编码，避免中文输出乱码
chcp 65001 >nul

REM 启用局部变量作用域
setlocal enabledelayedexpansion

REM 获取脚本所在目录（双击/任意目录调用都能正确定位到JAR）
set "SCRIPT_DIR=%~dp0"
REM 去掉末尾反斜杠，统一路径格式
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

echo ============================================
echo [信息] 启动脚本目录: %SCRIPT_DIR%
echo [信息] 当前工作目录: %CD%
echo ============================================
echo.

REM 设置JAR文件名（根据实际打包后的文件名修改）
REM 说明：如果你的打包产物是 deploy-tool-1.0.0-SNAPSHOT.jar，请同步修改这里
set "JAR_NAME=deploy-tool-1.0.0.jar"

REM 默认假设JAR和脚本在同一目录（你的场景即 C:\deploy 下）
set "JAR_PATH=%SCRIPT_DIR%\%JAR_NAME%"
echo [信息] 优先尝试JAR路径: %JAR_PATH%
if exist "%JAR_PATH%" goto :found_jar

REM 备选：脚本目录下的 target 子目录（兼容未手工拷贝JAR的情况）
set "JAR_PATH=%SCRIPT_DIR%\target\%JAR_NAME%"
echo [信息] 备选尝试JAR路径: %JAR_PATH%
if exist "%JAR_PATH%" goto :found_jar

REM 如果都找不到，给出清晰提示并停留窗口
echo.
echo [错误] 未找到JAR文件: %JAR_NAME%
echo [错误] 已尝试以下路径:
echo         1. %SCRIPT_DIR%\%JAR_NAME%
echo         2. %SCRIPT_DIR%\target\%JAR_NAME%
echo.
echo [提示] 请确认:
echo         - JAR文件已经打包生成
echo         - JAR已放到与 start-jar.bat 同一目录，或同步修改脚本中的 JAR_NAME
echo.
pause
exit /b 1

:found_jar
REM 将路径标准化为绝对路径，避免相对路径造成歧义
for %%I in ("%JAR_PATH%") do set "JAR_PATH=%%~fI"
echo.
echo [信息] 最终使用的JAR路径: %JAR_PATH%
echo.

REM 检查Java环境
REM 优先使用 JAVA_HOME\bin\java.exe，其次使用PATH中的 java
set "JAVA_EXE="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    )
)

REM 如果 JAVA_EXE 还未确定，则退回到 PATH 中的 java
if not defined JAVA_EXE (
    for /f "delims=" %%I in ('where java 2^>nul') do (
        if not defined JAVA_EXE set "JAVA_EXE=%%I"
    )
)

REM 若仍未找到Java，给出清晰错误信息
if not defined JAVA_EXE (
    echo [错误] 未找到可用的 Java 可执行文件
    echo [提示] 请确认：
    echo         1. 已安装 JDK 1.8 或更高版本
    echo         2. 已正确设置 JAVA_HOME，或已将 java 加入 PATH
    echo.
    echo 当前 JAVA_HOME: %JAVA_HOME%
    echo 当前 PATH 片段:
    echo   %PATH%
    echo.
    pause
    exit /b 1
)

echo [信息] 使用的Java可执行文件: %JAVA_EXE%
echo.
echo [信息] Java版本信息:
"%JAVA_EXE%" -version
echo.

REM 设置JVM参数（可按实际机器资源进行调整）
REM 说明：这里给一个相对保守的默认值，避免内存过大导致启动失败
set "JVM_OPTS=-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m"

REM 预留应用参数（如指定外部配置文件等）
REM 示例：set "APP_OPTS=--spring.config.location=classpath:/application.yml"
set "APP_OPTS="

REM 打印即将执行的完整命令，方便你直接复制到命令行复现
echo ============================================
echo [信息] 即将执行的启动命令:
echo   "%JAVA_EXE%" %JVM_OPTS% -jar "%JAR_PATH%" %APP_OPTS%
echo ============================================
echo.

REM 为了兼容相对路径资源，切换到脚本所在目录
cd /d "%SCRIPT_DIR%"

REM 真正启动应用（前台运行），让窗口在服务运行期间保持存在
REM 如果应用异常退出，会在后面打印退出码
"%JAVA_EXE%" %JVM_OPTS% -jar "%JAR_PATH%" %APP_OPTS%
set "APP_EXIT_CODE=%errorlevel%"

echo.
echo ============================================
echo [信息] Java进程已退出，退出码: %APP_EXIT_CODE%
if not "%APP_EXIT_CODE%"=="0" (
    echo [错误] 应用启动/运行过程中出现异常，请根据上方堆栈信息排查
    echo [提示] 你也可以手动复制上面的命令到 cmd 中再次执行，观察详细输出
)
echo ============================================
echo.

REM 无论成功还是失败，都等待用户按键，避免窗口直接关闭
pause

