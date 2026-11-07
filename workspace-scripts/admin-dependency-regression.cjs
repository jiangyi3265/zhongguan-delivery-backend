// Verify the installed editor matches the single committed dependency contract.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const {createRequire} = require('node:module');
const root = path.resolve(__dirname, '..'), admin = path.join(root, 'RuoYi-Vue3');
const req = createRequire(path.join(admin, 'package.json'));
const manifest = req('./package.json');
const editor = req('@vueup/vue-quill/package.json');
const editorRequire = createRequire(req.resolve('@vueup/vue-quill'));
const quillEntry = editorRequire.resolve('quill');
let folder = path.dirname(quillEntry), quill;
while (folder !== path.dirname(folder)) {
  const file = path.join(folder, 'package.json');
  if (fs.existsSync(file)) {
    const value = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (value.name === 'quill') { quill = value; break; }
  }
  folder = path.dirname(folder);
}
const output = path.resolve(process.env.QICAI_EVIDENCE_DIR || 'tmp/admin-dependency-results');
fs.mkdirSync(output, {recursive:true});
const destination=path.join(output,'admin-dependency-results.json');
if(fs.existsSync(destination)) throw new Error('Refuse to overwrite evidence');
const report={startedAt:new Date().toISOString(),kind:'Actual installed package/lock integrity; not browser compatibility',checks:[]};
function check(name,run) { try {run();report.checks.push({name,passed:true})}catch(e){report.checks.push({name,passed:false,error:e.message})} }
check('Single package manager and lock contract',()=>{
  assert.equal(manifest.packageManager,'pnpm@11.2.2');
  assert(!fs.existsSync(path.join(admin,'package-lock.json')));
  assert(!fs.existsSync(path.join(admin,'yarn.lock')));
  const lock=fs.readFileSync(path.join(admin,'pnpm-lock.yaml'),'utf8');
  assert(lock.includes('quill@2.0.2:'));
  assert(!lock.includes('quill@1.3.7:'));
});
check('Wrapper and its actual Quill dependency match supported pinned versions',()=>{
  assert.equal(manifest.dependencies['@vueup/vue-quill'],'1.5.5');
  assert.equal(editor.version,'1.5.5');
  assert.equal(quill.version,'2.0.2');
  assert(editor.dependencies.quill.split(' || ').includes('2.0.2'));
});
const {spawnSync}=require('node:child_process');
for(const userAgent of ['npm/11.0.0 node/v24','yarn/1.22.0','pnpm/10.0.0','pnpm/11.2.2 npm/? node/v24']) {
  check('Install guard '+userAgent.split(' ')[0],()=>{
    const result=spawnSync(process.execPath,[path.join(admin,'scripts/check-package-manager.cjs')],{env:{...process.env,npm_config_user_agent:userAgent},windowsHide:true});
    assert.equal(result.status,userAgent.startsWith('pnpm/11.2.2 ')?0:1);
  });
}
report.versions={wrapper:editor.version,quill:quill?.version};
report.exitCode=report.checks.every(x=>x.passed)?0:1;
report.endedAt=new Date().toISOString();
fs.writeFileSync(destination,JSON.stringify(report,null,2));
console.log(JSON.stringify(report));process.exitCode=report.exitCode;
