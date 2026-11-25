// Asserts the P6-C unit-level 「流水线配置」 block in a real browser, in both themes, then restores the
// documented defaults:
//
//   * default render (UNIT 2): the block follows the 「CODING 任务绑定」 block, `configured:false` shows
//     「当前为默认配置」, environment checks are on with the three check names, build 60 / deploy 8,
//     batch order 顺序 (FORWARD), and the safety notes («停用对在途评估立即生效», «高风险：先高批后低批»);
//   * range guards: build 4/241 and deploy 0/121 are rejected by the page with the 5–240 / 1–120
//     message and never reach the API (the stored values stay 60/8);
//   * save echo: 15/3 saves, the API echoes `configured:true` + `updatedBy: dev`, the page shows
//     「最近保存：dev · …」 and the subtitle 构建 15 / 部署 3 分钟 · 顺序;
//   * batch order: switching to 逆序 asks for the high-risk confirmation («高风险：先高批后低批»),
//     saves REVERSE, then switching back to 顺序 saves without any dialog;
//   * LIB 91001 (scripts/demo-lib.sql of P6-A): environment checks, deploy timeout and batch order are
//     hidden (a library has no VERIFY node and no production deployment), only the build timeout
//     remains; saving the visible field sends the hidden values back unchanged;
//   * read-only: with `ops-lead` (no `setting:manage`) the block shows the permission reason, renders
//     no control and issues no request to the pipeline-config endpoints;
//   * 0 console errors.
//
//   node scripts/verify-p6c-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6c-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes unit-pipeline-{light,dark}.png. Run from the repository root so the relative
// --shots path lands in the repo.
//
// The probe needs the "never saved" state to assert `configured:false` and the 当前为默认配置 note.
// The API has no delete endpoint, so the probe deletes the unit_pipeline_config row of both units
// through the local dev MySQL (root/root, the application.yml defaults) before each run and in a
// `finally` block. When the MySQL client is not on PATH it falls back to a PUT of the documented
// defaults (60/8/FORWARD/env-checks on) and only skips the "never saved" assertions; every value
// assertion still runs. Either way the probe leaves the units on the documented defaults.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p6c-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6c-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const UNIT = 2 // demo-api-1121222614 (UNIT)
const LIB = 91001 // demo-sdk (LIB, seeded by scripts/demo-lib.sql)
const ADMIN = 'dev'
const READONLY = 'ops-lead'

const DEFAULTS = {
  envChecksEnabled: 'true',
  buildTimeoutMinutes: '60',
  deployTimeoutMinutes: '8',
  batchOrder: 'FORWARD'
}
const ENV_CHECKS = [
  ['安全动态测试', 'SECURITY_DYNAMIC'],
  ['自动化测试', 'AUTO_TEST'],
  ['数据库规范', 'DB_NORM']
]

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

// ------------------------------------------------------------------ API helpers

async function readConfig(unitId, userId = ADMIN) {
  const res = await fetch(`${BASE}/api/v1/units/${unitId}/pipeline-config`, {
    headers: { 'X-User-Id': userId }
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`GET pipeline-config/${unitId} failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

async function writeConfig(unitId, payload, userId = ADMIN) {
  const res = await fetch(`${BASE}/api/v1/units/${unitId}/pipeline-config`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: JSON.stringify(payload)
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`PUT pipeline-config/${unitId} failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

/**
 * Deletes the configuration row so the page returns to `configured:false` (the API has no delete
 * endpoint and the brief allows "delete the row"). Returns false when the client is unavailable, so
 * the caller can fall back to saving the defaults explicitly.
 */
function deleteRows(unitIds) {
  const sql = `DELETE FROM unit_pipeline_config WHERE unit_id IN (${unitIds.join(',')})`
  const res = spawnSync(
    'mysql',
    [
      '--host=127.0.0.1',
      '--user=root',
      '--password=root',
      '--database=release_pipeline',
      `--execute=${sql}`
    ],
    { encoding: 'utf8', timeout: 20000 }
  )
  return !res.error && res.status === 0
}

/** Puts both units back to "never saved" when possible, else to the documented defaults. */
async function resetState() {
  const deleted = deleteRows([UNIT, LIB])
  if (!deleted) {
    await writeConfig(UNIT, DEFAULTS)
    await writeConfig(LIB, DEFAULTS)
  }
  const [unit, lib] = await Promise.all([readConfig(UNIT), readConfig(LIB)])
  return { deleted, unit, lib }
}

function assertDefaults(expect, view, label, expectUnconfigured) {
  expect(view.buildTimeoutMinutes === 60, `${label} build default (${view.buildTimeoutMinutes})`)
  expect(view.deployTimeoutMinutes === 8, `${label} deploy default (${view.deployTimeoutMinutes})`)
  expect(view.batchOrder === 'FORWARD', `${label} batch order default (${view.batchOrder})`)
  expect(view.batchOrderLabel === '顺序', `${label} batch label default (${view.batchOrderLabel})`)
  expect(view.envChecksEnabled === true, `${label} env checks default (${view.envChecksEnabled})`)
  if (expectUnconfigured) {
    expect(view.configured === false, `${label} not configured (${view.configured})`)
    expect(!view.updatedBy && !view.updatedAt, `${label} default metadata (${view.updatedBy})`)
  }
}

// ------------------------------------------------------------------------------- page helpers

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

/** Waits for the header and the requested element, then gives async child blocks one beat. */
async function waitPage(page, selector) {
  await page.waitForSelector('.page-title', { timeout: 20000 })
  if (selector) {
    await page.waitForSelector(selector, { timeout: 20000 })
  }
  await sleep(400)
}

/** Waits until some visible ElMessage contains `text`; false when it never appears. */
async function waitMessage(page, text, timeout = 8000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        Array.from(document.querySelectorAll('.el-message')).some(
          // ElMessage is position:fixed, so offsetParent is null; measure the box instead.
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

function messageBoxText(page, title) {
  return page.evaluate((wanted) => {
    const box = Array.from(document.querySelectorAll('.el-message-box')).find(
      (node) =>
        node.offsetParent !== null &&
        (node.querySelector('.el-message-box__title')?.textContent ?? '').trim() === wanted
    )
    return box ? (box.querySelector('.el-message-box__message')?.innerText ?? '') : null
  }, title)
}

/** Waits for an ElMessageBox with this title; false when it never appears. */
async function waitMessageBox(page, title, timeout = 8000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        Array.from(document.querySelectorAll('.el-message-box')).some(
          (box) =>
            box.offsetParent !== null &&
            (box.querySelector('.el-message-box__title')?.textContent ?? '').trim() === wanted
        ),
      { timeout, polling: 100 },
      title
    )
    return true
  } catch {
    return false
  }
}

/** Waits for every visible ElMessage containing `text` to be gone (so the next toast is fresh). */
async function waitMessageGone(page, text, timeout = 6000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        !Array.from(document.querySelectorAll('.el-message')).some(
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

/** Waits for an ElMessageBox with this title and clicks one of its buttons. */
async function clickMessageBoxButton(page, title, label, timeout = 8000) {
  if (!(await waitMessageBox(page, title, timeout))) return false
  return page.evaluate(
    (wanted, text) => {
      const box = Array.from(document.querySelectorAll('.el-message-box')).find(
        (node) =>
          node.offsetParent !== null &&
          (node.querySelector('.el-message-box__title')?.textContent ?? '').trim() === wanted
      )
      if (!box) return false
      const hit = Array.from(box.querySelectorAll('button')).find(
        (button) => (button.textContent ?? '').replace(/\s+/g, ' ').trim() === text
      )
      if (!hit) return false
      hit.click()
      return true
    },
    title,
    label
  )
}

/** Reads everything the assertions need in one in-page pass. */
function readPanel(page) {
  return page.evaluate(() => {
    const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()
    const unit = document.querySelector('.unit-pipeline')
    const text = (root, selector) => flat(root?.querySelector(selector)?.textContent)
    const orderGroup = unit?.querySelector('.pipeline-order-group')
    return {
      theme: document.documentElement.getAttribute('data-theme'),
      present: !!unit,
      title: text(unit, '.panel-title'),
      subtitle: text(unit, '.panel-head > .weak.small'),
      state: text(unit, '.pipeline-state'),
      save: !!unit?.querySelector('.pipeline-save'),
      restricted: flat(unit?.querySelector('.restricted')?.innerText),
      defaultNote: flat(unit?.querySelector('.pipeline-default')?.innerText),
      env: {
        present: !!unit?.querySelector('.pipeline-env'),
        on: !!unit?.querySelector('.pipeline-env-switch.is-checked'),
        checks: Array.from(unit?.querySelectorAll('.env-checks .el-tag') ?? []).map((tag) =>
          flat(tag.textContent)
        ),
        note: flat(unit?.querySelector('.pipeline-env-note')?.innerText)
      },
      build: unit?.querySelector('.pipeline-build input')?.value ?? null,
      deploy: unit?.querySelector('.pipeline-deploy input')?.value ?? null,
      order: {
        present: !!orderGroup,
        options: Array.from(orderGroup?.querySelectorAll('.el-radio-button') ?? []).map((button) => ({
          label: flat(button.textContent),
          active: button.classList.contains('is-active')
        })),
        active: flat(orderGroup?.querySelector('.el-radio-button.is-active')?.textContent),
        hint: flat(orderGroup?.parentElement?.querySelector('.weak.small')?.innerText)
      },
      libNote: flat(unit?.querySelector('.pipeline-lib-note')?.innerText),
      audit: flat(unit?.querySelector('.pipeline-audit')?.innerText),
      counts: {
        switches: unit?.querySelectorAll('.pipeline-env-switch').length ?? 0,
        buildInputs: unit?.querySelectorAll('.pipeline-build input').length ?? 0,
        deployInputs: unit?.querySelectorAll('.pipeline-deploy input').length ?? 0,
        orderGroups: unit?.querySelectorAll('.pipeline-order-group').length ?? 0,
        saves: unit?.querySelectorAll('.pipeline-save').length ?? 0
      }
    }
  })
}

function setInput(page, selector, value) {
  return page.evaluate(
    (wanted, next) => {
      const input = document.querySelector(wanted)
      if (!input) return false
      // A real input event drives the Vue model exactly like typing would.
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(input, next)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return input.value === next
    },
    selector,
    value
  )
}

/** Clicks the batch-order radio button whose visible label is `label` (顺序 / 逆序). */
function clickOrder(page, label) {
  return page.evaluate((wanted) => {
    const button = Array.from(
      document.querySelectorAll('.pipeline-order-group .el-radio-button')
    ).find((node) => (node.textContent ?? '').replace(/\s+/g, ' ').trim() === wanted)
    if (!button) return false
    button.click()
    return true
  }, label)
}

// ----------------------------------------------------------------------------------- main run

let initial
try {
  initial = await resetState()
} catch (e) {
  console.error(`cannot reset the pipeline configuration: ${e.message}`)
  process.exit(1)
}
console.log(
  `initial state: rowDeleted=${initial.deleted} ` +
    `unit=${initial.unit.buildTimeoutMinutes}/${initial.unit.deployTimeoutMinutes}/${initial.unit.batchOrder}/` +
    `${initial.unit.envChecksEnabled ? 'on' : 'off'} configured=${initial.unit.configured} ` +
    `lib=${initial.lib.buildTimeoutMinutes}/${initial.lib.deployTimeoutMinutes}/${initial.lib.batchOrder} ` +
    `configured=${initial.lib.configured}`
)
if (!initial.deleted) {
  console.log('note: mysql client unavailable — the "never saved / 当前为默认配置" assertions are skipped')
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
    const pipelineRequests = []
    page.on('request', (req) => {
      if (req.url().includes('/pipeline-config')) {
        pipelineRequests.push(`${req.method()} ${req.url()}`)
      }
    })
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    const reset = await resetState()
    const fresh = reset.deleted && !reset.unit.configured

    // ------------------------------------------------------------ admin: default render (UNIT)
    await setUser(page, ADMIN)
    await open(page, `/units/${UNIT}`, theme)
    await waitPage(page, '.unit-pipeline .pipeline-build input')
    const initialPanel = await readPanel(page)

    expect(initialPanel.theme === theme, `theme is ${initialPanel.theme}, expected ${theme}`)
    expect(initialPanel.present, 'pipeline block missing')
    expect(initialPanel.title === '流水线配置', `pipeline title (${initialPanel.title})`)
    expect(initialPanel.save, 'save button missing for ADMIN')
    expect(initialPanel.env.present, 'environment-check switch missing for a UNIT')
    expect(initialPanel.env.on, 'environment checks are off by default')
    expect(
      initialPanel.env.checks.map((text) => ENV_CHECKS.find(([label]) => text.includes(label))?.[1]).join('|') ===
        ENV_CHECKS.map(([, value]) => value).join('|'),
      `environment check rows (${initialPanel.env.checks.join(' / ')})`
    )
    expect(
      initialPanel.env.note.includes('停用对在途评估立即生效'),
      `env-check warning missing (${initialPanel.env.note})`
    )
    expect(initialPanel.build === '60', `build default in the page (${initialPanel.build})`)
    expect(initialPanel.deploy === '8', `deploy default in the page (${initialPanel.deploy})`)
    expect(initialPanel.order.present, 'batch-order selector missing for a UNIT')
    expect(
      initialPanel.order.options.map((option) => option.label).join('|') === '顺序|逆序',
      `batch-order options (${initialPanel.order.options.map((option) => option.label).join('|')})`
    )
    expect(initialPanel.order.active === '顺序', `batch-order default (${initialPanel.order.active})`)
    expect(
      initialPanel.order.hint.includes('高风险') && initialPanel.order.hint.includes('先高批后低批'),
      `high-risk hint missing (${initialPanel.order.hint})`
    )
    if (fresh) {
      expect(
        initialPanel.subtitle.includes('当前为默认配置'),
        `default subtitle (${initialPanel.subtitle})`
      )
      expect(
        initialPanel.defaultNote.includes('当前为默认配置'),
        `default note missing (${initialPanel.defaultNote})`
      )
      expect(initialPanel.state === '默认配置', `default state tag (${initialPanel.state})`)
      expect(!initialPanel.audit, `default configuration shows an update echo (${initialPanel.audit})`)
      expect(initialPanel.counts.switches === 1, `switch count (${initialPanel.counts.switches})`)
      assertDefaults(expect, reset.unit, 'unit API', true)
      assertDefaults(expect, reset.lib, 'lib API', true)
    } else {
      expect(initialPanel.build === '60' && initialPanel.deploy === '8', 'defaults not shown')
    }
    expect(
      pipelineRequests.some((entry) => entry.startsWith('GET')),
      `no pipeline-config GET was issued (${pipelineRequests.join(', ')})`
    )

    // ------------------------------------------------------- range guards (page-level rejection)
    await setInput(page, '.pipeline-build input', '4')
    await page.click('.unit-pipeline .pipeline-save')
    expect(
      await waitMessage(page, '构建超时需为 5–240 之间的整数分钟'),
      'build 4 was not rejected by the page'
    )
    expect(await waitMessageGone(page, '构建超时需为 5–240 之间的整数分钟'), 'build 4 toast did not clear')
    expect((await readConfig(UNIT)).buildTimeoutMinutes === 60, 'build 4 reached the API')

    await setInput(page, '.pipeline-build input', '241')
    await page.click('.unit-pipeline .pipeline-save')
    expect(
      await waitMessage(page, '构建超时需为 5–240 之间的整数分钟'),
      'build 241 was not rejected by the page'
    )
    expect(await waitMessageGone(page, '构建超时需为 5–240 之间的整数分钟'), 'build 241 toast did not clear')
    expect((await readConfig(UNIT)).buildTimeoutMinutes === 60, 'build 241 reached the API')

    await setInput(page, '.pipeline-build input', '60')
    await setInput(page, '.pipeline-deploy input', '0')
    await page.click('.unit-pipeline .pipeline-save')
    expect(
      await waitMessage(page, '部署超时需为 1–120 之间的整数分钟'),
      'deploy 0 was not rejected by the page'
    )
    expect(await waitMessageGone(page, '部署超时需为 1–120 之间的整数分钟'), 'deploy 0 toast did not clear')
    expect((await readConfig(UNIT)).deployTimeoutMinutes === 8, 'deploy 0 reached the API')

    await setInput(page, '.pipeline-deploy input', '121')
    await page.click('.unit-pipeline .pipeline-save')
    expect(
      await waitMessage(page, '部署超时需为 1–120 之间的整数分钟'),
      'deploy 121 was not rejected by the page'
    )
    expect(await waitMessageGone(page, '部署超时需为 1–120 之间的整数分钟'), 'deploy 121 toast did not clear')
    expect((await readConfig(UNIT)).deployTimeoutMinutes === 8, 'deploy 121 reached the API')

    // --------------------------------------------------------- save 15/3 and read the echo back
    await setInput(page, '.pipeline-build input', '15')
    await setInput(page, '.pipeline-deploy input', '3')
    await page.click('.unit-pipeline .pipeline-save')
    expect(await waitMessage(page, '流水线配置已保存'), 'save toast missing')
    await sleep(300)
    const savedApi = await readConfig(UNIT)
    expect(
      savedApi.buildTimeoutMinutes === 15 && savedApi.deployTimeoutMinutes === 3,
      `API timeouts after save (${savedApi.buildTimeoutMinutes}/${savedApi.deployTimeoutMinutes})`
    )
    expect(savedApi.configured === true, 'API still reports configured:false after a save')
    expect(savedApi.updatedBy === ADMIN, `API updatedBy after save (${savedApi.updatedBy})`)
    const savedPanel = await readPanel(page)
    expect(savedPanel.build === '15' && savedPanel.deploy === '3', 'page does not echo 15/3')
    expect(
      savedPanel.subtitle.includes('构建 15 / 部署 3 分钟 · 顺序'),
      `subtitle after save (${savedPanel.subtitle})`
    )
    expect(savedPanel.audit.includes('最近保存：dev'), `update echo missing (${savedPanel.audit})`)
    expect(savedPanel.state === '已保存', `state tag after save (${savedPanel.state})`)
    expect(!savedPanel.defaultNote, 'default note still shown after a save')

    // -------------------------------------------- batch order: REVERSE (confirm) then FORWARD
    expect(await waitMessageGone(page, '流水线配置已保存'), 'save toast did not clear')
    expect(await clickOrder(page, '逆序'), 'could not select 逆序')
    await sleep(200)
    await page.click('.unit-pipeline .pipeline-save')
    expect(await waitMessageBox(page, '保存流水线配置'), 'reverse confirmation did not appear')
    const confirmText = await messageBoxText(page, '保存流水线配置')
    expect(
      (confirmText ?? '').includes('高风险：先高批后低批'),
      `reverse confirmation wording (${confirmText})`
    )
    expect(
      await clickMessageBoxButton(page, '保存流水线配置', '确认逆序'),
      'could not confirm 逆序'
    )
    expect(await waitMessage(page, '流水线配置已保存'), 'reverse save toast missing')
    await sleep(300)
    const reversedApi = await readConfig(UNIT)
    expect(reversedApi.batchOrder === 'REVERSE', `API batch order after reverse (${reversedApi.batchOrder})`)
    const reversedPanel = await readPanel(page)
    expect(reversedPanel.order.active === '逆序', `page batch order after reverse (${reversedPanel.order.active})`)
    expect(reversedPanel.subtitle.includes('逆序'), `subtitle after reverse (${reversedPanel.subtitle})`)

    expect(await waitMessageGone(page, '流水线配置已保存'), 'reverse toast did not clear')
    expect(await clickOrder(page, '顺序'), 'could not select 顺序')
    await sleep(200)
    await page.click('.unit-pipeline .pipeline-save')
    const reConfirm = await clickMessageBoxButton(page, '保存流水线配置', '确认逆序', 1200)
    expect(!reConfirm, 'confirmation appeared when switching back to 顺序')
    expect(await waitMessage(page, '流水线配置已保存'), 'forward save toast missing')
    await sleep(300)
    const forwardApi = await readConfig(UNIT)
    expect(forwardApi.batchOrder === 'FORWARD', `API batch order after forward (${forwardApi.batchOrder})`)

    // ------------------------------------------------------------------- LIB (91001) differences
    await open(page, `/units/${LIB}`, theme)
    await waitPage(page, '.unit-pipeline .pipeline-build input')
    const libPanel = await readPanel(page)
    expect(libPanel.present && libPanel.title === '流水线配置', 'LIB pipeline block missing')
    expect(!libPanel.env.present, 'LIB shows the environment-check switch')
    expect(!libPanel.order.present, 'LIB shows the batch-order selector')
    expect(libPanel.counts.switches === 0, `LIB switches (${libPanel.counts.switches})`)
    expect(libPanel.counts.deployInputs === 0, `LIB deploy inputs (${libPanel.counts.deployInputs})`)
    expect(libPanel.counts.orderGroups === 0, `LIB order groups (${libPanel.counts.orderGroups})`)
    expect(libPanel.counts.buildInputs === 1, `LIB build inputs (${libPanel.counts.buildInputs})`)
    expect(libPanel.build === '60', `LIB build default (${libPanel.build})`)
    expect(libPanel.libNote.includes('二方库'), `LIB note missing (${libPanel.libNote})`)
    if (fresh) {
      expect(
        libPanel.subtitle.includes('当前为默认配置') && libPanel.defaultNote.includes('当前为默认配置'),
        `LIB default note missing (${libPanel.subtitle} | ${libPanel.defaultNote})`
      )
    }

    await setInput(page, '.pipeline-build input', '90')
    await page.click('.unit-pipeline .pipeline-save')
    expect(await waitMessage(page, '流水线配置已保存'), 'LIB save toast missing')
    await sleep(300)
    const libSaved = await readConfig(LIB)
    expect(libSaved.buildTimeoutMinutes === 90, `LIB build after save (${libSaved.buildTimeoutMinutes})`)
    expect(libSaved.envChecksEnabled === true, 'LIB save flipped the hidden environment switch')
    expect(libSaved.deployTimeoutMinutes === 8, `LIB save changed the hidden deploy timeout (${libSaved.deployTimeoutMinutes})`)
    expect(libSaved.batchOrder === 'FORWARD', `LIB save changed the hidden batch order (${libSaved.batchOrder})`)
    expect((await readPanel(page)).build === '90', 'LIB page does not echo the saved build timeout')

    // ------------------------------------- back to the never-saved defaults, then the screenshot
    const restored = await resetState()
    expect(restored.unit.buildTimeoutMinutes === 60 && restored.unit.deployTimeoutMinutes === 8, 'restore: unit values')
    expect(restored.unit.batchOrder === 'FORWARD' && restored.unit.envChecksEnabled === true, 'restore: unit switches')
    expect(restored.lib.buildTimeoutMinutes === 60, 'restore: LIB build')
    if (initial.deleted) {
      expect(
        restored.deleted,
        'restore used a PUT instead of deleting the row (the default note is not reproducible)'
      )
    }

    await open(page, `/units/${UNIT}`, theme)
    await waitPage(page, '.unit-pipeline .pipeline-build input')
    await sleep(250)
    const finalPanel = await readPanel(page)
    expect(finalPanel.build === '60' && finalPanel.deploy === '8', 'restored page timeouts')
    expect(finalPanel.order.active === '顺序', 'restored page batch order')
    expect(finalPanel.env.on, 'restored page environment checks')
    expect(
      finalPanel.subtitle.includes('当前为默认配置') && finalPanel.defaultNote.includes('当前为默认配置'),
      `restored page default note (${finalPanel.subtitle})`
    )

    if (SHOTS) {
      await page.evaluate(() =>
        document.querySelector('.unit-pipeline')?.scrollIntoView({ block: 'center' })
      )
      await sleep(300)
      const frame = await page.evaluate(() => {
        const block = document.querySelector('.unit-pipeline')?.getBoundingClientRect()
        return { top: block?.top ?? -1, bottom: block?.bottom ?? 9999, height: window.innerHeight }
      })
      expect(
        frame.top >= 0 && frame.bottom <= frame.height,
        `pipeline block outside the screenshot (${Math.round(frame.top)}..${Math.round(frame.bottom)} of ${frame.height})`
      )
      await page.screenshot({ path: join(SHOTS, `unit-pipeline-${theme}.png`) })
    }

    // -------------------------------------------------------------- read-only branch (ops-lead)
    await setUser(page, READONLY)
    pipelineRequests.length = 0
    await open(page, `/units/${UNIT}`, theme)
    await waitPage(page, '.unit-pipeline .restricted')
    await sleep(350)
    const readonly = await readPanel(page)
    expect(readonly.present, 'read-only pipeline block missing')
    expect(readonly.restricted.includes('需要平台管理员权限'), 'read-only reason missing')
    expect(readonly.restricted.includes('setting:manage'), 'read-only permission name missing')
    expect(!readonly.save && readonly.counts.saves === 0, 'save button shown to a read-only user')
    expect(readonly.counts.switches === 0, 'environment switch shown to a read-only user')
    expect(readonly.counts.buildInputs === 0 && readonly.counts.deployInputs === 0, 'inputs shown to a read-only user')
    expect(readonly.counts.orderGroups === 0, 'batch-order selector shown to a read-only user')
    expect(readonly.subtitle === '仅平台管理员可见', `read-only subtitle (${readonly.subtitle})`)
    expect(readonly.state === '只读', `read-only state tag (${readonly.state})`)
    expect(
      pipelineRequests.length === 0,
      `read-only page requested pipeline-config (${pipelineRequests.join(', ')})`
    )

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6c-${theme}: unit=${initialPanel.build}/${initialPanel.deploy}/${initialPanel.order.active}/` +
        `env=${initialPanel.env.on ? 'on' : 'off'} guards=build4/241,deploy0/121 saved=15/3 ` +
        `reverse=REVERSE->FORWARD lib=build90(hidden ok) ` +
        `restored=${finalPanel.build}/${finalPanel.deploy}/${finalPanel.order.active}/env=` +
        `${finalPanel.env.on ? 'on' : 'off'} readonly=yes pipelineRequests=${pipelineRequests.length} ` +
        `errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 3).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  // Leave the documented defaults even when an assertion (or a page) failed above: delete the row so
  // later screenshots keep showing "当前为默认配置"; a present mysql client falls back to a PUT.
  try {
    const restored = await resetState()
    lines.push(
      `restored: rowDeleted=${restored.deleted} ` +
        `unit=${restored.unit.buildTimeoutMinutes}/${restored.unit.deployTimeoutMinutes}/` +
        `${restored.unit.batchOrder}/env=${restored.unit.envChecksEnabled ? 'on' : 'off'} ` +
        `configured=${restored.unit.configured} ` +
        `lib=${restored.lib.buildTimeoutMinutes}/${restored.lib.deployTimeoutMinutes}/${restored.lib.batchOrder}`
    )
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
console.log('OK all P6-C pipeline-config assertions passed')