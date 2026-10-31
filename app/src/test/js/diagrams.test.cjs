const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const assets = path.resolve(__dirname, '../../main/assets');

// Minimal DOM surface for the integration. Pele itself runs unmodified, using its
// built-in text measurer when canvas isn't available. No browser/network is needed.
function setup() {
    let width = 360;
    const callbacks = {};
    const observed = new Set();
    class Element {
        constructor(tag) {
            this.tagName = tag.toUpperCase();
            this.children = [];
            this.style = { setProperty(name, value) { this[name] = value; } };
            this._text = '';
        }
        get isConnected() { return this === body || !!this.parentElement?.isConnected; }
        get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
        set textContent(text) { this._text = text; this.children = []; }
        setAttribute(name, value) { this[name] = value; }
        appendChild(child) { child.parentElement = this; this.children.push(child); return child; }
        replaceChildren(...children) {
            this.children.forEach(child => { child.parentElement = null; });
            this.children = [];
            children.forEach(child => this.appendChild(child));
        }
        replaceWith(next) {
            const parent = this.parentElement;
            parent.children[parent.children.indexOf(this)] = next;
            next.parentElement = parent;
            this.parentElement = null;
        }
        getBoundingClientRect() { return { width }; }
        getContext() { return null; }
    }
    const body = new Element('body');
    const document = {
        body,
        createElement: tag => new Element(tag),
        addEventListener: (event, callback) => { callbacks[event] = callback; },
        querySelectorAll() {
            const matches = [];
            function visit(element) {
                if (element.tagName === 'CODE' && element.parentElement?.tagName === 'PRE' &&
                    ['language-mermaid', 'language-pele'].includes(element.className) ||
                    element.tagName === 'PRE' && ['mermaid', 'pele'].includes(element.className)) matches.push(element);
                element.children.forEach(visit);
            }
            visit(body);
            return matches;
        }
    };
    const context = vm.createContext({
        document, console,
        getComputedStyle: () => ({ color: 'rgb(204, 204, 204)', backgroundColor: 'rgb(18, 18, 18)',
            fontFamily: 'sans-serif', fontSize: '16px' }),
        MutationObserver: class { constructor(callback) { callbacks.mutation = callback; } observe() {} },
        ResizeObserver: class {
            constructor(callback) { callbacks.resizeObserver = callback; }
            observe(element) { observed.add(element); }
            unobserve(element) { observed.delete(element); }
        },
        addEventListener: (event, callback) => { callbacks[event] = callback; }
    });
    context.window = context;
    vm.runInContext(fs.readFileSync(path.join(assets, 'pele/pele.global.js'), 'utf8'), context);
    vm.runInContext(fs.readFileSync(path.join(assets, 'viewer/diagrams.js'), 'utf8'), context);
    function add(source, language = 'mermaid', container = body) {
        const pre = container.appendChild(new Element('pre'));
        const code = pre.appendChild(new Element('code'));
        code.className = 'language-' + language;
        code.textContent = source;
        return pre;
    }
    return { context, body, callbacks, observed, add, Element, setWidth(value) { width = value; } };
}

test('the bundled Pele renders Mermaid and Pele fences, keeps other code and uses distinct SVG IDs', () => {
    const env = setup();
    env.add('flowchart LR\n accTitle: First\n A[시작] --> B[완료]');
    env.add('sequenceDiagram\n accTitle: Second\n Alice->>Bob: Hello', 'pele');
    const ordinary = env.add('const example = 1;', 'javascript');
    env.callbacks.DOMContentLoaded();
    const [first, second] = env.body.children;
    assert.match(first.innerHTML, /<svg[\s>]/);
    assert.match(first.innerHTML, /시작/);
    assert.match(second.innerHTML, /Alice/);
    assert.match(first.innerHTML, /uviewer-diagram-1/);
    assert.match(second.innerHTML, /uviewer-diagram-2/);
    assert.equal(env.body.children[2], ordinary);
    assert.equal(first.style['--pele-bg'], 'rgb(18, 18, 18)');
    env.context.renderDiagrams();
    assert.equal(env.body.children[0], first);
    assert.equal(env.observed.size, 2);
});

test('resizes redraw responsively; appended/prepended chunks render and removed chunks release observers', () => {
    const env = setup();
    env.setWidth(900);
    env.add('flowchart LR\n A[One] --> B[Two] --> C[Three] --> D[Four]');
    env.callbacks.DOMContentLoaded();
    const first = env.body.children[0];
    const wide = first.innerHTML;
    env.setWidth(240);
    env.callbacks.resizeObserver([{ target: first }]);
    assert.notEqual(first.innerHTML, wide);
    const chunk = env.body.appendChild(new env.Element('div'));
    env.add('pie\n "A" : 30\n "B" : 70', 'mermaid', chunk);
    env.callbacks.mutation();
    assert.match(chunk.children[0].innerHTML, /<svg/);
    assert.equal(env.observed.size, 2);
    env.body.replaceChildren(chunk);
    env.context.renderDiagrams();
    assert.equal(env.observed.has(first), false);
    assert.equal(env.observed.size, 1);
});

test('invalid source stays readable and is displayed as text, alongside working diagrams', () => {
    const env = setup();
    const invalid = 'not a diagram <img src=x onerror=alert(1)>';
    env.add(invalid);
    env.add('flowchart TD\n A --> B');
    env.callbacks.DOMContentLoaded();
    const [failure, success] = env.body.children;
    assert.equal(failure.children[0].tagName, 'FIGCAPTION');
    assert.match(failure.children[0].textContent, /^Pele:/);
    assert.equal(failure.children[1].textContent, invalid);
    assert.match(success.innerHTML, /<svg/);
});

test('diagram text cannot install scripts, callbacks or navigation links', () => {
    const env = setup();
    env.add('flowchart TD\n A["<script>alert(1)</script>"] --> B[OK]\n click B "https://example.com"');
    env.callbacks.DOMContentLoaded();
    const svg = env.body.children[0].innerHTML;
    assert.match(svg, /<svg/);
    assert.doesNotMatch(svg, /<script\b|\bonclick\s*=|href="https:\/\/example.com"/i);
});
