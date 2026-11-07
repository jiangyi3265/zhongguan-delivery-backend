/* SEC02 real Chromium: old HTML downloads; decoded image remains viewable. */
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const {chromium} = require('./playwright-runtime.cjs');
const base = 'http://127.0.0.1:15190';
if (process.env.QICAI_ISOLATED_TEST !== '1' || process.env.QICAI_BASE_URL !== base || !process.env.QICAI_EVIDENCE_DIR) throw new Error('Disposable acceptance environment required');
const out = path.resolve(process.env.QICAI_EVIDENCE_DIR), target = path.join(out, 'upload-security-browser-results.json');
if (fs.existsSync(target)) throw new Error('Refuse to overwrite evidence');
const fixture = JSON.parse(fs.readFileSync(path.join(out, 'security-browser-fixture.json'), 'utf8'));
if (fixture.baseUrl !== base || !fixture.legacyHtmlPath.startsWith('/profile/security-legacy-') || !fixture.pngPath.startsWith('/profile/')) throw new Error('Invalid isolated fixture');
const report = {startedAt: new Date().toISOString(), kind:'real local Chromium; synthetic cookie only; no production or payment', checks:[], networkFailures:[], httpErrors:[], consoleErrors:[], expectedDownloadAborts:[]};
function check(name, value) { report.checks.push({name, passed:!!value}); if (!value) throw new Error(name); }
(async () => {
  let browser;
  try {
    browser = await chromium.launch({headless:true});
    const context = await browser.newContext({acceptDownloads:true, viewport:{width:900,height:600}, extraHTTPHeaders:{Referer:'http://localhost:25186/'}});
    await context.addCookies([{name:'Security-Synthetic', value:'synthetic-only', url:base}]);
    await context.route('**/*', route => {
      const url=route.request().url();
      if (url.startsWith(base+'/') || url.startsWith('data:')) return route.continue();
      report.networkFailures.push({url:'outside-isolated-origin', reason:'blocked'}); return route.abort();
    });
    const page = await context.newPage();
    let clickedDownload = false;
    page.on('requestfailed', request => {
      const failure = {url:request.url(), error:request.failure()?.errorText};
      if (clickedDownload && request.isNavigationRequest() && request.url()===base+fixture.legacyHtmlPath && failure.error==='net::ERR_ABORTED') report.expectedDownloadAborts.push(failure);
      else report.networkFailures.push(failure);
    });
    page.on('response', response => { if (response.status()>=400) report.httpErrors.push({url:response.url(),status:response.status()}); });
    page.on('pageerror', error => report.consoleErrors.push(error.message));
    page.on('console', message => { if(message.type()==='error') report.consoleErrors.push(message.text()); });
    // Begin on a same-origin application response. An uploaded image document is
    // itself sandboxed, and its CSP correctly survives setContent into that document.
    await page.goto(base+'/qicai/mobile/health', {waitUntil:'load'});
    await page.setContent(`<html><head><title>上传安全验收</title></head><body><h1>上传安全验收</h1><p>真实 PNG 应显示，旧 HTML 应下载。</p><img id="safe-image" src="${base+fixture.pngPath}" width="100" height="100"/><p><a id="legacy-link" href="${base+fixture.legacyHtmlPath}">下载旧 HTML</a></p></body></html>`, {waitUntil:'load'});
    check('浏览器实际解码PNG', await page.locator('#safe-image').evaluate(image => image.complete && image.naturalWidth===1 && image.naturalHeight===1));
    const originalUrl = page.url();
    clickedDownload = true;
    const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#legacy-link').click()]);
    const saved = path.join(out,'synthetic-legacy-download.html');
    await download.saveAs(saved);
    check('旧HTML以下载交付且字节保留', crypto.createHash('sha256').update(fs.readFileSync(saved)).digest('hex')===fixture.htmlSha256);
    check('下载没有替换当前页面或执行HTML', page.url()===originalUrl && await page.title()==='上传安全验收' && !(await context.cookies(base)).some(cookie=>cookie.name==='SecUnexpected'));
    await page.screenshot({path:path.join(out,'upload-security-browser.png'),fullPage:true});
    check('无其他网络或控制台失败', report.networkFailures.length===0 && report.httpErrors.length===0 && report.consoleErrors.length===0 && report.expectedDownloadAborts.length<=1);
    report.exitCode=0;
  } catch (error) { report.exitCode=1; report.error=error.message; }
  finally { if(browser) await browser.close(); report.endedAt=new Date().toISOString(); fs.writeFileSync(target,JSON.stringify(report,null,2)); }
  process.exitCode=report.exitCode;
})();
