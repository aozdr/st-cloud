import json
import os
import urllib.request
import urllib.error
from pathlib import Path

base = 'http://127.0.0.1:18083/api'
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def request_warnings():
    log = Path('.ai/docs/20260930-tenant-context-warning/api-runtime.log').read_text(encoding='utf-8', errors='replace')
    return [line for line in log.splitlines() if 'TenantContext' in line and 'WARN' in line and 'exec-' in line]

def call(path, payload=None, token=None):
    headers = {'Content-Type':'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, data=None if payload is None else json.dumps(payload).encode(), headers=headers)
    try:
        response = client.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    return response.status, json.loads(response.read())

status, body = call('/auth/login', {'username':'admin', 'password':os.environ['STCLOUD_TEST_ADMIN_PASSWORD']})
assert status == 200 and body['code'] == 200
token = body['data']['token']
# 登录是无token公开接口，现有默认租户告警按约定保留；本次验证鉴权阶段的重复误报。
before = request_warnings()
for index in range(20):
    status, body = call('/auth/me', token=token)
    assert status == 200 and body['code'] == 200 and str(body['data']['userId']) == '1'
status, body = call('/auth/me')
assert body['code'] != 200, '后续匿名请求不可继承上一个请求身份'
after = request_warnings()
assert after == before, after[len(before):]
print('PASS: 20 real authenticated HTTP requests, no new TenantContext WARN during authentication, following anonymous request denied')
print('Anonymous response:', status, body['code'])
print('MQ uses isolated broker; MySQL and Redis use independent test data; no full business-flow claim for this check')
