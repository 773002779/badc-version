// Asserts the P5-C 待办中心 in a real browser (texts + selectors + console error count; exit
// non-zero on any miss), in both themes:
//
//   * the sidebar carries 「待办」 (/todos) and its badge equals the sum of the four group counts
//     (0 hides it);
//   * /todos renders the four DataSections (我的待测试 / 我的审批 / 待我执行 / 我发起的), each with
//     its count, a light empty state, the waiting copy (「已等待 N 小时」, 0 -> 「刚刚」) and the
//     server-provided link (clicking a row really navigates);
//   * the server's first-pending-step rule: ops-lead sees APPROVAL-A, dept-manager sees APPROVAL-B
//     and must NOT see A until the OPS_LEAD signature exists;
//   * demo-bot (no roles, no scope) gets four empty groups -> the whole-page empty state and no
//     badge;
//   * 0 console errors.
//
//   node scripts/verify-p5c-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p5c-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes todos-{light,dark}.png (dev's four-section page). Run from the repository root so
// the relative --shots path lands in the repo. Data comes from scripts/demo-todos.sql; re-run that
// seed immediately before this probe (the 刚刚 row is relative to the seed time).
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p5c-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p5c-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

/** Markers of scripts/demo-todos.sql. */
const TEST_CRID = 'CR-DEMO-TODO-TEST'
const TEST_LINK = '/tasks/90003?item=90031'
const EXEC_PUBLISH = 'PO-DEMO-TODO-EXECUTE'
const APPROVAL_A = 'PO-DEMO-TODO-APPROVAL-A'
const APPROVAL_B = 'PO-DEMO-TODO-APPROVAL-B'
const MINE_TASK = 'DEMO-TODO-MINE'
const NOW_TASK = 'DEMO-TODO-NOW'
const EMPTY_USER = 'demo-bot'
const SECTION_TITLES = ['我的待测试', '我的审批', '待我执行', '我发起的']

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

async function setUser(page, userId) {
  // localStorage is unavailable on the initial about:blank page; any same-origin document works.
  if (!page.url().startsWith(BASE)) {
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 })
  }
  await page.evaluate((id) => localStorage.setItem('rp.userId', id), userId)
}

/** Waits for the first render of the board (either the grid or the whole-page empty panel). */
async function waitBoard(page, timeout = 20000) {
  await page.waitForFunction(
    () => document.querySelectorAll('.todo-section').length === 4 || document.querySelector('.todo-empty'),
    { timeout }
  )
  await sleep(250)
}

/**
 * The board as the page renders it: section titles/subtitles, every row's text and href, the
 * segment empty states, the footer cap note, the sidebar badge and the page-level copy.
 */
function readBoard(page) {
  return page.evaluate(() => {
    const flat = (el) => (el?.innerText ?? '').replace(/\s+/g, ' ').trim()
    const sections = Array.from(document.querySelectorAll('.todo-section')).map((section) => ({
      title: flat(section.querySelector('.panel-title')),
      subtitle: flat(section.querySelector('.panel-head > .weak.small')),
      rows: Array.from(section.querySelectorAll('.todo-row')).map((row) => ({
        text: flat(row),
        href: row.getAttribute('href') ?? '',
        aria: row.getAttribute('aria-label') ?? ''
      })),
      empty: flat(section.querySelector('.empty')),
      more: flat(section.querySelector('.todo-more'))
    }))
    const nav = document.querySelector('.nav-item[href="/todos"]')
    return {
      sections,
      badge: flat(nav?.querySelector('.nav-badge')),
      navLabel: nav?.getAttribute('aria-label') ?? '',
      emptyPage: flat(document.querySelector('.todo-empty')),
      pageDesc: flat(document.querySelector('.page-desc')),
      headerCount: flat(document.querySelector('.page-actions .weak.small')),
      pageTitle: flat(document.querySelector('.page-title'))
    }
  })
}

/** Parses the leading number of a section subtitle (`1 项` / `9 个未完成项`). */
function countOf(section) {
  const matched = /^(\d+)/.exec(section.subtitle)
  return matched ? Number(matched[1]) : -1
}

function sectionOf(board, title) {
  return board.sections.find((s) => s.title === title)
}

function rowsText(section) {
  return (section?.rows ?? []).map((r) => r.text).join('\n')
}

/** Clicks the first todo row whose text contains `wanted`, then waits for its href as the URL. */
async function clickTodo(page, wanted) {
  const href = await page.evaluate((text) => {
    const hit = Array.from(document.querySelectorAll('.todo-row')).find((row) =>
      (row.innerText ?? '').includes(text)
    )
    if (!hit) return null
    const target = hit.getAttribute('href')
    hit.click()
    return target
  }, wanted)
  if (!href) return null
  await page.waitForFunction(
    (target) => location.pathname + location.search === target,
    { timeout: 10000 },
    href
  )
  return href
}

/** Reopens /todos for the current identity in the requested theme. */
async function openTodos(page, theme) {
  await page.goto(appendTheme(`${BASE}/todos`, theme), {
    waitUntil: 'networkidle2',
    timeout: 30000
  })
  await waitBoard(page)
  return readBoard(page)
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

    // ------------------------------------------------------------ dev: four sections
    await setUser(page, 'dev')
    let board = await openTodos(page, theme)

    expect(board.sections.length === 4, `dev sections (${board.sections.length})`)
    expect(
      board.sections.map((s) => s.title).join('|') === SECTION_TITLES.join('|'),
      `dev titles (${board.sections.map((s) => s.title).join('|')})`
    )
    if (!board.pageDesc.includes('按你的角色与数据范围计算')) {
      fails.push(`page description (${board.pageDesc})`)
    }

    const devCounts = board.sections.map(countOf)
    const devSum = devCounts.reduce((a, b) => a + b, 0)
    expect(devCounts.every((n) => n >= 0), `dev counts unparsed (${devCounts.join('/')})`)
    if (String(devSum) !== board.badge) {
      fails.push(`dev badge ${board.badge} != section sum ${devSum} (${devCounts.join('/')})`)
    }
    if (board.headerCount !== `共 ${devSum} 项`) {
      fails.push(`dev header count (${board.headerCount})`)
    }

    const test = sectionOf(board, '我的待测试')
    if (!test) fails.push('dev 待测试 section missing')
    else {
      const text = rowsText(test)
      if (!text.includes(TEST_CRID)) fails.push(`test row missing ${TEST_CRID}`)
      if (!text.includes('测试中')) fails.push('test row missing status label 测试中')
      if (!/已等待 [3-4] 小时/.test(text)) fails.push(`test waiting copy (${text})`)
      const row = test.rows.find((r) => r.text.includes(TEST_CRID))
      if (!row || row.href !== TEST_LINK) fails.push(`test link (${row?.href})`)
    }

    const approval = sectionOf(board, '我的审批')
    if (!approval) fails.push('dev 审批 section missing')
    else {
      if (approval.rows.length !== 0) fails.push('dev approval group is not empty')
      if (!approval.empty.includes('暂无待审批')) fails.push(`approval empty (${approval.empty})`)
    }

    const execute = sectionOf(board, '待我执行')
    if (!execute) fails.push('dev 待执行 section missing')
    else {
      const text = rowsText(execute)
      if (!text.includes(EXEC_PUBLISH)) fails.push(`execute row missing ${EXEC_PUBLISH}`)
      if (!text.includes('待执行')) fails.push('execute row missing status label 待执行')
      if (!/已等待 [2-3] 小时/.test(text)) fails.push(`execute waiting copy (${text})`)
      const row = execute.rows.find((r) => r.text.includes(EXEC_PUBLISH))
      if (!row || row.href !== `/releases/${EXEC_PUBLISH}`) {
        fails.push(`execute link (${row?.href})`)
      }
    }

    const mine = sectionOf(board, '我发起的')
    if (!mine) fails.push('dev 我发起的 section missing')
    else {
      const text = rowsText(mine)
      if (!text.includes(MINE_TASK)) fails.push(`mine row missing ${MINE_TASK}`)
      if (!text.includes(NOW_TASK)) fails.push(`mine row missing ${NOW_TASK}`)
      if (!/已等待 2 小时/.test(text)) fails.push(`mine waiting copy (${text})`)
      const nowRow = mine.rows.find((r) => r.text.includes(NOW_TASK))
      if (!nowRow || !nowRow.text.includes('刚刚')) {
        fails.push(`mine zero-hours copy (${nowRow?.text ?? 'row missing'})`)
      }
      if (!/^\d+ 个未完成项$/.test(mine.subtitle)) {
        fails.push(`mine subtitle (${mine.subtitle})`)
      }
      if (mine.more && !mine.more.includes('个未完成变更项')) {
        fails.push(`mine cap note (${mine.more})`)
      }
    }

    // 1. Row click: the server link, not a client-built route.
    const clickedItem = await clickTodo(page, TEST_CRID)
    if (!clickedItem) fails.push('test row click found no link')
    else {
      if (clickedItem !== TEST_LINK) fails.push(`test row href (${clickedItem})`)
      const loaded = await page
        .waitForFunction(
          () => document.querySelector('.page-title')?.textContent?.trim() === 'DEMO-TODO-TEST',
          { timeout: 10000 }
        )
        .then(() => true)
        .catch(() => false)
      if (!loaded) fails.push('test row did not open the task detail')
    }

    board = await openTodos(page, theme)
    const clickedOrder = await clickTodo(page, EXEC_PUBLISH)
    if (!clickedOrder) fails.push('execute row click found no link')
    else {
      if (clickedOrder !== `/releases/${EXEC_PUBLISH}`) {
        fails.push(`execute row href (${clickedOrder})`)
      }
      const loaded = await page
        .waitForFunction(
          () => (document.querySelector('.page-title')?.textContent ?? '').includes('发布内容'),
          { timeout: 10000 }
        )
        .then(() => true)
        .catch(() => false)
      if (!loaded) fails.push('execute row did not open the release page')
    }

    board = await openTodos(page, theme)
    if (SHOTS) {
      await page.evaluate(() => window.scrollTo(0, 0))
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `todos-${theme}.png`) })
    }

    // --------------------------------------------------------- ops-lead: 待审批 + 待执行
    await setUser(page, 'ops-lead')
    let ops = await openTodos(page, theme)
    const opsCounts = ops.sections.map(countOf)
    const opsSum = opsCounts.reduce((a, b) => a + b, 0)
    if (String(opsSum) !== ops.badge) {
      fails.push(`ops-lead badge ${ops.badge} != sum ${opsSum}`)
    }
    const opsTest = sectionOf(ops, '我的待测试')
    if (!opsTest || !opsTest.empty.includes('暂无待测试')) {
      fails.push(`ops-lead test empty state (${opsTest?.empty})`)
    }
    const opsApproval = sectionOf(ops, '我的审批')
    if (!opsApproval || !rowsText(opsApproval).includes(APPROVAL_A)) {
      fails.push('ops-lead missing APPROVAL-A')
    } else {
      if (!opsApproval.rows.some((r) => r.href === `/releases/${APPROVAL_A}`)) {
        fails.push('ops-lead APPROVAL-A link missing')
      }
      if (!/已等待 [5-6] 小时/.test(rowsText(opsApproval))) {
        fails.push(`ops-lead approval waiting copy (${rowsText(opsApproval)})`)
      }
    }
    const opsMine = sectionOf(ops, '我发起的')
    if (!opsMine || !opsMine.empty.includes('暂无我发起的任务')) {
      fails.push(`ops-lead mine empty state (${opsMine?.empty})`)
    }
    if (!rowsText(sectionOf(ops, '待我执行')).includes(EXEC_PUBLISH)) {
      fails.push('ops-lead missing the execute demo row')
    }

    // ------------------------------------------------ dept-manager: only the DEPT_MANAGER step
    await setUser(page, 'dept-manager')
    let dept = await openTodos(page, theme)
    const deptCounts = dept.sections.map(countOf)
    const deptSum = deptCounts.reduce((a, b) => a + b, 0)
    if (String(deptSum) !== dept.badge) {
      fails.push(`dept-manager badge ${dept.badge} != sum ${deptSum}`)
    }
    const deptApproval = sectionOf(dept, '我的审批')
    if (!deptApproval || !rowsText(deptApproval).includes(APPROVAL_B)) {
      fails.push('dept-manager missing APPROVAL-B')
    }
    if (rowsText(deptApproval).includes(APPROVAL_A)) {
      fails.push('dept-manager sees APPROVAL-A before the OPS_LEAD signature')
    }
    for (const title of ['我的待测试', '待我执行', '我发起的']) {
      const section = sectionOf(dept, title)
      if (!section || section.rows.length !== 0) {
        fails.push(`dept-manager ${title} not empty`)
      }
    }

    // ---------------------------------------- demo-bot: fail-closed, four empty groups, no badge
    await setUser(page, EMPTY_USER)
    const empty = await openTodos(page, theme)
    if (empty.sections.length !== 0) fails.push('demo-bot rendered sections instead of the page empty state')
    if (!empty.emptyPage.includes('当前没有待办')) {
      fails.push(`demo-bot page empty state (${empty.emptyPage})`)
    }
    if (empty.badge !== '') fails.push(`demo-bot badge (${empty.badge})`)
    if (empty.navLabel !== '待办') fails.push(`demo-bot nav label (${empty.navLabel})`)
    if (empty.headerCount !== '共 0 项') fails.push(`demo-bot header count (${empty.headerCount})`)

    // Give the last route a beat to settle before collecting console errors.
    await sleep(200)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p5c-${theme}: dev=${devCounts.join('/')} badge=${board.badge} itemLink=yes orderLink=yes ` +
        `emptySegment=yes | ops=${opsCounts.join('/')} badge=${ops.badge} approvalA=yes ` +
        `empty=yes | dept=${deptCounts.join('/')} badge=${dept.badge} approvalB=yes ` +
        `noApprovalA=yes | empty=${EMPTY_USER} pageEmpty=yes badge=none | errors=${errors.length}`
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
console.log('OK all todo-centre assertions passed')