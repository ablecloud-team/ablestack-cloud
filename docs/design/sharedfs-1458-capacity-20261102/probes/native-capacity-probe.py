# Design-only experiment; not imported or installed by the product.
from pathlib import Path
import os,sys,json,subprocess,tempfile,shutil,hashlib,time,stat,uuid
DMI='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
LIVE=Path('/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277')
def run(args,**kw):return subprocess.run(args,capture_output=True,text=True,timeout=30,check=True,**kw)
def flatten(rows):
 for row in rows:
  yield row
  yield from flatten(row.get('children') or [])
def main():
 assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()==DMI
 if '--inner'not in sys.argv:
  p=run(['unshare','--mount','--propagation','private',sys.executable,__file__,'--inner'])
  print(p.stdout);return
 run(['mount','--make-rprivate','/'])
 control=Path(tempfile.mkdtemp(prefix='epic1458-design-',dir='/run'))
 data=LIVE/('.epic1458-design-'+uuid.uuid4().hex);data.mkdir(mode=0o700)
 assert data.resolve().parent==LIVE.resolve()and data.name.startswith('.epic1458-design-')
 mounts=[];loops=[];checks=[]
 def passed(name,**value):checks.append({'name':name,'passed':True,**value})
 def mount(args,target):
  target.mkdir();run(['mount']+args+[str(target)]);mounts.append(target)
 try:
  images={}
  for name,size in [('ext4',128*1024**2),('raw',64*1024**2)]:
   path=data/(name+'.img')
   with path.open('xb')as f:f.truncate(size)
   assert path.stat().st_blocks*512<path.stat().st_size
   images[name]=path
  # Format a newly created owned regular file only, never the real DATA device.
  image=images['ext4'];info=image.lstat()
  assert image.parent==data and stat.S_ISREG(info.st_mode)and info.st_nlink==1
  run(['mkfs.ext4','-F','-q',str(image)])
  clone=data/'clone.img';run(['cp','--sparse=always',str(image),str(clone)]);images['clone']=clone
  for name,path in images.items():
   assert path.stat().st_blocks*512<path.stat().st_size
   dev=run(['losetup','--find','--show','--read-only',str(path)]).stdout.strip()
   loops.append(dev)
   backing=run(['losetup','--noheadings','--output','BACK-FILE',dev]).stdout.strip()
   assert Path(backing).resolve()==path.resolve()
   images[name]={'file':str(path),'device':dev,'bytes':path.stat().st_size,'allocatedBytes':path.stat().st_blocks*512}
  mount(['--bind',str(LIVE)],control/'live')
  run(['mount','-o','remount,bind,ro',str(control/'live')])
  mount(['--bind',str(LIVE/'epic1457/projects/team-a')],control/'live-alias')
  run(['mount','-o','remount,bind,ro',str(control/'live-alias')])
  mount(['-o','ro,noload',images['ext4']['device']],control/'ext4')
  mount(['--bind',str(control/'ext4')],control/'ext4-alias')
  mount(['-o','ro,noload',images['clone']['device']],control/'clone')
  rows=list(flatten(json.loads(run(['findmnt','-J','-o','SOURCE,TARGET,FSTYPE,FSROOT,UUID,MAJ:MIN']).stdout)['filesystems']))
  by_target={x['target']:x for x in rows}
  ids={}
  for name in ['live','live-alias','ext4','ext4-alias','clone']:
   row=by_target[str(control/name)];s=(control/name).stat()
   ids[name]={'uuid':row.get('uuid'),'dev':s.st_dev,'inode':s.st_ino,'fsroot':row.get('fsroot'),'fstype':row.get('fstype'),'root':str(control/name)}
  assert ids['live']['uuid']==ids['live-alias']['uuid']and ids['live']['dev']==ids['live-alias']['dev']
  assert ids['live']['fsroot']!=ids['live-alias']['fsroot']
  assert ids['ext4']['uuid']==ids['clone']['uuid']and ids['ext4']['dev']!=ids['clone']['dev']
  passed('real XFS subtree bind aliases share a filesystem identity with different FSROOT')
  passed('real EXT4 clones with equal UUID remain separate by device')
  fs_calls={}
  def fs_snapshot(identity):
   key=(identity['uuid'],identity['dev'])
   if key not in fs_calls:
    s=os.statvfs(identity['root'])
    fs_calls[key]={'used':(s.f_blocks-s.f_bfree)*s.f_frsize,'total':s.f_blocks*s.f_frsize}
   return key,fs_calls[key]
  observations={}
  for name in ids:observations[name]=fs_snapshot(ids[name])
  assert len(fs_calls)==3
  passed('statvfs is sampled once per unique filesystem, not per alias',calls=len(fs_calls))
  live_size=10737418240
  volumes=[{'id':'LIVE','vm':195,'type':'DATADISK','bytes':live_size,'observation':'live','display':True},
           {'id':'EXT4','vm':195,'type':'DATADISK','bytes':images['ext4']['bytes'],'observation':'ext4','display':False},
           {'id':'RAW','vm':195,'type':'DATADISK','bytes':images['raw']['bytes'],'observation':None,'display':False},
           {'id':'CLONE','vm':195,'type':'DATADISK','bytes':images['clone']['bytes'],'observation':'clone','display':True},
           {'id':'ROOT','vm':195,'type':'ROOT','bytes':5242880000,'observation':None,'display':True},
           {'id':'DETACHED','vm':None,'type':'DATADISK','bytes':images['raw']['bytes'],'observation':None,'display':True}]
  def aggregate(values,stale=False,changed=False):
   attached={v['id']:v for v in values if v['vm']==195 and v['type']=='DATADISK'}
   unique={observations[v['observation']][0]:observations[v['observation']][1]for v in attached.values()if v.get('observation')in observations}
   raw=sum(1 for v in attached.values()if v.get('observation')is None)
   return {'provisionedBytes':sum(v['bytes']for v in attached.values()),'volumeCount':len(attached),
           'filesystemUsedBytes':None if changed or not unique else sum(x['used']for x in unique.values()),
           'filesystemCount':len(unique),'unsupportedVolumeCount':raw,
           'usageStatus':'TRANSITION'if changed else('STALE'if stale else('PARTIAL'if raw and unique else('UNSUPPORTED'if raw else'CURRENT')))}
  result=aggregate(volumes)
  assert result['volumeCount']==4 and result['provisionedBytes']==live_size+320*1024**2
  assert result['unsupportedVolumeCount']==1 and result['filesystemCount']==3
  passed('attached DATA includes hidden and unreferenced volumes, excludes ROOT and preserved detached volume',summary=result)
  duplicates=volumes+[dict(volumes[0],observation='live-alias')]*10000+[dict(volumes[1],observation='ext4-alias')]*10000
  begin=time.monotonic();many=aggregate(duplicates);elapsed=time.monotonic()-begin
  assert many==result
  passed('20000 duplicate protocol or alias references cannot inflate volume/filesystem totals',seconds=elapsed)
  raw=aggregate([volumes[2]]);assert raw['filesystemUsedBytes']is None and raw['usageStatus']=='UNSUPPORTED'
  passed('raw block use is unsupported and null, never fabricated zero')
  stale=aggregate(volumes,stale=True);assert stale['usageStatus']=='STALE'and stale['filesystemUsedBytes']==result['filesystemUsedBytes']
  passed('stale values remain explicitly historical')
  changed=aggregate(volumes,changed=True);assert changed['usageStatus']=='TRANSITION'and changed['filesystemUsedBytes']is None
  passed('attachment generation mismatch invalidates old usage instead of showing zero')
  print(json.dumps({'success':True,'count':len(checks),'checks':checks,'identities':ids,'images':images,'productImplementation':False,'cloudResourceChanges':0,'notes':'Only LIVE is a real Cloud volume; other attachment metadata is injected test data. Private SPARSE loop files test real EXT4/raw/clone identity.'}))
 finally:
  cleanup=[]
  for target in reversed(mounts):
   r=subprocess.run(['umount',str(target)],capture_output=True)
   if r.returncode:cleanup.append(str(target))
  if cleanup:raise RuntimeError('Refusing recursive cleanup of mounted targets: '+str(cleanup))
  for dev in reversed(loops):
   subprocess.run(['losetup','-d',dev],capture_output=True,check=True)
  for target in mounts:
   assert subprocess.run(['findmnt','-n','-M',str(target)],capture_output=True).returncode!=0
  assert control.parent==Path('/run')and control.name.startswith('epic1458-design-')
  assert data.resolve().parent==LIVE.resolve()and data.name.startswith('.epic1458-design-')
  shutil.rmtree(data);shutil.rmtree(control)
if __name__=='__main__':main()
