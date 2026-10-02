from pathlib import Path
import re

root = Path(__file__).resolve().parents[3]
baseline = Path(__file__).parent / 'baseline'
pattern = re.compile(r'^<<<<<<<[^\n]*\n(.*?)^=======\n(.*?)^>>>>>>>[^\n]*\n?', re.M | re.S)
for source in baseline.rglob('*'):
    if not source.is_file():
        continue
    relative = source.relative_to(baseline)
    text = source.read_text(encoding='utf-8')
    if relative.name == 'ObjectCleanupRaceIntegrationTest.java':
        # 两侧测试关注不同路径，分别保留，避免同名类覆盖丢失规范对象回收回归。
        canonical = pattern.sub(lambda m: m.group(1), text).replace('ObjectCleanupRaceIntegrationTest', 'CanonicalObjectCleanupRaceIntegrationTest')
        root.joinpath(relative.with_name('CanonicalObjectCleanupRaceIntegrationTest.java')).write_text(canonical, encoding='utf-8')
    if relative.name == 'schema.sql':
        text = pattern.sub(lambda m: m.group(1) + '\n' + m.group(2), text)
    else:
        text = pattern.sub(lambda m: m.group(2), text)
    if relative.name == 'Sidebar.tsx':
        text = text.replace('EyeOff, GripVertical }', 'EyeOff, GripVertical, Bell }')
        needle = "{ key: 'favorites', to: '/favorites', icon: Star, label: '我的收藏', end: false, section: '文件' },"
        text = text.replace(needle, needle + "\n    { key: 'following', to: '/following', icon: Bell, label: '我的关注', end: false, section: '文件' },")
    if relative.name in ('UploadServiceImpl.java', 'TextFileServiceImpl.java', 'EditorCallbackServiceImpl.java', 'ArchiveServiceImpl.java'):
        # 新 UUID 候选由 upload 自行处理 PUT 失败；上层仅在业务提交失败时放弃，成功认领留在同一事务。
        text = re.sub(r'\s*@org\.springframework\.beans\.factory\.annotation\.Autowired\(required = false\)\n\s*private (?:com\.stcloud\.core\.service\.impl\.)?OrphanObjectCleanupService orphanObjectCleanupService;\n', '\n', text)
        text = re.sub(r'\n\s*if \(uploadedNew\) \{\n\s*if \(orphanObjectCleanupService != null\) \{\n\s*orphanObjectCleanupService\.markCommitted\(tenantId, storagePath\);\n\s*}\n\s*}', '', text)
        text = re.sub(r'if \(orphanObjectCleanupService != null\) \{\n\s*orphanObjectCleanupService\.markFailed\(tenantId, storagePath\);\n\s*}', 'cleanupOrphanUpload(tenantId, md5, storagePath);', text)
        # 流打开/关闭异常不能访问尚未赋值的路径；upload 内部负责记录上传失败候选。
        text = re.sub(r'(catch \(IOException e\) \{)\n\s*cleanupOrphanUpload\(tenantId, md5, storagePath\);', r'\1', text)
        text = re.sub(r'\s*} catch \(RuntimeException e\) \{\n\s*cleanupOrphanUpload\(tenantId, md5, storagePath\);\n\s*throw e;\n', '\n', text)
        # 解压条目已在提交中认领；没有规范路径活动计数需要提交后释放。
        text = re.sub(r'\n\s*for \(Map<String, Object> item : entries\) \{\n\s*if \(Boolean\.TRUE\.equals\(item\.get\("uploadedNew"\)\)\) \{\n\s*if \(orphanObjectCleanupService != null\) \{\n\s*orphanObjectCleanupService\.markCommitted\(tenantId, \(String\) item\.get\("storagePath"\)\);\n\s*}\n\s*}\n\s*}', '', text)
    if relative.name == 'ArchiveExtractTransactionBoundaryTest.java':
        needle = 'verify(storageService, org.mockito.Mockito.never()).deleteObject(anyString());'
        text = text.replace(needle, needle + '\n        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM object_upload_candidate WHERE tenant_id=1 AND md5 IN (?,?) AND state=\'DISCARDED\'", Long.class, DigestUtil.md5Hex("hello"), DigestUtil.md5Hex("world")));', 1)
    if '<<<<<<<' in text or '>>>>>>>' in text:
        raise RuntimeError(f'unresolved: {relative}')
    root.joinpath(relative).write_text(text, encoding='utf-8')
    print(relative.as_posix())
