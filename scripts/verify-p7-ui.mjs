// Asserts the PART-07 兼容模式 semantics in a real browser, in both themes, on the demo order of
// scripts/demo-redeploy.sql: the probe flips that unit's `branch_mode` to COMPATIBLE for the run
// (restored to STANDARD in a `finally` block), so it is safe to re-run:
//
//   * the release page of a COMPATIBLE unit renders the info banner (兼容模式单元：只投递镜像、
//     代码自管、回滚仅切镜像) and a STANDARD unit does not;
//   * the rollback drawer of a COMPATIBLE unit carries the same note and still opens normally;
//   * 0 console errors.
//
//   node scripts/verify-p7-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p7-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes compatible-{light,dark}.png. Run from the repository root.
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const ORDER = 'PO-DEMO-REDEPLOY' // seeded by scripts/demo-redeploy.sql (unit 2)
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

/** Flips the demo unit's branch mode; PART-07's semantics are display-only, so this is the whole state. */
function setBranchMode(mode) {
  const res = mysql([
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    `--execute=UPDATE deploy_unit SET branch_mode='${mode}' WHERE id=2;`
  ])
  if (res.error || res.status !== 0) {
    throw new Error(`cannot set branch_mode=${mode}: ${res.stderr || res.error?.message}`)
  }
}

// ---------------------------------------------------------------------- page helpers

async function open(page, path, theme) {
  await page.goto(appendTheme(`${BASE}${path}`, theme), {
    waitUntil: 'networkidle2',
    timeout: 30000
  })
}

async function waitBanner(page, present) {
  await page.waitForFunction(
    (wanted) =>
      Array.from(document.querySelectorAll('.compatible-banner')).some(
        (n) => n.getBoundingClientRect().width > 0
      ) === wanted,
    { timeout: 15000, polling: 250 },
    present
  )
}

async function readBanner(page) {
  return page.evaluate(() => {
    const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
    return { text: flat(document.querySelector('.compatible-banner')?.textContent) }
  })
}

// ---------------------------------------------------------------------------- main run

try {
  reseed()
  setBranchMode('STANDARD')
  console.log('seeded: PO-DEMO-REDEPLOY, unit 2 back on STANDARD')
} catch (e) {
  console.error(`cannot prepare the demo state: ${e.message}`)
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
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    // ---------------------------------------------- STANDARD: no banner anywhere
    await reseed()
    setBranchMode('STANDARD')
    await open(page, `/releases/${ORDER}`, theme)
    await page.waitForSelector('.task-row', { timeout: 20000 })
    await sleep(600)
    expect(
      !(await page.evaluate(() =>
        Array.from(document.querySelectorAll('.compatible-banner')).some(
          (n) => n.getBoundingClientRect().width > 0
        )
      )),
      'a STANDARD unit shows the compatible banner'
    )

    // ------------------------------------------------ COMPATIBLE: the release banner
    setBranchMode('COMPATIBLE')
    await open(page, `/releases/${ORDER}`, theme)
    await waitBanner(page, true)
    const banner = await readBanner(page)
    expect(banner.text.includes('兼容模式单元'), `banner title (${banner.text})`)
    expect(banner.text.includes('只投递镜像'), `banner copy (${banner.text})`)
    expect(banner.text.includes('回滚仅切换镜像'), `banner rollback copy (${banner.text})`)

    if (SHOTS) {
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `compatible-${theme}.png`) })
    }

    // ------------------------------------ COMPATIBLE: the rollback drawer note
    const opened = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const button = Array.from(document.querySelectorAll('button')).find(
        (b) => flat(b.textContent) === '进入生产回滚'
      )
      if (!button || button.disabled) return false
      button.click()
      return true
    })
    expect(opened, 'could not open the rollback drawer (进入生产回滚 not clickable)')
    await page.waitForSelector('.rollback-drawer', { timeout: 8000 })
    await page.waitForSelector('.rollback-drawer .compatible-note', { timeout: 8000 })
    await sleep(400)
    const note = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      return {
        text: flat(document.querySelector('.rollback-drawer .compatible-note')?.textContent),
        versions: document.querySelectorAll('.rollback-drawer .version-card').length
      }
    })
    expect(note.text.includes('兼容模式单元'), `drawer note title (${note.text})`)
    expect(note.text.includes('代码变更与回退'), `drawer note copy (${note.text})`)
    expect(note.versions > 0, 'the version list did not render (the note must not break the drawer)')

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++
    lines.push(`p7-${theme}: banner=${banner.text.includes('只投递镜像')} drawerNote=${note.text.includes('自行管理')} errors=${errors.length}`)
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  try {
    setBranchMode('STANDARD')
    reseed()
    lines.push('restored: unit 2 back on STANDARD; PO-DEMO-REDEPLOY re-seeded')
  } catch (e) {
    failures++
    lines.push(`RESTORE FAILED: ${e.message}`)
  }
  await browser.close()
}

lines.forEach((line) => console.log(line))
if (failures) {
  console.log(`FAILED (${failures} theme run(s))`)
  process.exit(1)
}
console.log('OK all PART-07 compatible-mode assertions passed')
