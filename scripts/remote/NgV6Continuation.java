import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 复用已完成正式/mean结果，等待在途重试结束后启动六并发混合版本接续。 */
public final class NgV6Continuation {
    public static void main(String[] args) throws Exception {
        Path control = Path.of(args[0]).toAbsolutePath().normalize();
        Properties p = new Properties();
        try(var r=Files.newBufferedReader(control.resolve("continuation.properties"))){p.load(r);}
        boolean plan = args.length > 1 && args[1].equals("plan");
        if (!plan) {
            Files.writeString(control.resolve("worker.lock"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
            long waitPid=Long.parseLong(p.getProperty("waitPid","0"));
            while(waitPid>0 && ProcessHandle.of(waitPid).map(ProcessHandle::isAlive).orElse(false)) {
                Files.writeString(control.resolve("state.txt"),"WAITING pid="+waitPid+" at="+Instant.now());
                Thread.sleep(10000);
            }
        }
        Path original=Path.of(p.getProperty("formal"));
        Path ab=Path.of(p.getProperty("ab"));
        List<String> lines=Files.readAllLines(original.resolve("solve.tsv"));
        String[] headers=lines.get(0).split("\t",-1);
        Map<String,Integer> col=new HashMap<>();
        for(int i=0;i<headers.length;i++)col.put(headers[i],i);
        List<String> pending=new ArrayList<>(), index=new ArrayList<>();
        pending.add(lines.get(0));
        index.add("runId\tsource\toutputDir");
        int old=0, mean=0, ng=0, ti=0;
        for(String line:lines.subList(1,lines.size())) {
            if(line.isBlank())continue;
            String[] v=line.split("\t",-1);
            String id=v[col.get("runId")];
            boolean isNg=v[col.get("algorithm")].equals("NG_DSSR");
            Path output=Path.of(v[col.get("outputDir")]);
            Path reuse=null;
            if(isNg) {
                Path retry=ab.resolve("retries").resolve(id+"-mean-retry1");
                Path main=ab.resolve("runs").resolve(id+"-mean");
                if(Files.exists(retry.resolve("SUCCESS")))reuse=retry;
                else if(Files.exists(main.resolve("SUCCESS")))reuse=main;
            }
            if(reuse!=null){index.add(id+"\tmean-experiment\t"+reuse);mean++;continue;}
            if(Files.exists(output.resolve("SUCCESS"))){index.add(id+"\toriginal\t"+output);old++;continue;}
            if(isNg) {
                String next=control.resolve("runs").resolve(id).toString().replace('\\','/');
                String oldText=v[col.get("outputDir")];
                String command=v[col.get("args")];
                if(!command.contains(oldText))throw new IllegalStateException("Output mismatch: "+id);
                v[col.get("args")]=command.replace(oldText,next);
                v[col.get("outputDir")]=next;
                ng++;
            } else {ti++;}
            pending.add(String.join("\t",v));
            index.add(id+"\t"+(isNg?"v6-pending":"ti-original-pending")+"\t"+v[col.get("outputDir")]);
        }
        String summary="reusedOriginal="+old+" reusedMean="+mean+" pendingNG="+ng+" pendingTI="+ti;
        Files.write(control.resolve(plan?"plan.tsv":"remaining.tsv"),pending,StandardCharsets.UTF_8);
        Files.write(control.resolve(plan?"plan-index.tsv":"result-index.tsv"),index,StandardCharsets.UTF_8);
        Files.writeString(control.resolve(plan?"plan.txt":"state.txt"),summary);
        System.out.println(summary);
        if(plan)return;
        System.setProperty("twet.scheduler.ngV6Classpath",p.getProperty("ngClasspath"));
        try {
            HEU.ExperimentBatchScheduler.main(new String[]{control.resolve("remaining.tsv").toString(),"6"});
            Files.writeString(control.resolve("state.txt"),"FINISHED "+Instant.now());
        } catch(Throwable e){Files.writeString(control.resolve("state.txt"),"FAILED "+e);throw e;}
    }
}
