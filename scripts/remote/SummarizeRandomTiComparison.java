import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** 按第一台正式清单汇总n60/n80 random的TI对照，只读结果，不扫描额外历史试验。 */
public class SummarizeRandomTiComparison {
    record Row(int n,String set,int machines,String scale,String window,String algorithm,
               boolean optimal,double seconds,double gap,double nodes,boolean reused) {
        String scenario() { return n+"/"+set+"/"+machines+"/"+scale+"/"+window; }
    }
    static final List<Row> rows=new ArrayList<>();
    static Path work;

    public static void main(String[] args) throws Exception {
        work=Path.of(args[0]).toRealPath();
        Path le60=work.resolve("experiments/20260914-balanced-family-ti-le60/unchanged-resume.tsv");
        Map<String,Path> retries=new HashMap<>();
        for(var r:tsv(work.resolve("experiments/20260927-ti-failed-retry/solve.tsv")))
            retries.put(r.get("runId").replaceFirst("-retry-20260927$",""),Path.of(r.get("outputDir")));
        for(var r:tsv(le60)) {
            if(!r.get("size").equals("60") || !r.get("setupType").equals("random")) continue;
            Path output=Path.of(r.get("outputDir"));
            if(!Files.exists(output.resolve("SUCCESS"))) output=retries.get(r.get("runId"));
            add(60,r,output,false);
        }
        Path n80=work.resolve("experiments/20260928-balanced-n80-ti-5h");
        for(var r:tsv(n80.resolve("completed-results/index.tsv"))) {
            if(r.get("setupType").equals("random")) add(80,r,Path.of(r.get("currentPath")),false);
        }
        for(var r:tsv(n80.resolve("reused-results.tsv"))) {
            if(r.get("setupType").equals("random")) add(80,r,Path.of(r.get("sourceOutputDir")),true);
        }
        Set<String> unique=new HashSet<>();
        for(Row r:rows) if(!unique.add(r.scenario()+"/"+r.algorithm)) throw new IllegalStateException("Duplicate result");
        for(int n:List.of(60,80)) for(String algo:List.of("TIME_INDEXED","TIME_INDEXED_SRI"))
            if(rows.stream().filter(r->r.n==n && r.algorithm.equals(algo)).count()!=135)
                throw new IllegalStateException("Expected 135 results per size/method");
        List<String> out=new ArrayList<>();
        out.add("dimension\tgroup\tn\talgorithm\tcount\toptimal\tmeanSeconds\tmeanGapPercent\tmeanNodes\tcommonOptimal\tcommonMeanSeconds\treused");
        for(String dim:List.of("all","scale","window","machines")) for(int n:List.of(60,80)) {
            List<Row> sizeRows=rows.stream().filter(r->r.n==n).toList();
            List<String> groups=switch(dim) {
                case "scale" -> List.of("base","medium","high");
                case "window" -> List.of("zero","narrow","wide");
                case "machines" -> sizeRows.stream().map(r->Integer.toString(r.machines)).distinct().sorted().toList();
                default -> List.of("all");
            };
            for(String group:groups) {
                List<Row> selected=sizeRows.stream().filter(r->group(r,dim).equals(group)).toList();
                Map<String,List<Row>> paired=selected.stream().collect(Collectors.groupingBy(Row::scenario));
                Set<String> common=paired.entrySet().stream().filter(e->e.getValue().size()==2 && e.getValue().stream().allMatch(Row::optimal))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
                for(String algo:List.of("TIME_INDEXED","TIME_INDEXED_SRI")) {
                    List<Row> method=selected.stream().filter(r->r.algorithm.equals(algo)).toList();
                    List<Row> both=method.stream().filter(r->common.contains(r.scenario())).toList();
                    out.add(String.join("\t",dim,group,Integer.toString(n),algo,Integer.toString(method.size()),
                        Long.toString(method.stream().filter(Row::optimal).count()),num(mean(method,0)),num(mean(method,1)),num(mean(method,2)),
                        Integer.toString(common.size()),both.isEmpty()?"NA":num(mean(both,0)),Long.toString(method.stream().filter(Row::reused).count())));
                }
            }
        }
        String result=String.join("\n",out)+"\n";
        System.out.print(result);
        Path destination=work.resolve("results/statistics/20261014-random-ti-n60-n80");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("summary.tsv"),result,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.writeString(destination.resolve("README.md"),
            "# n60/n80 random TI对照\n\nn60：m=2/3/4，每项10800秒；n80：m=3/4/5，每项18000秒。第一台普通Arc分支，"+
            "每规模每算法135项。n60按原清单、失败项用唯一成功重跑；n80用533项索引中的random加7项规定历史复用，"+
            "不纳入其他旧试验。reused列标明历史复用数。\n\n均值含限时结果；gap含最优的0。commonOptimal为同一规模、"+
            "同一分组内两算法共同最优数，commonMeanSeconds仅在该共同集上计算。规模之间输入与机器档、预算不同，"+
            "不能将均值之比解释成仅由n变化造成的倍率。只读取既有结果，不重跑或导出全量结果副本。\n",
            StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }

    static void add(int n,Map<String,String> meta,Path output,boolean reused) throws Exception {
        if(output==null || !Files.exists(output.resolve("SUCCESS"))) throw new IllegalStateException("Missing completed result: "+output);
        output=output.toRealPath();
        if(!output.startsWith(work)) throw new IllegalStateException("Outside work1");
        List<Path> cores;
        try(var files=Files.walk(output,2)) { cores=files.filter(p->p.toString().endsWith(".core-summary.csv")).toList(); }
        if(cores.size()!=1) throw new IllegalStateException("Ambiguous summary: "+output);
        List<String> text=Files.readAllLines(cores.get(0));
        List<String> keys=csv(text.get(0)),values=csv(text.get(1));
        Map<String,String> core=new HashMap<>();
        for(int i=0;i<keys.size();i++) core.put(keys.get(i),values.get(i));
        if("false".equals(core.get("validationFeasible")) || "false".equals(core.get("validationObjectiveConsistent")))
            throw new IllegalStateException("Invalid result: "+output);
        double gap=Double.parseDouble(core.get("gapPercent"));
        if(!Double.isFinite(gap)) throw new IllegalStateException("No valid gap: "+output);
        boolean optimal=Set.of("FINISHED","ROOT_PROCESSED").contains(core.get("status")) && Math.abs(gap)<=1e-6;
        rows.add(new Row(n,meta.get("taskSetId"),Integer.parseInt(meta.get("machines")),meta.get("scaleLevel"),meta.get("windowLevel"),
            meta.get("algorithm"),optimal,Double.parseDouble(core.get("solveTimeSeconds")),gap,Double.parseDouble(core.get("processedNodes")),reused));
    }
    static String group(Row r,String dim) { return switch(dim) { case "scale"->r.scale;case "window"->r.window;case "machines"->Integer.toString(r.machines);default->"all";}; }
    static double mean(List<Row> data,int metric) { return data.stream().mapToDouble(r->metric==0?r.seconds:metric==1?r.gap:r.nodes).average().orElse(Double.NaN); }
    static String num(double value) { return String.format(Locale.ROOT,"%.6f",value); }
    static List<Map<String,String>> tsv(Path file) throws Exception {
        List<String> lines=Files.readAllLines(file);String[] headers=lines.get(0).replace("\ufeff","").split("\t",-1);
        List<Map<String,String>> out=new ArrayList<>();
        for(String line:lines.subList(1,lines.size())) { if(line.isBlank())continue;String[] v=line.split("\t",-1);Map<String,String> r=new HashMap<>();
            for(int i=0;i<headers.length;i++) r.put(headers[i],v[i]);out.add(r); }
        return out;
    }
    static List<String> csv(String line) {
        List<String> out=new ArrayList<>();StringBuilder s=new StringBuilder();boolean quote=false;
        for(int i=0;i<line.length();i++) { char c=line.charAt(i);if(c=='"') {if(quote&&i+1<line.length()&&line.charAt(i+1)=='"'){s.append('"');i++;}else quote=!quote;}
            else if(c==','&&!quote){out.add(s.toString());s.setLength(0);}else s.append(c); }
        out.add(s.toString());return out;
    }
}
