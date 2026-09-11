// Run with: node --test app/src/test/js/pdf-zoom.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');

const { setup, tilePoint, tileRect } = require('./pdf-test-helpers.cjs');

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

function layerScale(layer) {
    const match = layer.style.transform?.match(/scale\(([^)]+)\)/);
    return match ? match[1].split(',').map(Number) : [1, 1];
}

function nativeViewport(s, scale, left, top, width, height) {
    return s.context.window.UviewerPdf.onNativeViewportChanged(scale * 2,
        left * scale * 2, top * scale * 2, width * scale * 2, height * scale * 2);
}

function assertVisible(render, visual, bounds = { left: 8, top: 8, width: 360, height: 600 }) {
    const [sx, , , sy, tx, ty] = render.transform;
    const left = bounds.left - tx / sx;
    const top = bounds.top - ty / sy;
    const right = left + render.pixelWidth / sx;
    const bottom = top + render.pixelHeight / sy;
    assert.ok(left < visual.pageLeft + visual.width && right > visual.pageLeft &&
        top < visual.pageTop + visual.height && bottom > visual.pageTop,
        `tile (${left}, ${top}, ${right}, ${bottom}) must intersect the visible screen`);
}

function holdRender(s, number = 1) {
    const original = s.page.render;
    let finish;
    let fail;
    let started;
    let cancellations = 0;
    const ready = new Promise(resolve => { started = resolve; });
    s.page.render = options => {
        const task = original(options);
        if (s.renders.length === number) {
            task.promise = new Promise((resolve, reject) => { finish = resolve; fail = reject; started(); });
            task.cancel = () => { cancellations++; fail({ name: 'RenderingCancelledException' }); };
        }
        return task;
    };
    return { ready, finish: () => finish(), fail: error => fail(error), cancelled: () => cancellations };
}

test('settled DOM viewport wins over stale native offsets at the same zoom', async () => {
    const s = setup();
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 330, pageTop: 536, width: 36, height: 72 });
    nativeViewport(s, 10, 0, 0, 36, 72);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    assert.ok(s.renders.length > 0);
    s.renders.forEach(render => assertVisible(render, s.visualViewport));
});

test('late DOM position corrects the first native snapshot without scroll or resize events', async () => {
    const s = setup();
    Object.assign(s.visualViewport, { scale: 1, pageLeft: 0, pageTop: 0, width: 360, height: 720 });
    nativeViewport(s, 10, 0, 0, 36, 72);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    const oldCount = s.renders.length;
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 330, pageTop: 536, width: 36, height: 72 });
    await s.elapse(60);
    assert.ok(s.renders.length > oldCount);
    s.renders.slice(oldCount).forEach(render => assertVisible(render, s.visualViewport));
});

test('a compositor commit rechecks the viewport even after settlement timers expire', async () => {
    const s = setup();
    s.visualViewport.scale = 1;
    nativeViewport(s, 10, 0, 0, 36, 72);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    await s.elapse(360);
    const oldCount = s.renders.length;
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 330, pageTop: 536, width: 36, height: 72 });
    await s.context.window.UviewerPdf.refreshDetailViewport();
    assert.ok(s.renders.length > oldCount);
    s.renders.slice(oldCount).forEach(render => assertVisible(render, s.visualViewport));
});

test('native position is used while DOM zoom still lags', async () => {
    const s = setup();
    s.visualViewport.scale = 1;
    nativeViewport(s, 10, 330, 536, 36, 72);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    s.renders.forEach(render => {
        assert.equal(render.transform[0], 20);
        assertVisible(render, { pageLeft: 330, pageTop: 536, width: 36, height: 72 });
    });
    const count = s.renders.length;
    s.visualViewport.scale = 3; // Late intermediate scale cannot downgrade the native snapshot.
    await s.elapse(360);
    assert.equal(s.renders.length, count);
});

test('10x detail renders only the visible area, at full density, without PNG encoding', async () => {
    const s = setup();
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 150, pageTop: 400, width: 36, height: 72 });
    await s.render();
    assert.ok(s.renders.length > 0 && s.renders.length <= 6);
    s.renders.forEach(render => {
        assertVisible(render, s.visualViewport);
        assert.equal(render.transform[0], 20);
        assert.equal(render.transform[3], 20);
        assert.ok(render.pixelWidth <= 1024 && render.pixelHeight <= 1024);
        assert.ok(render.canvasContext.canvas.width > 0);
    });
    assert.equal(s.detail().querySelectorAll('canvas').length, s.renders.length);
    assert.equal(s.blobs.size, 0);
    const count = s.renders.length;
    await s.elapse(2000);
    assert.equal(s.renders.length, count); // No peripheral background work.
});

test('the first ready canvas is displayed while the next visible tile is still rendering', async () => {
    const s = setup();
    const signals = [];
    s.context.window.Android = {
        onDetailRendering: active => signals.push(active ? 'start' : 'stop'),
        onDetailReady: () => signals.push('ready')
    };
    const held = holdRender(s, 2);
    const pending = s.context.window.UviewerPdf.onNativeGestureEnd();
    await held.ready;
    assert.equal(s.detail().querySelectorAll('canvas').length, 1);
    assert.deepEqual(signals, ['start', 'ready']);
    assertVisible(s.renders[0], s.visualViewport);
    held.finish();
    await pending;
    assert.equal(signals.at(-1), 'stop');
    assert.equal(signals.filter(value => value === 'ready').length, s.renders.length);
});

test('moderate zoom also uses visible tiles instead of waiting for a whole-page canvas', async () => {
    const s = setup();
    await s.render();
    const [sx, sy] = layerScale(s.detail());
    assert.equal(parseFloat(s.detail().style.width) * sx, 360);
    assert.equal(parseFloat(s.detail().style.height) * sy, 600);
    assert.ok(s.renders.length > 1);
    for (const render of s.renders) {
        assert.equal(render.transform[0], 6);
        assertVisible(render, s.visualViewport);
        assert.ok(render.pixelWidth <= 1024 && render.pixelHeight <= 1024);
    }
});

test('small pans within cached tiles reuse canvases; zoom-out releases every backing store', async () => {
    const s = setup();
    await s.render();
    const before = s.renders.length;
    const canvases = s.detail().querySelectorAll('canvas');
    s.visualViewport.pageLeft += 1;
    await s.render();
    assert.equal(s.renders.length, before);
    s.visualViewport.scale = 1;
    await s.render();
    assert.equal(s.detail(), null);
    assert.ok(canvases.every(canvas => canvas.width === 0 && canvas.height === 0));
});

test('panning to another region evicts old canvases and draws only newly visible tiles', async () => {
    const s = setup();
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 8, pageTop: 8, width: 36, height: 72 });
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    const before = s.renders.length;
    const old = s.detail().querySelectorAll('canvas');
    s.visualViewport.pageLeft = 300;
    s.visualViewport.pageTop = 520;
    s.listeners.scroll();
    await s.flush();
    assert.ok(s.renders.length > before);
    s.renders.slice(before).forEach(render => assertVisible(render, s.visualViewport));
    assert.ok(old.every(canvas => canvas.width === 0));
    assert.ok(s.detail().querySelectorAll('canvas').length <= 6);
});

test('offscreen pages release their cached tiles', async () => {
    const s = setup();
    await s.render();
    const old = s.detail().querySelectorAll('canvas');
    s.visualViewport.pageTop = 1000;
    await s.render();
    assert.equal(s.detail(), null);
    assert.ok(old.every(canvas => canvas.width === 0));
});

test('page selection uses document coordinates when layout scrolling is nonzero', async () => {
    const s = setup();
    s.context.window.scrollY = 1000;
    s.context.pageInfo.el.getBoundingClientRect = () => ({ left: 8, top: 508, width: 360, height: 600 });
    s.visualViewport.scale = 1;
    nativeViewport(s, 10, 330, 2036, 36, 72);
    await s.flush();
    assert.ok(s.renders.length > 0);
    s.renders.forEach(render => assertVisible(render,
        { pageLeft: 330, pageTop: 2036, width: 36, height: 72 }, { left: 8, top: 1508 }));
});

test('stale render completion cannot replace the last completed image', async () => {
    const s = setup();
    await s.render();
    const previous = s.detail();
    const held = holdRender(s, s.renders.length + 1);
    s.visualViewport.scale = 4;
    const pending = s.render();
    await held.ready;
    vm.runInContext('++detailSerial', s.context);
    held.finish();
    await pending;
    assert.equal(s.detail(), previous);
    assert.equal(s.renders.at(-1).canvasContext.canvas.width, 0);
});

test('render failure retains completed tiles and retries only missing work', async () => {
    const s = setup();
    const render = s.page.render;
    let attempts = 0;
    s.page.render = options => ++attempts === 2 ?
        { promise: Promise.reject({ name: 'RenderingCancelledException' }) } : render(options);
    await s.render();
    const first = s.detail().querySelectorAll('canvas')[0];
    s.page.render = render;
    await s.render();
    assert.equal(s.detail().querySelectorAll('canvas')[0], first);
    assert.equal(s.renders.filter(entry => entry.canvasContext.canvas === first).length, 1);
});

test('offscreen in-flight work is cancelled so a new visible area is not blocked', async () => {
    const s = setup();
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 8, pageTop: 8, width: 36, height: 72 });
    const held = holdRender(s);
    const old = s.context.window.UviewerPdf.onNativeGestureEnd();
    await held.ready;
    s.visualViewport.pageLeft = 300;
    s.visualViewport.pageTop = 520;
    await s.context.window.UviewerPdf.refreshDetailViewport();
    await old;
    assert.equal(held.cancelled(), 1);
    assert.equal(s.renders[0].canvasContext.canvas.width, 0);
    s.renders.slice(1).forEach(render => assertVisible(render, s.visualViewport));
});

test('duplicate viewport events and small pans do not cancel useful active work', async () => {
    const s = setup();
    const held = holdRender(s);
    const pending = s.context.window.UviewerPdf.onNativeGestureEnd();
    await held.ready;
    s.visualViewport.pageLeft += 1;
    s.listeners.scroll();
    s.listeners.resize();
    assert.equal(held.cancelled(), 0);
    held.finish();
    await pending;
    const count = s.renders.length;
    await s.elapse(360);
    assert.equal(s.renders.length, count);
});

test('fractional zoom preserves tile placement and PDF coordinates without seams', async () => {
    const s = setup();
    const width = 359.984375;
    const height = 599.984375;
    s.context.pageInfo.el.getBoundingClientRect = () => ({ left: 8, top: 8, width, height });
    s.visualViewport.scale = 7.35;
    s.context.window.devicePixelRatio = 2.625;
    await s.render();
    const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-8, `${actual} != ${expected}`);
    const rows = new Map();
    s.detail().children.forEach(region => {
        const canvas = region.children[0];
        const render = s.renders.find(entry => entry.canvasContext.canvas === canvas);
        const [sx, , , sy, tx, ty] = render.transform;
        const point = tilePoint(s.detail(), region, canvas, 123.456 * sx + tx, 456.789 * sy + ty);
        close(point.x, 123.456 * width / 360);
        close(point.y, 456.789 * height / 600);
        const rect = tileRect(s.detail(), region);
        if (!rows.has(rect.top)) rows.set(rect.top, []);
        rows.get(rect.top).push(rect);
    });
    const pixelWidth = Math.ceil(width * s.visualViewport.scale * s.context.window.devicePixelRatio);
    for (const row of rows.values()) {
        row.sort((a, b) => a.left - b.left);
        for (let i = 1; i < row.length; i++) {
            // Both sides expose one gutter pixel from the same PDF coordinates.
            close(row[i - 1].right - row[i].left, 2 * width / pixelWidth);
        }
    }
});

test('a held pinch stays on preview, then release renders without a debounce or scroll', async () => {
    const s = setup();
    s.context.window.UviewerPdf.onNativeGestureStart();
    s.context.window.UviewerPdf.onNativeScaleChanged(8);
    await s.elapse(1000);
    assert.equal(s.renders.length, 0);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    assert.ok(s.renders.length > 0);
    assert.equal(s.renders[0].transform[0], 8);
});

test('lifting one pinch finger starts rendering and the final release reuses the tiles', async () => {
    const s = setup();
    s.context.window.UviewerPdf.onNativeGestureStart();
    s.context.window.UviewerPdf.onNativeScaleChanged(8);
    await s.documentListeners.touchend({ touches: [{}, {}] });
    assert.equal(s.renders.length, 0);
    await s.documentListeners.touchend({ touches: [{}] });
    const count = s.renders.length;
    assert.ok(count > 0);
    await s.documentListeners.touchend({ touches: [] });
    await s.elapse(360);
    assert.equal(s.renders.length, count);
});

test('rapid scale changes debounce until 120ms after the latest update', async () => {
    const s = setup();
    s.listeners.resize();
    s.elapse(60);
    s.visualViewport.scale = 3.5;
    s.listeners.resize();
    s.elapse(60);
    s.visualViewport.scale = 4;
    s.listeners.resize();
    await s.elapse(119);
    assert.equal(s.renders.length, 0);
    s.listeners.resize();
    await s.elapse(1);
    assert.ok(s.renders.length > 0);
    s.renders.forEach(render => assert.equal(render.transform[0], 8));
});

test('scale arriving after release renders without resize or scroll', async () => {
    const s = setup();
    s.visualViewport.scale = 1;
    s.context.window.UviewerPdf.onNativeScaleChanged(2);
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    assert.equal(s.detail(), null);
    s.visualViewport.scale = 3;
    await s.elapse(60);
    assert.ok(s.renders.length > 0);
    assert.equal(s.renders[0].transform[0], 6);
});

test('failure stops the native frame pump without publishing an image', async () => {
    const s = setup();
    const signals = [];
    s.context.window.Android = {
        onDetailRendering: active => signals.push(active),
        onDetailReady: () => assert.fail('failed image must not be published')
    };
    s.page.render = () => ({ promise: Promise.reject({ name: 'RenderingCancelledException' }) });
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    assert.deepEqual(signals, [true, false]);
    assert.equal(s.detail(), null);
});

test('another pinch cancels rendering and stale completion cannot stop the new frame pump', async () => {
    const s = setup();
    const signals = [];
    s.context.window.Android = { onDetailRendering: active => signals.push(active) };
    const held = holdRender(s);
    const old = s.context.window.UviewerPdf.onNativeGestureEnd();
    await held.ready;
    s.context.window.UviewerPdf.onNativeGestureStart();
    assert.equal(held.cancelled(), 1);
    s.context.window.UviewerPdf.onNativeScaleChanged(8);
    const latest = s.context.window.UviewerPdf.onNativeGestureEnd();
    await Promise.all([old, latest]);
    assert.deepEqual(signals, [true, false, true, false]);
    assert.equal(s.renders.at(-1).transform[0], 8);
});

test('late compositor callbacks cannot render during another pinch', async () => {
    const s = setup();
    await s.context.window.UviewerPdf.onNativeGestureEnd();
    const count = s.renders.length;
    s.context.window.UviewerPdf.onNativeGestureStart();
    s.visualViewport.scale = 4;
    await s.context.window.UviewerPdf.refreshDetailViewport();
    await s.elapse(360);
    assert.equal(s.renders.length, count);
});

test('visible tile regions completely cover the viewport at integer and fractional zoom', async () => {
    for (const scale of [3, 4.75, 10]) {
        const s = setup();
        Object.assign(s.visualViewport, { scale, pageLeft: 97.25, pageTop: 306.5,
            width: 360 / scale, height: 720 / scale });
        await s.render();
        const left = Math.max(0, s.visualViewport.pageLeft - 8);
        const top = Math.max(0, s.visualViewport.pageTop - 8);
        const right = Math.min(360, left + s.visualViewport.width);
        const bottom = Math.min(600, top + s.visualViewport.height);
        const rects = s.detail().children.map(region => tileRect(s.detail(), region));
        // Check coverage of every horizontal band, allowing intentional gutter overlap.
        const ys = [...new Set([top, bottom, ...rects.flatMap(r => [r.top, r.bottom])])]
            .filter(y => y >= top && y <= bottom).sort((a, b) => a - b);
        for (let i = 1; i < ys.length; i++) {
            const y = (ys[i - 1] + ys[i]) / 2;
            let covered = left;
            for (const rect of rects.filter(r => r.top <= y && r.bottom >= y).sort((a, b) => a.left - b.left)) {
                assert.ok(rect.left <= covered + 1e-8, 'gap between adjacent visible tiles');
                covered = Math.max(covered, rect.right);
            }
            assert.ok(covered >= right - 1e-8, 'visible band must reach the right edge');
        }
    }
});

test('the page at screen center renders before the narrow edge of the preceding page', async () => {
    const s = setup();
    const first = s.context.pageInfo;
    s.context.secondPage = { num: 2, scale: 0.5, el: { ...first.el,
        getBoundingClientRect: () => ({ left: 8, top: 616, width: 360, height: 600 }) } };
    vm.runInContext('pages = [pageInfo, secondPage]', s.context);
    const pagesRendered = [];
    s.context.mockDoc.getPage = async num => { pagesRendered.push(num); return s.page; };
    s.visualViewport.pageTop = 590;
    await s.render();
    assert.equal(pagesRendered[0], 2);
    assert.ok(pagesRendered.includes(1));
});

test('WebView layout rounding does not shift neighbouring tile pixels at fractional zoom', async () => {
    const s = setup();
    const width = 359.984375;
    const height = 599.984375;
    s.context.pageInfo.el.getBoundingClientRect = () => ({ left: 8, top: 8, width, height });
    s.visualViewport.scale = 7.35;
    s.context.window.devicePixelRatio = 2.625;
    await s.render();
    const layer = s.detail();
    // Blink layout lengths are quantized to 1/64 CSS pixel before transforms.
    const layout = value => Math.trunc(parseFloat(value) * 64) / 64;
    for (const region of layer.children) {
        const canvas = region.children[0];
        const render = s.renders.find(entry => entry.canvasContext.canvas === canvas);
        const [sx, , , sy, tx, ty] = render.transform;
        const point = tilePoint(layer, region, canvas, 123.456 * sx + tx, 456.789 * sy + ty, layout);
        assert.ok(Math.abs(point.x - 123.456 * width / 360) < 1e-8, `tile x shifted by ${point.x - 123.456 * width / 360}`);
        assert.ok(Math.abs(point.y - 456.789 * height / 600) < 1e-8, `tile y shifted by ${point.y - 456.789 * height / 600}`);
    }
});

test('bottom tiles at high zoom do not require a page-sized backing-pixel layer', async () => {
    const s = setup();
    Object.assign(s.visualViewport, { scale: 10, pageLeft: 180, pageTop: 536, width: 120, height: 72 });
    await s.render();
    assert.ok(s.renders.length > 0);
    const layer = s.detail();
    assert.ok(parseFloat(layer.style.width) <= 360, 'container width must stay in page CSS pixels');
    assert.ok(parseFloat(layer.style.height) <= 600, 'container height must stay in page CSS pixels');
    assert.ok(s.renders.some(render => -render.transform[5] + render.pixelHeight === 12000),
        'the final partial row at the bottom of the page must be rendered');
});
