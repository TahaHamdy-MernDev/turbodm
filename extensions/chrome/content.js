/* TurboDM Content Script: IDM-style floating "Download this video" pill + page link collector */

(function () {
  if (window.__TURBODM_INJECTED__) return;
  window.__TURBODM_INJECTED__ = true;

  const api = typeof browser !== "undefined" ? browser : chrome;
  const STREAMING_PAGE_RE = /(youtube\.com\/(watch|shorts)|vimeo\.com\/\d+|dailymotion\.com\/video|twitch\.tv\/videos|tiktok\.com\/.*\/video|facebook\.com\/.*(videos|reel|watch)|fb\.watch)/i;

  let showFloatingButton = true;
  let dismissedForUrl = "";
  let currentPill = null;

  api.storage.local.get({ showFloatingButton: true }, (res) => {
    showFloatingButton = res ? res.showFloatingButton !== false : true;
    if (showFloatingButton) {
      scheduleScan();
    }
  });

  api.storage.onChanged.addListener((changes) => {
    if (changes.showFloatingButton) {
      showFloatingButton = changes.showFloatingButton.newValue !== false;
      if (!showFloatingButton) {
        removePill();
      } else {
        scheduleScan();
      }
    }
  });

  function removePill() {
    if (currentPill && currentPill.parentNode) {
      currentPill.parentNode.removeChild(currentPill);
    }
    currentPill = null;
  }

  function findPrimaryVideo() {
    const videos = Array.from(document.querySelectorAll("video"));
    let best = null;
    let bestArea = 0;
    for (const v of videos) {
      const rect = v.getBoundingClientRect();
      const area = rect.width * rect.height;
      if (rect.width >= 260 && rect.height >= 150 && area > bestArea) {
        best = v;
        bestArea = area;
      }
    }
    return best;
  }

  function getDownloadTarget(videoEl) {
    const pageUrl = window.location.href;
    if (STREAMING_PAGE_RE.test(pageUrl)) {
      return {
        url: pageUrl,
        filename: `${document.title.replace(/\s*-\s*YouTube$/i, "").trim() || "video"}.mp4`,
        isMedia: true,
      };
    }
    if (videoEl) {
      const directSrc = videoEl.currentSrc || videoEl.src;
      if (directSrc && directSrc.startsWith("http")) {
        return { url: directSrc, filename: "", isMedia: /\.m3u8|\.mpd/i.test(directSrc) };
      }
      const srcEl = videoEl.querySelector("source[src]");
      if (srcEl && srcEl.src && srcEl.src.startsWith("http")) {
        return { url: srcEl.src, filename: "", isMedia: /\.m3u8|\.mpd/i.test(srcEl.src) };
      }
    }
    return null;
  }

  function ensurePill(videoEl, target) {
    if (!showFloatingButton || dismissedForUrl === window.location.href) {
      removePill();
      return;
    }

    if (!currentPill) {
      const pill = document.createElement("div");
      pill.id = "turbodm-video-pill";
      pill.style.cssText = [
        "position: fixed",
        "z-index: 2147483647",
        "display: flex",
        "align-items: center",
        "gap: 6px",
        "padding: 6px 10px",
        "border-radius: 8px",
        "background: linear-gradient(135deg, #1c71d8, #1a5fb4)",
        "color: #ffffff",
        "font-family: system-ui, -apple-system, sans-serif",
        "font-size: 12px",
        "font-weight: 600",
        "box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35)",
        "border: 1px solid rgba(255, 255, 255, 0.25)",
        "cursor: pointer",
        "user-select: none",
        "opacity: 0.94",
        "transition: opacity 0.15s ease, transform 0.15s ease",
      ].join(";");

      const dlSpan = document.createElement("span");
      dlSpan.textContent = "⬇ Download this video";
      dlSpan.style.paddingRight = "4px";
      dlSpan.addEventListener("click", (e) => {
        e.stopPropagation();
        const t = currentPill.__target;
        if (!t) return;
        dlSpan.textContent = "Sending to TurboDM...";
        api.runtime.sendMessage(
          {
            type: "TURBODM_DOWNLOAD",
            url: t.url,
            filename: t.filename,
            referer: window.location.href,
            isMedia: t.isMedia,
          },
          (resp) => {
            if (resp && resp.ok) {
              dlSpan.textContent = "✓ Sent to TurboDM";
            } else {
              dlSpan.textContent = "⚠ Launch TurboDM first";
            }
            setTimeout(() => {
              dlSpan.textContent = "⬇ Download this video";
            }, 2200);
          }
        );
      });

      const closeBtn = document.createElement("span");
      closeBtn.textContent = "×";
      closeBtn.title = "Hide for this page";
      closeBtn.style.cssText = [
        "padding: 0 4px",
        "margin-left: 2px",
        "font-size: 15px",
        "line-height: 1",
        "border-left: 1px solid rgba(255,255,255,0.3)",
        "opacity: 0.85",
      ].join(";");
      closeBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        dismissedForUrl = window.location.href;
        removePill();
      });

      pill.appendChild(dlSpan);
      pill.appendChild(closeBtn);
      document.documentElement.appendChild(pill);
      currentPill = pill;
    }

    currentPill.__target = target;

    // Position right above top-right corner of the video player (IDM style)
    const rect = videoEl ? videoEl.getBoundingClientRect() : null;
    if (rect && rect.bottom > 40 && rect.top < window.innerHeight - 40 && rect.width > 0) {
      const top = Math.max(10, Math.min(window.innerHeight - 46, rect.top + 8));
      const right = Math.max(12, window.innerWidth - rect.right + 10);
      currentPill.style.top = `${Math.round(top)}px`;
      currentPill.style.right = `${Math.round(right)}px`;
      currentPill.style.display = "flex";
    } else if (STREAMING_PAGE_RE.test(window.location.href)) {
      currentPill.style.top = "72px";
      currentPill.style.right = "20px";
      currentPill.style.display = "flex";
    } else {
      currentPill.style.display = "none";
    }
  }

  let scanTimer = null;
  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(() => {
      scanTimer = null;
      scanPage();
    }, 350);
  }

  function scanPage() {
    if (!showFloatingButton) {
      removePill();
      return;
    }
    const videoEl = findPrimaryVideo();
    const target = getDownloadTarget(videoEl);
    if (target) {
      ensurePill(videoEl, target);
    } else {
      removePill();
    }
  }

  // Watch SPA navigations (YouTube, Vimeo, etc.) and scroll/resize
  let lastHref = window.location.href;
  setInterval(() => {
    if (window.location.href !== lastHref) {
      lastHref = window.location.href;
      dismissedForUrl = "";
      scheduleScan();
    } else if (currentPill) {
      scanPage();
    }
  }, 1000);

  const observer = new MutationObserver(scheduleScan);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener("scroll", scheduleScan, { passive: true });
  window.addEventListener("resize", scheduleScan, { passive: true });

  // Handle "Download all links on page with TurboDM"
  api.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg && msg.type === "TURBODM_COLLECT_LINKS") {
      const seen = new Set();
      const urls = [];
      for (const a of document.querySelectorAll("a[href]")) {
        const href = a.href;
        if (href && (href.startsWith("http://") || href.startsWith("https://")) && !seen.has(href)) {
          seen.add(href);
          urls.push(href);
        }
      }
      sendResponse({ urls });
    }
  });
})();
