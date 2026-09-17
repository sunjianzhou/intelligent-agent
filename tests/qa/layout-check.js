(() => {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const doc = document.documentElement;
  const out = {
    url: location.pathname,
    viewport: `${vw}x${vh}`,
    docScrollWidth: doc.scrollWidth,
    bodyScrollWidth: document.body ? document.body.scrollWidth : null,
    horizontalOverflow: doc.scrollWidth > vw + 1,
    offenders: [],
    fixedElements: [],
    zeroVisible: [],
  };
  const all = document.querySelectorAll('body *');
  for (const el of all) {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    // Horizontal offenders: stick out of the viewport either side
    if (r.right > vw + 1 || r.left < -1) {
      const cls = (el.className && typeof el.className === 'string') ? el.className.split(' ').slice(0, 3).join('.') : '';
      const id = el.id ? '#' + el.id : '';
      out.offenders.push({
        tag: el.tagName.toLowerCase(),
        id,
        cls,
        text: (el.textContent || '').trim().slice(0, 28),
        left: Math.round(r.left),
        right: Math.round(r.right),
        width: Math.round(r.width),
        pos: cs.position,
      });
    }
    if (cs.position === 'fixed' && (r.width > 0 || r.height > 0)) {
      out.fixedElements.push({
        tag: el.tagName.toLowerCase(),
        cls: (typeof el.className === 'string' ? el.className.split(' ').slice(0, 3).join('.') : ''),
        left: Math.round(r.left), top: Math.round(r.top),
        right: Math.round(r.right), bottom: Math.round(r.bottom),
        w: Math.round(r.width), h: Math.round(r.height),
      });
    }
  }
  // dedupe offenders by signature
  const seen = new Set();
  out.offenders = out.offenders.filter(o => {
    const k = `${o.tag}|${o.id}|${o.cls}|${o.left}|${o.right}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).slice(0, 25);
  return JSON.stringify(out, null, 1);
})();
