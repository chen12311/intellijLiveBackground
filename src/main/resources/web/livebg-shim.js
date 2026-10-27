// Live Background：模拟 Wallpaper Engine 给网页壁纸的接口。插在入口网页 <head> 的最前面，比壁纸自己的脚本先跑。
// 前面一个 <script> 放了 window.__livebgConfig = { properties, fps, filter }。
(function () {
  'use strict';
  var cfg = window.__livebgConfig || {};
  delete window.__livebgConfig;

  var paused = false;
  var audioListeners = [];

  function listener() {
    return window.wallpaperPropertyListener || {};
  }

  function call(name, arg) {
    var target = listener();
    if (typeof target[name] !== 'function') return;
    try {
      target[name](arg);
    } catch (e) {
      console.error('[LiveBg] wallpaperPropertyListener.' + name + ' failed', e);
    }
  }

  // 音频可视化：拿不到系统声音。按 30 fps 喂静音（左右声道各 64 个 0），频谱类壁纸至少能画出静止的样子
  window.wallpaperRegisterAudioListener = function (cb) {
    if (typeof cb === 'function') audioListeners.push(cb);
  };
  var silence = [];
  for (var i = 0; i < 128; i++) silence.push(0);
  setInterval(function () {
    if (paused) return;
    for (var i = 0; i < audioListeners.length; i++) {
      try {
        audioListeners[i](silence);
      } catch (e) {
        // 壁纸自己的错，别让它把定时器搞停
      }
    }
  }, 33);

  // 正在播放的歌、随机挑文件这些拿不到，给个空实现，免得壁纸调的时候报错
  [
    'wallpaperRegisterMediaStatusListener',
    'wallpaperRegisterMediaPropertiesListener',
    'wallpaperRegisterMediaThumbnailListener',
    'wallpaperRegisterMediaPlaybackListener',
    'wallpaperRegisterMediaTimelineListener',
    'wallpaperRequestRandomFileForProperty',
  ].forEach(function (name) {
    window[name] = function () {};
  });

  // 静音：插进来的 <video> / <audio> 一出现就静音，自己调 play() 的也先静音（静音的才能不经点击自动播放）
  function mute(el) {
    if (el instanceof HTMLMediaElement) el.muted = true;
  }
  var play = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    this.muted = true;
    return play.apply(this, arguments);
  };
  new MutationObserver(function (records) {
    records.forEach(function (r) {
      r.addedNodes.forEach(function (n) {
        mute(n);
        if (n.querySelectorAll) n.querySelectorAll('video, audio').forEach(mute);
      });
    });
  }).observe(document.documentElement, { childList: true, subtree: true });

  // 模糊、压暗：最上面盖一层 backdrop-filter，页面里的东西（包括画布、视频）都会被处理到
  if (cfg.filter) {
    var layer = document.createElement('div');
    layer.style.cssText =
      'position:fixed;left:0;top:0;width:100vw;height:100vh;pointer-events:none;z-index:2147483647;' +
      'backdrop-filter:' + cfg.filter + ';-webkit-backdrop-filter:' + cfg.filter;
    document.documentElement.appendChild(layer);
  }

  // Wallpaper Engine 在页面加载完后把设置交给壁纸。有的壁纸在 onload 里才设 wallpaperPropertyListener，所以再晚一拍
  window.addEventListener('load', function () {
    setTimeout(function () {
      call('applyGeneralProperties', { fps: cfg.fps });
      call('applyUserProperties', cfg.properties || {});
      if (paused) call('setPaused', true);
    }, 0);
  });

  // 插件在 IDE 失去焦点、暂停时调
  window.__livebgSetPaused = function (p) {
    if (paused === !!p) return;
    paused = !!p;
    call('setPaused', paused);
  };
})();
