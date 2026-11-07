// Validate actual resolved runtime families, including uni-h5's nested i18n copy.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{createRequire}=require('node:module');
const root=path.resolve(__dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/client-dependency-check');fs.mkdirSync(out,{recursive:true});
const mini=createRequire(path.join(root,'三端配送小程序/package.json')),admin=createRequire(path.join(root,'RuoYi-Vue3/package.json'));
const version=(req,name)=>req(name+'/package.json').version;
const h5=createRequire(mini.resolve('@dcloudio/uni-h5/package.json'));
const records={miniVue:version(mini,'vue'),miniCompiler:version(mini,'@vue/compiler-sfc'),miniVite:version(mini,'vite'),miniI18n:version(mini,'vue-i18n'),h5I18n:version(h5,'vue-i18n'),adminVue:version(admin,'vue'),axios:version(admin,'axios'),cookie:version(admin,'js-cookie'),echarts:version(admin,'echarts'),adminVite:version(admin,'vite')};
assert.equal(records.miniVue,'3.4.21');assert.equal(records.miniCompiler,records.miniVue);assert.equal(records.miniVite,'5.2.8');assert.equal(records.miniI18n,'9.14.5');assert.equal(records.h5I18n,records.miniI18n);
assert.equal(records.adminVue,'3.5.44');assert.equal(records.axios,'1.20.0');assert.equal(records.cookie,'3.0.8');assert.equal(records.echarts,'6.1.0');assert.equal(records.adminVite,'6.4.3');
fs.writeFileSync(path.join(out,'client-dependency-results.json'),JSON.stringify({kind:'Actual installed package contract, not a full vulnerability scan',records,passed:true},null,2));console.log(JSON.stringify({passed:true,records}));
