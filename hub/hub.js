// Injected by SideDash Hub into every game page. Shared, game-independent helpers.
(function () {
  // Keep the tablet screen on while the dashboard is open. Browsers only allow it after a tap.
  var noSleep = new NoSleep(), awake = false;
  function wake() {
    if (awake) return;
    noSleep.enable().then(function () { awake = true; }).catch(function () {});
  }
  document.addEventListener("click", wake, true);
  document.addEventListener("touchend", wake, true);
  document.addEventListener("visibilitychange", function () { if (document.visibilityState === "visible") awake = false; });

  // SideDash.alert(text, level): toast + vibration + short beep. level: "warn" | "bad".
  var box = document.createElement("div");
  box.style.cssText = "position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:99999;display:flex;" +
    "flex-direction:column;gap:8px;align-items:center;pointer-events:none;font:600 18px system-ui,sans-serif";
  document.addEventListener("DOMContentLoaded", function () { document.body.appendChild(box); });
  var audio;
  function beep(freq) {
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)();
      var o = audio.createOscillator(), g = audio.createGain();
      o.frequency.value = freq; g.gain.value = 0.08;
      o.connect(g); g.connect(audio.destination); o.start(); o.stop(audio.currentTime + 0.18);
    } catch (e) {}
  }
  // floating button: this game's stream deck (pages live at /<key>/g/<game>/, the deck at /<key>/deck/)
  var m = location.pathname.match(/^(\/[^/]+)\/g\/([^/]+)\//);
  if (m) document.addEventListener("DOMContentLoaded", function () {
    var a = document.createElement("a");
    a.href = m[1] + "/deck/?game=" + m[2];
    a.textContent = "🎛";
    a.title = "Пульт";
    a.style.cssText = "position:fixed;right:16px;bottom:16px;z-index:9999;width:56px;height:56px;border-radius:50%;display:grid;" +
      "place-items:center;font-size:26px;text-decoration:none;background:#1d252c;border:1px solid #5fd3a8;box-shadow:0 6px 20px #0008";
    document.body.appendChild(a);
  });

  window.SideDash = {
    alert: function (text, level) {
      var t = document.createElement("div");
      t.textContent = text;
      t.style.cssText = "padding:12px 20px;border-radius:12px;color:#fff;box-shadow:0 6px 24px #0008;background:" +
        (level === "bad" ? "#c23a3f" : "#b7791f");
      box.appendChild(t);
      setTimeout(function () { t.remove(); }, 4000);
      if (navigator.vibrate) navigator.vibrate(level === "bad" ? [200, 100, 200, 100, 400] : [150]);
      beep(level === "bad" ? 330 : 660);
    }
  };
})();
