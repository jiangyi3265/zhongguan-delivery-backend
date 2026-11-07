"""Interactive SSH/SFTP for this project's verification; password stays in memory."""
import base64, getpass, hashlib, json, pathlib, select, socketserver, sys, threading
import paramiko

ROOT = pathlib.Path(__file__).resolve().parent.parent
HOST = '124.222.244.144'
FINGERPRINT = 'YTIGnsOJqox7RLzIpCxUfMBB1ZjAP+osQHERNgaCfkI'

class PinnedHost(paramiko.MissingHostKeyPolicy):
    def missing_host_key(self, client, hostname, key):
        actual = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
        if actual != FINGERPRINT:
            raise RuntimeError('SSH host key does not match deployment record')

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(PinnedHost())
secret = getpass.getpass('SSH password: ')
ssh.connect(HOST, username='root', password=secret, look_for_keys=False, allow_agent=False, timeout=15)
del secret

class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        channel = ssh.get_transport().open_channel('direct-tcpip', ('127.0.0.1', 19186), self.request.getpeername())
        try:
            while True:
                readable, _, _ = select.select([self.request, channel], [], [], 30)
                for source in readable:
                    data = source.recv(65536)
                    if not data: return
                    (channel if source is self.request else self.request).sendall(data)
        finally:
            channel.close()

server = socketserver.ThreadingTCPServer(('127.0.0.1', 15190), Forward)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
print('CONNECTED; verification tunnel 127.0.0.1:15190 -> loopback:19186', flush=True)
try:
    for line in sys.stdin:
        try:
            job = json.loads(line)
            if job.get('quit'): break
            with ssh.open_sftp() as sftp:
                for local, remote in job.get('uploads', []):
                    sftp.put(str(ROOT / local), remote)
                    print('UPLOADED ' + local, flush=True)
                for remote, local in job.get('downloads', []):
                    sftp.get(remote, str(ROOT / local))
                    print('DOWNLOADED ' + local, flush=True)
            if job.get('run'):
                _, stdout, stderr = ssh.exec_command(job['run'])
                for output in stdout: print(output, end='', flush=True)
                print(stderr.read().decode(), end='', flush=True)
                print('EXIT ' + str(stdout.channel.recv_exit_status()), flush=True)
        except Exception as error:
            print('ERROR ' + str(error), flush=True)
finally:
    server.shutdown()
    ssh.close()
