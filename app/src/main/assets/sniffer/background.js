"use strict";

// GeckoView 内置嗅探扩展的主通道。
// 通过 webRequest.onBeforeRequest / onBeforeSendHeaders / onHeadersReceived 观察
// Necko 发起的全部请求（含 POST 与 HLS 内部加载），再用 native messaging 回传原生层。
// 禁止依赖 shouldInterceptRequest；本文件不改动除图片拦截外的任何请求。

const NATIVE_APP = "gecko_sniffer";

const MEDIA_EXT_RE = /\.(m3u8|ts|mp4|m4s|mpd|flv|webm|mp3|m4a)(\?|#|$)/i;
const EXTENSION_KIND = {
  m3u8: "HLS",
  ts: "TS",
  mp4: "VIDEO",
  m4s: "M4S",
  mpd: "MPD",
  flv: "VIDEO",
  webm: "VIDEO",
  mp3: "AUDIO",
  m4a: "AUDIO"
};

const config = {
  sniffEnabled: true,
  blockImages: false,
  blockMixedContent: true
};

// HTTPS 页面加载 HTTP 子资源时，按设置决定是否由 webRequest 提前阻断。
function isMixedContent(details) {
  if (!config.blockMixedContent) return false;
  if (details.type === "main_frame") return false;
  const page = details.documentUrl || details.originUrl || "";
  return /^https:/i.test(page) && /^http:/i.test(details.url);
}

// requestId -> 该请求已收集到的全部字段。onBeforeSendHeaders / onHeadersReceived
// 会补齐请求头与 Content-Type，从而覆盖 POST 鉴权参数与按 MIME 识别的场景。
const pending = new Map();

function extensionKind(url) {
  const clean = url.split("?")[0].split("#")[0];
  const dot = clean.lastIndexOf(".");
  if (dot < 0) return null;
  const ext = clean.substring(dot + 1).toLowerCase();
  return EXTENSION_KIND[ext] || null;
}

function classify(url, mime, requestType) {
  const m = (mime || "").toLowerCase();
  const byExt = extensionKind(url);
  if (byExt) return byExt;
  if (m.indexOf("vnd.apple.mpegurl") >= 0 || m.indexOf("x-mpegurl") >= 0) return "HLS";
  if (m.indexOf("dash+xml") >= 0) return "MPD";
  if (m.indexOf("video/mp2t") >= 0) return "TS";
  if (m.indexOf("video/") === 0) return "VIDEO";
  if (m.indexOf("audio/") === 0) return "AUDIO";
  // Necko 内部 HLS 播放常以 media / xmlhttprequest 类型出现，即使 URL 无后缀也要感知
  if (requestType === "media" || requestType === "object") return "VIDEO";
  return null;
}

function summarizeBody(body) {
  if (!body) return null;
  if (body.formData) {
    const keys = Object.keys(body.formData);
    return { formKeys: keys.slice(0, 32), fieldCount: keys.length };
  }
  if (body.raw) return { rawPartCount: body.raw.length };
  return null;
}

function send(payload) {
  if (!config.sniffEnabled) return;
  try {
    const promise = browser.runtime.sendNativeMessage(NATIVE_APP, payload);
    if (promise && typeof promise.catch === "function") {
      promise.catch(function () {});
    }
  } catch (e) {
    // 原生层未就绪时静默丢弃，避免后台脚本异常退出
  }
}

function emit(stage, entry) {
  send(Object.assign({ kind: "network", stage: stage }, entry));
}

// 1) 请求发起：拿到 URL、HTTP 方法、tabId、资源类型与 POST 请求体摘要
browser.webRequest.onBeforeRequest.addListener(
  function (details) {
    if (config.blockImages && details.type === "image") {
      return { cancel: true };
    }
    if (isMixedContent(details)) {
      return { cancel: true };
    }
    if (!config.sniffEnabled) return {};

    const tabId = typeof details.tabId === "number" ? details.tabId : -1;
    const hasBody = !!(details.requestBody &&
      (details.requestBody.formData || details.requestBody.raw));
    const resourceType = classify(details.url, null, details.type);
    const entry = {
      requestId: String(details.requestId),
      url: details.url,
      method: details.method || "GET",
      tabId: tabId,
      requestType: details.type || "",
      resourceType: resourceType,
      mime: null,
      headers: {},
      hasRequestBody: hasBody,
      requestBody: summarizeBody(details.requestBody),
      source: "webRequest.onBeforeRequest"
    };
    pending.set(entry.requestId, entry);
    if (resourceType) emit("beforeRequest", entry);
    return {};
  },
  { urls: ["<all_urls>"] },
  ["blocking", "requestBody"]
);

// 2) 请求头就绪：补齐请求头。非 GET 请求即使 URL 无媒体后缀也要上报，
//    用于证明带鉴权参数的 POST 初始化请求不再是不可见盲区。
browser.webRequest.onBeforeSendHeaders.addListener(
  function (details) {
    const headers = {};
    (details.requestHeaders || []).forEach(function (h) {
      if (h && h.name) headers[h.name] = h.value || "";
    });
    const entry = pending.get(String(details.requestId));
    if (entry) {
      entry.headers = headers;
      entry.method = details.method || entry.method;
      if (entry.resourceType || entry.method.toUpperCase() !== "GET") {
        emit("beforeSendHeaders", entry);
      }
    }
    return { requestHeaders: details.requestHeaders };
  },
  { urls: ["<all_urls>"] },
  ["blocking", "requestHeaders"]
);

// 3) 响应头就绪：按 Content-Type 二次识别，覆盖无后缀的 HLS / TS / DASH 资源
browser.webRequest.onHeadersReceived.addListener(
  function (details) {
    let contentType = null;
    (details.responseHeaders || []).forEach(function (h) {
      if (h && h.name && h.name.toLowerCase() === "content-type") {
        contentType = h.value || null;
      }
    });
    const entry = pending.get(String(details.requestId));
    if (entry) {
      entry.mime = contentType;
      const detected = classify(entry.url, contentType, entry.requestType);
      if (detected) {
        entry.resourceType = detected;
        emit("headersReceived", entry);
      }
    }
    return { responseHeaders: details.responseHeaders };
  },
  { urls: ["<all_urls>"] },
  ["blocking", "responseHeaders"]
);

function release(requestId) {
  pending.delete(String(requestId));
}

browser.webRequest.onCompleted.addListener(
  function (details) { release(details.requestId); },
  { urls: ["<all_urls>"] }
);
browser.webRequest.onErrorOccurred.addListener(
  function (details) { release(details.requestId); },
  { urls: ["<all_urls>"] }
);

// 4) 内容脚本的辅助探测（Blob 拼接、MediaSource.appendBuffer 等页面内部动态地址）
browser.runtime.onMessage.addListener(function (msg, sender) {
  if (!msg || msg.kind !== "page-resource") return;
  const tabId = sender && sender.tab && typeof sender.tab.id === "number"
    ? sender.tab.id : -1;
  const url = String(msg.url || "");
  if (!url) return;
  send({
    kind: "page",
    stage: "contentScript",
    requestId: "page-" + Date.now() + "-" + Math.random().toString(36).slice(2, 9),
    url: url,
    method: msg.method || "GET",
    tabId: tabId,
    requestType: "xmlhttprequest",
    resourceType: msg.resourceType || classify(url, msg.mime, "xmlhttprequest"),
    mime: msg.mime || null,
    headers: {},
    hasRequestBody: false,
    requestBody: null,
    source: msg.source || "content-script"
  });
});

// 5) 与原生层的双向端口：接收设置（嗅探开关、图片拦截、页内查找）、下发探测指令
function forwardToTabs(tabId, payload) {
  if (!browser.tabs || typeof browser.tabs.sendMessage !== "function") return;
  const deliver = function (id) {
    try {
      const p = browser.tabs.sendMessage(id, payload);
      if (p && typeof p.catch === "function") p.catch(function () {});
    } catch (e) {}
  };
  if (typeof tabId === "number" && tabId > 0) {
    deliver(tabId);
  } else if (typeof browser.tabs.query === "function") {
    browser.tabs.query({ active: true, currentWindow: true }).then(function (tabs) {
      tabs.forEach(function (t) { deliver(t.id); });
    }).catch(function () {});
  }
}

let port = null;

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
    port.onMessage.addListener(function (msg) {
      if (!msg) return;
      if (msg.type === "config") {
        config.sniffEnabled = !!msg.sniffEnabled;
        config.blockImages = !!msg.blockImages;
        config.blockMixedContent = !!msg.blockMixedContent;
      } else if (msg.type === "blockImages") {
        config.blockImages = !!msg.value;
      } else if (msg.type === "find" || msg.type === "probe") {
        forwardToTabs(msg.tabId, { type: msg.type, query: msg.query || "" });
      }
    });
    port.onDisconnect.addListener(function () {
      port = null;
      setTimeout(connect, 1000);
    });
  } catch (e) {
    port = null;
    setTimeout(connect, 2000);
  }
}

connect();
