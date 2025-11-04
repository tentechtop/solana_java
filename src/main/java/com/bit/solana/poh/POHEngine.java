package com.bit.solana.poh;

/**
 * POH 引擎：实现哈希链生成、空事件压缩、缓存同步，提供时序验证接口
 */
public class POHEngine {

    /**
     * 追加 POH 事件（空事件/非空事件）
     * @param eventData 事件数据：null = 空事件，非 null = 非空事件（如交易数据、合约事件）
     * @return POHRecord 事件记录（包含当前哈希链状态）
     */
    public POHRecord appendEvent(byte[] eventData) {

        return null;
    }

}
