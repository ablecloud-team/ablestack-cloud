import json,os,subprocess,shlex,sys
from pathlib import Path
root=Path('/home/ablecloud/work/snapshot-dialogs-1488-20261025')
def remote(c,args,host=None):
 user='root' if host else 'ablecloud'; addr='10.10.'+c+'.'+str(host or 10)
 options=['-o','StrictHostKeyChecking=yes']
 if c=='32' and host:options+=['-o','UserKnownHostsFile=/home/ablecloud/work/snapshot-archive-1477-20261023/trusted-hosts32']
 env={**os.environ,'SSHPASS':os.environ['HOST_SECRET' if host else 'SSH_SECRET']}
 try:return subprocess.check_output(['sshpass','-e','ssh','-n','-p','22']+options+[user+'@'+addr,shlex.join(args)],env=env,text=True).strip()
 except subprocess.CalledProcessError as e:raise RuntimeError('Remote command failed: '+addr+' exit '+str(e.returncode)) from None
def sql(c,q):return [json.loads(x) for x in remote(c,['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q]).splitlines()]
