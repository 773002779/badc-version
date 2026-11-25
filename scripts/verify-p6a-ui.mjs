// Asserts the P6-A second-party library pages in a real browser (texts + selectors + console
// error count; exit non-zero on any miss), in both themes:
//
//   * LIB unit 91001: 「制品坐标」 shows com.sf.demo:demo-sdk, the 「依赖单元」 block lists
//     demo-sdk-consumer (module_path = demo-sdk) and names MODULE_PATH as a heuristic; the edit
//     dialog carries both coordinate fields (prefilled);
//   * UNIT regression: unit 2 shows neither block, its edit dialog has no coordinate inputs, an
//     unchanged save still succeeds and leaves type/coordinates untouched (checked through the API);
//   * LIB release PO-DEMO-LIB: exactly three steps 构建 / 安全检测 / 入库, the three artifact checks,
//     the 豁免入库 marker and no 生产准入 / 验证 / 测试人员 / 回滚; 发布记录 lists both executions
//     (DONE and the FAILED ENGINE_UNAVAILABLE run);
//   * the 新建部署单元 form hides the coordinates for UNIT, shows two required fields for LIB and
//     rejects a blank / badly formatted coordinate before the request;
//   * 0 console errors.
//
//   node scripts/verify-p6a-ui.mjs http://127.0.0.1:5173
//   node scripts/verify-p6a-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
//
// --shots writes lib-{light,dark}.png (LIB unit detail) and lib-release-{light,dark}.png (LIB
// release). Run from the repository root so the relative --shots path lands in the repo. Data
// comes from scripts/demo-lib.sql; re-run that seed immediately before this probe.
//
// The repo has no root package.json for tooling, so `puppeteer-core` is only resolvable when this
// script sits in a scratch dir that has the dependency (ESM ignores NODE_PATH):
//   copy scripts\verify-p6a-ui.mjs %TEMP%\rp-shot-tools\
//   node %TEMP%\rp-shot-tools\verify-p6a-ui.mjs http://127.0.0.1:5173 --shots docs/design/shots
import puppeteer from 'puppeteer-core'
import { mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const SHOTS_FLAG = process.argv.indexOf('--shots')
const SHOTS = SHOTS_FLAG > 0 ? resolve(process.argv[SHOTS_FLAG + 1]) : null

/** Markers of scripts/demo-lib.sql. */
const LIB_UNIT = 91001
const UNIT = 2
const LIB_PUBLISH = 'PO-DEMO-LIB'
const LIB_DONE = 'PL-DEMO-LIB-DONE'
const LIB_FAIL = 'PL-DEMO-LIB-FAIL'
const GROUP = 'com.sf.demo'
const ARTIFACT = 'demo-sdk'
const STEP_LABELS = ['构建', '安全检测', '入库']

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const appendTheme = (path, theme) => `${path}${path.includes('?') ? '&' : '?'}theme=${theme}`
const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()

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

/** Clicks the element whose trimmed text is exactly `text`; returns whether it was found. */
function clickByText(page, selector, text) {
  return page.evaluate(
    (sel, label) => {
      const nodes = Array.from(document.querySelectorAll(sel))
      const hit = nodes.find((n) => (n.textContent ?? '').trim() === label && n.offsetParent !== null)
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
 * so the visible option is the one of the select just opened.
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

/** The visible `el-dialog` with this title, as text plus a helper to click its buttons. */
function visibleDialog(page, title) {
  return page.evaluate((wanted) => {
    const flat = (text) => (text ?? '').replace(/\s+/g, ' ').trim()
    const dialog = Array.from(document.querySelectorAll('.el-dialog')).find(
      (d) => d.offsetParent !== null && (d.querySelector('.el-dialog__title')?.textContent ?? '').trim() === wanted
    )
    return dialog
      ? {
          title: wanted,
          text: flat(dialog.innerText),
          buttons: Array.from(dialog.querySelectorAll('button')).map((b) => flat(b.textContent))
        }
      : null
  }, title)
}

async function clickDialogButton(page, title, label) {
  return page.evaluate(
    (wanted, text) => {
      const flat = (value) => (value ?? '').replace(/\s+/g, ' ').trim()
      const dialog = Array.from(document.querySelectorAll('.el-dialog')).find(
        (d) => d.offsetParent !== null && (d.querySelector('.el-dialog__title')?.textContent ?? '').trim() === wanted
      )
      if (!dialog) return false
      const hit = Array.from(dialog.querySelectorAll('button')).find((b) => flat(b.textContent) === text)
      if (!hit) return false
      hit.click()
      return true
    },
    title,
    label
  )
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

/** LIB unit detail: coordinate block, dependents heuristic and the edit-dialog fields. */
async function readLibUnit(page) {
  return page.evaluate(() => {
    const flat = (value) => (value ?? '').replace(/\s+/g, ' ').trim()
    const text = (sel) => flat(document.querySelector(sel)?.innerText)
    return {
      title: flat(document.querySelector('.page-title')?.textContent),
      coordinate: text('.lib-coordinates'),
      coordinateLine: flat(document.querySelector('.lib-coordinate')?.textContent),
      dependents: text('.lib-dependents'),
      source: text('.lib-source'),
      dependentsSubtitle: flat(document.querySelector('.lib-dependents .panel-head > .weak.small')?.textContent),
      rows: Array.from(document.querySelectorAll('.lib-dependents .el-table__row')).map((row) => ({
        text: flat(row.innerText),
        link: row.querySelector('a')?.getAttribute('href') ?? ''
      })),
      envDe: flat(document.querySelector('.grid')?.innerText).includes('环境 DE'),
      prerequisites: flat(document.querySelector('.grid')?.innerText).includes('发布前置条件')
    }
  })
}

/** LIB release: the three steps, summary, artifact checks, waiver and absent UNIT panels. */
async function readLibRelease(page) {
  return page.evaluate(() => {
    const flat = (value) => (value ?? '').replace(/\s+/g, ' ').trim()
    return {
    title: flat(document.querySelector('.page-title')?.textContent),
    isLib: !!document.querySelector('.lib-release'),
    steps: Array.from(document.querySelectorAll('.lib-release .steps .step')).map((step) => ({
      label: flat(step.querySelector('.label')?.textContent),
      sub: flat(step.querySelector('.sub')?.textContent)
    })),
    summary: flat(document.querySelector('.lib-release .summary')?.innerText),
    nodes: Array.from(document.querySelectorAll('.lib-release section.panel')).map((panel) =>
      flat(panel.querySelector('.panel-title')?.textContent)
    ),
    checks: Array.from(document.querySelectorAll('.lib-release .lib-check-row')).map((row) => flat(row.innerText)),
    waiver: flat(document.querySelector('.lib-release .lib-waiver')?.textContent),
    waiverNote: flat(document.querySelector('.lib-release .lib-waiver-note')?.innerText),
    checkHead: flat(document.querySelector('.lib-release .lib-check-head')?.innerText),
    hasAdmission: !!document.querySelector('.lib-release .admission'),
    hasVerify: !!document.querySelector('.lib-release .verify'),
    hasStop: Array.from(document.querySelectorAll('button')).some(
      (b) => flat(b.textContent) === '停止发布' && b.offsetParent !== null
    ),
    hasRollback: Array.from(document.querySelectorAll('button')).some(
      (b) => flat(b.textContent) === '进入生产回滚' && b.offsetParent !== null
    ),
    testGate: flat(document.querySelector('.lib-release .summary')?.innerText).includes('门禁（测试）')
    }
  })
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

    await setUser(page, 'dev')

    // ------------------------------------------------------- LIB unit detail + edit
    await open(page, `/units/${LIB_UNIT}`, theme)
    await waitPage(page, 'demo-sdk', '.lib-coordinates')
    await page.waitForSelector('.lib-dependents .el-table__row', { timeout: 20000 }).catch(() => {})
    await sleep(300)
    const libUnit = await readLibUnit(page)

    expect(libUnit.title === 'demo-sdk', `lib unit title (${libUnit.title})`)
    expect(libUnit.coordinate.includes('groupId'), 'lib coordinate label groupId')
    expect(libUnit.coordinate.includes('artifactId'), 'lib coordinate label artifactId')
    expect(libUnit.coordinate.includes(GROUP), `lib groupId (${libUnit.coordinate})`)
    expect(libUnit.coordinate.includes(ARTIFACT), `lib artifactId (${libUnit.coordinate})`)
    expect(libUnit.coordinateLine === `${GROUP}:${ARTIFACT}`, `lib coordinate line (${libUnit.coordinateLine})`)
    expect(libUnit.dependents.includes('依赖单元'), 'lib dependents title')
    expect(libUnit.dependentsSubtitle === '1 个', `lib dependents subtitle (${libUnit.dependentsSubtitle})`)
    expect(libUnit.source.includes('MODULE_PATH'), `lib source (${libUnit.source})`)
    expect(libUnit.source.includes('启发式'), `lib source heuristic note (${libUnit.source})`)
    expect(libUnit.rows.length === 1, `lib dependent rows (${libUnit.rows.length})`)
    expect(libUnit.rows[0]?.text.includes('demo-sdk-consumer'), `lib dependent name (${libUnit.rows[0]?.text})`)
    expect(libUnit.rows[0]?.text.includes('demo-sdk'), `lib dependent module path (${libUnit.rows[0]?.text})`)
    expect(libUnit.rows[0]?.link === '/units/91002', `lib dependent link (${libUnit.rows[0]?.link})`)
    expect(!libUnit.envDe, 'LIB still shows 环境 DE')
    expect(!libUnit.prerequisites, 'LIB still shows 发布前置条件')

    // Edit dialog: the LIB coordinates are prefilled and still required.
    const editOpened = await clickByText(page, '.page-actions button', '编辑')
    expect(editOpened, 'lib edit button')
    await sleep(500)
    const editDialog = await visibleDialog(page, '编辑部署单元')
    expect(!!editDialog, 'lib edit dialog did not open')
    const editCoords = await page.evaluate(() => ({
      group: document.querySelector('.el-dialog:has(.lib-group-input) .lib-group-input input')?.value ?? '',
      artifact: document.querySelector('.el-dialog:has(.lib-group-input) .lib-artifact-input input')?.value ?? ''
    }))
    expect(editCoords.group === GROUP, `lib edit group (${editCoords.group})`)
    expect(editCoords.artifact === ARTIFACT, `lib edit artifact (${editCoords.artifact})`)
    await clickDialogButton(page, '编辑部署单元', '取消')
    await sleep(300)

    if (SHOTS) {
      await page.evaluate(() => window.scrollTo(0, 0))
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `lib-${theme}.png`) })
    }

    // ----------------------------------------------- UNIT regression: the same page shape
    await open(page, `/units/${UNIT}`, theme)
    await waitPage(page, 'demo-api', '.grid')
    const unitPage = await page.evaluate(() => {
      const flat = (value) => (value ?? '').replace(/\s+/g, ' ').trim()
      return {
        hasCoordinates: !!document.querySelector('.lib-coordinates'),
        hasDependents: !!document.querySelector('.lib-dependents'),
        hasCoding: flat(document.querySelector('.grid')?.innerText).includes('CODING 任务绑定'),
        hasPrerequisites: flat(document.querySelector('.grid')?.innerText).includes('发布前置条件')
      }
    })
    expect(!unitPage.hasCoordinates, 'UNIT shows the LIB coordinates block')
    expect(!unitPage.hasDependents, 'UNIT shows the LIB dependents block')
    expect(unitPage.hasCoding, 'UNIT regression: CODING binding block missing')
    expect(unitPage.hasPrerequisites, 'UNIT regression: prerequisites block missing')

    const unitEditOpened = await clickByText(page, '.page-actions button', '编辑')
    expect(unitEditOpened, 'unit edit button')
    await sleep(500)
    const unitEdit = await visibleDialog(page, '编辑部署单元')
    expect(!!unitEdit, 'unit edit dialog did not open')
    expect(!(unitEdit?.text ?? '').includes('制品 groupId'), 'UNIT edit dialog shows LIB coordinates')
    const saved = await clickDialogButton(page, '编辑部署单元', '保存')
    expect(saved, 'unit edit save button')
    const saveOk = await waitMessage(page, '部署单元已保存')
    expect(saveOk, 'UNIT edit save did not succeed')
    // The unchanged save must leave the UNIT untouched: still UNIT, still no artifact.
    const unitAfter = await page.evaluate(async () => {
      const res = await fetch('/units/2', { headers: { 'X-User-Id': 'dev' } })
      return (await res.json()).data
    })
    expect(unitAfter?.type === 'UNIT', `UNIT regression: type became ${unitAfter?.type}`)
    expect(!unitAfter?.artifactGroupId && !unitAfter?.artifactArtifactId, 'UNIT regression: coordinates appeared')

    // ------------------------------------------------------------ LIB release page
    await open(page, `/releases/${LIB_PUBLISH}`, theme)
    await waitPage(page, `发布内容 ${LIB_PUBLISH}`, '.lib-release')
    await page.waitForSelector('.lib-release .lib-check-row', { timeout: 20000 }).catch(() => {})
    await sleep(350)
    const release = await readLibRelease(page)

    expect(release.isLib, 'LIB release missing the lib-release marker')
    expect(
      release.steps.map((s) => s.label).join('|') === STEP_LABELS.join('|'),
      `LIB steps (${release.steps.map((s) => s.label).join('|')})`
    )
    expect(release.steps.length === 3, `LIB step count (${release.steps.length})`)
    expect(release.summary.includes('二方库 · 制品发布'), `LIB summary kind (${release.summary})`)
    expect(release.summary.includes(`${GROUP}:${ARTIFACT}`), `LIB summary coordinate (${release.summary})`)
    expect(release.nodes.includes('构建'), `LIB node 构建 missing (${release.nodes.join('/')})`)
    expect(release.nodes.includes('安全检测'), `LIB node 安全检测 missing (${release.nodes.join('/')})`)
    expect(release.nodes.includes('入库'), `LIB node 入库 missing (${release.nodes.join('/')})`)
    expect(release.checks.length === 3, `LIB check rows (${release.checks.length})`)
    const checkText = release.checks.join('\n')
    expect(checkText.includes('Sonar 扫描'), 'LIB check Sonar')
    expect(checkText.includes('应用组件漏洞'), 'LIB check 组件漏洞')
    expect(checkText.includes('版本缺陷'), 'LIB check 版本缺陷')
    expect(release.waiver === '豁免入库', `LIB waiver marker (${release.waiver})`)
    expect(release.waiverNote.includes('豁免入库'), `LIB waiver note (${release.waiverNote})`)
    expect(release.waiverNote.includes('到期'), `LIB waiver expiry (${release.waiverNote})`)
    expect(release.checkHead.includes('制品安全检测'), `LIB check head (${release.checkHead})`)
    expect(!release.hasAdmission, 'LIB release shows the production admission panel')
    expect(!release.hasVerify, 'LIB release shows the verification/test panel')
    expect(!release.testGate, 'LIB release shows the test gate (testers)')
    expect(!release.hasStop, 'LIB release shows 停止发布')
    expect(!release.hasRollback, 'LIB release shows 进入生产回滚')

    if (SHOTS) {
      await page.evaluate(() => window.scrollTo(0, 0))
      await sleep(350)
      await page.screenshot({ path: join(SHOTS, `lib-release-${theme}.png`) })
    }

    // 发布记录 lists the DONE run and the FAILED ENGINE_UNAVAILABLE run.
    const recordsTab = await clickByText(page, '.el-tabs__item', '发布记录')
    expect(recordsTab, 'records tab')
    try {
      await page.waitForFunction(
        () => document.querySelectorAll('.el-tab-pane .el-table__row').length >= 2,
        { timeout: 10000 }
      )
    } catch {
      // reported by the row assertion below
    }
    const records = await page.$$eval('.el-tab-pane .el-table__row', (rows) =>
      rows.map((row) => (row.innerText ?? '').replace(/\s+/g, ' ').trim())
    )
    const recordText = records.join('\n')
    expect(recordText.includes(LIB_DONE), `records missing ${LIB_DONE}`)
    expect(recordText.includes(LIB_FAIL), `records missing ${LIB_FAIL}`)
    expect(recordText.includes('已完成'), 'records missing the DONE status label')
    expect(recordText.includes('失败'), 'records missing the FAILED status label')

    // ------------------------------------- create dialog: LIB coordinates required/checked
    await open(page, '/subsystems/2', theme)
    await waitPage(page, '', '.grid')
    await page.waitForFunction(
      () =>
        Array.from(document.querySelectorAll('.page-actions button')).some(
          (b) => (b.textContent ?? '').trim() === '新建部署单元'
        ),
      { timeout: 20000 }
    )
    await clickByText(page, '.page-actions button', '新建部署单元')
    await sleep(500)
    const createDialog = await visibleDialog(page, '新建部署单元')
    expect(!!createDialog, 'create dialog did not open')
    expect(!(createDialog?.text ?? '').includes('制品 groupId'), 'UNIT create form shows LIB coordinates')

    const typePicked = await selectOption(page, '.unit-type-select', '二方库')
    expect(typePicked, 'create type option 二方库')
    await page.waitForSelector('.lib-group-input input', { timeout: 10000 }).catch(() => {})
    const coordForm = await page.evaluate(() => {
      const flat = (value) => (value ?? '').replace(/\s+/g, ' ').trim()
      const items = Array.from(document.querySelectorAll('.el-dialog .el-form-item'))
        .filter((item) => item.offsetParent !== null)
        .map((item) => ({
          label: flat(item.querySelector('.el-form-item__label')?.textContent),
          required: item.classList.contains('is-required')
        }))
      return {
        text: flat(document.querySelector('.el-dialog')?.innerText),
        items,
        hasGroupInput: !!document.querySelector('.lib-group-input input'),
        hasArtifactInput: !!document.querySelector('.lib-artifact-input input')
      }
    })
    expect(coordForm.hasGroupInput, 'LIB create dialog missing 制品 groupId input')
    expect(coordForm.hasArtifactInput, 'LIB create dialog missing 制品 artifactId input')
    expect(coordForm.text.includes('仅字母、数字、点、下划线、连字符'), 'LIB create form missing the format hint')
    for (const label of ['制品 groupId', '制品 artifactId']) {
      const item = coordForm.items.find((i) => i.label === label)
      expect(!!item, `create form item ${label}`)
      expect(!!item?.required, `create form item ${label} not marked required`)
    }

    // Blank coordinates: blocked before any request, dialog stays open.
    await page.type('.unit-name-input input', 'probe-lib-unit')
    const blankClicked = await clickDialogButton(page, '新建部署单元', '创建')
    expect(blankClicked, 'create dialog 创建 button')
    const blankMsg = await waitMessage(page, '制品 groupId必填')
    expect(blankMsg, 'blank coordinate was not rejected')
    const dialogStillOpen = await visibleDialog(page, '新建部署单元')
    expect(!!dialogStillOpen, 'create dialog closed after a rejected coordinate')

    // Bad format: rejected with the documented character rule.
    await page.type('.lib-group-input input', 'com/sf demo')
    await page.type('.lib-artifact-input input', ARTIFACT)
    const badClicked = await clickDialogButton(page, '新建部署单元', '创建')
    expect(badClicked, 'create dialog 创建 button (bad format)')
    const badMsg = await waitMessage(page, '制品 groupId格式不合法')
    expect(badMsg, 'bad coordinate format was not rejected')

    // Switching back to UNIT hides the coordinates again (nothing is created).
    const backToUnit = await selectOption(page, '.unit-type-select', '部署单元')
    expect(backToUnit, 'create type option 部署单元')
    await sleep(400)
    const hiddenAgain = await page.evaluate(() => ({
      group: !!document.querySelector('.lib-group-input input'),
      artifact: !!document.querySelector('.lib-artifact-input input')
    }))
    expect(!hiddenAgain.group && !hiddenAgain.artifact, 'UNIT create form kept the coordinate inputs')
    await clickDialogButton(page, '新建部署单元', '取消')
    await sleep(300)

    // Give the last route a beat to settle before collecting console errors.
    await sleep(200)
    if (errors.length) fails.push(`console errors (${errors.length})`)
    if (fails.length) failures++

    lines.push(
      `p6a-${theme}: libUnit coords=${libUnit.coordinateLine} dependentRows=${libUnit.rows.length} ` +
        `source=${libUnit.source.includes('MODULE_PATH') ? 'MODULE_PATH' : '?'} unitRegression=yes ` +
        `steps=${release.steps.map((s) => s.label).join('/')} checks=${release.checks.length} ` +
        `waiver=${release.waiver || 'none'} noProdPanels=${!release.hasAdmission && !release.hasVerify} ` +
        `records=2 createGuard=blank+badFormat errors=${errors.length}`
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
console.log('OK all second-party library assertions passed')