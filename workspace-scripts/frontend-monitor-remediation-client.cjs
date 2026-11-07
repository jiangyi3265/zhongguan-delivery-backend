// Framework functions from actual SFC, synthetic rejected/successful requests.
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert/strict');
const root = path.resolve(__dirname, '..'), out = path.resolve(process.env.QICAI_EVIDENCE_DIR || 'tmp/remediation-20261017/frontend');
const dep = require('module').createRequire(path.join(fs.realpathSync(path.join(root, '三端配送小程序/node_modules/@vue/compiler-sfc')), 'package.json'));
const parser = dep('@babel/parser');
const report = { startedAt: new Date().toISOString(), kind: 'Actual framework SFC functions in VM, synthetic responses. Menus remain closed; not browser evidence.', checks: [] };
const unhandled = []; process.on('unhandledRejection', error => unhandled.push(error.message));
function functions(file, names, bindings) { const src = fs.readFileSync(path.join(root, file), 'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]; const ast = parser.parse(src, { sourceType: 'module' }); const picked = ast.program.body.filter(n => n.type === 'FunctionDeclaration' && names.includes(n.id.name)).map(n => src.slice(n.start, n.end)).join('\n'); const ctx = { ...bindings }; vm.runInNewContext(picked, ctx, { filename: file }); return ctx; }
const tick = () => new Promise(r => setImmediate(r));
async function test(name, fn) { try { await fn(); report.checks.push({ name, status: 'passed' }) } catch (error) { report.checks.push({ name, status: 'failed', error: error.stack }); process.exitCode = 1 } }
(async () => {
  for (const [file, method] of [['server/index.vue', 'getServer'], ['cache/index.vue', 'getCache']]) await test(file + ' releases mask on rejection and preserves error signal', async () => {
    const calls = []; const c = functions('RuoYi-Vue3/src/views/monitor/' + file, ['getList'], { proxy: { $modal: { loading: () => calls.push('load'), closeLoading: () => calls.push('close'), msgError: () => calls.push('error') } }, [method]: () => Promise.reject(new Error('offline')) });
    let rejected = false; try { await c.getList() } catch { rejected = true } await tick(); assert(calls.includes('close')); assert(rejected || calls.includes('error'));
  });
  for (const [label, names, api, loadingKey] of [['names', ['getCacheNames', 'refreshCacheNames'], 'listCacheName', 'loading'], ['keys', ['getCacheKeys', 'refreshCacheKeys'], 'listCacheKey', 'subLoading']]) {
    await test('cache ' + label + ' rejected refresh releases loading and never reports success', async () => { const messages = []; const loading = { value: false }; const bindings = { proxy: { $modal: { msgSuccess: x => messages.push(x), msgError() {} } }, loading: { value: false }, subLoading: { value: false }, cacheNames: { value: [] }, cacheKeys: { value: [] }, nowCacheName: { value: 'synthetic' }, [api]: () => Promise.reject(new Error('offline')) }; bindings[loadingKey] = loading; const c = functions('RuoYi-Vue3/src/views/monitor/cache/list.vue', names, bindings); try { await c[names[1]]() } catch {} await tick(); assert.equal(loading.value, false); assert.equal(messages.length, 0) });
    await test('cache ' + label + ' only reports success after fulfilled refresh', async () => { let resolve; const pending = new Promise(r => resolve = r), messages = []; const c = functions('RuoYi-Vue3/src/views/monitor/cache/list.vue', names, { proxy: { $modal: { msgSuccess: x => messages.push(x), msgError() {} } }, loading: { value: false }, subLoading: { value: false }, cacheNames: { value: [] }, cacheKeys: { value: [] }, nowCacheName: { value: 'synthetic' }, [api]: () => pending }); const result = c[names[1]](); assert.equal(messages.length, 0); resolve({ data: [] }); await result; await tick(); assert.equal(messages.length, 1) });
  }
  for (const [name, api, arg] of [['handleClearCacheName','clearCacheName',{cacheName:'synthetic:'}],['handleClearCacheKey','clearCacheKey','synthetic:key'],['handleCacheValue','getCacheValue','synthetic:key'],['handleClearCacheAll','clearCacheAll',undefined]]) {
    await test(name+' handles rejection without success, data replacement, follow-up or unhandled error',async()=>{
      const messages=[],initial={cacheValue:'last-known'},cacheForm={value:initial},start=unhandled.length;let follows=0;
      const c=functions('RuoYi-Vue3/src/views/monitor/cache/list.vue',[name],{proxy:{$modal:{msgSuccess:x=>messages.push('success'),msgError:x=>messages.push('error')}},cacheForm,nowCacheName:{value:'synthetic:'},getCacheKeys:()=>{follows++;return Promise.resolve(true)},[api]:()=>Promise.reject(new Error('synthetic network failure'))});
      const result=c[name](arg);await result;await tick();assert(result&&typeof result.then==='function','action must return its request chain');assert.deepEqual(messages,['error']);assert.equal(follows,0);assert.equal(cacheForm.value,initial);assert.equal(unhandled.length,start);
    });
    await test(name+' successful action completes only after the API result',async()=>{
      const messages=[],cacheForm={value:{}},gate={};gate.promise=new Promise(resolve=>gate.resolve=resolve);let follows=0;
      const c=functions('RuoYi-Vue3/src/views/monitor/cache/list.vue',[name],{proxy:{$modal:{msgSuccess:x=>messages.push('success'),msgError:x=>messages.push('error')}},cacheForm,nowCacheName:{value:'synthetic:'},getCacheKeys:()=>{follows++;return Promise.resolve(true)},[api]:()=>gate.promise});
      const result=c[name](arg);assert.equal(messages.length,0);assert.deepEqual(cacheForm.value,{});gate.resolve({data:{cacheValue:'actual result'}});await result;await tick();assert(result&&typeof result.then==='function');
      if(name==='handleCacheValue'){assert.equal(cacheForm.value.cacheValue,'actual result');assert.equal(messages.length,0)}else{assert.deepEqual(messages,['success']);assert.equal(follows,name==='handleClearCacheAll'?0:1)}
    });
  }
})().catch(e => { report.error = e.stack; process.exitCode = 1 }).finally(() => { report.endedAt = new Date().toISOString(); report.exitCode = process.exitCode || 0; report.passed = report.checks.filter(x => x.status === 'passed').length; report.failed = report.checks.length - report.passed; report.unhandledRejections = unhandled; fs.mkdirSync(out, { recursive: true }); fs.writeFileSync(path.join(out, 'frontend-monitor-remediation-results.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify({ passed: report.passed, failed: report.failed, exitCode: report.exitCode })); });
