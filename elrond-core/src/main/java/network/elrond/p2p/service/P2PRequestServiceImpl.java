package network.elrond.p2p.service;

import net.tomp2p.dht.PeerDHT;
import net.tomp2p.futures.FutureDirect;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import network.elrond.core.ThreadUtil;
import network.elrond.p2p.DirectBaseFutureListener;
import network.elrond.p2p.model.P2PConnection;
import network.elrond.p2p.model.P2PRequestChannel;
import network.elrond.p2p.model.P2PRequestChannelName;
import network.elrond.p2p.model.P2PRequestMessage;
import network.elrond.service.AppServiceProvider;
import network.elrond.sharding.Shard;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public class P2PRequestServiceImpl implements P2PRequestService {

    private static final Logger logger = LogManager.getLogger(P2PRequestServiceImpl.class);

    @Override
    public P2PRequestChannel createChannel(P2PConnection connection, Shard shard, P2PRequestChannelName channelName) {
        logger.traceEntry("params: {} {}", connection, channelName);

        P2PRequestChannel channel = new P2PRequestChannel(channelName, connection);
        try {
            subscribeToChannel(connection, shard, channel);

            Peer peer = connection.getPeer();
            peer.objectDataReply(connection.registerChannel(channel));

            return logger.traceExit(channel);
        } catch (Exception e) {
            logger.catching(e);
        }

        return logger.traceExit((P2PRequestChannel) null);
    }

    private void subscribeToChannel(P2PConnection connection, Shard shard, P2PRequestChannel channel) throws Exception {
        Number160 hash = Number160.createHash(channel.getChannelIdentifier(shard));

        logger.debug("Added self to request channel {}", channel.getChannelIdentifier(shard));
    }

    private HashSet<PeerAddress> getPeersOnChannel(P2PRequestChannel channel, Shard shard) {
        logger.traceEntry("params: {} {}", channel, shard);
        P2PConnection connection = channel.getConnection();

        HashSet<PeerAddress> totalPeers = connection.getPeersOnShard(shard.getIndex());

        return totalPeers;
    }


    private <K extends Serializable, R extends Serializable> List<R> sendRequestMessage(P2PRequestChannel channel, Shard shard, P2PRequestMessage message) {
        P2PConnection connection = channel.getConnection();
        PeerDHT dht = connection.getDht();

        //get all peers on channel
        HashSet<PeerAddress> peersOnChannel = getPeersOnChannel(channel, shard);

        if (peersOnChannel.size() > 0) {
            List<R> responses = new ArrayList<>();
            // remove self from channel peer list
            PeerAddress self = connection.getPeer().peerAddress();
            peersOnChannel.remove(self);
            Peer peer = dht.peer();

            List<DirectBaseFutureListener> listOfFutureGets = new ArrayList<>();

            peersOnChannel.stream().parallel().forEach(peerAddress -> {
                FutureDirect futureDirect = peer
                        .sendDirect(peerAddress)
                        .object(message).start();

                DirectBaseFutureListener directBaseFutureListener = new DirectBaseFutureListener();

                futureDirect.addListener(directBaseFutureListener);

                synchronized (listOfFutureGets) {
                    listOfFutureGets.add(directBaseFutureListener);
                }
            });

            long maxWaitTimeToMonitorResponses = 1000;
            long startTimeStamp = System.currentTimeMillis();

            while (startTimeStamp + maxWaitTimeToMonitorResponses > System.currentTimeMillis()) {
                ThreadUtil.sleep(1);

                synchronized (listOfFutureGets) {
                    //not sent to all
                    if (listOfFutureGets.size() != peersOnChannel.size()) {
                        continue;
                    }
                    //got all responses, not waiting
                    boolean isDone = true;
                    for (DirectBaseFutureListener directBaseFutureListener : listOfFutureGets) {
                        if (directBaseFutureListener.getObject() == null) {
                            isDone = false;
                            break;
                        }
                    }

                    if (isDone) {
                        break;
                    }
                }
            }

            synchronized (listOfFutureGets) {
                for (DirectBaseFutureListener directBaseFutureListener : listOfFutureGets) {
                    if (directBaseFutureListener.getObject() != null) {
                        responses.add((R) directBaseFutureListener.getObject());
                    }
                }
            }

            logger.trace("sendRequestMessage: {}", responses.size());

            return responses;
        }

        return null;
    }

    @Override
    public <K extends Serializable, R extends Serializable> R get(P2PRequestChannel channel, Shard shard, P2PRequestChannelName channelName, K key) {
        logger.traceEntry("params: {} {} {} {}", channel, shard, channelName, key);

        List<R> responses = sendRequestMessage(channel, shard, new P2PRequestMessage(key, channelName, shard));
        List<R> results = new ArrayList<>();


        if (responses != null && !responses.isEmpty()) {
            if (P2PRequestChannelName.BLOCK_HEIGHT.equals(channelName)) {
                return Collections.max(responses, Comparator.comparing(o -> ((BigInteger) o)));
            } else {
                Map<R, String> objectToHash = responses.stream()
                        .filter(entry -> !(entry instanceof Collection<?>) || !((Collection<?>) entry).isEmpty())
                        .filter(Objects::nonNull)
                        .collect(
                                Collectors.toMap(
                                        response -> response,
                                        response -> AppServiceProvider.getSerializationService().getHashString(response),
                                        (response1, response2) -> response1));

                logger.trace("objectToHash: {}", objectToHash.toString());

                if (!objectToHash.isEmpty()) {
                    Map<String, Long> counts = objectToHash.entrySet()
                            .stream()
                            .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.counting()));

                    String element = Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();

                    results = objectToHash.entrySet()
                            .stream()
                            .filter(entry -> Objects.equals(entry.getValue(), element))
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toList());
                }

                R result = (results.size() > 0) ? results.get(0) : null;

                return logger.traceExit(result);
            }
        }
        return logger.traceExit((R) null);

    }
}
