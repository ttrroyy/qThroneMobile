"""One VPN connection and HTTP probe against the isolated local SOCKS fixture."""
from pathlib import Path

source = Path(__file__).with_name('smoke_vpn.py').read_text()
setup = source.split("shell('am','start','-n',activity,'-a','android.intent.action.VIEW','-d','socks5://127.0.0.1:8868#VPN-queued')")[0]
setup = setup.replace('assert len(packages)==1', "packages = [p for p in packages if not p.endswith('.test')]\nassert len(packages)==1")
exec(compile(setup, 'smoke_vpn.py setup', 'exec'))
tap('VPN-smoke')
tap('Connect or disconnect VPN, Not connected')
time.sleep(2)
if 'Connection request' in xml():
    tap('OK')
wait_for('Connect or disconnect VPN, Connected')
state = shell('dumpsys', 'connectivity')
assert 'TRANSPORT_VPN' in state or 'Transports: VPN' in state
previous = len(hits)
tap('Check current connection')
for _ in range(20):
    if len(hits) > previous and re.search(r'\d+ ms', xml()):
        break
    time.sleep(1)
else:
    raise AssertionError('Current connection HTTP probe failed')
# An unsigned command from the shell must not silently stop a connected VPN.
shell('am', 'start', '-n', package + '/app.rawline.mobile.ui.QuickDisableShortcut')
time.sleep(2)
state = shell('dumpsys', 'connectivity')
assert 'TRANSPORT_VPN' in state or 'Transports: VPN' in state
assert 'VPN control' in xml(), 'Unsigned command did not require confirmation'
tap('Cancel')
wait_for('Connect or disconnect VPN, Connected')
tap('Connect or disconnect VPN, Connected')
wait_for('Connect or disconnect VPN, Not connected')
server.shutdown()
print('VPN connect, HTTP latency, unsigned disable protection and disconnect passed')
