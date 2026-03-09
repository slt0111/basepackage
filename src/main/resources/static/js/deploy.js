// WebSocket连接
let ws = null;
let currentStep = 1;
// 部署向导总步骤：基础配置 + 数据库配置已合并为「参数配置」
const totalSteps = 5;

// 配置数据存储
const deployConfig = {
    installDir: '',
    middlewareType: 'Tomcat',
    tongWebDeployDir: '',
    databases: {
        unified: {},
        cadre: {}
    },
    wars: {
        unified: { builtinNames: [] }, // 存储所有WAR包名称
        cadre: { builtinNames: [] }
    },
    ymlConfigs: {
        unified: '',
        cadre: ''
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    initWebSocket();
    initEventListeners();
    loadBuiltinWars();
    loadConfigList();
    showStep(1);
    // 支持从首页通过 URL 哈希（例如 #data-init）直达指定步骤，提升入口体验
    handleDeepLinkFromLocation();
    // 安装目录选择：绑定目录选择器（浏览器限制无法获取绝对路径，仅辅助填写）
    initInstallDirPicker();
});

// 从 URL 中解析哈希值，并将用户跳转到对应的部署向导步骤（当前支持数据初始化等入口）
function handleDeepLinkFromLocation() {
    const hash = (window.location.hash || '').toLowerCase();
    if (!hash) {
        return;
    }
    // 数据初始化步骤由 6 调整为 5
    if (hash === '#data-init' || hash === '#datainit' || hash === '#step5' || hash === '#step6') {
        showStep(5);
    }
}

// 安装目录选择：尽可能提供“选择目录”的交互（浏览器安全限制无法直接获取绝对路径）
function initInstallDirPicker() {
    const picker = document.getElementById('installDirPicker');
    if (!picker) return;
    picker.addEventListener('change', function () {
        const files = picker.files || [];
        if (!files.length) return;
        // webkitRelativePath 形如：folderName/sub/path/file.ext
        const rel = files[0].webkitRelativePath || '';
        const top = rel.split('/')[0] || '';
        if (top) {
            const input = document.getElementById('installDir');
            if (input && !input.value) {
                input.value = top;
                if (typeof Message !== 'undefined') {
                    Message.warning('已选择目录：' + top + '（浏览器限制无法获取绝对路径，请确认/补全）');
                }
            }
        }
        // 清空，便于重复选择同一目录也能触发 change
        picker.value = '';
    });
}


// 通用密码可见性切换：点击按钮在明文/密文之间切换数据库密码输入框
function togglePasswordVisibility(inputId, btn) {
    const input = document.getElementById(inputId);
    if (!input) return;
    const isPassword = input.type === 'password';
    input.type = isPassword ? 'text' : 'password';
    // 使用圆形图标按钮 + 两套图片（密文/明文），参考提供的 ciphertext / Plaintext 图标
    if (btn) {
        if (isPassword) {
            // 明文状态：增加 is-plain 类，CSS 中切换为明文图标
            btn.classList.add('is-plain');
        } else {
            // 密文状态：移除 is-plain 类，CSS 回退为密文图标
            btn.classList.remove('is-plain');
        }
    }
}


// 安装目录检查：调用后端接口验证当前配置的安装目录是否真实存在
function checkInstallDir() {
    const input = document.getElementById('installDir');
    const installDir = (input && input.value ? input.value : '').trim();

    if (!installDir) {
        if (typeof Message !== 'undefined') {
            Message.alert('请先输入安装目录');
        }
        return;
    }

    fetch(`/api/deploy/installDir/check?path=${encodeURIComponent(installDir)}`)
        .then(response => response.json())
        .then(data => {
            if (!data || !data.success) {
                if (typeof Message !== 'undefined') {
                    Message.error('目录检查失败: ' + (data && data.message ? data.message : '未知错误'));
                }
                return;
            }
            if (data.exists) {
                if (typeof Message !== 'undefined') {
                    Message.success(data.message || '安装目录存在，可用');
                }
            } else {
                if (typeof Message !== 'undefined') {
                    Message.warning(data.message || '安装目录不存在，请检查路径是否正确');
                }
            }
        })
        .catch(error => {
            if (typeof Message !== 'undefined') {
                Message.error('目录检查请求失败: ' + error.message);
            }
        });
}


// 初始化WebSocket连接
function initWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/deploy-log`;
    
    ws = new WebSocket(wsUrl);
    
    ws.onopen = function() {
        addLog('WebSocket连接已建立', 'success');
    };
    
    ws.onmessage = function(event) {
        addLog(event.data);
    };
    
    ws.onerror = function(error) {
        addLog('WebSocket连接错误: ' + error, 'error');
    };
    
    ws.onclose = function() {
        addLog('WebSocket连接已关闭', 'warning');
        setTimeout(initWebSocket, 5000);
    };
}

// 初始化事件监听器
function initEventListeners() {
    // 中间件类型改变
    document.getElementById('middlewareType').addEventListener('change', function() {
        deployConfig.middlewareType = this.value;
    });

    // WAR包管理不需要选择功能，只展示列表

    // 步骤指示器点击
    document.querySelectorAll('.step-item').forEach(item => {
        item.addEventListener('click', function() {
            const step = parseInt(this.dataset.step);
            if (step <= currentStep || isStepCompleted(step - 1)) {
                showStep(step);
            }
        });
    });
}

// 加载内置WAR包列表
// 加载内置WAR包列表（仅展示，不选择）
function loadBuiltinWars() {
    fetch('/api/war/list')
        .then(response => response.json())
        .then(data => {
            if (data.success && data.wars) {
                // 展示统一支撑WAR包列表
                const unifiedList = document.getElementById('war-list-unified');
                if (unifiedList) {
                    unifiedList.innerHTML = '';
                    if (data.wars.unifiedList && data.wars.unifiedList.length > 0) {
                        // 保存所有WAR包名称
                        deployConfig.wars.unified.builtinNames = data.wars.unifiedList;
                        // 展示列表
                        data.wars.unifiedList.forEach(warName => {
                            const li = document.createElement('li');
                            li.textContent = warName;
                            unifiedList.appendChild(li);
                        });
                    } else if (data.wars.unified) {
                        // 兼容旧格式（只有一个WAR包）
                        deployConfig.wars.unified.builtinNames = [data.wars.unified];
                        const li = document.createElement('li');
                        li.textContent = data.wars.unified;
                        unifiedList.appendChild(li);
                    } else {
                        const li = document.createElement('li');
                        li.textContent = '未找到WAR包';
                        li.style.color = '#999';
                        unifiedList.appendChild(li);
                    }
                }
                
                // 展示干部应用WAR包列表
                const cadreList = document.getElementById('war-list-cadre');
                if (cadreList) {
                    cadreList.innerHTML = '';
                    if (data.wars.cadreList && data.wars.cadreList.length > 0) {
                        // 保存所有WAR包名称
                        deployConfig.wars.cadre.builtinNames = data.wars.cadreList;
                        // 展示列表
                        data.wars.cadreList.forEach(warName => {
                            const li = document.createElement('li');
                            li.textContent = warName;
                            cadreList.appendChild(li);
                        });
                    } else if (data.wars.cadre) {
                        // 兼容旧格式（只有一个WAR包）
                        deployConfig.wars.cadre.builtinNames = [data.wars.cadre];
                        const li = document.createElement('li');
                        li.textContent = data.wars.cadre;
                        cadreList.appendChild(li);
                    } else {
                        const li = document.createElement('li');
                        li.textContent = '未找到WAR包';
                        li.style.color = '#999';
                        cadreList.appendChild(li);
                    }
                }
            } else {
                console.warn('获取WAR包列表失败:', data.message || '未知错误');
                const unifiedList = document.getElementById('war-list-unified');
                const cadreList = document.getElementById('war-list-cadre');
                if (unifiedList) {
                    unifiedList.innerHTML = '<li style="color: #e74c3c;">加载失败</li>';
                }
                if (cadreList) {
                    cadreList.innerHTML = '<li style="color: #e74c3c;">加载失败</li>';
                }
            }
        })
        .catch(error => {
            console.error('加载内置WAR包失败:', error);
            const unifiedList = document.getElementById('war-list-unified');
            const cadreList = document.getElementById('war-list-cadre');
            if (unifiedList) {
                unifiedList.innerHTML = '<li style="color: #e74c3c;">加载失败</li>';
            }
            if (cadreList) {
                cadreList.innerHTML = '<li style="color: #e74c3c;">加载失败</li>';
            }
        });
}

// 显示指定步骤
function showStep(step) {
    // 隐藏所有步骤
    document.querySelectorAll('.wizard-step').forEach(s => {
        s.style.display = 'none';
    });
    
    // 显示当前步骤
    document.getElementById(`step${step}`).style.display = 'block';
    
    // 更新步骤指示器
    document.querySelectorAll('.step-item').forEach((item, index) => {
        const stepNum = index + 1;
        item.classList.remove('active', 'completed');
        if (stepNum === step) {
            item.classList.add('active');
        } else if (stepNum < step) {
            item.classList.add('completed');
        }
    });
    
    currentStep = step;
    
    // 更新右侧底部固定操作栏：上一步/下一步常驻显示，通过 disabled 控制可用性
    const btnPrev = document.getElementById('deployBtnPrev');
    const btnNext = document.getElementById('deployBtnNext');
    if (btnPrev) {
        btnPrev.style.display = '';
        btnPrev.disabled = step <= 1;
    }
    if (btnNext) {
        btnNext.style.display = '';
        btnNext.disabled = step >= 5;
    }
    
    // 特殊处理：步骤3自动加载YML模板（参数配置已在步骤1保存）
    if (step === 3) {
        loadYmlTemplate();
    }

    // 特殊处理：步骤4显示配置摘要
    if (step === 4) {
        updateConfigSummary();
    }
}

// 检查步骤是否完成
function isStepCompleted(step) {
    switch(step) {
        case 1:
            return !!(deployConfig.installDir &&
                deployConfig.databases.unified &&
                deployConfig.databases.cadre &&
                deployConfig.databases.unified.ip &&
                deployConfig.databases.unified.username &&
                deployConfig.databases.unified.password &&
                deployConfig.databases.cadre.ip &&
                deployConfig.databases.cadre.username &&
                deployConfig.databases.cadre.password);
        case 2:
            // WAR包管理只是展示，不需要验证选择
            return true;
        case 3:
            return deployConfig.ymlConfigs.unified && deployConfig.ymlConfigs.cadre;
        default:
            return true;
    }
}

// 下一步
function nextStep() {
    // 验证当前步骤
    if (!validateCurrentStep()) {
        return;
    }
    
    // 保存当前步骤数据
    saveCurrentStepData();
    
    if (currentStep < totalSteps) {
        showStep(currentStep + 1);
    }
}

// 上一步
function prevStep() {
    if (currentStep > 1) {
        saveCurrentStepData();
        showStep(currentStep - 1);
    }
}

// 验证当前步骤
function validateCurrentStep() {
    switch(currentStep) {
        case 1:
            const installDir = document.getElementById('installDir').value;
            if (!installDir) {
                Message.alert('请输入部署目录');
                return false;
            }
            // 数据库配置：IP 统一配置在 db-ip-common，下方分别配置两个库的用户名/密码
            const commonIp = document.getElementById('db-ip-common').value;
            const unifiedUser = document.getElementById('db-user-unified').value;
            const unifiedPwd = document.getElementById('db-pwd-unified').value;
            const cadreUser = document.getElementById('db-user-cadre').value;
            const cadrePwd = document.getElementById('db-pwd-cadre').value;
            if (!commonIp || !unifiedUser || !unifiedPwd || !cadreUser || !cadrePwd) {
                Message.alert('请填写完整的数据库配置信息（统一IP + 各库用户名/密码）');
                return false;
            }
            return true;
        case 2:
            // WAR包管理只是展示，不需要验证
            return true;
        default:
            return true;
    }
}

// 保存当前步骤数据
function saveCurrentStepData() {
    switch(currentStep) {
        case 1:
            deployConfig.installDir = document.getElementById('installDir').value;
            deployConfig.middlewareType = document.getElementById('middlewareType').value;
            // TongWeb 也统一使用部署目录，无需单独的部署目录输入项
            deployConfig.tongWebDeployDir = deployConfig.installDir;
            // 数据库配置：仅保存统一IP + 各库用户名 / 密码；同时派生 connectionString 供后端接口复用
            // 注：目前默认使用达梦连接串模板（后续如需支持多数据库类型，可扩展为可选项）
            const commonIp = (document.getElementById('db-ip-common').value || '').trim();
            const unifiedUser = (document.getElementById('db-user-unified').value || '').trim();
            const unifiedPwd = (document.getElementById('db-pwd-unified').value || '').trim();
            const cadreUser = (document.getElementById('db-user-cadre').value || '').trim();
            const cadrePwd = (document.getElementById('db-pwd-cadre').value || '').trim();
            deployConfig.databases.unified = {
                type: '达梦',
                ip: commonIp,
                username: unifiedUser,
                password: unifiedPwd,
                connectionString: commonIp ? `jdbc:dm://${commonIp}:5236/DAMENG` : ''
            };
            deployConfig.databases.cadre = {
                type: '达梦',
                ip: commonIp,
                username: cadreUser,
                password: cadrePwd,
                connectionString: commonIp ? `jdbc:dm://${commonIp}:5236/DAMENG` : ''
            };
            break;
        case 2:
            // WAR包管理只是展示，不需要保存选择（已在loadBuiltinWars中保存默认值）
            break;
        case 3:
            const currentApp = document.getElementById('ymlAppSelect').value;
            deployConfig.ymlConfigs[currentApp] = document.getElementById('ymlContent').value;
            break;
    }
}

// 测试数据库连接
function testDatabase(app) {
    // 数据库测试：统一使用外层配置的数据库IP，仅输入各库用户名 / 密码；内部派生连接串与类型（默认达梦）
    const type = '达梦';
    const ip = (document.getElementById('db-ip-common')?.value || '').trim();
    const username = document.getElementById(`db-user-${app}`)?.value || '';
    const password = document.getElementById(`db-pwd-${app}`)?.value || '';
    const connectionString = ip ? `jdbc:dm://${ip}:5236/DAMENG` : '';

    if (!ip || !username || !password) {
        Message.alert('请填写完整的数据库信息（IP/用户名/密码）');
        return;
    }

    const resultSpan = document.getElementById(`test-result-${app}`);
    if (resultSpan) {
        resultSpan.textContent = '测试中...';
        resultSpan.className = 'test-result';
    }

    fetch('/api/database/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            type: type,
            connectionString: connectionString,
            username: username,
            password: password
        })
    })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (resultSpan) {
                    resultSpan.textContent = '✓ 连接成功';
                    resultSpan.className = 'test-result success';
                }
                // 连接成功后，更新配置并自动替换YML中的占位符
                deployConfig.databases[app] = {
                    type: type,
                    ip: ip,
                    connectionString: connectionString,
                    username: username,
                    password: password
                };
                // 如果当前在YML配置页面（步骤3），自动更新
                if (currentStep === 3) {
                    const ymlApp = document.getElementById('ymlAppSelect')?.value;
                    if (ymlApp === app && document.getElementById('ymlContent')?.value) {
                        let content = document.getElementById('ymlContent').value;
                        content = replaceDatabasePlaceholders(content, app);
                        document.getElementById('ymlContent').value = content;
                        deployConfig.ymlConfigs[app] = content;
                        Message.success('已同步数据库配置到YML内容');
                    }
                }
            } else {
                if (resultSpan) {
                    resultSpan.textContent = '✗ 连接失败: ' + (data.message || '');
                    resultSpan.className = 'test-result error';
                }
            }
        })
        .catch(error => {
            if (resultSpan) {
                resultSpan.textContent = '✗ 测试失败: ' + error.message;
                resultSpan.className = 'test-result error';
            }
        });
}

// 根据数据库类型获取驱动类名
function getDriverClassName(type) {
    if (!type) return '';
    
    const typeUpper = type.toUpperCase();
    switch (typeUpper) {
        case 'MYSQL':
            return 'com.mysql.cj.jdbc.Driver';
        case 'ORACLE':
            return 'oracle.jdbc.driver.OracleDriver';
        case 'POSTGRESQL':
            return 'org.postgresql.Driver';
        case '达梦':
        case 'DM':
            return 'dm.jdbc.driver.DmDriver';
        default:
            return '';
    }
}

// 获取数据库配置（优先从表单读取，其次从deployConfig读取）
function getDatabaseConfig(app) {
    console.log('[getDatabaseConfig] 开始获取数据库配置，app:', app);
    
    // 先尝试从表单读取（最实时）
    // 参数配置已简化：IP 统一从 db-ip-common 读取，各库只配置用户名 / 密码
    const ipEl = document.getElementById('db-ip-common');
    const userEl = document.getElementById(`db-user-${app}`);
    const pwdEl = document.getElementById(`db-pwd-${app}`);
    
    console.log('[getDatabaseConfig] 表单元素:', {
        ipEl: ipEl ? '存在' : '不存在',
        userEl: userEl ? '存在' : '不存在',
        pwdEl: pwdEl ? '存在' : '不存在'
    });
    
    if (ipEl && userEl && pwdEl) {
        const type = '达梦';
        const ip = (ipEl.value || '').trim();
        const connectionString = ip ? `jdbc:dm://${ip}:5236/DAMENG` : '';
        const username = userEl.value;
        const password = pwdEl.value;
        
        console.log('[getDatabaseConfig] 表单值:', {
            type: type,
            connectionString: connectionString ? connectionString.substring(0, 50) + '...' : '空',
            username: username,
            password: password ? '***' : '空'
        });
        
        if (ip && connectionString && username) {
            const config = {
                type: type,
                ip: ip,
                connectionString: connectionString,
                username: username,
                password: password
            };
            console.log('[getDatabaseConfig] 从表单获取到配置:', config);
            return config;
        } else {
            console.log('[getDatabaseConfig] 表单数据不完整，缺少:', {
                ip: !ip,
                username: !username
            });
        }
    } else {
        console.log('[getDatabaseConfig] 表单元素不存在，尝试从deployConfig读取');
    }
    
    // 如果表单没有数据，从deployConfig读取
    const configFromDeploy = deployConfig.databases[app];
    console.log('[getDatabaseConfig] deployConfig中的配置:', configFromDeploy);
    return configFromDeploy;
}

// 替换YML模板中的数据库占位符
function replaceDatabasePlaceholders(ymlContent, app) {
    console.log('[replaceDatabasePlaceholders] 开始替换占位符，app:', app);
    console.log('[replaceDatabasePlaceholders] YML内容长度:', ymlContent ? ymlContent.length : 0);
    console.log('[replaceDatabasePlaceholders] YML内容预览:', ymlContent ? ymlContent.substring(0, 200) : '空');
    
    // 强制从表单重新读取最新配置
    const dbConfig = getDatabaseConfig(app);
    console.log('[replaceDatabasePlaceholders] 获取到的数据库配置:', dbConfig);
    
    if (!dbConfig || !dbConfig.type || !dbConfig.connectionString) {
        console.warn('[replaceDatabasePlaceholders] 数据库配置不完整，无法替换:', {
            hasConfig: !!dbConfig,
            hasType: dbConfig ? !!dbConfig.type : false,
            hasConnectionString: dbConfig ? !!dbConfig.connectionString : false
        });
        return ymlContent; // 如果没有数据库配置，返回原内容
    }
    
    // 替换值（不包含单引号）
    const replacements = {
        '${type}': 'com.alibaba.druid.pool.DruidDataSource', // 数据源类型固定为Druid
        '${url}': dbConfig.connectionString || '',
        '${username}': dbConfig.username || '',
        '${password}': dbConfig.password || '',
        '${driver-class-name}': getDriverClassName(dbConfig.type)
    };
    
    console.log('[replaceDatabasePlaceholders] 替换映射:', {
        '${type}': replacements['${type}'],
        '${url}': replacements['${url}'].substring(0, 50) + '...',
        '${username}': replacements['${username}'],
        '${password}': replacements['${password}'] ? '***' : '空',
        '${driver-class-name}': replacements['${driver-class-name}']
    });
    
    let result = ymlContent;
    let replacedCount = 0;
    
    // 先检查YML内容中实际包含的占位符格式
    console.log('[replaceDatabasePlaceholders] 检查YML内容中的占位符格式...');
    for (const placeholder of Object.keys(replacements)) {
        const singleQuote = `'${placeholder}'`;
        const doubleQuote = `"${placeholder}"`;
        const noQuote = placeholder;
        
        const hasSingle = result.includes(singleQuote);
        const hasDouble = result.includes(doubleQuote);
        const hasNoQuote = result.includes(noQuote);
        
        console.log(`[replaceDatabasePlaceholders] ${placeholder} 格式检查:`, {
            '单引号': hasSingle,
            '双引号': hasDouble,
            '无引号': hasNoQuote,
            '单引号示例': hasSingle ? result.substring(result.indexOf(singleQuote) - 10, result.indexOf(singleQuote) + singleQuote.length + 10) : '无',
            '双引号示例': hasDouble ? result.substring(result.indexOf(doubleQuote) - 10, result.indexOf(doubleQuote) + doubleQuote.length + 10) : '无',
            '无引号示例': hasNoQuote ? result.substring(result.indexOf(noQuote) - 10, result.indexOf(noQuote) + noQuote.length + 10) : '无'
        });
    }
    
    // 替换占位符，支持带单引号和不带单引号的格式
    // 替换后值不带引号（如 '${type}' -> com.alibaba.druid.pool.DruidDataSource）
    for (const [placeholder, value] of Object.entries(replacements)) {
        // 转义占位符中的特殊字符用于正则表达式
        // 注意：$ 和 { } 需要特殊处理
        const escapedPlaceholder = placeholder.replace(/[.*+?^${}()|[\]\\]/g, (match) => {
            if (match === '$') return '\\$';
            if (match === '{') return '\\{';
            if (match === '}') return '\\}';
            return '\\' + match;
        });
        
        console.log(`[replaceDatabasePlaceholders] 处理占位符: ${placeholder}, 转义后: ${escapedPlaceholder}`);
        
        // 匹配带单引号的格式：'${placeholder}'（替换时去掉单引号）
        const singleQuotePattern = new RegExp(`'${escapedPlaceholder}'`, 'g');
        const beforeSingle = result;
        result = result.replace(singleQuotePattern, value);
        if (beforeSingle !== result) {
            const matches = beforeSingle.match(singleQuotePattern) || [];
            replacedCount += matches.length;
            console.log(`[replaceDatabasePlaceholders] ✅ 替换单引号格式 '${placeholder}': ${matches.length} 处 -> ${value}`);
        } else {
            console.log(`[replaceDatabasePlaceholders] ❌ 单引号格式 '${placeholder}' 未找到匹配`);
        }
        
        // 匹配带双引号的格式："${placeholder}"（替换时去掉双引号）
        const doubleQuotePattern = new RegExp(`"${escapedPlaceholder}"`, 'g');
        const beforeDouble = result;
        result = result.replace(doubleQuotePattern, value);
        if (beforeDouble !== result) {
            const matches = beforeDouble.match(doubleQuotePattern) || [];
            replacedCount += matches.length;
            console.log(`[replaceDatabasePlaceholders] ✅ 替换双引号格式 "${placeholder}": ${matches.length} 处 -> ${value}`);
        } else {
            console.log(`[replaceDatabasePlaceholders] ❌ 双引号格式 "${placeholder}" 未找到匹配`);
        }
        
        // 匹配不带引号的格式：${placeholder}
        const noQuotePattern = new RegExp(escapedPlaceholder, 'g');
        const beforeNoQuote = result;
        result = result.replace(noQuotePattern, value);
        if (beforeNoQuote !== result) {
            const matches = beforeNoQuote.match(noQuotePattern) || [];
            replacedCount += matches.length;
            console.log(`[replaceDatabasePlaceholders] ✅ 替换无引号格式 ${placeholder}: ${matches.length} 处 -> ${value}`);
        } else {
            console.log(`[replaceDatabasePlaceholders] ❌ 无引号格式 ${placeholder} 未找到匹配`);
        }
    }
    
    console.log('[replaceDatabasePlaceholders] 总共替换了', replacedCount, '处占位符');
    console.log('[replaceDatabasePlaceholders] 替换后的内容预览:', result.substring(0, 300));
    
    return result;
}

// 加载YML模板
function loadYmlTemplate() {
    console.log('[loadYmlTemplate] ========== 开始加载YML模板 ==========');
    const app = document.getElementById('ymlAppSelect').value;
    const appName = app === 'unified' ? '统一支撑' : '干部应用';
    console.log('[loadYmlTemplate] 应用:', app, appName);
    document.getElementById('ymlAppTitle').textContent = appName + 'YML配置';
    
    // 确保数据库配置已保存（从表单读取并保存到deployConfig）
    console.log('[loadYmlTemplate] 当前deployConfig.databases:', deployConfig.databases);
    const dbConfig = getDatabaseConfig(app);
    console.log('[loadYmlTemplate] 获取到的数据库配置:', dbConfig);
    
    if (dbConfig && dbConfig.type && dbConfig.connectionString) {
        deployConfig.databases[app] = dbConfig;
        console.log('[loadYmlTemplate] 已更新数据库配置到deployConfig:', app, {
            type: dbConfig.type,
            connectionString: dbConfig.connectionString.substring(0, 50) + '...',
            username: dbConfig.username
        });
    } else {
        console.warn('[loadYmlTemplate] 数据库配置不完整，无法更新:', dbConfig);
    }
    
    // 如果已有配置，显示配置；否则加载模板
    if (deployConfig.ymlConfigs[app]) {
        console.log('[loadYmlTemplate] 使用已保存的YML配置');
        let content = deployConfig.ymlConfigs[app];
        console.log('[loadYmlTemplate] 原始内容长度:', content.length);
        console.log('[loadYmlTemplate] 原始内容包含占位符:', {
            hasType: content.includes('${type}'),
            hasUrl: content.includes('${url}'),
            hasUsername: content.includes('${username}'),
            hasPassword: content.includes('${password}'),
            hasDriver: content.includes('${driver-class-name}')
        });
        
        // 如果已有配置，也尝试替换占位符（如果数据库配置已更新）
        // 先检查是否还有占位符需要替换
        const hasPlaceholders = content.includes('${type}') || content.includes('${url}') || 
                                content.includes('${username}') || content.includes('${password}') || 
                                content.includes('${driver-class-name}');
        console.log('[loadYmlTemplate] 是否还有占位符需要替换:', hasPlaceholders);
        
        if (hasPlaceholders) {
            console.log('[loadYmlTemplate] 检测到占位符，执行替换');
            content = replaceDatabasePlaceholders(content, app);
        } else {
            console.log('[loadYmlTemplate] 没有占位符，但强制重新替换以确保使用最新配置');
            // 即使没有占位符，也重新替换一次以确保使用最新配置
            content = replaceDatabasePlaceholders(content, app);
        }
        
        document.getElementById('ymlContent').value = content;
        deployConfig.ymlConfigs[app] = content;
        console.log('[loadYmlTemplate] 替换后内容已设置到textarea');
        console.log('[loadYmlTemplate] textarea当前值预览:', document.getElementById('ymlContent').value.substring(0, 300));
    } else {
        console.log('[loadYmlTemplate] 从服务器加载YML模板');
        fetch(`/api/yml/template/${app}`)
            .then(response => response.json())
            .then(data => {
                console.log('[loadYmlTemplate] 服务器响应:', data);
                if (data.success && data.content) {
                    let content = data.content;
                    console.log('[loadYmlTemplate] 模板内容长度:', content.length);
                    console.log('[loadYmlTemplate] 模板内容包含占位符:', {
                        hasType: content.includes('${type}'),
                        hasUrl: content.includes('${url}'),
                        hasUsername: content.includes('${username}'),
                        hasPassword: content.includes('${password}'),
                        hasDriver: content.includes('${driver-class-name}')
                    });
                    
                    // 自动替换数据库占位符
                    content = replaceDatabasePlaceholders(content, app);
                    document.getElementById('ymlContent').value = content;
                    deployConfig.ymlConfigs[app] = content;
                    console.log('[loadYmlTemplate] 模板加载并替换完成');
                } else {
                    console.warn('[loadYmlTemplate] 服务器返回失败或无内容');
                    let defaultContent = '# YML配置文件\n# 请在此编辑配置内容';
                    // 即使没有模板，也尝试替换占位符
                    defaultContent = replaceDatabasePlaceholders(defaultContent, app);
                    document.getElementById('ymlContent').value = defaultContent;
                }
            })
            .catch(error => {
                console.error('[loadYmlTemplate] 加载YML模板失败:', error);
                let defaultContent = '# YML配置文件\n# 请在此编辑配置内容';
                defaultContent = replaceDatabasePlaceholders(defaultContent, app);
                document.getElementById('ymlContent').value = defaultContent;
            });
    }
    console.log('[loadYmlTemplate] ========== 加载YML模板完成 ==========');
}

// 重置YML模板
function resetYmlTemplate() {
    const app = document.getElementById('ymlAppSelect').value;
    deployConfig.ymlConfigs[app] = '';
    loadYmlTemplate();
}

// 保存YML配置
function saveYmlConfig() {
    const app = document.getElementById('ymlAppSelect').value;
    const content = document.getElementById('ymlContent').value;
    deployConfig.ymlConfigs[app] = content;
    
    fetch('/api/yml/save', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            app: app,
            content: content
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            Message.success('YML配置保存成功');
        } else {
            Message.error('YML配置保存失败: ' + data.message);
        }
    })
    .catch(error => {
        Message.error('保存失败: ' + error.message);
    });
}

// 更新配置摘要
function updateConfigSummary() {
    const summaryDiv = document.getElementById('configSummary');
    let html = '';
    
    html += '<div class="config-summary-item">';
    html += '<h4>参数配置</h4>';
    html += `<p><strong>部署目录：</strong>${deployConfig.installDir}</p>`;
    html += `<p><strong>中间件类型：</strong>${deployConfig.middlewareType}</p>`;
    html += '</div>';
    
    html += '<div class="config-summary-item">';
    html += '<h4>数据库配置</h4>';
    html += '<p><strong>统一支撑：</strong></p>';
    html += `<p>IP：${deployConfig.databases.unified.ip || '未配置'}</p>`;
    html += `<p>用户名：${deployConfig.databases.unified.username || '未配置'}</p>`;
    html += '<p><strong>干部应用：</strong></p>';
    html += `<p>IP：${deployConfig.databases.cadre.ip || '未配置'}</p>`;
    html += `<p>用户名：${deployConfig.databases.cadre.username || '未配置'}</p>`;
    html += '</div>';
    
    html += '<div class="config-summary-item">';
    html += '<h4>WAR包配置</h4>';
    const unifiedWars = deployConfig.wars.unified.builtinNames && deployConfig.wars.unified.builtinNames.length > 0 
        ? deployConfig.wars.unified.builtinNames.join(', ') 
        : '未找到WAR包';
    const cadreWars = deployConfig.wars.cadre.builtinNames && deployConfig.wars.cadre.builtinNames.length > 0 
        ? deployConfig.wars.cadre.builtinNames.join(', ') 
        : '未找到WAR包';
    html += `<p><strong>统一支撑：</strong>${unifiedWars}</p>`;
    html += `<p><strong>干部应用：</strong>${cadreWars}</p>`;
    html += '</div>';
    
    summaryDiv.innerHTML = html;
}

// 开始部署
function startDeploy() {
    saveCurrentStepData();
    
    // 构建部署配置
    const config = {
        installDir: deployConfig.installDir,
        middlewareType: deployConfig.middlewareType,
        // TongWeb 部署目录统一使用部署目录，保持后端字段兼容
        tongWebDeployDir: deployConfig.installDir,
        // 端口/操作系统配置已按要求移除，如需恢复可在此扩展
        databases: [
            { name: '统一支撑', ...deployConfig.databases.unified },
            { name: '干部应用', ...deployConfig.databases.cadre }
        ],
        warFiles: [],
        useBuiltInWars: true,
        ymlConfigs: deployConfig.ymlConfigs
    };
    
    // 处理WAR包配置（部署目录下所有的WAR包）
    if (deployConfig.wars.unified.builtinNames && deployConfig.wars.unified.builtinNames.length > 0) {
        deployConfig.wars.unified.builtinNames.forEach(warName => {
            config.warFiles.push('tyzc/' + warName);
        });
    }
    if (deployConfig.wars.cadre.builtinNames && deployConfig.wars.cadre.builtinNames.length > 0) {
        deployConfig.wars.cadre.builtinNames.forEach(warName => {
            config.warFiles.push('gbgl/' + warName);
        });
    }
    
    addLog('正在启动部署...', 'info');
    
    fetch('/api/deploy/start', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(config)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            addLog('部署任务已启动', 'success');
        } else {
            addLog('启动部署失败: ' + data.message, 'error');
        }
    })
    .catch(error => {
        addLog('请求失败: ' + error.message, 'error');
    });
}

// 加载配置列表
function loadConfigList() {
    fetch('/api/deploy/config/list')
        .then(response => response.json())
        .then(data => {
            const configSelect = document.getElementById('configSelect');
            if (configSelect && data.success && data.configs) {
                configSelect.innerHTML = '<option value="">请选择配置</option>';
                data.configs.forEach(configName => {
                    const option = document.createElement('option');
                    option.value = configName;
                    option.textContent = configName;
                    configSelect.appendChild(option);
                });
            }
        })
        .catch(error => {
            console.error('加载配置列表失败:', error);
        });
}

// 加载配置
function loadConfig() {
    const configSelect = document.getElementById('configSelect');
    const configName = configSelect ? configSelect.value : '';
    
    if (!configName) {
        Message.alert('请先选择要加载的配置');
        return;
    }
    
    fetch('/api/deploy/config/load/' + encodeURIComponent(configName))
        .then(response => response.json())
        .then(data => {
            if (data.success && data.config) {
                const config = data.config;
                
                // 加载基础配置
                if (config.installDir) {
                    document.getElementById('installDir').value = config.installDir;
                    deployConfig.installDir = config.installDir;
                }
                if (config.middlewareType) {
                    document.getElementById('middlewareType').value = config.middlewareType;
                    deployConfig.middlewareType = config.middlewareType;
                }
                
                // 加载数据库配置（兼容旧配置：connectionString -> 解析 IP）
                if (config.databases && config.databases.length > 0) {
                    const parseIpFromConn = (conn) => {
                        if (!conn) return '';
                        const m = String(conn).match(/\/\/([^:\/]+)(?::\d+)?\//);
                        return m && m[1] ? m[1] : '';
                    };
                    // 统一抽取数据库IP：默认以统一支撑库的IP为准（假定两库IP相同）
                    let commonIp = '';
                    config.databases.forEach(db => {
                        if (db.name === '统一支撑') {
                            const ip = db.ip || parseIpFromConn(db.connectionString);
                            if (ip) {
                                commonIp = ip;
                                deployConfig.databases.unified.ip = ip;
                                deployConfig.databases.unified.connectionString = `jdbc:dm://${ip}:5236/DAMENG`;
                                deployConfig.databases.unified.type = '达梦';
                            }
                            if (db.username) {
                                document.getElementById('db-user-unified').value = db.username;
                                deployConfig.databases.unified.username = db.username;
                            }
                            if (db.password) {
                                document.getElementById('db-pwd-unified').value = db.password;
                                deployConfig.databases.unified.password = db.password;
                            }
                        } else if (db.name === '干部应用') {
                            const ip = db.ip || parseIpFromConn(db.connectionString);
                            if (ip) {
                                // 如果尚未确定统一IP，则以干部库IP作为统一IP（兼容旧数据）
                                if (!commonIp) {
                                    commonIp = ip;
                                }
                                deployConfig.databases.cadre.ip = ip;
                                deployConfig.databases.cadre.connectionString = `jdbc:dm://${ip}:5236/DAMENG`;
                                deployConfig.databases.cadre.type = '达梦';
                            }
                            if (db.username) {
                                document.getElementById('db-user-cadre').value = db.username;
                                deployConfig.databases.cadre.username = db.username;
                            }
                            if (db.password) {
                                document.getElementById('db-pwd-cadre').value = db.password;
                                deployConfig.databases.cadre.password = db.password;
                            }
                        }
                    });
                    // 将统一IP回填到外层IP输入框，便于后续修改
                    if (commonIp) {
                        const commonIpInput = document.getElementById('db-ip-common');
                        if (commonIpInput) {
                            commonIpInput.value = commonIp;
                        }
                    }
                }
                
                // 加载WAR包配置
                if (config.warFiles && config.warFiles.length > 0) {
                    const unifiedWars = [];
                    const cadreWars = [];
                    config.warFiles.forEach(warFile => {
                        if (warFile.startsWith('tyzc/')) {
                            unifiedWars.push(warFile.substring(5)); // 去掉 "tyzc/" 前缀
                        } else if (warFile.startsWith('gbgl/')) {
                            cadreWars.push(warFile.substring(5)); // 去掉 "gbgl/" 前缀
                        }
                    });
                    if (unifiedWars.length > 0) {
                        deployConfig.wars.unified.builtinNames = unifiedWars;
                    }
                    if (cadreWars.length > 0) {
                        deployConfig.wars.cadre.builtinNames = cadreWars;
                    }
                }
                
                // 加载YML配置
                if (config.ymlConfigs) {
                    if (config.ymlConfigs.unified) {
                        deployConfig.ymlConfigs.unified = config.ymlConfigs.unified;
                    }
                    if (config.ymlConfigs.cadre) {
                        deployConfig.ymlConfigs.cadre = config.ymlConfigs.cadre;
                    }
                }
                
                Message.success('配置加载成功！已填充到各个配置页面。');
                // 刷新WAR包列表显示
                loadBuiltinWars();
                // 如果当前在步骤4，更新配置摘要
                if (currentStep === 4) {
                    updateConfigSummary();
                }
                // 刷新配置列表（可能新增了配置）
                loadConfigList();
                // 提示用户可以开始配置或直接部署
                addLog('配置已加载，可以继续修改或直接开始部署', 'success');
            } else {
                Message.error('加载配置失败: ' + (data.message || '未找到保存的配置'));
            }
        })
        .catch(error => {
            Message.error('加载配置失败: ' + error.message);
            console.error('加载配置失败:', error);
        });
}

// 保存配置
function saveConfig() {
    const configNameInput = document.getElementById('configName');
    const configName = configNameInput ? configNameInput.value.trim() : '';
    
    if (!configName) {
        Message.alert('请输入配置名称');
        return;
    }
    
    saveCurrentStepData();
    
    const config = {
        installDir: deployConfig.installDir,
        middlewareType: deployConfig.middlewareType,
        // TongWeb 部署目录统一使用部署目录，保持后端字段兼容
        tongWebDeployDir: deployConfig.installDir,
        // 端口/操作系统配置已按要求移除，如需恢复可在此扩展
        databases: [
            { name: '统一支撑', ...deployConfig.databases.unified },
            { name: '干部应用', ...deployConfig.databases.cadre }
        ],
        warFiles: [],
        useBuiltInWars: true,
        ymlConfigs: deployConfig.ymlConfigs
    };
    
    // 添加WAR包配置（部署目录下所有的WAR包）
    if (deployConfig.wars.unified.builtinNames && deployConfig.wars.unified.builtinNames.length > 0) {
        deployConfig.wars.unified.builtinNames.forEach(warName => {
            config.warFiles.push('tyzc/' + warName);
        });
    }
    if (deployConfig.wars.cadre.builtinNames && deployConfig.wars.cadre.builtinNames.length > 0) {
        deployConfig.wars.cadre.builtinNames.forEach(warName => {
            config.warFiles.push('gbgl/' + warName);
        });
    }
    
    fetch('/api/deploy/config/save', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            configName: configName,
            config: config
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            Message.success('配置保存成功: ' + configName);
            addLog('配置已保存: ' + configName, 'success');
            // 清空配置名称输入框
            if (configNameInput) {
                configNameInput.value = '';
            }
            // 刷新配置列表
            loadConfigList();
        } else {
            Message.error('配置保存失败: ' + data.message);
            addLog('配置保存失败: ' + data.message, 'error');
        }
    })
    .catch(error => {
Message.error('请求失败: ' + error.message);
            addLog('请求失败: ' + error.message, 'error');
    });
}

// 检测服务
function checkService() {
    const port = parseInt(document.getElementById('checkPort').value);
    if (!port || port < 1 || port > 65535) {
        Message.alert('请输入有效的端口号');
        return;
    }

    const checkResult = document.getElementById('checkResult');
    checkResult.innerHTML = '正在检测...';
    checkResult.className = 'check-result';

    fetch(`/api/deploy/check/${port}`)
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            checkResult.innerHTML = data.message;
            checkResult.className = 'check-result ' + (data.running ? 'success' : 'error');
            addLog(data.message, data.running ? 'success' : 'warning');
        } else {
            checkResult.innerHTML = '检测失败: ' + data.message;
            checkResult.className = 'check-result error';
            addLog('服务检测失败: ' + data.message, 'error');
        }
    })
    .catch(error => {
        checkResult.innerHTML = '请求失败: ' + error.message;
        checkResult.className = 'check-result error';
        addLog('请求失败: ' + error.message, 'error');
    });
}

// 执行初始化脚本
function executeScript() {
    const app = document.getElementById('scriptDatabase').value;
    const databaseConfig = deployConfig.databases[app];
    
    if (!databaseConfig || !databaseConfig.connectionString) {
        Message.alert('请先配置数据库信息');
        return;
    }

    const scriptFilesInput = document.getElementById('scriptFiles').value;
    const scriptFiles = scriptFilesInput ? 
        scriptFilesInput.split(',').map(s => s.trim()).filter(s => s) : 
        null;

    const scriptLog = document.getElementById('scriptLog');
    scriptLog.textContent = '开始执行初始化脚本...\n';

    fetch('/api/deploy/script/execute', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            databaseConfig: databaseConfig,
            scriptFiles: scriptFiles
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            scriptLog.textContent += '脚本执行完成\n';
            Message.success('脚本执行完成');
        } else {
            scriptLog.textContent += '脚本执行失败: ' + data.message + '\n';
            Message.error('脚本执行失败: ' + data.message);
        }
    })
    .catch(error => {
        scriptLog.textContent += '请求失败: ' + error.message + '\n';
        Message.error('请求失败: ' + error.message);
    });
}

// 添加日志
function addLog(message, type) {
    const logContainer = document.getElementById('logContainer');
    if (!logContainer) return;
    
    const logEntry = document.createElement('div');
    logEntry.className = 'log-entry' + (type ? ' ' + type : '');
    logEntry.textContent = message;
    logContainer.appendChild(logEntry);
    logContainer.scrollTop = logContainer.scrollHeight;
}
