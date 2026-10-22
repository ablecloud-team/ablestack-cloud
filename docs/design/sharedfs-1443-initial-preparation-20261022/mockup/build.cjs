// SPDX-License-Identifier: Apache-2.0
const fs=require('fs'),path=require('path');
const ui=process.env.UI_ROOT||path.resolve(__dirname,'../../../../ui');
const sfc=require(path.join(ui,'node_modules/@vue/compiler-sfc')),less=require(path.join(ui,'node_modules/less')),webpack=require(path.join(ui,'node_modules/webpack'));
(async()=>{
 const d=sfc.parse(fs.readFileSync(path.join(ui,'src/components/view/MoldDialog.vue'),'utf8')).descriptor;
 fs.writeFileSync(path.join(__dirname,'MoldDialog.js'),d.script.content.replace('export default','window.DesignMoldDialog =')+';window.DesignMoldDialog.template='+JSON.stringify(d.template.content)+';');
 for(const [src,dst] of [['src/style/components/view/mold-dialog.less','mold-dialog.css'],['src/style/theme/tokens.less','mold-tokens.css']])fs.writeFileSync(path.join(__dirname,dst),(await less.render(fs.readFileSync(path.join(ui,src),'utf8'))).css);
 for(const [src,dst] of [['vue/dist/vue.global.prod.js','vue.global.prod.js'],['ant-design-vue/dist/antd.css','antd.css']])fs.copyFileSync(path.join(ui,'node_modules',src),path.join(__dirname,dst));
 webpack({mode:'development',entry:path.join(ui,'node_modules/ant-design-vue/lib/index.js'),output:{path:__dirname,filename:'antd-vue3.js',library:'antd'},externals:{vue:'Vue'},resolve:{modules:[path.join(ui,'node_modules'),'node_modules']},devtool:false},(e,s)=>{if(e||s.hasErrors()){console.error(e||s.toString({all:false,errors:true}));process.exitCode=1;}else console.log('Design-only mockup ready. Run python3 serve.py.');});
})().catch(e=>{console.error(e);process.exitCode=1;});