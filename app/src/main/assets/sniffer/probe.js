"use strict";

// 辅助层，仅用于 webRequest 覆盖不到的页面内部动态地址：
// Blob 拼接、MediaSource.appendBuffer、运行时赋值的 HTMLMediaElement.src。
// 主嗅探通道始终是 background.js 的 webRequest，本脚本不做请求拦截。

(function () {
  if (window.__geckoSnifferProbed) return;
  window.__geckoSnifferProbed = true;

  const MEDIA_RE = /\.(m3u8|ts|mp4|m4s|mpd|flv|webm|mp3|m4a)(\?|#|$)/i;

  function report(url, source, mime, resourceType) {
    if (!url || url === "about:blank") return;
    try {
      browser.runtime.sendMessage({
        kind: "page-resource",
        url: String(url),
        source: source,
        mime: mime || null,
        resourceType: resourceType || null,
        method: "GET"
      });
    } catch (e) {}
  }

  const originalXhrOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    report(url, "xhr", null, null);
    return originalXhrOpen.apply(this, arguments);
  };

  const originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input) {
      const u = typeof input === "string" ? input : (input && input.url);
      report(u, "fetch", null, null);
      return originalFetch.apply(this, arguments);
    };
  }

  try {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "src");
    if (descriptor && descriptor.set) {
      Object.defineProperty(HTMLMediaElement.prototype, "src", {
        configurable: true,
        get: descriptor.get,
        set: function (value) {
          report(value, "media-src", null, null);
          return descriptor.set.call(this, value);
        }
      });
    }
  } catch (e) {}

  try {
    const originalAdd = MediaSource.prototype.addSourceBuffer;
    MediaSource.prototype.addSourceBuffer = function () {
      report(window.location.href, "mediasource", "video/mp4", null);
      return originalAdd.apply(this, arguments);
    };
  } catch (e) {}

  try {
    const originalAppend = SourceBuffer.prototype.appendBuffer;
    SourceBuffer.prototype.appendBuffer = function () {
      report(window.location.href, "appendbuffer", "video/mp4", null);
      return originalAppend.apply(this, arguments);
    };
  } catch (e) {}

  // Blob 拼接：页面把分片拼成 Blob 后再交给 <video>，URL 只是 blob: 形式。
  // 这里记录并上报，原生层再决定是否用 evaluateJS 做补充探测。
  const blobMedia = [];
  try {
    const originalCreate = URL.createObjectURL;
    URL.createObjectURL = function (obj) {
      const u = originalCreate.apply(this, arguments);
      if (obj && obj.type && /^(video|audio)\//i.test(obj.type)) {
        blobMedia.push({ blobUrl: u, mime: obj.type, size: obj.size || 0 });
        report(u, "blob-media", obj.type, null);
      }
      return u;
    };
  } catch (e) {}

  // 原生层下发的 find / probe 指令
  browser.runtime.onMessage.addListener(function (msg) {
    if (!msg) return;
    if (msg.type === "find") {
      try { window.find(msg.query, false, false, true); } catch (e) {}
    } else if (msg.type === "probe") {
      // 补充探测：从 DOM 与 PerformanceResourceTiming 中收集媒体地址
      try {
        const found = [];
        document.querySelectorAll("video, audio, source").forEach(function (el) {
          if (el.src) found.push(el.src);
          if (el.currentSrc) found.push(el.currentSrc);
        });
        if (window.performance && performance.getEntriesByType) {
          performance.getEntriesByType("resource").forEach(function (r) {
            if (r.name && MEDIA_RE.test(r.name)) found.push(r.name);
          });
        }
        found.forEach(function (u) { report(u, "evaluatejs-probe", null, null); });
        blobMedia.forEach(function (b) {
          report(b.blobUrl, "evaluatejs-blob", b.mime, null);
        });
      } catch (e) {}
    }
  });
})();
