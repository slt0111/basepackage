#!/bin/bash
# ============================================
# JAR包启动脚本 - Linux版本
# ============================================

# 获取脚本所在目录（无论脚本在哪里都能正确找到JAR文件）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 设置JAR文件名（根据实际打包后的文件名修改）
JAR_NAME="deploy-tool-1.0.0.jar"

# 自动查找JAR文件（按优先级顺序）
# 1. 首先在脚本所在目录查找
JAR_PATH="${SCRIPT_DIR}/${JAR_NAME}"
if [ -f "$JAR_PATH" ]; then
    echo "[信息] 在脚本目录找到JAR文件: ${JAR_PATH}"
# 2. 在脚本所在目录的target子目录查找
elif [ -f "${SCRIPT_DIR}/target/${JAR_NAME}" ]; then
    JAR_PATH="${SCRIPT_DIR}/target/${JAR_NAME}"
    echo "[信息] 在脚本目录的target子目录找到JAR文件: ${JAR_PATH}"
# 3. 在当前工作目录查找
elif [ -f "${JAR_NAME}" ]; then
    JAR_PATH="$(cd "$(dirname "${JAR_NAME}")" && pwd)/$(basename "${JAR_NAME}")"
    echo "[信息] 在当前目录找到JAR文件: ${JAR_PATH}"
# 4. 在当前工作目录的target子目录查找
elif [ -f "target/${JAR_NAME}" ]; then
    JAR_PATH="$(cd "$(dirname "target/${JAR_NAME}")" && pwd)/$(basename "target/${JAR_NAME}")"
    echo "[信息] 在当前目录的target子目录找到JAR文件: ${JAR_PATH}"
else
    echo "[错误] 未找到JAR文件: ${JAR_NAME}"
    echo "已搜索以下位置:"
    echo "  1. ${SCRIPT_DIR}/${JAR_NAME}"
    echo "  2. ${SCRIPT_DIR}/target/${JAR_NAME}"
    echo "  3. $(pwd)/${JAR_NAME}"
    echo "  4. $(pwd)/target/${JAR_NAME}"
    echo ""
    echo "请确保JAR文件与启动脚本在同一目录，或修改脚本中的JAR_NAME变量"
    exit 1
fi

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "[错误] 未找到Java环境，请确保已安装JDK 1.8或更高版本"
    echo "请设置JAVA_HOME环境变量或将java添加到PATH"
    exit 1
fi

# 显示Java版本
echo "[信息] 检测Java环境..."
java -version
echo ""

# 设置JVM参数（可根据需要调整）
# -Xms: 初始堆内存大小
# -Xmx: 最大堆内存大小
# -XX:MetaspaceSize: 元空间初始大小
# -XX:MaxMetaspaceSize: 元空间最大大小
JVM_OPTS="-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m"

# 设置应用参数（如需要指定配置文件等）
# APP_OPTS="--spring.config.location=classpath:/application.yml"

# 设置日志文件路径（在脚本所在目录创建logs文件夹）
LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/app-$(date +%Y%m%d-%H%M%S).log"

# 设置PID文件路径
PID_FILE="${LOG_DIR}/app.pid"

# 检查是否已经运行
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "[警告] 应用已经在运行中 (PID: $PID)"
        echo "如需重启，请先执行 stop-jar.sh 停止服务"
        exit 1
    else
        # PID文件存在但进程不存在，删除旧的PID文件
        rm -f "$PID_FILE"
    fi
fi

# 检查端口是否被占用（默认8022端口，对应application.yml中的server.port）
#echo "[信息] 检查端口8022是否被占用..."
#if lsof -Pi :8022 -sTCP:LISTEN -t >/dev/null 2>&1 || netstat -tuln | grep -q ":8022 "; then
#    echo "[警告] 端口8022已被占用，启动可能会失败"
#    echo "请先停止占用该端口的程序，或修改application.yml中的端口配置"
#    read -p "是否继续启动? (y/n): " -n 1 -r
#    echo
#    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
#        exit 1
#    fi
#fi

# 启动应用
echo "[信息] 正在启动应用..."
echo "[信息] JAR文件: ${JAR_PATH}"
echo "[信息] 日志文件: ${LOG_FILE}"
echo "[信息] PID文件: ${PID_FILE}"
echo "[信息] 访问地址: http://localhost:8022"
echo ""
echo "============================================"
echo "启动中，请稍候..."
echo "查看日志: tail -f ${LOG_FILE}"
echo "停止服务: ./stop-jar.sh 或 kill \$(cat ${PID_FILE})"
echo "============================================"
echo ""

# 启动JAR（后台运行，输出到日志文件）
nohup java ${JVM_OPTS} -jar "${JAR_PATH}" ${APP_OPTS} > "${LOG_FILE}" 2>&1 &
PID=$!

# 保存PID
echo $PID > "$PID_FILE"

# 等待几秒检查进程是否启动成功
sleep 3
if ps -p "$PID" > /dev/null 2>&1; then
    echo "[成功] 应用启动成功！"
    echo "[信息] PID: $PID"
    echo "[信息] 日志文件: ${LOG_FILE}"
    echo "[信息] 使用以下命令查看日志: tail -f ${LOG_FILE}"
    echo "[信息] 使用以下命令停止服务: ./stop-jar.sh"
else
    echo "[错误] 应用启动失败，请查看日志文件: ${LOG_FILE}"
    rm -f "$PID_FILE"
    exit 1
fi

