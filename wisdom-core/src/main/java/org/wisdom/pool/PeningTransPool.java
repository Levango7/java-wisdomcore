package org.wisdom.pool;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.wisdom.core.WisdomBlockChain;
import org.wisdom.core.account.Transaction;
import org.wisdom.db.Leveldb;
import org.wisdom.keystore.crypto.RipemdUtility;
import org.wisdom.keystore.crypto.SHA3Utility;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PeningTransPool {

    private static final Logger logger = LoggerFactory.getLogger(PeningTransPool.class);

    @Autowired
    AdoptTransPool adoptTransPool;

    @Autowired
    WisdomBlockChain wisdomBlockChain;

    @Autowired
    private Leveldb leveldb;

    // publicKeyHash -> nonce -> transaction
    private Map<String, TreeMap<Long, TransPool>> ptpool;

    private Map<String, PendingNonce> ptnonce;

    public PeningTransPool() {
        ptpool = new ConcurrentHashMap<>();
        ptnonce = new ConcurrentHashMap<>();
        try {
            String dbdata = leveldb.readPoolDb("PendingPool");
            if (dbdata != null && !dbdata.equals("")) {
                List<TransPool> transPoolList = JSON.parseObject(dbdata, new TypeReference<ArrayList<TransPool>>() {
                });
                add(transPoolList);
            }
        } catch (Exception e) {
            ptpool = new ConcurrentHashMap<>();
            ptnonce = new ConcurrentHashMap<>();
        }
    }

    public void add(List<TransPool> pools) {
        for (TransPool transPool : pools) {
            Transaction transaction = transPool.getTransaction();
            byte[] from = transaction.from;
            String fromhash = Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(from)));
            if (ptpool.containsKey(fromhash)) {
                TreeMap<Long, TransPool> map = ptpool.get(fromhash);
                if(map.lastKey()>=transaction.nonce){
                    logger.info("PendingPool reject Nonce small transaction , tx="+Hex.encodeHexString(transaction.getHash()));
                    continue;
                }
                map.put(transaction.nonce, transPool);
                ptpool.put(fromhash, map);
                updateNonce(transaction.type, transaction.nonce, fromhash);
            } else {
                TreeMap<Long, TransPool> map = new TreeMap<>();
                map.put(transaction.nonce, transPool);
                ptpool.put(fromhash, map);
                updateNonce(transaction.type, transaction.nonce, fromhash);
            }
        }
    }

    public void updatePtNone(String key, PendingNonce pendingNonce) {
        if (ptnonce.containsKey(key)) {
            ptnonce.put(key, pendingNonce);
        }
    }

    public int size() {
        return getAll().size();
    }


    public List<TransPool> getAll() {
        List<TransPool> list = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Long, TransPool>> entry : ptpool.entrySet()) {
            TreeMap<Long, TransPool> map = entry.getValue();
            for (Map.Entry<Long, TransPool> entry1 : map.entrySet()) {
                list.add(entry1.getValue());
            }
        }
        return list;
    }

    public List<TransPool> getAllFrom(String from) {
        List<TransPool> list = new ArrayList<>();
        if (ptpool.containsKey(from)) {
            TreeMap<Long, TransPool> map = ptpool.get(from);
            for (Map.Entry<Long, TransPool> entry : map.entrySet()) {
                list.add(entry.getValue());
            }
        }
        return list;
    }

    public List<TransPool> getAllFromState(String from){
        List<TransPool> list = new ArrayList<>();
        if (ptpool.containsKey(from)) {
            TreeMap<Long, TransPool> map = ptpool.get(from);
            for (Map.Entry<Long, TransPool> entry : map.entrySet()) {
                if(entry.getValue().getState()!=2){
                    list.add(entry.getValue());
                }
            }
        }
        return list;
    }

    public TransPool getPoolTranHash(byte[] txhash) {
        for (Map.Entry<String, TreeMap<Long, TransPool>> entry : ptpool.entrySet()) {
            for (Map.Entry<Long, TransPool> entrys : entry.getValue().entrySet()) {
                Transaction t = entrys.getValue().getTransaction();
                if (Arrays.equals(t.getHash(), txhash)) {
                    return entrys.getValue();
                }
            }
        }
        return null;
    }

    public List<byte[]> getAllPubhash() throws DecoderException {
        List<byte[]> list = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Long, TransPool>> entry : ptpool.entrySet()) {
            TreeMap<Long, TransPool> map = entry.getValue();
            for (Map.Entry<Long, TransPool> entry1 : map.entrySet()) {
                TransPool transPool = entry1.getValue();
                if (transPool.getState() == 0) {
                    String key = entry.getKey();
                    byte[] pubkeyhash = Hex.decodeHex(key.toCharArray());
                    list.add(pubkeyhash);
                    break;
                }
            }
        }
        return list;
    }

    public Map<String, TreeMap<Long, TransPool>> getAllMap() {
        Map<String, TreeMap<Long, TransPool>> pendingmap = new HashMap<>();
        for (Map.Entry<String, TreeMap<Long, TransPool>> entry : ptpool.entrySet()) {
            Map<Long, TransPool> map = entry.getValue();
            TreeMap<Long, TransPool> treemap = new TreeMap<>();
            for (Map.Entry<Long, TransPool> entry1 : map.entrySet()) {
                TransPool transPool = entry1.getValue();
                if (transPool.getState() == 0) {
                    treemap.put(transPool.getTransaction().nonce, transPool);
                }
            }
            pendingmap.put(entry.getKey(), treemap);
        }
        return pendingmap;
    }

    public List<TransPool> getAllstate() {
        List<TransPool> list = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Long, TransPool>> entry : ptpool.entrySet()) {
            Map<Long, TransPool> map = entry.getValue();
            for (Map.Entry<Long, TransPool> entry1 : map.entrySet()) {
                TransPool transPool = entry1.getValue();
                if (transPool.getState() != 2) {
                    list.add(entry1.getValue());
                }
            }
        }
        return list;
    }

    public List<TransPool> getAllnostate() {
        List<TransPool> list = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Long, TransPool>> entry : ptpool.entrySet()) {
            Map<Long, TransPool> map = entry.getValue();
            for (Map.Entry<Long, TransPool> entry1 : map.entrySet()) {
                TransPool transPool = entry1.getValue();
                if (transPool.getState() == 0) {
                    list.add(entry1.getValue());
                }
            }
        }
        return list;
    }

    public void removeOne(String key, long nonce) {
        if (ptpool.containsKey(key)) {
            TreeMap<Long, TransPool> map = ptpool.get(key);
            if (map.containsKey(nonce)) {
                map.remove(nonce);
            }
            if(map.size()==0){
                ptpool.remove(key);
            }else{
                ptpool.put(key, map);
            }
        }
        if (ptnonce.containsKey(key)) {
            PendingNonce pendingNonce = ptnonce.get(key);
            if (pendingNonce.getNonce() == nonce) {
                pendingNonce.setNonce(0);
                pendingNonce.setState(2);
                ptnonce.put(key, pendingNonce);
            }
        }
//        String keys = key + nonce;
//        adoptTransPool.removeOne(key, keys);
    }

    public void remove(IdentityHashMap<String, Long> map) {
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            removeOne(entry.getKey(), entry.getValue());
        }
    }

    public void nonceupdate(String key, long nonce) {//进db,单nonce的事务pendingnonce修改为2
        if (ptnonce.containsKey(key)) {
            PendingNonce pendingNonce = ptnonce.get(key);
            if (pendingNonce.getNonce() == nonce) {
                pendingNonce.setState(2);
                ptnonce.put(key, pendingNonce);
            }
        }
    }

    public void updatePtNonce(List<String> listkey){
        listkey.stream().forEach(l->{
            if(ptnonce.containsKey(l)){
                PendingNonce pendingNonce = ptnonce.get(l);
                if(pendingNonce.getState()!=2){
                    pendingNonce.setState(2);
                    ptnonce.put(l, pendingNonce);
                }
            }
        });
    }

    public void updatePool(List<Transaction> txs, int type, long height) {
        for (Transaction t : txs) {
            String fromhash = Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(t.from)));
            if (ptpool.containsKey(fromhash)) {
                TreeMap<Long, TransPool> map = ptpool.get(fromhash);
                if (map.containsKey(t.nonce)) {
                    TransPool transPool = map.get(t.nonce);
                    transPool.setHeight(height);
                    transPool.setState(type);
                    map.put(t.nonce, transPool);
                    ptpool.put(fromhash, map);
                    if (type == 2) {//2 进db
                        if (t.type != 1 && t.type != 2 && t.type != 13) {//排除转账、投票、撤回投票
                            //ptnonce
                            nonceupdate(fromhash, t.nonce);
                        }
                    }
                }
            }
        }
    }

    public PendingNonce findptnonce(String key) {
        if (ptnonce.containsKey(key)) {
            return ptnonce.get(key);
        } else {
            return new PendingNonce(0, 2);
        }
    }

    public void updateNonce(int type, long nonce, String fromhash) {
        if (type == 1) {//转账
            ptnonce.put(fromhash, new PendingNonce(nonce, 2));
        } else {
            ptnonce.put(fromhash, new PendingNonce(nonce, 0));
        }
    }

    public Map<String, PendingNonce> getPtnonce(){
        Map<String, PendingNonce> maps=new HashMap<>();
        for(Map.Entry<String, PendingNonce> entry: ptnonce.entrySet()){
            PendingNonce pendingNonce=entry.getValue();
            if(pendingNonce.getState()==0){
                maps.put(entry.getKey(),entry.getValue());
            }
        }
        return maps;
    }
}

