const fs = require('fs');
const crypto = require('crypto');
const dir = '.ai/docs/20260930-team-recycle';
const statePath = '.ai/state/20260930-team-recycle.json';
const read = () => JSON.parse(fs.readFileSync(statePath, 'utf8').replace(/^\uFEFF/, ''));
const write = value => fs.writeFileSync(statePath, JSON.stringify(value, null, 2) + '\n');
function loop(...args) {
  const quote = value => "'"+String(value).replace(/'/g,"''")+"'";
  fs.appendFileSync(dir+'/loop-command.ps1','& .ai/scripts/loopctl.ps1 '+args.map(x=>/^-\w+$/.test(x)?x:quote(x)).join(' ')+'\nif ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }\n');
}
fs.writeFileSync(dir+'/loop-command.ps1','');
const [command, criterion, extra] = process.argv.slice(2);
if (command === 'setup') {
  const state = read();
  for (const event of state.history) if (event.action === 'init') event.after = { taskId:state.taskId, scale:state.scale, status:'running', revision:state.revision };
  for (const name of ['requirement','uispec','impact','architecture-review','design','testcases']) state.artifacts[name] = {status:'ready',ref:dir+'/'+name+'.md'};
  for (const id of ['REQ_ANALYSIS','TECH_DESIGN']) state.exitCriteria.find(x=>x.id===id).confirmationRequired = false;
  for (const id of ['EXP_DESIGN','EXP_ACCEPT']) state.exitCriteria.find(x=>x.id===id).applicable = true;
  write(state); loop('validate',statePath);
} else if (command === 'direct') {
  const state = read(), codeBound = ['IMPLEMENTED','CODE_REVIEW','SECURITY_REVIEW','TEST_PASS','KNOWLEDGE','ACCEPT'];
  const artifact = extra || ({DESIGN:'design',REQ_ANALYSIS:'requirement',IMPACT_ANALYSIS:'impact',TECH_DESIGN:'design',TESTCASES:'testcases',IMPLEMENTED:'changereport',KNOWLEDGE:'knowledge'}[criterion]);
  state.artifacts[artifact] = {status:'ready',ref:dir+'/'+artifact+'.md'}; write(state);
  const proposal = {taskId:state.taskId,criterionProposal:{id:criterion,outcome:'pass',by:'workflow-manager',evidenceRef:dir+'/'+artifact+'.md',validatedRevision:codeBound.includes(criterion)?state.revision.code:state.revision.design}};
  const file = dir+'/direct-'+criterion+'.json';fs.writeFileSync(file,JSON.stringify(proposal,null,2));
  loop('evaluate-direct',statePath,'-ProposalPath',file,'-Actor','workflow-manager');
} else if (command === 'envelope') {
  const state = read(); const role = criterion === 'TEST_PASS'?'tester':'reviewer';
  const type = {EXP_DESIGN:'exp-review',CODE_REVIEW:'review',SECURITY_REVIEW:'security',EXP_ACCEPT:'exp-review',TEST_PASS:'test',ACCEPT:'accept'}[criterion];
  const taskId = 'TASK-20260930-TEAM-RECYCLE-'+criterion;
  const taskFile = '.ai/tasks/'+taskId+'.md', dispatchId = 'DISPATCH-20260930-TEAM-RECYCLE-'+criterion+'-'+crypto.randomBytes(3).toString('hex');
  const evidence = '.ai/runtime/results/'+dispatchId+'.json';fs.mkdirSync('.ai/runtime/results',{recursive:true});
  const artifact = {EXP_DESIGN:'exp-review',CODE_REVIEW:'codereview',SECURITY_REVIEW:'security',EXP_ACCEPT:'exp-review',TEST_PASS:'testreport',ACCEPT:'acceptance'}[criterion];
  const goal = criterion==='EXP_DESIGN'?'独立审查定版uispec与design中的F8降级体验，输出exp-review.md':criterion==='TEST_PASS'?'独立核对当前代码修订的主线程串行测试日志、XML与团队回收站用例，必要时仅执行不争用构建缓存的补充验证，输出testreport.md':criterion==='ACCEPT'?'对当前修订逐项核对Goal九条完成标准与独立评审/测试证据，提出验收建议及acceptanceEvidence，不判定整个Goal完成':'独立审查本次团队回收站修复的'+criterion+'，只审本任务相对baseline的新改动；报告原有未改代码时须证明属于本任务修复必要范围';
  fs.writeFileSync(taskFile,`# ${taskId}\n\n目标：${goal}。\nState：${statePath}（只读最小快照）。\n修订：${state.revision.code||state.revision.design}。\n输入：${dir}/design.md、design.md、testcases.md及主TASK及design.md；baseline记录本次开始前源码哈希/副本。\ninclude 写入白名单：${dir}/${artifact}.md、${evidence}。其余源码/State/配置只读。\n不得生成子Agent，不得修改业务源码，不得调用Git提交或共享构建。\n结果包含背景、输入、分析、决策、State Delta proposal、风险、下一步、变更影响；真实执行者by使用Runtime提供的agent UUID，或报告自身canonical name由主线程与UUID映射。\n输出JSON含dispatchId/taskId/status/artifactRefs/criterionProposal（id=${criterion},outcome,by,dispatchId,evidenceRef,validatedRevision）/blockerProposals；ACCEPT还须逐项acceptanceEvidence（criterionId为goal原文、evidenceRef、validatedRevision）。\n`);
  const envelope={schemaVersion:2,dispatchId,taskId,idempotencyKey:taskId,role,taskType:type,objective:goal,taskRefs:[taskFile,dir+'/design.md',dir+'/design.md',dir+'/testcases.md'],stateRef:statePath,skillRefs:['-'],scope:{include:[dir+'/'+artifact+'.md',evidence],exclude:['.ai/state/**','**/src/**','docker/**']},acceptance:['产物真实且仅写入白名单','当前修订团队回收站范围有事实与证据，结果仅proposal'],validation:['读取最小State快照与本任务baseline，不加载历史State','对照用例和现有代码/日志，未执行验证须明确'],forbidSpawn:true,forbidGitMvn:true,output:{resultRef:evidence,artifactRef:dir+'/'+artifact+'.md',criterionId:criterion,validatedRevision:criterion==='EXP_DESIGN'?state.revision.design:state.revision.code}};
  const file=dir+'/envelope-'+criterion+'.json';fs.writeFileSync(file,JSON.stringify(envelope,null,2));loop('validate-dispatch','-DispatchPath',file);console.log('DISPATCH_ENVELOPE\n'+JSON.stringify(envelope,null,2));
} else if (command === 'register') {
  const envelope=JSON.parse(fs.readFileSync(dir+'/envelope-'+criterion+'.json','utf8')),state=read();
  state.dispatchLedger.push({dispatchId:envelope.dispatchId,taskId:envelope.taskId,idempotencyKey:envelope.idempotencyKey,role:envelope.role,taskType:envelope.taskType,criterionId:criterion,childId:extra,status:'planned',envelopeRef:dir+'/envelope-'+criterion+'.json',resultRef:envelope.output.resultRef});write(state);
  loop('dispatch-transition',statePath,'-DispatchId',envelope.dispatchId,'-DispatchStatus','spawned');
} else if (command === 'acked') {
  const envelope=JSON.parse(fs.readFileSync(dir+'/envelope-'+criterion+'.json','utf8'));
  loop('dispatch-transition',statePath,'-DispatchId',envelope.dispatchId,'-DispatchStatus','acked');loop('dispatch-transition',statePath,'-DispatchId',envelope.dispatchId,'-DispatchStatus','running');
} else if (command === 'evaluate') {
  const envelope=JSON.parse(fs.readFileSync(dir+'/envelope-'+criterion+'.json','utf8')), state=read();
  const result=JSON.parse(fs.readFileSync(envelope.output.resultRef,'utf8').replace(/^\uFEFF/,''));
  const attempt=state.dispatchLedger.find(x=>x.dispatchId===envelope.dispatchId);
  if (result.dispatchId!==envelope.dispatchId || result.taskId!==envelope.taskId) throw Error('结果身份不匹配');
  for(const ref of result.artifactRefs) if(!envelope.scope.include.includes(ref) || !fs.existsSync(ref)) throw Error('结果产物越界/缺失: '+ref);
  attempt.scopeVerified=true;
  if (result.criterionProposal.by!==attempt.childId) {result.runtimeBy=result.criterionProposal.by;result.criterionProposal.by=attempt.childId;fs.writeFileSync(envelope.output.resultRef,JSON.stringify(result,null,2));}
  for(const ref of result.artifactRefs)state.artifacts[ref]={status:'ready',ref};write(state);
  loop('dispatch-transition',statePath,'-DispatchId',envelope.dispatchId,'-DispatchStatus','returned');loop('evaluate',statePath,'-ProposalPath',envelope.output.resultRef);
}
