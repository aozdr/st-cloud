# 第三轮测试用例
设计已由用户“执行”确认，按阶段逐项验证，未执行的不标通过。
P0-01：两线程同 tenant/MD5 simpleUpload，PUT barrier 后失败方 DB rollback、胜者 commit；断言节点、object、ref_count=1、物理对象存在，失败方不 DELETE 胜者。
P0-02：解压与另一路同内容并发，一败一成；断言胜者物理对象和引用。
P0-03：真正孤儿超 grace 无引用/活跃会话，GC删除；路径不同的去重胜者不阻止败者回收。
P0-04：GC CAS 赢先，commit必须失败；commit先赢，GC不得删除；正常/回收站节点、版本、活跃会话均保护。
P0-05：DELETE后迟到PUT，write_finished未知墓碑持续；DELETE前finished=false不能凭DELETE后true停止扫描；S3失败重试。
P0-06：文本/OnlyOffice失败补偿不立即删；数据库事务中无S3；旧canonical可复用。
D-01：relay init→若干小块→pause→resume，只relay-chunk/finalize，无status/chunk-url/PUT/merge。
D-02：三项元数据SQLite读回；旧表升级重复执行仍保留内容。
D-03：重启无内存上下文及未知模式不进入direct；快速pause/resume单循环；业务失败不推进seq。
R-01：flush uploadPart异常，同seq重试明确失败/abort，不误确认。
R-02：跳号、0/负序号、in-flight重复拒绝，不推进。
R-03：已提交重复不重复写；请求中多次append正确保留flush结果；finalize不与in-flight交错。
S-01：filmstrip只thumbnail；非图片和失败无原图回退。
S-02：密码/过期/下载开关/权限集/范围/验证码/限额均保持；成功按现有语义计数。
全量：附件core定向与全量、team全量、web test:hash/build、desktop test/lint/build:main、share/preview测试、schema两次MySQL对比、git diff --check。每条记录命令、真实数量、退出码。
