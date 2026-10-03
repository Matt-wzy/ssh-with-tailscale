// 主题切换：auto / light / dark
// - auto   : 跟随系统 (prefers-color-scheme)，不写 data-theme
// - light  : 强制日间  <html data-theme="light">
// - dark   : 强制夜间  <html data-theme="dark">
// Bulma 1.0 的预构建 CSS 已包含 [data-theme] 与 prefers-color-scheme 选择器，
// 手动 data-theme 的优先级高于系统偏好。
(function () {
  const KEY = 'sshws-theme';
  const root = document.documentElement;
  const ORDER = ['auto', 'light', 'dark'];
  const META = {
    auto:  { icon: '🌗', label: '自动' },
    light: { icon: '☀️', label: '日间' },
    dark:  { icon: '🌙', label: '夜间' },
  };

  function current() {
    const v = localStorage.getItem(KEY);
    return ORDER.includes(v) ? v : 'auto';
  }

  function apply(pref) {
    pref = ORDER.includes(pref) ? pref : 'auto';
    if (pref === 'auto') {
      root.removeAttribute('data-theme');
      root.style.colorScheme = 'light dark';
    } else {
      root.setAttribute('data-theme', pref);
      root.style.colorScheme = pref;
    }
    syncButton(pref);
  }

  function syncButton(pref) {
    const btn = document.getElementById('theme-toggle');
    if (!btn) return;
    const m = META[pref] || META.auto;
    const icon = btn.querySelector('.theme-icon');
    const label = btn.querySelector('.theme-label');
    if (icon) icon.textContent = m.icon;
    if (label) label.textContent = m.label;
    btn.setAttribute('aria-label', '主题模式：' + m.label + '（点击切换）');
    btn.dataset.theme = pref;
  }

  function cycle() {
    const next = ORDER[(ORDER.indexOf(current()) + 1) % ORDER.length];
    localStorage.setItem(KEY, next);
    apply(next);
  }

  // 供 <head> 内联脚本调用，避免首屏闪烁
  window.__sshwsApplyTheme = apply;

  document.addEventListener('DOMContentLoaded', function () {
    apply(current());
    const btn = document.getElementById('theme-toggle');
    if (btn) btn.addEventListener('click', cycle);

    // 处于“自动”时，实时跟随系统切换
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => { if (current() === 'auto') apply('auto'); };
    if (mq.addEventListener) mq.addEventListener('change', onChange);
    else if (mq.addListener) mq.addListener(onChange);
  });
})();
