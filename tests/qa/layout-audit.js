(() => {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const doc = document.documentElement;
  const out = {
    url: location.pathname,
    viewport: `${vw}x${vh}`,
    clippedText: [],
    fixedOverlap: [],
    offscreenFixed: [],
    tallestVisible: null,
    bodyHeight: document.body ? document.body.scrollHeight : null,
  };
  const all = document.querySelectorAll('body *');
  const fixed = [];
  let maxH = 0, maxEl = null;
  for (const el of all) {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    // Clipped text: element has visible text, overflow hidden, but content wider than box
    const ownText = Array.from(el.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join('');
    if (ownText && r.width > 0 && r.width < 400) {
      if ((cs.overflowX === 'hidden' || cs.overflow === 'hidden' || cs.textOverflow === 'ellipsis') && el.scrollWidth > el.clientWidth + 2) {
        const cls = typeof el.className === 'string' ? el.className.split(' ').slice(0, 3).join('.') : '';
        out.clippedText.push({
          tag: el.tagName.toLowerCase(),
          cls,
          text: ownText.slice(0, 24),
          clientW: el.clientWidth,
          scrollW: el.scrollWidth,
        });
      }
    }
    if (cs.position === 'fixed') {
      fixed.push({ el, r, cls: typeof el.className === 'string' ? el.className.split(' ').slice(0, 3).join('.') : '' });
      if (r.top < 0 || r.left < 0 || r.right > vw + 1 || r.bottom > vh + 1) {
        out.offscreenFixed.push({
          cls: fixed[fixed.length - 1].cls,
          left: Math.round(r.left), top: Math.round(r.top),
          right: Math.round(r.right), bottom: Math.round(r.bottom),
        });
      }
    }
    if (r.height > maxH && r.top >= 0 && r.top < vh) { maxH = r.height; maxEl = el; }
  }
  for (let i = 0; i < fixed.length; i++) {
    for (let j = i + 1; j < fixed.length; j++) {
      const a = fixed[i].r, b = fixed[j].r;
      if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) {
        out.fixedOverlap.push([fixed[i].cls, fixed[j].cls]);
      }
    }
  }
  if (maxEl) {
    out.tallestVisible = {
      cls: typeof maxEl.className === 'string' ? maxEl.className.split(' ').slice(0, 3).join('.') : '',
      tag: maxEl.tagName.toLowerCase(),
      h: Math.round(maxEl.getBoundingClientRect().height),
    };
  }
  out.clippedText = out.clippedText.slice(0, 12);
  return JSON.stringify(out, null, 1);
})();
