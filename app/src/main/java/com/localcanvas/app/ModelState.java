package com.localcanvas.app;

public final class ModelState {
    public enum Status { NOT_INSTALLED, DOWNLOADING, INSTALLING, INSTALLED, LOADING, RUNNING, PAUSED, ERROR }
    public final String id, name, family;
    public final long bytes;
    public Status status;

    public ModelState(String i, String n, String f, long b) {
        id = i;
        name = n;
        family = f;
        bytes = b;
        status = Status.NOT_INSTALLED;
    }
}
