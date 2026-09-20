package com.relay.model;

public class SseEvent {

    private String type;
    private String message;
    private Object payload;

    public SseEvent() {
    }

    public SseEvent(String type, String message, Object payload) {
        this.type = type;
        this.message = message;
        this.payload = payload;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }
}