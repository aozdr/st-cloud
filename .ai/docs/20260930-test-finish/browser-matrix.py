from pathlib import Path
import subprocess,sys
out=Path('.ai/docs/20260930-test-finish')
old=Path('.ai/docs/20260927-test-resume')
ids=['9007199254740993','9007199254740995','9223372036854775806']
for i in range(3):
    folder=out/f'browser-id-{i}'
    folder.mkdir(exist_ok=True)
    code=(old/'browser-team.py').read_text(encoding='utf-8-sig')
    replacements={'9007199254740993':ids[i],'9007199254740995':ids[(i+1)%3],"'100'":"'"+ids[i]+"'","'200'":"'"+ids[(i+1)%3]+"'","'201'":"'"+ids[(i+2)%3]+"'","'202'":"'"+ids[(i+2)%3]+"'","'10'":"'"+ids[i]+"'","'11'":"'"+ids[(i+1)%3]+"'","'12'":"'"+ids[(i+2)%3]+"'"}
    import re
    code=re.sub('|'.join(re.escape(k) for k in replacements),lambda m:replacements[m.group()],code)
    code=code.replace('/team/1','/team/'+ids[i]).replace("'id':'1'","'id':'"+ids[i]+"'")
    # 动态邀请/ACL记录也使用字符串大ID。
    code=code.replace("'id':'13'","'id':'"+ids[(i+2)%3]+"'")
    code=code.replace("str(20+len(invites))","str(int('"+ids[(i+2)%3]+"')-len(invites))")
    code=code.replace("str(index+1)","'"+ids[(i+2)%3]+"'")
    # 旧失败路径的成员ID同步替换。
    code=code.replace('/member/11','/member/'+ids[(i+1)%3])
    (folder/'browser-team.py').write_text(code,encoding='utf-8')
    copy=(old/'browser-copy.py').read_text(encoding='utf-8-sig')
    copy=copy.replace('9007199254740993','SOURCE_ID').replace('9007199254740995','TARGET_ID')
    copy=copy.replace('SOURCE_ID',ids[i]).replace('TARGET_ID',ids[(i+1)%3]).replace('/team/1','/team/'+ids[(i+2)%3])
    copy=copy.replace("'id':'1'","'id':'"+ids[(i+2)%3]+"'")
    (folder/'browser-copy.py').write_text(copy,encoding='utf-8')
    for script in ['browser-team.py','browser-copy.py']:
        subprocess.run([sys.executable,str(folder/script)],check=True)
(out/'browser-preview.py').write_text((old/'browser-preview.py').read_text(encoding='utf-8-sig'),encoding='utf-8')
subprocess.run([sys.executable,str(out/'browser-preview.py')],check=True)
