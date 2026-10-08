/* Licensed under the Apache License, Version 2.0. */
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
