package com.panfan.hivelineage.core;

final class UseDatabaseResolution {
    private final boolean valid;
    private final String database;
    private final String failureMessage;

    private UseDatabaseResolution(boolean valid, String database, String failureMessage) {
        this.valid = valid;
        this.database = database;
        this.failureMessage = failureMessage;
    }

    static UseDatabaseResolution valid(String database) {
        return new UseDatabaseResolution(true, database, null);
    }

    static UseDatabaseResolution invalid(String failureMessage) {
        return new UseDatabaseResolution(false, null, failureMessage);
    }

    boolean isValid() {
        return valid;
    }

    String getDatabase() {
        return database;
    }

    String getFailureMessage() {
        return failureMessage;
    }
}
