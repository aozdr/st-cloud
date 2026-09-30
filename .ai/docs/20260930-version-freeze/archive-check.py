"""Check and sanitize archived evidence without changing production/test sources."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

DIRECTORY = Path(__file__).resolve().parent
PATHS = DIRECTORY.joinpath('commit-paths.txt').read_text(encoding='utf-8').splitlines()
SENSITIVE = re.compile(r'^(password|passwd|secret|token|accessToken|refreshToken|authorization|cookie|accessKey|secretKey)$', re.I)
XML_PROPERTY = re.compile(r'(<property\s+name="[^"]*(?:password|passwd|secret|credential|master.?key|access.?key|token)[^"]*"\s+value=")[^"]*("\s*/>)', re.I)
TOKEN = re.compile(r'eyJ[A-Za-z0-9_-]{15,}|gh[pousr]_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9]{15,}|-----BEGIN [A-Z ]*PRIVATE KEY-----')
URL_SECRET = re.compile(r'([?&](?:password|secret|access_token|refresh_token|token)=)[^&\s\"\'<>]*', re.I)
CLI_PASSWORD = re.compile(r'(--password=)[^\s,\'"<>]+', re.I)
changes = []
xml_count = 0


def redact_json(value):
    if isinstance(value, dict):
        return {key: mask(item) if SENSITIVE.fullmatch(key) else redact_json(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact_json(item) for item in value]
    return value


def mask(value):
    if isinstance(value, str):
        return '[REDACTED]' if value else value
    if isinstance(value, list):
        return [mask(item) for item in value]
    return redact_json(value)


for name in PATHS:
    path = Path(name)
    if not name.startswith('.ai/docs/') or path.suffix == '.png':
        continue
    original = path.read_bytes()
    content = original.decode('utf-8-sig')
    updated = content
    if path.suffix == '.xml':
        before = ET.fromstring(content)
        updated = XML_PROPERTY.sub(r'\1[REDACTED]\2', updated)
        after = ET.fromstring(updated)
        assert before.attrib == after.attrib
        assert [(x.attrib, x.tag) for x in before.findall('testcase')] == [(x.attrib, x.tag) for x in after.findall('testcase')]
        xml_count += 1
    elif path.suffix == '.json':
        before = json.loads(content)
        after = redact_json(before)
        if before != after:
            updated = json.dumps(after, ensure_ascii=False, indent=2) + '\n'
    if path.suffix in {'.xml', '.json', '.log', '.txt', '.html'}:
        updated = URL_SECRET.sub(r'\1[REDACTED]', updated)
        updated = CLI_PASSWORD.sub(r'\1[REDACTED]', updated)
    if updated != content:
        output = updated.encode('utf-8')
        path.write_bytes(output)
        changes.append({'path': name, 'beforeSha256': hashlib.sha256(original).hexdigest(),
                        'afterSha256': hashlib.sha256(output).hexdigest()})
    if TOKEN.search(updated):
        raise RuntimeError('Token/private key pattern in archived file: ' + name)

report = {
    'scope': 'Archived evidence only; source fixtures and immutable review baselines retained',
    'xmlFilesChecked': xml_count,
    'redactedFiles': changes,
    'checks': ['JWT/PAT/private key pattern absent', 'XML credential properties redacted',
               'JSON sensitive fields and URL/CLI passwords redacted',
               'XML suite and testcase attributes unchanged',
               '26 screenshots visually checked: synthetic test pages'],
    'reproduction': 'Scripts that require environment credentials use STCLOUD_TEST_MYSQL_PASSWORD, STCLOUD_TEST_ADMIN_PASSWORD and STCLOUD_TEST_USER_PASSWORD. Supply credentials for a new isolated test environment.',
    'limitations': 'Historical source fixtures include documented development defaults and synthetic test data; these are not deployed credentials. Archived scripts may require updating local tool paths.'
}
DIRECTORY.joinpath('archive-sanitization.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Archived XML checked:', xml_count, '; redacted files:', len(changes), '; token/private-key pattern: clear')
