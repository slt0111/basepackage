// 模拟数据导出页面脚本
// 说明：保持与 deploy.js 一致的兼容性要求，避免使用可选链等新语法，兼容旧浏览器（如 Firefox 68）。

let currentJobId = '';
let pollTimer = null;
let cachedSchemas = [];
let cachedObjects = []; // 扁平对象列表：{schema,name,type,comment}
// 说明：selectedObjectKeys 记录“用户手动修改过选择状态”的对象 key（schema::type::name），用于在切换过滤条件时保留勾选/取消勾选结果。
let selectedObjectKeys = {};

function qs(id) {
    return document.getElementById(id);
}

function logLine(text) {
    const box = qs('logBox');
    if (!box) return;
    // 说明：按用户要求隐藏时间戳，仅输出纯文本日志内容。
    box.textContent += text + '\n';
    box.scrollTop = box.scrollHeight;
}

function clearLog() {
    const box = qs('logBox');
    if (box) box.textContent = '';
}

// 复制右侧日志区域内容：交互类似代码块“点击复制”
function copyLogToClipboard() {
    const box = qs('logBox');
    const content = box ? (box.textContent || '') : '';
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
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    ta.style.top = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();

    let ok = false;
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
    const el = qs('statusText');
    if (el) el.textContent = '状态：' + text;
}

// 根据后端任务状态设置“状态：成功/失败/进行中”并带颜色
function setStatusFromJob(job) {
    const el = qs('statusText');
    if (!el || !job) return;
    const st = job.status || 'PENDING';
    const msg = job.message || '';
    let label = '准备中';
    let cls = 'status-pending';
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
    const el = qs('pillJob');
    if (el) el.textContent = 'job: ' + (jobId || '-');
}

function setProgress(total, completed) {
    const bar = qs('progressBar');
    const txt = qs('progressText');
    let percent = 0;
    if (total && total > 0) {
        percent = Math.floor((completed / total) * 100);
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
    }
    if (bar) {
        bar.style.width = percent + '%';
    }
    if (txt) {
        txt.textContent = percent + '%';
    }
}

function setProgressDetail(job, isFinal) {
    const el = qs('progressDetail');
    if (!el || !job) return;

    const started = job.startedAt || 0;
    const finished = job.finishedAt || 0;
    const now = Date.now();
    let elapsedMs = 0;
    if (started > 0) {
        elapsedMs = (finished > 0 ? finished : now) - started;
        if (elapsedMs < 0) elapsedMs = 0;
    }
    const seconds = Math.floor(elapsedMs / 1000);
    const timeText = formatDuration(seconds);

    // 文件大小与摘要仅在任务完成后展示
    if (isFinal && job.zipReady) {
        const sizeBytes = job.zipSizeBytes || 0;
        const summary = job.summary || '';
        el.textContent = '耗时约 ' + timeText + '，文件大小 ' + formatSize(sizeBytes) + (summary ? '，' + summary : '');
    } else {
        if (seconds > 0) {
            el.textContent = '已用时约 ' + timeText + '，正在导出...';
        } else {
            el.textContent = '准备中...';
        }
    }
}

function formatSize(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const kb = 1024;
    const mb = kb * 1024;
    const gb = mb * 1024;
    if (bytes >= gb) {
        return (bytes / gb).toFixed(2) + ' GB';
    }
    if (bytes >= mb) {
        return (bytes / mb).toFixed(2) + ' MB';
    }
    if (bytes >= kb) {
        return (bytes / kb).toFixed(2) + ' KB';
    }
    return bytes + ' B';
}

function formatDuration(seconds) {
    if (!seconds || seconds <= 0) return '0 秒';
    if (seconds < 60) return seconds + ' 秒';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    if (s === 0) return m + ' 分钟';
    return m + ' 分 ' + s + ' 秒';
}

function getConnection() {
    const jdbcUrlEl = qs('jdbcUrl');
    const userEl = qs('dbUsername');
    const pwdEl = qs('dbPassword');
    return {
        jdbcUrl: jdbcUrlEl ? String(jdbcUrlEl.value || '').trim() : '',
        username: userEl ? String(userEl.value || '').trim() : '',
        password: pwdEl ? String(pwdEl.value || '') : ''
    };
}

function validateConn(conn) {
    if (!conn.jdbcUrl) return '请填写 JDBC 连接串';
    if (!conn.username) return '请填写 DBA 用户名';
    if (!conn.password) return '请填写 DBA 密码';
    return '';
}

function loadSchemas() {
    const conn = getConnection();
    const err = validateConn(conn);
    if (err) {
        if (typeof Message !== 'undefined') Message.alert(err);
        else alert(err);
        return;
    }

    const btnLoadSchemas = qs('btnLoadSchemas');
    const btnLoadObjects = qs('btnLoadObjects');
    const btnStart = qs('btnStart');
    if (btnLoadSchemas) btnLoadSchemas.disabled = true;
    if (btnLoadObjects) btnLoadObjects.disabled = true;
    if (btnStart) btnStart.disabled = true;

    cachedSchemas = [];
    cachedObjects = [];
    // 说明：重载对象清单时清空本地选择状态映射，避免历史选择污染新一轮导出。
    selectedObjectKeys = {};
    renderSchemas([]);
    renderObjects([]);
    setStatusText('连接中，加载 schema 列表...');

    fetch('/api/mock-export/dm/schemas', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(conn)
    })
        .then(r => r.json())
        .then(data => {
            if (data && data.success && data.schemas) {
                cachedSchemas = data.schemas;
                renderSchemas(cachedSchemas);
                setStatusText('已加载 schema 列表');
                logLine('已加载 schema 数量: ' + cachedSchemas.length);
                if (btnLoadObjects) btnLoadObjects.disabled = false;
            } else {
                throw new Error((data && data.message) ? data.message : '获取 schema 失败');
            }
        })
        .catch(e => {
            console.error(e);
            setStatusText('加载 schema 失败');
            logLine('加载 schema 失败: ' + e.message);
        })
        .finally(() => {
            if (btnLoadSchemas) btnLoadSchemas.disabled = false;
        });
}

function getSelectedSchemas() {
    const box = qs('schemaBox');
    if (!box) return [];
    const inputs = box.querySelectorAll('input[type="checkbox"][data-schema]');
    const sel = [];
    for (let i = 0; i < inputs.length; i++) {
        const cb = inputs[i];
        if (cb && cb.checked) {
            sel.push(cb.getAttribute('data-schema'));
        }
    }
    return sel;
}

function loadObjects() {
    const conn = getConnection();
    const err = validateConn(conn);
    if (err) {
        if (typeof Message !== 'undefined') Message.alert(err);
        else alert(err);
        return;
    }
    const schemas = getSelectedSchemas();
    if (!schemas.length) {
        if (typeof Message !== 'undefined') Message.alert('请至少选择一个用户模式（Schema）');
        else alert('请至少选择一个用户模式（Schema）');
        return;
    }

    const btnLoadObjects = qs('btnLoadObjects');
    const btnStart = qs('btnStart');
    if (btnLoadObjects) btnLoadObjects.disabled = true;
    if (btnStart) btnStart.disabled = true;

    cachedObjects = [];
    renderObjects([]);
    setStatusText('加载对象清单中...');
    logLine('加载对象清单 schemas=' + schemas.join(','));

    fetch('/api/mock-export/dm/objects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ jdbcUrl: conn.jdbcUrl, username: conn.username, password: conn.password, schemas })
    })
        .then(r => r.json())
        .then(data => {
            if (!data || !data.success || !data.objectsBySchema) {
                throw new Error((data && data.message) ? data.message : '获取对象清单失败');
            }
            const bySchema = data.objectsBySchema;
            const flat = [];
            Object.keys(bySchema).forEach(schema => {
                const arr = bySchema[schema] || [];
                for (let i = 0; i < arr.length; i++) {
                    const o = arr[i];
                    if (o && o.schema && o.name && o.type) {
                        // 说明：透传后端返回的对象注释字段 comment，方便前端在名称后拼接显示。
                        flat.push({ schema: o.schema, name: o.name, type: o.type, comment: o.comment });
                    }
                }
            });
            cachedObjects = flat;
            syncObjectSchemaFilterOptions();
            renderObjectsFiltered();
            setStatusText('对象清单已加载');
            logLine('对象清单条目数: ' + cachedObjects.length);
            if (btnStart) btnStart.disabled = cachedObjects.length === 0;
        })
        .catch(e => {
            console.error(e);
            setStatusText('加载对象清单失败');
            logLine('加载对象清单失败: ' + e.message);
        })
        .finally(() => {
            if (btnLoadObjects) btnLoadObjects.disabled = false;
        });
}

function renderSchemas(schemas) {
    const box = qs('schemaBox');
    if (!box) return;
    if (!schemas || !schemas.length) {
        box.innerHTML = '<div class="muted">未加载</div>';
        return;
    }
    const html = [];
    html.push('<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:8px;">');
    html.push('<button type="button" class="btn btn-secondary" onclick="toggleAllSchemas(true)">全选</button>');
    html.push('<button type="button" class="btn btn-secondary" onclick="toggleAllSchemas(false)">全不选</button>');
    html.push('</div>');
    html.push('<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;">');
    for (let i = 0; i < schemas.length; i++) {
        const s = schemas[i];
        html.push(
            '<label class="check" style="background:#ffffff;">' +
            '<input type="checkbox" data-schema="' + escapeHtml(s) + '" checked />' +
            '<div><div class="check-title">' + escapeHtml(s) + '</div><div class="check-desc">用户模式</div></div>' +
            '</label>'
        );
    }
    html.push('</div>');
    box.innerHTML = html.join('');
}

function toggleAllSchemas(checked) {
    const box = qs('schemaBox');
    if (!box) return;
    const inputs = box.querySelectorAll('input[type="checkbox"][data-schema]');
    for (let i = 0; i < inputs.length; i++) {
        inputs[i].checked = !!checked;
    }
}

function syncObjectSchemaFilterOptions() {
    const sel = qs('objSchemaFilter');
    if (!sel) return;
    const old = sel.value || '';
    const schemas = {};
    for (let i = 0; i < cachedObjects.length; i++) {
        const o = cachedObjects[i];
        if (o && o.schema) schemas[o.schema] = true;
    }
    const keys = Object.keys(schemas).sort();
    sel.innerHTML = '<option value="">全部模式</option>' + keys.map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join('');
    // 尝试保留旧选择
    if (old) sel.value = old;
}

function getObjectFilters() {
    const searchEl = qs('objSearch');
    const typeEl = qs('objTypeFilter');
    const schemaEl = qs('objSchemaFilter');
    return {
        q: searchEl ? String(searchEl.value || '').trim().toLowerCase() : '',
        type: typeEl ? String(typeEl.value || '').trim().toUpperCase() : '',
        schema: schemaEl ? String(schemaEl.value || '').trim() : ''
    };
}

function filterObjects(list) {
    const f = getObjectFilters();
    const out = [];
    for (let i = 0; i < list.length; i++) {
        const o = list[i];
        if (!o) continue;
        if (f.schema && o.schema !== f.schema) continue;
        if (f.type && String(o.type || '').toUpperCase() !== f.type) continue;
        if (f.q) {
            // 说明：搜索支持按名称和注释匹配，提升对象查找体验。
            const name = String(o.name || '').toLowerCase();
            const comment = String(o.comment || '').toLowerCase();
            if (name.indexOf(f.q) < 0 && comment.indexOf(f.q) < 0) continue;
        }
        out.push(o);
    }
    return out;
}

function renderObjectsFiltered() {
    const filtered = filterObjects(cachedObjects);
    renderObjects(filtered);
    // 更新“开始导出”按钮状态：至少有一个对象可选
    const btnStart = qs('btnStart');
    if (btnStart) {
        btnStart.disabled = filtered.length === 0;
    }
}

function renderObjects(objects) {
    const box = qs('objectBox');
    if (!box) return;
    if (!objects || !objects.length) {
        box.innerHTML = '<div class="muted">未加载</div>';
        return;
    }

    const html = [];
    // 按 schema 分组展示（说明：列表长时按模式分组更易定位）
    const group = {};
    for (let i = 0; i < objects.length; i++) {
        const o = objects[i];
        if (!group[o.schema]) group[o.schema] = [];
        group[o.schema].push(o);
    }

    Object.keys(group).forEach(schema => {
        html.push('<div style="margin:8px 0 6px;font-weight:800;font-size:12px;color:#0f172a;">' + escapeHtml(schema) + '</div>');
        const arr = group[schema];
        for (let j = 0; j < arr.length; j++) {
            const o = arr[j];
            const key = schema + '::' + o.type + '::' + o.name;
            const typeUpper = String(o.type || '').toUpperCase();
            const isTable = typeUpper === 'TABLE';
            const badgeType = '<span class="badge badge-strong">' + escapeHtml(typeUpper) + '</span>';
            const badgeMode = '<span class="badge">' + escapeHtml(schema) + '</span>';
            const badgeRule = isTable ? '<span class="badge badge-warn">DDL + XML</span>' : '<span class="badge">仅 DDL</span>';
            // 说明：优先在对象标题后拼接注释文本，展示效果为“名称(注释)”。
            const hasComment = o.comment && String(o.comment).trim() !== '';
            const displayName = hasComment
                ? (String(o.name) + ' (' + String(o.comment) + ')')
                : String(o.name);

            html.push('<div class="obj-item">');
            // 说明：根据 selectedObjectKeys 中记录的状态还原勾选结果；未记录的对象默认选中。
            var selected = Object.prototype.hasOwnProperty.call(selectedObjectKeys, key)
                ? !!selectedObjectKeys[key]
                : true;
            html.push('<input type="checkbox" data-obj="' + escapeHtml(key) + '" ' + (selected ? 'checked ' : '') + 'onchange="onToggleObject(this)" />');
            html.push('<div class="obj-main">');
            html.push('<div class="obj-title">' + escapeHtml(displayName) + '</div>');
            html.push('<div class="obj-meta">' + badgeType + badgeRule + badgeMode + '</div>');
            html.push('</div>');
            html.push('</div>');
        }
    });

    box.innerHTML = html.join('');
}

function startExport() {
    const conn = getConnection();
    const err = validateConn(conn);
    if (err) {
        if (typeof Message !== 'undefined') Message.alert(err);
        else alert(err);
        return;
    }
    const schemas = getSelectedSchemas();
    const objects = getSelectedObjects();

    if (!schemas.length) {
        if (typeof Message !== 'undefined') Message.alert('请至少选择一个用户模式（Schema）');
        else alert('请至少选择一个用户模式（Schema）');
        return;
    }
    if (!objects.length) {
        if (typeof Message !== 'undefined') Message.alert('请至少选择一个导出对象');
        else alert('请至少选择一个导出对象');
        return;
    }

    const btnStart = qs('btnStart');
    const btnDownload = qs('btnDownload');
    if (btnStart) btnStart.disabled = true;
    if (btnDownload) btnDownload.disabled = true;

    setStatusText('启动导出...');
    logLine('开始导出：schemas=' + schemas.join(',') + ' objects=' + objects.length);
    // 启动时重置进度条
    setProgress(objects.length, 0);
    setProgressDetail({ startedAt: Date.now(), finishedAt: 0 }, false);

    fetch('/api/mock-export/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ connection: conn, schemas, objects })
    })
        .then(r => r.json())
        .then(data => {
            if (data && data.success && data.jobId) {
                currentJobId = data.jobId;
                setJobPill(currentJobId);
                setStatusText('导出中（jobId=' + currentJobId + '）');
                logLine('任务已启动 jobId=' + currentJobId);
                startPolling();
                connectLogWebSocket();
            } else {
                throw new Error((data && data.message) ? data.message : '启动失败');
            }
        })
        .catch(err => {
            console.error(err);
            setStatusText('启动失败');
            logLine('启动失败: ' + err.message);
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
    fetch('/api/mock-export/status/' + encodeURIComponent(currentJobId))
        .then(r => r.json())
        .then(data => {
            if (!data || !data.success || !data.job) return;
            const job = data.job;
            const st = job.status || 'unknown';
            setStatusFromJob(job);

            // 根据后端返回的 totalObjects / completedObjects 更新进度条
            const total = job.totalObjects || 0;
            const completed = job.completedObjects || 0;
            setProgress(total, completed);
            setProgressDetail(job, st === 'SUCCESS' || st === 'FAILED');

            const btnStart = qs('btnStart');
            const btnDownload = qs('btnDownload');

            if (st === 'SUCCESS' && job.zipReady) {
                if (btnStart) btnStart.disabled = false;
                if (btnDownload) btnDownload.disabled = false;
                stopPolling();
                logLine('导出完成：' + (job.zipFileName || 'zip'));
            } else if (st === 'FAILED') {
                if (btnStart) btnStart.disabled = false;
                if (btnDownload) btnDownload.disabled = true;
                stopPolling();
                logLine('导出失败：' + msg);
            }
        })
        .catch(err => {
            console.error(err);
        });
}

function downloadZip() {
    if (!currentJobId) return;
    // 说明：直接跳转下载接口，浏览器会触发文件下载
    window.location.href = '/api/mock-export/download/' + encodeURIComponent(currentJobId);
}

function getSelectedObjects() {
    // 说明：从 cachedObjects + selectedObjectKeys 还原“全局选择结果”，避免因过滤导致未渲染对象丢失勾选状态。
    const selected = [];
    for (let i = 0; i < cachedObjects.length; i++) {
        const o = cachedObjects[i];
        if (!o) continue;
        const key = o.schema + '::' + o.type + '::' + o.name;
        const hasKey = Object.prototype.hasOwnProperty.call(selectedObjectKeys, key);
        const isSelected = hasKey ? !!selectedObjectKeys[key] : true; // 未显式修改过的对象默认选中
        if (isSelected) {
            selected.push({ schema: o.schema, type: o.type, name: o.name, comment: o.comment });
        }
    }
    return selected;
}

function toggleAllObjects(checked) {
    const box = qs('objectBox');
    if (!box) return;
    // 说明：只操作当前过滤后渲染出来的对象（即“当前过滤结果”）
    const inputs = box.querySelectorAll('input[type="checkbox"][data-obj]');
    for (let i = 0; i < inputs.length; i++) {
        // 说明：批量操作同时更新 DOM 和 selectedObjectKeys，确保切换过滤条件后选择状态依然准确。
        inputs[i].checked = !!checked;
        onToggleObject(inputs[i]);
    }
}

/**
 * 单个对象勾选状态变更回调
 * 说明：将复选框的勾选结果同步到 selectedObjectKeys；true 表示选中，false 表示取消选中。
 */
function onToggleObject(cb) {
    if (!cb) return;
    const raw = cb.getAttribute('data-obj') || '';
    if (!raw) return;
    if (cb.checked) {
        // 选中：记录为 true，或可视为“显式选中”
        selectedObjectKeys[raw] = true;
    } else {
        // 取消选中：记录为 false（不能直接删除，否则会回退到“默认选中”语义）
        selectedObjectKeys[raw] = false;
    }
}

function escapeHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

let ws = null;
function connectLogWebSocket() {
    // 说明：复用现有部署日志 WebSocket 通道，让导出过程与一键部署一样可见。
    //      服务端会向所有连接广播日志，因此这里不做 jobId 过滤（后续可增强为频道化）。
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    try {
        const protocol = (location.protocol === 'https:') ? 'wss:' : 'ws:';
        ws = new WebSocket(protocol + '//' + location.host + '/ws/deploy-log');
        ws.onopen = function () {
            logLine('日志通道已连接');
        };
        ws.onmessage = function (evt) {
            if (evt && evt.data) {
                // 说明：deploy-log 通道消息通常是纯文本
                logLine(String(evt.data));
            }
        };
        ws.onerror = function () {
            logLine('日志通道连接异常（不影响导出任务执行）');
        };
        ws.onclose = function () {
            // ignore
        };
    } catch (e) {
        console.error(e);
    }
}

// 页面初始化
(function init() {
    setJobPill('');
    setStatusText('未开始');
    renderSchemas([]);
    renderObjects([]);

    // 过滤条件变化时实时刷新对象列表（说明：避免用户每次都点按钮）
    const s = qs('objSearch');
    const t = qs('objTypeFilter');
    const sc = qs('objSchemaFilter');
    if (s) s.addEventListener('input', function () { renderObjectsFiltered(); });
    if (t) t.addEventListener('change', function () { renderObjectsFiltered(); });
    if (sc) sc.addEventListener('change', function () { renderObjectsFiltered(); });
})();

