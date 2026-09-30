package com.stcloud.search.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 多JVM游标验收；Spring从真实进程环境加载密钥，候选为相同受控数据集。 */
class SearchProcessIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String KEY="isolated-process-cursor-secret-A-20260928";
    static class Instance implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BlockingQueue<String> output=new LinkedBlockingQueue<>();
        final List<String> lines=new CopyOnWriteArrayList<>();
        Instance(String key,Integer ttl) throws Exception {
            var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString()));
            if(ttl!=null)command.add("-Dstcloud.search.team-cursor-ttl-seconds="+ttl);
            command.addAll(List.of("-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),SearchInstanceWorker.class.getName()));
            var builder=new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().remove("STCLOUD_SEARCH_TEAM_CURSOR_SECRET");
            if(key!=null)builder.environment().put("STCLOUD_SEARCH_TEAM_CURSOR_SECRET",key);
            process=builder.start();input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
            Thread reader=new Thread(()->{try(var stream=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
                for(String line;(line=stream.readLine())!=null;){lines.add(line);output.add(line);}
            }catch(IOException ignored){}finally{output.add("EOF");}},"search-process-output");reader.setDaemon(true);reader.start();
        }
        boolean ready() throws Exception {return await("PROBE READY").equals("PROBE READY");}
        String await(String prefix) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(System.nanoTime()<end){String line=output.poll(1,TimeUnit.SECONDS);if(line==null)continue;if(line.equals("EOF")||line.startsWith(prefix))return line;}
            throw new AssertionError("子进程超时: "+String.join("\n",lines));
        }
        JsonNode search(Map<String,Object> query) throws Exception {
            input.write(JSON.writeValueAsString(query));input.newLine();input.flush();String line=await("PROBE RESULT ");
            assertTrue(line.startsWith("PROBE RESULT "),String.join("\n",lines));return JSON.readTree(line.substring(13));
        }
        public void close() throws Exception {
            if(process.isAlive()){input.write("STOP\n");input.flush();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
            assertFalse(process.isAlive());
            assertTrue(lines.stream().noneMatch(s->s.contains(KEY)||s.contains("isolated-process-cursor-secret-B")),"日志不得泄漏密钥");
        }
    }
    String cursor(JsonNode response){assertEquals(200,response.path("code").asInt(),response.toString());return response.path("page").path("nextCursor").asText();}
    @Test void tc0601And02AlternatingInstancesAndRestartPreserveEveryResult() throws Exception {
        try(var a=new Instance(KEY,null);var b=new Instance(KEY,null)) {
            assertTrue(a.ready());assertTrue(b.ready());var first=a.search(Map.of());var second=b.search(Map.of("cursor",cursor(first)));
            var originalThird=a.search(Map.of("cursor",cursor(second)));
            a.close();try(var restarted=new Instance(KEY,null)) {
                assertTrue(restarted.ready());var third=restarted.search(Map.of("cursor",cursor(second)));
                assertEquals(200,third.path("code").asInt());assertFalse(third.path("page").path("hasMore").asBoolean());
                assertEquals(originalThird.path("page").path("records"),third.path("page").path("records"));
                var ids=new ArrayList<String>();for(var page:List.of(first,second,third))for(var row:page.path("page").path("records"))ids.add(row.path("fileId").asText());
                assertEquals(List.of("100","101","102","103","104","105"),ids);
            }
        }
    }
    @Test void tc0604RealEnvironmentRequiredAndBlankFailsStartup() throws Exception {
        for(String key:Arrays.asList(null,"","   "))try(var app=new Instance(key,null)){assertFalse(app.ready());assertTrue(app.process.waitFor(5,TimeUnit.SECONDS));assertNotEquals(0,app.process.exitValue());}
        try(var app=new Instance(KEY,null)){assertTrue(app.ready());assertEquals(200,app.search(Map.of()).path("code").asInt());}
    }
    @Test void tc0605And06MalformedAndEveryQueryBindingRejectedBeforeEs() throws Exception {
        try(var app=new Instance(KEY,null)) {
            assertTrue(app.ready());String cursor=cursor(app.search(Map.of()));
            for(String invalid:List.of(cursor.substring(0,cursor.length()-4),"*.*",cursor+".extra",cursor.split("\\.")[0]+".AAAA",cursor+"=",(cursor.charAt(0)=='A'?"B":"A")+cursor.substring(1))) {
                var reply=app.search(Map.of("cursor",invalid));assertNotEquals(200,reply.path("code").asInt());assertEquals(0,reply.path("esCalls").asInt());
            }
            var changes=Map.<String,Object>ofEntries(Map.entry("tenant",2),Map.entry("user",6),Map.entry("space",11),Map.entry("folder",99),
                    Map.entry("keyword","changed"),Map.entry("size",3),Map.entry("nodeType",0),Map.entry("suffixes","pdf"),
                    Map.entry("sizeMin",1),Map.entry("sizeMax",999),Map.entry("dateFrom",1),Map.entry("dateTo",1999999999999L));
            for(var change:changes.entrySet()) {
                var reply=app.search(Map.of("cursor",cursor,change.getKey(),change.getValue()));
                assertNotEquals(200,reply.path("code").asInt(),change.getKey());assertEquals(0,reply.path("esCalls").asInt(),change.getKey());
            }
        }
    }
    @Test void tc0607ActualExpiryBoundaryAcrossInstances() throws Exception {
        try(var a=new Instance(KEY,4);var b=new Instance(KEY,4)) {
            assertTrue(a.ready());assertTrue(b.ready());String cursor=cursor(a.search(Map.of()));
            String payload=new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]),java.nio.charset.StandardCharsets.UTF_8);
            long expires=Long.parseLong(payload.split(";")[7]);assertEquals(200,b.search(Map.of("cursor",cursor)).path("code").asInt());
            while(Instant.now().getEpochSecond()<expires)Thread.sleep(40);
            var result=b.search(Map.of("cursor",cursor));assertEquals("SEARCH_CURSOR_EXPIRED",result.path("message").asText());assertEquals(0,result.path("esCalls").asInt());
            assertEquals("SEARCH_CURSOR_EXPIRED",a.search(Map.of("cursor",cursor)).path("message").asText());
            Thread.sleep(1100);assertEquals("SEARCH_CURSOR_EXPIRED",b.search(Map.of("cursor",cursor)).path("message").asText());
        }
    }
    @Test void tc0608And09RotationRejectsOldAllowsNewAndNoSecretInResponsesOrLogs() throws Exception {
        String old;try(var app=new Instance(KEY,null)){assertTrue(app.ready());old=cursor(app.search(Map.of()));}
        try(var a=new Instance("isolated-process-cursor-secret-B",null);var b=new Instance("isolated-process-cursor-secret-B",null)) {
            assertTrue(a.ready());assertTrue(b.ready());
            for(var app:List.of(a,b)){var rejected=app.search(Map.of("cursor",old));assertNotEquals(200,rejected.path("code").asInt());assertEquals(0,rejected.path("esCalls").asInt());}
            var fresh=a.search(Map.of());var next=b.search(Map.of("cursor",cursor(fresh)));assertEquals(200,next.path("code").asInt());
            assertFalse((fresh.toString()+next).contains(KEY));assertFalse((fresh.toString()+next).contains("isolated-process-cursor-secret-B"));
        }
    }
}
