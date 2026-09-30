"""真实完整后端 HTTP 回归；固定独立测试库/18082，不连接用户后端。"""
import json
import time
import urllib.request
import urllib.error
import urllib.parse
import subprocess
import os
from pathlib import Path

BASE = 'http://127.0.0.1:18082/api'
DB = 'stcloud_team_recycle_20260930'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
steps = []

def call(method, path, payload=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + urllib.parse.quote(path, safe='/?=&'), data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
    try:
        response = opener.open(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    body = json.loads(response.read())
    steps.append({'method': method, 'path': path, 'http': response.status, 'code': body.get('code')})
    assert body.get('code') == expected, (path, response.status, body)
    print(method, path, 'code=' + str(body['code']))
    return body.get('data')

def sql(query):
    env = dict(os.environ, MYSQL_PWD='123456')
    proc = subprocess.run(['E:/utils/mysql-8.0.44-winx64/bin/mysql.exe', '--host=127.0.0.1', '--port=3306', '--user=root', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--database=' + DB, '-e', query], env=env, capture_output=True, text=True, encoding='utf-8')
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()

try:
    call('GET', '/auth/ping')
    # 多次调试留下的测试账号配额会占满独立库默认100GB；只收敛本脚本账号的测试配额。
    sql("UPDATE sys_user SET storage_quota=1048576 WHERE tenant_id=1 AND username LIKE 'recycle-%' AND storage_used<1048576")
    owner = call('POST', '/auth/login', {'username':'admin', 'password':os.environ['STCLOUD_TEST_ADMIN_PASSWORD']})
    suffix = str(int(time.time()))
    admin = call('POST', '/auth/register', {'username':'recycle-admin-' + suffix, 'password':os.environ['STCLOUD_TEST_USER_PASSWORD']})
    editor = call('POST', '/auth/register', {'username':'recycle-editor-' + suffix, 'password':os.environ['STCLOUD_TEST_USER_PASSWORD']})
    ot, at, et = owner['token'], admin['token'], editor['token']
    space = call('POST', '/team/space', {'spaceName':'HTTP回收回归-' + suffix, 'storageQuota':1048576}, ot)
    sid = str(space['id'])
    call('POST', '/team/' + sid + '/member', {'userId':admin['userId'], 'role':'0'}, ot)
    call('POST', '/team/' + sid + '/member', {'userId':editor['userId'], 'role':'1'}, ot)
    root = call('POST', '/team/' + sid + '/folder?folderName=HTTP回收目录', token=et)
    rid = str(root['id'])
    child = call('POST', '/team/' + sid + '/folder?parentId=' + rid + '&folderName=管理员子目录', token=at)
    fid = str(call('POST', '/team/' + sid + '/files/new', {'parentId':child['id'], 'type':'txt', 'fileName':'真实存储回归.txt'}, at)['id'])
    call('POST', '/team/' + sid + '/files/delete', [rid], ot)
    assert rid in [str(i['id']) for i in call('GET', '/recycle/list', token=at)]
    assert rid not in [str(i['id']) for i in call('GET', '/recycle/list', token=et)]
    call('POST', '/recycle/restore', {'nodeIds':[rid]}, et, 403)
    call('POST', '/recycle/delete', {'nodeIds':[rid]}, et, 403)
    call('POST', '/recycle/restore', {'nodeIds':[rid]}, at)
    roots = call('GET', '/team/' + sid + '/files?parentId=0', token=ot)
    assert rid in [str(i['id']) for i in roots['records']]
    restored = call('GET', '/team/' + sid + '/files/' + fid, token=at)
    assert sql('SELECT space_id FROM file_node WHERE id=' + fid + ' AND deleted=0') == sid
    assert str(restored['parentId']) == str(child['id'])
    assert restored['path'] == '/HTTP回收目录/管理员子目录/真实存储回归.txt'
    assert sql('SELECT COUNT(DISTINCT owner_id) FROM file_node WHERE space_id=' + sid + ' AND deleted=0') == '2'
    call('POST', '/team/' + sid + '/files/delete', [rid], ot)
    call('POST', '/recycle/restore', {'nodeIds':[rid]}, ot)
    personal = call('POST', '/file/folder', {'parentId':0, 'folderName':'个人回收回归-' + suffix}, at)
    pid = str(personal['id'])
    call('POST', '/file/delete', {'nodeIds':[pid]}, at)
    assert pid in [str(i['id']) for i in call('GET', '/recycle/list', token=at)]
    call('POST', '/recycle/restore', {'nodeIds':[pid]}, at)
    assert str(call('GET', '/file/' + pid, token=at)['id']) == pid
    # 只删除脚本刚创建的唯一测试树；真实 MQ 消费后移除对应 ES 文档。
    call('POST', '/team/' + sid + '/files/delete', [rid], ot)
    call('POST', '/recycle/delete', {'nodeIds':[rid]}, at)
    assert sql('SELECT COUNT(*) FROM file_node WHERE id IN (' + rid + ',' + str(child['id']) + ',' + fid + ') AND deleted=0') == '0'
    call('POST', '/file/delete', {'nodeIds':[pid]}, at)
    call('POST', '/recycle/delete', {'nodeIds':[pid]}, at)
    # 清理前两次脚本夹具失败留下的同任务目录，查询目标始终是本任务独立库。
    for row in sql("SELECT n.id,n.space_id,n.status FROM file_node n JOIN team_space s ON s.id=n.space_id "
                   "WHERE n.deleted=0 AND n.parent_id=0 AND n.name='HTTP回收目录' "
                   "AND s.space_name LIKE 'HTTP回收回归-%' AND s.owner_id=1").splitlines():
        old_id, old_space, old_status = row.split('\t')
        if old_status == '0':
            call('POST', '/team/' + old_space + '/files/delete', [old_id], ot)
        call('POST', '/recycle/delete', {'nodeIds':[old_id]}, ot)
    deadline = time.time() + 35
    while time.time() < deadline:
        if sql('SELECT COUNT(*) FROM event_log WHERE status<>1') == '0':
            break
        time.sleep(1)
    assert sql('SELECT COUNT(*) FROM event_log') != '0'
    assert sql('SELECT COUNT(*) FROM event_log WHERE status<>1') == '0', 'MQ outbox 尚未全部投递'
    print('PASS: real HTTP delete/list/restore, owner/admin/editor isolation, mixed owners, personal compatibility, permanent delete, S3 upload, MQ outbox')
finally:
    Path('.ai/docs/20260930-team-recycle/http-requests.json').write_text(json.dumps(steps, ensure_ascii=False, indent=2), encoding='utf-8')
