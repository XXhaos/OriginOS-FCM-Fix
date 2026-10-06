package io.github.originosfcmguardian.privileged;

/** Child process used to exercise direct-argv command handling without platform shell utilities. */
public final class RemoteCommandRunnerFixture {
    private RemoteCommandRunnerFixture() {}

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected one fixture action");
        switch (args[0]) {
            case "output" -> System.out.print("abcdef");
            case "silent" -> { }
            default -> throw new IllegalArgumentException("Unknown fixture action");
        }
    }
}
