import subprocess
from pathlib import Path
root=Path('/home/ablecloud/work/snapshot-template-1474-20261024');repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots')
cp=':'.join(str(Path('/home/ablecloud/.m2/repository/org/ow2/asm')/name/'9.7'/(name+'-9.7.jar')) for name in ['asm','asm-tree','asm-util','asm-analysis'])
subprocess.run(['javac','-cp',cp,'SnapshotMethodPatchAsm.java'],cwd=root,check=True)
p=subprocess.run(['java','-cp','.'+':'+cp,'SnapshotMethodPatchAsm',str(root/'ssvm32-secondary-original.jar'),str(repo/'services/secondary-storage/server/target/classes'),str(root/'compat32')],cwd=root,text=True,capture_output=True);print(p.stdout,p.stderr);p.check_returncode();(root/'compat32-asm.log').write_text(p.stdout)
