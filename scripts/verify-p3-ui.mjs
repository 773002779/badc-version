// Asserts that the P3 panels actually rendered (no image review needed, and no console errors).
//   node <this> http://127.0.0.1:5173
import puppeteer from 'puppeteer-core'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const RELEASE = '/releases/PO-20261121-5976BF?theme=light'

const targets = [
  { name: 'release', path: RELEASE, checks: ['.admission', '.admission-title'], text: ['生产准入'] },
  { name: 'control', path: '/control?theme=light', checks: ['.freeze-list, .empty-state, .panel'], text: ['生产管控'] },
  { name: 'control-window', path: '/control?theme=light', clickText: '发布窗口', checks: ['.rule-head'], text: ['窗口规则', '窗口试算'] }
]

async function clickByText(page, label) {
  return page.evaluate((text) => {
    const nodes = Array.from(document.querySelectorAll('button, a, .el-button, .el-tabs__item'))
    const hit = nodes.find((n) => (n.textContent ?? '').trim() === text)
    if (!hit) return false
    hit.click()
    return true
  }, label)
}

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: 'new',
  args: ['--disable-gpu', '--hide-scrollbars', '--no-first-run', '--window-size=1440,900']
})

let failures = 0
try {
  for (const t of targets) {
    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 900 })
    const errors = []
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
    page.on('pageerror', (e) => errors.push(e.message))
    await page.goto(BASE + t.path, { waitUntil: 'networkidle2', timeout: 30000 })
    await new Promise((r) => setTimeout(r, 900))
    if (t.clickText) {
      await clickByText(page, t.clickText)
      await new Promise((r) => setTimeout(r, 600))
    }
    const found = {}
    for (const sel of t.checks) {
      found[sel] = await page.evaluate((s) => !!document.querySelector(s), sel)
    }
    const body = await page.evaluate(() => document.body.innerText)
    const missingText = t.text.filter((x) => !body.includes(x))
    const bad = Object.entries(found).filter(([, ok]) => !ok).map(([s]) => s)
    if (bad.length || missingText.length || errors.length) failures++
    console.log(
      `${t.name}: selectors=${bad.length ? 'MISSING ' + bad.join(',') : 'ok'} ` +
        `text=${missingText.length ? 'MISSING ' + missingText.join(',') : 'ok'} errors=${errors.length}`
    )
    errors.slice(0, 3).forEach((e) => console.log('   ' + e))
    await page.close()
  }
} finally {
  await browser.close()
}
process.exit(failures ? 1 : 0)