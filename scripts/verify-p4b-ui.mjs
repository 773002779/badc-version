// Asserts the P4-B notification slice in a real browser (text + selectors + console error count;
// exit non-zero on any miss): the topbar bell badge and its 5-row dropdown, the /notifications
// centre (rows, the server-side 未读 filter, mark-one-read moving the badge) and the settings
// 「消息通知」 block (editable as dev, read-only as ops-lead).
//
//   node scripts/verify-p4b-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p4b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes docs/design/shots/notifications-{light,dark}.png and
// settings-notify-{light,dark}.png; the assertions run the same way with or without it. Run from
// the repository root so a relative --shots path lands in the repo.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when the
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH). The usual recipe:
//   copy scripts\verify-p4b-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p4b-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// The test marks one unread row read per theme, so re-run scripts/demo-notify.sql when the seeded
// unread rows run out.
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

const BELL_ROWS = 5
const SETTINGS_TEXT = ['消息通知', '企微机器人通知', '机器人 Webhook', '敏感配置', '站内通知']

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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

/** The unread badge as a number; an absent badge means zero unread. */
function badgeCount(page) {
  return page.evaluate(() => {
    const el = document.querySelector('.notify-badge')
    return el ? Number(el.textContent.trim()) || 0 : 0
  })
}

async function waitBadge(page, expected) {
  await page.waitForFunction(
    (want) => {
      const el = document.querySelector('.notify-badge')
      const got = el ? el.textContent.trim() : '0'
      return got === want
    },
    { timeout: 10000, polling: 200 },
    expected
  )
}

const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`

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

    // 1. The bell: badge, then the newest-5 dropdown on the dashboard.
    await page.goto(appendTheme(`${BASE}/`, theme), { waitUntil: 'networkidle2', timeout: 30000 })
    await page.waitForSelector('.notify-bell', { visible: true, timeout: 15000 })
    await sleep(600)
    const unreadBefore = await badgeCount(page)
    if (!(unreadBefore > 0)) fails.push(`bell badge (${unreadBefore})`)

    await page.click('.notify-bell')
    try {
      await page.waitForSelector('.bell-item', { visible: true, timeout: 8000 })
    } catch {
      // The counts below report the miss.
    }
    const bellRows = await page.$$eval('.bell-item', (els) => els.length)
    const bellTitle = await page
      .$eval('.bell-item .bell-title', (el) => el.textContent.trim())
      .catch(() => '')
    const moreText = await page
      .$eval('.notify-more', (el) => el.textContent.trim())
      .catch(() => '')
    if (bellRows !== BELL_ROWS) fails.push(`dropdown rows (${bellRows})`)
    if (!bellTitle) fails.push('dropdown title')
    if (moreText !== '查看全部') fails.push(`dropdown more (${moreText})`)

    // 2. 「查看全部」 really opens the notification centre.
    await page.click('.notify-more')
    await page.waitForFunction(() => location.pathname === '/notifications', { timeout: 10000 })
    await page.waitForSelector('.el-table__row', { visible: true, timeout: 15000 })
    await sleep(500)

    const allRows = await page.$$eval('.el-table__row', (els) => els.length)
    const totalShown = await page
      .$eval('.panel-head .weak.small', (el) => el.textContent.trim())
      .catch(() => '')
    if (allRows < 1) fails.push(`notification rows (${allRows})`)

    if (SHOTS) {
      await page.screenshot({ path: join(SHOTS, `notifications-${theme}.png`) })
    }

    // 3. The 未读 filter is server-side: fewer rows than 全部, and still some rows.
    const filterClicked = await clickByText(page, '.el-radio-button__inner', '未读')
    if (!filterClicked) fails.push('unread filter button')
    try {
      await page.waitForFunction(
        (max) => document.querySelectorAll('.el-table__row').length < max,
        { timeout: 10000 },
        allRows
      )
    } catch {
      // Reported below by the row comparison.
    }
    await sleep(400)
    const unreadRows = await page.$$eval('.el-table__row', (els) => els.length)
    if (!(unreadRows >= 1 && unreadRows < allRows)) {
      fails.push(`unread filter rows (${unreadRows}/${allRows})`)
    }

    // 4. 单条标记已读 reduces the bell badge immediately (shared revision, not the 30s poll).
    const beforeMark = await badgeCount(page)
    const markClicked = await clickByText(page, 'button', '标记已读')
    if (!markClicked) fails.push('mark read button')
    const expected = String(Math.max(0, beforeMark - 1))
    try {
      await waitBadge(page, expected)
    } catch {
      // Reported below by the before/after comparison.
    }
    const afterMark = await badgeCount(page)
    if (afterMark !== Number(expected)) fails.push(`mark read badge (${beforeMark}->${afterMark})`)

    // 5. The settings 「消息通知」 block renders for dev (ADMIN).
    await page.goto(appendTheme(`${BASE}/settings`, theme), {
      waitUntil: 'networkidle2',
      timeout: 30000
    })
    await page.waitForSelector('.settings-notify', { visible: true, timeout: 15000 })
    await sleep(700)
    const settingsText = await page.$eval('.settings-notify', (el) => el.innerText)
    const missingText = SETTINGS_TEXT.filter((t) => !settingsText.includes(t))
    if (missingText.length) fails.push(`settings text (${missingText.join(',')})`)

    if (SHOTS) {
      await page.screenshot({ path: join(SHOTS, `settings-notify-${theme}.png`) })
    }

    // 6. Without setting:manage (ops-lead) the same block is read-only and says why.
    await page.evaluate(() => localStorage.setItem('rp.userId', 'ops-lead'))
    await page.reload({ waitUntil: 'networkidle2', timeout: 30000 })
    let readOnlyText = ''
    try {
      await page.waitForSelector('.settings-notify .restricted', { visible: true, timeout: 10000 })
      readOnlyText = await page.$eval('.settings-notify', (el) => el.innerText)
    } catch {
      // Reported below.
    }
    const readOnlyOk =
      readOnlyText.includes('需要平台管理员权限') && readOnlyText.includes('无权读取')
    if (!readOnlyOk) fails.push('read-only settings block')
    await page.evaluate(() => localStorage.setItem('rp.userId', 'dev'))

    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `notifications-${theme}: badge=${unreadBefore} dropdown=${bellRows} ` +
        `rows=${allRows} unreadRows=${unreadRows} markRead=${beforeMark}->${afterMark} ` +
        `settings=${missingText.length ? 'MISSING ' + missingText.join(',') : 'ok'} ` +
        `readonly=${readOnlyOk ? 'ok' : 'MISSING'} ` +
        `total=${JSON.stringify(totalShown)} errors=${errors.length}`
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
console.log('OK all notification assertions passed')