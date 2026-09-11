const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup({ createCanvas } = {}) {
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
            createElement: tag => {
                const element = {
                tag, tagName: tag.toUpperCase(), children: [], style: {}, getContext() { return { canvas: this }; },
                toBlob() { assert.fail('visible detail must not wait for PNG encoding'); },
                decode: async () => { assert.fail('visible detail must not wait for image decoding'); },
                appendChild(child) { child.parent = this; this.children.push(child); },
                querySelectorAll(selector) {
                    return this.children.flatMap(child => child.tag === selector ? [child] : child.querySelectorAll(selector));
                },
                removeAttribute(name) { delete this[name]; },
                remove() {
                    const siblings = this.parent ? this.parent.children : details;
                    const index = siblings.indexOf(this); if (index >= 0) siblings.splice(index, 1);
                }
                };
                if (tag === 'canvas' && createCanvas) {
                    delete element.getContext;
                    delete element.toBlob;
                    return Object.assign(createCanvas(1, 1), element);
                }
                return element;
            }
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

function cssMatrix(element) {
    const transform = element.style.transform || '';
    const matrix = transform.match(/matrix\(([^)]+)\)/);
    if (matrix) return matrix[1].split(',').map(Number);
    const scale = transform.match(/scale\(([^)]+)\)/);
    if (scale) {
        const [x, y] = scale[1].split(',').map(Number);
        return [x, 0, 0, y, 0, 0];
    }
    return [1, 0, 0, 1, 0, 0];
}

function elementPoint(element, x, y, length = parseFloat) {
    const [a, b, c, d, e, f] = cssMatrix(element);
    return { x: a * x + c * y + e + length(element.style.left || '0'),
        y: b * x + d * y + f + length(element.style.top || '0') };
}

function tilePoint(layer, region, canvas, x, y, length = parseFloat) {
    const local = elementPoint(canvas, x * length(canvas.style.width) / canvas.width,
        y * length(canvas.style.height) / canvas.height, length);
    const point = elementPoint(region, local.x, local.y, length);
    return elementPoint(layer, point.x, point.y, length);
}

function tileRect(layer, region) {
    const local = elementPoint(region, 0, 0);
    const end = elementPoint(region, parseFloat(region.style.width), parseFloat(region.style.height));
    const startPoint = elementPoint(layer, local.x, local.y);
    const endPoint = elementPoint(layer, end.x, end.y);
    return { left: startPoint.x, top: startPoint.y, right: endPoint.x, bottom: endPoint.y };
}

module.exports = { setup, cssMatrix, tilePoint, tileRect };
