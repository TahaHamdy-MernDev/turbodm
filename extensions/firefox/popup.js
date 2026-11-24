const api = typeof browser !== "undefined" ? browser : chrome;

function formatSize(bytes) {
  if (!bytes || bytes <= 0) return "";
  const units = ["B", "KB", "MB", "GB"];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(1)} ${units[i]}`;
}

async function initPopup() {
  const badge = document.getElementById("statusBadge");
  const mediaList = document.getElementById("mediaList");
  const chkFloating = document.getElementById("chkFloating");
  const chkAutoIntercept = document.getElementById("chkAutoIntercept");

  // Load settings
  api.storage.local.get({ showFloatingButton: true, autoIntercept: false }, (res) => {
    chkFloating.checked = res.showFloatingButton !== false;
    chkAutoIntercept.checked = Boolean(res.autoIntercept);
  });

  chkFloating.addEventListener("change", () => {
    api.storage.local.set({ showFloatingButton: chkFloating.checked });
  });
  chkAutoIntercept.addEventListener("change", () => {
    api.storage.local.set({ autoIntercept: chkAutoIntercept.checked });
  });

  // Ping native host
  api.runtime.sendMessage({ type: "TURBODM_PING" }, (resp) => {
    if (resp && resp.running) {
      badge.textContent = "Connected";
      badge.className = "badge online";
    } else {
      badge.textContent = "Standby (Auto-launch)";
      badge.className = "badge offline";
    }
  });

  // Active tab media list
  api.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    const tab = tabs && tabs[0];
    if (!tab) return;

    document.getElementById("btnGrabPage").addEventListener("click", () => {
      api.runtime.sendMessage({
        type: "TURBODM_DOWNLOAD",
        url: tab.url,
        filename: tab.title ? `${tab.title}.mp4` : "",
        referer: tab.url,
        isMedia: true,
      });
      window.close();
    });

    api.runtime.sendMessage(
      {
        type: "TURBODM_GET_TAB_MEDIA",
        tabId: tab.id,
        tabUrl: tab.url,
        tabTitle: tab.title,
      },
      (resp) => {
        mediaList.innerHTML = "";
        const items = (resp && resp.items) || [];
        if (items.length === 0) {
          mediaList.innerHTML =
            '<div class="empty">No active video/audio streams detected yet.<br/>Play a video or right-click any link → "Download with TurboDM".</div>';
          return;
        }
        for (const item of items) {
          const card = document.createElement("div");
          card.className = "media-item";

          const info = document.createElement("div");
          info.className = "media-info";

          const title = document.createElement("div");
          title.className = "media-title";
          title.textContent = item.title || "Media Stream";

          const meta = document.createElement("div");
          meta.className = "media-meta";
          const sz = formatSize(item.size);
          meta.textContent = sz ? `${item.mime} • ${sz}` : item.mime;

          info.appendChild(title);
          info.appendChild(meta);

          const btn = document.createElement("button");
          btn.textContent = "Download";
          btn.addEventListener("click", () => {
            btn.textContent = "Sent ✓";
            api.runtime.sendMessage({
              type: "TURBODM_DOWNLOAD",
              url: item.url,
              filename: item.isPageVideo ? `${item.title}.mp4` : "",
              referer: tab.url,
              isMedia: item.isMedia,
            });
          });

          card.appendChild(info);
          card.appendChild(btn);
          mediaList.appendChild(card);
        }
      }
    );
  });

  document.getElementById("btnOpenApp").addEventListener("click", () => {
    api.runtime.sendMessage({ type: "TURBODM_LAUNCH" });
    window.close();
  });
}

document.addEventListener("DOMContentLoaded", initPopup);
