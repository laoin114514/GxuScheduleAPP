package com.gxu.jwxt.model;

/** 从预选或正选页面解析出的选课开放状态。 */
public class CourseSelectionStatus {

    public enum Stage { PRESELECTION, REGULAR }
    public enum State { OPEN, CLOSED, UNKNOWN }

    private final Stage stage;
    private final State state;
    private final String controlId;
    private final String message;

    public CourseSelectionStatus(Stage stage, State state, String controlId, String message) {
        this.stage = stage;
        this.state = state;
        this.controlId = controlId;
        this.message = message;
    }

    public Stage getStage() { return stage; }
    public State getState() { return state; }
    public String getControlId() { return controlId; }
    public String getMessage() { return message; }
    public boolean isOpen() { return state == State.OPEN; }
}
