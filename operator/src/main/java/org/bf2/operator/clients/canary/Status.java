package org.bf2.operator.clients.canary;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Status {

    private Consuming consuming;

    @JsonProperty("Consuming")
    public Consuming getConsuming() {
        return consuming;
    }

    public void setConsuming(Consuming consuming) {
        this.consuming = consuming;
    }

    public static class Consuming {
        private int timeWindow;
        private int percentage;

        @JsonProperty("TimeWindow")
        public int getTimeWindow() {
            return timeWindow;
        }

        public void setTimeWindow(int timeWindow) {
            this.timeWindow = timeWindow;
        }

        @JsonProperty("Percentage")
        public int getPercentage() {
            return percentage;
        }

        public void setPercentage(int percentage) {
            this.percentage = percentage;
        }
    }
}
