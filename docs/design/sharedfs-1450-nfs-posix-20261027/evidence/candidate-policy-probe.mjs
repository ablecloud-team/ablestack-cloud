import assert from 'node:assert/strict'
import {recommendation,permissionDelta,mappingDelta,squashFlags} from '../mockup/policy.mjs'
const checks=[]
for (const policy of ['ROOT','NONE','ALL']) for (const ro of [false,true]) {
 const r=recommendation(policy,ro)
 assert.equal(r.owneruid,!ro&&policy!=='NONE'?65534:0)
 assert.equal(r.mode,!ro&&policy!=='NONE'?'0775':'0755')
 checks.push(policy+(ro?'_RO':'_RW'))
}
const baseline={owneruid:1234,ownergid:2345,mode:'0750',recursivepermission:true}
assert.deepEqual(permissionDelta(baseline,{...baseline,recursivepermission:false},false),{})
assert.deepEqual(permissionDelta(baseline,{...baseline,recursivepermission:false,readonly:true,rootsquash:false},true),{})
checks.push('unchanged_and_policy_only_fields_omitted')
assert.deepEqual(permissionDelta(baseline,{owneruid:0,ownergid:2345,mode:'0750'},true),{owneruid:0})
assert.deepEqual(permissionDelta({mode:'0775'},{mode:'775'},true),{})
checks.push('zero_is_preserved_and_mode_is_normalized')
assert.deepEqual(mappingDelta({anonuid:0,anongid:null},{anonuid:'0',anongid:2345}),{anongid:2345})
checks.push('mapping_independent_from_directory_permissions')
assert.deepEqual(squashFlags('ALL'),{rootsquash:true,allsquash:true})
checks.push('all_squash_is_unambiguous')
assert.deepEqual(recommendation('ALL',false,1234,2345),{owneruid:1234,ownergid:2345,mode:'0775',recursivepermission:false})
checks.push('custom_anonymous_recommendation')
console.log(JSON.stringify({designOnly:true,count:checks.length,checks,productFilesChanged:false},null,2))
