/*
 * GMSSH Android 壳 —— Electron preload 契约垫片
 *
 * 在页面（以及同源 iframe）的第一个脚本之前执行，提供：
 *   window.electronAPI  { invoke(channel,payload), on(channel,cb), removeListener(channel,cb) }
 *   window.networkStatus{ check(), subscribe(cb), unsubscribe(id) }
 *   window.electronParam{ node(), chrome(), electron(), platform(), arch(), preloadPath, distPath }
 *   window.api          { ping() }
 *   window.__gmDispatchMainToRender(json)   <- 原生 main-to-render 推送入口
 *   window.__gmNetworkChanged(online)       <- 原生网络状态变化入口
 *
 * 另外把 Electron 的 <webview> 标签降级为 <iframe>，并打通父子页面消息通道，
 * 让 terminal.html（原本跑在 Electron webview 里）也能在 Android 上加载。
 */
(function () {
  'use strict';

  var W = window;
  if (W.__gmShimInstalled) { return; }
  W.__gmShimInstalled = true;

  var IS_FRAME = (function () {
    try { return W.top !== W.self; } catch (e) { return true; }
  })();

  function log() {
    try {
      if (W.console && W.console.log) {
        W.console.log.apply(W.console, ['[gm-shim]'].concat(Array.prototype.slice.call(arguments)));
      }
    } catch (e) { /* ignore */ }
  }

  /* ------------------------------------------------------------------ */
  /* 原生通道                                                            */
  /* ------------------------------------------------------------------ */
  function bridge() {
    try {
      if (W.AndroidBridge) { return W.AndroidBridge; }
      if (IS_FRAME && W.parent && W.parent.AndroidBridge) { return W.parent.AndroidBridge; }
    } catch (e) { /* cross-origin */ }
    return null;
  }

  function nativeCall(method, args) {
    var b = bridge();
    if (!b || typeof b[method] !== 'function') { return null; }
    try { return b[method].apply(b, Array.prototype.slice.call(args || [])); }
    catch (e) { log('native call failed', method, e && e.message); return null; }
  }

  var seq = 0;

  /* ------------------------------------------------------------------ */
  /* 主页面（index.html）                                                */
  /* ------------------------------------------------------------------ */
  var mainToRenderListeners = [];
  var netListeners = {};

  function dispatchMainToRender(msg) {
    if (!msg || typeof msg !== 'object') { return; }
    mainToRenderListeners.slice().forEach(function (cb) {
      try { cb(msg); } catch (e) { log('main-to-render handler error', e); }
    });
  }

  W.__gmDispatchMainToRender = function (payload) {
    var msg = payload;
    if (typeof payload === 'string') {
      try { msg = JSON.parse(payload); } catch (e) { return; }
    }
    dispatchMainToRender(msg);
  };

  W.__gmNetworkChanged = function (online) {
    Object.keys(netListeners).forEach(function (k) {
      try { netListeners[k](!!online); } catch (e) { /* ignore */ }
    });
  };

  function invokeNative(payload) {
    var json;
    try { json = JSON.stringify(payload === undefined ? null : payload); }
    catch (e) { return Promise.reject(e); }
    var id = String(++seq);
    return new Promise(function (resolve) {
      var raw = nativeCall('invoke', [id, json]);
      if (raw === null || raw === undefined || raw === '') { resolve({ status: 'ok' }); return; }
      try { resolve(JSON.parse(raw)); } catch (e) { resolve(raw); }
    });
  }

  function makeElectronAPI(sendChannel, recvChannel) {
    var listeners = [];
    return {
      invoke: function (channel, payload) {
        if (channel !== sendChannel) { return Promise.resolve(undefined); }
        return invokeNative(payload);
      },
      on: function (channel, cb) {
        if (channel !== recvChannel || typeof cb !== 'function') { return; }
        if (listeners.indexOf(cb) === -1) { listeners.push(cb); }
      },
      removeListener: function (channel, cb) {
        if (channel !== recvChannel || typeof cb !== 'function') { return; }
        var i = listeners.indexOf(cb);
        if (i >= 0) { listeners.splice(i, 1); }
      },
      /* 内部使用：child bridge 投递消息 */
      __dispatch: function (msg) {
        listeners.slice().forEach(function (cb) {
          try { cb(msg); } catch (e) { log('listener error', e); }
        });
      }
    };
  }

  var electronAPI = makeElectronAPI('render-to-main', 'main-to-render');

  var networkStatus = {
    check: function () {
      var r = nativeCall('isNetworkAvailable', []);
      return Promise.resolve(r === true || r === 'true');
    },
    subscribe: function (cb) {
      if (typeof cb !== 'function') { return 0; }
      var id = ++seq;
      netListeners[id] = cb;
      return id;
    },
    unsubscribe: function (id) {
      delete netListeners[id];
    }
  };

  var paramCache = null;
  function deviceInfo() {
    if (paramCache) { return paramCache; }
    paramCache = {};
    var raw = nativeCall('platformInfo', []);
    if (raw) {
      try { paramCache = JSON.parse(raw) || {}; } catch (e) { paramCache = {}; }
    }
    return paramCache;
  }

  var electronParam = {
    node: function () { return deviceInfo().node || '20.0.0'; },
    chrome: function () { return deviceInfo().chrome || '126.0.6478.71'; },
    electron: function () { return deviceInfo().electron || '31.0.0'; },
    platform: function () { return 'android'; },
    arch: function () { return deviceInfo().arch || 'arm64'; },
    preloadPath: '/android_asset/dist-electron/preload.js',
    distPath: '/android_asset/dist'
  };

  W.electronAPI = electronAPI;
  W.networkStatus = networkStatus;
  W.electronParam = electronParam;
  W.api = { ping: function () { nativeCall('log', ['ping']); } };

  /* ------------------------------------------------------------------ */
  /* 子页面（iframe 内的 terminal.html 等）：webview-to-main / render-to-webview */
  /* ------------------------------------------------------------------ */
  if (IS_FRAME) {
    W.electronAPI = makeElectronAPI('webview-to-main', 'render-to-webview');
    W.addEventListener('message', function (ev) {
      var d = ev && ev.data;
      if (!d || typeof d !== 'object' || !d.__gmHost) { return; }
      if (d.channel === 'render-to-webview') {
        W.electronAPI.__dispatch(d.payload);
      } else if (d.channel === 'gm-eval' && typeof d.code === 'string') {
        try { W.eval(d.code); } catch (e) { log('eval failed', e); }
      }
    }, false);
    W.addEventListener('DOMContentLoaded', function () {
      try { W.parent.postMessage({ __gmChildReady: 1 }, '*'); } catch (e) { /* ignore */ }
    }, false);
    return;
  }

  /* ------------------------------------------------------------------ */
  /* 主页面：接收子 frame 的请求并转发给原生                              */
  /* ------------------------------------------------------------------ */
  W.addEventListener('message', function (ev) {
    var d = ev && ev.data;
    if (d && typeof d === 'object' && d.__gmChildReady) {
      // 子页面就绪：把 shim 再注入一次（部分内核不会对 iframe 执行 document-start 脚本）
      try { ev.source.eval(W.__GM_CHILD_SHIM_SRC || ''); } catch (e) { /* ignore */ }
      return;
    }
    if (!d || typeof d !== 'object' || !d.__gmChild) { return; }
    if (d.channel !== 'webview-to-main') { return; }
    invokeNative(d.payload).then(function (res) {
      try { ev.source.postMessage({ __gmHost: 1, channel: 'gm-ack', id: d.id, result: res }, '*'); }
      catch (e) { /* ignore */ }
    });
  }, false);

  /* ------------------------------------------------------------------ */
  /* <webview> -> <iframe> 降级                                          */
  /* ------------------------------------------------------------------ */
  function absolute(url) {
    try { return new URL(url, W.location.href).href; } catch (e) { return url; }
  }

  function upgradeWebview(el) {
    if (!el || el.tagName !== 'WEBVIEW' || el.__gmUpgraded) { return; }
    el.__gmUpgraded = true;
    var iframe = W.document.createElement('iframe');
    for (var i = 0; i < el.attributes.length; i++) {
      var attr = el.attributes[i];
      if (attr.name === 'src' || attr.name === 'preload' || attr.name === 'partition') { continue; }
      try { iframe.setAttribute(attr.name, attr.value); } catch (e) { /* ignore */ }
    }
    var src = el.getAttribute('src') || './terminal.html';
    iframe.setAttribute('src', absolute(src));
    iframe.setAttribute('allow', 'clipboard-read; clipboard-write');
    iframe.style.border = '0';
    iframe.style.display = 'block';
    iframe.style.width = '100%';
    iframe.style.height = '100%';
    iframe.style.background = '#1e1e1e';

    iframe.send = function (channel, payload) {
      try {
        iframe.contentWindow.postMessage({ __gmHost: 1, channel: channel, payload: payload }, '*');
      } catch (e) { log('webview.send failed', e); }
    };
    iframe.loadURL = function (url) { iframe.src = absolute(url); return Promise.resolve(); };
    iframe.reload = function () { try { iframe.contentWindow.location.reload(); } catch (e) { /* ignore */ } };
    iframe.reloadIgnoringCache = iframe.reload;
    iframe.getURL = function () { return iframe.src; };
    iframe.getWebContentsId = function () { return 0; };
    iframe.openDevTools = function () { /* no-op on Android */ };
    iframe.closeDevTools = function () { /* no-op */ };
    iframe.isDestroyed = function () { return !iframe.isConnected; };
    iframe.executeJavaScript = function (code) {
      try { iframe.contentWindow.eval(code); } catch (e) { log('executeJavaScript failed', e); }
      return Promise.resolve(true);
    };
    iframe.stop = function () { try { iframe.contentWindow.stop(); } catch (e) { /* ignore */ } };
    iframe.addEventListener('load', function () {
      try { iframe.dispatchEvent(new Event('dom-ready')); } catch (e) { /* ignore */ }
    });

    if (el.parentNode) { el.parentNode.replaceChild(iframe, el); }
    return iframe;
  }

  function scanWebviews(root) {
    if (!root || root.nodeType !== 1) { return; }
    if (root.tagName === 'WEBVIEW') { upgradeWebview(root); return; }
    var list = root.querySelectorAll ? root.querySelectorAll('webview') : [];
    for (var i = 0; i < list.length; i++) { upgradeWebview(list[i]); }
  }

  var observer = new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var added = mutations[i].addedNodes;
      for (var j = 0; j < added.length; j++) { scanWebviews(added[j]); }
    }
  });

  function startWebviewPolyfill() {
    scanWebviews(W.document.documentElement);
    try { observer.observe(W.document.documentElement, { childList: true, subtree: true }); } catch (e) { /* ignore */ }
  }

  if (W.document.readyState === 'loading') {
    W.document.addEventListener('DOMContentLoaded', startWebviewPolyfill, false);
  } else {
    startWebviewPolyfill();
  }

  // 供父页面往 iframe 里补注入 shim 使用（同源 iframe 可以直接 eval）
  W.__GM_CHILD_SHIM_SRC = null;

  log('installed, frame=' + IS_FRAME);
})();
