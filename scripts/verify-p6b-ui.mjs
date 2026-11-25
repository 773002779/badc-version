// Asserts the P6-B platform-settings blocks in a real browser (texts, defaults, interactions and
// the read-only branch), in both themes, then restores the documented defaults through the API:
//
//   * 门禁规则: seven checks rendered by machine value, all enabled by default, the "takes effect
//     immediately / never re-runs" warning, and a save that disables one check (confirm dialog)
//     followed by a save that re-enables it (no dialog) — the API is checked after both;
//   * 审批人名单: both role editors, the "blank = system-owner fallback" hint, adding a tag and
//     saving it, then removing the tag and saving an empty list;
//   * 锁超时: 168 by default, 0 and 8761 rejected in the page (the API keeps 168), a legal save
//     (200) accepted, the "lease stays 4 hours" warning;
//   * read-only: with `ops-lead` (no `setting:manage`) the three blocks show the permission reason,
//     render no controls and issue no request to the three endpoints;
//   * 0 console errors.
//
//   node scripts/verify-p6b-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes settings-rules-{light,dark}.png. Run from the repository root so the relative
// --shots path lands in the repo. The probe restores its defaults (7 checks / empty lists / 168)
// before the run and in a `finally` block, so it is safe to re-run and never leaves a test value
// behind.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p6b-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const ALL_CHECKS = [
  'SONAR',
  'COMPONENT_VULN',
  'ARCHITECTURE',
  'VERSION_DEFECT',
  'SECURITY_DYNAMIC',
  'AUTO_TEST',
  'DB_NORM'
]
const ADMIN = 'dev'
const READONLY = 'ops-lead'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

// ------------------------------------------------------------------ API helpers (restore + assert)

async function readSetting(section, userId = ADMIN) {
  const res = await fetch(`${BASE}/api/v1/settings/${section}`, {
    headers: { 'X-User-Id': userId }
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`GET ${section} failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

async function writeSetting(section, payload, userId = ADMIN) {
  const res = await fetch(`${BASE}/api/v1/settings/${section}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: JSON.stringify(payload)
  })
  const body = await res.json().catch(() => null)
  if (!res.ok || !body || body.code !== 0) {
    throw new Error(`PUT ${section} failed: ${body?.message ?? `HTTP ${res.status}`}`)
  }
  return body.data
}

/** The default of the three sections (V30 seed / design §4). */
async function restoreDefaults() {
  await writeSetting('gate', { enabledChecks: ALL_CHECKS })
  await writeSetting('approvers', { opsLead: [], deptManager: [] })
  await writeSetting('lock', { pauseBoundHours: 168 })
  const [gate, approvers, lock] = await Promise.all([
    readSetting('gate'),
    readSetting('approvers'),
    readSetting('lock')
  ])
  const ok =
    gate.enabledChecks.length === ALL_CHECKS.length &&
    approvers.opsLead.length === 0 &&
    approvers.deptManager.length === 0 &&
    lock.pauseBoundHours === 168
  if (!ok) {
    throw new Error(
      `restore mismatch: checks=${gate.enabledChecks.join('/')} ` +
        `approvers=${approvers.opsLead.join('/')}|${approvers.deptManager.join('/')} ` +
        `lock=${lock.pauseBoundHours}`
    )
  }
  return { gate, approvers, lock }
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

/** Waits for the header and the requested panel, then gives async child blocks one beat. */
async function waitPage(page, titlePart, selector) {
  await page.waitForFunction(
    (text) => (document.querySelector('.page-title')?.textContent ?? '').includes(text),
    { timeout: 20000 },
    titlePart
  )
  if (selector) {
    await page.waitForSelector(selector, { timeout: 20000 })
  }
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

/** Waits for an ElMessageBox with this title and clicks one of its buttons. */
async function clickMessageBoxButton(page, title, label, timeout = 8000) {
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
  } catch {
    return false
  }
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

/** Clicks the 门禁规则 checkbox row whose machine value is `value` (label click toggles it). */
function clickGateCheck(page, value) {
  return page.evaluate((wanted) => {
    const row = Array.from(document.querySelectorAll('.settings-gate .gate-check')).find(
      (node) => (node.querySelector('.check-code')?.textContent ?? '').trim() === wanted
    )
    if (!row) return false
    row.click()
    return true
  }, value)
}

/** Reads everything the assertions need in one in-page pass. */
function readSettings(page) {
  return page.evaluate(() => {
    const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()
    const panel = (selector) => document.querySelector(selector)
    const text = (root, selector) => flat(root?.querySelector(selector)?.textContent)
    const gate = panel('.settings-gate')
    const approvers = panel('.settings-approvers')
    const lock = panel('.settings-lock')
    const tags = (role) =>
      Array.from(
        approvers?.querySelectorAll(`[data-role="${role}"] .approver-tag`) ?? []
      ).map((tag) => flat(tag.textContent))
    return {
      theme: document.documentElement.getAttribute('data-theme'),
      gate: {
        present: !!gate,
        title: text(gate, '.panel-title'),
        subtitle: text(gate, '.panel-head > .weak.small'),
        chip: text(gate, '.chip'),
        checks: Array.from(gate?.querySelectorAll('.gate-check') ?? []).map((row) => ({
          value: text(row, '.check-code'),
          label: text(row, '.check-label'),
          note: text(row, '.check-note'),
          checked: row.classList.contains('is-checked')
        })),
        hint: flat(gate?.querySelector('.gate-hint')?.innerText),
        save: !!gate?.querySelector('.gate-save'),
        restricted: flat(gate?.querySelector('.restricted')?.innerText)
      },
      approvers: {
        present: !!approvers,
        title: text(approvers, '.panel-title'),
        subtitle: text(approvers, '.panel-head > .weak.small'),
        chip: text(approvers, '.chip'),
        tags: { opsLead: tags('opsLead'), deptManager: tags('deptManager') },
        inputs: !!approvers?.querySelector('.approver-input input'),
        hint: flat(approvers?.querySelector('.approver-hint')?.innerText),
        save: !!approvers?.querySelector('.approver-save'),
        restricted: flat(approvers?.querySelector('.restricted')?.innerText)
      },
      lock: {
        present: !!lock,
        title: text(lock, '.panel-title'),
        subtitle: text(lock, '.panel-head > .weak.small'),
        chip: text(lock, '.chip'),
        hours: lock?.querySelector('.lock-hours input')?.value ?? '',
        hint: flat(lock?.querySelector('.hint')?.innerText),
        save: !!lock?.querySelector('.lock-save'),
        restricted: flat(lock?.querySelector('.restricted')?.innerText)
      }
    }
  })
}

function setLockHours(page, value) {
  return page.evaluate((next) => {
    const input = document.querySelector('.settings-lock .lock-hours input')
    if (!input) return false
    // A real input event drives the Vue model exactly like typing would.
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(input, next)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    return input.value === next
  }, value)
}

async function addApproverTag(page, role, userId) {
  await page.click(`.settings-approvers [data-role="${role}"] .approver-input input`)
  await page.type(`.settings-approvers [data-role="${role}"] .approver-input input`, userId)
  await page.click(`.settings-approvers [data-role="${role}"] .approver-add`)
  await sleep(150)
}

function clickApproverClose(page, role, userId) {
  return page.evaluate(
    (wantedRole, wantedUser) => {
      const row = document.querySelector(`.settings-approvers [data-role="${wantedRole}"]`)
      const tag = Array.from(row?.querySelectorAll('.approver-tag') ?? []).find(
        (node) => (node.textContent ?? '').trim() === wantedUser
      )
      const close = tag?.querySelector('.el-tag__close')
      if (!close) return false
      close.click()
      return true
    },
    role,
    userId
  )
}

/** ElTag plays a leave transition, so wait for the row to actually disappear before reading it. */
async function waitApproverTagGone(page, role, userId, timeout = 3000) {
  try {
    await page.waitForFunction(
      (wantedRole, wantedUser) =>
        !Array.from(
          document.querySelectorAll(`.settings-approvers [data-role="${wantedRole}"] .approver-tag`)
        ).some((tag) => (tag.textContent ?? '').trim() === wantedUser),
      { timeout, polling: 100 },
      role,
      userId
    )
    return true
  } catch {
    return false
  }
}

// ----------------------------------------------------------------------------------- main run

let before = null
try {
  before = {
    gate: await readSetting('gate'),
    approvers: await readSetting('approvers'),
    lock: await readSetting('lock')
  }
} catch (e) {
  console.error(`cannot read the initial settings: ${e.message}`)
  process.exit(1)
}
console.log(
  `initial GET: checks=${before.gate.enabledChecks.length} ` +
    `approvers=${before.approvers.opsLead.join('/') || '-'}|${before.approvers.deptManager.join('/') || '-'} ` +
    `lock=${before.lock.pauseBoundHours}`
)

await restoreDefaults()
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
    const settingsRequests = []
    page.on('request', (req) => {
      if (/\/api\/v1\/settings\/(gate|approvers|lock)/.test(req.url())) {
        settingsRequests.push(req.url())
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
    await waitPage(page, '设置', '.settings-lock .lock-hours input')
    await sleep(300)
    const initial = await readSettings(page)

    expect(initial.theme === theme, `theme is ${initial.theme}, expected ${theme}`)
    expect(initial.gate.present && initial.approvers.present && initial.lock.present, 'three blocks missing')
    expect(initial.gate.title === '门禁规则', `gate title (${initial.gate.title})`)
    expect(initial.approvers.title === '审批人名单', `approver title (${initial.approvers.title})`)
    expect(initial.lock.title === '锁超时', `lock title (${initial.lock.title})`)
    expect(initial.gate.checks.length === ALL_CHECKS.length, `gate checks (${initial.gate.checks.length})`)
    expect(
      initial.gate.checks.map((c) => c.value).join('|') === ALL_CHECKS.join('|'),
      `gate machine values (${initial.gate.checks.map((c) => c.value).join('|')})`
    )
    expect(initial.gate.checks.every((c) => c.checked), 'not every check is enabled by default')
    expect(initial.gate.checks.every((c) => c.label && c.label !== c.value), 'gate labels not localised')
    expect(initial.gate.checks.every((c) => c.note.includes('检查')), 'gate stage notes missing')
    expect(
      initial.gate.hint.includes('停用对在途发布立即生效') && initial.gate.hint.includes('不会补跑'),
      `gate warning missing (${initial.gate.hint})`
    )
    expect(initial.gate.save, 'gate save button missing for ADMIN')
    expect(initial.approvers.tags.opsLead.length === 0, 'approver list not restored to empty (opsLead)')
    expect(initial.approvers.tags.deptManager.length === 0, 'approver list not restored to empty (deptManager)')
    expect(initial.approvers.inputs, 'approver input missing')
    expect(initial.approvers.save, 'approver save button missing for ADMIN')
    expect(
      initial.approvers.hint.includes('留空') && initial.approvers.hint.includes('系统负责人'),
      `approver fallback hint missing (${initial.approvers.hint})`
    )
    expect(
      initial.approvers.hint.includes('user_role') && initial.approvers.hint.includes('生效名单'),
      `approver effective-list note missing (${initial.approvers.hint})`
    )
    expect(initial.lock.hours === '168', `lock default (${initial.lock.hours})`)
    expect(
      initial.lock.hint.includes('4 小时') && initial.lock.hint.includes('TTL'),
      `lock fixed-lease warning missing (${initial.lock.hint})`
    )
    expect(initial.lock.hint.includes('下一轮'), `lock apply-window note missing (${initial.lock.hint})`)

    // --------------------------------------------------- gate: disable one, then enable it again
    const unchecked = await clickGateCheck(page, 'DB_NORM')
    expect(unchecked, 'could not toggle DB_NORM')
    await sleep(200)
    await page.click('.settings-gate .gate-save')
    const confirmShown = await clickMessageBoxButton(page, '保存门禁规则', '确认停用')
    expect(confirmShown, 'disable confirmation did not appear')
    expect(await waitMessage(page, '门禁规则已保存'), 'gate save toast missing')
    await sleep(250)
    const afterDisable = await readSettings(page)
    const apiAfterDisable = await readSetting('gate')
    expect(
      afterDisable.gate.checks.filter((c) => c.checked).length === ALL_CHECKS.length - 1,
      `page still has ${afterDisable.gate.checks.filter((c) => c.checked).length} checks`
    )
    expect(
      apiAfterDisable.enabledChecks.length === ALL_CHECKS.length - 1,
      `API checks after disable (${apiAfterDisable.enabledChecks.join('/')})`
    )
    expect(afterDisable.gate.chip === '已保存', `gate chip after save (${afterDisable.gate.chip})`)
    expect(afterDisable.gate.subtitle.includes('6 / 7 项启用'), `gate subtitle after save (${afterDisable.gate.subtitle})`)

    await clickGateCheck(page, 'DB_NORM')
    await sleep(200)
    await page.click('.settings-gate .gate-save')
    const noConfirm = await clickMessageBoxButton(page, '保存门禁规则', '确认停用', 1200)
    expect(!noConfirm, 'confirmation appeared when only re-enabling a check')
    expect(await waitMessage(page, '门禁规则已保存'), 'gate re-enable toast missing')
    await sleep(250)
    const apiAfterEnable = await readSetting('gate')
    expect(
      apiAfterEnable.enabledChecks.length === ALL_CHECKS.length,
      `API checks after re-enable (${apiAfterEnable.enabledChecks.join('/')})`
    )

    // --------------------------------------------------------- approvers: add a tag, save, remove
    await addApproverTag(page, 'opsLead', 'ops-lead')
    const tagsAfterAdd = await readSettings(page)
    expect(tagsAfterAdd.approvers.tags.opsLead.includes('ops-lead'), 'added approver tag missing')
    await page.click('.settings-approvers .approver-save')
    expect(await waitMessage(page, '审批人名单已保存'), 'approver save toast missing')
    await sleep(250)
    const apiWithApprover = await readSetting('approvers')
    expect(
      apiWithApprover.opsLead.includes('ops-lead') && apiWithApprover.deptManager.length === 0,
      `API approvers after add (${apiWithApprover.opsLead.join('/')} | ${apiWithApprover.deptManager.join('/')})`
    )

    const closed = await clickApproverClose(page, 'opsLead', 'ops-lead')
    expect(closed, 'could not remove the approver tag')
    expect(await waitApproverTagGone(page, 'opsLead', 'ops-lead'), 'approver tag still visible after close')
    const tagsAfterRemove = await readSettings(page)
    expect(tagsAfterRemove.approvers.tags.opsLead.length === 0, 'approver tag still visible after close')
    await page.click('.settings-approvers .approver-save')
    expect(await waitMessage(page, '审批人名单已保存'), 'approver empty save toast missing')
    await sleep(250)
    const apiEmptyApprovers = await readSetting('approvers')
    expect(
      apiEmptyApprovers.opsLead.length === 0 && apiEmptyApprovers.deptManager.length === 0,
      `API approvers after empty save (${apiEmptyApprovers.opsLead.join('/')} | ${apiEmptyApprovers.deptManager.join('/')})`
    )

    // ---------------------------------------------------------------- lock: guards then a save
    await setLockHours(page, '0')
    await page.click('.settings-lock .lock-save')
    expect(await waitMessage(page, '暂停上限需为 1–8760 之间的整数小时'), 'lock 0 was not rejected')
    await sleep(200)
    const lockAfterZero = await readSetting('lock')
    expect(lockAfterZero.pauseBoundHours === 168, `lock changed after 0 (${lockAfterZero.pauseBoundHours})`)

    await setLockHours(page, '8761')
    await page.click('.settings-lock .lock-save')
    expect(await waitMessage(page, '暂停上限需为 1–8760 之间的整数小时'), 'lock 8761 was not rejected')
    await sleep(200)
    const lockAfterHigh = await readSetting('lock')
    expect(lockAfterHigh.pauseBoundHours === 168, `lock changed after 8761 (${lockAfterHigh.pauseBoundHours})`)

    await setLockHours(page, '200')
    await page.click('.settings-lock .lock-save')
    expect(await waitMessage(page, '锁超时设置已保存'), 'lock save toast missing')
    await sleep(250)
    const lockAfterSave = await readSetting('lock')
    expect(lockAfterSave.pauseBoundHours === 200, `lock not saved (${lockAfterSave.pauseBoundHours})`)

    // -------------------------------------------------------------- restore + screenshot
    const restored = await restoreDefaults()
    expect(restored.gate.enabledChecks.length === ALL_CHECKS.length, 'restore: gate')
    expect(restored.approvers.opsLead.length === 0, 'restore: approvers')
    expect(restored.lock.pauseBoundHours === 168, 'restore: lock')
    await open(page, '/settings', theme)
    await waitPage(page, '设置', '.settings-lock .lock-hours input')
    await sleep(350)
    const finalState = await readSettings(page)
    expect(finalState.gate.checks.every((c) => c.checked), 'restored page does not show all checks')
    expect(finalState.approvers.tags.opsLead.length === 0, 'restored page still lists an approver')
    expect(finalState.lock.hours === '168', `restored page lock (${finalState.lock.hours})`)

    if (SHOTS) {
      await page.evaluate(() => document.querySelector('.settings-gate')?.scrollIntoView({ block: 'start' }))
      await sleep(300)
      const frame = await page.evaluate(() => {
        const gate = document.querySelector('.settings-gate')?.getBoundingClientRect()
        const lock = document.querySelector('.settings-lock')?.getBoundingClientRect()
        return { top: gate?.top ?? -1, bottom: lock?.bottom ?? 9999, height: window.innerHeight }
      })
      expect(
        frame.top >= -1 && frame.bottom <= frame.height + 1,
        `rules blocks outside the screenshot (${Math.round(frame.top)}..${Math.round(frame.bottom)} of ${frame.height})`
      )
      await page.screenshot({ path: join(SHOTS, `settings-rules-${theme}.png`) })
    }

    // -------------------------------------------------------------- read-only branch
    await setUser(page, READONLY)
    settingsRequests.length = 0
    await open(page, '/settings', theme)
    await waitPage(page, '设置', '.settings-lock .restricted')
    await sleep(400)
    const readonly = await readSettings(page)
    for (const [name, block] of [
      ['gate', readonly.gate],
      ['approvers', readonly.approvers],
      ['lock', readonly.lock]
    ]) {
      expect(block.restricted.includes('需要平台管理员权限'), `${name} read-only reason missing`)
      expect(block.restricted.includes('setting:manage'), `${name} read-only perm name missing`)
      expect(!block.save, `${name} save button shown to a read-only user`)
    }
    expect(readonly.gate.checks.length === 0, 'gate checkboxes shown to a read-only user')
    expect(!readonly.approvers.inputs, 'approver inputs shown to a read-only user')
    expect(readonly.lock.hours === '', 'lock input shown to a read-only user')
    expect(settingsRequests.length === 0, `read-only page requested settings (${settingsRequests.join(', ')})`)

    await sleep(250)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6b-${theme}: sections=3 checks=${initial.gate.checks.length} ` +
        `gateToggle=${ALL_CHECKS.length - 1}->${apiAfterEnable.enabledChecks.length} ` +
        `approvers=${apiWithApprover.opsLead.length}->${apiEmptyApprovers.opsLead.length} ` +
        `lockGuard=0/8761 lockSaved=${lockAfterSave.pauseBoundHours} ` +
        `restored=${finalState.gate.checks.filter((c) => c.checked).length}/${finalState.approvers.tags.opsLead.length}/${finalState.lock.hours} ` +
        `readonly=yes settingsGets=${settingsRequests.length} errors=${errors.length}`
    )
    if (fails.length) lines.push('   FAIL ' + fails.join('; '))
    errors.slice(0, 3).forEach((e) => lines.push('   ' + e))
    await page.close()
  }
} finally {
  // Leave the documented defaults even when an assertion (or a page) failed above.
  try {
    const restored = await restoreDefaults()
    lines.push(
      `restored: checks=${restored.gate.enabledChecks.length} ` +
        `approvers=${restored.approvers.opsLead.length}/${restored.approvers.deptManager.length} ` +
        `lock=${restored.lock.pauseBoundHours}`
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
console.log('OK all P6-B settings assertions passed')