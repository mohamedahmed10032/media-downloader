package com.melmeligy.mediadownloader.intercept

/**
 * The JavaScript half of the media & Blob URL bridge.
 *
 * Injected on every navigation. It:
 * 1. Extracts authoritative multi-quality video/audio streams directly from player configs
 *    (`flashvars_<id>` + `get_media`, `html5player`, `window.initials`, KVS `kt_player`
 *    with `license_code` decryption, JWPlayer, VideoJS, Plyr, FluidPlayer, HTML5 `<source>`)
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
  var origFetch = window.fetch ? window.fetch.bind(window) : null;

  function safe(fn) { try { return fn(); } catch (e) { return null; } }

  var adOrPreviewRe = /(exoclick|exosrv|exdynsrv|realsrv|magsrv|tsyndicate|trafficjunky|ptgncdn|adtng|trafficstars|juicyads|adsco\.re|popads|popcash|bngpt|plugrush|clickadu|hilltopads|adsterra|onclkds|etahub|stripchat|xlirdr|chaturbate|livejasmin|bongacams|camsoda|doubleclick|googlesyndication|mediabook|videopreview|video_preview|hover_preview|_preview\.|_preview_|-preview\.|-preview-|\/preview\/|\/previews\/|thumb_preview|_trailer\.|_trailer_|-trailer\.|-trailer-|\/trailer\/|_teaser\.|_teaser_|\/preroll|_preroll|pre-roll|\/midroll|\/postroll|\/bumper|_bumper|site_bumper|site_intro|intro_logo|promo_video|\/promo\/|mozaique|timeline_screens|videothumbs|\/thumbs\/|\/thumb\/|\/vast\.xml|\/vast\?|vast_tag=)/i;

  function cleanUrl(rawUrl) {
    if (!rawUrl || typeof rawUrl !== 'string') return '';
    var u = rawUrl.trim()
      .replace(/\\\//g, '/')
      .replace(/\\u002F/gi, '/')
      .replace(/\\u0026/gi, '&')
      .replace(/&amp;/g, '&');
    if (!u || u.indexOf('javascript:') === 0 || u.indexOf('data:') === 0) return '';
    u = u.replace(/([?&])(bytestart|byteend|range|rn|rbuf)=[^&#]*/gi, '$1')
      .replace(/\?&/g, '?')
      .replace(/[?&]+$/, '');
    try {
      var a = document.createElement('a');
      a.href = u;
      return a.href || u;
    } catch (e) {
      return u;
    }
  }

  function inferQuality(str) {
    if (!str) return 0;
    var s = String(str);
    if (/^\d{3,4}$/.test(s)) {
      var n = parseInt(s, 10);
      if ([2160,1440,1080,720,576,540,480,360,270,250,240,180,160,144].indexOf(n) > -1) return n;
    }
    var m = s.match(/(?:^|[^0-9])(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)p(?:[^a-z0-9]|$)/i);
    if (m) return parseInt(m[1], 10);
    var d = s.match(/(?:[_\/=-]|quality=|res=)(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)(?:[._\/&?-]|$)/i);
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
      var lic = licenseCode.replace(/\$/g, '');
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

  function getMetaInfo() {
    var ogTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
    var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
    var ogDur = document.querySelector('meta[property="video:duration"], meta[itemprop="duration"]');
    var t = (ogTitle && ogTitle.content) ? ogTitle.content.trim() : (document.title || '').trim();
    var img = (ogImg && ogImg.content) ? cleanUrl(ogImg.content) : '';
    var dur = 0;
    if (ogDur && ogDur.content) {
      var rawD = ogDur.content.trim();
      if (/^\d+$/.test(rawD)) dur = parseInt(rawD, 10);
    }
    return { title: t, thumb: img, duration: dur };
  }

  function reportAuthoritative(rawUrl, mime, quality, title, thumb, durationSec) {
    safe(function () {
      var full = cleanUrl(rawUrl);
      if (!full || adOrPreviewRe.test(full)) return;
      if (full.indexOf('http://') !== 0 && full.indexOf('https://') !== 0) return;
      var q = quality || inferQuality(full) || 0;
      var key = full + '|' + q;
      if (seenAuthoritative[key]) return;
      seenAuthoritative[key] = true;
      seenUrls[full] = true;
      var m = guessMime(full, mime);
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
  var mediaPattern = /\.(m3u8|mpd|mp4|m4a|mp3|webm|mov|aac)(\?|#|$)/i;

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

  /* 3. Authoritative Site & Player Extractors ---------------------------- */
  function extractAuthoritativePlayers(meta) {
    var foundCount = 0;

    // A. Pornhub (window.flashvars_<id> & remote get_media MP4/HLS)
    safe(function () {
      var keys = Object.keys(window);
      for (var k = 0; k < keys.length; k++) {
        var key = keys[k];
        if (key.indexOf('flashvars') !== 0) continue;
        var fv = window[key];
        if (!fv || typeof fv !== 'object') continue;

        var title = fv.video_title || meta.title || '';
        var thumb = fv.image_url || fv.thumb_url || meta.thumb || '';
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
              if (!remoteFetched[vUrl] && origFetch) {
                remoteFetched[vUrl] = true;
                (function (endpoint, t, th, d) {
                  origFetch(endpoint, { credentials: 'include' })
                    .then(function (r) { return r.text(); })
                    .then(function (txt) {
                      scanJsonMediaResponse(txt, { title: t, thumb: th, duration: d });
                    })
                    .catch(function () {});
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

        // KVS (Kernel Video Sharing: Arabsex & tube sites using kt_player)
        var kvsFields = [
          ['video_url', 'video_url_text'],
          ['video_alt_url', 'video_alt_url_text'],
          ['video_alt_url2', 'video_alt_url2_text'],
          ['video_alt_url3', 'video_alt_url3_text'],
          ['video_alt_url4', 'video_alt_url4_text']
        ];
        var lic = fv.license_code || '';
        for (var kf = 0; kf < kvsFields.length; kf++) {
          var rawKvs = fv[kvsFields[kf][0]];
          var qText = fv[kvsFields[kf][1]] || '';
          if (rawKvs && typeof rawKvs === 'string' && (rawKvs.indexOf('http') === 0 || rawKvs.indexOf('function/0/') === 0)) {
            var decUrl = decryptKvsUrl(rawKvs, lic);
            var kq = inferQuality(qText) || inferQuality(decUrl) || 0;
            reportAuthoritative(decUrl, '', kq, title, fv.preview_url || thumb, dur);
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
      if (hp.url_high) {
        reportAuthoritative(hp.url_high, 'video/mp4', inferQuality(hp.url_high) || 480, title, thumb, dur);
        foundCount++;
      }
      if (hp.url_low) {
        reportAuthoritative(hp.url_low, 'video/mp4', inferQuality(hp.url_low) || 240, title, thumb, dur);
        foundCount++;
      }
    });

    // C. xHamster (window.initials)
    safe(function () {
      var ini = window.initials;
      if (!ini || typeof ini !== 'object') return;
      var ve = ini.videoEntity || ini.videoModel || {};
      var title = ve.title || meta.title || '';
      var thumb = ve.thumbBig || ve.thumbURL || meta.thumb || '';
      var dur = parseInt(ve.duration || meta.duration || 0, 10) || 0;
      if (B.onPageMetadata && (title || thumb)) B.onPageMetadata(title, thumb);

      var xps = (ini.xplayerSettings && ini.xplayerSettings.sources) || ve.sources || {};
      if (xps.hls && typeof xps.hls === 'object') {
        var hlsEntry = xps.hls.h264 || xps.hls.mp4 || null;
        if (!hlsEntry) {
          var hKeys = Object.keys(xps.hls);
          for (var hk = 0; hk < hKeys.length; hk++) {
            if (xps.hls[hKeys[hk]] && xps.hls[hKeys[hk]].url) {
              hlsEntry = xps.hls[hKeys[hk]];
              break;
            }
          }
        }
        if (hlsEntry && hlsEntry.url) {
          reportAuthoritative(hlsEntry.url, 'application/x-mpegURL', 0, title, thumb, dur);
          foundCount++;
        }
      }
      if (xps.standard && typeof xps.standard === 'object') {
        var stdList = xps.standard.h264 || xps.standard.mp4 || null;
        if (!Array.isArray(stdList)) {
          var sKeys = Object.keys(xps.standard);
          for (var sk = 0; sk < sKeys.length; sk++) {
            if (Array.isArray(xps.standard[sKeys[sk]])) {
              stdList = xps.standard[sKeys[sk]];
              break;
            }
          }
        }
        if (Array.isArray(stdList)) {
          for (var si = 0; si < stdList.length; si++) {
            var item = stdList[si];
            if (!item) continue;
            var primaryUrl = item.url || item.fallback;
            if (primaryUrl) {
              var sq = inferQuality(item.quality || item.label) || inferQuality(primaryUrl) || 0;
              reportAuthoritative(primaryUrl, 'video/mp4', sq, title, thumb, dur);
              foundCount++;
            }
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

    // F. Preload HLS links (<link rel="preload" as="fetch" href="...m3u8">)
    safe(function () {
      var links = document.querySelectorAll('link[rel="preload"][href*=".m3u8"]');
      for (var l = 0; l < links.length; l++) {
        var href = links[l].href;
        if (href && !adOrPreviewRe.test(href)) {
          reportAuthoritative(href, 'application/x-mpegURL', 0, meta.title, meta.thumb, meta.duration);
          foundCount++;
        }
      }
    });

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
      var rect = vid.getBoundingClientRect ? vid.getBoundingClientRect() : null;
      if (rect && rect.width > 0 && rect.height > 0 && (rect.width < 180 || rect.height < 110)) {
        return true;
      }
    }
    return false;
  }

  function dismissAnnoyingOverlays() {
    safe(function () {
      var host = (location.hostname || '').toLowerCase();
      if (host.indexOf('instagram.com') > -1 || host.indexOf('tiktok.com') > -1 || host.indexOf('facebook.com') > -1) {
        var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"], div[data-bloks-name], .modal, ._a9-v');
        for (var i = 0; i < dialogs.length; i++) {
          var d = dialogs[i];
          var txt = (d.innerText || '').toLowerCase();
          if (txt.indexOf('watch this reel in the app') > -1 ||
              txt.indexOf('open instagram') > -1 ||
              txt.indexOf('use the app') > -1 ||
              txt.indexOf('open app') > -1 ||
              txt.indexOf('not now') > -1 ||
              txt.indexOf('log in') > -1 ||
              txt.indexOf('sign up') > -1 ||
              txt.indexOf('continue in app') > -1) {
            var closeBtn = d.querySelector('button[aria-label*="Close"], svg[aria-label*="Close"], [role="button"]');
            if (closeBtn && (closeBtn.innerText || '').toLowerCase().indexOf('open') === -1) {
              closeBtn.click();
            }
            d.style.display = 'none';
          }
        }
        document.body.style.overflow = 'auto';
      }
    });
  }

  function scanMedia() {
    return safe(function () {
      dismissAnnoyingOverlays();
      var meta = getMetaInfo();
      var authCount = extractAuthoritativePlayers(meta);

      // Explicit <video><source> quality children (FluidPlayer / Plyr / HTML5 multi-source)
      var sourceNodes = document.querySelectorAll('video source[src], audio source[src]');
      var sourceCount = 0;
      for (var s = 0; s < sourceNodes.length; s++) {
        var sNode = sourceNodes[s];
        if (isExcludedMediaNode(sNode)) continue;
        var sUrl = sNode.src || sNode.getAttribute('src') || '';
        if (!sUrl || adOrPreviewRe.test(sUrl)) continue;
        var qAttr = sNode.getAttribute('label') || sNode.getAttribute('title') ||
          sNode.getAttribute('res') || sNode.getAttribute('size') || sNode.getAttribute('data-quality') || '';
        var sq = inferQuality(qAttr) || inferQuality(sUrl) || 0;
        var parentTag = sNode.parentElement ? sNode.parentElement.tagName : 'VIDEO';
        var sMime = sNode.type || (parentTag === 'AUDIO' ? 'audio/mp4' : 'video/mp4');
        reportAuthoritative(sUrl, sMime, sq, meta.title, meta.thumb, meta.duration);
        sourceCount++;
      }

      // Only fall back to bare <video currentSrc> when no authoritative config or <source> list exists,
      // preventing VAST prerolls and site intro bumpers playing in <video> from polluting the format sheet.
      if (authCount === 0 && sourceCount === 0) {
        var mediaNodes = document.querySelectorAll('video, audio');
        for (var i = 0; i < mediaNodes.length; i++) {
          var node = mediaNodes[i];
          if (isExcludedMediaNode(node)) continue;
          // Skip short preroll/bumper clips if page metadata indicates a longer main video
          if (node.tagName === 'VIDEO' && meta.duration >= 45 && node.duration > 0 && node.duration < 25) {
            continue;
          }
          var src = node.currentSrc || node.src || '';
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

  window.__dlScanNow = scanMedia;
  scanMedia();
  setTimeout(scanMedia, 350);
  setInterval(scanMedia, 1500);
  document.addEventListener('DOMContentLoaded', scanMedia, true);
  document.addEventListener('loadedmetadata', scanMedia, true);
  document.addEventListener('play', scanMedia, true);

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
