// 模拟数据导入页面脚本
// 说明：与 export.js 风格一致，兼容旧浏览器，不使用可选链等新语法。

var currentFileId = '';
var currentJobId = '';
var pollTimer = null;
var previewSchemas = [];
var previewObjectsBySchema = {};
var cachedObjects = []; // 扁平对象列表：{schema,name,type}
var ws = null;
var lastLoadedReportJobId = '';

function qs(id) {
    return document.getElementById(id);
}

function logLine(text) {
    var box = qs('logBox');
    if (!box) return;
    box.textContent += text + '\n';
    box.scrollTop = box.scrollHeight;
}

function clearLog() {
    var box = qs('logBox');
    if (box) box.textContent = '';
}

// 复制右侧日志区域内容：交互类似代码块“点击复制”
function copyLogToClipboard() {
    var box = qs('logBox');
    var content = box ? (box.textContent || '') : '';
    if (!content || content.replace(/^\s+|\s+$/g, '') === '') {
        if (typeof Message !== 'undefined' && Message.warning) Message.warning('日志为空，无法复制');
        else alert('日志为空，无法复制');
        return;
    }

    // 首选 Clipboard API（新浏览器）
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(content)
            .then(function () {
                if (typeof Message !== 'undefined' && Message.success) Message.success('复制成功');
                else alert('复制成功');
            })
            .catch(function () {
                // 回退：兼容旧浏览器/权限受限场景
                fallbackCopyText(content);
            });
        return;
    }

    // 回退：兼容旧浏览器/不支持 Clipboard API 场景
    fallbackCopyText(content);
}

function fallbackCopyText(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    ta.style.top = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();

    var ok = false;
    try {
        ok = document.execCommand && document.execCommand('copy');
    } catch (e) {
        ok = false;
    }

    document.body.removeChild(ta);
    if (ok) {
        if (typeof Message !== 'undefined' && Message.success) Message.success('复制成功');
        else alert('复制成功');
    } else {
        if (typeof Message !== 'undefined' && Message.warning) Message.warning('复制失败，请手动选择复制');
        else alert('复制失败，请手动选择复制');
    }
}

function setStatusText(text) {
    var el = qs('statusText');
    if (el) el.textContent = '状态：' + text;
}

function setStatusFromJob(job) {
    var el = qs('statusText');
    if (!el || !job) return;
    var st = job.status || 'PENDING';
    var msg = job.message || '';
    var label = '准备中';
    var cls = 'status-pending';
    if (st === 'SUCCESS') {
        label = '成功';
        cls = 'status-ok';
    } else if (st === 'FAILED') {
        label = '失败';
        cls = 'status-fail';
    } else if (st === 'RUNNING') {
        label = '进行中';
        cls = 'status-running';
    }
    el.className = 'muted ' + cls;
    el.textContent = '状态：' + label + (msg ? (' - ' + msg) : '');
}

function setJobPill(jobId) {
    var el = qs('pillJob');
    if (el) el.textContent = 'job: ' + (jobId || '-');
}

function setProgress(total, completed) {
    var bar = qs('progressBar');
    var txt = qs('progressText');
    var percent = 0;
    if (total && total > 0) {
        percent = Math.floor((completed / total) * 100);
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
    }
    if (bar) bar.style.width = percent + '%';
    if (txt) txt.textContent = percent + '%';
}

function setProgressDetail(job, isFinal) {
    var el = qs('progressDetail');
    if (!el || !job) return;
    var started = job.startedAt || 0;
    var finished = job.finishedAt || 0;
    var now = Date.now();
    var elapsedMs = (finished > 0 ? finished : now) - started;
    if (elapsedMs < 0) elapsedMs = 0;
    var seconds = Math.floor(elapsedMs / 1000);
    var timeText = seconds < 60 ? seconds + ' 秒' : Math.floor(seconds / 60) + ' 分 ' + (seconds % 60) + ' 秒';
    if (isFinal && job.summary) {
        el.textContent = '耗时约 ' + timeText + '，' + (job.summary || '');
    } else {
        el.textContent = seconds > 0 ? '已用时约 ' + timeText + '，正在导入...' : '准备中...';
    }
}

function getConnection() {
    return {
        jdbcUrl: (qs('jdbcUrl') && qs('jdbcUrl').value) ? qs('jdbcUrl').value.trim() : '',
        username: (qs('dbUsername') && qs('dbUsername').value) ? qs('dbUsername').value.trim() : '',
        password: (qs('dbPassword') && qs('dbPassword').value) ? qs('dbPassword').value : ''
    };
}

function validateConn(conn) {
    if (!conn.jdbcUrl) return '请填写 JDBC 连接串';
    if (!conn.username) return '请填写用户名';
    if (!conn.password) return '请填写密码';
    return '';
}

// 上传 zip，获得 fileId
function uploadZip() {
    var fileInput = qs('zipFile');
    var uploadStatusEl = qs('uploadStatus');
    if (!fileInput || !fileInput.files || !fileInput.files.length) {
        if (typeof Message !== 'undefined') Message.alert('请选择要上传的 zip 文件');
        else alert('请选择要上传的 zip 文件');
        return;
    }
    var file = fileInput.files[0];
    if (!file.name || file.name.toLowerCase().indexOf('.zip') === -1) {
        if (typeof Message !== 'undefined') Message.alert('仅支持 .zip 文件');
        else alert('仅支持 .zip 文件');
        return;
    }
    var formData = new FormData();
    formData.append('file', file);
    if (uploadStatusEl) uploadStatusEl.textContent = '上传中...';

    fetch('/api/mock-import/upload', {
        method: 'POST',
        body: formData
    })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data && data.success && data.fileId) {
                currentFileId = data.fileId;
                if (uploadStatusEl) uploadStatusEl.textContent = '已上传: ' + file.name;
                logLine('上传成功: ' + file.name + ' -> fileId=' + data.fileId);
                // 上传成功后自动预览
                previewZip();
            } else {
                throw new Error((data && data.message) ? data.message : '上传失败');
            }
        })
        .catch(function(e) {
            console.error(e);
            if (uploadStatusEl) uploadStatusEl.textContent = '上传失败';
            logLine('上传失败: ' + e.message);
        });
}

// 预览 zip 内容，展示 schema 列表
function previewZip() {
    if (!currentFileId) {
        if (typeof Message !== 'undefined') Message.alert('请先上传 zip');
        else alert('请先上传 zip');
        return;
    }
    var schemaSection = qs('schemaSection');
    var schemaBox = qs('schemaBox');
    var btnStart = qs('btnStart');
    var importSummary = qs('importSummary');

    fetch('/api/mock-import/preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileId: currentFileId })
    })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || !data.success) {
                throw new Error((data && data.message) ? data.message : '预览失败');
            }
            previewSchemas = data.schemas || [];
            previewObjectsBySchema = data.objectsBySchema || {};
            
            // 扁平化对象列表用于清单显示
            cachedObjects = [];
            for (var s in previewObjectsBySchema) {
                if (previewObjectsBySchema.hasOwnProperty(s)) {
                    var objs = previewObjectsBySchema[s] || [];
                    for (var i = 0; i < objs.length; i++) {
                        var o = objs[i];
                        if (o && o.schema && o.name && o.type) {
                            cachedObjects.push({ 
                                schema: o.schema, 
                                name: o.name, 
                                type: o.type,
                                comment: o.comment || ''  // 添加注释字段
                            });
                        }
                    }
                }
            }
            
            var totalObj = cachedObjects.length;
            
            // 显示导入简要描述
            if (importSummary) {
                importSummary.innerHTML = '<strong>导入包概览：</strong>包含 ' + previewSchemas.length + ' 个 Schema，' + totalObj + ' 个对象。<br>' +
                    '<span style="color:#64748b;font-size:11px;">勾选下方 Schema 可动态筛选对象列表。</span>';
            }
            
            if (schemaSection) schemaSection.style.display = 'block';
            if (schemaBox) {
                var html = [];
                html.push('<div style="display:grid;grid-template-columns:1fr 1fr;gap:4px;">');
                for (var i = 0; i < previewSchemas.length; i++) {
                    var s = previewSchemas[i];
                    html.push(
                        '<label class="check" style="background:#ffffff;">' +
                        '<input type="checkbox" data-schema="' + escapeHtml(s) + '" checked onchange="onSchemaChange()" />' +
                        '<div><div class="check-title">' + escapeHtml(s) + '</div></div>' +
                        '</label>'
                    );
                }
                html.push('</div>');
                schemaBox.innerHTML = html.join('');
            }
            
            // 初始渲染对象清单（全部Schema）
            renderObjectsBySelectedSchemas();
            
            if (btnStart) btnStart.disabled = false;
            logLine('预览成功: ' + previewSchemas.length + ' schemas, ' + totalObj + ' objects');
        })
        .catch(function(e) {
            console.error(e);
            logLine('预览失败: ' + e.message);
        });
}

function getSelectedSchemas() {
    var box = qs('schemaBox');
    if (!box) return [];
    var inputs = box.querySelectorAll('input[type="checkbox"][data-schema]:checked');
    var sel = [];
    for (var i = 0; i < inputs.length; i++) {
        var s = inputs[i].getAttribute('data-schema');
        if (s) sel.push(s);
    }
    return sel;
}

function getDdlMode() {
    var radios = document.querySelectorAll('input[name="ddlMode"]:checked');
    return (radios && radios[0]) ? radios[0].value : 'STRUCTURE_AND_DATA';
}

function getWhenExists() {
    var radios = document.querySelectorAll('input[name="whenExists"]:checked');
    return (radios && radios[0]) ? radios[0].value : 'SKIP';
}

function escapeHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function startImport() {
    var conn = getConnection();
    var err = validateConn(conn);
    if (err) {
        if (typeof Message !== 'undefined') Message.alert(err);
        else alert(err);
        return;
    }
    if (!currentFileId) {
        if (typeof Message !== 'undefined') Message.alert('请先上传并预览 zip');
        else alert('请先上传并预览 zip');
        return;
    }
    var schemas = getSelectedSchemas();
    if (!schemas.length) {
        if (typeof Message !== 'undefined') Message.alert('请至少选择一个 Schema');
        else alert('请至少选择一个 Schema');
        return;
    }

    var btnStart = qs('btnStart');
    if (btnStart) btnStart.disabled = true;
    setStatusText('启动导入...');
    logLine('开始导入: fileId=' + currentFileId + ' schemas=' + schemas.join(','));

    var payload = {
        connection: conn,
        fileId: currentFileId,
        options: {
            schemas: schemas,
            ddlMode: getDdlMode(),
            whenExists: getWhenExists()
        }
    };

    fetch('/api/mock-import/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data && data.success && data.jobId) {
                currentJobId = data.jobId;
                setJobPill(currentJobId);
                setStatusText('导入中（jobId=' + currentJobId + '）');
                logLine('任务已启动 jobId=' + currentJobId);
                setProgress(0, 0);
                startPolling();
                connectLogWebSocket();
            } else {
                throw new Error((data && data.message) ? data.message : '启动失败');
            }
        })
        .catch(function(e) {
            console.error(e);
            setStatusText('启动失败');
            logLine('启动失败: ' + e.message);
            if (btnStart) btnStart.disabled = false;
        });
}

function startPolling() {
    stopPolling();
    pollTimer = setInterval(pollStatusOnce, 2000);
}

function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

function pollStatusOnce() {
    if (!currentJobId) return;
    fetch('/api/mock-import/status/' + encodeURIComponent(currentJobId))
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || !data.success || !data.job) return;
            var job = data.job;
            var st = job.status || 'unknown';
            setStatusFromJob(job);
            var total = job.totalObjects || 0;
            var completed = job.completedObjects || 0;
            setProgress(total, completed);
            setProgressDetail(job, st === 'SUCCESS' || st === 'FAILED');
            var btnStart = qs('btnStart');
            if (st === 'SUCCESS' || st === 'FAILED') {
                stopPolling();
                if (btnStart) btnStart.disabled = false;
                logLine(st === 'SUCCESS' ? ('导入完成: ' + (job.summary || '')) : ('导入失败: ' + (job.message || '')));
                // 说明：任务结束后自动加载导入报告，展示成功/失败清单，并提供下载入口
                loadImportReport();
            }
        })
        .catch(function(e) { console.error(e); });
}

function connectLogWebSocket() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    try {
        var protocol = (location.protocol === 'https:') ? 'wss:' : 'ws:';
        ws = new WebSocket(protocol + '//' + location.host + '/ws/deploy-log');
        ws.onopen = function() { logLine('日志通道已连接'); };
        ws.onmessage = function(evt) {
            if (evt && evt.data) logLine(String(evt.data));
        };
        ws.onerror = function() { logLine('日志通道异常'); };
    } catch (e) { console.error(e); }
}

function escapeHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}



function onSchemaChange() {
    renderObjectsBySelectedSchemas();
}

function getSelectedSchemasFromBox() {
    var box = qs('schemaBox');
    if (!box) return [];
    var inputs = box.querySelectorAll('input[type="checkbox"][data-schema]:checked');
    var sel = [];
    for (var i = 0; i < inputs.length; i++) {
        var s = inputs[i].getAttribute('data-schema');
        if (s) sel.push(s);
    }
    return sel;
}

function renderObjectsBySelectedSchemas() {
    var selectedSchemas = getSelectedSchemasFromBox();
    var filtered = cachedObjects.filter(function(o) {
        return selectedSchemas.indexOf(o.schema) >= 0;
    });
    renderObjects(filtered);
}

function renderObjects(objects) {
    var box = qs('objectBox');
    if (!box) return;
    if (!objects || !objects.length) {
        box.innerHTML = '<div class="muted">暂无对象（请勾选 Schema）</div>';
        return;
    }

    var html = [];
    // 按 schema 分组展示
    var group = {};
    for (var i = 0; i < objects.length; i++) {
        var o = objects[i];
        if (!group[o.schema]) group[o.schema] = [];
        group[o.schema].push(o);
    }

        Object.keys(group).forEach(function(schema) {
            html.push('<div style="margin:8px 0 6px;font-weight:800;font-size:12px;color:#0f172a;">' + escapeHtml(schema) + '</div>');
            var arr = group[schema];
            for (var j = 0; j < arr.length; j++) {
                var o = arr[j];
                var typeUpper = String(o.type || '').toUpperCase();
                var isTable = typeUpper === 'TABLE';
                var badgeType = '<span class="badge badge-strong">' + escapeHtml(typeUpper) + '</span>';
                var badgeRule = isTable ? '<span class="badge badge-warn">DDL + XML</span>' : '<span class="badge">仅 DDL</span>';
                
                // 显示对象名称，如果有注释则在名称后添加注释
                var nameHtml = escapeHtml(String(o.name));
                var comment = o.comment || '';
                if (comment && comment.trim() !== '') {
                    nameHtml += ' <span class="comment">(' + escapeHtml(comment) + ')</span>';
                }

                html.push('<div class="obj-item">');
                html.push('<div class="obj-main">');
                html.push('<div class="obj-title">' + nameHtml + '</div>');
                html.push('<div class="obj-meta">' + badgeType + badgeRule + '</div>');
                html.push('</div>');
                html.push('</div>');
            }
        });

    box.innerHTML = html.join('');
}

// 下载导入报告（JSON/TXT）
// 说明：任务结束后可下载 import-report.json 或 import-report.txt，便于离线排查。
function downloadImportReport(format) {
    if (!currentJobId) {
        logLine('无法下载报告：jobId 为空');
        return;
    }
    var fmt = (format === 'txt') ? 'txt' : 'json';
    window.location.href = '/api/mock-import/report-download/' + encodeURIComponent(currentJobId) + '?format=' + encodeURIComponent(fmt);
}

// 加载并渲染导入报告
// 说明：从后端读取 import-report.json，并在页面上展示失败明细清单。
function loadImportReport() {
    if (!currentJobId) return;
    fetch('/api/mock-import/report/' + encodeURIComponent(currentJobId))
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || !data.success || !data.report) {
                var msg = (data && data.message) ? data.message : '读取导入报告失败';
                renderImportReport(null, msg);
                return;
            }
            lastLoadedReportJobId = currentJobId;
            renderImportReport(data.report, '');
        })
        .catch(function(e) {
            console.error(e);
            renderImportReport(null, e.message);
        });
}

function renderImportReport(report, errMsg) {
    var box = qs('resultBox');
    var pill = qs('resultJobPill');
    var summary = qs('resultSummary');
    var failList = qs('failList');
    var btnJson = qs('btnDownloadJson');
    var btnTxt = qs('btnDownloadTxt');
    if (pill) pill.textContent = 'job: ' + (currentJobId || '-');
    if (!box) return;
    box.style.display = 'block';
    if (btnJson) btnJson.disabled = !currentJobId;
    if (btnTxt) btnTxt.disabled = !currentJobId;

    if (!report) {
        if (summary) summary.textContent = errMsg ? ('读取报告失败：' + errMsg) : '暂无导入报告（任务可能未完成）';
        if (failList) failList.innerHTML = '<div class="muted">无失败明细</div>';
        return;
    }

    var ddlSuccess = report.ddlSuccess || 0;
    var ddlFailed = report.ddlFailed || 0;
    var dataSuccess = report.dataSuccess || 0;
    var dataFailed = report.dataFailed || 0;
    var totalObjects = report.totalObjects || 0;
    if (summary) {
        summary.innerHTML = '对象总数：<strong>' + totalObjects + '</strong>；DDL 成功/失败：<strong>' + ddlSuccess + '</strong>/<strong>' + ddlFailed + '</strong>；数据 成功/失败：<strong>' + dataSuccess + '</strong>/<strong>' + dataFailed + '</strong>';
    }

    var objects = report.objects || [];
    var fails = [];
    for (var i = 0; i < objects.length; i++) {
        var o = objects[i] || {};
        var ddlOk = !!o.ddlOk;
        var dataOk = !!o.dataOk;
        var type = String(o.type || '');
        var ddlErr = String(o.ddlError || '');
        var dataErr = String(o.dataError || '');
        // 说明：展示 DDL 或数据有失败/错误信息的对象
        if (!ddlOk || ddlErr || (!dataOk && (type.toUpperCase() === 'TABLE') && dataErr)) {
            fails.push(o);
        }
    }

    if (failList) {
        if (!fails.length) {
            failList.innerHTML = '<div class="muted">全部对象导入成功（无失败明细）</div>';
        } else {
            var max = 80;
            var html = [];
            html.push('<div style="font-weight:800;font-size:12px;color:#0f172a;">失败明细（展示前 ' + Math.min(max, fails.length) + ' 条）</div>');
            for (var j = 0; j < fails.length && j < max; j++) {
                var f = fails[j] || {};
                var title = escapeHtml(String(f.schema || '') + '.' + String(f.name || '') + ' (' + String(f.type || '') + ')');
                var d1 = f.ddlError ? ('DDL：' + escapeHtml(String(f.ddlError))) : '';
                var d2 = f.dataError ? ('数据：' + escapeHtml(String(f.dataError))) : '';
                html.push('<div class="fail-item"><div><strong>' + title + '</strong></div>');
                if (d1) html.push('<div class="muted">' + d1 + '</div>');
                if (d2) html.push('<div class="muted">' + d2 + '</div>');
                html.push('</div>');
            }
            if (fails.length > max) {
                html.push('<div class="muted">还有 ' + (fails.length - max) + ' 条失败未展示，请下载报告查看完整清单。</div>');
            }
            failList.innerHTML = html.join('');
        }
    }
}

(function init() {
    setJobPill('');
    setStatusText('未开始');
})();
