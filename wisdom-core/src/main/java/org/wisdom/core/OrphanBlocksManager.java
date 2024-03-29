/*
 * Copyright (c) [2018]
 * This file is part of the java-wisdomcore
 *
 * The java-wisdomcore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-wisdomcore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-wisdomcore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.wisdom.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.wisdom.core.event.NewBlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.wisdom.db.StateDB;

import java.util.List;
import java.util.stream.Collectors;


/**
 * @author sal 1564319846@qq.com
 * manage orphan blocks
 */
@Component
public class OrphanBlocksManager implements ApplicationListener<NewBlockEvent> {
    private BlocksCacheWrapper orphans;

    @Autowired
    private StateDB stateDB;

    @Autowired
    private PendingBlocksManager pool;

    @Value("${p2p.max-blocks-per-transfer}")
    private int orphanHeightsRange;

    private static final Logger logger = LoggerFactory.getLogger(OrphanBlocksManager.class);


    public OrphanBlocksManager() {
        this.orphans = new BlocksCacheWrapper();
    }

    private boolean isOrphan(Block block) {
        return !stateDB.hasBlock(block.hashPrevBlock);
    }

    public void addBlock(Block block) {
        orphans.addBlock(block);
    }

    // remove orphans return writable blocks，过滤掉孤块
    public BlocksCache removeAndCacheOrphans(List<Block> blocks) {
        Block lastConfirmed = stateDB.getLastConfirmed();
        BlocksCache cache = new BlocksCache(blocks.stream()
                .filter(b -> b.nHeight > lastConfirmed.nHeight)
                .collect(Collectors.toList())
        );
        BlocksCache res = new BlocksCache();
        Block best = stateDB.getBestBlock();
        for (Block init : cache.getInitials()) {
            List<Block> descendantBlocks = cache.getDescendantBlocks(init);
            if (!isOrphan(init)) {
                res.addBlocks(descendantBlocks);
                continue;
            }
            for (Block b : descendantBlocks) {
                if (Math.abs(best.nHeight - b.nHeight) < orphanHeightsRange && !orphans.hasBlock(b.getHash())) {
                    logger.info("add block at height = " + b.nHeight + " to orphans pool");
                    addBlock(b);
                }
            }
        }
        return res;
    }

    public List<Block> getInitials() {
        return orphans.getInitials();
    }

    private void tryWriteNonOrphans() {
        for (Block ini : orphans.getInitials()) {
            if (!isOrphan(ini)) {
                logger.info("writable orphan block found in pool");
                List<Block> descendants = orphans.getDescendantBlocks(ini);
                orphans.deleteBlocks(descendants);
                pool.addPendingBlocks(new BlocksCache(descendants));
            }
        }
    }

    @Override
    public void onApplicationEvent(NewBlockEvent event) {
        tryWriteNonOrphans();
    }


    // 定时清理距离当前高度已经被确认的区块
    @Scheduled(fixedRate = 30 * 1000)
    public void clearOrphans() {
        Block lastConfirmed = stateDB.getLastConfirmed();
        orphans.getAll().stream()
                .filter(b -> b.nHeight <= lastConfirmed.nHeight || stateDB.hasBlock(b.getHash()))
                .forEach(b -> orphans.deleteBlock(b));
    }

    public List<Block> getOrphans(){
        return orphans.getAll();
    }
}
