// Run with: node --test app/src/test/js/pdf-zoom.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
    const renders = [];
    const blobs = new Set();
    let nextBlob = 0;
    const timers = new Map();
    let timerId = 0;
    let clock = 0;
    const listeners = {};
    const documentListeners = {};
    const visualViewport = {
        scale: 3, pageLeft: 100, pageTop: 200, width: 120, height: 240,
        addEventListener(name, handler) { listeners[name] = handler; }
    };
    const layer = {};
    const details = [];
    const pageInfo = {
        num: 1, scale: 0.5,
        el: {
            getBoundingClientRect: () => ({ left: 8, top: 8, width: 360, height: 600 }),
            querySelector: selector => selector === '.highlight-layer' ? layer :
                details.find(element => element.className === 'detail-canvas') || null,
            insertBefore(canvas, reference) {
                assert.equal(reference, layer);
                const index = details.indexOf(canvas);
                if (index >= 0) details.splice(index, 1);
                details.push(canvas);
            }
        }
    };
    const page = {
        getViewport: ({ scale }) => ({ width: 720 * scale, height: 1200 * scale }),
        render(options) {
            renders.push({ ...options, pixelWidth: options.canvasContext.canvas.width,
                pixelHeight: options.canvasContext.canvas.height });
            return { promise: Promise.resolve(), cancel() {} };
        }
    };
    const context = vm.createContext({
        console,
        setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, due: clock + delay }); return id; },
        clearTimeout(id) { timers.delete(id); },
        pdfjsLib: { GlobalWorkerOptions: {} },
        URL: {
            createObjectURL() { const url = 'blob:' + nextBlob++; blobs.add(url); return url; },
            revokeObjectURL(url) { blobs.delete(url); }
        },
        window: {
            visualViewport, devicePixelRatio: 2, scrollX: 0, scrollY: 0,
            innerWidth: 360, innerHeight: 720, addEventListener() {}
        },
        document: {
            getElementById: () => ({}),
            addEventListener(name, handler) { documentListeners[name] = handler; },
            createElement: tag => ({
                tag, children: [], style: {}, getContext() { return { canvas: this }; },
                toBlob(callback) { callback({}); },
                decode: async () => {},
                appendChild(child) { this.children.push(child); },
                querySelectorAll() {
                    return this.children.flatMap(child => child.tag === 'img' ? [child] : child.querySelectorAll());
                },
                removeAttribute(name) { delete this[name]; },
                remove() { const index = details.indexOf(this); if (index >= 0) details.splice(index, 1); }
            })
        },
        pageInfo, mockDoc: { getPage: async () => page }
    });
    const html = fs.readFileSync(path.join(__dirname, '../../main/assets/pdfjs/uviewer_pdf.html'), 'utf8');
    vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
    vm.runInContext('pdfDoc = mockDoc; pages = [pageInfo];', context);
    return {
        context, visualViewport, renders, page, listeners, documentListeners, blobs,
        flush: () => vm.runInContext('flushDetailRender()', context),
        elapse: ms => {
            const target = clock + ms;
            const results = [];
            while (true) {
                const next = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
                if (!next || next[1].due > target) break;
                clock = next[1].due;
                timers.delete(next[0]);
                results.push(next[1].callback());
            }
            clock = target;
            return Promise.all(results);
        },
        detail: () => details.find(element => element.className === 'detail-canvas') || null,
        displayed: () => details.at(-1),
        render: () => vm.runInContext('renderVisibleDetails(detailSerial)', context)
    };
}

test('tap zones follow the visible screen at both edges and the middle of a zoomed page', () => {
    const s = setup();
    const actions = [];
    s.context.window.UviewerPdf.previousPage = () => actions.push('previous');
    s.context.window.UviewerPdf.nextPage = () => actions.push('next');
    s.context.Android = s.context.window.Android = { toggleControls: () => actions.push('toggle') };
    for (const offset of [0, 120, 240]) {
        s.visualViewport.offsetLeft = offset;
        for (const fraction of [0.1, 0.5, 0.9]) {
            s.documentListeners.click({ clientX: offset + s.visualViewport.width * fraction });
        }
    }
    assert.deepEqual(actions, [
        'previous', 'toggle', 'next',
        'previous', 'toggle', 'next',
        'previous', 'toggle', 'next'
    ]);
});

test('tap zones work at normal zoom and without the visual viewport API', () => {
    const s = setup();
    const actions = [];
    s.context.window.UviewerPdf.previousPage = () => actions.push('previous');
    s.context.window.UviewerPdf.nextPage = () => actions.push('next');
    s.context.Android = s.context.window.Android = { toggleControls: () => actions.push('toggle') };
    s.visualViewport.offsetLeft = 0;
    s.visualViewport.width = 360;
    s.visualViewport.scale = 1;
    for (const visual of [s.visualViewport, undefined]) {
        s.context.window.visualViewport = visual;
        for (const clientX of [36, 180, 324]) s.documentListeners.click({ clientX });
    }
    assert.deepEqual(actions, ['previous', 'toggle', 'next', 'previous', 'toggle', 'next']);
});

test('pinch zoom renders the entire page at device density times zoom', async () => {
    const s = setup();
    await s.render();
    assert.equal(s.detail().width, 2160);
    assert.equal(s.detail().height, 3600);
    assert.deepEqual(Array.from(s.renders[0].transform), [6, 0, 0, 6, 0, 0]);
    assert.equal(s.detail().style.left, '0px');
    assert.equal(s.detail().style.width, '360px');
    assert.equal(s.renders[0].viewport.width, 360);
    assert.equal(typeof s.listeners.resize, 'function');
    assert.equal(typeof s.listeners.scroll, 'function');
    await s.render();
    assert.equal(s.renders.length, 1);
});

test('panning reuses the entire page bitmap; zoom out releases it', async () => {
    const s = setup();
    await s.render();
    const previous = s.detail();
    s.visualViewport.pageLeft += 20;
    await s.render();
    assert.equal(s.detail(), previous);
    assert.equal(s.renders.length, 1);
    s.visualViewport.scale = 1;
    await s.render();
    assert.equal(s.detail(), null);
    assert.equal(previous.width, 0);
});

test('offscreen pages release detail canvases', async () => {
    const s = setup();
    await s.render();
    s.visualViewport.pageTop = 1000;
    await s.render();
    assert.equal(s.detail(), null);
});

test('stale render does not replace the last complete image', async () => {
    const s = setup();
    await s.render();
    const previous = s.detail();
    let finish;
    let markStarted;
    const started = new Promise(resolve => { markStarted = resolve; });
    s.page.render = () => ({ promise: new Promise(resolve => {
        finish = resolve;
        markStarted();
    }), cancel() {} });
    s.visualViewport.scale = 4;
    const pending = s.render();
    await started;
    vm.runInContext('++detailSerial', s.context);
    finish();
    await pending;
    assert.equal(s.detail(), previous);
});

test('render failure retains previous image and allows retry', async () => {
    const s = setup();
    await s.render();
    const previous = s.detail();
    const original = s.page.render;
    s.page.render = () => ({
        promise: Promise.reject({ name: 'RenderingCancelledException' }), cancel() {}
    });
    s.visualViewport.scale = 4;
    await s.render();
    assert.equal(s.detail(), previous);
    s.page.render = original;
    await s.render();
    assert.notEqual(s.detail(), previous);
    assert.equal(s.detail().width, 2880);
});

test('scroll events do not cancel or restart an in-flight render of the same page', async () => {
    const s = setup();
    let finish;
    let markStarted;
    const started = new Promise(resolve => { markStarted = resolve; });
    let cancellations = 0;
    let renderCount = 0;
    s.page.render = () => {
        renderCount++;
        return {
            promise: new Promise(resolve => { finish = resolve; markStarted(); }),
            cancel() { cancellations++; }
        };
    };
    s.listeners.resize();
    const pending = s.flush();
    await started;
    s.visualViewport.pageLeft += 20;
    s.visualViewport.pageTop += 50;
    s.listeners.scroll();
    assert.equal(cancellations, 0);
    finish();
    await pending;
    assert.ok(s.detail());
    s.listeners.scroll();
    await s.flush();
    assert.equal(renderCount, 1);
});

test('10x zoom renders the full page at native zoom resolution in bounded tiles', async () => {
    const s = setup();
    s.visualViewport.scale = 10;
    await s.render();
    assert.equal(s.detail().tag, 'div');
    assert.equal(s.renders.length, 24);
    for (const render of s.renders) {
        assert.equal(render.transform[0], 20);
        assert.equal(render.transform[3], 20);
        assert.ok(render.pixelWidth <= 2048);
        assert.ok(render.pixelHeight <= 2048);
    }
    assert.equal(Math.max(...s.renders.map(r => -r.transform[4] + r.pixelWidth)), 7200);
    assert.equal(Math.max(...s.renders.map(r => -r.transform[5] + r.pixelHeight)), 12000);
    assert.equal(new Set(s.renders.map(r => r.canvasContext.canvas)).size, 1);
    assert.equal(s.renders[0].canvasContext.canvas.width, 0);
    const previous = s.detail();
    s.visualViewport.pageLeft += 20;
    await s.render();
    assert.equal(s.detail(), previous);
    assert.equal(s.renders.length, 24);
    assert.equal(s.blobs.size, 24);
    s.visualViewport.scale = 1;
    await s.render();
    assert.equal(s.detail(), null);
    assert.equal(s.blobs.size, 0);
});

test('cancelled tile rendering releases partial tiles and retains the previous image', async () => {
    const s = setup();
    await s.render();
    const previous = s.detail();
    s.visualViewport.scale = 10;
    const render = s.page.render;
    let count = 0;
    s.page.render = options => {
        if (++count === 2) vm.runInContext('++detailSerial', s.context);
        return render(options);
    };
    await s.render();
    assert.equal(s.detail(), previous);
    assert.equal(s.blobs.size, 0);
    assert.equal(s.renders.at(-1).canvasContext.canvas.width, 0);
});

test('a failed tile render releases blobs and can be retried', async () => {
    const s = setup();
    s.visualViewport.scale = 10;
    const render = s.page.render;
    let count = 0;
    s.page.render = options => {
        if (++count === 2) return { promise: Promise.reject({ name: 'RenderingCancelledException' }) };
        return render(options);
    };
    await s.render();
    assert.equal(s.detail(), null);
    assert.equal(s.blobs.size, 0);
    s.page.render = render;
    await s.render();
    assert.equal(s.detail().children.length, 24);
});

test('visible tiles appear before the full page is ready and panning reprioritizes remaining tiles', async () => {
    const s = setup();
    s.visualViewport.scale = 10;
    s.visualViewport.width = 36;
    s.visualViewport.height = 72;
    s.visualViewport.pageLeft = 330;
    s.visualViewport.pageTop = 536;
    let finishSecond;
    let markSecond;
    const secondStarted = new Promise(resolve => { markSecond = resolve; });
    const render = s.page.render;
    let count = 0;
    s.page.render = options => {
        const task = render(options);
        if (++count === 2) task.promise = new Promise(resolve => {
            finishSecond = resolve;
            markSecond();
        });
        return task;
    };
    const pending = s.render();
    await secondStarted;
    assert.equal(s.detail(), null); // Full page is still incomplete.
    assert.equal(s.displayed().children.length, 1); // First tile is already on screen.
    assert.equal(s.renders[0].transform[4], -6130);
    assert.equal(s.renders[0].transform[5], -10218);
    s.visualViewport.pageLeft = 8;
    s.visualViewport.pageTop = 8;
    finishSecond();
    await pending;
    assert.equal(s.renders[2].transform[4], -0);
    assert.equal(s.renders[2].transform[5], -0);
    assert.equal(new Set(s.renders.map(r => r.transform.slice(4).join(':'))).size, 24);
    assert.equal(s.detail().children.length, 24);
});

test('scale changes cancel the previous render and start the latest after the debounce', async () => {
    const s = setup();
    const starts = [];
    let notifyStart;
    let started = new Promise(resolve => { notifyStart = resolve; });
    let cancellations = 0;
    s.page.render = options => {
        const task = {};
        task.promise = new Promise((resolve, reject) => {
            task.finish = resolve;
            task.cancel = () => { cancellations++; reject({ name: 'RenderingCancelledException' }); };
        });
        starts.push({ task, scale: options.transform[0] });
        notifyStart();
        return task;
    };
    const first = s.listeners.resize();
    s.elapse(120);
    await started;
    started = new Promise(resolve => { notifyStart = resolve; });
    s.visualViewport.scale = 4;
    const second = s.listeners.resize();
    s.elapse(120);
    await started;
    assert.equal(cancellations, 1);
    assert.deepEqual(starts.map(start => start.scale), [6, 8]);
    starts[1].task.finish();
    await Promise.all([first, second]);
    assert.equal(s.detail().width, 2880);
});

test('fractional zoom and page sizes share exact tile boundaries and PDF coordinates', async () => {
    const s = setup();
    const width = 359.984375;
    const height = 599.984375;
    s.context.pageInfo.el.getBoundingClientRect = () => ({ left: 8, top: 8, width, height });
    s.visualViewport.scale = 7.35;
    s.context.window.devicePixelRatio = 2.625;
    await s.render();
    const layer = s.detail();
    const pixelWidth = parseFloat(layer.style.width);
    const pixelHeight = parseFloat(layer.style.height);
    const [cssScaleX, cssScaleY] = layer.style.transform.match(/scale\(([^)]+)\)/)[1].split(',').map(Number);
    const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-8,
        `${actual} should equal ${expected}`);
    close(pixelWidth * cssScaleX, width);
    close(pixelHeight * cssScaleY, height);
    let area = 0;
    const rows = new Map();
    layer.children.forEach((region, index) => {
        const x = parseFloat(region.style.left);
        const y = parseFloat(region.style.top);
        const w = parseFloat(region.style.width);
        const h = parseFloat(region.style.height);
        const image = region.children[0];
        const tx = s.renders[index].transform;
        area += w * h;
        for (const value of [x, y, w, h]) assert.ok(Number.isInteger(value));
        // A PDF point lands at the same displayed coordinate in every tile,
        // including the clipped gutter and the last partial row/column.
        close((123.456 * tx[0] + tx[4] + parseFloat(image.style.left) + x) * cssScaleX,
            123.456 * width / 360);
        close((456.789 * tx[3] + tx[5] + parseFloat(image.style.top) + y) * cssScaleY,
            456.789 * height / 600);
        if (!rows.has(y)) rows.set(y, []);
        rows.get(y).push({ x, w, h });
    });
    let nextY = 0;
    for (const [y, row] of [...rows].sort((a, b) => a[0] - b[0])) {
        assert.equal(y, nextY);
        let nextX = 0;
        for (const region of row.sort((a, b) => a.x - b.x)) {
            assert.equal(region.x, nextX);
            nextX += region.w;
        }
        assert.equal(nextX, pixelWidth);
        nextY += row[0].h;
    }
    assert.equal(nextY, pixelHeight);
    assert.equal(area, pixelWidth * pixelHeight);
});

test('tiles complete clockwise rings before stepping outward from the screen center', async () => {
    const s = setup();
    s.visualViewport.scale = 10;
    s.visualViewport.pageLeft = 8 + 1.5 * 2044 / 20 - 18;
    s.visualViewport.pageTop = 8 + 2.5 * 2044 / 20 - 18;
    s.visualViewport.width = 36;
    s.visualViewport.height = 36;
    await s.render();
    const cells = Array.from(s.detail().children, region => [
        parseFloat(region.style.left) / 2044, parseFloat(region.style.top) / 2044
    ]);
    assert.deepEqual(cells.slice(0, 9), [
        [1, 2], [2, 2], [2, 3], [1, 3], [0, 3], [0, 2], [0, 1], [1, 1], [2, 1]
    ]);
    let previousRing = -1;
    for (const [column, row] of cells) {
        const ring = Math.max(Math.abs(column - 1), Math.abs(row - 2));
        assert.ok(ring >= previousRing);
        previousRing = ring;
    }
});

test('the visible left third is rendered before any offscreen tile', async () => {
    const s = setup();
    s.visualViewport.scale = 10;
    s.visualViewport.pageLeft = 8 + 100 / 20;
    s.visualViewport.pageTop = 8 + 2.5 * 2044 / 20 - 10;
    s.visualViewport.width = (3 * 2044 - 200) / 20;
    s.visualViewport.height = 20;
    await s.render();
    const cells = Array.from(s.detail().children, region => [
        parseFloat(region.style.left) / 2044, parseFloat(region.style.top) / 2044
    ]);
    assert.deepEqual(cells.slice(0, 3), [[1, 2], [2, 2], [0, 2]]);
    assert.equal(cells.length, 24);
});

test('the first native pinch renders without viewport resize or scroll events', async () => {
    const s = setup();
    s.visualViewport.scale = 1;
    await s.render();
    assert.equal(s.detail(), null);
    // WebView reports physical pixels per CSS pixel; DPR is 2 in this fixture.
    s.context.window.UviewerPdf.onNativeScaleChanged(6);
    await s.elapse(120);
    assert.equal(s.detail().width, 2160);
    assert.equal(s.renders.length, 1);
    await s.context.window.UviewerPdf.onNativeScaleChanged(6);
    assert.equal(s.renders.length, 1);
    s.context.window.UviewerPdf.onNativeScaleChanged(8);
    await s.elapse(120);
    assert.equal(s.detail().width, 2880);
    s.visualViewport.scale = 4; // Even if the JS viewport now lags zoom-out.
    s.context.window.UviewerPdf.onNativeScaleChanged(2);
    await s.elapse(120);
    assert.equal(s.detail(), null);
});

test('rapid pinch updates coalesce into one render 120ms after the latest scale change', async () => {
    const s = setup();
    const first = s.listeners.resize();
    s.elapse(60);
    s.visualViewport.scale = 3.5;
    const second = s.listeners.resize();
    s.elapse(60);
    s.visualViewport.scale = 4;
    const last = s.listeners.resize();
    await s.elapse(119);
    assert.equal(s.renders.length, 0);
    // A duplicate viewport notification must not extend the delay.
    s.listeners.resize();
    await s.elapse(1);
    await Promise.all([first, second, last]);
    assert.equal(s.renders.length, 1);
    assert.equal(s.detail().width, 2880);
});

test('releasing the last finger flushes the pending scale without a second timer render', async () => {
    const s = setup();
    const pending = s.context.window.UviewerPdf.onNativeScaleChanged(8);
    await s.documentListeners.touchend({ touches: [{}] });
    assert.equal(s.renders.length, 0);
    await s.documentListeners.touchend({ touches: [] });
    await pending;
    assert.equal(s.renders.length, 1);
    assert.equal(s.detail().width, 2880);
    await s.elapse(120);
    assert.equal(s.renders.length, 1);
});

test('native pinch completion renders when WebView omits DOM touchend', async () => {
    const s = setup();
    s.visualViewport.scale = 1;
    s.context.window.UviewerPdf.onNativeScaleChanged(8);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    assert.equal(s.detail().width, 2880);
    await s.elapse(360);
    assert.equal(s.renders.length, 1);
});

test('a viewport scale arriving after finger release renders without resize or scroll', async () => {
    const s = setup();
    s.visualViewport.scale = 1;
    s.context.window.UviewerPdf.onNativeScaleChanged(2);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    assert.equal(s.detail(), null);
    s.visualViewport.scale = 3;
    await s.elapse(60);
    assert.equal(s.detail().width, 2160);
    await s.elapse(300);
    assert.equal(s.renders.length, 1);
});

test('settlement checks do not interrupt an active render', async () => {
    const s = setup();
    let finish;
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    let cancellations = 0;
    let count = 0;
    s.page.render = () => {
        count++;
        return {
            promise: new Promise(resolve => { finish = resolve; started(); }),
            cancel() { cancellations++; }
        };
    };
    const pending = s.context.window.UviewerPdf.onNativeGestureEnd();
    await ready;
    const checks = s.elapse(360);
    assert.equal(count, 1);
    assert.equal(cancellations, 0);
    finish();
    await Promise.all([pending, checks]);
    assert.ok(s.detail());
});

test('starting another pinch cancels previous settlement checks', async () => {
    const s = setup();
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    s.context.window.UviewerPdf.onNativeGestureStart();
    s.visualViewport.scale = 4;
    await s.elapse(360);
    assert.equal(s.renders.length, 1);
});
