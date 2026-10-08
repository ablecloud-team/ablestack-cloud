// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

const path = require('path')
const base = require('../../../../ui/vue.config.js')
module.exports = {
  ...base,
  lintOnSave: false,
  outputDir: path.resolve(__dirname, '../../../../../kubernetes-ui-design-dist-20261008'),
  chainWebpack: config => {
    base.chainWebpack(config)
    config.module.rules.delete('eslint')
    const aliases = config.resolve.alias.entries()
    config.resolve.alias.clear()
    config.resolve.alias.set('@/api$', path.resolve(__dirname, 'fixture-api.js'))
    config.resolve.alias.set('@/utils/plugins$', path.resolve(__dirname, 'fixture-plugins.js'))
    Object.entries(aliases).filter(([key])=>!['@/api$','@/utils/plugins$'].includes(key)).forEach(([key,value])=>config.resolve.alias.set(key,value))
    config.entry('app').clear().add(path.resolve(__dirname, 'main.js'))
    config.plugin('html').tap(args => { args[0].template = path.resolve(__dirname, 'index.html'); return args })
  },
  devServer: { host: '127.0.0.1', port: 8775, allowedHosts: ['localhost', '127.0.0.1'] }
}
