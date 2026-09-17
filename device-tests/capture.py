"""Capture only an explicitly named foreground app on the authorized test phone."""
import argparse
import sys
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--package', required=True)
parser.add_argument('--tag', required=True)
args = parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.tag):
    raise SystemExit('Invalid capture tag')
adb = Path(r'D:\Codex\.toolchains\mom-android\sdk\platform-tools\adb.exe')
prefix = [str(adb), '-s', args.serial]
def run(*command, binary=False):
    result = subprocess.run(prefix + list(command), capture_output=True, check=True, timeout=35)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')
windows = run('shell', 'dumpsys', 'window')
focus = next((line.strip() for line in windows.splitlines() if 'mCurrentFocus=' in line), '')
if args.package + '/' not in focus and args.package + '}' not in focus:
    raise SystemExit('Capture stopped: requested app is not the foreground window.')
destination = Path(__file__).parent / 'private'
destination.mkdir(exist_ok=True)
image_path = destination / (args.tag + '.png')
image_path.write_bytes(run('exec-out', 'screencap', '-p', binary=True))
remote = '/data/local/tmp/mom-probe-ui.xml'
dump_result = run('shell', 'uiautomator', 'dump', '--compressed', remote)
try:
    xml_data = run('shell', 'cat', remote)
except subprocess.CalledProcessError:
    print('SCREENSHOT:', image_path)
    print('UI_TREE_UNAVAILABLE:', dump_result.strip()[:300])
    raise SystemExit(0)
start = xml_data.find('<?xml')
if start < 0:
    start = xml_data.find('<hierarchy')
if start < 0:
    raise SystemExit('No UI hierarchy returned; screenshot retained locally.')
xml_data = xml_data[start:]
(destination / (args.tag + '.xml')).write_text(xml_data, encoding='utf-8')
run('shell', 'rm', '-f', remote)
print('SCREENSHOT:', image_path)
for node in ET.fromstring(xml_data).iter('node'):
    if node.get('package') != args.package or node.get('password') == 'true':
        continue
    label = node.get('text') or node.get('content-desc')
    if label:
        print(repr(label[:600]), 'bounds=' + node.get('bounds', ''),
              'clickable=' + node.get('clickable', ''), 'checked=' + node.get('checked', ''))
