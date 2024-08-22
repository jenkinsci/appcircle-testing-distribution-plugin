package io.jenkins.plugins.appcircle.testing.distribution;

public class AppVersions {
    private final String id;
    private final String name;

    public AppVersions(String id, String name) {
        this.id = id;
        this.name = name;
    }

    // Getters for id and name
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "AppVersions{id='" + id + "', name='" + name + "'}";
    }
}
