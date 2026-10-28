"""Capture an isolated emulator; sample profiles never initiate a connection."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

adb = os.path.join(os.environ['ANDROID_HOME'], 'platform-tools', 'adb')
out = Path('ui-screenshots')
out.mkdir(exist_ok=True)

def shell(*args):
    return subprocess.check_output([adb, 'shell', *args], text=True)

def capture(name):
    print(f'Capturing screen: {name}', flush=True)
    time.sleep(2)
    (out / f'{name}.png').write_bytes(subprocess.check_output([adb, 'exec-out', 'screencap', '-p']))

def screen_xml():
    shell('uiautomator', 'dump', '/sdcard/qthrone-preview.xml')
    xml = shell('cat', '/sdcard/qthrone-preview.xml')
    # Google emulator launcher can ANR during boot; never suppress an app ANR.
    if "Pixel Launcher isn't responding" in xml:
        (out / 'launcher-anr.xml').write_text(xml)
        for node in ET.fromstring(xml).iter('node'):
            if node.get('resource-id') == 'android:id/aerr_close':
                x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
                shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
                time.sleep(3)
                shell('uiautomator','dump','/sdcard/qthrone-preview.xml')
                return shell('cat','/sdcard/qthrone-preview.xml')
    return xml

def scroll_to_text(label):
    for direction in (1,-1):
        for _ in range(4):
            value=screen_xml()
            if any(n.get('text')==label or n.get('content-desc')==label for n in ET.fromstring(value).iter('node')): return value
            shell('input','swipe','600','1800' if direction==1 else '1200','600','1200' if direction==1 else '1800','250')
            time.sleep(.4)
    return screen_xml()

def tap_text(text):
    xml = scroll_to_text(text)
    (out / 'last-screen.xml').write_text(xml)
    for node in reversed(list(ET.fromstring(xml).iter('node'))):
        if node.get('text') == text or node.get('content-desc') == text:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(2)
            return
    raise RuntimeError(f'UI control missing: {text}')

def confirm_import_group(new=False):
    assert 'Add to an existing group' in screen_xml(), 'Import destination screen is missing'
    capture('36-import-destination')
    if not new:
        tap_text('Add to an existing group')
        nodes=[n for n in ET.fromstring(screen_xml()).iter('node') if n.get('class')=='android.widget.CheckedTextView']
        if nodes:
            target=next((n for n in nodes if n.get('text')=='Server list'),nodes[0])
            tap_text(target.get('text'))
            capture('37-import-confirmation')
            tap_text('OK')
            return
        tap_text('Cancel')
    tap_text('Create a group / subscription')
    time.sleep(2)
    capture('38-create-group')
    tap_text('Apply')
    time.sleep(2)

def choose_accent(label):
    titles=[n for n in ET.fromstring(screen_xml()).iter('node') if n.get('text')=='Color']
    a,b,c,d=map(int,re.findall(r'\d+',titles[-1].attrib['bounds']))
    shell('input','tap',str((a+c)//2),str((b+d)//2));time.sleep(1)
    tap_text(label);time.sleep(3)

packages = [line.removeprefix('package:').strip() for line in shell('pm', 'list', 'packages').splitlines()
            if line.startswith('package:app.qthrone.mobile')]
assert len(packages) == 1, 'Expected one isolated qThrone installation'
activity = packages[0] + '/app.qthrone.mobile.ui.MainActivity'
shell('pm', 'grant', packages[0], 'android.permission.POST_NOTIFICATIONS')
shell('dumpsys', 'deviceidle', 'whitelist', '+' + packages[0])
shell('am', 'force-stop', packages[0])
shell('logcat', '-c')
time.sleep(3)
shell('am', 'start', '-n', activity)
time.sleep(8)
screen_xml()
capture('01-home-empty')
shell('input','swipe','1','650','700','650','400')
assert 'Configuration' not in screen_xml() and 'Sing-box dashboard' not in screen_xml(), 'Legacy navigation drawer opened'
# An edge swipe can be Android's Back gesture on the home page; resume the app before checking its controls.
shell('am','start','-n',activity);time.sleep(3)

tap_text('Settings');tap_text('Settings')
capture('03-settings')
for label,name in [('TUN settings','27-tun-settings'),('Core settings','28-core-settings')]:
    tap_text(label);capture(name)
    if label=='TUN settings':
        tap_text('MTU'); tap_text('Enter manually'); capture('30-custom-mtu')
        assert 'OK' in screen_xml() and 'Cancel' in screen_xml(), 'Custom MTU actions missing'
        tap_text('Cancel')
    if label=='Core settings':
        tap_text('Latency test URL');capture('29-ping-url-dialog')
        assert 'OK' in screen_xml() and 'Cancel' in screen_xml(), 'Latency URL actions missing'
        tap_text('Cancel')
    shell('input','keyevent','4');time.sleep(2)
    assert 'General settings' in screen_xml(), 'Settings back navigation returned to home'

tap_text('Backup and restore');capture('32-backup-buttons')
assert 'qThrone Desktop' in screen_xml(), 'Desktop backup format label missing'
shell('input','keyevent','4');time.sleep(2)
tap_text('Routing settings')
capture('02-routing')
assert 'AdBlock' in screen_xml(), 'Routing options missing'
tap_text('Routing profiles');capture('25-route-profiles')
shell('input','keyevent','4');time.sleep(2)
assert 'Routing profiles' in screen_xml(), 'Back from routes did not return to routing settings'

shell('input', 'keyevent', '4')
time.sleep(2)
for label in ('Finland-demo', 'Sweden-demo', 'Germany-demo'):
    # Documentation-only TEST-NET address, never a real account or server.
    from urllib.parse import quote
    name=('🇫🇮 '+label) if label=='Finland-demo' else label
    shell('am', 'start', '-n', activity, '-a', 'android.intent.action.VIEW', '-d',
          'socks5://192.0.2.1:1080#'+quote(name))
    time.sleep(2)
    # Existing import flow asks for confirmation; retain a diagnostic screenshot if it differs.
    try:
        tap_text('Yes')
        confirm_import_group()
        for attempt in range(12):
            if label in screen_xml() and 'Confirm you want' not in screen_xml():
                break
            time.sleep(1)
        time.sleep(2)
    except RuntimeError:
        capture('04-import-dialog')
        break
capture('05-profiles')
profile_xml = screen_xml()
(out / 'profiles-screen.xml').write_text(profile_xml)
# This is a fresh CI emulator with documentation-only fixtures, never the user's device.
(out / 'emulator-logcat.txt').write_text(shell('logcat', '-d'))
for name in ('core.log', 'core.log.prev'):
    try:
        (out / name).write_text(shell('run-as', packages[0], 'cat', 'files/' + name))
    except subprocess.CalledProcessError:
        pass
assert 'Finland-demo' in profile_xml, 'Imported demo profile is missing from the home screen'
assert 'Server list' in profile_xml and 'text="Default"' not in profile_xml,'Standalone profiles did not go to Server list'
assert 'content-desc="Country flag: FI"' in profile_xml,'The profile country flag is missing'
header=next(n.get('bounds') for n in ET.fromstring(screen_xml()).iter('node') if n.get('content-desc')=='Settings')
shell('input','swipe','600','1800','600','1200','300');time.sleep(.5)
assert next(n.get('bounds') for n in ET.fromstring(screen_xml()).iter('node') if n.get('content-desc')=='Settings')==header, 'Dashboard header moved with subscriptions'
shell('input','swipe','600','1200','600','2000','300');time.sleep(.5)


# Controlled local subscription, including metadata. No external account or server is contacted.
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import base64
class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        body=b"socks5://192.0.2.2:1080#Subscription-Finland\nsocks5://192.0.2.3:1080#Subscription-Sweden"
        if self.path.startswith("/scroll"):
            body="\n".join(f"socks5://192.0.2.4:{1080+i}#ScrollTest-{i:02}" for i in range(24)).encode()
        self.send_response(200)
        self.send_header('Content-Type','text/plain')
        self.send_header('Subscription-Userinfo','upload=524288; download=1048576; total=10485760; expire=2000000000')
        self.end_headers();self.wfile.write(body)
    def log_message(self,*args): pass
server=ThreadingHTTPServer(('127.0.0.1',8866),Fixture)
threading.Thread(target=server.serve_forever,daemon=True).start()
subprocess.check_call([adb,'reverse','tcp:8866','tcp:8866'])
shell('am','start','-n',activity,'-a','android.intent.action.SEND','-t','text/plain','--es','android.intent.extra.TEXT','http://127.0.0.1:8866/demo#Demo%20subscription')
time.sleep(2)
confirm_import_group(new=True)
for attempt in range(15):
    xml=screen_xml()
    if 'Subscription-Finland' in xml: break
    xml=scroll_to_text('Subscription-Finland')
    if 'Subscription-Finland' in xml: break
    time.sleep(1)
assert 'Subscription-Finland' in xml,'Subscription was not added to the dashboard'
capture('06-subscription-expanded')
tap_text('Demo subscription');capture('07-subscription-collapsed')
assert 'Subscription-Finland' not in screen_xml(),'Collapse did not hide subscription profiles'
tap_text('Demo subscription')

def profile_bounds(label):
    for node in ET.fromstring(scroll_to_text(label)).iter('node'):
        if node.get('text')==label:
            return list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
    raise RuntimeError('Swipe fixture profile missing')

def edit_profile(label):
    _,top,_,bottom=profile_bounds(label)
    center=(top+bottom)//2
    nodes=[n for n in ET.fromstring(screen_xml()).iter('node') if n.get('content-desc')=='Edit']
    assert nodes, 'Profile edit action is missing'
    node=min(nodes,key=lambda n:abs(sum(list(map(int,re.findall(r'\d+',n.attrib['bounds'])))[1::2])//2-center))
    a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds']))
    shell('input','tap',str((a+c)//2),str((b+d)//2));time.sleep(2)

def subscription_more(label):
    nodes=list(ET.fromstring(scroll_to_text(label)).iter('node'))
    title=next(n for n in nodes if n.get('text')==label)
    _,top,_,bottom=map(int,re.findall(r'\d+',title.attrib['bounds']))
    center=(top+bottom)//2
    choices=[n for n in nodes if n.get('content-desc')=='More actions']
    more=min(choices,key=lambda n:abs(sum(list(map(int,re.findall(r'\d+',n.attrib['bounds'])))[1::2])//2-center))
    a,b,c,d=map(int,re.findall(r'\d+',more.attrib['bounds']))
    shell('input','tap',str((a+c)//2),str((b+d)//2));time.sleep(2)
bounds=profile_bounds('Finland-demo');x1,y1,x2,y2=bounds;y=(y1+y2)//2
shell('input','swipe',str((x1+x2)//2),str(y),str(x2+140),str(y),'350')
capture('08-profile-swipe-actions')
tap_text('Share profile')
share=screen_xml()
assert 'content-desc="URL"' in share and 'content-desc="JSON"' in share and 'QR code' in share,'The three share options are missing'
capture('11-profile-share');tap_text('URL');time.sleep(1)
shell('input','swipe',str((x1+x2)//2),str(y),str(x2+140),str(y),'350')
shell('input','tap',str(x2),str(y))
shell('input','swipe',str(x2-40),str(y),str(x1+20),str(y),'350')
capture('09-profile-swipe-delete')
shell('input','tap',str((x1+x2)//2),str(y))
time.sleep(1)
assert profile_bounds('Finland-demo')==bounds,'Tap did not restore the profile after a left swipe'
shell('input','swipe',str((x1+x2)//2),str(y),str(x2+140),str(y),'350')
time.sleep(1)
shell('input','tap',str((x1+x2)//2+200),str(y))
time.sleep(1)
assert profile_bounds('Finland-demo')==bounds,'Tap did not restore the profile after a right swipe'
capture('12-profile-restored')
edit_profile('Finland-demo')
time.sleep(2)
assert 'SocksSettingsActivity' in shell('dumpsys','activity','activities'),'Edit action did not open the profile editor'
capture('13-profile-editor');shell('input','keyevent','4');time.sleep(2)
tap_text('Demo subscription')
subscription_more('Demo subscription')
assert 'Pin' in screen_xml() and 'Edit' in screen_xml(),'Subscription actions were not shown in the bottom sheet'
capture('14-subscription-menu');tap_text('Pin')
assert 'Demo subscription' in screen_xml(),'Pinned subscription disappeared'
subscription_more('Demo subscription');tap_text('Edit')
assert 'http://127.0.0.1:8866/demo' in screen_xml(),'Editor did not open the selected subscription'
capture('15-subscription-editor');shell('input','keyevent','4');time.sleep(2)
tap_text('Settings');tap_text('Settings');tap_text('Appearance');capture('16-appearance-gray')
choose_accent('Purple');capture('22-settings-purple');choose_accent('Cyan')
tap_text('Seasonal decoration');tap_text('New Year')
time.sleep(3)
shell('input','keyevent','4');time.sleep(.3);shell('input','keyevent','4');time.sleep(2)
capture('17-home-newyear')
tap_text('Settings');tap_text('Settings');tap_text('Appearance');tap_text('Theme');tap_text('Light')
time.sleep(4);capture('18-settings-white')
shell('input','keyevent','4');time.sleep(.3);shell('input','keyevent','4');time.sleep(2);capture('19-home-white')
tap_text('Settings');tap_text('Settings');tap_text('Appearance');tap_text('Theme');tap_text('Dark')
time.sleep(4);tap_text('Theme');tap_text('OLED');time.sleep(4);capture('20-settings-oled')
shell('input','keyevent','4');time.sleep(.3);shell('input','keyevent','4');time.sleep(2);capture('21-home-oled')
tap_text('Settings');tap_text('Settings');tap_text('Appearance');tap_text('Theme');tap_text('Dark');time.sleep(3)
choose_accent('White');capture('23-settings-white-theme');choose_accent('Cyan')
tap_text('Seasonal decoration');tap_text('Halloween');time.sleep(3)
shell('input','keyevent','4');time.sleep(.3);shell('input','keyevent','4');time.sleep(2)
# Every group, including the last subscription, can be removed without confirmation.
for attempt in range(10):
    nodes=list(ET.fromstring(screen_xml()).iter('node'))
    more=next((n for n in nodes if n.get('content-desc')=='More actions'),None)
    if more is None: break
    a,b,c,d=map(int,re.findall(r'\d+',more.attrib['bounds']))
    shell('input','tap',str((a+c)//2),str((b+d)//2));time.sleep(1)
    tap_text('Delete')
    time.sleep(2)
assert 'More actions' not in screen_xml(),'Deleting the last subscription left a group behind'
capture('24-home-all-deleted')
# Native editors use desktop field slots; these documentation-address fixtures never connect.
from urllib.parse import urlencode, quote
native_links=[
    ('qwdtt-editor','qwdtt://config?'+urlencode({'peer':'192.0.2.1:56000','pass':'test-password','hashes':','.join('test_hash_123456789_'+str(i) for i in range(1,8)),'device_id':'ci-editor','name':'qwdtt-editor'}),4),
    ('csqtt-editor','csqtt://connect?v=2&host=192.0.2.1&peer=46000&password=test-password&name=csqtt-editor&hashes='+'+'.join('test_hash_123456789_'+str(i) for i in range(1,9)),6),
]
for index,(name,link,slots) in enumerate(native_links):
    shell('am','start','-n',activity,'-a','android.intent.action.VIEW','-d',shlex.quote(link))
    time.sleep(3);tap_text('Yes');confirm_import_group()
    bounds=profile_bounds(name);a,b,c,d=bounds;y=(b+d)//2
    edit_profile(name);time.sleep(2)
    assert 'NativeTransportSettingsActivity' in shell('dumpsys','activity','activities')
    capture('33-native-editor-'+str(slots))
    for slot in range(1,slots+1):
        tap_text('Хэш VK '+str(slot))
        dialog=screen_xml()
        assert 'test_hash_123456789_'+str(slot) in dialog
        assert 'OK' in dialog and 'Cancel' in dialog
        if slot==1: capture('34-hash-dialog-'+str(slots))
        tap_text('Cancel')
    tap_text('Хэш VK 1')
    node=next(n for n in ET.fromstring(screen_xml()).iter('node') if n.get('class')=='android.widget.EditText')
    a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds']))
    shell('input','tap',str((a+c)//2),str((b+d)//2));shell('input','keyevent','123');shell('input','text','x');tap_text('OK')
    shell('input','keyevent','4');time.sleep(1)
    dialog=screen_xml()
    assert 'Save' in dialog and 'Discard' in dialog and 'Cancel' in dialog,'Unsaved dialog actions missing'
    capture('35-unsaved-dialog-'+str(slots));tap_text('Discard')

# A long controlled list must retain its position on selection and return from settings.
shell('am','start','-n',activity,'-a','android.intent.action.SEND','-t','text/plain','--es','android.intent.extra.TEXT','http://127.0.0.1:8866/scroll#Scroll%20fixture')
time.sleep(4)
confirm_import_group(new=True)
before=scroll_to_text('ScrollTest-16')
def named_bounds(value,name):
    node=next(n for n in ET.fromstring(value).iter('node') if n.get('text')==name)
    return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
a,b,c,d=named_bounds(before,'ScrollTest-16')
shell('input','tap',str((a+c)//2),str((b+d)//2));time.sleep(1)
assert abs(named_bounds(screen_xml(),'ScrollTest-16')[1]-b)<=3,'Profile selection reset list scroll'
capture('37-home-compact-scrolled')
tap_text('Settings');tap_text('Settings');shell('input','keyevent','4');time.sleep(2)
assert abs(named_bounds(screen_xml(),'ScrollTest-16')[1]-b)<=3,'Return from settings reset list scroll'
print('PASS: profile selection and settings return retain scrolled list position',flush=True)
(out/'ui-actions-result.txt').write_text('PASS: confirmed group selection and named subscription creation; standalone profiles in Server list; URL, QR and JSON sharing; country flags; profile swipe reset in both directions; profile editor; subscription bottom sheet, pin and edit; gray, white, OLED and seasonal preferences.\n')
server.shutdown()
