// Destructive synthetic fixtures must only use the harness-owned loopback service.
const path = require('node:path')
const fs = require('node:fs')
function context() {
  if (process.env.QICAI_ISOLATED_TEST !== '1' || process.env.QICAI_BASE_URL !== 'http://127.0.0.1:15190') {
    throw new Error('Run via python scripts/acceptance.py: isolated acceptance environment is required')
  }
  if (!process.env.QICAI_EVIDENCE_DIR || !process.env.QICAI_ADMIN_PASSWORD) throw new Error('Missing acceptance evidence directory or fixture credentials')
  const out = path.resolve(process.env.QICAI_EVIDENCE_DIR)
  fs.mkdirSync(out, {recursive:true})
  return {out, base:process.env.QICAI_BASE_URL, password:process.env.QICAI_ADMIN_PASSWORD}
}
module.exports = context
