// Asserts the P5-A daily-release slice in a real browser (texts + selectors + console error count;
// exit non-zero on any miss): the 变更项 row 「日常」 action, the confirmation that spells out the
// daily boundary, the 日常发布 records block (columns, seeded DONE/FAILED rows, pager), the detail
// drawer (both nodes, their tasks, engine log links opening in a new tab) and the disabled action
// for a caller without `release:daily` (ops-lead), in both themes.
//
//   node scripts/verify-p5a-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p5a-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes daily-{light,dark}.png (drawer open on the seeded DONE record); the assertions run
// the same way with or without it. Run from the repository root so a relative --shots path lands in
// the repo.
//
// Data: scripts/demo-daily.sql seeds two daily instances of item 6 (unit 2): PL-DEMO-DAILY-DONE
// with BUILD + DAILY_DEPLOY and engine log links, PL-DEMO-DAILY-FAIL with a failed CODE_CHECK and a
// PENDING deploy. The real-path evidence (one API-started daily failing with ENGINE_UNAVAILABLE)
// lives on item 2; this probe only reads.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p5a-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p5a-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

/** The demo task/items the seed rows belong to (scripts/demo-daily.sql). */
const TASK_ID = 2
const DEMO_CRID = 'CR-DEMO-RB-NEW'
const DEMO_VERSION = 'V0.0.9-demo'
const DONE_NO = 'PL-DEMO-DAILY-DONE'
const FAIL_NO = 'PL-DEMO-DAILY-FAIL'
const DAILY_COLUMNS = ['流水线', '时间', '执行人', '分支', 'commit', '结果', '日志']
const NODE_LABELS = ['构建', '日常部署']
const TASK_LABELS = ['代码检查', '打包构建', '镜像构建', '部署']

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

/** The `日常` button of the 变更项 row identified by its CRID. */
function dailyButton(page, crid) {
  return page.evaluate((wanted) => {
    const section = document.querySelector('.items-block')
    if (!section) return { found: false, reason: 'items block' }
    const rows = Array.from(section.querySelectorAll('.el-table__row'))
    const row = rows.find((r) => (r.innerText ?? '').includes(wanted))
    if (!row) return { found: false, reason: 'row' }
    const button = Array.from(row.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').trim() === '日常'
    )
    const records = Array.from(row.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').trim() === '记录'
    )
    if (!button) return { found: true, hasDaily: false }
    return {
      found: true,
      hasDaily: true,
      hasRecords: Boolean(records),
      disabled: button.disabled,
      title: button.getAttribute('title') ?? '',
      rowText: (row.innerText ?? '').replace(/\s+/g, ' ').trim()
    }
  }, crid)
}

/** Clicks the `日常` button of the given row; false when it is absent. */
function clickDaily(page, crid) {
  return page.evaluate((wanted) => {
    const rows = Array.from(document.querySelectorAll('.items-block .el-table__row'))
    const row = rows.find((r) => (r.innerText ?? '').includes(wanted))
    if (!row) return false
    const button = Array.from(row.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').trim() === '日常'
    )
    if (!button) return false
    button.click()
    return true
  }, crid)
}

/** Clicks the 查看 action of the daily row carrying `pipelineNo` (or the row itself). */
function openDailyRecord(page, pipelineNo) {
  return page.evaluate((wanted) => {
    const rows = Array.from(document.querySelectorAll('.daily-block .el-table__row'))
    const row = rows.find((r) => (r.innerText ?? '').includes(wanted))
    if (!row) return false
    const button = Array.from(row.querySelectorAll('button')).find(
      (b) => (b.textContent ?? '').trim() === '查看'
    )
    if (button) button.click()
    else row.click()
    return true
  }, pipelineNo)
}

function headerCells(page, scope) {
  return page.$$eval(`${scope} .el-table__header th .cell`, (els) =>
    els.map((el) => (el.textContent ?? '').trim())
  )
}

function rowTexts(page, scope) {
  return page.$$eval(`${scope} .el-table__row`, (els) =>
    els.map((el) => (el.innerText ?? '').replace(/\s+/g, ' ').trim())
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

    // 1. The 变更项 row carries an enabled 「日常」 action for dev (release:daily).
    await page.goto(appendTheme(`${BASE}/tasks/${TASK_ID}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.items-block .el-table__row', { visible: true, timeout: 20000 })
    await page.waitForSelector('.daily-block .daily-table .el-table__row', {
      visible: true,
      timeout: 20000
    })
    await sleep(400)

    const button = await dailyButton(page, DEMO_CRID)
    if (!button.found || !button.hasDaily) fails.push(`daily button (${JSON.stringify(button)})`)
    else if (button.disabled) fails.push('daily button disabled for dev')
    if (!button.hasRecords) fails.push('daily records action')
    if (button.title && !button.title.includes('不占锁')) fails.push(`daily title (${button.title})`)

    // 2. Click → the confirmation explains the boundary; cancel, so no instance is created.
    const clicked = await clickDaily(page, DEMO_CRID)
    if (!clicked) fails.push('daily click')
    let boxText = ''
    try {
      await page.waitForSelector('.el-message-box', { visible: true, timeout: 8000 })
      await sleep(250)
      boxText = await page.$eval('.el-message-box', (el) =>
        (el.innerText ?? '').replace(/\s+/g, ' ').trim()
      )
    } catch {
      // Reported below.
    }
    for (const phrase of ['日常发布确认', '不占锁', '不改变更项状态', '不生成发布单', '并行', DEMO_VERSION]) {
      if (!boxText.includes(phrase)) fails.push(`confirm text (${phrase})`)
    }
    const canceled = await page.evaluate(() => {
      const button = Array.from(document.querySelectorAll('.el-message-box button')).find(
        (b) => (b.textContent ?? '').trim() === '取消'
      )
      if (!button) return false
      button.click()
      return true
    })
    if (!canceled) fails.push('confirm cancel')
    await sleep(300)

    // 3. The records block: columns, both seeded rows, statuses, requester and paging.
    const subtitle = await page
      .$eval('.daily-block .panel-head .weak.small', (el) => el.textContent.trim())
      .catch(() => '')
    if (!subtitle.includes(DEMO_CRID) || !/共 2 条/.test(subtitle)) fails.push(`daily subtitle (${subtitle})`)

    const columns = await headerCells(page, '.daily-block')
    const missingColumns = DAILY_COLUMNS.filter((c) => !columns.includes(c))
    if (missingColumns.length) fails.push(`daily columns (${missingColumns.join(',')})`)

    const rows = await rowTexts(page, '.daily-block')
    if (rows.length !== 2) fails.push(`daily rows (${rows.length})`)
    const doneRow = rows.find((t) => t.includes(DONE_NO)) ?? ''
    const failRow = rows.find((t) => t.includes(FAIL_NO)) ?? ''
    if (!doneRow) fails.push('daily done row')
    if (!failRow) fails.push('daily failed row')
    if (!doneRow.includes('已完成') || !doneRow.includes('dev')) fails.push(`done row (${doneRow})`)
    if (!failRow.includes('失败') || !failRow.includes('qa-zhang')) fails.push(`failed row (${failRow})`)
    if (!doneRow.includes('3c9e5a7b1d')) fails.push('done commit')

    // 4. The detail drawer: both nodes, their tasks, and the engine log links.
    const opened = await openDailyRecord(page, DONE_NO)
    if (!opened) fails.push('open daily detail')
    try {
      await page.waitForSelector('.daily-drawer .daily-node', { visible: true, timeout: 15000 })
      await sleep(500)
    } catch {
      // Reported below.
    }

    const detail = await page.evaluate(() => {
      const drawer = document.querySelector('.daily-drawer')
      if (!drawer) return null
      const title =
        drawer.querySelector('.el-drawer__title')?.textContent?.trim() ??
        drawer.querySelector('.el-drawer__header')?.textContent?.trim() ??
        ''
      return {
        title,
        nodes: Array.from(drawer.querySelectorAll('.daily-node')).map((n) =>
          (n.innerText ?? '').replace(/\s+/g, ' ').trim()
        ),
        drawerText: (drawer.innerText ?? '').replace(/\s+/g, ' ').trim(),
        links: Array.from(drawer.querySelectorAll('a.log-link')).map((a) => ({
          href: a.getAttribute('href') ?? '',
          target: a.getAttribute('target') ?? '',
          text: (a.textContent ?? '').trim()
        }))
      }
    })
    if (!detail) {
      fails.push('drawer missing')
    } else {
      if (!detail.title.includes(DONE_NO)) fails.push(`drawer title (${detail.title})`)
      if (detail.nodes.length !== 2) fails.push(`drawer nodes (${detail.nodes.length})`)
      for (const label of NODE_LABELS) {
        if (!detail.nodes.some((n) => n.includes(label))) fails.push(`node label (${label})`)
      }
      for (const label of TASK_LABELS) {
        if (!detail.drawerText.includes(label)) fails.push(`task label (${label})`)
      }
      if (!detail.drawerText.includes('release/20261119')) fails.push('drawer branch')
      if (!detail.drawerText.includes('3c9e5a7b1d')) fails.push('drawer commit')
      if (!detail.drawerText.includes('不占部署锁')) fails.push('drawer boundary note')
      if (detail.links.length < 3) fails.push(`log links (${detail.links.length})`)
      const badLink = detail.links.find(
        (l) => l.target !== '_blank' || !l.href.startsWith('https://') || l.text !== '打开日志'
      )
      if (badLink) fails.push(`log link (${JSON.stringify(badLink)})`)
    }

    if (SHOTS) {
      // Bring the records block (behind the drawer) into view for the shareable screenshot.
      await page.$eval('.daily-block', (el) => el.scrollIntoView({ block: 'center' }))
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `daily-${theme}.png`) })
    }

    // 5. Without release:daily (ops-lead) the same action is disabled with the reason.
    await page.evaluate(() => localStorage.setItem('rp.userId', 'ops-lead'))
    await page.goto(appendTheme(`${BASE}/tasks/${TASK_ID}`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.items-block .el-table__row', { visible: true, timeout: 20000 })
    await sleep(400)
    const denied = await dailyButton(page, DEMO_CRID)
    if (!denied.found || !denied.hasDaily) fails.push(`denied button (${JSON.stringify(denied)})`)
    else {
      if (!denied.disabled) fails.push('denied button enabled for ops-lead')
      if (!denied.title.includes('release:daily')) fails.push(`denied title (${denied.title})`)
    }
    await page.evaluate(() => localStorage.setItem('rp.userId', 'dev'))

    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `daily-${theme}: rows=${rows.length} done=${doneRow ? 'yes' : 'no'} ` +
        `fail=${failRow ? 'yes' : 'no'} nodes=${detail ? detail.nodes.length : 0} ` +
        `links=${detail ? detail.links.length : 0} denied=${denied.disabled} ` +
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
console.log('OK all daily assertions passed')