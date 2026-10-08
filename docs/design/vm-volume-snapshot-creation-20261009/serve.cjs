/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
*/

const http = require('http')
const fs = require('fs')
const path = require('path')
const root = __dirname
const contentTypes = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.json': 'application/json; charset=utf-8' }
http.createServer((request, response) => {
  const name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname)
  const file = path.resolve(root, '.' + (name === '/' ? '/mockup.html' : name))
  if (!file.startsWith(root + path.sep)) { response.writeHead(403).end(); return }
  fs.readFile(file, (error, data) => {
    if (error) { response.writeHead(404).end(); return }
    response.writeHead(200, { 'content-type': contentTypes[path.extname(file)] || 'text/plain; charset=utf-8' })
    response.end(data)
  })
}).listen(4189, '127.0.0.1', () => console.log('Design preview: http://127.0.0.1:4189/mockup.html'))
