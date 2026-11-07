// A pnpm lifecycle may omit user_agent. Exercise the same version/manager contract in both environments.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/package-manager-guard');fs.mkdirSync(out,{recursive:true});
const fixture=fs.mkdtempSync(path.join(out,'pnpm-fixture-')),bin=path.join(fixture,'bin');fs.mkdirSync(bin);const cli=path.join(bin,'pnpm.mjs');fs.writeFileSync(cli,'// synthetic package-manager fixture');
const packageFile=path.join(fixture,'package.json'),guard=path.join(root,'RuoYi-Vue3/scripts/check-package-manager.cjs');
const clean={...process.env};for(const key of Object.keys(clean))if(/^npm_config_user_agent$|^npm_execpath$/i.test(key))delete clean[key];
const checks=[];function check(name,expected,env){const result=spawnSync(process.execPath,[guard],{env:{...clean,...env},encoding:'utf8'});assert.equal(result.status,expected,name+': '+result.stderr);checks.push({name,passed:true})}
fs.writeFileSync(packageFile,JSON.stringify({name:'pnpm',version:'11.2.2'}));
check('declared pnpm agent is accepted',0,{npm_config_user_agent:'pnpm/11.2.2 node/v24.19.0'});
check('actual CLI metadata works when lifecycle omits agent',0,{npm_execpath:cli});
check('npm agent is rejected even with a pnpm-looking exec path',1,{npm_config_user_agent:'npm/11.0.0',npm_execpath:cli});
check('yarn agent is rejected',1,{npm_config_user_agent:'yarn/1.22.0'});
check('other pnpm version is rejected',1,{npm_config_user_agent:'pnpm/11.3.0'});
fs.writeFileSync(packageFile,JSON.stringify({name:'pnpm',version:'11.3.0'}));
check('metadata for a different pnpm version is rejected',1,{npm_execpath:cli});
check('no package manager information is rejected',1,{});
fs.writeFileSync(path.join(out,'package-manager-guard-results.json'),JSON.stringify({scope:'Synthetic lifecycle environment/version contract',checks,passed:true},null,2));console.log('PASS package manager guard: '+checks.length+' checks');
