// 模拟数据导入页面脚本
// 说明：与 export.js 风格一致，兼容旧浏览器，不使用可选链等新语法。

var currentFileId = '';
var currentJobId = '';
var pollTimer = null;
var previewSchemas = [];
var previewObjectsBySchema = {};
var ws = null;

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
    var statusEl = qs('uploadStatus');
    var btnPreview = qs('btnPreview');
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
    if (statusEl) statusEl.textContent = '上传中...';
    if (btnPreview) btnPreview.disabled = true;

    fetch('/api/mock-import/upload', {
        method: 'POST',
        body: formData
    })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data && data.success && data.fileId) {
                currentFileId = data.fileId;
                if (statusEl) statusEl.textContent = '已上传: ' + file.name;
                if (btnPreview) btnPreview.disabled = false;
                logLine('上传成功: ' + file.name + ' -> fileId=' + data.fileId);
            } else {
                throw new Error((data && data.message) ? data.message : '上传失败');
            }
        })
        .catch(function(e) {
            console.error(e);
            if (statusEl) statusEl.textContent = '上传失败';
            logLine('上传失败: ' + e.message);
        })
        .finally(function() {
            if (btnPreview) btnPreview.disabled = !currentFileId;
        });
}

// 预览 zip 内容，展示 schema 列表
function previewZip() {
    if (!currentFileId) {
        if (typeof Message !== 'undefined') Message.alert('请先上传 zip');
        else alert('请先上传 zip');
        return;
    }
    var statusEl = qs('previewStatus');
    var schemaSection = qs('schemaSection');
    var schemaBox = qs('schemaBox');
    var btnStart = qs('btnStart');
    if (statusEl) statusEl.textContent = '预览中...';

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
            var totalObj = 0;
            for (var s in previewObjectsBySchema) {
                if (previewObjectsBySchema.hasOwnProperty(s)) {
                    totalObj += (previewObjectsBySchema[s] || []).length;
                }
            }
            if (statusEl) statusEl.textContent = '共 ' + previewSchemas.length + ' 个 Schema，' + totalObj + ' 个对象';
            if (schemaSection) schemaSection.style.display = 'block';
            if (schemaBox) {
                var html = [];
                for (var i = 0; i < previewSchemas.length; i++) {
                    var s = previewSchemas[i];
                    html.push('<label><input type="checkbox" data-schema="' + escapeHtml(s) + '" checked /> ' + escapeHtml(s) + '</label>');
                }
                schemaBox.innerHTML = html.join('');
            }
            if (btnStart) btnStart.disabled = false;
            logLine('预览成功: ' + previewSchemas.length + ' schemas, ' + totalObj + ' objects');
        })
        .catch(function(e) {
            console.error(e);
            if (statusEl) statusEl.textContent = '预览失败';
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

(function init() {
    setJobPill('');
    setStatusText('未开始');
})();
