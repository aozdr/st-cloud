package com.stcloud.team;

import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=TeamTestApplication.class,properties="spring.datasource.url=jdbc:h2:mem:team-cross-process;MODE=MySQL;DB_CLOSE_DELAY=-1")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TeamPermissionProcessIntegrationTest extends AbstractTeamIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Test void tc0210AnotherJvmSeesCommittedRoleAndMembershipWithoutCacheInvalidation() throws Exception {
        setUpUser(100L,1L);insertUser(100L,1L,"process-owner");insertUser(200L,1L,"process-member");
        var create=new CreateSpaceRequest();create.setSpaceName("process-space");create.setStorageQuota(100000L);
        long space=teamService.createSpace(create).getData().getId();
        var role=new TeamRole();role.setId(9007199254740993L);role.setSpaceId(space);role.setName("process-role");role.setPermissions("{\"view\":true}");role.setStatus(1);teamRoleMapper.insert(role);
        var empty=new TeamRole();empty.setId(9007199254740995L);empty.setSpaceId(space);empty.setName("empty");empty.setPermissions("{}");empty.setStatus(1);teamRoleMapper.insert(empty);
        var invite=new InviteMemberRequest();invite.setUserId(200L);invite.setRole(role.getId());long member=teamService.inviteMember(space,invite).getData().getId();
        var node=insertFileNode(1L,100L,space,"doc.txt",1,0);node.setFileMd5("process-md5");fileNodeMapper.updateById(node);
        var server=org.h2.tools.Server.createTcpServer("-tcpPort","0","-tcpDaemon").start();
        Process child=null;var queue=new LinkedBlockingQueue<String>();var logs=new StringBuffer();var reader=Executors.newSingleThreadExecutor();
        try {
            child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),TeamPermissionInstanceWorker.class.getName(),
                "jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:team-cross-process",Long.toString(space),node.getId().toString()).redirectErrorStream(true).start();
            Process running=child;reader.submit(()->{try(var stream=running.inputReader()) {for(String line;(line=stream.readLine())!=null;){logs.append(line).append('\n');if(line.startsWith("PROBE "))queue.add(line);}}catch(java.io.IOException ignored){}finally{queue.add("EOF");}});
            assertEquals("PROBE READY",queue.poll(40,TimeUnit.SECONDS),logs.toString());var input=child.outputWriter();
            for(int i=0;i<2;i++){input.write("CHECK\n");input.flush();assertEquals("PROBE RESULT true 1",queue.poll(10,TimeUnit.SECONDS),logs.toString());}
            // 独立连接自动提交，刻意不发缓存清理；实例A必须在下次授权/输出前读取主库。
            jdbc.update("UPDATE team_role SET permissions='{}' WHERE id=?",role.getId());
            input.write("CHECK\n");input.flush();assertEquals("PROBE RESULT false 0",queue.poll(10,TimeUnit.SECONDS),logs.toString());
            jdbc.update("UPDATE team_role SET permissions=? WHERE id=?","{\"view\":true}",role.getId());
            input.write("CHECK\n");input.flush();assertEquals("PROBE RESULT true 1",queue.poll(10,TimeUnit.SECONDS),logs.toString());
            jdbc.update("UPDATE team_member SET role=? WHERE id=?",empty.getId(),member);
            input.write("CHECK\n");input.flush();assertEquals("PROBE RESULT false 0",queue.poll(10,TimeUnit.SECONDS),logs.toString());
            input.write("STOP\n");input.flush();assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        } finally {
            if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}reader.shutdownNow();reader.awaitTermination(5,TimeUnit.SECONDS);server.stop();
        }
    }
}
