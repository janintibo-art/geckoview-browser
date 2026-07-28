"use strict";

// Preparation du media avant l'entree dans le PiP Android.
(function () {
  let lastVideo = null;

  function visible() {
    try {
      if (typeof GB !== "undefined" && GB.foreground) return GB.foreground();
    } catch (e) { }
    return document.visibilityState === "visible";
  }

  function remember(event) {
    const node = event && event.target;
    if (node && node.tagName === "VIDEO") lastVideo = node;
  }

  function largestVideo() {
    const videos = Array.from(document.querySelectorAll("video"));
    const playing = videos.filter(v => !v.paused && !v.ended);
    const list = playing.length ? playing : videos;
    return list.sort((a, b) =>
      (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight))[0] || null;
  }

  async function preparePip() {
    const video = (lastVideo && document.contains(lastVideo)) ? lastVideo : largestVideo();
    if (!video) return;
    try { if (video.paused) await video.play(); } catch (e) { }
    try {
      if (!document.fullscreenElement && video.requestFullscreen) {
        await video.requestFullscreen();
      }
    } catch (e) { }
  }

  async function exitFullscreen() {
    try {
      if (document.fullscreenElement && document.exitFullscreen) {
        await document.exitFullscreen();
      }
    } catch (e) { }
  }

  document.addEventListener("play", remember, true);
  document.addEventListener("loadedmetadata", remember, true);

  browser.storage.onChanged.addListener(changes => {
    const value = changes.pageCommand && changes.pageCommand.newValue;
    if (!value || !visible()) return;
    if (value.cmd === "mediaPip") preparePip();
    if (value.cmd === "mediaExitFullscreen") exitFullscreen();
  });
})();
