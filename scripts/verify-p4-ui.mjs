// Asserts that the P4 rollback drawer and the global records page actually rendered (text +
// selectors + console error count; exit non-zero on any miss).
//
//   node scripts/verify-p4-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p4-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots also captures the four design screenshots (release-rollback-{light,dark},
// records-{light,dark}); the verification itself runs the same way with or without it.
//
// The repo has no root package.json for tooling, so this script needs `puppeteer-core` resolvable
// from where it is run (ESM ignores NODE_PATH). The usual recipe is to copy it into a scratch dir
// that already has the dependency, e.g.:
//   copy scripts/verify-p4-ui.mjs %TEMP%\rp-shot-tools\
//   cd %TEMP%\rp-shot-tools && node verify-p4-ui.mjs http://127.0.0.1:5173 --shots <abs shots dir>
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null
const RELEASE = '/releases/PO-20261121-5976BF'

const targets = [
  {
    name: 'release-rollback',
    shot: 'release-rollback',
    path: RELEASE,
    clickText: '进入生产回滚',
    waitFor: '.rollback',
    checks: ['.rollback', '.version-current', '.history-list', '.history-row', '.warn-box'],
    text: ['当前线上版本', '历史版本', '仅回滚镜像', '必读提示', '确认回滚'],
    minCount: { '.history-row': 1 }
  },
  {
    name: 'records',
    shot: 'records',
    path: '/records',
    waitFor: '.filter-bar',
    checks: ['.filter-bar', '.el-table', '.el-table__row', '.pager'],
    text: ['发布记录', '发布单', '变更项状态', '最新流水线', '计划生产时间'],
    minCount: { '.el-table__row': 1 }
  }
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

/** The rollback button is disabled until GET /me lands; wait for the enabled state before click. */
async function waitEnabled(page, label) {
  await page.waitForFunction(
    (text) => {
      const btn = Array.from(document.querySelectorAll('button')).find(
        (n) => (n.textContent ?? '').trim() === text
      )
      return !!btn && !btn.disabled
    },
    { timeout: 15000 },
    label
  )
}

const appendTheme = (path, theme) =>
  `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

if (SHOTS) await mkdir(SHOTS, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: 'new',
  args: ['--disable-gpu', '--hide-scrollbars', '--no-first-run', '--window-size=1440,900']
})

let failures = 0
try {
  for (const theme of ['light', 'dark']) {
    for (const t of targets) {
      const page = await browser.newPage()
      await page.setViewport({ width: 1440, height: 900 })
      const errors = []
      page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
      page.on('pageerror', (e) => errors.push(e.message))

      await page.goto(BASE + appendTheme(t.path, theme), {
        waitUntil: 'networkidle2',
        timeout: 30000
      })
      await new Promise((r) => setTimeout(r, 900))

      let clicked = true
      if (t.clickText) {
        try {
          await waitEnabled(page, t.clickText)
          clicked = await clickByText(page, t.clickText)
        } catch {
          clicked = false
        }
        await new Promise((r) => setTimeout(r, 900))
      }

      const waits = t.waitFor ? [t.waitFor] : []
      for (const sel of waits) {
        try {
          await page.waitForSelector(sel, { visible: true, timeout: 10000 })
        } catch {
          // The selector check below reports the miss; the timeout only bounds the wait.
        }
      }

      const found = {}
      const counts = {}
      for (const sel of t.checks) {
        counts[sel] = await page.$$eval(sel, (els) => els.length)
        found[sel] = counts[sel] > 0
      }
      const body = await page.evaluate(() => document.body.innerText)
      const missingText = t.text.filter((x) => !body.includes(x))
      const badSelectors = Object.entries(found)
        .filter(([, ok]) => !ok)
        .map(([sel]) => sel)
      const badCounts = Object.entries(t.minCount ?? {})
        .filter(([sel, min]) => (counts[sel] ?? 0) < min)
        .map(([sel, min]) => `${sel}>=${min} (got ${counts[sel] ?? 0})`)
      const bad = [...badSelectors, ...badCounts]
      if (!clicked) bad.push(`click:${t.clickText}`)
      if (bad.length || missingText.length || errors.length) failures++

      if (SHOTS) {
        await page.screenshot({ path: join(SHOTS, `${t.shot}-${theme}.png`) })
      }

      console.log(
        `${t.name}-${theme}: selectors=${bad.length ? 'MISSING ' + bad.join(',') : 'ok'} ` +
          `text=${missingText.length ? 'MISSING ' + missingText.join(',') : 'ok'} ` +
          `rows=${counts['.el-table__row'] ?? counts['.history-row'] ?? 0} errors=${errors.length}`
      )
      errors.slice(0, 3).forEach((e) => console.log('   ' + e))
      await page.close()
    }
  }
} finally {
  await browser.close()
}
process.exit(failures ? 1 : 0)