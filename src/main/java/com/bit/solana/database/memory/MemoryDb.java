package com.bit.solana.database.memory;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import org.springframework.stereotype.Component;

@Component
public class MemoryDb implements DataBase {



    @Override
    public boolean createDatabase(DbConfig config) {
        Integer maxSize = config.getMaxSize();

        return true;
    }

    @Override
    public boolean closeDatabase() {
        return false;
    }

    @Override
    public boolean isExist(String table, byte[] key) {
        return false;
    }

    @Override
    public void insert(String table, byte[] key, byte[] value) {

    }

    @Override
    public void delete(String table, byte[] key) {

    }

    @Override
    public void update(String table, byte[] key, byte[] value) {

    }

    @Override
    public byte[] get(String table, byte[] key) {
        return new byte[0];
    }

    @Override
    public void batchInsert(String table, byte[][] keys, byte[][] values) {

    }

    @Override
    public void batchDelete(String table, byte[][] keys) {

    }

    @Override
    public void batchUpdate(String table, byte[][] keys, byte[][] values) {

    }

    @Override
    public byte[][] batchGet(String table, byte[][] keys) {
        return new byte[0][];
    }

    @Override
    public void close() {

    }

    @Override
    public void compact(byte[] start, byte[] limit) {

    }
}
