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

package org.wisdom.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.wisdom.core.OrphanBlocksManager;
import org.wisdom.core.WisdomBlockChain;
import org.wisdom.db.StateDB;
import org.wisdom.encoding.BigEndian;
import org.wisdom.encoding.JSONEncodeDecoder;
import org.wisdom.core.Block;
import org.wisdom.core.account.Account;
import org.wisdom.core.account.Transaction;
import org.wisdom.core.incubator.Incubator;
import org.hibernate.validator.HibernateValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.validation.Validation;
import java.util.List;
import java.util.Map;

// 基本规则校验 校验区块版本号，字段类型, pow，交易 merkle root
@Component
public class BasicRule implements BlockRule, TransactionRule {

    @Autowired
    private WisdomBlockChain bc;

    @Autowired
    private StateDB stateDB;

    @Value("${wisdom.consensus.block-interval}")
    private int blockInterval;

    private Block genesis;
    private static javax.validation.Validator validator = Validation.byProvider(HibernateValidator.class)
            .configure()
            .failFast(true)
            .buildValidatorFactory().getValidator();
    private static final Logger logger = LoggerFactory.getLogger(BasicRule.class);
    private static final JSONEncodeDecoder codec = new JSONEncodeDecoder();

    @Value("${p2p.max-blocks-per-transfer}")
    private int orphanHeightsRange;

    @Override
    public Result validateBlock(Block block) {
        Block best = stateDB.getBestBlock();
        if (block == null) {
            return Result.Error("null block");
        }
        if (Math.abs(best.nHeight - block.nHeight) > orphanHeightsRange) {
            return Result.Error("the block height " + block.nHeight + " is too small or too large, current height is " + best.nHeight);
        }
        // 区块基本校验 字段值非空
        if (validator.validate(block).size() != 0) {
            return Result.Error(validator.validate(block).toArray()[0].toString());
        }
        // 区块时间戳必须在一个周期的时间内
        if (block.nTime - System.currentTimeMillis() / 1000 > blockInterval) {
            return Result.Error("the received block timestamp too large");
        }
        // 区块大小限制
        if (block.size() > Block.MAX_BLOCK_SIZE) {
            return Result.Error("block size exceed");
        }
        // 不可以接收创世区块
        if (block.nHeight == 0 || Arrays.areEqual(block.getHash(), genesis.getHash())) {
            return Result.Error("cannot write genesis block");
        }
        // 区块体不可以为空
        if (block.body == null || block.body.size() == 0) {
            return Result.Error("missing body");
        }
        // 区块版本
        if (block.nVersion != genesis.nVersion) {
            return Result.Error("version check fail");
        }
        // pow 校验
        if (BigEndian.compareUint256(Block.calculatePOWHash(block), block.nBits) >= 0) {
            return Result.Error("pow validate fail");
        }
        for (Transaction tx : block.body) {
            Result r = validateTransaction(tx);
            if (!r.isSuccess()) {
                return r;
            }
        }
        return Result.SUCCESS;
    }

    @Override
    public Result validateTransaction(Transaction transaction) {
        if (validator.validate(transaction).size() != 0 || transaction.version != Transaction.DEFAULT_TRANSACTION_VERSION) {
            return Result.Error(validator.validate(transaction).toArray()[0].toString() + "missing fields or version invalid");
        }
        // 1. deposit 事务的 amount 必须为 0
        if (transaction.type == Transaction.Type.DEPOSIT.ordinal() && transaction.amount != 0) {
            return Result.Error("the amount of deposit must be zero");
        }
        return Result.SUCCESS;
    }

    @Autowired
    public BasicRule(Block genesis, @Value("${node-character}") String character) {
        this.genesis = genesis;
    }
}
