// Execute the actual generated phone page script with a small deterministic DOM/XHR harness.
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = process.argv[2] || 'app/build/test-ui/phone-ai-page.html';
const html = fs.readFileSync(path, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
class Element {
  constructor(id) { this.id = id; this.options = []; this.selectedIndex = 0; this._value = ''; this.style = {}; }
  add(option) { this.options.push(option); }
  get value() { return ['provider', 'models'].includes(this.id) ? (this.options[this.selectedIndex] || {}).value || '' : this._value; }
  set value(value) { if (['provider', 'models'].includes(this.id)) this.selectedIndex = this.options.findIndex(o => o.value === value); else this._value = value; }
}
const elements = {};
for (const id of ['config', 'provider', 'key', 'base', 'models', 'fetch', 'save', 'status',
  'manualMode', 'manual', 'manualBox', 'selected']) elements[id] = new Element(id);
elements.config.action = 'http://192.168.1.2:9978/test-session-token';
const requests = [];
class XHR {
  constructor() { this.headers = {}; requests.push(this); }
  open(method, url) { this.method = method; this.url = url; }
  setRequestHeader(key, value) { this.headers[key] = value; }
  send(body) { this.body = body; }
  abort() { this.aborted = true; }
  reply(status, data) { this.status = status; this.responseText = JSON.stringify(data); this.readyState = 4; this.onreadystatechange(); }
}
const context = { document: { getElementById: id => elements[id] }, XMLHttpRequest: XHR,
  Option: function(text, value) { this.text = text; this.value = value; }, window: {}, encodeURIComponent };
vm.createContext(context); vm.runInContext(script, context);
assert.deepStrictEqual(elements.provider.options.map(o => o.text), ['DeepSeek', 'Qwen', 'GLM', 'KIMI', 'MIMO']);
assert.strictEqual(elements.save.disabled, true);
elements.key.value = 'test-phone-key'; elements.key.oninput(); elements.fetch.onclick();
const old = requests[0];
assert.strictEqual(old.method, 'POST'); assert(old.url.endsWith('/test-session-token/models'));
assert(!old.url.includes('test-phone-key')); assert(old.body.includes('apiKey=test-phone-key'));
elements.fetch.onclick(); assert.strictEqual(requests.length, 1);
elements.provider.value = 'mimo'; elements.provider.onchange();
assert(old.aborted); assert.strictEqual(elements.key.value, '');
old.reply(200, {source: 'remote', models: [{id: 'stale', name: 'Stale'}], message: 'old'});
assert(!elements.models.options.some(o => o.value === 'stale'));
elements.key.value = 'test-mimo-key'; elements.key.oninput(); elements.fetch.onclick();
requests[1].reply(200, {source: 'remote', models: [{id: 'mimo-model', name: '<img onerror=bad>'}], message: 'remote'});
assert.strictEqual(elements.save.disabled, true); // A user must select; never auto-select first model.
elements.models.value = 'mimo-model'; elements.models.onchange();
assert.strictEqual(elements.save.disabled, false);
elements.config.onsubmit({preventDefault() { throw Error('should save'); }});
assert.strictEqual(elements.selected.value, 'mimo-model');
elements.key.value = 'changed-key'; elements.key.oninput();
assert.strictEqual(elements.save.disabled, true); assert.strictEqual(elements.selected.value, '');
elements.fetch.onclick();
requests[2].reply(200, {source: 'preset', updatedAt: '2026-10-08', models: [{id: 'candidate', name: 'candidate'}], message: 'not verified'});
assert(elements.status.textContent.includes('2026-10-08'));
elements.manualMode.checked = true; elements.manualMode.onchange();
elements.manual.value = 'custom-text-model'; elements.manual.oninput();
assert.strictEqual(elements.save.disabled, false);
elements.config.onsubmit({preventDefault() { throw Error('should save manual'); }});
assert.strictEqual(elements.selected.value, 'custom-text-model');
elements.key.value = 'test-invalid-key'; elements.key.oninput(); elements.fetch.onclick();
requests[3].reply(200, {source: 'error', models: [], message: 'key invalid'});
assert.strictEqual(elements.save.disabled, true); assert.strictEqual(elements.models.options.length, 1);
context.window.onpagehide(); assert.strictEqual(elements.key.value, '');
assert(!html.includes('localStorage')); assert(!html.includes('sessionStorage'));
console.log('Phone AI page behavior: PASS (provider/key reset, stale response, explicit selection, fallback, manual, errors, privacy)');
