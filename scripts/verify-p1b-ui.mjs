// Asserts the PART-01 page-structure delivery in a real browser, in both themes, on the demo
// state of scripts/demo-redeploy.sql (re-seeded before the run and in a `finally` block):
//
//   * the unit list renders the new columns: 最近发布 (the demo order's digest + time; a unit
//     without production history shows —) and 占用锁 (— while free, 占用中 after a lock row is
//     inserted);
//   * the system detail page renders the 发布记录 block (the demo order row with its CRID) and
//     the 管控配置 entry;
//   * the 管控配置 entry navigates to /control?subsystemId=2 and the control page preselects that
//     subsystem;
//   * 0 console errors.
//
//   node scripts/verify-p1b-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p1b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes unit-summary-{light,dark}.png and subsystem-records-{light,dark}.png.
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const SUBSYSTEM = 2 // the fixture the demo seeds use (SCC-P6G-RD lives there too)
const ORDER = 'PO-DEMO-REDEPLOY' // seeded by scripts/demo-redeploy.sql
const CRID = 'CR-DEMO-REDEPLOY'
const ADMIN = 'dev'
const SQL = join(process.cwd(), 'scripts', 'demo-redeploy.sql')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

// ---------------------------------------------------------------- seed / mysql helpers

function mysql(args) {
  return spawnSync('mysql', args, {
    encoding: 'utf8',
    timeout: 30000,
    env: { ...process.env, MYSQL_PWD: 'root' }
  })
}

function reseed() {
  const res = mysql([
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    '--default-character-set=utf8mb4',
    `--execute=source ${SQL.replace(/\\/g, '/')}`
  ])
  if (res.error || res.status !== 0) {
    throw new Error(`cannot re-seed: ${res.error?.message ?? (res.stderr || `exit ${res.status}`)}`)
  }
}

/** The unit list's 占用锁 column needs a lock row to prove the occupied state; freed by re-seed. */
function insertLock() {
  const res = mysql([
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    '--execute=INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)'
      + " VALUES (2, 92101, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 4 HOUR);"
  ])
  if (res.error || res.status !== 0) {
    throw new Error(`cannot insert the lock row: ${res.stderr || res.error?.message}`)
  }
}

// ---------------------------------------------------------------------- page helpers

async function setUser(page, userId) {
  if (!page.url().startsWith(BASE)) {
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 })
  }
  await page.evaluate((id) => localStorage.setItem('rp.userId', id), userId)
}

async function open(page, path, theme) {
  await page.goto(appendTheme(`${BASE}${path}`, theme), {
    waitUntil: 'networkidle2',
    timeout: 30000
  })
}

// ---------------------------------------------------------------------------- main run

try {
  reseed()
  console.log('seeded: PO-DEMO-REDEPLOY (unit 2 with a successful production batch)')
} catch (e) {
  console.error(`cannot seed the demo state: ${e.message}`)
  process.exit(1)
}

if (SHOTS) await mkdir(SHOTS, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: 'new',
  args: ['--disable-gpu', '--hide-scrollbars', '--no-first-run', '--window-size=1440,900']
})

let failures = 0
const lines = []
try {
  for (const theme of ['light', 'dark']) {
    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 900 })
    const errors = []
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
    page.on('pageerror', (e) => errors.push(e.message))
    const summaryGets = []
    page.on('request', (req) => {
      if (/\/units\/\d+\/release-summary$/.test(req.url())) summaryGets.push(req.url())
    })
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    await reseed()
    await setUser(page, ADMIN)

    // -------------------------------------------------- unit list: the summary columns
    await open(page, '/units', theme)
    await page.waitForSelector('.el-table__row', { timeout: 20000 })
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('.el-table__row')).some((row) =>
        (row.textContent ?? '').includes('sha256:4a5') ||
          (row.textContent ?? '').includes('PO-DEMO-REDEPLOY')
      ),
      { timeout: 20000, polling: 300 }
    )
    await sleep(400)
    const cells = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      return Array.from(document.querySelectorAll('.el-table__row')).map((row) => ({
        text: flat(row.textContent).slice(0, 160)
      }))
    })
    const unitRow = cells.find((c) => c.text.includes('demo-api-1121222614'))
    expect(!!unitRow, 'the seeded unit row is missing from the list')
    expect(
      unitRow?.text.includes('sha256:4a5') || unitRow?.text.includes('4a5b6c'),
      `the unit row shows no latest digest (${unitRow?.text})`
    )
    expect(unitRow?.text.includes(ORDER), `the unit row shows no publish number (${unitRow?.text})`)
    expect(
      summaryGets.some((url) => url.endsWith('/units/2/release-summary')),
      `the page did not read unit 2's summary (${summaryGets.length} reads)`
    )
    // 占用锁 is free: —
    expect(unitRow && !/占用中/.test(unitRow.text), 'the free unit already shows 占用中')

    if (SHOTS) {
      await page.screenshot({ path: join(SHOTS, `unit-summary-${theme}.png`) })
    }

    // -------------------------------------------- unit list: the occupied lock column
    insertLock()
    await open(page, '/units', theme)
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('.el-table__row')).some(
        (row) =>
          (row.textContent ?? '').includes('demo-api-1121222614') &&
          (row.textContent ?? '').includes('占用中')
      ),
      { timeout: 20000, polling: 300 }
    )

    // -------------------------------------- system detail: 发布记录 + 管控配置 entry
    await open(page, `/subsystems/${SUBSYSTEM}`, theme)
    await page.waitForSelector('.system-releases .el-table__row', { timeout: 20000 })
    await sleep(400)
    const systemPage = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const block = document.querySelector('.system-releases')
      return {
        records: flat(block?.textContent).slice(0, 300),
        controlEntry: Array.from(document.querySelectorAll('a')).some(
          (a) => a.getAttribute('href')?.includes('/control') &&
            a.getAttribute('href')?.includes('subsystemId=2') &&
            flat(a.textContent).includes('管控配置')
        )
      }
    })
    expect(systemPage.records.includes(ORDER), `the 发布记录 block misses the order (${systemPage.records})`)
    expect(systemPage.records.includes(CRID), `the 发布记录 block misses the CRID (${systemPage.records})`)
    expect(systemPage.controlEntry, 'the 管控配置 entry is missing')

    if (SHOTS) {
      await page.evaluate(() => {
        document.querySelector('.system-releases')?.scrollIntoView({ block: 'start' })
      })
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `subsystem-records-${theme}.png`) })
    }

    // ------------------------------------ the entry navigates and preselects the system
    await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const link = Array.from(document.querySelectorAll('a')).find(
        (a) => a.getAttribute('href')?.includes('/control') &&
          flat(a.textContent).includes('管控配置')
      )
      link?.click()
    })
    await page.waitForFunction(
      () => window.location.search.includes('subsystemId=2') &&
        document.querySelector('.el-select') !== null,
      { timeout: 20000 }
    )
    await sleep(800)
    const preselected = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      // The window tab's subsystem select is the first el-select; Element Plus renders the
      // selected label into the placeholder slot.
      const select = document.querySelector('.el-select')
      const label = flat(
        select?.querySelector('.el-select__placeholder, .el-select__selected-item')?.textContent
      )
      return { value: label || flat(select?.textContent).slice(0, 40) }
    })
    expect(
      page.url().includes('subsystemId=2'),
      `the control page did not carry subsystemId=2 (${page.url()})`
    )
    // The preselected option renders the subsystem's own name, not the placeholder 全局默认.
    expect(
      preselected.value && preselected.value !== '全局默认',
      `the control page did not preselect subsystem 2 (${preselected.value})`
    )

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p1b-${theme}: unitRow=digest+order lock=占用中 records=${systemPage.records.includes(CRID)} ` +
        `controlEntry=${systemPage.controlEntry} preselect=${preselected.value} summaryReads=${summaryGets.length} errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  try {
    reseed()
    lines.push('re-seeded: PO-DEMO-REDEPLOY (locks cleared)')
  } catch (e) {
    failures++
    lines.push(`RE-SEED FAILED: ${e.message}`)
  }
  await browser.close()
}

lines.forEach((line) => console.log(line))
if (failures) {
  console.log(`FAILED (${failures} theme run(s))`)
  process.exit(1)
}
console.log('OK all PART-01 page-structure assertions passed')
