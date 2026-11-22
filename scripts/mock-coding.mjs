// Local CODING stand-in for verifying the 对接配置 page without a real tenant.
//
//   node scripts/mock-coding.mjs          # listens on 127.0.0.1:18090
//   MOCK_CODING_PORT=18091 node scripts/mock-coding.mjs
//
// It answers the one endpoint the connectivity probe uses:
//   GET /open-api/project/{project}/job/list?type=BUILD|DEPLOY
//
// Backend requirement: the base URL is http://, so start it with
//   java -jar ... --release.engine.allow-insecure-http=true
// (never do that for a production connection; this file is a local aid only).

import http from 'node:http'

const port = Number(process.env.MOCK_CODING_PORT ?? 18090)

const jobs = {
  BUILD: [
    { Id: 'P-BUILD-QUOTA', Name: 'quota-api 构建', Type: 'BUILD' },
    { Id: 'P-BUILD-GATEWAY', Name: 'gateway 构建', Type: 'BUILD' }
  ],
  DEPLOY: [
    { Id: 'P-DEPLOY-TEST', Name: '测试部署', Type: 'DEPLOY' },
    { Id: 'P-DEPLOY-PROD', Name: '生产部署', Type: 'DEPLOY' },
    { Id: 'P-DEPLOY-DAILY', Name: '日常部署', Type: 'DEPLOY' }
  ]
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`)
  const jobList = /^\/open-api\/project\/([^/]+)\/job\/list$/.exec(url.pathname)
  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify(body))
  }

  if (req.method === 'GET' && jobList) {
    const auth = req.headers.authorization ?? ''
    if (!auth.startsWith('Bearer ') || auth === 'Bearer ') {
      send(401, { error: 'missing credential' })
      return
    }
    const type = (url.searchParams.get('type') ?? 'BUILD').toUpperCase()
    send(200, { Response: { Data: { Jobs: jobs[type] ?? [] } } })
    return
  }

  send(404, { error: `no mock for ${req.method} ${url.pathname}` })
})

server.listen(port, '127.0.0.1', () => {
  console.log(`mock CODING on http://127.0.0.1:${port} (project/job/list)`)
})