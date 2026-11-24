package com.melmeligy.mediadownloader.intercept

/**
 * The JavaScript half of the media & Blob URL bridge.
 *
 * Injected on every navigation. It:
 * 1. Extracts authoritative multi-quality video/audio streams directly from player configs
 *    (`flashvars_<id>` + `get_media`, `html5player`, `window.initials` with 7-PRNG hex decryption
 *    and `hostMap` subdomain remapping, KVS `kt_player` with `license_code` decryption,
 *    JWPlayer, VideoJS, Plyr, FluidPlayer, nested JSON-LD, HTML5 `<source>`)
 *    without touching VAST prerolls, hover previews, related-video thumbs, or site bumpers.
 * 2. Exposes `window.__dlScanNow()` so tapping Download immediately harvests all qualities.
 * 3. Hooks `URL.createObjectURL`, `Blob`, `fetch`, and `XMLHttpRequest` for dynamic streams.
 */
object BlobBridge {

    /** Name the interface is registered under with `addJavascriptInterface`. */
    const val NAME = "AndroidBlobBridge"

    val SCRIPT: String = """
(function () {
  if (window.__mediaBridgeInstalled) {
    if (window.__dlScanNow) { try { window.__dlScanNow(); } catch (e) {} }
    return;
  }
  window.__mediaBridgeInstalled = true;

  var B = window.AndroidBlobBridge;
  if (!B) return;

  var CHUNK = 512 * 1024;
  var registry = {};
  var seenUrls = {};
  var seenAuthoritative = {};
  var remoteFetched = {};
  var remoteCache = {};
  var cachedGlobalsHref = '';
  var cachedCandidateKeys = null;
  var activePageHref = location.href;
  var overlayDismissCount = 0;
  var overlayHref = '';
  var origFetch = window.fetch ? window.fetch.bind(window) : null;

  function safe(fn) { try { return fn(); } catch (e) { return null; } }

  var adOrPreviewRe = /(exoclick|exosrv|exdynsrv|realsrv|magsrv|tsyndicate|trafficjunky|ptgncdn|adtng|trafficstars|juicyads|adsco\.re|popads|popcash|bngpt|plugrush|clickadu|hilltopads|adsterra|onclkds|etahub|wpadmngr|cdn4ads|gogukypynijoxy|mulewosizeseha|stripchat|xlirdr|chaturbate|livejasmin|bongacams|camsoda|myfreecams|doubleclick|googlesyndication|mediabook|videopreview|video_preview|hover_preview|_preview\.|_preview_|-preview\.|-preview-|\/preview\/|\/previews\/|thumb_preview|hover_video|_trailer\.|_trailer_|-trailer\.|-trailer-|\/trailer\/|\/trailers\/|_teaser\.|_teaser_|-teaser\.|-teaser-|\/preroll|_preroll|-preroll|pre-roll|\/midroll|\/postroll|\/bumper|_bumper|-bumper|site_bumper|site_intro|intro_logo|outro_logo|promo_video|\/promo\/|mozaique|timeline_screens|videothumbs|\/thumbs\/|\/thumb\/|storyboard|thumb-v|\.t\.mp4|\.t\.av1\.mp4|pix-fl\.phncdn\.com|pix-egi\.phncdn\.com|\.mp4\/plain\/|_fb\.mp4|180p_|event_reporting|\/movies\/[^\/]+\/download\/|\/init-|_init\.|init-v|\.mp4\/init|\/vast\.xml|\/vast\?|vast_tag=)/i;

  function cleanUrl(rawUrl) {
    if (!rawUrl || typeof rawUrl !== 'string') return '';
    var u = rawUrl.trim()
      .replace(/\\\//g, '/')
      .replace(/\\u002F/gi, '/')
      .replace(/\\u0026/gi, '&')
      .replace(/&amp;/g, '&');
    if (!u || u.indexOf('javascript:') === 0 || u.indexOf('data:') === 0 ||
        u.indexOf('about:') === 0 || u.indexOf('mailto:') === 0) return '';
    if (/^[0-9a-fA-F]{10,}${'$'}/.test(u)) return '';
    if (u.indexOf('function/0/') === 0) return '';
    u = u.replace(/([?&])(bytestart|byteend|range|rn|rbuf)=[^&#]*/gi, '${'$'}1')
      .replace(/\?&/g, '?')
      .replace(/[?&]+${'$'}/, '');
    var resolved = u;
    try {
      if (typeof document !== 'undefined' && document && document.createElement) {
        var a = document.createElement('a');
        a.href = u;
        resolved = a.href || u;
      }
    } catch (e) {
      resolved = u;
    }
    if (!resolved) return '';
    if (/(?:\/[0-9a-fA-F]{32,}|\/(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{10,31}|\/videos\/[0-9a-fA-F]{10,})\/?(?:[?#]|${'$'})/i.test(resolved)) {
      return '';
    }
    return resolved;
  }

  function hasOnlyHexMediaSrc(videoEl) {
    if (!videoEl) return false;
    var rawAttr = (videoEl.getAttribute && videoEl.getAttribute('src')) ? String(videoEl.getAttribute('src')).trim() : '';
    if (rawAttr) {
      return /^[0-9a-fA-F]{10,}${'$'}/.test(rawAttr);
    }
    if (videoEl.querySelectorAll) {
      var sources = videoEl.querySelectorAll('source[src]');
      if (sources && sources.length > 0) {
        for (var i = 0; i < sources.length; i++) {
          var sEl = sources[i];
          var sAttr = (sEl && sEl.getAttribute && sEl.getAttribute('src')) ? String(sEl.getAttribute('src')).trim() : '';
          if (!/^[0-9a-fA-F]{10,}${'$'}/.test(sAttr)) return false;
        }
        return true;
      }
    }
    return false;
  }

  function inferQuality(str) {
    if (!str) return 0;
    var s = String(str).replace(/\/multi=[^\/]+\//gi, '/');
    if (/^\d{3,4}${'$'}/.test(s)) {
      var n = parseInt(s, 10);
      if ([2160,1440,1080,720,576,540,480,360,270,250,240,180,160,144].indexOf(n) > -1) return n;
    }
    var m = s.match(/(?:^|[^0-9])(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)p(?:[^a-z0-9]|${'$'})/i);
    if (m) return parseInt(m[1], 10);
    var d = s.match(/(?:[_\/=-]|quality=|res=)(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)(?:[._\/&?-]|${'$'})/i);
    if (d) return parseInt(d[1], 10);
    if (/4k|uhd/i.test(s)) return 2160;
    if (/2k|qhd/i.test(s)) return 1440;
    if (/fhd/i.test(s)) return 1080;
    return 0;
  }

  function guessMime(url, explicitMime) {
    if (explicitMime && typeof explicitMime === 'string' && explicitMime.length > 0) return explicitMime;
    var lower = url.toLowerCase();
    if (lower.indexOf('.m3u8') > -1) return 'application/x-mpegURL';
    if (lower.indexOf('.mpd') > -1) return 'application/dash+xml';
    if (lower.indexOf('.m4a') > -1 || lower.indexOf('.aac') > -1) return 'audio/mp4';
    if (lower.indexOf('.mp3') > -1) return 'audio/mpeg';
    if (lower.indexOf('.webm') > -1) return 'video/webm';
    return 'video/mp4';
  }

  /* KVS (Kernel Video Sharing) function/0/ URL decryption ---------------- */
  function decryptKvsUrl(rawUrl, licenseCode) {
    if (!rawUrl || typeof rawUrl !== 'string') return '';
    if (rawUrl.indexOf('function/0/') !== 0) return rawUrl;
    var stripped = rawUrl.substring('function/0/'.length);
    if (!licenseCode || typeof licenseCode !== 'string') return stripped;
    return safe(function () {
      var lic = licenseCode.replace(/\${'$'}/g, '');
      if (lic.length < 6) return stripped;
      var licValues = [];
      var modLic = '';
      for (var i = 0; i < lic.length; i++) {
        var d = parseInt(lic.charAt(i), 10);
        var v = (!isNaN(d) && d !== 0) ? d : 1;
        licValues.push(v);
        modLic += String(v);
      }
      var center = Math.floor(modLic.length / 2);
      var front = parseInt(modLic.substring(0, center + 1), 10);
      var back = parseInt(modLic.substring(center), 10);
      var modStr = String(4 * Math.abs(front - back)).substring(0, center + 1);
      var token = [];
      for (var idx = 0; idx < modStr.length; idx++) {
        var cur = parseInt(modStr.charAt(idx), 10) || 0;
        for (var off = 0; off < 4; off++) {
          var lv = licValues[(idx + off) % licValues.length] || 1;
          token.push((lv + cur) % 16);
        }
      }
      var parts = stripped.split('/');
      for (var p = 3; p < parts.length; p++) {
        if (/^[0-9a-f]{32}/i.test(parts[p])) {
          var hashChars = parts[p].substring(0, 32).split('');
          var tail = parts[p].substring(32);
          for (var hi = 31; hi >= 0; hi--) {
            var sum = 0;
            for (var ti = hi; ti < token.length; ti++) sum += token[ti];
            var swap = (hi + sum) % 32;
            var tmp = hashChars[hi];
            hashChars[hi] = hashChars[swap];
            hashChars[swap] = tmp;
          }
          parts[p] = hashChars.join('') + tail;
          break;
        }
      }
      return parts.join('/');
    }) || stripped;
  }

  /* xHamster module 89999 7-PRNG XOR hex decryptor + module 38167 hostMap */
  function decodeXhHexSegment(hex) {
    if (!hex || typeof hex !== 'string' || hex.length < 12 || (hex.length % 2) !== 0 || !/^[0-9a-fA-F]+${'$'}/.test(hex)) return '';
    var b = new Uint8Array(hex.length >> 1);
    for (var k = 0; k < b.length; k++) b[k] = parseInt(hex.substr(k * 2, 2), 16);
    var algo = b[0];
    var s = (b[1] | (b[2] << 8) | (b[3] << 16) | (b[4] << 24)) | 0;
    var step;
    if (algo === 1) {
      step = function () { s = (Math.imul(s, 1664525) + 1013904223) | 0; return s & 255; };
    } else if (algo === 2) {
      step = function () { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return s & 255; };
    } else if (algo === 3) {
      step = function () {
        s = (s + 0x9e3779b9) | 0;
        var e = s ^ (s >>> 16);
        e = Math.imul(e, 0x85ebca77 | 0);
        e ^= e >>> 13;
        e = Math.imul(e, 0xc2b2ae3d | 0);
        return (e ^ (e >>> 16)) & 255;
      };
    } else if (algo === 4) {
      step = function () {
        s = (s + 0x6d2b79f5) | 0;
        var e = (s << 7) | (s >>> 25);
        e = (e + 0x9e3779b9) | 0;
        e ^= e >>> 11;
        e = Math.imul(e, 0x27d4eb2d);
        return e & 255;
      };
    } else if (algo === 5) {
      step = function () { s ^= s << 7; s ^= s >>> 9; s ^= s << 8; s = (s + 0xa5a5a5a5) | 0; return s & 255; };
    } else if (algo === 6) {
      step = function () {
        s = (Math.imul(s, 0x2c9277b5) + (0xac564b05 | 0)) | 0;
        return ((s ^ (s >>> 18)) >>> ((s >>> 27) & 31)) & 255;
      };
    } else if (algo === 7) {
      step = function () {
        s = (s + 0x9e3779b9) | 0;
        var e = s ^ (s << 5);
        e = Math.imul(e, 0x7feb352d);
        e ^= e >>> 15;
        e = Math.imul(e, 0x846ca68b | 0);
        return e & 255;
      };
    } else {
      return '';
    }
    var str = '';
    for (var idx = 5; idx < b.length; idx++) {
      var decByte = (b[idx] ^ step()) & 255;
      if (decByte < 32 || decByte > 126) return '';
      str += String.fromCharCode(decByte);
    }
    return str.trim();
  }

  function decryptXhUrl(raw, hostSub) {
    if (!raw || typeof raw !== 'string') return '';
    var u = raw.trim();
    if (!u) return '';
    var dec = '';
    if (u.length >= 12 && (u.length % 2) === 0 && /^[0-9a-fA-F]+${'$'}/.test(u)) {
      dec = decodeXhHexSegment(u);
    }
    if (!dec && u.indexOf('/') > -1) {
      var schemeIdx = u.indexOf('://');
      var slashIdx = schemeIdx > -1 ? u.indexOf('/', schemeIdx + 3)
        : (u.indexOf('//') === 0 ? u.indexOf('/', 2) : u.indexOf('/'));
      if (slashIdx > -1 && slashIdx + 1 < u.length) {
        var start = slashIdx + 1;
        var nextSlash = u.indexOf('/', start);
        var nextComma = u.indexOf(',', start);
        var end = (nextSlash > -1 && nextComma > -1) ? Math.min(nextSlash, nextComma)
          : (nextSlash > -1 ? nextSlash : (nextComma > -1 ? nextComma : u.length));
        if (end > start) {
          var decSeg = decodeXhHexSegment(u.substring(start, end));
          if (decSeg) {
            dec = u.substring(0, start) + decSeg + u.substring(end);
          }
        }
      }
    }
    if (!dec) dec = u;
    if (dec.indexOf('//') === 0) {
      dec = 'https:' + dec;
    }
    var sub = (hostSub && typeof hostSub === 'string') ? hostSub.trim() : '';
    if (sub) {
      var m = dec.match(/^(https?:\/\/)([^\/?#]+)([\s\S]*)${'$'}/i);
      if (m) {
        var scheme = m[1];
        var authority = m[2];
        var rest = m[3];
        if (authority.charAt(0) !== '[' && authority.indexOf('@') === -1) {
          var colonIdx = authority.indexOf(':');
          var hostOnly = colonIdx >= 0 ? authority.substring(0, colonIdx) : authority;
          var portPart = colonIdx >= 0 ? authority.substring(colonIdx) : '';
          if (!/^\d+(?:\.\d+){3}${'$'}/.test(hostOnly)) {
            var dotIdx = hostOnly.indexOf('.');
            if (dotIdx > 0 && dotIdx < hostOnly.length - 1) {
              dec = scheme + sub + hostOnly.substring(dotIdx) + portPart + rest;
            }
          }
        }
      }
    }
    return dec;
  }

  function parseIsoDuration(iso) {
    if (!iso || typeof iso !== 'string') return 0;
    var m = iso.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/i);
    if (!m) return 0;
    return (parseInt(m[1] || '0', 10) * 3600) + (parseInt(m[2] || '0', 10) * 60) + parseInt(m[3] || '0', 10);
  }

  function getMetaInfo() {
    var ogTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
    var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
    var ogDur = document.querySelector('meta[property="video:duration"], meta[itemprop="duration"]');
    var ogHeight = document.querySelector('meta[property="og:video:height"]');
    var t = (ogTitle && ogTitle.content) ? ogTitle.content.trim() : '';
    var img = (ogImg && ogImg.content) ? cleanUrl(ogImg.content) : '';
    var dur = 0;
    var jsonLdHeight = 0;
    if (ogDur && ogDur.content) {
      var rawD = ogDur.content.trim();
      if (/^\d+${'$'}/.test(rawD)) dur = parseInt(rawD, 10);
    }
    if (ogHeight && ogHeight.content) {
      jsonLdHeight = parseInt(ogHeight.content, 10) || 0;
    }
    var jsonLdContentUrl = '';
    safe(function () {
      function collectLd(node, out, depth) {
        var d = depth || 0;
        if (!node || typeof node !== 'object' || d >= 6) return;
        if (Array.isArray(node)) {
          for (var k = 0; k < node.length; k++) collectLd(node[k], out, d + 1);
          return;
        }
        out.push(node);
        if (node['@graph']) collectLd(node['@graph'], out, d + 1);
        if (node.video) collectLd(node.video, out, d + 1);
        if (node.mainEntity) collectLd(node.mainEntity, out, d + 1);
        if (node.subjectOf) collectLd(node.subjectOf, out, d + 1);
      }
      var ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
      for (var i = 0; i < ldScripts.length; i++) {
        var raw = (ldScripts[i].textContent || '').trim();
        if (!raw) continue;
        var items = [];
        safe(function () { collectLd(JSON.parse(raw), items); });
        var fallbackLdTitle = '';
        for (var j = 0; j < items.length; j++) {
          var obj = items[j];
          if (!obj || typeof obj !== 'object') continue;
          var oType = String(obj['@type'] || '').toLowerCase();
          var isMediaNode = oType.indexOf('video') > -1 || oType.indexOf('audio') > -1 || oType.indexOf('clip') > -1 || oType.indexOf('movie') > -1 || !!obj.contentUrl || !!obj.embedUrl;
          if (obj.name && typeof obj.name === 'string') {
            if (isMediaNode && !t) t = obj.name.trim();
            else if (!fallbackLdTitle) fallbackLdTitle = obj.name.trim();
          }
          if (!img) {
            var rawLdImg = obj.thumbnailUrl || obj.image || obj.primaryImageOfPage || obj.thumbnail;
            var th = Array.isArray(rawLdImg) ? rawLdImg[0] : rawLdImg;
            if (th && typeof th === 'object') th = th.contentUrl || th.url || th['@id'];
            if (typeof th === 'string' && th) img = cleanUrl(th);
          }
          if (!dur && obj.duration) dur = parseIsoDuration(String(obj.duration)) || dur;
          if (!jsonLdHeight && obj.height) {
            var parsedH = parseInt(obj.height, 10) || 0;
            if (parsedH > 0) jsonLdHeight = parsedH;
          }
          if (!jsonLdContentUrl && obj.contentUrl && typeof obj.contentUrl === 'string' && obj.contentUrl.indexOf('http') === 0) {
            jsonLdContentUrl = cleanUrl(obj.contentUrl);
          }
        }
        if (!t && fallbackLdTitle) t = fallbackLdTitle;
      }
    });
    if (!img) {
      safe(function () {
        var cssNodes = document.querySelectorAll('.BG--Mycima-Single,.wecima,.mycima,.Poster,.poster,.Single--Poster,.jw-preview,.vjs-poster,.plyr__poster,[data-lazy-style],[style*="--image"],[style*="background-image"]');
        for (var k = 0; k < cssNodes.length && !img; k++) {
          var st = (cssNodes[k].getAttribute('data-lazy-style') || '') + ' ' + (cssNodes[k].getAttribute('style') || '');
          var cm = st.match(/url\(\s*['"]?([^'")\s]+)['"]?\s*\)/i);
          if (cm && cm[1]) img = cleanUrl(cm[1]);
        }
        if (!img) {
          var imgs = document.querySelectorAll('.Poster img,.poster img,.Single--Poster img,.thumb img,.thumbnail img,img.wp-post-image,img[class*="poster" i],img[class*="thumb" i],img[class*="cover" i]');
          for (var n = 0; n < imgs.length && !img; n++) {
            var ci = imgs[n].getAttribute('data-lazy-src') || imgs[n].getAttribute('data-src') || imgs[n].getAttribute('data-original') || imgs[n].currentSrc || imgs[n].src;
            if (ci && !/logo|favicon|avatar|icon|sprite|placeholder|blank/i.test(ci)) img = cleanUrl(ci);
          }
        }
      });
    }
    if (!t) {
      var h1 = document.querySelector('h1');
      if (h1 && h1.innerText) t = h1.innerText.trim();
    }
    if (!t && document.title) t = (document.title || '').trim();
    return { title: t, thumb: img, duration: dur, jsonLdContentUrl: jsonLdContentUrl, jsonLdHeight: jsonLdHeight };
  }

  function reportAuthoritative(rawUrl, mime, quality, title, thumb, durationSec) {
    safe(function () {
      var full = cleanUrl(rawUrl);
      if (!full || adOrPreviewRe.test(full)) return;
      if (full.indexOf('http://') !== 0 && full.indexOf('https://') !== 0) return;
      var m = guessMime(full, mime);
      var isMasterHls = m === 'application/x-mpegURL' && /(_TPL_|\/multi=|\/hls\.m3u8)/i.test(full);
      var q = quality > 0 ? quality : (isMasterHls ? 0 : (inferQuality(full) || 0));
      var key = full + '|' + q;
      if (seenAuthoritative[key]) return;
      seenAuthoritative[key] = true;
      seenUrls[full] = true;
      if (B.onPlayerStreamDetected) {
        B.onPlayerStreamDetected(full, m, q, title || '', thumb || '', durationSec || 0, location.href);
      } else if (B.onHttpMediaDetected) {
        B.onHttpMediaDetected(full, m, location.href);
      }
    });
  }

  function reportUrl(rawUrl, fallbackMime) {
    safe(function () {
      var full = cleanUrl(rawUrl);
      if (!full || adOrPreviewRe.test(full)) return;
      if (seenUrls[full]) return;
      if (full.indexOf('blob:') === 0) {
        seenUrls[full] = true;
        B.onBlobDetectedInPlayer(full, location.href);
      } else if ((full.indexOf('http://') === 0 || full.indexOf('https://') === 0) && B.onHttpMediaDetected) {
        seenUrls[full] = true;
        B.onHttpMediaDetected(full, guessMime(full, fallbackMime), location.href);
      }
    });
  }

  /* 1. Hook URL.createObjectURL & Blob ----------------------------------- */
  safe(function () {
    if (typeof URL !== 'undefined' && URL && URL.createObjectURL) {
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
    }
    if (typeof URL !== 'undefined' && URL && URL.revokeObjectURL) {
      var nativeRevoke = URL.revokeObjectURL.bind(URL);
      URL.revokeObjectURL = function (u) {
        delete registry[u];
        return nativeRevoke(u);
      };
    }
    if (window.Blob && window.Blob.prototype) {
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
    }
  });

  /* 2. Watch XHR/fetch for dynamic manifests & get_media JSON ------------ */
  var mediaPattern = /(?:\.(m3u8|mpd|mp4|m4a|mp3|webm|mov|aac)(\?|#|${'$'})|\/(?:hls\d?|m3u8)\/[^"'\s]+\.(?:txt|m3u)(\?|#|${'$'})|\/(?:master|index|playlist)\.txt(\?|#|${'$'}))/i;

  function scanJsonMediaResponse(text, meta) {
    safe(function () {
      if (!text || text.length < 10) return;
      if (text.charAt(0) === '[') {
        var arr = JSON.parse(text);
        for (var i = 0; i < arr.length; i++) {
          var item = arr[i];
          if (item && item.videoUrl && typeof item.videoUrl === 'string' && !item.remote) {
            var q = Array.isArray(item.quality) ? 0 : (inferQuality(item.quality) || 0);
            var fmt = (item.format || '').toLowerCase();
            var mime = fmt === 'hls' ? 'application/x-mpegURL' : 'video/mp4';
            reportAuthoritative(item.videoUrl, mime, q, meta.title, meta.thumb, meta.duration);
          }
        }
        return;
      }
      if (text.indexOf('video_versions') > -1 || text.indexOf('playable_url') > -1 || text.indexOf('playAddr') > -1) {
        var unescaped = text.replace(/\\\//g, '/').replace(/\\u002F/gi, '/').replace(/\\u0026/gi, '&');
        var matches = unescaped.match(/https?:\/\/[^"'\s<>\\]+?\.(mp4|m4a|mp3|m3u8|mpd)[^"'\s<>\\]*/gi);
        if (matches) {
          for (var j = 0; j < matches.length && j < 15; j++) {
            var u = matches[j];
            if (!adOrPreviewRe.test(u)) reportUrl(u, '');
          }
        }
      }
    });
  }

  if (origFetch) {
    window.fetch = function (input, init) {
      safe(function () {
        var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        if (mediaPattern.test(u) && !adOrPreviewRe.test(u)) reportUrl(u, '');
      });
      return origFetch(input, init).then(function (resp) {
        safe(function () {
          var ct = (resp.headers && resp.headers.get) ? (resp.headers.get('content-type') || '') : '';
          var ru = resp.url || '';
          if (ru.indexOf('get_media') > -1 || ru.indexOf('/media/mp4') > -1 ||
              ((ct.indexOf('json') > -1 || ru.indexOf('graphql') > -1) && !adOrPreviewRe.test(ru))) {
            resp.clone().text().then(function (t) {
              scanJsonMediaResponse(t, getMetaInfo());
            }).catch(function () {});
          }
        });
        return resp;
      });
    };
  }

  if (window.XMLHttpRequest && XMLHttpRequest.prototype.open) {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      var reqUrl = typeof url === 'string' ? url : '';
      safe(function () {
        if (reqUrl && mediaPattern.test(reqUrl) && !adOrPreviewRe.test(reqUrl)) reportUrl(reqUrl, '');
      });
      this.addEventListener('load', function () {
        safe(function () {
          if (reqUrl && !adOrPreviewRe.test(reqUrl) &&
              (reqUrl.indexOf('get_media') > -1 || reqUrl.indexOf('/media/mp4') > -1 ||
               reqUrl.indexOf('graphql') > -1 || reqUrl.indexOf('/api/') > -1)) {
            scanJsonMediaResponse(this.responseText, getMetaInfo());
          }
        }.bind(this));
      });
      return origOpen.apply(this, arguments);
    };
  }

  function getCandidateWindowKeys(forceRefresh) {
    if (!forceRefresh && cachedCandidateKeys && cachedGlobalsHref === location.href) {
      return cachedCandidateKeys;
    }
    var out = [];
    var keys = Object.keys(window);
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      if (k.indexOf('flashvars') === 0 || /^t[0-9a-f]{6,}${'$'}/i.test(k)) {
        out.push(k);
      } else if (k.length < 32 && k.indexOf('webkit') !== 0 && k.indexOf('on') !== 0) {
        try {
          var v = window[k];
          var inner = (v && typeof v === 'object' && v !== window && v !== document)
            ? (v.conf || v.flashvars || v)
            : null;
          if (inner && typeof inner === 'object' &&
              (typeof inner.video_url === 'string' || typeof inner.video_alt_url === 'string') &&
              (inner.license_code || inner.rnd || inner.kt_player || v.conf || v.flashvars)) {
            out.push(k);
          }
        } catch (e) {}
      }
    }
    cachedGlobalsHref = location.href;
    cachedCandidateKeys = out;
    return out;
  }

  function inferKvsQuality(cfg, fieldName, qText, u) {
    var kq = inferQuality(qText) || inferQuality(u) || 0;
    if (!kq && fieldName === 'video_url') {
      var ph = parseInt(cfg.preview_height1 || cfg.preview_height || 0, 10) || 0;
      var isHd = String(cfg.video_url_hd || '') === '1' || cfg.video_url_hd === true;
      if (isHd && (ph === 0 || ph >= 720)) kq = 720;
      else if (ph >= 1080) kq = 1080;
      else if (ph >= 720) kq = 720;
      else kq = 480;
    }
    return kq;
  }

  /* 3. Authoritative Site & Player Extractors ---------------------------- */
  function extractAuthoritativePlayers(meta, forceRefresh) {
    var foundCount = 0;

    // A. Pornhub (window.flashvars_<id> & get_media) + KVS (player_obj, kvsplayer, #kt_player, t<hex>)
    safe(function () {
      var configs = [];
      var seenCfg = [];
      function pushCfg(obj) {
        if (!obj || typeof obj !== 'object') return;
        for (var c = 0; c < seenCfg.length; c++) {
          if (seenCfg[c] === obj) return;
        }
        seenCfg.push(obj);
        configs.push(obj);
      }

      if (window.player_obj && typeof window.player_obj === 'object') {
        pushCfg(window.player_obj.conf || window.player_obj.flashvars || window.player_obj);
      }
      if (window.kvsplayer && typeof window.kvsplayer === 'object') {
        var kpKeys = Object.keys(window.kvsplayer);
        for (var kp = 0; kp < kpKeys.length; kp++) {
          var inst = window.kvsplayer[kpKeys[kp]];
          if (inst && typeof inst === 'object') {
            pushCfg(inst.conf || inst.flashvars || inst);
          }
        }
      }
      var candKeys = getCandidateWindowKeys(forceRefresh);
      for (var k = 0; k < candKeys.length; k++) {
        var cv = window[candKeys[k]];
        if (cv && typeof cv === 'object') {
          pushCfg(cv.conf || cv.flashvars || cv);
        }
      }

      var kvsLiveFound = 0;
      var kvsFields = [
        ['video_url', 'video_url_text', 'video_url_redirect'],
        ['video_alt_url', 'video_alt_url_text', 'video_alt_url_redirect'],
        ['video_alt_url2', 'video_alt_url2_text', 'video_alt_url2_redirect'],
        ['video_alt_url3', 'video_alt_url3_text', 'video_alt_url3_redirect'],
        ['video_alt_url4', 'video_alt_url4_text', 'video_alt_url4_redirect']
      ];

      for (var ci = 0; ci < configs.length; ci++) {
        var fv = configs[ci];
        if (!fv || typeof fv !== 'object') continue;

        var title = fv.video_title || meta.title || '';
        var thumb = fv.image_url || fv.thumb_url || fv.preview_url || meta.thumb || '';
        var dur = parseInt(fv.video_duration || meta.duration || 0, 10) || 0;
        if (B.onPageMetadata && (title || thumb)) B.onPageMetadata(title, thumb);

        // Pornhub mediaDefinitions
        if (Array.isArray(fv.mediaDefinitions)) {
          for (var i = 0; i < fv.mediaDefinitions.length; i++) {
            var md = fv.mediaDefinitions[i];
            if (!md || !md.videoUrl || typeof md.videoUrl !== 'string') continue;
            var vUrl = cleanUrl(md.videoUrl);
            if (!vUrl) continue;
            if (md.remote || vUrl.indexOf('get_media') > -1 || vUrl.indexOf('/media/mp4') > -1 || vUrl.indexOf('/media/hls') > -1) {
              if (remoteCache[vUrl]) {
                scanJsonMediaResponse(remoteCache[vUrl], { title: title, thumb: thumb, duration: dur });
              } else if (!remoteFetched[vUrl] && origFetch) {
                remoteFetched[vUrl] = true;
                (function (endpoint, t, th, d) {
                  origFetch(endpoint, { credentials: 'include' })
                    .then(function (r) { return r.text(); })
                    .then(function (txt) {
                      if (txt && txt.length >= 10) {
                        remoteCache[endpoint] = txt;
                      }
                      scanJsonMediaResponse(txt, { title: t, thumb: th, duration: d });
                    })
                    .catch(function () {
                      delete remoteFetched[endpoint];
                    });
                })(vUrl, title, thumb, dur);
              }
            } else {
              var q = Array.isArray(md.quality) ? 0 : (inferQuality(md.quality) || 0);
              var fmt = (md.format || '').toLowerCase();
              var mime = (fmt === 'hls' || vUrl.indexOf('.m3u8') > -1) ? 'application/x-mpegURL' : 'video/mp4';
              reportAuthoritative(vUrl, mime, q, title, thumb, dur);
              foundCount++;
            }
          }
        }

        // KVS (never read event_reporting* honeypot fields)
        if (fv.video_url || fv.video_alt_url) {
          var ktVid = document.querySelector('#kt_player video, .kt-player video, video.fp-engine');
          if (ktVid) {
            var rawKtAttr = (ktVid.getAttribute && ktVid.getAttribute('src')) ? String(ktVid.getAttribute('src')).trim() : '';
            if (!hasOnlyHexMediaSrc(ktVid)) {
              var ktSrc = ktVid.currentSrc || ktVid.src || rawKtAttr || '';
              var cleanKtSrc = cleanUrl(ktSrc);
              if (cleanKtSrc && cleanKtSrc.indexOf('http') === 0 && !adOrPreviewRe.test(cleanKtSrc)) {
                var ktQ = inferKvsQuality(fv, 'video_url', fv.video_url_text || '', cleanKtSrc);
                reportAuthoritative(cleanKtSrc, 'video/mp4', ktQ, title, thumb, dur);
                foundCount++;
                kvsLiveFound++;
              }
            }
          }
          if (meta.jsonLdContentUrl && meta.jsonLdContentUrl.indexOf('http') === 0 &&
              /\/get_file\/\d+\//i.test(meta.jsonLdContentUrl) && !adOrPreviewRe.test(meta.jsonLdContentUrl)) {
            var ldQ = inferKvsQuality(fv, 'video_url', fv.video_url_text || '', meta.jsonLdContentUrl);
            reportAuthoritative(meta.jsonLdContentUrl, 'video/mp4', ldQ, title, thumb, dur);
            foundCount++;
            kvsLiveFound++;
          }

          var lic = fv.license_code || '';
          for (var kf = 0; kf < kvsFields.length; kf++) {
            var fName = kvsFields[kf][0];
            var tName = kvsFields[kf][1];
            var rName = kvsFields[kf][2];
            if (rName && (String(fv[rName] || '') === '1' || fv[rName] === true)) continue;
            var rawKvs = fv[fName];
            if (!rawKvs || typeof rawKvs !== 'string') continue;
            var qText = fv[tName] || '';
            if (rawKvs.indexOf('http') === 0) {
              var plainQ = inferKvsQuality(fv, fName, qText, rawKvs);
              reportAuthoritative(rawKvs, '', plainQ, title, thumb, dur);
              foundCount++;
            } else if (rawKvs.indexOf('function/0/') === 0) {
              if (fName === 'video_url' && kvsLiveFound > 0 && /\/get_file\/\d+\/[0-9a-fA-F]{38,}\//.test(rawKvs)) {
                continue;
              }
              var decUrl = decryptKvsUrl(rawKvs, lic);
              var decQ = inferKvsQuality(fv, fName, qText, decUrl);
              reportAuthoritative(decUrl, '', decQ, title, thumb, dur);
              foundCount++;
            }
          }
        }
      }

      // Closure-scoped KVS fallback when configs.length === 0 (or no global KVS object exposed)
      if (kvsLiveFound === 0) {
        var ktVidFallback = document.querySelector('#kt_player video, .kt-player video, video.fp-engine');
        if (ktVidFallback) {
          var rawFbAttr = (ktVidFallback.getAttribute && ktVidFallback.getAttribute('src')) ? String(ktVidFallback.getAttribute('src')).trim() : '';
          if (!hasOnlyHexMediaSrc(ktVidFallback)) {
            var rawFbSrc = ktVidFallback.currentSrc || ktVidFallback.src || rawFbAttr || '';
            var cleanFbSrc = cleanUrl(rawFbSrc);
            if (cleanFbSrc && cleanFbSrc.indexOf('http') === 0 && !adOrPreviewRe.test(cleanFbSrc)) {
              var fbDur = meta.duration || ((ktVidFallback.duration && isFinite(ktVidFallback.duration) && ktVidFallback.duration > 1) ? Math.round(ktVidFallback.duration) : 0);
              var fbQ = inferQuality(cleanFbSrc) || meta.jsonLdHeight || (/\/get_file\/\d+\//i.test(cleanFbSrc) ? 480 : 0);
              if (B.onPageMetadata && (meta.title || meta.thumb)) B.onPageMetadata(meta.title, meta.thumb);
              reportAuthoritative(cleanFbSrc, 'video/mp4', fbQ, meta.title, meta.thumb, fbDur);
              foundCount++;
              kvsLiveFound++;
            }
          }
        }
        if (meta.jsonLdContentUrl && meta.jsonLdContentUrl.indexOf('http') === 0 && !adOrPreviewRe.test(meta.jsonLdContentUrl)) {
          if (/\/get_file\/\d+\//i.test(meta.jsonLdContentUrl)) {
            var ldFbQ = inferQuality(meta.jsonLdContentUrl) || meta.jsonLdHeight || 480;
            if (B.onPageMetadata && (meta.title || meta.thumb)) B.onPageMetadata(meta.title, meta.thumb);
            reportAuthoritative(meta.jsonLdContentUrl, 'video/mp4', ldFbQ, meta.title, meta.thumb, meta.duration);
            foundCount++;
            kvsLiveFound++;
          } else if (foundCount === 0 && /\.(mp4|m3u8|webm|m4v)(\?|#|${'$'})/i.test(meta.jsonLdContentUrl)) {
            var ldFbMime = meta.jsonLdContentUrl.toLowerCase().indexOf('.m3u8') > -1 ? 'application/x-mpegURL' : 'video/mp4';
            if (B.onPageMetadata && (meta.title || meta.thumb)) B.onPageMetadata(meta.title, meta.thumb);
            reportAuthoritative(meta.jsonLdContentUrl, ldFbMime, inferQuality(meta.jsonLdContentUrl) || meta.jsonLdHeight || 0, meta.title, meta.thumb, meta.duration);
            foundCount++;
          }
        }
      }

      // Pornhub legacy quality_<N>p globals
      var qGlobals = [2160, 1440, 1080, 720, 480, 360, 240];
      for (var qg = 0; qg < qGlobals.length; qg++) {
        var qVal = window['quality_' + qGlobals[qg] + 'p'];
        if (qVal && typeof qVal === 'string' && qVal.indexOf('http') === 0) {
          reportAuthoritative(qVal, 'video/mp4', qGlobals[qg], meta.title, meta.thumb, meta.duration);
          foundCount++;
        }
      }
    });

    // B. XVideos & XNXX (window.html5player)
    safe(function () {
      var hp = window.html5player;
      if (!hp || typeof hp !== 'object') return;
      var title = hp.video_title || meta.title || '';
      var thumb = hp.url_thumb169 || hp.url_thumb || meta.thumb || '';
      var dur = parseInt(hp.video_duration || meta.duration || 0, 10) || 0;
      if (B.onPageMetadata && (title || thumb)) B.onPageMetadata(title, thumb);

      if (hp.url_hls) {
        reportAuthoritative(hp.url_hls, 'application/x-mpegURL', 0, title, thumb, dur);
        foundCount++;
      }
      var highClean = hp.url_high ? cleanUrl(hp.url_high) : '';
      var lowClean = hp.url_low ? cleanUrl(hp.url_low) : '';
      if (highClean) {
        reportAuthoritative(highClean, 'video/mp4', inferQuality(highClean) || 480, title, thumb, dur);
        foundCount++;
      }
      if (lowClean && lowClean !== highClean && lowClean.split('?')[0] !== highClean.split('?')[0]) {
        var lowQ = inferQuality(lowClean) || (lowClean.toLowerCase().indexOf('mp4_sd') > -1 ? 480 : 240);
        reportAuthoritative(lowClean, 'video/mp4', lowQ, title, thumb, dur);
        foundCount++;
      }
    });

    // C. xHamster (window.initials with module 89999 decryption + hostMap)
    safe(function () {
      var ini = window.initials;
      if (!ini || typeof ini !== 'object') return;
      var ve = ini.videoEntity || ini.videoModel || {};
      var title = ve.title || meta.title || '';
      var thumb = ve.thumbBig || ve.thumbURL || meta.thumb || '';
      var dur = parseInt(ve.duration || meta.duration || 0, 10) || 0;
      if (B.onPageMetadata && (title || thumb)) B.onPageMetadata(title, thumb);

      var hostMap = ini.hostMap || (ini.xplayerSettings && ini.xplayerSettings.hostMap) || {};
      var xps = (ini.xplayerSettings && ini.xplayerSettings.sources) || ve.sources || {};
      function resolveXhEntry(entry, primeSub, fbSub) {
        if (!entry) return '';
        if (entry.url) {
          var d1 = decryptXhUrl(entry.url, primeSub);
          if (d1 && d1.indexOf('http') === 0 && cleanUrl(d1)) return d1;
        }
        if (entry.fallback) {
          var d2 = decryptXhUrl(entry.fallback, fbSub);
          if (d2 && d2.indexOf('http') === 0 && cleanUrl(d2)) return d2;
        }
        return '';
      }
      var hlsReported = false;
      if (xps.hls && typeof xps.hls === 'object') {
        var hlsCandidates = [
          [xps.hls.h264, 'hls.h264'],
          [xps.hls.mp4, 'hls.mp4']
        ];
        for (var hc = 0; hc < hlsCandidates.length; hc++) {
          var entry = hlsCandidates[hc][0];
          var hmKey = hlsCandidates[hc][1];
          if (entry && (entry.url || entry.fallback)) {
            var decHls = resolveXhEntry(entry, hostMap[hmKey], hostMap[hmKey + '.fallback']);
            if (decHls && decHls.indexOf('http') === 0) {
              reportAuthoritative(decHls, 'application/x-mpegURL', 0, title, thumb, dur);
              foundCount++;
              hlsReported = true;
              break;
            }
          }
        }
        if (!hlsReported) {
          var hKeys = Object.keys(xps.hls);
          for (var hk = 0; hk < hKeys.length; hk++) {
            var kName = hKeys[hk];
            var fbEntry = xps.hls[kName];
            if (fbEntry && (fbEntry.url || fbEntry.fallback)) {
              var decAny = resolveXhEntry(fbEntry, hostMap['hls.' + kName], hostMap['hls.' + kName + '.fallback']);
              if (decAny && decAny.indexOf('http') === 0) {
                if (decAny.indexOf('_TPL_.av1.mp4.m3u8') > -1) {
                  reportAuthoritative(decAny.replace('_TPL_.av1.mp4.m3u8', '_TPL_.h264.mp4.m3u8'), 'application/x-mpegURL', 0, title, thumb, dur);
                  reportAuthoritative(decAny, 'application/x-mpegURL', 0, title, thumb, dur);
                } else {
                  reportAuthoritative(decAny, 'application/x-mpegURL', 0, title, thumb, dur);
                }
                foundCount++;
                hlsReported = true;
                break;
              }
            }
          }
        }
      }
      if (xps.standard && typeof xps.standard === 'object') {
        var stdCodec = Array.isArray(xps.standard.h264) ? 'h264'
          : (Array.isArray(xps.standard.mp4) ? 'mp4' : '');
        var stdList = stdCodec ? xps.standard[stdCodec] : null;
        if (!Array.isArray(stdList)) {
          var sKeys = Object.keys(xps.standard);
          for (var sk = 0; sk < sKeys.length; sk++) {
            if (sKeys[sk] !== 'av1' && Array.isArray(xps.standard[sKeys[sk]])) {
              stdCodec = sKeys[sk];
              stdList = xps.standard[stdCodec];
              break;
            }
          }
        }
        if (Array.isArray(stdList)) {
          for (var si = 0; si < stdList.length; si++) {
            var item = stdList[si];
            if (!item) continue;
            var qKey = String(item.quality || si).trim();
            var qRaw = String(item.quality || item.label || '').trim();
            var pKey = 'standard.' + stdCodec + '.' + qKey + '.prime';
            var fKey = 'standard.' + stdCodec + '.' + qKey + '.fallback';
            var decStd = resolveXhEntry(item, hostMap[pKey], hostMap[fKey]);
            if (!decStd || decStd.indexOf('http') !== 0) continue;
            if (decStd.indexOf('/movies/') > -1 && decStd.indexOf('/download') > -1) continue;
            if (qRaw.toLowerCase() === 'auto' || decStd.indexOf('.m3u8') > -1) {
              if (decStd.indexOf('.m3u8') > -1 && !hlsReported) {
                reportAuthoritative(decStd, 'application/x-mpegURL', 0, title, thumb, dur);
                foundCount++;
                hlsReported = true;
              }
              continue;
            }
            var sq = inferQuality(qRaw) || inferQuality(decStd) || 0;
            reportAuthoritative(decStd, 'video/mp4', sq, title, thumb, dur);
            foundCount++;
          }
        }
      }
    });

    // D. JWPlayer (used by many tube/embed sites; excludes advertising VAST tracks)
    safe(function () {
      if (typeof window.jwplayer === 'function') {
        var jw = window.jwplayer();
        if (jw && typeof jw.getPlaylist === 'function') {
          var pl = jw.getPlaylist();
          if (Array.isArray(pl) && pl.length > 0 && pl[0]) {
            var item = pl[0];
            var title = item.title || meta.title || '';
            var thumb = item.image || meta.thumb || '';
            var dur = parseInt(item.duration || meta.duration || 0, 10) || 0;
            var sources = item.sources || item.allSources || [];
            for (var s = 0; s < sources.length; s++) {
              var src = sources[s];
              if (src && src.file) {
                var jq = inferQuality(src.label) || inferQuality(src.height) || inferQuality(src.file) || 0;
                reportAuthoritative(src.file, src.type || '', jq, title, thumb, dur);
                foundCount++;
              }
            }
          }
        }
      }
    });

    // E. Video.js players (skipping active ad mode)
    safe(function () {
      if (window.videojs && window.videojs.players) {
        var pKeys = Object.keys(window.videojs.players);
        for (var pk = 0; pk < pKeys.length; pk++) {
          var p = window.videojs.players[pKeys[pk]];
          if (!p) continue;
          if (p.ads && typeof p.ads.isInAdMode === 'function' && p.ads.isInAdMode()) continue;
          if (typeof p.currentSources === 'function') {
            var cs = p.currentSources() || [];
            for (var ci = 0; ci < cs.length; ci++) {
              if (cs[ci] && cs[ci].src) {
                var vq = inferQuality(cs[ci].label || cs[ci].res) || inferQuality(cs[ci].src) || 0;
                reportAuthoritative(cs[ci].src, cs[ci].type || '', vq, meta.title, meta.thumb, meta.duration);
                foundCount++;
              }
            }
          }
        }
      }
    });

    // E2. Embed player globals: window.links ({hls2, hls3, hls4, hls}) & Mixdrop window.MDCore.wurl
    safe(function () {
      if (window.links && typeof window.links === 'object') {
        var linkKeys = ['hls2', 'hls3', 'hls4', 'hls', 'file', 'src'];
        for (var lk = 0; lk < linkKeys.length; lk++) {
          var lKey = linkKeys[lk];
          var lVal = window.links[lKey];
          if (lVal && typeof lVal === 'string') {
            var lMime = lKey.indexOf('hls') === 0 ? 'application/x-mpegURL' : '';
            reportAuthoritative(lVal, lMime, 0, meta.title, meta.thumb, meta.duration);
            foundCount++;
          }
        }
      }
      if (window.MDCore && typeof window.MDCore === 'object' && typeof window.MDCore.wurl === 'string' && window.MDCore.wurl) {
        reportAuthoritative(window.MDCore.wurl, 'video/mp4', 0, meta.title, meta.thumb, meta.duration);
        foundCount++;
      }
    });

    // F. Preload HLS links (<link rel="preload" as="fetch" href="...m3u8">) only as fallback
    if (foundCount === 0) {
      safe(function () {
        var links = document.querySelectorAll('link[rel="preload"][href*=".m3u8"]');
        for (var l = 0; l < links.length; l++) {
          var href = links[l].href;
          if (href && !adOrPreviewRe.test(href)) {
            if (href.indexOf('_TPL_.av1.mp4.m3u8') > -1) {
              reportAuthoritative(href.replace('_TPL_.av1.mp4.m3u8', '_TPL_.h264.mp4.m3u8'), 'application/x-mpegURL', 0, meta.title, meta.thumb, meta.duration);
            }
            reportAuthoritative(href, 'application/x-mpegURL', 0, meta.title, meta.thumb, meta.duration);
            foundCount++;
          }
        }
      });
    }

    return foundCount;
  }

  function isExcludedMediaNode(el) {
    if (!el) return true;
    if (el.closest && el.closest(
      '.related, .related-videos, .thumb, .thumbs, .thumb-block, .video-thumb, ' +
      '[class*="preview"], [class*="vast"], [id*="vast"], .ima-ad-container, ' +
      '.fluid_ad_playing, [class*="preroll"], [id*="preroll"], [data-ad]'
    )) {
      return true;
    }
    var vid = el.tagName === 'SOURCE' ? el.parentElement : el;
    if (vid && vid.tagName === 'VIDEO') {
      if (vid.loop && vid.muted && !vid.controls) return true;
      if (vid.className && /vast|preroll|preview|bumper|teaser/i.test(vid.className)) return true;
      if (vid.videoWidth > 0 && vid.videoHeight > 0 && (vid.videoWidth < 180 || vid.videoHeight < 110)) {
        return true;
      }
      var attrW = parseInt(vid.getAttribute && vid.getAttribute('width') || 0, 10) || 0;
      var attrH = parseInt(vid.getAttribute && vid.getAttribute('height') || 0, 10) || 0;
      if (attrW > 0 && attrH > 0 && (attrW < 180 || attrH < 110)) {
        return true;
      }
    }
    return false;
  }

  function dismissAnnoyingOverlays() {
    safe(function () {
      if (overlayHref !== location.href) {
        overlayHref = location.href;
        overlayDismissCount = 0;
      }
      if (overlayDismissCount >= 2) return;
      var host = (location.hostname || '').toLowerCase();
      if (host.indexOf('instagram.com') > -1 || host.indexOf('tiktok.com') > -1 || host.indexOf('facebook.com') > -1) {
        var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"], div[data-bloks-name], .modal, ._a9-v');
        var dismissed = false;
        for (var i = 0; i < dialogs.length; i++) {
          var d = dialogs[i];
          var txt = (d.textContent || '').toLowerCase();
          if (txt.indexOf('watch this reel in the app') > -1 ||
              txt.indexOf('open instagram') > -1 ||
              txt.indexOf('use the app') > -1 ||
              txt.indexOf('open app') > -1 ||
              txt.indexOf('not now') > -1 ||
              txt.indexOf('log in') > -1 ||
              txt.indexOf('sign up') > -1 ||
              txt.indexOf('continue in app') > -1) {
            var closeBtn = d.querySelector('button[aria-label*="Close"], svg[aria-label*="Close"], [role="button"]');
            if (closeBtn && (closeBtn.textContent || '').toLowerCase().indexOf('open') === -1) {
              closeBtn.click();
            }
            d.style.display = 'none';
            dismissed = true;
          }
        }
        if (dismissed) {
          overlayDismissCount++;
          if (document.body) document.body.style.overflow = 'auto';
        }
      }
    });
  }

  function scanMedia(forceRefresh) {
    return safe(function () {
      if (activePageHref !== location.href) {
        activePageHref = location.href;
        seenAuthoritative = {};
        seenUrls = {};
        remoteFetched = {};
        remoteCache = {};
        cachedCandidateKeys = null;
      } else if (forceRefresh) {
        seenAuthoritative = {};
        seenUrls = {};
        for (var rk in remoteFetched) {
          if (Object.prototype.hasOwnProperty.call(remoteFetched, rk) && !remoteCache[rk]) {
            delete remoteFetched[rk];
          }
        }
        cachedCandidateKeys = null;
      }
      dismissAnnoyingOverlays();
      var meta = getMetaInfo();
      var authCount = extractAuthoritativePlayers(meta, !!forceRefresh);

      // Explicit <video><source> quality children (FluidPlayer / Plyr / HTML5 multi-source)
      var sourceNodes = document.querySelectorAll('video source[src], audio source[src]');
      var sourceCount = 0;
      for (var s = 0; s < sourceNodes.length; s++) {
        var sNode = sourceNodes[s];
        if (isExcludedMediaNode(sNode)) continue;
        var rawSAttr = (sNode.getAttribute && sNode.getAttribute('src')) ? String(sNode.getAttribute('src')).trim() : '';
        if (/^[0-9a-fA-F]{10,}${'$'}/.test(rawSAttr)) continue;
        var sUrl = cleanUrl(sNode.src || rawSAttr || '');
        if (!sUrl || adOrPreviewRe.test(sUrl)) continue;
        var qAttr = sNode.getAttribute('label') || sNode.getAttribute('title') ||
          sNode.getAttribute('res') || sNode.getAttribute('size') || sNode.getAttribute('data-quality') || '';
        var sq = inferQuality(qAttr) || inferQuality(sUrl) || 0;
        var parentTag = sNode.parentElement ? sNode.parentElement.tagName : 'VIDEO';
        var sMime = sNode.type || (parentTag === 'AUDIO' ? 'audio/mp4' : 'video/mp4');
        reportAuthoritative(sUrl, sMime, sq, meta.title, meta.thumb, meta.duration);
        sourceCount++;
      }

      // Only fall back to bare <video currentSrc> when no authoritative config or <source> list exists
      if (authCount === 0 && sourceCount === 0) {
        var mediaNodes = document.querySelectorAll('video, audio');
        for (var i = 0; i < mediaNodes.length; i++) {
          var node = mediaNodes[i];
          if (isExcludedMediaNode(node)) continue;
          if (node.tagName === 'VIDEO' && meta.duration >= 45 && node.duration > 0 && node.duration < 25) {
            continue;
          }
          if (hasOnlyHexMediaSrc(node)) continue;
          var rawSrc = node.currentSrc || node.src || '';
          var src = rawSrc.indexOf('blob:') === 0 ? rawSrc : cleanUrl(rawSrc);
          if (!src || adOrPreviewRe.test(src)) continue;
          var mime = node.tagName === 'AUDIO' ? 'audio/mp4' : 'video/mp4';
          var dur = (node.duration && isFinite(node.duration) && node.duration > 0)
            ? Math.round(node.duration) : meta.duration;
          if (src.indexOf('blob:') === 0) {
            reportUrl(src, mime);
          } else {
            reportAuthoritative(src, mime, inferQuality(src), meta.title, node.poster || meta.thumb, dur);
          }
        }
      }

      return JSON.stringify({ auth: authCount + sourceCount });
    }) || '{"auth":0}';
  }

  var scanTimer = null;
  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(function () {
      scanTimer = null;
      scanMedia(false);
    }, 800);
  }

  window.__dlScanNow = function () { return scanMedia(true); };
  scanMedia(false);
  setTimeout(function () { scanMedia(false); }, 400);
  document.addEventListener('DOMContentLoaded', function () { scanMedia(false); }, true);
  window.addEventListener('load', function () { scanMedia(false); }, true);
  window.addEventListener('popstate', function () { cachedCandidateKeys = null; scheduleScan(); }, true);
  document.addEventListener('loadedmetadata', scheduleScan, true);
  document.addEventListener('play', scheduleScan, true);

  safe(function () {
    if (typeof MutationObserver !== 'undefined' && document.documentElement) {
      var relevantTagRe = /^(VIDEO|AUDIO|SOURCE|SCRIPT|LINK)${'$'}/i;
      var obs = new MutationObserver(function (mutations) {
        for (var i = 0; i < mutations.length; i++) {
          var mut = mutations[i];
          if (mut.type === 'attributes') {
            if (mut.target && relevantTagRe.test(mut.target.tagName || '')) {
              scheduleScan();
              return;
            }
          } else if (mut.addedNodes && mut.addedNodes.length > 0) {
            for (var j = 0; j < mut.addedNodes.length; j++) {
              var n = mut.addedNodes[j];
              if (n && n.nodeType === 1) {
                if (relevantTagRe.test(n.tagName || '') ||
                    (n.querySelector && n.querySelector('video, audio, source, script, link[rel="preload"]'))) {
                  scheduleScan();
                  return;
                }
              }
            }
          }
        }
      });
      obs.observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['src', 'href']
      });
    }
  });

  /* 4. Resolve a blob and stream it to the native layer ------------------ */
  window.__resolveBlob = function (objectUrl, taskId) {
    var known = registry[objectUrl];
    var source = known
      ? Promise.resolve(known)
      : (origFetch || fetch)(objectUrl).then(function (r) { return r.blob(); });

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
