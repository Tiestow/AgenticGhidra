package ghidraagent.codex;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;

final class FakeServerSupport {
    private FakeServerSupport() { }

    static List<String> command(String mode, Path pidFile) {
        String classpath = Stream.of(FakeAppServer.class, CodexClient.class, Gson.class)
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    }
                    catch (Exception e) {
                        throw new AssertionError("Cannot locate fixture classpath");
                    }
                }).distinct().collect(Collectors.joining(System.getProperty("path.separator")));
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:-UsePerfData", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", classpath, FakeAppServer.class.getName(), mode, pidFile.toString());
    }
}
