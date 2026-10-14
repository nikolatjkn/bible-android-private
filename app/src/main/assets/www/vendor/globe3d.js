/**
 * globe3d.js — a small, dependency-free 3D globe renderer for the
 * Maps / Bible Places pages.
 *
 * Draws a rotating, dark-themed "space" globe on a <canvas> (drag to
 * rotate, wheel/pinch to zoom, tap a pin for a details card) using
 * plain 2D canvas math — no WebGL, no CDN library for the globe
 * itself.
 *
 * Keep zooming in past the globe and it smoothly cross-fades into a
 * real slippy map (roads, rivers, elevation/hillshading) built from
 * standard {z}/{x}/{y} raster tiles — the same tile scheme Google
 * Maps/Earth, OpenStreetMap, and most map providers use. That detail
 * layer needs a network connection (the globe itself still works
 * fully offline). The tile source defaults to OpenTopoMap's terrain
 * style (free, keyless — close to Google's "Terrain" map type) but
 * can be swapped by setting `window.GLOBE3D_TILE_URL` (and, if you
 * change providers, `window.GLOBE3D_TILE_ATTRIBUTION`) before this
 * script loads — see README-extended notes.
 *
 * Public API (matches the shape the app expects):
 *   const globe = new window.Globe3D(containerEl);
 *   globe.draw(stops, color, mode, onStopClick, defaultEmoji);
 *   globe.destroy();
 *
 * `stops` is an array of { name, lat, lng, color?, emoji?, title?,
 * desc?, ref?: { label, book, chapter } }. `mode` is 'route' (draws a
 * connecting arc between consecutive stops) or 'points' (pins only).
 */
(function (global) {
    'use strict';

    const DEG2RAD = Math.PI / 180;

    function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

    function latLngToUnit(lat, lng) {
        const latR = lat * DEG2RAD;
        const lngR = lng * DEG2RAD;
        return {
            x: Math.cos(latR) * Math.sin(lngR),
            y: Math.sin(latR),
            z: Math.cos(latR) * Math.cos(lngR),
        };
    }

    function rotateY(p, a) {
        const c = Math.cos(a), s = Math.sin(a);
        return { x: p.x * c + p.z * s, y: p.y, z: -p.x * s + p.z * c };
    }

    function rotateX(p, a) {
        const c = Math.cos(a), s = Math.sin(a);
        return { x: p.x, y: p.y * c - p.z * s, z: p.y * s + p.z * c };
    }

    function esc(str) {
        return String(str || '').replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
        }[c]));
    }

    // Spherical linear interpolation between two unit vectors — used to
    // draw a great-circle arc between two stops that bulges gently
    // above the globe's surface, instead of a straight chord through it.
    function slerp(v0, v1, t) {
        let dot = clamp(v0.x * v1.x + v0.y * v1.y + v0.z * v1.z, -1, 1);
        const omega = Math.acos(dot);
        if (omega < 1e-6) return v0;
        const s0 = Math.sin((1 - t) * omega) / Math.sin(omega);
        const s1 = Math.sin(t * omega) / Math.sin(omega);
        return {
            x: v0.x * s0 + v1.x * s1,
            y: v0.y * s0 + v1.y * s1,
            z: v0.z * s0 + v1.z * s1,
        };
    }

    // ---- slippy-map (Web Mercator) tile math ------------------------------
    // Standard {z}/{x}/{y} scheme shared by OSM, Google, Mapbox, etc.

    const TILE_SIZE = 256;
    const GLOBE_MIN_ZOOM = 0.55;
    const GLOBE_MAX_ZOOM = 2.6;
    // How far past GLOBE_MAX_ZOOM (in the same zoom units) the cross-fade
    // into tiles takes to fully complete.
    const DETAIL_BLEND_SPAN = 0.5;
    const MAP_ZOOM_MIN = 3;
    // OpenTopoMap's tile server only renders up to z17 — asking past that
    // 404s, so the detail view's max is capped to match the tile source.
    const MAP_ZOOM_MAX = 17;
    const MAX_TILE_CACHE = 500;
    // Terrain-shaded tiles (hillshading, contour lines, vegetation) —
    // OpenTopoMap, free and keyless, close to Google's "Terrain" map type.
    // Swap providers by setting window.GLOBE3D_TILE_URL /
    // GLOBE3D_TILE_ATTRIBUTION before this script loads, e.g. plain OSM
    // streets ('https://tile.openstreetmap.org/{z}/{x}/{y}.png') or a dark
    // hillshaded style from a keyed provider like Stadia/Thunderforest.
    const TILE_URL_TEMPLATE = global.GLOBE3D_TILE_URL || 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png';
    const TILE_SUBDOMAINS = ['a', 'b', 'c'];
    const TILE_ATTRIBUTION = global.GLOBE3D_TILE_ATTRIBUTION
        || 'Map data: \u00A9 OpenStreetMap contributors, SRTM \u00B7 Map style: \u00A9 OpenTopoMap (CC-BY-SA)';

    function lonToTileX(lon, z) {
        return ((lon + 180) / 360) * Math.pow(2, z);
    }

    function latToTileY(lat, z) {
        const latR = clamp(lat, -85.05112878, 85.05112878) * DEG2RAD;
        return (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * Math.pow(2, z);
    }

    function tileXToLon(x, z) {
        return (x / Math.pow(2, z)) * 360 - 180;
    }

    function tileYToLat(y, z) {
        const n = Math.PI - (2 * Math.PI * y) / Math.pow(2, z);
        return (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
    }

    function tileUrl(z, x, y) {
        const subdomain = TILE_SUBDOMAINS[(x + y) % TILE_SUBDOMAINS.length];
        return TILE_URL_TEMPLATE
            .replace('{s}', subdomain)
            .replace('{z}', z)
            .replace('{x}', x)
            .replace('{y}', y);
    }

    class Globe3D {
        constructor(container) {
            this.container = container;
            container.classList.add('g3d-wrap');

            this.canvas = document.createElement('canvas');
            this.canvas.className = 'g3d-canvas';
            container.appendChild(this.canvas);
            this.ctx = this.canvas.getContext('2d');

            this.hint = document.createElement('div');
            this.hint.className = 'g3d-hint';
            this.hint.textContent = '\uD83C\uDF10 Drag to rotate \u00B7 Keep zooming in for a terrain map';
            container.appendChild(this.hint);

            this.attribution = document.createElement('div');
            this.attribution.className = 'g3d-attribution';
            this.attribution.textContent = TILE_ATTRIBUTION;
            container.appendChild(this.attribution);

            this.offlineBadge = document.createElement('div');
            this.offlineBadge.className = 'g3d-offline';
            this.offlineBadge.textContent = '\uD83D\uDCE1 Map tiles need a connection \u2014 showing what\u2019s cached';
            container.appendChild(this.offlineBadge);

            this.backButton = document.createElement('button');
            this.backButton.type = 'button';
            this.backButton.className = 'g3d-back';
            this.backButton.textContent = '\uD83C\uDF10 Back to globe';
            this.backButton.addEventListener('click', () => { this._exitDetail(); this._wake(); });
            container.appendChild(this.backButton);

            this.panel = document.createElement('div');
            this.panel.className = 'g3d-panel';
            container.appendChild(this.panel);

            // View state
            this.rotLon = 0.35;
            this.rotLat = 0.28;
            this.targetRotLon = this.rotLon;
            this.targetRotLat = this.rotLat;
            this.zoom = 1;
            this.dragging = false;
            this.dragMoved = false;
            this.lastX = 0;
            this.lastY = 0;
            this.lastInteraction = 0;
            this.pointers = new Map();
            this.pinchStartDist = null;
            this.pinchStartZoom = 1;
            this._pinchStartDetailZoom = MAP_ZOOM_MIN;

            // Detail (tile) view state. `detail` is non-null whenever the
            // tile layer is active or fading in/out; `blend` (0 = pure
            // globe, 1 = pure tiles) is what actually drives opacity and is
            // eased toward `targetBlend` every frame in `_tick`.
            this.detail = null;
            this.blend = 0;
            this.targetBlend = 0;
            this._tileCache = new Map();
            this._detailLoading = false;
            this._detailOffline = false;

            // Content
            this.stops = [];
            this.color = '#4f8cff';
            this.mode = 'points';
            this.onStopClick = null;
            this.defaultEmoji = '\uD83D\uDCCD';
            this.selected = null;
            this._hitTargets = [];
            this._stars = [];

            this._bind();
            this._resize();
            this._ro = new ResizeObserver(() => this._resize());
            this._ro.observe(container);

            this._raf = requestAnimationFrame(() => this._tick());
        }

        // ---- public API -----------------------------------------------

        draw(stops, color, mode, onStopClick, defaultEmoji) {
            this.stops = stops || [];
            this.color = color || '#4f8cff';
            this.mode = mode || 'points';
            this.onStopClick = onStopClick || null;
            this.defaultEmoji = defaultEmoji || '\uD83D\uDCCD';
            this.selected = null;
            // Switching journeys/pages always returns to the globe overview
            // rather than leaving the previous detail view up.
            this.detail = null;
            this.blend = 0;
            this.targetBlend = 0;
            this._hidePanel();
            this._focusOnStops();
        }

        destroy() {
            cancelAnimationFrame(this._raf);
            if (this._ro) this._ro.disconnect();
            this._unbind();
            this.container.innerHTML = '';
            this.container.classList.remove('g3d-wrap');
        }

        // ---- setup ------------------------------------------------------

        _resize() {
            const rect = this.container.getBoundingClientRect();
            const w = Math.max(1, Math.round(rect.width));
            const h = Math.max(1, Math.round(rect.height));
            const dpr = Math.min(2, global.devicePixelRatio || 1);
            this.width = w;
            this.height = h;
            this.canvas.width = Math.round(w * dpr);
            this.canvas.height = Math.round(h * dpr);
            this.canvas.style.width = w + 'px';
            this.canvas.style.height = h + 'px';
            this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

            // Offscreen layers so the globe and the tile map can each be
            // rendered independently, then composited with their own
            // opacity for the cross-fade (globalAlpha doesn't stack across
            // nested draw calls, so each layer needs its own canvas).
            this._globeCanvas = this._globeCanvas || document.createElement('canvas');
            this._detailCanvas = this._detailCanvas || document.createElement('canvas');
            this._globeCanvas.width = this.canvas.width;
            this._globeCanvas.height = this.canvas.height;
            this._detailCanvas.width = this.canvas.width;
            this._detailCanvas.height = this.canvas.height;
            this._globeCtx = this._globeCanvas.getContext('2d');
            this._detailCtx = this._detailCanvas.getContext('2d');
            this._globeCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
            this._detailCtx.setTransform(dpr, 0, 0, dpr, 0, 0);

            this._makeStars();
        }

        _makeStars() {
            const n = 90;
            const stars = [];
            for (let i = 0; i < n; i++) {
                stars.push({
                    x: Math.random() * this.width,
                    y: Math.random() * this.height,
                    r: Math.random() * 1.2 + 0.2,
                    a: Math.random() * 0.5 + 0.15,
                });
            }
            this._stars = stars;
        }

        _bind() {
            this._onDown = this._onPointerDown.bind(this);
            this._onMove = this._onPointerMove.bind(this);
            this._onUp = this._onPointerUp.bind(this);
            this._onWheel = this._onWheelEvt.bind(this);
            const c = this.canvas;
            c.addEventListener('pointerdown', this._onDown);
            global.addEventListener('pointermove', this._onMove);
            global.addEventListener('pointerup', this._onUp);
            global.addEventListener('pointercancel', this._onUp);
            c.addEventListener('wheel', this._onWheel, { passive: false });
        }

        _unbind() {
            const c = this.canvas;
            c.removeEventListener('pointerdown', this._onDown);
            global.removeEventListener('pointermove', this._onMove);
            global.removeEventListener('pointerup', this._onUp);
            global.removeEventListener('pointercancel', this._onUp);
            c.removeEventListener('wheel', this._onWheel);
        }

        // ---- interaction --------------------------------------------------

        _wake() { this.lastInteraction = Date.now(); this._fadeHint(); }

        _fadeHint() {
            if (this.hint && !this._hintFaded) {
                this._hintFaded = true;
                this.hint.classList.add('g3d-hint-fade');
            }
        }

        _onPointerDown(e) {
            this.canvas.setPointerCapture(e.pointerId);
            this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (this.pointers.size === 1) {
                this.dragging = true;
                this.dragMoved = false;
                this.lastX = e.clientX;
                this.lastY = e.clientY;
            } else if (this.pointers.size === 2) {
                const pts = Array.from(this.pointers.values());
                this.pinchStartDist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
                this.pinchStartZoom = this.zoom;
                this._pinchStartDetailZoom = this.detail ? this.detail.z : MAP_ZOOM_MIN;
            }
            this._wake();
        }

        _onPointerMove(e) {
            if (!this.pointers.has(e.pointerId)) return;
            this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

            if (this.pointers.size === 2) {
                const pts = Array.from(this.pointers.values());
                const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
                if (this.pinchStartDist) {
                    const ratio = dist / this.pinchStartDist;
                    if (this.detail) {
                        this.detail.z = clamp(
                            this._pinchStartDetailZoom + Math.log2(ratio) * 1.6,
                            MAP_ZOOM_MIN,
                            MAP_ZOOM_MAX,
                        );
                        if (this.detail.z <= MAP_ZOOM_MIN + 0.05 && ratio < 0.85) {
                            this._exitDetail();
                        } else {
                            this.targetBlend = 1;
                        }
                    } else {
                        this.zoom = clamp(this.pinchStartZoom * ratio, GLOBE_MIN_ZOOM, GLOBE_MAX_ZOOM);
                        if (this.zoom >= GLOBE_MAX_ZOOM - 0.01 && ratio > 1.12) {
                            this._enterDetail();
                        }
                    }
                }
                this._wake();
                return;
            }

            if (!this.dragging) return;
            const dx = e.clientX - this.lastX;
            const dy = e.clientY - this.lastY;
            if (Math.abs(dx) > 2 || Math.abs(dy) > 2) this.dragMoved = true;
            this.lastX = e.clientX;
            this.lastY = e.clientY;

            if (this.detail) {
                const z = this.detail.z;
                const tx = lonToTileX(this.detail.lon, z) - dx / TILE_SIZE;
                const ty = latToTileY(this.detail.lat, z) - dy / TILE_SIZE;
                this.detail.lon = tileXToLon(tx, z);
                this.detail.lat = clamp(tileYToLat(ty, z), -85, 85);
            } else {
                this.rotLon += dx * 0.006;
                this.rotLat = clamp(this.rotLat - dy * 0.006, -1.25, 1.25);
                this.targetRotLon = this.rotLon;
                this.targetRotLat = this.rotLat;
            }
            this._wake();
        }

        _onPointerUp(e) {
            const wasTap = this.pointers.size === 1 && this.dragging && !this.dragMoved;
            try { this.canvas.releasePointerCapture(e.pointerId); } catch (err) { /* noop */ }
            this.pointers.delete(e.pointerId);
            if (this.pointers.size < 2) this.pinchStartDist = null;
            if (this.pointers.size === 0) this.dragging = false;

            if (wasTap) this._handleTap(e.clientX, e.clientY);
            this._wake();
        }

        _onWheelEvt(e) {
            e.preventDefault();
            const zoomingIn = e.deltaY < 0;

            if (this.detail) {
                const next = clamp(this.detail.z + (zoomingIn ? 0.55 : -0.55), MAP_ZOOM_MIN, MAP_ZOOM_MAX);
                this.detail.z = next;
                if (!zoomingIn && next <= MAP_ZOOM_MIN + 0.02) {
                    this._exitDetail();
                } else {
                    this.targetBlend = 1;
                }
            } else if (zoomingIn && this.zoom >= GLOBE_MAX_ZOOM - 0.02) {
                this._enterDetail();
            } else {
                const dir = zoomingIn ? 1.1 : 0.91;
                this.zoom = clamp(this.zoom * dir, GLOBE_MIN_ZOOM, GLOBE_MAX_ZOOM);
            }

            this._wake();
        }

        _handleTap(clientX, clientY) {
            const rect = this.canvas.getBoundingClientRect();
            const x = clientX - rect.left;
            const y = clientY - rect.top;
            let best = null;
            let bestDist = 26;
            for (const t of this._hitTargets) {
                const d = Math.hypot(t.x - x, t.y - y);
                if (d < bestDist) { bestDist = d; best = t; }
            }
            if (best) {
                this.selected = best.stop;
                this._showPanel(best.stop);
            } else {
                this._hidePanel();
            }
        }

        // ---- panel ------------------------------------------------------

        _showPanel(s) {
            const title = s._popupTitle || s.title || s.name;
            const desc = s._popupDesc || s.desc || '';
            const showName = title !== s.name;
            const readLabel = s.ref ? `\uD83D\uDCD6 Read ${esc(s.ref.label)}` : null;
            this.panel.innerHTML = `
                <button type="button" class="g3d-panel-close" aria-label="Close">\u00D7</button>
                <div class="g3d-panel-title">${esc(title)}${showName ? ` \u2014 ${esc(s.name)}` : ''}</div>
                ${desc ? `<div class="g3d-panel-desc">${esc(desc)}</div>` : ''}
                ${readLabel ? `<button type="button" class="g3d-panel-btn">${readLabel}</button>` : ''}
            `;
            this.panel.classList.add('g3d-panel-open');
            const closeBtn = this.panel.querySelector('.g3d-panel-close');
            if (closeBtn) closeBtn.addEventListener('click', () => this._hidePanel());
            const readBtn = this.panel.querySelector('.g3d-panel-btn');
            if (readBtn && this.onStopClick) {
                readBtn.addEventListener('click', () => { this.onStopClick(s); this._hidePanel(); });
            }
        }

        _hidePanel() {
            this.panel.classList.remove('g3d-panel-open');
            this.selected = null;
        }

        // ---- camera framing ----------------------------------------------

        _focusOnStops() {
            if (!this.stops.length) return;
            let sx = 0, sy = 0, sz = 0;
            for (const s of this.stops) {
                const v = latLngToUnit(s.lat, s.lng);
                sx += v.x; sy += v.y; sz += v.z;
            }
            const len = Math.hypot(sx, sy, sz) || 1;
            sx /= len; sy /= len; sz /= len;
            const avgLat = Math.asin(clamp(sy, -1, 1));
            const avgLng = Math.atan2(sx, sz);
            this.targetRotLon = -avgLng;
            this.targetRotLat = clamp(avgLat, -1.1, 1.1);
            // A wide spread of stops (e.g. Paul's journeys) benefits from
            // zooming out a touch so the whole route is visible at once.
            this.zoom = this.stops.length > 4 ? 0.85 : 1;
        }

        // Lat/lon currently facing the camera at the center of the globe —
        // used as the entry point when the tile layer engages.
        _frontLatLon() {
            const lat = clamp(this.rotLat, -1.4, 1.4) * (180 / Math.PI);
            let lon = -this.rotLon * (180 / Math.PI);
            lon = ((lon + 180) % 360 + 360) % 360 - 180;
            return { lat, lon };
        }

        // ---- detail (tile) view -------------------------------------------

        _enterDetail() {
            if (this.detail) return;
            const center = this._frontLatLon();
            this.detail = { lat: center.lat, lon: center.lon, z: MAP_ZOOM_MIN };
            this.targetBlend = 1;
        }

        _exitDetail() {
            this.targetBlend = 0;
        }

        _getTile(z, x, y) {
            const key = z + '/' + x + '/' + y;
            let tile = this._tileCache.get(key);
            if (tile) return tile;
            tile = { img: null, loaded: false, failed: false };
            this._tileCache.set(key, tile);
            if (this._tileCache.size > MAX_TILE_CACHE) {
                const oldestKey = this._tileCache.keys().next().value;
                if (oldestKey !== key) this._tileCache.delete(oldestKey);
            }
            const img = new Image();
            img.decoding = 'async';
            img.onload = () => { tile.loaded = true; tile.img = img; };
            img.onerror = () => { tile.failed = true; };
            img.src = tileUrl(z, x, y);
            return tile;
        }

        // ---- render loop --------------------------------------------------

        _tick() {
            this._raf = requestAnimationFrame(() => this._tick());
            const idle = Date.now() - this.lastInteraction > 1800;

            if (this.detail) {
                // Keep the globe underneath oriented to match the tile
                // center so the cross-fade looks continuous either way.
                this.targetRotLon = -(this.detail.lon * DEG2RAD);
                this.targetRotLat = clamp(this.detail.lat * DEG2RAD, -1.4, 1.4);
            } else if (idle && !this.selected) {
                this.targetRotLon += 0.0009;
            }

            this.rotLon += (this.targetRotLon - this.rotLon) * 0.08;
            this.rotLat += (this.targetRotLat - this.rotLat) * 0.08;
            this.blend += (this.targetBlend - this.blend) * 0.12;
            if (Math.abs(this.targetBlend - this.blend) < 0.004) {
                this.blend = this.targetBlend;
            }
            if (this.blend <= 0.001 && this.targetBlend === 0 && this.detail) {
                this.detail = null;
                this.zoom = GLOBE_MAX_ZOOM;
            }

            this._render();
            this._updateChrome();
        }

        _updateChrome() {
            const inDetail = Boolean(this.detail);
            this.attribution.style.opacity = inDetail ? String(clamp(this.blend, 0, 1)) : '0';
            this.backButton.style.opacity = this.blend > 0.6 ? '1' : '0';
            this.backButton.style.pointerEvents = this.blend > 0.6 ? 'auto' : 'none';
            const showOffline = inDetail && this.blend > 0.5 && this._detailOffline;
            this.offlineBadge.classList.toggle('g3d-offline-visible', showOffline);
        }

        _render() {
            const ctx = this.ctx;
            const w = this.width, h = this.height;
            if (!w || !h) return;
            ctx.clearRect(0, 0, w, h);

            if (this.blend < 0.999) {
                this._renderGlobeLayer(w, h);
                ctx.save();
                ctx.globalAlpha = clamp(1 - this.blend, 0, 1);
                ctx.drawImage(this._globeCanvas, 0, 0, this._globeCanvas.width, this._globeCanvas.height, 0, 0, w, h);
                ctx.restore();
            }

            if (this.blend > 0.001 && this.detail) {
                this._renderDetailLayer(w, h);
                ctx.save();
                ctx.globalAlpha = clamp(this.blend, 0, 1);
                ctx.drawImage(this._detailCanvas, 0, 0, this._detailCanvas.width, this._detailCanvas.height, 0, 0, w, h);
                ctx.restore();
            }
        }

        _project(vec, R, cx, cy) {
            let p = rotateY(vec, this.rotLon);
            p = rotateX(p, this.rotLat);
            return {
                x: cx + p.x * R,
                y: cy - p.y * R,
                z: p.z,
            };
        }

        _renderGlobeLayer(w, h) {
            const ctx = this._globeCtx;
            ctx.clearRect(0, 0, w, h);

            const bg = ctx.createRadialGradient(w * 0.5, h * 0.42, 10, w * 0.5, h * 0.5, Math.max(w, h) * 0.75);
            bg.addColorStop(0, '#0d1424');
            bg.addColorStop(1, '#020306');
            ctx.fillStyle = bg;
            ctx.fillRect(0, 0, w, h);
            ctx.save();
            for (const st of this._stars) {
                ctx.globalAlpha = st.a;
                ctx.fillStyle = '#ffffff';
                ctx.beginPath();
                ctx.arc(st.x, st.y, st.r, 0, Math.PI * 2);
                ctx.fill();
            }
            ctx.restore();

            const cx = w / 2, cy = h / 2;
            const R = Math.min(w, h) * 0.36 * this.zoom;
            this.R = R; this.cx = cx; this.cy = cy;

            // Sphere body — shaded like a lit, dark ocean world
            ctx.save();
            const sphereGrad = ctx.createRadialGradient(
                cx - R * 0.35, cy - R * 0.38, R * 0.08,
                cx, cy, R * 1.05
            );
            sphereGrad.addColorStop(0, '#1d4a86');
            sphereGrad.addColorStop(0.5, '#0e2038');
            sphereGrad.addColorStop(0.78, '#081627');
            sphereGrad.addColorStop(1, '#03060c');
            ctx.beginPath();
            ctx.arc(cx, cy, R, 0, Math.PI * 2);
            ctx.fillStyle = sphereGrad;
            ctx.fill();
            ctx.lineWidth = Math.max(1.5, R * 0.015);
            ctx.strokeStyle = 'rgba(110,170,255,0.4)';
            ctx.shadowColor = 'rgba(90,150,255,0.55)';
            ctx.shadowBlur = R * 0.08;
            ctx.stroke();
            ctx.restore();

            this._drawGraticule(ctx, R, cx, cy);
            if (this.mode === 'route') this._drawRoutes(ctx, R, cx, cy);
            this._drawMarkers(ctx, R, cx, cy);
        }

        _drawGraticule(ctx, R, cx, cy) {
            ctx.save();
            ctx.clip(new Path2D((() => {
                const p = new Path2D();
                p.arc(cx, cy, R, 0, Math.PI * 2);
                return p;
            })()));

            // Meridians
            for (let lng = -150; lng <= 180; lng += 30) {
                ctx.beginPath();
                let started = false;
                ctx.strokeStyle = lng === 0 ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.07)';
                ctx.lineWidth = 1;
                for (let lat = -90; lat <= 90; lat += 4) {
                    const pr = this._project(latLngToUnit(lat, lng), R, cx, cy);
                    if (pr.z <= 0) { started = false; continue; }
                    if (!started) { ctx.moveTo(pr.x, pr.y); started = true; }
                    else ctx.lineTo(pr.x, pr.y);
                }
                ctx.stroke();
            }
            // Parallels
            for (let lat = -60; lat <= 60; lat += 30) {
                ctx.beginPath();
                let started = false;
                ctx.strokeStyle = lat === 0 ? 'rgba(255,255,255,0.18)' : 'rgba(255,255,255,0.07)';
                for (let lng = -180; lng <= 180; lng += 4) {
                    const pr = this._project(latLngToUnit(lat, lng), R, cx, cy);
                    if (pr.z <= 0) { started = false; continue; }
                    if (!started) { ctx.moveTo(pr.x, pr.y); started = true; }
                    else ctx.lineTo(pr.x, pr.y);
                }
                ctx.stroke();
            }
            ctx.restore();
        }

        _drawRoutes(ctx, R, cx, cy) {
            const stops = this.stops;
            for (let i = 0; i < stops.length - 1; i++) {
                const a = stops[i], b = stops[i + 1];
                const v0 = latLngToUnit(a.lat, a.lng);
                const v1 = latLngToUnit(b.lat, b.lng);
                const steps = 28;
                ctx.beginPath();
                let started = false;
                for (let s = 0; s <= steps; s++) {
                    const t = s / steps;
                    const v = slerp(v0, v1, t);
                    const lift = 1 + 0.045 * Math.sin(t * Math.PI);
                    const pr = this._project({ x: v.x * lift, y: v.y * lift, z: v.z * lift }, R, cx, cy);
                    if (pr.z <= 0) { started = false; continue; }
                    if (!started) { ctx.moveTo(pr.x, pr.y); started = true; }
                    else ctx.lineTo(pr.x, pr.y);
                }
                ctx.setLineDash([6, 5]);
                ctx.lineWidth = 2.5;
                ctx.strokeStyle = a.color || this.color;
                ctx.shadowColor = a.color || this.color;
                ctx.shadowBlur = 6;
                ctx.stroke();
                ctx.setLineDash([]);
                ctx.shadowBlur = 0;
            }
        }

        _drawMarkers(ctx, R, cx, cy) {
            const projected = this.stops.map((s, i) => {
                const v = latLngToUnit(s.lat, s.lng);
                const pr = this._project({ x: v.x * 1.02, y: v.y * 1.02, z: v.z * 1.02 }, R, cx, cy);
                return { s, i, pr };
            }).filter(o => o.pr.z > -0.05);
            projected.sort((a, b) => a.pr.z - b.pr.z);

            this._hitTargets = [];
            const labelRects = [];
            const overlapsExisting = (rx, ry, rw, rh) => labelRects.some(r =>
                rx < r.x + r.w && rx + rw > r.x && ry < r.y + r.h && ry + rh > r.y);

            for (const o of projected) {
                const { s, pr } = o;
                const alpha = clamp(pr.z / 0.22, 0.12, 1);
                const color = s.color || this.color;
                const emoji = s.emoji || this.defaultEmoji;
                const isSelected = this.selected === s;

                ctx.save();
                ctx.globalAlpha = alpha;
                ctx.shadowColor = 'rgba(0,0,0,0.55)';
                ctx.shadowBlur = 5;
                ctx.beginPath();
                ctx.arc(pr.x, pr.y, isSelected ? 12 : 9, 0, Math.PI * 2);
                ctx.fillStyle = color;
                ctx.fill();
                ctx.lineWidth = 2;
                ctx.strokeStyle = '#fff';
                ctx.shadowBlur = 0;
                ctx.stroke();
                ctx.font = '10px sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(emoji, pr.x, pr.y + 0.5);
                ctx.restore();

                if (alpha > 0.5) {
                    const label = this.mode === 'points' ? s.name : `${o.i + 1}. ${s.name}`;
                    ctx.font = '600 11px sans-serif';
                    const tw = ctx.measureText(label).width;
                    const lx = pr.x, ly = pr.y - 18;
                    const pad = 6;
                    const rw = tw + pad * 2, rh = 17;
                    const rx = lx - rw / 2, ry = ly - rh / 2;
                    // Markers close together on screen (common once several
                    // journey stops sit near each other on a small globe)
                    // would otherwise print overlapping, unreadable labels —
                    // keep the pin but skip the label once space runs out,
                    // unless this is the marker the user just tapped.
                    if (isSelected || !overlapsExisting(rx, ry, rw, rh)) {
                        labelRects.push({ x: rx, y: ry, w: rw, h: rh });
                        ctx.save();
                        ctx.globalAlpha = alpha;
                        ctx.fillStyle = 'rgba(10,10,14,0.82)';
                        ctx.strokeStyle = 'rgba(255,255,255,0.15)';
                        ctx.lineWidth = 1;
                        ctx.beginPath();
                        if (ctx.roundRect) ctx.roundRect(rx, ry, rw, rh, 6);
                        else ctx.rect(rx, ry, rw, rh);
                        ctx.fill();
                        ctx.stroke();
                        ctx.fillStyle = '#f1f1f1';
                        ctx.textAlign = 'center';
                        ctx.textBaseline = 'middle';
                        ctx.fillText(label, lx, ly + 0.5);
                        ctx.restore();
                    }
                }

                if (alpha > 0.3) {
                    this._hitTargets.push({ x: pr.x, y: pr.y, stop: s });
                }
            }
        }

        // ---- render: detail (tile) layer -----------------------------------

        _renderDetailLayer(w, h) {
            const ctx = this._detailCtx;
            ctx.clearRect(0, 0, w, h);
            ctx.fillStyle = '#0b1220';
            ctx.fillRect(0, 0, w, h);

            const d = this.detail;
            if (!d) return;

            const z = clamp(Math.round(d.z), MAP_ZOOM_MIN, MAP_ZOOM_MAX);
            // Fractional zoom between integer tile levels is handled by
            // scaling the already-fetched tiles rather than waiting on a
            // new fetch for every wheel tick — matches how most slippy
            // maps smooth continuous zoom.
            const scaleFactor = Math.pow(2, d.z - z);
            const centerPxX = lonToTileX(d.lon, z) * TILE_SIZE;
            const centerPxY = latToTileY(d.lat, z) * TILE_SIZE;
            const tilesAcrossX = Math.ceil(w / TILE_SIZE / scaleFactor / 2) + 2;
            const tilesAcrossY = Math.ceil(h / TILE_SIZE / scaleFactor / 2) + 2;
            const centerTileX = Math.floor(centerPxX / TILE_SIZE);
            const centerTileY = Math.floor(centerPxY / TILE_SIZE);
            const maxTile = Math.pow(2, z);

            let anyLoading = false;
            let anyFailed = false;

            ctx.save();
            ctx.translate(w / 2, h / 2);
            ctx.scale(scaleFactor, scaleFactor);

            for (let ty = centerTileY - tilesAcrossY; ty <= centerTileY + tilesAcrossY; ty++) {
                if (ty < 0 || ty >= maxTile) continue;
                for (let tx = centerTileX - tilesAcrossX; tx <= centerTileX + tilesAcrossX; tx++) {
                    const wrappedX = ((tx % maxTile) + maxTile) % maxTile;
                    const tile = this._getTile(z, wrappedX, ty);
                    const screenX = tx * TILE_SIZE - centerPxX;
                    const screenY = ty * TILE_SIZE - centerPxY;
                    if (tile.loaded && tile.img) {
                        ctx.drawImage(tile.img, screenX, screenY, TILE_SIZE, TILE_SIZE);
                    } else {
                        ctx.fillStyle = 'rgba(255,255,255,0.035)';
                        ctx.fillRect(screenX, screenY, TILE_SIZE, TILE_SIZE);
                        if (tile.failed) anyFailed = true;
                        else anyLoading = true;
                    }
                }
            }
            ctx.restore();

            this._detailLoading = anyLoading;
            this._detailOffline = anyFailed && !anyLoading;

            this._drawDetailMarkers(ctx, w, h, z, scaleFactor, centerPxX, centerPxY);
        }

        _projectDetailPoint(lat, lng, z, scaleFactor, centerPxX, centerPxY, w, h) {
            const px = lonToTileX(lng, z) * TILE_SIZE;
            const py = latToTileY(lat, z) * TILE_SIZE;
            return {
                x: w / 2 + (px - centerPxX) * scaleFactor,
                y: h / 2 + (py - centerPxY) * scaleFactor,
            };
        }

        _drawDetailMarkers(ctx, w, h, z, scaleFactor, centerPxX, centerPxY) {
            const project = (s) => this._projectDetailPoint(s.lat, s.lng, z, scaleFactor, centerPxX, centerPxY, w, h);

            if (this.mode === 'route' && this.stops.length > 1) {
                ctx.beginPath();
                this.stops.forEach((s, i) => {
                    const p = project(s);
                    if (i === 0) ctx.moveTo(p.x, p.y); else ctx.lineTo(p.x, p.y);
                });
                ctx.setLineDash([6, 5]);
                ctx.lineWidth = 2.5;
                ctx.strokeStyle = this.color;
                ctx.shadowColor = this.color;
                ctx.shadowBlur = 6;
                ctx.stroke();
                ctx.setLineDash([]);
                ctx.shadowBlur = 0;
            }

            const margin = 60;
            const projected = this.stops
                .map((s, i) => ({ s, i, p: project(s) }))
                .filter((o) => o.p.x > -margin && o.p.x < w + margin && o.p.y > -margin && o.p.y < h + margin);

            this._hitTargets = [];
            const labelRects = [];
            const overlapsExisting = (rx, ry, rw, rh) => labelRects.some(r =>
                rx < r.x + r.w && rx + rw > r.x && ry < r.y + r.h && ry + rh > r.y);

            for (const o of projected) {
                const { s, p } = o;
                const color = s.color || this.color;
                const emoji = s.emoji || this.defaultEmoji;
                const isSelected = this.selected === s;

                ctx.save();
                ctx.shadowColor = 'rgba(0,0,0,0.55)';
                ctx.shadowBlur = 5;
                ctx.beginPath();
                ctx.arc(p.x, p.y, isSelected ? 12 : 9, 0, Math.PI * 2);
                ctx.fillStyle = color;
                ctx.fill();
                ctx.lineWidth = 2;
                ctx.strokeStyle = '#fff';
                ctx.shadowBlur = 0;
                ctx.stroke();
                ctx.font = '10px sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(emoji, p.x, p.y + 0.5);
                ctx.restore();

                const label = this.mode === 'points' ? s.name : `${o.i + 1}. ${s.name}`;
                ctx.font = '600 11px sans-serif';
                const tw = ctx.measureText(label).width;
                const lx = p.x, ly = p.y - 18;
                const pad = 6;
                const rw = tw + pad * 2, rh = 17;
                const rx = lx - rw / 2, ry = ly - rh / 2;
                if (isSelected || !overlapsExisting(rx, ry, rw, rh)) {
                    labelRects.push({ x: rx, y: ry, w: rw, h: rh });
                    ctx.save();
                    ctx.fillStyle = 'rgba(10,10,14,0.82)';
                    ctx.strokeStyle = 'rgba(255,255,255,0.15)';
                    ctx.lineWidth = 1;
                    ctx.beginPath();
                    if (ctx.roundRect) ctx.roundRect(rx, ry, rw, rh, 6);
                    else ctx.rect(rx, ry, rw, rh);
                    ctx.fill();
                    ctx.stroke();
                    ctx.fillStyle = '#f1f1f1';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(label, lx, ly + 0.5);
                    ctx.restore();
                }

                this._hitTargets.push({ x: p.x, y: p.y, stop: s });
            }
        }
    }

    global.Globe3D = Globe3D;
})(window);
