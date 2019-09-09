package org.wisdom.p2p;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.wisdom.sync.SyncManager;
import org.wisdom.sync.TransactionHandler;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author sal 1564319846@qq.com
 * wisdom protocol implementation
 */
@Component
@ConditionalOnProperty(name = "p2p.mode", havingValue = "grpc")
public class PeerServer extends WisdomGrpc.WisdomImplBase {
    private static final int PEER_SCORE = 4;
    private static final int HALF_RATE = 60;
    private static final int EVIL_SCORE = -(1 << 10);
    private static final int MAX_PEERS = 32;
    private static final WisdomOuterClass.Ping PING = WisdomOuterClass.Ping.newBuilder().build();
    private static final WisdomOuterClass.Lookup LOOKUP = WisdomOuterClass.Lookup.newBuilder().build();
    private static final WisdomOuterClass.Nothing NOTHING = WisdomOuterClass.Nothing.newBuilder().build();
    private Server server;
    private static final Logger logger = LoggerFactory.getLogger(PeerServer.class);
    private static final int MAX_TTL = 8;
    private AtomicLong nonce;
    private Peer self;
    private List<Plugin> pluginList;
    private Set<HostPort> bootstraps;
    private Map<String, Peer> bootstrapPeers;
    private Map<String, Peer> trusted;
    private Map<String, Peer> blocked;
    private Map<Integer, Peer> peers;
    private Map<String, Peer> pended;
    private Map<String, ManagedChannel> chanBuffer;

    @Autowired
    private MessageFilter filter;

    @Autowired
    private PeersManager pmgr;

    @Autowired
    private MessageLogger messageLogger;

    @Autowired
    private SyncManager syncManager;

    @Autowired
    private TransactionHandler transactionHandler;

    @Value("${p2p.enable-discovery}")
    private boolean enableDiscovery;

    public PeerServer(
            @Value("${p2p.address}") String self,
            @Value("${p2p.bootstraps}") String bootstraps,
            @Value("${p2p.trustedpeers}") String trusted
    ) throws Exception {
        nonce = new AtomicLong();
        pluginList = new ArrayList<>();
        this.self = Peer.newPeer(self);
        this.bootstraps = new HashSet<>();
        this.trusted = new ConcurrentHashMap<>();
        this.blocked = new ConcurrentHashMap<>();
        this.peers = new ConcurrentHashMap<>();
        this.pended = new ConcurrentHashMap<>();
        this.chanBuffer = new ConcurrentHashMap<>();
        this.bootstrapPeers = new ConcurrentHashMap<>();
        String[] ts = new String[]{};
        if (trusted != null && !trusted.equals("")) {
            ts = trusted.split(",");
        }
        Optional.ofNullable(bootstraps)
                .map(x -> Arrays.asList(x.split(",")))
                .map(ps -> {
                    List<String> unparsed = new ArrayList<>();
                    ps.forEach(p -> {
                        try {
                            Peer peer = Peer.parse(p);
                            this.bootstrapPeers.put(peer.key(), peer);
                        } catch (Exception e) {
                            unparsed.add(p);
                        }
                    });
                    return unparsed;
                })
                .get()
                .forEach(link -> {
                    if (link == null || link.equals("")) {
                        return;
                    }
                    try {
                        URI u = new URI(link);
                        this.bootstraps.add(new HostPort(u.getHost(), u.getPort()));
                    } catch (Exception e) {
                        logger.error("invalid url");
                    }
                });


        for (String b : ts) {
            Peer p = Peer.parse(b);
            if (p.equals(this.self)) {
                throw new Exception("cannot treat yourself as trusted peer");
            }
            this.trusted.put(p.key(), p);
        }
    }

    public PeerServer use(Plugin plugin) {
        pluginList.add(plugin);
        return this;
    }

    /**
     * 加载插件，启动服务
     */
    @PostConstruct
    public void init() throws Exception {
        this.use(messageLogger)
                .use(filter)
                .use(syncManager)
                .use(transactionHandler)
                .use(pmgr);
        startListening();
    }

    public void startListening() throws Exception {
        logger.info("peer server is listening on " +
                Peer.PROTOCOL_NAME + "://" +
                Hex.encodeHexString(self.privateKey.getEncoded()) +
                Hex.encodeHexString(self.peerID) + "@" + self.hostPort());
        logger.info("provide address to your peers to connect " +
                Peer.PROTOCOL_NAME + "://" +
                Hex.encodeHexString(self.peerID) +
                "@" + self.hostPort());
        for (Plugin p : pluginList) {
            p.onStart(this);
        }
        server = ServerBuilder.forPort(self.port).addService(this).build().start();
    }

    @Scheduled(fixedRate = HALF_RATE * 1000)
    public void resolve() {
        bootstraps.forEach(h -> {
            dial(h.getHost(), h.getPort(), PING);
        });
    }

    @Scheduled(fixedRate = HALF_RATE * 1000)
    public void startHalf() {
        if (!enableDiscovery) {
            return;
        }
        boolean hasFull = peers.size() + trusted.size() >= MAX_PEERS;
        for (Peer p : pended.values()) {
            pended.remove(p.key());
            if (hasFull || hasPeer(p)) {
                continue;
            }
            dial(p, WisdomOuterClass.Ping.newBuilder().build());
        }
        for (Peer p : blocked.values()) {
            p.score /= 2;
            if (p.score == 0) {
                blocked.remove(p.key());
            }
        }
        for (Peer p : peers.values()) {
            p.score /= 2;
            if (p.score == 0) {
                removePeer(p);
            }
        }

        for (Peer p : getPeers()) {
            dial(p, PING); // keep alive
        }
        if (hasFull) {
            return;
        }
        // discover peers when bucket is not full
        Set<Peer> ps = new HashSet<>();
        ps.addAll(peers.values());
        ps.addAll(bootstrapPeers.values());
        for (Peer p : ps) {
            logger.info("peer found, address = " + p.toString() + " score = " + p.score);
            dial(p, LOOKUP);
        }
    }

    public Set<Peer> getBootstraps(){
        return new HashSet<>(bootstrapPeers.values());
    }

    public Peer getSelf() {
        return self;
    }

    private WisdomOuterClass.Message onMessage(WisdomOuterClass.Message message) {
        try {
            Payload payload = new Payload(message);
            Context ctx = new Context();
            ctx.payload = payload;
            for (Plugin p : pluginList) {
                p.onMessage(ctx, this);
                if (ctx.broken) {
                    break;
                }
            }
            if (ctx.remove) {
                removePeer(payload.getRemote());
            }
            if (ctx.pending) {
                pendPeer(payload.getRemote());
            }
            if (ctx.keep) {
                keepPeer(payload.getRemote());
            }
            if (ctx.block) {
                blockPeer(payload.getRemote());
            }
            if (ctx.relay) {
                relay(payload);
            }
            if (ctx.response != null) {
                return buildMessage(1, ctx.response);
            }
        } catch (Exception e) {
            logger.error("fail to parse message");
        }
        return buildMessage(1, NOTHING);
    }

    @Override
    public void entry(WisdomOuterClass.Message request, StreamObserver<WisdomOuterClass.Message> responseObserver) {
        WisdomOuterClass.Message resp = onMessage(request);
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    private void grpcCall(String host, int port, WisdomOuterClass.Message msg) {
        ManagedChannel ch = ManagedChannelBuilder.forAddress(host, port
        ).usePlaintext().build();
        WisdomGrpc.WisdomStub stub = WisdomGrpc.newStub(
                ch);
        stub.entry(msg, new StreamObserver<WisdomOuterClass.Message>() {
            @Override
            public void onNext(WisdomOuterClass.Message value) {
                onMessage(value);
            }

            @Override
            public void onError(Throwable t) {
                ch.shutdown();
            }

            @Override
            public void onCompleted() {
                ch.shutdown();
            }
        });
    }

    private void grpcCall(Peer peer, WisdomOuterClass.Message msg) {
        String key = peer.key();
        ManagedChannel ch = chanBuffer.get(key);
        if (ch == null) {
            ch = ManagedChannelBuilder.forAddress(peer.host, peer.port
            ).usePlaintext().build(); // without setting up any ssl
            chanBuffer.put(key, ch);
        }
        WisdomGrpc.WisdomStub stub = WisdomGrpc.newStub(
                ch);
        stub.entry(msg, new StreamObserver<WisdomOuterClass.Message>() {
            @Override
            public void onNext(WisdomOuterClass.Message value) {
                onMessage(value);
            }

            @Override
            public void onError(Throwable t) {
                int k = self.subTree(peer);
                Peer p = peers.get(k);
                if (p != null && p.equals(peer)) {
                    p.score /= 2;
                    if (p.score == 0) {
                        logger.error("cannot connect to peer " + peer.toString() + " remove it");
                        removePeer(p);
                    }
                }
            }

            @Override
            public void onCompleted() {
//                logger.info("send message " + msg.getCode().name() + " success content = " + msg.toString());
            }
        });
    }

    public void dial(String host, int port, Object msg) {
        grpcCall(host, port, buildMessage(MAX_TTL, msg));
    }

    public void dial(Peer p, Object msg) {
        grpcCall(p, buildMessage(1, msg));
    }

    public void broadcast(Object msg) {
        for (Peer p : getPeers()) {
            grpcCall(p, buildMessage(MAX_TTL, msg));
        }
    }

    public void relay(Payload payload) {
        if (payload.getTtl() <= 0) {
            return;
        }
        for (Peer p : getPeers()) {
            if (p.equals(payload.getRemote())) {
                continue;
            }
            try {
                grpcCall(p, buildMessage(payload.getTtl() - 1, payload.getBody()));
            } catch (Exception e) {
                logger.error("parse body fail");
            }
        }
    }

    public List<Peer> getPeers() {
        if (!enableDiscovery) {
            Set<Peer> res = new HashSet<>(bootstrapPeers.values());
            res.addAll(trusted.values());
            return Arrays.asList(res.toArray(new Peer[]{}));
        }
        List<Peer> ps = new ArrayList<>();
        ps.addAll(peers.values());
        ps.addAll(trusted.values());
        if (ps.size() == 0) {
            ps.addAll(bootstrapPeers.values());
        }
        return ps;
    }


    private void pendPeer(Peer peer) {
        String k = peer.key();
        if (peers.size() + trusted.size() >= MAX_PEERS) {
            return;
        }
        if (hasPeer(peer) || blocked.containsKey(k) || bootstrapPeers.containsKey(k)) {
            return;
        }
        pended.put(k, peer);
    }

    private void keepPeer(Peer peer) {
        String k = peer.key();
        if (trusted.containsKey(k) || blocked.containsKey(k)) {
            return;
        }
        peer.score = PEER_SCORE;
        HostPort hp = new HostPort(peer.host, peer.port);
        if (bootstraps.contains(hp)) {
            this.bootstrapPeers.put(peer.key(), peer);
            bootstraps.remove(hp);
        }
        int idx = self.subTree(peer);
        Peer p = peers.get(idx);
        if (p == null && peers.size() + trusted.size() < MAX_PEERS) {
            peers.put(idx, peer);
            return;
        }
        if (p == null) {
            return;
        }
        if (p.equals(peer)) {
            p.score += 2 * PEER_SCORE;
            return;
        }
        if (p.score < PEER_SCORE) {
            peers.put(idx, peer);
        }
    }


    private void blockPeer(Peer peer) {
        peer.score = EVIL_SCORE;
        removePeer(peer);
        blocked.put(peer.key(), peer);
    }

    private void removePeer(Peer peer) {
        int idx = self.subTree(peer);
        Peer p = peers.get(idx);
        if (p == null) {
            return;
        }
        if (p.equals(peer)) {
            peers.remove(idx);
        }
        String key = peer.key();
        ManagedChannel ch = chanBuffer.get(key);
        if (ch != null) {
            ch.shutdown();
        }
        chanBuffer.remove(key);
    }

    public boolean hasPeer(Peer peer) {
        String k = peer.key();
        if (trusted.containsKey(k)) {
            return true;
        }
        int idx = self.subTree(peer);
        return peers.containsKey(idx) && peers.get(idx).equals(peer);
    }

    private WisdomOuterClass.Message buildMessage(long ttl, Object msg) {
        WisdomOuterClass.Message.Builder builder = WisdomOuterClass.Message.newBuilder();
        builder.setCreatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build());
        builder.setRemotePeer(self.toString());
        builder.setTtl(ttl);
        builder.setNonce(nonce.getAndIncrement());
        if (msg instanceof WisdomOuterClass.Nothing) {
            builder.setCode(WisdomOuterClass.Code.NOTHING);
            return sign(builder.setBody(((WisdomOuterClass.Nothing) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Ping) {
            builder.setCode(WisdomOuterClass.Code.PING);
            return sign(builder.setBody(((WisdomOuterClass.Ping) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Pong) {
            builder.setCode(WisdomOuterClass.Code.PONG);
            return sign(builder.setBody(((WisdomOuterClass.Pong) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Lookup) {
            builder.setCode(WisdomOuterClass.Code.LOOK_UP);
            return sign(builder.setBody(((WisdomOuterClass.Lookup) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Peers) {
            builder.setCode(WisdomOuterClass.Code.PEERS);
            return sign(builder.setBody(((WisdomOuterClass.Peers) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.GetStatus) {
            builder.setCode(WisdomOuterClass.Code.GET_STATUS);
            return sign(builder.setBody(((WisdomOuterClass.GetStatus) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Status) {
            builder.setCode(WisdomOuterClass.Code.STATUS);
            return sign(builder.setBody(((WisdomOuterClass.Status) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.GetBlocks) {
            builder.setCode(WisdomOuterClass.Code.GET_BLOCKS);
            return sign(builder.setBody(((WisdomOuterClass.GetBlocks) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Blocks) {
            builder.setCode(WisdomOuterClass.Code.BLOCKS);
            return sign(builder.setBody(((WisdomOuterClass.Blocks) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Proposal) {
            builder.setCode(WisdomOuterClass.Code.PROPOSAL);
            return sign(builder.setBody(((WisdomOuterClass.Proposal) msg).toByteString())).build();
        }
        if (msg instanceof WisdomOuterClass.Transactions) {
            builder.setCode(WisdomOuterClass.Code.TRANSACTIONS);
            return sign(builder.setBody(((WisdomOuterClass.Transactions) msg).toByteString())).build();
        }
        logger.error("cannot deduce message type " + msg.getClass().toString());
        builder.setCode(WisdomOuterClass.Code.NOTHING).setBody(WisdomOuterClass.Nothing.newBuilder().build().toByteString());
        return sign(builder).build();
    }

    private WisdomOuterClass.Message.Builder sign(WisdomOuterClass.Message.Builder builder) {
        return builder.setSignature(
                ByteString.copyFrom(
                        self.privateKey.sign(Util.getRawForSign(builder.build()))
                )
        );
    }

    public String getNodePubKey() {
        return Peer.PROTOCOL_NAME + "://" +
                        Hex.encodeHexString(self.peerID) +
                        "@" + self.hostPort();
    }

    public String getIP(){
        InetAddress address = null;
        try {
            address = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return Objects.requireNonNull(address).getHostAddress();
    }

    public int getPort(){
        return self.port;
    }

}
