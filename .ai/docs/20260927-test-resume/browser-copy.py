import json,time,base64,sys
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright,expect
sys.stdout.reconfigure(encoding='utf-8')
OUT=Path(__file__).resolve().parent
revoked=False; requests=[]; errors=[]
node={'id':'9007199254740993','name':'source.txt','nodeType':1,'parentId':'0','path':'/source.txt','fileSize':1024,'suffix':'txt','uploadStatus':2,'status':0}
def reply(route,data,code=200):
    route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':code,'message':'目标目录已撤销上传权限' if code!=200 else 'ok','data':data},ensure_ascii=False))
def api(route):
    request=route.request; path=urlparse(request.url).path
    if path=='/api/auth/me': reply(route,{'userId':'100','username':'owner','roles':['admin'],'permissions':['file:copy'],'storageUsed':0,'storageQuota':1000000});return
    if path=='/api/team/1': reply(route,{'id':'1','spaceName':'复制撤权验收','ownerId':'100','myRole':'0','storageUsed':1024,'storageQuota':1000000});return
    if path=='/api/team/1/files': reply(route,{'records':[node],'total':1,'pages':1});return
    if path=='/api/team/1/tree': reply(route,[{'id':'9007199254740995','name':'target','children':[]}]);return
    if path=='/api/team/1/files/copy':
        requests.append(json.loads(request.post_data));reply(route,None,4605 if revoked else 200);return
    if path.endswith('/ids') or path.endswith('/list') or path.endswith('/roles'): reply(route,[]);return
    reply(route,{'records':[],'total':0,'unreadCount':0})
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True);page=browser.new_page(viewport={'width':1440,'height':1000})
    payload=base64.urlsafe_b64encode(json.dumps({'iat':int(time.time()),'exp':int(time.time())+3600}).encode()).decode().rstrip('=')
    page.add_init_script('sessionStorage.setItem("accessToken",'+json.dumps('fixture.'+payload+'.unsigned')+');')
    page.route('**/api/**',api);page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto('http://127.0.0.1:5187/team/1');page.wait_for_load_state('networkidle')
    (OUT/'browser-copy-initial.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
    page.get_by_text('source.txt',exact=True).click()
    page.get_by_role('button',name='复制到',exact=True).click()
    dialog=page.locator('.modal-content').filter(has=page.get_by_role('heading',name='复制到',exact=True))
    dialog.get_by_text('target',exact=True).click();revoked=True
    for i in range(2):
        dialog.get_by_role('button',name='确定',exact=True).click()
        expect(dialog.get_by_role('button',name='确定',exact=True)).to_be_enabled()
        expect(dialog).to_be_visible();assert len(requests)==i+1
        assert requests[-1]=={'nodeIds':['9007199254740993'],'targetParentId':'9007199254740995'}
    assert not errors,errors
    page.screenshot(path=str(OUT/'browser-copy-revoked.png'),full_page=True)
    (OUT/'browser-copy-requests.json').write_text(json.dumps(requests,indent=2),encoding='utf8')
    print('PASS: 打开复制对话框后撤权，两次提交均拒绝，保持目标选择且不显示成功。API受控，服务端另层真实H2验证。')
    browser.close()
