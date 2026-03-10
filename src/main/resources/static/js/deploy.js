// WebSocket连接
let ws = null;
let currentStep = 1;
// 部署向导总步骤：基础配置 + 数据库配置已合并为「参数配置」
const totalSteps = 5;

// 配置数据存储：统一管理各步骤表单与派生数据，便于跨步骤复用
const deployConfig = {
    installDir: '',
    middlewareType: 'Tomcat',
    tongWebDeployDir: '',
    /** 服务器 URL（IP+端口），用于替换 YML 中的 ${authurl}，如 http://10.200.58.167:8080 */
    serverUrl: '',
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
    },
    // YML 高亮行信息：记录模板中包含占位符的行号，用于只读视图中高亮展示替换行
    ymlHighlightLines: {
        unified: [],
        cadre: []
    },
    // TongWeb 部署目录合法性校验标记：仅当后端校验通过时置为 true，用于控制步骤跳转与部署脚本生成
    tongWebInstallValidated: false
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
        // 切换为 TongWeb 时，强制要求重新校验部署目录
        if (this.value && (this.value.toLowerCase() === 'tongweb' || this.value.toLowerCase() === 'tong_web')) {
            deployConfig.tongWebInstallValidated = false;
        }
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
// 说明：从后端获取 wars 目录下的 WAR 文件，并渲染为带“删除”操作的列表，同时回填到 deployConfig 中供后续部署使用。
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
                        // 展示列表：每个条目附带删除按钮，便于直接管理 wars/tyzc 目录下的文件
                        data.wars.unifiedList.forEach(warName => {
                            const li = document.createElement('li');
                            const nameSpan = document.createElement('span');
                            nameSpan.textContent = warName;
                            li.appendChild(nameSpan);

                            const delBtn = document.createElement('button');
                            delBtn.type = 'button';
                            delBtn.className = 'btn-secondary';
                            delBtn.style.marginLeft = '10px';
                            delBtn.textContent = '删除';
                            delBtn.onclick = function () {
                                deleteWar('unified', warName);
                            };
                            li.appendChild(delBtn);

                            unifiedList.appendChild(li);
                        });
                    } else if (data.wars.unified) {
                        // 兼容旧格式（只有一个WAR包）
                        deployConfig.wars.unified.builtinNames = [data.wars.unified];
                        const li = document.createElement('li');
                        const nameSpan = document.createElement('span');
                        nameSpan.textContent = data.wars.unified;
                        li.appendChild(nameSpan);
                        const delBtn = document.createElement('button');
                        delBtn.type = 'button';
                        delBtn.className = 'btn-secondary';
                        delBtn.style.marginLeft = '10px';
                        delBtn.textContent = '删除';
                        delBtn.onclick = function () {
                            deleteWar('unified', data.wars.unified);
                        };
                        li.appendChild(delBtn);

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
                        // 展示列表：每个条目附带删除按钮，便于直接管理 wars/gbgl 目录下的文件
                        data.wars.cadreList.forEach(warName => {
                            const li = document.createElement('li');
                            const nameSpan = document.createElement('span');
                            nameSpan.textContent = warName;
                            li.appendChild(nameSpan);

                            const delBtn = document.createElement('button');
                            delBtn.type = 'button';
                            delBtn.className = 'btn-secondary';
                            delBtn.style.marginLeft = '10px';
                            delBtn.textContent = '删除';
                            delBtn.onclick = function () {
                                deleteWar('cadre', warName);
                            };
                            li.appendChild(delBtn);

                            cadreList.appendChild(li);
                        });
                    } else if (data.wars.cadre) {
                        // 兼容旧格式（只有一个WAR包）
                        deployConfig.wars.cadre.builtinNames = [data.wars.cadre];
                        const li = document.createElement('li');
                        const nameSpan = document.createElement('span');
                        nameSpan.textContent = data.wars.cadre;
                        li.appendChild(nameSpan);
                        const delBtn = document.createElement('button');
                        delBtn.type = 'button';
                        delBtn.className = 'btn-secondary';
                        delBtn.style.marginLeft = '10px';
                        delBtn.textContent = '删除';
                        delBtn.onclick = function () {
                            deleteWar('cadre', data.wars.cadre);
                        };
                        li.appendChild(delBtn);

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

// 上传 WAR 包到指定应用的 wars 目录
// 说明：根据 app（unified/cadre）选择对应的文件输入控件，将选中的 .war 文件通过表单上传到后端。
function uploadWar(app) {
    const inputId = app === 'unified' ? 'war-upload-unified' : 'war-upload-cadre';
    const fileInput = document.getElementById(inputId);
    if (!fileInput || !fileInput.files || fileInput.files.length === 0) {
        if (typeof Message !== 'undefined') {
            Message.alert('请先选择要上传的WAR文件');
        } else {
            alert('请先选择要上传的WAR文件');
        }
        return;
    }

    const file = fileInput.files[0];
    const formData = new FormData();
    formData.append('file', file);
    formData.append('app', app);

    fetch('/api/war/upload', {
        method: 'POST',
        body: formData
    })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (typeof Message !== 'undefined') {
                    Message.success('WAR上传成功');
                }
                // 清空选择，避免同一文件无法再次触发 change
                fileInput.value = '';
                // 重新加载列表，反映最新文件状态
                loadBuiltinWars();
            } else {
                if (typeof Message !== 'undefined') {
                    Message.error('WAR上传失败: ' + (data.message || '未知错误'));
                } else {
                    alert('WAR上传失败: ' + (data.message || '未知错误'));
                }
            }
        })
        .catch(error => {
            if (typeof Message !== 'undefined') {
                Message.error('WAR上传失败: ' + error.message);
            } else {
                alert('WAR上传失败: ' + error.message);
            }
        });
}

// 删除指定应用下的单个 WAR 文件
// 说明：点击列表项右侧的“删除”按钮时调用，通过后端删除 wars 目录中的对应文件并刷新列表。
function deleteWar(app, warName) {
    if (!warName) {
        return;
    }

    // 简单二次确认，避免误删
    const ok = window.confirm('确定要删除 WAR 包：' + warName + ' 吗？\n删除后需要重新上传才能继续使用。');
    if (!ok) {
        return;
    }

    const url = '/api/war/delete?app=' + encodeURIComponent(app) + '&fileName=' + encodeURIComponent(warName);
    fetch(url, {
        method: 'DELETE'
    })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (typeof Message !== 'undefined') {
                    Message.success('WAR删除成功');
                }
                loadBuiltinWars();
            } else {
                if (typeof Message !== 'undefined') {
                    Message.error('WAR删除失败: ' + (data.message || '未知错误'));
                } else {
                    alert('WAR删除失败: ' + (data.message || '未知错误'));
                }
            }
        })
        .catch(error => {
            if (typeof Message !== 'undefined') {
                Message.error('WAR删除失败: ' + error.message);
            } else {
                alert('WAR删除失败: ' + error.message);
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
            // 基础参数 + 数据库信息已完整
            const hasBaseConfig = !!(deployConfig.installDir &&
                deployConfig.databases.unified &&
                deployConfig.databases.cadre &&
                deployConfig.databases.unified.ip &&
                deployConfig.databases.unified.username &&
                deployConfig.databases.unified.password &&
                deployConfig.databases.cadre.ip &&
                deployConfig.databases.cadre.username &&
                deployConfig.databases.cadre.password);
            if (!hasBaseConfig) {
                return false;
            }
            // 若当前中间件为 TongWeb，则还需 TongWeb 部署目录通过后端校验
            const mt = (deployConfig.middlewareType || '').toLowerCase();
            if (mt === 'tongweb' || mt === 'tong_web') {
                return !!deployConfig.tongWebInstallValidated;
            }
            return true;
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
            // TongWeb 特殊校验：通过后端接口检查部署目录是否符合安装层级，且存在 startd.sh / stopserver.sh
            const middlewareSelect = document.getElementById('middlewareType');
            const middlewareType = middlewareSelect ? (middlewareSelect.value || '').trim() : (deployConfig.middlewareType || '');
            if (middlewareType && (middlewareType.toLowerCase() === 'tongweb' || middlewareType.toLowerCase() === 'tong_web')) {
                try {
                    const xhr = new XMLHttpRequest();
                    xhr.open('GET', '/api/deploy/tongweb/installDir/check?path=' + encodeURIComponent(installDir), false);
                    xhr.send(null);
                    if (xhr.status === 200) {
                        const data = JSON.parse(xhr.responseText);
                        if (!data.success || !data.exists || !data.binExists || !data.scriptsValid) {
                            const msg = (data && data.message) ? data.message : 'TongWeb 部署目录校验未通过，请检查路径及安装结构。';
                            if (typeof Message !== 'undefined') {
                                Message.alert(msg);
                            } else {
                                alert(msg);
                            }
                            deployConfig.tongWebInstallValidated = false;
                            return false;
                        }
                        // 校验通过，记录标记并继续后续验证
                        deployConfig.tongWebInstallValidated = true;
                    } else {
                        const msg = 'TongWeb 部署目录校验请求失败，HTTP状态码: ' + xhr.status;
                        if (typeof Message !== 'undefined') {
                            Message.alert(msg);
                        } else {
                            alert(msg);
                        }
                        deployConfig.tongWebInstallValidated = false;
                        return false;
                    }
                } catch (e) {
                    const msg = 'TongWeb 部署目录校验异常: ' + e.message;
                    if (typeof Message !== 'undefined') {
                        Message.alert(msg);
                    } else {
                        alert(msg);
                    }
                    deployConfig.tongWebInstallValidated = false;
                    return false;
                }
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
            // 服务器 URL：用于替换 YML 中的 authurl
            deployConfig.serverUrl = (document.getElementById('serverUrl') && document.getElementById('serverUrl').value) ? document.getElementById('serverUrl').value.trim() : '';
            // 数据库配置：仅保存统一IP + 各库用户名 / 密码；同时派生 connectionString 供后端接口复用
            // 注：目前默认使用达梦连接串模板（后续如需支持多数据库类型，可扩展为可选项）
            const commonIp = (document.getElementById('db-ip-common').value || '').trim();
            const unifiedUser = (document.getElementById('db-user-unified').value || '').trim();
            const unifiedPwd = (document.getElementById('db-pwd-unified').value || '').trim();
            const cadreUser = (document.getElementById('db-user-cadre').value || '').trim();
            const cadrePwd = (document.getElementById('db-pwd-cadre').value || '').trim();
            // 连接串仅使用 IP，不再默认追加 :5236/DAMENG，端口与库名由实际环境或数据源配置决定
            deployConfig.databases.unified = {
                type: '达梦',
                ip: commonIp,
                username: unifiedUser,
                password: unifiedPwd,
                connectionString: commonIp ? `jdbc:dm://${commonIp}` : ''
            };
            deployConfig.databases.cadre = {
                type: '达梦',
                ip: commonIp,
                username: cadreUser,
                password: cadrePwd,
                connectionString: commonIp ? `jdbc:dm://${commonIp}` : ''
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
    const connectionString = ip ? `jdbc:dm://${ip}` : '';

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
        const connectionString = ip ? `jdbc:dm://${ip}` : '';
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
    
    // 服务器 URL：优先从表单读取，否则用 deployConfig
    const rawServerUrl = (document.getElementById('serverUrl') && document.getElementById('serverUrl').value)
        ? document.getElementById('serverUrl').value.trim()
        : (deployConfig.serverUrl || '');
    const middleware = (deployConfig.middlewareType || '').toLowerCase();

    // 对于 Tomcat：authurl 统一使用“统一支撑 Tomcat”的主机:端口（8111），不自动补全 http:// 协议前缀；
    // 即便是干部应用的 YML，也以统一支撑的 Tomcat 端口为认证入口。
    // 对于 TongWeb：直接使用用户填写的 serverUrl 文本，不做协议补全。
    let authUrlValue;
    if (middleware === 'tomcat') {
        const baseWithProto = buildTomcatBaseUrl(rawServerUrl, 8111);
        // 去掉协议前缀，仅保留 host:port 形式，避免在 YML 中强制添加 http://
        authUrlValue = baseWithProto.replace(/^https?:\/\//i, '');
    } else {
        authUrlValue = rawServerUrl.trim();
    }

    // 替换值（不包含单引号）：数据库占位符 + authurl
    const replacements = {
        '${type}': 'com.alibaba.druid.pool.DruidDataSource', // 数据源类型固定为Druid
        '${url}': dbConfig.connectionString || '',
        '${username}': dbConfig.username || '',
        '${password}': dbConfig.password || '',
        '${driver-class-name}': getDriverClassName(dbConfig.type),
        '${authurl}': authUrlValue
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

    // 兼容历史YML内容：若旧内容里存在默认的达梦连接串 jdbc:dm://<ip>:5236/DAMENG，
    // 且当前界面生成的连接串仅包含 IP（jdbc:dm://<ip>），则去除默认的 :5236/DAMENG，避免再次写入默认值。
    // 说明：仅在 connectionString 不包含端口/库名时触发，避免覆盖用户显式配置的非默认连接串。
    if (dbConfig && dbConfig.type && (dbConfig.type === '达梦' || String(dbConfig.type).toUpperCase() === 'DM')) {
        const cs = String(dbConfig.connectionString || '').trim();
        const simpleDm = /^jdbc:dm:\/\/[^\/:]+$/.test(cs);
        if (simpleDm) {
            const host = cs.replace('jdbc:dm://', '');
            const escapeReg = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            // 1) 精确替换当前 host 的默认拼接形式
            result = result.replace(new RegExp(`jdbc:dm://${escapeReg(host)}:5236/DAMENG`, 'g'), cs);
            // 2) 兜底：若 YML 内仍包含默认 :5236/DAMENG，则替换为当前 connectionString
            result = result.replace(/jdbc:dm:\/\/[^\s'"]+:5236\/DAMENG/g, cs);
        }
    }
    
    return result;
}

// 计算 YML 模板中需要高亮的行号（包含占位符的行），仅在模板首次加载或重置时调用
function computeYmlHighlightLines(rawContent) {
    // 使用占位符集合集中维护，后续若有新增占位符仅需在此处补充
    const placeholders = ['${type}', '${url}', '${username}', '${password}', '${driver-class-name}', '${authurl}'];
    const lines = rawContent.split(/\r?\n/);
    const highlight = [];
    lines.forEach((line, index) => {
        if (placeholders.some(ph => line.includes(ph))) {
            // 记录用户易理解的 1 开始的行号
            highlight.push(index + 1);
        }
    });
    return highlight;
}

// 将 textarea 中的 YML 内容渲染到只读视图中，支持按行高亮
function renderYmlViewer(app) {
    const viewer = document.getElementById('ymlViewer');
    const textarea = document.getElementById('ymlContent');
    if (!viewer || !textarea) {
        return;
    }
    const content = textarea.value || '';
    const highlightLines = (deployConfig.ymlHighlightLines && deployConfig.ymlHighlightLines[app]) || [];

    // 清空旧内容，重新构建只读代码视图
    viewer.innerHTML = '';
    const codeContainer = document.createElement('div');
    codeContainer.className = 'yml-viewer-code';

    const lines = content.split(/\r?\n/);
    lines.forEach((text, index) => {
        const lineNo = index + 1;
        const lineEl = document.createElement('div');
        // 根据预先计算的行号决定是否应用高亮类
        lineEl.className = 'yml-line' + (highlightLines.includes(lineNo) ? ' yml-line-modified' : '');

        const noEl = document.createElement('span');
        noEl.className = 'yml-line-no';
        noEl.textContent = String(lineNo).padStart(2, ' ');

        const textEl = document.createElement('span');
        textEl.className = 'yml-line-text';
        textEl.textContent = text || ' ';

        lineEl.appendChild(noEl);
        lineEl.appendChild(textEl);
        codeContainer.appendChild(lineEl);
    });

    viewer.appendChild(codeContainer);
}

// 复制当前应用的YML内容到剪贴板，便于快速粘贴到外部编辑器或配置平台
function copyYmlToClipboard() {
    const textarea = document.getElementById('ymlContent');
    const content = textarea ? textarea.value : '';
    if (!content) {
        if (typeof Message !== 'undefined') {
            Message.warning('当前没有可复制的YML内容');
        }
        return;
    }

    // 优先使用现代 Clipboard API，在不支持时降级为 execCommand 方案
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(content)
            .then(() => {
                if (typeof Message !== 'undefined') {
                    Message.success('YML内容已复制到剪贴板');
                }
            })
            .catch(err => {
                console.error('[copyYmlToClipboard] Clipboard API 复制失败:', err);
                fallbackCopyYml(content);
            });
    } else {
        fallbackCopyYml(content);
    }
}

// 回退复制实现：通过临时 textarea + execCommand('copy') 兼容老旧浏览器环境
function fallbackCopyYml(text) {
    const temp = document.createElement('textarea');
    temp.value = text;
    temp.style.position = 'fixed';
    temp.style.left = '-9999px';
    temp.style.top = '-9999px';
    document.body.appendChild(temp);
    temp.select();
    try {
        const ok = document.execCommand('copy');
        if (typeof Message !== 'undefined') {
            if (ok) {
                Message.success('YML内容已复制到剪贴板');
            } else {
                Message.warning('复制可能未成功，请手动选择并复制');
            }
        }
    } catch (e) {
        console.error('[fallbackCopyYml] execCommand 复制失败:', e);
        if (typeof Message !== 'undefined') {
            Message.error('浏览器不支持自动复制，请手动选择并复制');
        }
    } finally {
        document.body.removeChild(temp);
    }
}

// 标签切换：统一支撑 / 干部应用，在顶部 tab 与隐藏 select 之间保持状态同步
function switchYmlTab(app) {
    const appSelect = document.getElementById('ymlAppSelect');
    if (!appSelect) {
        return;
    }
    // 更新隐藏下拉框的值以复用已有逻辑
    appSelect.value = app;

    // 更新标签激活态
    document.querySelectorAll('.yml-tab').forEach(tab => {
        if (tab.dataset.app === app) {
            tab.classList.add('active');
        } else {
            tab.classList.remove('active');
        }
    });

    // 加载当前应用的 YML 模板，并在完成后渲染只读视图
    loadYmlTemplate();
}

// 加载YML模板并渲染为只读视图：内部仍复用 textarea 存储真实配置文本
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
    
    // 如果已有“有效保存”的配置，直接显示；否则从服务器加载模板
    // 说明：当之前因为模板异常回退到默认占位内容（以 "# YML配置文件" 开头）时，不视为有效保存配置，避免一直展示占位符
    const savedContent = deployConfig.ymlConfigs[app];
    const highlightLines = deployConfig.ymlHighlightLines && deployConfig.ymlHighlightLines[app];
    // 仅当存在非占位符内容且已具备高亮行信息时，才认为是“有效保存”的配置，直接渲染，避免丢失高亮
    const hasSavedConfig = !!(
        savedContent &&
        !savedContent.trim().startsWith('# YML配置文件') &&
        highlightLines &&
        Array.isArray(highlightLines) &&
        highlightLines.length > 0
    );
    if (hasSavedConfig) {
        console.log('[loadYmlTemplate] 使用已保存的YML配置');
        let content = savedContent;
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
        // 已保存配置通常不再包含占位符，此时沿用历史高亮设置即可
        renderYmlViewer(app);
    } else {
        console.log('[loadYmlTemplate] 从服务器加载YML模板');
        fetch(`/api/yml/template/${app}`)
            .then(response => response.json())
            .then(data => {
                console.log('[loadYmlTemplate] 服务器响应:', data);
                // content 为空字符串时也视为“无模板内容”，走默认占位模板逻辑，避免界面空白
                const hasContent = !!(data && typeof data.content === 'string' && data.content.trim().length > 0);
                if (data.success && hasContent) {
                    let content = data.content;
                    console.log('[loadYmlTemplate] 模板内容长度:', content.length);
                    console.log('[loadYmlTemplate] 模板内容包含占位符:', {
                        hasType: content.includes('${type}'),
                        hasUrl: content.includes('${url}'),
                        hasUsername: content.includes('${username}'),
                        hasPassword: content.includes('${password}'),
                        hasDriver: content.includes('${driver-class-name}')
                    });
                    
                    // 先记录原始模板，用于计算包含占位符的高亮行
                    deployConfig.ymlHighlightLines[app] = computeYmlHighlightLines(content);
                    // 自动替换数据库占位符
                    content = replaceDatabasePlaceholders(content, app);
                    document.getElementById('ymlContent').value = content;
                    deployConfig.ymlConfigs[app] = content;
                    console.log('[loadYmlTemplate] 模板加载并替换完成');
                    // 模板加载后立即渲染只读视图
                    renderYmlViewer(app);
                } else {
                    console.warn('[loadYmlTemplate] 服务器返回失败或无内容');
                    let defaultContent = '# YML配置文件\n# 请在此编辑配置内容';
                    // 即使没有模板，也尝试替换占位符
                    deployConfig.ymlHighlightLines[app] = computeYmlHighlightLines(defaultContent);
                    defaultContent = replaceDatabasePlaceholders(defaultContent, app);
                    document.getElementById('ymlContent').value = defaultContent;
                    // 渲染只读视图以避免空白
                    renderYmlViewer(app);
                }
            })
            .catch(error => {
                console.error('[loadYmlTemplate] 加载YML模板失败:', error);
                let defaultContent = '# YML配置文件\n# 请在此编辑配置内容';
                deployConfig.ymlHighlightLines[app] = computeYmlHighlightLines(defaultContent);
                defaultContent = replaceDatabasePlaceholders(defaultContent, app);
                document.getElementById('ymlContent').value = defaultContent;
                renderYmlViewer(app);
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

// 更新配置摘要：生成易读的配置清单 HTML，供确认配置步骤展示
function updateConfigSummary() {
    const summaryDiv = document.getElementById('configSummary');
    if (!summaryDiv) return;
    let html = '';

    html += '<div class="config-summary-item">';
    html += '<h4>参数配置</h4>';
    html += `<p><strong>部署目录：</strong>${escapeHtml(deployConfig.installDir)}</p>`;
    html += `<p><strong>中间件类型：</strong>${escapeHtml(deployConfig.middlewareType)}</p>`;
    html += `<p><strong>服务器 URL：</strong>${escapeHtml(deployConfig.serverUrl || '未配置')}</p>`;
    html += '</div>';

    html += '<div class="config-summary-item">';
    html += '<h4>数据库配置</h4>';
    html += '<p class="config-summary-sub">统一支撑</p>';
    html += `<p><strong>IP：</strong>${escapeHtml(deployConfig.databases.unified.ip || '未配置')}</p>`;
    html += `<p><strong>用户名：</strong>${escapeHtml(deployConfig.databases.unified.username || '未配置')}</p>`;
    html += '<p class="config-summary-sub">干部应用</p>';
    html += `<p><strong>IP：</strong>${escapeHtml(deployConfig.databases.cadre.ip || '未配置')}</p>`;
    html += `<p><strong>用户名：</strong>${escapeHtml(deployConfig.databases.cadre.username || '未配置')}</p>`;
    html += '</div>';

    const unifiedWars = deployConfig.wars.unified.builtinNames && deployConfig.wars.unified.builtinNames.length > 0
        ? deployConfig.wars.unified.builtinNames.join(', ')
        : '未找到WAR包';
    const cadreWars = deployConfig.wars.cadre.builtinNames && deployConfig.wars.cadre.builtinNames.length > 0
        ? deployConfig.wars.cadre.builtinNames.join(', ')
        : '未找到WAR包';
    html += '<div class="config-summary-item">';
    html += '<h4>WAR包配置</h4>';
    html += `<p><strong>统一支撑：</strong>${escapeHtml(unifiedWars)}</p>`;
    html += `<p><strong>干部应用：</strong>${escapeHtml(cadreWars)}</p>`;
    html += '</div>';

    summaryDiv.innerHTML = html;
}

// 简单转义，防止配置内容中的 HTML 被渲染
function escapeHtml(str) {
    if (str == null) return '';
    const s = String(str);
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// 开始部署
function startDeploy() {
    saveCurrentStepData();

    // 部署前再次用最新数据库配置刷新 YML 内容，避免加载旧配置/历史内容导致连接串仍残留 :5236/DAMENG
    if (deployConfig.ymlConfigs) {
        if (deployConfig.ymlConfigs.unified) {
            deployConfig.ymlConfigs.unified = replaceDatabasePlaceholders(deployConfig.ymlConfigs.unified, 'unified');
        }
        if (deployConfig.ymlConfigs.cadre) {
            deployConfig.ymlConfigs.cadre = replaceDatabasePlaceholders(deployConfig.ymlConfigs.cadre, 'cadre');
        }
    }
    
    // 构建部署配置
    const config = {
        installDir: deployConfig.installDir,
        // 操作系统类型：前端根据当前浏览器环境粗略判断，后端在具体执行时仍会结合 System.getProperty("os.name") 兜底
        osType: navigator.platform && navigator.platform.toLowerCase().indexOf('win') !== -1 ? 'Windows' : 'Linux',
        middlewareType: deployConfig.middlewareType,
        tongWebDeployDir: deployConfig.installDir,
        serverUrl: deployConfig.serverUrl || '',
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
            // 部署任务启动成功后，开始轮询检测业务服务心跳，直到成功或超出最大重试次数
            startHeartbeatPolling();
        } else {
            addLog('启动部署失败: ' + data.message, 'error');
            updateServiceStatusBar({ unified: 'error', cadre: 'error', message: '部署启动失败，未进行心跳检测' });
        }
    })
    .catch(error => {
        addLog('请求失败: ' + error.message, 'error');
        updateServiceStatusBar({ unified: 'error', cadre: 'error', message: '部署请求失败，未进行心跳检测' });
    });
}

// 生成部署脚本并通过弹窗展示
// 说明：脚本基于当前界面配置（部署目录、中间件类型、WAR列表等）生成，主要面向Linux目标服务器，便于运维直接复制到服务器执行。
function generateDeployScript() {
    // 先保存当前步骤的数据，确保 installDir / middlewareType / wars 信息是最新的
    saveCurrentStepData();

    const installDir = (deployConfig.installDir || '').trim();
    const middlewareType = (deployConfig.middlewareType || 'Tomcat').trim();
    const unifiedWars = (deployConfig.wars.unified && deployConfig.wars.unified.builtinNames) || [];
    const cadreWars = (deployConfig.wars.cadre && deployConfig.wars.cadre.builtinNames) || [];

    if (!installDir) {
        if (typeof Message !== 'undefined') {
            Message.alert('请先在第一步填写部署目录，再生成部署脚本');
        } else {
            alert('请先在第一步填写部署目录，再生成部署脚本');
        }
        return;
    }

    const script = buildLinuxDeployScript(
        installDir,
        middlewareType,
        unifiedWars,
        cadreWars,
        deployConfig.ymlConfigs.unified || '',
        deployConfig.ymlConfigs.cadre || ''
    );
    const scriptTextArea = document.getElementById('deployScriptText');
    if (scriptTextArea) {
        scriptTextArea.value = script;
        // 弹窗内滚动到顶部，方便用户立即看到生成结果
        scriptTextArea.scrollTop = 0;
        scriptTextArea.focus();
    }

    // 生成完成后弹窗展示脚本，避免占用主页面高度
    openDeployScriptModal();
}

// 打开部署脚本弹窗
// 说明：弹窗用于展示脚本内容，不占用主页面的部署日志高度。
function openDeployScriptModal() {
    const backdrop = document.getElementById('deployScriptModalBackdrop');
    if (!backdrop) return;
    backdrop.style.display = 'flex';

    // 点击遮罩层关闭（点击弹窗主体不关闭）
    if (!backdrop.dataset.bound) {
        backdrop.dataset.bound = '1';
        backdrop.addEventListener('click', function (e) {
            if (e && e.target === backdrop) {
                closeDeployScriptModal();
            }
        });
        // ESC 关闭
        window.addEventListener('keydown', function (e) {
            if (e && e.key === 'Escape') {
                closeDeployScriptModal();
            }
        });
    }
}

// 关闭部署脚本弹窗
function closeDeployScriptModal() {
    const backdrop = document.getElementById('deployScriptModalBackdrop');
    if (!backdrop) return;
    backdrop.style.display = 'none';
}

// 构建 Linux 环境下的部署脚本文本
// 说明：
// - 脚本假定：本脚本所在目录下存在 wars 目录（与JAR部署工具当前约定一致），其中包含 tyzc/ 与 gbgl/ 子目录及对应的 WAR 文件；
// - 对于 Tomcat：除复制 WAR 并启动实例外，会在部署前通过 unzip/zip 方式将 tyzc-api.war / gbgl.war 中的 application-dev-dm.yml 替换为当前界面生成的配置内容；
// - 对于 TongWeb：同样在复制前对 tyzc-api.war / gbgl.war 做 YML 替换，再拷贝到部署目录并调用 stopserver.sh / startd.sh。
function buildLinuxDeployScript(installDir, middlewareType, unifiedWars, cadreWars, unifiedYml, cadreYml) {
    const warsComment = '# WAR 源目录：假定为与本脚本同级的 wars 目录（与部署工具约定一致）';

    // 统一支撑 / 干部应用的YML内容（如存在），用于在脚本中写入临时文件并替换WAR内部的 application-dev-dm.yml
    const hasUnifiedYml = !!(unifiedYml && unifiedYml.trim());
    const hasCadreYml = !!(cadreYml && cadreYml.trim());

    let ymlSection = '';
    if (hasUnifiedYml) {
        const unifiedEscaped = unifiedYml.replace(/\\/g, '\\\\');
        ymlSection +=
`# 生成统一支撑应用的YML配置文件（供后续替换WAR内部 application-dev-dm.yml 使用）
YML_UNIFIED_FILE="$SCRIPT_DIR/yml_unified_application-dev-dm.yml"
cat > "$YML_UNIFIED_FILE" <<'EOF_YML_UNIFIED'
${unifiedEscaped}
EOF_YML_UNIFIED

`;
    }
    if (hasCadreYml) {
        const cadreEscaped = cadreYml.replace(/\\/g, '\\\\');
        ymlSection +=
`# 生成干部应用的YML配置文件（供后续替换WAR内部 application-dev-dm.yml 使用）
YML_CADRE_FILE="$SCRIPT_DIR/yml_cadre_application-dev-dm.yml"
cat > "$YML_CADRE_FILE" <<'EOF_YML_CADRE'
${cadreEscaped}
EOF_YML_CADRE

`;
    }

    const commonHeader =
`#!/bin/bash
set -e

# ============================================
# 一键部署脚本（Linux 示例）
# 说明：
# 1. 请在目标Linux服务器上执行本脚本；
# 2. 执行前请确认：
#    - 部署目录与当前界面中填写的“部署目录”保持一致；
#    - wars 目录结构为：
#        ./wars/tyzc/*.war
#        ./wars/gbgl/*.war
#    - Tomcat / TongWeb 已安装且端口等基础配置已正确设置。
# 3. 如需调整，请根据实际环境自行修改相应路径。
# ============================================

# 部署根目录（与界面“部署目录”一致）
INSTALL_DIR="${installDir}"

${warsComment}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WARS_DIR="\${SCRIPT_DIR}/wars"

mkdir -p "$INSTALL_DIR"

${ymlSection}

`;

    let tomcatPart = '';
    let tongwebPart = '';

    // Tomcat 部署片段
    if (middlewareType.toLowerCase() === 'tomcat') {
        const tyzcTomcatDir = `${installDir}/tomcat-tyzc`;
        const gbglTomcatDir = `${installDir}/tomcat-gbgl`;

        let tyzcCopyLines = '';
        if (unifiedWars && unifiedWars.length > 0) {
            unifiedWars.forEach(name => {
                const fileName = name;
                // 对 tyzc-api.war 执行YML替换流程（若已生成统一支撑YML）
                if (hasUnifiedYml && fileName === 'tyzc-api.war') {
                    tyzcCopyLines +=
`echo "[信息] 使用统一支撑YML配置替换 Tomcat WAR: ${fileName}"
WORK_DIR_TYZC_API="$(mktemp -d)"
cp "$WARS_DIR/tyzc/${fileName}" "$WORK_DIR_TYZC_API/app.war"
cd "$WORK_DIR_TYZC_API"
unzip -q app.war
mkdir -p WEB-INF/classes
cp "$YML_UNIFIED_FILE" "WEB-INF/classes/application-dev-dm.yml"
zip -qr app_new.war .
mv app_new.war "$WARS_DIR/tyzc/${fileName}"
cd "$SCRIPT_DIR"
rm -rf "$WORK_DIR_TYZC_API"

`;
                }
                tyzcCopyLines += `cp "$WARS_DIR/tyzc/${fileName}" "${tyzcTomcatDir}/webapps/${fileName}"\n`;
            });
        } else {
            tyzcCopyLines += `# TODO: 在 wars/tyzc 目录下放置统一支撑相关 WAR 包\n`;
        }

        let gbglCopyLines = '';
        if (cadreWars && cadreWars.length > 0) {
            cadreWars.forEach(name => {
                const fileName = name;
                // 对 gbgl.war 执行YML替换流程（若已生成干部应用YML）
                if (hasCadreYml && fileName === 'gbgl.war') {
                    gbglCopyLines +=
`echo "[信息] 使用干部应用YML配置替换 Tomcat WAR: ${fileName}"
WORK_DIR_GBGL="$(mktemp -d)"
cp "$WARS_DIR/gbgl/${fileName}" "$WORK_DIR_GBGL/app.war"
cd "$WORK_DIR_GBGL"
unzip -q app.war
mkdir -p WEB-INF/classes
cp "$YML_CADRE_FILE" "WEB-INF/classes/application-dev-dm.yml"
zip -qr app_new.war .
mv app_new.war "$WARS_DIR/gbgl/${fileName}"
cd "$SCRIPT_DIR"
rm -rf "$WORK_DIR_GBGL"

`;
                }
                gbglCopyLines += `cp "$WARS_DIR/gbgl/${fileName}" "${gbglTomcatDir}/webapps/${fileName}"\n`;
            });
        } else {
            gbglCopyLines += `# TODO: 在 wars/gbgl 目录下放置干部应用相关 WAR 包\n`;
        }

        tomcatPart =
`# ========== Tomcat 部署（统一支撑 + 干部应用）==========

echo "[信息] 开始部署 Tomcat 应用..."

# 统一支撑 Tomcat 实例目录
TOMCAT_TYZC_DIR="${tyzcTomcatDir}"
mkdir -p "$TOMCAT_TYZC_DIR/webapps"

echo "[信息] 复制统一支撑 WAR 包到: $TOMCAT_TYZC_DIR/webapps"
${tyzcCopyLines}

# 干部应用 Tomcat 实例目录
TOMCAT_GBGL_DIR="${gbglTomcatDir}"
mkdir -p "$TOMCAT_GBGL_DIR/webapps"

echo "[信息] 复制干部应用 WAR 包到: $TOMCAT_GBGL_DIR/webapps"
${gbglCopyLines}

echo "[信息] 启动统一支撑 Tomcat..."
# 启动前为 bin 目录下所有 Shell 脚本批量授予执行权限，避免 startup.sh 内部调用 catalina.sh 等脚本因无权限失败
chmod +x "$TOMCAT_TYZC_DIR/bin/"*.sh 2>/dev/null || true
"$TOMCAT_TYZC_DIR/bin/startup.sh"

echo "[信息] 启动干部应用 Tomcat..."
chmod +x "$TOMCAT_GBGL_DIR/bin/"*.sh 2>/dev/null || true
"$TOMCAT_GBGL_DIR/bin/startup.sh"

echo "[成功] Tomcat 部署流程执行完毕，请通过日志和端口检查实际运行状态。"

`;
    }

    // TongWeb 部署片段（基于用户提供的安装路径示例）
    if (middlewareType.toLowerCase() === 'tongweb' || middlewareType.toLowerCase() === 'tong_web') {
        let tongwebCopyLines = '';
        const allWars = [];
        if (unifiedWars && unifiedWars.length > 0) {
            unifiedWars.forEach(name => allWars.push({ app: '统一支撑', name }));
        }
        if (cadreWars && cadreWars.length > 0) {
            cadreWars.forEach(name => allWars.push({ app: '干部应用', name }));
        }

        if (allWars.length > 0) {
            allWars.forEach(item => {
                // 根据WAR名称判断是否需要执行YML替换（tyzc-api.war / gbgl.war）
                if (hasUnifiedYml && item.name === 'tyzc-api.war') {
                    tongwebCopyLines +=
`echo "[信息] 使用统一支撑YML配置替换 TongWeb WAR: ${item.name}"
WORK_DIR_TW_TYZC_API="$(mktemp -d)"
if [ -f "$WARS_DIR/tyzc/${item.name}" ]; then
  cp "$WARS_DIR/tyzc/${item.name}" "$WORK_DIR_TW_TYZC_API/app.war"
elif [ -f "$WARS_DIR/gbgl/${item.name}" ]; then
  cp "$WARS_DIR/gbgl/${item.name}" "$WORK_DIR_TW_TYZC_API/app.war"
else
  echo "[警告] 未在 wars 目录中找到 ${item.name}，跳过YML替换。"
fi
cd "$WORK_DIR_TW_TYZC_API"
if [ -f "app.war" ]; then
  unzip -q app.war
  mkdir -p WEB-INF/classes
  cp "$YML_UNIFIED_FILE" "WEB-INF/classes/application-dev-dm.yml"
  zip -qr app_new.war .
  mv app_new.war "$SCRIPT_DIR/${item.name}.tmp.war"
fi
cd "$SCRIPT_DIR"
rm -rf "$WORK_DIR_TW_TYZC_API"

if [ -f "$SCRIPT_DIR/${item.name}.tmp.war" ]; then
  cp "$SCRIPT_DIR/${item.name}.tmp.war" "${installDir}/${item.name}"
  rm -f "$SCRIPT_DIR/${item.name}.tmp.war"
else
  cp "$WARS_DIR/tyzc/${item.name}" "${installDir}/${item.name}" 2>/dev/null || cp "$WARS_DIR/gbgl/${item.name}" "${installDir}/${item.name}" 2>/dev/null || echo "[警告] 未在 wars 目录中找到 ${item.name}"
fi

`;
                } else if (hasCadreYml && item.name === 'gbgl.war') {
                    tongwebCopyLines +=
`echo "[信息] 使用干部应用YML配置替换 TongWeb WAR: ${item.name}"
WORK_DIR_TW_GBGL="$(mktemp -d)"
if [ -f "$WARS_DIR/gbgl/${item.name}" ]; then
  cp "$WARS_DIR/gbgl/${item.name}" "$WORK_DIR_TW_GBGL/app.war"
elif [ -f "$WARS_DIR/tyzc/${item.name}" ]; then
  cp "$WARS_DIR/tyzc/${item.name}" "$WORK_DIR_TW_GBGL/app.war"
else
  echo "[警告] 未在 wars 目录中找到 ${item.name}，跳过YML替换。"
fi
cd "$WORK_DIR_TW_GBGL"
if [ -f "app.war" ]; then
  unzip -q app.war
  mkdir -p WEB-INF/classes
  cp "$YML_CADRE_FILE" "WEB-INF/classes/application-dev-dm.yml"
  zip -qr app_new.war .
  mv app_new.war "$SCRIPT_DIR/${item.name}.tmp.war"
fi
cd "$SCRIPT_DIR"
rm -rf "$WORK_DIR_TW_GBGL"

if [ -f "$SCRIPT_DIR/${item.name}.tmp.war" ]; then
  cp "$SCRIPT_DIR/${item.name}.tmp.war" "${installDir}/${item.name}"
  rm -f "$SCRIPT_DIR/${item.name}.tmp.war"
else
  cp "$WARS_DIR/gbgl/${item.name}" "${installDir}/${item.name}" 2>/dev/null || cp "$WARS_DIR/tyzc/${item.name}" "${installDir}/${item.name}" 2>/dev/null || echo "[警告] 未在 wars 目录中找到 ${item.name}"
fi

`;
                } else {
                    tongwebCopyLines += `echo "[信息] 复制 ${item.app} WAR 包: ${item.name}"\n`;
                    tongwebCopyLines += `cp "$WARS_DIR/tyzc/${item.name}" "${installDir}/${item.name}" 2>/dev/null || cp "$WARS_DIR/gbgl/${item.name}" "${installDir}/${item.name}" 2>/dev/null || echo "[警告] 未在 wars 目录中找到 ${item.name}"\n`;
                }
            });
        } else {
            tongwebCopyLines += `# TODO: 请在 wars/tyzc 与 wars/gbgl 目录中放置需要部署的 WAR 包\n`;
        }

        tongwebPart =
`# ========== TongWeb 部署（参考路径：/usr/local/TongWeb8.0.9.09）==========

echo "[信息] 开始部署 TongWeb 应用..."

# TongWeb 安装根目录（请根据实际环境调整版本号）
TONGWEB_HOME="/usr/local/TongWeb8.0.9.09"
TONGWEB_BIN="$TONGWEB_HOME/bin"

if [ ! -d "$TONGWEB_HOME" ]; then
  echo "[警告] 未找到 TongWeb 安装目录: $TONGWEB_HOME，请按实际环境修改脚本中的 TONGWEB_HOME 变量"
fi

echo "[信息] 复制 WAR 包到 TongWeb 部署目录: ${installDir}"
mkdir -p "${installDir}"
${tongwebCopyLines}

echo "[信息] 尝试停止已有 TongWeb 服务..."
if [ -f "$TONGWEB_BIN/stopserver.sh" ]; then
  chmod +x "$TONGWEB_BIN/stopserver.sh" 2>/dev/null || true
  "$TONGWEB_BIN/stopserver.sh" || echo "[警告] TongWeb 停止命令执行可能失败，请根据日志确认。"
else
  echo "[提示] 未找到 stopserver.sh，请确认 TongWeb 安装路径。"
fi

echo "[信息] 启动 TongWeb..."
if [ -f "$TONGWEB_BIN/startd.sh" ]; then
  chmod +x "$TONGWEB_BIN/startd.sh" 2>/dev/null || true
  nohup "$TONGWEB_BIN/startd.sh" > /dev/null 2>&1 &
  echo "[信息] 已触发 TongWeb 启动，请稍后通过日志和端口检查实际状态。"
else
  echo "[提示] 未找到 startd.sh，请确认 TongWeb 安装路径。"
fi

`;
    }

    // 如果当前只选了某一种中间件，就只输出对应片段；若未来支持多中间件并行，可在此拼接多个片段
    let body = '';
    if (middlewareType.toLowerCase() === 'tomcat') {
        body = tomcatPart;
    } else if (middlewareType.toLowerCase() === 'tongweb' || middlewareType.toLowerCase() === 'tong_web') {
        body = tongwebPart;
    } else {
        // 未知类型时，同时给出两个示例，方便用户参考修改
        body = tomcatPart + '\n\n' + tongwebPart;
    }

    return commonHeader + body;
}

// 复制部署脚本到剪贴板
// 说明：便于运维在界面上直接一键复制脚本，然后粘贴到目标服务器上的文件中执行。
function copyDeployScript() {
    const scriptTextArea = document.getElementById('deployScriptText');
    if (!scriptTextArea || !scriptTextArea.value) {
        if (typeof Message !== 'undefined') {
            Message.warning('暂无可复制的部署脚本，请先生成脚本');
        } else {
            alert('暂无可复制的部署脚本，请先生成脚本');
        }
        return;
    }

    const content = scriptTextArea.value;

    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(content)
            .then(function () {
                if (typeof Message !== 'undefined') {
                    Message.success('部署脚本已复制到剪贴板');
                }
            })
            .catch(function (err) {
                console.error('[copyDeployScript] Clipboard API 复制失败:', err);
                fallbackCopyDeployScript(content);
            });
    } else {
        fallbackCopyDeployScript(content);
    }
}

// 复制脚本的降级方案：通过临时 textarea + execCommand 兼容旧浏览器
function fallbackCopyDeployScript(text) {
    const temp = document.createElement('textarea');
    temp.value = text;
    temp.style.position = 'fixed';
    temp.style.left = '-9999px';
    temp.style.top = '-9999px';
    document.body.appendChild(temp);
    temp.select();
    try {
        const ok = document.execCommand('copy');
        if (typeof Message !== 'undefined') {
            if (ok) {
                Message.success('部署脚本已复制到剪贴板');
            } else {
                Message.warning('复制可能未成功，请手动选择并复制');
            }
        }
    } catch (e) {
        console.error('[fallbackCopyDeployScript] execCommand 复制失败:', e);
        if (typeof Message !== 'undefined') {
            Message.error('浏览器不支持自动复制，请手动选择并复制');
        }
    } finally {
        document.body.removeChild(temp);
    }
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
                // 加载服务器 URL（用于 YML authurl 替换）
                if (config.serverUrl != null) {
                    const serverUrlEl = document.getElementById('serverUrl');
                    if (serverUrlEl) {
                        serverUrlEl.value = config.serverUrl;
                    }
                    deployConfig.serverUrl = config.serverUrl;
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
                            // 后端模型用 host 字段存 IP（前端传 ip 会被 @JsonAlias 映射），这里同时兼容 ip / host / 连接串三种来源
                            const ip = db.ip || db.host || parseIpFromConn(db.connectionString);
                            if (ip) {
                                commonIp = ip;
                                deployConfig.databases.unified.ip = ip;
                                deployConfig.databases.unified.connectionString = `jdbc:dm://${ip}`;
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
                            const ip = db.ip || db.host || parseIpFromConn(db.connectionString);
                            if (ip) {
                                // 如果尚未确定统一IP，则以干部库IP作为统一IP（兼容旧数据）
                                if (!commonIp) {
                                    commonIp = ip;
                                }
                                deployConfig.databases.cadre.ip = ip;
                                deployConfig.databases.cadre.connectionString = `jdbc:dm://${ip}`;
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
        tongWebDeployDir: deployConfig.installDir,
        serverUrl: deployConfig.serverUrl || '',
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

// 自动检测统一支撑 / 干部应用心跳（轮询直到成功或达到上限）
let heartbeatTimer = null;
let heartbeatAttempts = 0;
const HEARTBEAT_INTERVAL_MS = 5000;
const MAX_HEARTBEAT_ATTEMPTS = 60; // 最多轮询约 5 分钟

function startHeartbeatPolling() {
    // 重置轮询状态
    if (heartbeatTimer) {
        clearTimeout(heartbeatTimer);
        heartbeatTimer = null;
    }
    heartbeatAttempts = 0;
    // 先稍作等待，再开始第一次检测
    heartbeatTimer = setTimeout(runHeartbeatCheckOnce, HEARTBEAT_INTERVAL_MS);
}

function runHeartbeatCheckOnce() {
    heartbeatAttempts += 1;
    checkAppHeartbeats().finally(() => {
        // 如果已经达到最大次数，停止轮询
        if (heartbeatAttempts >= MAX_HEARTBEAT_ATTEMPTS) {
            addLog(`心跳检测已达到最大重试次数（${MAX_HEARTBEAT_ATTEMPTS} 次），停止轮询。`, 'warning');
            if (heartbeatTimer) {
                clearTimeout(heartbeatTimer);
                heartbeatTimer = null;
            }
            return;
        }

        // 如果两个服务都已经成功，则停止轮询
        const bar = document.getElementById('serviceStatusBar');
        if (bar && bar.querySelectorAll('.service-status-pill.success').length === 2) {
            addLog('统一支撑与干部应用服务均已启动，停止心跳轮询。', 'success');
            if (heartbeatTimer) {
                clearTimeout(heartbeatTimer);
                heartbeatTimer = null;
            }
            return;
        }

        // 否则继续下一轮检测
        heartbeatTimer = setTimeout(runHeartbeatCheckOnce, HEARTBEAT_INTERVAL_MS);
    });
}

// 单次检测统一支撑 / 干部应用心跳
// 统一支撑：服务器 URL + '/tyzc-api/heartbeat'
// 干部应用：服务器 URL + '/gbgl/heartbeat'
function checkAppHeartbeats() {
    const rawServerUrl = deployConfig.serverUrl || '';
    if (!rawServerUrl) {
        addLog('未配置服务器 URL，跳过服务心跳检测', 'warning');
        updateServiceStatusBar({ unified: 'error', cadre: 'error', message: '未配置服务器 URL' });
        return Promise.resolve();
    }

    const middleware = (deployConfig.middlewareType || '').toLowerCase();

    // 构造基础 URL（带协议和主机），针对 Tomcat / TongWeb 分别处理端口
    // 对于 Tomcat：统一支撑使用 8111 端口，干部应用使用 8222 端口；
    // 对于 TongWeb：沿用用户配置的 serverUrl 端口。
    let unifiedBaseUrl;
    let cadreBaseUrl;

    if (middleware === 'tomcat') {
        unifiedBaseUrl = buildTomcatBaseUrl(rawServerUrl, 8111);
        cadreBaseUrl = buildTomcatBaseUrl(rawServerUrl, 8222);
    } else {
        const normalized = normalizeServerUrl(rawServerUrl);
        unifiedBaseUrl = normalized;
        cadreBaseUrl = normalized;
    }

    const unifiedUrl = unifiedBaseUrl + '/tyzc-api/heartbeat';
    const cadreUrl = cadreBaseUrl + '/gbgl/heartbeat';

    addLog('开始检测统一支撑服务心跳: ' + unifiedUrl, 'info');
    addLog('开始检测干部应用服务心跳: ' + cadreUrl, 'info');

    // 通过后端代理心跳检测，避免浏览器跨域限制
    return fetch('/api/heartbeat/check', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            unifiedUrl: unifiedUrl,
            cadreUrl: cadreUrl
        })
    })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                addLog('心跳检测接口调用失败: ' + (data.message || ''), 'error');
                updateServiceStatusBar({ unified: 'error', cadre: 'error' });
                return;
            }

            const unifiedUp = !!data.unifiedUp;
            const cadreUp = !!data.cadreUp;

            if (data.unifiedMessage) {
                addLog(data.unifiedMessage, unifiedUp ? 'success' : 'error');
            }
            if (data.cadreMessage) {
                addLog(data.cadreMessage, cadreUp ? 'success' : 'error');
            }

            updateServiceStatusBar({
                unified: unifiedUp ? 'success' : 'error',
                cadre: cadreUp ? 'success' : 'error'
            });
        })
        .catch(err => {
            addLog('调用心跳检测接口异常: ' + err.message, 'error');
            updateServiceStatusBar({ unified: 'error', cadre: 'error' });
        });
}

// 规范化服务器 URL：补全协议，去掉末尾多余斜杠，仅保留基础地址（不含路径/查询）
function normalizeServerUrl(raw) {
    let url = (raw || '').trim();
    if (!url) return '';
    if (!/^https?:\/\//i.test(url)) {
        url = 'http://' + url;
    }
    try {
        const u = new URL(url);
        u.pathname = '';
        u.search = '';
        u.hash = '';
        return u.toString().replace(/\/+$/, '');
    } catch (e) {
        // 解析失败时退化为简单处理：去掉末尾斜杠
        return url.replace(/\/+$/, '');
    }
}

// 构造 Tomcat 基础 URL：使用给定端口（例如统一支撑 8111、干部应用 8222）
function buildTomcatBaseUrl(raw, port) {
    let url = (raw || '').trim();
    if (!url) return '';
    if (!/^https?:\/\//i.test(url)) {
        url = 'http://' + url;
    }
    try {
        const u = new URL(url);
        u.port = String(port);
        u.pathname = '';
        u.search = '';
        u.hash = '';
        return u.toString().replace(/\/+$/, '');
    } catch (e) {
        // 解析失败时，简单拼接 host:port
        const withoutProto = url.replace(/^https?:\/\//i, '').replace(/\/+$/, '');
        return 'http://' + withoutProto.split(':')[0] + ':' + String(port);
    }
}

// 更新部署日志标题右侧的服务状态徽标
function updateServiceStatusBar(status) {
    const bar = document.getElementById('serviceStatusBar');
    if (!bar) return;

    const unifiedStatus = status.unified || 'unknown';
    const cadreStatus = status.cadre || 'unknown';

    const unifiedClass = unifiedStatus === 'success' ? 'success' : (unifiedStatus === 'error' ? 'error' : '');
    const cadreClass = cadreStatus === 'success' ? 'success' : (cadreStatus === 'error' ? 'error' : '');

    bar.innerHTML = `
        <span class="service-status-pill ${unifiedClass}">
            <span class="service-status-dot"></span>
            <span>统一支撑</span>
        </span>
        <span class="service-status-pill ${cadreClass}">
            <span class="service-status-dot"></span>
            <span>干部应用</span>
        </span>
    `;
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
