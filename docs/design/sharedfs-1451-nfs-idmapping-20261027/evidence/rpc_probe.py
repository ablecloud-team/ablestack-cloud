import xdrlib,struct,socket,random,json
P=xdrlib.Packer
def opaque(v):
 p=P();p.pack_opaque(v if isinstance(v,bytes) else v.encode());return p.get_buffer()
def uint(v):return struct.pack('>I',v)
def u64(v):return struct.pack('>Q',v)
def rpc(ops,uid=1,gid=1,port=23049):
 body=opaque(b'probe')+uint(0)+uint(len(ops))+b''.join(ops)
 c=P();c.pack_uint(1);c.pack_string(b'probe');c.pack_uint(uid);c.pack_uint(gid);c.pack_array([gid],c.pack_uint)
 cred=c.get_buffer();xid=random.randint(1,2147483647)
 msg=b''.join([uint(xid),uint(0),uint(2),uint(100003),uint(4),uint(1),uint(1),opaque(cred),uint(0),uint(0),body])
 def recv(s,n):
  r=b''
  while len(r)<n:
   b=s.recv(n-len(r))
   if not b:raise RuntimeError('rpc eof')
   r+=b
  return r
 with socket.create_connection(('127.0.0.1',port),timeout=5) as s:
  s.settimeout(5);s.sendall(uint(0x80000000|len(msg))+msg);raw=b''
  while True:
   mark=struct.unpack('>I',recv(s,4))[0];raw+=recv(s,mark&0x7fffffff)
   if mark&0x80000000:break
 u=xdrlib.Unpacker(raw);assert u.unpack_uint()==xid;assert u.unpack_uint()==1;assert u.unpack_uint()==0
 u.unpack_uint();u.unpack_opaque();assert u.unpack_uint()==0
 status=u.unpack_uint();tag=u.unpack_opaque();count=u.unpack_uint();return u,status,count
def getattr_file(port=23049):
 ops=[uint(24),uint(15)+opaque('probe'),uint(15)+opaque('owner.txt'),uint(10),uint(9)+uint(2)+uint(0)+uint(0x32)]
 u,status,count=rpc(ops,port=port);handle=None;attrs={}
 for i in range(count):
  op=u.unpack_uint();code=u.unpack_uint()
  if code:raise RuntimeError('NFS op '+str(op)+' status '+str(code))
  if op==10:handle=u.unpack_opaque()
  if op==9:
   bitmap=u.unpack_array(u.unpack_uint);a=xdrlib.Unpacker(u.unpack_opaque())
   attrs={'mode':oct(a.unpack_uint()),'owner':a.unpack_string().decode(),'group':a.unpack_string().decode()}
 assert status==0;return attrs,handle
def read_file(handle,uid=1,port=23049):
 u,status,count=rpc([uint(22)+opaque(handle),uint(25)+bytes(16)+u64(0)+uint(4096)],uid,uid,port)
 results=[]
 for i in range(count):
  op=u.unpack_uint();code=u.unpack_uint()
  if code:return {'status':code}
  if op==25:return {'status':status,'eof':u.unpack_bool(),'data':u.unpack_opaque().decode()}
 return {'status':status}
if __name__=='__main__':
 import sys
 attrs,h=getattr_file(int(sys.argv[1]));print(json.dumps({'attrs':attrs,'readAsOwner':read_file(h,1,int(sys.argv[1])),'readAsOther':read_file(h,1001,int(sys.argv[1]))}))
