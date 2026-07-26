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

  /* 3. Watch players that pick up a blob source -------------------------- */
  function scanMedia() {
    safe(function () {
      var nodes = document.querySelectorAll('video, audio, source');
      for (var i = 0; i < nodes.length; i++) {
        var src = nodes[i].src || nodes[i].currentSrc || '';
        if (src.indexOf('blob:') === 0) {
          B.onBlobDetectedInPlayer(src, location.href);
        }
      }
    });
  }
  setInterval(scanMedia, 1500);
  document.addEventListener('loadedmetadata', scanMedia, true);

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
