(function () {
    'use strict';

    const diagrams = new Map();
    let nextId = 0;
    const selector = 'pre > code.language-mermaid, pre > code.language-pele, pre.mermaid, pre.pele';

    function draw(host, diagram) {
        const width = Math.floor(host.getBoundingClientRect().width);
        if (width <= 0 || width === diagram.width) return;
        diagram.width = width;
        const bodyStyle = getComputedStyle(document.body);
        host.style.setProperty('--uviewer-diagram-max-height', (bodyStyle.writingMode || '').startsWith('vertical')
            ? 'calc(100vh - 2em)' : 'none');
        const foreground = bodyStyle.color;
        const rgb = foreground.match(/[\d.]+/g) || [0, 0, 0];
        const dark = Number(rgb[0]) * 0.299 + Number(rgb[1]) * 0.587 + Number(rgb[2]) * 0.114 > 150;
        const background = bodyStyle.backgroundColor;
        host.style.setProperty('--pele-fg', foreground);
        host.style.setProperty('--pele-line', foreground);
        host.style.setProperty('--pele-bg', background === 'rgba(0, 0, 0, 0)' || background === 'transparent'
            ? (dark ? '#202124' : '#ffffff') : background);
        host.style.setProperty('--pele-muted', dark ? '#b0b0b0' : '#606060');
        host.style.setProperty('--pele-surface', dark ? '#303238' : '#f2f3f5');
        host.style.setProperty('--pele-surface-alt', dark ? '#3c3f46' : '#e7e9ed');
        host.style.setProperty('--pele-border', dark ? '#858890' : '#74777f');
        host.style.setProperty('--pele-accent', dark ? '#9ac1ff' : '#245ca6');
        host.style.setProperty('--pele-font', bodyStyle.fontFamily);
        try {
            const result = pele.render(diagram.source, {
                idPrefix: diagram.id,
                maxWidth: width,
                fontFamily: bodyStyle.fontFamily,
                fontSize: parseFloat(bodyStyle.fontSize) || 16,
                // Document content must not create navigation or network requests.
                links: false,
                images: false
            });
            host.innerHTML = result.svg;
        } catch (error) {
            // Keep invalid diagrams readable. Error messages may contain document text.
            const message = document.createElement('figcaption');
            message.textContent = 'Pele: ' + (error.message || String(error));
            const source = document.createElement('pre');
            source.textContent = diagram.source;
            host.replaceChildren(message, source);
        }
    }

    const resizeObserver = typeof ResizeObserver === 'function' ? new ResizeObserver(function (entries) {
        entries.forEach(function (entry) {
            const diagram = diagrams.get(entry.target);
            if (diagram) draw(entry.target, diagram);
        });
    }) : null;

    function renderDiagrams() {
        if (typeof pele === 'undefined') return;
        diagrams.forEach(function (_, host) {
            if (!host.isConnected) {
                if (resizeObserver) resizeObserver.unobserve(host);
                diagrams.delete(host);
            }
        });
        document.querySelectorAll(selector).forEach(function (code) {
            // Error fallbacks are plain pre elements and do not match this selector.
            const pre = code.tagName === 'PRE' ? code : code.parentElement;
            if (!pre.isConnected) return;
            const host = document.createElement('figure');
            host.className = 'uviewer-diagram';
            host.setAttribute('aria-label', 'Mermaid diagram');
            const diagram = { source: code.textContent, id: 'uviewer-diagram-' + (++nextId), width: -1 };
            pre.replaceWith(host);
            diagrams.set(host, diagram);
            draw(host, diagram);
            if (resizeObserver) resizeObserver.observe(host);
        });
    }

    window.renderDiagrams = renderDiagrams;
    document.addEventListener('DOMContentLoaded', function () {
        renderDiagrams();
        // Chunks are also rendered synchronously by the scrolling code, before measuring.
        new MutationObserver(renderDiagrams).observe(document.body, { childList: true, subtree: true });
        if (document.fonts) document.fonts.ready.then(function () {
            diagrams.forEach(function (diagram, host) { diagram.width = -1; draw(host, diagram); });
        });
    });
    window.addEventListener('resize', function () {
        diagrams.forEach(function (diagram, host) { draw(host, diagram); });
    });
})();
