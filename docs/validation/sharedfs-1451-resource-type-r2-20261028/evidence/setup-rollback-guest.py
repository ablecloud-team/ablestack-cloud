from pathlib import Path
import json,sys,time,subprocess,hashlib
sys.path.insert(0,'/usr/local/lib/ablestack-storage')
from configuration_transaction import NfsIdentityMapping
ob=NfsIdentityMapping().inspect()
assert ob['observationstatus']=='APPLIED' and ob['effectivemode']=='NAME_DOMAIN'
target=[e for e in ob['endpoints'] if e['port']==2050]
assert len(target)==1
key=target[0]['endpoint'];assert key=='0.0.0.0_2050'
unit='ablestack-storage-ganesha@'+key+'.service'
stage=Path('/run/epic1451-idmap-rollback');assert not stage.exists();stage.mkdir(mode=0o700)
script=stage/'fail-once.py'
script.write_text("from pathlib import Path\nimport json,sys,time\nstage=Path('/run/epic1451-idmap-rollback')\nconfig=Path('/etc/ganesha/ablestack-storage/0.0.0.0_2050.conf')\nif (stage/'armed').exists() and 'Only_Numeric_Owners = true;' in config.read_text():\n (stage/'armed').unlink()\n (stage/'fired.json').write_text(json.dumps({'code':'E1451_SECOND_PORT_ONCE','at':time.time(),'exit':17}))\n sys.stderr.write('E1451_SECOND_PORT_ONCE\\n')\n raise SystemExit(17)\n")
compile(script.read_text(),str(script),'exec');script.chmod(0o700)
(stage/'armed').write_text('one expected NUMERIC candidate only')
drop=Path('/run/systemd/system')/(unit+'.d');assert not drop.exists();drop.mkdir(mode=0o755)
override=drop/'epic1451-once.conf';override.write_text('[Service]\nExecStartPre=/usr/bin/python3 '+str(script)+'\n')
subprocess.run(['systemctl','daemon-reload'],check=True)
pre=subprocess.check_output(['systemctl','show',unit,'-p','ExecStartPre','--value'],text=True)
assert str(script) in pre
print(json.dumps({'armed':True,'targetUnit':unit,'targetPort':2050,'beforeObservation':ob,'beforePid':subprocess.check_output(['systemctl','show',unit,'-p','MainPID','--value'],text=True).strip(),'trustedPayloadEdited':False}))
