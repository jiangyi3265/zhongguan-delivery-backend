// Controlled client transport/timers; actual UI/backend evidence is extensions-browser.cjs.
import fs from 'node:fs/promises'
import vm from 'node:vm'
import path from 'node:path'
import assert from 'node:assert/strict'
const root=path.resolve(import.meta.dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/notice-client')
const source=(await fs.readFile(path.join(root,'三端配送小程序/common/notices.js'),'utf8')).replace(/^import .*$/gm,'').replace(/\bexport\s+/g,'')
const results=[],tick=()=>new Promise(resolve=>setImmediate(resolve))
function fixture(token='A') {
  let activeToken=token,revision=0,route='pages/buyer/home',roleOptions={},timer,cleared=0
  const requests=[],toasts=[],states=[],events={}
  const context=vm.createContext({getToken:()=>activeToken,getSessionRevision:()=>revision,getCurrentPages:()=>[{route,options:roleOptions}],
    uni:{$on:(key,fn)=>events[key]=fn,showToast:({title})=>toasts.push(title)},
    api:{noticeSummary:role=>new Promise((resolve,reject)=>requests.push({role,resolve,reject}))},
    setInterval:fn=>{timer=fn;return 1},clearInterval:()=>{timer=null;cleared++}})
  vm.runInContext(source+'\nglobalThis.actual={startNotices,stopNotices,refreshNotices,resetNotices,watchNotices}',context)
  context.actual.watchNotices(value=>states.push(value))
  return {...context.actual,requests,toasts,states,change(value,emit=true,changedRole='customer'){activeToken=value;revision++;if(emit)events['qicai-session-change']?.(changedRole)},setPage(value,options={}){route=value;roleOptions=options},timer:()=>timer?.(),get cleared(){return cleared}}
}
async function test(name,action){try{await action();results.push({name,passed:true});console.log('PASS '+name)}catch(e){results.push({name,passed:false,error:e.stack});console.error('FAIL '+name+': '+e.message)}}
await test('匿名不发送保护接口请求，后台停止轮询',async()=>{const f=fixture('');f.startNotices();assert.equal(f.requests.length,0);f.stopNotices();assert.equal(f.cleared,1);f.timer();assert.equal(f.requests.length,0)})
await test('同一消息只提醒一次，新消息自动提醒',async()=>{const f=fixture();f.startNotices();f.requests[0].resolve({unread:1,latestId:10});await tick();assert.equal(f.toasts.length,1);f.timer();f.requests[1].resolve({unread:1,latestId:10});await tick();assert.equal(f.toasts.length,1);f.timer();f.requests[2].resolve({unread:2,latestId:11});await tick();assert.equal(f.toasts.length,2);f.stopNotices()})
await test('切换账号立即清空计数且晚到响应不能串账号',async()=>{const f=fixture();f.startNotices();f.change('B');assert.equal(f.states.at(-1).unread,0);f.requests[0].resolve({unread:99,latestId:100});await tick();assert.equal(f.states.at(-1).unread,0);f.requests[1].resolve({unread:1,latestId:101});await tick();assert.equal(f.states.at(-1).unread,1);assert.equal(f.toasts.length,1);f.stopNotices()})
await test('跨窗口Token变化且请求失败也不能保留旧账号计数',async()=>{const f=fixture();f.startNotices();f.requests[0].resolve({unread:9,latestId:30});await tick();f.change('B',false);const pending=f.refreshNotices();assert.equal(f.states.at(-1).unread,0);f.requests[1].reject(new Error('synthetic network failure'));await pending;assert.equal(f.states.at(-1).unread,0);f.stopNotices()})
await test('401停止同一失效Token的重复请求且不强制跳转',async()=>{const f=fixture();f.startNotices();f.requests[0].reject(Object.assign(new Error('expired'),{status:401}));await tick();f.timer();assert.equal(f.requests.length,1);assert.equal(f.states.at(-1).unread,0);f.change('B');assert.equal(f.requests.length,2);f.requests[1].resolve({unread:0,latestId:0});await tick();f.stopNotices()})
await test('进入后台后晚到响应不弹提醒，回前台后仍提醒未读',async()=>{const f=fixture();f.startNotices();f.stopNotices();f.requests[0].resolve({unread:1,latestId:40});await tick();assert.equal(f.toasts.length,0);f.startNotices();f.requests[1].resolve({unread:1,latestId:40});await tick();assert.equal(f.toasts.length,1);f.stopNotices()})
await test('配送员消息使用配送身份，其他角色会话变更不重复提醒',async()=>{const f=fixture();f.setPage('pages/courier/home');f.startNotices();assert.equal(f.requests[0].role,'courier');f.requests[0].resolve({unread:1,latestId:50});await tick();f.change('A',true,'customer');assert.equal(f.requests.length,1);f.timer();f.requests[1].resolve({unread:1,latestId:50});await tick();assert.equal(f.toasts.length,1);f.stopNotices()})
await test('消息中心以新消息提示条提醒，避免全局重复弹窗',async()=>{const f=fixture();f.setPage('pages/buyer/messages',{role:'courier'});f.startNotices();assert.equal(f.requests[0].role,'courier');f.requests[0].resolve({unread:1,latestId:60});await tick();assert.equal(f.toasts.length,0);assert.equal(f.states.at(-1).unread,1);f.stopNotices()})
await test('较新的已读消息不使旧未读消息反复触发新消息提示',async()=>{const f=fixture();f.startNotices();f.requests[0].resolve({unread:1,latestId:80,latestUnreadId:70});await tick();assert.equal(f.states.at(-1).latestId,70);assert.equal(f.toasts.length,1);f.timer();f.requests[1].resolve({unread:1,latestId:81,latestUnreadId:70});await tick();assert.equal(f.states.at(-1).latestId,70);assert.equal(f.toasts.length,1);f.timer();f.requests[2].resolve({unread:2,latestId:82,latestUnreadId:82});await tick();assert.equal(f.toasts.length,2);f.stopNotices()})
await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'notice-client-results.json'),JSON.stringify({kind:'controlled actual client module, not provider/browser',results,passed:results.every(r=>r.passed)},null,2));if(results.some(r=>!r.passed))process.exitCode=1
