package vibe.e2e_hot;

/** Second compilation unit: proves multi-file modules work. */
final class Fmt {
    private Fmt() {
    }

    static String line(String kind, String version) {
        return "E2E-" + kind + " " + version;
    }
}
