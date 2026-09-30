import json,time,base64,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from playwright.sync_api import sync_playwright,expect
sys.stdout.reconfigure(encoding='utf-8')
OUT=Path(__file__).resolve().parent
IDS=['9007199254740993','9007199254740995','9223372036854775806']
requests=[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    for i in range(3):
        node,version,space=IDS[i],IDS[(i+1)%3],IDS[(i+2)%3]
        page=browser.new_page(viewport={'width':1440,'height':1000})
        payload=base64.urlsafe_b64encode(json.dumps({'iat':int(time.time()),'exp':int(time.time())+3600}).encode()).decode().rstrip('=')
        page.add_init_script('sessionStorage.setItem("accessToken",'+json.dumps('fixture.'+payload+'.unsigned')+');')
        file={'id':node,'name':'precise.txt','nodeType':1,'parentId':space,'path':'/precise.txt','fileSize':32,'suffix':'txt','uploadStatus':2,'status':0}
        def api(route):
            r=route.request;u=urlparse(r.url);q=parse_qs(u.query);path=u.path
            requests.append({'path':path,'query':q,'body':json.loads(r.post_data or 'null')})
            def reply(data):route.fulfill(content_type='application/json',body=json.dumps({'code':200,'data':data,'message':'ok'}))
            if path=='/api/auth/me':reply({'userId':space,'username':'owner','roles':['admin'],'permissions':['file:preview','file:download'],'storageUsed':0,'storageQuota':1000000})
            elif path=='/api/team/'+space:reply({'id':space,'spaceName':'精度空间','ownerId':space,'myRole':'0','storageUsed':0,'storageQuota':1000000})
            elif path.endswith('/files') or path=='/api/file/list':reply({'records':[file],'total':1,'pages':1})
            elif path==f'/api/file/{node}/versions':reply([{'id':space,'fileNodeId':node,'versionNum':2,'fileSize':32,'current':True},{'id':version,'fileNodeId':node,'versionNum':1,'fileSize':32}])
            elif path==f'/api/preview/{node}/version/{version}':reply({'type':'text','content':'精确历史内容'})
            elif path=='/api/share/access/access':reply({'fileNodeId':space,'fileName':'分享目录','fileType':0,'allowDownload':1,'permissions':'preview,download','shareType':0})
            elif path=='/api/share/access/list':reply([dict(file,nodeId=node)])
            elif '/api/share/access/stream/' in path:route.fulfill(content_type='text/plain',body='精确分享内容')
            elif path.endswith('/ids') or path.endswith('/roles') or path.endswith('/tree'):reply([])
            else:reply({'records':[],'total':0})
        page.route('**/api/**',api)
        page.route('**/fixture/version.txt',lambda r:r.fulfill(content_type='text/plain',body='精确历史内容'))
        page.goto('http://127.0.0.1:5187/files/'+space);page.wait_for_load_state('networkidle')
        page.get_by_text('precise.txt',exact=True).click(button='right')
        page.get_by_text('历史版本',exact=True).click()
        page.get_by_role('button',name='预览',exact=True).last.click()
        expect(page.get_by_text('精确历史内容',exact=False)).to_be_visible()
        assert any(r['path']==f'/api/preview/{node}/version/{version}' for r in requests)
        page.goto('http://127.0.0.1:5187/share/'+version+'?pwd=precision');page.wait_for_load_state('networkidle')
        assert any(r['path']=='/api/share/access/list' and r['query'].get('parentId')==[space] and r['query'].get('shareCode')==[version] for r in requests)
        page.get_by_role('button',name='预览',exact=True).last.click()
        expect(page.get_by_text('精确分享内容',exact=False)).to_be_visible()
        assert any(r['path']==f'/api/share/access/stream/{version}' and r['query'].get('nodeId')==[node] for r in requests)
        page.screenshot(path=str(OUT/f'browser-version-share-{i}.png'),full_page=True)
        page.close()
    browser.close()
(OUT/'browser-version-share-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2),encoding='utf8')
print('PASS 三组大ID历史版本预览、分享目录/query/主图节点字符串精度，实际组件，API受控。')
