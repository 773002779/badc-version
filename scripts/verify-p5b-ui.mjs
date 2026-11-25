// Asserts the P5-B stop / exit / close / archive slice in a real browser (texts + selectors +
// console error count; exit non-zero on any miss), in both themes:
//
//   * the release header carries 「停止发布」 and 「退出流水线」 for dev, both open the shared reason
//     dialog, show the cascade sentence and block an empty / oversized reason;
//   * a caller with control:execute-prod but no release:publish (ops-lead) can stop and exit, a
//     caller with neither (dept-manager) sees both disabled with the permission in the title;
//   * the 变更项 row closes through the same dialog, and a caller without release:publish cannot;
//   * 「归档任务」 shows the cascade preview (N unfinished items, M in-flight instances), archives
//     on confirm, and the page then is read-only while keeping the 发布记录 door.
//
//   node scripts/verify-p5b-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p5b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes release-stop-{light,dark}.png (stop dialog open on the PAUSED production release)
// and task-archived-{light,dark}.png (the read-only archived task). The assertions run the same
// way with or without it. Run from the repository root so a relative --shots path lands in the repo.
//
// Data: scripts/demo-control.sql seeds PO-DEMO-CTL-STOP (item PROD_DEPLOYING, pipeline PAUSED at
// PROD_DEPLOY) and the two archive tasks DEMO-CTL-ARCHIVE-A/-B (light/dark runs, one DONE item,
// one TESTING item with a RUNNING instance and one GATE_PENDING item). Re-run the seed before each
// probe run: the probe closes the GATE_PENDING item and archives the task, which the seed resets.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p5b-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p5b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

/** The stop demo of scripts/demo-control.sql. */
const STOP_PUBLISH = 'PO-DEMO-CTL-STOP'
const STOP_PIPELINE = 'PL-DEMO-CTL-STOP'
const STOP_CRID = 'CR-DEMO-CTL-STOP'

/** One archive task per theme: the seed can only be reset between runs, not between themes. */
const TASKS = {
  light: {
    id: 90001,
    name: 'DEMO-CTL-ARCHIVE-A',
    done: 'CR-DEMO-CTL-ARCH-A-DONE',
    open: 'CR-DEMO-CTL-ARCH-A-OPEN',
    close: 'CR-DEMO-CTL-ARCH-A-CLOSE'
  },
  dark: {
    id: 90002,
    name: 'DEMO-CTL-ARCHIVE-B',
    done: 'CR-DEMO-CTL-ARCH-B-DONE',
    open: 'CR-DEMO-CTL-ARCH-B-OPEN',
    close: 'CR-DEMO-CTL-ARCH-B-CLOSE'
  }
}

const REASON_STOP = '演示：生产批次 2 异常，停止发布'
const REASON_EXIT = '演示：需求取消，退出流水线'
const REASON_CLOSE = '演示：需求取消，关闭变更项'
const REASON_ARCHIVE = '演示：版本已收口，归档任务'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

async function setUser(page, userId) {
  await page.evaluate((id) => localStorage.setItem('rp.userId', id), userId)
}

/** The reason dialog currently on screen (there are several lazy dialogs in the DOM). */
function readReasonDialog(page) {
  return page.evaluate(() => {
    const dialog = Array.from(document.querySelectorAll('.reason-dialog')).find(
      (el) => el.getBoundingClientRect().height > 0
    )
    if (!dialog) return null
    const textarea = dialog.querySelector('.reason-input textarea') ?? dialog.querySelector('textarea')
    const confirm = dialog.querySelector('.reason-confirm')
    const cancel = Array.from(dialog.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').trim() === '取消'
    )
    return {
      title: dialog.querySelector('.el-dialog__title')?.textContent?.trim() ?? '',
      text: (dialog.innerText ?? '').replace(/\s+/g, ' ').trim(),
      message: dialog.querySelector('.reason-message')?.textContent?.trim() ?? '',
      warning: dialog.querySelector('.reason-warning')?.innerText?.replace(/\s+/g, ' ').trim() ?? '',
      hint: dialog.querySelector('.reason-meta')?.innerText?.replace(/\s+/g, ' ').trim() ?? '',
      counter: dialog.querySelector('.el-input__count')?.textContent?.trim() ?? '',
      textareaMax: textarea?.getAttribute('maxlength') ?? '',
      value: textarea?.value ?? '',
      confirmText: (confirm?.textContent ?? '').trim(),
      confirmDisabled: confirm ? confirm.disabled : true,
      cancelFound: Boolean(cancel)
    }
  })
}

async function waitReasonDialog(page, timeout = 8000) {
  await page.waitForFunction(
    () =>
      Array.from(document.querySelectorAll('.reason-dialog')).some(
        (el) => el.getBoundingClientRect().height > 0
      ),
    { timeout }
  )
  // The dialog runs a 220ms opening transition; read it after it settles.
  await sleep(280)
  return readReasonDialog(page)
}

async function waitNoReasonDialog(page, timeout = 8000) {
  await page.waitForFunction(
    () =>
      !Array.from(document.querySelectorAll('.reason-dialog')).some(
        (el) => el.getBoundingClientRect().height > 0
      ),
    { timeout }
  )
}

async function fillReason(page, reason) {
  // Element Plus keeps closed dialogs lazily in the DOM, so focus the *visible* textarea by hand;
  // a plain CSS selector would hit the hidden one from a previous dialog.
  const focused = await page.evaluate(() => {
    const dialog = Array.from(document.querySelectorAll('.reason-dialog')).find(
      (el) => el.getBoundingClientRect().height > 0
    )
    const textarea = dialog?.querySelector('textarea')
    if (!textarea) return false
    textarea.focus()
    return true
  })
  if (!focused) return false
  await page.keyboard.type(reason)
  await sleep(180)
  return true
}

async function clickDialog(page, text) {
  return page.evaluate((wanted) => {
    const dialog = Array.from(document.querySelectorAll('.reason-dialog')).find(
      (el) => el.getBoundingClientRect().height > 0
    )
    if (!dialog) return false
    const button = Array.from(dialog.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').trim() === wanted
    )
    if (!button) return false
    button.click()
    return true
  }, text)
}

/** The header buttons of a page, with their disabled state and tooltip. */
function headerButtons(page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('.page-actions button')).map((b) => ({
      text: (b.textContent ?? '').trim(),
      disabled: b.disabled,
      title: b.getAttribute('title') ?? '',
      className: b.className
    }))
  )
}

/** One row of the 变更项 table, by CRID. */
function itemRow(page, crid) {
  return page.evaluate((wanted) => {
    const row = Array.from(document.querySelectorAll('.items-block .el-table__row')).find((r) =>
      (r.innerText ?? '').includes(wanted)
    )
    if (!row) return null
    return {
      text: (row.innerText ?? '').replace(/\s+/g, ' ').trim(),
      buttons: Array.from(row.querySelectorAll('button')).map((b) => ({
        text: (b.textContent ?? '').trim(),
        disabled: b.disabled,
        title: b.getAttribute('title') ?? ''
      })),
      links: Array.from(row.querySelectorAll('a')).map((a) => ({
        text: (a.textContent ?? '').trim(),
        href: a.getAttribute('href') ?? ''
      }))
    }
  }, crid)
}

function itemButtons(page, crid) {
  return page.evaluate((wanted) => {
    const row = Array.from(document.querySelectorAll('.items-block .el-table__row')).find((r) =>
      (r.innerText ?? '').includes(wanted)
    )
    if (!row) return []
    return Array.from(row.querySelectorAll('button')).map((b) => ({
      text: (b.textContent ?? '').trim(),
      disabled: b.disabled,
      title: b.getAttribute('title') ?? ''
    }))
  }, crid)
}

async function clickItemButton(page, crid, text) {
  return page.evaluate(
    (wanted, label) => {
      const row = Array.from(document.querySelectorAll('.items-block .el-table__row')).find((r) =>
        (r.innerText ?? '').includes(wanted)
      )
      if (!row) return false
      const button = Array.from(row.querySelectorAll('button')).find(
        (b) => (b.textContent ?? '').trim() === label
      )
      if (!button || button.disabled) return false
      button.click()
      return true
    },
    crid,
    text
  )
}

function hasButton(buttons, text) {
  return buttons.find((b) => b.text === text)
}

async function waitRowStatus(page, crid, status, timeout = 15000) {
  await page.waitForFunction(
    (wanted, label) => {
      const row = Array.from(document.querySelectorAll('.items-block .el-table__row')).find((r) =>
        (r.innerText ?? '').includes(wanted)
      )
      return !!row && (row.innerText ?? '').includes(label)
    },
    { timeout },
    crid,
    status
  )
}

async function waitButtonEnabled(page, selector, timeout = 15000) {
  await page.waitForFunction(
    (sel) => {
      const b = document.querySelector(sel)
      return !!b && !b.disabled
    },
    { timeout },
    selector
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
    const task = TASKS[theme]
    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 900 })
    const errors = []
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
    page.on('pageerror', (e) => errors.push(e.message))
    const fails = []

    // ---------------------------------------------------------------- release: dev
    await page.goto(appendTheme(`${BASE}/releases/${STOP_PUBLISH}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.page-actions .stop-release', { visible: true, timeout: 20000 })
    await waitButtonEnabled(page, '.page-actions .stop-release')
    await sleep(300)

    const devButtons = await headerButtons(page)
    const stopButton = hasButton(devButtons, '停止发布')
    const exitButton = hasButton(devButtons, '退出流水线')
    if (!stopButton) fails.push('stop button missing for dev')
    else if (stopButton.disabled) fails.push(`stop disabled for dev (${stopButton.title})`)
    if (!exitButton) fails.push('exit button missing for dev')
    else if (exitButton.disabled) fails.push(`exit disabled for dev (${exitButton.title})`)
    if (stopButton && !stopButton.title.includes('已上线批次保留')) {
      fails.push(`stop title (${stopButton.title})`)
    }

    // 1. 停止发布: cascade sentence + empty-reason block + 200-char cap.
    await page.click('.page-actions .stop-release')
    let stopDialog = await waitReasonDialog(page)
    if (!stopDialog) fails.push('stop dialog missing')
    else {
      if (stopDialog.title !== '停止发布') fails.push(`stop title (${stopDialog.title})`)
      for (const phrase of ['已终止', '待签审批作废', '已上线', '不回滚', '镜像回滚']) {
        if (!stopDialog.text.includes(phrase)) fails.push(`stop text (${phrase})`)
      }
      if (!stopDialog.confirmDisabled) fails.push('stop confirm enabled with empty reason')
      if (stopDialog.textareaMax !== '200') fails.push(`stop maxlength (${stopDialog.textareaMax})`)
      if (!stopDialog.hint.includes('1–200')) fails.push(`stop hint (${stopDialog.hint})`)
      if (!stopDialog.counter.includes('200')) fails.push(`stop counter (${stopDialog.counter})`)
    }
    await fillReason(page, REASON_STOP)
    stopDialog = await readReasonDialog(page)
    if (!stopDialog) fails.push('stop dialog disappeared while typing')
    else {
      if (stopDialog.confirmDisabled) fails.push('stop confirm disabled with a valid reason')
      if (stopDialog.value !== REASON_STOP) fails.push(`stop typed reason (${stopDialog.value})`)
    }

    if (SHOTS) {
      // Keep the header behind the dialog: it carries the two new doors and the PROD_DEPLOYING state.
      await page.evaluate(() => window.scrollTo(0, 0))
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `release-stop-${theme}.png`) })
    }

    if (!(await clickDialog(page, '取消'))) fails.push('stop cancel click')
    await waitNoReasonDialog(page)
    const afterCancel = await headerButtons(page)
    const stopStill = hasButton(afterCancel, '停止发布')
    if (!stopStill || stopStill.disabled) fails.push('stop became unavailable after cancel')

    // 2. 退出流水线: the same dialog with the exit cascade wording.
    await page.click('.page-actions .exit-release')
    let exitDialog = await waitReasonDialog(page)
    if (!exitDialog) fails.push('exit dialog missing')
    else {
      if (exitDialog.title !== '退出流水线') fails.push(`exit title (${exitDialog.title})`)
      for (const phrase of ['未完成检查', '失效', '待签审批作废', '已上线批次保留', '生产回滚']) {
        if (!exitDialog.text.includes(phrase)) fails.push(`exit text (${phrase})`)
      }
      if (!exitDialog.confirmDisabled) fails.push('exit confirm enabled with empty reason')
    }
    await fillReason(page, REASON_EXIT)
    exitDialog = await readReasonDialog(page)
    if (exitDialog?.confirmDisabled) fails.push('exit confirm disabled with a valid reason')
    if (!(await clickDialog(page, '取消'))) fails.push('exit cancel click')
    await waitNoReasonDialog(page)

    // 3. Permission door: control:execute-prod without release:publish can stop and exit.
    await setUser(page, 'ops-lead')
    await page.goto(appendTheme(`${BASE}/releases/${STOP_PUBLISH}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.page-actions .stop-release', { visible: true, timeout: 20000 })
    await waitButtonEnabled(page, '.page-actions .stop-release')
    await sleep(300)
    const opsButtons = await headerButtons(page)
    const opsStop = hasButton(opsButtons, '停止发布')
    const opsExit = hasButton(opsButtons, '退出流水线')
    if (!opsStop || opsStop.disabled) fails.push(`ops-lead stop (${JSON.stringify(opsStop)})`)
    if (!opsExit || opsExit.disabled) fails.push(`ops-lead exit (${JSON.stringify(opsExit)})`)

    // 4. A caller without either permission sees both disabled with the missing permission.
    await setUser(page, 'dept-manager')
    await page.goto(appendTheme(`${BASE}/releases/${STOP_PUBLISH}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.page-actions .stop-release', { visible: true, timeout: 20000 })
    await sleep(500)
    const managerButtons = await headerButtons(page)
    const managerStop = hasButton(managerButtons, '停止发布')
    const managerExit = hasButton(managerButtons, '退出流水线')
    if (!managerStop || !managerStop.disabled) fails.push('dept-manager stop enabled')
    else if (!managerStop.title.includes('control:execute-prod')) {
      fails.push(`dept-manager stop title (${managerStop.title})`)
    }
    if (!managerExit || !managerExit.disabled) fails.push('dept-manager exit enabled')
    else if (!managerExit.title.includes('release:publish')) {
      fails.push(`dept-manager exit title (${managerExit.title})`)
    }

    // ------------------------------------------------------------- task: dev
    await setUser(page, 'dev')
    await page.goto(appendTheme(`${BASE}/tasks/${task.id}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.items-block .el-table__row', { visible: true, timeout: 20000 })
    await sleep(400)

    // 5. Without task:create (ops-lead) 归档 is disabled; without release:publish 关闭 is too.
    await setUser(page, 'ops-lead')
    await page.goto(appendTheme(`${BASE}/tasks/${task.id}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.items-block .el-table__row', { visible: true, timeout: 20000 })
    await sleep(400)
    const opsHeader = await headerButtons(page)
    const opsArchive = hasButton(opsHeader, '归档任务')
    if (!opsArchive) fails.push('archive button missing for ops-lead')
    else {
      if (!opsArchive.disabled) fails.push('archive enabled for ops-lead')
      if (!opsArchive.title.includes('task:create')) fails.push(`archive title (${opsArchive.title})`)
    }
    const opsClose = await itemButtons(page, task.close)
    const opsCloseButton = hasButton(opsClose, '关闭')
    if (!opsCloseButton) fails.push('close button missing for ops-lead')
    else {
      if (!opsCloseButton.disabled) fails.push('close enabled for ops-lead')
      if (!opsCloseButton.title.includes('release:publish')) {
        fails.push(`close title (${opsCloseButton.title})`)
      }
    }

    // --------------------------------------------------------- task: close item
    await setUser(page, 'dev')
    await page.goto(appendTheme(`${BASE}/tasks/${task.id}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.items-block .el-table__row', { visible: true, timeout: 20000 })
    await sleep(400)

    const closeRow = await itemRow(page, task.close)
    if (!closeRow) fails.push('close demo item missing')
    else {
      const closeButton = hasButton(closeRow.buttons, '关闭')
      if (!closeButton) fails.push('close row action missing')
      else if (closeButton.disabled) fails.push(`close row action disabled (${closeButton.title})`)
    }

    if (!(await clickItemButton(page, task.close, '关闭'))) fails.push('close item click')
    const closeDialog = await waitReasonDialog(page)
    if (!closeDialog) fails.push('close dialog missing')
    else {
      if (closeDialog.title !== '关闭变更项') fails.push(`close title (${closeDialog.title})`)
      if (!closeDialog.text.includes(task.close)) fails.push(`close message (${closeDialog.text})`)
      if (!closeDialog.text.includes('不可再发布')) fails.push('close irreversible warning')
      if (!closeDialog.confirmDisabled) fails.push('close confirm enabled with empty reason')
    }
    await fillReason(page, REASON_CLOSE)
    if (!(await clickDialog(page, '关闭变更项'))) fails.push('close confirm click')
    await waitNoReasonDialog(page, 15000)
    await waitRowStatus(page, task.close, '已关闭')

    // 6. 归档任务: cascade preview (the seed leaves one unfinished item + one RUNNING instance).
    const archiveHeader = await headerButtons(page)
    const archiveButton = hasButton(archiveHeader, '归档任务')
    if (!archiveButton) fails.push('archive button missing for dev')
    else if (archiveButton.disabled) fails.push(`archive disabled for dev (${archiveButton.title})`)
    await page.click('.page-actions .archive-task')

    const archiveDialog = await waitReasonDialog(page)
    if (!archiveDialog) fails.push('archive dialog missing')
    else {
      if (archiveDialog.title !== '归档任务') fails.push(`archive title (${archiveDialog.title})`)
      if (!archiveDialog.text.includes(task.name)) fails.push('archive message task name')
      if (!archiveDialog.confirmDisabled) fails.push('archive confirm enabled with empty reason')
    }
    await page.waitForFunction(
      () => {
        const dialog = Array.from(document.querySelectorAll('.reason-dialog')).find(
          (el) => el.getBoundingClientRect().height > 0
        )
        return !!dialog && /终止 1 个在途实例/.test(dialog.innerText ?? '')
      },
      { timeout: 10000 }
    )
    const counted = await readReasonDialog(page)
    if (!counted) fails.push('archive dialog closed while counting')
    else {
      if (!counted.text.includes('关闭 1 个未完成变更项')) {
        fails.push(`archive unfinished count (${counted.text})`)
      }
      if (!counted.text.includes('终止 1 个在途实例')) fails.push('archive in-flight count')
      for (const phrase of ['只读', '发布记录', '回滚', '不可撤销']) {
        if (!counted.text.includes(phrase)) fails.push(`archive warning (${phrase})`)
      }
    }
    await fillReason(page, REASON_ARCHIVE)
    if (!(await clickDialog(page, '确认归档'))) fails.push('archive confirm click')
    await page.waitForSelector('.archive-readonly', { visible: true, timeout: 20000 })
    await waitRowStatus(page, task.open, '已关闭')
    await sleep(400)

    // 7. The archived task is read-only: no create/publish/daily/archive doors, records stay.
    const archivedHeader = await headerButtons(page)
    if (hasButton(archivedHeader, '新建变更项')) fails.push('archived task still offers 新建变更项')
    if (hasButton(archivedHeader, '归档任务')) fails.push('archived task still offers 归档任务')

    const readonlyText = await page
      .$eval('.archive-readonly', (el) => (el.innerText ?? '').replace(/\s+/g, ' ').trim())
      .catch(() => '')
    if (!readonlyText.includes('只读')) fails.push(`readonly alert (${readonlyText})`)
    if (!readonlyText.includes('发布记录') || !readonlyText.includes('回滚')) {
      fails.push('readonly alert does not keep the record/rollback doors')
    }

    const doneRow = await itemRow(page, task.done)
    const openRow = await itemRow(page, task.open)
    const closedRow = await itemRow(page, task.close)
    if (!doneRow || !doneRow.text.includes('已完成')) fails.push('archived task lost the DONE item')
    if (!openRow) fails.push('archived task lost the open item row')
    if (!closedRow) fails.push('archived task lost the closed item row')
    for (const [label, row, crid] of [
      ['done', doneRow, task.done],
      ['open', openRow, task.open],
      ['closed', closedRow, task.close]
    ]) {
      if (!row) continue
      for (const action of ['发布', '关闭', '日常', '重跑']) {
        if (hasButton(row.buttons, action)) fails.push(`archived ${label} row still offers ${action}`)
      }
      const records = row.links.find((l) => l.text === '发布记录')
      if (!records) fails.push(`archived ${label} row lost 发布记录`)
      else if (!records.href.includes(`keyword=${crid}`)) {
        fails.push(`archived ${label} row record link (${records.href})`)
      }
    }
    const openButtons = openRow?.buttons.map((b) => b.text) ?? []
    if (openRow && !openButtons.includes('记录')) fails.push('archived open row lost the daily records door')

    if (SHOTS) {
      await page.evaluate(() => window.scrollTo(0, 0))
      await page.waitForFunction(() => !document.querySelector('.el-message'), { timeout: 6000 }).catch(() => {})
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `task-archived-${theme}.png`) })
    }

    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p5b-${theme}: task=${task.id} stop=on exit=on ops=stop/exit dept=blocked ` +
        `closed=yes archived=${readonlyText ? 'yes' : 'no'} rows=` +
        `${[doneRow, openRow, closedRow].filter(Boolean).length} errors=${errors.length}`
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
console.log('OK all stop/exit/close/archive assertions passed')