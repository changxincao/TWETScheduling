import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 根据已完成的移动清单同步n80目录说明与索引，保留原说明备份。 */
public class UpdateN80ResultLocationDocs {
    public static void main(String[] args) throws Exception {
        Path work=Path.of(args[0]).toRealPath();
        Path batch=work.resolve("experiments/20260928-balanced-n80-ti-5h");
        Path central=work.resolve("results/n80-completed").toRealPath();
        Path classified=batch.resolve("completed-results");
        List<String> moves=Files.readAllLines(central.resolve("move-index.tsv"));
        Map<Path,Path> locations=new HashMap<>();
        for(String line:moves.subList(1,moves.size())) {
            String[] f=line.split("\t",-1);
            Path original=Path.of(f[0]).normalize(), current=Path.of(f[1]).toRealPath();
            if(!current.startsWith(central) || !Files.isSameFile(original,current) || !Files.exists(current.resolve("SUCCESS")))
                throw new IllegalStateException("Invalid mapping: "+line);
            if(locations.put(original,current)!=null) throw new IllegalStateException("Duplicate mapping");
        }
        List<String> index=Files.readAllLines(classified.resolve("index.tsv"));
        List<String> headers=Arrays.asList(index.get(0).split("\t",-1));
        int source=headers.indexOf("source");
        if(locations.size()!=556 || index.size()!=534 || source<0 || headers.contains("currentPath"))
            throw new IllegalStateException("Unexpected current index");
        List<String> updated=new ArrayList<>();
        updated.add(index.get(0)+"\tcurrentPath");
        for(String line:index.subList(1,index.size())) {
            Path current=locations.get(Path.of(line.split("\t",-1)[source]).normalize());
            if(current==null) throw new IllegalStateException("Missing current path");
            updated.add(line+"\t"+current);
        }
        String classifiedText="# n80完成结果分类入口\n\n2026-10-14更新：结果目录本体已集中到`"+central+"`。"+
            "本目录的TI/TI-SRI、family/random子目录是分类入口，经过旧路径的兼容junction访问集中后的同一份结果。\n\n"+
            "本目录只对应20260928批次533项正常结束结果：TI 267项（38最优、229限时），TI+SRI 266项（70最优、196限时）。"+
            "index.tsv的source保留兼容旧路径，currentPath为当前实体路径，其他统计列未改变。\n\n"+
            "集中目录总计556份，另外23份来自此前五个批次。其中7项为本批reused-results.tsv规定的历史最优复用；"+
            "其余16份为额外历史记录。正式完整数据格为533+7=540项，不把全部556份混算，也不另复制复用结果。\n\n"+
            "外层状态已修正为FINISHED；原FAILED状态与solve.err.log保留。收尾错误来自运行期间run.cmd变长9字节造成CMD返回位置错位。\n";
        String centralText="# 第一台n80结果集中目录\n\n2026-10-14更新：本目录存放556份结果目录本体，按原批次/runId分开。"+
            "20260928批次533份，此前五批23份：20260903-cluster-ng-large-6h 5份、20260903-cluster-ng-large-followup-6h 3份、"+
            "20260904-arc-ng-large-random-4h 3份、20260904-ti-tisri-large-random-4h 6份、20260904-time-scale-large-random-4h 6份。\n\n"+
            "move-index.tsv有556条映射（加表头557行），记录旧路径、当前实体路径与core summary SHA-256。旧路径保留兼容junction。"+
            "本批533项的算法/数据分类入口仍在`"+classified+"`，其index.tsv已增加currentPath。\n\n"+
            "533项新批次加reused-results.tsv中的7项历史最优复用，对应540项正式数据格；23份历史记录中其余16份不属于本批正式格。"+
            "不同批次的数据、算法版本、预算和重复记录各自保留，不能把556份作为同一正式比较。SUCCESS包含限时结束，不等同于最优。\n";
        Path batchReadme=batch.resolve("README.md");
        String original=Files.readString(batchReadme).replaceFirst("^\uFEFF", "");
        String batchText="2026-10-14当前状态：FINISHED。本批533项全部正常结束（108最优、425限时）。实体集中在`"+central+
            "`的本批子目录，completed-results仍提供533项分类入口，其index.tsv包含旧路径source与实体路径currentPath。"+
            "此前五批23份也在集中目录，其中7份为本批历史复用。其他16份历史记录不计入本批540项正式格。"+
            "原FAILED状态和错误日志保留，收尾原因已确认。\n\n以下为部署时的配置说明，结果位置以上述当前说明为准。\n\n"+original;
        Map<Path,String> docs=new LinkedHashMap<>();
        docs.put(classified.resolve("README.md"),classifiedText);
        docs.put(central.resolve("README.md"),centralText);
        docs.put(batchReadme,batchText);
        docs.put(classified.resolve("index.tsv"),String.join("\n",updated)+"\n");
        for(Path file:docs.keySet()) {
            if(!file.toRealPath().startsWith(work)) throw new IllegalStateException("Outside work1");
            Files.copy(file,file.resolveSibling(file.getFileName()+".before-location-update-20261014"));
        }
        for(var entry:docs.entrySet()) Files.writeString(entry.getKey(),entry.getValue(),StandardCharsets.UTF_8);
        System.out.println("Updated 3 README files and index.tsv; mappings=556 classified=533; backups preserved");
    }
}
