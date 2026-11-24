// <vs-thinking-orb-search> — native Web Component. ZERO dependencies.
// AI "thinking" orb, state SEARCHING: a 3D cloud of points on a slowly turning
// sphere. A thin tilted scan band sweeps through it; the points it crosses
// light up and leave a short afterglow, as if the orb were scanning for an answer.
//
// API: size "md" (64px) | "sm" (20px) · speed (clock multiplier) · paused ·
// color (any CSS color) · label (accessible text, default "Thinking").
// Canvas 2D at devicePixelRatio, one rAF loop that stops when paused,
// disconnected or offscreen. Ink = computed `color` of the host, which reads
// var(--vs-color) (set by `color`) or the theme's --text — re-read on theme flips.

const HTMLElement = globalThis.HTMLElement ?? class {}; // SSR: no DOM on the server

// Page theme from the class a utility framework puts on <html>/<body> (Tailwind darkMode:'class',
// shadcn, next-themes). 'dark' | 'light' | null; mq=true falls back to prefers-color-scheme.
// Order everywhere: data-theme → this class → prefers-color-scheme.
let vsMqDark;
const vsClassTheme = (mq) => {
  const d = globalThis.document;
  if (d) for (const n of [d.documentElement, d.body]) {
    const c = n && n.classList;
    if (c && c.contains('dark')) return 'dark';
    if (c && c.contains('light')) return 'light';
  }
  if (!mq || !globalThis.matchMedia) return null;
  return (vsMqDark ??= globalThis.matchMedia('(prefers-color-scheme: dark)')).matches ? 'dark' : 'light';
};

const CSS = `
  :host {
    display: inline-block;
    position: relative;
    width: 64px;
    height: 64px;
    flex: 0 0 auto;
    vertical-align: middle;
    line-height: 0;
    font-family: inherit;
    color: var(--vs-color, var(--text, #ededed));
  }
  :host([size="sm"]) { width: 20px; height: 20px; }
  :host([hidden]) { display: none; }
  canvas { display: block; width: 100%; height: 100%; }
  /* reduced motion is handled in JS: the clock drops to a calm crawl */
  @media (prefers-reduced-motion: reduce) { canvas { transition: none; } }
`;

// `color` — repaints the component's BASE color (white ink on dark, black on
// light) with any CSS color, by overriding the design tokens it reads on the
// host. The orb itself reads the opt-in handle --vs-color.
let vsBrandProbe;
function vsBrandRgb(value) {
  vsBrandProbe ||= document.createElement('canvas').getContext('2d');
  if (!vsBrandProbe) return null;
  vsBrandProbe.fillStyle = '#000';
  vsBrandProbe.fillStyle = value; // any CSS color → normalized #rrggbb / rgba()
  const s = vsBrandProbe.fillStyle;
  if (s.charAt(0) === '#') return [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
  const m = s.match(/[\d.]+/g);
  return m && m.length >= 3 ? [+m[0], +m[1], +m[2]] : null;
}
const VS_BRAND_TOKENS = ['--ui-accent', '--inp-accent', '--btn-primary-bg', '--card-ink', '--ui-ring', '--inp-ring', '--fx-tint', '--ring', '--rip', '--ui-accent-fg', '--badge-solid-fg', '--btn-primary-fg', '--accent-fg', '--btn-primary-rip', '--btn-primary-glow', '--btn-primary-bg-hover', '--vs-color', '--vs-color-rgb', '--vs-color-fg'];
function vsApplyColor(host, value) {
  const rgb = value ? vsBrandRgb(String(value).trim()) : null;
  if (!rgb) { for (const t of VS_BRAND_TOKENS) host.style.removeProperty(t); return; }
  const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
  const lum = 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2]);
  const light = lum > 0.45;
  const fill = `rgb(${rgb[0]} ${rgb[1]} ${rgb[2]})`;
  const hover = rgb.map((c) => Math.round(light ? c * 0.92 : c + (255 - c) * 0.16));
  const set = (t, v) => host.style.setProperty(t, v);
  for (const t of ['--ui-accent', '--inp-accent', '--btn-primary-bg', '--card-ink']) set(t, fill);
  set('--btn-primary-bg-hover', `rgb(${hover[0]} ${hover[1]} ${hover[2]})`);
  for (const t of ['--ui-ring', '--inp-ring', '--fx-tint', '--ring', '--rip']) set(t, rgb.join(' '));
  for (const t of ['--ui-accent-fg', '--badge-solid-fg', '--btn-primary-fg', '--accent-fg']) set(t, light ? '#0b0b0b' : '#ffffff');
  for (const t of ['--btn-primary-rip', '--btn-primary-glow']) set(t, light ? '0 0 0' : '255 255 255');
  set('--vs-color', fill);
  set('--vs-color-rgb', rgb.join(' '));
  set('--vs-color-fg', light ? '#0b0b0b' : '#ffffff');
}

// ── the SEARCHING motion ──────────────────────────────────────────────────────
// A point cloud on a Fibonacci sphere turns slowly in perspective. A tilted
// latitude plane sweeps through it top to bottom; points it crosses light up
// and keep a short afterglow behind the band. The plane precesses a little
// every pass, so no two sweeps cut the sphere the same way. Everything is a
// pure function of the clock, so pause / speed / reduced motion stay exact.
const TAU = Math.PI * 2;
const clamp01 = (x) => Math.max(0, Math.min(1, x));
const smooth = (x) => { x = clamp01(x); return x * x * (3 - 2 * x); };
const SWEEP = 3, ACTIVE = 0.8; // seconds per pass · share of it spent sweeping

function fib(n) {
  const out = [], ga = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < n; i++) {
    const y = 1 - ((i + 0.5) / n) * 2, r = Math.sqrt(1 - y * y), th = i * ga;
    out.push([Math.cos(th) * r, y, Math.sin(th) * r]);
  }
  return out;
}
const PTS_MD = fib(172), PTS_SM = fib(30);

function drawOrb(ctx, S, t, ink, sm) {
  const c = S / 2, R = S * (sm ? 0.37 : 0.34);
  const a = (k) => `rgb(${ink} / ${clamp01(k).toFixed(3)})`;
  const pts = sm ? PTS_SM : PTS_MD;

  // sphere rotation (yaw, then a fixed pitch so the poles read)
  const yaw = t * 0.3, pitch = 0.38;
  const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
  const persp = sm ? 0.12 : 0.24;
  const project = (x, y, z) => { // view space → screen, z: +1 front
    const k = (1 - persp * 0.5) / (1 - z * persp);
    return [c + x * R * k, c + y * R * k, k];
  };

  // scanning plane: normal tilted off vertical and precessing, offset s sweeping +1.1 → −1.1
  const q = ((t / SWEEP) % 1 + 1) % 1;
  const pass = Math.floor(t / SWEEP);
  const s = 1.1 - 2.2 * smooth(q / ACTIVE);
  const env = q < ACTIVE ? 1 : 1 - smooth((q - ACTIVE) / 0.2); // afterglow fades once the pass ends
  const tilt = 0.42 + 0.06 * Math.sin(t * 0.4);
  const prec = 0.5 + pass * 2.1 + q * 0.6;
  const nx = Math.sin(tilt) * Math.cos(prec), ny = Math.cos(tilt), nz = Math.sin(tilt) * Math.sin(prec);
  const W = sm ? 0.2 : 0.085, TRAIL = sm ? 0.3 : 0.32;

  // faint body so the cloud has a volume
  const body = ctx.createRadialGradient(c - R * 0.25, c - R * 0.3, 0, c, c, R * 1.15);
  body.addColorStop(0, a(sm ? 0.1 : 0.07)); body.addColorStop(1, a(0));
  ctx.fillStyle = body;
  ctx.beginPath(); ctx.arc(c, c, R * 1.15, 0, TAU); ctx.fill();

  // the band's circle on the sphere (md): back half before the points, front half after
  const rho = Math.sqrt(Math.max(0, 1 - s * s));
  const ring = [];
  if (!sm && rho > 0.02) {
    let ux = ny, uy = -nx; const uz = 0; const ul = Math.hypot(ux, uy) || 1; ux /= ul; uy /= ul;
    const vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
    for (let i = 0; i <= 96; i++) {
      const th = (i / 96) * TAU, co = Math.cos(th) * rho, si = Math.sin(th) * rho;
      const x = nx * s + ux * co + vx * si, y = ny * s + uy * co + vy * si, z = nz * s + uz * co + vz * si;
      const [px, py] = project(x, -y, z);
      ring.push([px, py, z]);
    }
  }
  const strokeRing = (front) => {
    if (!ring.length || env <= 0) return;
    ctx.lineWidth = front ? 1 : 0.8;
    ctx.strokeStyle = a((front ? 0.5 : 0.12) * rho * env);
    ctx.beginPath();
    let open = false;
    for (let i = 1; i < ring.length; i++) {
      const on = ((ring[i - 1][2] + ring[i][2]) / 2 >= 0) === front;
      if (on) {
        if (!open) { ctx.moveTo(ring[i - 1][0], ring[i - 1][1]); open = true; }
        ctx.lineTo(ring[i][0], ring[i][1]);
      } else open = false;
    }
    ctx.stroke();
  };
  ctx.lineCap = 'round';
  strokeRing(false);

  // project + light every point
  const P = new Array(pts.length);
  for (let i = 0; i < pts.length; i++) {
    const [x0, y0, z0] = pts[i];
    const x1 = x0 * cy + z0 * sy, z1 = -x0 * sy + z0 * cy;
    const y2 = y0 * cp - z1 * sp, z2 = y0 * sp + z1 * cp;
    const h = x1 * nx + y2 * ny + z2 * nz;          // height along the scan normal
    const d = h - s;                                 // > 0: the band already passed it
    let lit = Math.exp(-((d / W) ** 2));
    if (d > 0) lit = Math.max(lit, Math.exp(-d / TRAIL) * 0.6);
    lit *= env;
    const [px, py, k] = project(x1, -y2, z2);
    P[i] = [px, py, (z2 + 1) / 2, lit, k];
  }
  P.sort((A, B) => A[2] - B[2]);

  for (const [x, y, dep, lit, k] of P) {
    if (!sm && lit > 0.3) {
      const gr = 4.2 * k;
      const g = ctx.createRadialGradient(x, y, 0, x, y, gr);
      g.addColorStop(0, a(0.3 * lit * (0.4 + 0.6 * dep))); g.addColorStop(1, a(0));
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(x, y, gr, 0, TAU); ctx.fill();
    }
    const base = sm ? 0.3 + 0.3 * dep : 0.1 + 0.3 * dep * dep;
    ctx.fillStyle = a(base + (1 - base) * lit * (sm ? 0.9 : 0.55 + 0.45 * dep));
    const r = sm ? 0.72 + 0.35 * dep + 0.3 * lit : (0.4 + 0.5 * dep + 0.6 * lit) * k;
    ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
  }

  strokeRing(true);
}

// ── shared host plumbing (identical across the orb family) ───────────────────
const reducedMq = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;

class VsThinkingOrbSearch extends HTMLElement {
  static observedAttributes = ['size', 'speed', 'paused', 'color', 'label'];
  #cv; #ctx; #raf = 0; #last = 0; #t = 0; #ink = '237 237 237';
  #S = 64; #dpr = 1; #visible = true; #on = false; #io = null; #mo = null; #frames = 0; #settle = 0;

  constructor() {
    super();
    const root = this.attachShadow({ mode: 'open' });
    const style = document.createElement('style');
    style.textContent = CSS;
    this.#cv = document.createElement('canvas');
    this.#cv.setAttribute('aria-hidden', 'true');
    root.append(style, this.#cv);
    this.#ctx = this.#cv.getContext('2d');
  }

  connectedCallback() {
    this.#on = true;
    vsApplyColor(this, this.getAttribute('color'));
    this.#aria();
    if ('IntersectionObserver' in globalThis) {
      this.#io = new IntersectionObserver((es) => {
        this.#visible = es[es.length - 1].isIntersecting;
        this.#kick();
      });
      this.#io.observe(this);
    }
    this.#mo = new MutationObserver(this.#onTheme);
    this.#mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class', 'style'] }); globalThis.document?.body && this.#mo?.observe?.(globalThis.document.body, { attributes: true, attributeFilter: ['class'] });
    reducedMq?.addEventListener?.('change', this.#onTheme);
    this.#readInk();
    this.#resize();
    this.#render();
    this.#kick();
  }

  disconnectedCallback() {
    this.#on = false;
    this.#stop();
    clearTimeout(this.#settle);
    this.#io?.disconnect(); this.#io = null;
    this.#mo?.disconnect(); this.#mo = null;
    reducedMq?.removeEventListener?.('change', this.#onTheme);
  }

  attributeChangedCallback(name) {
    if (name === 'color') vsApplyColor(this, this.getAttribute('color'));
    if (!this.#on) return;
    if (name === 'label') return this.#aria();
    if (name === 'color') this.#readInk();
    if (name === 'size') this.#resize();
    this.#render();
    this.#kick();
  }

  get #speed() {
    const v = parseFloat(this.getAttribute('speed'));
    return Number.isFinite(v) && v >= 0 ? Math.min(v, 10) : 1;
  }

  #aria() {
    this.setAttribute('role', 'status');
    this.setAttribute('aria-label', this.getAttribute('label') || 'Thinking');
  }

  // pages often transition `color` on a theme flip: read now, and again once it settles
  #onTheme = () => {
    this.#readInk(); this.#render();
    clearTimeout(this.#settle);
    this.#settle = setTimeout(() => { if (this.#on) { this.#readInk(); this.#render(); } }, 480);
  };

  #readInk() {
    const s = getComputedStyle(this).color || '';
    const m = s.match(/[\d.]+/g);
    if (!m || m.length < 3) return;
    let ch = m.slice(0, 3).map(Number);
    if (/srgb|color\(/.test(s)) ch = ch.map((v) => v * 255);
    this.#ink = ch.map((v) => Math.round(v)).join(' ');
  }

  #resize() {
    this.#S = this.getAttribute('size') === 'sm' ? 20 : 64;
    this.#dpr = Math.min(3, Math.max(1, vsDpr()));
    const px = Math.round(this.#S * this.#dpr);
    if (this.#cv.width !== px) { this.#cv.width = px; this.#cv.height = px; }
  }

  #render() {
    const ctx = this.#ctx;
    if (!ctx) return;
    const d = Math.min(3, Math.max(1, vsDpr()));
    if (d !== this.#dpr) this.#resize();
    ctx.setTransform(this.#dpr, 0, 0, this.#dpr, 0, 0);
    ctx.clearRect(0, 0, this.#S, this.#S);
    ctx.save(); // draw state (lineCap, clip…) never leaks into the next frame
    drawOrb(ctx, this.#S, this.#t, this.#ink, this.#S < 32);
    ctx.restore();
  }

  #kick() {
    const run = this.#on && this.#visible && !this.hasAttribute('paused');
    if (!run) return this.#stop();
    if (this.#raf) return;
    this.#last = performance.now();
    this.#raf = requestAnimationFrame(this.#tick);
  }

  #stop() {
    if (this.#raf) cancelAnimationFrame(this.#raf);
    this.#raf = 0;
  }

  #tick = (now) => {
    this.#raf = 0;
    const dt = Math.min(0.05, Math.max(0, (now - this.#last) / 1000));
    this.#last = now;
    this.#t += dt * this.#speed * (reducedMq?.matches ? 0.12 : 1);
    if (++this.#frames % 30 === 0) this.#readInk(); // cheap safety net for token changes
    this.#render();
    // chain directly (not via #kick) so #last keeps the rAF timebase
    if (this.#on && this.#visible && !this.hasAttribute('paused')) this.#raf = requestAnimationFrame(this.#tick);
  };
}

if (globalThis.customElements && !customElements.get('vs-thinking-orb-search')) customElements.define('vs-thinking-orb-search', VsThinkingOrbSearch);

// perf (paso 8): devicePixelRatio, capped by the catalog's lite mode
// (window.__vsPerf.dprCap). Without that layer (a copy in your project) it is a plain read.
function vsDpr(max = Infinity) {
  return Math.min(globalThis.devicePixelRatio || 1, max, globalThis.__vsPerf?.dprCap || Infinity);
}
