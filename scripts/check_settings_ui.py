"""Exercise navigation and live logs on the workflow's isolated emulator."""
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

adb = os.path.join(os.environ['ANDROID_HOME'], 'platform-tools', 'adb')
out = Path('ui-screenshots')
out.mkdir(exist_ok=True)

def shell(*args):
    return subprocess.check_output([adb, 'shell', *args], text=True)

package = next(line.removeprefix('package:').strip() for line in shell('pm', 'list', 'packages').splitlines()
               if line.startswith('package:app.rawline.mobile') and not line.endswith('.test'))
activity = package + '/app.rawline.mobile.ui.MainActivity'
shell('am', 'force-stop', package)
shell('am', 'start', '-n', activity)
time.sleep(3)

def state():
    shell('uiautomator', 'dump', '/sdcard/settings-check.xml')
    return shell('cat', '/sdcard/settings-check.xml')

def find(label, scroll=True):
    for attempt in range(9 if scroll else 1):
        root = ET.fromstring(state())
        nodes = [n for n in root.iter('node') if n.get('text') == label or n.get('content-desc') == label]
        if nodes:
            return nodes[-1]
        if scroll:
            shell('input', 'swipe', '600', '1800' if attempt < 4 else '900', '600', '900' if attempt < 4 else '1800', '250')
    raise AssertionError('Missing control: ' + label)

def tap(label):
    node = find(label)
    a,b,c,d = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'tap', str((a+c)//2), str((b+d)//2))
    time.sleep(.6)

def back():
    shell('input', 'keyevent', '4')
    time.sleep(.5)

def capture(name):
    (out / (name + '.png')).write_bytes(subprocess.check_output([adb, 'exec-out', 'screencap', '-p']))
    (out / (name + '.xml')).write_text(state(), encoding='utf-8')

def assert_main():
    assert 'MainActivity' in shell('dumpsys', 'activity', 'top').split('ACTIVITY')[-1], 'Settings launched a separate activity'

tap('Settings')
find('Logs')
capture('40-settings-logs-entry')
tap('Настройки VK-релея')
assert_main()
find('Деплой qWDTT')
capture('41-vk-relay-settings')
for title, name in [('Деплой qWDTT', 'qwdtt'), ('Деплой CSQTT', 'csqtt')]:
    tap(title)
    assert_main()
    find('Добавить VPS для деплоя')
    assert 'Новый сервер' not in state(), 'Unsaved VPS appeared by default'
    capture('42-deploy-' + name)
    tap('Добавить VPS для деплоя')
    assert 'DeployActivity' in shell('dumpsys', 'activity', 'top'), 'VPS editor did not open'
    find('Название сервера')
    back()
    assert_main()
    find('Добавить VPS для деплоя')
    back()
back()

# No real credentials or connection: inject controlled log lines into this disposable emulator only.
def append_log(text, replace=False):
    command = 'mkdir -p files; cat ' + ('>' if replace else '>>') + ' files/core.log'
    subprocess.run([adb, 'shell', 'run-as', package, 'sh', '-c', shlex.quote(command)], input=text.encode(), check=True)

append_log('UI-log-fixture-first\n', replace=True)
tap('Logs')
find('UI-log-fixture-first', scroll=False)
append_log('UI-log-fixture-live-update\n')
time.sleep(1.5)
assert 'UI-log-fixture-live-update' in state(), 'Log viewer did not update automatically'
capture('43-live-logs')
tap('More options')
find('Save logs…', scroll=False)
find('Hide sensitive data', scroll=False)
capture('44-log-export-options')
back()
tap('Clear logs')
assert 'Clear the current and previous session logs?' in state(), 'Log clearing has no confirmation'
tap('Cancel')
assert 'UI-log-fixture-live-update' in state(), 'Cancelling clear removed the logs'
back()
find('Logs')
(out / 'settings-check-result.txt').write_text('PASS: Logs entry, live updates, export options, clear cancellation, settings back navigation, VK relay and both deployment lists stay within MainActivity, unsaved VPS does not persist.\n')
print('PASS: logs and deployment settings UI', flush=True)
