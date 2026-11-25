// Asserts the P6-E 「审批时效」 settings block in a real browser, in both themes, and leaves the
// documented defaults (enabled=true / 12 / 24) behind so later screenshots stay on them:
//
//   * 超时提醒 block renders after 锁超时: switch, 提醒阈值 and 升级阈值, the 12/24 defaults filled
//     from `GET /api/v1/settings/approval`, the 保存 button enabled, and the reminder/escalation
//     copy (提醒发给当前待签审批人；升级发给 ADMIN 与系统负责人；升级后不再提醒);
//   * client-side guards: remind 0 rejects (1–72), escalate ≤ remind rejects, escalate 169 rejects
//     (2–168) — each with the reason visible inside the block and 保存 disabled, and no PUT leaves;
//   * a real save of 6/12 comes back through the API and re-renders (chip 已保存, subtitle, audit
//     line with updatedBy/updatedAt); turning the switch off saves enabled=false (chip 已关闭);
//     the last save puts enabled=true / 12 / 24 back through the UI;
//   * read-only: with `ops-lead` (no `setting:manage`) the block shows the permission reason, renders
//     no controls, and issues no request to the endpoint;
//   * 0 console errors.
//
//   node scripts/verify-p6e-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6e-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes settings-approval-{light,dark}.png（审批时效块占满视口顶部）. Run from the
// repository root so the relative --shots path lands in the repo; the probe restores the defaults
// before the run and again in a `finally` block, so it is safe to re-run.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p6e-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6e-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const ADMIN = 'dev'
const READONLY = 'ops-lead'
const DEFAULTS = { enabled: true, remindHours: 12, escalateHours: 24 }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

// ------------------------------------------------------------------ API helpers (restore + assert)

async function readApproval(userId = ADMIN) {
  const res = await fetch(`${BASE}/api/v1/settings/approval`, {
    headers: { 'X-User-Id': userId }
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`GET approval failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

async function writeApproval(payload, userId = ADMIN) {
  const res = await fetch(`${BASE}/api/v1/settings/approval`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: JSON.stringify(payload)
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`PUT approval failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

/** The V33 seed: enabled, 12h reminder, 24h escalation. Written and read back so a drifted DB heals. */
async function restoreDefaults() {
  await writeApproval(DEFAULTS)
  const view = await readApproval()
  const ok =
    view.enabled === DEFAULTS.enabled &&
    view.remindHours === DEFAULTS.remindHours &&
    view.escalateHours === DEFAULTS.escalateHours
  if (!ok) {
    throw new Error(
      `restore mismatch: enabled=${view.enabled} remind=${view.remindHours} escalate=${view.escalateHours}`
    )
  }
  return view
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

/** Waits for the 设置 header and the requested state of the block, then gives async blocks a beat. */
async function waitPage(page, selector) {
  await page.waitForFunction(
    (text) => (document.querySelector('.page-title')?.textContent ?? '').includes(text),
    { timeout: 20000 },
    '设置'
  )
  await page.waitForSelector(selector, { timeout: 20000 })
  await sleep(350)
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

/** Waits until no visible ElMessage contains `text`, so the next save's toast is unambiguous. */
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

/** Reads everything the assertions need in one in-page pass. */
function readApprovalBlock(page) {
  return page.evaluate(() => {
    const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()
    const block = document.querySelector('.settings-approval')
    const switchEl = block?.querySelector('.approval-enabled')
    const save = block?.querySelector('.approval-save')
    const invalid = block?.querySelector('.approval-invalid')
    return {
      present: !!block,
      title: flat(block?.querySelector('.panel-title')?.textContent),
      subtitle: flat(block?.querySelector('.panel-head > .weak.small')?.textContent),
      chip: flat(block?.querySelector('.chip')?.textContent),
      // ElSwitch marks itself with `is-checked` and mirrors the value into aria-checked.
      enabled: switchEl
        ? switchEl.classList.contains('is-checked') ||
          switchEl.getAttribute('aria-checked') === 'true'
        : null,
      switchVisible: !!switchEl,
      remind: block?.querySelector('.approval-remind input')?.value ?? '',
      escalate: block?.querySelector('.approval-escalate input')?.value ?? '',
      invalidVisible: !!invalid && invalid.getBoundingClientRect().width > 0,
      invalidText: flat(invalid?.innerText),
      save: !!save,
      saveDisabled: save ? save.disabled : null,
      hint: flat(block?.querySelector('.approval-hint')?.innerText),
      audit: flat(block?.querySelector('.approval-audit')?.innerText),
      restricted: flat(block?.querySelector('.restricted')?.innerText),
      controls: block?.querySelectorAll('.approval-grid input, .approval-grid .el-switch').length ?? 0
    }
  })
}

/** Sets one of the two hour fields the way typing would (input + change events). */
function setHours(page, field, value) {
  return page.evaluate(
    (selector, next) => {
      const input = document.querySelector(`.settings-approval ${selector} input`)
      if (!input) return false
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(input, next)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return input.value === next
    },
    field,
    value
  )
}

function setRemind(page, value) {
  return setHours(page, '.approval-remind', value)
}

function setEscalate(page, value) {
  return setHours(page, '.approval-escalate', value)
}

// ----------------------------------------------------------------------------------- main run

let before = null
try {
  before = await readApproval()
} catch (e) {
  console.error(`cannot read the approval settings: ${e.message}`)
  process.exit(1)
}
console.log(
  `initial GET: enabled=${before.enabled} remind=${before.remindHours} escalate=${before.escalateHours} ` +
    `updatedBy=${before.updatedBy ?? '-'}`
)

try {
  await restoreDefaults()
  console.log('defaults: enabled=true remind=12 escalate=24')
} catch (e) {
  console.error(`cannot restore the defaults: ${e.message}`)
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
    const approvalRequests = []
    const approvalPuts = []
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/settings/approval')) {
        approvalRequests.push(`${req.method()} ${req.url()}`)
        if (req.method() === 'PUT') approvalPuts.push(req.url())
      }
    })
    const fails = []
    const expect = (ok, message) => {
      if (!ok) fails.push(message)
    }

    await restoreDefaults()

    // ---------------------------------------------------------------- admin: default render
    await setUser(page, ADMIN)
    await open(page, '/settings', theme)
    await waitPage(page, '.settings-approval .approval-remind input')
    await sleep(300)
    const initial = await readApprovalBlock(page)

    expect(initial.present, '审批时效 block missing')
    expect(initial.title === '审批时效', `block title (${initial.title})`)
    expect(initial.remind === '12', `default 提醒阈值 (${initial.remind})`)
    expect(initial.escalate === '24', `default 升级阈值 (${initial.escalate})`)
    expect(initial.enabled === true, `default switch state (${initial.enabled})`)
    expect(initial.save, '保存 button missing for ADMIN')
    expect(initial.saveDisabled === false, '保存 is disabled on the default values')
    expect(!initial.invalidVisible, `invalid reason shown on defaults (${initial.invalidText})`)
    expect(
      initial.subtitle.includes('提醒 12 小时') && initial.subtitle.includes('升级 24 小时'),
      `subtitle defaults (${initial.subtitle})`
    )
    expect(
      initial.hint.includes('提醒发给当前待签审批人') &&
        initial.hint.includes('ADMIN 与系统负责人') &&
        initial.hint.includes('升级后不再提醒'),
      `reminder/escalation copy missing (${initial.hint})`
    )
    expect(approvalRequests.some((r) => r.startsWith('GET')), 'the block did not GET the setting')

    // ---------------------------------------------- guard: remind 0 (1–72), nothing is saved
    const remindSet = await setRemind(page, '0')
    expect(remindSet, 'could not type the remind threshold')
    await sleep(200)
    const afterZero = await readApprovalBlock(page)
    expect(afterZero.invalidVisible, 'remind 0 shows no visible reason')
    expect(afterZero.invalidText.includes('1–72'), `remind 0 reason (${afterZero.invalidText})`)
    expect(afterZero.saveDisabled === true, '保存 stays enabled with remind 0')
    const putsBefore = approvalPuts.length
    expect(putsBefore === 0, `a PUT left the page on remind 0 (${approvalPuts.join(', ')})`)
    const apiAfterZero = await readApproval()
    expect(
      apiAfterZero.remindHours === 12 && apiAfterZero.escalateHours === 24,
      `API changed on remind 0 (${apiAfterZero.remindHours}/${apiAfterZero.escalateHours})`
    )

    // ------------------------------------------- guard: escalate 12 ≤ remind 12, still blocked
    await setRemind(page, '12')
    const escalateEq = await setEscalate(page, '12')
    expect(escalateEq, 'could not type the escalate threshold')
    await sleep(200)
    const afterEqual = await readApprovalBlock(page)
    expect(afterEqual.invalidVisible, 'escalate ≤ remind shows no visible reason')
    expect(
      afterEqual.invalidText.includes('必须大于提醒阈值'),
      `escalate ≤ remind reason (${afterEqual.invalidText})`
    )
    expect(afterEqual.saveDisabled === true, '保存 stays enabled with escalate ≤ remind')
    expect(approvalPuts.length === 0, 'a PUT left the page with escalate ≤ remind')

    // ------------------------------------------------ guard: escalate 169 (2–168), still blocked
    const escalateHigh = await setEscalate(page, '169')
    expect(escalateHigh, 'could not type the high escalate threshold')
    await sleep(200)
    const afterHigh = await readApprovalBlock(page)
    expect(afterHigh.invalidVisible, 'escalate 169 shows no visible reason')
    expect(afterHigh.invalidText.includes('2–168'), `escalate 169 reason (${afterHigh.invalidText})`)
    expect(afterHigh.saveDisabled === true, '保存 stays enabled with escalate 169')
    expect(approvalPuts.length === 0, 'a PUT left the page with escalate 169')

    // -------------------------------------------------- valid 6/12 saves and re-renders
    await setRemind(page, '6')
    await setEscalate(page, '12')
    await sleep(200)
    const valid = await readApprovalBlock(page)
    expect(!valid.invalidVisible, `invalid reason still shown for 6/12 (${valid.invalidText})`)
    expect(valid.saveDisabled === false, '保存 is disabled for the legal 6/12 values')
    await page.click('.settings-approval .approval-save')
    expect(await waitMessage(page, '审批时效设置已保存'), 'save toast missing for 6/12')
    await sleep(300)
    const saved = await readApprovalBlock(page)
    const apiSaved = await readApproval()
    expect(
      apiSaved.enabled === true && apiSaved.remindHours === 6 && apiSaved.escalateHours === 12,
      `API after 6/12 save (${apiSaved.enabled}/${apiSaved.remindHours}/${apiSaved.escalateHours})`
    )
    expect(saved.remind === '6' && saved.escalate === '12', `page after 6/12 save (${saved.remind}/${saved.escalate})`)
    expect(saved.chip === '已保存', `chip after save (${saved.chip})`)
    expect(saved.subtitle.includes('提醒 6 小时') && saved.subtitle.includes('升级 12 小时'), `subtitle after save (${saved.subtitle})`)
    expect(saved.audit.includes(ADMIN), `audit line updatedBy (${saved.audit})`)
    expect(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(saved.audit), `audit line timestamp (${saved.audit})`)

    // --------------------------------------------------- switch off saves enabled=false
    await waitMessageGone(page, '审批时效设置已保存')
    await page.click('.settings-approval .approval-enabled')
    await sleep(200)
    const offDraft = await readApprovalBlock(page)
    expect(offDraft.enabled === false, 'the switch did not toggle off')
    await page.click('.settings-approval .approval-save')
    expect(await waitMessage(page, '审批时效设置已保存'), 'save toast missing for enabled=false')
    await sleep(300)
    const apiOff = await readApproval()
    const off = await readApprovalBlock(page)
    expect(apiOff.enabled === false, `API enabled after switch off (${apiOff.enabled})`)
    expect(off.chip === '已关闭', `chip after switch off (${off.chip})`)
    expect(off.subtitle.includes('已关闭'), `subtitle after switch off (${off.subtitle})`)

    // -------------------------------------- switch on + 12/24: back to the documented defaults
    await waitMessageGone(page, '审批时效设置已保存')
    await page.click('.settings-approval .approval-enabled')
    await setRemind(page, '12')
    await setEscalate(page, '24')
    await sleep(200)
    const backDraft = await readApprovalBlock(page)
    expect(backDraft.enabled === true, 'the switch did not toggle back on')
    expect(backDraft.saveDisabled === false, '保存 is disabled on 12/24')
    await page.click('.settings-approval .approval-save')
    expect(await waitMessage(page, '审批时效设置已保存'), 'save toast missing for the 12/24 restore')
    await sleep(400)
    const restoredUi = await readApprovalBlock(page)
    const apiRestored = await readApproval()
    expect(
      apiRestored.enabled === true && apiRestored.remindHours === 12 && apiRestored.escalateHours === 24,
      `API after the 12/24 restore (${apiRestored.enabled}/${apiRestored.remindHours}/${apiRestored.escalateHours})`
    )
    expect(
      restoredUi.enabled === true && restoredUi.remind === '12' && restoredUi.escalate === '24',
      `page after the 12/24 restore (${restoredUi.enabled}/${restoredUi.remind}/${restoredUi.escalate})`
    )
    expect(restoredUi.subtitle.includes('提醒 12 小时') && restoredUi.subtitle.includes('升级 24 小时'), `restored subtitle (${restoredUi.subtitle})`)

    // ---------------------------------------------------------------- screenshot
    if (SHOTS) {
      await page.evaluate(() => document.querySelector('.settings-approval')?.scrollIntoView({ block: 'start' }))
      await sleep(350)
      const frame = await page.evaluate(() => {
        const block = document.querySelector('.settings-approval')?.getBoundingClientRect()
        return { top: block?.top ?? -1, bottom: block?.bottom ?? 9999, height: window.innerHeight }
      })
      expect(
        frame.top >= -1 && frame.bottom <= frame.height + 1,
        `审批时效 block outside the screenshot (${Math.round(frame.top)}..${Math.round(frame.bottom)} of ${frame.height})`
      )
      await page.screenshot({ path: join(SHOTS, `settings-approval-${theme}.png`) })
    }

    // -------------------------------------------------------------- read-only branch
    await setUser(page, READONLY)
    approvalRequests.length = 0
    await open(page, '/settings', theme)
    await waitPage(page, '.settings-approval .restricted')
    await sleep(400)
    const readonly = await readApprovalBlock(page)
    expect(readonly.present, '审批时效 block missing for a read-only user')
    expect(readonly.restricted.includes('需要平台管理员权限'), 'read-only reason missing')
    expect(readonly.restricted.includes('setting:manage'), 'read-only perm name missing')
    expect(!readonly.save, '保存 button shown to a read-only user')
    expect(readonly.switchVisible === false, 'switch shown to a read-only user')
    expect(readonly.controls === 0, `editable controls shown to a read-only user (${readonly.controls})`)
    expect(readonly.remind === '' && readonly.escalate === '', 'thresholds rendered to a read-only user')
    expect(approvalRequests.length === 0, `read-only page requested the endpoint (${approvalRequests.join(', ')})`)

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6e-${theme}: block=审批时效 defaults=${initial.remind}/${initial.escalate} ` +
        `guard=0/12eq/169 puts=${approvalPuts.length} ` +
        `saved=${apiSaved.remindHours}/${apiSaved.escalateHours} ` +
        `off=${apiOff.enabled} restored=${apiRestored.enabled}/${apiRestored.remindHours}/${apiRestored.escalateHours} ` +
        `readonly=yes approvalRequests=${approvalRequests.length} errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 5).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  // Leave the documented defaults even when an assertion (or a page) failed above: later
  // screenshots must show enabled / 12 / 24, not whatever the probe last typed.
  try {
    const restored = await restoreDefaults()
    lines.push(
      `restored: enabled=${restored.enabled} remind=${restored.remindHours} escalate=${restored.escalateHours}`
    )
    if (!restored.enabled || restored.remindHours !== 12 || restored.escalateHours !== 24) {
      failures++
      lines.push('RESTORE FAILED: the approval settings are not back on enabled / 12 / 24')
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
console.log('OK all P6-E approval-reminder settings assertions passed')