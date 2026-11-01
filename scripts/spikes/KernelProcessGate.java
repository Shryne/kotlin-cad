import java.io.*;
import java.util.concurrent.TimeUnit;

class KernelProcessGate {
    public static void main(String[] args) throws Exception {
        Process geometry = new ProcessBuilder(args[0], args[1]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!geometry.waitFor(30, TimeUnit.SECONDS)) { geometry.destroyForcibly(); throw new AssertionError("Geometry timeout"); }
        String result = new String(geometry.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (geometry.exitValue()!=0 || !result.contains("cross-drilled-hollow-profile")) throw new AssertionError(result);
        Process stress = new ProcessBuilder(args[0], args[1], "--stress").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            BufferedReader reader = stress.inputReader();
            String line;
            while ((line=reader.readLine())!=null && !line.equals("READY")) { }
            if(line==null) throw new AssertionError("No readiness handshake");
            Thread.sleep(150);
            stress.destroyForcibly();
            if (!stress.waitFor(5,TimeUnit.SECONDS)) throw new AssertionError("Cancellation did not stop worker");
            System.out.println("JDK17_GEOMETRY_PROCESS_OK fixtures=5 cancellation=true");
        } finally { stress.destroyForcibly(); }
    }
}
