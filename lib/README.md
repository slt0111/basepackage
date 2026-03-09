# 达梦数据库驱动说明

## 安装达梦数据库驱动

达梦数据库驱动需要手动安装到项目中。

### 方法1：使用本地jar文件（推荐）

1. 从达梦官网下载 `DmJdbcDriver18.jar` 或对应版本的驱动jar包
2. 将jar包放入项目的 `lib/` 目录下
3. 确保 `pom.xml` 中已配置system scope依赖

### 方法2：安装到本地Maven仓库

```bash
mvn install:install-file -Dfile=DmJdbcDriver18.jar -DgroupId=com.dameng -DartifactId=DmJdbcDriver -Dversion=8.0 -Dpackaging=jar
```

然后取消 `pom.xml` 中注释的Maven依赖，删除system scope的依赖。

### 注意事项

- 达梦数据库驱动版本需要与数据库版本匹配
- 如果使用system scope，需要确保jar文件路径正确
- 生产环境建议使用Maven仓库方式管理依赖

