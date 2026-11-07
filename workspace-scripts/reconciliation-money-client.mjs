import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import assert from 'node:assert/strict'
const source=(await fs.readFile('RuoYi-Vue3/src/utils/qicai-money.js','utf8')).replace(/\bexport\s+/g,'')
const format=vm.runInNewContext(source+'\nformatReconciliationMoney'),results=[]
for(const expected of ['9999999999999999.99','4999999999999999.99','29.90','-7.80','0.00']) {
  const actual=format(expected);assert.equal(actual,expected);results.push({expected,actual,passed:true})
}
assert.equal(format('NaN'),'—');assert.equal(format('1e3'),'—');assert.equal(format('12.345'),'—')
const out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/reconciliation-money-client');await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'reconciliation-money-client-results.json'),JSON.stringify({scope:'Actual frontend money renderer, full supported decimal range',results,passed:true},null,2));console.log('PASS exact reconciliation money rendering '+results.length+' cases')
