package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import com.stcloud.common.cache.TtlCache;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamFolderPermission;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.mapper.TeamFolderPermissionMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文件夹权限链计算服务（权限模型重设计 TASK-PERM-BE1）
 * <p>
 * 新语义：用户对节点有效权限 = 空间角色权限集 ∪ 从当前节点向上遍历至空间根收集的
 * 全部匹配文件夹规则权限集（并集增强）。规则主体支持：
 * <ul>
 *   <li>all    —— 全体成员（管理员除外，管理员直通由 TeamService 保证）</li>
 *   <li>member —— 单个用户（subject_id = 用户ID）</li>
 *   <li>role   —— 角色（subject_id = 成员角色ID，0/1/2 预设或 >=100 自定义）</li>
 * </ul>
 * 规则权限以 permissions JSON 为权威，空则回退旧 permission 单值映射；
 * 显式 -1（无权限）仅标注、不参与并集（只增强不禁止）。
 */
@Slf4j
@Service
public class FolderPermissionService {

    // ==================== 权限点常量 ====================
    public static final String PERM_VIEW = "view";
    public static final String PERM_UPLOAD = "upload";
    public static final String PERM_DOWNLOAD = "download";
    public static final String PERM_DELETE = "delete";
    public static final String PERM_RENAME = "rename";
    public static final String PERM_MOVE = "move";
    public static final String PERM_SHARE = "share";
    /** 在线编辑文档（OnlyOffice 编辑权限，2026-08-15 新增；独立于上传） */
    public static final String PERM_EDIT = "edit";
    public static final String PERM_MANAGE_MEMBERS = "manage_members";
    public static final String PERM_MANAGE_SETTINGS = "manage_settings";

    /** 权限点固定顺序（JSON 序列化/展示使用） */
    private static final List<String> PERM_ORDER = List.of(
            PERM_VIEW, PERM_UPLOAD, PERM_DOWNLOAD, PERM_DELETE, PERM_RENAME,
            PERM_MOVE, PERM_SHARE, PERM_EDIT, PERM_MANAGE_MEMBERS, PERM_MANAGE_SETTINGS);

    /** 全部 10 个权限点 */
    public static final Set<String> ALL_PERMISSIONS = Set.copyOf(PERM_ORDER);

    /** 内置编辑者权限集（无空间级管理权限） */
    public static final Set<String> EDITOR_PERMISSIONS = Set.of(
            PERM_VIEW, PERM_UPLOAD, PERM_DOWNLOAD, PERM_DELETE, PERM_RENAME,
            PERM_MOVE, PERM_SHARE, PERM_EDIT);

    /** 内置查看者权限集：仅在线查看，download=false（对抗下载后续处理） */
    public static final Set<String> VIEWER_PERMISSIONS = Set.of(PERM_VIEW);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private TeamFolderPermissionMapper teamFolderPermissionMapper;

    @Resource
    private FileNodeMapper fileNodeMapper;

    @Resource
    private TeamMemberMapper teamMemberMapper;

    /** 权限结果缓存（TASK-005）：key=spaceId:nodeId:userId:rolePerms，未命中才向上遍历计算并回填；
     * 默认内存实现，开启 stcloud.cache.redis.enabled 后由 CacheFactory 切换为 Redis（TASK-003） */
    private Cache permissionCache = new TtlCache(PERMISSION_CACHE_TTL_MS);

    /** 缓存后端工厂（可选）：Redis 启用时切换缓存实现（TASK-003） */
    @Autowired(required = false)
    private CacheFactory cacheFactory;

    /** 权限缓存 TTL：60 秒，作为多实例/遗漏失效场景的最终一致性兜底 */
    private static final long PERMISSION_CACHE_TTL_MS = 60_000;

    /** 向上遍历父链最大层数，防脏数据死循环 */
    private static final int MAX_PARENT_DEPTH = 20;

    @PostConstruct
    void initPermissionCache() {
        // 仅当存在缓存工厂（Spring 容器）时按配置切换后端；单元测试直 new 时保持默认内存缓存
        if (cacheFactory != null) {
            permissionCache = cacheFactory.create(PERMISSION_CACHE_TTL_MS);
        }
    }

    // ==================== 权限点工具 ====================

    /**
     * 解析权限点 JSON（{"view":true,"upload":false,...}）为权限点集合；
     * 自动补全隐含关系：upload/download 隐含 view。
     * 解析失败返回空集（不影响主流程）。
     */
    public static Set<String> parsePermissions(String json) {
        Set<String> result = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                    result.add(entry.getKey());
                }
            });
        } catch (Exception e) {
            log.warn("权限点JSON解析失败: {}", json, e);
        }
        return normalizePermissions(result);
    }

    /**
     * 权限点集合转 JSON 字符串（按固定顺序输出全部 9 个键，缺省 false）。
     */
    public static String permissionsToJson(Set<String> permissions) {
        Set<String> perms = permissions == null ? Set.of() : permissions;
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < PERM_ORDER.size(); i++) {
            String key = PERM_ORDER.get(i);
            if (i > 0) sb.append(',');
            sb.append('"').append(key).append("\":").append(perms.contains(key));
        }
        return sb.append('}').toString();
    }

    /**
     * 权限点集合归一化：upload/download 隐含 view。
     */
    public static Set<String> normalizePermissions(Set<String> permissions) {
        Set<String> result = new LinkedHashSet<>();
        if (permissions != null) {
            result.addAll(permissions);
        }
        if (result.contains(PERM_UPLOAD) || result.contains(PERM_DOWNLOAD)) {
            result.add(PERM_VIEW);
        }
        return result;
    }

    /**
     * 内置角色权限集：0-管理员（全部）、1-编辑者、2-查看者（仅 view，download=false）。
     */
    public static Set<String> presetPermissions(int role) {
        return switch (role) {
            case 0 -> ALL_PERMISSIONS;
            case 1 -> EDITOR_PERMISSIONS;
            default -> VIEWER_PERMISSIONS;
        };
    }

    /**
     * 旧单值权限映射为权限点集合（历史数据回退用）：
     * -1 → 空集（仅标注，规则只增强不禁止）；0 → 全部；1 → 编辑者集；2 → {view}。
     */
    public static Set<String> legacyPermissionSet(Integer permission) {
        if (permission == null) {
            return Set.of();
        }
        return switch (permission) {
            case 0 -> ALL_PERMISSIONS;
            case 1 -> EDITOR_PERMISSIONS;
            case 2 -> VIEWER_PERMISSIONS;
            default -> Set.of();
        };
    }

    /**
     * 权限集推导旧角色等级（兼容旧 checkPermission/首值语义）：
     * 含空间管理权限 → 0；含任一内容操作权限 → 1；仅 view → 2；否则 -1。
     */
    public static int legacyLevelOf(Set<String> permissions) {
        Set<String> perms = permissions == null ? Set.of() : permissions;
        if (perms.contains(PERM_MANAGE_SETTINGS) || perms.contains(PERM_MANAGE_MEMBERS)) {
            return 0;
        }
        if (perms.contains(PERM_UPLOAD) || perms.contains(PERM_DOWNLOAD) || perms.contains(PERM_DELETE)
                || perms.contains(PERM_RENAME) || perms.contains(PERM_MOVE) || perms.contains(PERM_SHARE)) {
            return 1;
        }
        if (perms.contains(PERM_VIEW)) {
            return 2;
        }
        return -1;
    }

    // ==================== 权限解析 ====================

    /**
     * 计算用户对节点的有效权限点集合（并集）：
     * rolePerms ∪ 沿父链（最多 20 层）收集的 all/member/role 规则权限。
     * 规则权限以 permissions JSON 优先，空则回退旧 permission 单值映射。
     *
     * @param spaceId   空间ID
     * @param nodeId    文件/文件夹节点ID（null 时仅返回角色权限集）
     * @param userId    用户ID
     * @param rolePerms 用户空间角色权限点集合（角色解析由 TeamService 完成）
     * @return 有效权限点集合（只增强，不因规则减少）
     */
    public Set<String> resolvePermissions(Long spaceId, Long nodeId, Long userId, Set<String> rolePerms) {
        Set<String> result = normalizePermissions(rolePerms);
        if (nodeId == null) {
            return result;
        }

        // 命中缓存直接返回，避免大目录深层级向上遍历的重复 SQL（每层 2 条查询）
        String key = cacheKey(spaceId, nodeId, userId, result);
        @SuppressWarnings("unchecked")
        Set<String> cached = (Set<String>) permissionCache.get(key);
        if (cached != null) {
            return cached;
        }

        // 保持原调用链的查询与缓存行为；显式主体链路使用下方 fresh 入口。
        Long memberRole = null;
        Long currentId = nodeId;
        Set<Long> visited = new LinkedHashSet<>();
        boolean reachedRoot = currentId == 0;
        if (currentId < 0) {
            return Set.of();
        }
        for (int i = 0; i < MAX_PARENT_DEPTH && currentId != null && currentId > 0; i++) {
            // 常规授权也必须先核实整条父链的空间归属；跨空间、断链或循环都拒绝授权。
            if (!visited.add(currentId)) {
                return Set.of();
            }
            FileNode node = fileNodeMapper.selectById(currentId);
            if (node == null || !Objects.equals(node.getSpaceId(), spaceId)
                    || Objects.equals(node.getDeleted(), 1) || !node.isNormal()) {
                return Set.of();
            }
            List<TeamFolderPermission> perms = teamFolderPermissionMapper.selectList(
                    new LambdaQueryWrapper<TeamFolderPermission>()
                            .eq(TeamFolderPermission::getSpaceId, spaceId)
                            .eq(TeamFolderPermission::getFolderNodeId, currentId));
            for (TeamFolderPermission p : perms) {
                if (!matches(p, userId, memberRole, spaceId)) {
                    continue;
                }
                result.addAll(rulePermissionSet(p));
            }

            if (node.getParentId() == null || node.getParentId() == 0) {
                reachedRoot = true;
                break;
            }
            if (node.getParentId() < 0 || i == MAX_PARENT_DEPTH - 1) {
                return Set.of();
            }
            currentId = node.getParentId();
        }

        if (reachedRoot) {
            // 虚拟根目录没有 file_node；仅在父链确实到达 0 时读取本空间根规则。
            List<TeamFolderPermission> rootRules = teamFolderPermissionMapper.selectList(
                    new LambdaQueryWrapper<TeamFolderPermission>()
                            .eq(TeamFolderPermission::getSpaceId, spaceId)
                            .eq(TeamFolderPermission::getFolderNodeId, 0L));
            for (TeamFolderPermission rule : rootRules) {
                if (matches(rule, userId, memberRole, spaceId)) {
                    result.addAll(rulePermissionSet(rule));
                }
            }
        }

        // 回填缓存，供后续访问命中
        permissionCache.put(key, new LinkedHashSet<>(result));
        return result;
    }

    /**
     * 以当前数据库数据重新解析权限，不读取或写入共享权限缓存。
     * 团队搜索、通知投递等异步/显式主体链路必须使用该入口，避免把其他请求的权限快照当作授权依据。
     */
    public Set<String> resolvePermissionsFresh(Long spaceId, Long nodeId, Long userId, Set<String> rolePerms) {
        return resolvePermissionsInternal(null, spaceId, nodeId, userId, normalizePermissions(rolePerms), true);
    }

    /**
     * 显式租户版本的 fresh 权限解析。除权限规则外，祖先节点和成员角色查询也带租户条件，
     * 因而在 PRIVATE 模式关闭 MyBatis 租户拦截器时仍不会把其他租户的数据混入计算。
     */
    public Set<String> resolvePermissionsFreshForTenant(Long tenantId, Long spaceId, Long nodeId,
                                                          Long userId, Set<String> rolePerms) {
        return resolvePermissionsInternal(tenantId, spaceId, nodeId, userId, normalizePermissions(rolePerms), true);
    }

    /**
     * 搜索候选扫描专用的请求内快照。只在一次扫描中复用祖先和规则；响应输出前仍调用
     * 无缓存的 fresh 入口重新核权，避免把扫描早期的权限当作最终授权。
     */
    public FreshPermissionContext freshPermissionContext(Long tenantId, Long spaceId, Long userId,
                                                         Long memberRole, Set<String> rolePerms) {
        return new FreshPermissionContext(tenantId, spaceId, userId, memberRole,
                normalizePermissions(rolePerms));
    }

    public final class FreshPermissionContext {
        private final Long tenantId;
        private final Long spaceId;
        private final Long userId;
        private final Long memberRole;
        private final Set<String> rolePerms;
        private final Map<Long, FileNode> nodes = new HashMap<>();
        private final Map<Long, List<TeamFolderPermission>> rules = new HashMap<>();

        private FreshPermissionContext(Long tenantId, Long spaceId, Long userId,
                                       Long memberRole, Set<String> rolePerms) {
            this.tenantId = tenantId;
            this.spaceId = spaceId;
            this.userId = userId;
            this.memberRole = memberRole;
            this.rolePerms = rolePerms;
        }

        public Set<String> resolve(Long nodeId) {
            return resolvePermissionsInternal(tenantId, spaceId, nodeId, userId, rolePerms,
                    true, memberRole, nodes, rules);
        }
    }

    private Set<String> resolvePermissionsInternal(Long tenantId, Long spaceId, Long nodeId,
                                                     Long userId, Set<String> initialPermissions,
                                                     boolean failClosed) {
        return resolvePermissionsInternal(tenantId, spaceId, nodeId, userId, initialPermissions,
                failClosed, null, null, null);
    }

    private Set<String> resolvePermissionsInternal(Long tenantId, Long spaceId, Long nodeId,
                                                     Long userId, Set<String> initialPermissions,
                                                     boolean failClosed, Long memberRole,
                                                     Map<Long, FileNode> nodeCache,
                                                     Map<Long, List<TeamFolderPermission>> ruleCache) {
        Set<String> result = new LinkedHashSet<>(normalizePermissions(initialPermissions));
        if (nodeId == null) {
            return result;
        }

        Long currentId = nodeId;
        Set<Long> visited = new LinkedHashSet<>();
        boolean reachedRoot = currentId == 0;
        if (currentId < 0) {
            return failClosed ? Set.of() : result;
        }
        for (int i = 0; i < MAX_PARENT_DEPTH && currentId != null && currentId > 0; i++) {
            // 脏数据循环必须拒绝继续计算，避免权限链被无限遍历。
            if (!visited.add(currentId)) {
                return failClosed ? Set.of() : result;
            }

            // 先确认节点仍属于同一租户/空间且未逻辑删除，再收集该层规则。
            LambdaQueryWrapper<FileNode> nodeQuery = new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getId, currentId)
                    .eq(FileNode::getDeleted, 0);
            if (tenantId != null) {
                nodeQuery.eq(FileNode::getTenantId, tenantId);
            }
            if (spaceId != null) {
                nodeQuery.eq(FileNode::getSpaceId, spaceId);
            }
            FileNode node;
            if (nodeCache != null && nodeCache.containsKey(currentId)) {
                node = nodeCache.get(currentId);
            } else {
                node = fileNodeMapper.selectOne(nodeQuery);
                if (nodeCache != null) nodeCache.put(currentId, node);
            }
            if (node == null
                    || !node.isNormal()
                    || (tenantId != null && !Objects.equals(node.getTenantId(), tenantId))
                    || (spaceId != null && !Objects.equals(node.getSpaceId(), spaceId))) {
                // 显式主体解析遇到断链时不能保留角色权限，防止已删除节点借缓存/角色泄漏。
                return failClosed ? Set.of() : result;
            }

            LambdaQueryWrapper<TeamFolderPermission> permissionQuery = new LambdaQueryWrapper<TeamFolderPermission>()
                    .eq(TeamFolderPermission::getFolderNodeId, currentId);
            if (tenantId != null) {
                permissionQuery.eq(TeamFolderPermission::getTenantId, tenantId);
            }
            List<TeamFolderPermission> perms;
            if (ruleCache != null && ruleCache.containsKey(currentId)) {
                perms = ruleCache.get(currentId);
            } else {
                perms = teamFolderPermissionMapper.selectList(permissionQuery);
                if (perms == null) perms = List.of();
                if (ruleCache != null) ruleCache.put(currentId, perms);
            }

            // 并集收集：全部匹配规则均生效（只增强）
            for (TeamFolderPermission p : perms) {
                if (!matches(p, userId, memberRole, spaceId, tenantId)) {
                    continue;
                }
                result.addAll(rulePermissionSet(p));
            }

            if (node.getParentId() == null || node.getParentId() == 0) {
                reachedRoot = true;
                break;
            }
            if (node.getParentId() < 0) {
                return failClosed ? Set.of() : result;
            }
            if (i == MAX_PARENT_DEPTH - 1) {
                // 已达到安全上限且仍有父节点，不能把部分规则当作完整授权。
                return failClosed ? Set.of() : result;
            }
            currentId = node.getParentId();
        }
        if (failClosed && !reachedRoot && currentId != null && currentId > 0
                && visited.size() >= MAX_PARENT_DEPTH) {
            return Set.of();
        }
        if (reachedRoot && spaceId != null) {
            // 虚拟根目录的规则仅在完整父链已核实后参与授权；断链、循环和跨空间均不能借根规则放权。
            List<TeamFolderPermission> rootRules;
            if (ruleCache != null && ruleCache.containsKey(0L)) {
                rootRules = ruleCache.get(0L);
            } else {
                LambdaQueryWrapper<TeamFolderPermission> rootQuery = new LambdaQueryWrapper<TeamFolderPermission>()
                        .eq(TeamFolderPermission::getSpaceId, spaceId)
                        .eq(TeamFolderPermission::getFolderNodeId, 0L);
                if (tenantId != null) {
                    rootQuery.eq(TeamFolderPermission::getTenantId, tenantId);
                }
                rootRules = teamFolderPermissionMapper.selectList(rootQuery);
                if (rootRules == null) rootRules = List.of();
                if (ruleCache != null) ruleCache.put(0L, rootRules);
            }
            for (TeamFolderPermission rule : rootRules) {
                if (matches(rule, userId, memberRole, spaceId, tenantId)) {
                    result.addAll(rulePermissionSet(rule));
                }
            }
        }
        return result;
    }

    /**
     * 规则主体是否命中当前用户：
     * all 全体生效；member 匹配用户ID；role 匹配成员角色ID（首次命中时懒加载成员角色）。
     * P1 安全修复：规则必须属于当前空间，跨空间注入的规则一律不参与权限并集。
     */
    private boolean matches(TeamFolderPermission rule, Long userId, Long memberRole, Long spaceId) {
        if (rule == null || rule.getSubjectType() == null) {
            return false;
        }
        if (spaceId == null || !spaceId.equals(rule.getSpaceId())) {
            return false;
        }
        return switch (rule.getSubjectType()) {
            case "all" -> true;
            case "member" -> rule.getSubjectId() != null && rule.getSubjectId().equals(userId);
            case "role" -> {
                Long role = memberRole;
                if (role == null) {
                    role = loadMemberRole(spaceId, userId);
                }
                yield role != null && rule.getSubjectId() != null && rule.getSubjectId().equals(role);
            }
            default -> false;
        };
    }

    private boolean matches(TeamFolderPermission rule, Long userId, Long memberRole, Long spaceId,
                            Long tenantId) {
        if (rule == null || rule.getSubjectType() == null) {
            return false;
        }
        if (tenantId != null && !tenantId.equals(rule.getTenantId())) {
            return false;
        }
        if (spaceId == null || !spaceId.equals(rule.getSpaceId())) {
            return false;
        }
        return switch (rule.getSubjectType()) {
            case "all" -> true;
            case "member" -> rule.getSubjectId() != null && rule.getSubjectId().equals(userId);
            case "role" -> {
                Long role = memberRole;
                if (role == null) {
                    role = loadMemberRole(tenantId, spaceId, userId);
                }
                yield role != null && rule.getSubjectId() != null && rule.getSubjectId().equals(role);
            }
            default -> false;
        };
    }

    /**
     * 懒加载成员角色ID（role 规则匹配用），仅当链上存在 role 规则时触发一次查询。
     */
    private Long loadMemberRole(Long spaceId, Long userId) {
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId));
        return member == null || member.getRole() == null ? null : Long.valueOf(member.getRole());
    }

    private Long loadMemberRole(Long tenantId, Long spaceId, Long userId) {
        LambdaQueryWrapper<TeamMember> query = new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getDeleted, 0);
        if (tenantId != null) {
            query.eq(TeamMember::getTenantId, tenantId);
        }
        TeamMember member = teamMemberMapper.selectOne(query);
        return member == null ? null : Long.valueOf(member.getRole());
    }

    /**
     * 规则权限集：permissions JSON 优先；空则回退旧 permission 单值映射。
     * -1 映射为空集 → 不参与并集（只增强）。
     */
    private Set<String> rulePermissionSet(TeamFolderPermission rule) {
        if (rule.getPermissions() != null && !rule.getPermissions().isBlank()) {
            return parsePermissions(rule.getPermissions());
        }
        return legacyPermissionSet(rule.getPermission());
    }

    /**
     * 兼容旧接口：按旧单值角色语义计算，返回旧权限等级（0-管理 1-编辑 2-查看 -1-无权限）。
     * 内部映射为权限集并集后取最高等级；新调用方请使用 resolvePermissions。
     */
    @Deprecated
    public int resolvePermission(Long spaceId, Long nodeId, Long userId, int spaceRole) {
        return legacyLevelOf(resolvePermissions(spaceId, nodeId, userId, presetPermissions(spaceRole)));
    }

    // ==================== 缓存 ====================

    /**
     * 权限缓存键：spaceId:nodeId:userId:rolePerms（权限集排序拼接）。
     */
    private String cacheKey(Long spaceId, Long nodeId, Long userId, Set<String> rolePerms) {
        List<String> sorted = new ArrayList<>(rolePerms);
        sorted.sort(String::compareTo);
        return spaceId + ":" + nodeId + ":" + userId + ":" + String.join(",", sorted);
    }

    /**
     * 使整个空间的权限缓存失效（TASK-005）：规则/成员/角色任一变化均可能影响空间内权限链，全空间清除最简且正确。
     * 由权限规则写入（setPermissions 内）与成员/角色变更（TeamServiceImpl）调用。
     */
    public void invalidateSpace(Long spaceId) {
        permissionCache.removeByPrefix(spaceId + ":");
    }

    // ==================== 规则管理 ====================

    /**
     * 获取文件夹的权限规则列表
     */
    public List<TeamFolderPermission> listPermissions(Long spaceId, Long folderNodeId) {
        return teamFolderPermissionMapper.selectList(
                new LambdaQueryWrapper<TeamFolderPermission>()
                        .eq(TeamFolderPermission::getSpaceId, spaceId)
                        .eq(TeamFolderPermission::getFolderNodeId, folderNodeId));
    }

    /**
     * 设置文件夹权限（先删后建，全量覆盖）
     */
    public void setPermissions(Long spaceId, Long folderNodeId, List<TeamFolderPermission> rules) {
        // 先删除该文件夹的所有权限规则
        teamFolderPermissionMapper.delete(new LambdaQueryWrapper<TeamFolderPermission>()
                .eq(TeamFolderPermission::getSpaceId, spaceId)
                .eq(TeamFolderPermission::getFolderNodeId, folderNodeId));
        // 批量插入新规则
        for (TeamFolderPermission rule : rules) {
            rule.setSpaceId(spaceId);
            rule.setFolderNodeId(folderNodeId);
            teamFolderPermissionMapper.insert(rule);
        }
        // 权限规则变更：使该空间权限缓存失效，下次访问重新计算
        invalidateSpace(spaceId);
    }
}
