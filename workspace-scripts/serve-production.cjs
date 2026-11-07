// Serve the actual build outputs, with an internal QA API proxy; no data mocks.
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const mime = {'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.json':'application/json','.png':'image/png','.jpg':'image/jpeg','.svg':'image/svg+xml','.woff2':'font/woff2','.ico':'image/x-icon'}
function serve(port, folder, mobile = false) {
  const root=path.resolve(folder)
  http.createServer((req,res)=>{
    if(req.url.startsWith('/prod-api/') || (mobile && req.url.startsWith('/qicai/')) || req.url.startsWith('/profile/')) {
      const request=http.request({hostname:'127.0.0.1',port:15190,path:req.url.replace(/^\/prod-api/,''),method:req.method,headers:req.headers},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)})
      request.on('error',()=>{res.writeHead(502);res.end('QA backend unavailable')})
      req.pipe(request);return
    }
    let relative
    try { relative=decodeURIComponent(new URL(req.url,'http://localhost').pathname) } catch { res.writeHead(400);res.end();return }
    let file=path.resolve(root,'.'+relative)
    if(file!==root&&!file.startsWith(root+path.sep)){res.writeHead(403);res.end();return}
    if(!fs.existsSync(file)||!fs.statSync(file).isFile())file=path.join(root,'index.html')
    res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream')
    res.setHeader('Cache-Control','no-store')
    fs.createReadStream(file).pipe(res)
  }).listen(port,'127.0.0.1',()=>console.log('Production build preview http://127.0.0.1:'+port))
}
serve(Number(new URL(process.env.QICAI_ADMIN_URL || 'http://127.0.0.1:25186').port),'RuoYi-Vue3/dist')
serve(Number(new URL(process.env.QICAI_H5_URL || 'http://127.0.0.1:25187').port),'三端配送小程序/dist/build/h5',true)
