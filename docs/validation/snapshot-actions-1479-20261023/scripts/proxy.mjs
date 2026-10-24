// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
// Fixture-scoped transport faults. Business request bodies are forwarded unchanged.
import http from 'node:http'
import fs from 'node:fs'
const cluster = process.argv[2], port = Number(process.argv[3])
if (!['31', '32'].includes(cluster) || ![18031,18032].includes(port)) throw new Error('Unexpected proxy target')
const root = '/home/ablecloud/work/snapshot-actions-1479-20261023'
const file = `${root}/${cluster}-proxy-control.json`, log = `${root}/${cluster}-proxy.jsonl`
const jobs = new Set()
function control () { try { return JSON.parse(fs.readFileSync(file)) } catch (_) { return {} } }
const save = value => fs.appendFileSync(log, JSON.stringify({ at: new Date().toISOString(), ...value }) + '\n')
http.createServer(async (req, res) => {
 try {
  const chunks = []; for await (const chunk of req) chunks.push(chunk)
  const body = Buffer.concat(chunks)
  const url = new URL(req.url, `http://127.0.0.1:${port}`)
  const args = new URLSearchParams(url.search)
  if (req.method === 'POST') for (const [key, value] of new URLSearchParams(body.toString())) args.set(key.toLowerCase(), value)
  const cmd = args.get('command'), config = control()
  const ids = config.ids || []; const faults = (config.mode || '').split('+')
  const fixture = ['id', 'snapshotid', 'volumeid'].some(key => ids.includes(args.get(key))) || (args.get('name') || '').startsWith(`SNAP1479-${cluster}-`) || jobs.has(args.get('jobid'))
  const mutable = req.method === 'POST' && cmd && !/^(login|logout|list|query|find)/i.test(cmd) && args.get('dryrun') !== 'true'
  const info = { cmd, method: req.method, fixture, mutable, args: Object.fromEntries(['id','snapshotid','volumeid','name','zoneid','jobid','dryrun','customjobid'].filter(k=>args.has(k)).map(k=>[k,args.get(k)])) }
  if (fixture && cmd === 'queryAsyncJobResult' && faults.includes('block-query')) {
   save({ ...info, fault: 'query-unavailable', status: 503 }); res.writeHead(503, {'Content-Type':'application/json'}); res.end(JSON.stringify({errorresponse:{errorcode:503,errortext:'SNAP1479 fixture query transport unavailable'}})); return
  }
  const headers = {...req.headers, host:`10.10.${cluster}.10:8080`}; delete headers['accept-encoding']; delete headers['content-length']
  if (headers.origin) headers.origin = `http://10.10.${cluster}.10:8080`
  const upstream = http.request({ hostname: `10.10.${cluster}.10`, port:8080, path:req.url, method:req.method, headers }, upstream => {
   const pieces=[];upstream.on('data',chunk=>pieces.push(chunk));upstream.on('end',async()=>{
    const bytes=Buffer.concat(pieces);let data
    try {data=Object.values(JSON.parse(bytes))[0]}catch(_){}
    const jobid=data?.jobid
    if (fixture && jobid && mutable) jobs.add(jobid)
    if (fixture && cmd && !/^(login|logout)$/i.test(cmd)) save({...info,status:upstream.statusCode,jobid,jobstatus:data?.jobstatus,count:data?.count, fault: mutable && faults.includes('drop-submit') ? 'submit-response-dropped-after-forward' : undefined})
    if (fixture && mutable && faults.includes('drop-submit')) {res.destroy();return}
    if (fixture && cmd==='listSnapshots' && config.mode==='delay-read') await new Promise(resolve=>setTimeout(resolve,7000))
    const outgoing={...upstream.headers}; delete outgoing['content-length'];delete outgoing['transfer-encoding']
    res.writeHead(upstream.statusCode,outgoing);res.end(bytes)
   })
  });upstream.on('error',error=>{save({error:error.code});res.writeHead(502);res.end('fixture proxy transport error')});upstream.end(body)
 }catch(error){save({error:error.message});res.destroy()}
}).listen(port,'127.0.0.1',()=>console.log(`SNAP1479 ${cluster} fixture proxy listening on 127.0.0.1:${port}`))
