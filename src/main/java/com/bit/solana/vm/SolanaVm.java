package com.bit.solana.vm;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

import static com.bit.solana.util.ClassUtil.decompressClassBytes;


/**
 * 区块链虚拟机实现，基于Java类加载器
 */
@Slf4j
public class SolanaVm {

    // 自定义类加载器，用于加载区块链中的class字节码
    public static class BlockchainClassLoader extends ClassLoader {
        // 从字节数组加载类
        public Class<?> loadClassFromBytes(byte[] classData) {
            return defineClass(null, classData, 0, classData.length);
        }

        // 新增：从压缩字节数组加载类（内部先解压）
        public Class<?> loadClassFromCompressedBytes(byte[] compressedClassData) throws IOException {
            byte[] originalData = decompressClassBytes(compressedClassData); // 解压
            return loadClassFromBytes(originalData); // 加载原始字节码
        }
    }


    // 合约执行器工具类，封装反射调用细节
    public static class ContractExecutor {
        private final Class<?> contractClass; // 预加载的合约类
        private final Object contractInstance; // 合约实例（复用，避免重复创建）

        // 构造器：从类对象初始化
        public ContractExecutor(Class<?> contractClass) throws InstantiationException, IllegalAccessException {
            this.contractClass = contractClass;
            this.contractInstance = contractClass.newInstance(); // 假设无参构造
        }

        // 执行合约方法（简化版：自动推断参数类型）
        public Object execute(String methodName, Object... args) throws Exception {
            // 自动获取参数类型数组（根据实际传入的args）
            Class<?>[] parameterTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                parameterTypes[i] = args[i].getClass();
                // 处理基本类型包装类（如Integer -> int）
                if (parameterTypes[i] == Integer.class) parameterTypes[i] = int.class;
                if (parameterTypes[i] == Long.class) parameterTypes[i] = long.class;
                // 其他类型（如String、Boolean等）可按需补充
            }
            // 调用方法
            Method method = contractClass.getMethod(methodName, parameterTypes);
            return method.invoke(contractInstance, args);
        }
    }

}
