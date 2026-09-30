import json, io, sys
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from PIL import Image
from playwright.sync_api import sync_playwright, expect
sys.stdout.reconfigure(encoding='utf-8')
out = Path(__file__).resolve().parent
image = io.BytesIO()
Image.new('RGB', (40, 30), '#3683c0').save(image, format='PNG')
files = [{'id':str(100+i),'nodeId':str(100+i),'name':name,'suffix':name.split('.')[-1],
          'nodeType':1,'fileSize':32,'createdAt':'2026-09-30T00:00:00','updatedAt':'2026-09-30T00:00:00'}
         for i,name in enumerate(['network.jpg','broken.png','normal.jpg','modern.webp','script.svg'])]
requests = []
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={'width':1280,'height':900})
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    def api(route):
        u=urlparse(route.request.url); q=parse_qs(u.query); requests.append({'path':u.path,'query':q})
        def reply(data):route.fulfill(content_type='application/json',body=json.dumps({'code':200,'data':data,'message':'ok'}))
        if u.path=='/api/share/access/access':reply({'fileNodeId':'R','fileName':'胶卷验收','fileType':0,'allowDownload':1,'permissions':'preview,download','shareType':0})
        elif u.path=='/api/share/access/list':reply(files)
        elif '/api/share/access/thumbnail/' in u.path:
            if q.get('nodeId')==['100']:route.fulfill(status=503,body='temporary unavailable')
            elif q.get('nodeId')==['101']:route.fulfill(content_type='image/png',body=b'invalid image')
            else:route.fulfill(content_type='image/png',body=image.getvalue())
        elif '/api/share/access/stream/' in u.path:route.fulfill(content_type='image/png',body=image.getvalue())
        elif u.path.endswith('/ids'):reply([])
        else:reply({'records':[],'total':0})
    page.route('**/api/**', api)
    page.goto('http://127.0.0.1:5199/share/review-a?pwd=fixture')
    page.wait_for_load_state('networkidle')
    (out/'browser-filmstrip-before.html').write_text(page.content(),encoding='utf8')
    page.get_by_role('button',name='预览',exact=True).first.click()
    page.wait_for_load_state('networkidle')
    for file in files:
        button=page.get_by_role('button',name='预览 '+file['name'],exact=True)
        expect(button).to_be_visible()
        if file['id'] in ['100','101','103','104']:
            expect(button.locator('svg.lucide-image')).to_be_visible()
            assert button.locator('img').count()==0
        else:expect(button.locator('img')).to_be_visible()
    page.screenshot(path=str(out/'browser-filmstrip-fallback.png'),full_page=True)
    broken=page.get_by_role('button',name='预览 broken.png',exact=True)
    broken.click()
    expect(broken).to_have_class(__import__('re').compile('border-primary-400'))
    network=page.get_by_role('button',name='预览 network.jpg',exact=True)
    network.focus();page.keyboard.press('Enter')
    expect(network).to_have_class(__import__('re').compile('border-primary-400'))
    first_source=[r for r in requests if '/thumbnail/review-a' in r['path']]
    assert len([r for r in first_source if r['query'].get('nodeId')==['100']])==1
    # 换分享来源后同节点重新请求，失败状态不能污染新的URL。
    page.goto('http://127.0.0.1:5199/share/review-b?pwd=fixture')
    page.wait_for_load_state('networkidle')
    page.get_by_role('button',name='预览',exact=True).first.click()
    page.wait_for_load_state('networkidle')
    expect(page.get_by_role('button',name='预览 network.jpg',exact=True).locator('svg.lucide-image')).to_be_visible()
    assert any('/thumbnail/review-b' in r['path'] and r['query'].get('nodeId')==['100'] for r in requests)
    assert not errors,errors
    browser.close()
(out/'browser-filmstrip-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2),encoding='utf8')
print('PASS F8: 503/损坏图片与WebP/SVG图标降级；正常图片；名称/点击/Enter导航；新分享URL重新请求；无pageerror。真实React组件，API受控。')
