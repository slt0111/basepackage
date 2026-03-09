/**
 * 公共弹窗组件（类似 Vue Element 的 Message / MessageBox）
 * 提供 alert、confirm、success、error、warning 等统一提示，替代原生 alert/confirm
 */
(function (global) {
    'use strict';

    var container = null;

    function getContainer() {
        if (container) return container;
        container = document.createElement('div');
        container.id = 'message-container';
        container.setAttribute('aria-live', 'polite');
        document.body.appendChild(container);
        return container;
    }

    function addStyles() {
        if (document.getElementById('message-styles')) return;
        var style = document.createElement('style');
        style.id = 'message-styles';
        style.textContent = [
            /* 弹窗容器必须覆盖整个视口，避免被页面布局影响导致跑到左上角 */
            '#message-container { position: fixed; inset: 0; width: 100vw; height: 100vh; z-index: 10000; pointer-events: none; }',
            '#message-container.has-modal { pointer-events: auto; }',
            /* 蒙层负责居中布局，确保弹窗总在屏幕中间 */
            '.message-overlay { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(15,23,42,0.4); z-index: 10001; opacity: 0; transition: opacity 0.2s ease; }',
            '.message-overlay.show { opacity: 1; }',
            '.message-box { position: relative; z-index: 10002; min-width: 320px; max-width: 420px; background: #fff; border-radius: 12px; box-shadow: 0 24px 48px rgba(15,23,42,0.18); border: 1px solid #e2e8f0; padding: 20px 24px; opacity: 0; transform: scale(0.95); transition: opacity 0.2s ease, transform 0.2s ease; }',
            '.message-box.show { opacity: 1; transform: scale(1); }',
            '.message-box .message-title { font-size: 16px; font-weight: 600; color: #0f172a; margin-bottom: 8px; }',
            '.message-box .message-content { font-size: 14px; color: #475569; line-height: 1.6; margin-bottom: 20px; }',
            '.message-box .message-buttons { display: flex; justify-content: flex-end; gap: 10px; }',
            '.message-box .message-buttons .btn { padding: 8px 18px; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; border: none; transition: background 0.15s, color 0.15s; }',
            '.message-box .btn-primary { background: linear-gradient(135deg, #3b82f6, #6366f1); color: #fff; }',
            '.message-box .btn-primary:hover { background: linear-gradient(135deg, #2563eb, #4f46e5); }',
            '.message-box .btn-secondary { background: #f1f5f9; color: #475569; border: 1px solid #e2e8f0; }',
            '.message-box .btn-secondary:hover { background: #e2e8f0; color: #0f172a; }',
            '.message-toast { position: fixed; top: 24px; left: 50%; transform: translateX(-50%); z-index: 10003; min-width: 280px; max-width: 360px; padding: 12px 18px; border-radius: 10px; font-size: 14px; line-height: 1.5; box-shadow: 0 10px 30px rgba(15,23,42,0.15); border: 1px solid #e2e8f0; opacity: 0; transform: translateX(-50%) translateY(-10px); transition: opacity 0.2s ease, transform 0.2s ease; pointer-events: auto; }',
            '.message-toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }',
            '.message-toast.success { background: #f0fdf4; color: #166534; border-color: #bbf7d0; }',
            '.message-toast.error { background: #fef2f2; color: #b91c1c; border-color: #fecaca; }',
            '.message-toast.warning { background: #fffbeb; color: #b45309; border-color: #fde68a; }',
            '.message-toast.info { background: #f0f9ff; color: #0369a1; border-color: #bae6fd; }'
        ].join('\n');
        document.head.appendChild(style);
    }

    /**
     * 弹窗提示（仅确定按钮）
     * @param {string} content - 提示内容
     * @param {string} [title='提示'] - 标题
     */
    function alert(content, title) {
        addStyles();
        title = title || '提示';
        var el = getContainer();
        el.classList.add('has-modal');
        var overlay = document.createElement('div');
        overlay.className = 'message-overlay';
        var box = document.createElement('div');
        box.className = 'message-box';
        box.innerHTML = '<div class="message-title">' + escapeHtml(title) + '</div><div class="message-content">' + escapeHtml(content) + '</div><div class="message-buttons"><button type="button" class="btn btn-primary message-ok">确定</button></div>';
        overlay.appendChild(box);
        el.appendChild(overlay);
        requestAnimationFrame(function () {
            overlay.classList.add('show');
            box.classList.add('show');
        });
        function close() {
            overlay.classList.remove('show');
            box.classList.remove('show');
            setTimeout(function () {
                if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                if (!el.querySelector('.message-overlay')) el.classList.remove('has-modal');
            }, 200);
        }
        box.querySelector('.message-ok').addEventListener('click', close);
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) close();
        });
    }

    /**
     * 确认框（确定/取消）
     * @param {string} content - 提示内容
     * @param {Object} [opts] - { title: '确认', onOk: function(), onCancel: function() }
     */
    function confirm(content, opts) {
        opts = opts || {};
        addStyles();
        var title = opts.title || '确认';
        var el = getContainer();
        el.classList.add('has-modal');
        var overlay = document.createElement('div');
        overlay.className = 'message-overlay';
        var box = document.createElement('div');
        box.className = 'message-box';
        box.innerHTML = '<div class="message-title">' + escapeHtml(title) + '</div><div class="message-content">' + escapeHtml(content) + '</div><div class="message-buttons"><button type="button" class="btn btn-secondary message-cancel">取消</button><button type="button" class="btn btn-primary message-ok">确定</button></div>';
        overlay.appendChild(box);
        el.appendChild(overlay);
        requestAnimationFrame(function () {
            overlay.classList.add('show');
            box.classList.add('show');
        });
        function close(callback) {
            overlay.classList.remove('show');
            box.classList.remove('show');
            setTimeout(function () {
                if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                if (!el.querySelector('.message-overlay')) el.classList.remove('has-modal');
                if (typeof callback === 'function') callback();
            }, 200);
        }
        box.querySelector('.message-ok').addEventListener('click', function () { close(opts.onOk); });
        box.querySelector('.message-cancel').addEventListener('click', function () { close(opts.onCancel); });
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) close(opts.onCancel);
        });
    }

    /**
     * 轻提示（顶部居中，几秒后自动消失）
     */
    function toast(content, type) {
        type = type || 'info';
        addStyles();
        var el = getContainer();
        var div = document.createElement('div');
        div.className = 'message-toast ' + type;
        div.textContent = content;
        el.appendChild(div);
        requestAnimationFrame(function () { div.classList.add('show'); });
        setTimeout(function () {
            div.classList.remove('show');
            setTimeout(function () {
                if (div.parentNode) div.parentNode.removeChild(div);
            }, 200);
        }, 2500);
    }

    function escapeHtml(s) {
        if (typeof s !== 'string') return '';
        var div = document.createElement('div');
        div.textContent = s;
        return div.innerHTML;
    }

    var Message = {
        alert: alert,
        confirm: confirm,
        success: function (msg) { toast(msg, 'success'); },
        error: function (msg) { toast(msg, 'error'); },
        warning: function (msg) { toast(msg, 'warning'); },
        info: function (msg) { toast(msg, 'info'); }
    };

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = Message;
    } else {
        global.Message = Message;
    }
})(typeof window !== 'undefined' ? window : this);
