// Read only: show this server's proxy route, never unrelated traffic or credentials.
const http=require('node:http'),fs=require('node:fs')
const config=fs.readFileSync('C:/Users/jiangyi/AppData/Roaming/io.github.clash-verge-rev.clash-verge-rev/clash-verge.yaml','utf8')
const secret=(config.match(/^secret:\s*(.*)$/m)?.[1]||'').trim().replace(/^['"]|['"]$/g,'')
const req=http.get({socketPath:'\\\\.\\pipe\\verge-mihomo',path:'/connections',headers:secret?{Authorization:'Bearer '+secret}:{}},res=>{let data='';res.on('data',x=>data+=x);res.on('end',()=>{try{const j=JSON.parse(data);console.log(JSON.stringify({status:res.statusCode,connections:(j.connections||[]).filter(c=>c.metadata.destinationIP==='124.222.244.144').map(c=>({network:c.metadata.network,type:c.metadata.type,port:c.metadata.destinationPort,chain:c.chains,rule:c.rule,rulePayload:c.rulePayload,uploaded:c.upload,downloaded:c.download,start:c.start}))},null,2))}catch{console.log('Non-JSON diagnostic response '+res.statusCode)}})})
req.setTimeout(5000,()=>req.destroy(new Error('Diagnostic timed out')));req.on('error',e=>{console.error(e.message);process.exitCode=1})
