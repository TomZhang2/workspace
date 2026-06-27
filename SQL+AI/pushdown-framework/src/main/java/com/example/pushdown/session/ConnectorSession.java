package com.example.pushdown.session;

public record ConnectorSession(
    String user,
    String queryId,
    String serverId
) {
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String user;
        private String queryId;
        private String serverId;

        public Builder user(String user) { this.user = user; return this; }
        public Builder queryId(String queryId) { this.queryId = queryId; return this; }
        public Builder serverId(String serverId) { this.serverId = serverId; return this; }

        public ConnectorSession build() {
            return new ConnectorSession(user, queryId, serverId);
        }
    }
}
