import http.server,pathlib
root=pathlib.Path(__file__).parent
class H(http.server.SimpleHTTPRequestHandler):
 def __init__(self,*a,**kw):super().__init__(*a,directory=str(root),**kw)
 def do_GET(self):
  if self.path.startswith('/assets/'):self.path=self.path.replace('/assets/','/',1)
  super().do_GET()
http.server.ThreadingHTTPServer(('127.0.0.1',8793),H).serve_forever()
