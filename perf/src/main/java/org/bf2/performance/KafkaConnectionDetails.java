package org.bf2.performance;

public class KafkaConnectionDetails {

    private String bootstrapURL;
    private String username;
    private String password;

    public String getBootstrapURL() {
        return bootstrapURL;
    }

    public void setBootstrapURL(String bootstrapURL) {
        this.bootstrapURL = bootstrapURL;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    public String toString() {
        return String.join("\n",
                           "bootstrap.servers=" + bootstrapURL,
                           "sasl.mechanism=PLAIN",
                           "security.protocol=SASL_SSL",
                           "sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + username + "\" password=\"" + password + "\";"
            );
    }
}
