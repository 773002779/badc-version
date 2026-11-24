// Asserts the P4-C audit slice in a real browser (texts + selectors + console error count; exit
// non-zero on any miss): the unit-detail 「操作日志」 block (rows, columns, short traceId with the
// full id on hover), the global `/audit` page (rows, the action and object-type filters narrowing
// the list, the CSV export really answering text/csv with a server filename) and 0 console errors,
// in both themes.
//
//   node scripts/verify-p4c-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p4c-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes audit-{light,dark}.png and unit-audit-{light,dark}.png; the assertions run the same
// way with or without it. Run from the repository root so a relative --shots path lands in the repo.
//
// Data: the unit block needs unit-scoped rows. The development seed used real actions (so every row
// has a traceId and before/after), all performed as dev against the live backend:
//   PUT /units/2  (a no-op edit of the demo unit, 3 times)
//   PUT /api/v1/settings/notifications   (same values)
//   PUT /api/v1/windows                  (same values)
//   POST /api/v1/freezes + POST /api/v1/freezes/{id}/release  (subsystem 2, released at once)
// Re-run those if the database is reset; the page assertions then see rows again.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p4c-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p4c-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

/** The demo unit the seed rows belong to; its rows are RESOURCE_UPDATE rows of a real edit. */
const UNIT_ID = 2
const UNIT_COLUMNS = ['时间', '操作人', '动作', '摘要', '原因', 'traceId']
const AUDIT_COLUMNS = ['时间', '操作人', '动作', '对象', '摘要', '原因', 'traceId']
/** Labels straight from `AuditAction.java` / `AuditObjectType.java` (the probe must not invent). */
const ACTION_CODE = 'RESOURCE_UPDATE'
const ACTION_LABEL = '更新资源'
const OBJECT_TYPE_CODE = 'SETTING'
const OBJECT_TYPE_LABEL = '平台设置'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

/** Clicks the element whose trimmed text is exactly `text`; returns whether it was found. */
function clickByText(page, selector, text) {
  return page.evaluate(
    (sel, label) => {
      const nodes = Array.from(document.querySelectorAll(sel))
      const hit = nodes.find((n) => (n.textContent ?? '').trim() === label)
      if (!hit) return false
      hit.click()
      return true
    },
    selector,
    text
  )
}

/**
 * Picks an option of one `el-select`. Element Plus renders every select's dropdown into `<body>`,
 * so the visible option (offsetParent) is the one of the select just opened.
 */
async function selectOption(page, selectSelector, label) {
  await page.click(`${selectSelector} .el-select__wrapper`)
  try {
    await page.waitForFunction(
      (text) =>
        Array.from(document.querySelectorAll('.el-select-dropdown__item')).some(
          (n) => n.offsetParent !== null && (n.textContent ?? '').trim() === text
        ),
      { timeout: 8000, polling: 100 },
      label
    )
  } catch {
    return false
  }
  return page.evaluate((text) => {
    const hit = Array.from(document.querySelectorAll('.el-select-dropdown__item')).find(
      (n) => n.offsetParent !== null && (n.textContent ?? '').trim() === text
    )
    if (!hit) return false
    hit.click()
    return true
  }, label)
}

/** The `N 条` subtitle of the audit panel as a number; -1 when it has not rendered. */
async function totalNumber(page) {
  const text = await page
    .$eval('.audit-page .panel-head .weak.small', (el) => el.textContent.trim())
    .catch(() => '')
  const matched = /^(\d+)/.exec(text)
  return matched ? Number(matched[1]) : -1
}

async function waitTotalChange(page, before) {
  try {
    await page.waitForFunction(
      (prev) => {
        const el = document.querySelector('.audit-page .panel-head .weak.small')
        return !!el && el.textContent.trim() !== prev
      },
      { timeout: 10000, polling: 150 },
      before
    )
  } catch {
    // The before/after comparison below reports the miss.
  }
}

function rowTexts(page, scope) {
  return page.$$eval(`${scope} .el-table__row`, (els) =>
    els.map((el) => (el.innerText ?? '').replace(/\s+/g, ' ').trim())
  )
}

function headerCells(page, scope) {
  return page.$$eval(`${scope} .el-table__header th .cell`, (els) =>
    els.map((el) => (el.textContent ?? '').trim())
  )
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

    // Let the programmatic `<a download>` of the export land in a temp dir, not the user's.
    try {
      const downloads = join(tmpdir(), 'rp-shot-tools-downloads')
      await mkdir(downloads, { recursive: true })
      const client = await page.createCDPSession()
      await client.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads })
    } catch {
      // The export assertion is the API response; the download itself is only best-effort here.
    }

    // 1. The unit page: the 操作日志 block with its rows, columns and folded trace id.
    await page.goto(appendTheme(`${BASE}/units/${UNIT_ID}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.unit-audit .el-table__row', { visible: true, timeout: 20000 })
    await sleep(400)

    const unitTitle = await page
      .$eval('.unit-audit .panel-title', (el) => el.textContent.trim())
      .catch(() => '')
    if (unitTitle !== '操作日志') fails.push(`unit title (${unitTitle})`)

    const unitCols = await headerCells(page, '.unit-audit')
    const unitMissing = UNIT_COLUMNS.filter((c) => !unitCols.includes(c))
    if (unitMissing.length) fails.push(`unit columns (${unitMissing.join(',')})`)

    const unitRows = await rowTexts(page, '.unit-audit')
    if (unitRows.length < 1) fails.push(`unit rows (${unitRows.length})`)

    const trace = await page
      .$eval('.unit-audit .trace-id', (el) => ({
        short: el.textContent.trim(),
        full: el.getAttribute('title') ?? ''
      }))
      .catch(() => null)
    if (!trace || trace.short.length !== 8 || trace.full.length <= trace.short.length) {
      fails.push(`unit trace (${JSON.stringify(trace)})`)
    }

    if (SHOTS) {
      await page.$eval('.unit-audit', (el) => el.scrollIntoView({ block: 'center' }))
      await sleep(300)
      await page.screenshot({ path: join(SHOTS, `unit-audit-${theme}.png`) })
    }

    // 2. The global page: the sidebar entry, the table and its total.
    await page.goto(appendTheme(`${BASE}/audit`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.audit-page .el-table__row', { visible: true, timeout: 20000 })
    await sleep(500)

    const navText = await page
      .$eval('.sidebar a[href="/audit"]', (el) => (el.textContent ?? '').replace(/\s+/g, '').trim())
      .catch(() => '')
    if (navText !== '审计日志') fails.push(`sidebar entry (${navText || 'missing'})`)

    const auditCols = await headerCells(page, '.audit-page')
    const auditMissing = AUDIT_COLUMNS.filter((c) => !auditCols.includes(c))
    if (auditMissing.length) fails.push(`audit columns (${auditMissing.join(',')})`)

    const allTotal = await totalNumber(page)
    if (!(allTotal >= 2)) fails.push(`audit total (${allTotal})`)
    const allRows = await rowTexts(page, '.audit-page')
    if (allRows.length < 1) fails.push(`audit rows (${allRows.length})`)

    const auditTrace = await page
      .$eval('.audit-page .trace-id', (el) => ({
        short: el.textContent.trim(),
        full: el.getAttribute('title') ?? ''
      }))
      .catch(() => null)
    if (!auditTrace || auditTrace.short.length !== 8 || auditTrace.full.length <= auditTrace.short.length) {
      fails.push(`audit trace (${JSON.stringify(auditTrace)})`)
    }

    if (SHOTS) {
      await page.screenshot({ path: join(SHOTS, `audit-${theme}.png`) })
    }

    // 2b. The expand row shows the changed fields (before/after are JSON texts on the row).
    const expandClicked = await page.evaluate(() => {
      const icon = document.querySelector('.audit-page .el-table__row .el-table__expand-icon')
      if (!icon) return false
      icon.click()
      return true
    })
    if (!expandClicked) fails.push('expand icon')
    await sleep(300)
    const diffText = await page
      .$eval('.audit-page .diff', (el) => (el.innerText ?? '').replace(/\s+/g, ' ').trim())
      .catch(() => '')
    if (!diffText.includes('变更前') || !diffText.includes('变更后')) {
      fails.push(`diff block (${diffText.slice(0, 40)})`)
    }
    await page.evaluate(() => {
      const icon = document.querySelector('.audit-page .el-table__row .el-table__expand-icon')
      if (icon) icon.click()
    })
    await sleep(200)

    // 3. The action filter is server-side: the list narrows and every row is that action.
    const actionPicked = await selectOption(page, '.filter-action', ACTION_LABEL)
    if (!actionPicked) fails.push(`action option (${ACTION_LABEL})`)
    await clickByText(page, 'button', '查询')
    await waitTotalChange(page, `${allTotal} 条`)
    const actionTotal = await totalNumber(page)
    const actionRows = await rowTexts(page, '.audit-page')
    if (!(actionTotal >= 1 && actionTotal < allTotal)) {
      fails.push(`action filter total (${actionTotal}/${allTotal})`)
    }
    const wrongAction = actionRows.filter((t) => !t.includes(ACTION_LABEL))
    if (wrongAction.length) fails.push(`action filter rows (${wrongAction.length}/${actionRows.length})`)
    // The chip's hover title is the machine code, not the label: the page shows the server label.
    const codeTitles = await page.$$eval('.audit-page .el-table__row .el-tag', (els) =>
      els.map((el) => el.getAttribute('title') ?? '')
    )
    if (codeTitles.some((t) => t !== ACTION_CODE)) {
      fails.push(`action code hint (${codeTitles.join(',')})`)
    }

    // 4. 重置 restores the unfiltered list.
    await clickByText(page, 'button', '重置')
    await waitTotalChange(page, `${actionTotal} 条`)
    const resetTotal = await totalNumber(page)
    if (resetTotal !== allTotal) fails.push(`reset total (${resetTotal}/${allTotal})`)

    // 5. The object-type filter: same shape, every row is that object type.
    const typePicked = await selectOption(page, '.filter-object', OBJECT_TYPE_LABEL)
    if (!typePicked) fails.push(`object option (${OBJECT_TYPE_LABEL})`)
    await clickByText(page, 'button', '查询')
    await waitTotalChange(page, `${resetTotal} 条`)
    const typeTotal = await totalNumber(page)
    const typeRows = await rowTexts(page, '.audit-page')
    if (!(typeTotal >= 1 && typeTotal < allTotal)) {
      fails.push(`object filter total (${typeTotal}/${allTotal})`)
    }
    const wrongType = typeRows.filter((t) => !t.includes(OBJECT_TYPE_LABEL))
    if (wrongType.length) fails.push(`object filter rows (${wrongType.length}/${typeRows.length})`)

    // 6. 导出 CSV: the authenticated fetch carries the *current* filter and answers the CSV body
    // (that response is the download's payload; the file itself is a browser-side bonus).
    let exportStatus = 0
    let exportType = ''
    let exportFilename = ''
    let exportUrl = ''
    try {
      const [response] = await Promise.all([
        page.waitForResponse((res) => res.url().includes('/api/v1/audit/export'), { timeout: 20000 }),
        clickByText(page, 'button', '导出 CSV')
      ])
      exportStatus = response.status()
      exportType = response.headers()['content-type'] ?? ''
      exportFilename = response.headers()['content-disposition'] ?? ''
      const params = new URL(response.url()).searchParams
      exportUrl = params.get('objectType') ?? ''
      if (params.has('page') || params.has('size')) fails.push('export carries paging')
    } catch {
      // Reported by the assertions below.
    }
    if (exportStatus !== 200) fails.push(`export status (${exportStatus})`)
    if (!exportType.includes('text/csv')) fails.push(`export type (${exportType || 'none'})`)
    if (!/audit-.*\.csv/.test(exportFilename)) fails.push(`export name (${exportFilename || 'none'})`)
    if (exportUrl !== OBJECT_TYPE_CODE) fails.push(`export filter (${exportUrl || 'none'})`)
    const exportToast = await page
      .waitForSelector('.el-message--success', { visible: true, timeout: 5000 })
      .then(() => true)
      .catch(() => false)
    if (!exportToast) fails.push('export toast')
    await sleep(400)

    // 7. Reset brings the unfiltered list back after the export.
    await clickByText(page, 'button', '重置')
    await waitTotalChange(page, `${typeTotal} 条`)
    const finalTotal = await totalNumber(page)
    if (finalTotal !== allTotal) fails.push(`final reset (${finalTotal}/${allTotal})`)

    // 8. The empty state: an object id that cannot match still answers 「没有符合条件的审计记录」.
    await page.type('.filter-object-id input', 'NO-SUCH-OBJECT')
    await clickByText(page, 'button', '查询')
    const emptyShown = await page
      .waitForSelector('.audit-page .empty', { visible: true, timeout: 10000 })
      .then(() => true)
      .catch(() => false)
    const emptyText = emptyShown
      ? await page.$eval('.audit-page .empty', (el) => (el.innerText ?? '').replace(/\s+/g, ' ').trim())
      : ''
    if (!emptyText.includes('没有符合条件的审计记录')) fails.push(`empty state (${emptyText || 'missing'})`)

    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `audit-${theme}: unitRows=${unitRows.length} unitTrace=${trace ? trace.short : 'none'} ` +
        `auditRows=${allRows.length} total=${allTotal} ` +
        `actionFilter=${actionRows.length}/${actionTotal} ` +
        `objectFilter=${typeRows.length}/${typeTotal} ` +
        `export=${exportStatus} ${exportType} ${exportFilename} ` +
        `errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 3).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  await browser.close()
}

lines.forEach((l) => console.log(l))
if (failures) {
  console.log(`FAILED (${failures} theme run(s))`)
  process.exit(1)
}
console.log('OK all audit assertions passed')