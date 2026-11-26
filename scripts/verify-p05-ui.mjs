// Asserts the PART-05 CODING multi-connection delivery in a real browser, in both themes, on
// subsystem 2 of the dev database (the probe's own override row is inserted and finally deleted,
// so it is safe to re-run):
//
//   * the system detail page renders the 「CODING 连接覆盖」 block (未配置 while no override row
//     exists) with the four fields and the GLOBAL→系统 resolution copy;
//   * a real save writes the SUBSYSTEM override through `PUT /api/v1/coding/conn?subsystemId=2`
//     and both the API read-back and the page answer it (已配置, the saved base URL);
//   * the unit detail's DE dialog carries the new 「CODING 部署目标」 input (the DE→CODING target
//     mapping entry);
//   * 0 console errors.
//
//   node scripts/verify-p05-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p05-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes coding-override-{light,dark}.png. Run from the repository root.
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const SUBSYSTEM = 2
const ADMIN = 'dev'
const OVERRIDE_URL = 'https://subsystem-2.coding.example'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

// ---------------------------------------------------------------- mysql / API helpers

function mysql(args) {
  return spawnSync('mysql', args, {
    encoding: 'utf8',
    timeout: 30000,
    env: { ...process.env, MYSQL_PWD: 'root' }
  })
}

/** The probe's own override row; deleting it restores the "未配置" state. */
function clearOverride() {
  const res = mysql([
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    '--execute=DELETE FROM coding_conn WHERE scope=\'SUBSYSTEM\';'
  ])
  if (res.error || res.status !== 0) {
    throw new Error(`cannot clear the override: ${res.stderr || res.error?.message}`)
  }
}

async function readOverride() {
  const res = await fetch(`${BASE}/api/v1/coding/conn?subsystemId=${SUBSYSTEM}`, {
    headers: { 'X-User-Id': ADMIN }
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`GET override failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
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

/** Waits until some visible ElMessage contains `text`; false when it never appears. */
async function waitMessage(page, text, timeout = 8000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        Array.from(document.querySelectorAll('.el-message')).some(
          (n) => n.getBoundingClientRect().width > 0 && (n.textContent ?? '').includes(wanted)
        ),
      { timeout, polling: 100 },
      text
    )
    return true
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------- main run

try {
  clearOverride()
  console.log('cleared: no SUBSYSTEM override row')
} catch (e) {
  console.error(`cannot prepare: ${e.message}`)
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
    const putRequests = []
    page.on('request', (req) => {
      if (req.method() === 'PUT' && req.url().includes('/api/v1/coding/conn')) {
        putRequests.push(req.url())
      }
    })
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    await clearOverride()
    await setUser(page, ADMIN)

    // ------------------------------------------------ the block renders unconfigured
    await open(page, `/subsystems/${SUBSYSTEM}`, theme)
    await page.waitForSelector('.coding-override', { timeout: 20000 })
    await sleep(400)
    const before = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const block = document.querySelector('.coding-override')
      return {
        text: flat(block?.textContent).slice(0, 200),
        subtitle: flat(block?.querySelector('.panel-head .weak')?.textContent),
        inputs: block?.querySelectorAll('input').length ?? 0
      }
    })
    expect(before.inputs >= 4, `the block has ${before.inputs} inputs (expected 4 fields)`)
    expect(before.text.includes('全局 → 系统覆盖'), `resolution copy missing (${before.text})`)
    expect(before.subtitle.includes('未配置'), `unconfigured subtitle (${before.subtitle})`)

    // ---------------------------------------------------- a real save of the override
    await page.evaluate((url) => {
      const block = document.querySelector('.coding-override')
      const inputs = Array.from(block.querySelectorAll('input'))
      const set = (input, value) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
        setter.call(input, value)
        input.dispatchEvent(new Event('input', { bubbles: true }))
        input.dispatchEvent(new Event('change', { bubbles: true }))
      }
      set(inputs[0], url)
      set(inputs[1], 'team-2')
      set(inputs[2], 'project-2')
      set(inputs[3], 'tok-subsystem-2')
    }, OVERRIDE_URL)
    await sleep(200)
    await page.evaluate(() => {
      const block = document.querySelector('.coding-override')
      Array.from(block.querySelectorAll('button')).find(
        (b) => (b.textContent ?? '').trim() === '保存覆盖'
      )?.click()
    })
    expect(await waitMessage(page, 'CODING 连接覆盖已保存'), 'the override save toast is missing')
    await sleep(500)
    expect(
      putRequests.some((url) => url.includes('subsystemId=2')),
      `the save did not target the subsystem (${putRequests.join(', ')})`
    )
    const apiView = await readOverride()
    expect(apiView.configured === true, 'the API read-back is not configured')
    expect(apiView.baseUrl === OVERRIDE_URL, `the saved base URL (${apiView.baseUrl})`)
    expect(apiView.scope === 'SUBSYSTEM', `the row scope (${apiView.scope})`)
    await sleep(300)
    const after = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const block = document.querySelector('.coding-override')
      return {
        subtitle: flat(block?.querySelector('.panel-head .weak')?.textContent),
        url: block?.querySelectorAll('input')[0]?.value ?? ''
      }
    })
    expect(after.subtitle.includes('已配置'), `configured subtitle (${after.subtitle})`)
    expect(after.url === OVERRIDE_URL, `the form re-rendered the saved URL (${after.url})`)

    if (SHOTS) {
      await page.evaluate(() => {
        document.querySelector('.coding-override')?.scrollIntoView({ block: 'start' })
      })
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `coding-override-${theme}.png`) })
    }

    // ------------------------------- the unit detail's DE dialog has the mapping field
    await open(page, '/units/2', theme)
    await page.waitForSelector('.el-table__row, .de-row', { timeout: 20000 })
    await sleep(400)
    const opened = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const button = Array.from(document.querySelectorAll('button')).find(
        (b) => flat(b.textContent) === '添加'
      )
      if (!button) return false
      button.click()
      return true
    })
    expect(opened, 'the DE dialog did not open (添加 button not found)')
    await page.waitForSelector('.el-dialog', { timeout: 8000 })
    await sleep(300)
    const dialog = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const box = document.querySelector('.el-dialog')
      return {
        labels: Array.from(box?.querySelectorAll('.el-form-item__label') ?? []).map((n) =>
          flat(n.textContent)
        )
      }
    })
    expect(
      dialog.labels.includes('CODING 部署目标'),
      `the DE dialog has no CODING target field (${dialog.labels.join('、')})`
    )

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p05-${theme}: block=${before.subtitle.includes('未配置') ? 'unconfigured' : '?'} ` +
        `save=${apiView.baseUrl === OVERRIDE_URL} scope=${apiView.scope} ` +
        `deDialog=${dialog.labels.includes('CODING 部署目标')} errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  try {
    clearOverride()
    lines.push('restored: no SUBSYSTEM override row')
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
console.log('OK all PART-05 multi-connection assertions passed')
