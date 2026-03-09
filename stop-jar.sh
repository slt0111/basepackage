#!/bin/bash
# ============================================
# JAR包停止脚本 - Linux版本
# ============================================

# 获取脚本所在目录（与启动脚本保持一致）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 设置PID文件路径（与启动脚本保持一致）
LOG_DIR="${SCRIPT_DIR}/logs"
PID_FILE="${LOG_DIR}/app.pid"

# 设置JAR文件名（与启动脚本保持一致）
JAR_NAME="war-deploy-tool-1.0.0.jar"

echo "[信息] 正在停止应用..."

# 方法1: 通过PID文件停止
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "[信息] 找到进程 PID: $PID"
        echo "[信息] 正在停止进程..."
        kill "$PID" 2>/dev/null
        
        # 等待进程结束，最多等待10秒
        for i in {1..10}; do
            if ! ps -p "$PID" > /dev/null 2>&1; then
                echo "[成功] 进程 $PID 已停止"
                rm -f "$PID_FILE"
                exit 0
            fi
            sleep 1
        done
        
        # 如果进程仍在运行，强制杀死
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "[警告] 进程未响应，强制停止..."
            kill -9 "$PID" 2>/dev/null
            sleep 1
            if ! ps -p "$PID" > /dev/null 2>&1; then
                echo "[成功] 进程 $PID 已强制停止"
                rm -f "$PID_FILE"
            else
                echo "[错误] 无法停止进程 $PID"
            fi
        fi
    else
        echo "[信息] PID文件存在但进程不存在，清理PID文件"
        rm -f "$PID_FILE"
    fi
fi

# 方法2: 通过JAR名称查找并停止
echo "[信息] 查找运行中的Java进程..."
JAVA_PIDS=$(ps aux | grep "[j]ava.*${JAR_NAME}" | awk '{print $2}')

if [ -n "$JAVA_PIDS" ]; then
    for PID in $JAVA_PIDS; do
        echo "[信息] 找到进程 PID: $PID"
        echo "[信息] 正在停止进程..."
        kill "$PID" 2>/dev/null
        
        # 等待进程结束
        for i in {1..5}; do
            if ! ps -p "$PID" > /dev/null 2>&1; then
                echo "[成功] 进程 $PID 已停止"
                break
            fi
            sleep 1
        done
        
        # 如果进程仍在运行，强制杀死
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "[警告] 进程未响应，强制停止..."
            kill -9 "$PID" 2>/dev/null
            sleep 1
            if ! ps -p "$PID" > /dev/null 2>&1; then
                echo "[成功] 进程 $PID 已强制停止"
            fi
        fi
    done
fi

# 方法3: 通过端口查找并停止（默认8080端口）
echo "[信息] 检查端口8080占用情况..."
PORT_PID=$(lsof -ti:8080 2>/dev/null || netstat -tuln | grep ":8080" | awk '{print $NF}' | cut -d'/' -f1 | head -1)

if [ -n "$PORT_PID" ]; then
    echo "[信息] 找到占用8080端口的进程 PID: $PORT_PID"
    echo "[信息] 正在停止进程..."
    kill "$PORT_PID" 2>/dev/null
    
    # 等待进程结束
    sleep 2
    if ! ps -p "$PORT_PID" > /dev/null 2>&1; then
        echo "[成功] 进程 $PORT_PID 已停止"
    else
        echo "[警告] 进程未响应，强制停止..."
        kill -9 "$PORT_PID" 2>/dev/null
        sleep 1
        if ! ps -p "$PORT_PID" > /dev/null 2>&1; then
            echo "[成功] 进程 $PORT_PID 已强制停止"
        fi
    fi
fi

# 最终检查
sleep 1
if lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null 2>&1 || netstat -tuln | grep -q ":8080 "; then
    echo "[警告] 端口8080仍被占用，可能还有其他进程"
    echo "[信息] 使用以下命令查看占用端口的进程: lsof -i:8080 或 netstat -tuln | grep 8080"
else
    echo "[成功] 服务已完全停止"
fi

