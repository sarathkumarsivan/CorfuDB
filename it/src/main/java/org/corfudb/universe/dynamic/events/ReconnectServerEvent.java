package org.corfudb.universe.dynamic.events;


import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.view.ClusterStatusReport;
import org.corfudb.universe.dynamic.state.State;
import org.corfudb.universe.node.server.CorfuServer;

/**
 * Reconnect a corfu server node.
 *
 * Created by edilmo on 11/06/18.
 */
@Slf4j
public class ReconnectServerEvent extends ServerEvent {

    /**
     * A short description of the action over a corfu server node.
     *
     * @return Short description of the action.
     */
    @Override
    protected String getActionDescription() {
        return "ReconnectServerEvent";
    }

    /**
     * Get the desire-state of the universe after this event happened.
     * This method is called before @executeRealPartialTransition.
     * The method must perform the updates directly over the @currentDesireState reference.
     *
     * @param currentDesireState Desire-state of the universe before this event happened.
     * @return Desire-state of the universe after this event happened.
     */
    @Override
    public void applyDesirePartialTransition(State currentDesireState) {
        currentDesireState.updateServerStatus(this.serverName, ClusterStatusReport.NodeStatus.UP);
    }

    /**
     * Execute the transition of the universe that materialize the occurrence of the event.
     * The method must perform the updates directly over the parameter currentRealState reference.
     *
     * @param currentRealState Real-state of the universe before this event happened.
     */
    @Override
    public void executeRealPartialTransition(State currentRealState) {
        this.corfuServer.reconnect();
    }

    public ReconnectServerEvent(String nodeName, CorfuServer corfuServer){
        super(nodeName, corfuServer);
    }
}
