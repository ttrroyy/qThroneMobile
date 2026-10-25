"""Install the pinned native binaries; Actions retention does not affect builds."""
import hashlib
import io
import json
import tarfile
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'native-transports.lock.json').read_text())
properties = dict(line.split('=', 1) for line in (root / 'nb4a.properties').read_text().splitlines() if '=' in line)
assert properties['THRONE_CORE_REF'] == lock['core_commit'], 'Update the transport lock for the new core pin'
for name, expected in lock['inputs'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, 'Rebuild transports after changing ' + name
for item in lock['archives']:
    with urllib.request.urlopen(item['url'], timeout=60) as response:
        archive = response.read()
    assert hashlib.sha256(archive).hexdigest() == item['sha256'], 'Native archive checksum mismatch'
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        files = {}
        for member in tar.getmembers():
            assert member.isfile() and '/' not in member.name and '\\' not in member.name
            files[member.name] = tar.extractfile(member).read()
    assert files['SOURCES.txt'].decode() == item['sources'], 'Transport source pins mismatch'
    for line in files['SHA256SUMS'].decode().splitlines():
        digest, name = line.split()
        assert hashlib.sha256(files[name]).hexdigest() == digest
    assert set(name for name in files if name.endswith('.so')) == {
        'libqwdtt.so', 'libolcrtc.so', 'libopenflux.so', 'libcsqtt_transport.so',
        'libdnstun.so', 'libmasterdns.so', 'libs3bypass.so', 'libslipstream.so', 'libwdttplus.so'}
    for name, data in files.items():
        if name.endswith('.so'):
            output = root / 'deployment/carriers' / item['abi'] / name
        elif name.endswith('-LICENSE') or name == 'SOURCES.txt':
            output = root / 'app/src/main/assets/carrier-licenses' / item['abi'] / name
        else:
            continue
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
    print('Verified native transports:', item['abi'])

# Small security rebuilds replace only the affected clients, retaining all other pinned transports.
secure = lock.get('secure_socks')
if secure:
    assert secure['core_commit'] == properties['THRONE_CORE_REF'], 'Secure SOCKS core pin mismatch'
    for name, expected in secure['inputs'].items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, 'Rebuild secure SOCKS clients after changing ' + name
    assert {item['abi'] for item in secure['archives']} == {item['abi'] for item in lock['archives']}
    for item in secure['archives']:
        with urllib.request.urlopen(item['url'], timeout=60) as response:
            archive = response.read()
        assert hashlib.sha256(archive).hexdigest() == item['sha256'], 'Secure SOCKS archive checksum mismatch'
        files = {}
        with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
            for member in tar.getmembers():
                assert member.isfile() and '/' not in member.name and '\\' not in member.name
                assert member.name not in files, 'Duplicate archive entry'
                files[member.name] = tar.extractfile(member).read()
        assert set(files) == {'libqwdtt.so', 'libwdttplus.so', 'libcsqtt_transport.so', 'qWDTT-LICENSE', 'WDTT-Plus-LICENSE', 'CSQTT-LICENSE', 'SOURCES.txt', 'SHA256SUMS', 'SECURITY.json'}
        provenance = json.loads(files['SECURITY.json'])
        for key in ('build_commit', 'core_commit', 'wdtt_commit', 'csqtt_commit', 'inputs'):
            assert provenance[key] == secure[key], 'Secure SOCKS provenance mismatch'
        assert files['SOURCES.txt'].decode() == item['sources']
        sums = dict(line.split()[::-1] for line in files['SHA256SUMS'].decode().splitlines())
        assert set(sums) == {'libqwdtt.so', 'libwdttplus.so', 'libcsqtt_transport.so'}
        for name, expected in sums.items():
            assert hashlib.sha256(files[name]).hexdigest() == expected, 'Secure SOCKS binary checksum mismatch'
            output = root / 'deployment/carriers' / item['abi'] / name
            output.write_bytes(files[name])
        license_dir = root / 'app/src/main/assets/carrier-licenses' / item['abi']
        for name in ('qWDTT-LICENSE', 'WDTT-Plus-LICENSE', 'CSQTT-LICENSE'):
            (license_dir / name).write_bytes(files[name])
        (license_dir / 'SOCKS-SOURCES.txt').write_bytes(files['SOURCES.txt'])
        print('Verified secure SOCKS transports:', item['abi'])

# Remove stale compatibility files when reusing a local staging directory.
for item in lock['archives']:
    (root / 'deployment/carriers' / item['abi'] / 'libcsqtt_compat.so').unlink(missing_ok=True)
    for name in ('CSQTT-Compat-LICENSE', 'CSQTT-Compat-SOURCES.txt'):
        (root / 'app/src/main/assets/carrier-licenses' / item['abi'] / name).unlink(missing_ok=True)

assert 'sstp' in lock, 'Build and pin SSTP before packaging this version'
sstp = lock['sstp']
source_paths = {p.relative_to(root).as_posix() for p in (root / 'carriers/sstp').rglob('*')
                if p.is_file() and (p.suffix in {'.go', '.mod', '.sum'} or p.name == 'LICENSE')}
source_paths.add('scripts/build_sstp.sh')
assert source_paths == set(sstp['inputs']), 'Rebuild SSTP after adding or removing source files'
for name, expected in sstp['inputs'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, 'Rebuild SSTP after changing ' + name
assert {item['abi'] for item in sstp['archives']} == {item['abi'] for item in lock['archives']}
for item in sstp['archives']:
    with urllib.request.urlopen(item['url'], timeout=60) as response:
        archive = response.read()
    assert hashlib.sha256(archive).hexdigest() == item['sha256'], 'SSTP archive checksum mismatch'
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        for member in tar.getmembers():
            assert member.isfile() and '/' not in member.name and '\\' not in member.name
            assert member.name not in files, 'Duplicate SSTP archive entry'
            files[member.name] = tar.extractfile(member).read()
    assert set(files) == {'libsstp.so', 'SSTP-LICENSE', 'SSTP.json', 'SOURCES.txt', 'SHA256SUMS'}
    provenance = json.loads(files['SSTP.json'])
    assert provenance == {'build_commit': sstp['build_commit'], 'abi': item['abi'], 'inputs': sstp['inputs']}, 'SSTP build provenance mismatch'
    assert files['SOURCES.txt'].decode() == item['sources']
    digest, binary = files['SHA256SUMS'].decode().split()
    assert binary == 'libsstp.so' and hashlib.sha256(files[binary]).hexdigest() == digest, 'SSTP binary checksum mismatch'
    (root / 'deployment/carriers' / item['abi'] / binary).write_bytes(files[binary])
    license_dir = root / 'app/src/main/assets/carrier-licenses' / item['abi']
    (license_dir / 'SSTP-LICENSE').write_bytes(files['SSTP-LICENSE'])
    (license_dir / 'SSTP-SOURCES.txt').write_bytes(files['SOURCES.txt'])
    print('Verified SSTP transport:', item['abi'])

# CottenDNS and Soar are pinned apart from the other transports.
assert 'dns_engines' in lock, 'Build and pin the DNS engines before packaging this version'
dns = lock['dns_engines']
source_paths = {p.relative_to(root).as_posix() for folder in ('carriers/cottendns', 'carriers/soar') for p in (root / folder).glob('*.go')}
source_paths.add('scripts/build_dns_engines.sh')
assert source_paths == set(dns['inputs']), 'Rebuild the DNS engines after adding or removing source files'
for name, expected in dns['inputs'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, 'Rebuild the DNS engines after changing ' + name
assert {item['abi'] for item in dns['archives']} == {item['abi'] for item in lock['archives']}
for item in dns['archives']:
    with urllib.request.urlopen(item['url'], timeout=60) as response:
        archive = response.read()
    assert hashlib.sha256(archive).hexdigest() == item['sha256'], 'DNS engine archive checksum mismatch'
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        for member in tar.getmembers():
            assert member.isfile() and '/' not in member.name and '\\' not in member.name
            assert member.name not in files, 'Duplicate DNS engine archive entry'
            files[member.name] = tar.extractfile(member).read()
    assert set(files) == {'libcottendns.so', 'libsoar.so', 'CottenDNS-LICENSE', 'Soar-LICENSE', 'DNS.json', 'SOURCES.txt', 'SHA256SUMS'}
    provenance = json.loads(files['DNS.json'])
    assert provenance == {'build_commit': dns['build_commit'], 'abi': item['abi'], 'inputs': dns['inputs']}, 'DNS engine build provenance mismatch'
    assert files['SOURCES.txt'].decode() == item['sources']
    sums = dict(line.split()[::-1] for line in files['SHA256SUMS'].decode().splitlines())
    assert set(sums) == {'libcottendns.so', 'libsoar.so'}
    license_dir = root / 'app/src/main/assets/carrier-licenses' / item['abi']
    for name, expected in sums.items():
        assert hashlib.sha256(files[name]).hexdigest() == expected, 'DNS engine binary checksum mismatch'
        (root / 'deployment/carriers' / item['abi'] / name).write_bytes(files[name])
    for name in ('CottenDNS-LICENSE', 'Soar-LICENSE'):
        (license_dir / name).write_bytes(files[name])
    (license_dir / 'DNS-SOURCES.txt').write_bytes(files['SOURCES.txt'])
    print('Verified DNS engines:', item['abi'])
