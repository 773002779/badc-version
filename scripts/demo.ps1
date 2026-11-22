#requires -Version 5.1
<#
.SYNOPSIS
    跑一遍一期完整链路：子系统 → 部署单元 → CODING 任务绑定 → 环境 DE → 任务 → 变更项 → 发布 → 查询。

.DESCRIPTION
    用本地账号 dev（start.ps1 已播种）通过 HTTP 调接口，逐步打印返回。
    没有配置 CODING 连接时，发布会停在构建阶段并显示 ENGINE_UNAVAILABLE —— 这是一期的预期结果，
    用来证明链路、状态机、锁和查询都通了；接上真实 CODING 后同一条链路会走完验证确认，并停在生产部署前（PAUSED，P3）。

.PARAMETER BaseUrl
    服务地址，默认 http://127.0.0.1:8080。

.PARAMETER User
    调用身份（X-User-Id），默认 dev。
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$User = 'dev'
)

$ErrorActionPreference = 'Stop'
$headers = @{ 'X-User-Id' = $User }
$stamp = Get-Date -Format 'MMddHHmmss'
$planDate = (Get-Date).AddDays(7).ToString('yyyy-MM-dd')

function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body
    )
    $uri = "$BaseUrl$Path"
    $params = @{
        Method      = $Method
        Uri         = $uri
        Headers     = $headers
        ContentType = 'application/json; charset=utf-8'
    }
    if ($null -ne $Body) {
        $params['Body'] = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }
    try {
        $resp = Invoke-RestMethod @params
    } catch {
        Say "✗ $Method $Path 请求失败：$($_.Exception.Message)" 'Red'
        throw
    }
    if ($resp.code -ne 0) {
        Say "✗ $Method $Path -> code=$($resp.code) $($resp.message)" 'Red'
        if ($null -ne $resp.data) { Say ("  data: " + ($resp.data | ConvertTo-Json -Depth 6 -Compress)) 'DarkYellow' }
        throw "业务错误 $($resp.code)"
    }
    return $resp.data
}

Say "== 0. 连通性 ==" 'Cyan'
try {
    $null = Invoke-Api GET '/subsystems?page=1&size=1'
} catch {
    Say "服务未就绪，先运行 scripts\start.ps1。地址：$BaseUrl" 'Red'
    exit 1
}
Say '  ✓ 服务可达'

Say "== 1. 创建子系统 ==" 'Cyan'
$subsystemId = Invoke-Api POST '/subsystems' @{
    name        = "DEMO-SUBSYSTEM-$stamp"
    ownerId     = $User
    description = 'demo'
}
Say "  subsystemId = $subsystemId"

Say "== 2. 创建部署单元 ==" 'Cyan'
$unitId = Invoke-Api POST '/units' @{
    subsystemId      = $subsystemId
    name             = "demo-api-$stamp"
    ownerId          = $User
    type             = 'UNIT'
    frameworkTemplate= 'spring-boot'
    branchMode       = 'STANDARD'
    modulePath       = 'demo-api'
}
Say "  unitId = $unitId"

Say "== 3. 绑定 CODING 任务（BUILD 的分支是变更项的唯一来源）==" 'Cyan'
$null = Invoke-Api PUT "/units/$unitId/coding-tasks" @{
    bindings = @(
        @{ taskKind = 'BUILD';       taskId = 'demo-build';       paramsJson = '{"branch":"release/demo"}' },
        @{ taskKind = 'TEST_DEPLOY'; taskId = 'demo-test-deploy' },
        @{ taskKind = 'PROD_DEPLOY'; taskId = 'demo-prod-deploy' }
    )
}
Say '  已绑定 BUILD / TEST_DEPLOY / PROD_DEPLOY'

Say "== 4. 创建环境 DE（发布要求至少 1 个测试 DE、1 个生产 DE）==" 'Cyan'
$null = Invoke-Api POST "/units/$unitId/env-des" @{
    envKind = 'TEST'; envTag = 'sit'; deName = "demo-sit-$stamp"; deployBatchNo = 0
}
$null = Invoke-Api POST "/units/$unitId/env-des" @{
    envKind = 'PROD'; envTag = 'prod'; deName = "demo-prod-$stamp"; deployBatchNo = 1
}
$des = Invoke-Api GET "/units/$unitId/env-des"
Say ("  DE: " + (($des | ForEach-Object { "$($_.envKind)/$($_.deName)" }) -join ', '))

Say "== 5. 创建发布任务 ==" 'Cyan'
$taskId = Invoke-Api POST '/tasks' @{
    name           = "DEMO-$stamp"
    subsystemId    = $subsystemId
    planFinishDate = $planDate
    purpose        = '新功能'
}
Say "  taskId = $taskId"

Say "== 6. 创建变更项（分支由 BUILD 绑定带出，客户端不能指定）==" 'Cyan'
$item = Invoke-Api POST '/api/v1/items' @{
    taskId          = $taskId
    unitId          = $unitId
    version         = 'V0.0.1-demo'
    planReleaseDate = $planDate
}
Say "  itemId = $($item.itemId)  crid = $($item.crid)"

Say "== 7. 发布 ==" 'Cyan'
$publish = Invoke-Api POST "/api/v1/items/$($item.itemId)/publish" $null
Say "  publishNo = $($publish.publishNo)  pipelineNo = $($publish.pipelineNo)"

Start-Sleep -Seconds 2

Say "== 8. 查询发布内容 ==" 'Cyan'
$detail = Invoke-Api GET "/api/v1/releases/$($publish.publishNo)"
Say "  状态     : $($detail.status)"
Say "  门禁     : $($detail.gateStatus) / 测试 $($detail.testStatus)"
Say "  触发方式 : $($detail.triggerType)"
Say "  镜像摘要 : $($detail.imageDigest)"
foreach ($node in $detail.nodes) {
    $tasks = ($node.tasks | ForEach-Object { "$($_.taskType)=$($_.status)" }) -join ', '
    Say ("  - {0,-12} {1,-8} {2}" -f $node.nodeType, $node.status, $tasks)
}
$records = Invoke-Api GET "/api/v1/releases/$($publish.publishNo)/records"
Say ("  执行记录 : " + (($records | ForEach-Object { "$($_.pipelineNo)/$($_.triggerType)/$($_.status)" }) -join ', '))

Say ''
if ($detail.status -eq 'FAILED' -and -not $detail.imageDigest) {
    Say '结果说明：构建阶段的引擎调用失败（没有配置 CODING 连接），实例按设计置 FAILED。' 'Yellow'
    Say '          链路本身是通的：任务→变更项→发布单→4 节点→查询 都成功了。' 'Yellow'
    Say '          接上真实 CODING（设置页「CODING 对接」+ 端点模板）后，同一条链路会继续构建、部署测试环境，' 'Yellow'
    Say '          进入验证确认跑七项检查，并在生产部署前停住（P3）。' 'Yellow'
} elseif ($detail.status -eq 'PAUSED') {
    Say '结果说明：构建、测试部署与门禁都已完成，流水线停在 PROD_DEPLOY 之前（PAUSED，生产部署为 P3）。' 'Yellow'
} else {
    Say "结果说明：实例状态 $($detail.status)。" 'Yellow'
}