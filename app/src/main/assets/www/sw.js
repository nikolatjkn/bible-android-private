// Bible Study App - Service Worker
// Caches the app shell so it works offline after the first load,
// and is required (alongside manifest.json) for Chrome/Android to
// treat this as an installable PWA.

const CACHE_NAME = 'bible-study-v33';
const APP_SHELL = [
    './',
    './index.html',
    './manifest.json',
    './icon-192.png',
    './icon-512.png',
    './myanmar.js',
    './myanmar_judson_aionian.js',
    './tamil.js',
    './thngv.js',
    './thngv_info.js',
    './Myanmar_Standard_2005_Clean.js',
    './crossrefs.js',
    './audio_ids.js',
    './bookintros.js',
    './bookintros_ta_full.js',
    './kjv_italics.js',
    './hebrew.js',
    './greek_strongs_data.js',
    './redletter.js',
    './features.js',
    './features2.js',
    './titles_judson.js',
    './titles_mcl2005.js',
    './titles_kjv.js',
    './titles_tamil.js',
    './footnotes_judson.js',
    './footnotes_tamil.js',
    './bibledictionary.js',
    // Local vendor copies of React/ReactDOM/Leaflet (see index.html) —
    // these used to be loaded from CDNs, which meant the app couldn't
    // even open offline on a device that had never been online yet (or
    // whenever those CDNs weren't reachable). Precaching them here as
    // part of the app shell makes the app truly offline-first from the
    // very first install.
    './vendor/react.production.min.js',
    './vendor/react-dom.production.min.js',
    './vendor/leaflet.js',
    './vendor/leaflet.css',
    './vendor/images/marker-icon.png',
    './vendor/images/marker-icon-2x.png',
    './vendor/images/marker-shadow.png',
    './vendor/images/layers.png',
    './vendor/images/layers-2x.png',
];

// Names of the per-version caches the Offline Audio Bible download
// manager (AudioDownloadPage, in index.html) writes into. Keep this in
// sync with offlineAudioCacheName() there — one cache per audio_ids.js
// language code.
const OFFLINE_AUDIO_CACHES = ['bible-audio-en', 'bible-audio-my', 'bible-audio-ta'];

// Matches every URL an audio chapter could stream from: Drive's iframe
// preview host, its file-serving CDN host, and the googleapis.com Drive
// v3 media endpoint used by the full-mode <audio> player.
const AUDIO_URL_PATTERN = /drive\.google\.com|googleusercontent\.com|googleapis\.com\/drive|\.mp3(\?|$)/i;

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL))
    );
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((keys) =>
            Promise.all(
                keys
                    .filter((k) => k !== CACHE_NAME && OFFLINE_AUDIO_CACHES.indexOf(k) === -1)
                    .map((k) => caches.delete(k))
            )
        )
    );
    self.clients.claim();
});

self.addEventListener('fetch', (event) => {
    if (event.request.method !== 'GET') return;
    const url = event.request.url;

    if (AUDIO_URL_PATTERN.test(url)) {
        // Audio chapters are never auto-cached just from being played —
        // that only happens when the user explicitly downloads a version
        // from the Offline Audio Bible page. Here we just check whether
        // that download already put this exact chapter in one of the
        // per-version caches; if so, serve it with zero network (this is
        // what makes downloaded chapters work in airplane mode). If not
        // found, stream fresh from the network as before.
        event.respondWith(
            (async () => {
                for (const name of OFFLINE_AUDIO_CACHES) {
                    const cache = await caches.open(name);
                    const hit = await cache.match(event.request);
                    if (hit) return hit;
                }
                return fetch(event.request);
            })()
        );
        return;
    }

    // Cache-first: once loaded, the app (including the big embedded Bible
    // data inside index.html) works with no network at all.
    event.respondWith(
        caches.match(event.request).then((cached) => {
            if (cached) return cached;
            return fetch(event.request)
                .then((response) => {
                    if (response.ok) {
                        const clone = response.clone();
                        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
                    }
                    return response;
                })
                .catch(() => cached);
        })
    );
});
