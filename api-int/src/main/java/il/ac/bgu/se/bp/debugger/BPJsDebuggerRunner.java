package il.ac.bgu.se.bp.debugger;

import java.util.Map;

public interface BPJsDebuggerRunner<T> {

    void setup(Map<Integer, Boolean> breakpoints);
    boolean isSetup();
    void start(Map<Integer, Boolean> breakpoints);
    boolean isStarted();

    T setBreakpoint(int lineNumber);
    T removeBreakpoint(int lineNumber);
    T continueRun();
    T stepInto();
    T stepOver();
    T stepOut();
    T getVars();
    T exit();
    void startSync();
    void nextSync();

}
