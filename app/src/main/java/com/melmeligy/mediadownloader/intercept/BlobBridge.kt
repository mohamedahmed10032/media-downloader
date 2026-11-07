package com.melmeligy.mediadownloader.intercept

/**
 * The JavaScript half of the Blob URL bridge.
 *
 * The script is injected on every navigation. It hooks `URL.createObjectURL`, the `Blob`
 * constructor and `MediaSource`, keeps a registry of live blobs, and resolves a blob on
 * demand through `fetch` + `FileReader`, streaming the result to the native layer as
 * base64 chunks so the JS bridge string limit is never hit.
 */
object BlobBridge {

    /** Name the interface is registered under with `addJavascriptInterface`. */
    const val NAME = "AndroidBlobBridge"

    val SCRIPT: String = """
(function () {
  if (window.__mediaBridgeInstalled) return;
  window.__mediaBridgeInstalled = true;

  var B = window.AndroidBlobBridge;
  if (!B) return;

  var CHUNK = 512 * 1024;
  var registry = {};

  function safe(fn) { try { return fn(); } catch (e) { return null; } }

  /* 1. Hook URL.createObjectURL ------------------------------------------ */
  var nativeCreate = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var objectUrl = nativeCreate(obj);
    safe(function () {
      if (typeof Blob !== 'undefined' && obj instanceof Blob) {
        registry[objectUrl] = obj;
        B.onBlobCreated(objectUrl, obj.type || '', obj.size || 0);
      } else if (window.MediaSource && obj instanceof MediaSource) {
        B.onMediaSourceCreated(objectUrl, location.href);
      }
    });
    return objectUrl;
  };

  var nativeRevoke = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (u) {
    delete registry[u];
    return nativeRevoke(u);
  };

  /* 2. Hook Blob construction -------------------------------------------- */
  var NativeBlob = window.Blob;
  var PatchedBlob = function (parts, options) {
    var b = new NativeBlob(parts || [], options || {});
    safe(function () {
      var t = (b.type || '').toLowerCase();
      if (t.indexOf('video') === 0 || t.indexOf('audio') === 0 || t.indexOf('mpegurl') > -1) {
        B.onBlobCreated('', t, b.size || 0);
      }
    });
    return b;
  };
  PatchedBlob.prototype = NativeBlob.prototype;
  window.Blob = PatchedBlob;

  /* 3. Watch players and XHR/fetch that pick up blob or http(s) media ---- */
  var seenUrls = {};
  var mediaPattern = /\.(m3u8|mpd|mp4|m4a|mp3|webm|mov|aac)(\?|#|$)/i;

  function reportUrl(rawUrl, fallbackMime) {
    safe(function () {
      if (!rawUrl || typeof rawUrl !== 'string') return;
      var unescaped = rawUrl.replace(/\\\//g, '/').replace(/\\u002F/gi, '/').replace(/\\u0026/gi, '&').replace(/&amp;/g, '&');
      var cleaned = unescaped.replace(/([?&])(bytestart|byteend|range|rn|rbuf)=[^&#]*/gi, '$1').replace(/\?&/g, '?').replace(/[?&]+$/, '');
      var a = document.createElement('a');
      a.href = cleaned;
      var full = a.href || cleaned;
      if (seenUrls[full]) return;
      if (full.indexOf('blob:') === 0) {
        seenUrls[full] = true;
        B.onBlobDetectedInPlayer(full, location.href);
      } else if ((full.indexOf('http://') === 0 || full.indexOf('https://') === 0) && B.onHttpMediaDetected) {
        seenUrls[full] = true;
        B.onHttpMediaDetected(full, fallbackMime || '', location.href);
      }
    });
  }

  function scanTextForStreams(text) {
    safe(function () {
      if (!text || typeof text !== 'string' || text.length < 20) return;
      var unescaped = text.replace(/\\\//g, '/').replace(/\\u002F/gi, '/').replace(/\\u0026/gi, '&').replace(/&amp;/g, '&');
      var mp4Matches = unescaped.match(/https?:\/\/[^"'\s<>\\]+?\.(mp4|m4a|mp3|m3u8|mpd)[^"'\s<>\\]*/gi);
      if (mp4Matches) {
        for (var i = 0; i < mp4Matches.length && i < 15; i++) {
          var u = mp4Matches[i];
          var isAudio = u.indexOf('.m4a') > -1 || u.indexOf('.mp3') > -1 || u.indexOf('audio') > -1 || u.indexOf('heaac') > -1;
          reportUrl(u, isAudio ? 'audio/mp4' : 'video/mp4');
        }
      }
    });
  }

  if (window.fetch) {
    var origFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      safe(function () {
        var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        if (mediaPattern.test(u)) reportUrl(u, '');
      });
      return origFetch(input, init).then(function (resp) {
        safe(function () {
          var ct = (resp.headers && resp.headers.get) ? (resp.headers.get('content-type') || '') : '';
          if (ct.indexOf('json') > -1 || resp.url.indexOf('graphql') > -1 || resp.url.indexOf('/api/') > -1) {
            resp.clone().text().then(scanTextForStreams).catch(function(){});
          }
        });
        return resp;
      });
    };
  }

  if (window.XMLHttpRequest && XMLHttpRequest.prototype.open) {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      safe(function () {
        if (typeof url === 'string' && mediaPattern.test(url)) reportUrl(url, '');
      });
      this.addEventListener('load', function () {
        safe(function () {
          if (typeof url === 'string' && (url.indexOf('graphql') > -1 || url.indexOf('/api/') > -1)) {
            scanTextForStreams(this.responseText);
          }
        }.bind(this));
      });
      return origOpen.apply(this, arguments);
    };
  }

  function dismissAnnoyingOverlays() {
    safe(function () {
      var host = (location.hostname || '').toLowerCase();
      if (host.indexOf('instagram.com') > -1 || host.indexOf('tiktok.com') > -1 || host.indexOf('facebook.com') > -1) {
        var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
        for (var i = 0; i < dialogs.length; i++) {
          var d = dialogs[i];
          var txt = (d.innerText || '').toLowerCase();
          if (txt.indexOf('watch this reel in the app') > -1 ||
              txt.indexOf('open instagram') > -1 ||
              txt.indexOf('use the app') > -1 ||
              txt.indexOf('open app') > -1 ||
              txt.indexOf('continue in app') > -1) {
            var closeBtn = d.querySelector('button[aria-label*="Close"], svg[aria-label*="Close"], [role="button"]');
            if (closeBtn && (closeBtn.innerText || '').toLowerCase().indexOf('open') === -1) {
              closeBtn.click();
            }
            d.style.display = 'none';
            if (d.parentElement && d.parentElement.style && getComputedStyle(d.parentElement).position === 'fixed') {
              d.parentElement.style.display = 'none';
            }
          }
        }
        document.body.style.overflow = 'auto';
      }
    });
  }

  function scanMedia() {
    safe(function () {
      dismissAnnoyingOverlays();
      var nodes = document.querySelectorAll('video, audio, source');
      for (var i = 0; i < nodes.length; i++) {
        var src = nodes[i].currentSrc || nodes[i].src || '';
        if (!src) continue;
        var mime = nodes[i].type || (nodes[i].tagName === 'AUDIO' ? 'audio/mp4' : 'video/mp4');
        reportUrl(src, mime);
      }
      var scripts = document.querySelectorAll('script[type="application/json"], script:not([src])');
      for (var j = 0; j < scripts.length; j++) {
        var sText = scripts[j].textContent || '';
        if (sText.indexOf('video_versions') > -1 || sText.indexOf('video_url') > -1 || sText.indexOf('playable_url') > -1 || sText.indexOf('playAddr') > -1) {
          scanTextForStreams(sText);
        }
      }
    });
  }
  setInterval(scanMedia, 1000);
  document.addEventListener('loadedmetadata', scanMedia, true);
  document.addEventListener('play', scanMedia, true);

  /* 4. Resolve a blob and stream it to the native layer ------------------ */
  window.__resolveBlob = function (objectUrl, taskId) {
    var known = registry[objectUrl];
    var source = known
      ? Promise.resolve(known)
      : fetch(objectUrl).then(function (r) { return r.blob(); });

    source.then(function (b) {
      B.onBlobResolveStart(taskId, objectUrl, b.type || '', b.size || 0);
      var reader = new FileReader();
      reader.onload = function () {
        var data = String(reader.result);
        var comma = data.indexOf(',');
        var b64 = comma > -1 ? data.substring(comma + 1) : data;
        var offset = 0;
        while (offset < b64.length) {
          B.onBlobChunk(taskId, b64.substring(offset, offset + CHUNK));
          offset += CHUNK;
        }
        B.onBlobComplete(taskId);
      };
      reader.onerror = function () { B.onBlobError(taskId, 'FileReader failed'); };
      reader.readAsDataURL(b);
    }).catch(function (e) {
      B.onBlobError(taskId, String(e));
    });
  };
})();
"""
}
