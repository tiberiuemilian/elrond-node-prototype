package network.elrond.consensus.handlers;

import network.elrond.application.AppState;
import network.elrond.chronology.SubRound;
import network.elrond.core.EventHandler;

public class NopHandler implements EventHandler<SubRound> {
    @Override
    public void onEvent(AppState state, SubRound data) {
        //NOP
    }
}
