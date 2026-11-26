// Asserts the P6-G 单 DE 重部署 + 任务日志 actions of the release page in a real browser, in
// both themes, on the failed-batch demo order (scripts/demo-redeploy.sql), which is re-seeded
// before the run, before every theme and again in a `finally` block, so it is safe to re-run:
//
//   * the failed production task row shows an enabled 重新部署 button whose title explains the
//     semantics; the succeeded batch-1 row keeps the button disabled with the「成功」reason; the
//     build rows have no 重新部署 button at all, only 日志;
//   * a caller without `control:execute-prod` (p6g-viewer) sees the button disabled with the
//     permission reason;
//   * the confirm dialog states the rules (同批次 DE 一起重试 / 沿用镜像 / 检查将失效) before the
//     real POST;
//   * a real redeploy: the toast names the new execution, the detail API answers
//     RERUN_FROM_NODE at PROD_DEPLOY with batch 1 SKIPPED and batch 2 retried, and the item reads
//     PROD_DEPLOYING through the admission API;
//   * the 日志 drawer opens on a build task and renders the server's engine message (no CODING
//     connection is configured in the demo DB, so the drawer shows the ENGINE_UNAVAILABLE sentence
//     the server sent); 加载更多 stays disabled because the page was not truncated;
//   * 0 console errors.
//
//   node scripts/verify-p6g-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6g-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes redeploy-{light,dark}.png (the release page at the failed production node).
// Run from the repository root: the --shots path and scripts/demo-redeploy.sql are resolved from
// the current working directory. The repo has no root package.json for tooling, so puppeteer-core
// is only resolvable when this script sits in a scratch dir that has the dependency (ESM ignores
// NODE_PATH):
//   copy scripts\verify-p6g-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6g-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const ORDER = 'PO-DEMO-REDEPLOY' // seeded by scripts/demo-redeploy.sql
const ADMIN = 'dev'
const VIEWER = 'p6g-viewer' // TEST role: no control:execute-prod
const SQL = join(process.cwd(), 'scripts', 'demo-redeploy.sql')

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

async function api(path, userId = ADMIN) {
  const res = await fetch(`${BASE}${path}`, { headers: { 'X-User-Id': userId } })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`GET ${path} failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

const readDetail = () => api(`/api/v1/releases/${ORDER}`)
const readAdmission = () => api(`/api/v1/releases/${ORDER}/admission`)

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

/** Waits for the release page's production panel and its task rows. */
async function waitRelease(page) {
  await page.waitForSelector('.task-row', { timeout: 20000 })
  await page.waitForFunction(
    () => Array.from(document.querySelectorAll('.panel-title')).some((n) =>
      (n.textContent ?? '').includes('生产部署')
    ),
    { timeout: 20000 }
  )
  await sleep(400)
}

/**
 * Waits until the failed batch row's 重新部署 button is interactive: `GET /me` resolves
 * asynchronously, and until then the button is disabled with「正在读取权限」.
 */
async function waitRedeployReady(page) {
  await page.waitForFunction(
    () => {
      const row = Array.from(document.querySelectorAll('.task-row')).find((r) =>
        (r.textContent ?? '').includes('批次 2')
      )
      const button = Array.from(row?.querySelectorAll('button') ?? []).find(
        (b) => (b.textContent ?? '').trim() === '重新部署'
      )
      return !!button && !button.disabled
    },
    { timeout: 20000, polling: 250 }
  )
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

/**
 * One task row of the release page: the machine task type, the batch text and the two P6-G
 * actions. The 重新部署 title carries the disable reason exactly as the server-driven states
 * computed it.
 */
async function readTaskRows(page) {
  return page.evaluate(() => {
    const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
    return Array.from(document.querySelectorAll('.task-row')).map((row) => {
      const buttons = Array.from(row.querySelectorAll('button'))
      const redeploy = buttons.find((b) => flat(b.textContent) === '重新部署') ?? null
      return {
        taskType: flat(row.querySelector('.task-name')?.textContent),
        text: flat(row.textContent),
        hasRedeploy: !!redeploy,
        redeployDisabled: redeploy ? redeploy.disabled : null,
        redeployTitle: redeploy ? redeploy.getAttribute('title') ?? '' : '',
        hasLog: buttons.some((b) => flat(b.textContent) === '日志')
      }
    })
  })
}

/** Clicks 重新部署 / 日志 of the first row matching the predicate's text. */
async function clickTaskAction(page, label, includes) {
  const clicked = await page.evaluate(
    (label, includes) => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const row = Array.from(document.querySelectorAll('.task-row')).find(
        (r) => flat(r.textContent).includes(includes)
      )
      if (!row) return false
      const button = Array.from(row.querySelectorAll('button')).find(
        (b) => flat(b.textContent) === label
      )
      if (!button || button.disabled) return false
      button.click()
      return true
    },
    label,
    includes
  )
  if (!clicked) throw new Error(`could not click ${label} on the row containing "${includes}"`)
}

// ---------------------------------------------------------------------------- main run

try {
  reseed()
  console.log('seeded: PO-DEMO-REDEPLOY failed at production batch 2')
} catch (e) {
  console.error(`cannot seed the demo order: ${e.message}`)
  process.exit(1)
}

const before = await readDetail()
if (before.triggerType !== 'INITIAL' || before.status !== 'FAILED') {
  console.error(`unexpected seeded detail: ${before.triggerType}/${before.status}`)
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
    const redeployPosts = []
    page.on('request', (req) => {
      if (req.method() === 'POST' && /\/tasks\/\d+\/redeploy$/.test(req.url())) {
        redeployPosts.push(req.url())
      }
    })
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    await reseed()
    await setUser(page, ADMIN)
    await open(page, `/releases/${ORDER}`, theme)
    await waitRelease(page)
    await waitRedeployReady(page)

    // -------------------------------------------------- button conditions on the seeded rows
    const rows = await readTaskRows(page)
    const failedBatch = rows.find((r) => r.taskType === 'DEPLOY' && r.text.includes('批次 2'))
    const doneBatch = rows.find((r) => r.taskType === 'DEPLOY' && r.text.includes('批次 1'))
    const buildRow = rows.find((r) => r.taskType === 'CODE_CHECK')

    expect(!!failedBatch, 'the failed batch-2 DEPLOY row is missing')
    expect(failedBatch?.hasRedeploy, '重新部署 missing on the failed production row')
    expect(failedBatch?.redeployDisabled === false, `failed row's 重新部署 disabled (${failedBatch?.redeployTitle})`)
    expect(!!doneBatch, 'the succeeded batch-1 DEPLOY row is missing')
    expect(doneBatch?.hasRedeploy, '重新部署 missing on the succeeded production row')
    expect(doneBatch?.redeployDisabled === true, 'succeeded row keeps 重新部署 enabled')
    expect(
      (doneBatch?.redeployTitle ?? '').includes('只有失败或超时'),
      `succeeded row's disable reason (${doneBatch?.redeployTitle})`
    )
    expect(buildRow?.hasRedeploy === false, '重新部署 shown on a build row')
    expect(buildRow?.hasLog, '日志 missing on a build row')
    expect(
      rows.filter((r) => r.taskType === 'DEPLOY').every((r) => r.hasLog),
      'a production row without a 日志 button'
    )

    // ---------------------------------------------------------------------- screenshot
    if (SHOTS) {
      await page.evaluate(() => {
        const panels = Array.from(document.querySelectorAll('.panel'))
        const prod = panels.find((p) => (p.textContent ?? '').includes('生产部署'))
        prod?.scrollIntoView({ block: 'start' })
      })
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `redeploy-${theme}.png`) })
    }

    // ---------------------------------------------------------- viewer without the perm
    await setUser(page, VIEWER)
    await open(page, `/releases/${ORDER}`, theme)
    await waitRelease(page)
    const viewerRows = await readTaskRows(page)
    const viewerFailed = viewerRows.find((r) => r.taskType === 'DEPLOY' && r.text.includes('批次 2'))
    expect(viewerFailed?.hasRedeploy, '重新部署 missing for the viewer (button must exist, disabled)')
    expect(viewerFailed?.redeployDisabled === true, 'viewer keeps 重新部署 enabled')
    expect(
      (viewerFailed?.redeployTitle ?? '').includes('control:execute-prod'),
      `viewer's permission reason (${viewerFailed?.redeployTitle})`
    )

    // ------------------------------------------- the real redeploy as the permitted admin
    await setUser(page, ADMIN)
    await open(page, `/releases/${ORDER}`, theme)
    await waitRelease(page)
    await waitRedeployReady(page)
    await clickTaskAction(page, '重新部署', '批次 2')

    await page.waitForSelector('.el-message-box', { timeout: 8000 })
    const confirmText = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      return flat(document.querySelector('.el-message-box')?.textContent)
    })
    expect(confirmText.includes('同批次'), `confirm dialog copy (${confirmText})`)
    expect(confirmText.includes('检查将失效'), `confirm dialog check-invalidation copy (${confirmText})`)
    expect(confirmText.includes('不重新构建'), `confirm dialog image-reuse copy (${confirmText})`)
    await page.click('.el-message-box__btns .el-button--primary')

    expect(await waitMessage(page, '已创建重跑实例'), 'the redeploy toast is missing')
    await sleep(600)
    expect(redeployPosts.length === 1, `the page issued ${redeployPosts.length} redeploy POSTs`)

    const after = await readDetail()
    expect(after.triggerType === 'RERUN_FROM_NODE', `detail trigger after the redeploy (${after.triggerType})`)
    const prodNode = after.nodes.find((n) => n.nodeType === 'PROD_DEPLOY')
    const batchOne = prodNode?.tasks.find((t) => t.batchNo === 1)
    const batchTwo = prodNode?.tasks.find((t) => t.batchNo === 2)
    expect(batchOne?.status === 'SKIPPED', `batch 1 after the redeploy (${batchOne?.status})`)
    expect(
      ['PENDING', 'RUNNING', 'WAITING_EXTERNAL', 'FAILED'].includes(batchTwo?.status ?? ''),
      `batch 2 is not retried (${batchTwo?.status})`
    )
    const admission = await readAdmission()
    expect(
      admission.itemStatus === 'PROD_DEPLOYING' || admission.itemStatus === 'FAILED',
      // Without a CODING connection the retried deploy fails immediately (the documented dev
      // expectation), so PROD_DEPLOYING is not always observable here.
      `item status after the redeploy (${admission.itemStatus})`
    )

    // ----------------------------------------- the log drawer on a build task of the page
    await open(page, `/releases/${ORDER}`, theme)
    await waitRelease(page)
    await clickTaskAction(page, '日志', 'CODE_CHECK')
    await page.waitForSelector('.log-drawer .log-body-wrap', { timeout: 8000 })
    await sleep(400)
    const drawer = await page.evaluate(() => {
      const flat = (t) => (t ?? '').replace(/\s+/g, ' ').trim()
      const box = document.querySelector('.log-drawer')
      const alert = box?.querySelector('.el-alert')
      const more = Array.from(box?.querySelectorAll('button') ?? []).find(
        (b) => (b.textContent ?? '').trim() === '加载更多'
      )
      return {
        title: flat(box?.querySelector('.log-head')?.textContent),
        error: flat(alert?.textContent),
        errorVisible: !!alert && alert.getBoundingClientRect().width > 0,
        moreDisabled: more ? more.disabled : null,
        body: flat(box?.querySelector('.log-body')?.textContent)
      }
    })
    expect(drawer.title.includes('CODE_CHECK'), `drawer header (${drawer.title})`)
    expect(drawer.errorVisible, 'the engine-unavailable sentence is not visible in the drawer')
    expect((drawer.error ?? '').length >= 6, `the drawer's engine sentence is empty (${drawer.error})`)
    expect(drawer.moreDisabled === true, '加载更多 is enabled although the page was not truncated')

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6g-${theme}: failedRow=redeployable doneRow=${doneBatch?.redeployDisabled === true} ` +
        `viewer=no-perm confirm=ok post=${redeployPosts.length} ` +
        `after=${after.triggerType}/${batchOne?.status}+${batchTwo?.status} ` +
        `item=${admission.itemStatus} logDrawer=${drawer.error ? 'engine-sentence' : drawer.body} errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  // Leave the failed-batch state behind even when an assertion failed, so later screenshots of
  // other slices keep seeing the pre-redeploy order.
  try {
    reseed()
    lines.push('re-seeded: PO-DEMO-REDEPLOY failed at production batch 2')
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
console.log('OK all P6-G redeploy-and-logs assertions passed')
