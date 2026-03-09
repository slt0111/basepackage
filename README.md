# WAR包服务部署启动工具

基于Spring Boot的WAR包服务部署启动工具，支持Windows/Linux系统、Tomcat/TongWeb中间件，提供可视化界面进行配置管理和自动化部署。

## 功能特性

- ✅ 可视化配置界面
- ✅ 支持Windows和Linux系统
- ✅ 支持Tomcat和TongWeb中间件
- ✅ 自动解压Tomcat压缩包
- ✅ 自动部署WAR包
- ✅ 自动修改Tomcat端口配置
- ✅ 实时日志显示（WebSocket）
- ✅ 服务检测功能
- ✅ 数据库初始化脚本执行
- ✅ 配置保存和加载

## 系统要求

- JDK 1.8 或更高版本
- Maven 3.6+（用于编译打包）
- 操作系统：Windows 或 Linux

## 快速开始

### 1. 准备资源文件

将以下文件放入对应的资源目录：

```
src/main/resources/
├── wars/
│   ├── tyzc/
│   │   └── tyzc.war      # 统一支撑WAR包
│   └── gbgl/
│       └── gbgl.war      # 干部应用WAR包
├── tomcat/
│   └── tomcat.zip        # Tomcat压缩包（zip格式）
├── yml/
│   ├── tyzc/
│   │   └── application-dev-dm.yml  # 统一支撑YML模板
│   └── gbgl/
│       └── application-dev-dm.yml  # 干部应用YML模板
└── scripts/
    └── *.sql             # 初始化SQL脚本
```

### 2. 编译打包

**Windows:**
```bash
mvn clean package
```

**Linux:**
```bash
mvn clean package
```

### 3. 启动服务

**Windows:**
```bash
start.bat
```

**Linux:**
```bash
chmod +x start.sh
./start.sh
```

启动脚本会自动检查环境、编译打包（如需要）并启动服务。

### 4. 访问界面

打开浏览器访问：http://localhost:8080

## 使用说明

### 部署配置

1. **安装目录**：设置WAR包和Tomcat的安装目录
2. **操作系统**：选择Windows或Linux
3. **中间件类型**：选择Tomcat或TongWeb
4. **端口配置**：添加需要配置的端口（HTTP、AJP、Shutdown）
5. **数据库配置**：添加数据库连接信息（支持MySQL、Oracle、PostgreSQL）
6. **WAR包选择**：使用内置WAR包或选择自定义WAR包

### 向导式部署流程

工具采用6步向导式配置，按步骤完成部署：

**第一步：基础配置**
- 安装目录：设置WAR包和Tomcat的安装目录
- 操作系统：自动识别（Windows/Linux）
- 中间件类型：选择Tomcat或TongWeb
- 端口配置：统一支撑（8088）、干部应用（8099）

**第二步：数据库配置**
- 统一支撑数据库：配置数据库类型、连接串、用户名、密码
- 干部应用数据库：配置数据库类型、连接串、用户名、密码
- 提供连接测试功能，验证数据库连接是否正常

**第三步：WAR包管理**
- 统一支撑WAR包：展示资源目录下所有WAR包文件列表
- 干部应用WAR包：展示资源目录下所有WAR包文件列表
- 部署时会自动部署目录下所有的WAR包

**第四步：YML配置**
- 读取YML模板文件（resources/yml目录下）
- 区分统一支撑和干部应用
- 允许编辑YML配置内容
- 保存配置供后续部署使用

**第五步：确认配置**
- 左侧显示配置清单
- 右侧显示部署日志和服务检测
- 点击"开始部署"启动部署流程
- 点击"保存配置"保存当前配置到文件
- 点击"加载配置"从文件加载之前保存的配置

**第六步：数据初始化**
- 选择数据库（统一支撑或干部应用）
- 执行初始化SQL脚本

### 配置保存和加载

#### 配置保存
- **保存位置**：配置文件保存在项目根目录下的 `deploy-config.json` 文件
- **保存内容**：包括所有配置信息（安装目录、中间件类型、端口、数据库、WAR包、YML配置等）
- **保存方式**：在"确认配置"页面点击"保存配置"按钮

#### 配置加载
- **加载方式**：在"确认配置"页面点击"加载配置"按钮
- **加载逻辑**：
  1. 从 `deploy-config.json` 文件读取配置
  2. 自动填充到各个配置页面
  3. 更新配置摘要显示
- **注意事项**：
  - 如果配置文件不存在，会提示"未找到保存的配置"
  - 加载配置后，可以继续修改或直接开始部署
  - WAR包列表会重新从资源目录加载，但会保留配置中指定的WAR包信息

#### 配置文件位置
- **Windows**: `项目根目录\deploy-config.json`（例如：`E:\git_work_new\basepackage\deploy-config.json`）
- **Linux**: `项目根目录/deploy-config.json`

配置文件是JSON格式，可以直接查看和编辑（不推荐手动编辑，建议通过界面操作）。

### 停止服务

**Windows:**
```bash
stop.bat
```

**Linux:**
```bash
chmod +x stop.sh
./stop.sh
```

## JAR包启动方式

如果已经打包好JAR文件，可以直接使用JAR包启动脚本，无需重新编译。

### 1. 打包JAR文件

**Windows/Linux:**
```bash
mvn clean package
```

打包完成后，JAR文件位于 `target/war-deploy-tool-1.0.0.jar`

### 2. 启动JAR服务

**Windows:**
```bash
start-jar.bat
```

**Linux:**
```bash
chmod +x start-jar.sh
./start-jar.sh
```

启动脚本功能：
- ✅ 自动检测Java环境
- ✅ 自动检查端口占用
- ✅ 自动创建日志目录
- ✅ 后台运行（Linux）或前台运行（Windows）
- ✅ 输出日志到文件

### 3. 停止JAR服务

**Windows:**
```bash
stop-jar.bat
```

**Linux:**
```bash
chmod +x stop-jar.sh
./stop-jar.sh
```

### 4. 查看日志

**Windows:**
日志文件位于 `logs/app-YYYYMMDD-HHMMSS.log`

**Linux:**
```bash
# 实时查看日志
tail -f logs/app-*.log

# 查看最新日志
ls -lt logs/ | head -5
```

### 5. JVM参数配置

如需调整JVM参数（内存大小等），可以编辑启动脚本中的 `JVM_OPTS` 变量：

**Windows (start-jar.bat):**
```batch
set JVM_OPTS=-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m
```

**Linux (start-jar.sh):**
```bash
JVM_OPTS="-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m"
```

常用参数说明：
- `-Xms`: 初始堆内存大小（如：512m、1g）
- `-Xmx`: 最大堆内存大小（如：1024m、2g）
- `-XX:MetaspaceSize`: 元空间初始大小
- `-XX:MaxMetaspaceSize`: 元空间最大大小

## 项目结构

```
basepackage/
├── src/main/java/com/deploy/
│   ├── DeployApplication.java          # Spring Boot启动类
│   ├── controller/                     # API控制器
│   ├── service/                        # 业务服务
│   ├── model/                          # 数据模型
│   ├── util/                           # 工具类
│   ├── websocket/                      # WebSocket处理
│   └── config/                          # 配置类
├── src/main/resources/
│   ├── static/                         # 前端静态资源
│   ├── wars/                           # WAR包资源目录
│   ├── tomcat/                         # Tomcat资源目录
│   └── scripts/                        # SQL脚本目录
├── start.bat                           # Windows启动脚本（包含编译）
├── start.sh                            # Linux启动脚本（包含编译）
├── start-jar.bat                       # Windows JAR包启动脚本
├── start-jar.sh                        # Linux JAR包启动脚本
├── stop.bat                            # Windows停止脚本
├── stop.sh                             # Linux停止脚本
├── stop-jar.bat                        # Windows JAR包停止脚本
├── stop-jar.sh                         # Linux JAR包停止脚本
└── pom.xml                             # Maven配置
```

## API接口

- `POST /api/deploy/start` - 开始部署
- `GET /api/deploy/check/{port}` - 服务检测
- `POST /api/deploy/script/execute` - 执行初始化脚本
- `POST /api/deploy/config/save` - 保存配置
- `GET /api/deploy/config/load` - 加载配置
- WebSocket: `/ws/deploy-log` - 实时日志推送

## 注意事项

1. 确保有足够的磁盘空间用于解压Tomcat和部署WAR包
2. 确保配置的端口未被占用
3. Tomcat压缩包需要是zip格式（tar.gz格式暂不支持）
4. 数据库驱动已包含在依赖中，但Oracle驱动可能需要本地安装
5. 部署过程中请勿关闭浏览器，以便查看实时日志

## 待实现功能

- [ ] 数据库yml配置文件生成（模板后续提供）
- [ ] 支持tar.gz格式的Tomcat压缩包解压
- [ ] 支持更多中间件类型

## 许可证

内部使用

