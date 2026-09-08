// Run with: node --test app/src/test/js/pdf-zoom.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
    const renders = [];
    let timer;
    const listeners = {};
    const visualViewport = {
        scale: 3, pageLeft: 100, pageTop: 200, width: 120, height: 240,
        addEventListener(name, handler) { listeners[name] = handler; }
    };
    const layer = {};
    let detail = null;
    const pageInfo = {
        num: 1, scale: 0.5,
        el: {
            getBoundingClientRect: () => ({ left: 8, top: 8, width: 360, height: 600 }),
            querySelector: selector => selector === '.highlight-layer' ? layer : detail,
            insertBefore(canvas, reference) {
                assert.equal(reference, layer);
                detail = canvas;
            }
        }
    };
    const page = {
        getViewport: ({ scale }) => ({ width: 720 * scale, height: 1200 * scale }),
        render(options) {
            renders.push(options);
            return { promise: Promise.resolve(), cancel() {} };
        }
    };
    const context = vm.createContext({
        console,
        setTimeout(callback) { timer = callback; return 1; },
        clearTimeout() { timer = null; },
        pdfjsLib: { GlobalWorkerOptions: {} },
        window: {
            visualViewport, devicePixelRatio: 2, scrollX: 0, scrollY: 0,
            innerWidth: 360, innerHeight: 720, addEventListener() {}
        },
        document: {
            getElementById: () => ({}), addEventListener() {},
            createElement: () => ({
                style: {}, getContext() { return { canvas: this }; },
                remove() { if (detail === this) detail = null; }
            })
        },
        pageInfo, mockDoc: { getPage: async () => page }
    });
    const html = fs.readFileSync(path.join(__dirname, '../../main/assets/pdfjs/uviewer_pdf.html'), 'utf8');
    vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
    vm.runInContext('pdfDoc = mockDoc; pages = [pageInfo];', context);
    return {
        context, visualViewport, renders, page, listeners,
        flush: () => { const callback = timer; timer = null; return callback?.(); },
        detail: () => detail,
        render: () => vm.runInContext('renderVisibleDetails(detailSerial)', context)
    };
}

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

test('extreme zoom respects canvas memory and dimension limits', async () => {
    const s = setup();
    s.visualViewport.scale = 100;
    await s.render();
    assert.ok(s.detail().width * s.detail().height <= 16777216);
    assert.ok(s.detail().width <= 8192);
    assert.ok(s.detail().height <= 8192);
});
