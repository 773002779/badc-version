// Asserts the P6-D (J11) 紧急放行 door in a real browser, in both themes, and leaves the demo order
// back in PENDING_APPROVAL so later screenshots keep showing the pre-release state:
//
//   * seed: scripts/demo-emergency.sql 造 PO-DEMO-EMERGENCY（PENDING_APPROVAL + 两条 PENDING 审批 +
//     实例 PAUSED 在 PROD_DEPLOY），本脚本每次运行前、每个主题前、以及结束时都重播种；
//   * ADMIN (dev) sees the red 紧急放行 button enabled on the eligible order; its computed background
//     is the --danger token of the current theme (#d92d20 light / #f97066 dark);
//   * ops-lead (OPS + OPS_LEAD, never ADMIN) sees no button at all — not a disabled one — and issues
//     no POST to the emergency endpoint;
//   * READY order: ADMIN sees the same door disabled with the reason in its title (不影响其它状态)；
//   * the confirm dialog is a ReasonDialog with a red (error) warning, a danger confirm button, and
//     the empty reason leaves 确认紧急放行 disabled;
//   * a real POST: one real emergency release happens on the seeded order (toast, banner with
//     reason/operator/time, admission 审批链已作废 + both steps CANCELED with the 紧急放行 comment,
//     button now disabled), then the API and MySQL are read back (four order columns + one
//     emergency_release row + two CANCELED approvals);
//   * the 发布记录 row shows the red 紧急放行 chip; hovering it reveals 理由/操作人/time;
//   * 0 console errors;
//   * --shots writes emergency-{light,dark}.png（全页，横幅与作废审批链同图可见）.
//
//   node scripts/verify-p6d-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6d-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// Run from the repository root: the --shots path and scripts/demo-emergency.sql are resolved from
// the current working directory. The repo has no root package.json for tooling, so puppeteer-core is
// only resolvable when this script sits in a scratch dir that has the dependency (ESM ignores
// NODE_PATH):
//   copy scripts\verify-p6d-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6d-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { spawnSync } from 'node:child_process'
import { access, mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const ORDER = 'PO-DEMO-EMERGENCY' // seeded by scripts/demo-emergency.sql
const READY_ORDER = 'PO-DEMO-TODO-EXECUTE' // an already-READY demo order (P5-C seed)
const ADMIN = 'dev'
const READONLY = 'ops-lead'
const REASON = '线上支付故障，紧急放行修复（P6-D 探针）'

const SQL = join(process.cwd(), 'scripts', 'demo-emergency.sql')
const DANGER = { light: 'rgb(217, 45, 32)', dark: 'rgb(249, 112, 102)' }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

// ---------------------------------------------------------------- seed / database

function mysql(args) {
  return spawnSync('mysql', args, {
    encoding: 'utf8',
    timeout: 30000,
    env: { ...process.env, MYSQL_PWD: 'root' }
  })
}

/** Re-runs the idempotent demo seed; the probe cannot run without it. */
function reseed() {
  const res = mysql([
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    '--default-character-set=utf8mb4',
    `--execute=source ${SQL.replace(/\\/g, '/')}`
  ])
  if (res.error || res.status !== 0) {
    throw new Error(
      `cannot re-seed ${SQL}: ${res.error?.message ?? (res.stderr || `exit ${res.status}`)}`
    )
  }
  return res.stdout
}

/** Reads the trail straight from MySQL (the API has no endpoint for the history table). */
function readDbState() {
  const res = mysql([
    '--host=127.0.0.1',
    '--user=root',
    '--database=release_pipeline',
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names',
    '--execute=SELECT emergency_release, ' +
      '(SELECT COUNT(*) FROM emergency_release WHERE order_id = 92001), ' +
      '(SELECT COUNT(*) FROM approval WHERE order_id = 92001 AND status = \'CANCELED\') ' +
      'FROM release_order WHERE id = 92001'
  ])
  if (res.error || res.status !== 0) {
    throw new Error(`cannot read the demo order: ${res.error?.message ?? res.stderr}`)
  }
  const [flag, history, canceled] = (res.stdout ?? '').trim().split('\t')
  return { flag, history: Number(history), canceled: Number(canceled) }
}

// ------------------------------------------------------------------- API helpers

async function api(path, { method = 'GET', body, userId = ADMIN } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const payload = await res.json().catch(() => null)
  if (!res.ok || !payload || payload.code !== 0) {
    throw new Error(
      `${method} ${path} failed: ${payload?.message ?? `HTTP ${res.status}`}`
    )
  }
  return payload.data
}

const readDetail = (publishNo, userId = ADMIN) => api(`/api/v1/releases/${publishNo}`, { userId })
const readAdmission = (publishNo, userId = ADMIN) =>
  api(`/api/v1/releases/${publishNo}/admission`, { userId })

// ------------------------------------------------------------------- page helpers

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

async function waitPage(page, selector) {
  await page.waitForSelector('.page-title', { timeout: 20000 })
  if (selector) await page.waitForSelector(selector, { timeout: 20000 })
  await sleep(350)
}

/** Waits until a visible ElMessage contains `text`; false when it never appears. */
async function waitMessage(page, text, timeout = 8000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        Array.from(document.querySelectorAll('.el-message')).some(
          (node) =>
            node.getBoundingClientRect().width > 0 && (node.textContent ?? '').includes(wanted)
        ),
      { timeout, polling: 100 },
      text
    )
    return true
  } catch {
    return false
  }
}

async function waitMessageGone(page, text, timeout = 6000) {
  try {
    await page.waitForFunction(
      (wanted) =>
        !Array.from(document.querySelectorAll('.el-message')).some(
          (node) =>
            node.getBoundingClientRect().width > 0 && (node.textContent ?? '').includes(wanted)
        ),
      { timeout, polling: 100 },
      text
    )
    return true
  } catch {
    return false
  }
}

/** The 紧急放行 door of the page header, plus the red banner when it is released. */
function readDoor(page) {
  return page.evaluate(() => {
    const button = document.querySelector('.emergency-release')
    const banner = document.querySelector('.emergency-banner')
    const style = button ? getComputedStyle(button) : null
    return {
      present: !!button,
      disabled: button ? button.disabled : null,
      title: button?.getAttribute('title') ?? '',
      text: (button?.textContent ?? '').replace(/\s+/g, ' ').trim(),
      classes: button?.className ?? '',
      background: style?.backgroundColor ?? '',
      color: style?.color ?? '',
      banner: !!banner,
      bannerText: (banner?.innerText ?? '').replace(/\s+/g, ' ').trim()
    }
  })
}

/** The emergency confirmation dialog: red warning, danger confirm, required reason. */
function readEmergencyDialog(page) {
  return page.evaluate(() => {
    const dialog = document.querySelector('.reason-dialog')
    const alert = dialog?.querySelector('.el-alert')
    const icon = alert?.querySelector('.el-alert__icon')
    const confirm = dialog?.querySelector('.reason-confirm')
    return {
      present: !!dialog && dialog.getBoundingClientRect().width > 0,
      title: (dialog?.querySelector('.el-dialog__title')?.textContent ?? '').trim(),
      alertClasses: alert?.className ?? '',
      alertText: (alert?.innerText ?? '').replace(/\s+/g, ' ').trim(),
      alertIconColor: icon ? getComputedStyle(icon).color : '',
      confirmClasses: confirm?.className ?? '',
      confirmDisabled: confirm ? confirm.disabled : null,
      textarea: !!dialog?.querySelector('textarea')
    }
  })
}

/** The 生产准入 panel of the PROD_DEPLOY node, after the emergency release. */
function readAdmissionPanel(page) {
  return page.evaluate(() => {
    const root = document.querySelector('.admission-panel')
    const chain = Array.from(root?.querySelectorAll('.chain-row') ?? []).map((row) => ({
      classes: row.className,
      text: (row.innerText ?? '').replace(/\s+/g, ' ').trim(),
      canceled: !!row.querySelector('.st-canceled'),
      pending: !!row.querySelector('.st-pending')
    }))
    return {
      present: !!root,
      voidText: (root?.querySelector('.admission-emergency')?.innerText ?? '')
        .replace(/\s+/g, ' ')
        .trim(),
      chain,
      canceled: root?.querySelectorAll('.chain-row .st-canceled').length ?? 0,
      pending: root?.querySelectorAll('.chain-row .st-pending').length ?? 0,
      executeButtons: Array.from(root?.querySelectorAll('.actions button') ?? []).map((b) =>
        (b.textContent ?? '').replace(/\s+/g, ' ').trim()
      )
    }
  })
}

/** Types the reason into the shared ReasonDialog the way a real textarea input event would. */
function typeReason(page, reason) {
  return page.evaluate((wanted) => {
    const textarea = document.querySelector('.reason-dialog textarea')
    if (!textarea) return false
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
    setter.call(textarea, wanted)
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    return textarea.value === wanted
  }, reason)
}

async function waitConfirmEnabled(page, timeout = 5000) {
  try {
    await page.waitForFunction(
      () => {
        const button = document.querySelector('.reason-dialog .reason-confirm')
        return !!button && !button.disabled
      },
      { timeout, polling: 100 }
    )
    return true
  } catch {
    return false
  }
}

// ----------------------------------------------------------------------------------- main run

try {
  await access(SQL)
} catch {
  console.error(`cannot find the demo seed at ${SQL} (run from the repository root)`)
  process.exit(1)
}

try {
  reseed()
  console.log(`seed: ${ORDER} reset to PENDING_APPROVAL`)
} catch (e) {
  console.error(e.message)
  process.exit(1)
}

const seedAdmission = await readAdmission(ORDER)
const seedDetail = await readDetail(ORDER)
console.log(
  `initial state: itemStatus=${seedAdmission.itemStatus} chain=${seedAdmission.chain
    .map((s) => s.status)
    .join('/')} emergencyRelease=${seedDetail.emergencyRelease}`
)

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
    reseed()

    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 900 })
    const errors = []
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
    page.on('pageerror', (e) => errors.push(e.message))
    const emergencyPosts = []
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/emergency-release')) {
        emergencyPosts.push(req.url())
      }
    })

    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    // ------------------------------------------------------- 1. non-ADMIN sees no door
    await setUser(page, READONLY)
    await open(page, `/releases/${ORDER}`, theme)
    await waitPage(page, '.admission-panel')
    const readOnlyDoor = await readDoor(page)
    expect(!readOnlyDoor.present, 'ops-lead sees the 紧急放行 button')
    expect(!readOnlyDoor.banner, 'ops-lead sees an emergency banner')
    expect(emergencyPosts.length === 0, `ops-lead issued a POST (${emergencyPosts.join(', ')})`)

    // ---------------------------------------------- 2. ADMIN: eligible red door + dialog
    await setUser(page, ADMIN)
    await open(page, `/releases/${ORDER}`, theme)
    await waitPage(page, '.emergency-release')
    const door = await readDoor(page)
    expect(door.present, 'ADMIN does not see the 紧急放行 button')
    expect(door.text === '紧急放行', `door label (${door.text})`)
    expect(door.disabled === false, 'the eligible door is disabled')
    expect(door.classes.includes('el-button--danger'), `door is not danger-toned (${door.classes})`)
    expect(
      door.background === DANGER[theme],
      `door background is not the --danger token (${door.background}, want ${DANGER[theme]})`
    )
    expect(door.title.includes('仅管理员'), `door title (${door.title})`)
    expect(!door.banner, 'banner shown before the release')

    await page.click('.emergency-release')
    await page.waitForSelector('.reason-dialog textarea', { timeout: 10000 })
    await sleep(250)
    const dialog = await readEmergencyDialog(page)
    expect(dialog.present, 'emergency confirmation dialog did not open')
    expect(dialog.title === '紧急放行', `dialog title (${dialog.title})`)
    expect(dialog.alertClasses.includes('el-alert--error'), `dialog warning is not red (${dialog.alertClasses})`)
    expect(
      dialog.alertIconColor === DANGER[theme],
      `dialog warning icon is not the --danger token (${dialog.alertIconColor})`
    )
    expect(dialog.alertText.includes('不可撤销'), `dialog warning wording (${dialog.alertText})`)
    expect(dialog.alertText.includes('门禁'), `dialog warning hides the remaining checks (${dialog.alertText})`)
    expect(dialog.confirmClasses.includes('el-button--danger'), 'confirm button is not danger-toned')
    expect(dialog.confirmDisabled === true, 'confirm button is enabled with an empty reason')
    expect(dialog.textarea, 'reason textarea missing')

    expect(await typeReason(page, REASON), 'could not type the emergency reason')
    expect(await waitConfirmEnabled(page), 'confirm button stayed disabled after the reason')

    // -------------------------------------------------- 3. the real emergency release
    await page.click('.reason-dialog .reason-confirm')
    expect(await waitMessage(page, '已紧急放行'), 'success toast missing')
    await page.waitForSelector('.emergency-banner', { timeout: 15000 })
    await page.waitForSelector('.admission-panel .chain-row .st-canceled', { timeout: 15000 })
    await sleep(500)

    const released = await readDoor(page)
    expect(released.banner, 'emergency banner missing after the release')
    expect(released.bannerText.includes('已紧急放行'), `banner badge (${released.bannerText})`)
    expect(released.bannerText.includes(REASON), `banner reason (${released.bannerText})`)
    expect(released.bannerText.includes(ADMIN), `banner operator (${released.bannerText})`)
    expect(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(released.bannerText), `banner time (${released.bannerText})`)
    expect(released.disabled === true, 'door is still enabled after the release')
    expect(released.title.includes('已紧急放行'), `door title after the release (${released.title})`)

    const panel = await readAdmissionPanel(page)
    expect(panel.present, 'admission panel missing')
    expect(panel.voidText.includes('审批链已作废'), `审批链已作废 note (${panel.voidText})`)
    expect(panel.canceled === 2, `canceled steps (${panel.canceled})`)
    expect(panel.pending === 0, `pending steps remain (${panel.pending})`)
    expect(
      panel.chain.every((row) => row.text.includes('紧急放行') && row.text.includes(REASON)),
      `canceled chain comments (${panel.chain.map((row) => row.text).join(' | ')})`
    )
    expect(
      panel.executeButtons.some((label) => label.includes('执行生产发布')),
      `execute button label (${panel.executeButtons.join(', ')})`
    )

    // ------------------------------------------------------- 4. API + MySQL read-back
    const detail = await readDetail(ORDER)
    const admission = await readAdmission(ORDER)
    expect(detail.emergencyRelease === 'true', `detail emergencyRelease (${detail.emergencyRelease})`)
    expect(detail.emergencyReason === REASON, `detail emergencyReason (${detail.emergencyReason})`)
    expect(detail.emergencyBy === ADMIN, `detail emergencyBy (${detail.emergencyBy})`)
    expect(!!detail.emergencyAt, `detail emergencyAt (${detail.emergencyAt})`)
    expect(admission.itemStatus === 'READY', `item status (${admission.itemStatus})`)
    expect(admission.orderStatus === 'READY', `order status (${admission.orderStatus})`)
    expect(
      admission.chain.length === 2 && admission.chain.every((step) => step.status === 'CANCELED'),
      `API chain (${admission.chain.map((step) => step.status).join('/')})`
    )
    expect(
      admission.chain.every(
        (step) => (step.comment ?? '').includes('紧急放行') && (step.comment ?? '').includes(REASON)
      ),
      'API chain comments do not carry the reason'
    )
    const db = readDbState()
    expect(db.flag === '1', `release_order.emergency_release (${db.flag})`)
    expect(db.history === 1, `emergency_release history rows (${db.history})`)
    expect(db.canceled === 2, `CANCELED approvals (${db.canceled})`)

    // ------------------------------------------- 5. another status: disabled + title reason
    await open(page, `/releases/${READY_ORDER}`, theme)
    await waitPage(page, '.emergency-release')
    const readyDoor = await readDoor(page)
    expect(readyDoor.present, 'ADMIN loses the door on a READY order')
    expect(readyDoor.disabled === true, 'the door is enabled on a READY order')
    expect(
      readyDoor.title.includes('已经是待执行状态'),
      `READY order title (${readyDoor.title})`
    )

    // ------------------------------------------------------ 6. 发布记录 red chip + hover
    await open(page, `/records?keyword=${ORDER}`, theme)
    await page.waitForSelector('.emergency-chip', { timeout: 20000 })
    await sleep(250)
    const chip = await page.evaluate(() => {
      const node = document.querySelector('.emergency-chip')
      return {
        text: (node?.textContent ?? '').replace(/\s+/g, ' ').trim(),
        classes: node?.className ?? '',
        color: node ? getComputedStyle(node).color : ''
      }
    })
    expect(chip.text === '紧急放行', `record chip label (${chip.text})`)
    expect(chip.classes.includes('chip-danger'), `record chip is not red (${chip.classes})`)
    expect(chip.color === DANGER[theme], `record chip color (${chip.color})`)

    await page.hover('.emergency-chip')
    let tooltip = false
    try {
      await page.waitForFunction(
        (reason) =>
          Array.from(document.querySelectorAll('.el-popper')).some(
            (node) =>
              node.getBoundingClientRect().width > 0 && (node.textContent ?? '').includes(reason)
          ),
        { timeout: 5000, polling: 100 },
        REASON
      )
      tooltip = true
    } catch {
      tooltip = false
    }
    const tooltipText = tooltip
      ? await page.evaluate(
          (reason) =>
            Array.from(document.querySelectorAll('.el-popper'))
              .filter((node) => node.getBoundingClientRect().width > 0)
              .map((node) => (node.textContent ?? '').replace(/\s+/g, ' ').trim())
              .find((text) => text.includes(reason)) ?? '',
          REASON
        )
      : ''
    expect(tooltip, 'record chip hover shows no reason tooltip')
    expect(tooltipText.includes(ADMIN), `record chip tooltip operator (${tooltipText})`)

    // ---------------------------------------------------------------- 7. screenshot
    await open(page, `/releases/${ORDER}`, theme)
    await page.waitForSelector('.emergency-banner', { timeout: 20000 })
    await page.waitForSelector('.admission-panel .chain-row .st-canceled', { timeout: 20000 })
    await sleep(500)
    const frame = await page.evaluate(() => {
      const banner = document.querySelector('.emergency-banner')?.getBoundingClientRect()
      const panel = document.querySelector('.admission-panel')?.getBoundingClientRect()
      return {
        banner: banner ? { top: Math.round(banner.top + scrollY), bottom: Math.round(banner.bottom + scrollY) } : null,
        panel: panel ? { top: Math.round(panel.top + scrollY), bottom: Math.round(panel.bottom + scrollY) } : null,
        height: document.documentElement.scrollHeight
      }
    })
    expect(frame.banner !== null, 'screenshot: banner missing')
    expect(frame.panel !== null, 'screenshot: admission panel missing')
    if (SHOTS) {
      await page.screenshot({ path: join(SHOTS, `emergency-${theme}.png`), fullPage: true })
    }

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6d-${theme}: readonlyHidden=${!readOnlyDoor.present} ` +
        `doorDanger=${door.background} posts=${emergencyPosts.length} ` +
        `dialogRed=${dialog.alertClasses.includes('el-alert--error')} ` +
        `item=${admission.itemStatus} chain=${admission.chain.map((s) => s.status).join('/')} ` +
        `canceled=${db.canceled} history=${db.history} ` +
        `readyDoorDisabled=${readyDoor.disabled} chip=${chip.color} tooltip=yes ` +
        `frame=${frame.banner?.top}..${frame.panel?.bottom} of ${frame.height} errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  // Always leave the demo order back in PENDING_APPROVAL: later screenshots (and a re-run of this
  // probe) need the eligible pre-release state. The re-seed also deletes the emergency history row.
  try {
    reseed()
    const [detail, admission] = await Promise.all([readDetail(ORDER), readAdmission(ORDER)])
    lines.push(
      `restored: emergencyRelease=${detail.emergencyRelease} itemStatus=${admission.itemStatus} ` +
        `chain=${admission.chain.map((step) => step.status).join('/')}`
    )
    if (detail.emergencyRelease !== 'false' || admission.itemStatus !== 'PENDING_APPROVAL') {
      failures++
      lines.push('RESTORE FAILED: the demo order is not back in PENDING_APPROVAL')
    }
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
console.log('OK all P6-D emergency-release assertions passed')