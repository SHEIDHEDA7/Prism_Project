package dev.prism;

/**
 * Local provider used for demos and tests; it never calls an external AI service.
 */
final class MockProviderAdapter implements ProviderAdapter {
    private final String name;
    private volatile boolean down;

    MockProviderAdapter(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    void setDown(boolean value) {
        down = value;
    }

    public String generate(String prompt) {
        if (down) throw new IllegalStateException("simulated transient provider failure");
        if (name.equals("mock-fast"))
            return "Quick answer: " + (prompt.length() > 100 ? prompt.substring(0, 100) : prompt);
        return "Thoughtful answer: I considered the request carefully. " + (prompt.length() > 180 ? prompt.substring(0, 180) : prompt);
    }
}
