/* TurboDM Browser Extension Background Service Worker (Chrome MV3 & Firefox compatible) */

const api = typeof browser !== "undefined" ? browser : chrome;
const NATIVE_HOST = "com.turbodm.host";

const DEFAULT_SETTINGS = {
  autoIntercept: false,       // OFF by default per user preference
  showFloatingButton: true,   // ON by default for IDM-style video grabbing
  minInterceptSizeBytes: 1024 * 1024,
};

const MEDIA_EXT_RE = /\.(mp4|webm|mkv|m3u8|mpd|flv|mov|m4v|mp3|m4a|flac|ogg|opus)(\?.*)?$/i;
const MEDIA_MIME_PREFIXES = [
  "video/",
  "audio/",
  "application/vnd.apple.mpegurl",
  "application/x-mpegurl",
  "application/dash+xml",
];
const STREAMING_HOST_RE = /(youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com|twitch\.tv|tiktok\.com|facebook\.com|fb\.watch|instagram\.com|x\.com|twitter\.com|soundcloud\.com|reddit\.com)/i;

// tabId -> Map<url, {url, type, mime, size, title, timestamp}>
const tabMediaMap = new Map();

function getSettings() {
  return new Promise((resolve) => {
    api.storage.local.get(DEFAULT_SETTINGS, (res) => {
      resolve(Object.assign({}, DEFAULT_SETTINGS, res || {}));
    });
  });
}

function getCookiesForUrl(url) {
  return new Promise((resolve) => {
    if (!api.cookies || !url || !url.startsWith("http")) {
      resolve("");
      return;
    }
    try {
      api.cookies.getAll({ url }, (cookies) => {
        if (api.runtime.lastError || !cookies) {
          resolve("");
          return;
        }
        resolve(cookies.map((c) => `${c.name}=${c.value}`).join("; "));
      });
    } catch (_e) {
      resolve("");
    }
  });
}

function sendToNativeHost(payload) {
  return new Promise((resolve) => {
    try {
      api.runtime.sendNativeMessage(NATIVE_HOST, payload, (response) => {
        if (api.runtime.lastError) {
          resolve({ ok: false, error: api.runtime.lastError.message });
          return;
        }
        resolve(response || { ok: false, error: "Empty response from native host" });
      });
    } catch (err) {
      resolve({ ok: false, error: String(err) });
    }
  });
}

async function sendDownloadToTurboDM({
  url,
  filename = "",
  referer = "",
  isMedia = null,
  showDialog = true,
}) {
  if (!url || (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ftp://"))) {
    return { ok: false, error: "Invalid URL" };
  }
  const cookies = await getCookiesForUrl(url);
  const payload = {
    action: "add",
    url,
    filename,
    referer,
    cookies,
    user_agent: navigator.userAgent,
    is_media: isMedia,
    show_dialog: showDialog,
  };
  return sendToNativeHost(payload);
}

// ---------- Context Menus ----------

function setupContextMenus() {
  api.contextMenus.removeAll(() => {
    api.contextMenus.create({
      id: "turbodm-download-link",
      title: "Download with TurboDM",
      contexts: ["link", "image", "video", "audio"],
    });
    api.contextMenus.create({
      id: "turbodm-download-video",
      title: "Download page video with TurboDM",
      contexts: ["page"],
    });
    api.contextMenus.create({
      id: "turbodm-download-all",
      title: "Download all links on page with TurboDM",
      contexts: ["page"],
    });
  });
}

api.runtime.onInstalled.addListener(setupContextMenus);
if (api.runtime.onStartup) {
  api.runtime.onStartup.addListener(setupContextMenus);
}

api.contextMenus.onClicked.addListener(async (info, tab) => {
  const pageUrl = (tab && tab.url) || info.pageUrl || "";
  if (info.menuItemId === "turbodm-download-link") {
    const targetUrl = info.linkUrl || info.srcUrl;
    if (targetUrl) {
      await sendDownloadToTurboDM({
        url: targetUrl,
        referer: pageUrl,
        isMedia: info.mediaType === "video" || info.mediaType === "audio" ? true : null,
      });
    }
  } else if (info.menuItemId === "turbodm-download-video") {
    if (pageUrl) {
      await sendDownloadToTurboDM({
        url: pageUrl,
        filename: (tab && tab.title) ? `${tab.title}.mp4` : "",
        referer: pageUrl,
        isMedia: true,
      });
    }
  } else if (info.menuItemId === "turbodm-download-all" && tab && tab.id) {
    api.tabs.sendMessage(tab.id, { type: "TURBODM_COLLECT_LINKS" }, async (resp) => {
      if (api.runtime.lastError || !resp || !resp.urls || resp.urls.length === 0) {
        return;
      }
      const cookies = await getCookiesForUrl(pageUrl);
      await sendToNativeHost({
        action: "add_batch",
        urls: resp.urls,
        referer: pageUrl,
        cookies,
        user_agent: navigator.userAgent,
      });
    });
  }
});

// ---------- Network Media Sniffer (for Floating Button & Popup) ----------

function recordMedia(tabId, entry) {
  if (tabId < 0) return;
  if (!tabMediaMap.has(tabId)) {
    tabMediaMap.set(tabId, new Map());
  }
  const map = tabMediaMap.get(tabId);
  map.set(entry.url, entry);
  // Keep latest 30 streams per tab
  if (map.size > 30) {
    const oldestKey = map.keys().next().value;
    map.delete(oldestKey);
  }
}

if (api.webRequest && api.webRequest.onHeadersReceived) {
  api.webRequest.onHeadersReceived.addListener(
    (details) => {
      if (details.tabId < 0 || !details.url) return;
      const headers = details.responseHeaders || [];
      let contentType = "";
      let contentLength = 0;
      for (const h of headers) {
        const name = (h.name || "").toLowerCase();
        if (name === "content-type") contentType = (h.value || "").toLowerCase();
        if (name === "content-length") contentLength = parseInt(h.value || "0", 10) || 0;
      }

      const isMediaMime = MEDIA_MIME_PREFIXES.some((p) => contentType.startsWith(p));
      const isMediaExt = MEDIA_EXT_RE.test(details.url);
      // Skip tiny tracking/range fragments unless HLS/DASH manifest
      const isManifest = /\.(m3u8|mpd)(\?.*)?$/i.test(details.url) || contentType.includes("mpegurl") || contentType.includes("dash+xml");
      if (!isManifest && contentLength > 0 && contentLength < 64 * 1024) return;
      if (details.url.includes("googlevideo.com/videoplayback")) return; // Handled cleanly via YouTube page URL

      if (isMediaMime || isMediaExt) {
        recordMedia(details.tabId, {
          url: details.url,
          mime: contentType.split(";")[0] || "video/mp4",
          size: contentLength,
          isManifest,
          timestamp: Date.now(),
        });
      }
    },
    { urls: ["http://*/*", "https://*/*"] },
    ["responseHeaders"]
  );
}

api.tabs.onRemoved.addListener((tabId) => {
  tabMediaMap.delete(tabId);
});

api.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === "loading" && changeInfo.url) {
    tabMediaMap.delete(tabId);
  }
});

// ---------- Optional Auto-Intercept (OFF by default!) ----------

if (api.downloads && api.downloads.onCreated) {
  api.downloads.onCreated.addListener(async (downloadItem) => {
    const settings = await getSettings();
    if (!settings.autoIntercept) {
      return; // User opted out of auto-interception by default
    }
    const url = downloadItem.finalUrl || downloadItem.url;
    if (!url || !url.startsWith("http")) return;
    if (downloadItem.fileSize > 0 && downloadItem.fileSize < settings.minInterceptSizeBytes) {
      return;
    }

    const res = await sendDownloadToTurboDM({
      url,
      filename: downloadItem.filename || "",
      referer: downloadItem.referrer || "",
    });
    if (res && res.ok) {
      try {
        api.downloads.cancel(downloadItem.id);
        api.downloads.erase({ id: downloadItem.id });
      } catch (_e) {
        // Ignore if already completed
      }
    }
  });
}

// ---------- Messages from Content Script & Popup ----------

api.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || !msg.type) return false;

  if (msg.type === "TURBODM_PING") {
    sendToNativeHost({ action: "ping" }).then(sendResponse);
    return true;
  }

  if (msg.type === "TURBODM_LAUNCH") {
    sendToNativeHost({ action: "launch" }).then(sendResponse);
    return true;
  }

  if (msg.type === "TURBODM_DOWNLOAD") {
    const referer = msg.referer || (sender.tab && sender.tab.url) || "";
    sendDownloadToTurboDM({
      url: msg.url,
      filename: msg.filename || "",
      referer,
      isMedia: msg.isMedia !== undefined ? msg.isMedia : null,
      showDialog: msg.showDialog !== undefined ? msg.showDialog : true,
    }).then(sendResponse);
    return true;
  }

  if (msg.type === "TURBODM_GET_TAB_MEDIA") {
    const tabId = msg.tabId || (sender.tab && sender.tab.id);
    const tabUrl = msg.tabUrl || (sender.tab && sender.tab.url) || "";
    const tabTitle = msg.tabTitle || (sender.tab && sender.tab.title) || "";
    const items = [];

    if (tabUrl && STREAMING_HOST_RE.test(tabUrl)) {
      items.push({
        url: tabUrl,
        title: tabTitle || "Current Page Video",
        mime: "streaming/yt-dlp",
        size: 0,
        isMedia: true,
        isPageVideo: true,
      });
    }

    const map = tabMediaMap.get(tabId);
    if (map) {
      for (const entry of map.values()) {
        items.push({
          url: entry.url,
          title: entry.url.split("/").pop().split("?")[0] || "Media Stream",
          mime: entry.mime,
          size: entry.size,
          isMedia: entry.isManifest,
          isPageVideo: false,
        });
      }
    }
    sendResponse({ ok: true, items });
    return false;
  }

  return false;
});
