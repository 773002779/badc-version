// Asserts the P6-F 页面补齐 delivery in a real browser, in both themes, on the demo task seeded
// by scripts/demo-batchpublish.sql (re-seeded before the run, before every theme and again in a
// `finally` block, so it is safe to re-run):
//
//   * 批量发布 (PART-02): the 变更项 table has a selection column where only the two PENDING rows
//     are selectable (the DONE row's checkbox is disabled); selecting both shows 批量发布（2）,
//     the confirm dialog states the serial semantics (某一项失败不会中断后续项), and one real run
//     issues exactly two POST /items/{id}/publish — one succeeds (item leaves PENDING), the
//     unconfigured item fails — and the result summary names both the success and the failed CRID
//     with its reason;
//   * 豁免默认有效期 (PART-06): the 门禁规则 block renders the field with the configured default,
//     and a real save of 10 comes back through the API (restored in a `finally`);
//   * 0 console errors.
//
//   node scripts/verify-p6f-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6f-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes batch-publish-{light,dark}.png (the task detail with the selection column) and
// settings-waiver-{light,dark}.png (the gate block with the waiver field). Run from the repository
// root. The repo has no root package.json for tooling, so puppeteer-core is only resolvable when
// this script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p6f-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6f-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const TASK = 92102 // seeded by scripts/demo-batchpublish.sql
const ITEM_OK = 92102 // PENDING on unit 1 (bindings + DEs): the publish succeeds
const ITEM_FAIL = 92103 // PENDING on the unbound unit 91002: the publish is refused
const ITEM_DONE = 92104 // DONE: must not be selectable
const CRID_OK = 'CR-DEMO-BATCH-1'
const CRID_FAIL = 'CR-DEMO-BATCH-2'
const ADMIN = 'dev'
const SQL = join(process.cwd(), 'scripts', 'demo-batchpublish.sql')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

// ---------------------------------------------------------------- seed / API helpers

function reseed() {
  const res = spawnSync('mysql', [
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    '--default-character-set=utf8mb4',
    `--execute=source ${SQL.replace(/\\/g, '/')}`
  ], {
    encoding: 'utf8',
    timeout: 30000,
    env: { ...process.env, MYSQL_PWD: 'root' }
  })
  if (res.error || res.status !== 0) {
    throw new Error(
      `cannot re-seed ${SQL}: ${res.error?.message ?? (res.stderr || `exit ${res.status}`)}`
    )
  }
  return res.stdout
}

async function api(path, init = {}) {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'X-User-Id': ADMIN, ...(init.headers ?? {}) }
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`${init.method ?? 'GET'} ${path} failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

const itemStatus = async (id) => {
  const items = await api(`/tasks/${TASK}/items`)
  return items.find((item) => item.id === id)?.status ?? null
}

async function readWaiverDays() {
  const view = await api('/api/v1/settings/waiver')
  return view.defaultDays
}

async function restoreWaiverDefault() {
  await api('/api/v1/settings/waiver', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultDays: 7 })
  })
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

/** Waits for the task-detail 变更项 table rows. */
async function waitTaskPage(page) {
  await page.waitForSelector('.items-block .el-table__row', { timeout: 20000 })
  await sleep(400)
}

/** Waits until some visible ElMessage / ElMessageBox alert contains `text`. */
async function waitMessage(page, text, timeout = 8000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        Array.from(document.querySelectorAll('.el-message, .el-message-box')).some(
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

/** One row of the 变更项 table with its selection checkbox state. */
async function readItemRows(page) {
  return page.evaluate(() => {
    const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
    return Array.from(document.querySelectorAll('.items-block .el-table__row')).map((row) => {
      const box = row.querySelector('.el-checkbox')
      const disabled = box ? box.classList.contains('is-disabled') : null
      const checked = box ? box.classList.contains('is-checked') : false
      const crid = flat(row.querySelector('.mono')?.textContent)
      const status = flat(
        Array.from(row.querySelectorAll('.status-dot, .el-tooltip__trigger'))
          .map((n) => n.getAttribute('aria-label') ?? n.textContent)
          .find(Boolean)
      )
      return { crid, text: flat(row.textContent), disabled, checked, status }
    })
  })
}

/** Checks the rows whose CRID matches one of `crids` (the way a user would click). */
async function checkRows(page, crids) {
  const clicked = await page.evaluate((wanted) => {
    const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
    let hit = 0
    for (const row of Array.from(document.querySelectorAll('.items-block .el-table__row'))) {
      const crid = flat(row.querySelector('.mono')?.textContent)
      if (wanted.includes(crid)) {
        const box = row.querySelector('.el-checkbox')
        if (box && !box.classList.contains('is-disabled')) {
          box.querySelector('input')?.click()
          hit++
        }
      }
    }
    return hit
  }, crids)
  if (clicked !== crids.length) {
    throw new Error(`could not check ${clicked}/${crids.length} of ${crids.join(', ')}`)
  }
  await sleep(250)
}

// ---------------------------------------------------------------------------- main run

try {
  reseed()
  console.log('seeded: DEMO-BATCH with two PENDING items and one DONE item')
} catch (e) {
  console.error(`cannot seed the demo task: ${e.message}`)
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
    const publishPosts = []
    page.on('request', (req) => {
      if (req.method() === 'POST' && /\/items\/\d+\/publish$/.test(req.url())) {
        publishPosts.push(req.url())
      }
    })
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    await reseed()
    await setUser(page, ADMIN)
    await open(page, `/tasks/${TASK}`, theme)
    await waitTaskPage(page)

    // ------------------------------------------------ selection: only PENDING is checkable
    const rows = await readItemRows(page)
    const okRow = rows.find((r) => r.crid === CRID_OK)
    const failRow = rows.find((r) => r.crid === CRID_FAIL)
    const doneRow = rows.find((r) => r.crid === 'CR-DEMO-BATCH-3')
    expect(!!okRow && !!failRow && !!doneRow, `the three demo items are not all rendered (${rows.length} rows)`)
    expect(okRow?.disabled === false, 'the PENDING ok item is not selectable')
    expect(failRow?.disabled === false, 'the PENDING unconfigured item is not selectable')
    expect(doneRow?.disabled === true, 'the DONE item stays selectable (design §5.4)')

    // ------------------------------------------------------------ screenshot (selection)
    if (SHOTS) {
      await page.evaluate(() => {
        const block = document.querySelector('.items-block')
        block?.scrollIntoView({ block: 'start' })
      })
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `batch-publish-${theme}.png`) })
    }

    // ------------------------------------------------------ the batch: serial two POSTs
    await checkRows(page, [CRID_OK, CRID_FAIL])
    const batchButton = await page.evaluate(() => {
      const button = document.querySelector('.items-block .batch-publish')
      return button
        ? { disabled: button.disabled, text: flat(button.textContent) }
        : null
      function flat(t) {
        return (t ?? '').replace(/\s+/g, ' ').trim()
      }
    })
    expect(!!batchButton, '批量发布 button missing')
    expect(batchButton?.text.includes('2'), `批量发布 count label (${batchButton?.text})`)
    expect(batchButton?.disabled === false, '批量发布 stays disabled with two items checked')

    await page.click('.items-block .batch-publish')
    await page.waitForSelector('.el-message-box', { timeout: 8000 })
    const confirmText = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      return flat(document.querySelector('.el-message-box')?.textContent)
    })
    expect(confirmText.includes('串行'), `confirm dialog serial copy (${confirmText})`)
    expect(confirmText.includes('不会中断'), `confirm dialog no-abort copy (${confirmText})`)
    await page.click('.el-message-box__btns .el-button--primary')

    expect(
      await waitMessage(page, '成功 1 项'),
      'the result summary naming one success is missing'
    )
    await sleep(600)
    expect(publishPosts.length === 2, `the page issued ${publishPosts.length} publish POSTs`)
    expect(
      publishPosts.some((url) => url.endsWith(`/items/${ITEM_OK}/publish`)),
      'the ok item was not posted'
    )
    expect(
      publishPosts.some((url) => url.endsWith(`/items/${ITEM_FAIL}/publish`)),
      'the unconfigured item was not posted (the failure must be a real server refusal)'
    )

    const okStatus = await itemStatus(ITEM_OK)
    const failStatus = await itemStatus(ITEM_FAIL)
    expect(okStatus !== 'PENDING', `the ok item did not leave PENDING (${okStatus})`)
    expect(failStatus === 'PENDING', `the refused item changed status (${failStatus})`)

    // ---------------------------------------- the waiver default field of the gate block
    await open(page, '/settings', theme)
    await page.waitForSelector('.settings-gate .waiver-days-input input', { timeout: 20000 })
    await sleep(400)
    const waiverBefore = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const block = document.querySelector('.settings-gate')
      const input = block?.querySelector('.waiver-days-input input')
      return {
        value: input?.value ?? '',
        hint: flat(block?.querySelector('.waiver-days-hint')?.textContent),
        title: flat(block?.querySelector('.field-label')?.textContent)
      }
    })
    const apiBefore = await readWaiverDays()
    expect(waiverBefore.value === String(apiBefore), `field value (${waiverBefore.value} vs ${apiBefore})`)
    expect(
      waiverBefore.hint.includes('留空') && waiverBefore.hint.includes('默认'),
      `the empty-validity hint (${waiverBefore.hint})`
    )

    // a real save of 10 comes back through the API
    await page.evaluate(() => {
      const input = document.querySelector('.settings-gate .waiver-days-input input')
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(input, '10')
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
    })
    await sleep(200)
    await page.click('.settings-gate .waiver-save')
    expect(await waitMessage(page, '豁免默认有效期已保存'), 'the waiver save toast is missing')
    await sleep(300)
    expect((await readWaiverDays()) === 10, 'the saved waiver default did not reach the API')
    await restoreWaiverDefault()
    expect((await readWaiverDays()) === 7, 'the waiver default was not restored to 7')

    // ------------------------------------------------------------ screenshot (waiver field)
    if (SHOTS) {
      await page.evaluate(() => {
        document.querySelector('.settings-gate')?.scrollIntoView({ block: 'start' })
      })
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `settings-waiver-${theme}.png`) })
    }

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6f-${theme}: selection=2/3 posts=${publishPosts.length} ` +
        `ok=${okStatus} fail=${failStatus} ` +
        `waiver=${waiverBefore.value}→10→restored summary=yes errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  // Restore the documented waiver default and the three-item demo state even on failure.
  try {
    await restoreWaiverDefault()
    reseed()
    lines.push('restored: WAIVER defaultDays=7; DEMO-BATCH re-seeded')
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
console.log('OK all P6-F page-fill assertions passed')
