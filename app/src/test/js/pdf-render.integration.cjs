// Real raster check, separate from the fast unit suite.
// Install @napi-rs/canvas in a temporary directory, then set
// UVIEWER_TEST_CANVAS_MODULE to that directory's node_modules/@napi-rs/canvas.
// Run: node --test app/src/test/js/pdf-render.integration.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { setup, cssMatrix } = require('./pdf-test-helpers.cjs');
const { createCanvas } = require(process.env.UVIEWER_TEST_CANVAS_MODULE || '@napi-rs/canvas');
const pdfjs = require('../../main/assets/pdfjs/pdf.min.js');
pdfjs.GlobalWorkerOptions.workerSrc = require.resolve('../../main/assets/pdfjs/pdf.worker.min.js');

function vectorPdf({ solid = false } = {}) {
    const commands = ['1 1 1 rg 0 0 720 1200 re f'];
    for (let y = 0; y < 1200; y += 31) {
        commands.push(`${(y % 113) / 113} 0.25 0.65 rg 0 ${y} 720 15 re f`);
    }
    for (let x = 0; x < 720; x += 37) {
        commands.push(`0.12 ${(x % 97) / 97} 0.35 rg ${x} 0 9 1200 re f`);
    }
    commands.push('0 0 0 rg 213 420 60 120 re f');
    if (solid) commands.splice(0, commands.length, '0 0 0 rg 0 0 720 1200 re f');
    const stream = commands.join('\n');
    const objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 720 1200] /Resources << >> /Contents 4 0 R >>',
        `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`
    ];
    let pdf = '%PDF-1.4\n';
    const offsets = [0];
    objects.forEach((object, i) => {
        offsets.push(Buffer.byteLength(pdf));
        pdf += `${i + 1} 0 obj\n${object}\nendobj\n`;
    });
    const xref = Buffer.byteLength(pdf);
    pdf += `xref\n0 5\n0000000000 65535 f \n`;
    offsets.slice(1).forEach(offset => { pdf += `${String(offset).padStart(10, '0')} 00000 n \n`; });
    pdf += `trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
    return new Uint8Array(Buffer.from(pdf));
}

test('bundled PDF.js renders visible direct canvases with pixels matching a full-resolution reference', async () => {
    const loading = pdfjs.getDocument({ data: vectorPdf(), useSystemFonts: false });
    const doc = await loading.promise;
    try {
        const page = await doc.getPage(1);
        const s = setup({ createCanvas });
        s.page.getViewport = options => page.getViewport(options);
        const record = s.page.render;
        s.page.render = options => {
            record(options);
            return page.render(options);
        };
        // Current DOM viewport is displaced; native offset has not caught up.
        s.context.window.UviewerPdf.onNativeViewportChanged(6, 0, 0, 720, 1440);
        await s.context.window.UviewerPdf.onNativeGestureEnd();
        assert.ok(s.renders.length > 0 && s.renders.length <= 6);
        const reference = createCanvas(2160, 3600);
        await page.render({ canvasContext: reference.getContext('2d'),
            viewport: page.getViewport({ scale: 0.5 }), transform: [6, 0, 0, 6, 0, 0] }).promise;
        const full = reference.getContext('2d').getImageData(0, 0, 2160, 3600).data;
        let checkedPixels = 0;
        let differingChannels = 0;
        let maxDelta = 0;
        let totalDelta = 0;
        for (const render of s.renders) {
            const canvas = render.canvasContext.canvas;
            const actual = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            const left = -render.transform[4];
            const top = -render.transform[5];
            for (let y = 2; y < canvas.height - 2; y++) for (let x = 2; x < canvas.width - 2; x++) {
                const a = (y * canvas.width + x) * 4;
                const b = ((top + y) * 2160 + left + x) * 4;
                for (let c = 0; c < 4; c++) {
                    const delta = Math.abs(actual[a + c] - full[b + c]);
                    if (delta > 1) differingChannels++;
                    maxDelta = Math.max(maxDelta, delta);
                    totalDelta += delta;
                }
                checkedPixels++;
            }
        }
        assert.ok(checkedPixels > 1000000);
        assert.equal(differingChannels, 0, `tile pixels differ from reference (max delta ${maxDelta}, total ${totalDelta})`);
        assert.equal(s.detail().querySelectorAll('canvas').length, s.renders.length);
        s.visualViewport.scale = 1;
        s.context.window.UviewerPdf.onNativeViewportChanged(2, 0, 0, 720, 1440);
        await s.flush();
        assert.equal(s.detail(), null); // Backing-store release is covered by the DOM unit fixture.
        reference.width = 0;
    } finally {
        await loading.destroy();
    }
});


function compositeDetail(layer, { outputScale, left, top, width = 512, height = 512, fraction = 0 }) {
    const result = createCanvas(width, height);
    const ctx = result.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, width, height);
    ctx.setTransform(outputScale, 0, 0, outputScale,
        -left * outputScale + fraction, -top * outputScale + fraction);
    const layout = value => Math.trunc(parseFloat(value || '0') * 64) / 64;
    ctx.translate(layout(layer.style.left), layout(layer.style.top));
    ctx.transform(...cssMatrix(layer));
    const regions = [...layer.children].sort((a, b) => Number(a.style.zIndex || 0) - Number(b.style.zIndex || 0));
    for (const region of regions) {
        const canvas = region.children[0];
        ctx.save();
        ctx.translate(layout(region.style.left), layout(region.style.top));
        ctx.transform(...cssMatrix(region));
        ctx.beginPath();
        ctx.rect(0, 0, layout(region.style.width), layout(region.style.height));
        ctx.clip();
        ctx.drawImage(canvas, layout(canvas.style.left), layout(canvas.style.top),
            layout(canvas.style.width), layout(canvas.style.height));
        ctx.restore();
    }
    return result;
}

function assertBlack(canvas, message) {
    const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    let brightest = 0;
    for (let i = 0; i < data.length; i += 4) brightest = Math.max(brightest, data[i], data[i + 1], data[i + 2]);
    assert.equal(brightest, 0, message);
}

test('composited tile edges and corners stay covered at fractional zoom and screen offsets', async () => {
    const loading = pdfjs.getDocument({ data: vectorPdf({ solid: true }), useSystemFonts: false });
    const doc = await loading.promise;
    try {
        const page = await doc.getPage(1);
        const s = setup({ createCanvas });
        const width = 359.984375;
        const height = 599.984375;
        const density = 2.625;
        const zoom = 7.35;
        const outputScale = density * zoom;
        const pixelWidth = Math.ceil(width * outputScale);
        const pixelHeight = Math.ceil(height * outputScale);
        const pixelX = pixelWidth / width;
        const pixelY = pixelHeight / height;
        s.context.pageInfo.el.getBoundingClientRect = () => ({ left: 8, top: 8, width, height });
        s.context.window.devicePixelRatio = density;
        Object.assign(s.visualViewport, { scale: zoom, pageLeft: 8 + 900 / pixelX,
            pageTop: 8 + 1800 / pixelY, width: 600 / pixelX, height: 600 / pixelY });
        s.page.getViewport = options => page.getViewport(options);
        const record = s.page.render;
        s.page.render = options => { record(options); return page.render(options); };
        await s.render();
        const layer = s.detail();
        // Compose using layout-quantized bounds and CSS matrices, on white
        // backing. An uncovered edge/corner exposes a bright seam in a black PDF.
        for (const fraction of [0, 0.25, 0.5, 0.75]) {
            const result = compositeDetail(layer, { outputScale, fraction,
                left: 950 * width / pixelWidth, top: 1850 * height / pixelHeight });
            assertBlack(result, `visible seam at fractional screen offset ${fraction}`);
            result.width = 0;
        }
    } finally {
        await loading.destroy();
    }
});


test('the lower screen stays fully covered through the last partial tile row at high zoom', async () => {
    const loading = pdfjs.getDocument({ data: vectorPdf({ solid: true }), useSystemFonts: false });
    const doc = await loading.promise;
    try {
        const page = await doc.getPage(1);
        for (const zoom of [3, 7.35, 10]) {
            const s = setup({ createCanvas });
            const width = 359.984375;
            const height = 599.984375;
            const density = 2.625;
            const outputScale = density * zoom;
            s.context.pageInfo.el.getBoundingClientRect = () => ({ left: 8, top: 8, width, height });
            s.context.window.devicePixelRatio = density;
            Object.assign(s.visualViewport, { scale: zoom,
                pageLeft: 8 + width - 720 / outputScale,
                pageTop: 8 + height - 1440 / outputScale,
                width: 720 / outputScale, height: 1440 / outputScale });
            s.page.getViewport = options => page.getViewport(options);
            const record = s.page.render;
            s.page.render = options => { record(options); return page.render(options); };
            await s.context.window.UviewerPdf.onNativeGestureEnd();
            const layer = s.detail();
            assert.equal(parseFloat(layer.style.width), width);
            assert.equal(parseFloat(layer.style.height), height);
            assert.ok(s.renders.every(render => render.pixelWidth <= 1024 && render.pixelHeight <= 1024));
            assert.ok(s.renders.some(render => -render.transform[5] + render.pixelHeight === Math.ceil(height * outputScale)));
            const result = compositeDetail(layer, { outputScale, height: 1024,
                left: width - 600 / outputScale, top: height - 1025 / outputScale });
            assertBlack(result, `unrendered pixels at the bottom of the screen at ${zoom}x`);
            s.visualViewport.scale = 1;
            await s.render();
            result.width = 0;
        }
    } finally {
        await loading.destroy();
    }
});
