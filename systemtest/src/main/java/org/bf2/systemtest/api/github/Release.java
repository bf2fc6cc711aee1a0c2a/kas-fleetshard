package org.bf2.systemtest.api.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.json.Json;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Release {
    public String id;
    public String name;
    public Boolean draft;
    public Boolean prerelease;

    @Override
    public String toString() {
        return Json.encode(this);
    }
}
