function initPlyr() {
  if (typeof Plyr === 'undefined') return;
  document.querySelectorAll('video:not([data-plyr-init])').forEach(function (video) {
    video.setAttribute('data-plyr-init', '1');
    new Plyr(video, {
      controls: [
        'play-large', 'rewind', 'play', 'fast-forward',
        'progress', 'current-time', 'duration',
        'mute', 'volume', 'captions', 'fullscreen',
      ],
      speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 2] },
      keyboard: { focused: true, global: false },
      tooltips: { controls: true, seek: true },
      ratio: '16:9',
    });
  });
}

// Initial page load
document.addEventListener('DOMContentLoaded', initPlyr);

// MkDocs Material instant navigation (fires on every page transition)
if (typeof document$ !== 'undefined') {
  document$.subscribe(function () { setTimeout(initPlyr, 50); });
}
