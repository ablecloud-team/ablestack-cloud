// Design-only model: not imported by product code.
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
const modes=new Set(['NAME_DOMAIN','NUMERIC'])
export function policy(c={}){const mode=c.nfs4IdentityMappingMode??'NAME_DOMAIN',revision=c.nfs4IdentityPolicyRevision??0;if(!modes.has(mode)||!Number.isSafeInteger(revision)||revision<0)throw Error('INVALID_IDENTITY_POLICY');return{mode,revision}}
export function update(rows,mode,revision,security=['sys']){if(!modes.has(mode))throw Error('INVALID_MODE');if(mode==='NUMERIC'&&(security.length!==1||security[0]!=='sys'))throw Error('AUTH_SYS_REQUIRED');if(!rows.some(r=>r.protocol==='NFS'))throw Error('NFS_NOT_ENABLED');return rows.map(r=>r.protocol==='NFS'?{...r,config:{...r.config,nfs4IdentityMappingMode:mode,nfs4IdentityPolicyRevision:revision}}:structuredClone(r))}
export function digest(instance,revision,rows,target){return createHash('sha256').update(JSON.stringify({instance,revision,policies:rows.filter(r=>r.protocol==='NFS').map(r=>[r.id,policy(r.config)]).sort(),target})).digest('hex')}
export function observed(declared,receipt,process,parsed){if(!receipt)return{mode:null,status:'LEGACY_UNCONFIRMED'};if(['pid','boot','start'].some(k=>receipt[k]!==process[k]))return{mode:null,status:'UNKNOWN'};if(receipt.mode!==parsed.mode||receipt.revision!==declared.revision||receipt.mode!==declared.mode)return{mode:receipt.mode,status:'DRIFT'};return{mode:receipt.mode,status:'APPLIED'}}
let count=0;const check=(name,fn)=>{fn();count++}
const actual=JSON.parse(process.argv.length>2?await (await import('node:fs/promises')).readFile(process.argv[2],'utf8'):'{}')
const source=[{id:'nfs-live',protocol:'NFS',config:actual},{id:'smb-keep',protocol:'SMB',config:{listenerGroups:[{port:445}],marker:'unchanged'}}]
check('missing means legacy NAME_DOMAIN/0',()=>assert.deepEqual(policy(actual),{mode:'NAME_DOMAIN',revision:0}))
check('invalid mode rejects',()=>assert.throws(()=>policy({nfs4IdentityMappingMode:'UNKNOWN'})))
check('invalid security rejects',()=>assert.throws(()=>update(source,'NUMERIC',7,['krb5'])))
check('NFS not enabled rejects',()=>assert.throws(()=>update([source[1]],'NUMERIC',7)))
const changed=update(source,'NUMERIC',7)
check('NFS mode revision set',()=>assert.deepEqual(policy(changed[0].config),{mode:'NUMERIC',revision:7}))
check('listener config retained',()=>assert.deepEqual(changed[0].config.listenerGroups,source[0].config.listenerGroups))
check('protocol mode retained',()=>assert.equal(changed[0].config.protocolMode,source[0].config.protocolMode))
check('SMB untouched',()=>assert.deepEqual(changed[1],source[1]))
check('input untouched',()=>assert.equal(source[0].config.nfs4IdentityMappingMode,undefined))
check('all NFS rows same',()=>{const r=update([...source,{id:'nfs-disabled',protocol:'NFS',state:'Disabled',config:{...actual}}],'NUMERIC',7);assert.deepEqual(policy(r[2].config),policy(r[0].config));assert.equal(r[2].state,'Disabled')})
check('new endpoint inherits policy only',()=>{const c={protocolMode:'V4_ONLY',listenerGroups:[{port:2050}],nfs4IdentityMappingMode:changed[0].config.nfs4IdentityMappingMode,nfs4IdentityPolicyRevision:changed[0].config.nfs4IdentityPolicyRevision};assert.deepEqual(policy(c),{mode:'NUMERIC',revision:7});assert.equal(c.listenerGroups[0].port,2050)})
check('ack is target-bound',()=>assert.notEqual(digest('instance',12,source,'NUMERIC'),digest('instance',12,source,'NAME_DOMAIN')))
check('ack is revision-bound',()=>assert.notEqual(digest('instance',12,source,'NUMERIC'),digest('instance',13,source,'NUMERIC')))
check('no receipt never claims applied',()=>assert.equal(observed({mode:'NAME_DOMAIN',revision:0},null,{},{}).status,'LEGACY_UNCONFIRMED'))
const receipt={pid:1,boot:'boot',start:20,mode:'NUMERIC',revision:7},identity={pid:1,boot:'boot',start:20}
check('valid process and policy applied',()=>assert.equal(observed({mode:'NUMERIC',revision:7},receipt,identity,{mode:'NUMERIC'}).status,'APPLIED'))
check('new PID invalidates observation',()=>assert.equal(observed({mode:'NUMERIC',revision:7},receipt,{...identity,pid:2},{mode:'NUMERIC'}).status,'UNKNOWN'))
check('HUP file change does not impersonate active mode',()=>assert.equal(observed({mode:'NUMERIC',revision:7},{...receipt,mode:'NAME_DOMAIN'},identity,{mode:'NUMERIC'}).status,'DRIFT'))
check('legacy raw before config and explicit NAME equivalent',()=>assert.equal(observed({mode:'NAME_DOMAIN',revision:0},{...receipt,mode:'NAME_DOMAIN',revision:0},identity,policy({})).status,'APPLIED'))
console.log(JSON.stringify({designModelChecks:count,pass:true,productCodeModified:false,actualProtocolConfigUsed:actual}))
