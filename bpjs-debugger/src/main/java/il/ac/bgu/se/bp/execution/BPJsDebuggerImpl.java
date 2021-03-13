package il.ac.bgu.se.bp.execution;

import il.ac.bgu.cs.bp.bpjs.internal.ExecutorServiceMaker;
import il.ac.bgu.cs.bp.bpjs.model.*;
import il.ac.bgu.cs.bp.bpjs.model.eventselection.EventSelectionResult;
import il.ac.bgu.cs.bp.bpjs.model.eventselection.EventSelectionStrategy;
import il.ac.bgu.se.bp.debugger.BPJsDebugger;
import il.ac.bgu.se.bp.debugger.commands.*;
import il.ac.bgu.se.bp.debugger.engine.DebuggerEngine;
import il.ac.bgu.se.bp.debugger.engine.DebuggerEngineImpl;
import il.ac.bgu.se.bp.debugger.engine.SyncSnapshotHolder;
import il.ac.bgu.se.bp.debugger.engine.SyncSnapshotHolderImpl;
import il.ac.bgu.se.bp.debugger.state.BPDebuggerState;
import il.ac.bgu.se.bp.debugger.state.EventInfo;
import il.ac.bgu.se.bp.error.ErrorCode;
import il.ac.bgu.se.bp.logger.Logger;
import il.ac.bgu.se.bp.rest.response.BooleanResponse;
import il.ac.bgu.se.bp.rest.response.GetSyncSnapshotsResponse;
import il.ac.bgu.se.bp.utils.DebuggerStateHelper;
import il.ac.bgu.se.bp.utils.Pair;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static il.ac.bgu.se.bp.utils.ResponseHelper.createErrorResponse;
import static il.ac.bgu.se.bp.utils.ResponseHelper.createSuccessResponse;
import static java.util.Collections.reverseOrder;

/**
 * Runs a {@link BProgram} in debug mode.
 */
public class BPJsDebuggerImpl implements BPJsDebugger<BooleanResponse> {
    private final static AtomicInteger debuggerId = new AtomicInteger(0);
    private final Logger logger = new Logger(BPJsDebuggerImpl.class);
    private final BProgram bProg;
    private DebuggerEngine<BProgramSyncSnapshot> debuggerEngine;
    private final ExecutorService execSvc = ExecutorServiceMaker.makeWithName("BPJsDebuggerRunner-" + debuggerId.incrementAndGet());
    private BProgramSyncSnapshot syncSnapshot = null;
    private volatile boolean isBProgSetup = false; //indicated if bprog setup
    private volatile boolean isSetup = false;
    private volatile boolean isStarted = false;
    private volatile boolean isSkipSyncPoints = false;
    private RunnerState state = new RunnerState();
    private List<Thread> runningThreads;
    private final Callable onExitInterrupt;
    private final SyncSnapshotHolder<BProgramSyncSnapshot, BEvent> syncSnapshotHolder;
    private DebuggerStateHelper debuggerStateHelper = new DebuggerStateHelper();

    public BPJsDebuggerImpl(String filename, Callable onExitInterrupt, Function<BPDebuggerState, Void> onStateChangedEvent) {
        this.onExitInterrupt = onExitInterrupt;
        runningThreads = new LinkedList<>();
        debuggerEngine = new DebuggerEngineImpl(filename, state, onStateChangedEvent, debuggerStateHelper);
        bProg = new ResourceBProgram(filename);
        syncSnapshotHolder = new SyncSnapshotHolderImpl();
    }

    @Override
    public BooleanResponse setup(Map<Integer, Boolean> breakpoints, boolean isSkipBreakpoints, boolean isSkipSyncPoints) {
        if (!isBProgSetup) { // may get twice to setup - must do bprog setup first time only
            syncSnapshot = bProg.setup();
            isBProgSetup = true;
            if (syncSnapshot.getFailedAssertion() != null) {
                return createErrorResponse(ErrorCode.BP_SETUP_FAIL);// todo: add failed assertion message
            }
        }
        setIsSkipSyncPoints(isSkipSyncPoints);
        try {
            debuggerEngine.setupBreakpoints(breakpoints);
            debuggerEngine.toggleMuteBreakpoints(isSkipBreakpoints);
        }
        catch (IllegalArgumentException e){
            logger.error("cant set breakpoint line {0} to true ", e.getMessage());
            return createErrorResponse(ErrorCode.BREAKPOINT_NOT_ALLOWED);
        }
        debuggerEngine.setSyncSnapshot(syncSnapshot);
        setIsSetup(true);
        state.setDebuggerState(RunnerState.State.STOPPED);
//        this.bProg.setWaitForExternalEvents(true);        //todo: add wait for external event toggle
        return createSuccessResponse();
    }

    @Override
    public synchronized BooleanResponse setIsSkipSyncPoints(boolean isSkipSyncPoints) {
        this.isSkipSyncPoints = isSkipSyncPoints;
        return createSuccessResponse();
    }

    @Override
    public GetSyncSnapshotsResponse getSyncSnapshotsHistory() {
        SortedMap<Long, BPDebuggerState> syncSnapshotsHistory = new TreeMap<>();

        syncSnapshotHolder.getAllSyncSnapshots().forEach((time, bProgramSyncSnapshotBEventPair) -> {
            BPDebuggerState bpDebuggerState = debuggerStateHelper.generateDebuggerState(bProgramSyncSnapshotBEventPair.getLeft(), state, null);
            BEvent chosenEvent = bProgramSyncSnapshotBEventPair.getRight();
            bpDebuggerState.setChosenEvent(new EventInfo(chosenEvent == null ? null : chosenEvent.getName()));
            syncSnapshotsHistory.put(time, bpDebuggerState);
        });

        return new GetSyncSnapshotsResponse(syncSnapshotsHistory);
    }

    @Override
    public BooleanResponse setSyncSnapshots(long snapShotTime) {
        BProgramSyncSnapshot newSnapshot = syncSnapshotHolder.popKey(snapShotTime);
        if (newSnapshot == null) {
            return createErrorResponse(ErrorCode.CANNOT_REPLACE_SNAPSHOT);
        }

        syncSnapshot = newSnapshot;
        nextSync();
        return createSuccessResponse();
    }

    private synchronized void setItStarted(boolean isStarted) {
        this.isStarted = isStarted;
    }

    private synchronized void setIsSetup(boolean isSetup) {
        this.isSetup = isSetup;
    }

    @Override
    public synchronized boolean isSetup() {
        return isSetup;
    }

    @Override
    public synchronized boolean isStarted() {
        return isStarted;
    }

    private void updateRecentlyRegisteredBT(){
        Set<BThreadSyncSnapshot> recentlyRegisteredBthreads = bProg.getRecentlyRegisteredBthreads();
        Set<Pair<String, Object>> recentlyRegistered = new HashSet<>();
        for(BThreadSyncSnapshot b: recentlyRegisteredBthreads){
            recentlyRegistered.add(new Pair<>(b.getName(), b.getEntryPoint()));
        }
        debuggerStateHelper.setRecentlyRegisteredBthreads(recentlyRegistered);
    }

    @Override
    public BooleanResponse startSync(boolean isSkipBreakpoints, boolean isSkipSyncPoints) {
        if (!isSetup()) {
            setup(new HashMap<>(), isSkipSyncPoints, isSkipBreakpoints);
        }
        setItStarted(true);
        Thread startSyncThread = new Thread(() -> {
            try {
                updateRecentlyRegisteredBT();

                syncSnapshot = syncSnapshot.start(execSvc);
                state.setDebuggerState(RunnerState.State.SYNC_STATE);
                debuggerEngine.setSyncSnapshot(syncSnapshot);
                syncSnapshotHolder.addSyncSnapshot(syncSnapshot, null);
                logger.debug("Generate state from startSync");
                debuggerEngine.onStateChanged();
                logger.info("~FIRST SYNC STATE~");
                state.setDebuggerState(RunnerState.State.SYNC_STATE);
                if (isSkipSyncPoints) {
                    nextSync();
                }
            } catch (InterruptedException e) {
                logger.warning("got InterruptedException in startSync");
            }
            catch (RejectedExecutionException e){
                logger.error("Forced to stop");
            }
        });
        startSyncThread.setName("startSyncThread");
        runningThreads.add(startSyncThread);
        startSyncThread.start();
        return createSuccessResponse();
    }

    @Override
    public BooleanResponse nextSync() {
        if (this.state.getDebuggerState() == RunnerState.State.WAITING_FOR_EXTERNAL_EVENT)
            return createErrorResponse(ErrorCode.WAITING_FOR_EXTERNAL_EVENT);
        else if (this.state.getDebuggerState() == RunnerState.State.JS_DEBUG)
            return createErrorResponse(ErrorCode.NOT_IN_BP_SYNC_STATE);
        else if (this.state.getDebuggerState() == RunnerState.State.RUNNING)
            return createErrorResponse(ErrorCode.ALREADY_RUNNING);
        else if (!isStarted())
            return createErrorResponse(ErrorCode.NOT_STARTED);

        Thread nextSyncThread = createNextSyncThread();
        nextSyncThread.setName("nextSyncThread");
        runningThreads.add(nextSyncThread);
        nextSyncThread.start();
        return createSuccessResponse();
    }

    private Thread createNextSyncThread() {
        return new Thread(() -> {
            state.setDebuggerState(RunnerState.State.RUNNING);
            EventSelectionStrategy eventSelectionStrategy = this.bProg.getEventSelectionStrategy();
            Set<BEvent> possibleEvents = eventSelectionStrategy.selectableEvents(this.syncSnapshot);
            if (possibleEvents.isEmpty()) {
                possibleEvents = nextSyncOnNoPossibleEvents(eventSelectionStrategy, possibleEvents);
            }

            logger.info("Possible events(internal): " + possibleEvents);
            logger.info("External events: " + syncSnapshot.getExternalEvents());

            try {
                Optional<EventSelectionResult> eventOptional = eventSelectionStrategy.select(syncSnapshot, possibleEvents);
                if (eventOptional.isPresent()) {
                    nextSyncOnChosenEvent(eventOptional.get());
                }
                else {
                    logger.info("Events queue is empty");
                }
            } catch (InterruptedException e) {
                logger.warning("got InterruptedException in nextSync");
            }
        });
    }

    private void nextSyncOnChosenEvent(EventSelectionResult eventSelectionResult) throws InterruptedException {
        BEvent event = eventSelectionResult.getEvent();
        if (!eventSelectionResult.getIndicesToRemove().isEmpty()) {
            removeExternalEvents(eventSelectionResult);
        }
        logger.info("Triggering event " + event);
        updateRecentlyRegisteredBT();
        syncSnapshot = syncSnapshot.triggerEvent(event, execSvc, new ArrayList<>());
        state.setDebuggerState(RunnerState.State.SYNC_STATE);
        debuggerEngine.setSyncSnapshot(syncSnapshot);
        debuggerStateHelper.setLastChosenEvent(event);
        logger.debug("Generate state from nextSync");
        debuggerEngine.onStateChanged();
        syncSnapshotHolder.addSyncSnapshot(syncSnapshot, event);
        logger.info("~NEW SYNC STATE~");
        if (isSkipSyncPoints)
            nextSync();
    }

    private Set<BEvent> nextSyncOnNoPossibleEvents(EventSelectionStrategy eventSelectionStrategy, Set<BEvent> possibleEvents) {
        if (bProg.isWaitForExternalEvents()) {
            try {
                state.setDebuggerState(RunnerState.State.WAITING_FOR_EXTERNAL_EVENT);
                BEvent next = bProg.takeExternalEvent(); // and now we wait for external event
                state.setDebuggerState(RunnerState.State.RUNNING);
                if (next == null) {
                    return possibleEvents;
                }
                else {
                    syncSnapshot.getExternalEvents().add(next);
                    return eventSelectionStrategy.selectableEvents(syncSnapshot);
                }
            } catch (Exception e) {
                return possibleEvents;
            }
        }
        else {
            logger.info("Event queue empty, not need to wait to external event. terminating....");
            onExit();
            return possibleEvents;
        }
    }

    private void onExit() {
        try {
            onExitInterrupt.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeExternalEvents(EventSelectionResult esr) {
        // the event selection affected the external event queue.
        List<BEvent> updatedExternals = new ArrayList<>(this.syncSnapshot.getExternalEvents());
        esr.getIndicesToRemove().stream().sorted(reverseOrder())
                .forEach(idxObj -> updatedExternals.remove(idxObj.intValue()));
        this.syncSnapshot = this.syncSnapshot.copyWith(updatedExternals);
    }

    @Override
    public BooleanResponse continueRun() {
        if (!isSetup())
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        if (this.state.getDebuggerState() != RunnerState.State.JS_DEBUG) {
            return createErrorResponse(ErrorCode.NOT_IN_JS_DEBUG_STATE);
        }
        return addCommandIfStarted(new Continue());
    }

    @Override
    public BooleanResponse stepInto() {
        if (!isSetup())
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        if (this.state.getDebuggerState() != RunnerState.State.JS_DEBUG) {
            return createErrorResponse(ErrorCode.NOT_IN_JS_DEBUG_STATE);
        }
        return addCommandIfStarted(new StepInto());
    }

    @Override
    public BooleanResponse stepOver() {
        if (!isSetup())
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        if (this.state.getDebuggerState() != RunnerState.State.JS_DEBUG) {
            return createErrorResponse(ErrorCode.NOT_IN_JS_DEBUG_STATE);
        }
        return addCommandIfStarted(new StepOver());
    }

    @Override
    public BooleanResponse stepOut() {
        if (!isSetup())
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        if (this.state.getDebuggerState() != RunnerState.State.JS_DEBUG) {
            return createErrorResponse(ErrorCode.NOT_IN_JS_DEBUG_STATE);
        }
        return addCommandIfStarted(new StepOut());
    }

    @Override
    public BooleanResponse setBreakpoint(final int lineNumber, final boolean stopOnBreakpoint) {
        if (!isSetup()) {
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        }
        return new SetBreakpoint(lineNumber, stopOnBreakpoint).applyCommand(debuggerEngine);
    }

    @Override
    public BooleanResponse stop() {
        if (!isSetup())
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        //todo: remove?
        while (!execSvc.isTerminated()) {
            runningThreads.forEach(Thread::interrupt);
            execSvc.shutdownNow();
            try {
                execSvc.awaitTermination(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (!execSvc.isTerminated()) {
                logger.info("not yet terminated");
            }
        }
        setItStarted(false);
        return new Stop().applyCommand(debuggerEngine);
    }

    @Override
    public BooleanResponse getState() {
        if (!isSetup())
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        else if (state.getDebuggerState() == RunnerState.State.JS_DEBUG)
            return createErrorResponse(ErrorCode.NOT_IN_BP_SYNC_STATE);
        else if (state.getDebuggerState() == RunnerState.State.RUNNING)
            return createErrorResponse(ErrorCode.ALREADY_RUNNING);
        return new GetState().applyCommand(debuggerEngine);
    }

    @Override
    public BooleanResponse toggleMuteBreakpoints(boolean toggleBreakPointStatus) {
        if (!isSetup())
            return new BooleanResponse(false, ErrorCode.SETUP_REQUIRED);
        return new ToggleMuteBreakpoints(toggleBreakPointStatus).applyCommand(debuggerEngine);
    }

    @Override
    public BooleanResponse addExternalEvent(String externalEvent) {
        if (StringUtils.isEmpty(externalEvent)) {
            return createErrorResponse(ErrorCode.INVALID_EVENT);
        }
        bProg.enqueueExternalEvent(new BEvent(externalEvent));
        return createSuccessResponse();
    }

    @Override
    public BooleanResponse removeExternalEvent(String externalEvent) {
        if (StringUtils.isEmpty(externalEvent)) {
            return createErrorResponse(ErrorCode.INVALID_EVENT);
        }
        List<BEvent> updatedExternals = new ArrayList<>(syncSnapshot.getExternalEvents());
        updatedExternals.removeIf(bEvent -> bEvent.getName().equals(externalEvent));
        syncSnapshot = syncSnapshot.copyWith(updatedExternals);
        return createSuccessResponse();
    }

    @Override
    public BooleanResponse setWaitForExternalEvents(boolean shouldWait) {
        bProg.setWaitForExternalEvents(shouldWait);
        return createSuccessResponse();
    }

    private BooleanResponse addCommandIfStarted(DebuggerCommand debuggerCommand) {
        if (!isSetup()) {
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        }
        try {
            debuggerEngine.addCommand(debuggerCommand);
            return createSuccessResponse();
        } catch (Exception e) {
            logger.error("failed adding command", e);
            e.printStackTrace();
        }
        return createErrorResponse(ErrorCode.FAILED_ADDING_COMMAND);
    }


//    //OLD METHOD TO RUN BPROG - JUST FOR REFERENCE
//    public void start(Map<Integer, Boolean> breakpoints) {
//        if (!isSetup) {
//            setup(breakpoints, false);
//            return;
//        }
//        BProgramRunner rnr = new BProgramRunner();
//        rnr.addListener(new PrintBProgramRunnerListener());
//        rnr.addListener(new BProgramRunnerListenerAdapter() {
//            @Override
//            public void ended(BProgram bp) {
//                setItStarted(false);
//            }
//        });
//        rnr.setBProgram(bProg);
//        setItStarted(true);
//        new Thread(rnr).start();
//    }
}