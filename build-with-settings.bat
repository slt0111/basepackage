@echo off
chcp 65001 >nul
echo ========================================
echo 使用自定义settings.xml构建项目
echo ========================================
echo.

REM 检查Maven环境
where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到Maven环境，请先安装Maven
    pause
    exit /b 1
)

echo [信息] 使用settings.xml配置文件进行构建...
echo.

REM 使用当前目录的settings.xml文件
mvn clean package -s settings.xml -DskipTests

if %errorlevel% equ 0 (
    echo.
    echo [成功] 构建完成！
    echo [信息] jar包位置: target\war-deploy-tool-1.0.0.jar
) else (
    echo.
    echo [错误] 构建失败，请检查错误信息
)

pause

