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

打开浏览器访问：http://localhost:8022

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

打包完成后，JAR文件位于 `target/deploy-tool-1.0.0.jar`

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

### 5. 与 Tomcat / JDK 相关的行为说明

- **Tomcat JDK 版本要求**
  - 部署工具自身可以运行在 JDK 8+，但 **Tomcat 实例要求运行在 JDK 17 或更高版本**。
  - 在「参数配置」第 1 步点击“下一步”时，系统会按以下顺序自动校验：
    1. **优先检测当前环境 JDK**：调用 `/api/deploy/java/check`，如果当前 `java` 已是 JDK17+，直接通过；
    2. **再检测全局配置中的 JDK 路径**：若环境 JDK 不是 17+，则读取全局设置中的 `Tomcat 专用 JDK17 安装目录`，调用 `/api/deploy/java/check?jdkHome=...` 校验该目录下的 `bin/java` 是否为 17+；
    3. **最后弹窗要求重新配置**：若全局路径不存在或版本不对，会弹窗要求输入 JDK17 安装目录，二次校验通过后会自动写回全局设置，后续部署复用该路径。

- **Tomcat 实际使用的 JDK**
  - 若 **当前环境 JDK 已是 17+**：Tomcat 使用环境中的 `JAVA_HOME`/`java` 启动，不强制写入专用路径；
  - 若 **通过全局配置提供了 JDK17 路径**：部署时会在每个 Tomcat 实例的 `bin/setclasspath.bat` 中写入一行：
    - `set JAVA_HOME=C:\Program Files\Java\jdk-17`（示例路径）
    - Windows 下通过 `startup.bat` 启动时，Tomcat 会优先使用这里指定的 JDK17。

- **Tomcat 启动/停止方式**
  - 启动：
    - Windows：在后台执行 `<安装目录>\tomcat-tyzc\bin\startup.bat` / `<安装目录>\tomcat-gbgl\bin\startup.bat`，**不会弹出新的命令行窗口**；
    - Linux：在后台执行 `bin/startup.sh`（部署前会自动为 `bin/*.sh` 添加执行权限）。
  - 停止：
    - 每次部署前会自动调用 `shutdown.bat` / `shutdown.sh` 关闭已运行的 Tomcat，并在必要时按端口强制结束进程；
    - **关闭部署工具 JAR 并不会自动关闭 Tomcat**，Tomcat 会继续在后台占用端口运行。

- **前端浏览器兼容性**
  - 为兼容 **旧版本浏览器（例如 Firefox 68）**，前端脚本 `deploy.js` 中避免使用可选链（`?.` 等语法），统一改为显式 DOM 判空逻辑，确保部署向导在旧浏览器中也能正常使用。

### 6. JVM参数配置

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

## 全局配置说明（Global Settings）

- **配置入口与存储位置**
  - 配置入口：部署页面右上角/侧边栏的“全局设置”按钮，点击后会弹出全局设置弹窗；
  - 存储位置：后端将全局配置以 JSON 形式持久化在 `generated/settings/global-settings.json` 文件中；
  - 加载流程：页面加载时前端调用 `GET /api/settings/global`，将返回的配置写入内存中的 `deployConfig.globalSettings`，作为各步骤的默认值来源。

- **可配置项一览**
  - **默认中间件类型**（`defaultMiddlewareType`）
    - 作用：当“参数配置”中未明确选择中间件类型时，后端会使用这里配置的默认值（默认 `Tomcat`）。
  - **Tomcat 默认端口**（`tomcat.unifiedPort` / `tomcat.cadrePort` / `tomcat.authPort` / `tomcat.authUrlStripProtocol`）
    - 作用：
      - 为端口配置步骤提供默认端口；
      - 用于构造 `authurl`、服务检测等场景中默认使用的主机端口；
      - `authUrlStripProtocol` 控制写入 YML 的 `${authurl}` 是否去掉 `http(s)://` 前缀。
  - **Tomcat 专用 JDK17 安装目录**（`tomcat.tomcatJdkHome`）
    - 作用：
      - 当当前运行部署工具的 JDK 版本 **低于 17** 时，Tomcat 实例仍要求运行在 JDK17+ 上；
      - 在“参数配置”第 1 步校验阶段，前端会调用 `/api/deploy/java/check?jdkHome=...`，验证此目录下的 `bin/java` 是否为 JDK17+；
      - 校验通过后，该路径会被写回全局配置，并在部署时用于更新各实例 `bin/setclasspath.bat` 中的 `JAVA_HOME`，确保 Tomcat 使用此 JDK17 启动。
  - **数据库默认类型与达梦连接串模板**（`database.defaultType`、`database.dm.connectionPrefix`、`database.dm.connectionSuffix`）
    - 作用：
      - 控制前端拼接数据库连接串的规则，例如默认 `jdbc:dm://{ip}`；
      - 在“测试数据库连接”和替换 YML 模板中的占位符时，会使用这里配置的前缀/后缀；
      - 便于现场根据实际库名、端口等调整连接串模板，而无需改代码。
  - **YML 数据源类型替换**（`yml.datasourceTypeReplacement`）
    - 作用：
      - 控制 YML 模板中 `${type}` 占位符的替换值（默认 `com.alibaba.druid.pool.DruidDataSource`）；
      - 若未来需要更换数据源实现，只需在全局设置中修改该值即可。

- **全局配置与部署流程的关系**
  - **页面加载阶段**：读取全局配置，为参数配置、端口配置、YML 模板等提供默认值；
  - **参数配置阶段**：
    - 使用 `GlobalSettings` 中的端口、数据库类型、JDK17 路径等信息进行校验；
    - 若当前环境 JDK 不满足要求，会结合 `tomcat.tomcatJdkHome` 引导用户修正配置并持久化；
  - **开始部署阶段**：
    - 直接消费已经校验通过的全局配置（包括 Tomcat JDK 路径、端口策略、YML 模板规则等），不会再重复弹出 JDK 相关提示；
    - 使得“全局设置一次，后续多次部署复用”，减少重复输入和环境差异带来的问题。

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

## 常见问题
执行 sh权限不够
```bash
chmod 744 /usr/local/delopy/start-jar.sh
```