truncate table "HY_ZGGL_ZZGB"."AA01";


insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('BIRTHDAY_TYPE', '出生年月类型', '2', '1-不带(岁),2-带(岁)', '1', '1', '');
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('DEFAULT_TREE', '默认机构树', 'DEFAULT', 'B01_EXTRACT主键', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('HY_TYPE', '红云', 'HZB', '指定发布版本', '1', '1', '     ');
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('HZB_PATH', '汇总版系统主目录', '${HZB_PATH}', '汇总版系统主目录', '1', '1', 'c:/hzb');
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('HZB_PATH_LINUX', '汇总版系统主目录(linux)', '${HZB_PATH_LINUX}', '汇总版系统主目录(linux)', '1', '1', '/usr/hzb');
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('MEN_TXNL', '男性退休年龄', '60', '男60,女55', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('PARTITION_FRAGMENT', '分区段数', '3', '采用机构id的第几段进行分区', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('PERIOD', '统计系统报告期', '2020', '统计系统报告期', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('QGGWY_CODE', '全国公务员代码维护', 'ON', 'ON,OFF', '0', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('SQLINJECTION', '防sql注入过滤器开关', 'OFF', '防sql注入过滤器开关:ON,OFF', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('TGJSTYPE', '套改晋升默认类别', '1', '套改晋升默认类别', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('WOMEN_TXNL', '女性退休年龄', '55', '男60,女55', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('TREE_TYPE', '机构树类型', '1', '机构树类型', '1', '1', '');
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('YBS_ICON', '一表式图标隐藏，true-隐藏，-false显示', 'false', '一表式图标隐藏，true-隐藏，-false显示', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('GRBQ_VERSION_TYPE', '一表式个人标签模块版本（1.新版本，2.老版本）', '3', '一表式个人标签模块版本（1.新版本，2.老版本）	', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('EXPORT_FILE_TYPE', '任免表导出文件类型', 'PDF', '任免表导出文件类型(PDF ,OFD_WIN,OFD_LINUX)', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('UNIT_JC_OPEN', '单位简称开关', '1', '单位简称开关(1：开 0：关)', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('HELP_CONTROUNT', '帮助按钮开关', '1', '帮助按钮开关(1：开 显示 0：关 隐藏)', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('TIPS_CONTROUNT', '提示语开关', '1', '提示语开关(1：开 显示 0：关 隐藏)', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('MaxA40001', '名册模型当前已使用编码最大值', '205', '数字递增', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('RPCSERVERURL', '远程rpc服务调用地址', '${RPCSERVERURL}', 'hessian远程rpc服务调用地址', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('IS_RPC_BUSSINESS', '业务远程调用开关', '1', '业务远程调用开关', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('JDK_PATH', 'JDK_PATH目录', 'D:\Java\jdk-17.0.17\bin\', 'JDK_PATH目录', '1', '1', null);
insert into "HY_ZGGL_ZZGB"."AA01" ("AAA001","AAA002","AAA005","AAA105","AAA104","ACTIVE","OLDPARAM") values ('ROOT_B0111', '机构树根节点', '001.001', '机构树根节点', '1', '1', null);
