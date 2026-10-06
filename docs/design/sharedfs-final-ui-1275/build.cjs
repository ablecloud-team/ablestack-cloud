/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file for details.
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 */
// Build only the offline design preview, using the repository UI dependencies.
const path = require('path')
const fs = require('fs')
const { createRequire } = require('module')
const repo = path.resolve(__dirname, '../../..')
const modules = process.env.MOLD_UI_NODE_MODULES || path.join(repo, 'ui/node_modules')
const fromUI = createRequire(path.join(modules, '../package.json'))
const webpack = fromUI('webpack')
const { VueLoaderPlugin } = fromUI('vue-loader')
const loader = name => fromUI.resolve(name)
const bundleLicense = fs.readFileSync(path.join(repo, "LICENSE.header"), "utf8").split(/\r?\n/).map(line => line.trimEnd()).join("\n").trim()
const config = {
  mode: 'production',
  entry: path.join(__dirname, 'src/main.js'),
  output: { path: path.join(__dirname, 'assets'), filename: 'preview.js' },
  resolve: { extensions: ['.js', '.vue'], alias: { '@': path.join(repo, 'ui/src'), vue: fromUI.resolve('vue/dist/vue.runtime.esm-bundler.js') }, modules: [modules, 'node_modules'] },
  resolveLoader: { modules: [modules] },
  module: { rules: [
    { test: /\.vue$/, loader: loader('vue-loader') },
    { test: /\.js$/, exclude: /node_modules/, loader: loader('babel-loader'), options: { babelrc: false, configFile: false, plugins: [loader('@babel/plugin-proposal-optional-chaining'), loader('@babel/plugin-proposal-nullish-coalescing-operator')] } },
    { test: /\.css$/, use: [loader('vue-style-loader'), loader('css-loader')] },
    { test: /\.less$/, use: [loader('vue-style-loader'), loader('css-loader'), { loader: loader('less-loader'), options: { javascriptEnabled: true } }] },
    { test: /\.scss$/, use: [loader('vue-style-loader'), loader('css-loader'), loader('sass-loader')] }
  ] },
  plugins: [new webpack.BannerPlugin({ banner: "/*!\n" + bundleLicense + "\n*/", raw: true, entryOnly: true }), new VueLoaderPlugin(), new webpack.DefinePlugin({ __VUE_OPTIONS_API__: true, __VUE_PROD_DEVTOOLS__: false, __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: false })],
  optimization: { minimize: true },
  performance: { hints: false },
  devtool: false
}
webpack(config, (error, stats) => {
  if (error || stats.hasErrors()) {
    console.error(error || stats.toString({ all: false, errors: true }))
    process.exitCode = 1
    return
  }
  const metadata = { vue: fromUI('vue/package.json').version, compilerSfc: fromUI('@vue/compiler-sfc/package.json').version, antDesignVue: fromUI('ant-design-vue/package.json').version, uiSource: '73dbd8258f762e454f8b8fcb3b14436b5540117b', entry: 'src/App.vue', shared: ['layouts/ResourceLayout.vue', 'Status.vue', 'style/vars.less', 'style/dark-mode.less', 'style/theme/*.less', 'components/view/MoldDialog.vue', 'style/components/view/mold-dialog.less'], offline: true }
  fs.writeFileSync(path.join(__dirname, 'assets/build-info.json'), JSON.stringify(metadata, null, 2) + '\n')
  console.log(stats.toString({ all: false, assets: true, timings: true, warnings: true }))
  console.log(JSON.stringify(metadata))
})
