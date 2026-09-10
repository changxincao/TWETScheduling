/** 调度切换集成验收用的短任务，不调用优化器。 */
public final class NgV6SwitchProbe {
    public static void main(String[] args) throws Exception {
        System.out.println(java.util.Base64.getEncoder().encodeToString(
                System.getProperty("java.class.path").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Thread.sleep(Long.parseLong(args[0]));
        if(args.length>1 && args[1].equals("fail-once") &&
                !java.nio.file.Files.exists(java.nio.file.Path.of("failed-once"))) {
            java.nio.file.Files.writeString(java.nio.file.Path.of("failed-once"),"1");
            System.exit(7);
        }
    }
}
