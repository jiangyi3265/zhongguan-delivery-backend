// Project-local Playwright first; optional explicit path; then Windows npm global.
const path = require('node:path')
function load(suffix = '') {
  try { return require('playwright' + suffix) } catch (error) {
    if (error.code !== 'MODULE_NOT_FOUND') throw error
    const dir = process.env.QICAI_PLAYWRIGHT_DIR || path.join(process.env.APPDATA || '', 'npm', 'node_modules', 'playwright')
    return require(path.join(dir, suffix.replace(/^\//, '')))
  }
}
module.exports = { ...load(), test: load('/test') }
