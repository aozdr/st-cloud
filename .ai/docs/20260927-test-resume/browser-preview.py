import json,time,base64,sys,io
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from collections import Counter
from PIL import Image
from playwright.sync_api import sync_playwright,expect
sys.stdout.reconfigure(encoding='utf-8')
OUT=Path(__file__).resolve().parent
files=[{'id':str(100+i),'name':name,'suffix':name.split('.')[-1],'nodeType':1,'parentId':'0','path':'/'+name,'fileSize':50000000,'status':0,'uploadStatus':2} for i,name in enumerate(['large-a.jpg','large-b.jpg','large-c.jpg','modern.webp','script.svg','animated.gif'])]
requests=[];errors=[];media=[];badSvg=False
def picture(kind):
    out=io.BytesIO();Image.new('RGB',(24,16),'red').save(out,format=kind);return out.getvalue()
def reply(route,data,code=200):
    route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':code,'data':data,'message':'isolated error' if code!=200 else 'ok'}))
def api(route):
    req=route.request;p=urlparse(req.url);q=parse_qs(p.query);path=p.path;requests.append({'path':path,'query':q,'method':req.method})
    if path=='/api/auth/me':reply(route,{'userId':'100','username':'owner','roles':['admin'],'permissions':['file:download','file:preview'],'storageUsed':0,'storageQuota':1000000000});return
    if path=='/api/team/1':reply(route,{'id':'1','spaceName':'预览验收','ownerId':'100','myRole':'0','storageUsed':0,'storageQuota':1000000000});return
    if path=='/api/team/1/files':reply(route,{'records':files,'total':len(files),'pages':1});return
    if path.startswith('/api/preview/'):
        assert req.headers.get('authorization','').startswith('Bearer fixture.')
        id=path.split('/')[3]
        if path.endswith('/thumbnail'):
            if q.get('size')==['sm']:
                if id=='100':route.fulfill(status=404,body='missing')
                elif id=='101':reply(route,None,400)
                else:reply(route,'/fixture/broken-image')
            else:reply(route,'/fixture/main.png')
        else:reply(route,{'type':'image','url':'/fixture/'+('image.webp' if id=='103' else 'script.svg')+'?authorized=true'})
        return
    if path.endswith('/download-token'):reply(route,{'token':'fixture-node-scoped-token'});return
    if path.endswith('/stream'):
        assert path=='/api/file/105/stream';assert q.get('token')==['fixture-node-scoped-token'];route.fulfill(content_type='image/gif',body=picture('GIF'));return
    if path.endswith('/ids') or path.endswith('/list') or path.endswith('/roles'):reply(route,[]);return
    reply(route,{'records':[],'total':0,'unreadCount':0})
def fixture(route):
    p=urlparse(route.request.url);media.append(p.path)
    if p.path.endswith('script.svg'):
        route.fulfill(content_type='image/svg+xml',body='invalid-svg' if badSvg else '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" onload="window.__svgExecuted=true"><script>window.__svgExecuted=true;fetch("https://svg-external.invalid/executed")</script><image href="https://svg-external.invalid/image"/><rect width="100" height="100" fill="red"/></svg>')
    elif p.path.endswith('image.webp'):route.fulfill(content_type='image/webp',body=picture('WEBP'))
    elif p.path.endswith('broken-image'):route.fulfill(content_type='image/jpeg',body=b'invalid-image')
    else:route.fulfill(content_type='image/png',body=picture('PNG'))
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True);page=browser.new_page(viewport={'width':1440,'height':1000})
    payload=base64.urlsafe_b64encode(json.dumps({'iat':int(time.time()),'exp':int(time.time())+3600}).encode()).decode().rstrip('=')
    page.add_init_script('sessionStorage.setItem("accessToken",'+json.dumps('fixture.'+payload+'.unsigned')+');')
    page.route('**/api/**',api);page.route('**/fixture/**',fixture)
    externals=[];page.route('https://svg-external.invalid/**',lambda r:(externals.append(r.request.url),r.abort()))
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto('http://127.0.0.1:5187/team/1');page.wait_for_load_state('networkidle')
    page.get_by_text('large-a.jpg',exact=True).dblclick();page.wait_for_load_state('networkidle')
    (OUT/'browser-preview-open.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
    (OUT/'browser-preview-debug.json').write_text(json.dumps({'requests':requests,'errors':errors},ensure_ascii=False,indent=2),encoding='utf8')
    page.screenshot(path=str(OUT/'browser-preview-open.png'),full_page=True)
    expect(page.get_by_role('button',name='下一个',exact=True)).to_be_enabled()
    for file in files:expect(page.locator('button[title="'+file['name']+'"]')).to_be_visible()
    assert not any(r['path'].endswith('/stream') or r['path'].endswith('/download-token') for r in requests)
    before=Counter(r['path']+str(r['query']) for r in requests);page.wait_for_timeout(600)
    assert before==Counter(r['path']+str(r['query']) for r in requests),'fallback重复发请求'
    for _ in range(3):page.get_by_role('button',name='下一个',exact=True).click();page.wait_for_load_state('networkidle')
    expect(page.locator('img[src="/fixture/image.webp?authorized=true"]')).to_be_visible()
    page.get_by_role('button',name='下一个',exact=True).click();page.wait_for_load_state('networkidle')
    expect(page.locator('img[src="/fixture/script.svg?authorized=true"]')).to_be_visible()
    assert page.locator('object,iframe,embed').count()==0
    assert page.evaluate('window.__svgExecuted') is None;assert not externals,externals
    page.screenshot(path=str(OUT/'browser-preview-svg.png'),full_page=True)
    page.locator('button[title="animated.gif"]').click();page.wait_for_load_state('networkidle')
    expect(page.locator('img[src*="/api/file/105/stream"]')).to_be_visible()
    assert any(r['path']=='/api/file/105/stream' for r in requests)
    assert not any(r['path'].endswith('/stream') and r['path']!='/api/file/105/stream' for r in requests)
    assert not any(r['path'] in ['/api/preview/103/thumbnail','/api/preview/104/thumbnail'] for r in requests)
    badSvg=True;page.locator('button[title="script.svg"]').click()
    expect(page.get_by_text('暂无法预览，可下载原文件',exact=True)).to_be_visible()
    beforeRetry=sum(r['path']=='/api/preview/104' for r in requests)
    page.get_by_role('button',name='重试预览',exact=True).click()
    expect(page.get_by_text('暂无法预览，可下载原文件',exact=True)).to_be_visible()
    expect(page.get_by_role('button',name='重试预览',exact=True)).to_have_count(0)
    assert sum(r['path']=='/api/preview/104' for r in requests)==beforeRetry+1
    before=Counter(r['path']+str(r['query']) for r in requests);page.wait_for_timeout(600)
    assert before==Counter(r['path']+str(r['query']) for r in requests)
    expect(page.get_by_role('button',name='上一个',exact=True)).to_be_enabled()
    page.screenshot(path=str(OUT/'browser-preview-fallback.png'),full_page=True)
    assert not errors,errors
    (OUT/'browser-preview-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2),encoding='utf8')
    print('PASS: 胶卷404/业务错误/坏图片降级，无批量原图及循环请求；名称与导航保持；WebP/SVG主图经授权API并仅img展示，脚本/外链不执行；GIF主图使用专用token原流。')
    browser.close()
