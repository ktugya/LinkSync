(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const isAndroid = typeof window.AndroidBridge !== 'undefined';
  let state = null;
  let settingsFilled = false;
  let toastTimer = null;
  let qrPending = new URLSearchParams(location.search).get('qr') === '1';

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));

  async function api(path, opts = {}) {
    const res = await fetch(path, { cache: 'no-store', ...opts });
    if (!res.ok) {
      const t = await res.text().catch(() => '');
      throw new Error(t || ('HTTP ' + res.status));
    }
    const ct = res.headers.get('content-type') || '';
    return ct.includes('json') ? res.json() : res.text();
  }

  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2200);
  }

  function fmtBytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function fmtTime(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts));
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    return sameDay
      ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      : d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' +
        d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }

  function platformName(p) {
    return p === 'android' ? '安卓' : 'PC';
  }

  async function load() {
    try {
      state = await api('/api/state');
      render();
      if (qrPending) {
        qrPending = false;
        setTimeout(showQr, 150);
      }
    } catch (e) {
      $('#selfLine').textContent = '连接本机服务失败';
      $('#peerCount').textContent = '离线';
      const dot = $('#netBadge').querySelector('.dot');
      if (dot) dot.classList.remove('online');
    }
  }

  function render() {
    if (!state) return;
    const self = state.self || {};
    const peers = state.peers || [];
    const online = peers.filter((p) => p.online).length;
    $('#selfLine').textContent = `${self.name || '本机'} · ${platformName(self.platform)} · 端口 ${self.port || '—'}`;
    $('#peerCount').textContent = `${online} / ${peers.length} 台设备`;
    $('#netBadge').querySelector('.dot').classList.toggle('online', online > 0);
    $('#aboutPort').textContent = `本机 ID：${self.id || '—'} · 监听端口 ${self.port || '—'}`;

    renderPeers(peers);
    renderTransfers(state.transfers || []);
    renderClipboard(state.clipboard || []);
    renderMessages(state.messages || []);
    renderSettings(state.settings || {});
    const auto = state.settings && state.settings.autoClipboard;
    $('#autoClipHint').textContent = auto ? '自动同步已开启' : '自动同步已关闭';
  }

  function renderPeers(peers) {
    $('#peerEmpty').hidden = peers.length > 0;
    $('#peerGrid').innerHTML = peers.map((p) => `
      <div class="card peer">
        <div class="peer-top">
          <span class="peer-name">${esc(p.name)}</span>
          <span class="badge">${platformName(p.platform)}</span>
        </div>
        <div class="peer-meta">
          <span class="dot ${p.online ? 'online' : ''}"></span>${p.online ? '在线' : '离线'}
          <span>·</span><span>${esc(p.ip)}:${p.port}</span>
        </div>
        <div class="peer-actions">
          <button class="btn" data-action="send-file" data-id="${esc(p.id)}" ${p.online ? '' : 'disabled'}>发文件</button>
          <button class="btn secondary" data-action="send-text" data-id="${esc(p.id)}">发文本</button>
        </div>
      </div>`).join('');
  }

  function renderTransfers(items) {
    $('#transferEmpty').hidden = items.length > 0;
    $('#transferList').innerHTML = items.map((t) => {
      const done = t.status === 'done';
      const failed = t.status === 'failed';
      const pct = done ? 100 : (t.size ? Math.max(0, Math.min(100, Math.round((t.bytes / t.size) * 100))) : 0);
      const dir = t.direction === 'out' ? '发送到' : '接收自';
      const status = failed ? ('失败' + (t.error ? '：' + t.error : ''))
        : done ? '已完成' : `${pct}% · ${fmtBytes(t.bytes)} / ${fmtBytes(t.size)}`;
      return `
        <div class="item ${done ? 'done' : ''} ${failed ? 'failed' : ''}">
          <div>
            <div class="item-title">${esc(t.name)}</div>
            <div class="item-sub"><span>${dir} ${esc(t.peerName || '')}</span><span>${fmtTime(t.ts)}</span></div>
          </div>
          <div class="item-side">${status}</div>
          <div class="bar"><i style="width:${pct}%"></i></div>
          ${t.path ? `<div class="item-sub">${esc(t.path)}</div>` : ''}
        </div>`;
    }).join('');
  }

  function renderClipboard(items) {
    $('#clipEmpty').hidden = items.length > 0;
    $('#clipList').innerHTML = items.map((c) => `
      <div class="item">
        <div>
          <div class="item-text">${esc((c.text || '').slice(0, 400))}${(c.text || '').length > 400 ? '…' : ''}</div>
          <div class="item-sub"><span>来自 ${esc(c.fromName || c.from || '本机')}</span><span>${fmtTime(c.ts)}</span></div>
        </div>
        <div class="item-actions">
          <button class="btn ghost" data-action="copy-clip" data-id="${esc(c.id)}">复制</button>
        </div>
      </div>`).join('');
  }

  function renderMessages(items) {
    $('#msgEmpty').hidden = items.length > 0;
    $('#msgList').innerHTML = items.map((m) => `
      <div class="item">
        <div>
          <div class="item-text">${esc(m.text || '')}</div>
          <div class="item-sub"><span>来自 ${esc(m.fromName || m.from || '本机')}</span><span>${fmtTime(m.ts)}</span></div>
        </div>
        <div class="item-actions">
          <button class="btn ghost" data-action="copy-msg" data-id="${esc(m.id)}">复制</button>
        </div>
      </div>`).join('');
  }

  function renderSettings(s) {
    if (isAndroid) {
      $('#downloadDirField').hidden = true;
      $('#startupField').hidden = true;
      $('#batteryField').hidden = false;
    } else {
      $('#batteryField').hidden = true;
    }
    const active = document.activeElement;
    const editing = active && $('#page-settings').contains(active);
    if (settingsFilled && editing) return;
    $('#setName').value = s.name || '';
    $('#setAutoClipboard').checked = !!s.autoClipboard;
    $('#setDownloadDir').value = s.downloadDir || '';
    $('#setStartup').checked = !!s.startWithWindows;
    settingsFilled = true;
  }

  async function saveSettings() {
    const body = {
      name: $('#setName').value.trim(),
      autoClipboard: $('#setAutoClipboard').checked,
      autoReceive: true,
      downloadDir: $('#setDownloadDir').value.trim(),
      startWithWindows: $('#setStartup').checked
    };
    try {
      await api('/api/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      settingsFilled = false;
      toast('设置已保存');
      load();
    } catch (e) {
      toast('保存失败：' + e.message);
    }
  }

  function openTextDialog(preselected) {
    const peers = (state && state.peers || []);
    if (!peers.length) { toast('没有可用的设备'); return; }
    $('#textTargets').innerHTML = peers.map((p) => `
      <label class="target">
        <input type="checkbox" value="${esc(p.id)}" ${p.id === preselected ? 'checked' : ''}>
        <span>${esc(p.name)}${p.online ? '' : '（离线，连上后补发）'}</span>
      </label>`).join('');
    $('#textContent').value = '';
    $('#textDialog').showModal();
    setTimeout(() => $('#textContent').focus(), 30);
  }

  async function sendText() {
    const text = $('#textContent').value;
    const to = $$('#textTargets input:checked').map((i) => i.value);
    if (!text.trim()) { toast('请输入文字'); return; }
    if (!to.length) { toast('请选择目标设备'); return; }
    try {
      await api('/api/text/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ to, text })
      });
      $('#textDialog').close();
      toast('已发送');
      load();
    } catch (e) {
      toast('发送失败：' + e.message);
    }
  }

  async function sendClipboard() {
    try {
      if (isAndroid) {
        window.AndroidBridge.sendClipboard();
        toast('已读取剪贴板并发送');
      } else {
        const r = await api('/api/local/clipboard', { method: 'POST' });
        toast(r && r.text ? '已发送' : '剪贴板没有文字');
      }
      load();
    } catch (e) {
      toast('发送失败：' + e.message);
    }
  }

  async function copyText(text) {
    if (!text) return;
    try {
      if (isAndroid) {
        window.AndroidBridge.copyText(text);
      } else {
        await api('/api/local/copy', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text })
        });
      }
      toast('已复制');
    } catch (e) {
      toast('复制失败：' + e.message);
    }
  }

  function pickFiles(peerId) {
    if (isAndroid) {
      window.AndroidBridge.pickFiles(peerId);
      return;
    }
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.style.display = 'none';
    input.onchange = () => {
      const files = Array.from(input.files || []);
      if (files.length) uploadFiles(peerId, files);
      input.remove();
    };
    document.body.appendChild(input);
    input.click();
  }

  function uploadFiles(peerId, files) {
    for (const f of files) {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/send?peer=${encodeURIComponent(peerId)}`);
      xhr.setRequestHeader('X-File-Name', encodeURIComponent(f.name));
      xhr.setRequestHeader('X-File-Size', String(f.size));
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) toast(`已发送 ${f.name}`);
        else toast(`发送失败：${f.name}`);
        load();
      };
      xhr.onerror = () => toast(`发送失败：${f.name}`);
      xhr.send(f);
    }
    toast(`开始发送 ${files.length} 个文件`);
  }

  async function addPeer() {
    const addr = $('#manualAddr').value.trim();
    if (!addr) return;
    try {
      await api('/api/peers/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ addr })
      });
      $('#manualAddr').value = '';
      toast('已添加');
      load();
    } catch (e) {
      toast('添加失败：' + e.message);
    }
  }

  async function clearTransfers() {
    try {
      await api('/api/transfers/clear', { method: 'POST' });
      load();
    } catch (e) { toast('操作失败'); }
  }

  function showQr() {
    const self = (state && state.self) || {};
    const ips = self.ips || [];
    const ip = ips[0] || '';
    if (!ip) { toast('没有可用的局域网 IP'); return; }
    if (!window.QRCode) { toast('二维码组件加载失败'); return; }
    const port = self.port || 47821;
    const payload = `linksync://add?ip=${encodeURIComponent(ip)}&port=${port}&name=${encodeURIComponent(self.name || '')}`;
    const box = $('#qrBox');
    box.innerHTML = '';
    new QRCode(box, {
      text: payload,
      width: 236,
      height: 236,
      correctLevel: QRCode.CorrectLevel.M
    });
    const others = ips.slice(1);
    $('#qrAddr').textContent = `${ip}:${port}` +
      (others.length ? `（其他网卡：${others.join(' / ')}）` : '');
    const dlg = $('#qrDialog');
    if (!dlg.open) dlg.showModal();
  }

  function scanQr() {
    if (isAndroid && window.AndroidBridge && window.AndroidBridge.scanQr) {
      window.AndroidBridge.scanQr();
    } else {
      toast('请在 Android 设备上使用扫一扫');
    }
  }

  function bind() {
    $$('.tab').forEach((t) => t.addEventListener('click', () => {
      $$('.tab').forEach((x) => x.classList.toggle('active', x === t));
      $$('.page').forEach((p) => p.classList.toggle('active', p.id === 'page-' + t.dataset.tab));
    }));

    document.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-action]');
      if (btn) {
        const id = btn.dataset.id;
        switch (btn.dataset.action) {
          case 'send-file': pickFiles(id); break;
          case 'send-text': openTextDialog(id); break;
          case 'copy-clip': {
            const item = ((state && state.clipboard) || []).find((x) => x.id === id);
            if (item) copyText(item.text);
            break;
          }
          case 'copy-msg': {
            const item = ((state && state.messages) || []).find((x) => x.id === id);
            if (item) copyText(item.text);
            break;
          }
        }
        return;
      }
      if (e.target.id === 'sendClipboardBtn') { sendClipboard(); return; }
      if (e.target.id === 'openTextBtn') { openTextDialog(); return; }
      if (e.target.id === 'addPeerBtn') { addPeer(); return; }
      if (e.target.id === 'clearTransfers') { clearTransfers(); return; }
      if (e.target.id === 'saveSettings') { saveSettings(); return; }
      if (e.target.id === 'showQrBtn') { showQr(); return; }
      if (e.target.id === 'scanQrBtn') { scanQr(); return; }
      if (e.target.id === 'closeQrBtn') { $('#qrDialog').close(); return; }
      if (e.target.id === 'batteryBtn') {
        if (isAndroid) window.AndroidBridge.requestBattery();
        return;
      }
    });

    $('#sendTextBtn').addEventListener('click', (e) => { e.preventDefault(); sendText(); });
    $('#textContent').addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); sendText(); }
    });
    $('#manualAddr').addEventListener('keydown', (e) => { if (e.key === 'Enter') addPeer(); });

    $('#scanQrBtn').hidden = !isAndroid;
    document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
  }

  bind();
  load();
  setInterval(() => { if (!document.hidden) load(); }, 800);
})();
