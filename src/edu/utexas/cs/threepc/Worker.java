package edu.utexas.cs.threepc;

import edu.utexas.cs.netutil.*;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.*;

/**
 * Note: maybe use sock.connect to set a timeout.
 */
public class Worker {

    private int  processId;
    private int  totalProcess;
    private int  leader, oldLeader;
    private File DTLog;
    private Map<String, String> playlist;

    private String currentCommand;
    private String currentState;
    private boolean isRecover;

    private int       viewNumber;
    private Boolean[] processAlive;

    private Boolean[] voteStats;
    private int       voteNum;
    private Boolean[] ackStats;
    private int       ackNum;
    private Boolean[] hasRespond;
    private Boolean   rejectNextChange;
    private Boolean[] stateReqAck;
    private String[]  stateReqList;
    private int       stateReqAckNum;

    private String hostName;
    private int    basePort;
    private int    reBuild;
    private NetController netController;
    private int msgCounter;
    private int msgAllowed;
    private boolean countingMsg;
    private boolean canSendMsg;
    private int instanceNum;
    private String lastLine;

    public static final String PREFIX_COMMAND    = "COMMAND:";
    public static final String PREFIX_PROCNUM    = "PROCNUM:";
    public static final String PREFIX_VOTE       = "VOTE:";
    public static final String PREFIX_START      = "START-3PC:";

    /* COORDINATOR */
    public static final String STATE_START       = "START-3PC";
    public static final String STATE_WAITVOTE    = "WAITVOTE";
    public static final String STATE_PRECOMMITED = "PRECOMMITED";
    public static final String STATE_WAITACK     = "WAITACK";
    public static final String STATE_ACKED       = "ACKED";
    /* PARTICIPANT */
    public static final String STATE_VOTEREQ     = "VOTEREQ";
    public static final String STATE_VOTED       = "VOTED";
    public static final String STATE_PRECOMMIT   = "PRECOMMIT";
    public static final String STATE_STREQ       = "STATE_REQ";
    public static final String STATE_SELETC      = "SELECT_COORDINATOR";
    /* BOTH */
    public static final String STATE_WAIT        = "WAIT";
    public static final String STATE_RECOVER     = "RECOVER";
    public static final String STATE_COMMIT      = "COMMIT";
    public static final String STATE_ABORT       = "ABORT";
    public static final String STATE_NOTANS      = "NOTANS";

    private Timer timerCoordinator;
    private Timer timerParticipant;
    public static final int    TIME_OUT        = 2500;
    public static final int    TIME_OUT_P      = 5000;
    private int   decision;

    /**
     *
     * @param processId
     * @param totalProcess  equals to 0 if created as participant or recovered process
     * @param hostName
     * @param basePort
     * @param ld
     * @param rebuild
     */
    public Worker(final int processId, final int totalProcess, String hostName, int basePort, int ld, int rebuild) {
        this.processId = processId;
        this.totalProcess = totalProcess;
        this.hostName = hostName;
        this.basePort = basePort;
        this.countingMsg = false;
        this.msgCounter = 0;
        this.instanceNum = 0;
        this.canSendMsg = true;
        leader = ld;
        oldLeader = -1;
        reBuild = rebuild;

        currentCommand = "";
        rejectNextChange = false;
        currentState = STATE_WAIT;

        if (totalProcess > 0) setTimer();

        playlist = new HashMap<String, String>();
        isRecover = true;
        try {
            DTLog = new File("../log/dt_" + processId + ".log");
            if (!DTLog.exists() || reBuild == 0) {
                DTLog.getParentFile().mkdirs();
                DTLog.delete();
                DTLog.createNewFile();
            } else {
                // Read recovery log and try to reconstruct the state
                terminalLog("starts recovering");
                currentState = STATE_RECOVER;
                BufferedReader br = new BufferedReader(new FileReader(DTLog));
                String line;
                lastLine = "";
                while ((line = br.readLine()) != null) {
                    processRecovery(line);
                }

                // Need to process the last state if it is not commit or abort.
                // Recovery Protocol
                if (!currentState.equals(STATE_ABORT) || !currentState.equals(STATE_COMMIT)) {
                    // p fails before sending yes
                    if (!currentCommand.equals("") && currentCommand.split(" ")[2].equals("vr") && currentState.equals(STATE_VOTEREQ)) {
                        performAbort();
                    } else if (currentState.startsWith(STATE_VOTED) ||
                            currentState.equals(STATE_PRECOMMIT) ||
                            currentState.equals(STATE_START)) {
                        // TODO: ask other process for help
                        isRecover = false;
                        broadcastMsgs("rr "+instanceNum+" "+currentState);
                    }
                }
                br.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        isRecover = false;

        if (rebuild == 0) {
            buildSocket();
            getReceivedMsgs(netController);
        }

        if (totalProcess > 0 && processId == leader && reBuild == 0) {
            logWrite(PREFIX_PROCNUM+totalProcess);
            initializeArrays();
        }

        // Send STATE_REQ
        if (rebuild == 0 && processId != leader) {
            unicastMsgs(leader, "ir");
        }
    }

    private void setTimer() {
        timerCoordinator = new Timer(TIME_OUT, new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                switch (currentState) {
                    // Coordinator waits for VOTE
                    case STATE_WAITVOTE:
                        for (int i = 1; i <= totalProcess; i++)
                            if (i != processId && !hasRespond[i]) {
                                terminalLog(String.format("participant %d times out", i));
                            }
                        performAbort();
                        break;
                    // Coordinator waits for ACK
                    case STATE_WAITACK:
                        for (int i = 1; i <= totalProcess; i++)
                            if (i != processId && !hasRespond[i]) {
                                terminalLog(String.format("participant %d times out", i));
                            }
                        if (!currentState.equals(STATE_COMMIT))
                            logWrite(STATE_ACKED);

                        for (int i = 1; i <= totalProcess; i++)
                            if (i != processId && hasRespond[i])
                                unicastMsgs(i, "c");
                        performCommit();
                        break;
                    // New coordinator waits for STATE_REQ ANS
                    case STATE_STREQ:
                        for (int i = 1; i <= totalProcess; i++)
                            if (i != processId && !hasRespond[i]) {
                                terminalLog(String.format("participant %d times out", i));
                            }
                        terminationProtocol();
                        break;
                    default:
                        break;
                }
            }
        });
        timerCoordinator.setRepeats(false);

        timerParticipant = new Timer(TIME_OUT_P, new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                switch (currentState) {
                    // Participant waits for PRECOMMIT
                    case STATE_VOTED:
                    // Participant waits for COMMIT
                    case STATE_PRECOMMIT:
                        processAlive[leader] = false;
                        electCoordinator();
                        break;
                    // Participant waits for STATE_REQ ACK
                    case STATE_STREQ:
                        for (int i = 1; i < totalProcess; i++)
                            if (i != processId && !hasRespond[i]) {
                                terminalLog(String.format("participant %d times out", i));
                            }
                        if (currentState.equals(STATE_VOTED)) {
                            performAbort();
                            broadcastMsgs("abt");
                        }
                        break;
                    // Wait STATE_REQ from new coordinator
                    case STATE_SELETC:
                        processAlive[viewNumber] = false;
                        electCoordinator();
                        break;
                    default: // TODO: Termination protocol here?
                        break;
                }
            }
        });
        timerParticipant.setRepeats(false);
    }

    private void initializeArrays() {
        processAlive = new Boolean[totalProcess+1];
        Arrays.fill(processAlive, true);
        voteStats = new Boolean[totalProcess+1];
        Arrays.fill(voteStats, false);
        voteNum = 0;
        ackStats = new Boolean[totalProcess+1];
        Arrays.fill(ackStats, false);
        ackNum = 0;
        hasRespond = new Boolean[totalProcess+1];
        Arrays.fill(hasRespond, false);
        stateReqAckNum = 0;
        stateReqAck = new Boolean[totalProcess+1];
        Arrays.fill(stateReqAck, false);
        stateReqList = new String[totalProcess+1];
        Arrays.fill(stateReqList, STATE_NOTANS);
    }

    public void processRecovery(String message) {
        if (!message.startsWith(STATE_RECOVER))
            lastLine = message;

        String[] splits = message.split(":");

        if (message.startsWith(PREFIX_COMMAND)) {
            if (splits[1].equals("rnc"))
                rejectNextChange = true;
            else {
                currentCommand = splits[1];
                if (!currentState.equals(STATE_START))
                    instanceNum = Integer.parseInt(splits[1].split(" ")[1]);
            }
        }
        else if (message.startsWith(PREFIX_PROCNUM)) {
            totalProcess = Integer.parseInt(splits[1]);
            terminalLog("total number of processes is "+totalProcess);
            initializeArrays();
            setTimer();
            buildSocket();
            getReceivedMsgs(netController);
        }
        else if (message.startsWith(PREFIX_VOTE)) {
            if (splits[1].equals("YES")) {
                currentState = STATE_VOTED;
            }
        }
        else if (message.startsWith(PREFIX_START)) {
            leader = processId;
            instanceNum = Integer.parseInt(splits[1]);
            currentState = STATE_START;
        }
        else if (!message.startsWith(STATE_RECOVER)){
            // TODO: I think recovery is not fully implemented.
            switch (message) {
                case STATE_ABORT:
                    currentCommand = "";
                    currentState = STATE_ABORT;
                    break;
                case STATE_VOTEREQ:
                    currentState = STATE_VOTEREQ;
                    break;
                case STATE_COMMIT:
                    performCommit();
                    currentCommand = "";
                    currentState = STATE_COMMIT;
                    break;
                case STATE_PRECOMMITED:
                case STATE_PRECOMMIT:
                case STATE_ACKED:
                    currentState = STATE_PRECOMMIT;
                    break;
                default:
                    terminalLog("unrecognized command in recovery log: " + message);
            }
        }
    }

    public void processMessage(String message) {
        String[] splits = message.split(" ");

        int senderId = Integer.parseInt(splits[0]);
        if (splits.length >= 3 && splits[2].equals("vr")) {
            currentCommand = message;
            logWrite(PREFIX_COMMAND+message);
            logWrite(STATE_VOTEREQ);
            currentState = STATE_VOTED;

            instanceNum = Integer.parseInt(splits[1]);
            if (leader != senderId) {
                oldLeader = leader;
                leader = senderId;
            }
            switch (splits[3]) {
                case "add":
                    voteAddParticipant(splits[4]);
                    break;
                case "e":
                    voteEditParticipant(splits[4], splits[5]);
                    break;
                case "rm":
                    voteRmParticipant(splits[4]);
                    break;
                default:
                    terminalLog("receives wrong command");
            }
        }
        else
        switch (splits[1]) {
            case "v":
                countVote(senderId, splits[2]);
                break;
            case "abt":
                performAbort();
                break;
            case "pc": // PRECOMMIT
                timerParticipant.stop();
                if (!currentState.equals(STATE_COMMIT))
                    logWrite(STATE_PRECOMMIT);
                sendAcknowledge();
                break;
            case "ack":
                countAck(senderId);
                break;
            case "c":
                performCommit();
                break;
            case "add":
                currentCommand = message;
                voteAddCoordinator(splits[2], splits[3]);
                break;
            case "e":
                currentCommand = message;
                voteEditCoordinator(splits[2], splits[3], splits[4]);
                break;
            case "rm":
                currentCommand = message;
                voteRmCoordinator(splits[2]);
                break;
            case "rnc":
                rejectNextChange = true;
                //logWrite(PREFIX_COMMAND+"rnc");
                break;
            case "pl":
                broadcastMsgs("pll");
                printPlayList();
                break;
            case "pll":
                printPlayList();
                break;
            case "ir": // INITIAL_REQUEST
                unicastMsgs(senderId, "ia "+totalProcess);
                break;
            case "ia": // INITIAL_REQ_ACK
                int totalProcessNum = Integer.parseInt(splits[2]);
                initialRequest(totalProcessNum);
                break;
            case "ue": // UR_ELECTED
                if (leader != processId) {
                    oldLeader = leader;
                    leader = processId;
                    terminalLog("is elected");
                    unicastWithoutPartial(0, "nl"); // newLeader
                    termination();
                }
                break;
            case "sr": // STATE_REQ
                timerParticipant.stop();
                if (senderId != oldLeader) {
                    for (int i = 1; i < senderId; i++)
                        processAlive[i] = false;
                    oldLeader = leader;
                    leader = senderId;
                }
                unicastMsgs(senderId, "sa "+currentState);
                break;
            case "sa": // STATE_ACK
                countStateAck(senderId, splits[2]);
                break;
            case "rr": // RECOVER_REQ
                timerCoordinator.stop();
                int reqInstanceNum = Integer.parseInt(splits[2]);
                unicastMsgs(senderId, "ra "+reqInstanceNum+" "+readLog(reqInstanceNum));
                // Coordinator broadcast
                if (!currentState.equals(STATE_ABORT) &&
                        !currentState.equals(STATE_COMMIT) &&
                        !currentState.equals(STATE_START) && leader == processId) {
                    if (reqInstanceNum == instanceNum) {
                        if (splits[3].equals(STATE_ABORT))
                            performAbort();
                        else if (splits[3].equals(STATE_COMMIT))
                            performCommit();
                        else if (splits[3].equals(STATE_PRECOMMIT)) {
                            if (!currentState.equals(STATE_PRECOMMIT))
                                logWrite(STATE_PRECOMMIT);
                            waitAck();
                            broadcastMsgs("pc");
                        }
                    }
                }
                break;
            case "ra": // RECOVER_REQ_ANS
                stateReqList[senderId] = splits[3];
                if (!currentState.equals(STATE_ABORT) &&
                        !currentState.equals(STATE_COMMIT)) {
                    int raInstanceNum = Integer.parseInt(splits[2]);
                    if (raInstanceNum == instanceNum) {
                        if (splits[3].equals(STATE_ABORT))
                            performAbort();
                        else if (splits[3].equals(STATE_COMMIT))
                            performCommit();
                    }
                    if (currentState.equals(STATE_PRECOMMIT) || splits[3].equals(STATE_PRECOMMIT)) {
                        if (!timerCoordinator.isRunning()) {
                            if (!currentState.equals(STATE_PRECOMMIT))
                                logWrite(STATE_PRECOMMIT);
                            waitAck();
                            broadcastMsgs("pc");
                        }
                        break;
                    }
                    if (splits[3].equals(STATE_ABORT) || splits[3].equals(STATE_COMMIT))
                        break;
                    boolean anyCertain = false;
                    boolean allWakeup = true;
                    for (int i = 1; i <= totalProcess; i++) {
                        if (stateReqList[i].equals(STATE_ABORT) || stateReqList.equals(STATE_COMMIT)) {
                            anyCertain = true;
                            break;
                        }
                        if (stateReqList[i].equals(STATE_NOTANS)) {
                            allWakeup = false;
                            break;
                        }
                        if (!anyCertain && allWakeup) {
                            performAbort();
                            broadcastMsgs("abt");
                        }
                    }
                }
                break;
            case "pm": // partial message
                msgCounter = 0;
                countingMsg = true;
                msgAllowed = Integer.parseInt(splits[2]);
                break;
            case "ac": // allClear
                broadcastMsgs("acc");
            case "acc":
                timerCoordinator.stop();
                timerParticipant.stop();
                currentCommand = "";
                currentState = STATE_WAIT;
                break;
            default:
                terminalLog("cannot recognize this command: " + message);
                break;
        }
    }

    private String readLog(int reqInstanceNum) {
        DTLog = new File("../log/dt_" + processId + ".log");
        // Read recovery log and try to reconstruct the state
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(DTLog));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        String line;
        int logInstanceNum;
        String lastLineOther = "";
        try {
            while ((line = br.readLine()) != null) {
                if (line.startsWith(PREFIX_START) || line.startsWith(PREFIX_COMMAND)) {
                    if (line.startsWith(PREFIX_START))
                        logInstanceNum = Integer.parseInt(line.split(":")[1]);
                    else
                        logInstanceNum = Integer.parseInt(line.split(" ")[1]);
                    if (logInstanceNum == reqInstanceNum)
                        break;
                }
            }

            while ((line = br.readLine()) != null) {
                if (line.equals(STATE_ABORT) || line.equals(STATE_COMMIT))
                    return line;
                if (!line.startsWith(STATE_RECOVER)) lastLineOther = line;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (lastLineOther.startsWith(PREFIX_VOTE))
            lastLineOther = STATE_VOTED;
        if (lastLineOther.equals(""))
            lastLineOther = STATE_ABORT;
        return lastLineOther;
    }

    /**
     * Print local playlist to storage
     */
    private void printPlayList() {
        try {
            File playlistFile = new File(String.format("../log/playlist_%d.txt", processId));
            playlistFile.getParentFile().mkdirs();
            playlistFile.createNewFile();

            BufferedWriter out = new BufferedWriter(new FileWriter(String.format("../log/playlist_%d.txt", processId)));

            Iterator<Map.Entry<String, String>> it = playlist.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> pairs = it.next();
                out.write(pairs.getKey() + "\t==>\t" + pairs.getValue() + "\n");
            }

            out.close();
            terminalLog("outputs local playlist");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void logWrite(String str) {
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(DTLog, true)))) {
            pw.println(str);
            pw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void initialRequest(int totalProcessNum) {
        logWrite(PREFIX_PROCNUM + totalProcessNum);
        totalProcess = totalProcessNum;
        initializeArrays();
    }

    /**
     * Termination Protocol
     * Deal with ACK of STATE_REQ
     * @param senderId
     * @param state
     */
    public void countStateAck(int senderId, String state) {
        stateReqAckNum++;
        hasRespond[senderId] = true;
        stateReqList[senderId] = state;
        processAlive[senderId] = true;

        if (stateReqAckNum == totalProcess - 1) {
            timerCoordinator.stop();
            terminationProtocol();
        }
    }

    private void terminationProtocol() {
        stateReqList[processId] = currentState;

        // TR1
        for (int i = 1; i <= totalProcess; i++)
            if (stateReqList[i] == STATE_ABORT) {
                performAbort();
                for (int j = 1; j <= totalProcess; j++)
                    if (stateReqList[j] != STATE_ABORT)
                        unicastMsgs(j, "abt");
                return;
            }

        // TR2
        for (int i = 1; i <= totalProcess; i++)
            if (stateReqList[i] == STATE_COMMIT) {
                performCommit();
                for (int j = 1; j <= totalProcess; j++)
                    if (stateReqList[j] != STATE_COMMIT)
                        unicastMsgs(j, "c");
                return;
            }

        // TR4
        for (int i = 1; i <= totalProcess; i++)
            if (stateReqList[i] == STATE_PRECOMMIT) {
                broadcastMsgs("pc");
                return;
            }

        // TR3
        performAbort();
    }

    /**
     * Start termination protocol after electing new coordinator
     */
    private void termination() {
        timerParticipant.stop();
        stateReqAckNum = 0;
        Arrays.fill(hasRespond, false);
        Arrays.fill(stateReqList, STATE_NOTANS);
        Arrays.fill(processAlive, false);
        currentState = STATE_STREQ;
        broadcastMsgs("sr");
        timerCoordinator.start();
    }

    /**
     * Elect new coordinator
     */
    private void electCoordinator() {
        viewNumber = 1;
        while (!processAlive[viewNumber]) {
            viewNumber++;
            if (viewNumber > totalProcess) viewNumber = 1;
        }
        if (viewNumber == processId && leader != processId) {
            oldLeader = leader;
            leader = processId;
            terminalLog("elects itself");
            unicastWithoutPartial(0, "nl"); // newLeader
            termination();
        }
        else {
            currentState = STATE_SELETC;
            unicastMsgs(viewNumber, "ue");
            timerParticipant.start();
        }
    }

    /**
     * Wait participants to vote
     */
    public void waitVote() {
        timerCoordinator.start();
        voteNum = 0;
        decision = 1;
        currentState = STATE_WAITVOTE;
        Arrays.fill(hasRespond, false);
    }

    /**
     * Count the number of received VOTE
     * @param senderId
     * @param vote
     */
    public void countVote(int senderId, String vote) {
        voteNum++;
        hasRespond[senderId] = true;
        if (vote.equals("no")) {
            decision = 0;
            if (voteNum == totalProcess-1) {
                timerCoordinator.stop();
                performAbort();
            }
        }
        else {
            if (!voteStats[senderId]) {
                voteStats[senderId] = true;
                if (voteNum == totalProcess-1 && decision == 1) {
                    timerCoordinator.stop();
                    logWrite(STATE_PRECOMMIT);
                    waitAck();
                    broadcastMsgs("pc");
                }
            }
        }
    }

    /**
     * Wait participants to reply ACK
     */
    public void waitAck() {
        ackNum = 0;
        currentState = STATE_WAITACK;
        Arrays.fill(hasRespond, false);
        timerCoordinator.start();
    }

    /**
     * Count the number of received ACK
     * @param senderId
     */
    public void countAck(int senderId) {
        ackNum++;
        hasRespond[senderId] = true;
        if (!ackStats[senderId]) {
            if (ackNum == totalProcess-1) {
                timerCoordinator.stop();
                if (!currentState.equals(STATE_COMMIT))
                    logWrite(STATE_ACKED);
                broadcastMsgs("c");
                //currentCommand = "# " + currentCommand; // format the command
                performCommit();
            }
        }
    }

    /**
     * Perform COMMIT operation
     */
    public void performCommit() {
        timerCoordinator.stop();
        timerParticipant.stop();
        
        if (currentCommand.equals("")) return;
        if (isRecover)
            logWrite(STATE_RECOVER+":"+currentCommand);
        else
            logWrite(STATE_COMMIT);

        String[] splits = currentCommand.split(" ");
        int base = 3;
        if (splits[2].equals("vr")) base++;
        else if (!splits[1].equals("0") && processId == leader) base = 2;
        switch (splits[base-1]) {
            case "add":
                add(splits[base], splits[base+1]);
                break;
            case "e":
                edit(splits[base], splits[base+1], splits[base+2]);
                break;
            case "rm":
                remove(splits[base]);
                break;
            default:
                terminalLog("current command is " + currentCommand + ", splits["+(base-1)+"] is " + splits[base-1]);
                break;
        }

        currentState = STATE_COMMIT;
        currentCommand = "";
        voteNum = ackNum = 0;
        Arrays.fill(voteStats, false);
        Arrays.fill(ackStats, false);
    }

    /**
     * Perform ABORT operation
     */
    public void performAbort() {
        if (currentState.equals(STATE_ABORT))
            return;
        timerCoordinator.stop();
        timerParticipant.stop();
        if (currentState.equals(STATE_START) || currentState.equals(STATE_WAIT) || currentState.equals(STATE_VOTED)) {
            terminalLog("aborts the request");
        }
        else {
            if (currentState.equals(STATE_WAITVOTE)) {
                for (int i = 1; i <= totalProcess; i++)
                    if (i != processId && voteStats[i])
                        unicastMsgs(i, "abt");
            }
            else {
                broadcastMsgs("abt");
            }
            terminalLog("aborts the request");
        }
        logWrite(STATE_ABORT);
        currentState = STATE_ABORT;
        if (currentCommand.equals("")) instanceNum++;

        currentCommand = "";
        voteNum = ackNum = 0;
        Arrays.fill(voteStats, false);
        Arrays.fill(ackStats, false);
    }

    private void checkPartialMsg() {
        if (countingMsg) {
            this.msgCounter++;
            if (msgCounter == msgAllowed) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.exit(0);
            }
        }
    }

    /**
     * Update alive process list using "1,2,3"
     * @param newList
     */
    public void updateProcessList(String newList) {
        String[] newSplits = newList.split(",");
        Arrays.fill(processAlive, false);
        for (int i = 0; i < newSplits.length; i++)
            processAlive[Integer.parseInt(newSplits[i])] = true;
        //logWrite(PREFIX_ALIVEPROC+aliveProcessList());
    }

    /**
     * Respond to VOTE_REQ
     * @param songName
     * @param URL
     */
    public void voteAddCoordinator(String songName, String URL) {
        currentState = STATE_START;
        instanceNum ++;
        logWrite(PREFIX_START + instanceNum);
        logWrite(PREFIX_COMMAND + instanceNum + " " + currentCommand);
        if (playlist.containsKey(songName) || rejectNextChange) {
            performAbort();
        } else {
            currentState = STATE_WAITVOTE;
            broadcastMsgs(instanceNum + " vr add " + songName + " " + URL);
            waitVote();
        }
        rejectNextChange = false;
    }

    public void voteAddParticipant(String songName) {
        if (playlist.containsKey(songName) || rejectNextChange) {
            performAbort();
            if (canSendMsg) {
                unicastMsgs(leader, "v no");
            }
        } else {
            logWrite(PREFIX_VOTE + "YES");
            if (canSendMsg) {
                currentState = STATE_VOTED;
                timerParticipant.start();
                unicastMsgs(leader, "v yes");
            }
        }
        rejectNextChange = false;
    }

    /**
     * Add song to playlist
     * @param songName
     * @param URL
     */
    public void add(String songName, String URL) {
        playlist.put(songName, URL);
        terminalLog("add <" + songName + ", " + URL + ">");
    }

    /**
     * Respond to VOTE_REQ
     * @param songName
     */
    public void voteRmCoordinator(String songName) {
        currentState = STATE_START;
        logWrite(PREFIX_START + instanceNum);
        logWrite(PREFIX_COMMAND + instanceNum + " " + currentCommand);
        if (playlist.containsKey(songName) && !rejectNextChange) {
            currentState = STATE_WAITVOTE;
            broadcastMsgs(instanceNum+" vr rm " + songName);
            waitVote();
        } else {
            performAbort();
        }
        rejectNextChange = false;
    }

    public void voteRmParticipant(String songName) {
        if (playlist.containsKey(songName) && !rejectNextChange) {
            logWrite(PREFIX_VOTE + "YES");
            if (canSendMsg) {
                currentState = STATE_VOTED;
                timerParticipant.start();
                unicastMsgs(leader, "v yes");
            }
        } else {
            performAbort();
            if (canSendMsg)
                unicastMsgs(leader, "v no");
        }
        rejectNextChange = false;
    }

    /**
     * Remove song from playlist
     * @param songName
     */
    public void remove(String songName) {
        playlist.remove(songName);
        terminalLog("remove <" + songName + ">");
    }

    /**
     * Respond to VOTE_REQ
     * @param songName
     * @param URL
     */
    public void voteEditCoordinator(String songName, String newSongName, String URL) {
        currentState = STATE_START;
        logWrite(PREFIX_START + instanceNum);
        logWrite(PREFIX_COMMAND + instanceNum + " " + currentCommand);
        if (playlist.containsKey(songName) && !playlist.containsKey(newSongName) && !rejectNextChange) {
            currentState = STATE_WAITVOTE;
            broadcastMsgs(instanceNum+" vr e " + songName + " " + newSongName + " " + URL);
            waitVote();
        } else {
            performAbort();
        }
        rejectNextChange = false;
    }

    public void voteEditParticipant(String songName, String newSongName) {
        if (playlist.containsKey(songName) && !playlist.containsKey(newSongName) && !rejectNextChange) {
            logWrite(PREFIX_VOTE + "YES");
            if (canSendMsg) {
                currentState = STATE_VOTED;
                timerParticipant.start();
                unicastMsgs(leader, "v yes");
            }
        } else {
            performAbort();
            if (canSendMsg)
                unicastMsgs(leader, "v no");
        }
        rejectNextChange = false;
    }

    /**
     * Edit song in playlist
     * @param songName
     * @param newSongName
     * @param newSongURL
     */
    public void edit(String songName, String newSongName, String newSongURL) {
        remove(songName);
        add(newSongName, newSongURL);
        terminalLog("update <" + songName + "> to <" + newSongName + ", " + newSongURL + ">");
    }

    public void sendAcknowledge() {
        if (!currentState.equals(STATE_COMMIT))
            currentState = STATE_PRECOMMIT;
        timerParticipant.start();
        unicastMsgs(leader, "ack");
    }

    public void buildSocket() {
        Config config = null;
        try {
            config = new Config(processId, totalProcess, hostName, basePort);
        } catch (IOException e) {
            e.printStackTrace();
        }
        netController = new NetController(config);
    }

    /**
     * Unicast messages without check PartialMsgs
     * @param destId
     * @param instruction
     */
    private void unicastWithoutPartial(int destId, String instruction) {
        String msg = String.format("%d %s", processId, instruction);
        netController.sendMsg(destId, msg);
    }

    /**
     * Unicast to a partner
     * @param destId
     * @param instruction
     */
    private void unicastMsgs(int destId, String instruction) {
        String msg = String.format("%d %s", processId, instruction);
        if (canSendMsg) {
            netController.sendMsg(destId, msg);
            terminalLog("sends \"" + instruction + "\" to " + destId);
            checkPartialMsg();
        }
    }

    /**
     * Broadcast to available partners
     * @param instruction
     */
    private void broadcastMsgs(String instruction) {
        for (int i = 1; i <= totalProcess; i++)
            if (i != processId) {
                unicastMsgs(i, instruction);
                System.out.println(String.format("[%s#%d] asks #%d to respond to \"%s\"", processId==leader?"COORDINATOR":"PARTICIPANT", processId, i, instruction));
            }
    }

    /**
     * Receive messages
     * @param netController
     */
    private void getReceivedMsgs(final NetController netController) {
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    List<String> receivedMsgs = new ArrayList<String>(netController.getReceivedMsgs());
                    for (int i = 0; i < receivedMsgs.size(); i++) {
                        terminalLog(String.format("receive \"%s\"", receivedMsgs.get(i)));
                        processMessage(receivedMsgs.get(i));
                    }
                }
            }
        }).start();
    }

    private void terminalLog(String message) {
        System.out.println(String.format("[%s#%d] %s", processId == leader ? "COORDINATOR" : "PARTICIPANT", processId, message));
    }

    public static void main(String args[]) {
        int processId = Integer.parseInt(args[0]);
        if (args.length < 5) {
            System.err.println("[process "+processId+"] wrong parameters");
            System.exit(-1);
        }
        int    totalProcess = Integer.parseInt(args[1]);
        String hostName = args[2];
        int    basePort = Integer.parseInt(args[3]);
        int    leader   = Integer.parseInt(args[4]);
        int    reBuild  = Integer.parseInt(args[5]);

        Worker w = new Worker(processId, totalProcess, hostName, basePort, leader, reBuild);
        System.out.println("[process "+processId+"] started");
    }
}
