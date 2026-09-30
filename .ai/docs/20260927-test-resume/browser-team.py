import json, time, base64, sys
sys.stdout.reconfigure(encoding='utf-8')
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

OUT=Path(__file__).resolve().parent
R='9007199254740993'; R2='9007199254740995'
roles=[{'id':str(i),'name':name,'status':1,'isPreset':True} for i,name in enumerate(['管理员','编辑者','查看者'])]
roles += [{'id':value,'name':'同名角色','status':1,'isPreset':False,'permissions':'{"view":true,"upload":true}'} for value in [R,R2]]
members=[{'id':'10','userId':'100','username':'owner','nickname':'空间所有者','role':'0'},
         {'id':'11','userId':'200','username':'member','nickname':'普通成员','role':R},
         {'id':'12','userId':'201','username':'unknown','nickname':'失效成员','role':'9007199254740997'}]
invites=[]; requests=[]; failures={}; errors=[]; acl=[]
def reply(route,data,code=200,message='ok'):
    route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':code,'data':data,'message':message},ensure_ascii=False))
def api(route):
    req=route.request; parsed=urlparse(req.url); path=parsed.path; query=parse_qs(parsed.query)
    body=json.loads(req.post_data or 'null')
    requests.append({'method':req.method,'path':path,'query':query,'body':body})
    if path in failures:
        if failures[path]=='network': route.abort('failed')
        else: reply(route,None,403,'隔离权限拒绝')
        return
    if path=='/api/auth/me': reply(route,{'userId':'100','username':'owner','nickname':'所有者','roles':['admin'],'permissions':['team:invite'],'storageUsed':0,'storageQuota':1000000000}); return
    if path=='/api/team/1': reply(route,{'id':'1','spaceName':'角色验收空间','ownerId':'100','myRole':'0','storageUsed':0,'storageQuota':1000000000,'memberCount':len(members)}); return
    if path.endswith('/roles'): reply(route,roles); return
    if path.endswith('/members'): reply(route,{'records':members,'total':len(members)}); return
    if path.endswith('/invites'): reply(route,{'records':invites,'total':len(invites)}); return
    if path.endswith('/users/search'): reply(route,[{'userId':'202','username':'newuser','nickname':'待邀请用户'}]); return
    if path=='/api/team/1/member' and req.method=='POST':
        members.append({'id':'13','userId':body['userId'],'username':'newuser','nickname':'待邀请用户','role':body['role']}); reply(route,members[-1]); return
    if '/member/' in path and req.method=='PUT':
        for m in members:
            if m['id']==path.split('/')[-1]: m['role']=query['role'][0]
        reply(route,None); return
    if path=='/api/team/1/invite' and req.method=='POST':
        item={'id':str(20+len(invites)),'inviteCode':'isolated-code','role':body['role'],'status':1,'createdByName':'所有者','expireAt':None}
        invites.append(item);reply(route,item);return
    if path.endswith('/ids') or path.endswith('/tree') or path.endswith('/list'): reply(route,[]); return
    if path.endswith('/permissions'):
        if req.method=='PUT': acl[:]=[dict(rule,id=str(index+1)) for index,rule in enumerate(body['rules'])]
        reply(route,acl);return
    reply(route,{'records':[],'total':0,'unreadCount':0})

with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    page=browser.new_page(viewport={'width':1440,'height':1000})
    payload=base64.urlsafe_b64encode(json.dumps({'iat':int(time.time()),'exp':int(time.time())+3600}).encode()).decode().rstrip('=')
    page.add_init_script('sessionStorage.setItem("accessToken",'+json.dumps('fixture.'+payload+'.unsigned')+');')
    page.route('**/api/**',api)
    page.on('pageerror',lambda error: errors.append(str(error)))
    page.goto('http://127.0.0.1:5187/team/1');page.wait_for_load_state('networkidle')
    (OUT/'browser-team-initial.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
    page.screenshot(path=str(OUT/'browser-team-initial.png'),full_page=True)
    page.get_by_role('button',name='成员',exact=False).first.click()
    page.wait_for_load_state('networkidle')
    (OUT/'browser-team-members.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
    selects=page.locator('select')
    for index in [0,1]:
        values=selects.nth(index).locator('option').evaluate_all('(els)=>els.map(e=>e.value)')
        assert values==['0','1','2',R,R2],values
    expect(page.get_by_label('修改unknown的角色')).to_have_value('9007199254740997')
    expect(page.get_by_label('修改unknown的角色').locator('option:checked')).to_have_text('无效角色')
    page.get_by_placeholder('输入用户名或昵称搜索').fill('new')
    page.get_by_role('button',name='待邀请用户').click()
    selects.nth(0).select_option(R)
    failures['/api/team/1/member']='permission'
    page.get_by_role('button',name='邀请',exact=True).click()
    expect(page.get_by_text('隔离权限拒绝',exact=True)).to_be_visible()
    expect(selects.nth(0)).to_have_value(R)
    assert len(members)==3
    failures['/api/team/1/member']='network'
    page.get_by_role('button',name='邀请',exact=True).click()
    expect(page.get_by_text('Network Error',exact=True)).to_be_visible()
    expect(selects.nth(0)).to_have_value(R)
    del failures['/api/team/1/member']
    page.get_by_role('button',name='邀请',exact=True).click()
    expect(page.get_by_label('修改newuser的角色')).to_have_value(R)
    selects.nth(1).select_option(R2)
    for failure in ['permission','network']:
        failures['/api/team/1/invite']=failure
        page.get_by_role('button',name='生成',exact=True).click()
        expect(page.get_by_text('隔离权限拒绝' if failure=='permission' else 'Network Error',exact=True).last).to_be_visible()
        expect(selects.nth(1)).to_have_value(R2)
        assert not invites
    del failures['/api/team/1/invite']
    page.get_by_role('button',name='生成',exact=True).click()
    expect(page.get_by_role('button',name='复制链接',exact=True)).to_be_visible()
    assert invites[-1]['role']==R2
    page.get_by_label('修改member的角色',exact=True).select_option(R2)
    expect(page.get_by_label('修改member的角色',exact=True)).to_have_value(R2)
    page.locator('#main-content').get_by_role('button',name='关闭',exact=True).click()
    page.get_by_role('button',name='成员',exact=False).first.click()
    expect(page.get_by_label('修改member的角色',exact=True)).to_have_value(R2)
    expect(page.get_by_label('修改newuser的角色')).to_have_value(R)
    failures['/api/team/1/member/11']='permission'
    page.get_by_label('修改member的角色',exact=True).select_option(R)
    expect(page.get_by_text('隔离权限拒绝',exact=True).last).to_be_visible()
    assert next(m for m in members if m['id']=='11')['role']==R2
    (OUT/'browser-team-failure-selection.json').write_text(json.dumps({'selected':page.get_by_label('修改member的角色',exact=True).input_value(),'intended':R,'persisted':R2}),encoding='utf8')
    expect(page.get_by_label('修改member的角色',exact=True)).to_have_value(R)
    expect(page.get_by_role('status').filter(has_text='未保存')).to_be_visible()
    page.screenshot(path=str(OUT/'browser-team-retry.png'),full_page=True)
    failures['/api/team/1/member/11']='network'
    page.get_by_role('button',name='重试修改member的角色').click()
    expect(page.get_by_text('Network Error',exact=True).last).to_be_visible()
    expect(page.get_by_label('修改member的角色',exact=True)).to_have_value(R)
    assert next(m for m in members if m['id']=='11')['role']==R2
    del failures['/api/team/1/member/11']
    page.get_by_role('button',name='重试修改member的角色').click()
    expect(page.get_by_role('button',name='重试修改member的角色')).to_have_count(0)
    assert next(m for m in members if m['id']=='11')['role']==R
    page.locator('#main-content').get_by_role('button',name='关闭',exact=True).click()
    while page.get_by_role('button',name='关闭',exact=True).count():
        page.get_by_role('button',name='关闭',exact=True).first.click()
    page.get_by_role('button',name='空间根目录权限',exact=True).click()
    page.wait_for_load_state('networkidle')
    (OUT/'browser-team-acl.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
    page.get_by_label('规则主体类型').select_option('role')
    expect(page.get_by_label('选择角色').locator('option')).to_have_count(5)
    page.get_by_label('选择角色').select_option(R)
    page.get_by_role('checkbox',name='上传文件',exact=True).click()
    page.get_by_role('button',name='添加规则',exact=True).click()
    page.get_by_label('选择角色').select_option(R2)
    page.get_by_role('button',name='添加规则',exact=True).click()
    page.get_by_role('button',name='保存',exact=True).click()
    expect(page.get_by_label('规则主体类型')).to_have_count(0)
    assert [rule['subjectId'] for rule in acl]==[R,R2],acl
    assert json.loads(acl[0]['permissions'])['upload'] is True
    assert not json.loads(acl[1]['permissions']).get('upload',False)
    page.get_by_role('button',name='空间根目录权限',exact=True).click()
    page.get_by_label('规则主体类型').select_option('role')
    page.get_by_label('选择角色').select_option(R2)
    expect(page.get_by_role('checkbox',name='上传文件',exact=True).last).not_to_be_checked()
    page.get_by_label('选择角色').select_option(R)
    expect(page.get_by_role('checkbox',name='上传文件',exact=True).last).to_be_checked()
    print('PASS: 大ID邀请、成员更新/回显、权限/网络失败保留和重试、同名角色ACL分离保存与回显')
    assert not errors,errors
    page.screenshot(path=str(OUT/'browser-team-complete.png'),full_page=True)
    print('ERRORS',errors)
    (OUT/'browser-team-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2),encoding='utf8')
    browser.close()
